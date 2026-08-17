// The DOM-free rules the viewer calls, gathered under one name.
//
// This is what `window.cpViewerQuery` used to be. That global existed for exactly one reason: the
// caller was an untyped IIFE in another build, so the only way to hand it a typed function was
// through the window. `viewer.ts` is now part of this package and imports these directly, so the
// aggregation is all that is left of the seam — and it is worth keeping, because it is the list of
// decisions the viewer does NOT make inline. Every one of these has a test file beside it.

export * from "./fit.js";
export * from "./keyInput.js";
export * from "./laneState.js";
export * from "./motionPlayback.js";
export * from "./ownedParams.js";
export * from "./renderQuery.js";
export * from "./specBaseline.js";
export * from "./themeChoice.js";
