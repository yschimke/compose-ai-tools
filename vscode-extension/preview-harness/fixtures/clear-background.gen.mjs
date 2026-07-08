// Generator for `clear-background.json`. Run with:
//   node preview-harness/fixtures/clear-background.gen.mjs > preview-harness/fixtures/clear-background.json
//
// Exercises the focus-toolbar "clear background" (crisp outline) toggle
// (`#btn-clear-background`). The fixture focuses a card — which surfaces the
// focus-controls bar — then clicks the toggle so the snapshot captures the
// button in its pressed (`aria-pressed="true"`) state alongside the other
// focus-bar controls. Unlike the touch-overlay / keyboard-band toggles this
// button is not gated on a daemon-advertised capability (both render backends
// always honour `renderNow.overrides.clearBackground`), so it shows whenever a
// card is in focus.
//
// The click posts `{ command: "toggleClearBackground", previewId, enabled }` to
// the host (which re-renders the snapshot with the override); the harness has no
// host, so the post is captured and the button's local pressed state is what the
// snapshot records.

import {
    EARLY_FEATURES_DATASET,
    buildMobileMock,
    buildPreviewPair,
    focusAction,
} from "./_utils.mjs";

const mock = buildMobileMock();
const { W, H, png } = mock;

const focusId = "com.example.buttonKt.FilledButtonPreview";
const { focused, sibling } = buildPreviewPair({
    focusId,
    width: W,
    height: H,
    fnName: "FilledButtonPreview",
    file: "ButtonShowcase.kt",
});

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

const fixture = {
    name: "clear-background",
    description:
        "Focused card with the focus-controls bar visible; clicks the `#btn-clear-background` (crisp outline) toggle so the snapshot shows the new focus-bar button in its pressed state. The toggle posts `toggleClearBackground` to the host, which re-renders the preview's snapshot with `renderNow.overrides.clearBackground`.",
    dataset: EARLY_FEATURES_DATASET,
    messages: [setPreviews, updateImage],
    actions: [
        focusAction(focusId),
        // The toggle is visible only in focus mode (applyClearBackgroundButtonState
        // gates on `inFocus`), so this click comes after the focus action.
        { click: `#btn-clear-background` },
    ],
    expectedPosts: [
        {
            command: "toggleClearBackground",
            previewId: focusId,
            enabled: true,
        },
    ],
};

process.stdout.write(JSON.stringify(fixture, null, 2) + "\n");
