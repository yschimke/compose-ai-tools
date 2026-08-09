import { spawn, type ChildProcess } from "child_process";
import * as path from "path";
import type { GradleApi } from "../../gradleService";

/**
 * Grace period between SIGTERM and SIGKILL when cancelling a hung gradlew.
 * SIGTERM lets the Gradle client disconnect cleanly (which signals its
 * daemon to cancel the in-flight build and release the build lock); the
 * SIGKILL fallback covers a wedged JVM that ignores the term.
 */
const KILL_GRACE_MS = 5_000;

/**
 * Real {@link GradleApi} that shells out to the repo's `./gradlew` wrapper.
 * Used by the daily/manual e2e suite to drive the *actual* Gradle plugin
 * end-to-end, instead of the recording stub the fast suite uses.
 *
 * Stays out of `src/main/` because it pulls `child_process` and resolves
 * paths assuming the test layout — production extension code reaches
 * Gradle through the official `vscjava.vscode-gradle` API, never directly.
 */
export class RealGradleApi implements GradleApi {
    /**
     * @param gradlewDir Absolute path to the directory containing the
     *                   `gradlew` script (the repo root).
     * @param onLog Optional sink for human-readable progress lines.
     * @param extraArgs Extra CLI arguments appended to every `gradlew`
     *                  invocation. Used by suites that need to set Gradle
     *                  properties (`-Pfoo=bar`) without plumbing through
     *                  `gradleService.ts`.
     */
    constructor(
        private readonly gradlewDir: string,
        private readonly onLog: (line: string) => void = () => {},
        private readonly extraArgs: ReadonlyArray<string> = [],
    ) {}

    /**
     * Live gradlew child processes keyed by `cancellationKey`. Tracked so
     * {@link cancelRunTask} can actually terminate a hung/superseded build
     * — the original no-op left orphaned gradlew clients running, holding
     * the project's Gradle build lock. Across a long serial e2e session
     * (multiple render + daemon suites) those orphans accumulate and a
     * later module render blocks forever on the lock, surfacing only as a
     * 20-minute mocha hook timeout with no Gradle output. See
     * `e2eCachedPreloadOnSwitch.test.ts`.
     */
    private readonly liveChildren = new Map<string, ChildProcess>();

    /**
     * Task names this API actually terminated, in order. Populated by
     * {@link cancelRunTask} only when a live child was killed — a cancel
     * against an already-exited task is a no-op and is not recorded.
     *
     * Exposed because a cancellation is invisible to the tests otherwise.
     * When `gradleService`'s task cap kills a render mid-flight, the
     * extension does the production thing — `renderWithDiskFallback` paints
     * whatever manifest the truncated render left behind — so a suite that
     * asserts only "≥N previews arrived" passes on a render that never
     * finished. That is exactly how the 2026-08-08 cold-runner slowdown
     * stayed hidden in the `cmp-smoke` shard after the task cap was raised:
     * green, but on the fallback path. Suites where no cancellation is
     * expected can read this and say so.
     */
    private readonly cancelledTaskNames: string[] = [];

    /** @see cancelledTaskNames */
    get cancelledTasks(): ReadonlyArray<string> {
        return this.cancelledTaskNames;
    }

    runTask(opts: {
        projectFolder: string;
        taskName: string;
        args?: ReadonlyArray<string>;
        showOutputColors: boolean;
        onOutput?: (output: {
            getOutputBytes(): Uint8Array;
            getOutputType(): number;
        }) => void;
        cancellationKey?: string;
    }): Promise<void> {
        const gradlewPath =
            process.platform === "win32"
                ? path.join(this.gradlewDir, "gradlew.bat")
                : path.join(this.gradlewDir, "gradlew");
        const gradleArgs = [
            opts.taskName,
            ...(opts.args ?? []),
            ...this.extraArgs,
        ];
        this.onLog(
            `[realGradleApi] ${gradlewPath} ${gradleArgs.join(" ")} (cwd=${opts.projectFolder})`,
        );

        // Under e2e-external the underlying gradleService routes Gradle output
        // to the extension's `outputChannel` (via `logger.append`), which
        // doesn't surface in CI stdout. That makes a silent `composePreviewApplied`
        // failure or a missing-marker fan-out invisible — the test fails with
        // "resolveModule returned null" and no Gradle context. Forward stderr
        // verbatim and stdout selectively (build status + task names + errors)
        // to console.log so the CI log has enough to triage. Cheap: this is a
        // test-only realGradleApi, not the production GradleApi path.
        const diagE2e = process.env.COMPOSE_PREVIEW_E2E_EXTERNAL === "1";
        const stdoutDiagRe =
            /BUILD\s+(SUCCESSFUL|FAILED)|composePreviewApplied|FAILURE|^Configuring |Could not resolve|Exception/m;
        return new Promise((resolve, reject) => {
            const child = spawn(gradlewPath, gradleArgs, {
                cwd: opts.projectFolder,
                env: { ...process.env },
                stdio: ["ignore", "pipe", "pipe"],
            });
            // Register under the cancellation key so a later
            // `cancelRunTask` (fired by gradleService's 5-minute task
            // timeout or a refresh supersession) can terminate this exact
            // process instead of leaving it orphaned on the build lock.
            if (opts.cancellationKey) {
                this.liveChildren.set(opts.cancellationKey, child);
            }
            const forget = () => {
                if (opts.cancellationKey) {
                    // Only drop the entry if it still points at *this* child;
                    // a re-run under the same key would have replaced it.
                    if (this.liveChildren.get(opts.cancellationKey) === child) {
                        this.liveChildren.delete(opts.cancellationKey);
                    }
                }
            };
            child.stdout.on("data", (chunk: Buffer) => {
                if (diagE2e) {
                    const text = chunk.toString("utf-8");
                    for (const line of text.split("\n")) {
                        if (stdoutDiagRe.test(line))
                            console.log(`[gradle stdout] ${line}`);
                    }
                }
                opts.onOutput?.({
                    getOutputBytes: () => new Uint8Array(chunk),
                    // 0 = stdout, matches the bytes-shaped contract the
                    // production GradleApi consumer (gradleService.ts) uses.
                    getOutputType: () => 0,
                });
            });
            child.stderr.on("data", (chunk: Buffer) => {
                if (diagE2e) {
                    process.stderr.write(`[gradle stderr] ${chunk}`);
                }
                opts.onOutput?.({
                    getOutputBytes: () => new Uint8Array(chunk),
                    getOutputType: () => 1,
                });
            });
            child.once("error", (err) => {
                forget();
                reject(err);
            });
            child.once("close", (code, signal) => {
                forget();
                if (diagE2e) {
                    console.log(
                        `[gradle exit] code=${code} signal=${signal ?? "none"} args=${gradleArgs.join(" ")}`,
                    );
                }
                if (code === 0) {
                    resolve();
                } else {
                    // A cancelled build exits via signal (SIGTERM/SIGKILL)
                    // with a null code. Surface it as a distinct, matchable
                    // message so callers can tell "I cancelled this" apart
                    // from a genuine build failure.
                    reject(
                        new Error(
                            signal
                                ? `gradlew ${gradleArgs.join(" ")} cancelled (signal ${signal})`
                                : `gradlew ${gradleArgs.join(" ")} exited with ${code}`,
                        ),
                    );
                }
            });
        });
    }

    async cancelRunTask(opts: {
        projectFolder: string;
        taskName: string;
        cancellationKey?: string;
    }): Promise<void> {
        // Match the production `vscode-gradle` contract: cancellation is
        // keyed by `cancellationKey`. Without a key there's nothing
        // specific to cancel (gradleService always supplies one).
        const key = opts.cancellationKey;
        const child = key ? this.liveChildren.get(key) : undefined;
        if (!child || child.pid === undefined || child.exitCode !== null) {
            return;
        }
        // SIGTERM first so the Gradle client disconnects gracefully and its
        // daemon cancels the build (releasing the build lock); escalate to
        // SIGKILL if the process is still alive after the grace period.
        //
        // `kill` returns false (or throws) when the signal couldn't be
        // delivered because the process is already gone — which is a real
        // race here, since the interesting case is a build finishing right
        // as the task cap fires. Record and log only when the signal
        // actually went out, so `cancelledTasks` can't attribute a
        // truncation to a render that completed on its own.
        let signalSent = false;
        try {
            signalSent = child.kill("SIGTERM");
        } catch {
            signalSent = false; /* already gone */
        }
        if (signalSent) {
            this.cancelledTaskNames.push(opts.taskName);
            this.onLog(
                `[realGradleApi] cancel ${opts.taskName} (key=${key}) — terminating gradlew pid ${child.pid}`,
            );
        }
        const killTimer = setTimeout(() => {
            if (child.exitCode === null && child.signalCode === null) {
                try {
                    child.kill("SIGKILL");
                } catch {
                    /* already gone */
                }
            }
        }, KILL_GRACE_MS);
        // Don't keep the test host's event loop alive on the grace timer.
        killTimer.unref?.();
        // Resolve once the child has actually exited so callers serialise
        // their retry *after* the build lock is released, not before.
        await new Promise<void>((resolve) => {
            if (child.exitCode !== null || child.signalCode !== null) {
                clearTimeout(killTimer);
                resolve();
                return;
            }
            child.once("close", () => {
                clearTimeout(killTimer);
                resolve();
            });
        });
    }
}
