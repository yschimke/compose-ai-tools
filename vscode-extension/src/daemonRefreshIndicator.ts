/** What the indicator needs from the panel, so tests don't need a webview. */
export interface DaemonRefreshEffects {
    /** Post `setProgress` to the panel. */
    setProgress(label: string, percent: number, phase: string): void;
    /** Post `clearProgress` to the panel. */
    clearProgress(): void;
    setInterval(handler: () => void, ms: number): unknown;
    clearInterval(handle: unknown): void;
    setTimeout(handler: () => void, ms: number): unknown;
    clearTimeout(handle: unknown): void;
}

/** Crawl tick period, ms. */
const TICK_MS = 250;
/** Asymptote the crawl approaches but never reaches. */
const CRAWL_CEILING = 0.9;
/** Fraction of the remaining distance covered per tick. */
const CRAWL_RATE = 0.12;
/** Where the bar starts, so it is visibly non-zero immediately. */
const INITIAL_PCT = 0.08;

/**
 * Hard ceiling on the crawl, so a render that never posts an image (daemon
 * reported `unchanged`, stub path, or zero resolved previews) still resolves
 * the strip instead of crawling forever.
 */
export const DAEMON_REFRESH_SAFETY_MS = 12000;

/**
 * Top progress-strip driver for the daemon save path, as one object.
 *
 * The Gradle refresh path drives the webview `<progress-bar>` through
 * `refresh()`'s `tracker` (it parses Gradle stdout for phase/percent). The
 * daemon save path — compile-only + `daemonScheduler.renderNow` — never went
 * through that tracker, so saves in the default `full` mode produced no top-bar
 * feedback at all: "BUILD SUCCESSFUL" in the output channel and nothing in the
 * panel. This lightweight driver posts the same `setProgress` / `clearProgress`
 * messages so a save shows "Refreshing previews…" until the first rendered
 * image lands, the render fails, or the safety timeout fires.
 *
 * It is a class rather than five module-level `let`s because that is what it
 * always was: `daemonRefreshActive` / `daemonRefreshLabel` / `daemonRefreshPct`
 * / `daemonRefreshTick` / `daemonRefreshSafety` were one state machine spread
 * across five mutable globals in `extension.ts`, with five free functions
 * reaching into them. `docs/AGENTS.md` § "State seams" already makes this point
 * about the file — and notes that `RefreshQueue` replaced the earlier
 * `firstSaveSeen` / `pendingSavePath` / `debounceElapsed` / `refreshInFlight`
 * quartet for the same reason. This quintet was declared twenty lines below
 * that note.
 *
 * Effects are injected so the timing behaviour is testable without a webview or
 * real timers.
 */
export class DaemonRefreshIndicator {
    private active = false;
    private label = "";
    private pct = 0;
    private tick: unknown = null;
    private safety: unknown = null;

    constructor(private readonly effects: DaemonRefreshEffects) {}

    /** True while the strip is being driven. */
    get isActive(): boolean {
        return this.active;
    }

    /** Start (or relabel) the strip for an in-flight daemon save. */
    begin(label: string): void {
        this.label = label;
        if (this.active) {
            // Already crawling (compile → render relabel): keep the current
            // fill, just update the caption so the bar doesn't jump backwards.
            this.post(this.pct, "rendering");
            return;
        }
        this.active = true;
        this.pct = INITIAL_PCT;
        this.post(this.pct, "rendering");
        this.tick = this.effects.setInterval(() => {
            // Asymptotic crawl toward CRAWL_CEILING — always visibly alive,
            // never claims completion before the image actually arrives.
            this.pct += (CRAWL_CEILING - this.pct) * CRAWL_RATE;
            this.post(this.pct, "rendering");
        }, TICK_MS);
        // NB: the safety ceiling is armed separately (`armSafety`), only once
        // the render is in flight — see that method for why.
    }

    /**
     * Arm (or re-arm) the hard ceiling that resolves the strip if the daemon
     * posts no image.
     *
     * Deliberately NOT armed by {@link begin}: the compile phase that precedes
     * the render is unbounded (a cold daemon or large module can take most of
     * the budget), and arming the timer there would flash the bar to done
     * mid-compile — losing the feedback this exists to provide. Called from the
     * save path once the render is queued, so the full budget covers the
     * post-render image wait.
     */
    armSafety(): void {
        if (!this.active) return;
        if (this.safety !== null) this.effects.clearTimeout(this.safety);
        this.safety = this.effects.setTimeout(
            () => this.finish(),
            DAEMON_REFRESH_SAFETY_MS,
        );
    }

    /**
     * Drive the strip to 100% (the `<progress-bar>` holds the completed state
     * briefly, then resets to idle). Called when the first rendered image lands.
     */
    finish(): void {
        if (!this.active) return;
        this.active = false;
        this.clearTimers();
        this.pct = 1;
        this.post(1, "done");
    }

    /**
     * Tear the strip down without a completion flash. Used on the failure /
     * fall-through-to-Gradle paths, where either an error banner takes over or
     * the Gradle `tracker` is about to drive the same bar.
     */
    cancel(): void {
        if (!this.active) return;
        this.active = false;
        this.clearTimers();
        this.effects.clearProgress();
    }

    /**
     * Release timers on extension deactivate. Deliberately silent — the panel
     * is going away, so posting a completion or clear would be a message into
     * the void. Mirrors the old free-standing `clearDaemonRefreshTimers()`,
     * which `deactivate()` called for exactly this reason.
     */
    dispose(): void {
        this.active = false;
        this.clearTimers();
    }

    private clearTimers(): void {
        if (this.tick !== null) {
            this.effects.clearInterval(this.tick);
            this.tick = null;
        }
        if (this.safety !== null) {
            this.effects.clearTimeout(this.safety);
            this.safety = null;
        }
    }

    private post(percent: number, phase: string): void {
        this.effects.setProgress(this.label, percent, phase);
    }
}
