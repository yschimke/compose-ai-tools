import * as vscode from 'vscode';
import { detectPreviews } from './previewDetection';
import { PreviewRegistry } from './previewRegistry';

// Hover image is a peek of the rendered preview. VS Code's hover markdown
// doesn't reliably honor inline `max-width`/`max-height` on images (the
// render appears clipped), so we set explicit `width`/`height` HTML
// attributes instead. 120px is small enough to keep the hover compact and
// leave room for the function name above it.
const HOVER_IMG_MAX = 120;

export class PreviewHoverProvider implements vscode.HoverProvider {
    constructor(
        private registry: PreviewRegistry,
        private log?: (msg: string) => void,
    ) {}

    async provideHover(doc: vscode.TextDocument, position: vscode.Position): Promise<vscode.Hover | undefined> {
        if (doc.languageId !== 'kotlin') { return; }
        const detected = await detectPreviews(doc, this.registry, this.log);
        for (const det of detected) {
            if (!det.nameRange.contains(position)) { continue; }
            const entry = this.registry.find(doc.uri.fsPath, det.functionName);

            const md = new vscode.MarkdownString();
            md.isTrusted = true;
            md.supportHtml = true;
            md.appendMarkdown(`**${det.functionName}** — Compose Preview\n\n`);

            if (entry?.imageBase64) {
                // Derive MIME from the primary capture's renderOutput so
                // GIF-mode previews hover as animated GIFs instead of a
                // static first frame interpreted as PNG.
                const primary = entry.preview.captures?.[0]?.renderOutput ?? '';
                const mime = typeof primary === 'string' && primary.toLowerCase().endsWith('.gif')
                    ? 'image/gif'
                    : 'image/png';
                md.appendMarkdown(
                    `<img src="data:${mime};base64,${entry.imageBase64}" `
                    + `width="${HOVER_IMG_MAX}" height="${HOVER_IMG_MAX}" />`,
                );
            } else {
                md.appendMarkdown('_No render yet._ ');
                const args = encodeURIComponent(JSON.stringify([doc.uri.fsPath]));
                md.appendMarkdown(`[Run Preview](command:composePreview.runForFile?${args})`);
            }
            return new vscode.Hover(md, det.nameRange);
        }
        return undefined;
    }
}
