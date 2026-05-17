// Tracks the in-flight window between "user enables a data extension in the
// focus inspector" and "the daemon attaches the first matching data product
// on a render." Without a visible indicator, non-a11y extension toggles
// (recomposition, AI trace, display filters) appear inert until the next save
// triggers a refresh. The tracker drives:
//
//   - the slim progress bar at the top of the preview panel (an indeterminate
//     ease-out curve while at least one kind is pending — only painted when no
//     real refresh is driving the bar, since refresh progress carries richer
//     phase information),
//   - a VS Code status-bar item summarising activity across all panels, and
//   - a safety timeout per preview that, when it expires, fires a diagnostic
//     callback so the extension can dump daemon state into the output channel.
//
// All timer / clock dependencies are injected so the unit tests can drive the
// tracker deterministically (see `test/dataExtensionProgress.test.ts`).

export interface ProgressPost {
    label: string;
    percent: number;
}

export interface StatusSummary {
    pendingPreviewCount: number;
    pendingKindCount: number;
    /** Distinct kinds across all pending entries (sorted, deduplicated). */
    kinds: readonly string[];
}

export interface DataExtensionPendingDiagnostics {
    previewId: string;
    moduleId: string;
    kinds: readonly string[];
    elapsedMs: number;
}

export interface DataExtensionTrackerScheduler {
    setTimeout: (cb: () => void, ms: number) => unknown;
    clearTimeout: (handle: unknown) => void;
    setInterval: (cb: () => void, ms: number) => unknown;
    clearInterval: (handle: unknown) => void;
}

export interface DataExtensionTrackerOptions {
    /** Safety window before we declare the subscribe stuck. Default 30_000 ms. */
    timeoutMs?: number;
    /** Ease-out span for the indeterminate progress. Default 6_000 ms. */
    easeMs?: number;
    /** Tick interval for the progress curve. Default 250 ms. */
    tickIntervalMs?: number;
    /** Called when the panel slim progress bar should update. The caller is
     *  responsible for suppressing the post when a real refresh is mid-flight. */
    onProgress: (post: ProgressPost) => void;
    /** Called when the panel slim progress bar should clear (no pending work). */
    onClear: () => void;
    /** Called whenever the pending set changes. `null` means no active work. */
    onStatus: (summary: StatusSummary | null) => void;
    /** Fired exactly once per pending entry when its timeout expires. The
     *  caller is responsible for assembling a richer diagnostic dump (daemon
     *  state, capability snapshot, etc.). The tracker still clears the entry
     *  after firing so a stuck subscribe doesn't pin the UI forever. */
    onTimeout: (diag: DataExtensionPendingDiagnostics) => void;
    now?: () => number;
    scheduler?: DataExtensionTrackerScheduler;
}

interface PendingEntry {
    previewId: string;
    moduleId: string;
    kinds: Set<string>;
    startedAt: number;
    timeout: unknown | null;
}

const DEFAULT_TIMEOUT_MS = 30_000;
const DEFAULT_EASE_MS = 6_000;
const DEFAULT_TICK_INTERVAL_MS = 250;
const START_PERCENT = 0.08;
const MAX_PERCENT = 0.88;

const defaultScheduler: DataExtensionTrackerScheduler = {
    setTimeout: (cb, ms) => setTimeout(cb, ms),
    clearTimeout: (h) => clearTimeout(h as ReturnType<typeof setTimeout>),
    setInterval: (cb, ms) => setInterval(cb, ms),
    clearInterval: (h) => clearInterval(h as ReturnType<typeof setInterval>),
};

export class DataExtensionProgressTracker {
    private readonly pending = new Map<string, PendingEntry>();
    private ticker: unknown | null = null;
    private readonly timeoutMs: number;
    private readonly easeMs: number;
    private readonly tickIntervalMs: number;
    private readonly now: () => number;
    private readonly scheduler: DataExtensionTrackerScheduler;
    private disposed = false;

    constructor(private readonly opts: DataExtensionTrackerOptions) {
        this.timeoutMs = opts.timeoutMs ?? DEFAULT_TIMEOUT_MS;
        this.easeMs = opts.easeMs ?? DEFAULT_EASE_MS;
        this.tickIntervalMs = opts.tickIntervalMs ?? DEFAULT_TICK_INTERVAL_MS;
        this.now = opts.now ?? Date.now;
        this.scheduler = opts.scheduler ?? defaultScheduler;
    }

    begin(previewId: string, moduleId: string, kinds: readonly string[]): void {
        if (this.disposed) return;
        const additions = kinds.filter((k) => k.length > 0);
        if (additions.length === 0) return;
        let entry = this.pending.get(previewId);
        if (!entry) {
            entry = {
                previewId,
                moduleId,
                kinds: new Set(additions),
                startedAt: this.now(),
                timeout: null,
            };
            this.pending.set(previewId, entry);
        } else {
            for (const k of additions) entry.kinds.add(k);
            // Reset the elapsed clock when fresh kinds are added so a slow
            // second subscribe doesn't ride the first one's already-running
            // timeout. Keeps the safety window honest per chip-toggle batch.
            entry.startedAt = this.now();
        }
        this.armTimeout(entry);
        this.startTicker();
        this.emitStatus();
        this.emitProgress();
    }

    resolve(previewId: string, kinds: readonly string[]): void {
        if (this.disposed) return;
        const entry = this.pending.get(previewId);
        if (!entry) return;
        for (const k of kinds) entry.kinds.delete(k);
        if (entry.kinds.size === 0) {
            this.dropEntry(previewId);
        } else {
            this.emitStatus();
        }
    }

    clearAll(): void {
        if (this.disposed) return;
        const ids = [...this.pending.keys()];
        for (const id of ids) this.dropEntry(id, { silent: true });
        this.stopTicker();
        this.opts.onStatus(null);
        this.opts.onClear();
    }

    snapshot(): readonly DataExtensionPendingDiagnostics[] {
        const at = this.now();
        const out: DataExtensionPendingDiagnostics[] = [];
        for (const e of this.pending.values()) {
            out.push({
                previewId: e.previewId,
                moduleId: e.moduleId,
                kinds: [...e.kinds].sort(),
                elapsedMs: at - e.startedAt,
            });
        }
        return out;
    }

    get size(): number {
        return this.pending.size;
    }

    dispose(): void {
        if (this.disposed) return;
        this.disposed = true;
        this.stopTicker();
        for (const e of this.pending.values()) {
            if (e.timeout) this.scheduler.clearTimeout(e.timeout);
        }
        this.pending.clear();
    }

    private armTimeout(entry: PendingEntry): void {
        if (entry.timeout) this.scheduler.clearTimeout(entry.timeout);
        entry.timeout = this.scheduler.setTimeout(() => {
            const live = this.pending.get(entry.previewId);
            if (!live || live !== entry) return;
            const diag: DataExtensionPendingDiagnostics = {
                previewId: entry.previewId,
                moduleId: entry.moduleId,
                kinds: [...entry.kinds].sort(),
                elapsedMs: this.now() - entry.startedAt,
            };
            // Drop the entry first so the diagnostic callback sees a clean
            // tracker state (snapshot() during onTimeout won't include the
            // expiring entry — its data is in `diag` already).
            this.dropEntry(entry.previewId, { silent: true });
            this.opts.onTimeout(diag);
            // Re-emit status / clear AFTER onTimeout so the callback can read
            // tracker.size === 0 reliably if it wants to.
            this.emitStatus();
            if (this.pending.size === 0) {
                this.stopTicker();
                this.opts.onClear();
            }
        }, this.timeoutMs);
    }

    private dropEntry(previewId: string, opts?: { silent?: boolean }): void {
        const entry = this.pending.get(previewId);
        if (!entry) return;
        if (entry.timeout) this.scheduler.clearTimeout(entry.timeout);
        this.pending.delete(previewId);
        if (opts?.silent) return;
        if (this.pending.size === 0) {
            this.stopTicker();
            this.opts.onClear();
            this.opts.onStatus(null);
        } else {
            this.emitStatus();
        }
    }

    private startTicker(): void {
        if (this.ticker !== null) return;
        this.ticker = this.scheduler.setInterval(
            () => this.emitProgress(),
            this.tickIntervalMs,
        );
    }

    private stopTicker(): void {
        if (this.ticker === null) return;
        this.scheduler.clearInterval(this.ticker);
        this.ticker = null;
    }

    private emitProgress(): void {
        if (this.pending.size === 0) return;
        // Track the entry that's been waiting the longest — that's the one
        // the user is most likely watching, and its elapsed time is the
        // honest signal for the bar's fill.
        let oldestAt = Number.POSITIVE_INFINITY;
        let oldest: PendingEntry | null = null;
        const allKinds = new Set<string>();
        for (const e of this.pending.values()) {
            if (e.startedAt < oldestAt) {
                oldestAt = e.startedAt;
                oldest = e;
            }
            for (const k of e.kinds) allKinds.add(k);
        }
        if (!oldest) return;
        const elapsed = Math.max(0, this.now() - oldestAt);
        const ratio = 1 - Math.exp(-elapsed / this.easeMs);
        const percent = clamp01(
            START_PERCENT + (MAX_PERCENT - START_PERCENT) * ratio,
        );
        const kinds = [...allKinds].sort();
        const label = formatLabel(kinds, this.pending.size);
        this.opts.onProgress({ label, percent });
    }

    private emitStatus(): void {
        if (this.pending.size === 0) {
            this.opts.onStatus(null);
            return;
        }
        const allKinds = new Set<string>();
        for (const e of this.pending.values()) {
            for (const k of e.kinds) allKinds.add(k);
        }
        const kinds = [...allKinds].sort();
        this.opts.onStatus({
            pendingPreviewCount: this.pending.size,
            pendingKindCount: kinds.length,
            kinds,
        });
    }
}

export function formatLabel(
    kinds: readonly string[],
    previewCount: number,
): string {
    const display = kinds.slice(0, 2).map(displayKind);
    if (kinds.length > 2) display.push(`+${kinds.length - 2} more`);
    const subject = display.join(", ") || "data extension";
    if (previewCount > 1) {
        return `Loading ${subject} (${previewCount} previews)`;
    }
    return `Loading ${subject}`;
}

function displayKind(kind: string): string {
    // Kinds use `category/name` slashes; the short tail is more recognisable
    // in a compact label than the full `a11y/atf` token. Keeps "a11y" out of
    // a label like "Loading atf, hierarchy" still useful because the chip
    // group above the panel already shows the family.
    const slash = kind.indexOf("/");
    return slash >= 0 ? kind.slice(slash + 1) : kind;
}

function clamp01(n: number): number {
    return Math.max(0, Math.min(1, n));
}
