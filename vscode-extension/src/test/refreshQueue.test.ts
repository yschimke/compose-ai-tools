import * as assert from "assert";
import {
    RefreshQueue,
    RefreshQueueEffects,
    RefreshQueueTimer,
} from "../refreshQueue";

/**
 * Deterministic fake clock: schedules callbacks with a virtual delay and
 * fires them only when `advance(ms)` crosses their deadline. Lets every
 * transition assertion run synchronously — no `setImmediate` /
 * `await flushMicrotasks()` dance.
 */
class FakeClock {
    private now = 0;
    private nextHandle = 1;
    private readonly pending = new Map<
        number,
        { dueAt: number; cb: () => void }
    >();

    setTimeout(cb: () => void, ms: number): RefreshQueueTimer {
        const handle = this.nextHandle++;
        this.pending.set(handle, { dueAt: this.now + ms, cb });
        return handle as unknown as RefreshQueueTimer;
    }

    clearTimeout(timer: RefreshQueueTimer): void {
        this.pending.delete(timer as unknown as number);
    }

    /** Advance virtual time and fire any callbacks whose deadline passes. */
    advance(ms: number): void {
        const target = this.now + ms;
        while (true) {
            const next = this.dueBefore(target);
            if (next === null) break;
            this.now = next.dueAt;
            this.pending.delete(next.handle);
            next.cb();
        }
        this.now = target;
    }

    pendingCount(): number {
        return this.pending.size;
    }

    private dueBefore(
        target: number,
    ): { handle: number; dueAt: number; cb: () => void } | null {
        let best: { handle: number; dueAt: number; cb: () => void } | null =
            null;
        for (const [handle, entry] of this.pending) {
            if (
                entry.dueAt <= target &&
                (best === null || entry.dueAt < best.dueAt)
            ) {
                best = { handle, ...entry };
            }
        }
        return best;
    }
}

/**
 * Captures the queue's interactions with its effects and offers a manually
 * resolvable `runRefresh` so tests can simulate long-running renders.
 */
class RecordingEffects implements RefreshQueueEffects {
    public readonly runRefreshCalls: string[] = [];
    public readonly invalidateCalls: string[] = [];
    private pendingResolves: Array<() => void> = [];

    constructor(public readonly clock: FakeClock) {}

    runRefresh = (target: string): Promise<void> => {
        this.runRefreshCalls.push(target);
        return new Promise<void>((resolve) => {
            this.pendingResolves.push(resolve);
        });
    };

    invalidateModuleCache = (target: string): void => {
        this.invalidateCalls.push(target);
    };

    setTimeout = (cb: () => void, ms: number): RefreshQueueTimer =>
        this.clock.setTimeout(cb, ms);

    clearTimeout = (timer: RefreshQueueTimer): void =>
        this.clock.clearTimeout(timer);

    /** Resolve the most recent in-flight runRefresh. */
    completeLastRefresh(): Promise<void> {
        const resolve = this.pendingResolves.shift();
        assert.ok(resolve, "no in-flight refresh to complete");
        resolve();
        // Let the queue's .then() handler run before the next assertion.
        return new Promise((r) => setImmediate(r));
    }

    inFlightCount(): number {
        return this.pendingResolves.length;
    }
}

function makeQueue(): {
    queue: RefreshQueue;
    effects: RecordingEffects;
    clock: FakeClock;
} {
    const clock = new FakeClock();
    const effects = new RecordingEffects(clock);
    const queue = new RefreshQueue(effects);
    return { queue, effects, clock };
}

const FAST = { allowImmediate: true, debounceMs: 300 };
const SLOW = { allowImmediate: true, debounceMs: 1500 };
const WATCHER = { allowImmediate: false, debounceMs: 300 };

describe("RefreshQueue", () => {
    describe("first-seen fast path", () => {
        it("runs the first save of a file immediately, no debounce", () => {
            const { queue, effects, clock } = makeQueue();
            queue.dispatchSave("/a.kt", FAST);
            assert.deepStrictEqual(effects.runRefreshCalls, ["/a.kt"]);
            assert.deepStrictEqual(effects.invalidateCalls, ["/a.kt"]);
            assert.strictEqual(clock.pendingCount(), 0);
            assert.strictEqual(queue.isInFlight(), true);
        });

        it("does NOT take the fast path on the second save of the same file", async () => {
            const { queue, effects, clock } = makeQueue();
            queue.dispatchSave("/a.kt", FAST);
            await effects.completeLastRefresh();
            // Second save: file already in seen set; not idle? It IS idle now,
            // but the firstSeen predicate filters it out. Must debounce.
            queue.dispatchSave("/a.kt", FAST);
            assert.strictEqual(effects.runRefreshCalls.length, 1);
            assert.strictEqual(clock.pendingCount(), 1);
            clock.advance(300);
            assert.deepStrictEqual(effects.runRefreshCalls, ["/a.kt", "/a.kt"]);
        });

        it("debounces a first-seen save when another refresh is already in flight", () => {
            const { queue, effects, clock } = makeQueue();
            queue.dispatchSave("/a.kt", FAST);
            // While A is in flight, B's first save lands. It cannot fast-path
            // because the queue is busy — must debounce.
            queue.dispatchSave("/b.kt", FAST);
            assert.deepStrictEqual(effects.runRefreshCalls, ["/a.kt"]);
            assert.strictEqual(clock.pendingCount(), 1);
            assert.strictEqual(queue.snapshot().pendingTarget, "/b.kt");
        });
    });

    describe("debounce", () => {
        it("collapses a burst of saves of the same file into one refresh", async () => {
            const { queue, effects, clock } = makeQueue();
            // First save warms the seen-set and runs immediately.
            queue.dispatchSave("/a.kt", FAST);
            await effects.completeLastRefresh();
            // Now a burst: 5 saves within the debounce window.
            for (let i = 0; i < 5; i++) {
                queue.dispatchSave("/a.kt", FAST);
                clock.advance(50);
            }
            assert.strictEqual(effects.runRefreshCalls.length, 1);
            // Only after the debounce elapses without another save does the
            // refresh fire — and exactly once.
            clock.advance(300);
            assert.deepStrictEqual(effects.runRefreshCalls, ["/a.kt", "/a.kt"]);
        });

        it("latest target wins when the burst spans multiple files", async () => {
            const { queue, effects, clock } = makeQueue();
            queue.dispatchSave("/a.kt", FAST);
            await effects.completeLastRefresh();
            queue.dispatchSave("/a.kt", FAST);
            clock.advance(100);
            queue.dispatchSave("/b.kt", FAST);
            clock.advance(100);
            queue.dispatchSave("/c.kt", FAST);
            clock.advance(300);
            assert.deepStrictEqual(effects.runRefreshCalls, ["/a.kt", "/c.kt"]);
        });

        it("uses the dispatcher's chosen debounceMs (scope vs normal)", async () => {
            const { queue, effects, clock } = makeQueue();
            queue.dispatchSave("/a.kt", FAST);
            await effects.completeLastRefresh();
            // Normal (long) debounce queued: short tick must NOT fire it.
            queue.dispatchSave("/a.kt", SLOW);
            clock.advance(300);
            assert.strictEqual(effects.runRefreshCalls.length, 1);
            clock.advance(1200);
            assert.strictEqual(effects.runRefreshCalls.length, 2);
        });

        it("restarts the timer on each save so a steady stream defers indefinitely", async () => {
            const { queue, effects, clock } = makeQueue();
            queue.dispatchSave("/a.kt", FAST);
            await effects.completeLastRefresh();
            for (let i = 0; i < 10; i++) {
                queue.dispatchSave("/a.kt", FAST);
                // Save more frequently than the debounce window — the timer
                // restarts each time, so nothing fires.
                clock.advance(200);
            }
            assert.strictEqual(effects.runRefreshCalls.length, 1);
            clock.advance(300);
            assert.strictEqual(effects.runRefreshCalls.length, 2);
        });
    });

    describe("never stack builds", () => {
        it("queues a save that arrives mid-refresh and fires it on completion", async () => {
            const { queue, effects, clock } = makeQueue();
            queue.dispatchSave("/a.kt", FAST);
            assert.strictEqual(effects.inFlightCount(), 1);
            // Save during the in-flight render.
            queue.dispatchSave("/a.kt", FAST);
            clock.advance(300); // debounce elapses while still in-flight
            // tryFire bails because inFlight; pending sticks around.
            assert.strictEqual(effects.runRefreshCalls.length, 1);
            assert.strictEqual(queue.snapshot().pendingTarget, "/a.kt");
            await effects.completeLastRefresh();
            // Completion drains the pending refresh.
            assert.deepStrictEqual(effects.runRefreshCalls, ["/a.kt", "/a.kt"]);
        });

        it("only fires once when both debounce-elapsed and completion happen together", async () => {
            const { queue, effects, clock } = makeQueue();
            queue.dispatchSave("/a.kt", FAST);
            queue.dispatchSave("/a.kt", FAST);
            // Race: debounce elapses BEFORE completion.
            clock.advance(300);
            assert.strictEqual(effects.runRefreshCalls.length, 1);
            await effects.completeLastRefresh();
            assert.strictEqual(effects.runRefreshCalls.length, 2);
            // …and no extra fires after that.
            assert.strictEqual(queue.snapshot().pendingTarget, null);
        });

        it("a refresh that throws still settles inFlight and drains the next pending", async () => {
            const clock = new FakeClock();
            const rejecters: Array<() => void> = [];
            const effects: RefreshQueueEffects = {
                runRefresh: (_target) =>
                    new Promise<void>((_resolve, reject) => {
                        rejecters.push(() =>
                            reject(new Error("render bombed")),
                        );
                    }),
                invalidateModuleCache: () => {},
                setTimeout: (cb, ms) => clock.setTimeout(cb, ms),
                clearTimeout: (t) => clock.clearTimeout(t),
            };
            const queue = new RefreshQueue(effects);
            queue.dispatchSave("/a.kt", FAST);
            queue.dispatchSave("/b.kt", FAST);
            clock.advance(300);
            assert.strictEqual(queue.isInFlight(), true);
            assert.strictEqual(rejecters.length, 1);
            rejecters[0]();
            await new Promise((r) => setImmediate(r));
            // After the failure: inFlight cleared, pending /b.kt fired.
            assert.strictEqual(queue.isInFlight(), true); // /b.kt is now in flight
            assert.strictEqual(queue.snapshot().pendingTarget, null);
        });
    });

    describe("watcher events (allowImmediate=false)", () => {
        it("never takes the fast path, even on a never-before-seen file", () => {
            const { queue, effects, clock } = makeQueue();
            queue.dispatchSave("/a.kt", WATCHER);
            assert.deepStrictEqual(effects.runRefreshCalls, []);
            assert.strictEqual(clock.pendingCount(), 1);
            clock.advance(300);
            assert.deepStrictEqual(effects.runRefreshCalls, ["/a.kt"]);
        });

        it("does not mark the file as seen, so a later editor save of the same file can still fast-path", async () => {
            const { queue, effects, clock } = makeQueue();
            queue.dispatchSave("/a.kt", WATCHER);
            clock.advance(300);
            await effects.completeLastRefresh();
            // Now the editor saves /a.kt for the first time. Should fast-path
            // because the file was never marked seen by the watcher.
            queue.dispatchSave("/a.kt", FAST);
            assert.deepStrictEqual(effects.runRefreshCalls, ["/a.kt", "/a.kt"]);
            assert.strictEqual(clock.pendingCount(), 0);
        });
    });

    describe("markSeen", () => {
        it("blocks a later first-seen fast path", () => {
            const { queue, effects, clock } = makeQueue();
            queue.markSeen("/a.kt");
            queue.dispatchSave("/a.kt", FAST);
            // The file is already in seen, so even though the queue is idle,
            // dispatch falls through to debounce.
            assert.deepStrictEqual(effects.runRefreshCalls, []);
            assert.strictEqual(clock.pendingCount(), 1);
        });
    });

    describe("dispose", () => {
        it("cancels the pending debounce timer", () => {
            const { queue, effects, clock } = makeQueue();
            queue.dispatchSave("/a.kt", FAST);
            // Burn the first refresh so subsequent saves debounce.
            // We can't easily complete it without async here, so dispatch a
            // second save which schedules a timer.
            queue.dispatchSave("/b.kt", FAST);
            assert.strictEqual(clock.pendingCount(), 1);
            queue.dispose();
            assert.strictEqual(clock.pendingCount(), 0);
            // Advancing time after dispose must not fire anything.
            clock.advance(10_000);
            assert.strictEqual(effects.runRefreshCalls.length, 1);
        });
    });
});
