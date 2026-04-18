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

export interface PreviewInfo {
    id: string;
    functionName: string;
    className: string;
    sourceFile: string | null;
    params: PreviewParams;
    renderOutput: string | null;
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
    entries: { previewId: string; findings: AccessibilityFinding[] }[];
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
    | { command: 'updateImage'; previewId: string; imageData: string }
    | { command: 'setImageError'; previewId: string; message: string }
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
