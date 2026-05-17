// Pure parser for `compose-preview bundle render`'s summary block. Lives
// in its own module (no `vscode` import) so it can be unit-tested under
// the host mocha harness — `bundleRender.ts` pulls vscode in for the
// `withProgress` integration and isn't loadable outside the editor.

import * as path from "path";

export interface BundleRenderedPreview {
    /** Preview id as recorded in the bundle's `previews.json`. */
    id: string;
    /** Absolute path to the rendered PNG inside the output directory. */
    outputFile: string;
}

export interface BundleFailedPreview {
    id: string;
    exitCode: number;
}

export interface BundleRenderResult {
    previewCount: number;
    succeeded: BundleRenderedPreview[];
    failed: BundleFailedPreview[];
}

/**
 * Parses the CLI's render summary block. Output shape from
 * `cli/src/main/kotlin/.../BundleCommand.kt` (RenderSubcommand):
 *
 *     rendered N / M preview(s) → <outputDir>
 *       ok    <id>  →  <basename.png>
 *       FAIL  <id>  (exit=<code>)
 *
 * Returns `null` when no summary line was found — the caller treats that
 * as launcher failure rather than "rendered 0 of 0".
 */
export function parseRenderOutput(
    stdout: string,
    outputDir: string,
): BundleRenderResult | null {
    const summaryMatch = stdout.match(
        /rendered\s+(\d+)\s*\/\s*(\d+)\s+preview\(s\)/,
    );
    if (!summaryMatch) return null;
    const succeeded: BundleRenderedPreview[] = [];
    const failed: BundleFailedPreview[] = [];
    const okRe = /^\s{2}ok\s+(\S+)\s+→\s+(.+?)\s*$/;
    const failRe = /^\s{2}FAIL\s+(\S+)\s+\(exit=(-?\d+)\)/;
    for (const line of stdout.split(/\r?\n/)) {
        const ok = line.match(okRe);
        if (ok) {
            const id = ok[1];
            const filename = ok[2];
            succeeded.push({
                id,
                outputFile: path.isAbsolute(filename)
                    ? filename
                    : path.join(outputDir, filename),
            });
            continue;
        }
        const fail = line.match(failRe);
        if (fail) {
            failed.push({ id: fail[1], exitCode: parseInt(fail[2], 10) });
        }
    }
    return {
        previewCount: parseInt(summaryMatch[2], 10),
        succeeded,
        failed,
    };
}
