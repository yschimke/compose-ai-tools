import { MissingImageDataProduct } from "./scrollDataProductRender";

/**
 * Viewport-aware filter for the `@ScrollingPreview` Gradle backfill. The naive trigger
 * from the first cut fired `composePreviewRender('full')` whenever ANY preview in the
 * active file's scope had a missing image data product, on every refresh — wasting a
 * Gradle invocation on a module's full preview set even when the user couldn't see the
 * affected cards.
 *
 * This helper takes the visible + predicted preview ids reported by the webview's
 * `viewportTracker` (the same set already used to drive the daemon's `setVisible`) and
 * groups the missing products into the modules whose missing previews intersect that
 * set. Modules whose missing previews are entirely outside the viewport are skipped —
 * the user hasn't asked to look at them, so we don't pay the Gradle cost yet.
 *
 * Predicted ids (cards the panel believes the user is about to scroll into) count as
 * viewport intersections too: pre-warming the backfill before the card lands keeps the
 * placeholder window short, matching the daemon's existing speculative-renderNow path.
 */
export interface ViewportBackfillCandidate {
    modulePath: string;
    missing: readonly MissingImageDataProduct[];
}

export function modulesNeedingViewportBackfill(
    missingByModule: ReadonlyMap<string, readonly MissingImageDataProduct[]>,
    visible: readonly string[],
    predicted: readonly string[],
    alreadyRequested: ReadonlySet<string>,
): ViewportBackfillCandidate[] {
    if (missingByModule.size === 0) return [];
    const want = new Set<string>([...visible, ...predicted]);
    if (want.size === 0) return [];
    const result: ViewportBackfillCandidate[] = [];
    for (const [modulePath, missing] of missingByModule) {
        if (alreadyRequested.has(modulePath)) continue;
        if (!missing.some((m) => want.has(m.previewId))) continue;
        result.push({ modulePath, missing });
    }
    return result;
}
