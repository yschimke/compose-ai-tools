/**
 * Pure predicate for the daemon-vs-Gradle save decision. Extracted out
 * of `extension.ts` so unit tests can exercise it without stubbing the
 * VS Code API (extension.ts imports `vscode`).
 *
 * The production wrapper inside extension.ts reads the live
 * `gradleService` state and forwards into here.
 */

export type RefreshMode = "daemon" | "gradle";

/**
 * - `'daemon'` — the file resolves to a module. The save path skips
 *   `renderPreviews` entirely; daemon failures are surfaced as errors instead of falling back.
 * - `'gradle'` — file outside any module. Existing Gradle render path runs.
 *
 * Accepts the resolved module as an opaque value — only truthiness matters
 * here. Callers pass either `ModuleInfo | null` or the raw modulePath
 * string; both work.
 */
export function pickRefreshModeFor(
    _filePath: string,
    module: { readonly modulePath: string } | string | null,
): RefreshMode {
    if (!module) {
        return "gradle";
    }
    return "daemon";
}
