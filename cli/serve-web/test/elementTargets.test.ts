// What can be selected, and in which plane a drag ends up recorded.

import assert from "node:assert/strict";
import { tagTargets, toRenderPixels } from "../src/report/elementTargets.js";

const entry = (
    count: number,
    bounds?: { x: number; y: number; width: number; height: number } | null,
    space = "render-pixels",
) => ({ count, bounds: bounds ?? null, space });

describe("tagTargets", () => {
    it("reads the published index into offerable targets", () => {
        const targets = tagTargets({
            previewId: "Button",
            tags: {
                glyph: entry(1, { x: 18, y: 18, width: 24, height: 24 }),
                label: entry(1, { x: 0, y: 40, width: 90, height: 20 }),
            },
        });
        assert.deepEqual(
            targets.map((t) => [t.tag, t.count, t.ambiguous]),
            [
                ["glyph", 1, false],
                ["label", 1, false],
            ],
        );
        assert.deepEqual(targets[0].bounds, {
            x: 18,
            y: 18,
            width: 24,
            height: 24,
        });
    });

    it("marks a tag more than one node carries as ambiguous", () => {
        // `count` is the uniqueness check and it counts EVERY node carrying the tag, including ones
        // whose bounds are unusable — so a zero-area duplicate cannot hide behind a usable sibling
        // and report a genuinely ambiguous tag as unique.
        const [row] = tagTargets({
            tags: { row: entry(2, { x: 0, y: 0, width: 10, height: 10 }) },
        });
        assert.equal(row.ambiguous, true);
        assert.equal(row.count, 2);
    });

    it("offers a tag whose every node had a zero-area box, without bounds", () => {
        // A tag with no geometry is still an identity: it is `count` that makes it one. The batch
        // brief calls this out explicitly — do not assume `bounds` is present.
        const [only] = tagTargets({ tags: { ghost: entry(1, null) } });
        assert.equal(only.tag, "ghost");
        assert.equal(only.ambiguous, false);
        assert.equal(only.bounds, undefined);
    });

    it("keeps the tag verbatim, whitespace and case included", () => {
        // Two tags that differ only by edge whitespace are two identities. Collapsing them produces
        // false ambiguity one way and false disappearance the other.
        const tags = tagTargets({
            tags: { " item ": entry(1), item: entry(1) },
        }).map((t) => t.tag);
        assert.deepEqual(tags, [" item ", "item"]);
    });

    it("drops an entry that declares no space, or one this version does not know", () => {
        // Never defaulted. Reading an undeclared index as render-pixel is exactly what the
        // discriminator was added to prevent, and a future canonical-plane producer must not be
        // mistaken for this one by an older page.
        assert.deepEqual(
            tagTargets({
                tags: {
                    undeclared: { count: 1, bounds: null },
                    canonical: entry(1, null, "canonical-plane"),
                    fine: entry(1, null),
                },
            }).map((t) => t.tag),
            ["fine"],
        );
    });

    it("drops an entry counting fewer than one node", () => {
        assert.deepEqual(tagTargets({ tags: { nobody: entry(0) } }), []);
    });

    it("answers nothing for a payload it cannot read", () => {
        assert.deepEqual(tagTargets(null), []);
        assert.deepEqual(tagTargets({}), []);
        assert.deepEqual(tagTargets({ tags: "nonsense" }), []);
    });
});

describe("toRenderPixels", () => {
    const frame = (naturalWidth: number, clientWidth: number) => ({
        naturalWidth,
        clientWidth,
    });

    it("leaves a rectangle alone when the frame is shown at its natural size", () => {
        assert.deepEqual(
            toRenderPixels(
                { x: 10, y: 20, width: 30, height: 40 },
                frame(400, 400),
            ),
            { x: 10, y: 20, width: 30, height: 40 },
        );
    });

    it("scales a rectangle dragged on a frame shown at half size", () => {
        // The whole reason a drag is safe to record: `v1` accepts render pixels only, and a
        // display-plane rectangle would make an element that never moved report as moved the first
        // time someone opened the page at a different width.
        assert.deepEqual(
            toRenderPixels(
                { x: 10, y: 20, width: 30, height: 40 },
                frame(800, 400),
            ),
            { x: 20, y: 40, width: 60, height: 80 },
        );
    });

    it("rounds outward, so the selection still contains what was dragged around", () => {
        // floor the origin, ceil the far edge. A box that grew by half a pixel still holds the
        // thing; one that shrank may have clipped the very edge being pointed at.
        assert.deepEqual(
            toRenderPixels(
                { x: 10.4, y: 10.4, width: 5.3, height: 5.3 },
                frame(400, 400),
            ),
            { x: 10, y: 10, width: 6, height: 6 },
        );
    });

    it("answers null for a click with no drag", () => {
        assert.equal(
            toRenderPixels(
                { x: 5, y: 5, width: 0, height: 0 },
                frame(400, 400),
            ),
            null,
        );
    });

    it("answers null before the frame has decoded", () => {
        // No natural size means no scale, and guessing one is how a rectangle ends up in a plane
        // nobody stated.
        assert.equal(
            toRenderPixels(
                { x: 0, y: 0, width: 10, height: 10 },
                frame(0, 400),
            ),
            null,
        );
        assert.equal(
            toRenderPixels(
                { x: 0, y: 0, width: 10, height: 10 },
                frame(400, 0),
            ),
            null,
        );
    });
});
