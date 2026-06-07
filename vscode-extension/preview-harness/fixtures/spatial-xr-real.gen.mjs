// Generator for a LIVE spatial fixture, built from a real `composePreviewRenderXr`
// render directory. Unlike `spatial-semantics.gen.mjs` (which wraps the committed
// `spatial-rich` capture assembled from per-panel renders), this reads the actual
// XR render-task output —
//   renders/<id>/scene.json
//   renders/<id>/compose-spatial-semantics.json   (optional companion)
//   renders/<id>/<panel>.png                       (one texture per SpatialPanel)
// — so CI can snapshot the 3D viewer fed by the real subspace render: the true
// end-to-end capture, closing the gap between the committed fixture-proxy and the
// production render path.
//
// Usage (CI, in the `render-xr-composite` job after `composePreviewRenderXr`):
//   node preview-harness/fixtures/spatial-xr-real.gen.mjs \
//     --render-dir <module>/build/compose-previews/renders/<id> \
//     --texture-base /spatial-fixtures/spatial-xr-real/ \
//     > preview-harness/fixtures/spatial-xr-real.json
// (the render dir's textures are copied under spatial-fixtures/spatial-xr-real/ so
//  the harness server resolves them against `textureBaseUri`).

import { readFileSync } from "node:fs";
import { join } from "node:path";

/**
 * Assemble the harness fixture object from a real render dir. Pure (no I/O beyond
 * reading the two JSON inputs) so it is unit-testable. The `scene.json` is
 * required; the semantics tree is best-effort (a missing one just omits the 2D
 * wireframe overlays, matching the production `loadSpatialRender` contract).
 */
export function buildXrRealFixture(renderDir, textureBaseUri) {
    const scene = JSON.parse(
        readFileSync(join(renderDir, "scene.json"), "utf8"),
    );
    let semanticsTree;
    try {
        semanticsTree = JSON.parse(
            readFileSync(
                join(renderDir, "compose-spatial-semantics.json"),
                "utf8",
            ),
        );
    } catch {
        semanticsTree = undefined;
    }
    return {
        description:
            "Live XR spatial render: setSpatialScene carries the real " +
            "composePreviewRenderXr scene + harvested semantics for " +
            `${scene.previewId ?? "an XR preview"}, so the 3D viewer composites ` +
            "the actual rendered panels. Click the toggle to switch to 3D.",
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
                textureBaseUri,
                ...(semanticsTree ? { semanticsTree } : {}),
            },
        ],
        actions: [{ click: "#btn-spatial-toggle" }],
        // three.js texture decode + canvas composite isn't observable via the
        // rAF/img settle, so give it a beat (matches the other spatial fixtures).
        settleMs: 1600,
    };
}

function arg(name, fallback) {
    const i = process.argv.indexOf("--" + name);
    return i >= 0 ? process.argv[i + 1] : fallback;
}

// CLI entry — only when run directly, not when imported by the test.
if (import.meta.url === `file://${process.argv[1]}`) {
    const renderDir = arg("render-dir");
    if (!renderDir) {
        process.stderr.write(
            "usage: spatial-xr-real.gen.mjs --render-dir <dir> [--texture-base <uri>]\n",
        );
        process.exit(2);
    }
    const textureBase = arg("texture-base", "/spatial-fixtures/spatial-xr-real/");
    process.stdout.write(
        JSON.stringify(buildXrRealFixture(renderDir, textureBase), null, 2) +
            "\n",
    );
}
