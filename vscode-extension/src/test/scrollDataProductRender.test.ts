import * as assert from "assert";
import { ModuleInfo } from "../gradleService";
import { findMissingImageDataProducts } from "../scrollDataProductRender";
import { PreviewInfo, ScrollCapture } from "../types";

const mod = (modulePath: string): ModuleInfo =>
    ({
        modulePath,
        projectDir: modulePath.replace(/^:/, "").replace(/:/g, "/"),
    }) as ModuleInfo;

function preview(opts: {
    id: string;
    renderOutput?: string;
    dataProducts?: PreviewInfo["dataProducts"];
}): PreviewInfo {
    return {
        id: opts.id,
        functionName: opts.id.split(".").pop()!,
        className: opts.id.substring(0, opts.id.lastIndexOf(".")),
        sourceFile: "src/main/kotlin/Previews.kt",
        params: {
            name: null,
            device: null,
            widthDp: null,
            heightDp: null,
            fontScale: 1,
            showSystemUi: false,
            showBackground: false,
            backgroundColor: 0,
            uiMode: 0,
            locale: null,
            group: null,
        },
        captures: [
            {
                advanceTimeMillis: null,
                scroll: null,
                renderOutput: opts.renderOutput ?? "renders/x.png",
            },
        ],
        dataProducts: opts.dataProducts,
    } as PreviewInfo;
}

const longScroll: ScrollCapture = {
    mode: "LONG",
    axis: "VERTICAL",
    maxScrollPx: 0,
    reduceMotion: false,
    atEnd: false,
    reachedPx: null,
};

const gifScroll: ScrollCapture = {
    mode: "GIF",
    axis: "VERTICAL",
    maxScrollPx: 0,
    reduceMotion: false,
    atEnd: false,
    reachedPx: null,
};

describe("findMissingImageDataProducts", () => {
    it("returns empty when no preview has data products", () => {
        const p = preview({ id: "com.example.Plain" });
        const result = findMissingImageDataProducts(
            [p],
            () => mod(":wear"),
            "/ws",
            () => false,
        );
        assert.strictEqual(result.size, 0);
    });

    it("flags a scroll-long preview when the data-product PNG is missing on disk", () => {
        // Exact repro from the user's bug: daemon writes the static base PNG, panel drops
        // it for scroll-long, scroll-long PNG never lands because no producer ran.
        const p = preview({
            id: "com.example.LongScroll",
            renderOutput: "renders/LongScroll.png",
            dataProducts: [
                {
                    kind: "render/scroll/long",
                    advanceTimeMillis: null,
                    scroll: longScroll,
                    output: "data/render-scroll-long/LongScroll.png",
                },
            ],
        });
        const result = findMissingImageDataProducts(
            [p],
            () => mod(":wear"),
            "/ws",
            // Daemon's static base happens to be on disk — verify must still see the
            // SCROLL PNG as missing (which is what's actually displayed).
            (file) =>
                file ===
                "/ws/wear/build/compose-previews/renders/LongScroll.png",
        );
        assert.strictEqual(result.size, 1);
        const missing = result.get(":wear");
        assert.ok(missing && missing.length === 1);
        assert.deepStrictEqual(missing[0], {
            previewId: "com.example.LongScroll",
            // Scroll-long drops the static base, so its data-product output lands at
            // displayed-capture index 0.
            captureIndex: 0,
            renderOutput: "data/render-scroll-long/LongScroll.png",
            absolutePath:
                "/ws/wear/build/compose-previews/data/render-scroll-long/LongScroll.png",
        });
    });

    it("flags a scroll-gif preview targeting a .gif output", () => {
        // GIF data products carry mediaType image/gif; the panel renders them via
        // `mimeFor` which sniffs the extension. Both .png and .gif extensions count
        // as image data products for the missing-on-disk scan.
        const p = preview({
            id: "com.example.GifScroll",
            dataProducts: [
                {
                    kind: "render/scroll/gif",
                    advanceTimeMillis: null,
                    scroll: gifScroll,
                    output: "data/render-scroll-gif/GifScroll.gif",
                },
            ],
        });
        const result = findMissingImageDataProducts(
            [p],
            () => mod(":wear"),
            "/ws",
            () => false,
        );
        const missing = result.get(":wear");
        assert.ok(missing && missing.length === 1);
        assert.strictEqual(missing[0].captureIndex, 0);
        assert.ok(missing[0].renderOutput.endsWith(".gif"));
    });

    it("skips data products whose file is already on disk", () => {
        const p = preview({
            id: "com.example.ReadyScroll",
            dataProducts: [
                {
                    kind: "render/scroll/long",
                    advanceTimeMillis: null,
                    scroll: longScroll,
                    output: "data/render-scroll-long/Ready.png",
                },
            ],
        });
        const result = findMissingImageDataProducts(
            [p],
            () => mod(":wear"),
            "/ws",
            () => true,
        );
        assert.strictEqual(result.size, 0);
    });

    it("skips non-image data products (a11y JSON outputs)", () => {
        // Only image kinds drive a Gradle re-render. JSON data products (a11y/atf,
        // compose/semantics, etc.) come through the daemon's attach path and would
        // never benefit from a Gradle composePreviewRender.
        const p = preview({
            id: "com.example.A11y",
            dataProducts: [
                {
                    kind: "a11y/hierarchy",
                    advanceTimeMillis: null,
                    scroll: null,
                    output: "data/a11y/hierarchy/X.json",
                },
            ],
        });
        const result = findMissingImageDataProducts(
            [p],
            () => mod(":wear"),
            "/ws",
            () => false,
        );
        assert.strictEqual(result.size, 0);
    });

    it("groups multiple missing products by module so callers can dedup the Gradle render", () => {
        const wearA = preview({
            id: "com.example.A",
            dataProducts: [
                {
                    kind: "render/scroll/long",
                    advanceTimeMillis: null,
                    scroll: longScroll,
                    output: "data/render-scroll-long/A.png",
                },
            ],
        });
        const wearB = preview({
            id: "com.example.B",
            dataProducts: [
                {
                    kind: "render/scroll/gif",
                    advanceTimeMillis: null,
                    scroll: gifScroll,
                    output: "data/render-scroll-gif/B.gif",
                },
            ],
        });
        const androidC = preview({
            id: "com.example.C",
            dataProducts: [
                {
                    kind: "render/scroll/long",
                    advanceTimeMillis: null,
                    scroll: longScroll,
                    output: "data/render-scroll-long/C.png",
                },
            ],
        });
        const result = findMissingImageDataProducts(
            [wearA, wearB, androidC],
            (id) => (id === "com.example.C" ? mod(":android") : mod(":wear")),
            "/ws",
            () => false,
        );
        assert.strictEqual(result.size, 2);
        assert.strictEqual(result.get(":wear")?.length, 2);
        assert.strictEqual(result.get(":android")?.length, 1);
    });
});
