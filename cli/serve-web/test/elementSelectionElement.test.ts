// `<cp-element-selection>` end to end: from a click on the focused comparison to the two fields the
// filed issue actually carries.
//
// The rules are pinned next door (`elementTargets.test.ts`, `reportLocator.test.ts`). What only the
// element can answer is whether the selection REACHES the report — the failure mode this whole batch
// exists to close is a report that says "somewhere in this picture", and a selector whose choice
// never lands in the body is indistinguishable from not having one.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import "../src/components/ElementSelection.js";
import "../src/components/ReferenceCompare.js";

const TEMPLATE = [
    "### What's wrong",
    "",
    "```compose-parity-locator/v1",
    "repository: yschimke/m3-catalog",
    "system: m3-catalog",
    "component: IconButton/Tonal",
    "preview: iconbutton-tonal",
    "reference: iconbutton-tonal-figma",
    "variant: ideal/default/light",
    "overrides: {}",
    "{{selection}}",
    "revision: yschimke/m3-catalog@design-artifacts/m3-catalog",
    "```",
    "",
].join("\n");

const INDEX = {
    previewId: "iconbutton-tonal",
    tags: {
        glyph: {
            count: 1,
            bounds: { x: 18, y: 18, width: 24, height: 24 },
            space: "render-pixels",
        },
        // The case the annotation-box-only design misses: a uniquely tagged node with neither
        // typography nor container tokens produces no annotation at all, so nothing on the page
        // draws a box for it — and it is exactly the kind of node a tag selector is best at.
        "plain-marker": { count: 1, bounds: null, space: "render-pixels" },
        row: {
            count: 2,
            bounds: { x: 0, y: 0, width: 90, height: 20 },
            space: "render-pixels",
        },
    },
};

let fetched: string[] = [];

function stubFetch(ok = true): void {
    fetched = [];
    globalThis.fetch = (async (url: string) => {
        fetched.push(String(url));
        if (!ok) return { ok: false, status: 404 };
        return { ok: true, json: async () => INDEX };
    }) as unknown as typeof fetch;
}

/** happy-dom reports every layout box as zero, so the frame's geometry has to be declared. */
function sizeFrame(naturalWidth = 400, clientWidth = 200): void {
    const img = document.querySelector<HTMLImageElement>(
        "#cp-compare-actual img",
    )!;
    for (const [key, value] of Object.entries({
        naturalWidth,
        clientWidth,
        clientHeight: clientWidth,
    }))
        Object.defineProperty(img, key, {
            configurable: true,
            get: () => value,
        });
    img.getBoundingClientRect = () =>
        ({
            left: 0,
            top: 0,
            width: clientWidth,
            height: clientWidth,
        }) as DOMRect;
}

async function mount(withTags = true): Promise<void> {
    window.history.replaceState(null, "", "/m3/compare/iconbutton-tonal");
    document.body.innerHTML = `
      <div id="cp-reference-compare" data-reference="/m3/reference/x.png"
           data-actual="/m3/render/iconbutton-tonal.png?token=t">
        <div class="cp-reference-grid">
          <section><h2>Actual</h2>
            <div class="cp-compare-shot" id="cp-compare-actual" data-preview-id="iconbutton-tonal">
              <img src="/m3/render/iconbutton-tonal.png?token=t">
              <div id="cp-selection-layer" hidden></div>
            </div>
          </section>
        </div>
        <p class="cp-reference-result"></p>
        <div class="cp-selection-controls" id="cp-element-selection"${
            withTags ? ' data-cp-tags="/m3/tags/iconbutton-tonal?token=t"' : ""
        }>
          <select class="cp-selection-tag" hidden><option value="">the whole render</option></select>
          <button type="button" class="cp-selection-drag">Drag a region…</button>
          <button type="button" class="cp-selection-clear" hidden>Clear</button>
          <p class="cp-selection-state">Reporting the whole render.</p>
        </div>
        <form><input type="hidden" id="cp-report-body" value="server body"
                     data-report-template="${TEMPLATE.replace(/"/g, "&quot;").replace(/\n/g, "&#10;")}"></form>
      </div>
      <cp-reference-compare></cp-reference-compare>
      <cp-element-selection></cp-element-selection>`;
    sizeFrame();
    for (let i = 0; i < 6; i++) await flush();
}

const picker = () =>
    document.querySelector<HTMLSelectElement>(".cp-selection-tag")!;
const body = () =>
    document.getElementById("cp-report-body") as HTMLInputElement;
const state = () =>
    document.querySelector<HTMLElement>(".cp-selection-state")!.textContent;
const locatorLines = () =>
    body()
        .value.split("\n")
        .filter(
            (line) =>
                line.startsWith("element: ") || line.startsWith("bounds: "),
        );

async function choose(tag: string): Promise<void> {
    picker().value = tag;
    picker().dispatchEvent(new Event("change"));
    for (let i = 0; i < 3; i++) await flush();
}

/** A pointer gesture over the selection layer, in DISPLAY pixels. */
async function drag(
    from: [number, number],
    to: [number, number],
): Promise<void> {
    document
        .querySelector<HTMLButtonElement>(".cp-selection-drag")!
        .dispatchEvent(new Event("click"));
    await flush();
    const layer = document.getElementById("cp-selection-layer")!;
    const send = (type: string, [x, y]: [number, number]) => {
        const event = new Event(type, { bubbles: true }) as Event & {
            clientX: number;
            clientY: number;
        };
        event.clientX = x;
        event.clientY = y;
        layer.dispatchEvent(event);
    };
    send("pointerdown", from);
    send("pointermove", to);
    send("pointerup", to);
    for (let i = 0; i < 3; i++) await flush();
}

describe("<cp-element-selection>", () => {
    beforeEach(() => stubFetch());
    afterEach(() => {
        resetDom();
        window.history.replaceState(null, "", "/");
    });

    it("offers the published tags, and says nothing is selected yet", async () => {
        await mount();
        assert.deepEqual(fetched, ["/m3/tags/iconbutton-tonal?token=t"]);
        assert.equal(picker().hidden, false);
        assert.deepEqual(
            Array.from(picker().options).map((o) => o.value),
            ["", "glyph", "plain-marker", "row"],
        );
        assert.equal(state(), "Reporting the whole render.");
        assert.deepEqual(locatorLines(), []);
    });

    it("rides a tag selection into the report's locator block", async () => {
        await mount();
        await choose("glyph");
        assert.deepEqual(locatorLines(), [
            'element: "glyph"',
            'bounds: {"height":24,"space":"render-pixels","width":24,"x":18,"y":18}',
        ]);
        assert.match(state()!, /glyph/);
    });

    it("selects a tagged node that has no annotation and no bounds at all", async () => {
        // The case an annotation-box-only selector misses entirely: no typography, no container
        // tokens, so nothing draws a box for it. It is `count` that makes the tag an identity, not
        // its geometry, so the element is recorded with no region beside it.
        await mount();
        await choose("plain-marker");
        assert.deepEqual(locatorLines(), ['element: "plain-marker"']);
    });

    it("refuses a duplicated tag as an element selector", async () => {
        await mount();
        const duplicated = Array.from(picker().options).find(
            (o) => o.value === "row",
        )!;
        // Listed, so somebody hunting for a tag they can see in the code finds out WHY it is not
        // offered — and disabled, because `count > 1` is not an identity.
        assert.equal(duplicated.disabled, true);
        assert.match(duplicated.textContent!, /2 nodes, not unique/);
        // And refused again if it reaches the handler anyway (a keyboard path, a page script, a
        // browser that ignores `disabled`).
        await choose("row");
        assert.deepEqual(locatorLines(), []);
        assert.equal(state(), "Reporting the whole render.");
    });

    it("records a dragged region in the render's own pixels, not the display's", async () => {
        // The frame is mounted at half its natural width, so a 40×40 drag is an 80×80 region. This
        // is the conversion `v1` refuses to do for you: a display-plane rectangle would make an
        // element that never moved report as moved.
        await mount();
        await drag([10, 20], [50, 60]);
        assert.deepEqual(locatorLines(), [
            'bounds: {"height":80,"space":"render-pixels","width":80,"x":20,"y":40}',
        ]);
    });

    it("keeps a chosen tag when a region is dragged around it", async () => {
        await mount();
        await choose("glyph");
        await drag([10, 20], [50, 60]);
        assert.deepEqual(locatorLines(), [
            'element: "glyph"',
            'bounds: {"height":80,"space":"render-pixels","width":80,"x":20,"y":40}',
        ]);
    });

    it("treats a click with no drag as a cancel", async () => {
        await mount();
        await drag([10, 20], [10, 20]);
        assert.deepEqual(locatorLines(), []);
    });

    it("clears back to the whole render", async () => {
        await mount();
        await choose("glyph");
        assert.equal(
            document.querySelector<HTMLButtonElement>(".cp-selection-clear")!
                .hidden,
            false,
        );
        document
            .querySelector<HTMLButtonElement>(".cp-selection-clear")!
            .dispatchEvent(new Event("click"));
        await flush();
        assert.deepEqual(locatorLines(), []);
        assert.equal(state(), "Reporting the whole render.");
    });

    it("offers only the drag where the server withheld the tag index", async () => {
        // No `data-cp-tags` is the server saying a tag selection would not describe THIS frame — an
        // override or a pin has re-rendered it. The drag is derived from the displayed pixels, so it
        // stays honest and stays available.
        await mount(false);
        assert.deepEqual(fetched, []);
        assert.equal(picker().hidden, true);
        await drag([10, 20], [50, 60]);
        assert.deepEqual(locatorLines(), [
            'bounds: {"height":80,"space":"render-pixels","width":80,"x":20,"y":40}',
        ]);
    });

    it("leaves the page alone when the index cannot be fetched", async () => {
        // An empty picker would read as "this render has no tagged elements", which is a different
        // and false claim.
        stubFetch(false);
        await mount();
        assert.equal(picker().hidden, true);
    });
});
