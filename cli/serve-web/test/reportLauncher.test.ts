// The floating launcher's one job: routing a report to the tracker that owns what is wrong.
//
// The catalog half is the part worth testing. It is offered only on pages that carry the
// per-preview affordance, it has to name the repository that affordance derives, and pressing it
// must land the reporter in that form rather than navigating anywhere — all of which is invisible
// from the markup, because the markup ships the entry hidden and unlabelled.

import "./setup.js";
import assert from "node:assert/strict";
import { resetDom } from "./setup.js";
import { installReportLauncher } from "../src/chrome/reportLauncher.js";

/** The launcher as `ServeWeb.reportLauncherHtml` emits it, plus whatever the page adds. */
function page(extra = ""): void {
    document.documentElement.removeAttribute("data-cp-capture-ready");
    document.body.innerHTML = `
      ${extra}
      <div class="cp-fab" data-cp-capture-src="/assets/serve/abc/report-capture.js">
        <details class="cp-fab-menu">
          <summary class="cp-fab-btn">!</summary>
          <div class="cp-fab-panel">
            <a class="cp-fab-catalog cp-fab-choice" href="#cp-report" hidden>
              <span class="cp-fab-what">this preview</span>
              <span class="cp-fab-who">wrong colours</span>
            </a>
            <form class="cp-report-bug" method="get" action="/report-bug">
              <button type="submit" class="cp-fab-choice">the preview server</button>
            </form>
          </div>
        </details>
      </div>`;
}

/** The per-preview affordance the catalog half points at. */
const REPORT = `
  <details class="cp-report" id="cp-report" data-cp-repo="acme/widgets">
    <summary class="cp-report-link">report a catalog issue</summary>
    <form class="cp-report-form"><input class="cp-report-summary-input" type="text"></form>
  </details>`;

function catalogChoice(): HTMLAnchorElement {
    return document.querySelector(".cp-fab-catalog") as HTMLAnchorElement;
}

describe("the report launcher's catalog half", () => {
    beforeEach(resetDom);

    it("stays hidden on a page with no preview to file against", () => {
        // The front door, `/status`, a catalog that failed to load. There is no catalog bug to
        // report from a page showing no catalog, and offering one would send it to the fallback.
        page();
        installReportLauncher();
        assert.equal(catalogChoice().hidden, true);
    });

    it("appears and names the repository the preview's own report derives", () => {
        page(REPORT);
        installReportLauncher();
        assert.equal(catalogChoice().hidden, false);
        assert.match(catalogChoice().textContent ?? "", /acme\/widgets/);
    });

    it("opens the preview's report in place instead of navigating", () => {
        page(REPORT);
        installReportLauncher();
        const report = document.getElementById(
            "cp-report",
        ) as HTMLDetailsElement;
        assert.equal(report.open, false);
        const event = new MouseEvent("click", {
            bubbles: true,
            cancelable: true,
        });
        catalogChoice().dispatchEvent(event);
        assert.equal(event.defaultPrevented, true);
        assert.equal(report.open, true);
        assert.equal(
            document.querySelector(".cp-fab-menu")?.hasAttribute("open"),
            false,
        );
    });
});

describe("fetching the capture bundle", () => {
    beforeEach(resetDom);

    it("is not fetched merely because the page has a launcher", () => {
        // The whole reason it is a second bundle: a visitor who never reports anything should not
        // download a selection overlay and a canvas cropper.
        page();
        installReportLauncher();
        assert.equal(document.querySelector("script[data-cp-capture]"), null);
    });

    it("is fetched once, when the panel is first opened", () => {
        page();
        installReportLauncher();
        const menu = document.querySelector(
            ".cp-fab-menu",
        ) as HTMLDetailsElement;
        menu.open = true;
        menu.dispatchEvent(new Event("toggle"));
        menu.open = false;
        menu.dispatchEvent(new Event("toggle"));
        menu.open = true;
        menu.dispatchEvent(new Event("toggle"));
        assert.equal(
            document.querySelectorAll("script[data-cp-capture]").length,
            1,
        );
    });

    it("is fetched immediately on the report page, which has no launcher", () => {
        document.body.innerHTML = `
          <div class="cp-shots" data-cp-capture-src="/assets/serve/abc/report-capture.js">
            <ul class="cp-shot-list"></ul>
          </div>`;
        installReportLauncher();
        assert.equal(
            document.querySelectorAll("script[data-cp-capture]").length,
            1,
        );
    });

    it("refuses a source that is not this origin's", () => {
        // The attribute is server-rendered, but it is DOM text like any other, and this one becomes
        // a `<script src>`.
        document.body.innerHTML = `
          <div class="cp-shots" data-cp-capture-src="https://elsewhere.example/x.js">
            <ul class="cp-shot-list"></ul>
          </div>`;
        installReportLauncher();
        assert.equal(document.querySelector("script[data-cp-capture]"), null);
    });
});
