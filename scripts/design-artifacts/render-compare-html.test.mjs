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

test("images load with crossOrigin so the canvas isn't tainted on htmlpreview", () => {
  // htmlpreview serves the page from htmlpreview.github.io but the PNGs from raw.githubusercontent
  // (cross-origin); without a CORS request the canvas taints and no row scores. Pin the fix.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /img\.crossOrigin = "anonymous"/);
  // …but not for the same-origin blob: / data: URLs the hybrid inline path builds.
  assert.match(html, /\/\^\(data\|blob\):\/i\.test\(src\)/);
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

test("each row carries its group as a sublabel, in one flat sortable table", () => {
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set() });
  // Single sortable body, rows tagged .crow so the scorer can reorder them.
  assert.match(html, /<tbody id="rows">/);
  assert.match(html, /<tr class="crow"/);
  // Group is a per-row sublabel (not a group header row), in catalog order.
  assert.match(html, /<span class="grp">Buttons<\/span>/);
  assert.match(html, /<span class="grp">Cards<\/span>/);
  assert.ok(html.indexOf("Buttons") < html.indexOf("Cards"), "initial paint keeps catalog order");
});

test("the scorer reorders rows worst-match-first once scored", () => {
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /function sortKey/);
  // Unscored/n-a rows sink to the bottom (no scoreValue → Infinity).
  assert.match(html, /Number\.isFinite\(v\) \? v : Infinity/);
  // Sorted ascending by match % and re-appended into the flat body.
  assert.match(html, /sort\(\(a, b\) => sortKey\(a\) - sortKey\(b\)\)/);
  assert.match(html, /getElementById\("rows"\)/);
  // The Match header signals the ascending (worst-first) order.
  assert.match(html, /Match ↑/);
});

test("the scorer inlines hybrid raster crops as data URIs so their layers score", () => {
  // A hybrid figma-svg's <image href="…figma-raster/…png"> layers don't draw in a
  // secure-static <img> load; the scorer must inline them so hybrid stickers score
  // their full chrome, not a half-empty vector. Pin the mechanism.
  const html = renderCompareHtml(catalog, {
    figmaSvgSlugs: new Set(["button-filled"]),
    hybridSlugs: new Set(["button-filled"]),
  });
  assert.match(html, /function inlineRasters/);
  assert.match(html, /readAsDataURL/);
  assert.match(html, /new Blob\(\[svgText\], \{ type: "image\/svg\+xml" \}\)/);
  assert.match(html, /xlink:href\|href/); // matches both href spellings
  // Crops must resolve from the SVG's resolved response URL, not location.href: under
  // htmlpreview the page origin differs from the <base> that relative assets resolve from.
  assert.match(html, /inlineRasters\(svgText, resp\.url\)/);
  assert.doesNotMatch(html, /new URL\(tr\.dataset\.svg, location\.href\)/);
});

test("no figma-svgs at all → every row inert, still a complete inventory", () => {
  const html = renderCompareHtml(catalog, {});
  assert.doesNotMatch(html, /data-svg=/);
  // Both components still listed.
  assert.match(html, /button-filled/);
  assert.match(html, /card-elevated/);
});
