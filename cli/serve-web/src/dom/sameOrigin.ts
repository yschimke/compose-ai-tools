// The guard on every URL that reaches a canvas or the address bar.
//
// The same one `viewer.js` puts on the spec raster and the Wasm frame: a server-set attribute is
// still DOM text, and a `javascript:` or cross-origin `data:` URL must never reach `drawImage` even
// if the attribute were mis-set. Resolving against the page's own origin and then comparing is what
// makes that true by construction rather than by pattern-matching the string.
//
// Shared rather than copied, because a security guard that exists twice is a guard that will
// eventually be tightened once. It lives under `dom/` rather than beside either caller for the same
// reason: the spec lane rasterises with it and the catalog grid navigates with it, and neither owns
// it.

/**
 * The URL if it is ours, or `""`.
 *
 * `blob:` is admitted explicitly. One minted by this page from its own fetch already is ours —
 * `new URL` reports its origin as the page's, so it would pass the comparison below on every
 * browser that implements it — but saying so keeps the intent legible rather than leaving a
 * security-relevant pass looking accidental.
 */
export function sameOrigin(
    candidate: string | null | undefined,
    origin: string,
): string {
    if (!candidate) return "";
    let url: URL;
    try {
        url = new URL(candidate, origin);
    } catch {
        // Not a URL at all. Nothing to draw.
        return "";
    }
    if (url.protocol === "blob:") return url.href;
    return url.origin === origin ? url.href : "";
}

/**
 * The same check for a URL about to become a NAVIGATION, which is stricter.
 *
 * `blob:` is refused here. A blob this page minted is safe to draw, but navigating to one hands the
 * visitor a document whose contents were assembled client-side at our own origin — a different
 * question from "may these bytes be painted", and not one a sign-in link ever needs to ask. Sharing
 * `sameOrigin` outright would have widened a navigation sink to admit it, silently, as a side effect
 * of removing a duplicate.
 */
export function sameOriginNavigation(
    candidate: string | null | undefined,
    origin: string,
): string {
    const href = sameOrigin(candidate, origin);
    return href.startsWith("blob:") ? "" : href;
}
