import * as assert from "assert";
import { planFollowFocusTeardown } from "../webview/preview/followFocus";

// Pins the branch logic that `LiveStateController.enforceSingleTargetFollowFocus`
// used to inline. The planner is the single choke point for the
// "follow focus when exactly one stream is live" rule — every
// non-trivial guard (size === 0, size > 1, focus matches lone, focus
// missing) is exercised here so the controller wrapper can stay a
// thin "post + clear + repaint" sandwich.
describe("planFollowFocusTeardown", () => {
    let liveSet: Set<string>;

    beforeEach(() => {
        liveSet = new Set<string>();
    });

    afterEach(() => {
        liveSet.clear();
    });

    it("returns null when the live set is empty", () => {
        // No live streams → nothing to tear down regardless of focus.
        assert.strictEqual(planFollowFocusTeardown(liveSet, null), null);
        assert.strictEqual(
            planFollowFocusTeardown(liveSet, "com.example.A"),
            null,
        );
    });

    it("returns null when there are 2+ live targets (multi-target opt-in)", () => {
        // Multi-target (size > 1) is the explicit Shift+click opt-in
        // upstream. Those streams must persist across focus navigation.
        liveSet.add("com.example.A");
        liveSet.add("com.example.B");
        assert.strictEqual(planFollowFocusTeardown(liveSet, null), null);
        assert.strictEqual(
            planFollowFocusTeardown(liveSet, "com.example.A"),
            null,
        );
        assert.strictEqual(
            planFollowFocusTeardown(liveSet, "com.example.OTHER"),
            null,
        );
    });

    it("returns null when exactly one live target matches the focused card", () => {
        // The lone live stream is already focused — no teardown
        // needed; the LIVE chip is on the right card.
        liveSet.add("com.example.A");
        assert.strictEqual(
            planFollowFocusTeardown(liveSet, "com.example.A"),
            null,
        );
    });

    it("returns the lone teardown id when exactly one live target mismatches the focused card", () => {
        // User navigated focus off the lone live card. The LIVE chip
        // should follow focus, so the planner names the prior id for
        // teardown.
        liveSet.add("com.example.A");
        const plan = planFollowFocusTeardown(liveSet, "com.example.B");
        assert.notStrictEqual(plan, null);
        assert.strictEqual(plan?.teardownId, "com.example.A");
    });

    it("returns the lone teardown id when exactly one live target and no focus", () => {
        // No focused card (e.g. transient layout) and a single
        // orphaned live stream — tear it down so the chip doesn't
        // dangle.
        liveSet.add("com.example.A");
        const plan = planFollowFocusTeardown(liveSet, null);
        assert.notStrictEqual(plan, null);
        assert.strictEqual(plan?.teardownId, "com.example.A");
    });
});
