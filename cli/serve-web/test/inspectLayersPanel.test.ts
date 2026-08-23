// `<cp-inspect-layers>` mounted over a panel rather than the viewer — the focused comparison's
// Actual frame.
//
// This is the half of the element that did not exist before: the comparison page carries only the
// producer-authored redline from a bundle's `annotations/index.json`, so the layers PROJECTED from
// the render's own semantics tree — typography, theme, layout — never reached the page where a
// parity report is actually filed. What that costs is not cosmetic: those layers are the targets an
// element selection points at, so without them the render side of the comparison has nothing to
// click.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import "../src/components/InspectLayers.js";

const ANNOTATIONS = {
    annotations: [
        {
            kind: "typography",
            bounds: { x: 0, y: 0, width: 100, height: 50 },
            role: "Title",
            label: "20sp",
        },
        {
            kind: "theme",
            bounds: { x: 0, y: 60, width: 100, height: 50 },
            label: "surface",
        },
        {
            kind: "layout",
            bounds: { x: 0, y: 0, width: 200, height: 400 },
            label: "pad 16dp",
            role: "Frame",
        },
    ],
};

let urls: string[] = [];

function stubFetch(): void {
    urls = [];
    globalThis.fetch = (async (url: string) => {
        urls.push(String(url));
        return { ok: true, json: async () => ANNOTATIONS };
    }) as unknown as typeof fetch;
}

async function mountPanel(withTag = true, selectable = true): Promise<void> {
    window.history.replaceState(null, "", "/m3/compare/plain.Button?token=t");
    document.body.innerHTML = `
      <div id="cp-reference-compare">
        <div class="cp-reference-grid">
          <section><h2>Reference</h2><div class="cp-compare-shot"><img src="/m3/reference/Button.png"></div></section>
          <section><h2>Actual</h2>
            <div class="cp-compare-shot" id="cp-actual-panel" data-preview-id="plain.Button">
              <img src="/m3/render/plain.Button.png?token=t">
              <div class="cp-inspect-layer" id="cp-derived-layer"></div>
            </div>
          </section>
        </div>
        <div class="cp-inspect-legend" id="cp-derived-legend" hidden></div>
        <label><input class="cp-derived-inspect" data-cp-inspect="typography" type="checkbox"> Type</label>
        <label><input class="cp-derived-inspect" data-cp-inspect="theme" type="checkbox"> Theme</label>
        <label><input class="cp-derived-inspect" data-cp-inspect="layout" type="checkbox"> Layout</label>
      </div>
      ${
          withTag
              ? `<cp-inspect-layers
        data-cp-host="#cp-actual-panel"
        data-cp-layer="#cp-derived-layer"
        data-cp-legend="#cp-derived-legend"
        data-cp-toggles=".cp-derived-inspect"
        ${selectable ? 'data-cp-selectable="1"' : ""}
        data-cp-base="/m3"></cp-inspect-layers>`
              : ""
      }`;
    await flush();
}

async function tick(kind: string): Promise<void> {
    const el = document.querySelector<HTMLInputElement>(
        `.cp-derived-inspect[data-cp-inspect="${kind}"]`,
    )!;
    el.checked = true;
    el.dispatchEvent(new Event("change"));
    for (let i = 0; i < 5; i++) await flush();
}

const boxes = () =>
    Array.from(
        document.querySelectorAll<HTMLElement>(
            "#cp-derived-layer .cp-inspect-box",
        ),
    );
const rows = () =>
    Array.from(
        document.querySelectorAll<HTMLElement>(
            "#cp-derived-legend [data-cp-entry]",
        ),
    );

describe("<cp-inspect-layers> over a comparison panel", () => {
    beforeEach(stubFetch);
    afterEach(() => {
        resetDom();
        window.history.replaceState(null, "", "/");
    });

    it("draws nothing until a layer is ticked", async () => {
        await mountPanel();
        assert.equal(urls.length, 0);
        assert.equal(boxes().length, 0);
        assert.equal(
            document.getElementById("cp-derived-legend")!.hidden,
            true,
        );
    });

    it("derives its endpoint from the panel's own frame, session key and all", async () => {
        // From `src`, not from a rebuilt query: the panel is server-rendered and never swaps its
        // frame, so its `src` IS the address of the pixels being compared — including the token
        // that makes the render lane answer at all on a gated box.
        await mountPanel();
        await tick("typography");
        assert.deepEqual(urls, ["/m3/render/plain.Button.annotations?token=t"]);
    });

    it("draws into the panel's layer and legend", async () => {
        await mountPanel();
        await tick("typography");
        await tick("theme");
        assert.equal(boxes().length, 2);
        assert.deepEqual(
            boxes().map((box) => box.getAttribute("data-cp-kind")),
            ["typography", "theme"],
        );
        assert.deepEqual(
            rows().map((row) => row.getAttribute("data-cp-entry")),
            ["typography-0", "theme-0"],
        );
        assert.equal(
            document.getElementById("cp-derived-legend")!.hidden,
            false,
        );
        assert.equal(
            document
                .getElementById("cp-actual-panel")!
                .getAttribute("data-inspect"),
            "on",
        );
    });

    it("fetches the shared payload once for the two layers that share it", async () => {
        await mountPanel();
        await tick("typography");
        await tick("theme");
        assert.equal(urls.length, 1);
    });

    it("never touches the Reference panel", async () => {
        // Derived layers describe the RENDER's semantics tree. The reference is an imported raster
        // with no tree behind it, so drawing this preview's boxes over it would be a picture of the
        // wrong thing that looks exactly as authoritative as the right one.
        await mountPanel();
        await tick("typography");
        assert.equal(
            document.querySelectorAll(
                ".cp-reference-grid section:first-child .cp-inspect-box",
            ).length,
            0,
        );
    });

    it("announces a pick when a selectable host's box is clicked", async () => {
        // The brief's first of two ways to choose. The bounds travel as they are — every source
        // reports them in the render's own pixel space, so a box click cannot acquire the
        // display-plane error a drag has to be converted out of.
        await mountPanel();
        await tick("typography");
        const picks: unknown[] = [];
        window.addEventListener("cp-element-pick", (event) =>
            picks.push((event as CustomEvent).detail?.bounds),
        );
        boxes()[0].dispatchEvent(new Event("click", { bubbles: true }));
        assert.deepEqual(picks, [{ x: 0, y: 0, width: 100, height: 50 }]);
        assert.ok(
            boxes()[0].classList.contains("cp-inspect-box--selectable"),
            "a selectable box says so, so the cursor can",
        );
    });

    it("picks a layout box through its badge, which is the only part that takes a pointer", async () => {
        // Layout boxes are `pointer-events: none` by design — the layer draws every slot box and the
        // outermost covers the whole frame, so a clickable interior would swallow every other
        // layer's hover and make "click anywhere" select the root. The badge is the target, and the
        // handler has to be reachable through it: a click on the badge bubbles to the box.
        await mountPanel();
        await tick("layout");
        const picks: unknown[] = [];
        window.addEventListener("cp-element-pick", (event) =>
            picks.push((event as CustomEvent).detail?.bounds),
        );
        const badge = boxes()[0].querySelector(".cp-inspect-badge")!;
        badge.dispatchEvent(new Event("click", { bubbles: true }));
        assert.equal(picks.length, 1, "the badge must reach the box's handler");
    });

    it("lets a keyboard reader pick through the legend row", async () => {
        // The box cannot be the keyboard path: it is an unfocusable div over the frame, and the
        // layout layer's interior takes no pointer at all. Without this the withheld-picker case —
        // annotations published, no tag index — leaves a keyboard user no selection path whatever,
        // since the drag is pointer-only.
        await mountPanel();
        await tick("typography");
        const picks: unknown[] = [];
        window.addEventListener("cp-element-pick", (event) =>
            picks.push((event as CustomEvent).detail?.bounds),
        );
        const row = rows()[0];
        assert.equal(row.getAttribute("role"), "button");
        assert.ok(
            row.classList.contains("cp-inspect-entry--selectable"),
            "a selectable row says so, so the cursor can",
        );
        row.dispatchEvent(
            new window.KeyboardEvent("keydown", { key: "Enter" }),
        );
        row.dispatchEvent(new window.KeyboardEvent("keydown", { key: " " }));
        assert.deepEqual(picks, [
            { x: 0, y: 0, width: 100, height: 50 },
            { x: 0, y: 0, width: 100, height: 50 },
        ]);
    });

    it("records the same bounds however the same entry was reached", async () => {
        // A keyboard reader and a pointer reader filing different reports for one element is the
        // kind of divergence nobody notices until the two reports disagree.
        await mountPanel();
        await tick("theme");
        const picks: unknown[] = [];
        window.addEventListener("cp-element-pick", (event) =>
            picks.push((event as CustomEvent).detail),
        );
        boxes()[0].dispatchEvent(new Event("click", { bubbles: true }));
        rows()[0].dispatchEvent(
            new window.KeyboardEvent("keydown", { key: "Enter" }),
        );
        assert.equal(picks.length, 2);
        assert.deepEqual(picks[0], picks[1]);
    });

    it("leaves the legend inert where a pick means nothing", async () => {
        // The viewer's rows are a reading aid. Announcing a pick from them would give a page that
        // files no reports an event nobody listens for, and a `role` that lies about it.
        await mountPanel(true, false);
        await tick("typography");
        const picks: unknown[] = [];
        window.addEventListener("cp-element-pick", () => picks.push(1));
        const row = rows()[0];
        assert.equal(row.getAttribute("role"), null);
        row.dispatchEvent(
            new window.KeyboardEvent("keydown", { key: "Enter" }),
        );
        assert.equal(picks.length, 0);
    });

    it("stays inert when the page is missing the layer it named", async () => {
        // The guard matters most here rather than on the viewer: this mount is added by one page
        // and the parts it names are spread across that page, so a rename anywhere is a mount with
        // a dangling selector. Drawing nothing is the safe answer; the alternative a resolver could
        // reach for — falling back to the viewer's ids — would put this page's boxes on a frame
        // belonging to another page entirely.
        await mountPanel(false);
        document.getElementById("cp-derived-layer")!.remove();
        const remount = document.createElement("cp-inspect-layers");
        for (const [name, value] of [
            ["data-cp-host", "#cp-actual-panel"],
            ["data-cp-layer", "#cp-derived-layer"],
            ["data-cp-legend", "#cp-derived-legend"],
            ["data-cp-toggles", ".cp-derived-inspect"],
        ])
            remount.setAttribute(name, value);
        document.body.appendChild(remount);
        await flush();
        await tick("typography");
        assert.equal(urls.length, 0);
        assert.equal(boxes().length, 0);
    });
});
