// Typed dispatcher for `ExtensionToWebview` messages received by the live
// preview panel.
//
// Replaces the giant `switch (msg.command)` block that used to live inline in
// `behavior.ts` (under `@ts-nocheck`). The discriminated union from
// `../shared/types` flows through `handleExtensionMessage` so each case body
// is type-checked against the shape its variant carries — pre-#784's
// `buildErrorPanel` arity mismatch (caught only when the carousel was lifted
// into a typed module) wouldn't have shipped under this dispatcher.
//
// The handler module owns no state of its own; it routes each command to a
// callback supplied by `behavior.ts` via `PreviewMessageContext`. Things that
// are thin orchestration over a typed controller (live state, stale badge,
// loading overlay, focus inspector, diff overlay, viewport tracker) call into
// those controllers directly. The remaining fields on the context cover the
// state still owned imperatively by `behavior.ts` — `allPreviews`,
// `moduleDir`, `lastScopedPreviewId`, `a11yOverlayPreviewId` — and the
// orchestration callbacks (`renderPreviews`, `applyLayout`,
// `applyFilters`, `applyA11yUpdate`, `focusOnCard`, etc.) that should
// fold into a future `<preview-card>` Lit component. (`updateImage`
// already has — the dispatcher resolves the card by id and calls
// `card.paintCapture(...)` directly; no `ctx.updateImage` callback.)
// The
// per-preview Maps (`cardCaptures`, `cardA11yFindings`, `cardA11yNodes`)
// now live on `previewStore` and are reached through the helpers in
// `previewStore.ts`. The per-module availability Maps (`moduleDaemonReady`,
// `moduleInteractiveSupported`) live on `LiveStateController`; this
// dispatcher writes them via `ctx.liveState.setAvailability(...)`.
//
// Some `ExtensionToWebview` variants are handled directly by Lit components
// without going through this dispatcher — `<message-banner>` listens for
// `showMessage`, `<progress-bar>` for `setProgress` / `clearProgress`,
// `<compile-errors-banner>` for `setCompileErrors` / `clearCompileErrors`.
// Those cases appear here as no-op branches to satisfy exhaustiveness on the
// discriminated union.

import type {
    AccessibilityFinding,
    AccessibilityNode,
    ExtensionToWebview,
    PreviewInfo,
} from "../shared/types";
import type { VsCodeApi } from "../shared/vscode";
import { safeArrayIndex } from "../shared/safeIndex";
import { sanitizeId } from "./cardData";
import { PreviewCard } from "./components/PreviewCard";
import { PreviewGrid } from "./components/PreviewGrid";
import { buildErrorPanel } from "./errorPanel";
import { showDiffOverlay, type DiffOverlayConfig } from "./diffOverlay";
import { FocusInspectorController } from "./focusInspector";
import { LiveStateController } from "./liveState";
import { LoadingOverlay } from "./loadingOverlay";
import {
    bumpPreviewMapsRevision,
    clearCardA11yFindings,
    clearCardA11yNodes,
    previewStore,
} from "./previewStore";
import { StaleBadgeController } from "./staleBadge";
import { StreamingPainter } from "./streamingPainter";

/** Every dependency a message handler can reach for. Built once in
 *  `behavior.ts` and held by-reference for the lifetime of the panel. */
export interface PreviewMessageContext {
    vscode: VsCodeApi<unknown>;
    grid: PreviewGrid;
    /** `<filter-toolbar>` handle — typed via `Element & FilterToolbarApi`
     *  rather than importing the Lit class directly so the dispatcher
     *  doesn't pull in Lit at type level. */
    filterToolbar: FilterToolbarApi;
    inspector: FocusInspectorController;
    liveState: LiveStateController;
    staleBadge: StaleBadgeController;
    loadingOverlay: LoadingOverlay;
    diffOverlayConfig: DiffOverlayConfig;
    /** Painter for `composestream/1` live frames — see streamingPainter.ts. */
    streamingPainter: StreamingPainter;

    earlyFeatures(): boolean;
    getA11yOverlayId(): string | null;
    setA11yOverlayId(id: string | null): void;

    /** `setPreviews` reseeds the manifest array. */
    setAllPreviews(previews: PreviewInfo[]): void;
    /** `setPreviews` carries the active module dir. */
    setModuleDir(dir: string): void;
    /** `clearAll` resets the previewScopeChanged dedup so the next setPreviews
     *  re-publishes scope. */
    setLastScopedPreviewId(id: string | null): void;

    renderPreviews(previews: PreviewInfo[]): void;
    applyRelativeSizing(previews: PreviewInfo[]): void;
    applyFilters(): void;
    applyLayout(): void;
    applyInteractiveButtonState(): void;
    applyRecordingButtonState(): void;
    saveFilterState(): void;
    restoreFilterState(): void;
    ensureNotBlank(): void;
    applyA11yUpdate(
        previewId: string,
        findings: readonly AccessibilityFinding[] | null | undefined,
        nodes: readonly AccessibilityNode[] | null | undefined,
    ): void;
    focusOnCard(card: HTMLElement): void;
}

/** Methods the dispatcher needs from `<filter-toolbar>`. The component
 *  defines a wider Lit-element surface; this is just the slice the dispatcher
 *  reaches. */
export interface FilterToolbarApi {
    setFunctionOptions(opts: readonly string[]): void;
    setGroupOptions(opts: readonly string[]): void;
    setFunctionValue(value: string): void;
}

export function handleExtensionMessage(
    msg: ExtensionToWebview,
    ctx: PreviewMessageContext,
): void {
    switch (msg.command) {
        case "setPreviews":
            return handleSetPreviews(msg, ctx);
        case "markAllLoading":
            ctx.loadingOverlay.markAll();
            return;
        case "clearAll":
            return handleClearAll(ctx);
        case "updateImage": {
            // Resolve the card and delegate to its `paintCapture`
            // method — `<preview-card>` owns the per-frame paint
            // path, so the dispatcher hands off the captureIndex /
            // imageData straight to the component.
            const card = document.getElementById(
                "preview-" + sanitizeId(msg.previewId),
            );
            if (card instanceof PreviewCard) {
                card.paintCapture(
                    safeArrayIndex(msg.captureIndex),
                    msg.imageData,
                );
            }
            return;
        }
        case "updateA11y":
            ctx.applyA11yUpdate(msg.previewId, msg.findings, msg.nodes);
            return;
        case "setModules":
            // Module selector removed from UI — module is resolved from the
            // active editor. Kept on the wire for backwards compat.
            return;
        case "setFunctionFilter":
            // Driven by the gutter-icon hover link: narrow the grid to a
            // single @Preview function. `<filter-toolbar>`'s setFunctionValue
            // ensures the option exists for the gutter-before-setPreviews
            // case so the value sticks.
            ctx.filterToolbar.setFunctionValue(msg.functionName);
            ctx.saveFilterState();
            ctx.applyFilters();
            return;
        case "setLoading":
            return handleSetLoading(msg, ctx);
        case "setError":
        case "setImageError":
            return handleErrorMessage(msg, ctx);
        case "previewDiffReady":
            return handlePreviewDiffReady(msg, ctx);
        case "previewDiffError":
            return handlePreviewDiffError(msg, ctx);
        case "focusAndDiff":
            return handleFocusAndDiff(msg, ctx);
        case "setInteractiveAvailability":
            return handleSetInteractiveAvailability(msg, ctx);
        case "clearInteractive":
            ctx.liveState.handleExtensionClearInteractive(
                msg.previewId ?? null,
            );
            return;
        case "clearRecording":
            ctx.liveState.handleExtensionClearRecording(msg.previewId ?? null);
            return;
        case "previewMainRefChanged":
            return handlePreviewMainRefChanged(ctx);
        case "setEarlyFeatures":
            return handleSetEarlyFeatures(msg, ctx);
        case "streamStarted": {
            const card = document.getElementById(
                "preview-" + sanitizeId(msg.previewId),
            );
            if (card) {
                ctx.streamingPainter.attach(
                    card as HTMLElement,
                    msg.previewId,
                    msg.frameStreamId,
                );
            }
            return;
        }
        case "streamFrame":
            ctx.streamingPainter.onFrame({
                frameStreamId: msg.frameStreamId,
                seq: msg.seq,
                ptsMillis: msg.ptsMillis,
                widthPx: msg.widthPx,
                heightPx: msg.heightPx,
                codec: msg.codec,
                keyframe: msg.keyframe ?? false,
                final: msg.final ?? false,
                payloadBase64: msg.payloadBase64,
            });
            return;
        case "streamStopped":
            ctx.streamingPainter.detach(msg.previewId);
            return;
        case "showMessage":
        case "setProgress":
        case "clearProgress":
        case "setCompileErrors":
        case "clearCompileErrors":
            // Handled directly by Lit components (`<message-banner>`,
            // `<progress-bar>`, `<compile-errors-banner>`) — they listen on
            // `window` for these and the dispatcher does not duplicate the
            // routing.
            return;
        default:
            return assertNever(msg);
    }
}

function handleSetPreviews(
    msg: Extract<ExtensionToWebview, { command: "setPreviews" }>,
    ctx: PreviewMessageContext,
): void {
    ctx.setAllPreviews(msg.previews);
    ctx.setModuleDir(msg.moduleDir);
    ctx.renderPreviews(msg.previews);
    ctx.applyRelativeSizing(msg.previews);
    // Stale-tier badges depend on the latest render's tier (sent from the
    // extension as `heavyStaleIds`). Apply *after* renderPreviews so the
    // badge attaches to cards that were just inserted, not stripped by a
    // stale-state diff from the previous setPreviews.
    ctx.staleBadge.updateAll(ctx.grid, msg.heavyStaleIds);

    const fns = [...new Set(msg.previews.map((p) => p.functionName))].sort();
    const groups = [
        ...new Set(
            msg.previews
                .map((p) => p.params.group)
                .filter((g): g is string => Boolean(g)),
        ),
    ].sort();

    ctx.filterToolbar.setFunctionOptions(fns);
    ctx.filterToolbar.setGroupOptions(groups);

    ctx.restoreFilterState();
    ctx.applyFilters();
    ctx.applyLayout();
    // setPreviews can rebuild the focused card from scratch; re-stamp the live
    // badge so the LIVE chip reattaches to the right card(s). Drop any live
    // previewIds that are gone from the new manifest — silent cleanup; the
    // preview no longer exists for the daemon to dispatch into anyway.
    const newIds = new Set(msg.previews.map((p) => p.id));
    ctx.liveState.pruneLive((id) => newIds.has(id));
    ctx.liveState.applyLiveBadge();
    ctx.applyInteractiveButtonState();
    // Tell the extension the cards reached the grid. Powers the e2e test's
    // "real webview consumed setPreviews" assertion — `postedMessageLog`
    // alone only proves the host posted the message, not that a resolved
    // webview ever received it.
    ctx.vscode.postMessage({
        command: "webviewPreviewsRendered",
        count: ctx.grid.querySelectorAll(".preview-card").length,
    });
}

function handleClearAll(ctx: PreviewMessageContext): void {
    ctx.setAllPreviews([]);
    ctx.grid.innerHTML = "";
    // Reset so the next setPreviews can re-publish the narrowed-preview scope
    // if applicable — otherwise a stale id from the previous module would
    // dedupe the first publish and the History panel would miss it.
    ctx.setLastScopedPreviewId(null);
    previewStore.setState({ focusedPreviewId: null });
    // Cards are gone — escalation timer has nothing left to promote. Avoids
    // a stray timer firing after the next refresh has installed fresh
    // minimal overlays.
    ctx.loadingOverlay.cancel();
    // Don't clear the message here — if it came with a follow-up showMessage
    // (the usual pattern) it'll be replaced; if not, ensureNotBlank will
    // backstop a placeholder so the view never ends up empty+silent.
    ctx.ensureNotBlank();
}

function handleSetLoading(
    msg: Extract<ExtensionToWebview, { command: "setLoading" }>,
    ctx: PreviewMessageContext,
): void {
    if (!msg.previewId) {
        // Whole-panel loading state is now carried by the slim progress bar
        // at the top of the view (setProgress). Avoid double-signalling with
        // a "Building…" banner — it competes with the bar for visual attention.
        return;
    }
    const card = document.getElementById(
        "preview-" + sanitizeId(msg.previewId),
    );
    if (!card) return;
    const container = card.querySelector(".image-container");
    if (!container) return;
    if (container.querySelector(".loading-overlay")) return;
    const overlay = document.createElement("div");
    overlay.className = "loading-overlay";
    overlay.innerHTML = '<div class="spinner" aria-label="Rendering"></div>';
    container.appendChild(overlay);
}

function handleErrorMessage(
    msg:
        | Extract<ExtensionToWebview, { command: "setError" }>
        | Extract<ExtensionToWebview, { command: "setImageError" }>,
    ctx: PreviewMessageContext,
): void {
    const errCard = document.getElementById(
        "preview-" + sanitizeId(msg.previewId),
    );
    if (!errCard) return;
    // Stash per-capture error so carousel navigation restores the message
    // when the user returns to that specific capture. setError is preview-
    // wide (captureIndex defaulted to 0) — applies to the representative
    // image container only.
    const rawCaptureIndex =
        msg.command === "setImageError" ? msg.captureIndex : 0;
    const captureIndex = safeArrayIndex(rawCaptureIndex);
    const renderError =
        msg.command === "setImageError" ? (msg.renderError ?? null) : null;
    const caps = previewStore.getState().cardCaptures.get(msg.previewId);
    const replaceExisting =
        msg.command !== "setImageError" || msg.replaceExisting !== false;
    const container = errCard.querySelector<HTMLElement>(".image-container");
    if (!container) return;
    const existingImg = container.querySelector("img");
    // Bounds-check the array access before reading or writing — CodeQL flags
    // unconstrained indexed writes off a wire-supplied number as a prototype-
    // pollution vector even when TypeScript narrows the type to `number`.
    const capture =
        caps && captureIndex < caps.length ? caps[captureIndex] : null;
    const existingImageData = capture ? capture.imageData : null;
    const keepExistingImage =
        !replaceExisting && (existingImageData || existingImg);
    if (capture) {
        capture.errorMessage = msg.message;
        capture.renderError = renderError;
        if (!keepExistingImage) {
            capture.imageData = null;
        }
        // Mutated the capture object in place — Map identity unchanged
        // but `mapsRevision` needs to advance so subscribers selecting
        // on it see the new error state.
        bumpPreviewMapsRevision();
    }
    const cur = parseInt(errCard.dataset.currentIndex || "0", 10);
    if (caps && cur !== captureIndex) return;

    errCard.classList.add("has-error");
    container.querySelector(".error-message")?.remove();
    container.querySelector(".loading-overlay")?.remove();
    const skeleton = container.querySelector(".skeleton");
    if (skeleton && (keepExistingImage || msg.command === "setImageError")) {
        skeleton.remove();
    }
    // setImageError keeps any existing rendered <img> visible underneath the
    // error overlay so the user still has the previous render as a reference.
    // setError is the preview-wide path — wipe everything and replace with
    // just the error.
    if (msg.command === "setError") {
        container.querySelector("img")?.remove();
    }
    container.appendChild(
        buildErrorPanel(
            ctx.vscode,
            msg.message,
            renderError,
            errCard.dataset.className ?? "",
        ),
    );
}

function handlePreviewDiffReady(
    msg: Extract<ExtensionToWebview, { command: "previewDiffReady" }>,
    ctx: PreviewMessageContext,
): void {
    if (!ctx.earlyFeatures()) return;
    const card = document.getElementById(
        "preview-" + sanitizeId(msg.previewId),
    );
    if (!card) return;
    showDiffOverlay(
        card,
        msg.against,
        {
            leftLabel: msg.leftLabel,
            leftImage: msg.leftImage,
            rightLabel: msg.rightLabel,
            rightImage: msg.rightImage,
        },
        null,
        ctx.diffOverlayConfig,
    );
}

function handlePreviewDiffError(
    msg: Extract<ExtensionToWebview, { command: "previewDiffError" }>,
    ctx: PreviewMessageContext,
): void {
    if (!ctx.earlyFeatures()) return;
    const card = document.getElementById(
        "preview-" + sanitizeId(msg.previewId),
    );
    if (!card) return;
    showDiffOverlay(
        card,
        msg.against,
        null,
        msg.message || "Diff unavailable.",
        ctx.diffOverlayConfig,
    );
}

function handleFocusAndDiff(
    msg: Extract<ExtensionToWebview, { command: "focusAndDiff" }>,
    ctx: PreviewMessageContext,
): void {
    if (!ctx.earlyFeatures()) return;
    const card = document.getElementById(
        "preview-" + sanitizeId(msg.previewId),
    );
    if (!card) return;
    ctx.focusOnCard(card);
    showDiffOverlay(card, msg.against, null, null, ctx.diffOverlayConfig);
    ctx.vscode.postMessage({
        command: "requestPreviewDiff",
        previewId: msg.previewId,
        against: msg.against,
    });
}

function handleSetInteractiveAvailability(
    msg: Extract<ExtensionToWebview, { command: "setInteractiveAvailability" }>,
    ctx: PreviewMessageContext,
): void {
    ctx.liveState.setAvailability(
        msg.moduleId,
        !!msg.ready,
        !!msg.interactiveSupported,
    );
    // Daemon went away while a card was live — drop the live state so the
    // user doesn't keep seeing a LIVE badge on a card whose stream has
    // stopped. Today the panel is single-module-scoped so any not-ready
    // signal applies to every live preview; if the panel ever shows multi-
    // module previews simultaneously, this needs scoping by each preview's
    // owning module.
    if (!msg.ready) {
        ctx.liveState.handleDaemonLost();
    } else {
        ctx.applyInteractiveButtonState();
        ctx.applyRecordingButtonState();
    }
}

function handlePreviewMainRefChanged(ctx: PreviewMessageContext): void {
    if (!ctx.earlyFeatures()) return;
    // compose-preview/main moved — re-issue any open vs-main diff overlay so
    // the user sees the new bytes without clicking. Other diffs (HEAD,
    // current, previous) are unaffected.
    document
        .querySelectorAll<HTMLElement>(
            '.preview-diff-overlay[data-against="main"]',
        )
        .forEach((overlay) => {
            const card = overlay.closest<HTMLElement>(".preview-card");
            const previewId = card?.dataset.previewId;
            if (!card || !previewId) return;
            showDiffOverlay(card, "main", null, null, ctx.diffOverlayConfig);
            ctx.vscode.postMessage({
                command: "requestPreviewDiff",
                previewId,
                against: "main",
            });
        });
}

function handleSetEarlyFeatures(
    msg: Extract<ExtensionToWebview, { command: "setEarlyFeatures" }>,
    ctx: PreviewMessageContext,
): void {
    previewStore.setState({ earlyFeaturesEnabled: !!msg.enabled });
    if (!ctx.earlyFeatures()) {
        document
            .querySelectorAll(".preview-diff-overlay")
            .forEach((overlay) => overlay.remove());
        // Tear down every a11y rendering surface — finding legends, finding-
        // overlay boxes, and the daemon-attached hierarchy overlay — and
        // drop the cached findings/nodes so re-enabling the feature picks
        // up fresh data from the next setPreviews / updateA11y.
        document
            .querySelectorAll(
                ".a11y-legend, .a11y-overlay, .a11y-hierarchy-overlay",
            )
            .forEach((el) => el.remove());
        clearCardA11yFindings();
        clearCardA11yNodes();
        ctx.inspector.clearProducts();
        const a11yId = ctx.getA11yOverlayId();
        if (a11yId) {
            ctx.vscode.postMessage({
                command: "setA11yOverlay",
                previewId: a11yId,
                enabled: false,
            });
            ctx.setA11yOverlayId(null);
        }
        ctx.liveState.handleEarlyFeaturesDisabled();
    }
    ctx.applyLayout();
}

function assertNever(value: never): never {
    throw new Error(
        `Unhandled ExtensionToWebview variant: ${JSON.stringify(value)}`,
    );
}
