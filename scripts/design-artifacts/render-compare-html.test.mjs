/**
 * Unit tests for the PNG-vs-SVG comparison page (`compare.html`): each component
 * is a row pairing its rendered PNG with its browser-rendered figma-svg, and the
 * page carries the in-page structural-similarity (SSIM) scorer. The actual scoring
 * runs in a browser canvas (untestable under `node --test`); here we pin the page
 * structure — the `data-png`/`data-svg` wiring the scorer walks, the fallbacks for
 * components missing one side, the hybrid flag, and that the SSIM script is present.
 *
 * Run with `node --test scripts/design-artifacts/`.
 */
import assert from "node:assert/strict";
import { test } from "node:test";

import { renderCompareHtml } from "./render-compare-html.mjs";

const png = (path, extra = {}) => ({
  path,
  variant: "ideal",
  state: "default",
  theme: "light",
  width: 200,
  height: 100,
  ...extra,
});

const catalog = {
  system: "compose-m3",
  title: "Compose M3",
  renderer: "compose-preview 0.16.2",
  components: [
    {
      componentId: "button-filled",
      group: "Buttons",
      images: [png("images/button-filled/ideal__default__light.png")],
    },
    {
      componentId: "card-elevated",
      group: "Cards",
      images: [png("images/card-elevated/ideal__default__light.png")],
    },
  ],
};

test("a comparable component wires data-png + data-svg for the scorer", () => {
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(
    html,
    /data-png="images\/button-filled\/ideal__default__light\.png" data-svg="figma\/button-filled\.svg"/,
  );
  // Both columns show the actual images.
  assert.match(html, /<img[^>]*src="images\/button-filled\/ideal__default__light\.png"/);
  assert.match(html, /<img[^>]*src="figma\/button-filled\.svg"/);
});

test("the in-page SSIM scorer is embedded", () => {
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /function ssim/);
  assert.match(html, /querySelectorAll\("tr\[data-png\]\[data-svg\]"\)/);
});

test("the scorer aligns the SVG's export padding out before scoring (translate crop)", () => {
  // The export pads the canvas + wraps the tree in translate(tx,ty); the scorer must crop
  // that back to the PNG's padding-free space (mirroring FigmaSvgFidelity.alignToRender),
  // else every faithful vector scores low from a constant inset. Pin the mechanism.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /function translateOf/);
  assert.match(html, /translate\\\(/); // reads the SVG's root translate
  assert.match(html, /-tx \* scale, -ty \* scale/); // draws the SVG offset to crop the padding
  assert.match(html, /fetch\(tr\.dataset\.svg\)/); // fetches the SVG source to read the translate
});

test("a component with no figma-svg gets an inert row (no data-svg, '—' score)", () => {
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  // card-elevated has a PNG but no svg → not scored, no data-svg attribute for it.
  assert.doesNotMatch(html, /data-svg="figma\/card-elevated\.svg"/);
  assert.match(html, /no figma-svg/);
});

test("the light-themed default PNG is chosen over dark for a fair compare", () => {
  const themed = {
    system: "compose-m3",
    components: [
      {
        componentId: "button-filled",
        group: "Buttons",
        images: [
          png("images/button-filled/ideal__default__dark.png", { theme: "dark" }),
          png("images/button-filled/ideal__default__light.png", { theme: "light" }),
        ],
      },
    ],
  };
  const html = renderCompareHtml(themed, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /data-png="images\/button-filled\/ideal__default__light\.png"/);
  assert.doesNotMatch(html, /data-png="images\/button-filled\/ideal__default__dark\.png"/);
});

test("a hybrid figma-svg is flagged", () => {
  const html = renderCompareHtml(catalog, {
    figmaSvgSlugs: new Set(["button-filled"]),
    hybridSlugs: new Set(["button-filled"]),
  });
  assert.match(html, /class="badge"[^>]*>hybrid</);
});

test("the summary counts comparable components (both PNG and figma-svg)", () => {
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  // 2 components, 1 comparable (only button-filled has an svg).
  assert.match(html, /2 components/);
  assert.match(html, /1 comparable/);
  assert.match(html, /id="done">0<\/b> \/ 1/);
});

test("components are grouped, preserving first-seen group order", () => {
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set() });
  assert.ok(html.indexOf("Buttons") < html.indexOf("Cards"), "Buttons group precedes Cards");
});

test("no figma-svgs at all → every row inert, still a complete inventory", () => {
  const html = renderCompareHtml(catalog, {});
  assert.doesNotMatch(html, /data-svg=/);
  // Both components still listed.
  assert.match(html, /button-filled/);
  assert.match(html, /card-elevated/);
});
