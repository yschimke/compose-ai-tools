import * as assert from "assert";
import { RefreshQueue, defaultRefreshQueueEffects } from "../refreshQueue";

/**
 * End-to-end integration test for {@link RefreshQueue} against the real
 * `setTimeout` / `clearTimeout` from Node. The unit-test sister file uses a
 * fake clock; this file proves that the queue behaves correctly when wired
 * to the same timer primitives production uses.
 *
 * Debounce values are deliberately small (10–40 ms) so the suite still
 * completes inside a few hundred ms even though every assertion waits on
 * real wall-clock time.
 */

const TINY_DEBOUNCE_MS = 20;
const LONG_DEBOUNCE_MS = 60;

function sleep(ms: number): Promise<void> {
    return new Promise((r) => setTimeout(r, ms));
}

interface Recorder {
    runs: string[];
    invalidations: string[];
    /** Optional per-target hold so a test can pin runRefresh in flight. */
    holdMs?: number;
}

function makeQueueAgainstRealTimers(rec: Recorder): RefreshQueue {
    const effects = defaultRefreshQueueEffects(
        async (target) => {
            rec.runs.push(target);
            if (rec.holdMs && rec.holdMs > 0) {
                await sleep(rec.holdMs);
            }
        },
        (target) => {
            rec.invalidations.push(target);
        },
    );
    return new RefreshQueue(effects);
}

describe("RefreshQueue (real-timer integration)", () => {
    it("end-to-end: first save fires immediately, burst collapses, watcher event coalesces", async () => {
        const rec: Recorder = { runs: [], invalidations: [], holdMs: 5 };
        const queue = makeQueueAgainstRealTimers(rec);
        try {
            // 1) First editor save — fast path.
            queue.dispatchSave("/a.kt", {
                allowImmediate: true,
                debounceMs: TINY_DEBOUNCE_MS,
            });
            assert.deepStrictEqual(rec.runs, ["/a.kt"]);
            // Let the in-flight refresh settle before the next phase so we're
            // not also testing the never-stack-builds path here.
            await sleep(rec.holdMs! + 5);
            assert.strictEqual(queue.isInFlight(), false);

            // 2) Burst of three saves of the same file within the debounce
            //    window — must collapse to a single refresh.
            queue.dispatchSave("/a.kt", {
                allowImmediate: true,
                debounceMs: TINY_DEBOUNCE_MS,
            });
            await sleep(5);
            queue.dispatchSave("/a.kt", {
                allowImmediate: true,
                debounceMs: TINY_DEBOUNCE_MS,
            });
            await sleep(5);
            queue.dispatchSave("/a.kt", {
                allowImmediate: true,
                debounceMs: TINY_DEBOUNCE_MS,
            });
            // No refresh yet — debounce hasn't elapsed.
            assert.strictEqual(rec.runs.length, 1);
            await sleep(TINY_DEBOUNCE_MS + rec.holdMs! + 10);
            assert.deepStrictEqual(rec.runs, ["/a.kt", "/a.kt"]);

            // 3) A watcher event for a never-before-seen file. Must NOT take
            //    the fast path — debounce first, then fire.
            queue.dispatchSave("/b.kt", {
                allowImmediate: false,
                debounceMs: TINY_DEBOUNCE_MS,
            });
            assert.strictEqual(rec.runs.length, 2);
            await sleep(TINY_DEBOUNCE_MS + rec.holdMs! + 10);
            assert.deepStrictEqual(rec.runs, ["/a.kt", "/a.kt", "/b.kt"]);
        } finally {
            queue.dispose();
        }
    });

    it("end-to-end: a save during an in-flight refresh drains exactly once on completion", async () => {
        const rec: Recorder = { runs: [], invalidations: [], holdMs: 60 };
        const queue = makeQueueAgainstRealTimers(rec);
        try {
            queue.dispatchSave("/a.kt", {
                allowImmediate: true,
                debounceMs: TINY_DEBOUNCE_MS,
            });
            assert.strictEqual(queue.isInFlight(), true);
            // While /a.kt is held in flight, fire two more saves of /a.kt.
            await sleep(10);
            queue.dispatchSave("/a.kt", {
                allowImmediate: true,
                debounceMs: TINY_DEBOUNCE_MS,
            });
            await sleep(5);
            queue.dispatchSave("/a.kt", {
                allowImmediate: true,
                debounceMs: TINY_DEBOUNCE_MS,
            });
            // Debounce window passes while still in flight — tryFire bails.
            await sleep(TINY_DEBOUNCE_MS + 5);
            assert.strictEqual(rec.runs.length, 1);
            assert.strictEqual(queue.snapshot().pendingTarget, "/a.kt");
            // Wait for completion + drain.
            await sleep(rec.holdMs!);
            // The drained refresh now runs (and holds for holdMs more).
            await sleep(rec.holdMs! + 10);
            assert.deepStrictEqual(rec.runs, ["/a.kt", "/a.kt"]);
        } finally {
            queue.dispose();
        }
    });

    it("end-to-end: long debounce honours its duration against the real clock", async () => {
        const rec: Recorder = { runs: [], invalidations: [] };
        const queue = makeQueueAgainstRealTimers(rec);
        try {
            queue.dispatchSave("/a.kt", {
                allowImmediate: true,
                debounceMs: TINY_DEBOUNCE_MS,
            });
            await sleep(10);
            // Subsequent save uses the LONG debounce. Wait less than that —
            // refresh must NOT have fired yet.
            queue.dispatchSave("/a.kt", {
                allowImmediate: true,
                debounceMs: LONG_DEBOUNCE_MS,
            });
            await sleep(TINY_DEBOUNCE_MS + 10);
            assert.strictEqual(rec.runs.length, 1);
            await sleep(LONG_DEBOUNCE_MS);
            assert.strictEqual(rec.runs.length, 2);
        } finally {
            queue.dispose();
        }
    });

    it("end-to-end: dispose cancels the timer so no refresh fires after teardown", async () => {
        const rec: Recorder = { runs: [], invalidations: [] };
        const queue = makeQueueAgainstRealTimers(rec);
        // Use a watcher event so the FIRST dispatch goes through debounce
        // (the editor-save fast path would fire synchronously and there'd be
        // no pending timer for dispose to cancel).
        queue.dispatchSave("/a.kt", {
            allowImmediate: false,
            debounceMs: LONG_DEBOUNCE_MS,
        });
        assert.strictEqual(rec.runs.length, 0);
        queue.dispose();
        await sleep(LONG_DEBOUNCE_MS + 10);
        assert.strictEqual(rec.runs.length, 0);
    });
});
