// Generator for `text-strings.json`. Run with:
//   node preview-harness/fixtures/text-strings.gen.mjs > preview-harness/fixtures/text-strings.json
//
// Demonstrates the Text / i18n bundle end-to-end: the focused card
// paints overflow / truncation overlay boxes from `text/strings`
// entries, the side legend lists each drawn-text node, and the
// bundle tab body renders the strings / fonts / translations
// sub-tables.
//
// Payload shapes mirror `TextStringsPayload` (`data/strings/core/.../
// StringModels.kt`) and the fonts/used + i18n/translations
// structures the daemon emits — enough fields to drive the bundle
// presenter without depending on the live recorder.

import {
    EARLY_FEATURES_DATASET,
    activateBundleAction,
    buildMobileMock,
    buildPreviewPair,
    expectSetDataExtension,
    focusAction,
    forbidSetDataExtensionEnabled,
    rectBounds,
} from "./_utils.mjs";

const mock = buildMobileMock();
const { HEADER, BTN1, BTN2, FOOTER, W, H, png } = mock;

const focusId = "com.example.OnboardingKt.WelcomeScreenPreview";
const { focused, sibling } = buildPreviewPair({
    focusId,
    width: W,
    height: H,
    fnName: "WelcomeScreenPreview",
    file: "Onboarding.kt",
});

// `text/strings` — drawn-text entries with overflow + truncation
// flags so the overlay paints around the offending text bounds.
const textStrings = {
    texts: [
        {
            nodeId: "header-text",
            boundsInScreen: rectBounds(HEADER),
            text: "Welcome to Compose AI Tools",
            semanticsText: "Welcome to Compose AI Tools",
            fontSize: "22.0sp",
            foregroundColor: "#FFFFFFFF",
            backgroundColor: "#FF3C5AA8",
            localeTag: "en-US",
            truncated: false,
            didOverflowWidth: false,
            didOverflowHeight: false,
        },
        {
            nodeId: "primary-cta-text",
            boundsInScreen: rectBounds(BTN1),
            text: "Get started",
            semanticsText: "Get started",
            fontSize: "16.0sp",
            foregroundColor: "#FFFFFFFF",
            backgroundColor: "#FF0E639C",
            localeTag: "en-US",
            truncated: false,
            didOverflowWidth: false,
            didOverflowHeight: false,
        },
        {
            nodeId: "footer-text",
            boundsInScreen: rectBounds(FOOTER),
            text: "By continuing you agree to our terms and conditions of service",
            semanticsText:
                "By continuing you agree to our terms and conditions of service",
            fontSize: "12.0sp",
            foregroundColor: "#FFC8C8C8",
            backgroundColor: "#FFF5F6FA",
            localeTag: "en-US",
            truncated: true,
            didOverflowWidth: true,
            didOverflowHeight: false,
            overflow: "ellipsis",
        },
    ],
};

// `fonts/used` — three resolved families.
const fontsUsed = {
    fonts: [
        {
            requestedFamily: "Inter",
            resolvedFamily: "Inter",
            weight: 400,
            style: "normal",
            sourceFile: "fonts/Inter-Regular.ttf",
            consumerNodeIds: ["header-text", "primary-cta-text"],
        },
        {
            requestedFamily: "Inter",
            resolvedFamily: "Inter",
            weight: 600,
            style: "normal",
            sourceFile: "fonts/Inter-SemiBold.ttf",
            consumerNodeIds: ["header-text"],
        },
        {
            requestedFamily: "SystemFallback",
            resolvedFamily: "Roboto",
            fellBackFrom: ["MissingFont"],
            weight: 400,
            style: "normal",
            consumerNodeIds: ["footer-text"],
        },
    ],
};

// `i18n/translations` — one entry per node so the configure
// expander has translations to render once the user opts in.
const i18nTranslations = {
    translations: [
        {
            nodeId: "header-text",
            key: "onboarding.title",
            sourceLocale: "en-US",
            translations: {
                "en-US": "Welcome to Compose AI Tools",
                "es-ES": "Bienvenido a Compose AI Tools",
                "ja-JP": "Compose AI Tools へようこそ",
            },
        },
        {
            nodeId: "primary-cta-text",
            key: "onboarding.cta.primary",
            sourceLocale: "en-US",
            translations: {
                "en-US": "Get started",
                "es-ES": "Empezar",
                "ja-JP": "始める",
            },
        },
    ],
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
        { kind: "text/strings", payload: textStrings },
        { kind: "fonts/used", payload: fontsUsed },
        { kind: "i18n/translations", payload: i18nTranslations },
    ],
};

const fixture = {
    name: "text-strings",
    description:
        "Focused card with realistic text/strings + fonts/used + i18n/translations payloads. Activates the Text / i18n bundle so the overflow/truncation overlay paints over the footer, the side legend lists each drawn-text entry, and the tab body renders the strings + fonts sub-tables.",
    dataset: EARLY_FEATURES_DATASET,
    messages: [setPreviews, updateImage, updateDataProducts],
    actions: [
        focusAction(focusId),
        activateBundleAction("text"),
        // Click the footer drawn-text row (the truncated / overflow
        // one) so the snapshot exercises the row → detail panel
        // wiring. Row ids are positional in the presenter
        // (`text-string-${idx}`); footer is the third entry.
        {
            click: `[data-bundle="text"] tr[data-legend-id="text-string-2"]`,
        },
    ],
    // The Text / i18n bundle's two default-ON kinds (per
    // `bundleRegistry.ts`) — translations is default-OFF and only
    // arrives via the Configure expander, so it is intentionally
    // not asserted here.
    expectedPosts: [
        expectSetDataExtension(focusId, "text/strings", true),
        expectSetDataExtension(focusId, "fonts/used", true),
    ],
    // `i18n/translations` is default-OFF — chip activation must not
    // subscribe it. The fixture preloads the payload so the
    // Translations sub-table can render once the user opts in via
    // the Configure expander.
    forbiddenPosts: [forbidSetDataExtensionEnabled(focusId, "i18n/translations")],
};

process.stdout.write(JSON.stringify(fixture, null, 2) + "\n");
