import * as vscode from "vscode";
import { detectPreviews } from "./previewDetection";
import { PreviewRegistry } from "./previewRegistry";

/**
 * Renders a clickable "Focus in Preview panel" lens above every detected
 * `@Preview` function. The gutter icon itself can't receive click events
 * (VS Code doesn't expose clicks on decoration gutter icons), so this
 * CodeLens is the actual affordance users click to focus a preview.
 */
export class PreviewCodeLensProvider implements vscode.CodeLensProvider {
    private readonly emitter = new vscode.EventEmitter<void>();
    readonly onDidChangeCodeLenses = this.emitter.event;

    constructor(
        private registry: PreviewRegistry,
        private log?: (msg: string) => void,
    ) {
        registry.onDidChange(() => this.emitter.fire());
    }

    async provideCodeLenses(
        doc: vscode.TextDocument,
    ): Promise<vscode.CodeLens[]> {
        if (doc.languageId !== "kotlin") {
            return [];
        }
        const detected = await detectPreviews(doc, this.registry, this.log);
        const filePath = doc.uri.fsPath;
        return detected.map(
            (det) =>
                new vscode.CodeLens(
                    new vscode.Range(
                        det.funLineNumber,
                        0,
                        det.funLineNumber,
                        0,
                    ),
                    {
                        title: "$(eye) Focus in Preview panel",
                        command: "composePreview.focusPreview",
                        arguments: [det.functionName, filePath],
                    },
                ),
        );
    }

    dispose(): void {
        this.emitter.dispose();
    }
}
