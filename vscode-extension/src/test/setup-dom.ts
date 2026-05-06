// Mocha setup file that registers a global `document` / `window` /
// `HTMLElement` / etc. via happy-dom so tests can exercise webview
// modules that touch the DOM without each test having to spin up its
// own environment.
//
// Loaded via `.mocharc.json`'s `file` field — runs once before any
// `describe(...)` block.
//
// happy-dom is the lightest of the three popular DOM impls (jsdom,
// happy-dom, linkedom). The webview DOM ops we test against (`<div>`
// creation, classList toggles, dataset access, querySelector,
// getBoundingClientRect, getElementById, IntersectionObserver shape)
// all work under happy-dom; if a test needs something happy-dom
// doesn't ship — most commonly element-resize observers or the full
// CSSOM — switch the test to a hand-rolled stub for that one case
// rather than swapping the global setup.
//
// Tests that operate on pure data (Store, formatRelative,
// formatDiffStatsLabel, etc.) ignore this file entirely — Node's
// global namespace has document/window after registration but pure
// helpers don't reach for them.

import { GlobalRegistrator } from "@happy-dom/global-registrator";

GlobalRegistrator.register();
