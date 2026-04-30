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
 * - `'daemon'` — the experimental flag is on, the file resolves to a
 *   module, and the daemon for that module is up and the sandbox has
 *   finished bootstrapping (post-#327 `RobolectricHost.start` blocks
 *   until ready, so `isDaemonReady` is the right signal). The save
 *   path skips `renderPreviews` entirely.
 * - `'gradle'` — daemon disabled, file outside any module, daemon not
 *   yet warm, daemon spawn failed, or daemon exited (e.g. classpath
 *   dirty). Existing Gradle render path runs.
 *
 * The decision is made fresh on each save — `isDaemonReady` reads the
 * live registry, so a daemon that crashed mid-session silently flips
 * back to the Gradle path on the next save without any extension state
 * to manage.
 */
export function pickRefreshModeFor(
    _filePath: string,
    daemonEnabled: boolean,
    moduleId: string | null,
    isDaemonReady: (moduleId: string) => boolean,
): RefreshMode {
    if (!daemonEnabled) { return 'gradle'; }
    if (!moduleId) { return 'gradle'; }
    if (!isDaemonReady(moduleId)) { return 'gradle'; }
    return 'daemon';
}
