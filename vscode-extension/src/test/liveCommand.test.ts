// Pins the LIVE-button → wire-command rule. Same single source of truth
// every entry point on the webview side (per-card click, toolbar
// stop-all, scroll-out, focus-mode toggle) routes through; without this
// any one entry point could silently regress to the wrong wire shape
// while the others stay correct.

import * as assert from "assert";
import { liveToggleCommand, liveViewportCommand } from "../daemon/liveCommand";

describe("liveToggleCommand", () => {
    it("posts requestStreamStart when enabling", () => {
        const cmd = liveToggleCommand("preview-A", true);
        assert.strictEqual(cmd.command, "requestStreamStart");
        assert.strictEqual(cmd.previewId, "preview-A");
        // requestStreamStart has no `enabled` field; pinning the absence
        // catches a regression where the controller posts the wrong shape.
        assert.ok(!("enabled" in cmd));
    });

    it("posts requestStreamStop when disabling", () => {
        const cmd = liveToggleCommand("preview-A", false);
        assert.strictEqual(cmd.command, "requestStreamStop");
        assert.strictEqual(cmd.previewId, "preview-A");
        assert.ok(!("enabled" in cmd));
    });

    it("forwards `overrides` on start when any field is defined", () => {
        const cmd = liveToggleCommand("preview-A", true, {
            touchOverlay: true,
        });
        assert.strictEqual(cmd.command, "requestStreamStart");
        if (cmd.command === "requestStreamStart") {
            assert.deepStrictEqual(cmd.overrides, { touchOverlay: true });
        }
    });

    it("forwards a multi-field `overrides` payload verbatim", () => {
        const cmd = liveToggleCommand("preview-A", true, {
            touchOverlay: true,
            keyboard: { visible: true },
        });
        if (cmd.command === "requestStreamStart") {
            assert.deepStrictEqual(cmd.overrides, {
                touchOverlay: true,
                keyboard: { visible: true },
            });
        }
    });

    it("drops `overrides` entirely when every field is undefined", () => {
        // Empty object MUST collapse to the no-overrides shape — otherwise the
        // wire payload grows for no reason and pre-toggle tests break.
        const cmd = liveToggleCommand("preview-A", true, {});
        assert.strictEqual(cmd.command, "requestStreamStart");
        assert.ok(!("overrides" in cmd));
    });

    it("ignores `overrides` on disable (stop has no overrides field)", () => {
        const cmd = liveToggleCommand("preview-A", false, {
            touchOverlay: true,
        });
        assert.strictEqual(cmd.command, "requestStreamStop");
        assert.ok(!("overrides" in cmd));
    });
});

describe("liveViewportCommand", () => {
    it("returns requestStreamVisibility so the held session stays warm", () => {
        const cmd = liveViewportCommand("preview-A", false);
        assert.strictEqual(cmd.command, "requestStreamVisibility");
        if (cmd.command === "requestStreamVisibility") {
            assert.strictEqual(cmd.visible, false);
            assert.strictEqual(cmd.previewId, "preview-A");
        }
    });

    it("forwards the optional fps to the visibility throttle", () => {
        const cmd = liveViewportCommand("preview-A", false, 5);
        assert.strictEqual(cmd.command, "requestStreamVisibility");
        if (cmd.command === "requestStreamVisibility") {
            assert.strictEqual(cmd.fps, 5);
        }
    });

    it("forwards visible=true (scroll-back-into-view resumes full fps)", () => {
        const cmd = liveViewportCommand("preview-A", true);
        assert.strictEqual(cmd.command, "requestStreamVisibility");
        if (cmd.command === "requestStreamVisibility") {
            assert.strictEqual(cmd.visible, true);
        }
    });
});
