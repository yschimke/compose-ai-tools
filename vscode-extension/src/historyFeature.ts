/**
 * History feature gate — **post-1.0; deferred to 1.1**.
 *
 * Mirrors the Kotlin-side `HistoryFeature.ENABLED` constant in `:daemon:core`. While `false` the
 * VS Code extension does not build a `historySource`, does not register the history-related panel
 * messages on the focus-mode webview, and does not call `history/*` JSON-RPC methods on the
 * daemon (which themselves return `MethodNotFound` when the daemon's flag is off). The Diff vs
 * Main / Diff vs Head commands short-circuit to the same "history is not ready" branches the
 * daemon-unavailable path already covers.
 *
 * Flip back to `true` for the 1.1 cut.
 */
export const HISTORY_FEATURE_ENABLED = false;
