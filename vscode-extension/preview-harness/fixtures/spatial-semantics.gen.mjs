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

const here = dirname(fileURLToPath(import.meta.url));
const scene = JSON.parse(
    readFileSync(
        join(here, "../../spatial-fixtures/spatial-rich/scene.json"),
        "utf8",
    ),
);

// Illustrative 2D semantics per panel id — a handful of boxes in content space
// (`left,top,right,bottom`). `merge: true` marks a mergeDescendants content root (renders
// amber, like the 2D inspector's merge boundary); `role` / `clickable` ride along for the
// hover tooltip. Coordinates stay inside each quad's `sizeDp` so the boxes sit on-screen.
const CONTENT = {
    "now-playing": {
        children: [
            { id: "np-title", bounds: "24,28,360,72", text: "Midnight City" },
            { id: "np-artist", bounds: "24,84,240,116", text: "M83" },
            { id: "np-thumb", bounds: "440,24,536,120", role: "Image" },
        ],
    },
    "album-art": {
        children: [
            {
                id: "art-image",
                bounds: "20,20,440,440",
                role: "Image",
                label: "Album cover",
            },
        ],
    },
    queue: {
        children: [
            {
                id: "q-row1",
                bounds: "16,24,284,84",
                text: "Outro",
                role: "Button",
                clickable: true,
            },
            {
                id: "q-row2",
                bounds: "16,96,284,156",
                text: "Reunion",
                role: "Button",
                clickable: true,
            },
            {
                id: "q-row3",
                bounds: "16,168,284,228",
                text: "Wait",
                role: "Button",
                clickable: true,
            },
        ],
    },
    lyrics: {
        children: [
            { id: "ly-1", bounds: "20,28,280,60", text: "Waiting for the…" },
            { id: "ly-2", bounds: "20,72,260,104", text: "sun to set…" },
            { id: "ly-3", bounds: "20,116,236,148", text: "over the city" },
        ],
    },
    // The transport bar merges its controls into one a11y node — show that as a merge root.
    transport: {
        merge: true,
        children: [
            {
                id: "tp-prev",
                bounds: "120,24,184,88",
                role: "Button",
                label: "Previous",
                clickable: true,
            },
            {
                id: "tp-play",
                bounds: "248,16,312,96",
                role: "Button",
                label: "Play",
                clickable: true,
            },
            {
                id: "tp-next",
                bounds: "376,24,440,88",
                role: "Button",
                label: "Next",
                clickable: true,
            },
        ],
    },
    volume: {
        children: [
            {
                id: "vol-track",
                bounds: "32,24,48,296",
                role: "Slider",
                label: "Volume",
            },
        ],
    },
};

/** Build a `SpatialSemanticsNode` for one scene quad (panel or orbiter), or null if unmapped. */
function semanticsNode(quad, kind) {
    const spec = CONTENT[quad.id];
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
            children: spec.children.map((c) => ({
                nodeId: c.id,
                boundsInRoot: c.bounds,
                ...(c.text ? { text: c.text } : {}),
                ...(c.label ? { label: c.label } : {}),
                ...(c.role ? { role: c.role } : {}),
                ...(c.clickable ? { clickable: true } : {}),
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
