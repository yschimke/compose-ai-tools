// Test-only stub for vscjava.vscode-gradle. Exists solely to satisfy
// compose-preview's extensionDependencies declaration so the host can
// activate during integration tests. Returns a GradleApi-shaped no-op;
// real test bodies replace this via ComposePreviewTestApi.injectGradleApi.

function activate() {
    return {
        runTask(_opts) { return Promise.resolve(); },
        cancelRunTask(_opts) { return Promise.resolve(); },
    };
}

function deactivate() {}

module.exports = { activate, deactivate };
