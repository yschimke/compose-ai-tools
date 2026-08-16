// What the wall is showing, and where each half of it came from.
//
// Three sources want to pick the format and the theme — the page's own defaults, what this visitor
// last chose here, and the URL — and they are NOT equal. Getting the order wrong is not a crash; it
// is a link that opens on something other than what it says, which nobody reports because it looks
// like a page that simply chose differently.

import {
    firstAvailable,
    supportsFormat,
    type Available,
    type Format,
} from "./pairing.js";

export type Theme = "light" | "dark";

export interface WallState {
    format: Format;
    theme: Theme;
    query: string;
}

export function themeOf(value: string | null | undefined): Theme | null {
    return value === "light" || value === "dark" ? value : null;
}

/**
 * What the page opens on.
 *
 * Format: the URL wins, but only when this page can actually show it. A `?format=` naming a format
 * this catalog has no artifacts for falls back to the default rather than emptying the table — an
 * empty wall reads as "nothing matches your filter", which is a different and wrong answer.
 *
 * Theme: the URL outranks what was remembered. The remembered value is a standing preference; a
 * `?theme=` in the address bar is there because someone picked it *here* or was handed the link, and
 * a shared link that silently reverts to the reader's own preference is not the link that was sent.
 */
export function initialState(input: {
    defaults: { format: string; theme: string };
    remembered: string | null;
    params: URLSearchParams;
    available: Available;
}): WallState {
    const { defaults, remembered, params, available } = input;

    const asked = params.get("format");
    // The page's own default, then whatever this catalog actually publishes. Never a hardcoded
    // "svg": a Remote-Compose-only catalog has none, and opening on it shows an empty table.
    const fallbackFormat = supportsFormat(defaults.format, available)
        ? defaults.format
        : (firstAvailable(available) ?? "svg");
    const format = supportsFormat(asked, available) ? asked : fallbackFormat;

    const theme =
        themeOf(params.get("theme")) ??
        themeOf(remembered) ??
        themeOf(defaults.theme) ??
        "light";

    return { format, theme, query: params.get("q") ?? "" };
}

/**
 * What a Back or Forward lands on.
 *
 * An entry that names no format or theme is one from before the visitor picked either, so it
 * restores what THIS LOAD resolved to rather than the page's bare default — anything else makes Back
 * from a shared `?theme=dark` link land somewhere the visitor has never been.
 *
 * Note it does not consult the remembered theme: that is a preference for opening the page, not a
 * history entry, and letting it answer here would make Back depend on what the visitor did on some
 * other visit.
 */
export function poppedState(input: {
    initial: WallState;
    params: URLSearchParams;
    available: Available;
}): WallState {
    const { initial, params, available } = input;
    const asked = params.get("format");
    const theme = themeOf(params.get("theme"));
    return {
        format: supportsFormat(asked, available) ? asked : initial.format,
        theme: theme ?? initial.theme,
        query: params.get("q") ?? "",
    };
}
