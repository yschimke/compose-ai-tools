// Daemon-attached a11y refresh DOM operations — handles `updateA11y`
// from the extension. Lifted out of `cardBuilder.ts` so the imperative
// DOM operation is testable under happy-dom without dragging
// cardBuilder's wider transitive imports (PreviewCard, FocusInspector,
// FrameCarousel, …) into the host tsconfig.
//
// The cache mutators (`setCardA11yFindings`, `deleteCardA11yFindings`,
// `setCardA11yNodes`, `deleteCardA11yNodes`) are passed in as
// callbacks rather than imported from `previewStore` directly: that
// keeps this module narrow (only `a11yOverlay.ts` + `cardData.ts` from
// the webview tree, plus `shared/types`) and lets tests stub the
// per-preview Maps without standing up the full store. `cardBuilder.ts`
// re-exports the helper bound to the real `previewStore` mutators so
// existing call sites are unchanged.

import { sanitizeId } from "./cardData";
import type {
    AccessibilityFinding,
    AccessibilityNode,
    PreviewInfo,
} from "../shared/types";

/** Narrow inspector surface — `applyA11yUpdate` only ever calls
 *  `render(card)` to repaint the focus inspector when fresh a11y data
 *  lands for the focused card. Structural typing keeps
 *  `FocusInspectorController` out of this module. */
export interface InspectorRenderer {
    render(card: HTMLElement): void;
}

/** Subset of `CardBuilderConfig` plus injected cache mutators that
 *  `applyA11yUpdate` reaches for. The mutators are injected (rather
 *  than imported from `previewStore`) so this module's import surface
 *  stays narrow enough to fit the host tsconfig — see file header. */
export interface A11yUpdateConfig {
    /** Latest `setPreviews` manifest snapshot. `applyA11yUpdate`
     *  mutates the matching entry's `a11yFindings` so legend rebuilds
     *  via `buildA11yLegend(card, p)` see the fresh findings without a
     *  separate parameter. */
    getAllPreviews(): readonly PreviewInfo[];
    /** Focus-inspector handle so we can re-render when a11y data lands
     *  for the focused card. */
    inspector: InspectorRenderer;
    /** Gates the whole operation — daemon-attached a11y data is
     *  dropped silently when the user has not opted into the
     *  accessibility-overlay feature surface. */
    earlyFeatures(): boolean;
    /** Whether the panel is currently in focus mode. */
    inFocus(): boolean;
    /** The currently focused card element, or null outside focus mode
     *  / when no card is selected. */
    focusedCard(): HTMLElement | null;
    /** Replace the a11y findings list for a previewId in the cache. */
    setCardA11yFindings(
        previewId: string,
        findings: readonly AccessibilityFinding[],
    ): void;
    /** Drop the a11y findings entry for a previewId. */
    deleteCardA11yFindings(previewId: string): void;
    /** Replace the a11y hierarchy nodes for a previewId. */
    setCardA11yNodes(
        previewId: string,
        nodes: readonly AccessibilityNode[],
    ): void;
    /** Drop the a11y hierarchy nodes entry for a previewId. */
    deleteCardA11yNodes(previewId: string): void;
}

/**
 * D2 — handles `updateA11y` from the extension (daemon-attached a11y data
 * products). Updates the per-preview caches and re-applies whichever
 * overlays are now relevant without rebuilding the whole card. Findings
 * → legend + finding overlay; nodes → hierarchy overlay. Either argument
 * may be omitted to leave that side untouched. Gated on `earlyFeatures()`
 * so daemon-attached a11y data is dropped silently when the user has not
 * opted into the accessibility-overlay feature surface.
 */
export function applyA11yUpdate(
    previewId: string,
    findings: readonly AccessibilityFinding[] | null | undefined,
    nodes: readonly AccessibilityNode[] | null | undefined,
    config: A11yUpdateConfig,
): void {
    if (!config.earlyFeatures()) return;
    const card = document.getElementById("preview-" + sanitizeId(previewId));
    if (!card) return;
    if (findings !== undefined) {
        if (findings && findings.length > 0) {
            // Mutate the manifest entry's findings so downstream surfaces
            // (focus inspector, A11y bundle tab) see the fresh list.
            // The legacy `.a11y-overlay` stamp is gone — the A11y
            // bundle owns the on-image paint via `cardBundleOverlay`
            // after #1087; this helper just keeps caches + manifest in
            // sync so `refreshA11yBundle` finds the latest data.
            const p = config.getAllPreviews().find((pp) => pp.id === previewId);
            if (p) p.a11yFindings = [...findings];
            config.setCardA11yFindings(previewId, findings);
        } else {
            config.deleteCardA11yFindings(previewId);
        }
    }
    if (nodes !== undefined) {
        if (nodes && nodes.length > 0) {
            config.setCardA11yNodes(previewId, nodes);
        } else {
            config.deleteCardA11yNodes(previewId);
        }
    }
    if (config.inFocus() && config.focusedCard() === card) {
        config.inspector.render(card);
    }
}
