// `window.cpUrlState` — the one place that writes a page's selection into the address bar.
// Replaces `assets/url-state.js`.
//
// Every serve surface keeps some selection client-side: the catalog grid's section tab, theme chip,
// filter text and stage backing; the viewer's overrides and knobs; the compare page's format and
// theme. Held only in `localStorage`, the URL of the page someone was looking at described a
// *different* page than the one on their screen — a link to `/meshcore-mobile/` reopened on whatever
// the last visitor to that browser had picked, and "Components, Dynamic Dark" could not be
// bookmarked or shared at all.
//
// It only ever touches the params a page declares it owns, so `token` / `session` and anything else
// the server put there survive untouched, and it never navigates: a state change is a `pushState` /
// `replaceState`, so nothing reloads and no render is re-requested.
//
// `push` for a discrete choice (a tab, a theme, a mode) so Back returns to the previous one;
// `replace` for continuous input (typing in a filter, dragging a slider) so one search does not bury
// the page the visitor arrived from under twenty history entries.

import type { UrlState } from "../urlState.js";

type Values = Record<string, string | null | undefined>;

function params(): URLSearchParams {
    return new URLSearchParams(location.search);
}

/** `""` for an absent param, so callers treat missing and empty as the same "not chosen". */
function get(name: string): string {
    return params().get(name) ?? "";
}

function urlFor(next: URLSearchParams): string {
    const query = next.toString();
    return location.pathname + (query ? `?${query}` : "") + location.hash;
}

function write(next: URLSearchParams, replace: boolean): void {
    const url = urlFor(next);
    if (url === location.pathname + location.search + location.hash) return;
    try {
        if (replace) history.replaceState(history.state, "", url);
        else history.pushState(history.state, "", url);
    } catch {
        // A page in an opaque origin (a sandboxed iframe) throws SecurityError on either call. The
        // URL is a nicety there; the page itself must keep working.
    }
}

/**
 * Set or clear the named params, leaving every other one alone. An empty (or null/undefined) value
 * clears the param rather than writing `?tab=`, so the default state is the clean URL a visitor can
 * be handed.
 */
function apply(values: Values, replace: boolean): void {
    const next = params();
    for (const [name, value] of Object.entries(values)) {
        if (value === null || value === undefined || value === "")
            next.delete(name);
        else next.set(name, String(value));
    }
    write(next, replace);
}

/**
 * Replace a whole *slice* of the query: every param `owned` claims is dropped unless `values`
 * supplies it. The viewer needs this because its knob params are open-ended (`knob.<key>`) —
 * clearing a knob has to remove the param, which a per-name update cannot express.
 */
function sync(
    values: Values,
    owned: (name: string) => boolean,
    replace: boolean,
): void {
    const next = params();
    const stale: string[] = [];
    next.forEach((_, name) => {
        if (owned(name) && !(name in values)) stale.push(name);
    });
    for (const name of stale) next.delete(name);
    for (const [name, value] of Object.entries(values)) {
        if (value === null || value === undefined || value === "")
            next.delete(name);
        else next.set(name, String(value));
    }
    write(next, replace);
}

export const urlStateApi: UrlState = {
    get,
    push: (values) => apply(values, false),
    replace: (values) => apply(values, true),
    sync,
    // Returns its own unsubscribe. `serve-chrome.js` is the sole owner of this global now that
    // `url-state.js` is gone, so there is no legacy caller to keep a `void` return for — and a
    // subscriber that can outlive its subscription (a custom element that is detached and
    // reinserted) needs one, or every reconnection stacks another callback on the same event.
    onPop: (callback) => {
        window.addEventListener("popstate", callback);
        return () => window.removeEventListener("popstate", callback);
    },
};

/**
 * Publish the global. Called at module evaluation, which is why `serve-chrome.js` has to be
 * evaluated before the component bundle and before the legacy scripts — all of them read
 * `window.cpUrlState`, and `backgroundChoice.ts` reads it as its element connects.
 */
export function installUrlState(): void {
    window.cpUrlState = urlStateApi;
}
