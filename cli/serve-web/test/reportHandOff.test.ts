// The last few centimetres of a screenshot's journey into a GitHub issue. The normal path hosts
// the capture and embeds its URL in the prefilled body; the fallback re-copies it inside the submit
// gesture so browsers permit the clipboard write and an intervening copy cannot replace it.
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

/** The page these tests treat as the one being reported, unless a case says otherwise. */
const SUBJECT = "/catalog/p/loading-button";

function capture(id: string, label: string, page = SUBJECT): Capture {
    return { id, label, dataUrl: `${PNG}${id}`, width: 8, height: 8, page };
}

/** Put the tab on `/report-bug`, reporting [from] — the shape the footer form arrives in. */
function reporting(from = SUBJECT): void {
    history.replaceState(
        {},
        "",
        `/report-bug?from=${encodeURIComponent(from)}`,
    );
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
function reportPage(canUpload = false): void {
    reporting();
    document.documentElement.removeAttribute("data-cp-capture-ready");
    document.body.innerHTML = `
      <form class="cp-report-bug-form" method="get" target="_blank" rel="noopener"
        action="https://github.com/acme/tools/issues/new">
        <input class="cp-bug-summary-input" type="text" name="title" required>
        <input type="hidden" name="body" id="cp-bug-body" value="report">
        <button type="submit" class="cp-bug-submit">Open a prefilled issue</button>
      </form>
      <div class="cp-shots" data-cp-capture-src="/assets/serve/abc/report-capture.js"
        data-cp-image-upload="${canUpload}">
        <p class="cp-sub cp-shots-empty">No captures came across.</p>
        <ul class="cp-shot-list"></ul>
        <p class="cp-shot-note" role="status"></p>
      </div>`;
}

/**
 * A preview page: the per-preview report box, and the capture block in the launcher panel.
 *
 * Both disclosures start SHUT, which is the state a submit leaves them in — `reportLauncher.ts`
 * closes `#cp-report` on submit (issue #4333) and the capture flow closed the launcher to take the
 * shot. That is what makes the note's visibility a real question here and not on `/report-bug`.
 */
function previewPage(): void {
    history.replaceState({}, "", SUBJECT);
    document.documentElement.removeAttribute("data-cp-capture-ready");
    document.body.innerHTML = `
      <details class="cp-report" id="cp-report">
        <summary class="cp-report-link">report a catalog issue</summary>
        <div class="cp-report-panel">
          <form class="cp-report-form" method="get" target="_blank"
            action="https://github.com/acme/widgets/issues/new">
            <input class="cp-report-summary-input" type="text" name="title" required>
            <input type="hidden" name="body" id="cp-report-body" value="report">
          </form>
        </div>
      </details>
      <div class="cp-fab">
        <details class="cp-fab-menu">
          <summary class="cp-fab-btn">!</summary>
          <div class="cp-fab-panel">
            <div class="cp-shot">
              <p class="cp-shot-note" role="status"></p>
              <ul class="cp-shot-list"></ul>
            </div>
          </div>
        </details>
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

    it("hands off from a form the page rebuilt after the bundle loaded", async () => {
        // `installCapture` runs once per page, but `.cp-report-form` is emitted by whichever
        // surface bundle drew the preview and the comparison wall replaces its own as the wall
        // re-renders. A snapshot taken at install time wires a form that is no longer in the
        // document, and the failure is exactly the silent one this whole change removes.
        stubBrowser([capture("shot-1", "Whole view")]);
        reportPage();
        installCapture();
        const shots = document.querySelector(".cp-shots")!;
        document.querySelector(".cp-report-bug-form")!.remove();
        shots.insertAdjacentHTML(
            "beforebegin",
            `<form class="cp-report-bug-form" method="get" target="_blank"
               action="https://github.com/acme/tools/issues/new"></form>`,
        );
        submitReport();
        await settled();
        assert.equal(written.length, 1);
        assert.match(note(), /on the clipboard/);
    });

    it("hands off from a preview's own report form too", async () => {
        // The per-preview affordance files against the CATALOG's repo rather than the server's, but
        // the screenshot problem is identical and so is the route out of it.
        stubBrowser([capture("shot-1", "Region")]);
        previewPage();
        installCapture();
        submitReport(".cp-report-form");
        await settled();
        assert.equal(written.length, 1);
        assert.match(note(), /on the clipboard/);
    });
});

describe("hosting captures in the prefilled issue body", () => {
    beforeEach(resetDom);

    it("uploads on the report page and embeds the returned image instead of requiring a paste", async () => {
        stubBrowser([capture("shot-1", "Whole view")]);
        Object.defineProperty(globalThis, "fetch", {
            configurable: true,
            value: (url: string) =>
                String(url).startsWith("data:")
                    ? Promise.resolve({
                          blob: () => Promise.resolve(new Blob(["png"])),
                      })
                    : Promise.resolve({
                          ok: true,
                          json: () =>
                              Promise.resolve({
                                  url: "https://preview.example/i/bug_shot.png",
                              }),
                      }),
        });
        reportPage(true);
        installCapture();
        await settled();
        await settled();
        const body = document.querySelector<HTMLInputElement>("#cp-bug-body")!;
        assert.match(
            body.value,
            /!\[Whole view\]\(https:\/\/preview\.example\/i\/bug_shot\.png\)/,
        );
        assert.match(note(), /uploaded/);
        submitReport();
        await settled();
        assert.equal(written.length, 0, "a hosted capture is not copied again");
        assert.match(note(), /embedded/);
    });

    it("offers the markup editor on every captured image", () => {
        stubBrowser([capture("shot-1", "Region")]);
        reportPage();
        installCapture();
        const button = Array.from(
            document.querySelectorAll<HTMLButtonElement>(".cp-shot-action"),
        ).find((item) => item.textContent === "Mark up")!;
        assert.ok(button);
        button.click();
        assert.deepEqual(
            Array.from(
                document.querySelectorAll<HTMLButtonElement>(".cp-markup-tool"),
            ).map((item) => item.textContent),
            ["Box", "Arrow", "Pen", "Text"],
        );
        assert.ok(document.querySelector(".cp-markup-canvas"));
    });

    it("does not let a stale upload restore a capture removed while it was in flight", async () => {
        stubBrowser([capture("shot-1", "Whole view")]);
        let finishUpload!: (value: unknown) => void;
        const response = new Promise((resolve) => (finishUpload = resolve));
        Object.defineProperty(globalThis, "fetch", {
            configurable: true,
            value: (url: string) =>
                String(url).startsWith("data:")
                    ? Promise.resolve({
                          blob: () => Promise.resolve(new Blob(["png"])),
                      })
                    : response,
        });
        reportPage(true);
        installCapture();
        await settled();
        const remove = Array.from(
            document.querySelectorAll<HTMLButtonElement>(".cp-shot-action"),
        ).find((item) => item.textContent === "Remove")!;
        remove.click();
        finishUpload({
            ok: true,
            json: () =>
                Promise.resolve({
                    url: "https://preview.example/i/stale.png",
                }),
        });
        await settled();
        await settled();
        assert.equal(document.querySelectorAll(".cp-shot-item").length, 0);
        assert.doesNotMatch(
            document.querySelector<HTMLInputElement>("#cp-bug-body")!.value,
            /stale\.png/,
        );
    });
});

describe("telling this report's capture from one the tab is carrying", () => {
    beforeEach(resetDom);

    it("stays silent when the only captures are of another page", async () => {
        // `sessionStorage` lasts as long as the tab. File one report with a screenshot, then file a
        // second later from somewhere else without taking another, and the first picture is still
        // in the pile — handing it over while saying "paste this" attaches a screenshot of an
        // unrelated page, with every appearance of being deliberate.
        stubBrowser([
            capture("shot-1", "Whole view", "/catalog/p/somewhere-else"),
        ]);
        reportPage();
        installCapture();
        submitReport();
        await settled();
        assert.equal(written.length, 0);
        assert.equal(note(), "");
    });

    it("picks the newest capture OF the reported page, not the newest overall", async () => {
        stubBrowser([
            capture("shot-1", "Whole view"),
            capture("shot-2", "Region", "/catalog/p/somewhere-else"),
        ]);
        reportPage();
        installCapture();
        submitReport();
        await settled();
        assert.equal(written.length, 1);
        assert.deepEqual(
            await (written[0] as { items: Record<string, Promise<string>> })
                .items["image/png"],
            `${PNG}shot-1`,
        );
        // …and it does not count the other page's capture among "the others still here".
        assert.doesNotMatch(note(), /other/);
    });

    it("ignores a capture stored before the page was recorded", async () => {
        // A pile written by an older build has no page on it, so nothing can vouch that it belongs
        // to this report. The Copy button still sends it; the automatic hand-off does not.
        const old = capture("shot-1", "Whole view");
        delete old.page;
        stubBrowser([old]);
        reportPage();
        installCapture();
        submitReport();
        await settled();
        assert.equal(written.length, 0);
    });

    it("matches on the path, so knob changes in the query do not lose it", async () => {
        // Two reports about the same preview at different settings are the same subject, and a
        // capture of one is honest evidence for the other.
        stubBrowser([capture("shot-1", "Whole view")]);
        reporting(`${SUBJECT}?mode=spec&specView=triptych`);
        reportPage();
        installCapture();
        submitReport();
        await settled();
        assert.equal(written.length, 1);
    });
});

describe("making a failed hand-off visible", () => {
    beforeEach(resetDom);

    it("opens the launcher panel the note is buried in", async () => {
        // On a preview page the note's only home is the capture block inside the launcher panel,
        // which the capture flow closed to take the shot and the submit closed again. Writing the
        // one message that needs acting on into a closed drawer is the same silent failure in a
        // different costume: the reporter returns from GitHub and pastes stale clipboard contents.
        stubBrowser([capture("shot-1", "Region")]);
        previewPage();
        installCapture();
        clipboardWorks = false;
        submitReport(".cp-report-form");
        await settled();
        assert.match(note(), /Press Copy/);
        assert.equal(
            (document.querySelector(".cp-fab-menu") as HTMLDetailsElement).open,
            true,
        );
    });

    it("leaves the panels shut when the hand-off worked", async () => {
        // A success has nothing to act on. Reopening the launcher over the page would undo the
        // dismissal that issue #4333 is about.
        stubBrowser([capture("shot-1", "Region")]);
        previewPage();
        installCapture();
        submitReport(".cp-report-form");
        await settled();
        assert.match(note(), /on the clipboard/);
        assert.equal(
            (document.querySelector(".cp-fab-menu") as HTMLDetailsElement).open,
            false,
        );
    });
});

describe("embedding hosted captures in the form actually being submitted", () => {
    beforeEach(resetDom);

    /** A capture the server already hosts, so the report can embed it by URL. */
    function hosted(id: string, label: string): Capture {
        return {
            ...capture(id, label),
            // Absolute and same-origin: `safeUploadUrl` parses with no base, and
            // `hostedCaptureUrl` then requires the origin to match and the path to be
            // `/i/<id>.<ext>`. A relative value fails the first and the store drops the
            // whole capture.
            uploadedUrl: `${location.origin}/i/${id}.png`,
        };
    }

    function body(selector: string): string {
        return document.querySelector<HTMLInputElement>(selector)!.value;
    }

    it("writes into a preview page's own form, not just the bug page's", async () => {
        // Two forms carry a report, with two different body ids: `#cp-bug-body` on `/report-bug`
        // and `#cp-report-body` on a preview page. Looking only for the first meant a hand-off
        // from the second embedded nothing — and then reported success anyway, because "every
        // capture hosted" was read as "every capture embedded". The issue opened with no
        // screenshot and no clipboard fallback either: the one path where the reporter is told
        // it worked and it did not.
        stubBrowser([hosted("shot-1", "Region")]);
        previewPage();
        installCapture();
        submitReport(".cp-report-form");
        await settled();
        assert.match(
            body("#cp-report-body"),
            /!\[Region\]\(\S*\/i\/shot-1\.png\)/,
        );
        assert.match(note(), /embedded in the report/);
    });

    it("falls back to the clipboard when there is no body field to embed into", async () => {
        // A form with nowhere to write is not a successful embed. Better to copy the pixels and
        // say so than to claim an embed that cannot have happened.
        stubBrowser([hosted("shot-1", "Region")]);
        previewPage();
        document.querySelector("#cp-report-body")!.remove();
        installCapture();
        submitReport(".cp-report-form");
        await settled();
        assert.equal(written.length, 1);
        assert.match(note(), /on the clipboard/);
    });

    it("rebuilds from the body as it is now, not as it was first submitted", async () => {
        // A preview page's body is live: `refreshReportLink` in viewer.ts replaces it wholesale
        // with the current render URL whenever the knobs change. Caching the first submission's
        // value and rebuilding every later one from it means the second report quietly describes
        // the first bug — the reporter changed the preview precisely because the first framing
        // was wrong.
        stubBrowser([hosted("shot-1", "Region")]);
        previewPage();
        installCapture();
        submitReport(".cp-report-form");
        await settled();
        assert.match(body("#cp-report-body"), /report/);

        // The reporter goes back, changes the preview, and files again.
        document.querySelector<HTMLInputElement>("#cp-report-body")!.value =
            "second render";
        submitReport(".cp-report-form");
        await settled();

        const now = body("#cp-report-body");
        assert.match(now, /second render/);
        assert.doesNotMatch(now, /^report/);
        // …and the capture is still embedded, once.
        assert.equal(now.match(/!\[Region\]/g)?.length, 1);
    });

    it("copies the newest capture the report will NOT carry", async () => {
        // A capture is unhosted because its upload failed, or because marking it up cleared the
        // URL. Copying `latest` regardless sends a picture the body already embeds and drops the
        // edited one entirely — the reporter's most recent, most deliberate evidence.
        stubBrowser([
            capture("shot-1", "Edited region"),
            hosted("shot-2", "Whole view"),
        ]);
        previewPage();
        installCapture();
        submitReport(".cp-report-form");
        await settled();
        assert.equal(written.length, 1);
        assert.deepEqual(
            await (written[0] as { items: Record<string, Promise<string>> })
                .items["image/png"],
            `${PNG}shot-1`,
        );
    });
});
