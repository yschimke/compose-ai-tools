import * as vscode from "vscode";
import { ExtensionToWebview, WebviewToExtension } from "./types";

export class PreviewPanel implements vscode.WebviewViewProvider {
    public static readonly viewId = "composePreview.panel";

    private view?: vscode.WebviewView;
    private extensionUri: vscode.Uri;
    private onMessage: (msg: WebviewToExtension) => void;
    private earlyFeaturesEnabled: () => boolean;
    private autoEnableCheapEnabled: () => boolean;
    private shouldRestoreVisibility: () => boolean;

    constructor(
        extensionUri: vscode.Uri,
        onMessage: (msg: WebviewToExtension) => void,
        earlyFeaturesEnabled: () => boolean = () => false,
        shouldRestoreVisibility: () => boolean = () => false,
        autoEnableCheapEnabled: () => boolean = () => false,
    ) {
        this.extensionUri = extensionUri;
        this.onMessage = onMessage;
        this.earlyFeaturesEnabled = earlyFeaturesEnabled;
        this.autoEnableCheapEnabled = autoEnableCheapEnabled;
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
        const autoEnableCheapEnabled = this.autoEnableCheapEnabled();
        const styleUri = webview.asWebviewUri(
            vscode.Uri.joinPath(this.extensionUri, "media", "preview.css"),
        );
        const codiconUri = webview.asWebviewUri(
            vscode.Uri.joinPath(this.extensionUri, "media", "codicon.css"),
        );

        const scriptUri = webview.asWebviewUri(
            vscode.Uri.joinPath(
                this.extensionUri,
                "media",
                "webview",
                "preview.js",
            ),
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
    <preview-app
        data-early-features="${earlyFeaturesEnabled ? "true" : "false"}"
        data-auto-enable-cheap="${autoEnableCheapEnabled ? "true" : "false"}"
    ></preview-app>
    <script nonce="${nonce}" src="${scriptUri}"></script>
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
