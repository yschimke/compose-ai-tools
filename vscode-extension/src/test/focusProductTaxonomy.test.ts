import * as assert from "assert";
import {
    MAX_SUGGESTIONS,
    bucketOf,
    bumpMru,
    costOf,
    groupByBucket,
    isWearDevice,
    suggestFor,
    type ProductDescriptor,
} from "../webview/preview/focusProductTaxonomy";
import type { PreviewInfo } from "../types";

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
        id: "com.example.PreviewsKt.Sample",
        functionName: "Sample",
        className: "com.example.PreviewsKt",
        sourceFile: "Previews.kt",
        params: baseParams,
        captures: [
            {
                advanceTimeMillis: null,
                scroll: null,
                renderOutput: "renders/com.example.Sample.png",
            },
        ],
        ...overrides,
    };
}

function descriptor(kind: string): ProductDescriptor {
    return {
        kind,
        label: kind,
        icon: "circle",
        cost: costOf(kind),
        daemonBacked: true,
    };
}

describe("focusProductTaxonomy.bucketOf", () => {
    it("routes a11y/* to accessibility", () => {
        assert.strictEqual(bucketOf("a11y/atf"), "accessibility");
        assert.strictEqual(bucketOf("a11y/hierarchy"), "accessibility");
    });

    it("routes layout/* to layout", () => {
        assert.strictEqual(bucketOf("layout/tree"), "layout");
    });

    it("special-cases compose/recomposition as performance", () => {
        assert.strictEqual(bucketOf("compose/recomposition"), "performance");
    });

    it("special-cases compose/theme as theming", () => {
        assert.strictEqual(bucketOf("compose/theme"), "theming");
    });

    it("routes render/* to performance", () => {
        assert.strictEqual(bucketOf("render/trace"), "performance");
        assert.strictEqual(bucketOf("render/composeAiTrace"), "performance");
    });

    it("routes resources/, fonts/, text/ to resources", () => {
        assert.strictEqual(bucketOf("resources/used"), "resources");
        assert.strictEqual(bucketOf("fonts/used"), "resources");
        assert.strictEqual(bucketOf("text/strings"), "resources");
    });

    it("strips local/ prefix and recurses", () => {
        assert.strictEqual(bucketOf("local/a11y/overlay"), "accessibility");
        assert.strictEqual(bucketOf("local/layout/tree"), "layout");
    });

    it("falls through to 'more' for unknown namespaces", () => {
        assert.strictEqual(bucketOf("future/widget"), "more");
        assert.strictEqual(bucketOf("vendor.foo/bar"), "more");
    });
});

describe("focusProductTaxonomy.costOf", () => {
    it("classifies inspection-only kinds as cheap", () => {
        assert.strictEqual(costOf("compose/theme"), "cheap");
        assert.strictEqual(costOf("text/strings"), "cheap");
        assert.strictEqual(costOf("fonts/used"), "cheap");
        assert.strictEqual(costOf("resources/used"), "cheap");
        assert.strictEqual(costOf("layout/tree"), "cheap");
    });

    it("classifies instrumented and re-render kinds as expensive", () => {
        assert.strictEqual(costOf("compose/recomposition"), "expensive");
        assert.strictEqual(costOf("render/trace"), "expensive");
        assert.strictEqual(costOf("a11y/atf"), "expensive");
    });

    it("treats unknown kinds as expensive (safe default)", () => {
        assert.strictEqual(costOf("future/widget"), "expensive");
    });

    it("trusts the daemon hint for unknown kinds", () => {
        assert.strictEqual(
            costOf("future/widget", { requiresRerender: false }),
            "cheap",
        );
        assert.strictEqual(
            costOf("future/widget", { requiresRerender: true }),
            "expensive",
        );
    });

    it("ignores the daemon hint when the kind is in the cheap allowlist", () => {
        assert.strictEqual(
            costOf("compose/theme", { requiresRerender: true }),
            "cheap",
        );
    });

    it("treats absent or undefined hint as no signal", () => {
        assert.strictEqual(
            costOf("future/widget", { requiresRerender: undefined }),
            "expensive",
        );
        assert.strictEqual(costOf("future/widget", {}), "expensive");
    });
});

describe("focusProductTaxonomy.isWearDevice", () => {
    it("matches obvious Wear strings", () => {
        assert.strictEqual(isWearDevice("id:wearos_small_round"), true);
        assert.strictEqual(isWearDevice("id:wearos_square"), true);
    });

    it("matches watch substring", () => {
        assert.strictEqual(isWearDevice("id:watch_round"), true);
    });

    it("matches spec strings with round=true", () => {
        assert.strictEqual(
            isWearDevice("spec:width=200dp,height=200dp,round=true"),
            true,
        );
    });

    it("rejects phone / null / undefined", () => {
        assert.strictEqual(isWearDevice("id:pixel_5"), false);
        assert.strictEqual(isWearDevice(null), false);
        assert.strictEqual(isWearDevice(undefined), false);
        assert.strictEqual(
            isWearDevice("spec:width=200dp,height=200dp"),
            false,
        );
    });
});

describe("focusProductTaxonomy.suggestFor", () => {
    const allKinds = new Set([
        "compose/recomposition",
        "compose/theme",
        "local/a11y/overlay",
        "layout/tree",
        "fonts/used",
    ]);

    it("suggests recomposition for Wear devices", () => {
        const out = suggestFor({
            preview: makePreview({
                params: { ...baseParams, device: "id:wearos_small_round" },
            }),
            findingsCount: 0,
            mru: [],
            available: allKinds,
        });
        assert.ok(out.includes("compose/recomposition"));
    });

    it("suggests recomposition when a scroll capture is present", () => {
        const out = suggestFor({
            preview: makePreview({
                captures: [
                    {
                        advanceTimeMillis: null,
                        scroll: {
                            mode: "LONG",
                            axis: "VERTICAL",
                            maxScrollPx: 1000,
                            reduceMotion: false,
                            atEnd: false,
                            reachedPx: null,
                        },
                        renderOutput: "renders/scroll.png",
                    },
                ],
            }),
            findingsCount: 0,
            mru: [],
            available: allKinds,
        });
        assert.ok(out.includes("compose/recomposition"));
    });

    it("suggests a11y overlay when findings exist", () => {
        const out = suggestFor({
            preview: makePreview(),
            findingsCount: 3,
            mru: [],
            available: allKinds,
        });
        assert.strictEqual(out[0], "local/a11y/overlay");
    });

    it("suggests theme tokens for non-default UI mode", () => {
        const out = suggestFor({
            preview: makePreview({
                params: { ...baseParams, uiMode: 0x21 },
            }),
            findingsCount: 0,
            mru: [],
            available: allKinds,
        });
        assert.ok(out.includes("compose/theme"));
    });

    it("falls back to MRU when annotations don't trigger", () => {
        const out = suggestFor({
            preview: makePreview(),
            findingsCount: 0,
            mru: ["fonts/used", "compose/theme"],
            available: allKinds,
        });
        assert.ok(
            out.indexOf("fonts/used") < out.indexOf("local/a11y/overlay"),
        );
    });

    it("drops suggestions that aren't in the available set", () => {
        const out = suggestFor({
            preview: makePreview({
                params: { ...baseParams, device: "id:wearos_small_round" },
            }),
            findingsCount: 0,
            mru: [],
            available: new Set(["compose/theme"]),
        });
        assert.ok(!out.includes("compose/recomposition"));
    });

    it("caps suggestions at MAX_SUGGESTIONS", () => {
        const out = suggestFor({
            preview: makePreview(),
            findingsCount: 1,
            mru: [
                "compose/recomposition",
                "compose/theme",
                "fonts/used",
                "layout/tree",
            ],
            available: allKinds,
        });
        assert.ok(out.length <= MAX_SUGGESTIONS);
    });

    it("dedups when MRU and annotation hint overlap", () => {
        const out = suggestFor({
            preview: makePreview({
                params: { ...baseParams, uiMode: 0x21 },
            }),
            findingsCount: 0,
            mru: ["compose/theme"],
            available: allKinds,
        });
        const themeOccurrences = out.filter(
            (k) => k === "compose/theme",
        ).length;
        assert.strictEqual(themeOccurrences, 1);
    });
});

describe("focusProductTaxonomy.groupByBucket", () => {
    it("returns all five buckets plus more, in stable order", () => {
        const result = groupByBucket([]);
        assert.deepStrictEqual(
            [...result.keys()],
            [
                "accessibility",
                "layout",
                "performance",
                "theming",
                "resources",
                "more",
            ],
        );
    });

    it("places products into the right bucket and preserves input order", () => {
        const products = [
            descriptor("compose/theme"),
            descriptor("a11y/atf"),
            descriptor("a11y/hierarchy"),
            descriptor("future/widget"),
        ];
        const grouped = groupByBucket(products);
        assert.deepStrictEqual(
            grouped.get("accessibility")?.map((p) => p.kind),
            ["a11y/atf", "a11y/hierarchy"],
        );
        assert.deepStrictEqual(
            grouped.get("theming")?.map((p) => p.kind),
            ["compose/theme"],
        );
        assert.deepStrictEqual(
            grouped.get("more")?.map((p) => p.kind),
            ["future/widget"],
        );
    });
});

describe("focusProductTaxonomy.bumpMru", () => {
    it("moves the kind to the front", () => {
        assert.deepStrictEqual(bumpMru(["a", "b", "c"], "c"), ["c", "a", "b"]);
    });

    it("inserts when the kind is new", () => {
        assert.deepStrictEqual(bumpMru(["a", "b"], "c"), ["c", "a", "b"]);
    });

    it("dedups instead of duplicating", () => {
        assert.deepStrictEqual(bumpMru(["a", "b", "a"], "a"), ["a", "b"]);
    });

    it("trims to maxLen", () => {
        assert.deepStrictEqual(bumpMru(["a", "b", "c"], "d", 2), ["d", "a"]);
    });
});
