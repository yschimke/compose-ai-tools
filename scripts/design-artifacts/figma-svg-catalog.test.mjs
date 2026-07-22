/**
 * Unit tests for shipping the layered `compose/figma-svg` export per sticker in the design-catalog
 * bundle: the index card links `figma/<slug>.svg` only when one was carried, and the README glance
 * table reports the figma-svg count. The driver's `figmaSvgByFunction` reader + emit loop are
 * exercised end-to-end by the bundle round-trip; here we pin the two presentation surfaces.
 *
 * Also covers the per-variant emit (`figma/<slug>/<variant>.svg`, one per rendered preview,
 * mirroring `images/<slug>/<variant>.png`): its bundle readers (`figmaSvgById` / `figmaSvgByIds`,
 * including the `--extra-renders` fold), its manifest-driven path mapping (`figmaVariantSvgPath`)
 * and the variant-keyed hybrid raster dir.
 *
 * Run with `node --test "scripts/design-artifacts/*.test.mjs"`.
 */
import assert from "node:assert/strict";
import { test } from "node:test";

import { renderIndexHtml } from "./render-index-html.mjs";
import { renderReadmeMd } from "./render-readme-md.mjs";
import {
  figmaRastersForId,
  figmaSvgByFunction,
  figmaSvgByFunctions,
  figmaSvgById,
  figmaSvgByIds,
  figmaVariantSvgPath,
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

test("index renders the hero <img> when the catalog carries images (not 'no render')", () => {
  // Regression: renderIndexHtml must be fed the serialized catalog (which carries component.images),
  // not the in-memory one — else every card falls back to the shot--missing "no render" placeholder.
  const withImages = {
    system: "compose-m3",
    title: "Compose M3",
    components: [
      {
        componentId: "button-filled",
        group: "Buttons",
        images: [
          {
            path: "images/button-filled/ideal__default__light.png",
            variant: "ideal",
            width: 200,
            height: 100,
          },
        ],
      },
    ],
  };
  const html = renderIndexHtml(withImages, {});
  assert.match(html, /<img[^>]*src="images\/button-filled\/ideal__default__light\.png"/);
  // The card figure must be the rendered `shot`, not the `shot shot--missing` "no render" fallback
  // (the bare `.shot--missing` CSS class still appears in the <style> block — match the card only).
  assert.doesNotMatch(html, /class="shot shot--missing"/);
});

test("index shows 'no render' only when a component truly has no images", () => {
  const html = renderIndexHtml({ components: [{ componentId: "x", group: "G", images: [] }] }, {});
  assert.match(html, /class="shot shot--missing"/);
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

test("figmaSvgByFunctions folds an --extra-renders bundle in (adds extra-only fns; extra wins)", () => {
  const primary = {
    previews: [{ id: "Card_Light", functionName: "Card" }],
    entries: { "previews/Card_Light.figma.svg": enc("<svg><!--primary card--></svg>") },
  };
  const extra = {
    previews: [
      { id: "Card_Light", functionName: "Card" }, // same-named: extra overrides
      { id: "Chat_Light", functionName: "Chat" }, // extra-only: must be added, not dropped
    ],
    entries: {
      "previews/Card_Light.figma.svg": enc("<svg><!--extra card--></svg>"),
      "previews/Chat_Light.figma.svg": enc("<svg><!--extra chat--></svg>"),
    },
  };
  const byFn = figmaSvgByFunctions([primary, extra]);
  // The regression this guards: an extra-renders-only function used to be dropped (primary-only read).
  assert.ok(byFn.has("Chat"), "extra-only function carries its figma-svg");
  assert.match(byFn.get("Chat").svg, /extra chat/);
  // On a name clash the extra bundle wins, matching the candidate fold.
  assert.match(byFn.get("Card").svg, /extra card/);
  // Falsy bundles are skipped (the primary-only, no-extra-renders case).
  assert.deepEqual([...figmaSvgByFunctions([primary, null]).keys()], ["Card"]);
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

test("rewriteRasterHrefs keys the per-variant dir on the variant basename", () => {
  // Both variants of one component carry a `node-1.png` and both vectors live in the same
  // `figma/<slug>/` dir, so keying on the slug would have the dark crop overwrite the light one.
  const svg = '<image href="figma-raster/node-1.png"/>';
  assert.equal(
    rewriteRasterHrefs(svg, "ideal__default__light"),
    '<image href="ideal__default__light.figma-raster/node-1.png"/>',
  );
  assert.equal(
    rewriteRasterHrefs(svg, "ideal__default__dark"),
    '<image href="ideal__default__dark.figma-raster/node-1.png"/>',
  );
});

test("figmaSvgById keeps every carried variant, not just the light one", () => {
  const bundle = {
    previews: [
      { id: "Fab_Dark", functionName: "Fab" },
      { id: "Fab_Light", functionName: "Fab" },
      { id: "Fab_NoVector", functionName: "Fab" },
    ],
    entries: {
      "previews/Fab_Dark.figma.svg": enc("<svg><!--dark--></svg>"),
      "previews/Fab_Light.figma.svg": enc("<svg><!--light--></svg>"),
    },
  };
  const byId = figmaSvgById(bundle);
  assert.deepEqual([...byId.keys()].sort(), ["Fab_Dark", "Fab_Light"]);
  assert.match(byId.get("Fab_Dark"), /dark/);
  assert.match(byId.get("Fab_Light"), /light/);
  // A preview that produced no drawing layers carries no entry — that's the reportable gap.
  assert.equal(byId.has("Fab_NoVector"), false);
});

test("figmaSvgById ignores a carried entry that isn't an SVG", () => {
  const bundle = {
    previews: [{ id: "Fab_Light", functionName: "Fab" }],
    entries: { "previews/Fab_Light.figma.svg": enc("not markup") },
  };
  assert.equal(figmaSvgById(bundle).size, 0);
});

test("figmaSvgByIds folds an --extra-renders bundle in (adds extra-only ids; extra wins)", () => {
  // The regression this guards: a preview rendered ONLY by the supplementary bundle (a screen from
  // a second CMP-desktop module) used to get no per-variant vector at all, because the emit read
  // the primary bundle alone — which is the whole extra-renders-shaped catalog.
  const primary = {
    previews: [{ id: "Card_Light", functionName: "Card" }],
    entries: { "previews/Card_Light.figma.svg": enc("<svg><!--primary card--></svg>") },
  };
  const extra = {
    previews: [
      { id: "Card_Light", functionName: "Card" }, // same id: extra overrides
      { id: "ChatScreen_Dark", functionName: "ChatScreen" }, // extra-only: must be added
    ],
    entries: {
      "previews/Card_Light.figma.svg": enc("<svg><!--extra card--></svg>"),
      "previews/ChatScreen_Dark.figma.svg": enc("<svg><!--extra chat dark--></svg>"),
    },
  };
  const byId = figmaSvgByIds([primary, extra]);
  assert.ok(byId.has("ChatScreen_Dark"), "extra-renders-only preview id carries its figma-svg");
  assert.match(byId.get("ChatScreen_Dark"), /extra chat dark/);
  assert.match(byId.get("Card_Light"), /extra card/);
  // Falsy bundles are skipped (the primary-only, no-extra-renders case).
  assert.deepEqual([...figmaSvgByIds([primary, null]).keys()], ["Card_Light"]);
});

test("figmaVariantSvgPath mirrors the raster path (images/ → figma/, .png → .svg)", () => {
  assert.equal(
    figmaVariantSvgPath("images/button-filled/ideal__default__dark__compact.png"),
    "figma/button-filled/ideal__default__dark__compact.svg",
  );
  // The back-compat vector `figma/<slug>.svg` is a *file* while the per-variant set lives in a
  // `figma/<slug>/` *directory*, so the two never collide.
  assert.notEqual(
    figmaVariantSvgPath("images/button-filled/ideal__default__light.png"),
    "figma/button-filled.svg",
  );
});

test("figmaVariantSvgPath rejects anything that isn't a plain images/<slug>/<variant>.png", () => {
  for (const bad of [
    undefined,
    null,
    42,
    "",
    "wireframes/button-filled.svg",
    "images/button-filled/shot.webp",
    "images/",
    // A flat image path would map onto the back-compat `figma/<slug>.svg` and clobber it.
    "images/button-filled.png",
    "images/../../etc/passwd.png",
    "images/a/../../b.png",
    "images/a\\b.png",
    "images//b.png",
  ]) {
    assert.equal(figmaVariantSvgPath(bad), null, `expected null for ${JSON.stringify(bad)}`);
  }
});

test("manifest-driven emit pairs every image with its variant vector and counts the gaps", () => {
  // Mirrors the driver's per-variant loop: walk the written manifest's images[], look the image's
  // previewId up in the vectors folded across BOTH bundles, and write the vector at the image's own
  // path. An image whose previewId carried no vector is a counted gap rather than a silent
  // omission; an image with no previewId at all (deliberately left unbridged) is skipped silently.
  const bundle = {
    previews: [
      { id: "Fab_Light", functionName: "Fab" },
      { id: "Fab_Dark", functionName: "Fab" },
    ],
    entries: {
      "previews/Fab_Light.figma.svg": enc("<svg><!--light--></svg>"),
      "previews/Fab_Dark.figma.svg": enc("<svg><!--dark--></svg>"),
    },
  };
  // Extra-renders-only screen: present in neither the primary bundle nor `figmaSvgByFunction`.
  const extraBundle = {
    previews: [{ id: "ChatScreen_Light", functionName: "ChatScreen" }],
    entries: { "previews/ChatScreen_Light.figma.svg": enc("<svg><!--chat--></svg>") },
  };
  const manifest = {
    components: [
      {
        componentId: "fab",
        images: [
          { path: "images/fab/ideal__default__light.png", previewId: "Fab_Light" },
          { path: "images/fab/ideal__default__dark.png", previewId: "Fab_Dark" },
          { path: "images/fab/ideal__default__dark__compact.png", previewId: "Fab_Dark_Compact" },
          { path: "images/fab/ideal__default__light__no-preview-id.png" },
        ],
      },
      {
        componentId: "chat-screen",
        images: [
          { path: "images/chat-screen/ideal__default__light.png", previewId: "ChatScreen_Light" },
        ],
      },
    ],
  };
  const byId = figmaSvgByIds([bundle, extraBundle]);
  const written = new Map();
  let gaps = 0;
  for (const component of manifest.components) {
    for (const image of component.images) {
      if (!image.previewId) continue;
      const target = figmaVariantSvgPath(image.path);
      if (!target) continue;
      const carried = byId.get(image.previewId);
      if (!carried) {
        gaps += 1;
        continue;
      }
      written.set(target, carried);
    }
  }
  assert.deepEqual(
    [...written.keys()].sort(),
    [
      "figma/chat-screen/ideal__default__light.svg",
      "figma/fab/ideal__default__dark.svg",
      "figma/fab/ideal__default__light.svg",
    ],
  );
  assert.match(written.get("figma/fab/ideal__default__dark.svg"), /dark/);
  // The extra-renders-only screen gets its vector from the second bundle, not the primary.
  assert.match(written.get("figma/chat-screen/ideal__default__light.svg"), /chat/);
  // Fab_Dark_Compact was rendered but carried no vector; the image with no previewId isn't a gap.
  assert.equal(gaps, 1);
});
