import * as assert from "assert";
import {
    drawWireframeBoxes,
    type WireframeContext2D,
} from "../webview/spatial/wireframeCompositor";
import { type PanelWireframe } from "../webview/spatial/semanticsTreeLoader";

interface StrokeCall {
    style: string;
    rect: [number, number, number, number];
}

/** A recording stand-in for CanvasRenderingContext2D so the pure draw logic is testable. */
class FakeContext implements WireframeContext2D {
    lineWidth = 0;
    strokeStyle: string | CanvasGradient | CanvasPattern = "";
    readonly calls: StrokeCall[] = [];
    strokeRect(x: number, y: number, w: number, h: number): void {
        this.calls.push({
            style: String(this.strokeStyle),
            rect: [x, y, w, h],
        });
    }
}

function wireframe(): PanelWireframe {
    return {
        panelId: "now-playing",
        contentSize: { width: 560, height: 200 },
        boxes: [
            {
                id: "now-playing:10",
                bounds: { left: 0, top: 0, right: 560, bottom: 200 },
                level: "info",
            },
            {
                id: "now-playing:11",
                bounds: { left: 16, top: 16, right: 300, bottom: 52 },
                level: "info",
            },
            {
                id: "now-playing:button",
                bounds: { left: 24, top: 24, right: 88, bottom: 72 },
                level: "warning",
            },
        ],
    };
}

describe("wireframeCompositor", () => {
    describe("drawWireframeBoxes", () => {
        it("scales box bounds from content space onto the target pixel size", () => {
            const ctx = new FakeContext();
            // 2× the content size, so every coordinate doubles.
            drawWireframeBoxes(ctx, wireframe(), 1120, 400);

            assert.strictEqual(ctx.calls.length, 3);
            assert.deepStrictEqual(ctx.calls[0].rect, [0, 0, 1120, 400]);
            assert.deepStrictEqual(ctx.calls[1].rect, [32, 32, 568, 72]);
            assert.deepStrictEqual(ctx.calls[2].rect, [48, 48, 128, 96]);
        });

        it("strokes each box in its level colour", () => {
            const ctx = new FakeContext();
            drawWireframeBoxes(ctx, wireframe(), 560, 200);
            assert.strictEqual(ctx.calls[0].style, "#4a9eff"); // info
            assert.strictEqual(ctx.calls[2].style, "#ffb84a"); // warning
        });

        it("honours an explicit per-box colour override", () => {
            const wf = wireframe();
            wf.boxes[0].color = "#00ff00";
            const ctx = new FakeContext();
            drawWireframeBoxes(ctx, wf, 560, 200);
            assert.strictEqual(ctx.calls[0].style, "#00ff00");
        });

        it("sets a target-relative line width of at least 2px", () => {
            const small = new FakeContext();
            drawWireframeBoxes(small, wireframe(), 560, 200);
            assert.strictEqual(small.lineWidth, 2);

            const large = new FakeContext();
            drawWireframeBoxes(large, wireframe(), 2800, 1000);
            assert.strictEqual(large.lineWidth, Math.round(1000 / 240));
        });

        it("no-ops on a degenerate content size", () => {
            const ctx = new FakeContext();
            const wf = wireframe();
            wf.contentSize = { width: 0, height: 0 };
            drawWireframeBoxes(ctx, wf, 560, 200);
            assert.strictEqual(ctx.calls.length, 0);
        });
    });
});
