// Generator for `spatial-semantics.json`. Run with:
//   node preview-harness/fixtures/spatial-semantics.gen.mjs > preview-harness/fixtures/spatial-semantics.json
//
// Same rich scene as `spatial-view`, but paired with a companion `SpatialSemanticsTree`
// so the 3D viewer composites each panel's 2D wireframe boxes over its screenshot face —
// the "screenshot + wireframe overlay" preview. The tree's node ids match the scene's
// panel / orbiter ids so every overlay lands on the right quad (the viewer keys wireframes
// by id); each box's `boundsInRoot` lives in that panel's content space (`0,0 → sizeDp`),
// which the compositor scales onto the texture's natural pixels.
//
// Built programmatically from the committed scene so panel ids + sizes stay in lockstep
// with `spatial-fixtures/spatial-rich/scene.json` — editing the scene can't silently drift
// the overlay onto the wrong panel.

import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { PANEL_CONTENT } from "../../spatial-fixtures/panel-content.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const scene = JSON.parse(
    readFileSync(
        join(here, "../../spatial-fixtures/spatial-rich/scene.json"),
        "utf8",
    ),
);

/**
 * Build a `SpatialSemanticsNode` for one scene quad (panel or orbiter) from the SHARED
 * {@link PANEL_CONTENT} widget spec — the same spec `spatial-rich.gen.mjs` paints into the texture,
 * so each wireframe box lands on the element it describes. Returns null for an unmapped quad.
 */
function semanticsNode(quad, kind) {
    const spec = PANEL_CONTENT[quad.id];
    if (!spec) return null;
    const { width, height } = quad.sizeDp;
    return {
        id: quad.id,
        kind,
        label: quad.label,
        poseInRoot: quad.poseInRoot,
        sizeDp: { width, height, depth: 0 },
        panelContent: {
            nodeId: `${quad.id}-root`,
            boundsInRoot: `0,0,${width},${height}`,
            ...(spec.merge ? { mergeMode: "mergeDescendants" } : {}),
            children: spec.widgets.map((wgt) => ({
                nodeId: wgt.id,
                boundsInRoot: wgt.bounds.join(","),
                ...(wgt.text ? { text: wgt.text } : {}),
                ...(wgt.label ? { label: wgt.label } : {}),
                ...(wgt.role ? { role: wgt.role } : {}),
                ...(wgt.clickable ? { clickable: true } : {}),
            })),
        },
    };
}

const children = [
    ...scene.panels.map((p) => semanticsNode(p, "panel")),
    ...(scene.orbiters ?? []).map((o) => semanticsNode(o, "orbiter")),
].filter(Boolean);

const semanticsTree = {
    version: 1,
    units: "dp",
    previewId: scene.previewId,
    root: {
        id: "subspaceRoot",
        kind: "subspaceRoot",
        poseInRoot: {
            translation: { x: 0, y: 0, z: 0 },
            rotation: { x: 0, y: 0, z: 0, w: 1 },
        },
        sizeDp: { width: 0, height: 0, depth: 0 },
        children,
    },
};

const fixture = {
    description:
        "Spatial 3D view with 2D wireframe overlays: setSpatialScene carries a companion " +
        "semantics tree, so the viewer draws each panel's semantics boxes over its screenshot " +
        "face. Click the toggle to switch to 3D.",
    dataset: {
        earlyFeatures: "false",
        minimalMode: "false",
        spatialSrc: "/media/webview/spatial.js",
        cspNonce: "harness",
    },
    messages: [
        {
            command: "setSpatialScene",
            scene,
            textureBaseUri: "/spatial-fixtures/spatial-rich/",
            semanticsTree,
        },
    ],
    actions: [{ click: "#btn-spatial-toggle" }],
    // three.js texture decode + canvas composite isn't observable via the rAF/img settle.
    settleMs: 1600,
};

process.stdout.write(JSON.stringify(fixture, null, 2) + "\n");
