// Entry point for the capture bundle (`assets/report-capture.js`).
//
// Its own bundle, and NOT part of `serve-chrome.js`, for one reason: the shell bundle is on every
// page including the front door, and this is several kilobytes of screen-capture, selection-overlay
// and clipboard machinery that matters only to the fraction of visits that file a report. The
// launcher fetches it when its panel is first opened (`chrome/reportLauncher.ts`), and `/report-bug`
// fetches it on load because that page's whole job is the pile of captures it renders.
//
// Loaded twice — panel opened, then the report page — is a normal outcome, so `installCapture` is
// idempotent rather than assuming it runs once.

import { installCapture } from "./report/ui.js";

if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => installCapture(), {
        once: true,
    });
} else {
    installCapture();
}
