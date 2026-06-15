// Generator for `history-a11y-diff.json`. Run with:
//   node preview-harness/fixtures/history-a11y-diff.gen.mjs > preview-harness/fixtures/history-a11y-diff.json
//
// Drives the **history panel** webview (`history.js` / `<history-app>`) to capture the a11y
// data-diff section (#1872) that renders below the pixel diff when two history entries are diffed.
// A `setEntries` populates the timeline; a `diffReady` carrying both entries' `a11y/hierarchy` node
// lists makes `historyDiffView.appendA11yDiffSection` paint the `.diff-a11y` block.
//
// The node lists are shaped so the diff exercises all three kinds (matched by stable ref, #1784):
//   - changed: the `submit` button's label "Submit" → "Send" and states Enabled → Disabled
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

// "Previous" (older) hierarchy — base side of the diff.
const previousA11y = {
    nodes: [
        {
            ref: "a1",
            role: "Button",
            label: "Submit",
            states: ["Enabled"],
            merged: true,
            boundsInScreen: "40,240,160,296",
        },
        {
            ref: "a2",
            role: "Image",
            label: "Logo",
            states: [],
            merged: true,
            boundsInScreen: "0,0,360,80",
        },
        {
            ref: "a3",
            label: "By continuing you agree to the terms",
            states: [],
            merged: true,
            boundsInScreen: "0,520,360,600",
        },
    ],
};

// "This entry" (newer) hierarchy — head side of the diff.
const thisA11y = {
    nodes: [
        {
            ref: "a1",
            role: "Button",
            label: "Send",
            states: ["Disabled"],
            merged: true,
            boundsInScreen: "40,240,160,296",
        },
        {
            ref: "a2",
            role: "Image",
            label: "Logo",
            states: [],
            merged: true,
            boundsInScreen: "0,0,360,80",
        },
        {
            ref: "a4",
            role: "Image",
            label: "Hero",
            states: [],
            merged: true,
            boundsInScreen: "0,80,360,200",
        },
    ],
};

const fixture = {
    name: "history-a11y-diff",
    description:
        "History panel webview: two entries diffed, with the a11y data-diff section rendered below the pixel diff. Exercises a changed node (label + states), an added image node, and a removed caption — matched by stable ref.",
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
            leftA11y: thisA11y,
            rightA11y: previousA11y,
        },
    ],
};

process.stdout.write(JSON.stringify(fixture, null, 2) + "\n");
