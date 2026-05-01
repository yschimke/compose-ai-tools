import * as assert from 'assert';
import {
    DIAGNOSTIC_SEVERITY,
    DiagnosticLike,
    extractCompileErrors,
} from '../compileErrors';

/** Minimal diagnostic builder mirroring the bits we use from vscode.Diagnostic. */
function diag(opts: {
    severity: number;
    line: number;
    column?: number;
    message: string;
    source?: string;
}): DiagnosticLike {
    return {
        severity: opts.severity,
        message: opts.message,
        range: { start: { line: opts.line, character: opts.column ?? 0 } },
        source: opts.source,
    };
}

describe('extractCompileErrors', () => {
    it('returns an empty array when no diagnostics are present', () => {
        const out = extractCompileErrors('/ws/Foo.kt', []);
        assert.deepStrictEqual(out, []);
    });

    it('returns an empty array when all diagnostics are warnings/hints', () => {
        const out = extractCompileErrors('/ws/Foo.kt', [
            diag({ severity: DIAGNOSTIC_SEVERITY.Warning, line: 0, message: 'unused import', source: 'kotlin' }),
            diag({ severity: DIAGNOSTIC_SEVERITY.Hint, line: 1, message: 'spell', source: 'kotlin' }),
        ]);
        assert.deepStrictEqual(out, []);
    });

    it('extracts an Error diagnostic with 1-based line/column', () => {
        const out = extractCompileErrors('/ws/Foo.kt', [
            diag({
                severity: DIAGNOSTIC_SEVERITY.Error,
                line: 41,        // 0-based
                column: 4,       // 0-based
                message: 'Unresolved reference: Modfier',
                source: 'kotlin',
            }),
        ]);
        assert.strictEqual(out.length, 1);
        assert.strictEqual(out[0].file, 'Foo.kt');
        assert.strictEqual(out[0].line, 42);     // 1-based for display
        assert.strictEqual(out[0].column, 5);    // 1-based for display
        assert.strictEqual(out[0].message, 'Unresolved reference: Modfier');
    });

    it('mixes errors and warnings — only errors are returned', () => {
        const out = extractCompileErrors('/ws/Foo.kt', [
            diag({ severity: DIAGNOSTIC_SEVERITY.Warning, line: 5, message: 'shadows outer', source: 'kotlin' }),
            diag({ severity: DIAGNOSTIC_SEVERITY.Error, line: 10, message: 'expected }', source: 'kotlin' }),
            diag({ severity: DIAGNOSTIC_SEVERITY.Hint, line: 12, message: 'redundant', source: 'kotlin' }),
        ]);
        assert.strictEqual(out.length, 1);
        assert.strictEqual(out[0].line, 11);
    });

    it('rejects errors from untrusted sources', () => {
        // A markdown linter or a custom checker shouldn't be able to gate a
        // Kotlin build — only diagnostics from known compile sources count.
        const out = extractCompileErrors('/ws/Foo.kt', [
            diag({
                severity: DIAGNOSTIC_SEVERITY.Error,
                line: 0,
                message: 'spelling: Composble',
                source: 'cspell',
            }),
        ]);
        assert.deepStrictEqual(out, []);
    });

    it('accepts errors from trusted Kotlin LSP sources', () => {
        for (const src of ['kotlin', 'kotlin-language-server', 'fwcd.kotlin', 'gradle', 'JetBrains']) {
            const out = extractCompileErrors('/ws/Foo.kt', [
                diag({ severity: DIAGNOSTIC_SEVERITY.Error, line: 0, message: 'x', source: src }),
            ]);
            assert.strictEqual(out.length, 1, `expected source "${src}" to be trusted`);
        }
    });

    it('accepts errors with no source field set', () => {
        // Some servers don't populate `source`. Default-trust those rather
        // than silently swallowing the gate.
        const out = extractCompileErrors('/ws/Foo.kt', [
            diag({ severity: DIAGNOSTIC_SEVERITY.Error, line: 0, message: 'oops' }),
        ]);
        assert.strictEqual(out.length, 1);
    });

    it('returns errors sorted by line then column', () => {
        const out = extractCompileErrors('/ws/Foo.kt', [
            diag({ severity: DIAGNOSTIC_SEVERITY.Error, line: 50, column: 0, message: 'late', source: 'kotlin' }),
            diag({ severity: DIAGNOSTIC_SEVERITY.Error, line: 10, column: 5, message: 'early B', source: 'kotlin' }),
            diag({ severity: DIAGNOSTIC_SEVERITY.Error, line: 10, column: 1, message: 'early A', source: 'kotlin' }),
        ]);
        assert.deepStrictEqual(out.map(e => e.message), ['early A', 'early B', 'late']);
    });

    it('uses only the first line of multi-line diagnostic messages', () => {
        // A long Kotlin error message with type-inference detail can run
        // many lines. Banner rows are one line each so we trim — full
        // detail still in the LSP hover / Problems panel.
        const out = extractCompileErrors('/ws/Foo.kt', [
            diag({
                severity: DIAGNOSTIC_SEVERITY.Error,
                line: 0,
                message: 'Type mismatch.\nRequired: String\nFound: Int',
                source: 'kotlin',
            }),
        ]);
        assert.strictEqual(out[0].message, 'Type mismatch.');
    });

    it('extracts the file basename for display, even from absolute paths', () => {
        const out = extractCompileErrors('/long/abs/path/to/Previews.kt', [
            diag({ severity: DIAGNOSTIC_SEVERITY.Error, line: 0, message: 'x', source: 'kotlin' }),
        ]);
        assert.strictEqual(out[0].file, 'Previews.kt');
    });

    it('handles Windows-style paths', () => {
        const out = extractCompileErrors('C:\\workspace\\src\\Previews.kt', [
            diag({ severity: DIAGNOSTIC_SEVERITY.Error, line: 0, message: 'x', source: 'kotlin' }),
        ]);
        assert.strictEqual(out[0].file, 'Previews.kt');
    });
});
