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
  <div id="cp-design-page">
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
  </div>
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
    // Read through to STAGE on every call, so a test can narrow the stage mid-run the
    // way an opening side panel does.
    // A 1 px border, like the real stage's: the canvas is `inset: 0`, so it fills the
    // INNER box and the clamp has to be built from that, not from the border box.
    Object.defineProperty(stage, "clientWidth", {
        configurable: true,
        get: () => STAGE.width,
    });
    Object.defineProperty(stage, "clientHeight", {
        configurable: true,
        get: () => STAGE.height,
    });
    stage.getBoundingClientRect = () =>
        ({
            ...STAGE,
            right: STAGE.left + STAGE.width,
            bottom: STAGE.top + STAGE.height,
        }) as DOMRect;
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
    // The browser's hit test, over the same model — TOPMOST FIRST, which is the order a
    // real one answers in and the order `chainAt` reads a lineage from.
    document.elementsFromPoint = ((x: number, y: number) =>
        Array.from(document.querySelectorAll("[data-node-id]"))
            .filter((node) => {
                const r = node.getBoundingClientRect();
                return (
                    x >= r.left && x <= r.right && y >= r.top && y <= r.bottom
                );
            })
            .sort(topmostFirst)) as typeof document.elementsFromPoint;
}

/**
 * Document order, REVERSED — which is paint order for an SVG, and therefore the order a
 * real `elementsFromPoint` answers in. It gets both cases the drill cares about right: a
 * child paints over its parent, and a later sibling paints over an earlier one.
 */
function topmostFirst(a: Element, b: Element): number {
    return a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING
        ? 1
        : -1;
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
    // happy-dom's `WheelEvent` drops every MouseEvent field its init carries — the
    // modifier AND the coordinates — so they have to be defined onto the event. Without
    // the coordinates the zoom still scales but anchors on `NaN`, which is exactly the
    // half of this gesture a scale-only assertion cannot see.
    Object.defineProperty(event, "ctrlKey", { value: ctrl });
    Object.defineProperty(event, "clientX", { value: point.x });
    Object.defineProperty(event, "clientY", { value: point.y });
    el(".cp-page-stage").dispatchEvent(event);
    return event.defaultPrevented;
}

/** happy-dom has no `PointerEvent`; a `MouseEvent` with a pointer id stands in. */
function pointer(
    type: string,
    x: number,
    y: number,
    id = 1,
    buttons = 1,
): void {
    const event = new MouseEvent(type, {
        bubbles: true,
        clientX: x,
        clientY: y,
        button: 0,
        buttons,
    });
    Object.defineProperty(event, "pointerId", { value: id });
    const target = type === "pointerdown" ? el(".cp-page-stage") : window;
    target.dispatchEvent(event);
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
        const stage = el<HTMLElement>(".cp-page-stage");
        assert.equal(stage.classList.contains("cp-page-zoomed"), false);
        // The published scale goes back to 1 as well. The stylesheet counter-scales every node's
        // mark by it, so a reset that restored the view and left the variable behind would draw
        // hairlines at the old zoom over a sheet at 1:1 — a residue no view assertion can see.
        // Compared as the published STRING, with no `|| 1` fallback: a reset that published `0`,
        // an empty value or `NaN` would parse-or-default its way past a numeric check while the
        // counter-scaling stayed broken.
        assert.equal(stage.style.getPropertyValue("--cp-page-zoom"), "1");
    });

    it("resets from a WHEEL zoom too, not only from a framed section", async () => {
        // The capture that used to assert this could only afford one route in. Reset is reachable
        // from a continuous wheel zoom as well as a discrete double-click frame, and the two arrive
        // at the view through different code — `zoomAbout` versus `frameRect`.
        await mount();
        wheel(at(320, 215), -120, true);
        wheel(at(320, 215), -120, true);
        await flush();
        assert.ok(view().scale > 1, "the wheel zoomed");
        el<HTMLButtonElement>("[data-cp-page-zoom-reset]").click();
        await flush();
        assert.deepEqual(view(), { scale: 1, x: 0, y: 0 });
        assert.equal(el<HTMLElement>("cp-page-zoom").hidden, true);
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

    it("keeps the zoom when Escape is the press that clears a selection", async () => {
        await mount();
        // `design-page.js` listens on `#cp-design-page` and clears its selection there.
        // Reproduced exactly, because the ORDER is the whole point: a bubbling document
        // listener runs after this one, sees the mark already gone, and throws away a
        // reading position three double-clicks deep in answer to a press meant for a
        // tooltip.
        const spot = el(".cp-page-node");
        el("#cp-design-page").addEventListener("keydown", (event) => {
            if ((event as KeyboardEvent).key === "Escape") {
                spot.classList.remove("cp-page-selected");
            }
        });
        dblclick(at(65, 430));
        await flush();
        const framed = percent();
        spot.classList.add("cp-page-selected");
        spot.dispatchEvent(
            new KeyboardEvent("keydown", { key: "Escape", bubbles: true }),
        );
        await flush();
        assert.equal(percent(), framed, "the first press is the selection's");
        assert.equal(spot.classList.contains("cp-page-selected"), false);
        // …and the next one is the zoom's.
        spot.dispatchEvent(
            new KeyboardEvent("keydown", { key: "Escape", bubbles: true }),
        );
        await flush();
        assert.equal(percent(), 100);
    });

    it("forgets how deep the reader walked once the view is back at 1:1", async () => {
        await mount();
        dblclick(at(65, 430));
        await flush();
        const framed = percent();
        dblclick(at(110, 200));
        await flush();
        assert.ok(percent() > framed);
        // Back to 1:1 by the button rather than by Reset. The drill stack has to go with
        // the zoom, or the next double-click resumes from a depth nothing on screen shows.
        const out = document.querySelectorAll<HTMLButtonElement>(
            "cp-page-zoom .cp-page-zoom-step",
        )[0];
        for (let i = 0; i < 12 && percent() > 100; i++) {
            out.click();
            await flush();
        }
        assert.equal(percent(), 100);
        dblclick(at(65, 430));
        await flush();
        assert.equal(
            percent(),
            framed,
            "the walk starts again from the outermost level",
        );
    });

    it("keeps the reader's place when the stage changes size", async () => {
        await mount();
        dblclick(at(65, 430));
        await flush();
        const before = view();
        const fraction = -before.x / (STAGE.width * before.scale);
        // A side panel opens: the stage narrows under a zoomed sheet.
        STAGE.width = 600;
        window.dispatchEvent(new Event("resize"));
        await flush();
        const after = view();
        assert.ok(
            Math.abs(-after.x / (STAGE.width * after.scale) - fraction) < 0.001,
            "the same part of the sheet is still under the middle of the view",
        );
        STAGE.width = 1200;
    });

    it("jumps rather than eases when focus reveals an off-screen node", async () => {
        await mount();
        dblclick(at(65, 430));
        await flush();
        // The overlay's node sits below the framed view; `design-page.js` parks its
        // tooltip from the box this reveal leaves behind, so an eased pan would put the
        // two in different places.
        const spot = el<HTMLElement>(".cp-page-node");
        spot.getBoundingClientRect = () =>
            ({
                left: 100,
                top: 1400,
                width: 80,
                height: 80,
                right: 180,
                bottom: 1480,
            }) as DOMRect;
        const moved = view();
        spot.dispatchEvent(new FocusEvent("focus", { bubbles: true }));
        await flush();
        assert.notEqual(
            view().y,
            moved.y,
            "the sheet panned to bring the node in",
        );
        assert.equal(
            el(".cp-page-canvas").classList.contains("cp-page-canvas-live"),
            true,
            "…with the transition off, so the node is where it will be measured",
        );
    });

    it("drills the tree, not whatever else is painted under the pointer", async () => {
        // Two OVERLAPPING SIBLINGS: a badge drawn over the left card, big enough to be
        // drillable. Ordering every hit by area would make the card look like the badge's
        // parent and let a second double-click "descend" from one into the other, which is
        // a relationship the export does not have.
        await mount(`
          <div id="cp-design-page">
          <div class="cp-page-stage">
            <div class="cp-page-canvas" data-cp-page-canvas>
              <svg data-box="0,0,1200,800">
                <g data-node-id="card-a" data-box="40,90,560,690">
                  <g data-node-id="slot-a1" data-box="90,115,460,200"></g>
                </g>
                <g data-node-id="badge" data-box="100,120,300,150"></g>
              </svg>
            </div>
            <cp-page-zoom hidden></cp-page-zoom>
          </div>
          </div>`);
        // A point inside the badge, the card and the slot at once. The badge is the
        // topmost thing painted there, so the drill takes the badge's own lineage — and
        // the badge has no addressable parent, so that is where it stops.
        dblclick(at(200, 200));
        await flush();
        const framed = percent();
        assert.ok(framed > 100, "the badge is framed");
        dblclick(at(200, 200));
        await flush();
        assert.ok(
            percent() < framed,
            "and there is nowhere deeper to go — a sibling is not a child",
        );
    });

    it("gives a keyboard reader a way in and out", async () => {
        await mount();
        // The sheet is at 1:1 with the corner control hidden, and every other gesture here
        // needs a pointer. Focus lands on an overlay the way Tab puts it there.
        const spot = el<HTMLElement>(".cp-page-node");
        spot.dispatchEvent(
            new KeyboardEvent("keydown", { key: "+", bubbles: true }),
        );
        await flush();
        assert.ok(percent() > 100, "+ zooms in from rest");
        spot.dispatchEvent(
            new KeyboardEvent("keydown", { key: "-", bubbles: true }),
        );
        await flush();
        assert.equal(percent(), 100, "- comes back");
        spot.dispatchEvent(
            new KeyboardEvent("keydown", { key: "+", bubbles: true }),
        );
        spot.dispatchEvent(
            new KeyboardEvent("keydown", { key: "+", bubbles: true }),
        );
        await flush();
        spot.dispatchEvent(
            new KeyboardEvent("keydown", { key: "0", bubbles: true }),
        );
        await flush();
        assert.equal(percent(), 100, "0 resets");
    });

    it("leaves the keys to a control that has focus", async () => {
        await mount();
        const box = document.createElement("input");
        box.type = "checkbox";
        el("#cp-design-page").appendChild(box);
        box.dispatchEvent(
            new KeyboardEvent("keydown", { key: "+", bubbles: true }),
        );
        await flush();
        assert.equal(percent(), 100);
    });

    it("treats a page-mode wheel as a page, not as a pixel", async () => {
        await mount();
        // DOM_DELTA_PAGE sends ±1 for a whole notch; read as pixels that is a factor of
        // 1.002 and the gesture looks inert.
        wheel({ x: 600, y: 400 }, -1, true, 2);
        await flush();
        assert.ok(
            percent() > 105,
            `a page-mode notch must zoom, got ${percent()}%`,
        );
    });

    it("does not drill when the double-click is on its own buttons", async () => {
        await mount();
        const [out, into] = document.querySelectorAll<HTMLButtonElement>(
            "cp-page-zoom .cp-page-zoom-step",
        );
        into.click();
        await flush();
        const once = percent();
        into.click();
        await flush();
        const twice = percent();
        assert.ok(twice > once, "two presses of + are two steps in");
        // …and the `dblclick` those two presses also produce must not be read as a
        // gesture on the sheet: drilling from the button's coordinates would either
        // frame whatever is painted under the bar or spend a step zooming back out.
        into.dispatchEvent(
            new MouseEvent("dblclick", {
                bubbles: true,
                clientX: 1150,
                clientY: 760,
            }),
        );
        await flush();
        assert.equal(
            percent(),
            twice,
            "the double-click over the control changed nothing",
        );
        out.click();
        await flush();
        assert.equal(percent(), once, "and the buttons still work either way");
    });

    it("re-describes the focused node after a keyboard zoom", async () => {
        await mount();
        // `design-page.js` parks its tooltip when focus lands on an overlay, and a
        // keyboard zoom moves that overlay without producing a new focus event — so
        // without a nudge the tip is left stranded where the node used to be.
        const spot = el<HTMLElement>(".cp-page-node");
        let described = 0;
        spot.addEventListener("focus", () => described++);
        spot.focus();
        const parked = described;
        spot.dispatchEvent(
            new KeyboardEvent("keydown", { key: "+", bubbles: true }),
        );
        await flush();
        assert.ok(percent() > 100, "the sheet zoomed");
        assert.ok(
            described > parked,
            "…and the page was asked to re-measure the tip",
        );
    });

    it("writes the settled transform before it measures a target", async () => {
        await mount();
        dblclick(at(65, 430));
        await flush();
        const framed = view();
        // What a mid-flight second drill would otherwise read: the canvas still
        // carrying an interpolated transform while `this.view` holds the destination.
        // `settle()` has to put the destination back before anything is measured.
        el<HTMLElement>(".cp-page-canvas").style.transform =
            "translate(0px, 0px) scale(1.4)";
        dblclick(at(110, 200));
        await flush();
        assert.ok(
            percent() > Math.round(framed.scale * 100),
            "the second drill went deeper from the settled view, not from a stale one",
        );
    });

    it("keeps the control in place while a keyboard reader is standing on it", async () => {
        await mount();
        const spot = el<HTMLElement>(".cp-page-node");
        spot.dispatchEvent(
            new KeyboardEvent("keydown", { key: "+", bubbles: true }),
        );
        await flush();
        const reset = el<HTMLButtonElement>("[data-cp-page-zoom-reset]");
        reset.focus();
        reset.click();
        await flush();
        // Back at 1:1 — but hiding the bar now would delete the focused element from
        // under the reader, and the browser would drop focus to `<body>`.
        assert.equal(percent(), 100);
        assert.equal(el<HTMLElement>("cp-page-zoom").hidden, false);
        assert.equal(document.activeElement, reset);
        // It goes once focus has moved on.
        spot.focus();
        await new Promise((r) => setTimeout(r, 5));
        assert.equal(el<HTMLElement>("cp-page-zoom").hidden, true);
    });

    it("never spends the drag guard on a keyboard click", async () => {
        await mount();
        const spot = el<HTMLElement>(".cp-page-node");
        spot.dispatchEvent(
            new KeyboardEvent("keydown", { key: "+", bubbles: true }),
        );
        await flush();
        // A pan the browser took over: it travels, then cancels. No click ever follows,
        // so the guard must not be left armed for an unrelated keyboard activation.
        pointer("pointerdown", 400, 400);
        pointer("pointermove", 460, 430);
        pointer("pointercancel", 460, 430);
        let clicked = 0;
        spot.addEventListener("click", () => clicked++);
        // `detail: 0` is what Enter and Space produce.
        spot.dispatchEvent(
            new MouseEvent("click", { bubbles: true, detail: 0 }),
        );
        assert.equal(clicked, 1, "the keyboard activation was delivered");
    });

    it("holds the sheet against the stage's inner edge, not its border", async () => {
        await mount();
        wheel({ x: 600, y: 400 }, -300, true);
        await flush();
        const scale = view().scale;
        assert.ok(scale > 1.5, "zoomed enough to have somewhere to pan");
        // Drag far past the right edge; the clamp is what stops it.
        pointer("pointerdown", 900, 400);
        pointer("pointermove", -4000, 400);
        pointer("pointerup", -4000, 400);
        await flush();
        // The sheet's right edge lands ON the stage's inner edge. Clamping against the
        // border box instead would allow 2 x (scale - 1) more travel — a blank strip
        // where the drawing should be, tens of pixels wide at high zoom.
        assert.ok(
            Math.abs(view().x - (STAGE.width - STAGE.width * scale)) < 0.5,
            `expected the pan to stop at the inner edge, got x=${view().x}`,
        );
    });

    it("gives up a pan whose release it never saw", async () => {
        await mount();
        wheel({ x: 600, y: 400 }, -300, true);
        await flush();
        pointer("pointerdown", 600, 400);
        // Released outside the window: no `pointerup`, no `pointercancel`. The next move
        // arrives with no button held, and must not drag the sheet.
        pointer("pointermove", 500, 400, 1, 0);
        const parked = view();
        pointer("pointermove", 200, 400, 1, 0);
        await flush();
        assert.deepEqual(
            view(),
            parked,
            "the sheet stayed where the reader left it",
        );
    });

    it("lets a jittery tap through to the component it landed on", async () => {
        await mount();
        wheel({ x: 600, y: 400 }, -300, true);
        await flush();
        const spot = el<HTMLElement>(".cp-page-node");
        let clicked = 0;
        spot.addEventListener("click", () => clicked++);
        // A noisy finger: eight moves, none of them more than 2 px from where it landed.
        // Summed as a path that is 16 px of "drag"; as a displacement it is a tap.
        pointer("pointerdown", 500, 400);
        for (let i = 0; i < 4; i++) {
            pointer("pointermove", 502, 401);
            pointer("pointermove", 500, 400);
        }
        pointer("pointerup", 500, 400);
        spot.dispatchEvent(
            new MouseEvent("click", { bubbles: true, detail: 1 }),
        );
        assert.equal(clicked, 1, "the tap reached the component's link");
    });

    it("still swallows the click after a drag that wandered back", async () => {
        await mount();
        wheel({ x: 600, y: 400 }, -300, true);
        await flush();
        const spot = el<HTMLElement>(".cp-page-node");
        let clicked = 0;
        spot.addEventListener("click", () => clicked++);
        // Out 200 px and back to the start: the sheet was panned and panned back, so the
        // click that follows is the tail of a drag, not a navigation.
        pointer("pointerdown", 500, 400);
        pointer("pointermove", 700, 400);
        pointer("pointermove", 500, 400);
        pointer("pointerup", 500, 400);
        spot.dispatchEvent(
            new MouseEvent("click", { bubbles: true, detail: 1 }),
        );
        assert.equal(clicked, 0, "the drag's click was swallowed");
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
