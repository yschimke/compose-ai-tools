// Behavioural contract for `<cp-page-zoom>`, driven against a fake layout.
//
// happy-dom has no layout, so every rect here comes from a tiny model: each
// `[data-node-id]` declares its box in the export's user units, and the helper
// maps it through whatever transform the element has written on the canvas —
// exactly what a browser does. That is what makes a NESTED drill testable without
// one: the second double-click has to see the boxes as they are after the first.
//
// The real browser still gets the last word: `pages-snapshot.spec.mjs` drives the
// same gestures with a real pointer and screenshots the result.

import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import "../src/components/PageZoom.js";

/** The stage, in CSS pixels, and the sheet's user-unit size. */
const STAGE = { left: 0, top: 0, width: 1200, height: 800 };
const SHEET = { width: 1200, height: 800 };

/**
 * A design page: two portrait cards, each holding slots, each slot a component —
 * the shape a real Figma export has, and the committed page fixture with it.
 */
const PAGE = `
  <div class="cp-page-stage">
    <div class="cp-page-canvas" data-cp-page-canvas>
      <svg data-box="0,0,1200,800">
        <g data-node-id="card-a" data-box="40,90,560,690">
          <g data-node-id="slot-a1" data-box="90,115,460,200">
            <g data-node-id="shape-a1" data-box="230,125,180,180"></g>
          </g>
        </g>
        <g data-node-id="card-b" data-box="620,90,560,690"></g>
      </svg>
      <a class="cp-page-node" data-cp-node="shape-a1" href="/p/shape"></a>
    </div>
    <cp-page-zoom hidden></cp-page-zoom>
  </div>`;

function el<T extends Element>(selector: string): T {
    return document.querySelector(selector) as T;
}

function view(): { scale: number; x: number; y: number } {
    const transform = el<HTMLElement>(".cp-page-canvas").style.transform;
    const match =
        /translate\((-?[\d.]+)px, (-?[\d.]+)px\) scale\(([\d.]+)\)/.exec(
            transform,
        );
    if (!match) return { scale: 1, x: 0, y: 0 };
    return { x: +match[1], y: +match[2], scale: +match[3] };
}

/** Where a user-unit point currently sits on screen. */
function at(ux: number, uy: number): { x: number; y: number } {
    const { scale, x, y } = view();
    return {
        x: STAGE.left + x + (ux / SHEET.width) * STAGE.width * scale,
        y: STAGE.top + y + (uy / SHEET.height) * STAGE.height * scale,
    };
}

/**
 * Install the fake layout: the stage is fixed, and everything inside the canvas is
 * its declared user box mapped through the current transform.
 */
function stubLayout(): void {
    const stage = el<HTMLElement>(".cp-page-stage");
    stage.getBoundingClientRect = () =>
        ({ ...STAGE, right: 1200, bottom: 800 }) as DOMRect;
    const canvas = el<HTMLElement>(".cp-page-canvas");
    if (!canvas) return;
    const mapped = (node: Element): DOMRect => {
        const declared = (node.getAttribute("data-box") ?? "0,0,0,0")
            .split(",")
            .map(Number);
        const [ux, uy, uw, uh] = declared;
        const { scale, x, y } = view();
        const left = STAGE.left + x + (ux / SHEET.width) * STAGE.width * scale;
        const top = STAGE.top + y + (uy / SHEET.height) * STAGE.height * scale;
        const width = (uw / SHEET.width) * STAGE.width * scale;
        const height = (uh / SHEET.height) * STAGE.height * scale;
        return {
            left,
            top,
            width,
            height,
            right: left + width,
            bottom: top + height,
        } as DOMRect;
    };
    canvas.getBoundingClientRect = () => mapped(el(".cp-page-canvas svg"));
    for (const node of document.querySelectorAll("[data-box], .cp-page-node")) {
        const box = node.hasAttribute("data-box")
            ? node
            : el('[data-node-id="shape-a1"]');
        (node as HTMLElement).getBoundingClientRect = () => mapped(box);
    }
    // The browser's hit test, over the same model: every addressable box the point
    // lands in, which is the ancestor chain plus nothing else in this fixture.
    document.elementsFromPoint = ((x: number, y: number) =>
        Array.from(document.querySelectorAll("[data-node-id]")).filter(
            (node) => {
                const r = node.getBoundingClientRect();
                return (
                    x >= r.left && x <= r.right && y >= r.top && y <= r.bottom
                );
            },
        )) as typeof document.elementsFromPoint;
}

async function mount(markup = PAGE): Promise<void> {
    document.body.innerHTML = markup;
    stubLayout();
    await flush();
}

function dblclick(
    point: { x: number; y: number },
    init: MouseEventInit = {},
): void {
    el(".cp-page-stage").dispatchEvent(
        new MouseEvent("dblclick", {
            bubbles: true,
            clientX: point.x,
            clientY: point.y,
            ...init,
        }),
    );
}

/**
 * happy-dom's `WheelEvent` drops `ctrlKey` from its init, so the modifier — the
 * whole contract of this gesture — has to be defined onto the event. Nothing
 * production-side depends on the workaround, and the real browser path is covered
 * by the harness's `zoom-wheel` state.
 */
function wheel(
    point: { x: number; y: number },
    deltaY: number,
    ctrl = true,
    deltaMode = 0,
): boolean {
    const event = new WheelEvent("wheel", {
        bubbles: true,
        cancelable: true,
        clientX: point.x,
        clientY: point.y,
        deltaY,
        deltaMode,
    });
    Object.defineProperty(event, "ctrlKey", { value: ctrl });
    el(".cp-page-stage").dispatchEvent(event);
    return event.defaultPrevented;
}

function percent(): number {
    return parseInt(el("[data-cp-page-zoom-level]").textContent ?? "", 10);
}

describe("<cp-page-zoom>", () => {
    afterEach(() => resetDom());

    it("stays out of the way until there is a zoom to undo", async () => {
        await mount();
        assert.equal(el<HTMLElement>("cp-page-zoom").hidden, true);
        assert.equal(percent(), 100);
    });

    it("frames the section a double-click lands in", async () => {
        await mount();
        // The left card's own ground: inside the card, outside every slot.
        dblclick(at(65, 430));
        await flush();
        assert.ok(percent() > 150, `expected a real zoom, got ${percent()}%`);
        assert.equal(el<HTMLElement>("cp-page-zoom").hidden, false);
        // …and the card, not its neighbour, is what fills the stage.
        const card = el('[data-node-id="card-a"]').getBoundingClientRect();
        assert.ok(card.left >= -1 && card.right <= STAGE.width + 1);
    });

    it("drills one level deeper on the next double-click", async () => {
        await mount();
        dblclick(at(65, 430));
        await flush();
        const framed = percent();
        // The slot's own padding, inside the card now filling the stage.
        dblclick(at(110, 200));
        await flush();
        assert.ok(
            percent() > framed,
            `expected deeper than ${framed}%, stayed at ${percent()}%`,
        );
    });

    it("steps back out when there is nothing deeper under the pointer", async () => {
        await mount();
        dblclick(at(65, 430));
        await flush();
        const framed = percent();
        // Card B is not on screen now, and the point over empty ground inside the
        // framed card has no smaller box under it.
        dblclick(at(65, 430));
        await flush();
        assert.ok(percent() < framed, "a dead-end double-click zooms out");
    });

    it("zooms out a level on alt-double-click without drilling", async () => {
        await mount();
        dblclick(at(65, 430));
        await flush();
        const framed = percent();
        dblclick(at(65, 430), { altKey: true });
        await flush();
        assert.ok(percent() < framed);
    });

    it("does nothing at all on a double-click over unzoomable ground", async () => {
        await mount();
        // Outside both cards: the fake hit test finds no addressable box.
        dblclick(at(610, 800));
        await flush();
        assert.equal(percent(), 100);
        assert.equal(el<HTMLElement>("cp-page-zoom").hidden, true);
    });

    it("zooms about the pointer on ⌘/Ctrl + wheel, and only then", async () => {
        await mount();
        assert.equal(
            wheel(at(320, 215), -120, false),
            false,
            "a plain wheel is the page's",
        );
        assert.equal(percent(), 100);
        assert.equal(
            wheel(at(320, 215), -120, true),
            true,
            "a modified wheel is ours",
        );
        await flush();
        assert.ok(percent() > 100);
    });

    it("treats a line-mode wheel as pixels, not as a thousandth of one", async () => {
        await mount();
        wheel({ x: 600, y: 400 }, -3, true, 1);
        await flush();
        assert.ok(
            percent() > 105,
            `a three-line scroll must zoom, got ${percent()}%`,
        );
    });

    it("publishes the scale for the stylesheet to counter-scale the marks by", async () => {
        await mount();
        dblclick(at(65, 430));
        await flush();
        const stage = el<HTMLElement>(".cp-page-stage");
        assert.ok(
            parseFloat(stage.style.getPropertyValue("--cp-page-zoom")) > 1.5,
        );
        assert.ok(stage.classList.contains("cp-page-zoomed"));
    });

    it("resets to exactly 1:1, and takes itself off the stage", async () => {
        await mount();
        dblclick(at(65, 430));
        await flush();
        el<HTMLButtonElement>("[data-cp-page-zoom-reset]").click();
        await flush();
        assert.deepEqual(view(), { scale: 1, x: 0, y: 0 });
        assert.equal(el<HTMLElement>("cp-page-zoom").hidden, true);
        assert.equal(
            el<HTMLElement>(".cp-page-stage").classList.contains(
                "cp-page-zoomed",
            ),
            false,
        );
    });

    it("zooms in and out a notch from the corner buttons", async () => {
        await mount();
        const buttons = document.querySelectorAll<HTMLButtonElement>(
            "cp-page-zoom .cp-page-zoom-step",
        );
        buttons[1].click();
        await flush();
        const inned = percent();
        assert.ok(inned > 100);
        buttons[0].click();
        await flush();
        assert.ok(percent() < inned);
    });

    it("unwinds the zoom on Escape", async () => {
        await mount();
        dblclick(at(65, 430));
        await flush();
        document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
        await flush();
        assert.equal(percent(), 100);
    });

    it("defers Escape to the page's own selection first", async () => {
        await mount();
        dblclick(at(65, 430));
        await flush();
        // `design-page.js` marks the selected node; one press must clear that, not
        // throw away a reading position three double-clicks deep.
        el(".cp-page-node").classList.add("cp-page-selected");
        document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
        await flush();
        assert.ok(
            percent() > 150,
            "the zoom survives the press that clears a selection",
        );
    });

    it("leaves Escape alone when the sheet is at rest", async () => {
        await mount();
        let seen = false;
        document.addEventListener("keydown", () => (seen = true));
        document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
        assert.equal(seen, true);
        assert.equal(percent(), 100);
    });

    it("stops driving the stage once removed", async () => {
        await mount();
        dblclick(at(65, 430));
        await flush();
        const framed = view();
        el("cp-page-zoom").remove();
        await flush();
        dblclick(at(110, 200));
        await flush();
        assert.deepEqual(
            view(),
            framed,
            "no listener should survive the element",
        );
    });

    it("is inert on a stage with no canvas to transform", async () => {
        await mount(`
          <div class="cp-page-stage">
            <svg></svg>
            <cp-page-zoom hidden></cp-page-zoom>
          </div>`);
        dblclick({ x: 100, y: 100 });
        await flush();
        assert.equal(el<HTMLElement>("cp-page-zoom").hidden, true);
    });
});
