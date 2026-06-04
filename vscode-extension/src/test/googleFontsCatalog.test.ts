// Pins the Google Fonts catalog parser + search + css2 URL builders.

import * as assert from "assert";
import {
    buildCss2BrowseUrl,
    buildCss2DownloadUrl,
    css2FamilyToken,
    parseFontsMetadata,
    searchCatalog,
    sortAxisTags,
    stripXssiPrefix,
    type FontFamilyMeta,
} from "../googleFontsCatalog";

const SAMPLE = {
    axisRegistry: [
        {
            tag: "wght",
            displayName: "Weight",
            min: 1,
            max: 1000,
            defaultValue: 400,
        },
        {
            tag: "opsz",
            displayName: "Optical Size",
            min: 6,
            max: 144,
            defaultValue: 14,
        },
        {
            tag: "GRAD",
            displayName: "Grade",
            min: -200,
            max: 150,
            defaultValue: 0,
        },
    ],
    familyMetadataList: [
        {
            family: "Roboto",
            category: "Sans Serif",
            subsets: ["latin", "cyrillic"],
            popularity: 1,
            fonts: { "100": {}, "400": {}, "400italic": {}, "700": {} },
            axes: [],
        },
        {
            family: "Roboto Flex",
            category: "Sans Serif",
            subsets: ["latin"],
            popularity: 50,
            fonts: { "400": {} },
            axes: [
                { tag: "wght", min: 100, max: 1000, defaultValue: 400 },
                { tag: "opsz", min: 8, max: 144, defaultValue: 14 },
                { tag: "GRAD", min: -200, max: 150, defaultValue: 0 },
            ],
        },
        {
            family: "Lora",
            category: "Serif",
            subsets: ["latin"],
            popularity: 20,
            fonts: { "400": {}, "700": {} },
            axes: [],
        },
    ],
};

describe("googleFontsCatalog", () => {
    it("strips the XSSI guard before the first brace", () => {
        assert.strictEqual(stripXssiPrefix(')]}\'\n{"a":1}'), '{"a":1}');
        assert.strictEqual(stripXssiPrefix('{"a":1}'), '{"a":1}');
    });

    it("parses families, instances, axes and sorts by popularity", () => {
        const catalog = parseFontsMetadata(SAMPLE);
        assert.deepStrictEqual(
            catalog.families.map((f) => f.family),
            ["Roboto", "Lora", "Roboto Flex"],
        );
        assert.deepStrictEqual(catalog.categories, ["Sans Serif", "Serif"]);

        const roboto = catalog.families.find((f) => f.family === "Roboto")!;
        assert.strictEqual(roboto.isVariable, false);
        assert.strictEqual(roboto.hasItalic, true);
        assert.deepStrictEqual(roboto.weights, [100, 400, 700]);

        const flex = catalog.families.find((f) => f.family === "Roboto Flex")!;
        assert.strictEqual(flex.isVariable, true);
        assert.strictEqual(flex.axes.length, 3);
        const opsz = flex.axes.find((a) => a.tag === "opsz")!;
        assert.strictEqual(opsz.displayName, "Optical Size");
        assert.strictEqual(opsz.max, 144);
    });

    it("drops malformed families without throwing", () => {
        const catalog = parseFontsMetadata({
            familyMetadataList: [
                null,
                { category: "Serif" }, // no family
                { family: "Good", fonts: { regular: {}, italic: {} } },
            ],
        });
        assert.deepStrictEqual(
            catalog.families.map((f) => f.family),
            ["Good"],
        );
        const good = catalog.families[0];
        assert.strictEqual(good.hasItalic, true);
        assert.deepStrictEqual(good.weights, [400]);
    });

    it("filters by query and category and caps results", () => {
        const catalog = parseFontsMetadata(SAMPLE);
        assert.deepStrictEqual(
            searchCatalog(catalog, { query: "roboto" }).map((f) => f.family),
            ["Roboto", "Roboto Flex"],
        );
        assert.deepStrictEqual(
            searchCatalog(catalog, { category: "Serif" }).map((f) => f.family),
            ["Lora"],
        );
        assert.strictEqual(searchCatalog(catalog, { limit: 1 }).length, 1);
    });

    it("encodes family tokens and sorts axis tags css2-style", () => {
        assert.strictEqual(css2FamilyToken("Open Sans"), "Open+Sans");
        assert.deepStrictEqual(sortAxisTags(["wght", "GRAD", "opsz", "ital"]), [
            "ital",
            "opsz",
            "wght",
            "GRAD",
        ]);
    });

    it("builds a browse URL for the default upright face", () => {
        assert.strictEqual(
            buildCss2BrowseUrl("Open Sans"),
            "https://fonts.googleapis.com/css2?family=Open+Sans&display=swap",
        );
    });

    it("builds a static download URL enumerating instances", () => {
        const roboto = parseFontsMetadata(SAMPLE).families.find(
            (f) => f.family === "Roboto",
        )!;
        assert.strictEqual(
            buildCss2DownloadUrl(roboto),
            "https://fonts.googleapis.com/css2?family=Roboto:ital,wght@0,100;0,400;0,700;1,400&display=swap",
        );
    });

    it("builds a variable download URL with full axis ranges", () => {
        const flex = parseFontsMetadata(SAMPLE).families.find(
            (f) => f.family === "Roboto Flex",
        )!;
        // ital absent (no italic instances), axes sorted opsz,wght,GRAD.
        assert.strictEqual(
            buildCss2DownloadUrl(flex),
            "https://fonts.googleapis.com/css2?family=Roboto+Flex:opsz,wght,GRAD@8..144,100..1000,-200..150&display=swap",
        );
    });

    it("includes both ital tuples for a variable font with italics", () => {
        const meta: FontFamilyMeta = {
            family: "Test VF",
            category: "Sans Serif",
            subsets: ["latin"],
            popularity: 1,
            axes: [
                {
                    tag: "wght",
                    displayName: "Weight",
                    min: 100,
                    max: 900,
                    defaultValue: 400,
                },
            ],
            instances: [
                { weight: 400, italic: false },
                { weight: 400, italic: true },
            ],
            weights: [400],
            hasItalic: true,
            isVariable: true,
        };
        assert.strictEqual(
            buildCss2DownloadUrl(meta),
            "https://fonts.googleapis.com/css2?family=Test+VF:ital,wght@0,100..900;1,100..900&display=swap",
        );
    });
});
