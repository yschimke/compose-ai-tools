import * as vscode from 'vscode';
import { detectPreviews } from './previewDetection';
import { PreviewRegistry } from './previewRegistry';

/**
 * Paints a gutter marker next to every function the plugin recognised as a
 * `@Preview` (or transitive multi-preview). Purely visual — interaction lives
 * in the hover provider, which offers Run/Re-render links when the user hovers
 * the marked line.
 */
export class PreviewGutterDecorations implements vscode.Disposable {
    private readonly decoration: vscode.TextEditorDecorationType;
    private readonly disposables: vscode.Disposable[] = [];
    /** Debounce per-document to avoid re-decorating on every keystroke. */
    private pending = new Map<string, NodeJS.Timeout>();

    constructor(
        extensionUri: vscode.Uri,
        private registry: PreviewRegistry,
        private log?: (msg: string) => void,
    ) {
        this.decoration = vscode.window.createTextEditorDecorationType({
            gutterIconPath: vscode.Uri.joinPath(extensionUri, 'media', 'preview-gutter.svg'),
            gutterIconSize: 'contain',
            isWholeLine: false,
        });

        this.disposables.push(
            this.decoration,
            vscode.window.onDidChangeVisibleTextEditors(editors =>
                editors.forEach(ed => this.scheduleUpdate(ed))),
            vscode.workspace.onDidChangeTextDocument(e => {
                for (const ed of vscode.window.visibleTextEditors) {
                    if (ed.document === e.document) { this.scheduleUpdate(ed); }
                }
            }),
            registry.onDidChange(() =>
                vscode.window.visibleTextEditors.forEach(ed => this.scheduleUpdate(ed))),
        );

        vscode.window.visibleTextEditors.forEach(ed => this.scheduleUpdate(ed));
    }

    private scheduleUpdate(editor: vscode.TextEditor): void {
        if (editor.document.languageId !== 'kotlin') { return; }
        const key = editor.document.uri.toString();
        const existing = this.pending.get(key);
        if (existing) { clearTimeout(existing); }
        this.pending.set(key, setTimeout(() => {
            this.pending.delete(key);
            void this.update(editor);
        }, 150));
    }

    private async update(editor: vscode.TextEditor): Promise<void> {
        if (editor.document.languageId !== 'kotlin') {
            editor.setDecorations(this.decoration, []);
            return;
        }
        const detected = await detectPreviews(editor.document, this.registry, this.log);
        const filePath = editor.document.uri.fsPath;
        const options: vscode.DecorationOptions[] = detected.map(det => {
            // Hover the gutter icon (or any part of the `fun` line) to expose
            // a command link that filters the side panel to this function and
            // reveals the view. Approximates "click the gutter icon to focus"
            // since decoration gutter icons don't support direct click events.
            const args = encodeURIComponent(JSON.stringify([det.functionName, filePath]));
            const hover = new vscode.MarkdownString(
                `[Focus \`${det.functionName}\` in Preview panel](command:composePreview.focusPreview?${args})`,
            );
            hover.isTrusted = true;
            return {
                range: new vscode.Range(det.funLineNumber, 0, det.funLineNumber, 0),
                hoverMessage: hover,
            };
        });
        editor.setDecorations(this.decoration, options);
    }

    dispose(): void {
        this.pending.forEach(t => clearTimeout(t));
        this.pending.clear();
        this.disposables.forEach(d => d.dispose());
    }
}
