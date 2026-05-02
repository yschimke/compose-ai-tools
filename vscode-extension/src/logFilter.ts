/**
 * Output-channel noise filter for the "Compose Preview" log.
 *
 * Three sources flow through here:
 *   - Gradle stdout (per-task UP-TO-DATE/SKIPPED lines, configuration-cache
 *     bookkeeping, repeated kotlin-dsl warnings, etc.) — see [filterGradleChunk].
 *   - Daemon stderr (boot banner, classpath fingerprint dumps, repeated
 *     Roborazzi ActionBar warnings, startup-timeline tables) — see
 *     [filterDaemonStderrLine].
 *   - Extension-side `[refresh] / [doctor] / [daemon]` lines emitted directly
 *     by GradleService / DaemonGate / DaemonScheduler, which already go
 *     through this module via [shouldEmitVerboseExtensionLine] for the
 *     informational (non-error) variants.
 *
 * The level is read lazily on every call so a `composePreview.logging.level`
 * change in settings.json takes effect on the next Gradle invocation without
 * a window reload.
 */

export type LogLevel = 'quiet' | 'normal' | 'verbose';

const KNOWN_LEVELS: ReadonlySet<string> = new Set(['quiet', 'normal', 'verbose']);

export function parseLogLevel(raw: string | undefined | null, fallback: LogLevel = 'normal'): LogLevel {
    if (raw && KNOWN_LEVELS.has(raw)) { return raw as LogLevel; }
    return fallback;
}

// Per-task status suffix that means "nothing happened" — the task body wasn't
// re-executed. Drowning the user in twenty of these per refresh is what the
// `normal` level exists to suppress.
const NOOP_TASK_SUFFIX_RE = /\s(UP-TO-DATE|NO-SOURCE|SKIPPED|FROM-CACHE)\s*$/;

// Hides the full Gradle "1 actionable task: 1 up-to-date" / "9 actionable
// tasks: 9 up-to-date" footer at normal+. The line is bookkeeping only —
// `BUILD SUCCESSFUL/FAILED` (which we keep) already conveys the outcome.
const ACTIONABLE_FOOTER_RE = /^\d+ actionable tasks?: /;

const CONFIG_CACHE_RE =
    /^(Reusing configuration cache\.|Configuration cache entry (reused|stored)\.|Calculating task graph .*)$/;

const INCUBATING_RE = /^\[Incubating\] /;

// Configure-project blocks are emitted whenever Gradle re-runs the
// `settings.gradle.kts` configuration. They reliably contain a multi-paragraph
// kotlin-dsl mismatch warning + an `android.experimental.enableScreenshotTest`
// notice that isn't actionable for the user. We swallow the whole block — from
// `> Configure project :name` through to the next blank line followed by a
// non-warning-shaped line — at normal level.
const CONFIGURE_PROJECT_RE = /^> Configure project :/;

// Daemon boot-banner lines from `daemon/android/.../DaemonMain.kt` (and the
// desktop counterpart). Dropped at normal level — the user already sees
// `[daemon] ready for ... (daemonVersion=..., previews=...)` from
// daemonGate.ts which carries the same useful summary.
const DAEMON_BOOT_BANNER_RE =
    /^compose-ai-tools daemon: (hello|UserClassLoaderHolder active|PreviewManifestRouter active|ClasspathFingerprint active|PreviewIndex loaded|IncrementalDiscovery active|HistoryManager active)\b/;

// Per-mark / summary-table lines from `StartupTimings.kt`. Useful for daemon
// devs investigating slow boots, useless for end users.
const DAEMON_TIMING_RE =
    /^(compose-ai-daemon: \[\+\d+ms] |compose-ai-daemon: startup timeline:|  \[\s*\d+ms]\s*\+\d+ms\s+)/;

// Robolectric's "you don't have Java 21" warning fires on every daemon spawn.
// Show it once per session.
const ROBOLECTRIC_SDK_WARN_PREFIX = '[Robolectric] WARN:';

// Roborazzi prints a 6-line ActionBar overlap workaround block on every
// captured preview — 17 captures × 6 lines = 102 lines per refresh. We dedupe
// the entire block to its first line and suppress all six lines on subsequent
// captures within the session.
const ROBORAZZI_ACTIONBAR_FIRST_LINE_RE =
    /^Roborazzi: Hiding the ActionBar to avoid content overlap issues during capture\.$/;
const ROBORAZZI_ACTIONBAR_FOLLOWUP_RE = new RegExp(
    '^(' + [
        'This workaround is used when an ActionBar is present and the SDK version is 35 or higher\\.',
        'Hiding the ActionBar might cause slight performance overhead due to layout invalidation\\.',
        'We recommend setting the theme using <application android:theme="@android:style/Theme\\.Material\\.NoActionBar" /> in your test/AndroidManifest\\.xml to avoid this workaround\\.',
        'If you are intentionally using an ActionBar, you can disable this workaround by setting the gradle property \'roborazzi\\.compose\\.actionbar\\.overlap\\.fix\' to false\\.',
        'This problem is tracked in https://issuetracker\\.google\\.com/issues/383368165',
    ].join('|') + ')$',
);

const BUILD_SUCCESS_FAIL_RE = /^BUILD (SUCCESSFUL|FAILED) /;

// Extension-side `[refresh]` / `[doctor]` / Gradle-task progress lines
// emitted from `extension.ts` and `gradleService.ts`. These are useful at
// normal but redundant at quiet — quiet keeps only error lines and the BUILD
// outcome.
const EXTENSION_INFO_PREFIXES = [
    '[refresh] start ',
    '[refresh] rendered ',
    '[doctor] doctor diagnostics refreshed ',
    '[daemon] spawning ',
    '[daemon] ready for ',
    '[detect] ',
];

// `> :module:task` and `> :module:task completed` are progress markers we
// suppress at quiet. The `FAILED` / `cancelled` variants flow through their
// own branches in GradleService and bypass [shouldEmitInformational].
const TASK_PROGRESS_RE = /^> :[\w:.-]+( completed)?$/;

export class LogFilter {
    private warnedOnce = new Set<string>();
    private inConfigureBlock = false;
    private inRoborazziBlock = false;

    constructor(private readonly levelProvider: () => LogLevel = () => 'normal') {}

    private level(): LogLevel { return this.levelProvider(); }

    /**
     * Splits [chunk] on newlines, drops lines that are noise at the current
     * level, and re-joins. Returns an empty string when every line in [chunk]
     * was dropped — the caller can `if (!filtered) return;` to avoid
     * appending an empty `\n` to the output channel.
     */
    filterGradleChunk(chunk: string): string {
        if (this.level() === 'verbose') { return chunk; }
        // Preserve trailing newlines so partial chunks reassemble correctly.
        const lines = chunk.split(/\r?\n/);
        const out: string[] = [];
        for (let i = 0; i < lines.length; i++) {
            const line = lines[i];
            const isLast = i === lines.length - 1;
            const decision = this.classifyGradleLine(line);
            if (decision === 'keep') { out.push(line); continue; }
            if (decision === 'drop') {
                // For non-last lines we still need to consume the newline; for
                // the trailing partial we drop nothing so downstream
                // line-buffering keeps working.
                if (!isLast) { /* swallow the line + its newline */ }
                else { out.push(''); }
            }
        }
        return out.join('\n');
    }

    private classifyGradleLine(line: string): 'keep' | 'drop' {
        // Configure-project block runs until a blank line. The block's body is
        // continuation text we also want to drop.
        if (this.inConfigureBlock) {
            if (line.length === 0) { this.inConfigureBlock = false; }
            return 'drop';
        }
        if (CONFIGURE_PROJECT_RE.test(line)) {
            this.inConfigureBlock = true;
            return 'drop';
        }

        const trimmed = line.trimEnd();
        if (trimmed.length === 0) { return 'keep'; }

        if (this.level() === 'quiet') {
            // Quiet keeps only outcome lines and FAILED/error-shaped lines.
            if (BUILD_SUCCESS_FAIL_RE.test(trimmed)) { return 'keep'; }
            if (/^FAILURE: /.test(trimmed)) { return 'keep'; }
            if (/^\* What went wrong:/.test(trimmed)) { return 'keep'; }
            if (/(^|\s)FAILED(\s|$)/.test(trimmed)) { return 'keep'; }
            if (/^Caused by: /.test(trimmed)) { return 'keep'; }
            if (/^[ \t]+at .+\(/.test(line)) { return 'keep'; }
            if (/^e: /.test(trimmed)) { return 'keep'; } // kotlinc errors
            if (/^w: /.test(trimmed)) { return 'keep'; } // kotlinc warnings
            return 'drop';
        }

        // normal level
        if (trimmed.startsWith('> Task ') && NOOP_TASK_SUFFIX_RE.test(trimmed)) { return 'drop'; }
        if (CONFIG_CACHE_RE.test(trimmed)) { return 'drop'; }
        if (ACTIONABLE_FOOTER_RE.test(trimmed)) { return 'drop'; }
        if (INCUBATING_RE.test(trimmed)) { return 'drop'; }
        return 'keep';
    }

    /**
     * Returns the line to print, or `null` if it should be suppressed at the
     * current level. The line is passed without the `[daemon stderr] ` prefix.
     */
    filterDaemonStderrLine(line: string): string | null {
        if (this.level() === 'verbose') { return line; }

        // Roborazzi ActionBar block — first line shows once, follow-ups are
        // always suppressed at normal/quiet. The block is line-by-line so we
        // track our way through it with a flag.
        if (ROBORAZZI_ACTIONBAR_FIRST_LINE_RE.test(line)) {
            this.inRoborazziBlock = true;
            if (this.warnedOnce.has('roborazzi-actionbar')) { return null; }
            this.warnedOnce.add('roborazzi-actionbar');
            return line;
        }
        if (this.inRoborazziBlock) {
            if (ROBORAZZI_ACTIONBAR_FOLLOWUP_RE.test(line)) { return null; }
            // The block is exactly 6 lines (1 header + 5 follow-ups); anything
            // else means the block ended.
            this.inRoborazziBlock = false;
        }

        if (line.startsWith(ROBOLECTRIC_SDK_WARN_PREFIX) ||
            line.startsWith('Android SDK 36 requires Java 21')) {
            const key = 'robolectric-sdk-java';
            if (this.warnedOnce.has(key)) { return null; }
            this.warnedOnce.add(key);
            return line;
        }

        if (this.level() === 'quiet') {
            // Drop everything that isn't an exception or stack frame.
            if (line.startsWith('Exception ') || line.startsWith('Caused by: ')) { return line; }
            if (/^\s+at .+\(/.test(line)) { return line; }
            if (line.startsWith('compose-ai-daemon: fatal') ||
                line.startsWith('compose-ai-daemon: dispatch error')) {
                return line;
            }
            return null;
        }

        // normal level
        if (DAEMON_BOOT_BANNER_RE.test(line)) { return null; }
        if (DAEMON_TIMING_RE.test(line)) { return null; }
        return line;
    }

    /**
     * Used by extension-side `[refresh] ...` / `[doctor] ...` informational
     * lines. Returns true at normal+ for non-error chatter, false at quiet.
     * Errors and `FAILED` lines should bypass this and emit unconditionally.
     */
    shouldEmitInformational(line: string): boolean {
        if (this.level() !== 'quiet') { return true; }
        for (const prefix of EXTENSION_INFO_PREFIXES) {
            if (line.startsWith(prefix)) { return false; }
        }
        if (TASK_PROGRESS_RE.test(line)) { return false; }
        return true;
    }

    /** Returns true only when verbose logging is enabled. */
    shouldEmitVerbose(): boolean {
        return this.level() === 'verbose';
    }

    /** Resets dedupe state — call at session boundaries (extension activation in tests). */
    reset(): void {
        this.warnedOnce.clear();
        this.inConfigureBlock = false;
        this.inRoborazziBlock = false;
    }
}
