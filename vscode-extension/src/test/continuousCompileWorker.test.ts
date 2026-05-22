import * as assert from "assert";
import { EventEmitter, Readable, Writable } from "stream";
import { ChildProcess, SpawnOptions } from "child_process";
import {
    BuildOutcome,
    ContinuousCompileWorker,
} from "../daemon/continuousCompileWorker";

/**
 * Test double for the subset of `ChildProcess` the worker reads. We push
 * canned stdout chunks through `pushStdout` (and `pushStderr`) to drive
 * the worker's parser the way real Gradle output would.
 */
class FakeChild extends EventEmitter {
    stdout = new Readable({ read() {} });
    stderr = new Readable({ read() {} });
    stdin: Writable & { ended: boolean };
    stdinEnded = false;
    killed: NodeJS.Signals | null = null;

    constructor() {
        super();
        const self = this;
        this.stdin = Object.assign(
            new Writable({
                write(_chunk, _enc, cb) {
                    cb();
                },
                final(cb) {
                    self.stdinEnded = true;
                    cb();
                },
            }),
            { ended: false },
        );
    }

    pushStdout(chunk: string): void {
        this.stdout.push(chunk);
    }

    pushStderr(chunk: string): void {
        this.stderr.push(chunk);
    }

    exit(code: number | null = 0): void {
        this.emit("exit", code);
    }

    kill(signal: NodeJS.Signals = "SIGTERM"): boolean {
        this.killed = signal;
        return true;
    }
}

interface SpawnCall {
    command: string;
    args: readonly string[];
    options: SpawnOptions;
}

function fakeSpawn(onSpawn: (call: SpawnCall) => FakeChild): {
    spawnFn: (
        command: string,
        args: readonly string[],
        options: SpawnOptions,
    ) => ChildProcess;
    calls: SpawnCall[];
    children: FakeChild[];
} {
    const calls: SpawnCall[] = [];
    const children: FakeChild[] = [];
    const spawnFn = (
        command: string,
        args: readonly string[],
        options: SpawnOptions,
    ): ChildProcess => {
        const call: SpawnCall = { command, args, options };
        calls.push(call);
        const child = onSpawn(call);
        children.push(child);
        return child as unknown as ChildProcess;
    };
    return { spawnFn, calls, children };
}

class FakeClock {
    t = 0;
    now = (): number => this.t;
    advance(ms: number): void {
        this.t += ms;
    }
}

function makeWorker(
    clock: FakeClock,
    spawnFn: ReturnType<typeof fakeSpawn>["spawnFn"],
    logger?: { appendLine(line: string): void },
): ContinuousCompileWorker {
    return new ContinuousCompileWorker({
        workspaceRoot: "/repo",
        taskPath: ":samples:android:composePreviewCompile",
        spawnFn: spawnFn as unknown as typeof import("child_process").spawn,
        nowFn: clock.now,
        logger,
        buildTimeoutMs: 1_000,
    });
}

/** Flush the microtask queue so EventEmitter-routed Promises settle. */
function tick(): Promise<void> {
    return new Promise((resolve) => setImmediate(resolve));
}

describe("ContinuousCompileWorker", () => {
    it("spawns gradlew with --continuous and the requested task", () => {
        const clock = new FakeClock();
        const { spawnFn, calls, children } = fakeSpawn(() => new FakeChild());
        const worker = makeWorker(clock, spawnFn);
        worker.start();
        assert.strictEqual(calls.length, 1);
        assert.match(calls[0].command, /gradlew(\.bat)?$/);
        assert.deepStrictEqual(calls[0].args, [
            ":samples:android:composePreviewCompile",
            "--continuous",
            "--console=plain",
        ]);
        assert.strictEqual(calls[0].options.cwd, "/repo");
        // Spawned child is tracked for subsequent assertions.
        assert.strictEqual(children.length, 1);
        // running is true after start.
        assert.strictEqual(worker.running, true);
    });

    it("ignores the warm-up BUILD SUCCESSFUL (no Change detected before it)", async () => {
        const clock = new FakeClock();
        const { spawnFn, children } = fakeSpawn(() => new FakeChild());
        const worker = makeWorker(clock, spawnFn);
        worker.start();
        const child = children[0];

        // Warm-up build runs immediately at worker start — no "Change detected".
        clock.advance(100);
        child.pushStdout(
            "> Task :samples:android:composePreviewCompile UP-TO-DATE\n",
        );
        child.pushStdout("BUILD SUCCESSFUL in 480ms\n");
        await tick();

        // Now the caller fires waitForNextBuild — must NOT see the warm-up.
        clock.advance(50);
        const pending = worker.waitForNextBuild(500);
        // Drive a real source-change build through.
        clock.advance(20);
        child.pushStdout("Change detected, executing build...\n");
        clock.advance(420);
        child.pushStdout("BUILD SUCCESSFUL in 420ms\n");
        const outcome = await pending;
        assert.notStrictEqual(outcome, null);
        assert.strictEqual(outcome!.ok, true);
        assert.strictEqual(outcome!.durationMs, 420);
    });

    it("resolves with BUILD SUCCESSFUL after Change detected", async () => {
        const clock = new FakeClock();
        const { spawnFn, children } = fakeSpawn(() => new FakeChild());
        const worker = makeWorker(clock, spawnFn);
        worker.start();
        const child = children[0];

        clock.advance(50);
        const pending = worker.waitForNextBuild();
        clock.advance(10);
        child.pushStdout("Change detected, executing build...\n");
        clock.advance(900);
        child.pushStdout("BUILD SUCCESSFUL in 900ms\n");
        const outcome = await pending;
        assert.deepStrictEqual(outcome, {
            ok: true,
            finishedAtMs: 960,
            durationMs: 900,
            errors: [],
        } satisfies BuildOutcome);
    });

    it("captures Kotlin compile errors on BUILD FAILED", async () => {
        const clock = new FakeClock();
        const { spawnFn, children } = fakeSpawn(() => new FakeChild());
        const worker = makeWorker(clock, spawnFn);
        worker.start();
        const child = children[0];

        const pending = worker.waitForNextBuild();
        child.pushStdout("Change detected, executing build...\n");
        child.pushStdout(
            "e: file:///repo/samples/android/src/main/kotlin/Foo.kt:12:5 Unresolved reference: bar\n",
        );
        child.pushStdout("BUILD FAILED in 2s\n");
        const outcome = await pending;
        assert.notStrictEqual(outcome, null);
        assert.strictEqual(outcome!.ok, false);
        assert.strictEqual(outcome!.durationMs, 2000);
        assert.strictEqual(outcome!.errors.length, 1);
        assert.strictEqual(outcome!.errors[0].line, 12);
        assert.match(outcome!.errors[0].file, /Foo\.kt$/);
    });

    it("times out cleanly when no build follows", async () => {
        const clock = new FakeClock();
        const { spawnFn } = fakeSpawn(() => new FakeChild());
        const worker = makeWorker(clock, spawnFn);
        worker.start();
        const outcome = await worker.waitForNextBuild(20);
        assert.strictEqual(outcome, null);
    });

    it("returns null when the subprocess exits mid-wait", async () => {
        const clock = new FakeClock();
        const { spawnFn, children } = fakeSpawn(() => new FakeChild());
        const worker = makeWorker(clock, spawnFn);
        worker.start();
        const child = children[0];
        const pending = worker.waitForNextBuild(5_000);
        child.exit(1);
        const outcome = await pending;
        assert.strictEqual(outcome, null);
        assert.strictEqual(worker.running, false);
    });

    it("returns null immediately when called on a stopped worker", async () => {
        const clock = new FakeClock();
        const { spawnFn, children } = fakeSpawn(() => new FakeChild());
        const worker = makeWorker(clock, spawnFn);
        worker.start();
        children[0].exit(0);
        await tick();
        const outcome = await worker.waitForNextBuild(5_000);
        assert.strictEqual(outcome, null);
    });

    it("treats an in-flight Change-detected build as the caller's build", async () => {
        // Race: Gradle's watcher fires before our save event delivers a
        // waitForNextBuild() call. The build's Change-detected timestamp is
        // after the caller's clock, so we should still observe its outcome.
        const clock = new FakeClock();
        const { spawnFn, children } = fakeSpawn(() => new FakeChild());
        const worker = makeWorker(clock, spawnFn);
        worker.start();
        const child = children[0];
        clock.advance(100);
        // Change detected at t=100ms (after our t=0 baseline).
        child.pushStdout("Change detected, executing build...\n");
        await tick();
        clock.advance(50);
        // Caller fires at t=150ms — build started at t=100ms is still in flight.
        const pending = worker.waitForNextBuild(5_000);
        clock.advance(400);
        child.pushStdout("BUILD SUCCESSFUL in 450ms\n");
        const outcome = await pending;
        assert.notStrictEqual(outcome, null);
        assert.strictEqual(outcome!.ok, true);
    });

    it("ignores an in-flight build older than the accept window", async () => {
        // A build that started long before this call is treated as stale —
        // it almost certainly doesn't include the caller's freshly-saved
        // edits, so waiting for it would produce a render that lags the
        // source. We wait for the NEXT build instead, even though it costs
        // an extra round-trip.
        const clock = new FakeClock();
        const { spawnFn, children } = fakeSpawn(() => new FakeChild());
        const worker = makeWorker(clock, spawnFn);
        worker.start();
        const child = children[0];
        // Build started at t=0 (long before the caller).
        child.pushStdout("Change detected, executing build...\n");
        await tick();
        // Caller fires at t=1500ms — delta=1500 > IN_FLIGHT_ACCEPT_WINDOW_MS (500).
        clock.advance(1_500);
        const pending = worker.waitForNextBuild(50);
        // The stale build completes but its outcome must NOT satisfy us.
        clock.advance(100);
        child.pushStdout("BUILD SUCCESSFUL in 1600ms\n");
        const outcome = await pending;
        assert.strictEqual(outcome, null);
    });

    it("parses duration trailers in ms, s, and fractional seconds", async () => {
        const cases: Array<[string, number]> = [
            ["BUILD SUCCESSFUL in 480ms\n", 480],
            ["BUILD SUCCESSFUL in 1s\n", 1000],
            ["BUILD SUCCESSFUL in 1.4s\n", 1400],
            ["BUILD SUCCESSFUL in 12s\n", 12000],
        ];
        for (const [line, expected] of cases) {
            const clock = new FakeClock();
            const { spawnFn, children } = fakeSpawn(() => new FakeChild());
            const worker = makeWorker(clock, spawnFn);
            worker.start();
            const child = children[0];
            const pending = worker.waitForNextBuild();
            child.pushStdout("Change detected, executing build...\n");
            child.pushStdout(line);
            const outcome = await pending;
            assert.strictEqual(
                outcome?.durationMs,
                expected,
                `parse failure for ${JSON.stringify(line)}`,
            );
        }
    });

    it("stop() sends EOF on stdin and resolves once the child exits", async () => {
        const clock = new FakeClock();
        const { spawnFn, children } = fakeSpawn(() => new FakeChild());
        const worker = makeWorker(clock, spawnFn);
        worker.start();
        const child = children[0];
        const stopping = worker.stop();
        // Simulate gradle reacting to EOF.
        setTimeout(() => child.exit(0), 5);
        await stopping;
        assert.strictEqual(child.stdinEnded, true);
        assert.strictEqual(worker.running, false);
    });
});
