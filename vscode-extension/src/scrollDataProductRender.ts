import * as fs from "fs";
import * as path from "path";
import { withDataProductCaptures } from "./captureLabels";
import { ModuleInfo } from "./gradleService";
import { PreviewInfo } from "./types";

/**
 * One displayed capture slot whose image data product is missing from disk — the
 * panel paints a placeholder for it because the daemon's normal renderNow only
 * produces the static base capture and `@ScrollingPreview(LONG/GIF)` data products
 * are renderer-side only (see `docs/daemon/DATA-PRODUCTS.md` § "data/scroll is
 * renderer-side only"). Gradle's `composePreviewRender` is the only producer.
 *
 * Carries the `captureIndex` in the displayed (post-fold) captures so the caller
 * can post `updateImage` to the right slot once Gradle fills the gap.
 */
export interface MissingImageDataProduct {
    previewId: string;
    captureIndex: number;
    /** Module-relative output path, e.g. `data/render-scroll-long/<id>.png`. */
    renderOutput: string;
    /** Absolute path under `<module>/build/compose-previews/`. */
    absolutePath: string;
}

/**
 * For each preview, finds image data-product output files (`render/scroll/long` →
 * `.png`, `render/scroll/gif` → `.gif`) declared in the manifest but absent from
 * disk. Groups by owning module's `modulePath` so callers can fire one Gradle
 * render per module rather than one per missing product.
 *
 * `moduleFor(previewId)` resolves the owning module — extension keeps this in
 * `previewModuleIndex`; injected here so the helper stays testable.
 *
 * The `captureIndex` is computed against `withDataProductCaptures(preview)` (the
 * same fold the panel uses), so the value can feed `updateImage` directly.
 * Captures whose data product doesn't surface in the displayed fold (no
 * corresponding output, scroll dropped by the renderer, etc.) are skipped.
 */
export function findMissingImageDataProducts(
    previews: readonly PreviewInfo[],
    moduleFor: (previewId: string) => ModuleInfo | undefined,
    workspaceRoot: string,
    fileExists: (p: string) => boolean = (p) => fs.existsSync(p),
): Map<string, MissingImageDataProduct[]> {
    const byModule = new Map<string, MissingImageDataProduct[]>();
    for (const preview of previews) {
        const mod = moduleFor(preview.id);
        if (!mod) continue;
        const dataProducts = preview.dataProducts ?? [];
        if (dataProducts.length === 0) continue;
        const display = withDataProductCaptures(preview);
        for (const dp of dataProducts) {
            const out = dp.output;
            if (!out) continue;
            const lower = out.toLowerCase();
            if (!lower.endsWith(".png") && !lower.endsWith(".gif")) continue;
            const abs = path.join(
                workspaceRoot,
                mod.projectDir,
                "build",
                "compose-previews",
                out,
            );
            if (fileExists(abs)) continue;
            const captureIndex = display.captures.findIndex(
                (c) => c.renderOutput === out,
            );
            if (captureIndex < 0) continue;
            const bucket = byModule.get(mod.modulePath) ?? [];
            bucket.push({
                previewId: preview.id,
                captureIndex,
                renderOutput: out,
                absolutePath: abs,
            });
            byModule.set(mod.modulePath, bucket);
        }
    }
    return byModule;
}
