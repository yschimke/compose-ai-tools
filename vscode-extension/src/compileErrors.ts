/**
 * LSP-driven compile-error gate. Reads `vscode.languages.getDiagnostics(uri)`
 * upstream of every refresh and short-circuits the build when the active
 * file has Error-severity diagnostics. Saves a 5–30 s round-trip through
 * `compileKotlin` on a typo-fix loop.
 *
 * The gate is silent when no Kotlin language server is attached
 * (`getDiagnostics` returns an empty array regardless of file content), so
 * users without an LSP fall through to the existing Gradle-only path with
 * no visible difference.
 *
 * This module deliberately avoids importing `vscode`: it operates on a
 * structural subset of `vscode.Diagnostic` so the same parsing logic can
 * be exercised from mocha unit tests that run outside the VS Code
 * extension host. The production caller in `extension.ts` adapts
 * `vscode.Diagnostic` to [DiagnosticLike] at the call site.
 */

/** Subset of `vscode.DiagnosticSeverity` used by [extractCompileErrors]. */
export const DIAGNOSTIC_SEVERITY = {
    /** Matches `vscode.DiagnosticSeverity.Error`. */
    Error: 0,
    /** Matches `vscode.DiagnosticSeverity.Warning`. */
    Warning: 1,
    /** Matches `vscode.DiagnosticSeverity.Information`. */
    Information: 2,
    /** Matches `vscode.DiagnosticSeverity.Hint`. */
    Hint: 3,
} as const;

/** Structural shape we need from `vscode.Diagnostic`. */
export interface DiagnosticLike {
    severity: number;
    message: string;
    range: { start: { line: number; character: number } };
    /**
     * Diagnostic source (e.g. `'kotlin'`, `'gradle'`). Used to filter out
     * non-Kotlin sources that happen to attach diagnostics to the file —
     * spell-checkers, custom rules. Optional; missing source defaults to
     * "trust this diagnostic".
     */
    source?: string;
}

/** Serialisable per-error record sent to the webview banner. */
export interface CompileError {
    /** Display label — basename of the file. */
    file: string;
    /**
     * Absolute path used by the click-to-open handler. Each error carries
     * its own path so cross-file kotlinc failures (an error in `Theme.kt`
     * surfaced while editing `Previews.kt`) deep-link to the right file
     * rather than the panel's currently-scoped one.
     */
    path: string;
    /** 1-based line for human display. The webview opens via this number. */
    line: number;
    /** 1-based column. */
    column: number;
    /** First line of the diagnostic message — keeps the banner row at one line. */
    message: string;
}

/**
 * Sources we trust as "this is a real compile error from the language
 * tooling". An empty / missing source field still passes through (some
 * LSPs don't populate it). Listed conservatively so a markdown-lint or
 * spelling diagnostic can't accidentally gate a build.
 */
const TRUSTED_SOURCES = new Set([
    "kotlin",
    "kotlin-language-server",
    "fwcd.kotlin",
    "gradle",
    "JetBrains",
]);

/**
 * Return the subset of `diagnostics` that should block a refresh, in
 * source-position order. Filters by:
 *   1. Error severity only — warnings and hints are non-blocking.
 *   2. Trusted source (or no declared source).
 * Empty array means "go ahead and build".
 */
export function extractCompileErrors(
    filePath: string,
    diagnostics: readonly DiagnosticLike[],
): CompileError[] {
    const fileLabel = basename(filePath);
    const errors: CompileError[] = [];
    for (const d of diagnostics) {
        if (d.severity !== DIAGNOSTIC_SEVERITY.Error) {
            continue;
        }
        if (d.source && !TRUSTED_SOURCES.has(d.source)) {
            continue;
        }
        errors.push({
            file: fileLabel,
            path: filePath,
            // VS Code's range is 0-indexed. Convert to 1-based for display
            // and consistency with `kotlinc -e: file://…:42:5` output.
            line: d.range.start.line + 1,
            column: d.range.start.character + 1,
            message: firstLine(d.message),
        });
    }
    errors.sort((a, b) => a.line - b.line || a.column - b.column);
    return errors;
}

function firstLine(message: string): string {
    const nl = message.indexOf("\n");
    return nl >= 0 ? message.slice(0, nl) : message;
}

function basename(filePath: string): string {
    const slash = Math.max(
        filePath.lastIndexOf("/"),
        filePath.lastIndexOf("\\"),
    );
    return slash >= 0 ? filePath.slice(slash + 1) : filePath;
}
