import { spawn, type ChildProcess } from "child_process";
import * as fs from "fs";
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
 * Arguments handed to the build that follows a cancellation which left a module's compiled output
 * empty — the plugin's own advice, applied instead of left for the next scenario to trip over.
 *
 * `composePreviewDiscover` prints it when it happens ("its active class outputs contain 0 .class
 * files … rerun with --no-build-cache --rerun-tasks"), and by then the run is already lost: the
 * panel resolves to an empty state or waits out its budget for renders of classes that do not
 * exist. See {@link RealGradleApi.cancelRunTask}.
 */
export const CANCELLATION_REPAIR_ARGS: ReadonlyArray<string> = [
    "--rerun-tasks",
    "--no-build-cache",
];

/** One `./gradlew` invocation, as the timeout diagnostics read it back. */
export interface GradleInvocationRecord {
    /** Head task, as `gradleService` names it (`:samples:cmp:composePreviewRenderAll`). */
    readonly task: string;
    readonly args: ReadonlyArray<string>;
    readonly startedAt: number;
    endedAt?: number;
    status: "running" | "succeeded" | "failed" | "cancelled";
    /** `> Task :x:y OUTCOME` lines, in the order Gradle printed them. */
    readonly taskOutcomes: Array<{ task: string; outcome: string }>;
    /** Set when this invocation was given {@link CANCELLATION_REPAIR_ARGS}. */
    repaired?: boolean;
}

/**
 * The `> Task :path OUTCOME` lines in `chunk`.
 *
 * Gradle omits the outcome word for a task that actually ran, which is the single most useful
 * distinction a stuck-render diagnostic can draw — so it is spelled `EXECUTED` here rather than
 * left blank.
 */
export function parseTaskOutcomes(
    chunk: string,
): Array<{ task: string; outcome: string }> {
    const out: Array<{ task: string; outcome: string }> = [];
    for (const line of chunk.split("\n")) {
        const match = /^> Task (\S+)(?:\s+(\S[\S ]*?))?\s*$/.exec(line.trim());
        if (!match) continue;
        out.push({ task: match[1], outcome: match[2]?.trim() || "EXECUTED" });
    }
    return out;
}

/**
 * Module directory a task path belongs to, relative to the repo root —
 * `:samples:cmp:composePreviewRenderAll` → `samples/cmp`.
 *
 * Root-level tasks (`composePreviewApplied`) belong to no module and return `undefined`; there is
 * nothing module-scoped to check or repair for those.
 */
export function moduleDirForTask(taskName: string): string | undefined {
    if (!taskName.startsWith(":")) return undefined;
    const segments = taskName.slice(1).split(":");
    if (segments.length < 2) return undefined;
    return path.join(...segments.slice(0, -1));
}

/** Module directories owned by every task in a combined Gradle invocation. */
export function moduleDirsForInvocation(
    taskName: string,
    args: ReadonlyArray<string> = [],
): ReadonlyArray<string> {
    return [taskName, ...args].reduce<string[]>((modules, task) => {
        const moduleDir = moduleDirForTask(task);
        if (moduleDir && !modules.includes(moduleDir)) modules.push(moduleDir);
        return modules;
    }, []);
}

/** Directories a module's compiled classes can land in, relative to its project dir. */
const CLASS_OUTPUT_ROOTS = [
    path.join("build", "classes"),
    path.join("build", "tmp", "kotlin-classes"),
];

/**
 * Whether `moduleDir` has compiled output directories that contain no `.class` file at all.
 *
 * This is the plugin-side invariant restated on the harness side: a module that has been compiled
 * has class files, and a module whose output roots exist but are empty was compiled into nothing —
 * the shape a cancelled compile leaves behind, and the shape a build-cache entry stored from one
 * hands back for free on every later run. A module that has never been built has no output roots
 * and is not damaged, so it reports `false`.
 */
export function hasEmptyClassOutputs(
    repoRoot: string,
    moduleDir: string,
): boolean {
    const roots = CLASS_OUTPUT_ROOTS.map((relative) =>
        path.join(repoRoot, moduleDir, relative),
    ).filter((dir) => fs.existsSync(dir));
    if (roots.length === 0) return false;
    return !roots.some(containsClassFile);
}

/** First-hit search for a `.class` file — no need to count them to know the output is not empty. */
function containsClassFile(dir: string): boolean {
    let entries: fs.Dirent[];
    try {
        entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
        return false;
    }
    for (const entry of entries) {
        if (entry.isDirectory()) {
            if (containsClassFile(path.join(dir, entry.name))) return true;
        } else if (entry.name.endsWith(".class")) {
            return true;
        }
    }
    return false;
}

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

    /**
     * Every `./gradlew` this API has driven, oldest first.
     *
     * The point of keeping it is the failure this suite actually reports: a wait that times out
     * naming captures that never appeared. That message cannot tell "the render task ran and
     * produced nothing" apart from "the render task was never reached", and the two want opposite
     * investigations. {@link describeGradleActivity} turns the ledger into the line that does.
     */
    private readonly invocations: GradleInvocationRecord[] = [];

    /**
     * Modules whose last build was cancelled and whose compiled output has not been vetted since.
     *
     * A cancelled compile is how this suite manufactures the condition it then trips over: the
     * output directory is left empty, the next build compiles nothing into it, and Gradle stores
     * *that* as a cache entry — after which discovery finds 18 source files declaring `@Preview`
     * and 0 `.class` files, on this run and on every later run that hits the same entry.
     */
    private readonly unvettedModules = new Set<string>();

    /**
     * Cancellation repairs currently rebuilding a module's class output.
     *
     * Gradle 9.7.1 made the race this guards deterministic: terminating the wrapper client can
     * return before daemon-side output cleanup has completely settled. Starting the repair and a
     * save-triggered compile together then lets one invocation snapshot/cache the other's empty
     * output directory. Keep every later invocation for that module behind the one cache-bypassing
     * repair; unrelated modules remain free to build in parallel.
     */
    private readonly moduleRepairs = new Map<string, Promise<void>>();

    /** Runs waiting for a module repair, keyed so cancellation can stop them before they spawn. */
    private readonly queuedRuns = new Map<
        string,
        { taskName: string; cancelled: boolean }
    >();

    /** @see invocations */
    get gradleInvocations(): ReadonlyArray<GradleInvocationRecord> {
        return this.invocations;
    }

    /**
     * One line per recent `./gradlew`, for a timeout message to embed: what was asked for, how it
     * ended, and what the compose-preview tasks in it did.
     *
     * Deliberately compact and deliberately not filtered by module — when renders do not arrive,
     * the question is which of three states the pipeline is in (never ran / served from cache /
     * cancelled mid-flight), and that answer is a line, not a log tail.
     */
    describeGradleActivity(limit = 4): string {
        const recent = this.invocations.slice(-limit);
        if (recent.length === 0) return "gradle: <no invocations>";
        return recent
            .map((record) => {
                const seconds = Math.round(
                    ((record.endedAt ?? Date.now()) - record.startedAt) / 1000,
                );
                const interesting = record.taskOutcomes
                    .filter(({ task }) =>
                        /composePreview|[Cc]ompile/.test(task),
                    )
                    .map(({ task, outcome }) => `${task} ${outcome}`);
                const repaired = record.repaired
                    ? ` [repaired: ${CANCELLATION_REPAIR_ARGS.join(" ")}]`
                    : "";
                const detail =
                    interesting.length > 0
                        ? `\n      ${interesting.join("\n      ")}`
                        : " — no compile/render task reached";
                return `gradle ${record.task}: ${record.status} after ${seconds}s${repaired}${detail}`;
            })
            .join("\n    ");
    }

    /**
     * Extra arguments this invocation needs because a *previous* build for the same module was
     * cancelled and left its compiled output empty.
     *
     * Checked here, at the last moment before the build starts, rather than assumed at cancellation
     * time: a cancelled build usually damages nothing, and {@link CANCELLATION_REPAIR_ARGS} is
     * expensive enough (a full rerun with the cache bypassed) that paying it on every cancellation
     * would push a healthy scenario past its own budget. The check itself is a first-hit directory
     * walk. Either way the module stops being suspect — this is the build that either repairs it
     * or proves it was fine.
     */
    private repairModulesFor(
        projectFolder: string,
        moduleDirs: ReadonlyArray<string>,
    ): ReadonlyArray<string> {
        return moduleDirs.filter((moduleDir) => {
            if (!this.unvettedModules.has(moduleDir)) return false;
            if (!hasEmptyClassOutputs(projectFolder, moduleDir)) {
                this.unvettedModules.delete(moduleDir);
                return false;
            }
            this.onLog(
                `[realGradleApi] ${moduleDir} has compiled output directories with no .class files ` +
                    `after a cancelled build — rerunning with ${CANCELLATION_REPAIR_ARGS.join(" ")}`,
            );
            return true;
        });
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
        const moduleDirs = moduleDirsForInvocation(opts.taskName, opts.args);
        const repairsInFlight = [
            ...new Set(
                moduleDirs
                    .map((moduleDir) => this.moduleRepairs.get(moduleDir))
                    .filter((repair): repair is Promise<void> => !!repair),
            ),
        ];
        if (repairsInFlight.length > 0) {
            this.onLog(
                `[realGradleApi] cancellation repair is still running for ${moduleDirs.join(", ")} — queueing ${opts.taskName}`,
            );
            const queued = { taskName: opts.taskName, cancelled: false };
            if (opts.cancellationKey) {
                this.queuedRuns.set(opts.cancellationKey, queued);
            }
            const forgetQueued = () => {
                if (
                    opts.cancellationKey &&
                    this.queuedRuns.get(opts.cancellationKey) === queued
                ) {
                    this.queuedRuns.delete(opts.cancellationKey);
                }
            };
            const afterRepairs = () => {
                if (queued.cancelled) {
                    throw new Error(
                        `gradlew ${opts.taskName} cancelled before start`,
                    );
                }
                // From this point cancellation must target the child registered by the recursive
                // run. Keeping the queued marker until that child exits makes cancelRunTask return
                // after marking an already-started queue entry and leaves the Gradle process alive.
                forgetQueued();
                return this.runTask(opts);
            };
            return Promise.all(repairsInFlight)
                .then(afterRepairs, (error) => {
                    if (queued.cancelled) return afterRepairs();
                    throw error;
                })
                .finally(forgetQueued);
        }
        const gradlewPath =
            process.platform === "win32"
                ? path.join(this.gradlewDir, "gradlew.bat")
                : path.join(this.gradlewDir, "gradlew");
        const repairModules = this.repairModulesFor(
            opts.projectFolder,
            moduleDirs,
        );
        const repairArgs =
            repairModules.length > 0 ? CANCELLATION_REPAIR_ARGS : [];
        // A daemon bootstrap and several other module-owned tasks do not compile consumer sources.
        // A repair invocation must include a task that can actually recreate the missing classes,
        // otherwise the postcondition below turns a successful bootstrap into a permanent failure.
        const requestedTasks = [opts.taskName, ...(opts.args ?? [])];
        const repairCompileTasks = repairModules
            .map(
                (moduleDir) =>
                    `:${moduleDir.split(path.sep).join(":")}:composePreviewDiscover`,
            )
            .filter((task) => !requestedTasks.includes(task));
        const gradleArgs = [
            opts.taskName,
            ...(opts.args ?? []),
            ...repairCompileTasks,
            ...this.extraArgs,
            ...repairArgs,
        ];
        this.onLog(
            `[realGradleApi] ${gradlewPath} ${gradleArgs.join(" ")} (cwd=${opts.projectFolder})`,
        );
        const record: GradleInvocationRecord = {
            task: opts.taskName,
            args: gradleArgs,
            startedAt: Date.now(),
            status: "running",
            taskOutcomes: [],
            repaired: repairArgs.length > 0 || undefined,
        };
        this.invocations.push(record);
        // Gradle's task lines can straddle a chunk boundary, so parse from a carry-over buffer
        // rather than per chunk — a `> Task … FROM-CACHE` split down the middle is exactly the
        // line a stuck-render diagnostic needs.
        let pending = "";

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
        const invocation = new Promise<void>((resolve, reject) => {
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
                pending += chunk.toString("utf-8");
                const lastBreak = pending.lastIndexOf("\n");
                if (lastBreak >= 0) {
                    record.taskOutcomes.push(
                        ...parseTaskOutcomes(pending.slice(0, lastBreak)),
                    );
                    pending = pending.slice(lastBreak + 1);
                }
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
                record.endedAt = Date.now();
                record.status = "failed";
                reject(err);
            });
            child.once("close", (code, signal) => {
                forget();
                record.taskOutcomes.push(...parseTaskOutcomes(pending));
                pending = "";
                record.endedAt = Date.now();
                record.status =
                    code === 0 ? "succeeded" : signal ? "cancelled" : "failed";
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
        if (repairModules.length === 0) return invocation;

        const repair = invocation.then(() => {
            for (const moduleDir of repairModules) {
                if (hasEmptyClassOutputs(opts.projectFolder, moduleDir)) {
                    throw new Error(
                        `gradlew ${gradleArgs.join(" ")} completed but ${moduleDir} still has no .class files`,
                    );
                }
                this.unvettedModules.delete(moduleDir);
            }
        });
        for (const moduleDir of repairModules) {
            this.moduleRepairs.set(moduleDir, repair);
        }
        return repair.finally(() => {
            for (const moduleDir of repairModules) {
                if (this.moduleRepairs.get(moduleDir) === repair) {
                    this.moduleRepairs.delete(moduleDir);
                }
            }
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
        const queued = key ? this.queuedRuns.get(key) : undefined;
        if (queued) {
            if (!queued.cancelled) {
                queued.cancelled = true;
                this.cancelledTaskNames.push(queued.taskName);
                this.onLog(
                    `[realGradleApi] cancel ${queued.taskName} (key=${key}) — dropping queued run`,
                );
            }
            return;
        }
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
            // A build killed mid-compile can leave the module's class output directory empty, and
            // the *next* build then compiles nothing into it and caches that. Remember the module
            // so the next build for it is vetted before it starts — see {@link repairArgsFor}.
            const moduleDir = moduleDirForTask(opts.taskName);
            if (moduleDir) this.unvettedModules.add(moduleDir);
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
