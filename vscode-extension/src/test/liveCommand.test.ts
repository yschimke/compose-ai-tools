// Pins the LIVE-button → wire-command rule. Same single source of truth
// every entry point on the webview side (per-card click, toolbar
// stop-all, scroll-out, focus-mode toggle) routes through; without this
// the streaming opt-in would silently regress for one entry point but
// not the others.

import * as assert from "assert";
import { liveToggleCommand, liveViewportCommand } from "../daemon/liveCommand";

describe("liveToggleCommand", () => {
    it("posts setInteractive when streaming is off (legacy default)", () => {
        const cmd = liveToggleCommand("preview-A", true, false);
        assert.strictEqual(cmd.command, "setInteractive");
        assert.strictEqual(cmd.previewId, "preview-A");
        if (cmd.command === "setInteractive") {
            assert.strictEqual(cmd.enabled, true);
        }
    });

    it("posts requestStreamStart when enabling under streaming", () => {
        const cmd = liveToggleCommand("preview-A", true, true);
        assert.strictEqual(cmd.command, "requestStreamStart");
        assert.strictEqual(cmd.previewId, "preview-A");
        // requestStreamStart has no `enabled` field — that's the
        // setInteractive shape. Pinning the absence catches a regression
        // where the controller posts the wrong shape.
        assert.ok(!("enabled" in cmd));
    });

    it("posts requestStreamStop when disabling under streaming", () => {
        const cmd = liveToggleCommand("preview-A", false, true);
        assert.strictEqual(cmd.command, "requestStreamStop");
        assert.strictEqual(cmd.previewId, "preview-A");
        assert.ok(!("enabled" in cmd));
    });

    it("posts setInteractive(false) when disabling under legacy", () => {
        const cmd = liveToggleCommand("preview-A", false, false);
        assert.strictEqual(cmd.command, "setInteractive");
        if (cmd.command === "setInteractive") {
            assert.strictEqual(cmd.enabled, false);
        }
    });
});

describe("liveViewportCommand", () => {
    it("returns requestStreamVisibility under streaming so the held session stays warm", () => {
        const cmd = liveViewportCommand("preview-A", false, true);
        assert.strictEqual(cmd.command, "requestStreamVisibility");
        if (cmd.command === "requestStreamVisibility") {
            assert.strictEqual(cmd.visible, false);
            assert.strictEqual(cmd.previewId, "preview-A");
        }
    });

    it("forwards the optional fps to the visibility throttle", () => {
        const cmd = liveViewportCommand("preview-A", false, true, 5);
        assert.strictEqual(cmd.command, "requestStreamVisibility");
        if (cmd.command === "requestStreamVisibility") {
            assert.strictEqual(cmd.fps, 5);
        }
    });

    it("falls back to setInteractive(false) under legacy (hard stop on scroll-out)", () => {
        const cmd = liveViewportCommand("preview-A", false, false);
        assert.strictEqual(cmd.command, "setInteractive");
        if (cmd.command === "setInteractive") {
            assert.strictEqual(cmd.enabled, false);
        }
    });

    it("returns setInteractive(true) on visible=true under legacy", () => {
        const cmd = liveViewportCommand("preview-A", true, false);
        assert.strictEqual(cmd.command, "setInteractive");
        if (cmd.command === "setInteractive") {
            assert.strictEqual(cmd.enabled, true);
        }
    });
});
