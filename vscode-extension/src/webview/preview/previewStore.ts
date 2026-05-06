// Singleton state for the live "Compose Preview" panel.
//
// Migration scope: the panel-level scalars that components want to
// subscribe to. The big per-preview maps (`cardCaptures`,
// `cardA11yFindings`, `cardA11yNodes`) intentionally still live as
// closure state in `behavior.ts` until the `<preview-card>` Lit
// component lands — moving them in would force every `Map.set` to
// allocate a fresh Map (the store's reference-equality rule for change
// detection) which is too much churn while the imperative
// `renderPreviews` / `updateImage` paths still own them.

import type { PreviewInfo } from "../shared/types";
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
}

const initialState: PreviewState = {
    earlyFeaturesEnabled: false,
    a11yOverlayPreviewId: null,
    focusedPreviewId: null,
    allPreviews: [],
    moduleDir: "",
    focusIndex: 0,
    previousLayout: "grid",
    lastScopedPreviewId: null,
};

export const previewStore = new Store<PreviewState>(initialState);
