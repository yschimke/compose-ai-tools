// The floating "Report a problem" launcher, and the one decision it exists to make plain: whether
// what is wrong belongs to the preview SERVER or to the CATALOG.
//
// Those go to different repositories and always have — the server's own tracker for the page, the
// controls and the render lanes; the catalog's own for a preview that draws the wrong thing — but
// until this existed the distinction was a sentence on `/report-bug`, which is a page you only
// reach after choosing. A report in the wrong tracker reaches people who cannot act on it, so the
// panel states the split before the choice and names the repository each half files against.
//
// Lives in the page-shell bundle because the launcher is on every page, and it is small on purpose:
// the capture machinery it can reach is a separate bundle fetched on first use, so a visitor who
// never reports anything pays for a `<details>` and this file.

import { whenReady } from "../dom/whenReady.js";

/** Load the capture bundle named by a `data-cp-capture-src` on the page, at most once. */
function loadCapture(): void {
    const host = document.querySelector<HTMLElement>("[data-cp-capture-src]");
    const src = host?.getAttribute("data-cp-capture-src");
    if (!src || document.querySelector(`script[data-cp-capture]`)) return;
    const script = document.createElement("script");
    // Same-origin by construction: the value is an asset href this server rendered. Resolving it
    // against the page's own origin and refusing anything else keeps that true by construction
    // rather than by trusting the attribute, which is DOM text like any other.
    const url = new URL(src, location.href);
    if (url.origin !== location.origin) return;
    script.src = url.href;
    script.defer = true;
    script.setAttribute("data-cp-capture", "1");
    document.body.appendChild(script);
}

/**
 * Offer the catalog half, on the pages that have one.
 *
 * The per-preview affordance is already in the page — `<details id="cp-report">`, emitted beside
 * the preview's "source" link — and it already knows the derived repository, published on
 * `data-cp-repo`. So the launcher does not build a second report: it names the destination and
 * takes you to the one that is there. On a page with no preview (the front door, `/status`, a
 * catalog that failed to load) the entry stays hidden, which is the truth — there is no catalog
 * bug to file from a page that is showing no catalog.
 */
function wireCatalogChoice(): void {
    const choice = document.querySelector<HTMLAnchorElement>(".cp-fab-catalog");
    const report = document.querySelector<HTMLElement>("#cp-report");
    if (!choice || !report) return;
    // The destination is completed HERE rather than server-side, because only this page knows it:
    // the repo is derived per catalog and published on the affordance the launcher points at. The
    // named form is the one that matters — "goes to the catalog's own repository" is true and
    // useless next to "goes to `acme/widgets`" — so the generic wording is only the fallback for a
    // page whose affordance published no repo.
    const repo = report.getAttribute("data-cp-repo") || "";
    const who = choice.querySelector<HTMLElement>(".cp-fab-who");
    if (who && repo) {
        const code = document.createElement("code");
        code.textContent = repo;
        // `append`, not an HTML string: the repo is derived from a catalog's own manifest.
        who.append(" — goes to ", code);
    } else if (who) {
        who.append(" — goes to the catalog's own repository");
    }
    choice.hidden = false;
    choice.addEventListener("click", (event) => {
        event.preventDefault();
        const menu = document.querySelector<HTMLDetailsElement>(".cp-fab-menu");
        if (menu) menu.open = false;
        if (report instanceof HTMLDetailsElement) report.open = true;
        report.scrollIntoView({ block: "center", behavior: "smooth" });
        // Focus the field the reporter has to fill in — the Summary is `required`, so landing
        // anywhere else means a second click before they can start typing.
        report
            .querySelector<HTMLInputElement>(".cp-report-summary-input")
            ?.focus({ preventScroll: true });
    });
}

export function installReportLauncher(): void {
    // Deferred until the document is parsed. This bundle is the first element in `<body>`, so at
    // evaluation time the launcher — which `ServeWeb.document` emits after `<main>` — does not
    // exist yet, and every query below would find nothing and no-op for the life of the page.
    whenReady(install);
}

function install(): void {
    const fab = document.querySelector<HTMLElement>(".cp-fab");
    if (fab) {
        wireCatalogChoice();
        const menu = fab.querySelector<HTMLDetailsElement>(".cp-fab-menu");
        // Fetched when the panel first opens rather than on load: see the header. `toggle` fires
        // for close as well, hence the guard, and the listener stays because `loadCapture` is the
        // thing that is idempotent.
        menu?.addEventListener("toggle", () => {
            if (menu.open) loadCapture();
        });
    }
    // `/report-bug` has no launcher — it IS where the launcher leads — but it renders the captures
    // that came across from the page being reported, so it needs the bundle immediately.
    if (document.querySelector(".cp-shots")) loadCapture();
}
