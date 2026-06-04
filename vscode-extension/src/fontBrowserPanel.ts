// Editor-style webview panel: a Google Fonts browser.
//
// Search the keyless Google Fonts catalog, download a family into the
// extension's global-storage cache, and live-customise the downloaded
// face (weight / italic / size / spacing, plus per-axis sliders for
// variable fonts) with a generated Compose snippet.
//
// The panel owns all network + disk work; the webview only renders and
// posts intent. One panel per window — re-invoking the command reveals
// the existing tab.

import * as crypto from "crypto";
import * as fsp from "fs/promises";
import * as path from "path";
import * as vscode from "vscode";
import {
    buildCss2DownloadUrl,
    type FontCatalog,
    type FontFamilyMeta,
} from "./googleFontsCatalog";
import {
    downloadFontBytes,
    fetchCss2Faces,
    fetchFontCatalog,
    type Css2Face,
} from "./googleFontsClient";
import {
    DownloadedFontsStore,
    nodeStorageFs,
    slugFamily,
    type DownloadedFont,
} from "./downloadedFontsStore";
import type {
    DownloadedFontView,
    FaceView,
} from "./webview/fonts/fontBrowserLogic";
import type { HostToWebview, WebviewToHost } from "./webview/fonts/protocol";

const CHANNEL = "[font-browser]";
const CATALOG_TTL_MS = 7 * 24 * 60 * 60 * 1000;

export interface FontBrowserDeps {
    extensionUri: vscode.Uri;
    globalStorageUri: vscode.Uri;
    logLine: (message: string) => void;
}

export class FontBrowserPanel {
    private static current: FontBrowserPanel | null = null;

    static open(deps: FontBrowserDeps): FontBrowserPanel {
        if (FontBrowserPanel.current) {
            FontBrowserPanel.current.panel.reveal(vscode.ViewColumn.Active);
            return FontBrowserPanel.current;
        }
        const store = new DownloadedFontsStore(
            deps.globalStorageUri.fsPath,
            nodeStorageFs(),
        );
        const panel = vscode.window.createWebviewPanel(
            "composePreview.fontBrowser",
            "Google Fonts",
            vscode.ViewColumn.Active,
            {
                enableScripts: true,
                retainContextWhenHidden: true,
                localResourceRoots: [
                    deps.extensionUri,
                    vscode.Uri.file(store.resourceRoot),
                ],
            },
        );
        const browser = new FontBrowserPanel(panel, deps, store);
        FontBrowserPanel.current = browser;
        return browser;
    }

    private disposed = false;
    private catalog: FontCatalog | null = null;

    private constructor(
        private readonly panel: vscode.WebviewPanel,
        private readonly deps: FontBrowserDeps,
        private readonly store: DownloadedFontsStore,
    ) {
        panel.webview.html = this.buildHtml();
        panel.onDidDispose(() => this.dispose());
        panel.webview.onDidReceiveMessage((msg: WebviewToHost) => {
            void this.onMessage(msg);
        });
    }

    private dispose(): void {
        if (this.disposed) return;
        this.disposed = true;
        if (FontBrowserPanel.current === this) {
            FontBrowserPanel.current = null;
        }
    }

    private postToWebview(msg: HostToWebview): void {
        if (this.disposed) return;
        void this.panel.webview.postMessage(msg);
    }

    private async onMessage(msg: WebviewToHost): Promise<void> {
        if (this.disposed) return;
        switch (msg.command) {
            case "ready":
                await this.sendDownloaded();
                await this.loadCatalog(false);
                break;
            case "refreshCatalog":
                await this.loadCatalog(true);
                break;
            case "download":
                await this.handleDownload(msg.family);
                break;
            case "removeDownloaded":
                await this.handleRemove(msg.familyId);
                break;
            case "copySnippet":
                await vscode.env.clipboard.writeText(msg.text);
                void vscode.window.showInformationMessage(
                    "Compose font snippet copied to clipboard.",
                );
                break;
            case "openExternal":
                if (/^https?:\/\//i.test(msg.url)) {
                    void vscode.env.openExternal(vscode.Uri.parse(msg.url));
                }
                break;
        }
    }

    // ---- catalog --------------------------------------------------------

    private get catalogCachePath(): string {
        return path.join(
            this.deps.globalStorageUri.fsPath,
            "fonts",
            "catalog.json",
        );
    }

    private async loadCatalog(forceRefresh: boolean): Promise<void> {
        this.postToWebview({ command: "catalogLoading" });
        if (!forceRefresh) {
            if (this.catalog) {
                this.postToWebview({
                    command: "catalog",
                    catalog: this.catalog,
                });
                return;
            }
            const cached = await this.readCachedCatalog(false);
            if (cached) {
                this.catalog = cached;
                this.postToWebview({ command: "catalog", catalog: cached });
                return;
            }
        }
        try {
            const catalog = await fetchFontCatalog();
            this.catalog = catalog;
            await this.writeCachedCatalog(catalog);
            this.postToWebview({ command: "catalog", catalog });
        } catch (err) {
            const message = (err as Error).message;
            this.deps.logLine(`${CHANNEL} catalog fetch failed: ${message}`);
            // Fall back to any cached catalog if we have one before
            // erroring — here we deliberately ignore the TTL: a stale
            // catalog is far more useful than an empty error state when
            // the network is unavailable.
            const stale = this.catalog ?? (await this.readCachedCatalog(true));
            if (stale) {
                this.catalog = stale;
                this.postToWebview({ command: "catalog", catalog: stale });
            } else {
                this.postToWebview({ command: "catalogError", message });
            }
        }
    }

    /**
     * Read the on-disk catalog cache. With [ignoreTtl] false (the normal
     * pre-fetch path) a catalog older than [CATALOG_TTL_MS] is rejected
     * so we refresh from the network; with it true (the network-failure
     * fallback) any parseable cache is returned — stale data beats an
     * error screen when we're offline.
     */
    private async readCachedCatalog(
        ignoreTtl: boolean,
    ): Promise<FontCatalog | null> {
        try {
            const raw = await fsp.readFile(this.catalogCachePath, "utf8");
            const catalog = JSON.parse(raw) as FontCatalog;
            if (!ignoreTtl) {
                const age = Date.now() - Date.parse(catalog.fetchedAt);
                if (!Number.isFinite(age) || age > CATALOG_TTL_MS) return null;
            }
            return catalog;
        } catch {
            return null;
        }
    }

    private async writeCachedCatalog(catalog: FontCatalog): Promise<void> {
        try {
            await fsp.mkdir(path.dirname(this.catalogCachePath), {
                recursive: true,
            });
            await fsp.writeFile(
                this.catalogCachePath,
                JSON.stringify(catalog),
                "utf8",
            );
        } catch (err) {
            this.deps.logLine(
                `${CHANNEL} catalog cache write failed: ${(err as Error).message}`,
            );
        }
    }

    // ---- downloads ------------------------------------------------------

    private async handleDownload(family: string): Promise<void> {
        const familyId = slugFamily(family);
        const meta = this.findMeta(family);
        if (!meta) {
            this.postToWebview({
                command: "downloadState",
                familyId,
                family,
                state: "error",
                message: "Font not found in catalog",
            });
            return;
        }
        this.postToWebview({
            command: "downloadState",
            familyId,
            family,
            state: "downloading",
        });
        try {
            const faces = await this.downloadFamily(meta);
            if (faces.length === 0) {
                throw new Error("Google returned no downloadable font files");
            }
            await this.store.add({
                family: meta.family,
                category: meta.category,
                isVariable: meta.isVariable,
                axes: [...meta.axes],
                faces,
            });
            this.postToWebview({
                command: "downloadState",
                familyId,
                family,
                state: "done",
            });
            await this.sendDownloaded();
            this.deps.logLine(
                `${CHANNEL} downloaded ${meta.family} (${faces.length} faces)`,
            );
        } catch (err) {
            const message = (err as Error).message;
            this.deps.logLine(
                `${CHANNEL} download ${family} failed: ${message}`,
            );
            this.postToWebview({
                command: "downloadState",
                familyId,
                family,
                state: "error",
                message,
            });
            void vscode.window.showErrorMessage(
                `Compose Preview — failed to download ${family}: ${message}`,
            );
        }
    }

    private async downloadFamily(
        meta: FontFamilyMeta,
    ): Promise<{ face: Css2Face; bytes: Uint8Array }[]> {
        const css2Url = buildCss2DownloadUrl(meta);
        const cssFaces = await fetchCss2Faces(css2Url);
        // De-duplicate by file URL so the same gstatic file isn't fetched
        // twice (variable fonts emit one block per ital tuple).
        const seen = new Map<string, Css2Face>();
        for (const face of cssFaces) {
            if (!seen.has(face.url)) seen.set(face.url, face);
        }
        const out: { face: Css2Face; bytes: Uint8Array }[] = [];
        for (const face of seen.values()) {
            const bytes = await downloadFontBytes(face.url);
            out.push({ face, bytes });
        }
        return out;
    }

    private async handleRemove(familyId: string): Promise<void> {
        await this.store.remove(familyId);
        await this.sendDownloaded();
    }

    private async sendDownloaded(): Promise<void> {
        const fonts = await this.store.list();
        this.postToWebview({
            command: "downloaded",
            fonts: fonts.map((f) => this.toView(f)),
        });
    }

    private toView(font: DownloadedFont): DownloadedFontView {
        const faces: FaceView[] = font.faces.map((face) => ({
            style: face.style,
            weightMin: face.weightMin,
            weightMax: face.weightMax,
            format: face.format,
            uri: this.panel.webview
                .asWebviewUri(vscode.Uri.file(this.store.facePath(font, face)))
                .toString(),
        }));
        return {
            family: font.family,
            familyId: font.familyId,
            category: font.category,
            isVariable: font.isVariable,
            axes: font.axes,
            faces,
        };
    }

    private findMeta(family: string): FontFamilyMeta | null {
        if (!this.catalog) return null;
        const lower = family.toLowerCase();
        return (
            this.catalog.families.find(
                (f) => f.family.toLowerCase() === lower,
            ) ?? null
        );
    }

    // ---- html -----------------------------------------------------------

    private buildHtml(): string {
        const webview = this.panel.webview;
        const nonce = crypto.randomBytes(16).toString("hex");
        const styleUri = webview.asWebviewUri(
            vscode.Uri.joinPath(this.deps.extensionUri, "media", "fonts.css"),
        );
        const scriptUri = webview.asWebviewUri(
            vscode.Uri.joinPath(
                this.deps.extensionUri,
                "media",
                "webview",
                "fonts.js",
            ),
        );
        // `style-src` carries `'unsafe-inline'` (not a nonce) because the
        // webview sets inline `style="font-variation-settings: …"` on the
        // live specimen and injects `@font-face` rules at runtime —
        // attribute-level inline styles can't be nonced. Scripts stay
        // locked to the nonce. Font files come from gstatic (browse
        // previews) and the webview-served cache (`cspSource`).
        const csp = [
            "default-src 'none'",
            `img-src ${webview.cspSource} data:`,
            `font-src ${webview.cspSource} https://fonts.gstatic.com`,
            `style-src ${webview.cspSource} https://fonts.googleapis.com 'unsafe-inline'`,
            `script-src 'nonce-${nonce}'`,
        ].join("; ");
        return /* html */ `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta http-equiv="Content-Security-Policy" content="${csp};">
    <link href="${styleUri}" rel="stylesheet">
</head>
<body>
    <font-browser-app></font-browser-app>
    <script nonce="${nonce}" src="${scriptUri}"></script>
</body>
</html>`;
    }
}
