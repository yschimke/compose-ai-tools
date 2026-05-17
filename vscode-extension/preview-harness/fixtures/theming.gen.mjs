// Generator for `theming.json`. Run with:
//   node preview-harness/fixtures/theming.gen.mjs > preview-harness/fixtures/theming.json
//
// Drives focus mode + the Theming bundle so the rendered tab body
// shows:
//   - Colors section with CSSOM-applied swatches (a regression test
//     for the lit `style=` sanitisation bug — if swatches go back to
//     the empty checkerboard, this fixture snapshots the change).
//   - Typography section with the token name rendered in the resolved
//     Google Fonts family / weight / style.
//   - Shapes section with a 20×20 preview square whose border-radius
//     is parsed from the RoundedCornerShape value string.
//   - Wallpaper kind is preloaded but default-OFF, so the Configure
//     expander has something to opt into.
//
// Payload shapes mirror `ThemePayload` (`data/theme/core/.../
// Material3ThemeModels.kt`) and `WallpaperPayload`
// (`data/wallpaper/core/.../WallpaperModels.kt`) — enough fields to
// drive the bundle presenter without depending on the live recorder.

import {
    EARLY_FEATURES_DATASET,
    activateBundleAction,
    buildMobileMock,
    buildPreviewPair,
    expectSetDataExtension,
    focusAction,
    forbidSetDataExtensionEnabled,
} from "./_utils.mjs";

const mock = buildMobileMock();
const { W, H, png } = mock;

const focusId = "com.example.ThemeShowcaseKt.LightThemePreview";
const { focused, sibling } = buildPreviewPair({
    focusId,
    width: W,
    height: H,
    fnName: "LightThemePreview",
    file: "ThemeShowcase.kt",
});

// `compose/theme` — Material 3 baseline-ish light scheme (the values
// the user's screenshot was showing). Mixing #AARRGGBB and #RRGGBB
// inputs so the cssColor() ARGB-vs-RGB path is exercised.
const themePayload = {
    resolvedTokens: {
        colorScheme: {
            primary: "#FF6750A4",
            onPrimary: "#FFFFFFFF",
            primaryContainer: "#FFEADDFF",
            onPrimaryContainer: "#FF21005D",
            secondary: "#FF625B71",
            onSecondary: "#FFFFFFFF",
            secondaryContainer: "#FFE8DEF8",
            onSecondaryContainer: "#FF1D192B",
            tertiary: "#FF7D5260",
            onTertiary: "#FFFFFFFF",
            tertiaryContainer: "#FFFFD8E4",
            onTertiaryContainer: "#FF31111D",
            error: "#FFB3261E",
            onError: "#FFFFFFFF",
            errorContainer: "#FFF9DEDC",
            onErrorContainer: "#FF410E0B",
            background: "#FFFEF7FF",
            onBackground: "#FF1D1B20",
            surface: "#FFFEF7FF",
            onSurface: "#FF1D1B20",
            surfaceVariant: "#FFE7E0EC",
            onSurfaceVariant: "#FF49454F",
            outline: "#FF79747E",
            outlineVariant: "#FFCAC4D0",
            inverseSurface: "#FF322F35",
            inverseOnSurface: "#FFF5EFF7",
            inversePrimary: "#FFD0BCFF",
            scrim: "#FF000000",
            surfaceTint: "#FF6750A4",
        },
        typography: {
            displayLarge: {
                fontFamily: "FontFamily.SansSerif",
                fontSize: 57,
                fontSizeUnit: "sp",
                fontWeight: "FontWeight(weight=400)",
                fontStyle: "Normal",
                lineHeight: 64,
                lineHeightUnit: "sp",
                letterSpacing: -0.25,
                letterSpacingUnit: "sp",
            },
            displayMedium: {
                fontFamily: "FontFamily.SansSerif",
                fontSize: 45,
                fontSizeUnit: "sp",
                fontWeight: "FontWeight(weight=400)",
                fontStyle: "Normal",
                lineHeight: 52,
                lineHeightUnit: "sp",
                letterSpacing: 0,
                letterSpacingUnit: "sp",
            },
            headlineLarge: {
                fontFamily: "FontFamily.SansSerif",
                fontSize: 32,
                fontSizeUnit: "sp",
                fontWeight: "FontWeight(weight=400)",
                fontStyle: "Normal",
                lineHeight: 40,
                lineHeightUnit: "sp",
                letterSpacing: 0,
                letterSpacingUnit: "sp",
            },
            titleLarge: {
                fontFamily: "FontFamily.SansSerif",
                fontSize: 22,
                fontSizeUnit: "sp",
                fontWeight: "FontWeight(weight=500)",
                fontStyle: "Normal",
                lineHeight: 28,
                lineHeightUnit: "sp",
                letterSpacing: 0,
                letterSpacingUnit: "sp",
            },
            bodyLarge: {
                fontFamily: "FontFamily.SansSerif",
                fontSize: 16,
                fontSizeUnit: "sp",
                fontWeight: "FontWeight(weight=400)",
                fontStyle: "Normal",
                lineHeight: 24,
                lineHeightUnit: "sp",
                letterSpacing: 0.5,
                letterSpacingUnit: "sp",
            },
            labelSmall: {
                fontFamily: "FontFamily.SansSerif",
                fontSize: 11,
                fontSizeUnit: "sp",
                fontWeight: "FontWeight(weight=500)",
                fontStyle: "Normal",
                lineHeight: 16,
                lineHeightUnit: "sp",
                letterSpacing: 0.5,
                letterSpacingUnit: "sp",
            },
            // Italicised serif so the Name column renders the typeface
            // distinctly — exercises both the FontFamily.Serif → Google
            // Fonts stack and the italic CSSOM application.
            displayItalic: {
                fontFamily: "FontFamily.Serif",
                fontSize: 24,
                fontSizeUnit: "sp",
                fontWeight: "FontWeight(weight=700)",
                fontStyle: "Italic",
                lineHeight: 32,
                lineHeightUnit: "sp",
                letterSpacing: 0,
                letterSpacingUnit: "sp",
            },
            // Cursive — the Caveat family in the loaded Google Fonts
            // set. Useful for verifying the cursive branch of
            // `cssFontFamily`.
            displayCursive: {
                fontFamily: "FontFamily.Cursive",
                fontSize: 20,
                fontSizeUnit: "sp",
                fontWeight: "FontWeight(weight=400)",
                fontStyle: "Normal",
                lineHeight: 28,
                lineHeightUnit: "sp",
                letterSpacing: 0,
                letterSpacingUnit: "sp",
            },
        },
        shapes: {
            extraSmall:
                "RoundedCornerShape(topStart = CornerSize(size = 4.0.dp), topEnd = CornerSize(size = 4.0.dp), bottomEnd = CornerSize(size = 4.0.dp), bottomStart = CornerSize(size = 4.0.dp))",
            small: "RoundedCornerShape(8.0.dp)",
            medium: "RoundedCornerShape(12.0.dp)",
            large: "RoundedCornerShape(16.0.dp)",
            // Asymmetric — exercises the four-corner branch of the
            // parser so the preview square's corners differ visibly.
            asymmetric:
                "RoundedCornerShape(topStart = CornerSize(size = 16.0.dp), topEnd = CornerSize(size = 4.0.dp), bottomEnd = CornerSize(size = 16.0.dp), bottomStart = CornerSize(size = 4.0.dp))",
            extraLarge: "RoundedCornerShape(28.0.dp)",
            // Fully rounded — percent form, parsed via the percent
            // branch of `parseBorderRadius`.
            full: "RoundedCornerShape(50)",
        },
    },
    // A couple of consumers so the Consumers column shows a non-zero
    // count for the tokens the mock UI references most.
    consumers: [
        {
            nodeId: "header-container",
            tokens: ["primary", "onPrimary", "titleLarge"],
        },
        {
            nodeId: "primary-button",
            tokens: ["primary", "onPrimary", "labelSmall", "small"],
        },
        {
            nodeId: "body-text",
            tokens: ["onSurface", "bodyLarge"],
        },
    ],
};

// `compose/wallpaper` — preloaded but the kind is default-OFF in the
// Theming bundle's catalog, so the Configure expander has something
// to opt into. Activating the chip alone must NOT subscribe this
// kind — the `forbiddenPosts` assertion below pins that.
const wallpaperPayload = {
    seedColor: "#FF6750A4",
    isDark: false,
    paletteStyle: "TONAL_SPOT",
    contrastLevel: 0,
    derivedColorScheme: {
        primary: "#FF6750A4",
        onPrimary: "#FFFFFFFF",
        secondary: "#FF625B71",
        tertiary: "#FF7D5260",
        background: "#FFFEF7FF",
    },
};

const setPreviews = {
    command: "setPreviews",
    moduleDir: "/workspace/sample-android",
    heavyStaleIds: [],
    previews: [focused, sibling],
};

const updateImage = {
    command: "updateImage",
    previewId: focusId,
    captureIndex: 0,
    imageData: png,
};

const updateDataProducts = {
    command: "updateDataProducts",
    previewId: focusId,
    dataProducts: [
        { kind: "compose/theme", payload: themePayload },
        { kind: "compose/wallpaper", payload: wallpaperPayload },
    ],
};

const fixture = {
    name: "theming",
    description:
        "Focused card with a baseline Material 3 light colour scheme, eight typography tokens (sans / serif-italic / cursive), and six shape tokens. Activates the Theming bundle so the Colors swatches paint via CSSOM, the Typography rows render their token names in the resolved Google Fonts family, and the Shapes column shows the 20×20 corner-radius preview square. Wallpaper payload is preloaded but its kind is default-OFF, so it stays available behind the Configure expander.",
    dataset: EARLY_FEATURES_DATASET,
    messages: [setPreviews, updateImage, updateDataProducts],
    actions: [focusAction(focusId), activateBundleAction("theming")],
    // The Theming bundle's only default-ON kind (per `bundleRegistry.ts`).
    expectedPosts: [expectSetDataExtension(focusId, "compose/theme", true)],
    // `compose/wallpaper` is default-OFF — chip activation must not
    // subscribe it. The payload above is preloaded so the wallpaper
    // section can render once the user opts in via the Configure
    // expander.
    forbiddenPosts: [forbidSetDataExtensionEnabled(focusId, "compose/wallpaper")],
};

process.stdout.write(JSON.stringify(fixture, null, 2) + "\n");
