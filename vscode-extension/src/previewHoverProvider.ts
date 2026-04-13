import * as vscode from 'vscode';
import { detectPreviews } from './previewDetection';
import { PreviewRegistry } from './previewRegistry';

const MAX_HOVER_WIDTH = 320;

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
                    `<img src="data:image/png;base64,${entry.imageBase64}" width="${MAX_HOVER_WIDTH}" />`,
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
