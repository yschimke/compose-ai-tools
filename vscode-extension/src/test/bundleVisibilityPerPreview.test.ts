import * as assert from "assert";
import {
    availableBundleIdsForPreview,
    bundleAppliesToPreview,
    getBundle,
    previewBundleContext,
} from "../webview/preview/bundleRegistry";
import type { Capture, PreviewDataProduct, PreviewInfo } from "../types";

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

function preview(
    paramsOverrides: Partial<PreviewInfo["params"]> = {},
    overrides: Partial<PreviewInfo> = {},
): PreviewInfo {
    return {
        id: "com.example.PreviewsKt.MyPreview",
        functionName: "MyPreview",
        className: "com.example.PreviewsKt",
        sourceFile: null,
        params: { ...baseParams, ...paramsOverrides },
        captures: [baseCapture],
        ...overrides,
    };
}

function lottieProduct(): PreviewDataProduct {
    return {
        kind: "animation/lottie",
        advanceTimeMillis: null,
        scroll: null,
        output: "",
    };
}

const EARLY = { earlyFeatures: true };

describe("availableBundleIdsForPreview (per-preview chip filtering)", () => {
    it("shows the Lottie bundle for a kind=LOTTIE preview and hides Watch", () => {
        const ids = availableBundleIdsForPreview(
            preview({ kind: "LOTTIE" }),
            EARLY,
        );
        assert.ok(ids.includes("lottie"), "lottie chip should show");
        assert.ok(!ids.includes("watch"), "watch chip should be hidden");
    });

    it("shows the Lottie bundle when an animation/lottie product is attached to a COMPOSE preview", () => {
        const p = preview(
            { kind: "COMPOSE" },
            { dataProducts: [lottieProduct()] },
        );
        const ids = availableBundleIdsForPreview(p, EARLY);
        assert.ok(ids.includes("lottie"));
    });

    it("shows the Lottie bundle from a live (post-focus) product kind, not just the manifest field", () => {
        // COMPOSE preview with NO manifest dataProducts, but a live
        // `animation/lottie` attachment threaded through `dataProductKinds`.
        const ids = availableBundleIdsForPreview(preview({ kind: "COMPOSE" }), {
            earlyFeatures: true,
            dataProductKinds: new Set(["animation/lottie"]),
        });
        assert.ok(ids.includes("lottie"));
    });

    it("shows the Watch bundle for a wear-device preview and hides Lottie", () => {
        const ids = availableBundleIdsForPreview(
            preview({ device: "wearos", kind: "COMPOSE" }),
            EARLY,
        );
        assert.ok(ids.includes("watch"), "watch chip should show");
        assert.ok(!ids.includes("lottie"), "lottie chip should be hidden");
    });

    it("detects wear by a small square device size", () => {
        const ids = availableBundleIdsForPreview(
            preview({ widthDp: 227, heightDp: 227 }),
            EARLY,
        );
        assert.ok(ids.includes("watch"));
    });

    it("hides both Lottie and Watch for a plain phone COMPOSE preview, keeps universal bundles", () => {
        const ids = availableBundleIdsForPreview(
            preview({ kind: "COMPOSE", widthDp: 411, heightDp: 891 }),
            EARLY,
        );
        assert.ok(!ids.includes("lottie"));
        assert.ok(!ids.includes("watch"));
        assert.ok(ids.includes("a11y"), "universal a11y bundle still shows");
        assert.ok(
            ids.includes("theming"),
            "universal theming bundle still shows",
        );
    });

    it("hides preview-gated bundles when nothing is focused (null preview)", () => {
        const ids = availableBundleIdsForPreview(null, EARLY);
        assert.ok(!ids.includes("lottie"));
        assert.ok(!ids.includes("watch"));
        assert.ok(ids.includes("a11y"), "universal bundles unaffected by null");
    });

    it("shows only a11y when early features are off, regardless of preview type", () => {
        assert.deepStrictEqual(
            availableBundleIdsForPreview(preview({ kind: "LOTTIE" }), {
                earlyFeatures: false,
            }),
            ["a11y"],
        );
        assert.deepStrictEqual(
            availableBundleIdsForPreview(preview({ device: "wearos" }), {
                earlyFeatures: false,
            }),
            ["a11y"],
        );
    });
});

describe("bundleAppliesToPreview", () => {
    it("treats bundles without an appliesTo predicate as universal", () => {
        const a11y = getBundle("a11y")!;
        assert.strictEqual(bundleAppliesToPreview(a11y, null), true);
        assert.strictEqual(bundleAppliesToPreview(a11y, preview()), true);
    });

    it("gates the lottie bundle on the preview context", () => {
        const lottie = getBundle("lottie")!;
        assert.strictEqual(
            bundleAppliesToPreview(lottie, preview({ kind: "LOTTIE" })),
            true,
        );
        assert.strictEqual(
            bundleAppliesToPreview(lottie, preview({ kind: "COMPOSE" })),
            false,
        );
        assert.strictEqual(bundleAppliesToPreview(lottie, null), false);
    });
});

describe("previewBundleContext", () => {
    it("projects kind, wear, and data-product kinds off a preview", () => {
        const ctx = previewBundleContext(
            preview({ kind: "LOTTIE" }, { dataProducts: [lottieProduct()] }),
        );
        assert.strictEqual(ctx.kind, "LOTTIE");
        assert.strictEqual(ctx.isWear, false);
        assert.ok(ctx.dataProductKinds.has("animation/lottie"));
    });
});
