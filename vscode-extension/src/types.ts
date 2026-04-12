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
}

export interface PreviewManifest {
    module: string;
    variant: string;
    previews: PreviewInfo[];
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
    | { command: 'setModules'; modules: string[]; selected: string };

/** Messages from webview to extension */
export type WebviewToExtension =
    | { command: 'refresh' }
    | { command: 'openFile'; className: string; functionName: string }
    | { command: 'selectModule'; value: string };
