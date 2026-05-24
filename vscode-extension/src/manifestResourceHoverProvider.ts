import * as path from "node:path";
import * as vscode from "vscode";
import { GradleService } from "./gradleService";
import { readVariantImages } from "./manifestResourceHoverImages";
import { findManifestIconReferences } from "./manifestIconReferences";
import { buildResourceVariantHoverMarkdown } from "./resourceVariantHover";

/**
 * Renders a hover over every `android:icon` / `roundIcon` / `logo` / `banner` attribute in
 * `AndroidManifest.xml` whose target resource resolved into the module's `resources.json`. The
 * hover shows every rendered capture — adaptive-icon variants (FULL_COLOR / THEMED_LIGHT /
 * THEMED_DARK / LEGACY), or a single image for vectors — so the user sees what the launcher
 * actually looks like under themed-icon mode without leaving the editor.
 *
 * Pairs with [AndroidManifestCodeLensProvider]: the lens is the explicit "open in viewer" entry
 * point, the hover is the at-a-glance peek. Both providers read `resources.json` directly and
 * tolerate it being absent (consumer hasn't run `composePreviewRenderAndroidResources` yet).
 */
export class ManifestResourceHoverProvider implements vscode.HoverProvider {
    constructor(private readonly gradleService: GradleService) {}

    provideHover(
        doc: vscode.TextDocument,
        position: vscode.Position,
    ): vscode.Hover | undefined {
        if (!doc.fileName.endsWith("AndroidManifest.xml")) {
            return undefined;
        }
        const module = this.gradleService.resolveModule(doc.uri.fsPath);
        if (!module) {
            return undefined;
        }
        const manifest = this.gradleService.readResourceManifest(module);
        if (!manifest) {
            return undefined;
        }

        const matches = findManifestIconReferences(doc.getText());
        const offset = doc.offsetAt(position);
        const hit = matches.find(
            (m) => offset >= m.offset && offset <= m.offset + m.length,
        );
        if (!hit) {
            return undefined;
        }

        const resourceId = `${hit.resourceType}/${hit.resourceName}`;
        const resource = manifest.resources.find((r) => r.id === resourceId);
        if (!resource) {
            return undefined;
        }

        const moduleRoot = path.join(
            this.gradleService.workspaceRoot,
            module.projectDir,
            "build",
            "compose-previews",
        );
        const images = readVariantImages(resource, moduleRoot);
        if (images.length === 0) {
            return undefined;
        }

        const md = new vscode.MarkdownString(
            buildResourceVariantHoverMarkdown({ resource, images }),
        );
        // `resources.json` is workspace-controlled (could be authored by an
        // attacker if a victim opens a hostile project). The hover only needs
        // `supportHtml` for the inline data-URI `<img>` previews — it never
        // emits `command:` links — so leave `isTrusted` off and the markdown
        // builder also escapes interpolated fields. See issue #1442.
        md.isTrusted = false;
        md.supportHtml = true;

        const startPos = doc.positionAt(hit.offset);
        const endPos = doc.positionAt(hit.offset + hit.length);
        return new vscode.Hover(md, new vscode.Range(startPos, endPos));
    }
}
