// What the Submit button is doing while captures upload in the background.
//
// The upload runs off to one side while the reporter writes their summary, and it holds Submit
// down until it finishes so a report cannot be filed pointing at a URL that does not exist yet.
// That makes every path out of the upload responsible for letting go again — and a path that
// forgets is invisible in the happy case and unrecoverable in the unhappy one: the reporter is
// left on a bug-reporting page whose only button is dead, with no hint that reloading is the way
// out. Someone reaches for this tool when something is *already* broken.

import "./setup.js";
import assert from "node:assert/strict";
import { resetDom, flush } from "./setup.js";
import { installCapture } from "../src/report/ui.js";
import { Capture, STORE_KEY } from "../src/report/store.js";

const PNG = "data:image/png;base64,iVBORw0KGgo=";
const SUBJECT = "/catalog/p/loading-button";

function capture(id: string, label: string): Capture {
    return {
        id,
        label,
        dataUrl: `${PNG}${id}`,
        width: 8,
        height: 8,
        page: SUBJECT,
    };
}

/** Resolvers for the in-flight `POST /images` calls, newest last. */
let uploads: Array<(url: string) => void> = [];

/**
 * `sessionStorage` plus a fetch that lets a test decide when an upload finishes.
 *
 * The timing is the whole subject here, so the upload must be able to still be in the air when
 * the next thing happens.
 */
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
    uploads = [];
    Object.defineProperty(globalThis, "fetch", {
        configurable: true,
        value: (input: string | URL, init?: { method?: string }) => {
            // The data-URL read `blobFromDataUrl` does before posting.
            if (!init || init.method !== "POST") {
                return Promise.resolve({
                    blob: () => Promise.resolve(String(input)),
                });
            }
            return new Promise((resolve) => {
                uploads.push((url: string) =>
                    resolve({
                        ok: true,
                        status: 200,
                        json: () => Promise.resolve({ url }),
                    }),
                );
            });
        },
    });
}

/** `/report-bug` with the image lane advertised, so the background upload runs. */
function reportPage(): void {
    history.replaceState(
        {},
        "",
        `/report-bug?from=${encodeURIComponent(SUBJECT)}`,
    );
    document.documentElement.removeAttribute("data-cp-capture-ready");
    document.body.innerHTML = `
      <form class="cp-report-bug-form" method="get" target="_blank" rel="noopener"
        action="https://github.com/acme/tools/issues/new">
        <input class="cp-bug-summary-input" type="text" name="title" required>
        <input type="hidden" name="body" id="cp-bug-body" value="report">
        <button type="submit" class="cp-bug-submit">Open a prefilled issue</button>
      </form>
      <div class="cp-shots" data-cp-capture-src="/assets/serve/abc/report-capture.js"
        data-cp-image-upload="true">
        <p class="cp-sub cp-shots-empty">No captures came across.</p>
        <ul class="cp-shot-list"></ul>
        <p class="cp-shot-note" role="status"></p>
      </div>`;
}

/**
 * Answer every upload, in the order the loop actually makes them.
 *
 * The loop is sequential — `for (…) await uploadCapture(…)` — so exactly one request exists at a
 * time and the next appears only once the previous resolves. Resolving a fixed set up front
 * therefore answers only the first, leaves the loop parked forever, and lets a test that asserts
 * "the editor survived" pass because the redraw is still waiting on an upload that never lands.
 * That is a green test for a bug that is still there, so the draining is done properly.
 */
async function settle(): Promise<void> {
    for (let guard = 0; guard < 12 && uploads.length; guard++) {
        uploads.shift()!(`${location.origin}/i/shot-${guard}.png`);
        await flush();
    }
    for (let i = 0; i < 4; i++) await flush();
}

function submit(): HTMLButtonElement {
    return document.querySelector<HTMLButtonElement>(".cp-bug-submit")!;
}

/** Press a named row action on the only capture row. */
function press(label: string): void {
    const button = [
        ...document.querySelectorAll<HTMLButtonElement>(".cp-shot-action"),
    ].find((b) => b.textContent === label);
    assert.ok(button, `no "${label}" action on the row`);
    button.click();
}

describe("the Submit button while captures upload", () => {
    beforeEach(resetDom);

    it("releases Submit when a removal leaves nothing to upload", async () => {
        // Remove the last capture while its own upload is still in the air. That starts a new
        // generation with an empty pending list, so the in-flight call's cleanup sees a
        // generation mismatch and correctly declines to touch the button — which makes releasing
        // it this call's job. Returning early instead left Submit disabled until a page reload.
        stubBrowser([capture("shot-1", "Region")]);
        reportPage();
        installCapture();
        await flush();
        assert.equal(submit().disabled, true, "upload should hold Submit down");

        press("Remove");
        await flush();

        assert.equal(submit().disabled, false);
    });

    it("still releases Submit the ordinary way, once the upload lands", async () => {
        // The path that always worked, kept honest beside the one that did not.
        stubBrowser([capture("shot-1", "Region")]);
        reportPage();
        installCapture();
        await flush();
        assert.equal(submit().disabled, true);

        await settle();

        assert.equal(submit().disabled, false);
    });
});

describe("an open markup editor while an upload finishes", () => {
    beforeEach(resetDom);

    it("is not torn down by the redraw the upload triggers", async () => {
        // The editor is a child of the row, and refreshing the list replaces every row. So a
        // background upload completing while someone is drawing on a capture wiped the canvas
        // and every unsaved box, arrow, note and stroke on it — no warning, no undo.
        stubBrowser([
            capture("shot-1", "Region"),
            capture("shot-2", "Whole view"),
        ]);
        reportPage();
        installCapture();
        await flush();

        press("Mark up");
        assert.ok(
            document.querySelector(".cp-markup"),
            "editor should be open",
        );

        await settle();

        assert.ok(
            document.querySelector(".cp-markup"),
            "the editor survived the upload's redraw",
        );
    });

    it("redraws the list as soon as the editor closes", async () => {
        // Deferring the render must not drop it. The row still has to catch up with the store,
        // just later — otherwise the pile shows a capture that is no longer there.
        stubBrowser([
            capture("shot-1", "Region"),
            capture("shot-2", "Whole view"),
        ]);
        reportPage();
        installCapture();
        await flush();

        press("Mark up");
        press("Remove");
        await flush();
        assert.equal(
            document.querySelectorAll(".cp-shot-item").length,
            2,
            "the deferred render has not run while the editor is open",
        );

        [
            ...document.querySelectorAll<HTMLButtonElement>(
                ".cp-markup .cp-shot-action",
            ),
        ]
            .find((b) => b.textContent === "Cancel")!
            .click();
        await flush();

        assert.equal(document.querySelectorAll(".cp-shot-item").length, 1);
    });
});
