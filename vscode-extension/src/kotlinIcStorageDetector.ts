/**
 * Scans Gradle output for the Kotlin incremental-compiler "Storage already registered" failure
 * (upstream KT-59321 / KT-55435). The in-process Kotlin compile service retains a
 * `FilePageCache` registration across builds; a subsequent build's attempt to re-register the
 * same `caches-jvm/.../source-to-classes.tab` path throws.
 *
 * Symptom path that brought us here (issue #1493): editing a file under VS Code triggers
 * `gradleService.compileOnly` → daemon kicks `:<module>:composePreviewCompile`. Gradle reports
 * BUILD SUCCESSFUL because Kotlin falls back to non-incremental, but the on-disk IC cache is
 * left inconsistent, and the next save's `compileKotlin UP-TO-DATE` doesn't pick up the edit.
 * From the user's side, "save isn't doing anything."
 *
 * Feed each `onOutput` chunk through {@link consume}; after the task completes call
 * {@link end} and {@link getDetectedCachesJvmDirs}. A non-empty result is the signal to wipe
 * those directories, stop the Gradle daemon (which restarts the in-process Kotlin compile
 * service with a clean cache registry on next connect), and retry the build with
 * `--rerun-tasks`. The Kotlin-side equivalent in `:gradle-preview-driver`
 * (`KotlinIcStorageDetector.kt`) covers the CLI driver path; this module covers the
 * VS Code → vscjava.vscode-gradle daemon-compile path that the issue title names.
 */

import * as path from "path";

const STORAGE_RE = /Storage for \[([^\]]+)\] is already registered/;
const MAX_BUFFER = 16 * 1024;

/**
 * Walks the parents of [filePath] until a directory named `caches-jvm` is found. Returns null
 * if no such ancestor exists — defensive against future Kotlin error-message refactors that
 * change the cache layout. Exported for test access.
 */
export function findCachesJvmAncestor(filePath: string): string | null {
    let p = path.normalize(filePath);
    while (true) {
        const base = path.basename(p);
        if (base === "caches-jvm") return p;
        const parent = path.dirname(p);
        if (parent === p) return null;
        p = parent;
    }
}

export class KotlinIcStorageDetector {
    private buffer = "";
    private readonly detected = new Set<string>();

    /**
     * Accept a chunk of decoded stdout/stderr. Safe to call with partial lines — bytes are
     * buffered until a newline arrives. Unlike the single-finding detectors, this keeps
     * scanning after a hit so multi-module builds report every affected directory in one pass.
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
        // Bound the buffer for pathological no-newline producers — 16 KiB dwarfs any single
        // Kotlin diagnostic and matches JdkImageErrorDetector's policy for consistency.
        if (this.buffer.length > MAX_BUFFER) {
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

    /** Distinct caches-jvm directories the recovery pass needs to wipe. */
    getDetectedCachesJvmDirs(): string[] {
        return Array.from(this.detected);
    }

    private scanLine(line: string): void {
        const m = STORAGE_RE.exec(line);
        if (!m) return;
        const cachesJvm = findCachesJvmAncestor(m[1]);
        if (cachesJvm) this.detected.add(cachesJvm);
    }
}
