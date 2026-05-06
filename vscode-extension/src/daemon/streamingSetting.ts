// Typed accessor for `composePreview.streaming.enabled`.
//
// Read fresh on every call (no cache) so changes via the VS Code Settings UI
// take effect on the next live-stream open without an extension reload —
// matches the pattern `earlyFeaturesEnabled()` uses in `extension.ts`.
//
// Kept in its own module so the extension host, the LiveState controller,
// and the future webview→extension `requestStreamStart` handler all read
// the flag through one source of truth — and so the unit test can drive it
// without dragging in `extension.ts`'s activation graph.

/** Fully-qualified setting key. Exposed for diagnostics + the test. */
export const STREAMING_ENABLED_SETTING = "composePreview.streaming.enabled";

/**
 * Minimal subset of VS Code's `WorkspaceConfiguration.get` shape we need —
 * narrowed to a function so the unit test can pass a stub without pulling
 * the `vscode` module into a node-runner mocha run (the existing pattern
 * `daemonProtocol.test.ts` uses to stay vscode-free).
 */
export type ConfigReader = <T>(section: string, key: string, fallback: T) => T;

/**
 * Default reader — pulls `composePreview.streaming.enabled` from the live
 * VS Code config. Lazily requires `vscode` so importing this module from a
 * pure-node test doesn't blow up at import time.
 */
function defaultReader<T>(section: string, key: string, fallback: T): T {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const vscode = require("vscode") as typeof import("vscode");
    return vscode.workspace.getConfiguration(section).get<T>(key, fallback);
}

/**
 * `true` when the user opted in to the `composestream/1` live-frame
 * protocol. `false` otherwise. Defaults to `false` — the legacy
 * `<img src=…>` swap path stays the default until the new wire shape
 * has bedded down.
 *
 * Reads through VS Code's `getConfiguration` so workspace + user scopes
 * are layered the way users expect (workspace overrides user, user
 * overrides default).
 *
 * @param reader Optional override for testing. Production callers pass
 *   nothing — the lazily-loaded `vscode` reader is used.
 */
export function streamingEnabled(
    reader: ConfigReader = defaultReader,
): boolean {
    return reader<boolean>("composePreview", "streaming.enabled", false);
}
