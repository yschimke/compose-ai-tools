import * as assert from "assert";
import {
    DAEMON_REFRESH_SAFETY_MS,
    DaemonRefreshIndicator,
    DaemonRefreshEffects,
} from "../daemonRefreshIndicator";

interface Posted {
    label: string;
    percent: number;
    phase: string;
}

/**
 * Deterministic effects double: records posts and drives virtual timers, so the
 * crawl and the safety ceiling are asserted synchronously rather than by
 * sleeping. Same approach as `refreshQueue.test.ts`'s FakeClock.
 */
class FakeEffects implements DaemonRefreshEffects {
    readonly posts: Posted[] = [];
    cleared = 0;
    private nextHandle = 1;
    private intervals = new Map<number, { ms: number; cb: () => void }>();
    private timeouts = new Map<number, { dueIn: number; cb: () => void }>();

    setProgress(label: string, percent: number, phase: string): void {
        this.posts.push({ label, percent, phase });
    }
    clearProgress(): void {
        this.cleared += 1;
    }
    setInterval(cb: () => void, ms: number): unknown {
        const h = this.nextHandle++;
        this.intervals.set(h, { ms, cb });
        return h;
    }
    clearInterval(handle: unknown): void {
        this.intervals.delete(handle as number);
    }
    setTimeout(cb: () => void, ms: number): unknown {
        const h = this.nextHandle++;
        this.timeouts.set(h, { dueIn: ms, cb });
        return h;
    }
    clearTimeout(handle: unknown): void {
        this.timeouts.delete(handle as number);
    }

    /** Fire every live interval [times] times. */
    tick(times = 1): void {
        for (let i = 0; i < times; i++) {
            for (const { cb } of [...this.intervals.values()]) cb();
        }
    }
    /** Advance virtual time, firing any timeout that comes due. */
    advance(ms: number): void {
        for (const [h, t] of [...this.timeouts.entries()]) {
            t.dueIn -= ms;
            if (t.dueIn <= 0) {
                this.timeouts.delete(h);
                t.cb();
            }
        }
    }
    get liveIntervals(): number {
        return this.intervals.size;
    }
    get liveTimeouts(): number {
        return this.timeouts.size;
    }
    get last(): Posted | undefined {
        return this.posts[this.posts.length - 1];
    }
}

describe("DaemonRefreshIndicator", () => {
    it("posts a visible non-zero bar immediately on begin", () => {
        const fx = new FakeEffects();
        new DaemonRefreshIndicator(fx).begin("Refreshing previews…");
        assert.strictEqual(fx.posts.length, 1);
        assert.strictEqual(fx.last?.label, "Refreshing previews…");
        assert.ok(
            (fx.last?.percent ?? 0) > 0,
            "bar should start visibly non-zero",
        );
    });

    it("crawls toward but never reaches the ceiling", () => {
        // It must never claim completion before the image actually arrives.
        const fx = new FakeEffects();
        new DaemonRefreshIndicator(fx).begin("x");
        fx.tick(200);
        const pct = fx.last?.percent ?? 0;
        assert.ok(pct > 0.5, `expected meaningful progress, got ${pct}`);
        assert.ok(pct < 0.9, `crawl must stay under 0.9, got ${pct}`);
    });

    it("relabels without rewinding the bar", () => {
        // The compile → render relabel: caption changes, fill does not jump back.
        const fx = new FakeEffects();
        const ind = new DaemonRefreshIndicator(fx);
        ind.begin("Compiling…");
        fx.tick(10);
        const before = fx.last?.percent ?? 0;
        ind.begin("Rendering…");
        assert.strictEqual(fx.last?.label, "Rendering…");
        assert.strictEqual(
            fx.last?.percent,
            before,
            "relabel rewound the progress bar",
        );
    });

    it("does not start a second crawl on relabel", () => {
        const fx = new FakeEffects();
        const ind = new DaemonRefreshIndicator(fx);
        ind.begin("a");
        ind.begin("b");
        assert.strictEqual(fx.liveIntervals, 1);
    });

    it("does not arm the safety ceiling on begin", () => {
        // Deliberate: the compile phase before the render is unbounded, and
        // arming here would flash the bar to done mid-compile.
        const fx = new FakeEffects();
        new DaemonRefreshIndicator(fx).begin("x");
        assert.strictEqual(fx.liveTimeouts, 0);
    });

    it("resolves the strip when the safety ceiling fires", () => {
        // Covers the daemon `unchanged` / stub / zero-preview cases, where no
        // image is ever posted and the crawl would otherwise never end.
        const fx = new FakeEffects();
        const ind = new DaemonRefreshIndicator(fx);
        ind.begin("x");
        ind.armSafety();
        fx.advance(DAEMON_REFRESH_SAFETY_MS);
        assert.strictEqual(fx.last?.phase, "done");
        assert.strictEqual(fx.last?.percent, 1);
        assert.strictEqual(ind.isActive, false);
    });

    it("re-arming the safety replaces the pending timer", () => {
        const fx = new FakeEffects();
        const ind = new DaemonRefreshIndicator(fx);
        ind.begin("x");
        ind.armSafety();
        ind.armSafety();
        assert.strictEqual(fx.liveTimeouts, 1);
    });

    it("armSafety is inert when not active", () => {
        const fx = new FakeEffects();
        new DaemonRefreshIndicator(fx).armSafety();
        assert.strictEqual(fx.liveTimeouts, 0);
    });

    it("finish drives to 100% and stops every timer", () => {
        const fx = new FakeEffects();
        const ind = new DaemonRefreshIndicator(fx);
        ind.begin("x");
        ind.armSafety();
        ind.finish();
        assert.strictEqual(fx.last?.phase, "done");
        assert.strictEqual(fx.last?.percent, 1);
        assert.strictEqual(fx.liveIntervals, 0);
        assert.strictEqual(fx.liveTimeouts, 0);
    });

    it("cancel tears down with no completion flash", () => {
        // Failure / fall-through-to-Gradle: an error banner or the Gradle
        // tracker takes over the same bar, so a 100% flash would be a lie.
        const fx = new FakeEffects();
        const ind = new DaemonRefreshIndicator(fx);
        ind.begin("x");
        const postsBefore = fx.posts.length;
        ind.cancel();
        assert.strictEqual(
            fx.posts.length,
            postsBefore,
            "cancel posted progress",
        );
        assert.strictEqual(fx.cleared, 1);
        assert.strictEqual(fx.liveIntervals, 0);
    });

    it("finish and cancel are idempotent when idle", () => {
        const fx = new FakeEffects();
        const ind = new DaemonRefreshIndicator(fx);
        ind.finish();
        ind.cancel();
        assert.strictEqual(fx.posts.length, 0);
        assert.strictEqual(fx.cleared, 0);
    });

    it("a stale crawl tick cannot post after finish", () => {
        // The bug the old five-global version could hit: timers outliving the
        // state they were driving.
        const fx = new FakeEffects();
        const ind = new DaemonRefreshIndicator(fx);
        ind.begin("x");
        ind.finish();
        const after = fx.posts.length;
        fx.tick(5);
        assert.strictEqual(fx.posts.length, after);
    });

    it("dispose releases timers silently", () => {
        // deactivate(): the panel is going away, so posting would be a message
        // into the void.
        const fx = new FakeEffects();
        const ind = new DaemonRefreshIndicator(fx);
        ind.begin("x");
        ind.armSafety();
        const posts = fx.posts.length;
        ind.dispose();
        assert.strictEqual(fx.liveIntervals, 0);
        assert.strictEqual(fx.liveTimeouts, 0);
        assert.strictEqual(fx.posts.length, posts);
        assert.strictEqual(fx.cleared, 0);
    });
});
