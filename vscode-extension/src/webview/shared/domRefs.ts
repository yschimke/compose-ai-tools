// Fail-fast DOM lookup helpers used at panel boot to grab the static
// elements `<preview-app>` / `<history-app>` have already rendered into
// the light DOM. Throw on miss so a missing template (e.g. an
// HTML-template typo) surfaces early rather than landing as a runtime
// null-deref deeper in the panel's setup code.
//
// Lifted out of duplicated copies in `preview/behavior.ts` and
// `history/behavior.ts` so the two panels share one source of truth.
// Both helpers are pure DOM operations — `document.getElementById` /
// `document.querySelector` — and live in `webview/shared/` so any
// future panel can pick them up without a re-implementation.

/**
 * Look up a known-present DOM element by id. Throws if `#id` is
 * missing (typo in the static HTML template, or the element rendered
 * conditionally and the lookup ran before it landed).
 */
export function requireElementById<T extends HTMLElement>(id: string): T {
    const el = document.getElementById(id);
    if (!el) throw new Error(`Required element #${id} not found`);
    return el as T;
}

/**
 * Look up a known-present DOM element by CSS selector. Throws if no
 * match. Use for custom-element queries (e.g.
 * `requireSelector<MessageBanner>("message-banner")`) where the host
 * has registered the tag and rendered exactly one instance.
 */
export function requireSelector<T extends Element>(selector: string): T {
    const el = document.querySelector<T>(selector);
    if (!el) throw new Error(`Required element ${selector} not found`);
    return el;
}
