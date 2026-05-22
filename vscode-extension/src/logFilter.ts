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

export type LogLevel = "quiet" | "normal" | "verbose";

const KNOWN_LEVELS: ReadonlySet<string> = new Set([
    "quiet",
    "normal",
    "verbose",
]);

export function parseLogLevel(
    raw: string | undefined | null,
    fallback: LogLevel = "normal",
): LogLevel {
    if (raw && KNOWN_LEVELS.has(raw)) {
        return raw as LogLevel;
    }
    return fallback;
}

// `> Task :module:taskname` headers — Gradle's per-task status line. Drowning
// the user in dozens of these per refresh is what the `normal` level exists to
// suppress. `FAILED` variants are caught by the failure-shaped branches above
// before we get to this drop.
const TASK_HEADER_RE = /^> Task :/;

// Hides the full Gradle "1 actionable task: 1 up-to-date" / "9 actionable
// tasks: 9 up-to-date" footer at normal+. The line is bookkeeping only —
// `BUILD SUCCESSFUL/FAILED` (which we keep) already conveys the outcome.
const ACTIONABLE_FOOTER_RE = /^\d+ actionable tasks?: /;

// `Discovered N preview(s) in module '…':` + the indented bullet list that
// follows it. Emitted by the gradle plugin's composePreviewDiscover task. Useful for
// debugging the discovery pipeline, redundant for end users who already see
// `[refresh] rendered N preview(s) for <file>`. The bullet continuation lines
// start with two spaces, so we drop the header and any subsequent `  …` line
// until a non-indented line.
const DISCOVERED_HEADER_RE = /^Discovered \d+ preview\(s\) in module /;
const DISCOVERED_BULLET_RE = /^ {2}\S/;

// `BUILD SUCCESSFUL in 16s` is bookkeeping the user already infers from the
// `[refresh] rendered N preview(s)` line that follows. `BUILD FAILED` is the
// counterpart — we always keep that one (it's a failure).
const BUILD_SUCCESSFUL_RE = /^BUILD SUCCESSFUL /;

// Bare-status progress noise emitted by Gradle's rich console (and/or
// vscode-gradle's own progress reporter) after ANSI cursor moves have been
// stripped. Each task's `> Task :…` header was rendered to a column the
// daemon later overwrote with the visible status token; with ANSI stripped,
// only the status tokens survive — often glued together without separators,
// and sometimes glued onto a `BUILD SUCCESSFUL in …` trailer in the same
// physical chunk. `--console=plain` is supposed to suppress this but the
// Tooling API path doesn't always honour it. Matches a line that is
// **entirely** status tokens (optionally trailed by BUILD SUCCESSFUL); a
// `FAILED` token is deliberately excluded so failure signals always survive.
const BARE_STATUS_NOISE_RE =
    /^\s*((UP-TO-DATE|SKIPPED|FROM-CACHE|NO-SOURCE)\s*)+(BUILD SUCCESSFUL in [^\n]*)?\s*$/;

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
const ROBOLECTRIC_SDK_WARN_PREFIX = "[Robolectric] WARN:";

// Roborazzi prints a 6-line ActionBar overlap workaround block on every
// captured preview — 17 captures × 6 lines = 102 lines per refresh. We dedupe
// the entire block to its first line and suppress all six lines on subsequent
// captures within the session.
const ROBORAZZI_ACTIONBAR_FIRST_LINE_RE =
    /^Roborazzi: Hiding the ActionBar to avoid content overlap issues during capture\.$/;
const ROBORAZZI_ACTIONBAR_FOLLOWUP_RE = new RegExp(
    "^(" +
        [
            "This workaround is used when an ActionBar is present and the SDK version is 35 or higher\\.",
            "Hiding the ActionBar might cause slight performance overhead due to layout invalidation\\.",
            'We recommend setting the theme using <application android:theme="@android:style/Theme\\.Material\\.NoActionBar" /> in your test/AndroidManifest\\.xml to avoid this workaround\\.',
            "If you are intentionally using an ActionBar, you can disable this workaround by setting the gradle property 'roborazzi\\.compose\\.actionbar\\.overlap\\.fix' to false\\.",
            "This problem is tracked in https://issuetracker\\.google\\.com/issues/383368165",
        ].join("|") +
        ")$",
);

const BUILD_SUCCESS_FAIL_RE = /^BUILD (SUCCESSFUL|FAILED) /;

// Extension-side chatter that's high-volume but low-signal. At normal we keep
// only the lines that answer "what's the panel doing right now" (focused file,
// render outcome, panel actions, daemon readiness, failures); the rest drops
// down to verbose. Quiet drops these too.
//
// Prefixes are deliberately *specific* — we don't blanket-drop `[doctor] ` or
// `[refresh] ` because the same prefix carries failure messages (e.g.
// `[doctor] :samples:cmp:composePreviewDoctor failed: ...`) that must survive.
//
// `[refresh] [interactive] ...` / `[refresh] [stream] ...` shapes come from
// logLine() wrapping `[interactive] …` / `[stream] …` messages with the
// `[refresh] ` prefix — interactive scroll/click events and stream
// start/stop fire per user gesture and would otherwise drown the channel.
const NORMAL_CHATTER_PREFIXES = [
    "[refresh] cache hit:",
    "[refresh] cancelled — superseded ",
    "[refresh] [interactive] ",
    "[refresh] [stream] ",
    "[refresh] daemon: skip ",
    "[interactive] ",
    "[stream] started ",
    "[stream] stopped ",
    "[daemon] spawning ",
    "[daemon] webview ack ",
    "[daemon] onDataProductsAttached ",
    "[daemon] decoded a11y ",
    "[daemon] updateDataProducts ",
    "[doctor] doctor diagnostics refreshed ",
    "[detect] ",
];

// Lines that survive at normal (and verbose) but drop at quiet. The `[panel]`
// prefix carries user-initiated actions (preview selection, extension toggle,
// focus). They survive at normal but drop at quiet — quiet is reserved for
// "loading, daemon running, errors" only.
const QUIET_CHATTER_PREFIXES = [
    "[refresh] start ",
    "[refresh] rendered ",
    "[refresh] no module ",
    "[refresh] done ",
    "[refresh] auto-render: ",
    "[panel] ",
];

// Lines that survive at every level (including quiet) because they answer
// "is the extension loaded and is the daemon up". Used by the quiet branch of
// `shouldEmitInformational`.
const QUIET_KEEP_PREFIXES = ["[startup] ", "[daemon] ready for "];

// `> :module:task` / `> :module:task completed` / `> :module:task cancelled`
// progress markers from gradleService.ts. The `FAILED` variant flows through a
// separate branch in GradleService and bypasses [shouldEmitInformational].
const TASK_PROGRESS_RE = /^> :[\w:.-]+( (completed|cancelled))?$/;

// Daemon stderr — per-render phase / classloader / renderFinished chatter from
// `RenderEngine.kt` and `UserClassLoaderHolder.kt`. Each render emits ~25 of
// these lines (one per render phase × per data extension); a single refresh
// can produce hundreds. Useful for daemon devs, useless for end users.
//
// Phase-failure lines (`phase=*.failed`) are kept — they're how a botched
// render surfaces in the output channel without an exception stack.
const DAEMON_RENDER_CHATTER_RE =
    /^compose-ai-daemon: \[(render|renderFinished|classloader)\] /;
const DAEMON_RENDER_FAILURE_RE =
    /^compose-ai-daemon: \[render\] phase=[\w./]+\.failed\b/;
const DAEMON_SANDBOX_POOL_RE = /^compose-ai-tools daemon: sandbox pool active /;

export class LogFilter {
    private warnedOnce = new Set<string>();
    private inConfigureBlock = false;
    private inRoborazziBlock = false;
    // Tracks the `Discovered N preview(s) in module …:` continuation — bullet
    // lines starting with two spaces drop until a non-indented line ends the
    // block. Persists across chunks like the configure-block flag.
    private inDiscoveredBlock = false;
    // Tracks whether the last line we emitted from filterGradleChunk was blank,
    // so that dropping interstitial content between blanks doesn't accumulate
    // multiple consecutive blank lines in the output channel. Persists across
    // chunks since Gradle's tooling-API splits a single FAILURE block over
    // several stdout chunks.
    private prevEmittedBlank = false;

    constructor(
        private readonly levelProvider: () => LogLevel = () => "normal",
    ) {}

    private level(): LogLevel {
        return this.levelProvider();
    }

    /**
     * Splits [chunk] on newlines, drops lines that are noise at the current
     * level, and re-joins. Returns an empty string when every line in [chunk]
     * was dropped — the caller can `if (!filtered) return;` to avoid
     * appending an empty `\n` to the output channel.
     */
    filterGradleChunk(chunk: string): string {
        if (this.level() === "verbose") {
            return chunk;
        }
        // Preserve trailing newlines so partial chunks reassemble correctly.
        const lines = chunk.split(/\r?\n/);
        const out: string[] = [];
        for (let i = 0; i < lines.length; i++) {
            const line = lines[i];
            const isLast = i === lines.length - 1;
            const decision = this.classifyGradleLine(line);
            if (decision === "keep") {
                const isBlank = line.trimEnd().length === 0;
                if (isBlank && this.prevEmittedBlank) {
                    if (isLast) {
                        out.push("");
                    }
                    continue;
                }
                out.push(line);
                if (!isLast) {
                    this.prevEmittedBlank = isBlank;
                }
                continue;
            }
            if (decision === "drop") {
                // For non-last lines we still need to consume the newline; for
                // the trailing partial we drop nothing so downstream
                // line-buffering keeps working.
                if (!isLast) {
                    /* swallow the line + its newline */
                } else {
                    out.push("");
                }
            }
        }
        return out.join("\n");
    }

    private classifyGradleLine(line: string): "keep" | "drop" {
        // Configure-project block runs until a blank line. The block's body is
        // continuation text we also want to drop.
        if (this.inConfigureBlock) {
            if (line.length === 0) {
                this.inConfigureBlock = false;
            }
            return "drop";
        }
        if (CONFIGURE_PROJECT_RE.test(line)) {
            this.inConfigureBlock = true;
            return "drop";
        }

        // Discovered-preview block: header + indented bullet list. Drop the
        // whole block at normal/quiet — it duplicates `[refresh] rendered N`.
        if (this.inDiscoveredBlock) {
            if (DISCOVERED_BULLET_RE.test(line)) {
                return "drop";
            }
            this.inDiscoveredBlock = false;
            // Fall through to classify this line normally.
        }
        if (DISCOVERED_HEADER_RE.test(line)) {
            this.inDiscoveredBlock = true;
            return "drop";
        }

        const trimmed = line.trimEnd();
        if (trimmed.length === 0) {
            return "keep";
        }

        // Failure-shaped lines survive at every non-verbose level.
        if (/^FAILURE: /.test(trimmed)) {
            return "keep";
        }
        if (/^\* What went wrong:/.test(trimmed)) {
            return "keep";
        }
        if (/(^|\s)FAILED(\s|$)/.test(trimmed)) {
            return "keep";
        }
        if (/^Caused by: /.test(trimmed)) {
            return "keep";
        }
        if (/^[ \t]+at .+\(/.test(line)) {
            return "keep";
        }
        if (/^e: /.test(trimmed)) {
            return "keep";
        } // kotlinc errors
        if (/^w: /.test(trimmed)) {
            return "keep";
        } // kotlinc warnings

        if (this.level() === "quiet") {
            // Quiet keeps only outcome lines and the failure-shaped lines
            // above. `BUILD SUCCESSFUL` survives so the user sees the outcome
            // even when nothing went wrong.
            if (BUILD_SUCCESS_FAIL_RE.test(trimmed)) {
                return "keep";
            }
            return "drop";
        }

        // normal level — substantially less chatter than before. The
        // per-task `> Task :module:task [STATUS]` headers, the
        // composePreviewDiscover bullet list (handled above), and the bookkeeping
        // tail (config cache, incubating, actionable footer) all drop.
        // `BUILD SUCCESSFUL` also drops at normal — `[refresh] rendered N`
        // already conveys success; `BUILD FAILED` survives via the
        // failure-shaped branches above.
        if (TASK_HEADER_RE.test(trimmed)) {
            // Already handled by the `FAILED` branch above — drop everything
            // else.
            return "drop";
        }
        if (BARE_STATUS_NOISE_RE.test(trimmed)) {
            return "drop";
        }
        if (BUILD_SUCCESSFUL_RE.test(trimmed)) {
            return "drop";
        }
        if (CONFIG_CACHE_RE.test(trimmed)) {
            return "drop";
        }
        if (ACTIONABLE_FOOTER_RE.test(trimmed)) {
            return "drop";
        }
        if (INCUBATING_RE.test(trimmed)) {
            return "drop";
        }
        return "keep";
    }

    /**
     * Returns the line to print, or `null` if it should be suppressed at the
     * current level. The line is passed without the `[daemon stderr] ` prefix.
     */
    filterDaemonStderrLine(line: string): string | null {
        const lvl = this.level();
        if (lvl === "verbose") {
            return line;
        }

        // Roborazzi ActionBar block — at normal the first line shows once and
        // follow-ups are always suppressed; at quiet the entire block drops
        // (it's neither loading, daemon-ready, nor an error). The block is
        // line-by-line so we track our way through it with a flag.
        if (ROBORAZZI_ACTIONBAR_FIRST_LINE_RE.test(line)) {
            this.inRoborazziBlock = true;
            if (lvl === "quiet") {
                return null;
            }
            if (this.warnedOnce.has("roborazzi-actionbar")) {
                return null;
            }
            this.warnedOnce.add("roborazzi-actionbar");
            return line;
        }
        if (this.inRoborazziBlock) {
            if (ROBORAZZI_ACTIONBAR_FOLLOWUP_RE.test(line)) {
                return null;
            }
            // The block is exactly 6 lines (1 header + 5 follow-ups); anything
            // else means the block ended.
            this.inRoborazziBlock = false;
        }

        if (
            line.startsWith(ROBOLECTRIC_SDK_WARN_PREFIX) ||
            line.startsWith("Android SDK 36 requires Java 21")
        ) {
            const key = "robolectric-sdk-java";
            if (this.warnedOnce.has(key)) {
                return null;
            }
            this.warnedOnce.add(key);
            return line;
        }

        if (lvl === "quiet") {
            // Drop everything that isn't an exception or stack frame.
            if (
                line.startsWith("Exception ") ||
                line.startsWith("Caused by: ")
            ) {
                return line;
            }
            if (/^\s+at .+\(/.test(line)) {
                return line;
            }
            if (
                line.startsWith("compose-ai-daemon: fatal") ||
                line.startsWith("compose-ai-daemon: dispatch error")
            ) {
                return line;
            }
            return null;
        }

        // normal level
        if (DAEMON_BOOT_BANNER_RE.test(line)) {
            return null;
        }
        if (DAEMON_TIMING_RE.test(line)) {
            return null;
        }
        // Per-render phase / classloader / renderFinished chatter — hundreds
        // of lines per refresh. Failure-shaped phase lines (`phase=*.failed`)
        // survive so botched renders still surface.
        if (DAEMON_RENDER_FAILURE_RE.test(line)) {
            return line;
        }
        if (DAEMON_RENDER_CHATTER_RE.test(line)) {
            return null;
        }
        if (DAEMON_SANDBOX_POOL_RE.test(line)) {
            return null;
        }
        return line;
    }

    /**
     * Used by extension-side `[refresh] ...` / `[doctor] ...` informational
     * lines and gradle-task progress markers. At normal we keep only the
     * lines that answer "what's the panel doing right now" — focused file,
     * render outcome, panel actions, daemon readiness, failures. Quiet
     * drops everything that isn't the [panel] action prefix or an
     * unrecognised (likely error-shaped) line. Verbose emits unconditionally.
     *
     * Errors and `FAILED`-shaped lines should bypass this and emit through
     * `outputChannel.appendLine` directly — both at quiet and normal.
     */
    shouldEmitInformational(line: string): boolean {
        const lvl = this.level();
        if (lvl === "verbose") {
            return true;
        }
        if (lvl === "normal") {
            // `[panel]` is user-initiated (preview selection, extension toggle).
            // Always emit at normal so the user sees what their click did.
            if (line.startsWith("[panel] ")) {
                return true;
            }
            for (const prefix of NORMAL_CHATTER_PREFIXES) {
                if (line.startsWith(prefix)) {
                    return false;
                }
            }
            if (TASK_PROGRESS_RE.test(line)) {
                return false;
            }
            return true;
        }
        // quiet — "loading, daemon running, errors" only. Allow-list the few
        // signals that always survive, then deny-list the known chatter, and
        // keep anything else (unfamiliar lines are likely error-shaped).
        for (const prefix of QUIET_KEEP_PREFIXES) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        for (const prefix of QUIET_CHATTER_PREFIXES) {
            if (line.startsWith(prefix)) {
                return false;
            }
        }
        for (const prefix of NORMAL_CHATTER_PREFIXES) {
            if (line.startsWith(prefix)) {
                return false;
            }
        }
        if (TASK_PROGRESS_RE.test(line)) {
            return false;
        }
        return true;
    }

    /** Returns true only when verbose logging is enabled. */
    shouldEmitVerbose(): boolean {
        return this.level() === "verbose";
    }

    /** Resets dedupe state — call at session boundaries (extension activation in tests). */
    reset(): void {
        this.warnedOnce.clear();
        this.inConfigureBlock = false;
        this.inRoborazziBlock = false;
        this.inDiscoveredBlock = false;
        this.prevEmittedBlank = false;
    }
}
