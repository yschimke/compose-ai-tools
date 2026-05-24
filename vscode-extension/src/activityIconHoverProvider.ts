import * as vscode from "vscode";
import { findIconForActivityFqn } from "./activityIconLookup";
import { GradleService } from "./gradleService";
import {
    extractClassDeclarations,
    extractPackage,
    isActivityLikeDeclaration,
    KotlinClassDeclaration,
} from "./kotlinClassFqn";
import { readVariantImages } from "./manifestResourceHoverProvider";
import { buildResourceVariantHoverMarkdown } from "./resourceVariantHover";
import { ResourcePreview } from "./types";
import * as path from "node:path";

/**
 * Surfaces the AndroidManifest-declared icon for the Activity (or
 * Service / Receiver / Provider) the user is currently editing. Hover
 * the class-name identifier on a line like `class MainActivity :
 * ComponentActivity()` and see the same variant grid the manifest
 * hover renders for `android:icon="@drawable/ic_settings"`.
 *
 * Joins three sources of truth:
 *  - The file's package + top-level class names ([extractPackage],
 *    [extractClassDeclarations]).
 *  - `ResourceManifest.manifestReferences` — the recorder-emitted
 *    `(componentFqn, attributeName, resourceType, resourceName)` index
 *    from the merged `AndroidManifest.xml`.
 *  - `ResourceManifest.resources` — the rendered captures for each
 *    drawable / mipmap.
 *
 * Falls back to the `<application>` icon when a class extends a
 * platform component base (`ComponentActivity` / `Activity` / `Service`
 * / ...) but has no `android:icon` override of its own — matching what
 * the OS will actually show on the launcher / task switcher.
 */
export class ActivityIconHoverProvider implements vscode.HoverProvider {
    constructor(private readonly gradleService: GradleService) {}

    provideHover(
        doc: vscode.TextDocument,
        position: vscode.Position,
    ): vscode.Hover | undefined {
        if (doc.languageId !== "kotlin") {
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

        const text = doc.getText();
        const declarations = extractClassDeclarations(text);
        if (declarations.length === 0) {
            return undefined;
        }
        const offset = doc.offsetAt(position);
        const hitDecl = declarations.find(
            (d) =>
                offset >= d.nameOffset && offset <= d.nameOffset + d.nameLength,
        );
        if (!hitDecl) {
            return undefined;
        }

        const pkg = extractPackage(text);
        const fqn = pkg ? `${pkg}.${hitDecl.name}` : hitDecl.name;
        const match = findIconForActivityFqn(
            manifest,
            fqn,
            isActivityLikeDeclaration(hitDecl),
        );
        if (!match) {
            return undefined;
        }

        const resourceId = `${match.reference.resourceType}/${match.reference.resourceName}`;
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
            renderHoverBody(hitDecl, fqn, resource, images, match.source),
        );
        md.isTrusted = true;
        md.supportHtml = true;

        const start = doc.positionAt(hitDecl.nameOffset);
        const end = doc.positionAt(hitDecl.nameOffset + hitDecl.nameLength);
        return new vscode.Hover(md, new vscode.Range(start, end));
    }
}

function renderHoverBody(
    decl: KotlinClassDeclaration,
    fqn: string,
    resource: ResourcePreview,
    images: ReturnType<typeof readVariantImages>,
    source: "direct" | "application-fallback",
): string {
    const variantMarkdown = buildResourceVariantHoverMarkdown({
        resource,
        images,
    });
    const attribution =
        source === "direct"
            ? `Manifest icon for **${decl.name}** (\`${fqn}\`)`
            : `No \`android:icon\` on **${decl.name}** — falling back to \`<application>\` icon`;
    return [attribution, "", variantMarkdown].join("\n");
}
