// Theming bundle presenter (#1056 Cluster C). Pins the row shape for
// the Colors / Typography / Shapes sections plus the wallpaper-seed
// prepend behaviour. The presenter is stateless so these tests work
// on plain payload fixtures — no DOM, no daemon round-trip.

import * as assert from "assert";
import {
    computeThemingBundleData,
    cssFontFamily,
    parseFontWeight,
    parseShapeBorderRadius,
    type ThemePayload,
    type ThemingColorRow,
    type ThemingShapeRow,
    type ThemingTypographyRow,
    type ThemingSeedRow,
    type WallpaperPayload,
} from "../webview/preview/themingBundlePresenter";

function theme(overrides?: Partial<ThemePayload>): ThemePayload {
    return {
        resolvedTokens: overrides?.resolvedTokens ?? {
            colorScheme: {},
            typography: {},
            shapes: {},
        },
        consumers: overrides?.consumers ?? [],
    };
}

function wallpaper(overrides?: Partial<WallpaperPayload>): WallpaperPayload {
    return {
        seedColor: "#FF8B5CF6",
        isDark: false,
        paletteStyle: "TONAL_SPOT",
        contrastLevel: 0,
        derivedColorScheme: {
            primary: "#FF3700B3",
            onPrimary: "#FFFFFFFF",
        },
        ...overrides,
    };
}

describe("computeThemingBundleData", () => {
    it("returns no rows when both payloads are null", () => {
        const data = computeThemingBundleData(null, null, "preview-1");
        assert.strictEqual(data.rows.length, 0);
        assert.strictEqual(data.jsonPayload.previewId, "preview-1");
        assert.strictEqual(data.jsonPayload.theme, null);
        assert.strictEqual(data.jsonPayload.wallpaper, null);
    });

    it("round-trips a typography token's scalar attributes", () => {
        const data = computeThemingBundleData(
            theme({
                resolvedTokens: {
                    colorScheme: {},
                    typography: {
                        bodyLarge: {
                            fontFamily: "Roboto",
                            fontSize: 16,
                            fontSizeUnit: "sp",
                            fontWeight: "Normal",
                            fontStyle: "Normal",
                            lineHeight: 24,
                            lineHeightUnit: "sp",
                            letterSpacing: 0.5,
                            letterSpacingUnit: "sp",
                        },
                    },
                    shapes: {},
                },
            }),
            null,
        );
        const typo = data.rows.find(
            (r) => r.kind === "typography",
        ) as ThemingTypographyRow;
        assert.ok(typo, "bodyLarge typography row should be present");
        assert.strictEqual(typo.name, "bodyLarge");
        assert.strictEqual(typo.family, "Roboto");
        assert.strictEqual(typo.size, "16sp");
        assert.strictEqual(typo.weight, "Normal");
        assert.strictEqual(typo.style, "Normal");
        assert.strictEqual(typo.lineHeight, "24sp");
        assert.strictEqual(typo.letterSpacing, "0.5sp");
    });

    it("formats null typography attributes as em dashes", () => {
        const data = computeThemingBundleData(
            theme({
                resolvedTokens: {
                    colorScheme: {},
                    typography: {
                        sparse: {
                            fontFamily: null,
                            fontSize: null,
                            fontWeight: null,
                        },
                    },
                    shapes: {},
                },
            }),
            null,
        );
        const typo = data.rows[0] as ThemingTypographyRow;
        assert.strictEqual(typo.family, "—");
        assert.strictEqual(typo.size, "—");
        assert.strictEqual(typo.weight, "—");
        assert.strictEqual(typo.lineHeight, "—");
    });

    it("surfaces consumer counts on color rows from compose/theme", () => {
        const data = computeThemingBundleData(
            theme({
                resolvedTokens: {
                    colorScheme: {
                        primary: "#FF1976D2",
                        secondary: "#FF03DAC6",
                    },
                    typography: {},
                    shapes: {},
                },
                consumers: [
                    { nodeId: "n1", tokens: ["primary"] },
                    { nodeId: "n2", tokens: ["primary", "secondary"] },
                    { nodeId: "n3", tokens: ["primary"] },
                ],
            }),
            null,
        );
        const primary = data.rows.find(
            (r) => r.kind === "color" && r.name === "primary",
        ) as ThemingColorRow;
        const secondary = data.rows.find(
            (r) => r.kind === "color" && r.name === "secondary",
        ) as ThemingColorRow;
        assert.strictEqual(primary.consumerCount, 3);
        assert.strictEqual(secondary.consumerCount, 1);
        assert.strictEqual(primary.source, "theme");
    });

    it("prepends a Seed row when wallpaper is present alongside theme", () => {
        const data = computeThemingBundleData(
            theme({
                resolvedTokens: {
                    colorScheme: { primary: "#FF1976D2" },
                    typography: {},
                    shapes: {},
                },
            }),
            wallpaper({
                seedColor: "#FF8B5CF6",
                isDark: true,
                paletteStyle: "VIBRANT",
                contrastLevel: 0.5,
                derivedColorScheme: { primary: "#FFAB47BC" },
            }),
        );
        // First row must be the seed summary so the user reads top to
        // bottom: "this seed produced this scheme, layered into these
        // theme tokens."
        const first = data.rows[0] as ThemingSeedRow;
        assert.strictEqual(first.kind, "seed");
        assert.strictEqual(first.section, "Colors");
        assert.strictEqual(first.hex, "#FF8B5CF6");
        assert.strictEqual(first.isDark, true);
        assert.strictEqual(first.paletteStyle, "VIBRANT");
        assert.strictEqual(first.contrastLevel, 0.5);
        // Wallpaper-derived colour rows come next, tagged with the
        // wallpaper source so the UI can badge them separately.
        const wallpaperColors = data.rows.filter(
            (r) => r.kind === "color" && r.source === "wallpaper",
        );
        assert.strictEqual(wallpaperColors.length, 1);
        assert.strictEqual(
            (wallpaperColors[0] as ThemingColorRow).name,
            "primary",
        );
        // Theme colours follow with the `theme` source tag.
        const themeColors = data.rows.filter(
            (r) => r.kind === "color" && r.source === "theme",
        );
        assert.strictEqual(themeColors.length, 1);
    });

    it("omits the seed row entirely when wallpaper is null", () => {
        const data = computeThemingBundleData(
            theme({
                resolvedTokens: {
                    colorScheme: { primary: "#FF1976D2" },
                    typography: {},
                    shapes: {},
                },
            }),
            null,
        );
        assert.ok(!data.rows.some((r) => r.kind === "seed"));
        assert.ok(
            !data.rows.some(
                (r) => r.kind === "color" && r.source === "wallpaper",
            ),
        );
    });

    it("converts #AARRGGBB hex into a CSS-safe rgba swatch", () => {
        const data = computeThemingBundleData(
            theme({
                resolvedTokens: {
                    colorScheme: { primary: "#FF1976D2" },
                    typography: {},
                    shapes: {},
                },
            }),
            null,
        );
        const color = data.rows[0] as ThemingColorRow;
        // CSS doesn't parse `#AARRGGBB` — alpha lives at the end in CSS.
        // The presenter has to translate or the swatch shows transparent.
        assert.ok(color.swatchCss.startsWith("rgba("));
        assert.ok(color.swatchCss.includes("25, 118, 210"));
    });

    it("emits shape rows in name-sorted order", () => {
        const data = computeThemingBundleData(
            theme({
                resolvedTokens: {
                    colorScheme: {},
                    typography: {},
                    shapes: {
                        large: "RoundedCornerShape(16.dp)",
                        small: "RoundedCornerShape(4.dp)",
                        medium: "RoundedCornerShape(8.dp)",
                    },
                },
            }),
            null,
        );
        const shapeNames = data.rows
            .filter((r) => r.kind === "shape")
            .map((r) => r.name);
        assert.deepStrictEqual(shapeNames, ["large", "medium", "small"]);
    });

    it("renders the derived scheme even when theme is null", () => {
        // Wallpaper override can land before compose/theme on slow
        // boots — surface what we have rather than blanking the tab.
        const data = computeThemingBundleData(null, wallpaper());
        assert.ok(data.rows.some((r) => r.kind === "seed"));
        const derived = data.rows.filter(
            (r) => r.kind === "color" && r.source === "wallpaper",
        );
        assert.strictEqual(derived.length, 2);
    });

    it("derives CSS font + weight + style for typography rows", () => {
        const data = computeThemingBundleData(
            theme({
                resolvedTokens: {
                    colorScheme: {},
                    typography: {
                        displayLarge: {
                            fontFamily: "FontFamily.SansSerif",
                            fontWeight: "FontWeight(weight=500)",
                            fontStyle: "Italic",
                        },
                    },
                    shapes: {},
                },
            }),
            null,
        );
        const typo = data.rows[0] as ThemingTypographyRow;
        // Sans-serif maps to Roboto so the Google-Fonts-loaded family
        // is the one the swatch paints with.
        assert.ok(typo.cssFontFamily.includes("Roboto"));
        assert.strictEqual(typo.cssFontWeight, 500);
        assert.strictEqual(typo.cssFontStyle, "italic");
    });

    it("emits a CSS border-radius for shape rows when parseable", () => {
        const data = computeThemingBundleData(
            theme({
                resolvedTokens: {
                    colorScheme: {},
                    typography: {},
                    shapes: {
                        large: "RoundedCornerShape(16.dp)",
                        asymmetric:
                            "RoundedCornerShape(topStart = CornerSize(size = 4.0.dp), topEnd = CornerSize(size = 12.0.dp), bottomEnd = CornerSize(size = 12.0.dp), bottomStart = CornerSize(size = 4.0.dp))",
                    },
                },
            }),
            null,
        );
        const byName = (n: string) =>
            data.rows.find(
                (r) => r.kind === "shape" && r.name === n,
            ) as ThemingShapeRow;
        assert.strictEqual(byName("large").previewBorderRadius, "16px");
        assert.strictEqual(
            byName("asymmetric").previewBorderRadius,
            "4px 12px 12px 4px",
        );
    });
});

describe("cssFontFamily", () => {
    it("maps generic Compose families onto Google-Fonts stacks", () => {
        assert.ok(cssFontFamily("FontFamily.SansSerif").includes("Roboto"));
        assert.ok(cssFontFamily("FontFamily.Serif").includes("Roboto Serif"));
        assert.ok(
            cssFontFamily("FontFamily.Monospace").includes("Roboto Mono"),
        );
        assert.ok(cssFontFamily("FontFamily.Cursive").includes("Caveat"));
    });
    it("falls back to the system font when the family is missing", () => {
        assert.strictEqual(cssFontFamily(null), "var(--vscode-font-family)");
    });
});

describe("parseFontWeight", () => {
    it("extracts the numeric weight from `FontWeight(weight=N)`", () => {
        assert.strictEqual(parseFontWeight("FontWeight(weight=400)"), 400);
        assert.strictEqual(parseFontWeight("FontWeight(weight=700)"), 700);
    });
    it("maps named weights onto CSS numerics", () => {
        assert.strictEqual(parseFontWeight("Bold"), 700);
        assert.strictEqual(parseFontWeight("Medium"), 500);
    });
    it("returns null for unparseable input", () => {
        assert.strictEqual(parseFontWeight("—"), null);
        assert.strictEqual(parseFontWeight(null), null);
    });
});

describe("parseShapeBorderRadius", () => {
    it("handles the single-value `RoundedCornerShape(N.dp)` shape", () => {
        assert.strictEqual(
            parseShapeBorderRadius("RoundedCornerShape(8.dp)"),
            "8px",
        );
    });
    it("preserves CSS-percentage shorthand", () => {
        assert.strictEqual(
            parseShapeBorderRadius("RoundedCornerShape(50%)"),
            "50%",
        );
    });
    it("returns null for CutCornerShape (CSS can't bevel)", () => {
        assert.strictEqual(
            parseShapeBorderRadius("CutCornerShape(4.dp)"),
            null,
        );
    });
    it("returns null for shapes outside our parser's scope", () => {
        assert.strictEqual(parseShapeBorderRadius("GenericShape(...)"), null);
        assert.strictEqual(parseShapeBorderRadius(null), null);
    });
});
