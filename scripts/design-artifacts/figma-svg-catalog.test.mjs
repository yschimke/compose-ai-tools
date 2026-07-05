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
