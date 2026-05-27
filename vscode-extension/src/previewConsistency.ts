import * as fs from "fs";
import * as path from "path";
import { withDataProductCaptures } from "./captureLabels";
import { GradleService, ModuleInfo } from "./gradleService";
import { PreviewInfo, PreviewManifest } from "./types";

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
      }
    /** A file sitting in `build/compose-previews/` that no manifest entry points at. Almost
     *  always a leftover from a previous filename sanitiser shape (e.g. `Foo Bar.png` after
     *  the sanitiser dropped spaces in favour of `_`). Reported so the user can wipe stale
     *  renders that masquerade as fresh data and confuse the verify totals. */
    | {
          kind: "extra-file-on-disk";
          path: string;
      }
    /** A manifest entry whose expected PNG is missing, but a different file in the same
     *  directory has a name close enough that it's almost certainly the same preview under
     *  the previous sanitiser shape. Strongest signal that a discover-cache hit handed back
     *  a manifest pointing at renamed outputs. */
    | {
          kind: "renamed-on-disk";
          previewId: string;
          expectedPngPath: string;
          actualPath: string;
      };

export interface VerifyDeps {
    readonly gradleService: GradleService;
    /** Resolves the cached image bytes for a preview, or `null` when the registry has
     *  nothing for it. The host's `PreviewRegistry` already exposes this shape. */
    registryGetImage(previewId: string): string | null;
    /** True when a file exists on disk. Decoupled from `fs` so tests can fake it. */
    fileExists(filePath: string): boolean;
    /** Recursively list every regular file under [dir], returning module-relative paths
     *  (relative to the directory passed in). Returns an empty list when [dir] doesn't
     *  exist. Decoupled from `fs` so tests can fake it. */
    listFilesUnder(dir: string): string[];
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
    /** Total regular files seen under `build/compose-previews/` excluding `previews.json` and
     *  the per-extension report sidecars the manifest already names — i.e. the universe the
     *  extras scan was drawn from. */
    diskFileCount: number;
    /** Detected mismatches. Empty list = consistent. */
    inconsistencies: Inconsistency[];
}

/**
 * Resolves the on-disk path for the preview's primary displayed capture — the one the panel
 * actually paints into the card. Mirrors `GradleService.readPreviewImage`'s lookup so the
 * verify check reads from the exact same directory the host loads images from.
 *
 * Uses `withDataProductCaptures` so `@ScrollingPreview(LONG/GIF)` previews resolve to their
 * `data/render-scroll-*` data-product output (which is what the card displays) rather than
 * the manifest's static base capture (which the panel drops). Without this fold the verify
 * would call a missing scroll PNG a "stale placeholder with PNG on disk" — the daemon's
 * static base PNG that the panel correctly ignores.
 */
/**
 * Cheap "does the manifest match what's on disk?" probe for the refresh path. Walks every
 * capture and data-product output the manifest references and returns `true` the moment one is
 * missing. Short-circuits so the cost is one `existsSync` per file at most, and zero when the
 * manifest's first output is present and complete.
 *
 * Used to detect render/manifest drift after `composePreviewDiscover` returns FROM-CACHE with
 * filenames that no `composePreviewRender` has ever written under (sanitiser bumps, wiped
 * `build/`, branch switches, half-finished renders). When this returns `true` the refresh path
 * escalates `forceRender=false` to `forceRender=true` so the renderer fills the gap before the
 * panel paints — without the escalation the user sees placeholder cards indefinitely.
 */
export function manifestExpectedFilesMissing(
    workspaceRoot: string,
    module: ModuleInfo,
    manifest: PreviewManifest,
    fileExists: (filePath: string) => boolean = realFileExists,
): boolean {
    const root = path.join(
        workspaceRoot,
        module.projectDir,
        "build",
        "compose-previews",
    );
    for (const preview of manifest.previews) {
        for (const capture of preview.captures) {
            if (!capture.renderOutput) continue;
            if (!fileExists(path.join(root, capture.renderOutput))) return true;
        }
        for (const product of preview.dataProducts ?? []) {
            if (!product.output) continue;
            if (!fileExists(path.join(root, product.output))) return true;
        }
    }
    return false;
}

export function pngPathFor(
    workspaceRoot: string,
    module: ModuleInfo,
    preview: PreviewInfo,
): string | null {
    const display = withDataProductCaptures(preview);
    const capture = display.captures.find((c) => !!c.renderOutput);
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
    const empty = (module: ModuleInfo | null = null): VerifyResult => ({
        module,
        manifestCount: 0,
        diskPngCount: 0,
        registryImageCount: 0,
        diskFileCount: 0,
        inconsistencies: [],
    });
    if (!filePath) return empty();
    const module = deps.gradleService.resolveModule(filePath);
    if (!module) return empty();
    const manifest = deps.gradleService.readManifest(module);
    if (!manifest) return empty(module);

    const inconsistencies: Inconsistency[] = [];
    const previewsRoot = path.join(
        deps.gradleService.workspaceRoot,
        module.projectDir,
        "build",
        "compose-previews",
    );

    // Collect every output path the manifest expects (representative capture + extra captures +
    // data products) so the disk-scan can subtract them in one pass. Using a Set lets us treat
    // the absence of `path === expected` as the only "extra" signal — partial-prefix matches
    // are inferred from the leftover set, never from the manifest entries themselves.
    const expectedRelPaths = new Set<string>();
    for (const preview of manifest.previews) {
        for (const capture of preview.captures) {
            if (capture.renderOutput)
                expectedRelPaths.add(capture.renderOutput);
        }
        for (const product of preview.dataProducts ?? []) {
            if (product.output) expectedRelPaths.add(product.output);
        }
    }

    let diskPngCount = 0;
    let registryImageCount = 0;
    const renamedHits = new Map<string, string>(); // expectedRel → actualRel
    const diskRelPaths = deps.listFilesUnder(previewsRoot);
    // Index disk files by their containing directory + stem so we can match a manifest entry
    // against its likely-renamed predecessor without scanning the whole tree per preview.
    const diskByDir = new Map<string, string[]>();
    for (const rel of diskRelPaths) {
        const dir = path.dirname(rel);
        let bucket = diskByDir.get(dir);
        if (!bucket) {
            bucket = [];
            diskByDir.set(dir, bucket);
        }
        bucket.push(rel);
    }

    for (const preview of manifest.previews) {
        const pngPath = pngPathFor(
            deps.gradleService.workspaceRoot,
            module,
            preview,
        );
        if (pngPath === null) continue;
        const expectedRel = path.relative(previewsRoot, pngPath);
        const onDisk = deps.fileExists(pngPath);
        const inRegistry = deps.registryGetImage(preview.id) !== null;
        if (onDisk) diskPngCount++;
        if (inRegistry) registryImageCount++;
        if (onDisk && !inRegistry) {
            inconsistencies.push({
                kind: "disk-has-png-registry-empty",
                previewId: preview.id,
                pngPath,
            });
            continue;
        }
        if (onDisk) continue;
        // Look for a near-match in the same directory: same extension, similar stem (collapse
        // non-alphanumerics so `Foo Bar.png` and `Foo_Bar.png` resolve to the same fingerprint).
        // Renamed-from-old-sanitiser drift is the dominant cause; a single fingerprint match per
        // expected file is enough signal.
        const dir = path.dirname(expectedRel);
        const targetExt = path.extname(expectedRel);
        const expectedKey = stemFingerprint(
            path.basename(expectedRel, targetExt),
        );
        let renamed: string | null = null;
        for (const candidateRel of diskByDir.get(dir) ?? []) {
            if (renamedHits.has(candidateRel)) continue;
            if (path.extname(candidateRel) !== targetExt) continue;
            const actualKey = stemFingerprint(
                path.basename(candidateRel, targetExt),
            );
            if (actualKey === expectedKey) {
                renamed = candidateRel;
                break;
            }
        }
        if (renamed !== null) {
            renamedHits.set(renamed, expectedRel);
            inconsistencies.push({
                kind: "renamed-on-disk",
                previewId: preview.id,
                expectedPngPath: pngPath,
                actualPath: path.join(previewsRoot, renamed),
            });
            continue;
        }
        if (!inRegistry) {
            inconsistencies.push({
                kind: "no-image-anywhere",
                previewId: preview.id,
                expectedPngPath: pngPath,
            });
        }
    }

    // Anything still on disk that the manifest didn't claim (and wasn't paired as a rename
    // above) is an extra. Skip the manifest itself and obvious sidecar shapes that the
    // discover task writes alongside `previews.json` (per-extension reports). The remaining
    // leftover is the actionable signal — usually a stale-sanitiser PNG.
    for (const rel of diskRelPaths) {
        if (expectedRelPaths.has(rel)) continue;
        if (renamedHits.has(rel)) continue;
        if (isManifestSidecar(rel)) continue;
        inconsistencies.push({
            kind: "extra-file-on-disk",
            path: path.join(previewsRoot, rel),
        });
    }

    return {
        module,
        manifestCount: manifest.previews.length,
        diskPngCount,
        registryImageCount,
        diskFileCount: diskRelPaths.length,
        inconsistencies,
    };
}

/** Collapse runs of non-alphanumeric characters into a single underscore and lowercase so two
 *  filenames that differ only in their sanitiser shape (`Foo - Bar.png` vs `Foo_Bar.png`)
 *  fingerprint to the same key. Mirrors the gradle plugin's per-segment sanitiser. */
function stemFingerprint(stem: string): string {
    return stem.replace(/[^A-Za-z0-9]+/g, "_").toLowerCase();
}

/** Files written next to `previews.json` by the discover task that aren't per-preview render
 *  outputs — e.g. the manifest itself, per-extension report sidecars. Skipping them keeps the
 *  extras list focused on stale render leftovers (the only actionable signal). */
function isManifestSidecar(relPath: string): boolean {
    if (relPath === "previews.json") return true;
    if (relPath.endsWith(".json") && !relPath.includes(path.sep)) return true;
    return false;
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
    const renamed = result.inconsistencies.filter(
        (i) => i.kind === "renamed-on-disk",
    ).length;
    const extras = result.inconsistencies.filter(
        (i) => i.kind === "extra-file-on-disk",
    ).length;
    const consistent = result.inconsistencies.length === 0;
    const drift: string[] = [];
    if (stalePlaceholders > 0)
        drift.push(`${stalePlaceholders} placeholder(s) with PNG on disk`);
    if (renamed > 0) drift.push(`${renamed} renamed on disk`);
    if (extras > 0) drift.push(`${extras} extra file(s) on disk`);
    if (neverRendered > 0) drift.push(`${neverRendered} never-rendered`);
    return (
        `verify: ${path.basename(filePath)} ` +
        `manifest=${result.manifestCount} ` +
        `disk=${result.diskPngCount} ` +
        `registry=${result.registryImageCount} ` +
        `diskFiles=${result.diskFileCount} ` +
        (consistent ? "→ consistent" : `→ ${drift.join(", ")}`)
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

/**
 * Production-side {@link VerifyDeps.listFilesUnder} backed by a recursive `fs.readdirSync`
 * walk. Returns paths relative to [dir]; missing directory ⇒ empty list (a freshly cleaned
 * `build/` is the dominant cause of "nothing here" and shouldn't surface as an error).
 */
export function realListFilesUnder(dir: string): string[] {
    const out: string[] = [];
    const walk = (current: string, relPrefix: string): void => {
        let entries: fs.Dirent[];
        try {
            entries = fs.readdirSync(current, { withFileTypes: true });
        } catch {
            return;
        }
        for (const entry of entries) {
            const childAbs = path.join(current, entry.name);
            const childRel = relPrefix
                ? path.join(relPrefix, entry.name)
                : entry.name;
            if (entry.isDirectory()) {
                walk(childAbs, childRel);
            } else if (entry.isFile()) {
                out.push(childRel);
            }
        }
    };
    walk(dir, "");
    return out;
}
