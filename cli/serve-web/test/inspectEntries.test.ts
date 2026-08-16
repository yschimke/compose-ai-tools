// What the inspection layers decide to draw, as a table.
//
// Almost none of this is visible in a screenshot: a rectangle over a component looks equally right
// whether it is the correct node, a duplicate of its parent, or a finding that was silently
// dropped. Each case below is a wrong picture the rule exists to prevent.

import assert from "node:assert/strict";
import {
    PALETTE,
    a11yEntries,
    annotationEntries,
    isFocusStop,
    levelOf,
    parseBounds,
    worst,
    type A11yPayload,
} from "../src/inspect/entries.js";

describe("parseBounds", () => {
    it("reads the daemon's left,top,right,bottom", () => {
        assert.deepEqual(parseBounds("10,20,110,70"), {
            x: 10,
            y: 20,
            width: 100,
            height: 50,
        });
    });

    it("refuses a box nobody could point at", () => {
        // A zero- or negative-area box draws an invisible rectangle and puts a legend row beside it
        // that highlights nothing.
        for (const bad of ["10,20,10,70", "10,20,110,20", "10,20,5,70"]) {
            assert.equal(parseBounds(bad), null, bad);
        }
    });

    it("refuses anything that is not four numbers", () => {
        for (const bad of [
            "",
            null,
            undefined,
            "1,2,3",
            "1,2,3,4,5",
            "a,b,c,d",
            "1,2,3,x",
        ]) {
            assert.equal(parseBounds(bad), null, String(bad));
        }
    });
});

describe("levelOf / worst", () => {
    it("takes the two severities that mean something", () => {
        assert.equal(levelOf("error"), "error");
        assert.equal(levelOf("ERROR"), "error");
        assert.equal(levelOf("warning"), "warning");
        assert.equal(levelOf("warn"), "warning");
    });

    it("treats anything unrecognised as information, not failure", () => {
        for (const quiet of ["", null, undefined, "info", "nonsense"]) {
            assert.equal(levelOf(quiet), "info", String(quiet));
        }
    });

    it("escalates rather than overwrites", () => {
        // A node carrying both a warning and an error has to read as the error; the order the wire
        // happened to list them in must not decide which colour it gets.
        assert.equal(worst(null, "warning"), "warning");
        assert.equal(worst("warning", "error"), "error");
        assert.equal(worst("error", "warning"), "error");
        assert.equal(worst("error", "info"), "error");
        assert.equal(worst(null, "info"), null, "info alone is not a flag");
    });
});

describe("isFocusStop", () => {
    it("treats an ABSENT merged flag as merged", () => {
        // The daemon omits `merged` when it is true — it is the Kotlin default. Read as `!merged`,
        // every unmerged inner Text would draw a second rectangle on exactly its focusable
        // ancestor's pixels: two boxes, two legend rows, one thing.
        assert.equal(isFocusStop({}), true);
        assert.equal(isFocusStop({ merged: true }), true);
        assert.equal(isFocusStop({ merged: false }), false);
    });
});

const payload = (over: Partial<A11yPayload> = {}): A11yPayload => ({
    nodes: [
        {
            boundsInScreen: "0,0,100,50",
            label: "Save",
            role: "Button",
            states: ["enabled"],
        },
        { boundsInScreen: "0,60,100,110", label: "Cancel", role: "Button" },
    ],
    findings: [],
    touchTargets: [],
    ...over,
});

describe("a11yEntries", () => {
    it("draws one entry per screen-reader stop", () => {
        const entries = a11yEntries(payload());
        assert.deepEqual(
            entries.map((e) => e.title),
            ["Save", "Cancel"],
        );
        assert.equal(entries[0].detail, "Button · enabled");
        assert.equal(entries[0].level, "info");
    });

    it("skips the merged nodes that would stack a second box on the same pixels", () => {
        const entries = a11yEntries(
            payload({
                nodes: [
                    { boundsInScreen: "0,0,100,50", label: "Save" },
                    {
                        boundsInScreen: "0,0,100,50",
                        label: "Save",
                        merged: false,
                    },
                ],
            }),
        );
        assert.equal(entries.length, 1);
    });

    it("gives un-flagged stops distinct hues so adjacent boxes can be told apart", () => {
        // With one colour for everything, adjacent focus targets in a list merge into a block and
        // the legend cannot be matched back to a box by eye.
        const entries = a11yEntries(payload());
        assert.equal(entries[0].color, PALETTE[0]);
        assert.equal(entries[1].color, PALETTE[1]);
    });

    it("takes its colour from the level once a stop is flagged", () => {
        const entries = a11yEntries(
            payload({
                findings: [
                    {
                        boundsInScreen: "0,0,100,50",
                        level: "ERROR",
                        type: "SpeakableTextPresentCheck",
                        message: "no label",
                    },
                ],
            }),
        );
        assert.equal(entries[0].level, "error");
        assert.equal(
            entries[0].color,
            null,
            "a hue here would compete with the severity",
        );
        assert.ok(
            entries[0].detail.includes("SpeakableTextPresentCheck: no label"),
        );
    });

    it("escalates to the worst finding on a node, whatever order they arrive in", () => {
        const entries = a11yEntries(
            payload({
                findings: [
                    {
                        boundsInScreen: "0,0,100,50",
                        level: "warning",
                        type: "A",
                        message: "m",
                    },
                    {
                        boundsInScreen: "0,0,100,50",
                        level: "error",
                        type: "B",
                        message: "m",
                    },
                ],
            }),
        );
        assert.equal(entries[0].level, "error");
    });

    it("warns on a too-small touch target that nothing else flagged", () => {
        // The element is reachable and labelled; it is just too small to hit. Left as `info` it
        // would read as a pass.
        const entries = a11yEntries(
            payload({
                touchTargets: [
                    {
                        boundsInScreen: "0,0,100,50",
                        widthDp: 20.4,
                        heightDp: 20.6,
                        findings: ["smaller than 48dp"],
                    },
                ],
            }),
        );
        assert.equal(entries[0].level, "warning");
        assert.ok(entries[0].detail.includes("20×21 dp"), entries[0].detail);
        assert.ok(entries[0].detail.includes("smaller than 48dp"));
    });

    it("reports a target's size without warning when it carries no findings", () => {
        const entries = a11yEntries(
            payload({
                touchTargets: [
                    { boundsInScreen: "0,0,100,50", widthDp: 48, heightDp: 48 },
                ],
            }),
        );
        assert.equal(entries[0].level, "info");
        assert.ok(entries[0].detail.includes("48×48 dp"));
    });

    it("surfaces a finding the hierarchy has no node for", () => {
        // The rule that matters most: a finding whose bounds do not line up with a node is still a
        // real problem, and dropping it reports the frame as clean on exactly the elements the
        // hierarchy could not describe.
        const entries = a11yEntries(
            payload({
                findings: [
                    {
                        boundsInScreen: "200,200,300,260",
                        level: "error",
                        type: "TouchTargetSizeCheck",
                        message: "too small",
                        viewDescription: "android.widget.Button",
                    },
                ],
            }),
        );
        assert.equal(entries.length, 3);
        assert.equal(entries[2].title, "android.widget.Button");
        assert.equal(entries[2].level, "error");
    });

    it("does not double-report a finding that already landed on its node", () => {
        const entries = a11yEntries(
            payload({
                findings: [
                    {
                        boundsInScreen: "0,0,100,50",
                        level: "error",
                        type: "A",
                        message: "m",
                    },
                ],
            }),
        );
        assert.equal(
            entries.length,
            2,
            "no orphan copy beside the node that carries it",
        );
    });

    it("names an unlabelled stop rather than drawing a blank row", () => {
        const entries = a11yEntries(
            payload({ nodes: [{ boundsInScreen: "0,0,10,10" }] }),
        );
        assert.equal(entries[0].title, "(unlabelled)");
    });

    it("drops a node or finding whose bounds cannot be drawn", () => {
        const entries = a11yEntries(
            payload({
                nodes: [{ boundsInScreen: "bad", label: "Ghost" }],
                findings: [
                    {
                        boundsInScreen: "",
                        level: "error",
                        type: "A",
                        message: "m",
                    },
                ],
            }),
        );
        assert.deepEqual(entries, []);
    });

    it("says nothing at all about a payload that never arrived", () => {
        assert.deepEqual(a11yEntries(null), []);
        assert.deepEqual(a11yEntries({}), []);
    });
});

describe("annotationEntries", () => {
    const shared = {
        annotations: [
            {
                kind: "typography",
                bounds: { x: 0, y: 0, width: 10, height: 10 },
                role: "Title",
                label: "20sp Roboto",
                detail: {
                    fontVariationSettings: "opsz 18.0, wght 700.0",
                },
            },
            {
                kind: "theme",
                bounds: { x: 0, y: 0, width: 10, height: 10 },
                label: "surface",
            },
            { kind: "typography", label: "no bounds" },
        ],
    };

    it("takes only its own kind, and only what it can place", () => {
        const entries = annotationEntries(shared, "typography");
        assert.equal(entries.length, 1);
        assert.equal(entries[0].title, "Title");
        assert.equal(entries[0].detail, "20sp Roboto · axes opsz=18,wght=700");
    });

    it("surfaces variable-font axes even when typography has no role", () => {
        const entries = annotationEntries(
            {
                annotations: [
                    {
                        kind: "typography",
                        bounds: { x: 0, y: 0, width: 10, height: 10 },
                        label: "Roboto Flex",
                        detail: {
                            fontVariationSettings: [
                                { tag: "wght", value: 650 },
                                { tag: "wdth", value: 90 },
                            ],
                        },
                    },
                ],
            },
            "typography",
        );

        assert.equal(entries[0].title, "Roboto Flex");
        assert.equal(entries[0].detail, "axes wdth=90,wght=650");
    });

    it("falls back to the label as the title when there is no role", () => {
        const entries = annotationEntries(shared, "theme");
        assert.equal(entries[0].title, "surface");
        assert.equal(entries[0].detail, "", "nothing left to say twice");
    });

    it("is always informational — these describe, they do not judge", () => {
        for (const entry of annotationEntries(shared, "typography")) {
            assert.equal(entry.level, "info");
            assert.equal(entry.color, null);
        }
    });

    it("says nothing at all about a payload that never arrived", () => {
        assert.deepEqual(annotationEntries(null, "typography"), []);
    });
});
