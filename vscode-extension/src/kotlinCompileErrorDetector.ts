/**
 * Streaming detector for Kotlin compiler error lines in Gradle stdout.
 *
 * Catches the cross-file and build-script cases that the LSP-driven gate
 * (compileErrors.ts) misses — the gate only inspects the active file's
 * diagnostics, so an error in `Theme.kt` while editing `Previews.kt`
 * sails past it. Gradle then fails at `compileDebugKotlin`; today the
 * user sees a generic "Build failed. See Output > Compose Preview"
 * message. This detector parses the same output stream we already
 * consume for [JdkImageErrorDetector] and surfaces structured errors
 * that the panel can render in its existing compile-error banner.
 *
 * Decision tree mirrors `JdkImageErrorDetector`: feed each `onOutput`
 * chunk through [consume] (partial lines buffered), call [end] when the
 * stream finishes, then read [getErrors]. An empty array means "no
 * Kotlin compile errors were observed" — which is a fine outcome even
 * for a failed build (e.g. configuration-cache failure, missing
 * artifact, runtime crash in the test runner).
 *
 * Output format we parse, observed across Kotlin 1.9 / 2.0 / 2.1:
 *
 *   e: file:///abs/path/Foo.kt:42:5 Unresolved reference: Modfier
 *   e: file:///abs/path/Foo.kt:42:5: error: Unresolved reference: Modfier
 *   e: /abs/path/Foo.kt:42:5 Unresolved reference: Modfier
 *
 * Warnings (`w:` prefix) are intentionally NOT collected — the panel
 * banner is for blocking failures only. Warnings still flow through
 * the user-visible log via the normal logger path.
 */

import { CompileError } from "./compileErrors";

/**
 * Match `e:` lines emitted by `kotlinc`. The path can be either a
 * `file://` URL or a bare absolute path; both forms are seen in the
 * wild depending on Kotlin version + how Gradle pipes the output.
 *
 * The capture groups are: 1=path, 2=line, 3=column, 4=message.
 *
 * The path matcher is non-greedy so it stops at the line-number colon
 * — Windows paths with drive letters (`C:\...`) work because the
 * non-greedy match pairs the path's colons with the regex's `:` only
 * after we've matched the line/column digits, leaving the drive-letter
 * `:` inside the path capture.
 */
const KOTLIN_ERROR_RE =
    /^e:\s+(?:file:\/\/)?(.+?):(\d+):(\d+)(?::?\s*(?:error:\s*)?)(.+)$/;

export class KotlinCompileErrorDetector {
    private buffer = "";
    private errors: CompileError[] = [];
    /** Bound the count we hold so a runaway compile (~100s of errors on
     *  a broken refactor) doesn't grow the array without limit. The
     *  banner truncates display anyway; capturing more than ~50 helps
     *  nobody. */
    private static readonly MAX_ERRORS = 50;

    /**
     * Accept a chunk of decoded stdout/stderr. Safe to call with partial
     * lines — they're buffered until a newline arrives. Bounded to 16 KiB
     * of unflushed buffer so a producer emitting megabytes without
     * newlines can't grow the buffer without limit.
     */
    consume(chunk: string): void {
        this.buffer += chunk;
        let nl = this.buffer.indexOf("\n");
        while (nl !== -1) {
            const line = this.buffer.slice(0, nl);
            this.buffer = this.buffer.slice(nl + 1);
            this.scanLine(line);
            nl = this.buffer.indexOf("\n");
        }
        if (this.buffer.length > 16 * 1024) {
            this.scanLine(this.buffer);
            this.buffer = "";
        }
    }

    /** Flush the residual buffer. Call once after the stream ends. */
    end(): void {
        if (this.buffer.length > 0) {
            this.scanLine(this.buffer);
            this.buffer = "";
        }
    }

    /** Snapshot of errors observed so far. Returns a copy — caller can
     *  mutate the result without affecting subsequent parses. */
    getErrors(): CompileError[] {
        return this.errors.slice();
    }

    private scanLine(line: string): void {
        if (this.errors.length >= KotlinCompileErrorDetector.MAX_ERRORS) {
            return;
        }
        const trimmed = line.replace(/\r$/, ""); // strip CR from CRLF lines
        const m = KOTLIN_ERROR_RE.exec(trimmed);
        if (!m) {
            return;
        }
        const path = m[1];
        const lineNum = parseInt(m[2], 10);
        const column = parseInt(m[3], 10);
        const message = m[4].trim();
        if (!Number.isFinite(lineNum) || !Number.isFinite(column)) {
            return;
        }
        this.errors.push({
            file: basename(path),
            path,
            // kotlinc emits 1-based positions, same as the banner expects.
            line: lineNum,
            column,
            message,
        });
    }
}

/**
 * Thrown by [GradleService] when a Gradle task fails AND the streaming
 * Kotlin detector observed at least one structured error. Carries the
 * parsed errors plus the failing task so the extension can drive the
 * compile-error banner without re-parsing the log.
 */
export class KotlinCompileError extends Error {
    constructor(
        readonly errors: CompileError[],
        readonly task: string,
    ) {
        super(
            `Gradle task ${task} failed: ${errors.length} Kotlin compile error(s).`,
        );
        this.name = "KotlinCompileError";
    }
}

function basename(filePath: string): string {
    const slash = Math.max(
        filePath.lastIndexOf("/"),
        filePath.lastIndexOf("\\"),
    );
    return slash >= 0 ? filePath.slice(slash + 1) : filePath;
}
