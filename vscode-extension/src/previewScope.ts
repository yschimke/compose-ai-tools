import { PreviewInfo } from "./types";
import { previewSourceMatches } from "./sourcePath";

/**
 * Splits a module's previews into two buckets relative to [filePath]:
 *
 * - **primary** — previews whose own `sourceFile` matches the file (the file
 *   contains the `@Preview` annotations). Same set the panel always showed.
 * - **referenced** — previews from *other* files in the module whose inferred
 *   `targets[i].sourceFile` matches the file. These surface when the user
 *   opens a production composable that has its `@Preview`s in a sibling file
 *   (idiomatic `XxxPreviews.kt` / `screenshotTest` layout). Each entry is a
 *   shallow copy with `referenced = true` so the webview can render them
 *   under a "from elsewhere" treatment without touching the cached
 *   `PreviewInfo` on `moduleManifestCache`.
 *
 * Previews that match both (a preview that lives in the file *and* targets
 * the file) are reported as primary only — `referenced` would be redundant.
 */
export function partitionPreviewsForFile(
    previews: PreviewInfo[],
    workspaceRoot: string,
    module: string,
    filePath: string,
): { primary: PreviewInfo[]; referenced: PreviewInfo[] } {
    const primary: PreviewInfo[] = [];
    const referenced: PreviewInfo[] = [];
    const primaryIds = new Set<string>();
    for (const preview of previews) {
        if (
            previewSourceMatches(
                preview.sourceFile,
                filePath,
                workspaceRoot,
                module,
            )
        ) {
            primary.push(preview);
            primaryIds.add(preview.id);
        }
    }
    for (const preview of previews) {
        if (primaryIds.has(preview.id)) {
            continue;
        }
        const targets = preview.targets;
        if (!targets || targets.length === 0) {
            continue;
        }
        const matchesByTarget = targets.some((t) =>
            previewSourceMatches(t.sourceFile, filePath, workspaceRoot, module),
        );
        if (matchesByTarget) {
            referenced.push({ ...preview, referenced: true });
        }
    }
    return { primary, referenced };
}

/**
 * Convenience wrapper that returns `[...primary, ...referenced]` — the order
 * the panel renders so primary previews stay at the top.
 */
export function visiblePreviewsForFile(
    previews: PreviewInfo[],
    workspaceRoot: string,
    module: string,
    filePath: string,
): PreviewInfo[] {
    const { primary, referenced } = partitionPreviewsForFile(
        previews,
        workspaceRoot,
        module,
        filePath,
    );
    return [...primary, ...referenced];
}
