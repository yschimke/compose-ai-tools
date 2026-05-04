import * as vscode from "vscode";
import { ExtensionToWebview, WebviewToExtension } from "./types";

export class PreviewPanel implements vscode.WebviewViewProvider {
    public static readonly viewId = "composePreview.panel";

    private view?: vscode.WebviewView;
    private extensionUri: vscode.Uri;
    private onMessage: (msg: WebviewToExtension) => void;
    private earlyFeaturesEnabled: () => boolean;
    private shouldRestoreVisibility: () => boolean;

    constructor(
        extensionUri: vscode.Uri,
        onMessage: (msg: WebviewToExtension) => void,
        earlyFeaturesEnabled: () => boolean = () => false,
        shouldRestoreVisibility: () => boolean = () => false,
    ) {
        this.extensionUri = extensionUri;
        this.onMessage = onMessage;
        this.earlyFeaturesEnabled = earlyFeaturesEnabled;
        this.shouldRestoreVisibility = shouldRestoreVisibility;
    }

    resolveWebviewView(
        webviewView: vscode.WebviewView,
        _context: vscode.WebviewViewResolveContext,
        _token: vscode.CancellationToken,
    ): void {
        this.view = webviewView;
        webviewView.webview.options = {
            enableScripts: true,
            localResourceRoots: [this.extensionUri],
        };
        webviewView.webview.html = this.getHtml(webviewView.webview);
        webviewView.webview.onDidReceiveMessage((msg: WebviewToExtension) => {
            this.onMessage(msg);
        });
        webviewView.onDidChangeVisibility(() => {
            if (webviewView.visible || !this.shouldRestoreVisibility()) {
                return;
            }
            void vscode.commands.executeCommand(`${PreviewPanel.viewId}.focus`);
        });
    }

    postMessage(msg: ExtensionToWebview): void {
        this.view?.webview.postMessage(msg);
    }

    private getHtml(webview: vscode.Webview): string {
        const nonce = getNonce();
        const earlyFeaturesEnabled = this.earlyFeaturesEnabled();
        const styleUri = webview.asWebviewUri(
            vscode.Uri.joinPath(this.extensionUri, "media", "preview.css"),
        );
        const codiconUri = webview.asWebviewUri(
            vscode.Uri.joinPath(this.extensionUri, "media", "codicon.css"),
        );
        const scriptUri = webview.asWebviewUri(
            vscode.Uri.joinPath(this.extensionUri, "media", "preview.js"),
        );

        return /* html */ `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta http-equiv="Content-Security-Policy"
          content="default-src 'none'; img-src data:; font-src ${webview.cspSource}; style-src ${webview.cspSource} 'nonce-${nonce}'; script-src 'nonce-${nonce}';">
    <link href="${codiconUri}" rel="stylesheet">
    <link href="${styleUri}" rel="stylesheet">
</head>
<body>
    <div id="progress-bar" class="progress-bar" role="progressbar"
         aria-label="Refresh progress"
         aria-valuemin="0" aria-valuemax="100" aria-valuenow="0">
        <div class="progress-label" id="progress-label"></div>
        <div class="progress-track">
            <div class="progress-fill"></div>
        </div>
    </div>
    <div id="compile-errors" class="compile-errors" role="alert" hidden>
        <div class="compile-errors-header">
            <i class="codicon codicon-error" aria-hidden="true"></i>
            <span id="compile-errors-title">Compile errors</span>
        </div>
        <div id="compile-errors-list" class="compile-errors-list"></div>
        <div class="compile-errors-footnote">Showing last successful render.</div>
    </div>
    <div class="toolbar" id="toolbar" role="toolbar" aria-label="Preview filters">
        <div class="select-wrapper">
            <select id="filter-function" title="Filter by function" aria-label="Function filter">
                <option value="all">All functions</option>
            </select>
            <i class="codicon codicon-chevron-down select-chevron" aria-hidden="true"></i>
        </div>
        <div class="select-wrapper">
            <select id="filter-group" title="Filter by @Preview group" aria-label="Group filter">
                <option value="all">All groups</option>
            </select>
            <i class="codicon codicon-chevron-down select-chevron" aria-hidden="true"></i>
        </div>
        <div class="select-wrapper">
            <select id="layout-mode" title="Layout" aria-label="Layout mode">
                <option value="grid">Grid</option>
                <option value="flow">Flow</option>
                <option value="column">Column</option>
                <option value="focus">Focus</option>
            </select>
            <i class="codicon codicon-chevron-down select-chevron" aria-hidden="true"></i>
        </div>
    </div>

    <div id="message" class="message" role="status" aria-live="polite"></div>
    <div id="focus-controls" class="focus-controls" hidden>
        <button class="icon-button" id="btn-prev" title="Previous preview" aria-label="Previous preview">
            <i class="codicon codicon-arrow-left" aria-hidden="true"></i>
        </button>
        <span id="focus-position" aria-live="polite"></span>
        <button class="icon-button" id="btn-next" title="Next preview" aria-label="Next preview">
            <i class="codicon codicon-arrow-right" aria-hidden="true"></i>
        </button>
        <button class="icon-button" id="btn-diff-head" title="Diff vs last archived render (HEAD)" aria-label="Diff vs HEAD">
            <i class="codicon codicon-git-compare" aria-hidden="true"></i>
        </button>
        <button class="icon-button" id="btn-diff-main" title="Diff vs the latest render archived on main" aria-label="Diff vs main">
            <i class="codicon codicon-source-control" aria-hidden="true"></i>
        </button>
        <button class="icon-button" id="btn-launch-device" title="Launch on connected Android device" aria-label="Launch on device">
            <i class="codicon codicon-device-mobile" aria-hidden="true"></i>
        </button>
        <button class="icon-button" id="btn-a11y-overlay" title="Show accessibility overlay"
                aria-label="Toggle accessibility overlay" aria-pressed="false">
            <i class="codicon codicon-eye" aria-hidden="true"></i>
        </button>
        <button class="icon-button" id="btn-interactive" title="Daemon not ready — live mode unavailable"
                aria-label="Toggle live (interactive) mode" aria-pressed="false" disabled hidden>
            <i class="codicon codicon-circle-large-outline" aria-hidden="true"></i>
        </button>
        <button class="icon-button" id="btn-stop-interactive" title="Stop live preview"
                aria-label="Stop live preview" hidden>
            <i class="codicon codicon-debug-stop" aria-hidden="true"></i>
        </button>
        <button class="icon-button" id="btn-recording" title="Record focused preview"
                aria-label="Record focused preview" aria-pressed="false" disabled hidden>
            <i class="codicon codicon-record-keys" aria-hidden="true"></i>
        </button>
        <select id="recording-format" title="Recording format" aria-label="Recording format" hidden>
            <option value="apng">APNG</option>
            <option value="mp4">MP4</option>
        </select>
        <button class="icon-button" id="btn-exit-focus" title="Exit focus mode" aria-label="Exit focus mode">
            <i class="codicon codicon-close" aria-hidden="true"></i>
        </button>
    </div>
    <div id="preview-grid" class="preview-grid" role="list" aria-label="Preview cards"></div>
    <div id="focus-inspector" class="focus-inspector" hidden aria-label="Focused preview data"></div>

    <script nonce="${nonce}" src="${scriptUri}"
            data-early-features="${earlyFeaturesEnabled ? "true" : "false"}"></script>
</body>
</html>`;
    }
}

function getNonce(): string {
    let text = "";
    const possible =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    for (let i = 0; i < 32; i++) {
        text += possible.charAt(Math.floor(Math.random() * possible.length));
    }
    return text;
}
