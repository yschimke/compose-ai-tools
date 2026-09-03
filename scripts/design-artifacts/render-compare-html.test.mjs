/**
 * Unit tests for the SVG-vs-PNG comparison page (`compare.html`): each component
 * is a row pairing its browser-rendered figma-svg with its rendered PNG, and the
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

test("a deferred vector uses the live image URL instead of a missing static path", () => {
  const deferred = structuredClone(catalog);
  deferred.components[0].images[0].figmaSvg =
    "https://preview.coo.ee/compose-m3/render/button-filled__ideal__default__light.svg";
  const html = renderCompareHtml(deferred, {
    figmaSvgSlugs: new Set(["button-filled"]),
    figmaVariantSvgPaths: new Set(),
  });
  assert.match(
    html,
    /data-svg="https:\/\/preview\.coo\.ee\/compose-m3\/render\/button-filled__ideal__default__light\.svg"/,
  );
  assert.doesNotMatch(html, /data-svg="figma\/button-filled\.svg"/);
});

test("the design vector column comes before the render column", () => {
  // The house rule: an imported/exported design spec is drawn to the LEFT of the
  // render it is compared against — the same order the viewer's spec lane uses
  // (Spec / Diff / Render) and the `figma-svg | diff | render` fidelity composite.
  // This page used to lead with the PNG, so a reader who moved between the two
  // surfaces had to re-establish which side was which.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /<th scope="col">SVG \(browser-rendered\)<\/th>\s*<th scope="col">PNG<\/th>/);
  const row = html.slice(html.indexOf('<tr class="crow"'));
  assert.ok(
    row.indexOf('class="col-svg"') < row.indexOf('class="col-png"'),
    "the figma-svg cell must precede the PNG cell in the row",
  );
});

test("each theme pairs its PNG with the matching per-variant SVG", () => {
  const themed = {
    system: "compose-m3",
    components: [
      {
        componentId: "button-filled",
        group: "Buttons",
        images: [
          png("images/button-filled/ideal__default__light.png", { theme: "light" }),
          png("images/button-filled/ideal__default__dark.png", { theme: "dark" }),
        ],
      },
    ],
  };
  const html = renderCompareHtml(themed, {
    figmaSvgSlugs: new Set(["button-filled"]),
    figmaVariantSvgPaths: new Set([
      "figma/button-filled/ideal__default__light.svg",
      "figma/button-filled/ideal__default__dark.svg",
    ]),
    hybridSlugs: new Set(["button-filled"]),
  });
  assert.match(
    html,
    /data-png="images\/button-filled\/ideal__default__light\.png" data-png-dark="images\/button-filled\/ideal__default__dark\.png" data-svg="figma\/button-filled\/ideal__default__light\.svg" data-svg-dark="figma\/button-filled\/ideal__default__dark\.svg"/,
  );
  assert.match(html, /function currentSvg/);
  assert.match(html, /fetch\(svgPath\)/);
  // Raster hrefs are resolved from whichever variant SVG was fetched.
  assert.match(html, /inlineRasters\(svgText, resp\.url\)/);
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
  assert.match(html, /fetch\(svgPath\)/); // fetches the theme-matched SVG to read the translate
});

test("both columns are framed to the component's content bbox", () => {
  // A wear sticker is rendered on a 454² device canvas but its figma-svg is content-cropped; the
  // page reads the svg's translate + viewBox and clips the PNG column to the same window so the two
  // columns display at matching sizes instead of a speck-in-a-frame vs a tight vector. Pin it.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /function frameToComponent/);
  assert.match(html, /\.shot--framed/); // the clip style the scorer toggles on
  assert.match(html, /frameToComponent\(tr, rw, rh, tx, ty, sw, sh\)/); // called with the read bbox
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

test("the override control bar carries font scale, embedded fonts, and backdrop knobs", () => {
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /id="ov-fontScale"[^>]*type="range"/);
  assert.match(html, /id="ov-fonts"[^>]*type="checkbox"/);
  assert.match(html, /id="ov-bg"/);
  assert.match(html, /id="ov-reset"/);
  // The live value label + the active-probe banner the scorer fills in.
  assert.match(html, /id="ov-fontScale-val"/);
  assert.match(html, /id="ov-active"/);
});

test("the scorer applies the active overrides to the SVG before scoring", () => {
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /function applyOverrides/);
  // Font scale multiplies font-size / letter-spacing on the vector's text.
  assert.match(html, /font-size\|letter-spacing/);
  assert.match(html, /parseFloat\(n\) \* fs/);
  // Embedded-fonts-off drops the @font-face <style> so the browser substitutes a face.
  assert.match(html, /@font-face/);
  assert.match(html, /const svgText = applyOverrides\(rawSvg\)/);
});

test("a control change supersedes an in-flight scoring pass (run token)", () => {
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /let runSeq = 0/);
  assert.match(html, /mySeq !== runSeq/);
  // Slider drags are debounced so they don't launch a pass per pixel.
  assert.match(html, /function scheduleRun/);
});

test("the theme control + data-png-dark appear only when a dark capture exists", () => {
  // No dark render in the base catalog → no theme control, no data-png-dark.
  const light = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.doesNotMatch(light, /id="ov-theme"/);
  assert.doesNotMatch(light, /data-png-dark=/);

  // A component with a dark default → theme control offered, dark path wired for the probe.
  const dual = {
    system: "compose-m3",
    components: [
      {
        componentId: "button-filled",
        group: "Buttons",
        images: [
          png("images/button-filled/ideal__default__light.png", { theme: "light" }),
          png("images/button-filled/ideal__default__dark.png", { theme: "dark" }),
        ],
      },
    ],
  };
  const html = renderCompareHtml(dual, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /id="ov-theme"/);
  assert.match(html, /data-png-dark="images\/button-filled\/ideal__default__dark\.png"/);
  // The light default still drives the primary data-png.
  assert.match(html, /data-png="images\/button-filled\/ideal__default__light\.png"/);
});

test("identity overrides keep the cheap unchanged-vector path (baseline score preserved)", () => {
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  // When applyOverrides returns the text unchanged, the plain <img src=svg> load is used.
  assert.match(html, /const changed = inlined !== rawSvg/);
  assert.match(html, /changed \? await loadSvgString\(inlined\) : await loadImage\(svgPath\)/);
});

test("returning overrides to identity restores the displayed SVG (not the last blob)", () => {
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /function restoreSvg/);
  // The scorer takes the restore branch when the vector is unchanged.
  assert.match(html, /if \(changed\) showSvg\(tr, inlined\);\s*else restoreSvg\(tr\);/);
  // restoreSvg puts src back to the currently selected authored theme variant.
  assert.match(html, /const svgPath = currentSvg\(tr\)/);
  assert.match(html, /img\.getAttribute\("src"\) !== svgPath/);
});

test("a lazy display SVG keeps its blob URL until the image settles", () => {
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  // Below-fold images may not request their blob URL for an arbitrary amount of time. Revoke it
  // only after this display image has loaded (or failed), rather than on a wall-clock timeout.
  assert.match(html, /img\.addEventListener\("load", release\)/);
  assert.match(html, /img\.addEventListener\("error", release\)/);
  assert.match(html, /URL\.revokeObjectURL\(url\)/);
  assert.doesNotMatch(html, /setTimeout\(\(\) => URL\.revokeObjectURL\(url\), 4000\)/);
});

test("the theme override repoints the displayed PNG, not just the scored one", () => {
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  // The PNG column's <img> src follows the theme-selected path (guarded to avoid reloads).
  assert.match(html, /const pngImg = tr\.querySelector\("\.col-png \.shot img"\)/);
  assert.match(html, /shownPng !== pngPath/);
  assert.match(html, /pngImg\.src = pngPath/);
});

test("a superseded scoring pass bails before mutating the row", () => {
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  // scoreRow receives the run token and re-checks it after its awaits, before any DOM write.
  assert.match(html, /async function scoreRow\(tr, seq\)/);
  assert.match(html, /await scoreRow\(tr, mySeq\)/);
  assert.match(html, /if \(seq !== runSeq\) return null/);
});

test("a row is scored on two grounds, worst result winning", () => {
  // One opaque ground annihilates ink that matches it, and two planes flattened to the same
  // uniform field are IDENTICAL — so SSIM answers 1.0 and the row reports a perfect match for a
  // pair sharing nothing but the colour of its ink. A dark-first system draws light ink onto a
  // transparent sticker, which is exactly that case. Same rule the serve scorer applies through
  // COMPARISON_GROUNDS; this lane carries its own copy because it runs inside the report page.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /const GROUNDS = \["#ffffff", "#000000"\]/);
  // The ground is a parameter, not a constant baked into the rasteriser.
  assert.match(html, /function grayFromDraw\(draw, tw, th, ground\)/);
  assert.match(html, /ctx\.fillStyle = ground/);
  // Every ground rasterised before any is scored, then the minimum kept.
  assert.match(html, /const planes = GROUNDS\.map\(/);
  assert.match(html, /worst = Math\.min\(worst, Math\.max\(0, Math\.min\(100, ssim\(/);
});

test("the extra ground is gated on both sides actually showing one", () => {
  // A MIXED pair — an opaque Figma export against a render with a transparent surround — moves on
  // one side only, so the black pass would measure the grounds rather than the artwork and the
  // minimum would take it. Equal planes are how opacity is detected, which is why every ground is
  // rasterised up front rather than scored one at a time.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /function samePlane\(a, b\)/);
  assert.match(html, /const varies = \(side\) =>/);
  assert.match(html, /varies\("a"\) && varies\("b"\) \? planes : \[planes\[0\]\]/);
});

test("the page carries the two-sided eyedropper readout", () => {
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  // The panel names both sides, because the point of one hover is two pixels.
  assert.match(html, /id="pick-svg-val"/);
  assert.match(html, /id="pick-png-val"/);
  assert.match(html, /id="pick-verdict"/);
  assert.match(html, /<aside class="pick" id="pick" hidden/);
  assert.match(html, /wirePicker\(\)/);
});

test("the eyedropper samples at native resolution, not the scorer's downscale", () => {
  // MAX_SIDE exists to make SSIM robust to a half-pixel offset by box-averaging neighbours
  // together. Reading a colour back off that plane would answer with a blend that exists in
  // neither image — the one answer a colour picker must never give.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /function sampler\(tr, side\)/);
  assert.match(html, /ctx\.imageSmoothingEnabled = false/);
  assert.match(html, /ctx\.drawImage\(img, 0, 0\)/);
  // The sample is a single source pixel, addressed in that image's own space.
  assert.match(html, /getImageData\(px, py, 1, 1\)/);
});

test("the two samples are the same point, aligned the way the score is", () => {
  // The vector's pixel is the tile offset over the framing scale; the render's is that same
  // point less the export's root translate — the alignment scoreRow already applies before
  // measuring. Sharing it is what makes the pair one point rather than two lookalikes.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  // Both axes separately: frameToComponent rounds the displayed width and height independently,
  // so a capped non-square component is scaled by slightly different factors across and down.
  assert.match(html, /const meet = Math\.min\(svgRect\.width \/ nw, svgRect\.height \/ nh\);/);
  assert.match(html, /const svgX = \(fx - boxX\) \/ meet, svgY = \(fy - boxY\) \/ meet;/);
  // The render coordinate goes through the PNG's own displayed placement, whose width and
  // offset frameToComponent rounds independently; the exact translate is the fallback.
  assert.match(html, /renderX = \(fx - \(pr\.left - ps\.left\)\) \/ px;/);
  assert.match(html, /renderX = svgX - rec\.tx;/);
  // Both columns are marked, so the reading is visibly about one point in two images.
  assert.match(html, /for \(const shot of tr\.querySelectorAll\("\.shot--framed"\)\)/);
});

test("a sample is reported composited over the tile's own backdrop", () => {
  // A 10% state layer is a white pixel at alpha 26 in the vector and an opaque lightened
  // container in the render. Straight colours at different alphas are not comparable; only
  // the composite says whether the two agree, which is the question the row is asking.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /function over\(c, bg\)/);
  assert.match(html, /function backdrop\(fx, fy\)/);
  // The backdrop tracks the header's own control rather than assuming one ground.
  assert.match(html, /if \(OV\.bg === "white"\) return \{ r: 255, g: 255, b: 255 \}/);
});

test("a point outside one image reads as absent rather than as a colour", () => {
  // The two images have different extents: a point inside the vector's bbox can be off the
  // edge of the render. Clamping would invent a colour for a pixel that isn't there.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /if \(!\(px >= 0 && py >= 0 && px < ctx\.canvas\.width && py < ctx\.canvas\.height\)\) return null/);
  assert.match(html, /raw === undefined \? "still loading"/);
  assert.match(html, /: "outside this image";/);
});

test("the picker gives up on a tainted canvas the same way the scorer does", () => {
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /PICK\.blocked = true/);
  assert.match(html, /if \(b\) b\.style\.display = "block"/);
});

test("the readout docks away from the cursor", () => {
  // Pinned to one corner it covers the match column exactly when a bottom row is being read,
  // which is the moment that number matters most.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /panel\.classList\.toggle\("pick--left", rect\.left \+ fx > window\.innerWidth \/ 2\)/);
  assert.match(html, /\.pick--left \{ left:16px; right:auto; \}/);
});

test("a framed image is exempt from the narrow-viewport width cap", () => {
  // `.shot--framed img` and `.shot img` TIE on specificity — one class and one type each — so
  // the `max-width:150px` cap in the later media block wins and clamps a framed image inside a
  // tile up to 240px wide. That breaks the framing itself (the PNG column is offset using the
  // unclamped scale, so the columns stop lining up) and any mapping from the tile to a source
  // pixel with it. The doubled class outranks the cap.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /\.shot\.shot--framed img \{ position:absolute; max-width:none; max-height:none; \}/);
  assert.doesNotMatch(html, /^\s*\.shot--framed img \{/m);
});

test("only the hovered row holds sampling canvases", () => {
  // Every scored row stays in the DOM, so a canvas per row would grow with every row the cursor
  // visited — two full-size backing stores each — rather than staying flat the way the
  // sequential scorer intends.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /pending: \{ png: null, svg: null \},/);
  assert.match(html, /if \(ACTIVE\.tr !== tr\) \{ releaseActive\(\); ACTIVE\.tr = tr; \}/);
  // Zeroing the dimensions drops the backing store now rather than at collection time.
  assert.match(html, /ACTIVE\[side\]\.canvas\.width = 0; ACTIVE\[side\]\.canvas\.height = 0;/);
  // The per-row record carries the alignment only — no bitmaps.
  assert.match(html, /SAMPLES\.set\(tr, \{ tx, ty \}\)/);
});

test("the picker reads the images the row is displaying", () => {
  // The scorer swaps an overridden or hybrid vector into the column it shows; sampling that
  // element keeps what is read identical to what is seen, and retains nothing the page was not
  // already holding.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /function displayImg\(tr, side\)/);
  assert.match(html, /if \(!img \|\| !img\.complete \|\| !img\.naturalWidth\) \{/);
  // Used directly only when reading it cannot taint the canvas.
  assert.match(html, /if \(!taints\(img\)\) \{/);
});

test("a cross-origin display image is sampled through an origin-clean copy", () => {
  // The display elements carry no `crossorigin` attribute, and on the published htmlpreview
  // report they resolve cross-origin to raw.githubusercontent.com — relative `images/...` paths
  // included, since those resolve against the injected base. Drawing one taints the canvas and
  // every read throws. Putting the attribute in the markup would fix the read and break the
  // picture outright on a host that sends no header, so the copy is fetched instead, through the
  // same CORS path the scorer already loads by.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /function taints\(img\)/);
  assert.match(html, /new URL\(src, document\.baseURI\)\.origin !== location\.origin/);
  assert.match(html, /loadImage\(src\)\.then\(\(clean\) => \{/);
  // A blob or data URL is already origin-clean, and an overridden or hybrid vector lives in a
  // blob no reload could reproduce — so those are read off the element itself.
  assert.match(html, /if \(\/\^\(data\|blob\):\/i\.test\(src\)\) return false/);
  // The hover is replayed once the copy lands, so the reading appears where the cursor is.
  assert.match(html, /replayPick\(tr\);/);
});

test("an abandoned hover is not replayed when its copy lands", () => {
  // The cross-origin path waits for an origin-clean copy. If the cursor leaves meanwhile, the
  // resolved load must not reopen the panel and crosshairs at a point nobody is pointing at.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /LAST\.shot = null;/);
  assert.match(html, /function replayPick\(tr\)/);
  assert.match(html, /if \(!LAST\.shot \|\| !LAST\.shot\.isConnected\) return;/);
  assert.match(html, /if \(LAST\.shot\.closest\("tr\.crow"\) !== tr\) return;/);
});

test("a frozen reading is refreshed when its row rescores", () => {
  // The lock stops pointer moves from refreshing the panel, so a theme or backdrop change would
  // otherwise leave it asserting the previous pass's colours, over the previous pass's ground,
  // against a picture that has moved on.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /if \(ACTIVE\.tr === tr\) releaseActive\(\);\n\s*\/\/[^\n]*\n(\s*\/\/[^\n]*\n)*\s*replayPick\(tr\);/);
});

test("a not-yet-decoded image reads as pending, and cannot be frozen", () => {
  // Display images are loading="lazy", so a row scrolled to and clicked at once can be framed and
  // scored — the scorer loads its own copies — while the element has not decoded. Reporting that
  // as "outside this image" asserts something false about the picture, and locking it strands a
  // placeholder that no pointer move can refresh.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /if \(ctx\) return undefined;|if \(!ctx\) return undefined;/);
  assert.match(html, /PICK\.ready = svgRaw !== undefined && pngRaw !== undefined;/);
  assert.match(html, /PICK\.locked = PICK\.ready;/);
  // And the element's own load replays the hover, since nothing else would.
  assert.match(html, /img\.addEventListener\("load", \(\) => \{/);
});

test("each side tracks its own pending origin-clean load", () => {
  // One slot for both sides is overwritten by the second side of the same hover, so every later
  // pointermove restarts the first side's load — many concurrent decodes while the cursor moves,
  // instead of once per row per side.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /if \(ACTIVE\.pending\[side\] !== key\) \{/);
  assert.match(html, /if \(ACTIVE\.tr !== tr \|\| ACTIVE\.pending\[side\] !== key\) return;/);
  assert.match(html, /ACTIVE\.pending\.png = null;/);
  assert.match(html, /ACTIVE\.pending\.svg = null;/);
});

test("the live hover is stored against its tile, not the viewport", () => {
  // run() re-appends the rows in score order, the page scrolls, the window resizes — none of
  // which fires a pointer event, so a stored client point silently starts naming a different
  // row. An offset into a tile is measured against the thing being read and survives all three.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /const LAST = \{ shot: null, fx: 0, fy: 0, cx: 0, cy: 0 \}/);
  assert.match(html, /function pickAt\(shot, fx, fy\)/);
  // The pointer handlers do the conversion, once, where the event is.
  assert.match(html, /pickAt\(shot, e\.clientX - r\.left, e\.clientY - r\.top\)/);
});

test("an unlocked reading is dropped when scoring reorders the rows", () => {
  // A reorder fires no pointer event, so an unlocked panel would keep naming a row that has
  // slid out from under the cursor. A frozen reading is tied to its row and survives.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /if \(!PICK\.locked\) hidePick\(\);/);
});

test("crosshairs are cleared before the current row's pair is drawn", () => {
  // Moving straight from one row to the next never passes over non-shot content, so nothing
  // else takes the previous row's pair down and two rows both look like the live sample.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /function marks\(tr, fx, fy\) \{\n(\s*\/\/[^\n]*\n)+\s*clearMarks\(\);/);
});

test("one place waits for an image that has not decoded", () => {
  // Both the scale read in pickAt and the canvas in sampler need a decoded element, and neither
  // can wait on its own — a replacement vector from a rescore reaches the first and returns
  // before the second, which is where a frozen reading got stuck.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /function awaitImage\(tr, img\)/);
  assert.match(html, /awaitImage\(tr, svgImg\);/);
  assert.match(html, /awaitImage\(tr, img\);\n\s*return null;/);
});

test("a translucent pixel composites over the checker square under it", () => {
  // The checker is two colours: 8px #202022 squares over a #161617 base. Compositing everything
  // over the base reports a colour that is not on screen wherever the point lands on a light
  // square, and can call a transparent pixel and an opaque #161617 one identical. The parity is
  // measured from the rendered pattern: even square index is the base, odd is the light square.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /const light = \(Math\.floor\(fx \/ 8\) \+ Math\.floor\(fy \/ 8\)\) % 2 !== 0;/);
  assert.match(html, /return light \? \{ r: 32, g: 32, b: 34 \} : \{ r: 22, g: 22, b: 23 \};/);
  // And the ground is chosen per sampled point, not once per row.
  assert.match(html, /const g = backdrop\(fx, fy\);/);
});

test("pixels a host will not release settle as unreadable rather than retrying", () => {
  // A refused CORS load is not pending: a host that sends no header will not start sending one
  // because the cursor moved, so retrying on every pointermove churns doomed requests while the
  // panel reads "still loading" for good.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /failed: \{ png: null, svg: null \},/);
  assert.match(html, /if \(ACTIVE\.failed\[side\] === src\) return UNREADABLE;/);
  assert.match(html, /ACTIVE\.failed\[side\] = src;/);
  assert.match(html, /raw === UNREADABLE \? "pixels not readable \(no CORS\)"/);
});

test("the vector is mapped through its meet transform, not a stretch", () => {
  // An SVG in an <img> honours preserveAspectRatio, defaulting to xMidYMid meet, and the emitted
  // vectors never override it — so it scales uniformly by the smaller ratio and centres,
  // letterboxing the rest. frameToComponent rounds the box's width and height independently, so
  // the box rarely has the vector's natural aspect and the letterbox is real. Measured: a 308x109
  // vector in a 240x200 box draws an 84.9px band with 57.5px of transparency above and below.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /const meet = Math\.min\(svgRect\.width \/ nw, svgRect\.height \/ nh\);/);
  assert.match(html, /const boxX = \(svgRect\.width - nw \* meet\) \/ 2, boxY = \(svgRect\.height - nh \* meet\) \/ 2;/);
  // The render is a raster and does stretch to its box, so its mapping stays per-axis.
  assert.match(html, /const px = pr\.width \/ pngImg\.naturalWidth, py = pr\.height \/ pngImg\.naturalHeight;/);
});

test("a scroll or resize re-aims an unlocked reading", () => {
  // Both move the tiles under a cursor that has not moved, and neither fires a pointer event: the
  // tile offset stays valid for the tile it names while the cursor is now over a different part
  // of it. The client point has not changed, so it is what re-aims. A frozen reading is tied to
  // its row rather than the cursor and is left alone.
  const html = renderCompareHtml(catalog, { figmaSvgSlugs: new Set(["button-filled"]) });
  assert.match(html, /function reaim\(\)/);
  assert.match(html, /if \(PICK\.locked \|\| PICK\.blocked \|\| !LAST\.shot\) return;/);
  assert.match(html, /document\.elementFromPoint\(LAST\.cx, LAST\.cy\)/);
  assert.match(html, /addEventListener\("scroll", reaim, \{ passive: true, capture: true \}\);/);
  assert.match(html, /addEventListener\("resize", reaim\);/);
});
