// Run something once the document has a `<body>` to query — synchronously when it already does.
//
// Load-bearing for everything in the page shell, and not defensive. `ServeWeb.document` emits
// `serve-chrome.js` as the FIRST element in `<body>`, ahead of every surface's own scripts, and the
// tag carries no `defer` — so at evaluation time neither the footer, nor the report launcher, nor
// the page's own content has been parsed. An installer that queries eagerly finds nothing and
// no-ops permanently, and every such failure so far has been silent: the footer submitted an empty
// `from` (losing the page context and, on a gated host, the token, so `/report-bug` 404'd), the
// report page never added its browser section, and the launcher never offered its catalog half.
// The affordance still looked right in all three cases.
//
// Callback rather than a promise — the shape `dom/whenParsed.ts` gives the Lit components — for one
// reason: on an already-parsed document this runs the work in the SAME task, which is what lets the
// installers be driven straight through in a test without a flush between arranging the DOM and
// asserting on it. A promise would defer even the already-ready case by a microtask.

export function whenReady(fn: () => void): void {
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", fn, { once: true });
    } else {
        fn();
    }
}
