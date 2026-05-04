/**
 * Short, lossy "X{s,m,h,d} ago" formatter for ISO-8601 timestamps. Used by
 * the panel's render-history hover and a few status-bar tooltips where
 * the column budget is tight.
 *
 * Pulled out of `extension.ts` so the rounding boundaries are testable
 * without spinning up the extension host.
 */

/**
 * @param iso  ISO-8601 timestamp (or undefined). When undefined,
 *             returns `"(unknown)"`. When unparseable, returns the input
 *             verbatim — surfacing garbage to the user is more useful
 *             than silently swallowing it.
 * @param now  Override for "now", in epoch milliseconds. Default reads
 *             `Date.now()`. Tests pass a fixed value so the assertion
 *             doesn't race the wall clock.
 */
export function formatRelativeShort(
    iso: string | undefined,
    now: () => number = Date.now,
): string {
    if (!iso) {
        return "(unknown)";
    }
    const t = Date.parse(iso);
    if (isNaN(t)) {
        return iso;
    }
    const s = Math.round((now() - t) / 1000);
    if (s < 60) {
        return s + "s ago";
    }
    const m = Math.round(s / 60);
    if (m < 60) {
        return m + "m ago";
    }
    const h = Math.round(m / 60);
    if (h < 24) {
        return h + "h ago";
    }
    const d = Math.round(h / 24);
    return d + "d ago";
}
