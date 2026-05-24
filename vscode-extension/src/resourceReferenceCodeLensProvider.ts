import * as path from "node:path";
import * as vscode from "vscode";
import { dedupRefsByResource } from "./androidResourceReferences";
import { GradleService } from "./gradleService";
import { referencesFor } from "./resourceReferenceHoverProvider";

/**
 * Surfaces a `$(file-media) Preview <type>/<name>` CodeLens above the
 * first occurrence of each unique drawable / mipmap reference in the
 * open file. One lens per unique resource per file — a layout that
 * references `@drawable/foo` four times gets one lens, not four. Click
 * → opens the rendered PNG via the existing `composePreview.previewResource`
 * command.
 *
 * Pairs with [ResourceReferenceHoverProvider]: the lens advertises that
 * a preview exists, the hover shows it.
 */
export class ResourceReferenceCodeLensProvider
    implements vscode.CodeLensProvider
{
    private readonly emitter = new vscode.EventEmitter<void>();
    readonly onDidChangeCodeLenses = this.emitter.event;

    constructor(private readonly gradleService: GradleService) {}

    /** Re-emit after a render run produces new captures. */
    refresh(): void {
        this.emitter.fire();
    }

    provideCodeLenses(doc: vscode.TextDocument): vscode.CodeLens[] {
        const refs = dedupRefsByResource(referencesFor(doc));
        if (refs.length === 0) {
            return [];
        }
        const module = this.gradleService.resolveModule(doc.uri.fsPath);
        if (!module) {
            return [];
        }
        const manifest = this.gradleService.readResourceManifest(module);
        if (!manifest) {
            return [];
        }

        const lenses: vscode.CodeLens[] = [];
        for (const ref of refs) {
            const resourceId = `${ref.resourceType}/${ref.resourceName}`;
            const resource = manifest.resources.find(
                (r) => r.id === resourceId,
            );
            if (!resource) {
                continue;
            }
            const firstCapture = resource.captures.find((c) => c.renderOutput);
            if (!firstCapture) {
                continue;
            }
            const pngPath = path.join(
                this.gradleService.workspaceRoot,
                module.projectDir,
                "build",
                "compose-previews",
                firstCapture.renderOutput,
            );
            const startPos = doc.positionAt(ref.offset);
            const range = new vscode.Range(startPos.line, 0, startPos.line, 0);
            const variantCount = resource.captures.length;
            const variantSuffix =
                variantCount > 1 ? ` (${variantCount} variants)` : "";
            lenses.push(
                new vscode.CodeLens(range, {
                    title: `$(file-media) Preview ${resourceId}${variantSuffix}`,
                    command: "composePreview.previewResource",
                    arguments: [pngPath, resourceId],
                }),
            );
        }
        return lenses;
    }

    dispose(): void {
        this.emitter.dispose();
    }
}
