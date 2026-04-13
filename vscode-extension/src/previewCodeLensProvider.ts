import * as vscode from 'vscode';
import { detectPreviews } from './previewDetection';
import { PreviewRegistry } from './previewRegistry';

export class PreviewCodeLensProvider implements vscode.CodeLensProvider {
    private _onDidChangeCodeLenses = new vscode.EventEmitter<void>();
    readonly onDidChangeCodeLenses = this._onDidChangeCodeLenses.event;

    constructor(
        private registry: PreviewRegistry,
        private log?: (msg: string) => void,
    ) {
        registry.onDidChange(() => this._onDidChangeCodeLenses.fire());
    }

    async provideCodeLenses(doc: vscode.TextDocument): Promise<vscode.CodeLens[]> {
        if (doc.languageId !== 'kotlin') { return []; }
        const detected = await detectPreviews(doc, this.registry, this.log);
        return detected.map(det => {
            const entry = this.registry.find(doc.uri.fsPath, det.functionName);
            const hasRender = entry?.imageBase64 !== undefined;
            const range = new vscode.Range(det.funLineNumber, 0, det.funLineNumber, 0);
            return new vscode.CodeLens(range, {
                title: hasRender ? '$(play) Re-render Preview' : '$(play) Run Preview',
                command: 'composePreview.runForFile',
                arguments: [doc.uri.fsPath],
            });
        });
    }

    dispose(): void {
        this._onDidChangeCodeLenses.dispose();
    }
}
