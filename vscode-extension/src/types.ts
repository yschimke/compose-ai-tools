export interface PreviewParams {
    name: string | null;
    device: string | null;
    widthDp: number;
    heightDp: number;
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
}

export interface PreviewManifest {
    module: string;
    variant: string;
    previews: PreviewInfo[];
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
    | { command: 'setHistory'; previewId: string; entries: HistoryEntry[] }
    | { command: 'updateHistoryImage'; previewId: string; filename: string; imageData: string };

/** Messages from webview to extension */
export type WebviewToExtension =
    | { command: 'refresh' }
    | { command: 'openFile'; className: string; functionName: string }
    | { command: 'selectModule'; value: string }
    | { command: 'showHistory'; previewId: string }
    | { command: 'loadHistoryImage'; previewId: string; filename: string };
