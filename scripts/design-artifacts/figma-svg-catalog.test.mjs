/**
 * Unit tests for shipping the layered `compose/figma-svg` export per sticker in the design-catalog
 * bundle: the index card links `figma/<slug>.svg` only when one was carried, and the README glance
 * table reports the figma-svg count. The driver's `figmaSvgByFunction` reader + emit loop are
 * exercised end-to-end by the bundle round-trip; here we pin the two presentation surfaces.
 *
 * Run with `node --test scripts/design-artifacts/`.
 */
import assert from "node:assert/strict";
import { test } from "node:test";

import { renderIndexHtml } from "./render-index-html.mjs";
import { renderReadmeMd } from "./render-readme-md.mjs";
import {
  figmaRastersForId,
  figmaSvgByFunction,
  rewriteRasterHrefs,
} from "./figma-svg-emit.mjs";

const catalog = {
  system: "compose-m3",
  title: "Compose M3",
  components: [
    { componentId: "button-filled", group: "Buttons", images: [] },
    { componentId: "card-elevated", group: "Cards", images: [] },
  ],
};

test("index links figma/<slug>.svg only for components that carried a figma-svg", () => {
  const html = renderIndexHtml(catalog, {
    figmaSvgSlugs: new Set(["button-filled"]),
  });
  assert.match(html, /href="figma\/button-filled\.svg"[^>]*>figma svg ↗/);
  // card-elevated carried none — no figma link for it.
  assert.doesNotMatch(html, /href="figma\/card-elevated\.svg"/);
});

test("index emits no figma links when none were carried", () => {
  const html = renderIndexHtml(catalog, {});
  assert.doesNotMatch(html, /figma svg ↗/);
});

test("README glance reports the editable design-vector (figma-svg) count", () => {
  const md = renderReadmeMd(catalog, { imageCount: 4, wireframeCount: 2, figmaSvgCount: 2 });
  assert.match(md, /Editable design vectors \(figma-svg\)/);
  assert.match(md, /Editable design vectors \(figma-svg\)[^\n]*\*\*2\*\*/);
});

const enc = (s) => new TextEncoder().encode(s);

test("figmaSvgByFunction prefers the light variant and keeps the preview id", () => {
  const bundle = {
    previews: [
      { id: "Fab_Dark", functionName: "Fab" },
      { id: "Fab_Light", functionName: "Fab" },
    ],
    entries: {
      "previews/Fab_Dark.figma.svg": enc("<svg><!--dark--></svg>"),
      "previews/Fab_Light.figma.svg": enc("<svg><!--light--></svg>"),
    },
  };
  const byFn = figmaSvgByFunction(bundle);
  assert.equal(byFn.get("Fab").id, "Fab_Light");
  assert.match(byFn.get("Fab").svg, /light/);
});

test("figmaRastersForId returns only that preview's crops", () => {
  const bundle = {
    entries: {
      "previews/Fab_Light.figma-raster/node-1.png": enc("A"),
      "previews/Fab_Light.figma-raster/node-2.png": enc("B"),
      "previews/Other_Light.figma-raster/node-1.png": enc("C"),
      "previews/Fab_Light.figma.svg": enc("<svg/>"),
    },
  };
  const crops = figmaRastersForId(bundle, "Fab_Light");
  assert.deepEqual([...crops.keys()].sort(), ["node-1.png", "node-2.png"]);
});

test("figmaRastersForId rejects path-traversal / nested crop names (zip-slip guard)", () => {
  const bundle = {
    entries: {
      "previews/Fab_Light.figma-raster/node-1.png": enc("ok"),
      "previews/Fab_Light.figma-raster/../../README.md": enc("evil"),
      "previews/Fab_Light.figma-raster/nested/x.png": enc("evil"),
      "previews/Fab_Light.figma-raster/..": enc("evil"),
    },
  };
  const crops = figmaRastersForId(bundle, "Fab_Light");
  // Only the bare filename survives; anything with a separator or a `..` segment is dropped.
  assert.deepEqual([...crops.keys()], ["node-1.png"]);
});

test("rewriteRasterHrefs re-points hrefs to a per-slug dir (no cross-slug collisions)", () => {
  const svg = '<image href="figma-raster/node-1.png"/><image href="figma-raster/node-2.png"/>';
  const out = rewriteRasterHrefs(svg, "fab");
  assert.equal(
    out,
    '<image href="fab.figma-raster/node-1.png"/><image href="fab.figma-raster/node-2.png"/>',
  );
  // A vector-only SVG is untouched.
  assert.equal(rewriteRasterHrefs("<svg><rect/></svg>", "fab"), "<svg><rect/></svg>");
});
