// Singleton state for the live "Compose Preview" panel.
//
// Phase 6 of the migration: introduces the store with the smallest
// useful field — `earlyFeaturesEnabled` — so the pattern is concrete
// before bigger lifts. Future commits will grow the field set
// (interactive/recording previewIds, a11y overlay target, daemon
// readiness map, focus-mode index, etc.) and have new components
// subscribe via [StoreController].

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
}

const initialState: PreviewState = {
    earlyFeaturesEnabled: false,
    a11yOverlayPreviewId: null,
    focusedPreviewId: null,
};

export const previewStore = new Store<PreviewState>(initialState);
