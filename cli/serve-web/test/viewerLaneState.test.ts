// Which lane is showing, what it is called, and what each chip reports.
//
// The failure these guard against is a chip claiming something untrue — lit over a lane that is no
// longer on the stage, or naming a renderer that is not drawing. That is worse than a control that
// visibly fails, because nothing looks wrong.

import assert from "node:assert/strict";
import {
    anyInteractive,
    bestLiveMode,
    currentLaneValue,
    laneChip,
    laneLabelText,
    liveTransportAvailable,
    type LaneFlags,
} from "../src/viewer/laneState.js";

const lanes = (over: Partial<LaneFlags> = {}): LaneFlags => ({
    rcWasm: false,
    rc: false,
    wasm: false,
    spec: false,
    live: false,
    ...over,
});

describe("anyInteractive", () => {
    it("counts every lane that paints a RUNNING composition", () => {
        // Picking "JS" from the combo must light the same status dot as clicking into Live: both
        // are the claim "this is running", and reporting them differently would make the dot mean
        // two things.
        for (const key of ["live", "wasm", "rc", "rcWasm"] as const) {
            assert.equal(anyInteractive(lanes({ [key]: true })), true, key);
        }
    });

    it("does not count a finished image", () => {
        assert.equal(anyInteractive(lanes()), false);
        assert.equal(
            anyInteractive(lanes({ spec: true })),
            false,
            "the design spec is a picture",
        );
    });
});

describe("liveTransportAvailable / bestLiveMode", () => {
    it("prefers the daemon stream, and falls back to the in-browser app", () => {
        assert.equal(bestLiveMode({ daemon: true, wasm: true }), "live");
        assert.equal(bestLiveMode({ daemon: true, wasm: false }), "live");
        assert.equal(bestLiveMode({ daemon: false, wasm: true }), "wasm");
    });

    it("answers null, not a lane, when this session offers neither", () => {
        assert.equal(bestLiveMode({ daemon: false, wasm: false }), null);
        assert.equal(
            liveTransportAvailable({ daemon: false, wasm: false }),
            false,
        );
        assert.equal(
            liveTransportAvailable({ daemon: false, wasm: true }),
            true,
        );
    });
});

describe("currentLaneValue", () => {
    const pick = { defaultBackend: "", pickedBackend: "", picked: false };

    it("reports the painting lane, most specific first", () => {
        assert.equal(
            currentLaneValue(lanes({ rcWasm: true }), pick),
            "rc:cmp-wasm",
        );
        assert.equal(currentLaneValue(lanes({ rc: true }), pick), "rc:js");
        assert.equal(currentLaneValue(lanes({ wasm: true }), pick), "wasm");
        assert.equal(currentLaneValue(lanes({ spec: true }), pick), "spec");
    });

    it("falls through a daemon stream to the lane it will return to", () => {
        // A stream is not one of the offered renderers — it is the live form of whichever one is
        // picked. Reporting "live" here would make the chip rename itself on entering Live and
        // forget which renderer it came from.
        const rc = { defaultBackend: "java", pickedBackend: "", picked: false };
        assert.equal(currentLaneValue(lanes({ live: true }), rc), "rc:java");
    });

    it("prefers the visitor's pick over the server's default", () => {
        assert.equal(
            currentLaneValue(lanes(), {
                defaultBackend: "java",
                pickedBackend: "cmp-jvm",
                picked: true,
            }),
            "rc:cmp-jvm",
        );
        assert.equal(
            currentLaneValue(lanes(), {
                defaultBackend: "java",
                pickedBackend: "cmp-jvm",
                picked: false,
            }),
            "rc:java",
            "an unpicked backend stays on the server's default",
        );
    });

    it("is the plain snapshot when there is no Remote Compose at all", () => {
        assert.equal(currentLaneValue(lanes(), pick), "png");
    });
});

describe("laneLabelText", () => {
    const laneOptions = new Map([
        ["rc:java", "Java"],
        ["rc:js", "JS"],
        ["png", "Snapshot"],
    ]);

    it("says Live while the stream is up, whatever is picked underneath", () => {
        assert.equal(
            laneLabelText({
                live: true,
                laneOptions,
                wanted: "rc:java",
                defaultLabel: "Live preview",
            }),
            "Live",
        );
    });

    it("uses the combo's OWN label, so the two cannot disagree", () => {
        assert.equal(
            laneLabelText({
                live: false,
                laneOptions,
                wanted: "rc:js",
                defaultLabel: "Live preview",
            }),
            "JS",
        );
    });

    it("keeps naming the render lane on the spec lane", () => {
        // There is no `spec` option, deliberately: the spec chip beside this one is already lit and
        // names it, and two adjacent chips both reading "Figma" would be two controls arguing about
        // the same fact. So this one names where clicking it goes back TO.
        assert.equal(
            laneLabelText({
                live: false,
                laneOptions,
                wanted: "spec",
                defaultLabel: "Live preview",
            }),
            "Live preview",
        );
    });

    it("falls back to the server-rendered label on a preview with no combo", () => {
        assert.equal(
            laneLabelText({
                live: false,
                laneOptions: null,
                wanted: "png",
                defaultLabel: "Live preview",
            }),
            "Live preview",
        );
    });
});

describe("laneChip", () => {
    it("presses when its lane is on the stage", () => {
        assert.deepEqual(laneChip({ onLane: true, available: true }), {
            pressed: true,
            disabled: false,
        });
    });

    it("stays ENABLED on its own lane even when unavailable", () => {
        // The only way back out. A chip that disabled itself on entry would strand the visitor on
        // the lane it just entered.
        assert.deepEqual(laneChip({ onLane: true, available: false }), {
            pressed: true,
            disabled: false,
        });
    });

    it("disables only when there is nothing to enter and nothing to leave", () => {
        assert.deepEqual(laneChip({ onLane: false, available: false }), {
            pressed: false,
            disabled: true,
        });
        assert.deepEqual(laneChip({ onLane: false, available: true }), {
            pressed: false,
            disabled: false,
        });
    });
});
