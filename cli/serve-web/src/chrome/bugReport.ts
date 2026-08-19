// The two browser-side halves of "report a bug in the preview server".
//
// Both live in the page shell rather than a surface bundle because the affordance itself does: the
// footer form is emitted by `ServeWeb.document`, so it is on the front door, `/status`, a 404 and
// every catalog page alike, and none of those load `main.js`. Each half no-ops when its elements
// are absent, so the cost on a page that has neither is two failed `querySelector` calls.
//
// Nothing here writes an `href` or navigates. Both functions only ever set INPUT VALUES on
// server-rendered forms whose `action` is a literal the script never touches — the rule the serve
// UI follows everywhere it puts page-derived state into a link (see `ServeIssueReport.action`).

import { whenReady } from "../dom/whenReady.js";

/** Params the report page needs from the visitor's current URL, and nothing else. */
const CARRIED = ["token"];

/**
 * Fill the footer form's hidden inputs so pressing "report a bug" arrives at `/report-bug` knowing
 * where the visitor came from.
 *
 * `from` is the page's own path + query. It is sent as a form field rather than being pre-baked
 * server-side because the URL a visitor is looking at is not the one the server rendered: the
 * viewer's controls rewrite the query as knobs change (`installUrlState`), so the served HTML knows
 * the *initial* overrides and the address bar knows the current ones. The address bar is the honest
 * answer to "what were you looking at".
 *
 * The token is copied across separately because `/report-bug` is gated like `/status`, and the
 * visitor's own token — already in their URL — is the capability that gets them in. It is *not*
 * left inside `from`: the server strips it there anyway (`ServeBugReport.sanitizeFrom`), since that
 * value is quoted into a public issue body while this one only ever reaches this server.
 */
export function installBugReportLink(): void {
    whenReady(() => {
        fillBugReportLink();
        // …and again at SUBMIT, which is the only moment that actually matters. The fields describe
        // the address bar, and `installUrlState` rewrites it with `pushState`/`replaceState` every
        // time a knob, device, theme or history step changes the selection — so a value frozen at
        // load describes the page as it was opened, not as it was when the visitor decided
        // something was wrong. That would report and re-render the wrong overrides through the
        // server's own override propagation, which is the whole point of carrying `from`.
        document
            .querySelectorAll<HTMLFormElement>(".cp-report-bug")
            .forEach((form) =>
                form.addEventListener("submit", fillBugReportLink),
            );
    });
}

/**
 * The fill itself, separated from the scheduling so tests can drive it against a built DOM.
 *
 * Fills EVERY copy of the form on the page. There are two entry points to `/report-bug` — the
 * footer link and the floating launcher — and they are the same three hidden inputs twice.
 * `querySelector` filled whichever came first in the document, so the launcher submitted an empty
 * `from` (losing the page, and on a gated host the token with it) while the footer beside it worked
 * perfectly: a failure invisible from either end.
 */
export function fillBugReportLink(): void {
    document
        .querySelectorAll<HTMLFormElement>(".cp-report-bug")
        .forEach(fillOne);
}

function fillOne(form: HTMLFormElement): void {
    const from = form.querySelector<HTMLInputElement>('input[name="from"]');
    const token = form.querySelector<HTMLInputElement>('input[name="token"]');
    const scheme = form.querySelector<HTMLInputElement>('input[name="scheme"]');
    if (from) {
        const current = new URLSearchParams(location.search);
        CARRIED.forEach(function (name) {
            current.delete(name);
        });
        const query = current.toString();
        from.value = location.pathname + (query ? `?${query}` : "");
    }
    if (token)
        token.value = new URLSearchParams(location.search).get("token") ?? "";
    // Captured HERE, on the page being reported, because it cannot be recovered on `/report-bug`:
    // a catalog that pinned dark chrome hands the report page a scheme of its own, and the OS
    // preference alone mislabels "dark preview on a light OS" — the exact condition a visual bug
    // needs to reproduce.
    if (scheme) scheme.value = pageScheme();
}

/**
 * The scheme this page is actually PAINTED in, as opposed to the one the OS asks for.
 *
 * `serve.css` writes every mode-dependent value as a `light-dark()` pair and the page pins its
 * choice with `cp-scheme-light` / `cp-scheme-dark` on `<html>` (see `pageTheme`), so those classes —
 * not `prefers-color-scheme` — are what decided the pixels whenever a theme was selected. Falls
 * back to the media query only when the page pinned nothing, which is the case where the two agree.
 */
export function pageScheme(): string {
    const root = document.documentElement;
    if (root.classList.contains("cp-scheme-dark")) return "dark";
    if (root.classList.contains("cp-scheme-light")) return "light";
    return osScheme();
}

function osScheme(): string {
    return typeof window.matchMedia === "function" &&
        window.matchMedia("(prefers-color-scheme: dark)").matches
        ? "dark"
        : "light";
}

/**
 * The carried `?scheme=`, accepted only as one of the two values this can legitimately be.
 *
 * It arrives in a URL anyone can hand a visitor, and it lands in a markdown table cell — so
 * `?scheme=dark|forged` would shear the Browser row and let arbitrary text pose as a diagnostic.
 * An allowlist is the right shape here rather than escaping: there are exactly two valid answers,
 * and anything else is not a mangled scheme but a value that was never a scheme at all. Unknown
 * input falls back to this page's own scheme, which is at least a real observation.
 */
function knownScheme(value: string | null): string | undefined {
    return value === "light" || value === "dark" ? value : undefined;
}

/**
 * On `/report-bug`, splice the browser's own facts into the report.
 *
 * The server fills the form's hidden `body` for everything it knows, leaving `{{client}}` where the
 * browser section goes — so a visitor with JS off still files a complete server report, just
 * without this part. These four facts are the ones a "the page draws wrong" bug turns on and the
 * only ones the server cannot observe: a render that is correct at 1x and broken at 2x, or correct
 * in light and wrong in dark, is otherwise a report nobody can reproduce.
 *
 * The visible `<pre>` is rewritten from the same string, because the page's promise is that what is
 * shown is what gets filed; updating the hidden input alone would quietly break that.
 */
export function installBugReportBody(): void {
    whenReady(fillBugReportBody);
}

/** The fill itself, separated from the scheduling so tests can drive it against a built DOM. */
export function fillBugReportBody(): void {
    const body = document.querySelector<HTMLInputElement>("#cp-bug-body");
    if (!body) return;
    const template = body.getAttribute("data-report-template");
    if (!template) return;
    // The scheme of the page being REPORTED, carried here by the footer form; absent when the
    // visitor reached `/report-bug` directly, which is the one case the report page's own scheme
    // is the honest answer.
    const reported = knownScheme(
        new URLSearchParams(location.search).get("scheme"),
    );
    // `replace` with a STRING replacement honours `$&`, `$'`, `` $` `` and `$1` — so a value that
    // reached the block could splice copies of the surrounding report into itself. A function
    // replacement is taken literally, which is what a substitution of fixed text should be.
    const filled = template.replace("{{client}}", () => clientBlock(reported));
    body.value = filled;
    const preview = document.querySelector<HTMLElement>("#cp-bug-preview");
    if (preview) preview.textContent = filled;
}

/**
 * The browser section, as the same two-column markdown table the server's sections use.
 *
 * [reportedScheme] is the scheme of the page the bug is about, carried from the footer form; when
 * absent this page's own scheme stands in.
 */
export function clientBlock(reportedScheme?: string): string {
    const rows = clientRows(reportedScheme);
    if (!rows.length) return "";
    return (
        "### Browser\n\n| | |\n| --- | --- |\n" +
        rows.map((row) => `| ${row[0]} | ${row[1]} |`).join("\n") +
        "\n"
    );
}

/**
 * Make free text safe inside a markdown table cell that is itself inside a code span.
 *
 * Three characters matter and the ORDER matters: the backslash must go first, or escaping the
 * others would double-escape the backslashes this pass just added. A `|` would shear the row; a
 * backtick would close the code span and let the rest of the string render as markdown.
 */
function cell(text: string): string {
    return text
        .replace(/\\/g, "\\\\")
        .replace(/\|/g, "\\|")
        .replace(/`/g, "\\`");
}

function clientRows(reportedScheme?: string): string[][] {
    const rows: string[][] = [];
    const ua = navigator.userAgent;
    if (ua) rows.push(["User agent", "`" + cell(ua) + "`"]);
    if (window.innerWidth && window.innerHeight) {
        rows.push([
            "Viewport",
            `${window.innerWidth}×${window.innerHeight} CSS px`,
        ]);
    }
    if (window.devicePixelRatio) {
        rows.push(["Device pixel ratio", String(window.devicePixelRatio)]);
    }
    // Both, and labelled apart: the page's scheme is what produced the pixels, the OS preference is
    // what a triager would otherwise assume produced them. Reporting only one of a disagreeing pair
    // is what made "dark preview on a light OS" unreproducible.
    rows.push(["Page colour scheme", reportedScheme || pageScheme()]);
    rows.push(["OS colour scheme", osScheme()]);
    return rows;
}
