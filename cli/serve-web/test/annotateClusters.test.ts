// Which typography usages get surrounded by one box.
//
// The interesting case is the three-way merge: an item that touches two existing clusters has to
// join them into one. The loop that does it splices in reverse index order, and written forwards it
// drops usages silently — no error, just a box that no longer covers everything under it. So the
// merge is tested by counting the usages that survive, not only the clusters.

import assert from "node:assert/strict";
import {
    clusterTypography,
    expandedBoxesTouch,
    pixelScaleOf,
    unionBounds,
} from "../src/annotate/clusters.js";
import type { AnnotationItem } from "../src/annotate/match.js";
import type { TypographyGroup } from "../src/annotate/typography.js";

const b = (x: number, y: number, width: number, height: number) => ({
    x,
    y,
    width,
    height,
});

function group(
    boxes: Array<ReturnType<typeof b>>,
    lineHeight?: number,
): TypographyGroup {
    return {
        key: "k",
        spec: { lineHeight, labelOnly: false },
        items: boxes.map((bounds) => ({ bounds }) as AnnotationItem),
        roles: new Set<string>(),
    };
}

describe("expandedBoxesTouch", () => {
    it("counts a gap smaller than the allowance as touching", () => {
        const left = b(0, 0, 10, 10);
        const right = b(14, 0, 10, 10);
        assert.equal(expandedBoxesTouch(left, right, 4, 0), true);
        assert.equal(expandedBoxesTouch(left, right, 3, 0), false);
    });

    it("requires BOTH axes to overlap", () => {
        // Two words on the same line are one phrase; the same two words a screen apart vertically
        // are not, however close their columns are.
        assert.equal(
            expandedBoxesTouch(b(0, 0, 10, 10), b(0, 500, 10, 10), 100, 4),
            false,
        );
    });
});

describe("unionBounds", () => {
    it("covers every box it was given", () => {
        assert.deepEqual(
            unionBounds([
                { bounds: b(10, 10, 10, 10) },
                { bounds: b(50, 0, 10, 30) },
            ] as AnnotationItem[]),
            b(10, 0, 50, 30),
        );
    });
});

describe("pixelScaleOf", () => {
    it("takes the MEDIAN ratio, so one scaled usage cannot drag the rest", () => {
        // Three usages at 1× and one inside a container drawn at 10×. A mean would answer ~3.2 and
        // grow the gap enough to cluster the whole screen into one box; the median answers 1.
        const scaled = group(
            [
                b(0, 0, 10, 16),
                b(0, 20, 10, 16),
                b(0, 40, 10, 16),
                b(0, 60, 10, 160),
            ],
            16,
        );
        assert.equal(pixelScaleOf(scaled), 1);
    });

    it("clamps a line height the capture got badly wrong", () => {
        // Otherwise the derived gap is larger than the frame and every style is one cluster.
        assert.equal(pixelScaleOf(group([b(0, 0, 10, 1000)], 1)), 8);
        assert.equal(pixelScaleOf(group([b(0, 0, 10, 1)], 1000)), 0.5);
    });

    it("assumes a 16-unit line when the style did not say", () => {
        assert.equal(pixelScaleOf(group([b(0, 0, 10, 32)])), 2);
    });
});

describe("clusterTypography", () => {
    it("puts a run down a list under one box", () => {
        const rows = group(
            [b(0, 0, 100, 16), b(0, 24, 100, 16), b(0, 48, 100, 16)],
            16,
        );
        const clusters = clusterTypography(rows);
        assert.equal(clusters.length, 1);
        assert.deepEqual(clusters[0], b(0, 0, 100, 64));
    });

    it("keeps two distant runs apart", () => {
        const split = group([b(0, 0, 100, 16), b(0, 900, 100, 16)], 16);
        assert.equal(clusterTypography(split).length, 2);
    });

    it("merges the clusters a later usage bridges, losing none of them", () => {
        // Two runs far enough apart to start separate, then a usage between them that touches both.
        // The merge splices, and getting the splice order wrong drops the far run's usages while
        // still answering one cluster — so the box is what has to be checked.
        const bridged = group(
            [b(0, 0, 20, 16), b(0, 60, 20, 16), b(0, 30, 20, 16)],
            16,
        );
        const clusters = clusterTypography(bridged);
        assert.equal(clusters.length, 1);
        assert.deepEqual(
            clusters[0],
            b(0, 0, 20, 76),
            "the merged box still covers the far run",
        );
    });

    it("says nothing about a group with no usages", () => {
        assert.deepEqual(clusterTypography(group([])), []);
    });
});
