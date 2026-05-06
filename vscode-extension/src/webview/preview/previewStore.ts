// Singleton state for the live "Compose Preview" panel.
//
// Migration scope: the panel-level scalars that components want to
// subscribe to plus the per-preview Maps (`cardCaptures`,
// `cardA11yFindings`, `cardA11yNodes`) that the upcoming
// `<preview-card>` Lit component will subscribe to. Owning the Maps
// here lets components react to per-card churn without `behavior.ts`
// holding the only references — step 1 of issue #857.
//
// Versioned-counter pattern: the Maps are MUTABLE references. Mutating
// the same Map in place would defeat the store's reference-equality
// change detection (a `Map.set` does not change the Map's identity),
// but `updateImage` fires per frame in live mode so allocating a fresh
// Map for every mutation is too churny. Instead, callers mutate the
// Maps in place via the helpers exported below — each helper bumps
// `mapsRevision`, a single monotonic counter that subscribers can use
// as a selector to detect "something in the per-preview caches
// changed". Step 2 of issue #857 (the `<preview-card>` shell) wires
// the actual subscriptions; this module only owns the state and the
// mutation choreography.
//
// Outside this module, treat the Maps as read-only — every mutation
// must go through one of the helpers below so the revision counter
// stays in sync.

import type {
    AccessibilityFinding,
    AccessibilityNode,
    PreviewInfo,
} from "../shared/types";
import type { CapturePresentation } from "./frameCarousel";
import { Store } from "../shared/store";

export interface PreviewState {
    /**
     * Reflects `composePreview.earlyFeatures.enabled`. Starts at
     * `false`; `setupPreviewBehavior` seeds it from its parameter at
     * panel boot, and the `setEarlyFeatures` extension message
     * updates it at runtime.
     */
    earlyFeaturesEnabled: boolean;

    /**
     * `previewId` of the card whose accessibility overlay subscription
     * is currently active, or `null` when no card is subscribed. Set by
     * `toggleA11yOverlay` and cleared on focus navigation, daemon
     * restart, panel scope change, etc.
     */
    a11yOverlayPreviewId: string | null;

    /**
     * `previewId` the panel is currently focused on (focus mode), or
     * `null` outside focus mode / when no card is selected. Same value
     * the `previewScopeChanged` message publishes upstream — written
     * here so subscribers can react without re-walking the DOM.
     */
    focusedPreviewId: string | null;

    /**
     * Reflects `composePreview.streaming.enabled`. When true, the LIVE
     * button posts `requestStreamStart` instead of `setInteractive` so
     * the new `composestream/1` painter takes over from the legacy
     * `<img src=…>` swap. Updated at boot (initial extension seed) and
     * on the `setStreamingEnabled` extension message.
     */
    streamingEnabled: boolean;

    /**
     * Latest manifest from the extension's `setPreviews` message. The
     * canonical source of preview metadata that `<preview-card>` will
     * subscribe to once that component lands. Replaced (not mutated)
     * on every `setPreviews` so reference-equality change detection
     * works.
     */
    allPreviews: readonly PreviewInfo[];

    /**
     * Module directory the latest manifest came from — used to resolve
     * relative `previewMetadata.sourceFile` paths back to workspace
     * URIs. Empty string before the first `setPreviews`.
     */
    moduleDir: string;

    /**
     * Index into `getVisibleCards()` for the focus-mode-active card.
     * Bounded to `[0, visible.length - 1]`. Meaningful only in focus
     * layout; other layouts retain the value so re-entering focus
     * lands on the same card.
     */
    focusIndex: number;

    /**
     * Layout to fall back to when the user exits focus mode. Captured
     * whenever we transition into focus from another layout (dropdown
     * change, dblclick on a card). Defaults to `"grid"` so the very
     * first exit lands somewhere sensible.
     */
    previousLayout: "grid" | "flow" | "column";

    /**
     * Last `previewId` published to the extension via
     * `previewScopeChanged`. Tracked here so we don't spam the History
     * panel with redundant re-scopes (e.g. layout reapplies on every
     * filter tweak).
     */
    lastScopedPreviewId: string | null;

    /**
     * Per-preview carousel runtime state — `imageData` / `errorMessage`
     * / `renderError` per capture. Populated by `buildPreviewCard` /
     * `updateCardMetadata`, mutated by `updateImage` / `setImageError`,
     * pruned by `renderPreviews` when a preview disappears from the
     * manifest. MUTABLE — bump `mapsRevision` after every change via
     * the helpers below.
     */
    cardCaptures: Map<string, CapturePresentation[]>;

    /**
     * Per-preview a11y findings cache — populated by `applyA11yUpdate`
     * from daemon `a11y/atf` payloads (and rebuilt from `setPreviews`
     * by `renderPreviews`), re-read on every image (re)load to repaint
     * the finding overlay. MUTABLE — bump `mapsRevision` after every
     * change.
     */
    cardA11yFindings: Map<string, readonly AccessibilityFinding[]>;

    /**
     * Per-preview a11y hierarchy nodes cache — same shape, populated
     * from `a11y/hierarchy` payloads. MUTABLE — bump `mapsRevision`
     * after every change.
     */
    cardA11yNodes: Map<string, readonly AccessibilityNode[]>;

    /**
     * Monotonic counter bumped on every mutation of `cardCaptures` /
     * `cardA11yFindings` / `cardA11yNodes`. Subscribers select on this
     * field to detect "something in the per-preview caches changed"
     * without us having to allocate a fresh Map per `Map.set`. The
     * counter is the only reference-equality-friendly signal the
     * store's change detection has against the in-place Map mutations.
     */
    mapsRevision: number;
}

const initialState: PreviewState = {
    earlyFeaturesEnabled: false,
    a11yOverlayPreviewId: null,
    focusedPreviewId: null,
    streamingEnabled: false,
    allPreviews: [],
    moduleDir: "",
    focusIndex: 0,
    previousLayout: "grid",
    lastScopedPreviewId: null,
    cardCaptures: new Map(),
    cardA11yFindings: new Map(),
    cardA11yNodes: new Map(),
    mapsRevision: 0,
};

export const previewStore = new Store<PreviewState>(initialState);

/**
 * Bump the maps-revision counter so subscribers selecting on
 * `mapsRevision` see a change. Callers that mutate any of the
 * per-preview Maps in place MUST call this afterwards. The dedicated
 * `setCardCaptures` / `deleteCardCaptures` / etc. helpers below already
 * call this — only reach for it directly when a single logical update
 * spans several Map mutations and you want one notification at the
 * end (e.g. `renderPreviews`'s manifest reseed).
 */
export function bumpPreviewMapsRevision(): void {
    const cur = previewStore.getState().mapsRevision;
    previewStore.setState({ mapsRevision: cur + 1 });
}

/** Replace the captures array for a previewId. */
export function setCardCaptures(
    previewId: string,
    captures: CapturePresentation[],
): void {
    previewStore.getState().cardCaptures.set(previewId, captures);
    bumpPreviewMapsRevision();
}

/** Drop the captures entry for a previewId (preview removed from
 *  manifest). Bumps revision only if there was actually an entry. */
export function deleteCardCaptures(previewId: string): void {
    if (previewStore.getState().cardCaptures.delete(previewId)) {
        bumpPreviewMapsRevision();
    }
}

/** Replace the a11y findings list for a previewId. */
export function setCardA11yFindings(
    previewId: string,
    findings: readonly AccessibilityFinding[],
): void {
    previewStore.getState().cardA11yFindings.set(previewId, findings);
    bumpPreviewMapsRevision();
}

/** Drop the a11y findings entry for a previewId. Bumps revision only
 *  if there was actually an entry. */
export function deleteCardA11yFindings(previewId: string): void {
    if (previewStore.getState().cardA11yFindings.delete(previewId)) {
        bumpPreviewMapsRevision();
    }
}

/** Replace the a11y hierarchy nodes for a previewId. */
export function setCardA11yNodes(
    previewId: string,
    nodes: readonly AccessibilityNode[],
): void {
    previewStore.getState().cardA11yNodes.set(previewId, nodes);
    bumpPreviewMapsRevision();
}

/** Drop the a11y hierarchy nodes entry for a previewId. Bumps revision
 *  only if there was actually an entry. */
export function deleteCardA11yNodes(previewId: string): void {
    if (previewStore.getState().cardA11yNodes.delete(previewId)) {
        bumpPreviewMapsRevision();
    }
}

/** Wipe the a11y findings cache (e.g. on `setPreviews` reseed or
 *  `setEarlyFeatures` off). No-op + no revision bump if already empty. */
export function clearCardA11yFindings(): void {
    const map = previewStore.getState().cardA11yFindings;
    if (map.size === 0) return;
    map.clear();
    bumpPreviewMapsRevision();
}

/** Wipe the a11y hierarchy nodes cache. No-op if already empty. */
export function clearCardA11yNodes(): void {
    const map = previewStore.getState().cardA11yNodes;
    if (map.size === 0) return;
    map.clear();
    bumpPreviewMapsRevision();
}
