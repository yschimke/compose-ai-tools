import * as path from 'path';
import * as vscode from 'vscode';
import { GradleService } from './gradleService';
import { findManifestIconReferences } from './manifestIconReferences';
import { ResourceManifest, ResourcePreview } from './types';

/**
 * Surfaces a "$(file-media) Preview <id>" CodeLens above every `android:icon` / `roundIcon` /
 * `logo` / `banner` attribute in `AndroidManifest.xml` whose target resource resolves into the
 * module's `resources.json`. Click → opens the rendered PNG via the standard VS Code image
 * editor.
 *
 * The lens lookup uses `resources.json` directly rather than the `manifestReferences` index in
 * that file. Reasons:
 *   - The manifest reference's `source` field points at the *merged* manifest under `build/`,
 *     not the source manifest the user has open. Surfacing the lens on the open document means
 *     parsing it client-side anyway.
 *   - The lookup we want is "is there a rendered preview for this resource id?" — a direct
 *     `resources[]` scan answers that without a join through `manifestReferences`.
 */
export class AndroidManifestCodeLensProvider implements vscode.CodeLensProvider {
    private readonly emitter = new vscode.EventEmitter<void>();
    readonly onDidChangeCodeLenses = this.emitter.event;

    constructor(private readonly gradleService: GradleService) {}

    /** Fired after a render or discovery run so existing lenses pick up new resources. */
    refresh(): void {
        this.emitter.fire();
    }

    provideCodeLenses(doc: vscode.TextDocument): vscode.CodeLens[] {
        if (!doc.fileName.endsWith('AndroidManifest.xml')) { return []; }
        const module = this.gradleService.resolveModule(doc.uri.fsPath);
        if (!module) { return []; }
        const manifest = this.gradleService.readResourceManifest(module);
        if (!manifest) { return []; }

        const byId = indexResources(manifest);
        const matches = findManifestIconReferences(doc.getText());
        const lenses: vscode.CodeLens[] = [];
        for (const m of matches) {
            const resourceId = `${m.resourceType}/${m.resourceName}`;
            const resource = byId.get(resourceId);
            if (!resource) { continue; }
            const firstCapture = resource.captures.find((c) => c.renderOutput);
            if (!firstCapture) { continue; }
            const pngPath = path.join(
                this.gradleService.workspaceRoot,
                module,
                'build',
                'compose-previews',
                firstCapture.renderOutput,
            );
            const pos = doc.positionAt(m.offset);
            const range = new vscode.Range(pos.line, 0, pos.line, 0);
            lenses.push(
                new vscode.CodeLens(range, {
                    title: `$(file-media) Preview ${resourceId}`,
                    command: 'composePreview.previewResource',
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

function indexResources(manifest: ResourceManifest): Map<string, ResourcePreview> {
    const out = new Map<string, ResourcePreview>();
    for (const r of manifest.resources) {
        out.set(r.id, r);
    }
    return out;
}
