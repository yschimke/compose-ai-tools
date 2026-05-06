import * as assert from "assert";
import {
    computeImagePoint,
    isEventInsideRect,
} from "../webview/preview/pointerGeometry";

describe("computeImagePoint", () => {
    it("maps a 1:1-scaled image's center to the natural-pixel center", () => {
        // 100×100 natural, 100×100 displayed at (0,0). Click in the middle.
        const point = computeImagePoint(100, 100, 100, 100, 0, 0, 50, 50);
        assert.deepStrictEqual(point, {
            clientX: 50,
            clientY: 50,
            pixelX: 50,
            pixelY: 50,
        });
    });

    it("scales 2x-displayed CSS pixels back to natural pixels", () => {
        // 100×100 natural, displayed at 200×200 → each CSS pixel is 0.5
        // natural pixels.
        const point = computeImagePoint(100, 100, 200, 200, 0, 0, 100, 100);
        assert.strictEqual(point?.pixelX, 50);
        assert.strictEqual(point?.pixelY, 50);
        // clientX / clientY are still in displayed-CSS space.
        assert.strictEqual(point?.clientX, 100);
        assert.strictEqual(point?.clientY, 100);
    });

    it("subtracts rect.left / rect.top so the math is image-relative", () => {
        const point = computeImagePoint(100, 100, 100, 100, 50, 30, 75, 80);
        assert.strictEqual(point?.clientX, 25);
        assert.strictEqual(point?.clientY, 50);
        assert.strictEqual(point?.pixelX, 25);
        assert.strictEqual(point?.pixelY, 50);
    });

    it("clamps a click on the right/bottom edge to naturalDimension - 1", () => {
        // 100×100 natural at (0,0), exactly 100×100 displayed. Click at
        // the bottom-right corner — without the clamp pixelX/pixelY would
        // be 100 (out of bounds for a 100-pixel image).
        const point = computeImagePoint(100, 100, 100, 100, 0, 0, 100, 100);
        assert.strictEqual(point?.pixelX, 99);
        assert.strictEqual(point?.pixelY, 99);
    });

    it("clamps a click outside the left/top edge to 0", () => {
        const point = computeImagePoint(100, 100, 100, 100, 50, 50, 0, 0);
        assert.strictEqual(point?.pixelX, 0);
        assert.strictEqual(point?.pixelY, 0);
    });

    it("rounds rather than truncates the natural-pixel coords", () => {
        // 3-pixel-wide natural image displayed at 10 CSS pixels:
        // each CSS pixel = 0.3 natural. Click at clientX=2 (CSS) →
        // pixelX = round(2 * 3/10) = round(0.6) = 1.
        const point = computeImagePoint(3, 3, 10, 10, 0, 0, 2, 2);
        assert.strictEqual(point?.pixelX, 1);
        assert.strictEqual(point?.pixelY, 1);
    });

    it("returns null when the image's natural dimensions are zero", () => {
        // <img> not yet loaded — naturalWidth/Height are 0.
        assert.strictEqual(
            computeImagePoint(0, 100, 100, 100, 0, 0, 50, 50),
            null,
        );
        assert.strictEqual(
            computeImagePoint(100, 0, 100, 100, 0, 0, 50, 50),
            null,
        );
    });

    it("returns null when the displayed rect has zero size", () => {
        // Hidden / collapsed image — getBoundingClientRect width / height
        // are zero.
        assert.strictEqual(
            computeImagePoint(100, 100, 0, 100, 0, 0, 50, 50),
            null,
        );
        assert.strictEqual(
            computeImagePoint(100, 100, 100, 0, 0, 0, 50, 50),
            null,
        );
    });
});

describe("isEventInsideRect", () => {
    const rect = { left: 10, top: 20, right: 110, bottom: 120 };

    it("returns true for a point in the interior", () => {
        assert.strictEqual(isEventInsideRect(rect, 50, 50), true);
    });

    it("returns true for a point exactly on each edge (inclusive)", () => {
        assert.strictEqual(isEventInsideRect(rect, 10, 50), true); // left
        assert.strictEqual(isEventInsideRect(rect, 110, 50), true); // right
        assert.strictEqual(isEventInsideRect(rect, 50, 20), true); // top
        assert.strictEqual(isEventInsideRect(rect, 50, 120), true); // bottom
    });

    it("returns true for the four corner points (inclusive)", () => {
        assert.strictEqual(isEventInsideRect(rect, 10, 20), true);
        assert.strictEqual(isEventInsideRect(rect, 110, 20), true);
        assert.strictEqual(isEventInsideRect(rect, 10, 120), true);
        assert.strictEqual(isEventInsideRect(rect, 110, 120), true);
    });

    it("returns false for points just outside any edge", () => {
        assert.strictEqual(isEventInsideRect(rect, 9, 50), false); // left of left
        assert.strictEqual(isEventInsideRect(rect, 111, 50), false); // right of right
        assert.strictEqual(isEventInsideRect(rect, 50, 19), false); // above top
        assert.strictEqual(isEventInsideRect(rect, 50, 121), false); // below bottom
    });

    it("returns false for points outside two edges (corner + offset)", () => {
        assert.strictEqual(isEventInsideRect(rect, 9, 19), false);
        assert.strictEqual(isEventInsideRect(rect, 200, 200), false);
    });
});
