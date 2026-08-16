// Pairing the annotations that describe the same element on each side, as a table.
//
// These ran in Chromium before, against a script tag, because the code they exercise was inside an
// IIFE. Not one of them needs a browser: they are arithmetic over boxes. Moved here they run in
// milliseconds and can be read beside the code they pin.
//
// The recorded fixture is the important one. 94 Figma boxes against 7 Compose ones is the case the
// whole matcher exists for, and it is real recorded output rather than a hand-built matrix.

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
    annotationHasBounds,
    annotationMatchCost,
    annotationRoleFamily,
    annotationText,
    largestAnnotationFrame,
    mapAnnotationBounds,
    matchAnnotationItems,
    minimumCostAssignment,
    type AnnotationItem,
} from "../src/annotate/match.js";

const RECORDED_MENU = JSON.parse(
    readFileSync(
        new URL(
            "../../../vscode-extension/preview-harness/fixtures/menu-dropdown-annotations.recorded.json",
            import.meta.url,
        ),
        "utf8",
    ),
) as { source: string; reference: AnnotationItem[]; actual: AnnotationItem[] };

const b = (x: number, y: number, width: number, height: number) => ({
    x,
    y,
    width,
    height,
});
const layout = (
    role: string,
    bounds: ReturnType<typeof b>,
    label = "spacing",
) => ({ kind: "layout", role, label, bounds }) as AnnotationItem;

describe("annotationHasBounds", () => {
    it("refuses a box nothing could be drawn on", () => {
        assert.equal(annotationHasBounds({ bounds: b(0, 0, 10, 10) }), true);
        assert.equal(annotationHasBounds({ bounds: b(0, 0, 0, 10) }), false);
        assert.equal(annotationHasBounds({ bounds: b(0, 0, 10, -1) }), false);
        assert.equal(annotationHasBounds({}), false);
        assert.equal(annotationHasBounds(null), false);
    });

    it("refuses a box whose numbers are not numbers", () => {
        // Otherwise `annotationMatchCost` divides by NaN and every cost becomes NaN, which the
        // assignment will happily "optimise" into an arbitrary pairing.
        assert.equal(annotationHasBounds({ bounds: b(NaN, 0, 10, 10) }), false);
        assert.equal(
            annotationHasBounds({ bounds: b(0, 0, Infinity, 10) }),
            false,
        );
    });
});

describe("largestAnnotationFrame", () => {
    it("takes the largest LAYOUT box as the frame", () => {
        const frame = largestAnnotationFrame([
            layout("small", b(0, 0, 10, 10)),
            layout("big", b(0, 0, 100, 100)),
            { kind: "typography", bounds: b(0, 0, 500, 500) },
        ]);
        assert.deepEqual(frame, b(0, 0, 100, 100));
    });

    it("falls back to any box on an all-typography payload", () => {
        // A capture with no layout annotations still needs a frame to scale against.
        const frame = largestAnnotationFrame([
            { kind: "typography", bounds: b(0, 0, 10, 10) },
            { kind: "typography", bounds: b(0, 0, 40, 40) },
        ]);
        assert.deepEqual(frame, b(0, 0, 40, 40));
    });
});

describe("mapAnnotationBounds", () => {
    it("lands the candidate frame's origin on the reference frame's", () => {
        // What removes a preview scaffold or crop offset: the two captures rarely start at the same
        // pixel, and comparing raw coordinates would report every element as displaced.
        const mapped = mapAnnotationBounds(
            b(60, 40, 20, 10),
            b(0, 0, 200, 200),
            b(50, 30, 100, 100),
            2,
        );
        assert.deepEqual(mapped, b(20, 20, 40, 20));
    });

    it("passes bounds through when there is no frame on one side", () => {
        assert.deepEqual(
            mapAnnotationBounds(b(1, 2, 3, 4), null, b(0, 0, 1, 1), 2),
            b(1, 2, 3, 4),
        );
    });
});

describe("annotationText / annotationRoleFamily", () => {
    it("reads px, dp and sp as one unit", () => {
        // "16px" and "16dp" are the same length said two ways; treating them as different labels
        // would stop a matching pair from matching.
        assert.equal(annotationText("16px"), "16u");
        assert.equal(annotationText(" 16DP "), "16u");
        assert.equal(annotationText("16sp"), "16u");
    });

    it("collapses ordinals and positions into one family", () => {
        assert.equal(
            annotationRoleFamily("Button 1"),
            annotationRoleFamily("Button 2"),
        );
        assert.equal(
            annotationRoleFamily("first item"),
            annotationRoleFamily("last item"),
        );
        assert.notEqual(
            annotationRoleFamily("Button"),
            annotationRoleFamily("Chip"),
        );
    });
});

describe("annotationMatchCost", () => {
    const frame = b(0, 0, 100, 100);

    it("costs nothing geometric for two boxes in the same place", () => {
        // The geometry term is zero; the total is -0.04 because two items with NO label both read
        // as the empty string and collect the matching-label bonus. Harmless — it is applied to
        // every such pair equally, so it cannot reorder them — but it means the cost is not a
        // distance and should not be read as one.
        assert.equal(
            annotationMatchCost(
                {},
                {},
                b(10, 10, 20, 20),
                b(10, 10, 20, 20),
                frame,
            ),
            -0.04,
        );
        assert.equal(
            annotationMatchCost(
                { label: "a" },
                { label: "b" },
                b(10, 10, 20, 20),
                b(10, 10, 20, 20),
                frame,
            ),
            0,
        );
    });

    it("measures displacement as a FRACTION of the frame", () => {
        // So the number means the same thing on a chip and on a full screen.
        const small = annotationMatchCost(
            {},
            {},
            b(0, 0, 10, 10),
            b(10, 0, 10, 10),
            b(0, 0, 100, 100),
        );
        const large = annotationMatchCost(
            {},
            {},
            b(0, 0, 10, 10),
            b(100, 0, 10, 10),
            b(0, 0, 1000, 1000),
        );
        assert.ok(Math.abs(small - large) < 1e-9);
    });

    it("lets role and label break a geometric tie, never overrule a real distance", () => {
        const near = b(10, 10, 20, 20);
        const far = b(80, 80, 20, 20);
        const sameRole = annotationMatchCost(
            { role: "Button" },
            { role: "Button" },
            near,
            far,
            frame,
        );
        const noRole = annotationMatchCost({}, {}, near, near, frame);
        assert.ok(
            noRole < sameRole,
            "a distant box with a matching role still loses to a co-located one",
        );
    });
});

describe("minimumCostAssignment", () => {
    it("finds the globally minimal pairing, not the greedy one", () => {
        // The reason this is the Hungarian algorithm and not nearest-match. Greedy takes row 0's
        // favourite (column 0, cost 1) and leaves row 1 with column 1 at cost 9 — total 10. The
        // optimum pairs them the other way for 2 + 1 = 3.
        const assignment = minimumCostAssignment([
            [1, 2],
            [1, 9],
        ]);
        assert.deepEqual(assignment, [1, 0]);
    });

    it("handles more columns than rows", () => {
        const assignment = minimumCostAssignment([[5, 1, 9]]);
        assert.deepEqual(assignment, [1]);
    });

    it("says nothing about an empty matrix", () => {
        assert.deepEqual(minimumCostAssignment([]), []);
    });
});

describe("matchAnnotationItems", () => {
    it("reduces the recorded 94-vs-7 menu to seven shared elements", () => {
        assert.ok(
            RECORDED_MENU.source.includes(
                "menu-dropdown__ideal__default__light",
            ),
        );
        assert.equal(RECORDED_MENU.reference.length, 94);
        assert.equal(RECORDED_MENU.actual.length, 7);

        const matched = matchAnnotationItems(
            RECORDED_MENU.reference,
            RECORDED_MENU.actual,
        );
        assert.equal(matched.reference.length, 7);
        assert.equal(matched.actual.length, 7);
        assert.deepEqual(
            matched.reference.map((item) => item.comparisonOrdinal),
            [1, 2, 3, 4, 5, 6, 7],
        );
        assert.deepEqual(
            matched.actual.map((item) => item.comparisonOrdinal),
            [1, 2, 3, 4, 5, 6, 7],
        );
        assert.deepEqual(
            matched.reference.slice(1).map((item) => item.role),
            [
                "Menu-item 01 - First",
                "Menu-item 02",
                "Menu-item 03",
                "Menu-item 04",
                "Menu-item 05",
                // The render's last visible row aligns with Figma's last item. Figma's item 06 is
                // lower / off-viewport in this recorded layout despite appearing earlier in
                // traversal — which is exactly the ordering a positional matcher has to get right.
                "Menu-item 12 - Last",
            ],
        );
    });

    it("numbers both sides in DESIGN order", () => {
        // A shared ordinal has to identify the same element on both panels even when the Compose
        // tree arrived in a different traversal order — otherwise the two legends disagree about
        // what "3" means.
        const reference = [
            layout("top", b(0, 0, 100, 10)),
            layout("bottom", b(0, 90, 100, 10)),
        ];
        const actual = [
            layout("bottom", b(0, 90, 100, 10)),
            layout("top", b(0, 0, 100, 10)),
        ];
        const matched = matchAnnotationItems(reference, actual);
        assert.deepEqual(
            matched.reference.map((item) => item.role),
            ["top", "bottom"],
        );
        assert.deepEqual(
            matched.actual.map((item) => item.role),
            ["top", "bottom"],
        );
    });

    it("drops an unmatched LAYOUT node but keeps unmatched typography", () => {
        // Different rules on purpose. An extra layout box has no counterpart and says nothing; an
        // extra typography usage is either a missing design usage or a local render override, and
        // both are the finding the page exists to show.
        const reference = [
            layout("a", b(0, 0, 100, 100)),
            layout("b", b(0, 0, 10, 10)),
            { kind: "typography", label: "ref-only", bounds: b(0, 0, 5, 5) },
        ];
        const actual = [
            layout("a", b(0, 0, 100, 100)),
            { kind: "typography", label: "act-only", bounds: b(50, 50, 5, 5) },
            { kind: "typography", label: "act-extra", bounds: b(60, 60, 5, 5) },
        ];
        const matched = matchAnnotationItems(reference, actual);
        assert.equal(
            matched.reference.filter((i) => i.kind === "layout").length,
            1,
            "the unpaired layout node is gone",
        );
        assert.equal(
            matched.actual.filter((i) => i.kind === "typography").length,
            2,
            "both typography usages survive",
        );
    });

    it("keeps a kind captured on only one side", () => {
        const matched = matchAnnotationItems(
            [layout("a", b(0, 0, 10, 10))],
            [{ kind: "theme", label: "surface", bounds: b(0, 0, 10, 10) }],
        );
        assert.equal(matched.actual.length, 1);
        assert.equal(matched.actual[0].kind, "theme");
    });

    it("hands back whatever it was given when one side is empty", () => {
        assert.deepEqual(matchAnnotationItems([], []), {
            reference: [],
            actual: [],
        });
        const oneSided = matchAnnotationItems(
            [layout("a", b(0, 0, 10, 10))],
            [],
        );
        assert.equal(oneSided.reference.length, 1);
        assert.equal(oneSided.actual.length, 0);
    });

    it("survives a payload that is not an array at all", () => {
        assert.deepEqual(matchAnnotationItems(null, undefined), {
            reference: [],
            actual: [],
        });
    });
});
