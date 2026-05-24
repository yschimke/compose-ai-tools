import * as path from "node:path";
import * as vscode from "vscode";
import {
    findKotlinResourceReferences,
    findXmlResourceReferences,
    refAt,
    ResourceRef,
} from "./androidResourceReferences";
import { GradleService } from "./gradleService";
import { readVariantImages } from "./manifestResourceHoverProvider";
import { buildResourceVariantHoverMarkdown } from "./resourceVariantHover";

/**
 * Hovers over `R.drawable.foo` / `R.mipmap.foo` in Kotlin source, and
 * `@drawable/foo` / `@mipmap/foo` in res-tree XML, reusing the same
 * variant-grid markdown as the AndroidManifest hover. Lets the user
 * peek the rendered asset without leaving the call site.
 *
 * Kotlin and layout XML share one provider (instance gated on
 * languageId / fileName) rather than two near-identical classes — the
 * only branching is the source-text extractor, which is cheap and
 * keeps the wiring in `extension.ts` flat.
 *
 * Pairs with [ResourceReferenceCodeLensProvider]: the lens is the
 * one-per-file discovery affordance, the hover is the per-occurrence
 * peek.
 */
export class ResourceReferenceHoverProvider implements vscode.HoverProvider {
    constructor(private readonly gradleService: GradleService) {}

    provideHover(
        doc: vscode.TextDocument,
        position: vscode.Position,
    ): vscode.Hover | undefined {
        const refs = referencesFor(doc);
        if (refs.length === 0) {
            return undefined;
        }
        const offset = doc.offsetAt(position);
        const ref = refAt(refs, offset);
        if (!ref) {
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
        const resourceId = `${ref.resourceType}/${ref.resourceName}`;
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
        md.isTrusted = true;
        md.supportHtml = true;

        const start = doc.positionAt(ref.offset);
        const end = doc.positionAt(ref.offset + ref.length);
        return new vscode.Hover(md, new vscode.Range(start, end));
    }
}

/**
 * Choose the right extractor for [doc]. Exported so the CodeLens
 * provider can share it — both surfaces need the same Kotlin /
 * res-tree-XML routing and we don't want it to drift.
 *
 * `AndroidManifest.xml` deliberately returns no refs here: it already
 * has its own hover ([ManifestResourceHoverProvider]) that knows about
 * the icon-attribute semantics, and double-registration would surface
 * two overlapping popovers.
 */
export function referencesFor(doc: vscode.TextDocument): ResourceRef[] {
    if (doc.languageId === "kotlin") {
        return findKotlinResourceReferences(doc.getText());
    }
    if (isResourceXml(doc)) {
        return findXmlResourceReferences(doc.getText());
    }
    return [];
}

/**
 * True iff [doc] is an XML file under a `res/` directory (any qualifier
 * suffix) **other than** `AndroidManifest.xml`. Matches the natural
 * scope of `@drawable/...` references — layouts, drawable XML,
 * adaptive-icon XML, menus, anims, etc.
 */
export function isResourceXml(doc: vscode.TextDocument): boolean {
    if (doc.fileName.endsWith("AndroidManifest.xml")) {
        return false;
    }
    if (!doc.fileName.toLowerCase().endsWith(".xml")) {
        return false;
    }
    return /[/\\]res[/\\]/.test(doc.fileName);
}
