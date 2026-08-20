// The last few centimetres of a screenshot's journey into a GitHub issue.
//
// GitHub's new-issue form prefills from a URL and a URL has no attachment field, so a captured
// picture reaches the issue by exactly one route: the reporter's clipboard, pasted into GitHub's
// own editor. `report/ui.ts` therefore re-copies the newest capture inside the `submit` handler of
// whichever report form was pressed (issue #4334) — the last instant this tab controls, and a real
// user gesture, which is what the clipboard write needs to be authorised.
//
// None of that is visible by looking at the running feature: a hand-off that silently never fires
// looks exactly like one that fired, right up until the paste produces whatever the reporter
// happened to copy while they were typing the summary.

import "./setup.js";
import assert from "node:assert/strict";
import { resetDom } from "./setup.js";
import { installCapture } from "../src/report/ui.js";
import { Capture, STORE_KEY } from "../src/report/store.js";

const PNG = "data:image/png;base64,iVBORw0KGgo=";

function capture(id: string, label: string): Capture {
    return { id, label, dataUrl: `${PNG}${id}`, width: 8, height: 8 };
}

/** What the clipboard was handed, in order. */
let written: unknown[] = [];
/** Whether the clipboard accepts the write at all — Safari's private mode, a denied permission. */
let clipboardWorks = true;

/** `sessionStorage`, `fetch` and the clipboard, as much of each as the hand-off touches. */
function stubBrowser(captures: Capture[]): void {
    const store = new Map<string, string>([
        [STORE_KEY, JSON.stringify(captures)],
    ]);
    Object.defineProperty(globalThis, "sessionStorage", {
        configurable: true,
        value: {
            getItem: (key: string) => store.get(key) ?? null,
            setItem: (key: string, value: string) => void store.set(key, value),
            removeItem: (key: string) => void store.delete(key),
        },
    });
    written = [];
    clipboardWorks = true;
    Object.defineProperty(globalThis, "ClipboardItem", {
        configurable: true,
        value: class {
            constructor(readonly items: Record<string, unknown>) {}
        },
    });
    Object.defineProperty(navigator, "clipboard", {
        configurable: true,
        value: {
            write: (items: unknown[]) => {
                if (!clipboardWorks) {
                    return Promise.reject(new Error("denied"));
                }
                written.push(...items);
                return Promise.resolve();
            },
        },
    });
    // `blobFromDataUrl` fetches the data URL. happy-dom has no data-URL fetch, and the bytes are
    // not what is under test — which capture was chosen is.
    Object.defineProperty(globalThis, "fetch", {
        configurable: true,
        value: (url: string) =>
            Promise.resolve({ blob: () => Promise.resolve(url) }),
    });
}

/** `/report-bug` as `ServeWeb.bugReportPage` emits it, minus the diagnostics. */
function reportPage(): void {
    document.documentElement.removeAttribute("data-cp-capture-ready");
    document.body.innerHTML = `
      <form class="cp-report-bug-form" method="get" target="_blank" rel="noopener"
        action="https://github.com/acme/tools/issues/new">
        <input class="cp-bug-summary-input" type="text" name="title" required>
        <input type="hidden" name="body" id="cp-bug-body" value="report">
        <button type="submit" class="cp-bug-submit">Open a prefilled issue</button>
      </form>
      <div class="cp-shots" data-cp-capture-src="/assets/serve/abc/report-capture.js">
        <p class="cp-sub cp-shots-empty">No captures came across.</p>
        <ul class="cp-shot-list"></ul>
        <p class="cp-shot-note" role="status"></p>
      </div>`;
}

function submitReport(selector = ".cp-report-bug-form"): void {
    document
        .querySelector<HTMLFormElement>(selector)!
        .dispatchEvent(
            new Event("submit", { bubbles: true, cancelable: true }),
        );
}

/** The promise chain inside the handler is one microtask deep past the clipboard write. */
function settled(): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, 0));
}

function note(): string {
    return document.querySelector(".cp-shot-note")?.textContent ?? "";
}

describe("handing a capture to the clipboard as the issue is opened", () => {
    beforeEach(resetDom);

    it("copies the capture when the prefilled issue is opened", async () => {
        // The whole point: it was copied once at the shutter, then the reporter navigated here and
        // typed a summary. Anything they copied in between won that clipboard.
        stubBrowser([capture("shot-1", "Whole view")]);
        reportPage();
        installCapture();
        submitReport();
        await settled();
        assert.equal(written.length, 1);
        assert.match(note(), /on the clipboard/);
    });

    it("copies the NEWEST capture, and says the others are still reachable", async () => {
        // A clipboard holds one image. The newest is the one the pile's own eviction rule already
        // treats as most wanted, and the rest keep their Copy buttons on this page.
        stubBrowser([
            capture("shot-1", "Whole view"),
            capture("shot-2", "Region"),
            capture("shot-3", "Element · table"),
        ]);
        reportPage();
        installCapture();
        submitReport();
        await settled();
        assert.equal(written.length, 1);
        assert.deepEqual(
            await (written[0] as { items: Record<string, Promise<string>> })
                .items["image/png"],
            `${PNG}shot-3`,
        );
        assert.match(note(), /other 2/);
    });

    it("says what to do instead when the clipboard refuses", async () => {
        // The capture is not lost — it is in the list with a Copy button on it — but a reporter who
        // is not told will paste whatever they last copied and file a report with the wrong picture.
        stubBrowser([capture("shot-1", "Whole view")]);
        reportPage();
        installCapture();
        clipboardWorks = false;
        submitReport();
        await settled();
        assert.equal(written.length, 0);
        assert.match(note(), /Press Copy/);
    });

    it("does nothing at all when no capture came across", async () => {
        // Most reports carry none, and a status line about a clipboard nobody touched would be a
        // lie on every one of them.
        stubBrowser([]);
        reportPage();
        installCapture();
        submitReport();
        await settled();
        assert.equal(written.length, 0);
        assert.equal(note(), "");
    });

    it("hands off from a preview's own report form too", async () => {
        // The per-preview affordance files against the CATALOG's repo rather than the server's, but
        // the screenshot problem is identical and so is the route out of it.
        stubBrowser([capture("shot-1", "Region")]);
        document.documentElement.removeAttribute("data-cp-capture-ready");
        document.body.innerHTML = `
          <details class="cp-report" id="cp-report" open>
            <summary class="cp-report-link">report a catalog issue</summary>
            <div class="cp-report-panel">
              <form class="cp-report-form" method="get" target="_blank"
                action="https://github.com/acme/widgets/issues/new">
                <input class="cp-report-summary-input" type="text" name="title" required>
              </form>
            </div>
          </details>
          <div class="cp-shot" hidden>
            <p class="cp-shot-note" role="status"></p>
            <ul class="cp-shot-list"></ul>
          </div>`;
        installCapture();
        submitReport(".cp-report-form");
        await settled();
        assert.equal(written.length, 1);
        assert.match(note(), /on the clipboard/);
    });
});
