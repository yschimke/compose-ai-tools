// Type-only re-exports of the wire types the webview needs.
// Lives next to the webview source so the webview build doesn't pull in
// host-side imports from `../../types.ts` (which is fine today — those
// types are pure — but this keeps the webview tree self-contained and the
// boundary explicit).

export type {
    AccessibilityFinding,
    AccessibilityNode,
    Capture,
    DaemonDataExtensionDescriptor,
    DaemonDataProductCapability,
    ExtensionToWebview,
    HistoryDiffSummary,
    HistoryEntry,
    HistoryToWebview,
    PreviewInfo,
    PreviewParams,
    PreviewRenderError,
    WebviewToExtension,
} from "../../types";

export type { CompileError } from "../../compileErrors";
