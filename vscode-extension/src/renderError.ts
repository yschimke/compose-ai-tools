/**
 * Pure formatter for the per-preview render-error sidecar. Lives outside
 * `extension.ts` so unit tests can exercise it without pulling the
 * `vscode` runtime onto the mocha classpath.
 *
 * The renderer writes one `<renderOutput>.error.json` per failing
 * preview when a `@Preview` function throws — see
 * `gradle-plugin/.../PreviewRenderError.kt` and
 * `renderer-desktop/.../DesktopRendererMain.kt#writeErrorSidecar`.
 * The extension parses that JSON via [PreviewRenderError] (in `types.ts`)
 * and runs it through this formatter to produce the one-line message
 * that lands on the failing card via `setImageError.message`.
 */

import { PreviewRenderError } from './types';

/**
 * Render the structured renderer error into a single-line message for
 * the failing card. Examples:
 *
 *   `NullPointerException: LocalContext was null (at Previews.kt:47 in HomeScreen)`
 *   `IllegalStateException (at Previews.kt:18 in setUp)`  // no message
 *   `IllegalArgumentException: bad arg`                    // no top frame
 *   `RuntimeException`                                     // neither
 *
 * Strips the package prefix from the exception class — full FQN is
 * already available in `renderError.exception` for tooling that wants it.
 */
export function formatRenderErrorMessage(err: PreviewRenderError): string {
    const cls = err.exception.split('.').pop() ?? err.exception;
    const head = err.message ? `${cls}: ${err.message}` : cls;
    const frame = err.topAppFrame;
    if (frame && frame.file) {
        const lineSuffix = frame.line > 0 ? `:${frame.line}` : '';
        const fnSuffix = frame.function ? ` in ${frame.function}` : '';
        return `${head} (at ${frame.file}${lineSuffix}${fnSuffix})`;
    }
    return head;
}
