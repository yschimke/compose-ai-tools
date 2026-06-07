import * as vscode from "vscode";
import { ExtensionToWebview, WebviewToExtension } from "./types";
import type { SpatialScene } from "./webview/shared/spatialScene";
import type { SpatialSemanticsTree } from "./webview/shared/spatialSemanticsTree";

export class PreviewPanel implements vscode.WebviewViewProvider {
    public static readonly viewId = "composePreview.panel";

    private view?: vscode.WebviewView;
    private extensionUri: vscode.Uri;
    private onMessage: (msg: WebviewToExtension) => void;
    private earlyFeaturesEnabled: () => boolean;
    private minimalModeEnabled: () => boolean;
    private shouldRestoreVisibility: () => boolean;
    /** The scene last handed to {@link showSpatialScene}; re-posted on every
     *  `webviewReady` so a late-resolving or reloaded webview still gets it.
     *  `semanticsTree` is the optional companion that overlays per-panel 2D
     *  wireframes (matched to the scene by panel id). */
    private currentSpatialScene?: {
        scene: SpatialScene;
        sceneDir: vscode.Uri;
        semanticsTree?: SpatialSemanticsTree;
    };

    constructor(
        extensionUri: vscode.Uri,
        onMessage: (msg: WebviewToExtension) => void,
        earlyFeaturesEnabled: () => boolean = () => false,
        shouldRestoreVisibility: () => boolean = () => false,
        minimalModeEnabled: () => boolean = () => false,
    ) {
        this.extensionUri = extensionUri;
        this.onMessage = onMessage;
        this.earlyFeaturesEnabled = earlyFeaturesEnabled;
        this.minimalModeEnabled = minimalModeEnabled;
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
            // The webview's `webviewReady` handshake is the host's cue to
            // (re)publish stateful messages a just-resolved/reloaded panel
            // would otherwise miss. The spatial scene is one of those: a
            // `setSpatialScene` posted before the webview booted (e.g. the
            // dev command run from the palette before the view existed) is
            // dropped, so re-post the current one here.
            if (msg?.command === "webviewReady") {
                this.postSpatialScene();
            }
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

    /**
     * Hand the panel's 3D spatial view a scene to render. `sceneDir` is the
     * directory the scene's relative `texture` paths resolve against (where
     * the panel PNGs live); it is run through `asWebviewUri` so textures load
     * under the strict CSP, and must sit inside `localResourceRoots` (the
     * extension dir covers the committed fixtures). The producer (`:renderer-xr`)
     * and the dev `openSpatialFixture` command both drive this. The optional
     * `semanticsTree` companion overlays each panel's 2D wireframe boxes onto
     * its screenshot face, matched to the scene by panel id.
     */
    showSpatialScene(
        scene: SpatialScene,
        sceneDir: vscode.Uri,
        semanticsTree?: SpatialSemanticsTree,
    ): void {
        // Retain as the current scene so it survives a not-yet-resolved view
        // and a webview reload (hidden → shown): `postSpatialScene` re-sends it
        // on every `webviewReady`, the same way the host republishes
        // `setPreviews` et al.
        this.currentSpatialScene = { scene, sceneDir, semanticsTree };
        this.postSpatialScene();
    }

    private postSpatialScene(): void {
        const webview = this.view?.webview;
        if (!webview || !this.currentSpatialScene) {
            return;
        }
        const { scene, sceneDir, semanticsTree } = this.currentSpatialScene;
        const textureBaseUri = `${webview.asWebviewUri(sceneDir)}/`;
        webview.postMessage({
            command: "setSpatialScene",
            scene,
            textureBaseUri,
            ...(semanticsTree ? { semanticsTree } : {}),
        });
    }

    private getHtml(webview: vscode.Webview): string {
        const nonce = getNonce();
        const earlyFeaturesEnabled = this.earlyFeaturesEnabled();
        const minimalModeEnabled = this.minimalModeEnabled();
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

        // The 3D spatial viewer (three.js) ships as its own bundle, loaded
        // lazily by the panel only on the first switch to the 3D view. Its
        // URI + the page nonce ride on `<preview-app>` dataset attributes so
        // the webview can inject a nonce'd `<script>` under the strict CSP.
        const spatialUri = webview.asWebviewUri(
            vscode.Uri.joinPath(
                this.extensionUri,
                "media",
                "webview",
                "spatial.js",
            ),
        );

        return /* html */ `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta http-equiv="Content-Security-Policy"
          content="default-src 'none'; img-src data: ${webview.cspSource}; font-src ${webview.cspSource} https://fonts.gstatic.com; style-src ${webview.cspSource} https://fonts.googleapis.com 'nonce-${nonce}'; script-src 'nonce-${nonce}';">
    <link href="${codiconUri}" rel="stylesheet">
    <link href="${styleUri}" rel="stylesheet">
    <link href="https://fonts.googleapis.com/css2?family=Roboto:ital,wght@0,300;0,400;0,500;0,700;1,400&family=Roboto+Serif:ital,wght@0,400;0,500;0,700;1,400&family=Roboto+Mono:ital,wght@0,400;0,500;0,700&family=Caveat:wght@400;700&display=swap" rel="stylesheet">
</head>
<body>
    <preview-app
        data-early-features="${earlyFeaturesEnabled ? "true" : "false"}"
        data-minimal-mode="${minimalModeEnabled ? "true" : "false"}"
        data-spatial-src="${spatialUri}"
        data-csp-nonce="${nonce}"
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
