// Generator for `history-source-ref.json`. Run with:
//   node preview-harness/fixtures/history-source-ref.gen.mjs > preview-harness/fixtures/history-source-ref.json
//
// Drives the **history panel** webview (`history.js` / `<history-app>`) to capture the reporting-
// branch source picker (#1872, slice 2). A `setSourceRef` message labels the toolbar's source
// button with the active reporting branch (`preview/main`) and flags it active; a `setEntries`
// populates the timeline with git-sourced entries so the panel shows what "viewing a reporting
// branch" looks like. This is the visual evidence for the new toolbar control — the CI visual-diff
// bot renders it on every PR.

const previewId = "com.example.OnboardingKt.WelcomeScreenPreview";

function entry(id, timestamp, pngHash, shortCommit) {
    return {
        id,
        previewId,
        module: ":sample-android",
        timestamp,
        pngHash,
        pngSize: 4218,
        pngPath: `${id}.png`,
        producer: "daemon",
        trigger: "renderNow",
        // Reporting-branch entries are stamped as a git source (see GitRefHistorySource).
        source: {
            kind: "git",
            id: `git:refs/heads/preview/main@${shortCommit}`,
        },
        git: { branch: "main", shortCommit, dirty: false },
    };
}

const fixture = {
    name: "history-source-ref",
    description:
        "History panel webview: viewing a pushed reporting branch (#1872). The toolbar source button shows the active branch `preview/main` and the timeline lists git-sourced entries.",
    app: "history-app",
    bundle: "../media/webview/history.js",
    dataset: { earlyFeatures: "true", minimalMode: "false" },
    messages: [
        // Toolbar reflects the active source: a reporting branch, not local.
        {
            command: "setSourceRef",
            ref: "refs/heads/preview/main",
            label: "preview/main",
        },
        { command: "setScopeLabel", label: "WelcomeScreenPreview" },
        {
            command: "setEntries",
            result: {
                entries: [
                    entry(
                        "20260430-101200-bbbbbbbb",
                        "2026-04-30T10:12:00Z",
                        "b".repeat(64),
                        "a1b2c3d",
                    ),
                    entry(
                        "20260430-101000-aaaaaaaa",
                        "2026-04-30T10:10:00Z",
                        "a".repeat(64),
                        "9f8e7d6",
                    ),
                ],
            },
        },
    ],
};

process.stdout.write(JSON.stringify(fixture, null, 2) + "\n");
