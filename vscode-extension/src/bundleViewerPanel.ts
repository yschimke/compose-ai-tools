// Editor-style webview panel for an opened preview bundle.
//
// The bundle is a `composePreviewBundle` polyglot file (PNG header
// followed by a zip). We extract its manifest, re-render the contained
// previews via `compose-preview bundle render`, then surface the
// renders in a panel that reuses the existing `<preview-app>` Lit
// element in `bundle-mode` — focus-mode chrome, no Gradle / daemon
// coupling.
//
// One panel per bundle path; opening the same bundle twice reveals the
// existing tab rather than spawning a duplicate render.

import * as crypto from "crypto";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import * as vscode from "vscode";
import {
    BundleContents,
    bundleLabel,
    BundleFormatError,
    readBundleContents,
} from "./bundleFormat";
import {
    BundleCliNotFoundError,
    BundleRenderResult,
    locateBundleCli,
    renderBundle,
} from "./bundleRender";
import type { Capture, PreviewInfo, PreviewParams } from "./types";

const CHANNEL = "[bundle-viewer]";

export interface BundleViewerHostDeps {
    extensionUri: vscode.Uri;
    earlyFeaturesEnabled: () => boolean;
    logLine: (message: string) => void;
}

export class BundleViewerPanel {
    /** Open panels keyed by absolute bundle path. */
    private static readonly active = new Map<string, BundleViewerPanel>();

    /**
     * Open (or reveal) a viewer for [bundlePath]. The file is validated
     * before the panel is created — non-bundle PNGs / unreadable files
     * surface an error toast without creating a stray empty tab.
     */
    static async open(
        bundlePath: string,
        deps: BundleViewerHostDeps,
    ): Promise<BundleViewerPanel | null> {
        const absolute = path.resolve(bundlePath);
        const existing = BundleViewerPanel.active.get(absolute);
        if (existing) {
            existing.panel.reveal(vscode.ViewColumn.Active);
            return existing;
        }
        let contents: BundleContents;
        try {
            contents = await readBundleContents(absolute);
        } catch (err) {
            const message =
                err instanceof BundleFormatError
                    ? err.message
                    : `Unable to read bundle: ${(err as Error).message}`;
            void vscode.window.showErrorMessage(
                `Compose Preview — ${path.basename(absolute)}: ${message}`,
            );
            deps.logLine(`${CHANNEL} reject ${absolute}: ${message}`);
            return null;
        }
        if (!contents.previews || contents.previews.previews.length === 0) {
            void vscode.window.showWarningMessage(
                `Compose Preview — ${path.basename(absolute)}: bundle has no previews to render.`,
            );
            return null;
        }
        const panel = vscode.window.createWebviewPanel(
            "composePreview.bundleViewer",
            `Bundle: ${bundleLabel(absolute)}`,
            vscode.ViewColumn.Active,
            {
                enableScripts: true,
                retainContextWhenHidden: true,
                localResourceRoots: [deps.extensionUri],
            },
        );
        const viewer = new BundleViewerPanel(absolute, panel, deps, contents);
        BundleViewerPanel.active.set(absolute, viewer);
        viewer.start();
        return viewer;
    }

    private renderRoot: string | null = null;
    private disposed = false;

    private constructor(
        private readonly bundlePath: string,
        private readonly panel: vscode.WebviewPanel,
        private readonly deps: BundleViewerHostDeps,
        private readonly contents: BundleContents,
    ) {
        panel.webview.html = this.buildHtml();
        panel.onDidDispose(() => this.dispose());
        panel.webview.onDidReceiveMessage(
            (msg: { command?: string }) => void this.onWebviewMessage(msg),
        );
    }

    private dispose(): void {
        if (this.disposed) return;
        this.disposed = true;
        BundleViewerPanel.active.delete(this.bundlePath);
        if (this.renderRoot) {
            // Best-effort: drop the temp renders so we don't accumulate
            // them across sessions. Ignore errors — the OS will clean up
            // anything we leave behind on reboot.
            fs.promises
                .rm(this.renderRoot, { recursive: true, force: true })
                .catch(() => {
                    /* ignore */
                });
        }
    }

    private start(): void {
        void this.runRender();
    }

    private async runRender(): Promise<void> {
        let cliPath: string;
        try {
            cliPath = await locateBundleCli();
        } catch (err) {
            const message =
                err instanceof BundleCliNotFoundError
                    ? err.message
                    : (err as Error).message;
            this.deps.logLine(`${CHANNEL} ${message}`);
            this.postError(
                "compose-preview CLI not found",
                "Install the CLI (scripts/install.sh) or set `composePreview.bundleCliPath`.",
            );
            return;
        }
        const renderRoot = await fs.promises.mkdtemp(
            path.join(os.tmpdir(), "compose-preview-bundle-"),
        );
        this.renderRoot = renderRoot;
        let result: BundleRenderResult;
        try {
            result = await vscode.window.withProgress(
                {
                    location: vscode.ProgressLocation.Notification,
                    title: `Rendering bundle ${bundleLabel(this.bundlePath)}…`,
                    cancellable: true,
                },
                async (progress, token) => {
                    return renderBundle({
                        bundlePath: this.bundlePath,
                        outputDir: renderRoot,
                        cliPath,
                        cancellation: token,
                        onOutput: (chunk) => {
                            const line = chunk.split(/\r?\n/).at(-1)?.trim();
                            if (line) progress.report({ message: line });
                        },
                    });
                },
            );
        } catch (err) {
            const message = (err as Error).message ?? String(err);
            this.deps.logLine(`${CHANNEL} render failed: ${message}`);
            this.postError("Bundle render failed", message);
            return;
        }
        await this.publish(result);
    }

    private async publish(result: BundleRenderResult): Promise<void> {
        if (this.disposed) return;
        const previews = synthesisePreviews(this.contents, result);
        this.panel.webview.postMessage({
            command: "setPreviews",
            previews,
            moduleDir: this.renderRoot ?? "",
            heavyStaleIds: [],
        });
        await Promise.all(
            result.succeeded.map(async (rendered) => {
                try {
                    const buf = await fs.promises.readFile(rendered.outputFile);
                    if (this.disposed) return;
                    this.panel.webview.postMessage({
                        command: "updateImage",
                        previewId: rendered.id,
                        captureIndex: 0,
                        imageData: buf.toString("base64"),
                    });
                } catch (err) {
                    this.deps.logLine(
                        `${CHANNEL} read render ${rendered.id} failed: ${(err as Error).message}`,
                    );
                }
            }),
        );
        if (result.failed.length > 0) {
            const ids = result.failed.map((f) => f.id).join(", ");
            void vscode.window.showWarningMessage(
                `Compose Preview — ${path.basename(this.bundlePath)}: ${result.failed.length} preview(s) failed to render (${ids}).`,
            );
        }
    }

    private postError(title: string, detail: string): void {
        void vscode.window.showErrorMessage(
            `Compose Preview — ${path.basename(this.bundlePath)}: ${title}. ${detail}`,
        );
    }

    private async onWebviewMessage(msg: { command?: string }): Promise<void> {
        // The bundle viewer only round-trips a handful of messages today.
        // Most webview→host commands (refreshHeavy, requestStreamStart,
        // …) assume a Gradle module; in bundle mode they no-op silently.
        if (msg.command === "openExternal") {
            const url = (msg as { url?: string }).url;
            if (url && /^https?:\/\//i.test(url)) {
                void vscode.env.openExternal(vscode.Uri.parse(url));
            }
        }
    }

    private buildHtml(): string {
        const webview = this.panel.webview;
        const nonce = crypto.randomBytes(16).toString("hex");
        const styleUri = webview.asWebviewUri(
            vscode.Uri.joinPath(this.deps.extensionUri, "media", "preview.css"),
        );
        const codiconUri = webview.asWebviewUri(
            vscode.Uri.joinPath(this.deps.extensionUri, "media", "codicon.css"),
        );
        const scriptUri = webview.asWebviewUri(
            vscode.Uri.joinPath(
                this.deps.extensionUri,
                "media",
                "webview",
                "preview.js",
            ),
        );
        const early = this.deps.earlyFeaturesEnabled() ? "true" : "false";
        return /* html */ `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta http-equiv="Content-Security-Policy"
          content="default-src 'none'; img-src data:; font-src ${webview.cspSource} https://fonts.gstatic.com; style-src ${webview.cspSource} https://fonts.googleapis.com 'nonce-${nonce}'; script-src 'nonce-${nonce}';">
    <link href="${codiconUri}" rel="stylesheet">
    <link href="${styleUri}" rel="stylesheet">
    <link href="https://fonts.googleapis.com/css2?family=Roboto:ital,wght@0,300;0,400;0,500;0,700;1,400&family=Roboto+Serif:ital,wght@0,400;0,500;0,700;1,400&family=Roboto+Mono:ital,wght@0,400;0,500;0,700&family=Caveat:wght@400;700&display=swap" rel="stylesheet">
</head>
<body>
    <preview-app
        data-early-features="${early}"
        data-minimal-mode="false"
        data-bundle-mode="true"
    ></preview-app>
    <script nonce="${nonce}" src="${scriptUri}"></script>
</body>
</html>`;
    }
}

/**
 * Map the bundle's discovery-time `previews.json` + the per-preview CLI
 * render outcomes into the `PreviewInfo` shape `<preview-app>` consumes
 * via `setPreviews`. The render-output filename is bundle-viewer-local
 * (`<renderRoot>/<safeFilename(id)>.png`), which we record as an absolute
 * path; the panel's `setPreviews` handler treats `moduleDir` + the
 * `renderOutput` field as an absolute path when the first segment is
 * already rooted.
 */
function synthesisePreviews(
    contents: BundleContents,
    result: BundleRenderResult,
): PreviewInfo[] {
    const renderById = new Map(result.succeeded.map((r) => [r.id, r]));
    return (contents.previews?.previews ?? []).map((entry) => {
        const params: PreviewParams = {
            name: entry.params.name ?? null,
            device: entry.params.device ?? null,
            widthDp: entry.params.widthDp ?? null,
            heightDp: entry.params.heightDp ?? null,
            fontScale: entry.params.fontScale ?? 1,
            showSystemUi: false,
            showBackground: entry.params.showBackground ?? false,
            backgroundColor: entry.params.backgroundColor ?? 0,
            uiMode: entry.params.uiMode ?? 0,
            locale: entry.params.locale ?? null,
            group: entry.params.group ?? null,
        };
        const rendered = renderById.get(entry.id);
        const captures: Capture[] = [
            {
                advanceTimeMillis: null,
                scroll: null,
                renderOutput: rendered ? rendered.outputFile : "",
            },
        ];
        return {
            id: entry.id,
            functionName: entry.functionName,
            className: entry.className,
            sourceFile: entry.sourceFile ?? null,
            params,
            captures,
        };
    });
}
