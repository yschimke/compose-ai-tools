// Generator for `spatial-semantics.json`. Run with:
//   node preview-harness/fixtures/spatial-semantics.gen.mjs > preview-harness/fixtures/spatial-semantics.json
//
// Wraps the committed `spatial-rich` scene + its companion harvested SpatialSemanticsTree into a
// harness fixture, so the 3D viewer draws each panel's REAL Compose semantics over its screenshot
// face (the "screenshot + wireframe overlay" preview). Both inputs are produced together by the
// Kotlin generator `SpatialRichFixtureGeneratorTest` (renders each panel composable to a PNG and
// harvests its semantics), so the boxes land exactly on the rendered UI — no hand-authored geometry.

import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const richDir = join(here, "../../spatial-fixtures/spatial-rich");
const scene = JSON.parse(readFileSync(join(richDir, "scene.json"), "utf8"));
const semanticsTree = JSON.parse(
    readFileSync(join(richDir, "semantics-tree.json"), "utf8"),
);

const fixture = {
    description:
        "Spatial 3D view with 2D wireframe overlays: setSpatialScene carries the spatial-rich scene " +
        "plus its harvested Compose semantics tree, so the viewer draws each panel's real semantics " +
        "boxes over its screenshot face. Click the toggle to switch to 3D.",
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
