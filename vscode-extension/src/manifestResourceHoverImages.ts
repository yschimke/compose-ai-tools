// Disk-reader for the manifest-resource hover. Split out of
// `manifestResourceHoverProvider.ts` so Mocha tests can drive it without
// loading the `vscode` host module — the provider's only host-side
// dependency was this `fs.readFileSync` + path arithmetic, so isolating
// it keeps the security-sensitive boundary in a unit-testable module.

import * as fs from "node:fs";
import * as path from "node:path";

import { ResourceRenderError, VariantImage } from "./resourceVariantHover";
import { ResourcePreview } from "./types";

/**
 * Read each capture's PNG/GIF off disk and base64-encode it for the markdown
 * `<img>` tag. Captures whose file is missing are skipped silently — the
 * renderer might have failed on that particular variant, or a heavy-tier
 * filter could have dropped it; the hover still shows whichever variants
 * did land.
 *
 * `capture.renderOutput` comes from the workspace `resources.json`, which is
 * user-controlled. Each candidate is resolved and forced to stay strictly
 * underneath [moduleBuildRoot] before any disk I/O — an entry like
 * `'../../../etc/passwd'` would otherwise let a hovering user trigger
 * arbitrary local-file reads. Variants outside the root (or absolute paths
 * that escape it) are skipped silently, same as a missing file; we don't
 * throw the whole hover. See issue #1442.
 */
export function readVariantImages(
    resource: ResourcePreview,
    moduleBuildRoot: string,
): VariantImage[] {
    // Anchor the boundary check on the resolved absolute path of the build
    // root, and require candidates to live strictly underneath — `+ path.sep`
    // stops `/foo/bar/baz` from passing when the root is `/foo/bar`.
    const resolvedRoot = path.resolve(moduleBuildRoot);
    const rootPrefix = resolvedRoot.endsWith(path.sep)
        ? resolvedRoot
        : resolvedRoot + path.sep;
    const out: VariantImage[] = [];
    for (const capture of resource.captures) {
        const abs = path.resolve(resolvedRoot, capture.renderOutput);
        if (!abs.startsWith(rootPrefix)) {
            // Path-traversal attempt or absolute escape — drop the variant.
            continue;
        }
        try {
            const bytes = fs.readFileSync(abs);
            out.push({
                renderOutput: capture.renderOutput,
                base64: bytes.toString("base64"),
            });
        } catch {
            // File missing — render run produced no output for this variant.
            // Drop silently; the hover renders whatever did land.
        }
    }
    return out;
}

/**
 * Reads the renderer's `resource-render-errors.json` sidecar (written inside the render task's
 * declared output tree, `renders/resources/`) and returns its entries. Tolerates an absent or
 * malformed sidecar (returns `[]`) — an older renderer, or a run that produced nothing.
 * [moduleBuildRoot] is the module's `build/compose-previews` dir, same anchor as [readVariantImages].
 */
export function readResourceRenderErrors(
    moduleBuildRoot: string,
): ResourceRenderError[] {
    const sidecar = path.resolve(
        moduleBuildRoot,
        "renders",
        "resources",
        "resource-render-errors.json",
    );
    try {
        const parsed = JSON.parse(fs.readFileSync(sidecar, "utf8"));
        const entries: unknown[] = Array.isArray(parsed?.entries)
            ? (parsed.entries as unknown[])
            : [];
        return entries
            .filter(
                (e: unknown): e is Record<string, unknown> =>
                    typeof e === "object" &&
                    e !== null &&
                    typeof (e as Record<string, unknown>).renderOutput ===
                        "string",
            )
            .map((e) => ({
                renderOutput: String(e.renderOutput),
                status: String(e.status ?? ""),
                message: String(e.message ?? ""),
            }));
    } catch {
        return [];
    }
}
