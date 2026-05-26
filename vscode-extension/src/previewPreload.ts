import * as path from "path";
import { GradleService, ModuleInfo } from "./gradleService";
import { captureLabel, withDataProductCaptures } from "./captureLabels";
import { visiblePreviewsForFile } from "./previewScope";
import { ExtensionToWebview, PreviewInfo } from "./types";

/**
 * Tagged outcome of {@link loadCachedPreviews}. The five `skipped` reasons replace what were
 * previously silent `return false`s in the inlined preload — each is now visible to logging and
 * pinned by a regression test. The `painted` variant carries the state the caller must commit
 * (the visible-preview list, the owning module) so the orchestration globals
 * (`hasPreviewsLoaded`, `moduleManifestCache`, the scope) stay at the call site rather than
 * being mutated from inside the preload helper.
 */
export type PreloadOutcome =
    | {
          kind: "painted";
          module: ModuleInfo;
          /** The previews actually painted (filtered by `previewsForFile` to the active file). */
          previews: PreviewInfo[];
      }
    | {
          kind: "skipped";
          reason: PreloadSkipReason;
          /** The resolved module when known — included on `no-manifest` / `empty-manifest` /
           *  `no-visible-previews` so log lines can name which module preload bailed on. Null
           *  when the skip happened before module resolution. */
          module: ModuleInfo | null;
      };

export type PreloadSkipReason =
    /** The file isn't a Kotlin / XML source the extension previews. */
    | "not-preview-file"
    /** `gradleService.resolveModule` returned null — no `applied.json` marker yet, or the file
     *  is outside any preview-applying module. */
    | "no-module"
    /** `gradleService.readManifest` returned null — `previews.json` doesn't exist on disk yet
     *  (cold start; nothing to preload). */
    | "no-manifest"
    /** Manifest loaded but contained zero previews (module compiled but no @Preview functions). */
    | "empty-manifest"
    /** Manifest had previews, but none of them belong to the requested file. */
    | "no-visible-previews";

export interface PreloadDeps {
    readonly gradleService: GradleService;
    /** Forwards a panel message. Decoupled from `vscode.Webview` so tests can use a recorder. */
    postMessage(msg: ExtensionToWebview): void;
    /** Caches the rendered image bytes against [previewId] in the host-side registry. */
    setImage(previewId: string, imageData: string): void;
    /** Records the owning module for a preview id in the host-side index. */
    setPreviewModule(previewId: string, module: ModuleInfo): void;
    /** Predicate the host uses to decide which files have previews. */
    isPreviewSourceFile(filePath: string): boolean;
}

/**
 * Paint previously-rendered cached PNGs from `build/compose-previews/` onto the panel so the
 * user never opens onto an empty screen while Gradle warms up. Returns an outcome the caller
 * uses to log the skip reason or commit the painted state.
 *
 * Side effects when `kind === "painted"`:
 * - Posts `setPreviews` + one `updateImage` per capture to the webview.
 * - Calls `setImage` / `setPreviewModule` on the host registry / index so subsequent reads see
 *   the cached state immediately.
 * - Does NOT update the editor scope, `hasPreviewsLoaded`, or any manifest cache — those
 *   belong to the caller (so the orchestration semantics stay visible in one place).
 */
export async function loadCachedPreviews(
    filePath: string,
    deps: PreloadDeps,
): Promise<PreloadOutcome> {
    if (!deps.isPreviewSourceFile(filePath)) {
        return { kind: "skipped", reason: "not-preview-file", module: null };
    }
    const module = deps.gradleService.resolveModule(filePath);
    if (!module) {
        return { kind: "skipped", reason: "no-module", module: null };
    }
    const manifest = deps.gradleService.readManifest(module);
    if (!manifest) {
        return { kind: "skipped", reason: "no-manifest", module };
    }
    if (manifest.previews.length === 0) {
        return { kind: "skipped", reason: "empty-manifest", module };
    }

    const visiblePreviews = visiblePreviewsForFile(
        manifest.previews,
        deps.gradleService.workspaceRoot,
        module,
        filePath,
    );
    if (visiblePreviews.length === 0) {
        return { kind: "skipped", reason: "no-visible-previews", module };
    }

    for (const p of visiblePreviews) {
        for (const capture of p.captures) {
            capture.label = captureLabel(capture);
        }
        deps.setPreviewModule(p.id, module);
    }

    const displayPreviews = visiblePreviews.map(withDataProductCaptures);

    deps.postMessage({
        command: "setPreviews",
        previews: displayPreviews,
        moduleDir: module.projectDir,
        heavyStaleIds: [],
    });

    const imageJobs: Promise<void>[] = [];
    for (const preview of displayPreviews) {
        for (let idx = 0; idx < preview.captures.length; idx++) {
            const capture = preview.captures[idx];
            if (!capture.renderOutput) {
                continue;
            }
            const captureIndex = idx;
            imageJobs.push(
                (async () => {
                    const imageData = await deps.gradleService.readPreviewImage(
                        module,
                        capture.renderOutput,
                    );
                    if (!imageData) {
                        return;
                    }
                    if (captureIndex === 0) {
                        deps.setImage(preview.id, imageData);
                    }
                    deps.postMessage({
                        command: "updateImage",
                        previewId: preview.id,
                        captureIndex,
                        imageData,
                    });
                })(),
            );
        }
    }
    await Promise.all(imageJobs);

    return { kind: "painted", module, previews: visiblePreviews };
}

/**
 * Human-readable label for a {@link PreloadOutcome}, used in the extension's diagnostic log.
 * Kept here (not at the call site) so the wording stays in lockstep with the outcome shape.
 */
export function describePreloadOutcome(
    filePath: string,
    outcome: PreloadOutcome,
): string {
    const file = path.basename(filePath);
    if (outcome.kind === "painted") {
        return `preload: painted ${outcome.previews.length} cached preview(s) for ${file} (module=${outcome.module.modulePath})`;
    }
    const moduleSuffix = outcome.module
        ? ` (module=${outcome.module.modulePath})`
        : "";
    return `preload: skipped ${file} — ${outcome.reason}${moduleSuffix}`;
}
