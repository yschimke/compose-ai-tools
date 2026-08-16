// Entry point for the page-shell bundle (`assets/serve-chrome.js`).
//
// What EVERY serve page needs, and nothing else: the URL-state global and the Page theme setting.
// `ServeWeb.document` emits this unconditionally — the front door and `/status` included — so it
// must stay small and must carry no Lit. Neither module here is a custom element, which is the
// whole reason this is a second bundle rather than more of `main.ts`.
//
// Order matters, and this file is where it is stated: `installUrlState` publishes
// `window.cpUrlState` at evaluation, and the component bundle plus `format-compare.js`,
// `rc-lanes.js` and `spec-compare.js` all read that global. So the shell emits this bundle ahead of
// every surface's own scripts, and everything downstream can assume it.

import { installUrlState } from "./chrome/installUrlState.js";
import { installPageTheme } from "./chrome/pageTheme.js";
import {
    installBugReportBody,
    installBugReportLink,
} from "./chrome/bugReport.js";

installUrlState();
installPageTheme();
// The footer's "report a bug" form is emitted by `ServeWeb.document`, so it is on every page — which
// is exactly why its wiring is here rather than in a surface bundle. Both calls no-op when their
// elements are absent, and neither depends on the two above.
installBugReportLink();
installBugReportBody();
