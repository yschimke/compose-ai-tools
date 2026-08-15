// Which preview a card streams, where its socket lives, and what to say when the lane refuses.

/** The per-card ids the server emits: `l` light, `d` dark. */
export interface CardEntry {
    l?: string;
    d?: string;
}

/** The server-emitted `window.cpCatalogLive`. An object literal in an inline script, NOT DOM text. */
export interface LiveConfig {
    base?: string;
    query?: string;
    holdMs?: number;
    signInHref?: string;
    cards?: CardEntry[];
}

/**
 * The preview a card is showing RIGHT NOW.
 *
 * A light/dark swap card carries both ids and the filter script re-points `data-bg-theme` as it
 * swaps, so the live session has to follow what is on screen rather than pinning the server-side
 * default — otherwise pressing a card in a dark grid opens the light preview it is not displaying.
 */
export function previewIdOf(
    entry: CardEntry,
    swap: boolean,
    bgTheme: string | null,
): string {
    if (!swap) return entry.l || entry.d || "";
    return bgTheme === "dark"
        ? entry.d || entry.l || ""
        : entry.l || entry.d || "";
}

/** `theme:dracula` → `dracula`. Anything else — a background choice, nothing pressed — is no provider. */
export function themeProviderOf(choice: string | null): string {
    const value = choice ?? "";
    return value.startsWith("theme:") ? value.slice("theme:".length) : "";
}

/**
 * The daemon socket for one preview.
 *
 * `wss:` follows the page's own scheme: a `ws:` socket from an https page is blocked as mixed
 * content, which surfaces as a lane that simply never connects.
 */
export function socketUrl(
    config: LiveConfig,
    previewId: string,
    location: { protocol: string; host: string },
    themeProvider = "",
): string {
    const proto = location.protocol === "https:" ? "wss:" : "ws:";
    let query = config.query ? `${config.query}&codec=webp` : "codec=webp";
    if (themeProvider)
        query += `&themeProvider=${encodeURIComponent(themeProvider)}`;
    const base = config.base ?? "";
    return `${proto}//${location.host}${base}/ws/${encodeURIComponent(previewId)}?${query}`;
}

/**
 * Why a live lane closed, in words a visitor can act on.
 *
 * Deliberately the VIEWER's wording, `viewer.js`'s `liveCloseReason` — the grid and the viewer must
 * explain a refused lane identically, or the same server condition reads as two different problems
 * depending on which page you pressed. `catalog-live.js` claimed that parity in a comment and did
 * not have it: its fallback stopped at "couldn't connect" and dropped the half that says where to
 * look. That fallback is the case that fires most (1006 — an abnormal close, typically a proxy 502
 * on the WS upgrade), so the shorter wording was what most people actually saw.
 *
 * `viewer.js` still owns its own copy; this is a mirror until that file is ported too.
 */
export function closeReason(
    event: {
        code?: number;
        reason?: string;
    } | null,
): string {
    if (event?.code === 1013)
        return "Live preview is at capacity — try again shortly.";
    if (event?.code === 1008) return "Live preview unauthorized.";
    if (event?.reason) return `Live preview unavailable: ${event.reason}`;
    return "Live preview couldn't connect — the live stream may be unavailable on this server.";
}

/**
 * Whether a pointerdown should begin a hold at all.
 *
 * Only an unmodified primary button. Every modifier here already means something on a link —
 * ctrl/meta opens a new tab, shift a new window — and claiming those for a gesture would break the
 * card's ordinary behaviour for exactly the people who navigate that way.
 */
export function startsHold(event: {
    button: number;
    ctrlKey?: boolean;
    metaKey?: boolean;
    shiftKey?: boolean;
    altKey?: boolean;
}): boolean {
    return (
        event.button === 0 &&
        !event.ctrlKey &&
        !event.metaKey &&
        !event.shiftKey &&
        !event.altKey
    );
}
