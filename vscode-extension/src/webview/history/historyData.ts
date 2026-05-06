// Pure data helpers for the History panel.
//
// Lifted out of `behavior.ts`'s `escapeHtml` / `cssEscape` /
// `formatRelative` / `formatAbsolute` / `findLatestMainHash` so the
// timestamp / escape / "latest main hash" logic is unit-testable in
// isolation. None of these reach into the DOM or the vscode handle —
// everything is a pure string / number / Date computation.
//
// `escapeHtml` is reimplemented as a regex-based escape rather than
// the previous `div.textContent → div.innerHTML` round-trip so the
// helper can run under the host tsconfig (no DOM lib) and so tests
// don't have to spin up a DOM.

import type { HistoryEntry } from "../shared/types";

/** HTML-escape arbitrary text for safe interpolation into an
 *  `innerHTML` string. Returns the empty string for null / undefined. */
export function escapeHtml(text: unknown): string {
    return String(text ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

/** Escape characters that would break out of a CSS attribute selector
 *  string (`'`, `"`, `\`). Used when building dynamic `[data-id="..."]`
 *  selectors from untrusted history-entry ids. */
export function cssEscape(s: string): string {
    return String(s).replace(/[\\"']/g, "\\$&");
}

/** Render an ISO timestamp as a relative duration ("just now", "5m
 *  ago", "3d ago", "2y ago"). Returns the original string when the
 *  input doesn't parse as a date, and "(no timestamp)" for empty
 *  input. Uses `Date.now()` so callers needing deterministic output
 *  in tests should pass a [now] snapshot. */
export function formatRelative(
    iso: string | undefined,
    now: number = Date.now(),
): string {
    if (!iso) return "(no timestamp)";
    const t = Date.parse(iso);
    if (isNaN(t)) return iso;
    const s = Math.round((now - t) / 1000);
    if (s < 5) return "just now";
    if (s < 60) return s + "s ago";
    const m = Math.round(s / 60);
    if (m < 60) return m + "m ago";
    const h = Math.round(m / 60);
    if (h < 24) return h + "h ago";
    const d = Math.round(h / 24);
    if (d < 30) return d + "d ago";
    const mo = Math.round(d / 30);
    if (mo < 12) return mo + "mo ago";
    return Math.round(mo / 12) + "y ago";
}

/** Render an ISO timestamp as a localised absolute label (e.g.
 *  "Mar 4, 14:32"). Returns the empty string for empty / unparseable
 *  input. */
export function formatAbsolute(iso: string | undefined): string {
    if (!iso) return "";
    const t = Date.parse(iso);
    if (isNaN(t)) return "";
    try {
        return new Date(t).toLocaleString(undefined, {
            month: "short",
            day: "numeric",
            hour: "2-digit",
            minute: "2-digit",
        });
    } catch (_) {
        return "";
    }
}

/** Pick the `pngHash` of the most recent main-branch render in
 *  [entries]. Used by the timeline's "vs main" indicator dot — entries
 *  whose own `pngHash` differs from this value get a dot. Returns
 *  `null` when no main-branch entry has a `pngHash`. */
export function findLatestMainHash(
    entries: readonly HistoryEntry[],
): string | null {
    let bestTs = "";
    let bestHash: string | null = null;
    for (const e of entries) {
        if (!e || (e.git && e.git.branch) !== "main") continue;
        if (!e.pngHash) continue;
        const ts = e.timestamp || "";
        if (ts > bestTs) {
            bestTs = ts;
            bestHash = e.pngHash;
        }
    }
    return bestHash;
}
