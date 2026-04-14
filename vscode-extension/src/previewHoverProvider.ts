import * as vscode from 'vscode';
import { detectPreviews } from './previewDetection';
import { PreviewRegistry } from './previewRegistry';

// Hover image is a compact thumbnail, not a full-size preview — keep it
// well under 100px so it sits beside the function name without dominating
// the hover card. The full-size render lives in the side panel.
const HOVER_IMG_MAX = 80;

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
                md.appendMarkdown(
                    `<img src="data:image/png;base64,${entry.imageBase64}" `
                    + `style="max-width:${HOVER_IMG_MAX}px;max-height:${HOVER_IMG_MAX}px;object-fit:contain" />`,
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
