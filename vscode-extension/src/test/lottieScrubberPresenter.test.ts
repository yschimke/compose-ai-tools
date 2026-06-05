import * as assert from "assert";
import {
    computeLottieScrubberData,
    type LottieTimelineMetadata,
} from "../webview/preview/lottieScrubberPresenter";

const META: LottieTimelineMetadata = {
    totalFrames: 60,
    frameRate: 30,
    durationMillis: 2000,
    width: 200,
    height: 200,
};

describe("computeLottieScrubberData", () => {
    it("reports unavailable with an empty-state summary for a null payload", () => {
        const d = computeLottieScrubberData(null, 0.4);
        assert.strictEqual(d.available, false);
        assert.strictEqual(d.totalFrames, 0);
        assert.strictEqual(d.frameIndex, 0);
        assert.strictEqual(d.summary, "no Lottie timeline");
        // Progress is still clamped/echoed so the slider can position itself.
        assert.strictEqual(d.progress, 0.4);
    });

    it("maps progress onto a frame index over [0, totalFrames-1]", () => {
        assert.strictEqual(computeLottieScrubberData(META, 0).frameIndex, 0);
        // 0.5 * 59 = 29.5 → rounds to 30.
        assert.strictEqual(computeLottieScrubberData(META, 0.5).frameIndex, 30);
        assert.strictEqual(computeLottieScrubberData(META, 1).frameIndex, 59);
    });

    it("builds a frame/fps/duration summary", () => {
        assert.strictEqual(
            computeLottieScrubberData(META, 0).summary,
            "frame 0 / 60 · 30 fps · 2.0s",
        );
    });

    it("clamps out-of-range and non-finite progress into 0..1", () => {
        assert.strictEqual(computeLottieScrubberData(META, 2).progress, 1);
        assert.strictEqual(computeLottieScrubberData(META, -1).progress, 0);
        assert.strictEqual(computeLottieScrubberData(META, NaN).progress, 0);
        // Clamped progress drives the frame index too (1 → last frame).
        assert.strictEqual(computeLottieScrubberData(META, 2).frameIndex, 59);
    });

    it("pins a single-frame clip to frame 0 at any progress", () => {
        const oneFrame: LottieTimelineMetadata = {
            totalFrames: 1,
            frameRate: 30,
            durationMillis: 33,
        };
        assert.strictEqual(
            computeLottieScrubberData(oneFrame, 0.9).frameIndex,
            0,
        );
        assert.strictEqual(
            computeLottieScrubberData(oneFrame, 0.9).available,
            true,
        );
    });

    it("omits fps / duration from the summary when the payload lacks them", () => {
        const framesOnly: LottieTimelineMetadata = { totalFrames: 48 };
        assert.strictEqual(
            computeLottieScrubberData(framesOnly, 0).summary,
            "frame 0 / 48",
        );
    });

    it("treats a zero / negative frame count as no timeline", () => {
        assert.strictEqual(
            computeLottieScrubberData({ totalFrames: 0 }, 0).available,
            false,
        );
        assert.strictEqual(
            computeLottieScrubberData({ totalFrames: -5 }, 0).available,
            false,
        );
    });
});
