import * as vscode from 'vscode';
import { PreviewRegistry } from './previewRegistry';
import { detectPreviews } from './previewDetection';

/**
 * Publishes ATF findings collected in the [PreviewRegistry] as VS Code
 * diagnostics so they show up in the Problems panel and as squigglies on the
 * `@Preview` annotation line.
 *
 * The registry holds the canonical (sourceFile, functionName) → findings
 * mapping; this class only handles the projection to open documents. It
 * listens for registry changes (new render completed) and for document
 * open/edit events so findings re-render against the current annotation
 * position if the user added lines above.
 *
 * Reuses [detectPreviews] rather than doing its own regex scan — that way we
 * agree with the gutter / code lens / hover providers on where each preview
 * annotation sits, and we get multi-preview resolution for free.
 */
export class PreviewA11yDiagnostics implements vscode.Disposable {
    private readonly collection: vscode.DiagnosticCollection;
    private readonly disposables: vscode.Disposable[] = [];
    /** Per-document debounce so rapid keystrokes collapse into one refresh. */
    private pending = new Map<string, NodeJS.Timeout>();

    constructor(
        private readonly registry: PreviewRegistry,
        private readonly log?: (msg: string) => void,
    ) {
        this.collection = vscode.languages.createDiagnosticCollection('compose-preview-a11y');
        this.disposables.push(this.collection);
        this.disposables.push(registry.onDidChange(() => this.refreshAll()));
        this.disposables.push(
            vscode.workspace.onDidOpenTextDocument((doc) => this.scheduleRefresh(doc)),
        );
        this.disposables.push(
            vscode.workspace.onDidChangeTextDocument((e) => this.scheduleRefresh(e.document)),
        );
        this.disposables.push(
            vscode.workspace.onDidCloseTextDocument((doc) => this.collection.delete(doc.uri)),
        );
        // Seed currently-open documents so findings render on first load
        // without the user having to re-open anything.
        for (const doc of vscode.workspace.textDocuments) {
            this.scheduleRefresh(doc);
        }
    }

    dispose(): void {
        for (const t of this.pending.values()) { clearTimeout(t); }
        this.pending.clear();
        for (const d of this.disposables) { d.dispose(); }
    }

    private refreshAll(): void {
        for (const doc of vscode.workspace.textDocuments) {
            this.scheduleRefresh(doc);
        }
    }

    private scheduleRefresh(doc: vscode.TextDocument): void {
        if (doc.languageId !== 'kotlin') { return; }
        const key = doc.uri.toString();
        const existing = this.pending.get(key);
        if (existing) { clearTimeout(existing); }
        this.pending.set(key, setTimeout(() => {
            this.pending.delete(key);
            void this.refreshDocument(doc);
        }, 200));
    }

    private async refreshDocument(doc: vscode.TextDocument): Promise<void> {
        if (doc.languageId !== 'kotlin') {
            this.collection.delete(doc.uri);
            return;
        }
        const detected = await detectPreviews(doc, this.registry, this.log);
        const diagnostics: vscode.Diagnostic[] = [];
        for (const det of detected) {
            const entry = this.registry.find(doc.uri.fsPath, det.functionName);
            const findings = entry?.preview.a11yFindings;
            if (!findings || findings.length === 0) { continue; }
            const line = det.funLineNumber;
            const range = new vscode.Range(line, 0, line, doc.lineAt(line).text.length);
            for (const f of findings) {
                if (f.level === 'INFO') { continue; }
                const diag = new vscode.Diagnostic(
                    range,
                    `${f.type}: ${f.message}`,
                    f.level === 'ERROR' ? vscode.DiagnosticSeverity.Error : vscode.DiagnosticSeverity.Warning,
                );
                diag.source = 'compose-preview-a11y';
                diag.code = f.type;
                diagnostics.push(diag);
            }
        }
        if (diagnostics.length === 0) {
            this.collection.delete(doc.uri);
        } else {
            this.collection.set(doc.uri, diagnostics);
        }
    }
}
