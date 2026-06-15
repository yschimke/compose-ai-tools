// Generator for `history-theme-diff.json`. Run with:
//   node preview-harness/fixtures/history-theme-diff.gen.mjs > preview-harness/fixtures/history-theme-diff.json
//
// Drives the **history panel** webview (`history.js` / `<history-app>`) to capture the theme
// data-diff section (#1872) that renders below the pixel diff when two history entries are diffed.
// A `setEntries` populates the timeline; a `diffReady` carrying both entries' `compose/theme`
// resolved tokens makes `historyDiffView.appendThemeDiffSection` paint the `.diff-theme` block.
//
// The token maps are shaped so the diff exercises all three kinds across categories:
//   - changed: color `primary`, shape `small`, typography `bodyLarge` (weight 400 → 500)
//   - added:   typography `labelSmall` (present only in this entry)
//   - removed: color `tertiary` (present only in the previous entry)

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

// "Previous" (older) tokens — base side of the diff.
const previousTheme = {
    resolvedTokens: {
        colorScheme: {
            primary: "#6750A4",
            secondary: "#625B71",
            tertiary: "#7D5260",
        },
        typography: {
            bodyLarge: {
                fontFamily: "Roboto",
                fontSize: 16,
                fontSizeUnit: "sp",
                fontWeight: "400",
            },
        },
        shapes: {
            small: "RoundedCorner(4.0.dp)",
            medium: "RoundedCorner(12.0.dp)",
        },
    },
};

// "This entry" (newer) tokens — head side of the diff.
const thisTheme = {
    resolvedTokens: {
        colorScheme: {
            // primary changed; tertiary removed; secondary unchanged.
            primary: "#4F378B",
            secondary: "#625B71",
        },
        typography: {
            // bodyLarge weight changed; labelSmall added.
            bodyLarge: {
                fontFamily: "Roboto",
                fontSize: 16,
                fontSizeUnit: "sp",
                fontWeight: "500",
            },
            labelSmall: {
                fontFamily: "Roboto",
                fontSize: 11,
                fontSizeUnit: "sp",
                fontWeight: "500",
            },
        },
        shapes: {
            // small changed; medium unchanged.
            small: "RoundedCorner(8.0.dp)",
            medium: "RoundedCorner(12.0.dp)",
        },
    },
};

const fixture = {
    name: "history-theme-diff",
    description:
        "History panel webview: two entries diffed, with the theme data-diff section rendered below the pixel diff. Exercises changed (color/shape/typography), added (typography token), and removed (color token) across all three token categories.",
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
            leftTheme: thisTheme,
            rightTheme: previousTheme,
        },
    ],
};

process.stdout.write(JSON.stringify(fixture, null, 2) + "\n");
