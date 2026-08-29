// Reporting several comparisons at once, from the wall.
//
// The wall's report is page-scoped — it names the page, because a wall singles out no preview — and
// that is the right report for "this whole lane is scoring zero". It is the wrong one for a reader
// who has spotted four components drifting the same way: filed page-scoped, that issue carries no
// locator, so the catalog's index skips it and it never reaches the Bugs column of any row it is
// about. Ticking rows is what gives it one block per row instead.
//
// The rule worth pinning hardest is the one with no symptom: the producer refuses a body naming a
// component twice, and refuses the WHOLE body — so a report ticking two variants of one component
// is not partly indexed, it silently never appears. The wall stops that at the tick.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom, stubStorage } from "./setup.js";
import { locatorBlocks, locatorForRow } from "../src/compare/picks.js";
import "../src/components/CompareWall.js";

const FACTS = {
    repository: "yschimke/wear-m3-catalog",
    system: "wear-m3-catalog",
    revision: "yschimke/wear-m3-catalog@design-artifacts/wear-m3-catalog",
};

describe("locatorForRow", () => {
    it("reads the pair off the focused comparison the row links to", () => {
        const locator = locatorForRow(
            "/wear-m3-catalog/compare/button__ideal__default__light?token=t&reference=design-button",
            "https://preview.example/wear-m3-catalog/compare",
            "Button/Ideal",
            FACTS,
        );
        assert.deepEqual(locator, {
            repository: "yschimke/wear-m3-catalog",
            system: "wear-m3-catalog",
            componentId: "Button/Ideal",
            previewId: "button__ideal__default__light",
            referenceId: "design-button",
            variant: "ideal/default/light",
            overrides: {},
            revision: FACTS.revision,
        });
    });

    // Off the reference lane the "+ file" link falls back to the viewer's own report, which names
    // no reference — and a locator missing either id is one the producer refuses outright.
    it("contributes nothing when the link names no pair", () => {
        assert.equal(
            locatorForRow(
                "/wear-m3-catalog/p/button__ideal__default__light#cp-report",
                "https://preview.example/",
                "Button/Ideal",
                FACTS,
            ),
            null,
        );
        assert.equal(
            locatorForRow("", "https://preview.example/", "B", FACTS),
            null,
        );
    });
});

describe("locatorBlocks", () => {
    const of = (component: string, preview: string, reference: string) => ({
        ...FACTS,
        componentId: component,
        previewId: preview,
        referenceId: reference,
        variant: "ideal",
        overrides: {},
    });

    it("writes one block per picked comparison", () => {
        const blocks = locatorBlocks([
            of("Button/Ideal", "button__ideal", "design-button"),
            of("Card/Ideal", "card__ideal", "design-card"),
        ]);
        assert.equal(blocks.length, 2);
        assert.ok(blocks[0].includes("component: Button/Ideal"));
        assert.ok(blocks[1].includes("component: Card/Ideal"));
    });

    // The producer rejects the whole body on any of these, so a body carrying one is not a partly
    // indexed report — it is an issue that never appears anywhere.
    it("never names a component, a preview or a reference twice", () => {
        const blocks = locatorBlocks([
            of("Button/Ideal", "button__light", "design-button-light"),
            of("Button/Ideal", "button__dark", "design-button-dark"),
            of("Card/Ideal", "button__light", "design-card"),
            of("Chip/Ideal", "chip__light", "design-button-light"),
        ]);
        assert.equal(blocks.length, 1);
        assert.ok(blocks[0].includes("preview: button__light"));
    });
});

// ---- the element -------------------------------------------------------------------------------

const TEMPLATE = [
    "### Which page",
    "",
    "| | |",
    "| --- | --- |",
    "| Design system | `wear-m3-catalog` |",
    "{{locators}}",
].join("\n");

function row(name: string, component: string, reference: string): string {
    return `
      <tr class="cp-compare-row" data-label="${name}" data-hay="${name.toLowerCase()}"
          data-preview-ids="${name}" data-component-id="${component}"
          data-png-light="/render/${name}.png"
          ${reference ? `data-reference-light="/reference/${reference}.png"` : ""}
          ${reference ? `data-reference-detail-light="/compare/${name}?reference=${reference}"` : ""}>
        <th scope="row"><label class="cp-compare-pick"><input type="checkbox" class="cp-compare-pick-input"></label><a href="/p/${name}">${name}</a></th>
        <td class="cp-compare-render-cell"><img class="cp-compare-png" alt=""></td>
        <td class="cp-compare-diff-cell"><canvas class="cp-compare-diff"></canvas></td>
        <td class="cp-compare-target-cell"><img class="cp-compare-vector" alt=""><canvas class="cp-compare-rc" hidden></canvas></td>
        <td class="cp-compare-score"></td>
        <td class="cp-compare-bugs"><a class="cp-compare-bug-new" href="${
            reference
                ? `/compare/${name}?reference=${reference}`
                : `/p/${name}#cp-report`
        }" data-bug-fallback="/p/${name}#cp-report">+ file</a></td>
      </tr>`;
}

async function wall(rows: string, locatorFacts = true): Promise<void> {
    document.body.innerHTML = `
      <cp-compare-wall></cp-compare-wall>
      <div id="cp-compare" data-default-format="reference" data-default-theme="light"
           data-theme-key="cp-compare-theme" data-has-reference="1" data-has-svg="1">
        <button data-compare-format="svg">SVG</button>
        <button data-compare-format="reference">Reference</button>
        <button data-compare-theme="light">Light</button>
        <button data-compare-theme="dark">Dark</button>
        <input id="cp-compare-search" value="">
        <span id="cp-compare-count"></span>
        <p id="cp-compare-picked" hidden><span class="cp-compare-picked-text"></span><button type="button" class="cp-compare-picked-clear">clear</button></p>
        <details class="cp-report" id="cp-report" data-cp-repo="${FACTS.repository}"${
            locatorFacts
                ? ` data-cp-locator-system="${FACTS.system}" data-cp-locator-revision="${FACTS.revision}"`
                : ""
        }>
          <form class="cp-report-form">
            <input type="hidden" name="body" id="cp-report-body" value="### Which page"
                   data-report-template="${TEMPLATE.replace(/\n/g, "&#10;")}">
          </form>
        </details>
        <div id="cp-compare-formats"><table class="cp-compare-table"><tbody>${rows}</tbody></table></div>
        <p id="cp-compare-empty" hidden></p>
      </div>`;
    await flush();
    for (let i = 0; i < 6; i++) await flush();
}

// By NAME, never by position: the wall re-orders its rows worst-first, so an unmeasurable row
// leads and an index into the document is not the row the test meant.
const box = (name: string): HTMLInputElement =>
    document.querySelector<HTMLInputElement>(
        `[data-label="${name}"] .cp-compare-pick-input`,
    )!;
const boxes = () =>
    Array.from(
        document.querySelectorAll<HTMLInputElement>(".cp-compare-pick-input"),
    );
const bodyValue = () =>
    document.querySelector<HTMLInputElement>("#cp-report-body")?.value ?? "";
const picked = () => document.getElementById("cp-compare-picked");
const tick = async (name: string) => {
    const input = box(name);
    input.checked = true;
    input.dispatchEvent(new Event("change"));
    await flush();
};

describe("the wall's multi-row picker", () => {
    beforeEach(() => {
        stubStorage();
        window.history.replaceState(null, "", "/compare");
    });
    afterEach(() => {
        resetDom();
        window.history.replaceState(null, "", "/");
    });

    it("names the ticked comparisons in the page report", async () => {
        await wall(
            row("Button", "Button/Ideal", "design-button") +
                row("Card", "Card/Ideal", "design-card"),
        );
        assert.equal(
            picked()?.hidden,
            true,
            "nothing said until something is ticked",
        );
        await tick("Button");
        await tick("Card");
        const body = bodyValue();
        assert.ok(body.includes("component: Button/Ideal"), body);
        assert.ok(body.includes("component: Card/Ideal"), body);
        assert.ok(body.includes("reference: design-card"), body);
        assert.equal(picked()?.hidden, false);
        assert.match(
            picked()?.textContent ?? "",
            /2 comparisons will be named/,
        );
    });

    // The failure with no symptom: two variants of one component make a body the producer refuses
    // wholesale, so the issue is filed and then indexed nowhere.
    it("refuses a second variant of a component already picked", async () => {
        await wall(
            row("ButtonLight", "Button/Ideal", "design-button-light") +
                row("ButtonDark", "Button/Ideal", "design-button-dark"),
        );
        await tick("ButtonLight");
        assert.equal(box("ButtonDark").disabled, true);
        assert.match(box("ButtonDark").title, /can name a component once/);
        box("ButtonLight").checked = false;
        box("ButtonLight").dispatchEvent(new Event("change"));
        await flush();
        assert.equal(
            box("ButtonDark").disabled,
            false,
            "unticking gives the sibling back",
        );
    });

    it("cannot pick a row this lane has no reference for", async () => {
        await wall(
            row("Button", "Button/Ideal", "design-button") +
                row("Chip", "Chip/Ideal", ""),
        );
        assert.equal(box("Chip").disabled, true);
        assert.match(box("Chip").title, /no design reference/);
    });

    // Off the reference lane there is nothing to name, so the ticks go rather than sitting there
    // claiming comparisons the page is no longer making.
    it("drops the picks when the lane leaves the reference comparison", async () => {
        await wall(row("Button", "Button/Ideal", "design-button"));
        await tick("Button");
        assert.ok(bodyValue().includes("component: Button/Ideal"));
        document
            .querySelector<HTMLElement>("[data-compare-format='svg']")!
            .click();
        for (let i = 0; i < 6; i++) await flush();
        assert.equal(box("Button").checked, false);
        assert.ok(!bodyValue().includes("compose-parity-locator"), bodyValue());
        assert.equal(picked()?.hidden, true);
    });

    it("clears every pick on request", async () => {
        await wall(
            row("Button", "Button/Ideal", "design-button") +
                row("Card", "Card/Ideal", "design-card"),
        );
        await tick("Button");
        await tick("Card");
        document
            .querySelector<HTMLElement>(".cp-compare-picked-clear")!
            .click();
        await flush();
        assert.deepEqual(
            boxes().map((b) => b.checked),
            [false, false],
        );
        assert.ok(!bodyValue().includes("compose-parity-locator"), bodyValue());
    });

    // A wall with no report to file against — a plain local session — must not offer a tick that
    // could not become anything.
    it("stays off entirely when the page has no locator facts", async () => {
        await wall(row("Button", "Button/Ideal", "design-button"), false);
        assert.equal(
            document
                .querySelector(".cp-compare-table")
                ?.getAttribute("data-picking"),
            null,
        );
    });
});
