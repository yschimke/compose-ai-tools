// Behavioural contract for `<cp-design-page>`.
//
// The calculations are pinned next door — `designInk`, `designScore`, `designGeometry`. What only
// the element can answer is the wiring: that a node named by the manifest but absent from the export
// says so instead of vanishing, that the design's own drawing is hidden only once ours has actually
// arrived, that the renders stay inert until a lane wants them, and that the coverage filter takes
// what it mutes out of the tab order as well as out of sight.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import "../src/components/DesignPage.js";

/**
 * The page as the server emits it: an inlined SVG whose nodes carry `data-node-id`, an overlay per
 * manifest node, and the renders parked in an inert `<template>`.
 */
async function mount(
    options: { lane?: string; missing?: boolean } = {},
): Promise<void> {
    const lane = options.lane ?? "code";
    const checked = (value: string) => (lane === value ? "checked" : "");
    document.body.innerHTML = `
      <cp-design-page></cp-design-page>
      <section id="cp-design-page">
        <label><input type="radio" name="lane" data-cp-page-lane value="code" ${checked("code")}></label>
        <label><input type="radio" name="lane" data-cp-page-lane value="design" ${checked("design")}></label>
        <label><input type="radio" name="lane" data-cp-page-lane value="diff" ${checked("diff")}></label>
        <label><input type="checkbox" data-cp-page-outlines></label>
        <label><input type="checkbox" data-cp-page-unlinked></label>
        <div class="cp-page-legend" hidden></div>
        <div class="cp-page-stage">
          <div data-cp-page-canvas>
            <svg viewBox="0 0 400 200">
              <g data-node-id="1:10"></g>
              <g data-node-id="1-20"></g>
            </svg>
            <a class="cp-page-node" data-cp-node="1:10" data-cp-gap></a>
            <a class="cp-page-node" data-cp-node="1:20"></a>
            ${options.missing ? '<a class="cp-page-node" data-cp-node="9:99"></a>' : ""}
          </div>
          <template data-cp-page-render-source>
            <img class="cp-page-render" data-cp-node="1:10" alt="">
            <img class="cp-page-render" data-cp-node="1:20" alt="">
            <img class="cp-page-render" data-cp-node="9:99" alt="">
          </template>
          <div data-cp-page-tip hidden></div>
        </div>
        <details class="cp-page-nodes">
          <ul class="cp-page-list">
            <li data-cp-node="1:10">Button</li>
            <li data-cp-node="1:20">Card</li>
          </ul>
        </details>
      </section>`;
    await flush();
}

const stage = () => document.querySelector(".cp-page-stage") as HTMLElement;
const overlay = (id: string) =>
    document.querySelector(
        `.cp-page-stage [data-cp-node="${id}"]`,
    ) as HTMLElement;
const check = async (input: HTMLInputElement, on = true) => {
    if (input.type === "radio") {
        for (const other of document.querySelectorAll<HTMLInputElement>(
            "[data-cp-page-lane]",
        ))
            other.checked = false;
    }
    input.checked = on;
    input.dispatchEvent(new Event("change", { bubbles: true }));
    await flush();
};
const renders = () =>
    Array.from(
        document.querySelectorAll(".cp-page-stage .cp-page-render"),
    ) as HTMLImageElement[];

describe("<cp-design-page>", () => {
    afterEach(resetDom);

    it("finds a node under EITHER of Figma's id spellings", async () => {
        // The export writes `1-20`, the manifest says `1:20`. Normalising would mean rewriting the
        // export's own attributes; trying the second spelling is far less invasive.
        await mount();
        assert.equal(overlay("1:20").hasAttribute("data-cp-missing"), false);
    });

    it("says so on a node the export does not carry, rather than dropping it", async () => {
        // A layer the design tool flattened on the way out. The row in the list still shows the
        // mapping, and `[data-cp-missing]` is what a person wondering where their shape went can
        // look for.
        await mount({ missing: true });
        assert.equal(overlay("9:99").hasAttribute("data-cp-missing"), true);
    });

    it("adopts the renders for the lane the page opens on", async () => {
        // They are served inert inside a `<template>`, so the browser loads none of them until a
        // lane that draws them is entered.
        await mount();
        assert.equal(renders().length, 2, "and only for nodes the export has");
        assert.equal(
            document.querySelector("[data-cp-page-render-source]"),
            null,
            "the template is spent",
        );
        assert.equal(stage().classList.contains("cp-page-swap-on"), true);
    });

    it("leaves the renders alone on the design's own lane", async () => {
        await mount({ lane: "design" });
        assert.equal(renders().length, 0);
        assert.ok(document.querySelector("[data-cp-page-render-source]"));
        assert.equal(stage().classList.contains("cp-page-hide-design"), false);
    });

    it("hides the design's drawing only once OURS has arrived", async () => {
        // Hiding on adoption leaves an empty slot for any render the server cannot produce — a
        // preview that throws, a daemon that falls over, a 404 — and the page opens on this lane
        // with no control to get the sheet back.
        await mount();
        const target = document.querySelector('[data-node-id="1:10"]')!;
        assert.equal(target.classList.contains("cp-page-replaced"), false);
        const image = renders()[0];
        image.dispatchEvent(new Event("load"));
        assert.equal(target.classList.contains("cp-page-replaced"), true);
    });

    it("puts the design's drawing BACK when our render fails", async () => {
        await mount();
        const target = document.querySelector('[data-node-id="1:10"]')!;
        const image = renders()[0];
        image.dispatchEvent(new Event("load"));
        image.dispatchEvent(new Event("error"));
        assert.equal(target.classList.contains("cp-page-replaced"), false);
        assert.equal(
            image.hidden,
            true,
            "or the broken-image glyph sits on the drawing we just restored",
        );
    });

    it("turns the marks on for a coverage filter that would otherwise be invisible", async () => {
        await mount();
        const unlinked = document.querySelector<HTMLInputElement>(
            "[data-cp-page-unlinked]",
        )!;
        const outlines = document.querySelector<HTMLInputElement>(
            "[data-cp-page-outlines]",
        )!;
        await check(unlinked);
        assert.equal(outlines.checked, true);
        assert.equal(stage().classList.contains("cp-page-outlines-on"), true);
        assert.equal(stage().classList.contains("cp-page-unlinked-only"), true);
    });

    it("leaves the marks on when the filter goes off again", async () => {
        // It was an explicit state to arrive at, and silently repainting the sheet plain would read
        // as the filter having broken something.
        await mount();
        const unlinked = document.querySelector<HTMLInputElement>(
            "[data-cp-page-unlinked]",
        )!;
        await check(unlinked);
        await check(unlinked, false);
        assert.equal(stage().classList.contains("cp-page-outlines-on"), true);
        assert.equal(
            stage().classList.contains("cp-page-unlinked-only"),
            false,
        );
    });

    it("takes a muted node out of the tab order, not just out of sight", async () => {
        // CSS alone cannot: `opacity: 0` + `pointer-events: none` still leaves a control focusable,
        // so a keyboard user could tab onto an invisible rectangle with no focus ring and no
        // indication of where they are. Keyed on the GAP — the sheet's own furniture is neither
        // linked nor a gap.
        await mount();
        await check(
            document.querySelector<HTMLInputElement>(
                "[data-cp-page-unlinked]",
            )!,
        );
        assert.equal(
            overlay("1:10").getAttribute("tabindex"),
            null,
            "a gap stays reachable",
        );
        assert.equal(overlay("1:20").getAttribute("tabindex"), "-1");
        assert.equal(overlay("1:20").getAttribute("aria-hidden"), "true");
    });

    it("describes the node under the pointer, from the list's own row", async () => {
        // One server-built description of a node instead of two that can disagree — and the row's
        // href never passes through JavaScript as a string.
        await mount();
        overlay("1:10").dispatchEvent(
            new MouseEvent("mouseenter", { bubbles: false }),
        );
        const tip = document.querySelector("[data-cp-page-tip]") as HTMLElement;
        assert.equal(tip.hidden, false);
        assert.equal(tip.textContent, "Button");
        assert.equal(
            tip.querySelector("[data-cp-node]"),
            null,
            "or the tip would answer the next hover and start cloning itself",
        );
        assert.equal(
            overlay("1:10").classList.contains("cp-page-selected"),
            true,
        );
    });

    it("clears the tip on the way out — it sits over the sheet", async () => {
        await mount();
        const spot = overlay("1:10");
        spot.dispatchEvent(new MouseEvent("mouseenter"));
        spot.dispatchEvent(new MouseEvent("mouseleave"));
        const tip = document.querySelector("[data-cp-page-tip]") as HTMLElement;
        assert.equal(tip.hidden, true);
        assert.equal(spot.classList.contains("cp-page-selected"), false);
    });

    it("pairs a list row with its node on the sheet", async () => {
        // The cheapest way to answer "which one is that?" on a sheet of near-identical silhouettes.
        await mount();
        const row = document.querySelector(
            '.cp-page-list [data-cp-node="1:20"]',
        ) as HTMLElement;
        row.dispatchEvent(new MouseEvent("mouseenter"));
        assert.equal(
            overlay("1:20").classList.contains("cp-page-active"),
            true,
        );
        assert.equal(row.classList.contains("cp-page-active"), true);
    });

    it("stays silent on a page that is not a design page", async () => {
        document.body.innerHTML = `<cp-design-page></cp-design-page><div class="cp-grid"></div>`;
        await flush();
        assert.equal(document.querySelector(".cp-page-selected"), null);
    });
});
