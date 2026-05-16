// Generator for `a11y-wear.json`. Run with:
//   node preview-harness/fixtures/a11y-wear.gen.mjs > preview-harness/fixtures/a11y-wear.json
//
// Wear-shaped Accessibility fixture. Reuses the real
// `:samples:wear:renderAllPreviews` output for `ActivityListPreview`
// (small-round 384×384, captured straight from Robolectric) so the
// scene under the overlay is the production render — same fonts,
// curved bezel mask, TransformingLazyColumn squashing, real
// material 3 expressive colours — rather than a hand-drawn mock.
//
// The a11y wire data is hand-authored to mirror what a
// `data/a11y/hierarchy-android` pass over this composition emits in
// practice: the `TransformingLazyColumn` is the scrollable root,
// each `TitleCard` is a merged clickable focus stop, and the inner
// `Text` children of each card are unmerged descendants that
// duplicate their parent's bounds. The default `mergedOnly: true`
// filter drops the unmerged children — they ride the wire, but the
// legend and on-image overlay stay focused on the three TalkBack
// stops the user can actually reach.
//
// ATF findings: each clickable card is missing a
// `contentDescription` (ERROR), the small status icon trips Wear's
// 32×32dp touch-target minimum (WARNING), and the muted "10:10"
// time text sits below the WCAG AA 4.5:1 contrast threshold against
// the deep-purple bezel mask (WARNING).
//
// Together this exercises:
//   - the narrow legend column treatment (`flex: 1 1 200px` vs the
//     preview's `flex: 3`) on a small square preview, and
//   - the merged-hierarchy default — six unmerged inner Text nodes
//     emit on the wire but the default filter halves the legend
//     entry count.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

import {
    EARLY_FEATURES_DATASET,
    activateBundleAction,
    expectSetDataExtension,
    focusAction,
    forbidSetDataExtensionEnabled,
    rectBounds,
} from "./_utils.mjs";

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(__dirname, "../../..");
const REAL_PNG = resolve(
    REPO_ROOT,
    "samples/wear/build/compose-previews/renders",
    "PreviewsKt.ActivityListPreview_Devices_-_Small_Round.png",
);
const imageData = readFileSync(REAL_PNG).toString("base64");

// Pixel-space coordinates against the rendered 384×384 small-round
// frame. Eyeballed off the captured PNG so on-image overlay boxes
// land where the real composition draws its cards / text.
const W = 384;
const H = 384;
const TIME_TEXT = { left: 160, top: 30, right: 224, bottom: 58 };
const TODAY = { left: 144, top: 88, right: 240, bottom: 116 };
const CARD1 = { left: 20, top: 142, right: 364, bottom: 250 };
const CARD2 = { left: 20, top: 268, right: 364, bottom: 376 };
const CARD1_TITLE = { left: 36, top: 156, right: 220, bottom: 188 };
const CARD1_SUB = { left: 36, top: 192, right: 280, bottom: 228 };
const CARD2_TITLE = { left: 36, top: 280, right: 220, bottom: 312 };
const CARD2_SUB = { left: 36, top: 316, right: 240, bottom: 352 };
const STATUS_ICON = { left: 304, top: 32, right: 332, bottom: 60 };

const FOCUS_ID = "com.example.samplewear.PreviewsKt.ActivityListPreview";

// Mirrors `AccessibilityNode` from
// `data/a11y/core/.../AccessibilityModels.kt`. The hierarchy below
// matches what `AccessibilityHierarchyExtension` emits for a wear
// `TransformingLazyColumn` of `TitleCard`s — scrollable root, one
// merged Card per item, two unmerged inner `Text` children per
// card that duplicate the card's bounds and would otherwise
// clutter the overlay.
const nodes = [
    {
        label: "",
        role: "scrollable",
        states: ["scrollable"],
        merged: true,
        boundsInScreen: rectBounds({ left: 0, top: 130, right: W, bottom: 384 }),
    },
    {
        label: "Today",
        role: "header",
        states: ["focusable"],
        merged: true,
        boundsInScreen: rectBounds(TODAY),
    },
    {
        label: "",
        role: "clickable",
        states: ["clickable", "focusable"],
        merged: true,
        boundsInScreen: rectBounds(CARD1),
    },
    {
        label: "Morning run",
        role: "text",
        states: [],
        merged: false,
        boundsInScreen: rectBounds(CARD1_TITLE),
    },
    {
        label: "5.2 km · 28 min",
        role: "text",
        states: [],
        merged: false,
        boundsInScreen: rectBounds(CARD1_SUB),
    },
    {
        label: "",
        role: "clickable",
        states: ["clickable", "focusable"],
        merged: true,
        boundsInScreen: rectBounds(CARD2),
    },
    {
        label: "Heart rate",
        role: "text",
        states: [],
        merged: false,
        boundsInScreen: rectBounds(CARD2_TITLE),
    },
    {
        label: "72 bpm",
        role: "text",
        states: [],
        merged: false,
        boundsInScreen: rectBounds(CARD2_SUB),
    },
    {
        label: "",
        role: "icon",
        states: ["focusable"],
        merged: true,
        boundsInScreen: rectBounds(STATUS_ICON),
    },
];

const findings = [
    {
        level: "ERROR",
        type: "SpeakableTextPresent",
        message:
            "Clickable activity card has no contentDescription. TalkBack users hear nothing when this stop is focused.",
        viewDescription: "Card",
        boundsInScreen: rectBounds(CARD1),
    },
    {
        level: "ERROR",
        type: "SpeakableTextPresent",
        message:
            "Clickable activity card has no contentDescription. TalkBack users hear nothing when this stop is focused.",
        viewDescription: "Card",
        boundsInScreen: rectBounds(CARD2),
    },
    {
        level: "WARNING",
        type: "TouchTargetSize",
        message:
            "Status icon is 20×20dp. Wear OS guidance recommends at least 32×32dp for small round faces so users can hit the target with a fingertip.",
        viewDescription: "Icon",
        boundsInScreen: rectBounds(STATUS_ICON),
    },
    {
        level: "WARNING",
        type: "TextContrast",
        message:
            "Time text uses #463C46 on #1E141C — contrast ratio 1.6:1, below the WCAG AA 4.5:1 threshold for body copy.",
        viewDescription: "TimeText",
        boundsInScreen: rectBounds(TIME_TEXT),
    },
];

const params = {
    name: null,
    device: "id:wearos_small_round",
    widthDp: 192,
    heightDp: 192,
    fontScale: 1.0,
    showSystemUi: false,
    showBackground: true,
    backgroundColor: 0,
    uiMode: 0,
    locale: null,
    group: "Devices - Small Round",
};

const focused = {
    id: FOCUS_ID,
    functionName: "ActivityListPreview",
    className: "com.example.samplewear.PreviewsKt",
    sourceFile: "Previews.kt",
    params,
    captures: [
        {
            advanceTimeMillis: null,
            scroll: null,
            renderOutput: `renders/${FOCUS_ID}.png`,
            label: "Small Round · 192×192dp",
        },
    ],
    a11yFindings: findings,
    a11yNodes: nodes,
};

const sibling = {
    ...focused,
    id: FOCUS_ID + "LargeRound",
    captures: [
        {
            advanceTimeMillis: null,
            scroll: null,
            renderOutput: `renders/${FOCUS_ID}LargeRound.png`,
            label: "Large Round · 227×227dp",
        },
    ],
};

const setPreviews = {
    command: "setPreviews",
    moduleDir: "/workspace/samples/wear",
    heavyStaleIds: [],
    previews: [focused, sibling],
};

const fixture = {
    name: "a11y-wear",
    description:
        "Wear OS ActivityListPreview (small-round, 384×384 capture of the real renderAllPreviews output) focused with the Accessibility bundle active. Two clickable activity cards trip ERROR-level missing-contentDescription findings, the status icon trips a WARNING-level touch-target rule, and the low-contrast time text trips a contrast WARNING. Drives the slim legend column treatment against a size-constrained preview and the merged-hierarchy default (four unmerged inner Text nodes are emitted on the wire but the default `mergedOnly` filter drops them from the legend and the on-image overlay).",
    dataset: EARLY_FEATURES_DATASET,
    messages: [
        setPreviews,
        {
            command: "updateImage",
            previewId: focused.id,
            captureIndex: 0,
            imageData,
        },
    ],
    actions: [focusAction(focused.id), activateBundleAction("a11y")],
    expectedPosts: [
        expectSetDataExtension(FOCUS_ID, "a11y/hierarchy", true),
        expectSetDataExtension(FOCUS_ID, "a11y/atf", true),
    ],
    forbiddenPosts: [
        forbidSetDataExtensionEnabled(FOCUS_ID, "a11y/touchTargets"),
        forbidSetDataExtensionEnabled(FOCUS_ID, "a11y/overlay"),
    ],
};

process.stdout.write(JSON.stringify(fixture, null, 2) + "\n");
