// The two browser-side halves of the server bug report: what the footer form carries to
// `/report-bug`, and what the report page splices into the issue body.
//
// Both matter for reasons that are invisible at the call site. The footer form is the only place
// the *current* URL (rather than the served one) becomes part of a report, and it is also where a
// session token could most easily leak into a public issue body by being left inside the path.

import "./setup.js";
import assert from "node:assert/strict";
import { resetDom } from "./setup.js";
import {
    clientBlock,
    installBugReportBody,
    installBugReportLink,
} from "../src/chrome/bugReport.js";

/** Point the document at a URL without navigating; happy-dom allows the assignment. */
function at(path: string): void {
    history.replaceState(null, "", path);
}

function footerForm(): HTMLFormElement {
    document.body.innerHTML = `
      <form class="cp-report-bug" method="get" action="/report-bug">
        <input type="hidden" name="from" value="">
        <input type="hidden" name="token" value="">
        <button type="submit">report a bug</button>
      </form>`;
    return document.querySelector("form.cp-report-bug") as HTMLFormElement;
}

function value(form: HTMLFormElement, name: string): string {
    return (form.querySelector(`input[name="${name}"]`) as HTMLInputElement)
        .value;
}

describe("the footer's report-a-bug form", () => {
    afterEach(() => resetDom());

    it("carries the page the visitor is actually on, not the one the server rendered", () => {
        // The viewer rewrites its query as knobs change, so the address bar is the honest answer.
        at("/compose-m3/p/button?uiMode=dark&device=pixel_7");
        const form = footerForm();
        installBugReportLink();
        assert.equal(
            value(form, "from"),
            "/compose-m3/p/button?uiMode=dark&device=pixel_7",
        );
    });

    it("moves the token out of the path and into its own field", () => {
        // `from` is quoted into a public issue body; `token` only ever reaches this server.
        at("/compose-m3/p/button?token=s3cret&uiMode=dark");
        const form = footerForm();
        installBugReportLink();
        assert.equal(value(form, "from"), "/compose-m3/p/button?uiMode=dark");
        assert.equal(value(form, "token"), "s3cret");
    });

    it("leaves the token field empty on a public server", () => {
        at("/compose-m3/");
        const form = footerForm();
        installBugReportLink();
        assert.equal(value(form, "from"), "/compose-m3/");
        assert.equal(value(form, "token"), "");
    });

    it("does nothing when the page has no footer form", () => {
        at("/");
        document.body.innerHTML = "";
        assert.doesNotThrow(() => installBugReportLink());
    });
});

describe("the report page's browser block", () => {
    afterEach(() => resetDom());

    it("is the same two-column markdown table the server's own sections use", () => {
        const block = clientBlock();
        assert.match(block, /^### Browser\n\n\| \| \|\n\| --- \| --- \|\n/);
        assert.match(block, /\| User agent \| `.+` \|/);
        assert.match(block, /\| Colour scheme \| (light|dark) \|/);
    });

    it("escapes a pipe in the user agent so it cannot shear the table row", () => {
        const original = navigator.userAgent;
        Object.defineProperty(navigator, "userAgent", {
            value: "Weird|Browser/1.0",
            configurable: true,
        });
        try {
            assert.match(
                clientBlock(),
                /\| User agent \| `Weird\\\|Browser\/1\.0` \|/,
            );
        } finally {
            Object.defineProperty(navigator, "userAgent", {
                value: original,
                configurable: true,
            });
        }
    });

    it("fills the hidden body AND the visible preview, so what is shown is what is filed", () => {
        // `type="hidden"` is load-bearing, not fixture noise: a markdown body is multi-line, and a
        // text input runs the value sanitization algorithm that strips newlines. `ServeWeb` emits
        // the hidden type for exactly that reason, and so must this fixture.
        document.body.innerHTML = `
          <input type="hidden" id="cp-bug-body" value="### Server"
            data-report-template="### Server\n{{client}}">
          <pre id="cp-bug-preview">### Server</pre>`;
        installBugReportBody();
        const body = document.querySelector("#cp-bug-body") as HTMLInputElement;
        const preview = document.querySelector(
            "#cp-bug-preview",
        ) as HTMLElement;
        assert.ok(!body.value.includes("{{client}}"), body.value);
        assert.ok(body.value.includes("### Browser"), body.value);
        assert.equal(preview.textContent, body.value);
    });

    it("does nothing on a page that is not the report page", () => {
        document.body.innerHTML = "<p>a catalog</p>";
        assert.doesNotThrow(() => installBugReportBody());
    });
});
