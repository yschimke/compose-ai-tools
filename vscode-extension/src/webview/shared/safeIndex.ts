// Defensive coercions for values that cross the webview boundary.
//
// TypeScript types these on the wire (e.g. `captureIndex: number` on
// `setImageError` / `updateImage` `ExtensionToWebview` variants), but the
// runtime value is whatever the extension actually sent — in principle a
// non-number could slip through. CodeQL flags array writes whose index
// flows from such a value, so we coerce defensively at the edge.
//
// `safeArrayIndex` lives in `webview/shared/` so both the preview-side
// `messageHandlers.ts` and any future webview module can pick it up
// without dragging the wider message-dispatch surface in. It has no DOM
// dependencies, so the host tsconfig can compile it for unit tests.

/**
 * Coerce a wire-supplied capture / array index into a safe non-negative
 * integer. Anything non-integer / negative / non-finite collapses to 0
 * (the representative capture / first element). Eliminates the
 * prototype-pollution flow CodeQL flags when an unconstrained index is
 * used to write into an array.
 */
export function safeArrayIndex(value: unknown): number {
    return typeof value === "number" && Number.isInteger(value) && value >= 0
        ? value
        : 0;
}
