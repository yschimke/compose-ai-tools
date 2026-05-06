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
 */
export function pickRefreshModeFor(
    _filePath: string,
    moduleId: string | null,
): RefreshMode {
    if (!moduleId) {
        return "gradle";
    }
    return "daemon";
}
