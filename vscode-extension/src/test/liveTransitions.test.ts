import * as assert from "assert";
import {
    planLiveToggle,
    planRecordingToggle,
} from "../webview/preview/liveTransitions";

describe("planLiveToggle", () => {
    describe("single-target (shift = false)", () => {
        it("turns on the target with no priors → next={target}, no deactivate", () => {
            const result = planLiveToggle(new Set(), "preview:A", false);
            assert.strictEqual(result.turnOnTarget, true);
            assert.deepStrictEqual(result.deactivate, []);
            assert.deepStrictEqual([...result.next], ["preview:A"]);
        });

        it("turns on the target and deactivates priors", () => {
            const result = planLiveToggle(
                new Set(["preview:X", "preview:Y"]),
                "preview:A",
                false,
            );
            assert.strictEqual(result.turnOnTarget, true);
            assert.deepStrictEqual(
                [...result.deactivate].sort(),
                ["preview:X", "preview:Y"].sort(),
            );
            assert.deepStrictEqual([...result.next], ["preview:A"]);
        });

        it("turns off the target when it's the only live (single-target tap-again)", () => {
            const result = planLiveToggle(
                new Set(["preview:A"]),
                "preview:A",
                false,
            );
            assert.strictEqual(result.turnOnTarget, false);
            assert.deepStrictEqual(result.deactivate, []);
            assert.strictEqual(result.next.size, 0);
        });

        it("turns off the target and deactivates other priors when the target is one of several", () => {
            // Edge case: shift=false on a card that's already in a multi-
            // target Shift+click set. The single-target semantics win —
            // priors get deactivated, target toggles off.
            const result = planLiveToggle(
                new Set(["preview:A", "preview:X"]),
                "preview:A",
                false,
            );
            assert.strictEqual(result.turnOnTarget, false);
            assert.deepStrictEqual(result.deactivate, ["preview:X"]);
            assert.strictEqual(result.next.size, 0);
        });

        it("returns a fresh Set instance even when there's nothing to do", () => {
            const current = new Set<string>();
            const result = planLiveToggle(current, "preview:A", false);
            assert.notStrictEqual(result.next, current);
        });
    });

    describe("multi-target (shift = true)", () => {
        it("adds the target without deactivating priors", () => {
            const result = planLiveToggle(
                new Set(["preview:X", "preview:Y"]),
                "preview:A",
                true,
            );
            assert.strictEqual(result.turnOnTarget, true);
            assert.deepStrictEqual(result.deactivate, []);
            assert.deepStrictEqual(
                [...result.next].sort(),
                ["preview:A", "preview:X", "preview:Y"].sort(),
            );
        });

        it("removes the target without touching others", () => {
            const result = planLiveToggle(
                new Set(["preview:A", "preview:X", "preview:Y"]),
                "preview:A",
                true,
            );
            assert.strictEqual(result.turnOnTarget, false);
            assert.deepStrictEqual(result.deactivate, []);
            assert.deepStrictEqual(
                [...result.next].sort(),
                ["preview:X", "preview:Y"].sort(),
            );
        });

        it("does not mutate the input Set", () => {
            const current = new Set(["preview:X"]);
            planLiveToggle(current, "preview:A", true);
            assert.deepStrictEqual([...current], ["preview:X"]);
        });
    });
});

describe("planRecordingToggle", () => {
    it("turns on the target with no priors", () => {
        const result = planRecordingToggle(new Set(), "preview:A");
        assert.strictEqual(result.turnOnTarget, true);
        assert.deepStrictEqual(result.deactivate, []);
        assert.deepStrictEqual([...result.next], ["preview:A"]);
    });

    it("turns on the target and deactivates the prior recording (single-target)", () => {
        const result = planRecordingToggle(new Set(["preview:X"]), "preview:A");
        assert.strictEqual(result.turnOnTarget, true);
        assert.deepStrictEqual(result.deactivate, ["preview:X"]);
        assert.deepStrictEqual([...result.next], ["preview:A"]);
    });

    it("turns off the target when it is the current recording", () => {
        const result = planRecordingToggle(new Set(["preview:A"]), "preview:A");
        assert.strictEqual(result.turnOnTarget, false);
        assert.deepStrictEqual(result.deactivate, []);
        assert.strictEqual(result.next.size, 0);
    });

    it("does not deactivate priors when turning the target off", () => {
        // Defensive: recording is supposed to be single-target so the
        // set should never have other ids when the target is in it,
        // but if it somehow does, the off path leaves them be (the
        // controller treats this like a no-op for the others).
        const result = planRecordingToggle(
            new Set(["preview:A", "preview:X"]),
            "preview:A",
        );
        assert.strictEqual(result.turnOnTarget, false);
        assert.deepStrictEqual(result.deactivate, []);
    });

    it("returns a fresh Set instance even when there's nothing to do", () => {
        const current = new Set<string>();
        const result = planRecordingToggle(current, "preview:A");
        assert.notStrictEqual(result.next, current);
    });
});
