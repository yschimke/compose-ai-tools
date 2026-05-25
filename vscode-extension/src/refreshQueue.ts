/**
 * Save-driven refresh coalescing as an explicit state machine.
 *
 * State:
 *   { inFlight, pendingTarget, debounceElapsed, seenFiles }
 *
 * Events:
 *   - dispatchSave(target, opts) — caller observed a save/watcher event
 *   - DEBOUNCE_ELAPSED (internal, fired from the injected timer)
 *   - REFRESH_COMPLETED (internal, fired from runRefresh resolution)
 *
 * Fire rule (single point):
 *   pendingTarget !== null && debounceElapsed && !inFlight  →  run pending
 *
 * Save dispatch rule:
 *   allowImmediate && firstSeen && !inFlight && pendingTarget === null
 *     → mark seen, invalidate cache, run immediately
 *   otherwise
 *     → if allowImmediate: mark seen
 *     → set pendingTarget, invalidate cache, restart debounce timer
 *
 * Timer and refresh runner are injected so unit tests can drive transitions
 * with a fake clock. Default effects use the global timers and accept any
 * Promise-returning runRefresh.
 */

export interface RefreshQueueTimer {}

export interface RefreshQueueEffects {
    /** Kick off the actual refresh work. Resolves when done (success or failure).
     *  Errors are swallowed by the queue — callers handle their own UX. */
    runRefresh: (target: string) => Promise<void>;
    /** Drop any cached module info for `target`. Called once at dispatch time
     *  so the subsequent runRefresh resolves modules from fresh disk state. */
    invalidateModuleCache: (target: string) => void;
    /** Schedule `cb` to fire after `ms`. Returns an opaque handle for cancel. */
    setTimeout: (cb: () => void, ms: number) => RefreshQueueTimer;
    /** Cancel a pending timer. Safe to call on an already-fired handle. */
    clearTimeout: (timer: RefreshQueueTimer) => void;
}

export interface SaveDispatchOptions {
    /** When true, eligible saves take the first-seen fast path (immediate
     *  refresh, no debounce wait). Onsave events from VS Code's
     *  `onDidSaveTextDocument` set this to true; file-system watchers and
     *  programmatic re-enqueues set it to false so external file mutations
     *  (git checkout, refactor tools) never bypass debounce. */
    allowImmediate: boolean;
    /** Debounce duration applied if this save needs to be queued. */
    debounceMs: number;
}

export interface RefreshQueueSnapshot {
    inFlight: boolean;
    pendingTarget: string | null;
    debounceElapsed: boolean;
    seenFiles: ReadonlySet<string>;
}

export class RefreshQueue {
    private inFlight = false;
    private pendingTarget: string | null = null;
    private debounceElapsed = true;
    private debounceTimer: RefreshQueueTimer | null = null;
    private readonly seenFiles = new Set<string>();

    constructor(private readonly effects: RefreshQueueEffects) {}

    /** Single entry point for save events from VS Code and file watchers. */
    dispatchSave(target: string, opts: SaveDispatchOptions): void {
        const firstSeen = !this.seenFiles.has(target);
        const idle = !this.inFlight && this.pendingTarget === null;
        if (opts.allowImmediate && firstSeen && idle) {
            this.seenFiles.add(target);
            this.effects.invalidateModuleCache(target);
            this.startRefresh(target);
            return;
        }
        if (opts.allowImmediate) {
            this.seenFiles.add(target);
        }
        this.scheduleDebounce(target, opts.debounceMs);
    }

    /** Mark a file as seen without scheduling work. Used by minimal-mode
     *  save handlers that bypass the queue but should still affect a later
     *  first-seen decision once the user leaves minimal mode. */
    markSeen(target: string): void {
        this.seenFiles.add(target);
    }

    isInFlight(): boolean {
        return this.inFlight;
    }

    snapshot(): RefreshQueueSnapshot {
        return {
            inFlight: this.inFlight,
            pendingTarget: this.pendingTarget,
            debounceElapsed: this.debounceElapsed,
            seenFiles: this.seenFiles,
        };
    }

    /** Cancel the debounce timer. Caller responsibility on shutdown. Does
     *  not touch inFlight — an already-running refresh runs to completion. */
    dispose(): void {
        if (this.debounceTimer !== null) {
            this.effects.clearTimeout(this.debounceTimer);
            this.debounceTimer = null;
        }
    }

    private scheduleDebounce(target: string, debounceMs: number): void {
        this.pendingTarget = target;
        this.effects.invalidateModuleCache(target);
        this.debounceElapsed = false;
        if (this.debounceTimer !== null) {
            this.effects.clearTimeout(this.debounceTimer);
        }
        this.debounceTimer = this.effects.setTimeout(() => {
            this.debounceTimer = null;
            this.debounceElapsed = true;
            this.tryFire();
        }, debounceMs);
    }

    private tryFire(): void {
        if (
            this.inFlight ||
            !this.debounceElapsed ||
            this.pendingTarget === null
        ) {
            return;
        }
        const target = this.pendingTarget;
        this.pendingTarget = null;
        this.startRefresh(target);
    }

    private startRefresh(target: string): void {
        this.inFlight = true;
        void this.effects.runRefresh(target).then(
            () => this.onRefreshSettled(),
            () => this.onRefreshSettled(),
        );
    }

    private onRefreshSettled(): void {
        this.inFlight = false;
        this.tryFire();
    }
}

/** Default effects backed by Node/VS Code globals. Production callers use
 *  this; tests inject a fake-clock variant. */
export function defaultRefreshQueueEffects(
    runRefresh: (target: string) => Promise<void>,
    invalidateModuleCache: (target: string) => void,
): RefreshQueueEffects {
    return {
        runRefresh,
        invalidateModuleCache,
        setTimeout: (cb, ms) => setTimeout(cb, ms),
        clearTimeout: (timer) => clearTimeout(timer as NodeJS.Timeout),
    };
}
