import * as fs from "fs";
import * as path from "path";
import type { SpatialScene } from "./webview/shared/spatialScene";
import type { SpatialSemanticsTree } from "./webview/shared/spatialSemanticsTree";
import { parseSpatialSceneJson } from "./webview/spatial/sceneLoader";
import { parseSpatialSemanticsTreeJson } from "./webview/spatial/semanticsTreeLoader";

/** A real XR preview's render output, ready to hand to `PreviewPanel.showSpatialScene`. */
export interface LoadedSpatialRender {
    /** Absolute dir holding `scene.json` + the panel textures — the texture base. */
    sceneDir: string;
    scene: SpatialScene;
    /** Per-panel 2D semantics overlay, when the producer emitted it. */
    semanticsTree?: SpatialSemanticsTree;
}

/**
 * Load the spatial render output for one preview capture, if it is an XR preview.
 *
 * The XR signal is a `scene.json` sitting in the capture's render subdir — the
 * `renders/<id>/` directory next to the optional `composite.png` that the
 * `composePreviewRenderXr` task writes. Ordinary previews have no `scene.json`,
 * so this returns `null` for them (and for XR previews not yet rendered).
 *
 * `previewsBaseDir` is `<module>/build/compose-previews`; `renderOutput` is the
 * capture's manifest-relative path (e.g. `renders/<id>/composite.png`). The
 * scene's relative panel textures resolve against the returned `sceneDir`.
 *
 * Throws (via the parsers) when a `scene.json` is present but violates the wire
 * contract — the caller surfaces that rather than silently degrading, since a
 * malformed scene is a producer bug, not "this isn't an XR preview".
 */
export function loadSpatialRender(
    previewsBaseDir: string,
    renderOutput: string,
): LoadedSpatialRender | null {
    const sceneDir = path.join(previewsBaseDir, path.dirname(renderOutput));
    let sceneText: string;
    try {
        sceneText = fs.readFileSync(path.join(sceneDir, "scene.json"), "utf8");
    } catch {
        return null; // no scene.json → not a (rendered) XR preview
    }
    const scene = parseSpatialSceneJson(sceneText);

    // The companion semantics tree is best-effort: a missing or malformed one
    // just means the 3D faces render without per-panel wireframe overlays.
    let semanticsTree: SpatialSemanticsTree | undefined;
    try {
        semanticsTree = parseSpatialSemanticsTreeJson(
            fs.readFileSync(
                path.join(sceneDir, "compose-spatial-semantics.json"),
                "utf8",
            ),
        );
    } catch {
        semanticsTree = undefined;
    }

    return { sceneDir, scene, semanticsTree };
}
