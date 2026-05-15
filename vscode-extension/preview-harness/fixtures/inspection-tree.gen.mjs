// Generator for `inspection-tree.json`. Run with:
//   node preview-harness/fixtures/inspection-tree.gen.mjs > preview-harness/fixtures/inspection-tree.json
//
// Demonstrates the Inspection bundle (semantics + layout-inspector +
// uia) end-to-end: tinted overlay on the focused card, side
// `<bundle-legend>` populated from the merged overlay, and the
// inspection tab body rendering its tree-table sections per
// enabled kind.
//
// Payload shape mirrors the structures in
// `data/layoutinspector/core/.../ComposeSemanticsModels.kt` and
// `data/uiautomator/core/.../UiAutomatorHierarchyModels.kt`. Bounds
// align with the buildMobileMock() regions so the overlay boxes
// visibly cover the header / buttons / footer.

import {
    EARLY_FEATURES_DATASET,
    activateBundleAction,
    buildMobileMock,
    buildPreviewPair,
    focusAction,
    rectBounds,
} from "./_utils.mjs";

const mock = buildMobileMock();
const { HEADER, BTN1, BTN2, FOOTER, ROOT, W, H, png } = mock;

const focusId = "com.example.OnboardingKt.WelcomeScreenPreview";
const { focused, sibling } = buildPreviewPair({
    focusId,
    width: W,
    height: H,
    fnName: "WelcomeScreenPreview",
    file: "Onboarding.kt",
});

// `compose/semantics` — recursive tree rooted on the full preview.
const semantics = {
    root: {
        nodeId: "root",
        boundsInRoot: rectBounds(ROOT),
        role: "Container",
        testTag: "onboarding_screen",
        children: [
            {
                nodeId: "header",
                boundsInRoot: rectBounds(HEADER),
                text: "Welcome to Compose AI Tools",
                role: "Header",
                testTag: "onboarding_header",
            },
            {
                nodeId: "primary-cta",
                boundsInRoot: rectBounds(BTN1),
                label: "Get started",
                role: "Button",
                testTag: "primary_cta",
            },
            {
                nodeId: "secondary-cta",
                boundsInRoot: rectBounds(BTN2),
                role: "Button",
                testTag: "icon_cta",
            },
            {
                nodeId: "footer",
                boundsInRoot: rectBounds(FOOTER),
                text: "By continuing you agree to our terms",
                role: "Text",
                testTag: "terms_footer",
            },
        ],
    },
};

// `layout/inspector` — flat node array referencing the same bounds
// so the merged overlay de-dupes across kinds (the bundle's
// `overlay` field carries the de-duped set; the legend renders
// from that).
const layoutInspector = {
    root: {
        nodeId: "layout-root",
        component: "OnboardingScreen",
        source: "Onboarding.kt:42",
        bounds: { left: 0, top: 0, right: W, bottom: H },
        size: { width: W, height: H },
        children: [
            {
                nodeId: "layout-column",
                component: "Column",
                source: "Onboarding.kt:48",
                bounds: { left: 0, top: 0, right: W, bottom: H },
                size: { width: W, height: H },
                children: [
                    {
                        nodeId: "layout-header",
                        component: "Surface",
                        source: "Onboarding.kt:56",
                        bounds: {
                            left: HEADER.left,
                            top: HEADER.top,
                            right: HEADER.right,
                            bottom: HEADER.bottom,
                        },
                        size: {
                            width: HEADER.right - HEADER.left,
                            height: HEADER.bottom - HEADER.top,
                        },
                    },
                    {
                        nodeId: "layout-cta",
                        component: "FilledButton",
                        source: "Onboarding.kt:72",
                        bounds: {
                            left: BTN1.left,
                            top: BTN1.top,
                            right: BTN1.right,
                            bottom: BTN1.bottom,
                        },
                        size: {
                            width: BTN1.right - BTN1.left,
                            height: BTN1.bottom - BTN1.top,
                        },
                    },
                ],
            },
        ],
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
        { kind: "compose/semantics", payload: semantics },
        { kind: "layout/inspector", payload: layoutInspector },
    ],
};

const fixture = {
    name: "inspection-tree",
    description:
        "Focused card with realistic compose/semantics + layout/inspector payloads. Activates the Inspection bundle so the overlay layer paints over the header / primary CTA / secondary CTA / footer regions, the side legend populates with one entry per overlay box (label · role · testTag), and the inspection tab body renders the per-kind tree tables.",
    dataset: EARLY_FEATURES_DATASET,
    messages: [setPreviews, updateImage, updateDataProducts],
    actions: [
        focusAction(focusId),
        activateBundleAction("inspection"),
        // Click the primary-CTA row in the semantics tree-table so
        // the snapshot exercises the bespoke delegated click handler
        // wired in `inspectionBody()`. Row ids are namespaced by
        // kind (`nsId("semantics", node.nodeId)`).
        {
            click: `[data-bundle="inspection"] tr[data-legend-id="semantics-primary-cta"]`,
        },
    ],
};

process.stdout.write(JSON.stringify(fixture, null, 2) + "\n");
