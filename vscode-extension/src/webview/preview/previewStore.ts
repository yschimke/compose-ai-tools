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
}

const initialState: PreviewState = {
    earlyFeaturesEnabled: false,
};

export const previewStore = new Store<PreviewState>(initialState);
