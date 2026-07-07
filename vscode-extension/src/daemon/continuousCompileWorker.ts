import { ChildProcess, spawn } from "child_process";
import { EventEmitter } from "events";
import * as path from "path";
import { ChunkLineSplitter } from "./daemonProcess";
import { KotlinCompileErrorDetector } from "../kotlinCompileErrorDetector";
import { CompileError } from "../compileErrors";

/**
 * Long-running `gradle --continuous :<module>:composePreviewCompile` worker.
 *
 * Spike behind `composePreview.daemon.continuousCompile`.
 * Removes the per-save Gradle configuration + Tooling-API round-trip from
 * the daemon save loop: a single Gradle invocation stays resident,
 * re-running `composePreviewCompile` whenever Gradle's continuous-mode
 * file watcher detects a source change.
 *
 * Production extension code normally reaches Gradle through the official
 * `vscjava.vscode-gradle` API (see `src/test/electron/realGradleApi.ts`
 * comment). This worker is the explicit exception — the vscode-gradle
 * gRPC layer doesn't surface clean EOF-to-stdin shutdown for continuous
 * mode, and cancellation semantics for a build that's meant to never
 * resolve are awkward. We own the subprocess directly here, gated behind
 * an experimental config flag so the regular code path is unchanged when
 * the flag is off.
 *
 * State machine, driven by Gradle's `--console=plain` output:
 *
 *   idle ──"Change detected, executing build…"──▶ running
 *   running ──"BUILD SUCCESSFUL in N(ms|s)" / "BUILD FAILED…"──▶ idle
 *
 * The very first build that runs at worker start (the warm-up, usually
 * `UP-TO-DATE`) is intentionally NOT flagged as a `buildStarted` event —
 * only `Change detected` triggers count. That way `waitForNextBuild()`
 * resolves only on a build that was kicked off by an actual source change
 * after the caller's `now()`, not on the warm-up.
 */

export interface BuildOutcome {
    ok: boolean;
    /** Wall-clock ms when the BUILD SUCCESSFUL/FAILED line was observed. */
    finishedAtMs: number;
    /** Reported by Gradle (`"in 480ms"` → 480, `"in 1.2s"` → 1200). null if
     *  the trailer didn't parse — keep the outcome but treat duration as
     *  unknown for metrics. */
    durationMs: number | null;
    /** Empty when ok=true. Populated from KotlinCompileErrorDetector on failure. */
    errors: CompileError[];
}

export interface ContinuousCompileWorkerLogger {
    appendLine(line: string): void;
}

export interface ContinuousCompileWorkerOptions {
    /** Repo root — the directory containing `gradlew`. */
    workspaceRoot: string;
    /** Fully-qualified gradle task path, e.g. `:samples:android:composePreviewCompile`. */
    taskPath: string;
    /** Extra args appended to the invocation (project properties etc.). */
    extraArgs?: ReadonlyArray<string>;
    logger?: ContinuousCompileWorkerLogger;
    /** Injection point for tests. Defaults to node's `child_process.spawn`. */
    spawnFn?: typeof spawn;
    /** Injection point for tests. Defaults to `Date.now`. */
    nowFn?: () => number;
    /** Default 60_000 ms. */
    buildTimeoutMs?: number;
}

/**
 * Matches Gradle's BUILD outcome trailer. Groups: 1=SUCCESSFUL|FAILED, 2=ms, 3=seconds.
 * Examples we've observed across Gradle 8/9:
 *   "BUILD SUCCESSFUL in 480ms"
 *   "BUILD SUCCESSFUL in 1s"
 *   "BUILD SUCCESSFUL in 1.4s"
 *   "BUILD FAILED in 2s"
 */
const BUILD_OUTCOME_RE =
    /^BUILD (SUCCESSFUL|FAILED) in (?:(\d+)\s*ms|(\d+(?:\.\d+)?)\s*s)\b/;
const CHANGE_DETECTED_RE = /^Change detected, executing build/;

/**
 * If a build is already in flight when `waitForNextBuild()` is called and its
 * `Change detected` line landed within this many ms before the call, we
 * accept that build as the caller's — Gradle's watcher commonly notices a
 * file save before VS Code's event loop dispatches the next microtask, so
 * the in-flight build IS the one triggered by the caller's save. Beyond
 * this window the build is treated as stale: we wait for the next one
 * instead, even though it costs an extra round-trip. Tuned higher than
 * Gradle's quiet-period debounce (~250 ms) and below a "rapid resave"
 * interval; a stale build that races a long-running cold compile would
 * otherwise mis-attribute its outcome to a later save and produce a render
 * that lags the source.
 */
const IN_FLIGHT_ACCEPT_WINDOW_MS = 500;

export class ContinuousCompileWorker {
    private child: ChildProcess | null = null;
    private readonly stdoutSplitter = new ChunkLineSplitter();
    private readonly stderrSplitter = new ChunkLineSplitter();
    private errorDetector = new KotlinCompileErrorDetector();
    private readonly events = new EventEmitter();
    /** null while idle; timestamp ms set on "Change detected", cleared on BUILD outcome. */
    private buildStartedAtMs: number | null = null;
    private stopped = false;
    private exitPromise: Promise<number | null> = Promise.resolve(null);
    private readonly logger: ContinuousCompileWorkerLogger;
    private readonly now: () => number;
    private readonly defaultTimeoutMs: number;

    constructor(private readonly opts: ContinuousCompileWorkerOptions) {
        this.logger = opts.logger ?? { appendLine() {} };
        this.now = opts.nowFn ?? Date.now;
        this.defaultTimeoutMs = opts.buildTimeoutMs ?? 60_000;
    }

    /** True between `start()` returning and `stop()` (or the subprocess exiting). */
    get running(): boolean {
        return this.child !== null && !this.stopped;
    }

    /**
     * Fires once when the underlying subprocess exits (either spawn error
     * or normal `exit`), regardless of whether the exit was caller-driven
     * via [stop] or a crash. Lets the manager clear stale entries from
     * its lookup map so a subsequent `ensureWorker` call can respawn.
     */
    onceExit(listener: () => void): void {
        this.events.once("exit", listener);
    }

    /** Spawn the long-running gradle process. Resolves once the child has been spawned;
     *  does NOT wait for the warm-up build to finish. */
    start(): void {
        if (this.child) {
            throw new Error("ContinuousCompileWorker.start() called twice");
        }
        const gradlewPath = path.join(
            this.opts.workspaceRoot,
            process.platform === "win32" ? "gradlew.bat" : "gradlew",
        );
        const args = [
            this.opts.taskPath,
            "--continuous",
            "--console=plain",
            ...(this.opts.extraArgs ?? []),
        ];
        this.logger.appendLine(
            `[continuous] spawn ${gradlewPath} ${args.join(" ")} (cwd=${this.opts.workspaceRoot})`,
        );
        const spawnFn = this.opts.spawnFn ?? spawn;
        const child = spawnFn(gradlewPath, args, {
            cwd: this.opts.workspaceRoot,
            env: { ...process.env },
            stdio: ["pipe", "pipe", "pipe"],
        });
        if (!child.stdout || !child.stderr) {
            throw new Error(
                "ContinuousCompileWorker: spawn produced no stdio streams",
            );
        }
        this.child = child;
        child.stdout.on("data", (chunk: Buffer) =>
            this.consumeStdout(chunk.toString("utf-8")),
        );
        child.stderr.on("data", (chunk: Buffer) =>
            this.consumeStderr(chunk.toString("utf-8")),
        );
        this.exitPromise = new Promise<number | null>((resolve) => {
            child.once("exit", (code) => {
                this.stopped = true;
                this.logger.appendLine(
                    `[continuous] gradle --continuous exited (code=${code})`,
                );
                this.events.emit("exit", code);
                resolve(code);
            });
            child.once("error", (err) => {
                this.stopped = true;
                this.logger.appendLine(
                    `[continuous] gradle --continuous error: ${err.message}`,
                );
                this.events.emit("exit", null);
                resolve(null);
            });
        });
    }

    /**
     * Resolves with the outcome of the build triggered by the caller's
     * save. Two acceptance paths:
     *
     *   - **In-flight, fresh:** a build is currently running and its
     *     `Change detected` landed within [IN_FLIGHT_ACCEPT_WINDOW_MS] of
     *     this call. We treat that build as the caller's and resolve on
     *     its `BUILD SUCCESSFUL/FAILED`.
     *   - **Idle (or stale in-flight):** wait for the next `Change
     *     detected` to fire after this call, then resolve on the
     *     subsequent BUILD outcome.
     *
     * Returns `null` on timeout, on subprocess exit, or if the worker is
     * already stopped. Callers should treat `null` the same as a compile
     * failure for save-loop purposes — defer to the Gradle fallback path.
     */
    waitForNextBuild(timeoutMs?: number): Promise<BuildOutcome | null> {
        if (this.stopped) {
            return Promise.resolve(null);
        }
        const callTimeMs = this.now();
        const timeout = timeoutMs ?? this.defaultTimeoutMs;
        return new Promise<BuildOutcome | null>((resolve) => {
            let settled = false;
            let observedTriggerAfterCall =
                this.buildStartedAtMs !== null &&
                callTimeMs - this.buildStartedAtMs < IN_FLIGHT_ACCEPT_WINDOW_MS;

            const onBuildStarted = (at: number) => {
                if (at >= callTimeMs) {
                    observedTriggerAfterCall = true;
                }
            };
            const onBuildFinished = (outcome: BuildOutcome) => {
                if (observedTriggerAfterCall) {
                    settle(outcome);
                }
            };
            const onExit = () => settle(null);
            const timer = setTimeout(() => settle(null), timeout);

            const settle = (v: BuildOutcome | null) => {
                if (settled) {
                    return;
                }
                settled = true;
                this.events.off("buildStarted", onBuildStarted);
                this.events.off("buildFinished", onBuildFinished);
                this.events.off("exit", onExit);
                clearTimeout(timer);
                resolve(v);
            };

            this.events.on("buildStarted", onBuildStarted);
            this.events.on("buildFinished", onBuildFinished);
            this.events.on("exit", onExit);
        });
    }

    /**
     * Gracefully stop the subprocess. Sends EOF on stdin (the canonical
     * "stop continuous build" signal from Gradle's user manual), then
     * falls back to SIGTERM after a 5s grace window.
     */
    async stop(): Promise<void> {
        if (!this.child || this.stopped) {
            return;
        }
        try {
            this.child.stdin?.end();
        } catch {
            /* ignore */
        }
        const fallback = new Promise<void>((resolve) =>
            setTimeout(() => {
                try {
                    this.child?.kill("SIGTERM");
                } catch {
                    /* ignore */
                }
                resolve();
            }, 5_000),
        );
        await Promise.race([this.exitPromise.then(() => undefined), fallback]);
        this.stopped = true;
        this.child = null;
    }

    private consumeStdout(chunk: string): void {
        this.errorDetector.consume(chunk);
        for (const line of this.stdoutSplitter.feed(chunk)) {
            this.scanLine(line);
        }
    }

    private consumeStderr(chunk: string): void {
        // Kotlin error lines (`e: file://...`) sometimes land on stderr depending
        // on Gradle/Kotlin version + console-mode interactions. Feed both.
        this.errorDetector.consume(chunk);
        for (const line of this.stderrSplitter.feed(chunk)) {
            this.scanLine(line);
        }
    }

    private scanLine(line: string): void {
        if (CHANGE_DETECTED_RE.test(line)) {
            this.buildStartedAtMs = this.now();
            this.events.emit("buildStarted", this.buildStartedAtMs);
            return;
        }
        const m = BUILD_OUTCOME_RE.exec(line);
        if (!m) {
            return;
        }
        this.errorDetector.end();
        const ok = m[1] === "SUCCESSFUL";
        const ms = m[2]
            ? parseInt(m[2], 10)
            : m[3]
              ? Math.round(parseFloat(m[3]) * 1000)
              : null;
        const errors = ok ? [] : this.errorDetector.getErrors();
        const outcome: BuildOutcome = {
            ok,
            finishedAtMs: this.now(),
            durationMs: ms,
            errors,
        };
        this.buildStartedAtMs = null;
        // Reset the detector so the next build's errors are scoped to that build.
        this.errorDetector = new KotlinCompileErrorDetector();
        this.events.emit("buildFinished", outcome);
    }
}
