import * as path from "path";
import * as fs from "fs";
import { GradleService, ModuleInfo } from "../gradleService";
import { DaemonGate } from "./daemonGate";
import {
    DataProductAttachment,
    DiscoveryUpdatedParams,
    FileChangeType,
    FileKind,
    HistoryAddedParams,
    HistoryPrunedParams,
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
export class DaemonScheduler {
    /** Last-known-visible IDs per module — let us dedup setVisible spam. */
    private lastVisible = new Map<string, string[]>();
    /** Last `setFocus` per module so editor refocus on the same file is a no-op. */
    private lastFocus = new Map<string, string[]>();
    /**
     * D2 — `(moduleId, previewId)` pairs we've already issued `data/subscribe` for. Subscribed kinds
     * are pinned at [DEFAULT_SUBSCRIBED_KINDS] today; per-kind tracking moves here when the panel
     * grows a runtime "show a11y" toggle. Daemon prunes its side automatically when a previewId
     * leaves `setVisible`, so we only need to dedup re-subscribes.
     */
    private readonly subscribedPairs = new Set<string>();
    /** Cards we've already speculatively requested so scrolling back over
     *  them doesn't re-queue identical work. Keyed by `${moduleId}::${id}`. */
    private speculated = new Set<string>();
    /** Modules where we've already logged a "daemon at stub stage" notice.
     *  Stops the per-render ENOENT spam when the daemon (B1.5) emits
     *  synthetic `daemon-stub-N.png` paths that don't exist on disk. */
    private warnedStubModules = new Set<string>();

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
        try {
            progress?.("bootstrapping");
            await gradleService.runDaemonBootstrap(module);
        } catch (err) {
            this.logger.appendLine(
                `[daemon] bootstrap task failed for ${module.modulePath}: ${(err as Error).message}`,
            );
            throw err;
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
     * The user moved focus to (or saved a file scoped to) a set of preview
     * IDs. These are rendered first when the queue drains.
     */
    async setFocus(module: ModuleInfo, previewIds: string[]): Promise<void> {
        const key = module.modulePath;
        if (sameSet(this.lastFocus.get(key), previewIds)) {
            return;
        }
        this.lastFocus.set(key, [...previewIds]);
        const client = await this.gate.getOrSpawn(
            module,
            this.daemonEvents(key),
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
            sameSet(this.lastVisible.get(moduleKey), visible) &&
            predicted.length === 0
        ) {
            return;
        }
        this.lastVisible.set(moduleKey, [...visible]);
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
            .filter((id) => !this.speculated.has(specKey(moduleKey, id)))
            .slice(0, SPECULATIVE_BUDGET);
        if (fresh.length === 0) {
            return;
        }
        for (const id of fresh) {
            this.speculated.add(specKey(moduleKey, id));
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
    ): Promise<void> {
        const client = await this.gate.getOrSpawn(
            module,
            this.daemonEvents(module.modulePath),
        );
        if (!client) {
            return;
        }
        let subscribedAny = false;
        for (const kind of kinds) {
            const subKey = `${module.modulePath}::${previewId}::${kind}`;
            const already = this.subscribedPairs.has(subKey);
            if (enabled === already) {
                continue;
            }
            if (enabled) {
                this.subscribedPairs.add(subKey);
                subscribedAny = true;
                client.dataSubscribe({ previewId, kind }).catch((err) => {
                    // Pre-D2 daemons reject with DataProductUnknown for every kind; that's
                    // expected, not noise — log to the daemon channel and roll back the
                    // bookkeeping so a later daemon spawn re-issues.
                    const msg = (err as Error)?.message ?? String(err);
                    this.logger.appendLine(
                        `[daemon] dataSubscribe(${previewId}, ${kind}) failed: ${msg}`,
                    );
                    this.subscribedPairs.delete(subKey);
                });
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
     * same one keeps a single live registration.
     */
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
                for (const k of [...this.speculated]) {
                    if (k.startsWith(`${moduleId}::`)) {
                        this.speculated.delete(k);
                    }
                }
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
                this.lastVisible.delete(moduleId);
                this.lastFocus.delete(moduleId);
                for (const k of [...this.speculated]) {
                    if (k.startsWith(`${moduleId}::`)) {
                        this.speculated.delete(k);
                    }
                }
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
            this.speculated.delete(specKey(moduleId, params.id));

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

function sameSet(a: string[] | undefined, b: string[]): boolean {
    if (!a || a.length !== b.length) {
        return false;
    }
    const set = new Set(a);
    return b.every((id) => set.has(id));
}

function specKey(moduleId: string, previewId: string): string {
    return `${moduleId}::${previewId}`;
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
