// Generator for `history-diff.json`. Run with:
//   node preview-harness/fixtures/history-diff.gen.mjs > preview-harness/fixtures/history-diff.json
//
// Demonstrates the History diff bundle end-to-end: the focused card
// paints tinted boxes over each changed region, the side legend
// lists the regions with their pixel-count subtitle, and the bundle
// tab body renders the diff table with the baseline header.
//
// Payload shape mirrors `HistoryDiffPayload` in
// `data/history/core/.../HistoryDiffModels.kt`.

import {
    EARLY_FEATURES_DATASET,
    activateBundleAction,
    buildMobileMock,
    buildPreviewPair,
    focusAction,
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

const totalPixels = W * H;
const region1Pixels = (HEADER.right - HEADER.left) * (HEADER.bottom - HEADER.top);
const region2Pixels = (BTN1.right - BTN1.left) * (BTN1.bottom - BTN1.top);
const region3Pixels = (BTN2.right - BTN2.left) * (BTN2.bottom - BTN2.top);
const changedPixels = region1Pixels + region2Pixels + region3Pixels;

const historyDiff = {
    baselineHistoryId: "main@a1b2c3d4",
    totalPixelsChanged: changedPixels,
    changedFraction: changedPixels / totalPixels,
    regions: [
        {
            bounds: rectBounds(HEADER),
            pixelCount: region1Pixels,
            avgDelta: { r: 12.4, g: 18.2, b: 33.7, a: 0.0 },
        },
        {
            bounds: rectBounds(BTN1),
            pixelCount: region2Pixels,
            avgDelta: { r: 4.1, g: 6.8, b: 8.2, a: 0.0 },
        },
        {
            bounds: rectBounds(BTN2),
            pixelCount: region3Pixels,
            avgDelta: { r: 22.6, g: 24.0, b: 28.9, a: 0.0 },
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
    dataProducts: [{ kind: "history/diff/regions", payload: historyDiff }],
};

const fixture = {
    name: "history-diff",
    description:
        "Focused card with realistic history/diff/regions payload (three regions, ~12% of pixels changed from baseline). Activates the History bundle so the diff overlay paints each region, the side legend lists them with pixel-count subtitles, and the tab body renders the diff table with the baseline header.",
    dataset: EARLY_FEATURES_DATASET,
    messages: [setPreviews, updateImage, updateDataProducts],
    actions: [
        focusAction(focusId),
        activateBundleAction("history"),
        // Click the highest-Δ region (index 2) so the snapshot
        // exercises the row → detail panel wiring. Row ids are
        // positional in the presenter
        // (`history-diff-region-${idx}`).
        {
            click: `[data-bundle="history"] tr[data-legend-id="history-diff-region-2"]`,
        },
    ],
};

process.stdout.write(JSON.stringify(fixture, null, 2) + "\n");
