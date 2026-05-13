// Theming bundle presenter (#1056 Cluster C). Pins the row shape for
// the Colors / Typography / Shapes sections plus the wallpaper-seed
// prepend behaviour. The presenter is stateless so these tests work
// on plain payload fixtures — no DOM, no daemon round-trip.

import * as assert from "assert";
import {
    computeThemingBundleData,
    type ThemePayload,
    type ThemingColorRow,
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
});
