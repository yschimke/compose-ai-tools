/**
 * Scans Gradle output for `UnsupportedClassVersionError` — fires when Gradle
 * is launched with a JDK whose `java` binary is older than the JDK that
 * compiled the plugin / build-logic classes it now has to load.
 *
 * The decisive line shape is:
 *
 *     java.lang.UnsupportedClassVersionError: pkg/SomePlugin has been compiled
 *     by a more recent version of the Java Runtime (class file version 69.0),
 *     this version of the Java Runtime only recognizes class file versions up
 *     to 65.0
 *
 * Class file version is `Java major + 44` (Java 21 = 65, Java 25 = 69), so
 * we report Java-version equivalents to keep the remediation message
 * actionable.
 *
 * Feed each `onOutput` chunk through {@link consume}. At the end of the build
 * read {@link getFinding}; it returns `null` unless a class-version line was
 * seen.
 */

export interface ClassVersionFinding {
    /** Internal name of the class that failed to load (e.g. `ee/.../FooPlugin`). */
    className: string;
    /** Class file major version embedded in the bytecode (e.g. 69 for Java 25). */
    compiledClassVersion: number;
    /** Highest class file major version the runtime accepts (e.g. 65 for Java 21). */
    runtimeMaxClassVersion: number;
    /** Java major version that produced the bytecode. */
    compiledJavaVersion: number;
    /** Java major version of the running JVM. */
    runtimeJavaVersion: number;
}

const CLASS_VERSION_RE =
    /UnsupportedClassVersionError:\s+(\S+)\s+has been compiled by a more recent version of the Java Runtime \(class file version (\d+)\.\d+\), this version of the Java Runtime only recognizes class file versions up to (\d+)\.\d+/;

const CLASSFILE_TO_JAVA_OFFSET = 44;

export class ClassVersionErrorDetector {
    private buffer = "";
    private finding: ClassVersionFinding | null = null;

    /**
     * Accept a chunk of decoded stdout/stderr. Safe to call with partial
     * lines — they're buffered until a newline arrives. No-op once a finding
     * has been recorded.
     */
    consume(chunk: string): void {
        if (this.finding) {
            return;
        }
        this.buffer += chunk;
        let nl = this.buffer.indexOf("\n");
        while (nl !== -1) {
            const line = this.buffer.slice(0, nl);
            this.buffer = this.buffer.slice(nl + 1);
            if (this.scanLine(line)) {
                return;
            }
            nl = this.buffer.indexOf("\n");
        }
        // Same 16 KiB bound as JdkImageErrorDetector — a producer emitting
        // megabytes without newlines shouldn't be able to grow the buffer
        // unboundedly.
        if (this.buffer.length > 16 * 1024) {
            this.scanLine(this.buffer);
            this.buffer = "";
        }
    }

    /** Flush the residual buffer. Call once after the stream ends. */
    end(): void {
        if (this.finding || this.buffer.length === 0) {
            this.buffer = "";
            return;
        }
        this.scanLine(this.buffer);
        this.buffer = "";
    }

    getFinding(): ClassVersionFinding | null {
        return this.finding;
    }

    private scanLine(line: string): boolean {
        const m = CLASS_VERSION_RE.exec(line);
        if (!m) {
            return false;
        }
        const compiledClassVersion = parseInt(m[2], 10);
        const runtimeMaxClassVersion = parseInt(m[3], 10);
        this.finding = {
            className: m[1],
            compiledClassVersion,
            runtimeMaxClassVersion,
            compiledJavaVersion:
                compiledClassVersion - CLASSFILE_TO_JAVA_OFFSET,
            runtimeJavaVersion:
                runtimeMaxClassVersion - CLASSFILE_TO_JAVA_OFFSET,
        };
        return true;
    }
}

/**
 * Thrown by {@link GradleService} when the task failed AND the output
 * contained an `UnsupportedClassVersionError`. Carries the finding so the
 * extension can offer a Java-version remediation instead of a generic
 * "Gradle task failed".
 */
export class ClassVersionError extends Error {
    constructor(
        readonly finding: ClassVersionFinding,
        readonly task: string,
    ) {
        super(
            `Gradle task ${task} failed: ${finding.className} needs Java ${finding.compiledJavaVersion}+, ` +
                `but Gradle is running on Java ${finding.runtimeJavaVersion}.`,
        );
        this.name = "ClassVersionError";
    }
}
