export interface PreviewParams {
    name: string | null;
    device: string | null;
    widthDp: number | null;
    heightDp: number | null;
    fontScale: number;
    showSystemUi: boolean;
    showBackground: boolean;
    backgroundColor: number;
    uiMode: number;
    locale: string | null;
    group: string | null;
}

/**
 * Scroll state of a capture. Intent fields (mode, axis, maxScrollPx,
 * reduceMotion) come from `@ScrollingPreview`; outcome fields (atEnd,
 * reachedPx) are populated by the renderer post-capture.
 */
export interface ScrollCapture {
    mode: 'END' | 'LONG' | string;
    axis: 'VERTICAL' | 'HORIZONTAL' | string;
    maxScrollPx: number;
    reduceMotion: boolean;
    /** Scrollable reached the end of its content before the renderer stopped.
     *  Distinct from `reachedPx === maxScrollPx`, which means the cap fired. */
    atEnd: boolean;
    /** Pixels actually scrolled. null when not reported. */
    reachedPx: number | null;
}

/**
 * One rendered snapshot of a preview. Non-null dimensional fields
 * (advanceTimeMillis, scroll) are the coordinates that distinguish this
 * capture from its siblings. A static preview has a single capture with
 * everything null.
 */
export interface Capture {
    advanceTimeMillis: number | null;
    scroll: ScrollCapture | null;
    renderOutput: string;
    /** Human-readable summary of this capture's non-null dimensions —
     *  e.g. `'500ms'`, `'scrolled end'`, `'500ms · scrolled end'`, or `''`
     *  for a plain static preview. Populated extension-side (see
     *  [captureLabels.captureLabel]) before sending to the webview so the
     *  carousel markup stays free of dimension logic. */
    label?: string;
}

export interface PreviewInfo {
    id: string;
    functionName: string;
    className: string;
    sourceFile: string | null;
    params: PreviewParams;
    /** Rendered snapshots — always at least one. Length > 1 ⇔ carousel. */
    captures: Capture[];
    /** Populated by the extension (not the Gradle manifest) — `true` iff
     *  the preview has at least one archived snapshot on disk. The webview
     *  uses this to decide whether to show the history button at all. */
    hasHistory?: boolean;
    /**
     * Populated by the extension from the sidecar accessibility.json referenced
     * in [PreviewManifest.accessibilityReport]. `null`/`undefined` means
     * accessibility checks were disabled for this module; an empty array means
     * checks ran and found nothing.
     */
    a11yFindings?: AccessibilityFinding[] | null;
    /** Absolute path to the annotated screenshot (clean PNG + overlay legend), when findings exist. */
    a11yAnnotatedPath?: string | null;
}

export interface PreviewManifest {
    module: string;
    variant: string;
    previews: PreviewInfo[];
    /** Relative path (from `previews.json`) to the sidecar a11y report, or null. */
    accessibilityReport?: string | null;
}

export interface AccessibilityFinding {
    level: 'ERROR' | 'WARNING' | 'INFO' | string;
    type: string;
    message: string;
    viewDescription?: string | null;
    boundsInScreen?: string | null;
}

export interface AccessibilityReport {
    module: string;
    entries: {
        previewId: string;
        findings: AccessibilityFinding[];
        annotatedPath?: string | null;
    }[];
}

/**
 * Output of `:<module>:composePreviewDoctor`. Matches the serialization in
 * `gradle-plugin/.../ComposePreviewDoctorTask.kt` and the per-module shape
 * inside `compose-preview doctor --json`'s `DoctorReport.checks`. Schema
 * version pinned in [DoctorModuleReport.schema] so extension can detect
 * incompatible plugin versions without dispatching on field shape.
 */
export interface DoctorModuleReport {
    schema: string;
    module: string;
    variant: string;
    findings: DoctorFinding[];
}

export interface DoctorFinding {
    id: string;
    severity: 'error' | 'warning' | 'info' | string;
    message: string;
    detail?: string | null;
    remediationSummary?: string | null;
    remediationCommands?: string[];
    docsUrl?: string | null;
}

/**
 * One historical snapshot for a preview. The Gradle plugin writes files
 * named `yyyyMMdd-HHmmss[-N].png`; [timestamp] is the filename minus `.png`,
 * kept as a display-friendly label. [iso] is a parsed ISO-8601 form for sort
 * stability and tooltip display.
 */
export interface HistoryEntry {
    filename: string;
    timestamp: string;
    iso: string;
}

/** Messages from extension to webview */
export type ExtensionToWebview =
    | { command: 'setPreviews'; previews: PreviewInfo[]; moduleDir: string }
    /** `captureIndex` addresses which capture within an animated preview the
     *  image belongs to. Static previews have a single capture at index 0. */
    | { command: 'updateImage'; previewId: string; captureIndex: number; imageData: string }
    | { command: 'setImageError'; previewId: string; captureIndex: number; message: string }
    | { command: 'setLoading'; previewId?: string }
    | { command: 'markAllLoading' }
    | { command: 'setError'; previewId: string; message: string }
    | { command: 'showMessage'; text: string }
    | { command: 'clearAll' }
    | { command: 'setModules'; modules: string[]; selected: string }
    | { command: 'setFunctionFilter'; functionName: string }
    | { command: 'setHistory'; previewId: string; entries: HistoryEntry[] }
    | { command: 'updateHistoryImage'; previewId: string; filename: string; imageData: string };

/** Messages from webview to extension */
export type WebviewToExtension =
    | { command: 'openFile'; className: string; functionName: string }
    | { command: 'selectModule'; value: string }
    | { command: 'showHistory'; previewId: string }
    | { command: 'loadHistoryImage'; previewId: string; filename: string };
