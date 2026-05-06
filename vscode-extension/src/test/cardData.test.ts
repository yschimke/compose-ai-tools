import * as assert from "assert";
import {
    buildTooltip,
    buildVariantLabel,
    isAnimatedPreview,
    isWearPreview,
    mimeFor,
    parseBounds,
    sanitizeId,
    shortDevice,
} from "../webview/preview/cardData";
import type { Capture, PreviewInfo } from "../types";

const baseCapture: Capture = {
    advanceTimeMillis: null,
    scroll: null,
    renderOutput: "x.png",
};

const baseParams: PreviewInfo["params"] = {
    name: null,
    device: null,
    widthDp: null,
    heightDp: null,
    fontScale: 1.0,
    showSystemUi: false,
    showBackground: false,
    backgroundColor: 0,
    uiMode: 0,
    locale: null,
    group: null,
};

function preview(overrides: Partial<PreviewInfo> = {}): PreviewInfo {
    return {
        id: "com.example.PreviewsKt.MyPreview",
        functionName: "MyPreview",
        className: "com.example.PreviewsKt",
        sourceFile: null,
        params: baseParams,
        captures: [baseCapture],
        ...overrides,
    };
}

describe("sanitizeId", () => {
    it("preserves alphanumerics, dash, underscore", () => {
        assert.strictEqual(sanitizeId("Foo-Bar_1"), "Foo-Bar_1");
    });

    it("replaces dots and other characters with underscore", () => {
        assert.strictEqual(
            sanitizeId("com.example.PreviewsKt-Foo_1"),
            "com_example_PreviewsKt-Foo_1",
        );
        assert.strictEqual(sanitizeId("a/b c$d"), "a_b_c_d");
    });

    it("handles empty string", () => {
        assert.strictEqual(sanitizeId(""), "");
    });
});

describe("mimeFor", () => {
    it("returns image/gif for .gif", () => {
        assert.strictEqual(mimeFor("foo.gif"), "image/gif");
        assert.strictEqual(mimeFor("FOO.GIF"), "image/gif");
    });

    it("defaults to image/png for everything else", () => {
        assert.strictEqual(mimeFor("plain.png"), "image/png");
        assert.strictEqual(mimeFor("scrolling.webp"), "image/png");
        assert.strictEqual(mimeFor("noext"), "image/png");
        assert.strictEqual(mimeFor(null), "image/png");
        assert.strictEqual(mimeFor(undefined), "image/png");
        assert.strictEqual(mimeFor(""), "image/png");
    });
});

describe("isAnimatedPreview", () => {
    it("is false for a single-capture static preview", () => {
        assert.strictEqual(isAnimatedPreview(preview()), false);
    });

    it("is true when there is more than one capture", () => {
        assert.strictEqual(
            isAnimatedPreview(
                preview({
                    captures: [
                        baseCapture,
                        { ...baseCapture, advanceTimeMillis: 500 },
                    ],
                }),
            ),
            true,
        );
    });

    it("is true when a single capture carries advanceTimeMillis", () => {
        assert.strictEqual(
            isAnimatedPreview(
                preview({
                    captures: [{ ...baseCapture, advanceTimeMillis: 250 }],
                }),
            ),
            true,
        );
    });
});

describe("isWearPreview", () => {
    it("is true when device starts with wearos_", () => {
        assert.strictEqual(
            isWearPreview(
                preview({
                    params: { ...baseParams, device: "wearos_large_round" },
                }),
            ),
            true,
        );
    });

    it("is false for a phone device", () => {
        assert.strictEqual(
            isWearPreview(
                preview({ params: { ...baseParams, device: "id:pixel_5" } }),
            ),
            false,
        );
    });

    it("is false when device is null", () => {
        assert.strictEqual(isWearPreview(preview()), false);
    });
});

describe("shortDevice", () => {
    it("strips an `id:` prefix and converts underscores to spaces", () => {
        assert.strictEqual(shortDevice("id:pixel_5"), "pixel 5");
        assert.strictEqual(
            shortDevice("wearos_large_round"),
            "wearos large round",
        );
    });

    it("returns empty string for null / undefined", () => {
        assert.strictEqual(shortDevice(null), "");
        assert.strictEqual(shortDevice(undefined), "");
    });
});

describe("buildVariantLabel", () => {
    it("is empty for a vanilla preview", () => {
        assert.strictEqual(buildVariantLabel(preview()), "");
    });

    it("includes the device short-name", () => {
        assert.strictEqual(
            buildVariantLabel(
                preview({ params: { ...baseParams, device: "id:pixel_5" } }),
            ),
            "pixel 5",
        );
    });

    it("joins primary · WxH · font · uiMode · locale", () => {
        const label = buildVariantLabel(
            preview({
                params: {
                    ...baseParams,
                    device: "id:pixel_5",
                    widthDp: 360,
                    heightDp: 640,
                    fontScale: 1.5,
                    locale: "fr-FR",
                    uiMode: 33, // night-yes (0x21)
                },
            }),
        );
        assert.strictEqual(
            label,
            "pixel 5 · 360×640 · 1.5× · uiMode 33 · fr-FR",
        );
    });

    it("skips redundant `1.0×` font when fontScale is the default", () => {
        const label = buildVariantLabel(
            preview({
                params: {
                    ...baseParams,
                    fontScale: 1.0,
                    widthDp: 100,
                    heightDp: 200,
                },
            }),
        );
        assert.strictEqual(label, "100×200");
    });

    it("prefers params.name over device for the primary slot", () => {
        const label = buildVariantLabel(
            preview({
                params: {
                    ...baseParams,
                    name: "Light theme",
                    device: "id:pixel_5",
                },
            }),
        );
        assert.strictEqual(label, "Light theme");
    });
});

describe("buildTooltip", () => {
    it("starts with `Open source: <FQN>`", () => {
        assert.match(
            buildTooltip(preview()),
            /^Open source: com\.example\.PreviewsKt\.MyPreview/,
        );
    });

    it("appends params after a `\\n`", () => {
        const tip = buildTooltip(
            preview({
                params: {
                    ...baseParams,
                    device: "id:pixel_5",
                    widthDp: 360,
                    heightDp: 640,
                },
            }),
        );
        assert.strictEqual(
            tip,
            "Open source: com.example.PreviewsKt.MyPreview\\n" +
                "id:pixel_5 · 360×640dp",
        );
    });

    it("includes group when set", () => {
        const tip = buildTooltip(
            preview({ params: { ...baseParams, group: "Light" } }),
        );
        assert.match(tip, /group: Light$/);
    });
});

describe("parseBounds", () => {
    it("parses 4-comma-separated integers", () => {
        assert.deepStrictEqual(parseBounds("0,0,100,200"), {
            left: 0,
            top: 0,
            right: 100,
            bottom: 200,
        });
    });

    it("trims whitespace around each component", () => {
        assert.deepStrictEqual(parseBounds(" 10 , 20 , 30 , 40 "), {
            left: 10,
            top: 20,
            right: 30,
            bottom: 40,
        });
    });

    it("returns null for malformed input", () => {
        assert.strictEqual(parseBounds("0,0,100"), null);
        assert.strictEqual(parseBounds("0,0,100,abc"), null);
        assert.strictEqual(parseBounds(""), null);
        assert.strictEqual(parseBounds(null), null);
        assert.strictEqual(parseBounds(undefined), null);
    });
});
