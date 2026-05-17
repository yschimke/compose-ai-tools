// Editor-style webview panel for an opened preview bundle.
//
// The bundle is a `composePreviewBundle` polyglot file (PNG header
// followed by a zip). We spawn the desktop daemon JVM bound to that
// bundle's classpath via `compose-preview bundle daemon`, then surface
// the daemon's renders + data extensions in a panel that reuses the
// existing `<preview-app>` Lit element in `bundle-mode`. The daemon
// stays resident for the lifetime of the tab so focus-mode features
// (data-extension chips, a11y overlay, interactive, recording) work the
// same way as in the sidebar panel — they're just sourced from a packed
// bundle rather than a Gradle module.
//
// One panel per absolute bundle path; opening the same bundle twice
// reveals the existing tab rather than spawning a duplicate daemon.

import * as crypto from "crypto";
import * as fs from "fs";
import * as path from "path";
import * as vscode from "vscode";
import {
    BundleContents,
    bundleLabel,
    BundleFormatError,
    readBundleContents,
} from "./bundleFormat";
import { BundleCliNotFoundError, locateBundleCli } from "./bundleRender";
import {
    A11Y_DATA_KINDS,
    BundleDaemonHandle,
    renderAll,
    setKindsSubscribed,
    spawnBundleDaemon,
} from "./bundleDaemonHost";
import { DaemonClient } from "./daemon/daemonClient";
import {
    DataProductAttachment,
    RenderFailedParams,
    RenderFinishedParams,
} from "./daemon/daemonProtocol";
import type { Capture, PreviewInfo, PreviewParams } from "./types";

const CHANNEL = "[bundle-viewer]";
const CLIENT_VERSION = "compose-preview-bundle-viewer/1";

export interface BundleViewerHostDeps {
    extensionUri: vscode.Uri;
    earlyFeaturesEnabled: () => boolean;
    logLine: (message: string) => void;
}

/**
 * Webview messages this panel reads from `<preview-app>`. Same wire
 * shapes as the sidebar router, just scoped to the subset that makes
 * sense for a bundle (no module-relative file opens, no Gradle refresh).
 */
type WebviewMessage =
    | { command: "webviewReady" }
    | { command: "openExternal"; url: string }
    | { command: "setA11yOverlay"; previewId: string; enabled: boolean }
    | {
          command: "setDataExtensionEnabled";
          previewId: string;
          kinds: string[];
          enabled: boolean;
      }
    | { command: "refreshHeavy"; previewId: string }
    | { command: "previewScopeChanged"; previewId: string | null }
    | {
          command: "viewportUpdated";
          visible: string[];
          predicted: string[];
      };

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

    private disposed = false;
    private daemon: BundleDaemonHandle | null = null;
    /** Webview signalled `webviewReady`; safe to push messages after this. */
    private webviewReady = false;
    /** Queued initial `setPreviews` until the webview is ready (the panel
     *  can boot before its first `firstUpdated` if the user has the tab in
     *  the background while VS Code restores window state). */
    private pendingSetPreviews: PreviewInfo[] | null = null;
    /** Active a11y-overlay subscriptions, used so `dispose` can unwind
     *  the daemon-side state cleanly even when the panel closes
     *  abruptly. */
    private readonly a11yOverlays = new Set<string>();
    /** `(previewId, kind)` subscriptions started via the data-extension
     *  chip-bar. Mirrors the wire state so a chip toggle off (or panel
     *  disposal) unsubscribes exactly what we subscribed to. */
    private readonly dataSubscriptions = new Map<string, Set<string>>();

    private constructor(
        private readonly bundlePath: string,
        private readonly panel: vscode.WebviewPanel,
        private readonly deps: BundleViewerHostDeps,
        private readonly contents: BundleContents,
    ) {
        panel.webview.html = this.buildHtml();
        panel.onDidDispose(() => this.dispose());
        panel.webview.onDidReceiveMessage((msg: WebviewMessage) => {
            void this.onWebviewMessage(msg);
        });
    }

    private dispose(): void {
        if (this.disposed) return;
        this.disposed = true;
        BundleViewerPanel.active.delete(this.bundlePath);
        this.daemon?.dispose();
        this.daemon = null;
    }

    private start(): void {
        void this.spawnDaemonAndSeed();
    }

    private async spawnDaemonAndSeed(): Promise<void> {
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
        let daemon: BundleDaemonHandle;
        try {
            daemon = await vscode.window.withProgress(
                {
                    location: vscode.ProgressLocation.Notification,
                    title: `Starting preview daemon for ${bundleLabel(this.bundlePath)}…`,
                    cancellable: false,
                },
                () =>
                    spawnBundleDaemon({
                        bundlePath: this.bundlePath,
                        cliPath,
                        logger: {
                            appendLine: (line) => this.deps.logLine(line),
                        },
                        clientVersion: CLIENT_VERSION,
                        events: {
                            onRenderFinished: (params) =>
                                void this.onRenderFinished(params),
                            onRenderFailed: (params) =>
                                this.onRenderFailed(params),
                        },
                    }),
            );
        } catch (err) {
            const message = (err as Error).message ?? String(err);
            this.deps.logLine(`${CHANNEL} daemon spawn failed: ${message}`);
            this.postError("Bundle daemon failed to start", message);
            return;
        }
        this.daemon = daemon;
        // If the JVM dies while the panel is still open, surface it once
        // and dispose so a follow-up open spawns a fresh daemon rather
        // than re-using the dead handle.
        daemon.exited.then((code) => {
            if (this.disposed || code === null || code === 0) return;
            this.postError(
                "Bundle daemon exited",
                `JVM exit code ${code} — open the Compose Preview output channel for details.`,
            );
            this.dispose();
        });

        const previews = synthesisePreviews(this.contents);
        this.pendingSetPreviews = previews;
        if (this.webviewReady) this.flushPendingSetPreviews();

        // Tell the daemon the panel cares about every preview in the
        // bundle. `setVisible` ungates the scheduler and lets
        // subsequent `renderNow` calls actually fan out.
        const ids = previews.map((p) => p.id);
        try {
            daemon.client.setVisible({ ids });
        } catch (err) {
            this.deps.logLine(
                `${CHANNEL} setVisible failed: ${(err as Error).message}`,
            );
        }
        try {
            await renderAll(daemon.client, ids, "bundle-open");
        } catch (err) {
            this.deps.logLine(
                `${CHANNEL} initial renderNow failed: ${(err as Error).message}`,
            );
        }
    }

    private flushPendingSetPreviews(): void {
        const previews = this.pendingSetPreviews;
        if (!previews) return;
        this.pendingSetPreviews = null;
        // Signal "daemon-backed mode is live" to the webview by writing a
        // truthy moduleDir — `<preview-app>`'s bundle-mode toolbar gating
        // unhides the daemon-driven buttons once it sees a non-empty
        // module id. Bundle path serves as the synthetic id.
        this.panel.webview.postMessage({
            command: "setPreviews",
            previews,
            moduleDir: `bundle:${this.bundlePath}`,
            heavyStaleIds: [],
        });
        this.panel.webview.postMessage({
            command: "bundleDaemonReady",
            bundlePath: this.bundlePath,
        });
    }

    private async onRenderFinished(
        params: RenderFinishedParams,
    ): Promise<void> {
        if (this.disposed) return;
        try {
            const buf = await fs.promises.readFile(params.pngPath);
            this.panel.webview.postMessage({
                command: "updateImage",
                previewId: params.id,
                captureIndex: 0,
                imageData: buf.toString("base64"),
            });
        } catch (err) {
            this.deps.logLine(
                `${CHANNEL} read renderFinished png ${params.pngPath}: ${(err as Error).message}`,
            );
        }
        // Forward subscribed data-product attachments to the webview.
        // Same shape the sidebar's `updateA11y` / `updateDataProducts`
        // uses; the bundle viewer's <preview-app> consumes them
        // identically.
        if (params.dataProducts?.length) {
            this.forwardDataProducts(params.id, params.dataProducts);
        }
    }

    private onRenderFailed(params: RenderFailedParams): void {
        if (this.disposed) return;
        this.panel.webview.postMessage({
            command: "setImageError",
            previewId: params.id,
            captureIndex: 0,
            message: params.error?.message ?? "Render failed",
        });
    }

    /**
     * Split [attachments] into the a11y bucket (which the webview
     * consumes via `updateA11y` with structured findings + hierarchy
     * nodes) and the generic bucket (`updateDataProducts`). Mirrors the
     * sidebar's mapping at extension.ts:onRenderFinished.
     */
    private forwardDataProducts(
        previewId: string,
        attachments: DataProductAttachment[],
    ): void {
        let findings: unknown = undefined;
        let nodes: unknown = undefined;
        const others: { kind: string; payload: unknown }[] = [];
        for (const att of attachments) {
            if (att.kind === "a11y/atf") {
                findings = att.payload ?? null;
            } else if (att.kind === "a11y/hierarchy") {
                nodes = att.payload ?? null;
            } else {
                others.push({ kind: att.kind, payload: att.payload });
            }
        }
        if (findings !== undefined || nodes !== undefined) {
            this.panel.webview.postMessage({
                command: "updateA11y",
                previewId,
                findings,
                nodes,
            });
        }
        if (others.length > 0) {
            this.panel.webview.postMessage({
                command: "updateDataProducts",
                previewId,
                dataProducts: others,
            });
        }
    }

    private postError(title: string, detail: string): void {
        void vscode.window.showErrorMessage(
            `Compose Preview — ${path.basename(this.bundlePath)}: ${title}. ${detail}`,
        );
    }

    private async onWebviewMessage(msg: WebviewMessage): Promise<void> {
        if (this.disposed) return;
        switch (msg.command) {
            case "webviewReady":
                this.webviewReady = true;
                if (this.pendingSetPreviews) this.flushPendingSetPreviews();
                break;
            case "openExternal":
                if (msg.url && /^https?:\/\//i.test(msg.url)) {
                    void vscode.env.openExternal(vscode.Uri.parse(msg.url));
                }
                break;
            case "setA11yOverlay":
                await this.handleA11yOverlay(msg.previewId, msg.enabled);
                break;
            case "setDataExtensionEnabled":
                await this.handleDataExtensionToggle(
                    msg.previewId,
                    msg.kinds,
                    msg.enabled,
                );
                break;
            case "refreshHeavy":
                await this.requestRender([msg.previewId], "refresh-heavy");
                break;
            case "previewScopeChanged":
                // Informational — the bundle viewer ignores scope shifts
                // because every preview in the bundle is "in scope".
                break;
            case "viewportUpdated":
                this.daemon?.client.setVisible({ ids: msg.visible });
                break;
        }
    }

    private async handleA11yOverlay(
        previewId: string,
        enabled: boolean,
    ): Promise<void> {
        if (!this.daemon) return;
        if (enabled) {
            this.a11yOverlays.add(previewId);
        } else {
            this.a11yOverlays.delete(previewId);
        }
        await setKindsSubscribed(
            this.daemon.client,
            previewId,
            A11Y_DATA_KINDS,
            enabled,
            (kind, err) =>
                this.deps.logLine(
                    `${CHANNEL} data/${enabled ? "subscribe" : "unsubscribe"} ${kind} ${previewId} failed: ${err.message}`,
                ),
        );
        if (!enabled) {
            // Tear down panel-side cache the same way the sidebar does —
            // an empty `updateA11y` clears the overlay without waiting
            // for a fresh renderFinished.
            this.panel.webview.postMessage({
                command: "updateA11y",
                previewId,
                findings: null,
                nodes: null,
            });
        } else {
            await this.requestRender([previewId], "a11y-overlay-on");
        }
    }

    private async handleDataExtensionToggle(
        previewId: string,
        kinds: string[],
        enabled: boolean,
    ): Promise<void> {
        if (!this.daemon) return;
        const bucket =
            this.dataSubscriptions.get(previewId) ?? new Set<string>();
        for (const kind of kinds) {
            if (enabled) bucket.add(kind);
            else bucket.delete(kind);
        }
        if (bucket.size === 0) this.dataSubscriptions.delete(previewId);
        else this.dataSubscriptions.set(previewId, bucket);
        await setKindsSubscribed(
            this.daemon.client,
            previewId,
            kinds,
            enabled,
            (kind, err) =>
                this.deps.logLine(
                    `${CHANNEL} data ${enabled ? "subscribe" : "unsubscribe"} ${kind} ${previewId}: ${err.message}`,
                ),
        );
        if (enabled) {
            // Trigger a render so the daemon attaches the newly-subscribed
            // kinds; mirrors what the sidebar does after a chip toggle.
            await this.requestRender([previewId], "extension-on");
        }
    }

    private async requestRender(
        previews: string[],
        reason: string,
    ): Promise<void> {
        const client: DaemonClient | undefined = this.daemon?.client;
        if (!client) return;
        try {
            await client.renderNow({ previews, tier: "full", reason });
        } catch (err) {
            this.deps.logLine(
                `${CHANNEL} renderNow ${previews.join(",")} (${reason}) failed: ${(err as Error).message}`,
            );
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
 * Map the bundle's discovery-time `previews.json` into the `PreviewInfo`
 * shape `<preview-app>` consumes via `setPreviews`. Images arrive later
 * via daemon `renderFinished` notifications → host `updateImage`
 * postMessages, so the synthetic `Capture[]` here carries a placeholder
 * `renderOutput` string that the panel only uses as a key.
 */
function synthesisePreviews(contents: BundleContents): PreviewInfo[] {
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
        const captures: Capture[] = [
            {
                advanceTimeMillis: null,
                scroll: null,
                renderOutput: `${entry.id}.png`,
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
