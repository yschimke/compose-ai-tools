// "Fit screen" — the measured height cap, and the guard that stops it feeding itself.

import assert from "node:assert/strict";
import {
    FIT_SLACK,
    MIN_FIT_HEIGHT,
    fitCap,
    needsRefit,
    zoomMode,
} from "../src/viewer/fit.js";

describe("zoomMode", () => {
    it("treats anything that is not an explicit fit-width as fit-screen", () => {
        assert.equal(zoomMode("width"), "width");
        assert.equal(zoomMode("fit"), "fit");
        assert.equal(zoomMode(null), "fit");
        assert.equal(zoomMode(undefined), "fit");
        assert.equal(zoomMode("nonsense"), "fit");
    });
});

describe("fitCap", () => {
    it("measures the space left BELOW the chrome above the stage", () => {
        // A fixed 72vh was wrong in both directions: past the fold on the viewer, and taller than
        // the window had on a short one.
        assert.equal(fitCap(200, 1000), `${1000 - 200 - FIT_SLACK}px`);
    });

    it("floors at a usable stage rather than a sliver", () => {
        assert.equal(fitCap(600, 700), `${MIN_FIT_HEIGHT}px`);
        assert.equal(
            fitCap(2000, 700),
            `${MIN_FIT_HEIGHT}px`,
            "stage below the fold",
        );
    });

    it("rounds to whole pixels, so a fractional measurement cannot churn", () => {
        // The value is compared against what was last applied. A sub-pixel difference surviving
        // into the string would make every re-measure look like a change.
        assert.equal(fitCap(100.4, 1000.6), fitCap(100.4, 1000.6));
        assert.ok(!fitCap(100.4, 1000.6).includes("."));
    });
});

describe("needsRefit", () => {
    it("leaves fit-width alone entirely", () => {
        // An explicit choice to ignore the viewport's height.
        assert.equal(needsRefit("width", "500px", null), false);
        assert.equal(needsRefit("width", "500px", "320px"), false);
    });

    it("re-applies when the measurement moved", () => {
        assert.equal(needsRefit("fit", "500px", "480px"), true);
        assert.equal(needsRefit("fit", "500px", null), true);
    });

    it("does NOTHING when the measurement matches — this is what stops the loop", () => {
        // Applying a cap resizes the image, which resizes the observed container, which
        // re-measures. Without this the observer feeds itself forever.
        assert.equal(needsRefit("fit", "500px", "500px"), false);
    });
});
