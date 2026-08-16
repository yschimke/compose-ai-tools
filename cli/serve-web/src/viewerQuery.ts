// `window.cpViewerQuery` — the render-URL rules, for `viewer.js` to call.
//
// NOT a custom element, and deliberately a global, for the same reason `rcFonts.ts` is one: the
// consumer is a 3,000-line untyped IIFE with a mode machine threaded through shared mutable state,
// and moving the whole thing in one change would be unreviewable. Moving the RULES first buys the
// type check and the tests (`viewer/renderQuery.ts` is DOM-free and has a table of cases); changing
// the seam can wait for the element that replaces the IIFE.
//
// `viewer.js` reads this handle at CALL time, inside `query()`, `syncThemeBar()` and friends — never at its own IIFE
// time. Nothing orders the two script tags, and a handle cached at load would be `null` on any page
// that emits them the other way round. That failure would be silent: the URL rules would simply
// stop applying.

import { fitCap, needsRefit, zoomMode } from "./viewer/fit.js";
import {
    activeThemeChoice,
    chosenThemeProvider,
    chosenUiMode,
    themeBarButton,
} from "./viewer/themeChoice.js";
import {
    explodeParamOn,
    explodeParams,
    knobEmitted,
    rcKnobEmitted,
    rcKnobValue,
    sizeOverrides,
    sizePx,
    withScroll,
    withSnapshotFormat,
} from "./viewer/renderQuery.js";

const api = {
    fitCap,
    zoomMode,
    needsRefit,
    activeThemeChoice,
    chosenUiMode,
    chosenThemeProvider,
    themeBarButton,
    knobEmitted,
    rcKnobEmitted,
    rcKnobValue,
    explodeParamOn,
    explodeParams,
    withScroll,
    withSnapshotFormat,
    sizePx,
    sizeOverrides,
};

declare global {
    interface Window {
        cpViewerQuery?: typeof api;
    }
}

window.cpViewerQuery = api;
