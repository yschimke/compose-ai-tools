// The guard on every URL this lane hands to a canvas.
//
// The same one `viewer.js` puts on the spec raster and the Wasm frame: a server-set attribute is
// still DOM text, and a `javascript:` or cross-origin `data:` URL must never reach `drawImage` even
// if the attribute were mis-set. Resolving against the page's own origin and then comparing is what
// makes that true by construction rather than by pattern-matching the string.

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
