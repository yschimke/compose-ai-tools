// `<cp-page-zoom>` — makes a design page's sheet readable: drill in by
// double-click, zoom about the pointer with ⌘/Ctrl + wheel, drag to pan, and one
// subtle corner control to get back out.
//
// WHY THIS SURFACE NEEDS IT AT ALL
//
// A specimen sheet is inlined at the size the design file drew it — m3-catalog's
// Styles page is 6263 px across — and it lands in a content column a sixth of
// that. Every type specimen, swatch number and elevation label on it is therefore
// sub-pixel: the page can be looked at but not read. Because the sheet is SVG
// rather than a raster, a transform is all it takes; the drawing stays vector
// sharp at any factor.
//
// WHAT IT DRIVES, AND WHY THAT IS ONE ELEMENT
//
// `.cp-page-canvas` — the layer holding the export, the component overlays over
// it and the renders inside those. One transform moves the sheet and its whole
// instrumentation together, so a slot cannot come unstuck from the shape it
// stands in. This element sits OUTSIDE that layer (as does the tooltip): a
// control that panned away with the sheet could not be reached to undo the pan.
//
// NEW BEHAVIOUR, SO IT STARTS HERE RATHER THAN IN `design-page.js`
//
// The rest of that page — measuring an overlay onto each node, the lane flip, the
// per-node diff scoring — is still a legacy IIFE awaiting its own port (README.md
// § Porting the next component). Growing it by another 300 lines of gesture
// handling would have made that port harder and left this feature untestable
// except through a browser. The coupling that remains is deliberately one-way and
// through the DOM: this element reads `.cp-page-selected` to know a node is
// selected (so Escape unwinds the selection before the zoom) and writes
// `--cp-page-zoom` on the stage for the stylesheet to counter-scale the marks by.

import { Fragment, h, type VNode } from "vue";
import { customElement } from "../controllerElement.js";
import { VueElement } from "../vueElement.js";
import {
    MIN_SCALE,
    STEP,
    clamp,
    frameRect,
    pickLevel,
    rescale,
    rest,
    revealDelta,
    zoomAbout,
    zoomed,
    type Box,
    type Level,
    type View,
} from "../zoom/viewport.js";

/** A drag under this many pixels was a click that wobbled, not a pan. */
const DRAG_SLOP = 5;

@customElement("cp-page-zoom")
export class PageZoom extends VueElement {
    /** The readout, and the only reactive state — the view itself is not rendered. */
    private percent = 100;

    private stage: HTMLElement | null = null;
    private canvas: HTMLElement | null = null;
    private svg: SVGSVGElement | null = null;

    private view: View = rest();

    /**
     * How deep the reader has walked, outermost first. The export's own
     * `<g data-node-id>` tree supplies the levels; this only remembers which of
     * them is framed. See `pickLevel`.
     */
    private drilled: Element[] = [];

    private panning: {
        id: number;
        x: number;
        y: number;
        /** Where the gesture started, so `moved` can be a displacement and not a path. */
        fromX: number;
        fromY: number;
        moved: number;
        held: boolean;
    } | null = null;

    /** Set by a pan that travelled, spent by the click it would otherwise become. */
    private swallowClick = false;

    private observer: ResizeObserver | null = null;

    /** The stage's size at the last commit, so a resize can carry the pan across it. */
    private stageSize: Box | null = null;

    /** The page this sheet belongs to — the scope the keyboard shortcuts answer in. */
    private page: HTMLElement | null = null;

    // Bound once so `removeEventListener` in `disconnectedCallback` matches.
    private readonly onDblClick = (event: MouseEvent) => this.drill(event);
    private readonly onWheel = (event: WheelEvent) => this.wheel(event);
    private readonly onPointerDown = (event: PointerEvent) =>
        this.startPan(event);
    private readonly onPointerMove = (event: PointerEvent) =>
        this.movePan(event);
    private readonly onPointerUp = (event: PointerEvent) =>
        this.endPan(event, true);
    private readonly onPointerCancel = (event: PointerEvent) =>
        this.endPan(event, false);
    private readonly onDragStart = (event: Event) => {
        // An overlay is an `<a>`, and a browser answers a drag on one by dragging
        // the LINK — a ghost image follows the cursor and the pan never starts.
        // Deliberately not `preventDefault()` on `pointerdown`, which would do the
        // same job and also suppress the compatibility mouse events the
        // double-click drill and a slot's own navigation are built on.
        if (zoomed(this.view)) event.preventDefault();
    };
    private readonly onClickCapture = (event: MouseEvent) => {
        // A keyboard activation (`detail === 0`) is never the click a drag produces, so
        // it must never be the one the guard spends. Without this, a pan that ended in
        // `pointercancel` — which produces no click at all — leaves the flag armed, and
        // the next Enter or Space on a zoom button or a slot link silently does nothing.
        if (event.detail === 0) return;
        if (!this.swallowClick) return;
        this.swallowClick = false;
        event.preventDefault();
        event.stopPropagation();
    };
    private readonly onFocusCapture = (event: FocusEvent) => {
        this.reveal(event.target as Element | null);
    };
    private readonly onKeyDown = (event: KeyboardEvent) => this.escape(event);
    private readonly onFocusOut = () => {
        // Deferred a frame: `focusout` fires BEFORE the next element takes focus, so
        // reading `activeElement` now would answer `<body>` even when focus is moving
        // between two of these buttons.
        setTimeout(() => {
            if (!zoomed(this.view) && !this.contains(document.activeElement)) {
                this.hidden = true;
            }
        }, 0);
    };
    private readonly onPageKeyDown = (event: KeyboardEvent) =>
        this.shortcut(event);
    private readonly onResize = () => {
        const box = this.stageBox();
        const was = this.stageSize;
        // `rescale` keeps the reader's place across the change; `apply` clamps it into
        // the new bounds and records the size the next resize will measure against.
        this.apply(
            was ? rescale(this.view, was, box) : clamp(this.view, box),
            false,
        );
    };

    connectedCallback(): void {
        super.connectedCallback();
        this.stage = this.closest<HTMLElement>(".cp-page-stage");
        this.canvas =
            this.stage?.querySelector<HTMLElement>("[data-cp-page-canvas]") ??
            null;
        this.svg = this.canvas?.querySelector("svg") ?? null;
        // Nothing to transform means the gestures are inert rather than
        // half-working: a readout climbing to 400% over a sheet that never moved
        // would be worse than no zoom at all.
        if (!this.stage || !this.canvas) return;

        this.stage.addEventListener("dblclick", this.onDblClick);
        this.stage.addEventListener("wheel", this.onWheel, { passive: false });
        this.stage.addEventListener("pointerdown", this.onPointerDown);
        this.stage.addEventListener("dragstart", this.onDragStart);
        // Capture phase, so it runs before an overlay's own handler and before the
        // anchor's default.
        this.stage.addEventListener("click", this.onClickCapture, true);
        // Also capture: `focus` does not bubble, and this has to land BEFORE the
        // page's own focus handler parks its tooltip at the node — otherwise the
        // tip is placed against the box the node had before the pan.
        this.stage.addEventListener("focus", this.onFocusCapture, true);
        window.addEventListener("pointermove", this.onPointerMove);
        window.addEventListener("pointerup", this.onPointerUp);
        window.addEventListener("pointercancel", this.onPointerCancel);
        // On the DOCUMENT, and in the CAPTURE phase. Document, because a reader who
        // zoomed with the mouse has focus on nothing in particular (the stage is not
        // focusable) and a listener on this page's own subtree would never see the
        // key. Capture, because `design-page.js` listens on `#cp-design-page` and a
        // bubbling document listener would run AFTER it — by which point the
        // selection it clears is already gone, and one press would unwind both the
        // selection and the zoom. Capture runs before it and still sees the mark.
        document.addEventListener("keydown", this.onKeyDown, true);
        // A KEYBOARD WAY IN, and the only one there is. Every other gesture here needs a
        // pointer — a double-click, a modified wheel, a drag — and the corner control is
        // hidden at 1:1 by design, so without this a keyboard-only reader has no way to
        // enlarge a sheet whose text is sub-pixel. `+` / `-` / `0`, on the page rather
        // than the document so the keys are inert everywhere else, and reachable because
        // every component overlay on the sheet is a real anchor in the tab order.
        this.page = this.closest<HTMLElement>("#cp-design-page") ?? this.stage;
        this.page.addEventListener("keydown", this.onPageKeyDown);
        this.addEventListener("focusout", this.onFocusOut);
        // A resize moves the pan limits with the stage — a sheet panned to its right
        // edge in a wide window is panned past it in a narrow one — and it moves the
        // reader's place, which `rescale` is what preserves. Both signals are wired:
        // the observer catches a stage that changes size on its own (a side panel, a
        // container query), `resize` covers a browser without `ResizeObserver`. Firing
        // twice is harmless: the second call sees no change from the size the first
        // one recorded.
        if (typeof ResizeObserver === "function") {
            this.observer = new ResizeObserver(this.onResize);
            this.observer.observe(this.stage);
        }
        window.addEventListener("resize", this.onResize);
        this.apply(this.view);
    }

    disconnectedCallback(): void {
        this.stage?.removeEventListener("dblclick", this.onDblClick);
        this.stage?.removeEventListener("wheel", this.onWheel);
        this.stage?.removeEventListener("pointerdown", this.onPointerDown);
        this.stage?.removeEventListener("dragstart", this.onDragStart);
        this.stage?.removeEventListener("click", this.onClickCapture, true);
        this.stage?.removeEventListener("focus", this.onFocusCapture, true);
        window.removeEventListener("pointermove", this.onPointerMove);
        window.removeEventListener("pointerup", this.onPointerUp);
        window.removeEventListener("pointercancel", this.onPointerCancel);
        window.removeEventListener("resize", this.onResize);
        document.removeEventListener("keydown", this.onKeyDown, true);
        this.page?.removeEventListener("keydown", this.onPageKeyDown);
        this.removeEventListener("focusout", this.onFocusOut);
        this.observer?.disconnect();
        this.observer = null;
        super.disconnectedCallback();
    }

    protected renderVue(): VNode {
        return h(Fragment, null, [
            h(
                "button",
                {
                    type: "button",
                    class: "cp-page-zoom-step",
                    "aria-label": "Zoom out",
                    onClick: () => this.step(1 / STEP),
                },
                "−",
            ),
            h(
                "span",
                {
                    class: "cp-page-zoom-level",
                    "data-cp-page-zoom-level": "",
                },
                `${this.percent}%`,
            ),
            h(
                "button",
                {
                    type: "button",
                    class: "cp-page-zoom-step",
                    "aria-label": "Zoom in",
                    onClick: () => this.step(STEP),
                },
                "+",
            ),
            h(
                "button",
                {
                    type: "button",
                    class: "cp-page-zoom-reset",
                    "data-cp-page-zoom-reset": "",
                    "aria-label": "Reset zoom",
                    onClick: () => this.reset(true),
                },
                "Reset",
            ),
        ]);
    }

    /**
     * Put the canvas exactly where `this.view` says it is, right now.
     *
     * Every measurement here — a drill target, a focused node — is a
     * `getBoundingClientRect`, and during the 170 ms travel that answers with the
     * INTERPOLATED position while `this.view` already holds the destination.
     * `frameRect` assumes the two describe the same view, so a second double-click
     * landing mid-flight would frame the next level from a box measured in one view
     * and a transform taken from another: a wild over-zoom, off centre.
     *
     * Cheapest correct fix: kill the transition, write the destination, and force the
     * browser to lay it out before reading anything. The move that follows re-enables
     * easing, so it still travels — from where the sheet had actually got to.
     */
    private settle(): void {
        if (!this.canvas) return;
        const { scale, x, y } = this.view;
        this.canvas.classList.add("cp-page-canvas-live");
        this.canvas.style.transform = `translate(${x}px, ${y}px) scale(${scale})`;
        // Reading a layout property is what flushes the style change; without it the
        // rects below are still the ones being animated away from.
        void this.canvas.getBoundingClientRect();
    }

    /**
     * The box the canvas actually fills — the stage's INNER box, not its border box.
     *
     * `.cp-page-canvas` is `inset: 0`, so it fills the padding box, and the stage draws
     * a 1 px border outside that. Clamping against the border box therefore allows
     * `2 × (scale - 1)` pixels of extra travel: at 24x the sheet can be dragged some
     * 46 px past its own edge, exposing a blank strip where the drawing should be.
     *
     * `clientWidth`/`clientHeight` are exactly that inner box. They answer 0 for an
     * element with no layout yet (a page opened in a background tab), so the measured
     * rect stands in — a slightly loose clamp beats no clamp at all.
     */
    private stageBox(): Box {
        const stage = this.stage;
        if (!stage) return { left: 0, top: 0, width: 0, height: 0 };
        const rect = stage.getBoundingClientRect();
        const style = getComputedStyle(stage);
        return {
            left: rect.left + (parseFloat(style.borderLeftWidth) || 0),
            top: rect.top + (parseFloat(style.borderTopWidth) || 0),
            width: stage.clientWidth || rect.width,
            height: stage.clientHeight || rect.height,
        };
    }

    /** Commit a view: transform the canvas, publish the scale, show or hide this bar. */
    private apply(next: View, eased = true): void {
        const box = this.stageBox();
        this.view = clamp(next, box);
        this.stageSize = {
            left: 0,
            top: 0,
            width: box.width,
            height: box.height,
        };
        // Back at 1:1 by ANY route — the minus button, a wheel out, a drill that
        // bottomed out — and the walk down the tree is over with it. Leaving the stack
        // populated presents a reset view whose next double-click resumes from a depth
        // the reader can no longer see: it either skips straight to a nested level or
        // reads as a dead end and backs out without zooming.
        if (!zoomed(this.view)) this.drilled = [];
        const { scale, x, y } = this.view;
        if (this.canvas) {
            // A continuous gesture drives the transform directly; a discrete one (a
            // drill, a button) is eased, so it reads as travel into the sheet
            // rather than as a cut to somewhere else.
            this.canvas.classList.toggle("cp-page-canvas-live", !eased);
            this.canvas.style.transform = `translate(${x}px, ${y}px) scale(${scale})`;
        }
        // Read by the stylesheet to counter-scale everything drawn OVER the sheet —
        // outline widths, score badges — so the instrumentation keeps its size in
        // screen pixels while the drawing grows.
        this.stage?.style.setProperty("--cp-page-zoom", String(scale));
        this.stage?.classList.toggle("cp-page-zoomed", zoomed(this.view));
        // At 1:1 there is nothing to reset, and a permanent control would be chrome
        // over the drawing — but NOT while the reader is standing on it. Pressing
        // Reset (or `-` until the sheet is back at 1:1) with the button focused would
        // otherwise delete the focused element from under them, and the browser drops
        // focus to `<body>`: the reader loses the sheet entirely. The bar waits for
        // focus to leave (see `onFocusOut`) and hides then.
        this.hidden =
            !zoomed(this.view) && !this.contains(document.activeElement);
        this.percent = Math.round(scale * 100);
        this.requestUpdate();
    }

    private step(factor: number, eased = true): void {
        const box = this.stageBox();
        this.apply(
            zoomAbout(
                this.view,
                box,
                box.left + box.width / 2,
                box.top + box.height / 2,
                factor,
            ),
            eased,
        );
    }

    private reset(eased = true): void {
        // The stack is cleared by `apply` itself, which is what makes every other route
        // back to 1:1 behave like this one.
        this.apply(rest(), eased);
    }

    /**
     * The addressable elements under a point, outermost first — the sheet's own
     * tree at that spot.
     *
     * `elementsFromPoint` is the browser's real hit test, so it answers with what
     * is PAINTED there and every ancestor of it, which is exactly the drill chain:
     * the glyph, the label group, the column, the card. A bbox scan would instead
     * hand back every box that merely CONTAINS the point, including a sibling the
     * reader can see they did not click. The scan is still the fallback, for a
     * point over unpainted ground — the gaps between specimens on a sheet with no
     * background fill — where the hit test finds nothing and the honest answer is
     * the enclosing frame.
     *
     * Sorted by area rather than trusted to arrive in tree order: overlapping
     * siblings can both be hit, and "biggest first" is the only ordering that
     * means "outermost first" for both sources.
     */
    private chainAt(clientX: number, clientY: number): Array<Level<Element>> {
        const svg = this.svg;
        if (!svg) return [];
        const hit =
            typeof document.elementsFromPoint === "function"
                ? document.elementsFromPoint(clientX, clientY)
                : [];
        // The topmost thing PAINTED here, and then ITS OWN ancestors — a lineage, not a
        // pile. `elementsFromPoint` also hands back overlapping SIBLINGS (a badge over a
        // card, a shadow layer beside it), and ordering those by area invents a
        // parent-child relationship the export does not have: the drill would frame one
        // sibling and then "descend" into another that never contained it.
        const top = hit.find((el) => el !== svg && svg.contains(el));
        if (top) {
            const lineage: Element[] = [];
            for (
                let el: Element | null = top;
                el && el !== svg;
                el = el.parentElement
            ) {
                if (el.hasAttribute("data-node-id")) lineage.unshift(el);
            }
            if (lineage.length) return lineage.map((node) => this.level(node));
        }
        // Nothing painted here, or nothing addressable above what is: the gaps between
        // specimens on a sheet with no background fill. There is no lineage to read, so
        // fall back to every box that CONTAINS the point, outermost first — which for
        // nested frames is the same answer, and for overlapping siblings is a guess the
        // reader can see rather than a wrong tree.
        return Array.from(svg.querySelectorAll("[data-node-id]"))
            .filter((el) => {
                const box = el.getBoundingClientRect();
                return (
                    box.width > 0 &&
                    box.height > 0 &&
                    clientX >= box.left &&
                    clientX <= box.left + box.width &&
                    clientY >= box.top &&
                    clientY <= box.top + box.height
                );
            })
            .map((node) => this.level(node))
            .filter((level) => level.box.width * level.box.height > 0)
            .sort(
                (a, b) =>
                    b.box.width * b.box.height - a.box.width * a.box.height,
            );
    }

    private level(node: Element): Level<Element> {
        return { node, box: node.getBoundingClientRect() as Box };
    }

    /**
     * WHAT A DOUBLE-CLICK OVER A COMPONENT SLOT DOES, AND WHY IT IS NOT THIS
     *
     * Nothing: the FIRST click of it has already navigated to that component's
     * preview, because every overlay is a real `<a>` and that is the affordance
     * the sheet is built around. Deferring the anchor behind a "was that a
     * double-click?" timer was the alternative and it is worse — it puts a couple
     * of hundred milliseconds on every navigation on the page, and reaches the
     * destination by script rather than by the browser following a link, which is
     * what makes the middle click, the modifier click and the status-bar preview
     * work.
     *
     * So drilling is a gesture of the SHEET and going is a gesture of a SLOT. The
     * two never contend for the same pixels in practice: a section's title, its
     * ground and the space between its specimens are all sheet, and that is where
     * a reader zooming into "Typography" clicks.
     */
    private drill(event: MouseEvent): void {
        // The corner controls are ON the stage but are not the sheet. A quick pair of
        // clicks on `+` arrives here as a double-click over the bar, and drilling from a
        // button's coordinates either frames whatever the sheet paints beneath it or
        // spends the gesture stepping back out — so two clicks on `+` would net one.
        // Same guard, same reason, as the one `startPan` takes.
        if (this.contains(event.target as Node)) return;
        // A second double-click can land inside the first one's travel; measure the
        // sheet where it is going, not where it currently is.
        this.settle();
        const target =
            event.altKey || event.shiftKey
                ? null
                : this.drillIn(event.clientX, event.clientY);
        if (target) {
            this.apply(frameRect(this.view, this.stageBox(), target.box));
        } else if (!this.drillOut(event.clientX, event.clientY)) {
            return;
        }
        // A double-click selects a word by default, and on a sheet of outlined text
        // that leaves a blue smear across whatever was just zoomed to.
        window.getSelection()?.removeAllRanges();
    }

    private drillIn(clientX: number, clientY: number): Level<Element> | null {
        const chain = this.chainAt(clientX, clientY);
        if (!chain.length || !this.svg) return null;
        // The deepest level we have entered that this point is still inside.
        // Double-clicking elsewhere therefore starts again from the outermost frame
        // there, rather than trying to descend a branch the pointer isn't in.
        let start = -1;
        for (let i = this.drilled.length - 1; i >= 0; i--) {
            const at = chain.findIndex(
                (level) => level.node === this.drilled[i],
            );
            if (at >= 0) {
                start = at;
                this.drilled.length = i + 1;
                break;
            }
        }
        if (start < 0) this.drilled = [];
        const level = pickLevel(
            chain,
            start,
            this.stageBox(),
            this.svg.getBoundingClientRect(),
            this.view.scale,
        );
        if (level) this.drilled.push(level.node);
        return level;
    }

    /** Back out one level, to the frame this one was entered from. */
    private drillOut(clientX: number, clientY: number): boolean {
        if (!this.drilled.length) {
            if (!zoomed(this.view)) return false;
            // Zoomed by wheel or button rather than by drilling, so there is no
            // level to return to: step out about the pointer instead.
            this.apply(
                zoomAbout(
                    this.view,
                    this.stageBox(),
                    clientX,
                    clientY,
                    1 / STEP,
                ),
            );
            return true;
        }
        this.drilled.pop();
        const back = this.drilled[this.drilled.length - 1];
        if (back) {
            this.apply(
                frameRect(
                    this.view,
                    this.stageBox(),
                    back.getBoundingClientRect(),
                ),
            );
        } else {
            this.apply(rest());
        }
        return true;
    }

    private wheel(event: WheelEvent): void {
        // The modifier is the whole contract: without it the wheel belongs to the
        // document, so the reader can scroll past a sheet taller than their
        // viewport. It is also what a trackpad pinch arrives as, which is why
        // pinch-to-zoom works with no gesture handler.
        if (!event.ctrlKey && !event.metaKey) return;
        event.preventDefault();
        const box = this.stageBox();
        // `deltaMode` is not always pixels: DOM_DELTA_LINE counts lines and DOM_DELTA_PAGE
        // counts PAGES, where a whole notch arrives as ±1. Taken at face value that is a
        // factor of 1.002 — the readout stays at 100% and the gesture looks broken on the
        // browsers that use those units.
        const unit =
            event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? box.height : 1;
        this.apply(
            zoomAbout(
                this.view,
                box,
                event.clientX,
                event.clientY,
                Math.exp(-event.deltaY * unit * 0.0022),
            ),
            false,
        );
    }

    /**
     * POINTER CAPTURE IS TAKEN LATE, AND THAT IS THE SUBTLETY HERE
     *
     * Capturing on `pointerdown` is the obvious way to keep tracking a drag that
     * leaves the stage, and it silently breaks two things: with a capture override
     * in place the browser dispatches the following `click` to the CAPTURE element
     * rather than to what was under the pointer, so while zoomed a click on a
     * component slot stopped reaching that slot's anchor (no navigation) and a
     * click on Reset stopped reaching the button (no way back out).
     *
     * So capture is taken only once the pointer has actually travelled — by which
     * point this IS a drag, the click that follows is one we swallow anyway, and
     * nothing is left for the retargeting to spoil.
     */
    private startPan(event: PointerEvent): void {
        // A pan whose `pointerup` landed outside the window never produces a click
        // for the guard to spend, so the flag is cleared here too — otherwise the
        // NEXT deliberate click on a component would be the one swallowed.
        this.swallowClick = false;
        if (!zoomed(this.view) || event.button !== 0) return;
        // These controls are ON the stage but are not the sheet: dragging from a
        // button is a mis-click, and treating it as a pan makes the button feel
        // broken.
        if (this.contains(event.target as Node)) return;
        this.panning = {
            id: event.pointerId,
            x: event.clientX,
            y: event.clientY,
            fromX: event.clientX,
            fromY: event.clientY,
            moved: 0,
            held: false,
        };
    }

    private movePan(event: PointerEvent): void {
        const pan = this.panning;
        if (!pan || event.pointerId !== pan.id) return;
        // The button came up somewhere this page never heard about — released outside
        // the window before the drag had travelled far enough to take capture, so
        // neither `pointerup` nor `pointercancel` reached us. Without this the next
        // move on the same pointer id drags the sheet around with no button held, and
        // `setPointerCapture` can throw for a pointer that is no longer active.
        if (!(event.buttons & 1)) {
            this.panning = null;
            this.stage?.classList.remove("cp-page-panning");
            return;
        }
        const dx = event.clientX - pan.x;
        const dy = event.clientY - pan.y;
        pan.x = event.clientX;
        pan.y = event.clientY;
        // How far the pointer has got FROM WHERE IT STARTED, not how far it has
        // travelled. A noisy finger or stylus emits a run of tiny oscillating moves
        // without ever leaving the click radius; summing every delta turns that jitter
        // into a "drag", which arms the guard below and eats the reader's tap on the
        // component. The furthest it ever strayed is what decides — so a real drag that
        // wanders out and comes back still counts as one, because the sheet did move.
        pan.moved = Math.max(
            pan.moved,
            Math.abs(event.clientX - pan.fromX) +
                Math.abs(event.clientY - pan.fromY),
        );
        if (pan.moved > DRAG_SLOP && !pan.held) {
            pan.held = true;
            try {
                this.stage?.setPointerCapture?.(event.pointerId);
            } catch {
                // The pointer is gone; the pan still works off the window listeners.
            }
            this.stage?.classList.add("cp-page-panning");
        }
        this.apply(
            { ...this.view, x: this.view.x + dx, y: this.view.y + dy },
            false,
        );
    }

    /**
     * `clicks` says whether this sequence can still produce one. A `pointercancel` —
     * the browser taking the gesture over — never does, so arming the guard there would
     * leave it primed for something else entirely to spend.
     */
    private endPan(event: PointerEvent, clicks: boolean): void {
        const pan = this.panning;
        if (!pan || event.pointerId !== pan.id) return;
        // A pan that MOVED must not also navigate: the pointer came up over an
        // overlay, and that overlay links to a preview page. Under the threshold it
        // was a click that wobbled, and swallowing that would break clicking a
        // component while zoomed in.
        if (clicks && pan.moved > DRAG_SLOP) this.swallowClick = true;
        if (pan.held && this.stage?.hasPointerCapture?.(event.pointerId)) {
            this.stage.releasePointerCapture(event.pointerId);
        }
        this.panning = null;
        this.stage?.classList.remove("cp-page-panning");
    }

    /**
     * Escape unwinds one thing at a time, innermost first: the selection, then the
     * zoom. Doing both at once would throw away a reading position the reader spent
     * three double-clicks arriving at, in answer to a key they pressed to dismiss a
     * tooltip. The selection is `design-page.js`'s to clear, so this defers to it by
     * looking for its mark.
     */
    private escape(event: KeyboardEvent): void {
        if (event.key !== "Escape" || !zoomed(this.view)) return;
        if (this.stage?.querySelector(".cp-page-selected")) return;
        this.reset();
    }

    /**
     * `+` zooms in, `-` out, `0` back to 1:1 — the same three steps the corner buttons
     * take, about the middle of the view.
     *
     * Ignored while a control has focus, so the keys still belong to a checkbox, a radio
     * or anything with text in it rather than to the sheet.
     */
    private shortcut(event: KeyboardEvent): void {
        if (event.metaKey || event.ctrlKey || event.altKey) return;
        const target = event.target as HTMLElement | null;
        if (target?.closest("input, textarea, select, [contenteditable]"))
            return;
        if (event.key === "+" || event.key === "=") this.step(STEP, false);
        else if (event.key === "-" || event.key === "_")
            this.step(1 / STEP, false);
        else if (event.key === "0") this.reset(false);
        else return;
        event.preventDefault();
        this.reparkTip();
    }

    /**
     * Tell the page to describe the focused node again, now that the sheet has moved
     * under it.
     *
     * `design-page.js` parks its tooltip at the node's measured box when focus lands
     * there, and a keyboard zoom moves that box without producing a new focus event —
     * so the tip would be left behind, stranded over whatever the pan brought under
     * it. Re-dispatching `focus` at the element it is already on makes that page
     * re-measure and re-park, which is why the two keyboard steps above are applied
     * UN-eased: the box has to be final before anything measures it.
     *
     * A one-way nudge through the DOM, like reading `.cp-page-selected` — the legacy
     * file still knows nothing about this element.
     */
    private reparkTip(): void {
        const focused = document.activeElement;
        if (!focused || !this.canvas?.contains(focused)) return;
        focused.dispatchEvent(new FocusEvent("focus"));
    }

    /** Pan a focused node into view. Does nothing at 1:1, or off the sheet. */
    private reveal(target: Element | null): void {
        if (!target || !zoomed(this.view)) return;
        this.settle();
        // Only the sheet's own overlays: a row in the audit list below is not
        // something the stage can bring into view, and asking it to would pan the
        // sheet to nowhere in answer to a focus that never left the list.
        if (!this.canvas?.contains(target)) return;
        const delta = revealDelta(
            target.getBoundingClientRect(),
            this.stageBox(),
        );
        if (!delta) return;
        // NOT eased, unlike every other discrete move. `design-page.js` handles the
        // same focus event and parks its tooltip at the node's measured box; with a
        // 170 ms transition in flight that box is wherever the animation currently is,
        // so the tip lands short and the sheet then slides out from under it. Jumping
        // puts the node where the tip is about to be told it is.
        this.apply(
            {
                ...this.view,
                x: this.view.x + delta.x,
                y: this.view.y + delta.y,
            },
            false,
        );
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-page-zoom": PageZoom;
    }
}
