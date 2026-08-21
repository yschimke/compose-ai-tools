// Which composited grounds a comparison is actually scored on.
//
// The scorer takes the WORST of several grounds, so admitting a ground that cannot say anything
// true is not a wasted pass — it is a wrong answer that wins the minimum.

import assert from "node:assert/strict";
import { groundsWorthScoring } from "../src/scorer/api.js";

describe("groundsWorthScoring", () => {
    const plane = (...values: number[]) => new Float32Array(values);
    const white = plane(255, 255, 255, 255);
    const black = plane(0, 0, 0, 0);
    const ink = plane(255, 0, 255, 0);

    it("scores every ground when BOTH sides show theirs", () => {
        // The case the second ground exists for: two alpha-bearing stickers, whose surrounds move
        // from white to black together. Dropping the black pass here is what let two different
        // white-ink components score a perfect 100.
        const planes = [
            { reference: white, candidate: white },
            { reference: black, candidate: black },
        ];
        assert.equal(groundsWorthScoring(planes).length, 2);
    });

    it("keeps only the FIRST ground when one side is opaque", () => {
        // A design-page reference is a crop of a rasterised sheet — opaque furniture and all — put
        // against a render with a transparent surround. On the black pass the reference does not
        // move and the render's whole surround does, so the pass measures the grounds rather than
        // the artwork, and `scoreOnEveryGround`'s minimum would take that as the verdict.
        const planes = [
            { reference: ink, candidate: white },
            { reference: ink, candidate: black },
        ];
        const kept = groundsWorthScoring(planes);
        assert.equal(kept.length, 1);
        assert.equal(kept[0], planes[0], "the white ground is the one kept");
    });

    it("keeps only the first ground when NEITHER side is transparent", () => {
        // Two opaque frames composite identically everywhere, so the extra passes are duplicates
        // and the minimum over them is a no-op. Dropping them is free.
        const planes = [
            { reference: ink, candidate: ink },
            { reference: ink, candidate: ink },
        ];
        assert.equal(groundsWorthScoring(planes).length, 1);
    });

    it("treats a sliver of ground through near-opaque alpha as opaque", () => {
        // Alpha 254 moves a luminance by well under one unit. Reading that as "this side shows its
        // ground" would re-admit the mixed-pair pessimism on frames that are opaque in every sense
        // that matters.
        const planes = [
            { reference: plane(255, 0, 255, 0), candidate: white },
            { reference: plane(254.2, 0.6, 254.2, 0.6), candidate: black },
        ];
        assert.equal(groundsWorthScoring(planes).length, 1);
    });
});
