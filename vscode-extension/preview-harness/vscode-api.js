// Shim that replaces VS Code's `acquireVsCodeApi()` for the in-browser
// preview harness. The real API only exists inside a VS Code webview;
// the harness runs in a plain Chromium page, so we install a stub that
// captures `postMessage` calls (so we can assert on them later) and
// uses sessionStorage for `getState` / `setState` parity.
//
// MUST be loaded BEFORE the bundled `preview.js` so `getVsCodeApi()`
// finds `window.acquireVsCodeApi` on first call.

(function () {
    const stateKey = "compose-preview-harness-state";
    const log = [];
    window.__composePreviewHarness = window.__composePreviewHarness ?? {};
    window.__composePreviewHarness.postedMessageLog = log;

    window.acquireVsCodeApi = function () {
        return {
            postMessage(msg) {
                log.push(msg);
            },
            getState() {
                try {
                    const raw = sessionStorage.getItem(stateKey);
                    return raw ? JSON.parse(raw) : undefined;
                } catch {
                    return undefined;
                }
            },
            setState(state) {
                try {
                    sessionStorage.setItem(stateKey, JSON.stringify(state));
                } catch {
                    // ignore
                }
                return state;
            },
        };
    };
})();
