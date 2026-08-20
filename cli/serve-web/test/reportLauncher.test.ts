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
  <details class="cp-report" id="cp-report" data-cp-repo="acme/widgets"
    data-cp-subject="this preview">
    <summary class="cp-report-link">report a catalog issue</summary>
    <form class="cp-report-form"><input class="cp-report-summary-input" type="text"></form>
  </details>`;

/** The same affordance as the comparison wall emits it: page-scoped, so it names no preview. */
const WALL_REPORT = REPORT.replace(
    'data-cp-subject="this preview"',
    'data-cp-subject="these comparisons"',
);

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

    it("says what the report is about, as the affordance itself declares it", () => {
        // "Something is wrong with this preview" is true on the viewer and false on the comparison
        // wall, which shows every component at once and files a page-scoped report (issue #4289).
        // The affordance publishes its own subject rather than the launcher guessing from the URL.
        page(WALL_REPORT);
        installReportLauncher();
        assert.match(
            catalogChoice().textContent ?? "",
            /Something is wrong with these comparisons/,
        );
        assert.equal(
            catalogChoice().querySelector("strong")?.textContent,
            "these comparisons",
        );
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

describe("putting a report panel away once it has been used", () => {
    beforeEach(resetDom);

    function submit(selector: string): void {
        document
            .querySelector<HTMLFormElement>(selector)!
            .dispatchEvent(
                new Event("submit", { bubbles: true, cancelable: true }),
            );
    }

    it("closes the preview's report box when the issue form is submitted", () => {
        // Both forms are `target="_blank"`, so nothing ever navigates the page that raised the
        // panel and it just stays — hanging over the render it is a report about (issue #4333).
        page(REPORT);
        installReportLauncher();
        const report = document.getElementById(
            "cp-report",
        ) as HTMLDetailsElement;
        report.open = true;
        submit(".cp-report-form");
        assert.equal(report.open, false);
    });

    it("closes the launcher panel when the server half is submitted", () => {
        page();
        installReportLauncher();
        const menu = document.querySelector(
            ".cp-fab-menu",
        ) as HTMLDetailsElement;
        menu.open = true;
        submit(".cp-report-bug");
        assert.equal(menu.open, false);
    });

    it("leaves the typed summary alone", () => {
        // Submitting is not necessarily the end of the gesture: a refused sign-in, a blocked popup
        // or a second thought all land the reporter back here wanting the words they wrote.
        page(REPORT);
        installReportLauncher();
        const summary = document.querySelector<HTMLInputElement>(
            ".cp-report-summary-input",
        )!;
        summary.value = "the ring is missing";
        submit(".cp-report-form");
        assert.equal(summary.value, "the ring is missing");
    });

    it("closes a panel added to the page after the launcher was installed", () => {
        // `#cp-report` is emitted by whichever surface bundle drew the preview, and the comparison
        // wall rebuilds its own as the wall re-renders — so per-form binding at install time would
        // cover the launcher and miss the affordance it points at.
        page();
        installReportLauncher();
        document.body.insertAdjacentHTML("beforeend", REPORT);
        const report = document.getElementById(
            "cp-report",
        ) as HTMLDetailsElement;
        report.open = true;
        submit(".cp-report-form");
        assert.equal(report.open, false);
    });

    it("leaves a disclosure alone whose form is not a report", () => {
        page(
            `<details id="other" open><form class="cp-knobs"></form></details>`,
        );
        installReportLauncher();
        submit(".cp-knobs");
        assert.equal(
            (document.getElementById("other") as HTMLDetailsElement).open,
            true,
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
