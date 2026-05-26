import * as fs from "fs";
import * as path from "path";
import { GradleService, ModuleInfo } from "./gradleService";
import { PreviewInfo } from "./types";

/**
 * The user-visible bug the verify command targets: "I'm seeing a placeholder card on screen,
 * but there's a perfectly good PNG sitting in `build/compose-previews/` for that preview." A
 * stale image is OK (the daemon will replace it on the next render); an absent image when one
 * exists on disk is NOT.
 *
 * The check is host-side: it compares the manifest (extension's view of the world), the
 * filesystem (what's actually on disk under `build/compose-previews/`), and the host's image
 * registry (the in-memory cache that backs the panel's per-card image bytes). If the registry
 * mirror is missing bytes for a preview whose PNG is on disk, the panel is showing a
 * placeholder unnecessarily and we report it.
 */
export type Inconsistency =
    /** Preview's PNG exists on disk but the host's image registry doesn't have its bytes —
     *  the most direct read of "user sees a placeholder while disk has the picture". */
    | {
          kind: "disk-has-png-registry-empty";
          previewId: string;
          pngPath: string;
      }
    /** Manifest lists a preview, but neither the registry nor the disk has any image for it.
     *  Not necessarily a bug — could just be never-rendered — but reported separately so the
     *  "what's missing where" picture is complete. */
    | {
          kind: "no-image-anywhere";
          previewId: string;
          expectedPngPath: string;
      };

export interface VerifyDeps {
    readonly gradleService: GradleService;
    /** Resolves the cached image bytes for a preview, or `null` when the registry has
     *  nothing for it. The host's `PreviewRegistry` already exposes this shape. */
    registryGetImage(previewId: string): string | null;
    /** True when a file exists on disk. Decoupled from `fs` so tests can fake it. */
    fileExists(filePath: string): boolean;
}

export interface VerifyResult {
    /** Module the verify ran against. `null` when the file's module couldn't be resolved or
     *  no `previews.json` was on disk. */
    module: ModuleInfo | null;
    /** Total previews the manifest claimed exist (including ones outside the active scope). */
    manifestCount: number;
    /** Previews whose first-capture PNG was found on disk. */
    diskPngCount: number;
    /** Previews whose host registry currently holds image bytes. */
    registryImageCount: number;
    /** Detected mismatches. Empty list = consistent. */
    inconsistencies: Inconsistency[];
}

/**
 * Resolves the on-disk path for a preview's first capture, the one the panel uses as its
 * primary image. Mirrors `GradleService.readPreviewImage`'s lookup so the verify check reads
 * from the exact same directory the host loads images from.
 */
export function pngPathFor(
    workspaceRoot: string,
    module: ModuleInfo,
    preview: PreviewInfo,
): string | null {
    const capture = preview.captures.find((c) => !!c.renderOutput);
    if (!capture || !capture.renderOutput) {
        return null;
    }
    return path.join(
        workspaceRoot,
        module.projectDir,
        "build",
        "compose-previews",
        capture.renderOutput,
    );
}

/**
 * Cross-check the manifest, the filesystem, and the host's image registry for the file
 * currently scoped to the preview panel. Returns a structured report the caller surfaces to
 * the user via an output channel + a status message.
 *
 * Designed to be cheap (one stat per preview + one Map lookup) so the user can invoke it
 * repeatedly via the command palette without measurable cost.
 */
export function verifyConsistency(
    filePath: string | null,
    deps: VerifyDeps,
): VerifyResult {
    if (!filePath) {
        return {
            module: null,
            manifestCount: 0,
            diskPngCount: 0,
            registryImageCount: 0,
            inconsistencies: [],
        };
    }
    const module = deps.gradleService.resolveModule(filePath);
    if (!module) {
        return {
            module: null,
            manifestCount: 0,
            diskPngCount: 0,
            registryImageCount: 0,
            inconsistencies: [],
        };
    }
    const manifest = deps.gradleService.readManifest(module);
    if (!manifest) {
        return {
            module,
            manifestCount: 0,
            diskPngCount: 0,
            registryImageCount: 0,
            inconsistencies: [],
        };
    }
    const inconsistencies: Inconsistency[] = [];
    let diskPngCount = 0;
    let registryImageCount = 0;
    for (const preview of manifest.previews) {
        const pngPath = pngPathFor(
            deps.gradleService.workspaceRoot,
            module,
            preview,
        );
        const onDisk = pngPath !== null && deps.fileExists(pngPath);
        const inRegistry = deps.registryGetImage(preview.id) !== null;
        if (onDisk) diskPngCount++;
        if (inRegistry) registryImageCount++;
        if (onDisk && !inRegistry && pngPath !== null) {
            inconsistencies.push({
                kind: "disk-has-png-registry-empty",
                previewId: preview.id,
                pngPath,
            });
        } else if (!onDisk && !inRegistry && pngPath !== null) {
            inconsistencies.push({
                kind: "no-image-anywhere",
                previewId: preview.id,
                expectedPngPath: pngPath,
            });
        }
    }
    return {
        module,
        manifestCount: manifest.previews.length,
        diskPngCount,
        registryImageCount,
        inconsistencies,
    };
}

/**
 * One-line summary suitable for the status bar / window message. The verbose breakdown
 * (per-preview inconsistencies) goes into the output channel separately.
 */
export function describeVerifyResult(
    filePath: string | null,
    result: VerifyResult,
): string {
    if (!filePath) {
        return "verify: no scope file";
    }
    if (!result.module) {
        return `verify: no module for ${path.basename(filePath)}`;
    }
    if (result.manifestCount === 0) {
        return `verify: no manifest for ${result.module.modulePath}`;
    }
    const stalePlaceholders = result.inconsistencies.filter(
        (i) => i.kind === "disk-has-png-registry-empty",
    ).length;
    const neverRendered = result.inconsistencies.filter(
        (i) => i.kind === "no-image-anywhere",
    ).length;
    const consistent = result.inconsistencies.length === 0;
    return (
        `verify: ${path.basename(filePath)} ` +
        `manifest=${result.manifestCount} ` +
        `disk=${result.diskPngCount} ` +
        `registry=${result.registryImageCount} ` +
        (consistent
            ? "→ consistent"
            : `→ ${stalePlaceholders} placeholder(s) with PNG on disk, ${neverRendered} never-rendered`)
    );
}

/** Production-side {@link VerifyDeps.fileExists} backed by `fs.existsSync`. */
export function realFileExists(filePath: string): boolean {
    try {
        return fs.existsSync(filePath);
    } catch {
        return false;
    }
}
