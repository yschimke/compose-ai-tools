import * as assert from "assert";
import {
    captureLabel,
    isAnimatedPreview,
    staticBaseCaptureIndex,
    withDataProductCaptures,
} from "../captureLabels";
import { PreviewInfo } from "../types";

const baseParams: PreviewInfo["params"] = {
    name: null,
    device: null,
    widthDp: 0,
    heightDp: 0,
    fontScale: 1.0,
    showSystemUi: false,
    showBackground: false,
    backgroundColor: 0,
    uiMode: 0,
    locale: null,
    group: null,
};

function makePreview(overrides: Partial<PreviewInfo> = {}): PreviewInfo {
    return {
        id: "com.example.PreviewsKt.LongPreview",
        functionName: "LongPreview",
        className: "com.example.PreviewsKt",
        sourceFile: "Previews.kt",
        params: baseParams,
        captures: [
            {
                advanceTimeMillis: null,
                scroll: null,
                renderOutput: "renders/com.example.LongPreview.png",
            },
        ],
        ...overrides,
    };
}

describe("captureLabel", () => {
    it("returns empty for static captures", () => {
        assert.strictEqual(
            captureLabel({
                advanceTimeMillis: null,
                scroll: null,
                renderOutput: "x.png",
            }),
            "",
        );
    });

    it("labels scroll modes via the outcome", () => {
        assert.strictEqual(
            captureLabel({
                advanceTimeMillis: null,
                scroll: {
                    mode: "LONG",
                    axis: "VERTICAL",
                    maxScrollPx: 0,
                    reduceMotion: false,
                    atEnd: false,
                    reachedPx: null,
                },
                renderOutput: "x.png",
            }),
            "scroll long",
        );
    });
});

describe("withDataProductCaptures", () => {
    it("returns the same preview when there are no data products", () => {
        const preview = makePreview();
        assert.strictEqual(withDataProductCaptures(preview), preview);
    });

    it("returns the same preview when data products are non-image", () => {
        const preview = makePreview({
            dataProducts: [
                {
                    kind: "a11y/atf",
                    advanceTimeMillis: null,
                    scroll: null,
                    output: "data/atf/x.json",
                },
            ],
        });
        assert.strictEqual(withDataProductCaptures(preview), preview);
    });

    it("replaces the static base capture with LONG/GIF data products", () => {
        const longScroll = {
            mode: "LONG",
            axis: "VERTICAL",
            maxScrollPx: 0,
            reduceMotion: false,
            atEnd: true,
            reachedPx: null,
        };
        const gifScroll = { ...longScroll, mode: "GIF", atEnd: false };
        const preview = makePreview({
            dataProducts: [
                {
                    kind: "render/scroll/long",
                    advanceTimeMillis: null,
                    scroll: longScroll,
                    output: "data/scroll/long/x.png",
                    cost: 20,
                },
                {
                    kind: "render/scroll/gif",
                    advanceTimeMillis: null,
                    scroll: gifScroll,
                    output: "data/scroll/gif/x.gif",
                    cost: 40,
                },
                {
                    kind: "a11y/atf",
                    advanceTimeMillis: null,
                    scroll: null,
                    output: "data/atf/x.json",
                },
            ],
        });
        const merged = withDataProductCaptures(preview);
        assert.strictEqual(merged.captures.length, 2);
        assert.strictEqual(
            merged.captures[0].renderOutput,
            "data/scroll/long/x.png",
        );
        assert.strictEqual(merged.captures[0].label, "scrolled end");
        assert.strictEqual(merged.captures[0].cost, 20);
        assert.strictEqual(
            merged.captures[1].renderOutput,
            "data/scroll/gif/x.gif",
        );
        assert.strictEqual(merged.captures[1].label, "scroll gif");
        assert.notStrictEqual(merged, preview);
        assert.strictEqual(preview.captures.length, 1);
    });

    it("keeps TOP and animated captures alongside LONG/GIF data products", () => {
        const topScroll = {
            mode: "TOP",
            axis: "VERTICAL",
            maxScrollPx: 0,
            reduceMotion: false,
            atEnd: false,
            reachedPx: null,
        };
        const longScroll = { ...topScroll, mode: "LONG", atEnd: true };
        const preview = makePreview({
            captures: [
                {
                    advanceTimeMillis: null,
                    scroll: null,
                    renderOutput: "renders/static.png",
                },
                {
                    advanceTimeMillis: 500,
                    scroll: null,
                    renderOutput: "renders/animated.png",
                },
                {
                    advanceTimeMillis: null,
                    scroll: topScroll,
                    renderOutput: "renders/top.png",
                },
            ],
            dataProducts: [
                {
                    kind: "render/scroll/long",
                    advanceTimeMillis: null,
                    scroll: longScroll,
                    output: "data/scroll/long/x.png",
                },
            ],
        });
        const merged = withDataProductCaptures(preview);
        assert.strictEqual(merged.captures.length, 3);
        assert.strictEqual(
            merged.captures[0].renderOutput,
            "renders/animated.png",
        );
        assert.strictEqual(merged.captures[1].renderOutput, "renders/top.png");
        assert.strictEqual(
            merged.captures[2].renderOutput,
            "data/scroll/long/x.png",
        );
    });

    it("makes a single-capture preview animated when a data product is added", () => {
        const preview = makePreview({
            dataProducts: [
                {
                    kind: "render/scroll/long",
                    advanceTimeMillis: null,
                    scroll: {
                        mode: "LONG",
                        axis: "VERTICAL",
                        maxScrollPx: 0,
                        reduceMotion: false,
                        atEnd: false,
                        reachedPx: null,
                    },
                    output: "data/scroll/long/x.png",
                },
            ],
        });
        assert.strictEqual(isAnimatedPreview(preview), false);
        assert.strictEqual(
            isAnimatedPreview(withDataProductCaptures(preview)),
            true,
        );
    });
});

describe("staticBaseCaptureIndex", () => {
    it("returns 0 for a vanilla static preview", () => {
        assert.strictEqual(staticBaseCaptureIndex(makePreview()), 0);
    });

    it("returns -1 when LONG/GIF data products dropped the static base", () => {
        // Regression for the "daemon's static representative paints into the
        // scroll-long card" bug — when @ScrollingPreview(LONG) is on a
        // function, withDataProductCaptures drops the static slot and the
        // scroll-long product takes index 0. The daemon's onPreviewImageReady
        // must skip rather than overwrite that slot with the non-scrolling
        // bytes it just rendered.
        const longScroll = {
            mode: "LONG",
            axis: "VERTICAL",
            maxScrollPx: 0,
            reduceMotion: false,
            atEnd: true,
            reachedPx: null,
        };
        const preview = makePreview({
            dataProducts: [
                {
                    kind: "render/scroll/long",
                    advanceTimeMillis: null,
                    scroll: longScroll,
                    output: "data/render-scroll-long/x.png",
                    cost: 20,
                },
            ],
        });
        assert.strictEqual(staticBaseCaptureIndex(preview), -1);
    });

    it("returns the surviving static base index when other captures shift it", () => {
        const topScroll = {
            mode: "TOP",
            axis: "VERTICAL",
            maxScrollPx: 0,
            reduceMotion: false,
            atEnd: false,
            reachedPx: null,
        };
        const preview = makePreview({
            captures: [
                {
                    advanceTimeMillis: 250,
                    scroll: null,
                    renderOutput: "renders/animated.png",
                },
                {
                    advanceTimeMillis: null,
                    scroll: null,
                    renderOutput: "renders/static.png",
                },
                {
                    advanceTimeMillis: null,
                    scroll: topScroll,
                    renderOutput: "renders/top.png",
                },
            ],
        });
        // No data products → withDataProductCaptures returns captures
        // verbatim; static base is at index 1.
        assert.strictEqual(staticBaseCaptureIndex(preview), 1);
    });
});
