import * as vscode from "vscode";

/**
 * History feature gate — gated behind the early-access flag.
 *
 * The render-history UI (the focus-view history section, the Diff vs Main / Diff vs Head
 * affordances) is an early, unstable preview feature, so it rides on the shared
 * `composePreview.earlyFeatures.enabled` setting alongside the other early features. When the
 * setting is `false` the extension does not build a `historySource`, does not register the
 * history-related panel messages on the focus-mode webview, and does not call `history/*`
 * JSON-RPC methods on the daemon. The Diff vs Main / Diff vs Head commands short-circuit to the
 * same "history is not ready" branches the daemon-unavailable path already covers.
 *
 * The daemon records history on by default now (`HistoryFeature.ENABLED` in `:daemon:core`); this
 * flag only controls whether the VS Code surfaces it.
 */
export function historyFeatureEnabled(): boolean {
    return vscode.workspace
        .getConfiguration("composePreview")
        .get<boolean>("earlyFeatures.enabled", false);
}
