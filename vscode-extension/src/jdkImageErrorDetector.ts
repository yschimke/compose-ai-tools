/**
 * Scans Gradle output for the AGP `JdkImageTransform` failure that fires when
 * Gradle is launched with a JRE that has no `jlink` binary — typically an
 * IDE-bundled runtime (Antigravity / VS Code Java language server) pointed at
 * via `org.gradle.java.home` or the Gradle extension's setting.
 *
 * The decisive signature in the output is:
 *
 *     jlink executable <abs-path> does not exist.
 *
 * Corroborating signatures (`Failed to transform core-for-system-modules.jar`,
 * `Execution failed for JdkImageTransform`) are common to other JDK-image
 * issues too, so we match on the jlink line and treat the rest as context.
 *
 * Feed each `onOutput` chunk through {@link consume}. At the end of the build
 * read {@link getFinding}; it returns `null` unless a jlink line was seen.
 */

export interface JdkImageFinding {
    /** Absolute path Gradle tried to execute (always ends in `/bin/jlink` or similar). */
    jlinkPath: string;
    /**
     * Short human-readable reason identifying the likely culprit from the path,
     * e.g. "Red Hat Java extension bundled runtime" or "bundled JRE (no JDK)".
     * Empty when the path doesn't match any known pattern.
     */
    reason: string;
}

const JLINK_RE = /jlink executable (\S+(?:\/bin\/jlink|\\bin\\jlink(?:\.exe)?))\s+does not exist/;

/**
 * Best-effort diagnosis of *why* the path is a JRE and not a JDK. Keyed off
 * path fragments we've seen in the wild. Order matters — the more specific
 * vendors match before the generic `/jre/` fallback.
 */
function diagnose(jlinkPath: string): string {
    const p = jlinkPath.replace(/\\/g, '/');
    if (/\/\.antigravity\/extensions\/redhat\.java-/.test(p)) {
        return 'Red Hat Java extension bundled runtime (Antigravity)';
    }
    if (/\/\.vscode(?:-server|-insiders)?\/extensions\/redhat\.java-/.test(p)) {
        return 'Red Hat Java extension bundled runtime (VS Code)';
    }
    if (/\/redhat\.java-[^/]+\/jre\//.test(p)) {
        return 'Red Hat Java extension bundled runtime';
    }
    if (/\/jre\/|\/jre$/.test(p)) {
        return 'bundled JRE (no JDK)';
    }
    return '';
}

export class JdkImageErrorDetector {
    private buffer = '';
    private finding: JdkImageFinding | null = null;

    /**
     * Accept a chunk of decoded stdout/stderr. Safe to call with partial
     * lines — they're buffered until a newline arrives. No-op once a finding
     * has been recorded (avoids paying the regex cost for the rest of the
     * build output).
     */
    consume(chunk: string): void {
        if (this.finding) { return; }
        this.buffer += chunk;
        let nl = this.buffer.indexOf('\n');
        while (nl !== -1) {
            const line = this.buffer.slice(0, nl);
            this.buffer = this.buffer.slice(nl + 1);
            if (this.scanLine(line)) { return; }
            nl = this.buffer.indexOf('\n');
        }
        // Bound the buffer so a pathological producer emitting megabytes
        // without newlines can't grow it without limit. 16 KiB is far
        // larger than any single Gradle log line we care about.
        if (this.buffer.length > 16 * 1024) {
            this.scanLine(this.buffer);
            this.buffer = '';
        }
    }

    /** Flush the residual buffer. Call once after the stream ends. */
    end(): void {
        if (this.finding || this.buffer.length === 0) {
            this.buffer = '';
            return;
        }
        this.scanLine(this.buffer);
        this.buffer = '';
    }

    getFinding(): JdkImageFinding | null {
        return this.finding;
    }

    private scanLine(line: string): boolean {
        const m = JLINK_RE.exec(line);
        if (!m) { return false; }
        this.finding = { jlinkPath: m[1], reason: diagnose(m[1]) };
        return true;
    }
}

/**
 * Thrown by {@link GradleService} when the task failed AND the output
 * contained the jlink-missing signature. Carries the finding so the
 * extension can offer a targeted remediation instead of the generic
 * "Gradle task failed" message.
 */
export class JdkImageError extends Error {
    constructor(readonly finding: JdkImageFinding, readonly task: string) {
        super(
            `Gradle task ${task} failed: jlink not found at ${finding.jlinkPath}. ` +
            `Gradle needs a full JDK (with jlink), not a JRE.`,
        );
        this.name = 'JdkImageError';
    }
}
