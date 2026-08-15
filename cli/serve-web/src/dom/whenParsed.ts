// Wait for the document to finish parsing before reading anything outside your own subtree.
//
// A light-DOM element is upgraded the moment the parser reaches its tag, which is BEFORE the rest
// of the page exists. Every component here that reads a sibling — `.cp-viewer`'s data attributes,
// the parity feed's rows, the comparison table's cells — is therefore reading a document that is
// still being built if it reads at connect time.
//
// The served pages get away with it only because their script tags happen to sit near the end of
// `<body>`, which is correct-by-accident that any reordering breaks, and which is not true at all
// of a document assembled in one `innerHTML` write — as `<cp-history-menu>`'s tests found. One
// microtask on an already-parsed document, one `DOMContentLoaded` otherwise, and the ordering
// assumption is gone.

export function whenParsed(): Promise<void> {
    if (document.readyState !== "loading") return Promise.resolve();
    return new Promise((resolve) =>
        document.addEventListener("DOMContentLoaded", () => resolve(), {
            once: true,
        }),
    );
}
