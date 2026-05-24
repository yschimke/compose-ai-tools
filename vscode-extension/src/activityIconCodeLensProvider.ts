import * as path from "node:path";
import * as vscode from "vscode";
import { findIconForActivityFqn } from "./activityIconLookup";
import { GradleService } from "./gradleService";
import {
    extractClassDeclarations,
    extractPackage,
    isActivityLikeDeclaration,
} from "./kotlinClassFqn";

/**
 * Surfaces a CodeLens above each top-level class declaration whose FQN
 * resolves to a manifest-declared (or `<application>`-fallback) icon.
 * Click → opens the rendered PNG via the same `composePreview.previewResource`
 * command used by [AndroidManifestCodeLensProvider], so the user lands
 * on a familiar editor surface.
 *
 * Discovery affordance for the [ActivityIconHoverProvider]: without a
 * visible lens nothing tells the user there's a hover to try.
 */
export class ActivityIconCodeLensProvider implements vscode.CodeLensProvider {
    private readonly emitter = new vscode.EventEmitter<void>();
    readonly onDidChangeCodeLenses = this.emitter.event;

    constructor(private readonly gradleService: GradleService) {}

    /** Re-emit lenses after a render run produces new captures. */
    refresh(): void {
        this.emitter.fire();
    }

    provideCodeLenses(doc: vscode.TextDocument): vscode.CodeLens[] {
        if (doc.languageId !== "kotlin") {
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

        const text = doc.getText();
        const declarations = extractClassDeclarations(text);
        if (declarations.length === 0) {
            return [];
        }
        const pkg = extractPackage(text);

        const lenses: vscode.CodeLens[] = [];
        for (const decl of declarations) {
            const fqn = pkg ? `${pkg}.${decl.name}` : decl.name;
            const match = findIconForActivityFqn(
                manifest,
                fqn,
                isActivityLikeDeclaration(decl),
            );
            if (!match) {
                continue;
            }
            const resourceId = `${match.reference.resourceType}/${match.reference.resourceName}`;
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
            const startPos = doc.positionAt(decl.nameOffset);
            const range = new vscode.Range(startPos.line, 0, startPos.line, 0);
            const title =
                match.source === "direct"
                    ? `$(file-media) Activity icon: ${resourceId}`
                    : `$(file-media) Application icon: ${resourceId} (no override)`;
            lenses.push(
                new vscode.CodeLens(range, {
                    title,
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
