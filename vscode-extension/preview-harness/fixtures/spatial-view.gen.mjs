// Generator for `spatial-view.json`. Run with:
//   node preview-harness/fixtures/spatial-view.gen.mjs > preview-harness/fixtures/spatial-view.json
//
// Exercises the panel's 2D ⇄ 3D toggle: boots `<preview-app>` with the spatial
// bundle source on its dataset, posts a `setSpatialScene` (the committed
// `spatial-rich` scene, reused so the two stay in lockstep), then clicks the
// toggle button to switch to the 3D `<spatial-view>`. `settleMs` gives three.js
// a beat to decode the quad textures before the screenshot.
//
// The scene's textures resolve against `textureBaseUri`; the harness server is
// rooted at the extension dir, so the committed `spatial-fixtures/spatial-rich/`
// PNGs are reachable at that absolute path.

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

const fixture = {
    description:
        "Panel 2D ⇄ 3D toggle: setSpatialScene + click the toggle to show the 3D spatial view (rich scene — angled panels + orbiters).",
    dataset: {
        earlyFeatures: "false",
        minimalMode: "false",
        // The controller injects this bundle on first switch to 3D. Absolute
        // path from the harness server root (the extension dir).
        spatialSrc: "/media/webview/spatial.js",
        cspNonce: "harness",
    },
    messages: [
        {
            command: "setSpatialScene",
            scene,
            textureBaseUri: "/spatial-fixtures/spatial-rich/",
        },
    ],
    actions: [{ click: "#btn-spatial-toggle" }],
    // three.js texture decode isn't observable via the rAF/img settle.
    settleMs: 1600,
};

process.stdout.write(JSON.stringify(fixture, null, 2) + "\n");
