import * as assert from "assert";
import {
    SpeculationCache,
    VisibilityMemo,
    WarmCoalescer,
} from "../daemon/schedulerState";

const modA = { modulePath: ":app" };
const modB = { modulePath: ":lib" };

describe("WarmCoalescer", () => {
    it("starts a fresh warm when no in-flight promise exists", async () => {
        const coalescer = new WarmCoalescer<boolean>();
        let callCount = 0;
        const promise = coalescer.getOrStart(modA, async () => {
            callCount++;
            return true;
        });
        assert.strictEqual(coalescer.isInFlight(modA), true);
        const result = await promise;
        assert.strictEqual(result, true);
        assert.strictEqual(callCount, 1);
        assert.strictEqual(coalescer.isInFlight(modA), false);
    });

    it("coalesces a second call onto the in-flight promise without re-invoking the factory", async () => {
        // Regression: a second warm arriving mid-bootstrap must NOT start a second
        // composePreviewDaemonStart (which the first invocation would then cancel).
        const coalescer = new WarmCoalescer<boolean>();
        let callCount = 0;
        let resolve!: (v: boolean) => void;
        const factory = () => {
            callCount++;
            return new Promise<boolean>((r) => (resolve = r));
        };
        const p1 = coalescer.getOrStart(modA, factory);
        const p2 = coalescer.getOrStart(modA, factory);
        assert.strictEqual(callCount, 1);
        assert.strictEqual(p1, p2);
        resolve(true);
        assert.strictEqual(await p1, true);
        assert.strictEqual(await p2, true);
    });

    it("isolates in-flight tracking per module", async () => {
        const coalescer = new WarmCoalescer<boolean>();
        let callCount = 0;
        const factory = () => {
            callCount++;
            return Promise.resolve(true);
        };
        const pA = coalescer.getOrStart(modA, factory);
        const pB = coalescer.getOrStart(modB, factory);
        assert.notStrictEqual(pA, pB);
        assert.strictEqual(callCount, 2);
        await Promise.all([pA, pB]);
    });

    it("clears in-flight after settle so a later call starts a fresh warm", async () => {
        // Regression for the "previous warm cancelled, retry must not get a stale rejected
        // promise back" invariant — the old code's `finally` did this; the class does too.
        const coalescer = new WarmCoalescer<boolean>();
        let callCount = 0;
        await coalescer.getOrStart(modA, async () => {
            callCount++;
            return true;
        });
        await coalescer.getOrStart(modA, async () => {
            callCount++;
            return true;
        });
        assert.strictEqual(callCount, 2);
    });

    it("clears in-flight even when the factory promise rejects", async () => {
        const coalescer = new WarmCoalescer<boolean>();
        await coalescer
            .getOrStart(modA, () => Promise.reject(new Error("boom")))
            .catch(() => {});
        // Next call should start fresh.
        let secondRan = false;
        await coalescer.getOrStart(modA, async () => {
            secondRan = true;
            return true;
        });
        assert.strictEqual(secondRan, true);
    });
});

describe("SpeculationCache", () => {
    it("has returns false for never-marked pairs", () => {
        const cache = new SpeculationCache();
        assert.strictEqual(cache.has(modA, "p1"), false);
    });

    it("mark + has round-trips per (module, previewId) pair", () => {
        const cache = new SpeculationCache();
        cache.mark(modA, "p1");
        assert.strictEqual(cache.has(modA, "p1"), true);
        assert.strictEqual(cache.has(modA, "p2"), false);
        assert.strictEqual(cache.has(modB, "p1"), false);
    });

    it("forget drops one pair, leaving siblings intact", () => {
        const cache = new SpeculationCache();
        cache.mark(modA, "p1");
        cache.mark(modA, "p2");
        cache.forget(modA, "p1");
        assert.strictEqual(cache.has(modA, "p1"), false);
        assert.strictEqual(cache.has(modA, "p2"), true);
    });

    it("forgetModule drops every pair under that module, leaving other modules intact", () => {
        // Regression: the old inline code spelled this out at two callsites (classpath dirty +
        // channel closed). The class method must match — only the named module's entries go,
        // sibling modules' speculations survive.
        const cache = new SpeculationCache();
        cache.mark(modA, "p1");
        cache.mark(modA, "p2");
        cache.mark(modB, "p1");
        cache.forgetModule(modA);
        assert.strictEqual(cache.has(modA, "p1"), false);
        assert.strictEqual(cache.has(modA, "p2"), false);
        assert.strictEqual(cache.has(modB, "p1"), true);
    });

    it("does not confuse modules whose modulePath is a prefix of another", () => {
        // The keying uses '::' as separator; a prefix-match prune must not catch a longer
        // sibling key. Belt-and-braces test since the production code keys are user-facing
        // Gradle module paths.
        const cache = new SpeculationCache();
        const short = { modulePath: ":app" };
        const longer = { modulePath: ":app:feature" };
        cache.mark(short, "p1");
        cache.mark(longer, "p1");
        cache.forgetModule(short);
        assert.strictEqual(cache.has(short, "p1"), false);
        assert.strictEqual(cache.has(longer, "p1"), true);
    });
});

describe("VisibilityMemo", () => {
    it("sameVisibleAsLast returns false until a value has been recorded", () => {
        const memo = new VisibilityMemo();
        assert.strictEqual(memo.sameVisibleAsLast(modA, ["a"]), false);
    });

    it("sameVisibleAsLast is order-insensitive", () => {
        // setVisible's dedup intent is "same set of visible IDs", not "same array". The
        // memo must collapse permutations or the daemon sees spurious updates.
        const memo = new VisibilityMemo();
        memo.recordVisible(modA, ["a", "b", "c"]);
        assert.strictEqual(memo.sameVisibleAsLast(modA, ["c", "b", "a"]), true);
        assert.strictEqual(memo.sameVisibleAsLast(modA, ["a", "b"]), false);
    });

    it("sameVisibleAsLast vs sameFocusAsLast are independent per module", () => {
        const memo = new VisibilityMemo();
        memo.recordVisible(modA, ["v1"]);
        memo.recordFocus(modA, ["f1"]);
        assert.strictEqual(memo.sameVisibleAsLast(modA, ["v1"]), true);
        assert.strictEqual(memo.sameVisibleAsLast(modA, ["f1"]), false);
        assert.strictEqual(memo.sameFocusAsLast(modA, ["f1"]), true);
        assert.strictEqual(memo.sameFocusAsLast(modA, ["v1"]), false);
    });

    it("records are per module", () => {
        const memo = new VisibilityMemo();
        memo.recordVisible(modA, ["a"]);
        // modB's last is undefined, so even if you re-pass modA's value it's different.
        assert.strictEqual(memo.sameVisibleAsLast(modB, ["a"]), false);
    });

    it("recordVisible snapshots the array so a caller-side mutation doesn't poison the memo", () => {
        // The prior code spread `[...visible]` at the callsite for exactly this reason.
        // The class method must do the snapshot internally so callers can't accidentally
        // share array identity with the memo.
        const memo = new VisibilityMemo();
        const live = ["a", "b"];
        memo.recordVisible(modA, live);
        live.push("c");
        assert.strictEqual(memo.sameVisibleAsLast(modA, ["a", "b"]), true);
        assert.strictEqual(
            memo.sameVisibleAsLast(modA, ["a", "b", "c"]),
            false,
        );
    });

    it("forgetModule drops both visible and focus memos for that module", () => {
        const memo = new VisibilityMemo();
        memo.recordVisible(modA, ["v"]);
        memo.recordFocus(modA, ["f"]);
        memo.recordVisible(modB, ["v"]);
        memo.forgetModule(modA);
        assert.strictEqual(memo.sameVisibleAsLast(modA, ["v"]), false);
        assert.strictEqual(memo.sameFocusAsLast(modA, ["f"]), false);
        // modB's memo survives.
        assert.strictEqual(memo.sameVisibleAsLast(modB, ["v"]), true);
    });
});
