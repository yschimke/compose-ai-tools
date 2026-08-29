// `window.cpRcFonts` — make the page's registered faces paintable BEFORE a Remote Compose lane
// paints. Replaces `assets/rc-fonts.js`.
//
// `@font-face` is lazy and canvas does not drive it: `ctx.font` neither triggers a load nor waits
// for one, and a canvas asked for an unloaded face silently paints the fallback — no
// `font-display` swap, no repaint. So a page can carry exactly the right `@font-face` block
// (`/rc-fonts/fonts.css`, see `ServeRcFonts`) and still draw its first frames in the viewer's own
// generics, which is the whole bug this exists to close (#3480). `document.fonts.ready` is not
// enough either: it resolves while a declared face is still `unloaded`.
//
// The faces are read out of `document.fonts` rather than restated here. The stylesheet is
// generated from `ServeRcFonts.FACES`, so iterating the registry keeps this automatically in step
// with it — and with the parity harness's table — instead of being a third list to keep in sync.
// Named families the player fetches for itself are unaffected: those are added to the registry
// later and the player repaints through `onFontLoaded`.
//
// NOT a custom element, and deliberately still a global. Its consumers include page-level code
// (`viewer.js`, `format-compare.js`, the inline doc-player script), which read
// `window.cpRcFonts` at call time and already guard for its absence. Moving the implementation
// into this package buys the type check and the tests; changing the *seam* would mean editing
// three untyped callers in the same change, which is the opposite of one reviewable step.

/** Resolves once every declared face has settled — loaded or definitively not. */
export type RcFontsReady = () => Promise<unknown>;

declare global {
    interface Window {
        cpRcFonts?: { ready: RcFontsReady };
    }
}

let pending: Promise<unknown> | null = null;

function loadDeclaredFaces(): Promise<unknown> {
    if (typeof document === "undefined" || !document.fonts) {
        return Promise.resolve();
    }
    const loads: Array<Promise<unknown>> = [];
    try {
        document.fonts.forEach((face) => {
            if (face.status === "loaded") return;
            try {
                // A rejected load is a face that will paint as its fallback. That is a worse
                // render, not a broken one, so it must not reach the caller as a failure.
                loads.push(face.load().catch(() => {}));
            } catch {
                // A face the browser cannot even start loading is a fallback, not a failure.
            }
        });
    } catch {
        // No iterable registry (an old engine, a locked-down document): nothing to preload, and
        // the lane still paints in the fallback face.
        return Promise.resolve();
    }
    // `.catch` on the whole chain, not a rejection handler on the `Promise.all` — the shape the
    // original used (`.then(onOk, onErr)`) left the `document.fonts.ready` returned by `onOk`
    // uncovered, so a rejecting registry would have rejected `ready()` in spite of the contract
    // right above. The spec says `fonts.ready` does not reject, which is why nobody hit it; a
    // guarantee worth writing down is worth actually holding.
    return Promise.all(loads)
        .then(() => document.fonts.ready)
        .catch(() => {});
}

/**
 * Memoized, and it NEVER rejects: a font that fails to load has to degrade to the fallback face,
 * not stop the lane from rendering at all. Every caller shares one pass over the registry — the
 * viewer can ask on its first paint and the compare wall on each lane without re-walking it.
 */
export function ready(): Promise<unknown> {
    if (!pending) pending = loadDeclaredFaces();
    return pending;
}

/** Test seam: drop the memoized pass so each case starts from a fresh registry. */
export function resetForTest(): void {
    pending = null;
}

window.cpRcFonts = { ready };
