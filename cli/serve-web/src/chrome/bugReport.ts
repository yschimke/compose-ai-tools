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
    const form = document.querySelector<HTMLFormElement>(".cp-report-bug");
    if (!form) return;
    const from = form.querySelector<HTMLInputElement>('input[name="from"]');
    const token = form.querySelector<HTMLInputElement>('input[name="token"]');
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
    const body = document.querySelector<HTMLInputElement>("#cp-bug-body");
    if (!body) return;
    const template = body.getAttribute("data-report-template");
    if (!template) return;
    const filled = template.replace("{{client}}", clientBlock());
    body.value = filled;
    const preview = document.querySelector<HTMLElement>("#cp-bug-preview");
    if (preview) preview.textContent = filled;
}

/** The browser section, as the same two-column markdown table the server's sections use. */
export function clientBlock(): string {
    const rows = clientRows();
    if (!rows.length) return "";
    return (
        "### Browser\n\n| | |\n| --- | --- |\n" +
        rows.map((row) => `| ${row[0]} | ${row[1]} |`).join("\n") +
        "\n"
    );
}

function clientRows(): string[][] {
    const rows: string[][] = [];
    const ua = navigator.userAgent;
    // A user agent is free text from the browser and lands in a markdown table cell, where a `|`
    // would shear the row. Escaped rather than dropped — a mangled UA is still the UA.
    if (ua) rows.push(["User agent", "`" + ua.replace(/\|/g, "\\|") + "`"]);
    if (window.innerWidth && window.innerHeight) {
        rows.push([
            "Viewport",
            `${window.innerWidth}×${window.innerHeight} CSS px`,
        ]);
    }
    if (window.devicePixelRatio) {
        rows.push(["Device pixel ratio", String(window.devicePixelRatio)]);
    }
    const dark =
        typeof window.matchMedia === "function" &&
        window.matchMedia("(prefers-color-scheme: dark)").matches;
    rows.push(["Colour scheme", dark ? "dark" : "light"]);
    return rows;
}
