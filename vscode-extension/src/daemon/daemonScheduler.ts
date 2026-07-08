import * as path from "path";
import * as fs from "fs";
import { GradleService, ModuleInfo } from "../gradleService";
import { DaemonClientEvents } from "./daemonClient";
import { DaemonGate } from "./daemonGate";
import {
    SpeculationCache,
    VisibilityMemo,
    WarmCoalescer,
} from "./schedulerState";
import {
    DataProductAttachment,
    DiscoveryUpdatedParams,
    FileChangeType,
    FileKind,
    HistoryAddedParams,
    HistoryPrunedParams,
    PreviewOverrides,
    RenderFinishedParams,
    RenderTier,
    StreamFrameParams,
} from "./daemonProtocol";

export type WarmProgress = (state: WarmState) => void;
export type WarmState = "bootstrapping" | "spawning" | "ready" | "fallback";

export interface SchedulerEvents {
    /**
     * A render finished and we resolved the on-disk PNG. Wire to the panel
     * so the user's visible card swaps in the new image with sub-second
     * latency on a hot Robolectric sandbox.
     */
    onPreviewImageReady: (
        moduleId: string,
        previewId: string,
        imageBase64: string,
        pngPath: string,
    ) => void;
    /**
     * D2 — `renderFinished` arrived with `dataProducts: [{kind, payload? | path?}]`.
     * Fires once per render, after the PNG has been read; consumers route each kind
     * into the registry / webview / diagnostics surface.
     *
     * Optional because tests may not wire it. Only fires when the daemon attaches
     * data products (i.e. the client subscribed via `dataSubscribe` for one of the
     * `(previewId, kind)` pairs, or attached one globally at `initialize`).
     */
    onDataProductsAttached?: (
        moduleId: string,
        previewId: string,
        dataProducts: DataProductAttachment[],
    ) => void;
    onRenderFailed: (
        moduleId: string,
        previewId: string,
        message: string,
    ) => void;
    /**
     * Daemon told us the classpath drifted (e.g. `libs.versions.toml`
     * bumped). The daemon will exit shortly; the scheduler stops issuing
     * renders for this module and the caller should re-run Gradle.
     */
    onClasspathDirty: (moduleId: string, detail: string) => void;
    /**
     * Daemon's incremental-discovery cascade emitted a non-empty diff —
     * `added`/`removed`/`changed` against its in-memory preview index for
     * this module. The daemon is silent on identity-only saves (empty
     * diff), so receiving this event always means the panel needs to
     * reshape. Optional because tests may not wire it.
     */
    onDiscoveryUpdated?: (
        moduleId: string,
        params: DiscoveryUpdatedParams,
    ) => void;
    /** Phase H2 — daemon archived a render. Forwarded to the History
     *  panel; optional because the panel may not exist in test mode. */
    onHistoryAdded?: (moduleId: string, params: HistoryAddedParams) => void;
    /** Phase H4 — daemon dropped one or more entries from disk. Subscribers
     *  invalidate any cached IDs. Optional for the same reason. */
    onHistoryPruned?: (moduleId: string, params: HistoryPrunedParams) => void;
    /**
     * `composestream/1` — daemon emitted a live frame. The scheduler
     * forwards it untouched; `extension.ts` routes it to the matching
     * webview via `postMessage`. Optional because most daemons / tests
     * never see one (no client subscribed via `stream/start`). See
     * docs/daemon/STREAMING.md.
     */
    onStreamFrame?: (moduleId: string, params: StreamFrameParams) => void;
    /**
     * The daemon's stdio channel closed (process exit, classpath dirty,
     * spawn died). Subscribers use this to drop module-scoped state that
     * doesn't survive a JVM restart — `extension.ts` clears
     * `activeInteractiveStreams` here so a subsequent `interactive/input`
     * doesn't get routed to a stream id the new daemon doesn't know.
     * Optional because tests may not wire it. Fires at most once per
     * daemon lifetime per module.
     */
    onChannelClosed?: (moduleId: string) => void;
}

const HEAVY_TIER_DEFAULT: RenderTier = "fast";

/**
 * Outcome of {@link DaemonScheduler.setDataProductSubscription}. The transition
 * field reports whether the module crossed an a11y first-on / last-off
 * boundary in this call; the panel uses it to gate a follow-up refresh that
 * repaints with the freshly-arrived (or now-absent) data products.
 */
export interface DataProductSubscriptionResult {
    a11yTransition: "enabled" | "disabled" | "unchanged";
}

/** Predicate: does this kind contribute to the module's a11y aggregate? */
function isA11yKind(kind: string): boolean {
    return kind.startsWith("a11y/");
}

/**
 * D2 — kinds the focus-mode "Show a11y overlay" button toggles. Pinned to the a11y producer
 * so the local finding + hierarchy overlays light up together; a future panel that wants to
 * subscribe to other kinds (`compose/recomposition`, `layout/inspector`) will export its own list.
 */
export const A11Y_OVERLAY_KINDS: readonly string[] = [
    "a11y/atf",
    "a11y/hierarchy",
];
/**
 * Cards we'll pre-render ahead of the visible viewport on each scroll-ahead
 * push from the webview. Bounded so a fast scroll past 50 cards doesn't
 * stack 50 speculative renders — see PREDICTIVE.md § 4 (default 4).
 */
const SPECULATIVE_BUDGET = 4;

/**
 * The scheduler role: translate editor events (saves, focus, viewport) into
 * the daemon's protocol notifications, and fan render-completion events back
 * out to the panel via [SchedulerEvents].
 *
 * Two implementations:
 *
 * - `LiveDaemonScheduler` is the production scheduler. Talks to a live
 *   `LiveDaemonGate` and forwards protocol traffic in both directions.
 * - `GradleOnlyDaemonScheduler` is the minimal-mode no-op. All write methods
 *   ignore their arguments and `warmModule` reports `'fallback'`. The events
 *   bag returned by `daemonEvents` is inert; it can still be passed to
 *   `GradleOnlyDaemonGate.getOrSpawn` (which never reads it) so call sites
 *   don't branch on the backend.
 */
export interface DaemonScheduler {
    ensureModule(module: ModuleInfo): Promise<boolean>;
    warmModule(
        gradleService: GradleService,
        module: ModuleInfo,
        progress?: WarmProgress,
    ): Promise<boolean>;
    fileChanged(
        module: ModuleInfo,
        absPath: string,
        changeType?: FileChangeType,
    ): Promise<void>;
    /**
     * Stage-2: send a `compileSources` JSON-RPC request. Returns the daemon's outcome
     * verbatim; null when the daemon isn't reachable (caller should treat that as a
     * fallback). See `compileSourcesInProcess` implementation comment in
     * [LiveDaemonScheduler] for routing semantics.
     */
    compileSourcesInProcess(
        module: ModuleInfo,
        sources: string[],
    ): Promise<import("./daemonProtocol").CompileSourcesResult | null>;
    setFocus(module: ModuleInfo, previewIds: string[]): Promise<void>;
    setVisible(
        module: ModuleInfo,
        visible: string[],
        predicted?: string[],
    ): Promise<void>;
    /**
     * Update one or more `(previewId, kind)` subscriptions for `module`.
     * Returns whether the module's overall a11y enablement crossed a
     * first-on / last-off boundary — callers (the panel) use that to
     * decide whether to kick a follow-up refresh so the next render's
     * data-product set is repainted. Non-a11y kinds never trip the
     * transition; a no-op subscribe / unsubscribe returns `"unchanged"`.
     */
    setDataProductSubscription(
        module: ModuleInfo,
        previewId: string,
        kinds: readonly string[],
        enabled: boolean,
    ): Promise<DataProductSubscriptionResult>;
    /**
     * Resolves once every in-flight `data/subscribe` for [moduleId] has been
     * acknowledged by the daemon (or has settled — failures count as drained).
     * Callers that issue a `renderNow` independently of
     * [setDataProductSubscription] (e.g. the activation-time warm-up render)
     * await this before sending so the daemon's
     * `subscriptionDrivenRenderMode` lock has the panel-side subscriptions
     * recorded by the time the render is dispatched. Without this barrier the
     * first daemon render on a fresh spawn races the panel's chip-driven
     * subscribe posts and the user sees an extension-less first frame.
     */
    awaitPendingSubscribes(moduleId: string): Promise<void>;
    renderNow(
        module: ModuleInfo,
        previewIds: string[],
        tier?: RenderTier,
        reason?: string,
        overrides?: PreviewOverrides,
    ): Promise<boolean>;
    /**
     * Issue #1528 — pulls a single `render/scroll/long` / `render/scroll/gif` artefact through
     * the daemon's `data/fetch` re-render path. `kind` is inferred from the missing entry's
     * `renderOutput` extension (`.png` → `render/scroll/long`, `.gif` → `render/scroll/gif`).
     * Returns the absolute on-disk path the daemon wrote (typically equal to
     * `missing.absolutePath` since the daemon writes to the same `<module>/build/compose-previews/`
     * location Gradle does), or `null` when the daemon isn't ready, doesn't advertise the kind,
     * or the fetch fails. Callers post `updateImage` against the returned path.
     */
    fetchScrollDataProduct(
        module: ModuleInfo,
        missing: {
            previewId: string;
            captureIndex: number;
            renderOutput: string;
            absolutePath: string;
        },
    ): Promise<string | null>;
    daemonEvents(moduleId: string): DaemonClientEvents;
}

/**
 * Drives the daemon end of the editor loop:
 *   - File saves → `fileChanged` notifications (Tier-2 trigger).
 *   - Active editor change → `setFocus` for that file's previews.
 *   - Webview reports visible card IDs and the next-page IDs it predicts
 *     will scroll into view → `setVisible` plus speculative `renderNow`
 *     calls bounded by [SPECULATIVE_BUDGET].
 *   - Daemon `renderFinished` → load PNG, base64, hand to caller.
 *
 * The scheduler does not own the panel state; it speaks only in protocol
 * primitives + image bytes. Wiring sits above in `extension.ts`.
 *
 * **History is intentionally out of scope here.** The user-facing history
 * timeline ships separately and will key off `docs/daemon/HISTORY.md` once
 * that design lands. Wire-up point: the `onPreviewImageReady` callback is
 * the right hook for an additional "snapshot-on-render" sink — it already
 * has the moduleId, previewId, and pngPath that a history archiver needs.
 */
export class LiveDaemonScheduler implements DaemonScheduler {
    /** Per-module `setVisible` / `setFocus` dedup memo. See {@link VisibilityMemo}. */
    private readonly visibility = new VisibilityMemo();
    /**
     * D2 — `(moduleId, previewId)` pairs we've already issued `data/subscribe` for. Subscribed kinds
     * are pinned at [DEFAULT_SUBSCRIBED_KINDS] today; per-kind tracking moves here when the panel
     * grows a runtime "show a11y" toggle. Daemon prunes its side automatically when a previewId
     * leaves `setVisible`, so we only need to dedup re-subscribes.
     */
    private readonly subscribedPairs = new Set<string>();
    /**
     * In-flight `data/subscribe` promises per moduleId. Tracked so an
     * out-of-band caller (the activation warm-up render in
     * `warmShownPreviewsForFile`) can drain them via
     * [awaitPendingSubscribes] before issuing a `renderNow`. Without this
     * drain, the warm-up render races the panel's chip-driven subscribes
     * for the very first preview after a daemon spawn and the daemon's
     * `subscriptionDrivenRenderMode` lock misses the in-flight render —
     * extensions appear empty until the user re-navigates.
     */
    private readonly pendingSubscribes = new Map<
        string,
        Set<Promise<unknown>>
    >();
    /** Per-`(module, previewId)` idempotency cache for speculative renders. See
     *  {@link SpeculationCache}. */
    private readonly speculation = new SpeculationCache();
    /** Modules where we've already logged a "daemon at stub stage" notice.
     *  Stops the per-render ENOENT spam when the daemon (B1.5) emits
     *  synthetic `daemon-stub-N.png` paths that don't exist on disk. */
    private warnedStubModules = new Set<string>();
    /** Coalesces concurrent `warmModule` calls per module. See {@link WarmCoalescer}. */
    private readonly warmCoalescer = new WarmCoalescer<boolean>();

    constructor(
        private readonly gate: DaemonGate,
        private readonly events: SchedulerEvents,
        private readonly logger: { appendLine(s: string): void } = {
            appendLine() {},
        },
    ) {}

    /**
     * Bring up the daemon for a module if necessary and ensure subsequent
     * notifications go to the right client. Returns true iff a client is
     * available (caller can use the daemon path); false means the daemon was explicitly disabled.
     * Enabled-but-broken daemon failures throw so callers do not silently fall back to Gradle.
     */
    async ensureModule(module: ModuleInfo): Promise<boolean> {
        const client = await this.gate.getOrSpawn(
            module,
            this.daemonEvents(module.modulePath),
        );
        return client !== null;
    }

    /**
     * Pre-warms a module: first tries the existing `daemon-launch.json`, then falls back to the
     * Gradle bootstrap task when the cached descriptor is absent/stale/disabled. Intended
     * to be called when the user navigates to a Kotlin file in a
     * daemon-enabled module, so the daemon is alive by the time they hit
     * save — the first save then collapses to "kotlinc + render" instead
     * of "Gradle bootstrap + JVM spawn + sandbox init + render".
     *
     * `progress` fires through `'spawning'` while the JVM comes up, `'bootstrapping'` only when the
     * cached descriptor could not be used, and `'ready'` once `initialize` is acknowledged.
     * `'fallback'` fires only when the daemon is explicitly disabled by the build.
     * Enabled-but-broken daemon failures throw so the UI can surface the error.
     */
    async warmModule(
        gradleService: GradleService,
        module: ModuleInfo,
        progress?: WarmProgress,
    ): Promise<boolean> {
        // Surface "ready" progress to a piggy-backing caller when the underlying daemon is
        // already up — the in-flight promise itself won't re-emit progress events. The
        // coalescer's getOrStart short-circuits to the existing promise; the side effect
        // here just keeps the second caller's UI in sync.
        if (
            this.warmCoalescer.isInFlight(module) &&
            this.gate.isDaemonReady(module.modulePath)
        ) {
            progress?.("ready");
        }
        return this.warmCoalescer.getOrStart(module, () =>
            this.warmModuleInner(gradleService, module, progress),
        );
    }

    private async warmModuleInner(
        gradleService: GradleService,
        module: ModuleInfo,
        progress?: WarmProgress,
    ): Promise<boolean> {
        if (this.gate.isDaemonReady(module.modulePath)) {
            progress?.("ready");
            return true;
        }
        progress?.("spawning");
        try {
            const ok = await this.ensureModule(module);
            if (ok) {
                progress?.("ready");
                return true;
            }
        } catch (err) {
            this.logger.appendLine(
                `[daemon] cached launch failed for ${module.modulePath}: ${(err as Error).message}; ` +
                    "running composePreviewDaemonStart",
            );
        }
        // Bootstrap may race with sibling refreshes that fire
        // `gradleService.cancel(...)`. The cancel pool spares
        // `composePreviewDaemonStart`, but extension shutdown / file-watcher
        // churn / Tooling-API timeouts can still cancel mid-flight. When that
        // happens the bootstrap throws "was cancelled" and the caller would
        // otherwise be stuck without a descriptor until the user manually
        // re-triggered warm. Retry once on cancellation so a transient kill
        // doesn't strand the daemon for the rest of the session.
        for (let attempt = 0; attempt < 2; attempt++) {
            try {
                progress?.("bootstrapping");
                await gradleService.runDaemonBootstrap(module);
                break;
            } catch (err) {
                const message = (err as Error).message ?? String(err);
                const isCancellation = /cancel/i.test(message);
                if (isCancellation && attempt === 0) {
                    this.logger.appendLine(
                        `[daemon] bootstrap cancelled for ${module.modulePath}: ${message}; retrying`,
                    );
                    continue;
                }
                this.logger.appendLine(
                    `[daemon] bootstrap task failed for ${module.modulePath}: ${message}`,
                );
                throw err;
            }
        }
        progress?.("spawning");
        let ok = false;
        try {
            ok = await this.ensureModule(module);
        } catch (err) {
            this.logger.appendLine(
                `[daemon] warm failed for ${module.modulePath}: ${(err as Error).message}`,
            );
            throw err;
        }
        progress?.(ok ? "ready" : "fallback");
        return ok;
    }

    /**
     * Called from the file-watcher / save events. The daemon classifies the
     * file internally per PROTOCOL.md § 4; we send a hint based on the path.
     */
    async fileChanged(
        module: ModuleInfo,
        absPath: string,
        changeType: FileChangeType = "modified",
    ): Promise<void> {
        const client = await this.gate.getOrSpawn(
            module,
            this.daemonEvents(module.modulePath),
        );
        if (!client) {
            return;
        }
        client.fileChanged({
            path: absPath,
            kind: classifyKind(absPath),
            changeType,
        });
    }

    /**
     * Stage-2 in-process compile dispatch.
     *
     * Sends a `compileSources` JSON-RPC request to the daemon. The daemon's handler
     * dispatches through `DefaultBtaCompileService` when its launch descriptor opted into
     * `btaCompile`; otherwise it returns `{result:"fallback"}`. Returns the daemon's
     * outcome verbatim to the caller; null when the daemon channel isn't available at all
     * (no spawn / module disabled), which is functionally equivalent to a fallback.
     *
     * Callers should treat `result === "fallback"` AND `null` as "use the stage-1 / 0
     * path" and `result === "compileError"` as a real Kotlin source diagnostic.
     */
    async compileSourcesInProcess(
        module: ModuleInfo,
        sources: string[],
    ): Promise<import("./daemonProtocol").CompileSourcesResult | null> {
        const client = await this.gate.getOrSpawn(
            module,
            this.daemonEvents(module.modulePath),
        );
        if (!client) {
            return null;
        }
        try {
            return await client.compileSources({ sources });
        } catch (err) {
            this.logger.appendLine(
                `[daemon] compileSources failed for ${module.modulePath}: ${(err as Error).message}`,
            );
            return null;
        }
    }

    /**
     * The user moved focus to (or saved a file scoped to) a set of preview
     * IDs. These are rendered first when the queue drains.
     */
    async setFocus(module: ModuleInfo, previewIds: string[]): Promise<void> {
        if (this.visibility.sameFocusAsLast(module, previewIds)) {
            return;
        }
        this.visibility.recordFocus(module, previewIds);
        const client = await this.gate.getOrSpawn(
            module,
            this.daemonEvents(module.modulePath),
        );
        if (!client) {
            return;
        }
        client.setFocus({ ids: previewIds });
    }

    /**
     * Cards currently rendered in the panel (true geometric viewport, not
     * just the file scope). The daemon uses this to filter Tier-3 stale sets
     * — see DESIGN.md § 8 Tier 4. `predicted` are the IDs the webview
     * believes the user is about to scroll into; speculative renders kick
     * off for them up to [SPECULATIVE_BUDGET].
     */
    async setVisible(
        module: ModuleInfo,
        visible: string[],
        predicted: string[] = [],
    ): Promise<void> {
        const moduleKey = module.modulePath;
        if (
            this.visibility.sameVisibleAsLast(module, visible) &&
            predicted.length === 0
        ) {
            return;
        }
        this.visibility.recordVisible(module, visible);
        const client = await this.gate.getOrSpawn(
            module,
            this.daemonEvents(moduleKey),
        );
        if (!client) {
            return;
        }
        client.setVisible({ ids: visible });

        // D2 — drop bookkeeping for previews that fell out of view. The daemon already
        // pruned its side on the same `setVisible`, so this just keeps our local dedup
        // set in sync. Subscriptions themselves are opt-in per focused preview via
        // [setDataProductSubscription]; the panel calls in when the user toggles the
        // a11y overlay in focus mode.
        const visibleSetForSub = new Set(visible);
        const modulePrefix = `${moduleKey}::`;
        for (const key of [...this.subscribedPairs]) {
            if (!key.startsWith(modulePrefix)) {
                continue;
            }
            const rest = key.slice(modulePrefix.length);
            const sep = rest.indexOf("::");
            const id = sep < 0 ? rest : rest.slice(0, sep);
            if (!visibleSetForSub.has(id)) {
                this.subscribedPairs.delete(key);
            }
        }

        if (predicted.length === 0) {
            return;
        }
        // Bound the speculative request so a flick-scroll past 50 cards
        // doesn't queue 50 renders. Visible IDs trump predicted ones —
        // they're already in the daemon's reactive queue.
        const visibleSet = new Set(visible);
        const fresh = predicted
            .filter((id) => !visibleSet.has(id))
            .filter((id) => !this.speculation.has(module, id))
            .slice(0, SPECULATIVE_BUDGET);
        if (fresh.length === 0) {
            return;
        }
        for (const id of fresh) {
            this.speculation.mark(module, id);
        }
        try {
            await client.renderNow({
                previews: fresh,
                tier: HEAVY_TIER_DEFAULT,
                reason: "scroll-ahead",
            });
        } catch (err) {
            this.logger.appendLine(
                `[daemon] scroll-ahead renderNow failed for ${moduleKey}: ${(err as Error).message}`,
            );
        }
    }

    /**
     * D2 — explicit per-`(previewId, kind)` subscription toggle. Wired to the focus-mode a11y
     * overlay button: enabling subscribes to `a11y/atf` + `a11y/hierarchy` (or whatever the
     * caller passes) so the next render attaches the payload; disabling unsubscribes. Idempotent
     * on both sides.
     *
     * We deliberately keep the auto-subscribe out of `setVisible` so the wire stays quiet for
     * unfocused previews — the design doc's "Default = nothing attached" stance with a per-panel
     * opt-in.
     */
    async setDataProductSubscription(
        module: ModuleInfo,
        previewId: string,
        kinds: readonly string[],
        enabled: boolean,
    ): Promise<DataProductSubscriptionResult> {
        const wasA11yActive = this.moduleHasA11ySubscription(module.modulePath);
        const client = await this.gate.getOrSpawn(
            module,
            this.daemonEvents(module.modulePath),
        );
        if (!client) {
            return { a11yTransition: "unchanged" };
        }
        let subscribedAny = false;
        for (const kind of kinds) {
            const subKey = `${module.modulePath}::${previewId}::${kind}`;
            const already = this.subscribedPairs.has(subKey);
            if (enabled === already) {
                // No-op short-circuit. The log line surfaces the silent path that
                // bites a11y toggling when host bookkeeping drifts away from the
                // daemon's actual subscription state: a stale `subscribedPairs`
                // entry from a previous toggle makes `subscribedAny` stay false,
                // which suppresses the follow-up renderNow at the bottom of this
                // method, which leaves the daemon without a `mode=a11y` render —
                // the chip stays checked but data products never attach and the
                // dataExtensionTracker hits its 30 s safety timeout. Emitting
                // this line means a triage capture can tell "subscribed and the
                // daemon dropped it" from "host thought it was already done."
                this.logger.appendLine(
                    `[daemon] data${enabled ? "Subscribe" : "Unsubscribe"}` +
                        `(${previewId}, ${kind}) skipped (already ${enabled ? "subscribed" : "unsubscribed"} per host bookkeeping)`,
                );
                continue;
            }
            if (enabled) {
                this.subscribedPairs.add(subKey);
                subscribedAny = true;
                const subPromise = client
                    .dataSubscribe({ previewId, kind })
                    .catch((err) => {
                        // Pre-D2 daemons reject with DataProductUnknown for every kind; that's
                        // expected, not noise — log to the daemon channel and roll back the
                        // bookkeeping so a later daemon spawn re-issues.
                        const msg = (err as Error)?.message ?? String(err);
                        this.logger.appendLine(
                            `[daemon] dataSubscribe(${previewId}, ${kind}) failed: ${msg}`,
                        );
                        this.subscribedPairs.delete(subKey);
                    });
                this.trackPendingSubscribe(module.modulePath, subPromise);
            } else {
                this.subscribedPairs.delete(subKey);
                client.dataUnsubscribe({ previewId, kind }).catch((err) => {
                    // Unsubscribe rejection is purely informational — the daemon already
                    // cleaned up on its side, our bookkeeping is gone, no rollback needed.
                    const msg = (err as Error)?.message ?? String(err);
                    this.logger.appendLine(
                        `[daemon] dataUnsubscribe(${previewId}, ${kind}) failed: ${msg}`,
                    );
                });
            }
        }
        // D2.2 — `data/subscribe` records subscription state but doesn't trigger a render
        // on its own; the daemon's `subscriptionDrivenRenderMode` only injects the mode tag
        // on the *next* renderNow for this preview. Without a follow-up render request the
        // chip stays checked but no a11y artefacts ever land — the focus inspector's UX
        // expects "click chip → see overlay within one render time," which means we need
        // to bridge that gap host-side. Fire a single fast-tier renderNow per call (not
        // per kind) when at least one new subscription took. Idempotent w.r.t. unsubscribes
        // and no-op-subscribes — they don't set `subscribedAny`. The daemon's existing
        // dedup means a second renderNow for an in-flight render coalesces.
        if (enabled && subscribedAny) {
            client
                .renderNow({
                    previews: [previewId],
                    tier: "fast",
                    reason: "data/subscribe",
                })
                .catch((err) => {
                    const msg = (err as Error)?.message ?? String(err);
                    this.logger.appendLine(
                        `[daemon] post-subscribe renderNow(${previewId}) failed: ${msg}`,
                    );
                });
        }
        const isA11yActive = this.moduleHasA11ySubscription(module.modulePath);
        return {
            a11yTransition:
                wasA11yActive === isA11yActive
                    ? "unchanged"
                    : isA11yActive
                      ? "enabled"
                      : "disabled",
        };
    }

    /**
     * True when any `(previewId, a11y/*)` pair under [modulePath] is currently
     * subscribed. Derived from {@link subscribedPairs} on every call so there's
     * no separate aggregate to keep in sync.
     */
    private moduleHasA11ySubscription(modulePath: string): boolean {
        const prefix = `${modulePath}::`;
        for (const key of this.subscribedPairs) {
            if (!key.startsWith(prefix)) continue;
            const rest = key.slice(prefix.length);
            const kindSep = rest.indexOf("::");
            if (kindSep < 0) continue;
            const kind = rest.slice(kindSep + 2);
            if (isA11yKind(kind)) return true;
        }
        return false;
    }

    /**
     * Stash the in-flight `data/subscribe` promise so a parallel `renderNow`
     * caller can drain it before sending. The promise is removed from the
     * pending set on settle (resolve or reject) so a long-lived module
     * doesn't accumulate references.
     */
    private trackPendingSubscribe(
        moduleId: string,
        promise: Promise<unknown>,
    ): void {
        let bucket = this.pendingSubscribes.get(moduleId);
        if (!bucket) {
            bucket = new Set();
            this.pendingSubscribes.set(moduleId, bucket);
        }
        bucket.add(promise);
        const drop = () => {
            const current = this.pendingSubscribes.get(moduleId);
            if (!current) return;
            current.delete(promise);
            if (current.size === 0) this.pendingSubscribes.delete(moduleId);
        };
        promise.then(drop, drop);
    }

    awaitPendingSubscribes(moduleId: string): Promise<void> {
        const bucket = this.pendingSubscribes.get(moduleId);
        if (!bucket || bucket.size === 0) {
            return Promise.resolve();
        }
        // Snapshot — settle the entries we know about now; subscribes
        // issued later are the next caller's problem.
        return Promise.allSettled([...bucket]).then(() => undefined);
    }

    /**
     * Explicit user-triggered render (Refresh button / first scope-in).
     * Returns when the daemon has accepted the work; the actual PNG arrives
     * via `onPreviewImageReady`.
     */
    async renderNow(
        module: ModuleInfo,
        previewIds: string[],
        tier: RenderTier = "fast",
        reason?: string,
        overrides?: PreviewOverrides,
    ): Promise<boolean> {
        const client = await this.gate.getOrSpawn(
            module,
            this.daemonEvents(module.modulePath),
        );
        if (!client) {
            return false;
        }
        try {
            const result = await client.renderNow({
                previews: previewIds,
                tier,
                reason,
                overrides,
            });
            for (const r of result.rejected) {
                this.logger.appendLine(
                    `[daemon] renderNow rejected ${r.id}: ${r.reason}`,
                );
            }
            return true;
        } catch (err) {
            this.logger.appendLine(
                `[daemon] renderNow failed for ${module.modulePath}: ${(err as Error).message}`,
            );
            return false;
        }
    }

    /**
     * Builds the [DaemonClientEvents]-shaped bag the gate registers per
     * module. Public so `extension.ts`'s history-source wiring can reuse
     * the same events bag when issuing one-off `historyList` /
     * `historyRead` / `historyDiff` calls — the gate's daemon registry
     * keys on identity equivalence of the events bag, so reusing the
     * Issue #1528 — pull a scroll artefact (`render/scroll/long` / `render/scroll/gif`) through
     * `data/fetch`. The daemon registry's `requiresRerender = true` semantics mean a missing
     * artefact returns `Outcome.RequiresRerender`, which the dispatcher converts into a
     * `mode=scroll-long|gif` re-render via `RenderEngine.runScrollScenario`. Returns the
     * on-disk path the daemon wrote (matches the `missing.absolutePath` the host already
     * computed off the manifest); `null` on any failure so callers can keep their Gradle
     * fallback behind the same code path.
     */
    async fetchScrollDataProduct(
        module: ModuleInfo,
        missing: {
            previewId: string;
            captureIndex: number;
            renderOutput: string;
            absolutePath: string;
        },
    ): Promise<string | null> {
        const client = await this.gate.getOrSpawn(
            module,
            this.daemonEvents(module.modulePath),
        );
        if (!client) return null;
        const lower = missing.renderOutput.toLowerCase();
        const kind = lower.endsWith(".gif")
            ? "render/scroll/gif"
            : lower.endsWith(".png")
              ? "render/scroll/long"
              : null;
        if (!kind) return null;
        try {
            const result = await client.dataFetch({
                previewId: missing.previewId,
                kind,
                inline: false,
            });
            return result.path ?? missing.absolutePath;
        } catch (err) {
            // FetchFailed / Unknown / BudgetExceeded all surface as throws from the
            // protocol client. Logged at the caller via the `null` return so the host
            // can fall back to its Gradle path on older daemons that don't advertise
            // the kinds. The thrown reason isn't logged here because the caller already
            // has the (previewId, kind) context that makes the error meaningful.
            void err;
            return null;
        }
    }

    daemonEvents(moduleId: string) {
        return {
            onRenderFinished: (params: RenderFinishedParams) => {
                this.handleRenderFinished(moduleId, params);
            },
            onRenderFailed: (params: {
                id: string;
                error: { message: string };
            }) => {
                this.events.onRenderFailed(
                    moduleId,
                    params.id,
                    params.error.message,
                );
            },
            onClasspathDirty: (params: { detail: string }) => {
                // Drop the speculative cache for this module so a re-spawned
                // daemon doesn't think we already pre-warmed those IDs.
                this.speculation.forgetModule({ modulePath: moduleId });
                this.events.onClasspathDirty(moduleId, params.detail);
            },
            onDiscoveryUpdated: (params: DiscoveryUpdatedParams) => {
                this.events.onDiscoveryUpdated?.(moduleId, params);
            },
            onHistoryAdded: (params: HistoryAddedParams) => {
                this.events.onHistoryAdded?.(moduleId, params);
            },
            onHistoryPruned: (params: HistoryPrunedParams) => {
                this.events.onHistoryPruned?.(moduleId, params);
            },
            onStreamFrame: (params: StreamFrameParams) => {
                this.events.onStreamFrame?.(moduleId, params);
            },
            onChannelClosed: () => {
                // Daemon died; clear caches so the next call re-issues them
                // against a fresh JVM.
                const modRef = { modulePath: moduleId };
                this.visibility.forgetModule(modRef);
                this.speculation.forgetModule(modRef);
                // D2 — wipe data-product subscription state so the next daemon spawn re-issues
                // `data/subscribe` against the fresh JVM. Subscriptions don't survive daemon
                // restarts (PROTOCOL.md / DATA-PRODUCTS.md § "Wire surface").
                for (const k of [...this.subscribedPairs]) {
                    if (k.startsWith(`${moduleId}::`)) {
                        this.subscribedPairs.delete(k);
                    }
                }
                // Forward to the extension so it can drop interactive-mode stream state for
                // this module — frameStreamIds don't survive a daemon restart, and a stale
                // entry in `activeInteractiveStreams` would route subsequent clicks to a
                // stream id the fresh daemon never minted.
                this.events.onChannelClosed?.(moduleId);
            },
        };
    }

    private handleRenderFinished(
        moduleId: string,
        params: RenderFinishedParams,
    ): void {
        try {
            // Speculative entries graduate to "real" once they actually render —
            // drop them from the dedup set so a subsequent fileChanged for the
            // same preview can re-render via the reactive path.
            this.speculation.forget({ modulePath: moduleId }, params.id);

            // INTERACTIVE.md § 5 frame dedup. Daemon already determined the bytes are
            // byte-identical to the prior frame; skip the disk read + base64 + postMessage
            // hop. The card stays painted with the bytes it's already showing — that's the
            // whole point of the dedup signal.
            //
            // BUT: a subscription-driven re-render against a previously-rendered preview
            // routinely produces identical pixels (the data product travels in its own
            // file — `a11y/overlay` is a separate PNG, `a11y/hierarchy` a separate JSON —
            // so the *primary* PNG bytes don't change). Dropping the whole notification
            // here also drops `dataProducts`, and the focus inspector's chip never gets
            // its payload. Forward attachments before returning so the data-product chain
            // doesn't depend on the primary image being dirty.
            if (params.unchanged === true) {
                if (params.dataProducts && params.dataProducts.length > 0) {
                    this.events.onDataProductsAttached?.(
                        moduleId,
                        params.id,
                        params.dataProducts,
                    );
                }
                return;
            }

            // Stub-render path: until B1.4 lands `RenderEngine` in the
            // daemon, every "successful" render returns
            // `<historyDir>/daemon-stub-<id>.{png,gif}` with no bytes
            // actually written. Documented in JsonRpcServer.kt's
            // `renderFinishedFromResult` KDoc. Silently no-op rather than
            // log ENOENT per render — the user's panel is already painted
            // by the existing Gradle path, and the daemon team will swap
            // this to real rendering without an extension change. Once
            // B1.4 ships, the path stops matching and this branch is dead
            // weight; no harm in keeping it.
            if (isDaemonStubPath(params.pngPath)) {
                if (!this.warnedStubModules.has(moduleId)) {
                    this.warnedStubModules.add(moduleId);
                    this.logger.appendLine(
                        `[daemon] ${moduleId} is at stub-render stage (B1.5); ` +
                            "real renders arrive once :daemon:android ships B1.4 RenderEngine. " +
                            "Suppressing per-render ENOENT logs for stub paths.",
                    );
                }
                return;
            }

            const buf = fs.readFileSync(params.pngPath);
            this.events.onPreviewImageReady(
                moduleId,
                params.id,
                buf.toString("base64"),
                params.pngPath,
            );
            // D2 — forward attached data products. The dispatcher already filtered to the
            // `(previewId, kind)` pairs the client subscribed to, so any non-empty list is a
            // payload the panel asked for. Empty / absent → nothing to do; we DON'T fire the
            // event in that case to keep the consumer-side dispatch trivial.
            if (params.dataProducts && params.dataProducts.length > 0) {
                this.events.onDataProductsAttached?.(
                    moduleId,
                    params.id,
                    params.dataProducts,
                );
            }
        } catch (err) {
            this.logger.appendLine(
                `[daemon] failed to read ${params.pngPath} for ${params.id}: ${(err as Error).message}`,
            );
            this.events.onRenderFailed(
                moduleId,
                params.id,
                "render finished but PNG was unreadable",
            );
        }
    }
}

/**
 * Minimal-mode counterpart to [LiveDaemonScheduler]. Every write path ignores
 * its arguments; `warmModule` reports `'fallback'`; `daemonEvents` returns an
 * inert events bag so call sites can still hand it to
 * `GradleOnlyDaemonGate.getOrSpawn` without branching on the backend.
 */
export class GradleOnlyDaemonScheduler implements DaemonScheduler {
    async ensureModule(_module: ModuleInfo): Promise<boolean> {
        return false;
    }

    async warmModule(
        _gradleService: GradleService,
        _module: ModuleInfo,
        progress?: WarmProgress,
    ): Promise<boolean> {
        progress?.("fallback");
        return false;
    }

    async fileChanged(
        _module: ModuleInfo,
        _absPath: string,
        _changeType?: FileChangeType,
    ): Promise<void> {
        /* no-op: minimal mode never notifies the daemon */
    }

    async compileSourcesInProcess(
        _module: ModuleInfo,
        _sources: string[],
    ): Promise<import("./daemonProtocol").CompileSourcesResult | null> {
        /* no-op: minimal mode never reaches the daemon, callers fall back to Gradle */
        return null;
    }

    async setFocus(_module: ModuleInfo, _previewIds: string[]): Promise<void> {
        /* no-op */
    }

    async setVisible(
        _module: ModuleInfo,
        _visible: string[],
        _predicted?: string[],
    ): Promise<void> {
        /* no-op */
    }

    async setDataProductSubscription(
        _module: ModuleInfo,
        _previewId: string,
        _kinds: readonly string[],
        _enabled: boolean,
    ): Promise<DataProductSubscriptionResult> {
        /* no-op: data extensions are disabled in minimal mode */
        return { a11yTransition: "unchanged" };
    }

    async awaitPendingSubscribes(_moduleId: string): Promise<void> {
        /* no-op: minimal mode never issues data/subscribe */
    }

    async renderNow(
        _module: ModuleInfo,
        _previewIds: string[],
        _tier?: RenderTier,
        _reason?: string,
    ): Promise<boolean> {
        return false;
    }

    async fetchScrollDataProduct(
        _module: ModuleInfo,
        _missing: {
            previewId: string;
            captureIndex: number;
            renderOutput: string;
            absolutePath: string;
        },
    ): Promise<string | null> {
        /* no-op: minimal mode has no daemon; caller falls back to Gradle */
        return null;
    }

    daemonEvents(_moduleId: string): DaemonClientEvents {
        return {};
    }
}

function classifyKind(absPath: string): FileKind {
    const lower = absPath.toLowerCase();
    if (
        lower.endsWith("libs.versions.toml") ||
        lower.endsWith(".gradle.kts") ||
        lower.endsWith("gradle.properties") ||
        lower.endsWith("local.properties") ||
        lower.endsWith("settings.gradle.kts")
    ) {
        return "classpath";
    }
    if (
        lower.includes(`${path.sep}res${path.sep}`) ||
        lower.includes("/res/")
    ) {
        return "resource";
    }
    return "source";
}

/**
 * True iff [pngPath]'s basename matches the documented daemon stub shape
 * `daemon-stub-<id>.{png,gif}` from `JsonRpcServer.kt`. Once :daemon:android
 * ships B1.4 (`RenderEngine`), real renders write to a different path and
 * this returns false on every render.
 *
 * Loose match — the daemon team is free to rename the directory; the
 * filename prefix is what's documented as the contract.
 */
function isDaemonStubPath(pngPath: string): boolean {
    const slash = Math.max(pngPath.lastIndexOf("/"), pngPath.lastIndexOf("\\"));
    const base = slash >= 0 ? pngPath.slice(slash + 1) : pngPath;
    return /^daemon-stub-.+\.(png|gif)$/.test(base);
}
