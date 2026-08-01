/**
 * Phase machine for one file-transition. The orchestrator drives the panel through
 * `preload → discover → reconcile → render → warm → idle`, owning the cancellation
 * token + phase observability so future race-condition bugs become assertable rather
 * than reproducible only by clicking.
 *
 * Two responsibilities the host's prior inline sequence conflated:
 *
 *   - Composition of the steps (which had drifted: focus-change happy path did all
 *     four, fallback paths skipped preload). `RefreshOrchestrator.transitionToFile`
 *     is the single entry point host wiring routes every transition through.
 *   - Observability of where the in-flight transition is RIGHT NOW. Callers can
 *     subscribe via {@link RefreshOrchestrator.onPhaseChange} and (in tests) assert
 *     "while in phase=warm for file A, switching to file B starts B's preload
 *     immediately" without introducing fake timers or polling.
 *
 * Cancellation model: each call to {@link RefreshOrchestrator.transitionToFile}
 * immediately aborts the previous transition's pending refresh via
 * `abortPendingRefresh` (the dep that binds the host's `pendingRefresh`
 * AbortController). The previous transition's already-resolved phases stand;
 * in-flight work that respects the abort signal unwinds. Warm is fire-and-forget
 * by design — the daemon JVM spawn doesn't cancel — so an A→B transition during
 * A's warm phase leaves A's warm running in the background while the orchestrator's
 * public phase flips to B's preload. Tests assert this behaviour directly.
 */
export type RefreshPhase =
    "idle" | "preload" | "discover" | "reconcile" | "render" | "warm";

/**
 * Sub-phase callback the orchestrator threads into `refresh()` so the monolithic
 * Gradle pipeline can report its three internal milestones. Today's `refresh` is one
 * big function but it already has clear boundaries — after `composePreviewDiscover`
 * resolves, after the `setPreviews` post lands, and after `Promise.all(imageJobs)`
 * completes. Hooking those into a callback keeps the orchestrator honest about the
 * 5-phase contract without decomposing `refresh` itself.
 */
export type RefreshSubPhase = "discover" | "reconcile" | "render";

export interface RefreshOrchestratorDeps {
    /** Aborts the previous transition's in-flight refresh and clears the bookkeeping
     *  (host-side: `pendingRefresh?.abort()` + `pendingRefreshKey = null`). Idempotent;
     *  safe to call when nothing is in flight. Fires before preload so a stale
     *  refresh continuation can't resume on top of the new file's painted state. */
    abortPendingRefresh: () => void;
    /** True iff preload reached the painted outcome. */
    preloadCachedPreviews: (filePath: string) => Promise<boolean>;
    /** The monolithic Gradle pipeline. Accepts an `onPhase` callback that fires at the
     *  three discover/reconcile/render milestones so the orchestrator stays accurate. */
    refresh: (
        forceRender: boolean,
        filePath: string,
        tier: "full" | "fast",
        opts: {
            showLoadingOverlay: boolean;
            onPhase?: (phase: RefreshSubPhase) => void;
        },
    ) => Promise<unknown>;
    /** Daemon JVM warm-up. Fire-and-forget from the orchestrator's perspective — the
     *  daemon spawn is unbounded and a second transition shouldn't block on it. */
    warmDaemonForFile: (
        filePath: string,
        opts: { refreshAfterReady: boolean },
    ) => Promise<unknown>;
}

export interface PhaseChange {
    phase: RefreshPhase;
    filePath: string | null;
}

export interface Subscription {
    dispose(): void;
}

/**
 * One orchestrator instance per host. Re-entrant: a new {@link transitionToFile}
 * call while one is already running aborts the previous via `abortPendingRefresh`
 * and starts fresh. The previous call's outstanding `await` chain unwinds naturally
 * once the aborted refresh resolves; its phase events stop firing because the
 * orchestrator's `currentTransitionId` no longer matches.
 */
export class RefreshOrchestrator {
    private _phase: RefreshPhase = "idle";
    private _filePath: string | null = null;
    /** Monotonic transition id. Each `transitionToFile` call increments and captures it;
     *  setPhase ignores events whose id doesn't match, so a slow phase callback from an
     *  aborted transition can't paint the wrong phase onto the orchestrator's public state. */
    private currentTransitionId = 0;
    private readonly listeners: Array<(change: PhaseChange) => void> = [];

    constructor(private readonly deps: RefreshOrchestratorDeps) {}

    get phase(): RefreshPhase {
        return this._phase;
    }

    get currentFile(): string | null {
        return this._filePath;
    }

    /**
     * Subscribe to phase changes for the in-flight transition. Aborted transitions
     * stop emitting; only the most recent transitionToFile call drives observable
     * state. The returned `Subscription` should be `dispose`d when the consumer
     * unmounts (panel reload, extension deactivate, test teardown).
     */
    onPhaseChange(handler: (change: PhaseChange) => void): Subscription {
        this.listeners.push(handler);
        return {
            dispose: () => {
                const i = this.listeners.indexOf(handler);
                if (i >= 0) this.listeners.splice(i, 1);
            },
        };
    }

    async transitionToFile(filePath: string): Promise<void> {
        const id = ++this.currentTransitionId;
        this.deps.abortPendingRefresh();
        this.setPhase("preload", filePath, id);
        const preloaded = await this.deps.preloadCachedPreviews(filePath);
        if (id !== this.currentTransitionId) return;
        await this.deps.refresh(false, filePath, "full", {
            showLoadingOverlay: !preloaded,
            onPhase: (sub) => {
                // Drop sub-phase events from a transition that's already been
                // superseded — the new transition has already painted its own
                // phase onto the orchestrator's public state.
                if (id !== this.currentTransitionId) return;
                this.setPhase(sub, filePath, id);
            },
        });
        if (id !== this.currentTransitionId) return;
        this.setPhase("warm", filePath, id);
        // Fire-and-forget: daemon spawn is unbounded and a follow-up transition
        // shouldn't block on it. We DO still await here so the orchestrator's
        // `phase=idle` only lands after warm resolves for THIS transition — but
        // a re-entrant call before warm resolves would already have bumped the
        // transition id and replaced the public phase.
        await this.deps.warmDaemonForFile(filePath, {
            refreshAfterReady: true,
        });
        if (id !== this.currentTransitionId) return;
        this.setPhase("idle", null, id);
    }

    private setPhase(
        phase: RefreshPhase,
        filePath: string | null,
        id: number,
    ): void {
        if (id !== this.currentTransitionId) return;
        this._phase = phase;
        this._filePath = filePath;
        const change: PhaseChange = { phase, filePath };
        for (const l of [...this.listeners]) {
            try {
                l(change);
            } catch {
                /* match vscode.EventEmitter swallow */
            }
        }
    }
}
