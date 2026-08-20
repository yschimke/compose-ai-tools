// What the design actually painted for a node, clips included — as a table over a fake document.
//
// The tree is described here rather than mounted, because the answer depends on three DOM reads no
// headless DOM implements (`getBBox`, `getScreenCTM`, `getBoundingClientRect` on SVG geometry). The
// module takes those as an injected `Geometry` for exactly this reason, so the walk — union the
// children, intersect the clips, degrade where a clip can't be resolved — is pinned here and only
// the reads themselves need a browser.

import assert from "node:assert/strict";
import {
    clipRegion,
    clippedInside,
    intersect,
    mapBox,
    multiply,
    paintedRect,
    union,
    type Geometry,
    type Matrix,
} from "../src/design/clip.js";

interface Node {
    tag: string;
    /** Screen rect, for a leaf the walk measures directly. */
    rect?: { left: number; top: number; width: number; height: number };
    /** User-space box, for a shape inside a `<clipPath>`. */
    bbox?: { x: number; y: number; width: number; height: number };
    /** The element's own `transform`. */
    local?: Matrix;
    /** User space → screen, for an element that references a clip. */
    ctm?: Matrix;
    attrs?: Record<string, string>;
    children?: Node[];
}

const IDENTITY: Matrix = { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 };

/** Indexes ids, then answers the module's reads off the literal above. */
function fake(root: Node): { geo: Geometry; byId: Map<string, Node> } {
    const byId = new Map<string, Node>();
    const walk = (node: Node) => {
        const id = node.attrs?.id;
        if (id) byId.set(id, node);
        for (const child of node.children ?? []) walk(child);
    };
    walk(root);
    const geo: Geometry = {
        rect: (element) => (element as unknown as Node).rect ?? null,
        bbox: (element) => (element as unknown as Node).bbox ?? null,
        screenMatrix: (element) => (element as unknown as Node).ctm ?? null,
        localMatrix: (element) => (element as unknown as Node).local ?? null,
        clipPath: (element) => {
            const value = (element as unknown as Node).attrs?.["clip-path"];
            const match = value ? /^url\(#([^)]+)\)$/.exec(value) : null;
            const clip = match ? byId.get(match[1]) : undefined;
            return (clip as unknown as Element) ?? null;
        },
        children: (element) =>
            ((element as unknown as Node).children ??
                []) as unknown as Element[],
        tag: (element) => (element as unknown as Node).tag,
        attribute: (element, name) =>
            (element as unknown as Node).attrs?.[name] ?? null,
    };
    return { geo, byId };
}

const box = (left: number, top: number, width: number, height: number) => ({
    left,
    top,
    width,
    height,
});

/**
 * The shape of the bug this module exists for: a placeholder node is a small container holding a
 * large rotated shimmer sweep, kept inside the component by a clip on the group that holds it.
 * `getBoundingClientRect()` reports the sweep, so the node measured ~7x too big and the render fitted
 * into that slot painted a grey blob across the page (issue #4323).
 */
function placeholderTree(): { geo: Geometry; node: Node } {
    const clip: Node = {
        tag: "clipPath",
        attrs: { id: "clip74" },
        children: [
            { tag: "path", bbox: { x: 100, y: 100, width: 52, height: 52 } },
        ],
    };
    const node: Node = {
        tag: "g",
        attrs: { "data-node-id": "71571:44842" },
        children: [
            {
                tag: "g",
                attrs: { "clip-path": "url(#clip74)" },
                ctm: IDENTITY,
                children: [
                    // The button itself.
                    { tag: "path", rect: box(100, 100, 52, 52) },
                    // The sweep: four times the size, and only the clip keeps it in.
                    { tag: "path", rect: box(-60, -60, 402, 402) },
                ],
            },
        ],
    };
    const root: Node = { tag: "svg", children: [node, clip] };
    return { geo: fake(root).geo, node };
}

describe("intersect / union", () => {
    it("overlaps two boxes", () => {
        assert.deepEqual(
            intersect(box(0, 0, 100, 100), box(50, 50, 100, 100)),
            box(50, 50, 50, 50),
        );
    });

    it("is null for boxes that do not meet", () => {
        assert.equal(intersect(box(0, 0, 10, 10), box(20, 20, 10, 10)), null);
    });

    it("unions, treating null as nothing measured yet", () => {
        assert.deepEqual(union(null, box(0, 0, 10, 10)), box(0, 0, 10, 10));
        assert.deepEqual(
            union(box(0, 0, 10, 10), box(20, 20, 10, 10)),
            box(0, 0, 30, 30),
        );
    });
});

describe("mapBox", () => {
    it("maps a box through a translation and a scale", () => {
        assert.deepEqual(
            mapBox(
                { x: 10, y: 20, width: 30, height: 40 },
                { a: 2, b: 0, c: 0, d: 2, e: 5, f: 5 },
            ),
            box(25, 45, 60, 80),
        );
    });

    it("boxes a ROTATED rectangle rather than reporting it as a sliver", () => {
        // A quarter turn: the two opposite corners alone would give a zero-area box on the diagonal,
        // and a shimmer sweep is rotated in exactly this way.
        const turned = mapBox(
            { x: 0, y: 0, width: 10, height: 20 },
            { a: 0, b: 1, c: -1, d: 0, e: 0, f: 0 },
        );
        assert.deepEqual(turned, box(-20, 0, 20, 10));
    });

    it("composes the referencing element's matrix with the shape's own", () => {
        const composed = multiply(
            { a: 2, b: 0, c: 0, d: 2, e: 0, f: 0 },
            { a: 1, b: 0, c: 0, d: 1, e: 3, f: 4 },
        );
        assert.deepEqual(
            mapBox({ x: 0, y: 0, width: 1, height: 1 }, composed),
            box(6, 8, 2, 2),
        );
    });
});

describe("clipRegion", () => {
    it("boxes the shapes a clip is built from, in screen coordinates", () => {
        const { geo, byId } = fake({
            tag: "svg",
            children: [
                {
                    tag: "g",
                    attrs: { id: "user", "clip-path": "url(#clip)" },
                    ctm: { a: 2, b: 0, c: 0, d: 2, e: 10, f: 10 },
                },
                {
                    tag: "clipPath",
                    attrs: { id: "clip" },
                    children: [
                        {
                            tag: "rect",
                            bbox: { x: 0, y: 0, width: 20, height: 20 },
                            local: { a: 1, b: 0, c: 0, d: 1, e: 5, f: 5 },
                        },
                    ],
                },
            ],
        });
        assert.deepEqual(
            clipRegion(byId.get("user") as unknown as Element, geo),
            box(20, 20, 40, 40),
        );
    });

    it("composes the <clipPath>'s OWN transform, which the referencing element's CTM lacks", () => {
        const { geo, byId } = fake({
            tag: "svg",
            children: [
                {
                    tag: "g",
                    attrs: { id: "user", "clip-path": "url(#clip)" },
                    ctm: IDENTITY,
                },
                {
                    tag: "clipPath",
                    attrs: { id: "clip" },
                    local: { a: 1, b: 0, c: 0, d: 1, e: 100, f: 50 },
                    children: [
                        {
                            tag: "rect",
                            bbox: { x: 0, y: 0, width: 20, height: 20 },
                        },
                    ],
                },
            ],
        });
        assert.deepEqual(
            clipRegion(byId.get("user") as unknown as Element, geo),
            box(100, 50, 20, 20),
        );
    });

    it("declines a clip in object-bounding-box units rather than guessing", () => {
        const { geo, byId } = fake({
            tag: "svg",
            children: [
                {
                    tag: "g",
                    attrs: { id: "user", "clip-path": "url(#clip)" },
                    ctm: IDENTITY,
                },
                {
                    tag: "clipPath",
                    attrs: { id: "clip", clipPathUnits: "objectBoundingBox" },
                    children: [
                        {
                            tag: "rect",
                            bbox: { x: 0, y: 0, width: 1, height: 1 },
                        },
                    ],
                },
            ],
        });
        assert.equal(
            clipRegion(byId.get("user") as unknown as Element, geo),
            null,
        );
    });

    it("is null for an element with no clip, and for one whose clip does not resolve", () => {
        const { geo, byId } = fake({
            tag: "svg",
            children: [
                { tag: "g", attrs: { id: "bare" }, ctm: IDENTITY },
                {
                    tag: "g",
                    attrs: { id: "dangling", "clip-path": "url(#gone)" },
                    ctm: IDENTITY,
                },
            ],
        });
        assert.equal(
            clipRegion(byId.get("bare") as unknown as Element, geo),
            null,
        );
        assert.equal(
            clipRegion(byId.get("dangling") as unknown as Element, geo),
            null,
        );
    });
});

describe("paintedRect", () => {
    it("measures a placeholder as the component, not as the shimmer it clips away", () => {
        const { geo, node } = placeholderTree();
        assert.deepEqual(
            paintedRect(node as unknown as Element, geo),
            box(100, 100, 52, 52),
        );
    });

    it("reads the element's own rect when nothing in the subtree is clipped", () => {
        // The fast path, and the one nearly every node takes: one layout read instead of one per
        // leaf. The children here are deliberately WIDER than the rect, so a walk would show.
        const { geo, byId } = fake({
            tag: "svg",
            children: [
                {
                    tag: "g",
                    attrs: { id: "node" },
                    rect: box(0, 0, 50, 50),
                    children: [{ tag: "path", rect: box(0, 0, 500, 500) }],
                },
            ],
        });
        assert.deepEqual(
            paintedRect(byId.get("node") as unknown as Element, geo),
            box(0, 0, 50, 50),
        );
    });

    it("leaves an ANCESTOR's clip alone: the box is what the node paints, not what shows", () => {
        // A card that crops a component is not a fact about the component's size, and this box is
        // what the render is fitted to — `fitInk` scales, so cropping here would squeeze the whole
        // render into the visible sliver and publish a shrunken component. Cropping the render to
        // its container is a different feature.
        const { geo, byId } = fake({
            tag: "svg",
            children: [
                {
                    tag: "g",
                    attrs: { "clip-path": "url(#card)" },
                    ctm: IDENTITY,
                    children: [
                        {
                            tag: "g",
                            attrs: { id: "node" },
                            rect: box(80, 0, 100, 40),
                        },
                    ],
                },
                {
                    tag: "clipPath",
                    attrs: { id: "card" },
                    children: [
                        {
                            tag: "rect",
                            bbox: { x: 0, y: 0, width: 120, height: 200 },
                        },
                    ],
                },
            ],
        });
        assert.deepEqual(
            paintedRect(byId.get("node") as unknown as Element, geo),
            box(80, 0, 100, 40),
        );
    });

    it("bounds a walk through a nested <svg> by that svg's viewport", () => {
        // Reading an element's own rect used to bound the answer for free, because a non-root
        // `<svg>` clips to its viewport. Walking its children does not, so a clip inside a nested
        // svg would otherwise trade one over-measure for another.
        const { geo, byId } = fake({
            tag: "svg",
            children: [
                {
                    tag: "g",
                    attrs: { id: "node" },
                    children: [
                        {
                            tag: "svg",
                            rect: box(0, 0, 60, 60),
                            children: [
                                {
                                    tag: "g",
                                    attrs: { "clip-path": "url(#inner)" },
                                    ctm: IDENTITY,
                                    children: [
                                        {
                                            tag: "path",
                                            rect: box(0, 0, 400, 400),
                                        },
                                    ],
                                },
                            ],
                        },
                    ],
                },
                {
                    tag: "clipPath",
                    attrs: { id: "inner" },
                    children: [
                        {
                            tag: "rect",
                            bbox: { x: 0, y: 0, width: 200, height: 200 },
                        },
                    ],
                },
            ],
        });
        assert.deepEqual(
            paintedRect(byId.get("node") as unknown as Element, geo),
            box(0, 0, 60, 60),
        );
    });

    it("is null when the clips leave nothing painted", () => {
        const { geo, byId } = fake({
            tag: "svg",
            children: [
                {
                    tag: "g",
                    attrs: { id: "node", "clip-path": "url(#elsewhere)" },
                    ctm: IDENTITY,
                    rect: box(0, 0, 40, 40),
                },
                {
                    tag: "clipPath",
                    attrs: { id: "elsewhere" },
                    children: [
                        {
                            tag: "rect",
                            bbox: { x: 500, y: 500, width: 10, height: 10 },
                        },
                    ],
                },
            ],
        });
        assert.equal(
            paintedRect(byId.get("node") as unknown as Element, geo),
            null,
        );
    });

    it("keeps the raw rect when the clip cannot be understood", () => {
        // Degrading to the measurement this replaced is the whole safety story: a clip in units this
        // doesn't resolve must not shrink — or grow — a node.
        const { geo, byId } = fake({
            tag: "svg",
            children: [
                {
                    tag: "g",
                    attrs: { id: "node", "clip-path": "url(#clip)" },
                    ctm: null as unknown as Matrix,
                    rect: box(10, 10, 30, 30),
                },
                {
                    tag: "clipPath",
                    attrs: { id: "clip" },
                    children: [
                        {
                            tag: "rect",
                            bbox: { x: 0, y: 0, width: 5, height: 5 },
                        },
                    ],
                },
            ],
        });
        assert.deepEqual(
            paintedRect(byId.get("node") as unknown as Element, geo),
            box(10, 10, 30, 30),
        );
    });

    it("skips the parts of a subtree that define paint rather than draw it", () => {
        const { geo, byId } = fake({
            tag: "svg",
            children: [
                {
                    tag: "g",
                    attrs: { id: "node" },
                    children: [
                        {
                            tag: "g",
                            attrs: { "clip-path": "url(#clip)" },
                            ctm: IDENTITY,
                            children: [
                                { tag: "path", rect: box(0, 0, 20, 20) },
                            ],
                        },
                        {
                            tag: "defs",
                            children: [
                                { tag: "path", rect: box(0, 0, 900, 900) },
                            ],
                        },
                    ],
                },
                {
                    tag: "clipPath",
                    attrs: { id: "clip" },
                    children: [
                        {
                            tag: "rect",
                            bbox: { x: 0, y: 0, width: 40, height: 40 },
                        },
                    ],
                },
            ],
        });
        assert.deepEqual(
            paintedRect(byId.get("node") as unknown as Element, geo),
            box(0, 0, 20, 20),
        );
    });
});

describe("clippedInside", () => {
    it("is true only when the subtree carries a clip", () => {
        const { geo, node } = placeholderTree();
        assert.equal(clippedInside(node as unknown as Element, geo), true);
        const plain = fake({
            tag: "svg",
            children: [
                {
                    tag: "g",
                    attrs: { id: "node" },
                    children: [{ tag: "path", rect: box(0, 0, 10, 10) }],
                },
            ],
        });
        assert.equal(
            clippedInside(
                plain.byId.get("node") as unknown as Element,
                plain.geo,
            ),
            false,
        );
    });
});
