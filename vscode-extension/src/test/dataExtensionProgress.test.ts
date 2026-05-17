import * as assert from "assert";
import {
    DataExtensionPendingDiagnostics,
    DataExtensionProgressTracker,
    ProgressPost,
    StatusSummary,
    formatLabel,
} from "../dataExtensionProgress";

/** Deterministic clock + scheduler shared with the buildProgress test idea —
 *  exposes `setTimeout` in addition to `setInterval` so we can fire safety
 *  timeouts on demand. */
class FakeClock {
    private t = 0;
    private intervals: { interval: number; nextAt: number; cb: () => void }[] =
        [];
    private timeouts: { firesAt: number; cb: () => void }[] = [];
    now = (): number => this.t;
    setInterval = (cb: () => void, ms: number): unknown => {
        const handle = { interval: ms, nextAt: this.t + ms, cb };
        this.intervals.push(handle);
        return handle;
    };
    clearInterval = (handle: unknown): void => {
        this.intervals = this.intervals.filter((h) => h !== handle);
    };
    setTimeout = (cb: () => void, ms: number): unknown => {
        const handle = { firesAt: this.t + ms, cb };
        this.timeouts.push(handle);
        return handle;
    };
    clearTimeout = (handle: unknown): void => {
        this.timeouts = this.timeouts.filter((h) => h !== handle);
    };
    advance(ms: number): void {
        const target = this.t + ms;
        // eslint-disable-next-line no-constant-condition
        while (true) {
            const nextInterval = this.intervals
                .filter((h) => h.nextAt <= target)
                .sort((a, b) => a.nextAt - b.nextAt)[0];
            const nextTimeout = this.timeouts
                .filter((h) => h.firesAt <= target)
                .sort((a, b) => a.firesAt - b.firesAt)[0];
            const interT = nextInterval?.nextAt ?? Number.POSITIVE_INFINITY;
            const timeT = nextTimeout?.firesAt ?? Number.POSITIVE_INFINITY;
            if (
                interT === Number.POSITIVE_INFINITY &&
                timeT === Number.POSITIVE_INFINITY
            ) {
                break;
            }
            if (timeT <= interT) {
                this.t = nextTimeout!.firesAt;
                const cb = nextTimeout!.cb;
                this.timeouts = this.timeouts.filter((h) => h !== nextTimeout);
                cb();
            } else {
                this.t = nextInterval!.nextAt;
                nextInterval!.nextAt += nextInterval!.interval;
                nextInterval!.cb();
            }
        }
        this.t = target;
    }
}

interface Capture {
    progress: ProgressPost[];
    clears: number;
    statuses: (StatusSummary | null)[];
    timeouts: DataExtensionPendingDiagnostics[];
}

function makeTracker(
    clock: FakeClock,
    overrides: { timeoutMs?: number; easeMs?: number } = {},
): {
    tracker: DataExtensionProgressTracker;
    capture: Capture;
} {
    const capture: Capture = {
        progress: [],
        clears: 0,
        statuses: [],
        timeouts: [],
    };
    const tracker = new DataExtensionProgressTracker({
        timeoutMs: overrides.timeoutMs ?? 5_000,
        easeMs: overrides.easeMs ?? 1_000,
        tickIntervalMs: 100,
        onProgress: (p) => capture.progress.push({ ...p }),
        onClear: () => {
            capture.clears += 1;
        },
        onStatus: (s) =>
            capture.statuses.push(s ? { ...s, kinds: [...s.kinds] } : null),
        onTimeout: (d) => capture.timeouts.push(d),
        now: clock.now,
        scheduler: {
            setInterval: clock.setInterval,
            clearInterval: clock.clearInterval,
            setTimeout: clock.setTimeout,
            clearTimeout: clock.clearTimeout,
        },
    });
    return { tracker, capture };
}

describe("DataExtensionProgressTracker", () => {
    it("emits status and progress when a pending entry is registered", () => {
        const clock = new FakeClock();
        const { tracker, capture } = makeTracker(clock);
        tracker.begin("Preview#one", ":samples:android", ["a11y/atf"]);
        assert.strictEqual(tracker.size, 1);
        assert.ok(capture.progress.length > 0, "initial progress emitted");
        assert.strictEqual(capture.progress[0].label, "Loading atf");
        assert.ok(capture.progress[0].percent > 0);
        assert.deepStrictEqual(capture.statuses.at(-1), {
            pendingPreviewCount: 1,
            pendingKindCount: 1,
            kinds: ["a11y/atf"],
        });
    });

    it("clears the bar when the matching data product arrives", () => {
        const clock = new FakeClock();
        const { tracker, capture } = makeTracker(clock);
        tracker.begin("Preview#one", ":samples:android", [
            "a11y/atf",
            "a11y/hierarchy",
        ]);
        tracker.resolve("Preview#one", ["a11y/atf"]);
        assert.strictEqual(tracker.size, 1, "still pending the other kind");
        assert.strictEqual(capture.clears, 0);
        tracker.resolve("Preview#one", ["a11y/hierarchy"]);
        assert.strictEqual(tracker.size, 0);
        assert.strictEqual(capture.clears, 1);
        assert.strictEqual(capture.statuses.at(-1), null);
    });

    it("advances the progress percent toward the cap as time passes", () => {
        const clock = new FakeClock();
        const { tracker, capture } = makeTracker(clock, { easeMs: 500 });
        tracker.begin("Preview#one", ":m", ["compose/recomposition"]);
        const initialPct = capture.progress.at(-1)!.percent;
        clock.advance(2_000);
        const laterPct = capture.progress.at(-1)!.percent;
        assert.ok(
            laterPct > initialPct,
            `expected percent to grow: ${initialPct} -> ${laterPct}`,
        );
        assert.ok(laterPct < 1, `percent should remain bounded: ${laterPct}`);
    });

    it("fires the safety timeout and clears the entry", () => {
        const clock = new FakeClock();
        const { tracker, capture } = makeTracker(clock, { timeoutMs: 1_000 });
        tracker.begin("Preview#stalled", ":m", ["a11y/atf"]);
        clock.advance(1_001);
        assert.strictEqual(capture.timeouts.length, 1);
        assert.strictEqual(capture.timeouts[0].previewId, "Preview#stalled");
        assert.deepStrictEqual(capture.timeouts[0].kinds, ["a11y/atf"]);
        assert.ok(
            capture.timeouts[0].elapsedMs >= 1_000,
            "elapsedMs should reflect the wait",
        );
        assert.strictEqual(tracker.size, 0, "entry dropped after timeout");
        assert.strictEqual(
            capture.clears,
            1,
            "bar cleared once the last entry expires",
        );
    });

    it("does not fire the safety timeout after a payload arrives", () => {
        const clock = new FakeClock();
        const { tracker, capture } = makeTracker(clock, { timeoutMs: 1_000 });
        tracker.begin("Preview#fast", ":m", ["a11y/atf"]);
        clock.advance(500);
        tracker.resolve("Preview#fast", ["a11y/atf"]);
        clock.advance(2_000);
        assert.strictEqual(capture.timeouts.length, 0);
    });

    it("aggregates kinds and preview counts in the status summary", () => {
        const clock = new FakeClock();
        const { tracker, capture } = makeTracker(clock);
        tracker.begin("Preview#one", ":m", ["a11y/atf"]);
        tracker.begin("Preview#two", ":m", ["compose/recomposition"]);
        const last = capture.statuses.at(-1)!;
        assert.strictEqual(last.pendingPreviewCount, 2);
        assert.deepStrictEqual(last.kinds, [
            "a11y/atf",
            "compose/recomposition",
        ]);
    });

    it("resolve is a no-op when the previewId is unknown", () => {
        const clock = new FakeClock();
        const { tracker, capture } = makeTracker(clock);
        tracker.resolve("Preview#never-began", ["a11y/atf"]);
        assert.strictEqual(tracker.size, 0);
        assert.strictEqual(capture.clears, 0);
    });

    it("snapshot exposes pending entries for diagnostic dumps", () => {
        const clock = new FakeClock();
        const { tracker } = makeTracker(clock);
        tracker.begin("Preview#one", ":samples:android", [
            "a11y/atf",
            "a11y/hierarchy",
        ]);
        clock.advance(750);
        const snap = tracker.snapshot();
        assert.strictEqual(snap.length, 1);
        assert.strictEqual(snap[0].previewId, "Preview#one");
        assert.strictEqual(snap[0].moduleId, ":samples:android");
        assert.deepStrictEqual(snap[0].kinds, ["a11y/atf", "a11y/hierarchy"]);
        assert.ok(snap[0].elapsedMs >= 750);
    });
});

describe("formatLabel", () => {
    it("uses the kind tail and pluralises for multi-preview activity", () => {
        assert.strictEqual(formatLabel(["a11y/atf"], 1), "Loading atf");
        assert.strictEqual(
            formatLabel(["a11y/atf", "a11y/hierarchy"], 3),
            "Loading atf, hierarchy (3 previews)",
        );
    });

    it("truncates long kind lists with a +N more suffix", () => {
        assert.strictEqual(
            formatLabel(["a/one", "b/two", "c/three", "d/four"], 1),
            "Loading one, two, +2 more",
        );
    });
});
