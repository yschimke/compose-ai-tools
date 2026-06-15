// Generator for `history-semantics-diff.json`. Run with:
//   node preview-harness/fixtures/history-semantics-diff.gen.mjs > preview-harness/fixtures/history-semantics-diff.json
//
// Drives the **history panel** webview (`history.js` / `<history-app>`, not the preview app) to
// capture the semantics data-diff section (#1872) that renders below the pixel diff when two history
// entries are diffed. A `setEntries` populates the timeline; a `diffReady` carrying both entries'
// `compose/semantics` trees makes `historyDiffView.appendSemanticsDiffSection` paint the
// `.diff-semantics` block (added / removed / changed nodes).
//
// The trees are shaped so the diff exercises all three row kinds:
//   - changed: the `submit` button's text "Submit" → "Send" (same stable ref)
//   - added:   a `hero` image present only in this (newer) entry
//   - removed: a `caption` present only in the previous (older) entry

import { buildMobileMock } from "./_utils.mjs";

const mock = buildMobileMock();
const previewId = "com.example.OnboardingKt.WelcomeScreenPreview";
const thisId = "20260430-101200-bbbbbbbb";
const prevId = "20260430-101000-aaaaaaaa";

function entry(id, timestamp, pngHash, trigger) {
    return {
        id,
        previewId,
        module: ":sample-android",
        timestamp,
        pngHash,
        pngSize: 4218,
        pngPath: `${id}.png`,
        producer: "daemon",
        trigger,
        source: { kind: "fs", id: "fs:/workspace/.compose-preview-history" },
        git: { branch: "feature/onboarding", shortCommit: "a1b2c3d", dirty: false },
    };
}

// "Previous" (older) tree — base side of the diff.
const previousSemantics = {
    root: {
        nodeId: "1",
        boundsInRoot: "0,0,360,600",
        role: "Column",
        children: [
            {
                nodeId: "2",
                boundsInRoot: "40,240,160,296",
                role: "Button",
                testTag: "submit",
                text: "Submit",
            },
            {
                nodeId: "3",
                boundsInRoot: "0,520,360,600",
                testTag: "caption",
                text: "By continuing you agree to the terms",
            },
        ],
    },
};

// "This entry" (newer) tree — head side of the diff.
const thisSemantics = {
    root: {
        nodeId: "1",
        boundsInRoot: "0,0,360,600",
        role: "Column",
        children: [
            {
                nodeId: "2",
                boundsInRoot: "40,240,160,296",
                role: "Button",
                testTag: "submit",
                text: "Send",
            },
            {
                nodeId: "9",
                boundsInRoot: "0,80,360,200",
                role: "Image",
                testTag: "hero",
            },
        ],
    },
};

const fixture = {
    name: "history-semantics-diff",
    description:
        "History panel webview: two entries diffed, with the semantics data-diff section rendered below the pixel diff. Exercises all three delta kinds — a changed button label, an added image, and a removed caption — matched by stable ref.",
    app: "history-app",
    bundle: "../media/webview/history.js",
    dataset: { earlyFeatures: "true", minimalMode: "false" },
    messages: [
        {
            command: "setEntries",
            result: {
                entries: [
                    entry(thisId, "2026-04-30T10:12:00Z", "b".repeat(64), "fileChanged"),
                    entry(prevId, "2026-04-30T10:10:00Z", "a".repeat(64), "renderNow"),
                ],
            },
        },
        {
            // No host in the harness, so post `diffReady` directly (the same message the host sends
            // after a "Diff vs previous" click). `setDiff` opens + fills the expansion.
            command: "diffReady",
            id: thisId,
            against: "previous",
            leftLabel: "This entry · 10:12",
            leftImage: mock.png,
            rightLabel: "Previous · 10:10",
            rightImage: mock.png,
            leftSemantics: thisSemantics,
            rightSemantics: previousSemantics,
        },
    ],
};

process.stdout.write(JSON.stringify(fixture, null, 2) + "\n");
