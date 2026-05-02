/**
 * Pure predicate for the daemon-vs-Gradle save decision. Extracted out
 * of `extension.ts` so unit tests can exercise it without stubbing the
 * VS Code API (extension.ts imports `vscode`).
 *
 * The production wrapper inside extension.ts reads the live
 * `daemonGate` / `gradleService` state and forwards into here.
 */

export type RefreshMode = 'daemon' | 'gradle';

/**
 * - `'daemon'` — the daemon flag is on and the file resolves to a module. The save path skips
 *   `renderPreviews` entirely; daemon failures are surfaced as errors instead of falling back.
 * - `'gradle'` — daemon disabled or file outside any module. Existing Gradle render path runs.
 *
 * The decision is made fresh on each save, so explicitly disabling the daemon still gives users a
 * temporary Gradle escape hatch while the daemon path is being stabilised.
 */
export function pickRefreshModeFor(
    _filePath: string,
    daemonEnabled: boolean,
    moduleId: string | null,
): RefreshMode {
    if (!daemonEnabled) { return 'gradle'; }
    if (!moduleId) { return 'gradle'; }
    return 'daemon';
}
