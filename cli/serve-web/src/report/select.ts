// Choosing which part of the page the capture is about.
//
// Runs AFTER the frame has been grabbed, and that order is the whole design. The alternative —
// select, then capture — puts this module's own overlay, marquee and hint bar on screen at the
// moment the browser takes its picture, so every capture would carry the tool that took it. Here
// the frame is already in memory and this is only ever choosing a rectangle in it, so the overlay
// is free to be as visible as it likes.
//
// Two pickers, because two different things get reported. A dragged REGION is right for "this
// corner of the comparison is wrong" — a wide wall where a full-viewport shot buries the defect. An
// ELEMENT is right for the far more common case on these pages, where the thing being reported has
// an exact boundary somebody would otherwise have to trace by hand: a render, a spec panel, a
// diagnostics table, one cell of one.

import { Rect, clampRect, isUsable, rectFromPoints } from "./geometry.js";

/** What a picker returns, or null when the visitor pressed Escape. */
export interface Picked {
    rect: Rect;
    /** The element under the pointer, for the element picker only. */
    element?: Element;
}

/** Viewport bounds, as the clamp needs them. */
function viewport() {
    return {
        width: window.innerWidth || document.documentElement.clientWidth || 0,
        height:
            window.innerHeight || document.documentElement.clientHeight || 0,
    };
}

/**
 * The overlay, its hint bar, and the box that marks the current selection.
 *
 * Built here rather than server-rendered because it exists for at most a few seconds inside one
 * gesture, on a page that may never see it. `serve.css` owns how it looks.
 */
function overlay(hint: string): {
    root: HTMLElement;
    box: HTMLElement;
    done: () => void;
} {
    const root = document.createElement("div");
    root.className = "cp-shot-overlay";
    const box = document.createElement("div");
    box.className = "cp-shot-marquee";
    box.hidden = true;
    const bar = document.createElement("p");
    bar.className = "cp-shot-hint";
    bar.textContent = hint;
    root.append(box, bar);
    document.body.appendChild(root);
    return { root, box, done: () => root.remove() };
}

function place(box: HTMLElement, rect: Rect): void {
    box.hidden = false;
    box.style.left = `${rect.x}px`;
    box.style.top = `${rect.y}px`;
    box.style.width = `${rect.width}px`;
    box.style.height = `${rect.height}px`;
}

/**
 * Drag a rectangle.
 *
 * The overlay takes the pointer itself here — it is a drawing surface, not a lens — so nothing
 * underneath can start a drag of its own, and a drag that wanders over a link does not end in a
 * navigation.
 */
export function pickRegion(): Promise<Picked | null> {
    return new Promise((resolve) => {
        const { root, box, done } = overlay(
            "Drag a box around the part that is wrong · Esc to cancel",
        );
        root.classList.add("cp-shot-overlay--drag");
        let start: { x: number; y: number } | null = null;
        const finish = (picked: Picked | null) => {
            done();
            window.removeEventListener("keydown", onKey, true);
            resolve(picked);
        };
        const onKey = (event: KeyboardEvent) => {
            if (event.key !== "Escape") return;
            event.preventDefault();
            finish(null);
        };
        root.addEventListener("pointerdown", (event: PointerEvent) => {
            start = { x: event.clientX, y: event.clientY };
            root.setPointerCapture(event.pointerId);
            place(box, rectFromPoints(start.x, start.y, start.x, start.y));
        });
        root.addEventListener("pointermove", (event: PointerEvent) => {
            if (!start) return;
            place(
                box,
                rectFromPoints(start.x, start.y, event.clientX, event.clientY),
            );
        });
        root.addEventListener("pointerup", (event: PointerEvent) => {
            if (!start) return;
            const rect = clampRect(
                rectFromPoints(start.x, start.y, event.clientX, event.clientY),
                viewport(),
            );
            // A click with no drag is not a selection — see `isUsable`. Treated as a cancel rather
            // than as an error, because that is what a stray click on a full-screen overlay IS.
            finish(isUsable(rect) ? { rect } : null);
        });
        window.addEventListener("keydown", onKey, true);
    });
}

/**
 * Point at an element.
 *
 * The overlay is a LENS here — `pointer-events: none` in the stylesheet — so `elementFromPoint`
 * reaches the page under it and the highlight can follow the real node. That makes the page's own
 * controls live under the pointer, which is why every one of the three pointer events is
 * intercepted in the CAPTURE phase and cancelled: without that, picking the theme chip would change
 * the theme, and picking a preview card would navigate away from the page being reported.
 */
export function pickElement(): Promise<Picked | null> {
    return new Promise((resolve) => {
        const { root, box, done } = overlay(
            "Click the element to capture — a render, a table, a single cell · Esc to cancel",
        );
        let current: Element | null = null;
        const finish = (picked: Picked | null) => {
            done();
            window.removeEventListener("keydown", onKey, true);
            window.removeEventListener("pointermove", onMove, true);
            window.removeEventListener("pointerdown", swallow, true);
            window.removeEventListener("pointerup", swallow, true);
            window.removeEventListener("click", onClick, true);
            resolve(picked);
        };
        const onKey = (event: KeyboardEvent) => {
            if (event.key !== "Escape") return;
            event.preventDefault();
            finish(null);
        };
        const onMove = (event: PointerEvent) => {
            const el = target(event.clientX, event.clientY);
            current = el;
            if (!el) {
                box.hidden = true;
                return;
            }
            place(box, clampRect(boundsOf(el), viewport()));
        };
        const swallow = (event: Event) => {
            event.preventDefault();
            event.stopPropagation();
        };
        const onClick = (event: MouseEvent) => {
            event.preventDefault();
            event.stopPropagation();
            const el = target(event.clientX, event.clientY) ?? current ?? null;
            if (!el) return finish(null);
            const rect = clampRect(boundsOf(el), viewport());
            finish(isUsable(rect) ? { rect, element: el } : null);
        };
        window.addEventListener("keydown", onKey, true);
        window.addEventListener("pointermove", onMove, true);
        window.addEventListener("pointerdown", swallow, true);
        window.addEventListener("pointerup", swallow, true);
        window.addEventListener("click", onClick, true);
    });
}

/** The element at a point, ignoring the reporting UI itself. */
function target(x: number, y: number): Element | null {
    const el = document.elementFromPoint(x, y);
    if (!el) return null;
    // The launcher is closed before a capture is taken, but it is still IN the document, and its
    // fixed button sits over the bottom-right corner of every page. Picking it would capture a
    // rectangle of the page that the tool itself covers.
    if (el.closest(".cp-shot-overlay, .cp-fab")) return null;
    return el;
}

function boundsOf(el: Element): Rect {
    const r = el.getBoundingClientRect();
    return { x: r.left, y: r.top, width: r.width, height: r.height };
}
