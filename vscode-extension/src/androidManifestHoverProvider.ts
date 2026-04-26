import * as path from 'path';
import * as vscode from 'vscode';
import { GradleService } from './gradleService';
import { findManifestIconReferences } from './manifestIconReferences';

/**
 * Hover sibling to [AndroidManifestCodeLensProvider]. When the cursor is over an
 * icon-bearing attribute in `AndroidManifest.xml` (`android:icon`, `roundIcon`, `logo`,
 * `banner`) whose target resolves into the module's `resources.json`, surfaces a markdown
 * tooltip with the rendered PNG inline plus a one-line summary of the resource type and
 * captures.
 *
 * Two reasons to keep this alongside the CodeLens rather than collapsing into one surface:
 *
 *  - **Different ergonomics.** Hover is "I'm reading the manifest, what does this icon look
 *    like?" — passive, on-demand, no commit to leaving the manifest. CodeLens is "open the
 *    PNG full-size in a sibling tab" — requires a click but gives you the actual viewer.
 *    Both are useful, neither subsumes the other.
 *  - **No vscode dependency in the parser.** `findManifestIconReferences` lives in a
 *    no-vscode module so the test runs under plain Mocha; layering both providers on top of
 *    that single parser is the natural shape.
 */
export class AndroidManifestHoverProvider implements vscode.HoverProvider {
    constructor(private readonly gradleService: GradleService) {}

    provideHover(
        doc: vscode.TextDocument,
        position: vscode.Position,
    ): vscode.Hover | undefined {
        if (!doc.fileName.endsWith('AndroidManifest.xml')) { return undefined; }
        const module = this.gradleService.resolveModule(doc.uri.fsPath);
        if (!module) { return undefined; }

        // Find the icon-attribute match, if any, that contains the cursor.
        const text = doc.getText();
        const offset = doc.offsetAt(position);
        const matches = findManifestIconReferences(text);
        const match = matches.find((m) => {
            // The full attribute span (start through closing quote of the
            // value) is roughly `android:NAME="@TYPE/NAME"`. The parser
            // returns the offset of `android:`, and the match length is
            // bounded by a generous estimate — but doc.offsetAt(position) is
            // a line+col offset, so we just check whether the cursor sits
            // anywhere on the line that owns this match.
            const matchLine = doc.positionAt(m.offset).line;
            return matchLine === position.line;
        });
        if (!match) { return undefined; }

        const manifest = this.gradleService.readResourceManifest(module);
        if (!manifest) { return undefined; }

        const resourceId = `${match.resourceType}/${match.resourceName}`;
        const resource = manifest.resources.find((r) => r.id === resourceId);
        if (!resource) { return undefined; }
        const firstCapture = resource.captures.find((c) => c.renderOutput);
        if (!firstCapture) { return undefined; }

        const pngPath = path.join(
            this.gradleService.workspaceRoot,
            module,
            'build',
            'compose-previews',
            firstCapture.renderOutput,
        );

        // Markdown image syntax + a one-line summary. Use a `vscode.MarkdownString`
        // with `isTrusted = false` (default) — the image src is a `file:` URL but
        // contains no executable content, so trust isn't required.
        const md = new vscode.MarkdownString();
        md.supportHtml = true;
        md.appendMarkdown(`![${resourceId}](${vscode.Uri.file(pngPath).toString()})\n\n`);
        const captureCount = resource.captures.length;
        const captureSummary = captureCount === 1
            ? '1 capture'
            : `${captureCount} captures`;
        md.appendMarkdown(`**\`${resourceId}\`** — ${resource.type}, ${captureSummary}`);
        return new vscode.Hover(md);
    }
}
