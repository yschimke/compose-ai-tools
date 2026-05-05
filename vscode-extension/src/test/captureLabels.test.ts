import * as assert from "assert";
import {
    captureLabel,
    isAnimatedPreview,
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

    it("appends LONG/GIF data products as carousel captures", () => {
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
        assert.strictEqual(merged.captures.length, 3);
        assert.strictEqual(
            merged.captures[1].renderOutput,
            "data/scroll/long/x.png",
        );
        assert.strictEqual(merged.captures[1].label, "scrolled end");
        assert.strictEqual(merged.captures[1].cost, 20);
        assert.strictEqual(
            merged.captures[2].renderOutput,
            "data/scroll/gif/x.gif",
        );
        assert.strictEqual(merged.captures[2].label, "scroll gif");
        assert.notStrictEqual(merged, preview);
        assert.strictEqual(preview.captures.length, 1);
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
