// What the design ACTUALLY painted for a node — the measured box, cut down by the clips that crop it.
//
// `getBoundingClientRect()` on an SVG element answers with the union of its descendants' geometry and
// ignores `clip-path` entirely, which is the browser behaving to spec and not what "the box the
// design drew" means. Figma exports lean on that difference: a shimmering placeholder is a small
// container with a huge rotated gradient sweep inside it, and the sweep is kept inside the component
// only by a `clip-path` on the group that holds it. Measured raw, that node's box came out 402×402
// user units for a 52×52 button — so the slot the render was fitted into was ~7.7x too big, and our
// circle landed on the page as a grey blob the size of a whole section (issue #4323).
//
// So the box is walked rather than read: children unioned, each element's own clip intersected in,
// and every ancestor's clip intersected in on the way up. Everything is in SCREEN coordinates, the
// same space `getBoundingClientRect()` answers in, so the result drops straight into the placement
// that already existed.
//
// DEGRADES TO THE RAW RECT. Every step that cannot be computed — a clip this doesn't understand
// (`objectBoundingBox` units, a clip on the clip), a browser with no `getScreenCTM`, a shape with no
// `getBBox` — contributes nothing rather than guessing, so the worst case is exactly the measurement
// this replaced.

import type { Box } from "./geometry.js";

/** A user-space rectangle, as `getBBox()` answers. */
export interface UserBox {
    x: number;
    y: number;
    width: number;
    height: number;
}

/** The affine part of an `SVGMatrix` / `DOMMatrix`. */
export interface Matrix {
    a: number;
    b: number;
    c: number;
    d: number;
    e: number;
    f: number;
}

/** The DOM reads this module needs, injected so the walk can be tested without a rendering engine. */
export interface Geometry {
    /** `getBoundingClientRect()`, or null when the element cannot be measured. */
    rect(element: Element): Box | null;
    /** `getBBox()` in the element's own user space, or null. */
    bbox(element: Element): UserBox | null;
    /** `getScreenCTM()` — the element's user space to screen coordinates. */
    screenMatrix(element: Element): Matrix | null;
    /** The element's own `transform`, as a matrix in its parent's user space. */
    localMatrix(element: Element): Matrix | null;
    /** The `<clipPath>` an element's `clip-path` names, or null when it has none / it doesn't resolve. */
    clipPath(element: Element): Element | null;
    /** Child elements, in document order. */
    children(element: Element): Element[];
    /** Parent element, or null at the root of the walk. */
    parent(element: Element): Element | null;
    /** The element's tag, lowercased. */
    tag(element: Element): string;
    /** An attribute value, or null. */
    attribute(element: Element, name: string): string | null;
}

/**
 * A guard against a pathological export rather than a real limit: the deepest node group in a Figma
 * page export is a handful of levels.
 */
const MAX_DEPTH = 24;

/** Tags whose box is the union of their children's rather than geometry of their own. */
const CONTAINERS = new Set(["g", "a", "svg", "switch"]);

/** Tags that define paint or geometry for others and are never drawn where they sit. */
const NON_PAINTING = new Set([
    "defs",
    "clippath",
    "mask",
    "marker",
    "symbol",
    "pattern",
    "lineargradient",
    "radialgradient",
    "filter",
    "style",
    "title",
    "desc",
    "metadata",
    "script",
]);

/** The overlap of two boxes, or null when they do not overlap. */
export function intersect(a: Box, b: Box): Box | null {
    const left = Math.max(a.left, b.left);
    const top = Math.max(a.top, b.top);
    const right = Math.min(a.left + a.width, b.left + b.width);
    const bottom = Math.min(a.top + a.height, b.top + b.height);
    if (!(right > left && bottom > top)) return null;
    return { left, top, width: right - left, height: bottom - top };
}

/** The smallest box holding both, with null standing for "nothing measured yet". */
export function union(a: Box | null, b: Box | null): Box | null {
    if (!a) return b;
    if (!b) return a;
    const left = Math.min(a.left, b.left);
    const top = Math.min(a.top, b.top);
    const right = Math.max(a.left + a.width, b.left + b.width);
    const bottom = Math.max(a.top + a.height, b.top + b.height);
    return { left, top, width: right - left, height: bottom - top };
}

/** `a` then `b` — the matrix for a point mapped by `b` and then by `a`. */
export function multiply(a: Matrix, b: Matrix): Matrix {
    return {
        a: a.a * b.a + a.c * b.b,
        b: a.b * b.a + a.d * b.b,
        c: a.a * b.c + a.c * b.d,
        d: a.b * b.c + a.d * b.d,
        e: a.a * b.e + a.c * b.f + a.e,
        f: a.b * b.e + a.d * b.f + a.f,
    };
}

/**
 * A user-space box through a matrix, as the axis-aligned box holding its four mapped corners.
 *
 * All four, not the two opposite ones: a rotated sweep is exactly the shape this module exists for,
 * and mapping only `(x, y)` and `(x+w, y+h)` reports a rotated rectangle as a sliver.
 */
export function mapBox(box: UserBox, m: Matrix): Box {
    const xs: number[] = [];
    const ys: number[] = [];
    for (const [x, y] of [
        [box.x, box.y],
        [box.x + box.width, box.y],
        [box.x, box.y + box.height],
        [box.x + box.width, box.y + box.height],
    ]) {
        xs.push(m.a * x + m.c * y + m.e);
        ys.push(m.b * x + m.d * y + m.f);
    }
    const left = Math.min(...xs);
    const top = Math.min(...ys);
    return {
        left,
        top,
        width: Math.max(...xs) - left,
        height: Math.max(...ys) - top,
    };
}

/**
 * The screen box a `clip-path` on `element` crops to, or null when it cannot be worked out.
 *
 * `userSpaceOnUse` only — the default, and what Figma writes. `objectBoundingBox` units would need
 * the referencing element's own bbox to resolve, and answering null there leaves the node measured
 * exactly as it was before this module existed.
 *
 * The clip's own shapes are BOXED, not traced: a clip is a region, and the box that holds it is the
 * most a rectangle can say about it. That is the right answer for the rectangles and rounded shapes a
 * component export clips with, and an over-estimate — never an under-estimate — for anything else.
 */
export function clipRegion(element: Element, geo: Geometry): Box | null {
    const clip = geo.clipPath(element);
    if (!clip) return null;
    const units = geo.attribute(clip, "clipPathUnits");
    if (units && units !== "userSpaceOnUse") return null;
    const toScreen = geo.screenMatrix(element);
    if (!toScreen) return null;
    let region: Box | null = null;
    for (const child of geo.children(clip)) {
        if (NON_PAINTING.has(geo.tag(child))) continue;
        const box = geo.bbox(child);
        if (!box) continue;
        const local = geo.localMatrix(child);
        region = union(
            region,
            mapBox(box, local ? multiply(toScreen, local) : toScreen),
        );
    }
    return region;
}

/**
 * Whether anything in this subtree is clipped at all.
 *
 * The walk below only pays for itself where a clip exists: with none, an element's own rect is
 * already the union of its children's, and reading it is ONE layout read against one per leaf. A
 * specimen sheet has hundreds of nodes and re-measures on every resize, so the fast path is the path
 * nearly every node takes.
 */
export function clippedInside(
    element: Element,
    geo: Geometry,
    depth = MAX_DEPTH,
): boolean {
    if (geo.attribute(element, "clip-path")) return true;
    if (depth <= 0) return false;
    for (const child of geo.children(element))
        if (clippedInside(child, geo, depth - 1)) return true;
    return false;
}

/** The painted box of a subtree, in screen coordinates, with each element's own clip applied. */
function paintedBox(
    element: Element,
    geo: Geometry,
    depth: number,
): Box | null {
    if (NON_PAINTING.has(geo.tag(element))) return null;
    let box: Box | null = null;
    const children = depth > 0 ? geo.children(element) : [];
    if (
        CONTAINERS.has(geo.tag(element)) &&
        children.length > 0 &&
        clippedInside(element, geo, depth)
    ) {
        for (const child of children)
            box = union(box, paintedBox(child, geo, depth - 1));
    } else {
        box = geo.rect(element);
    }
    if (!box) return null;
    const clip = clipRegion(element, geo);
    return clip ? intersect(box, clip) : box;
}

/**
 * The box the design actually paints for `element`, in screen coordinates.
 *
 * Null when nothing is painted — every child clipped away, or an element with no measurable
 * geometry. Callers treat that the same way they treat a zero-area box: the node is missing as far
 * as the sheet is concerned.
 */
export function paintedRect(element: Element, geo: Geometry): Box | null {
    let box = paintedBox(element, geo, MAX_DEPTH);
    if (!box) return null;
    // The ancestors' clips crop it too — a node half-outside the card it sits in is painted as the
    // half that shows, and its slot should be that half.
    for (
        let parent = geo.parent(element), guard = 0;
        parent && guard < MAX_DEPTH;
        parent = geo.parent(parent), guard++
    ) {
        const clip = clipRegion(parent, geo);
        if (!clip) continue;
        box = intersect(box, clip);
        if (!box) return null;
    }
    return box;
}

/** Reads straight off the document. Every read is guarded: an engine missing one degrades, not throws. */
export const domGeometry: Geometry = {
    rect(element) {
        const rect = element.getBoundingClientRect?.();
        if (!rect) return null;
        return {
            left: rect.left,
            top: rect.top,
            width: rect.width,
            height: rect.height,
        };
    },
    bbox(element) {
        const shape = element as SVGGraphicsElement;
        if (typeof shape.getBBox !== "function") return null;
        try {
            const box = shape.getBBox();
            return {
                x: box.x,
                y: box.y,
                width: box.width,
                height: box.height,
            };
        } catch {
            return null;
        }
    },
    screenMatrix(element) {
        const shape = element as SVGGraphicsElement;
        if (typeof shape.getScreenCTM !== "function") return null;
        try {
            return shape.getScreenCTM();
        } catch {
            return null;
        }
    },
    localMatrix(element) {
        const list = (element as SVGGraphicsElement).transform?.baseVal;
        if (!list || typeof list.consolidate !== "function") return null;
        try {
            return list.consolidate()?.matrix ?? null;
        } catch {
            return null;
        }
    },
    clipPath(element) {
        // The attribute, not the computed style: this runs per node on every measure, and
        // `getComputedStyle` is a style recalc each time. Figma writes the attribute.
        const value = element.getAttribute?.("clip-path");
        const id = value
            ? /^url\(["']?#([^"')]+)["']?\)$/.exec(value.trim())
            : null;
        if (!id) return null;
        // `getElementById` and not a selector: a clip id comes out of a design file, and
        // interpolating it into a selector is the shape of an injection.
        return element.ownerDocument?.getElementById(id[1]) ?? null;
    },
    children(element) {
        return Array.from(element.children ?? []);
    },
    parent(element) {
        const parent = element.parentElement;
        // Stop at the `<svg>`: above it the clips are HTML's, and the stage's own overflow is
        // already what bounds the drawing.
        if (!parent) return null;
        return parent.namespaceURI === "http://www.w3.org/2000/svg"
            ? parent
            : null;
    },
    tag(element) {
        return element.tagName.toLowerCase();
    },
    attribute(element, name) {
        return element.getAttribute?.(name) ?? null;
    },
};
