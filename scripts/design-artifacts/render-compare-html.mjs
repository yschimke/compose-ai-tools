/**
 * Render a self-contained `compare.html` for a design-artifact catalog: every
 * component on one row, its rendered **PNG** (the raster source of truth) in one
 * column, its editable **figma-svg** (re-rasterized *by the browser*) in a second,
 * and a **structural-similarity score** in a third — so a designer can eyeball
 * every component's PNG↔SVG fidelity at once and spot which vectors drift. Rows
 * sort **worst-match-first** once scored, so the biggest divergences surface first.
 *
 * Why the score is computed live in the page rather than baked at build time: the
 * whole point is to measure the *browser's* SVG rasterization against the PNG, so
 * the comparison has to run where the SVG is actually drawn. The page loads both
 * images, draws them into offscreen canvases at a common size over a white
 * backdrop, and computes a windowed SSIM (Wang et al. structural similarity). SSIM
 * is a *structural* metric — it compares local mean/variance/covariance over
 * sliding windows, not pixel-for-pixel — and we pre-blur + downscale before
 * scoring, so a half-pixel rasterization offset between the two engines barely
 * moves the number (a hard per-pixel diff would read as "everything is broken").
 *
 * Pure + dependency-free: takes the in-memory `catalog` (the flattened manifest,
 * same shape `renderIndexHtml` reads) and returns an HTML string. Image refs are
 * the catalog's own relative `images/...` and `figma/<slug>.svg` paths, so the
 * page works straight from the branch checkout — no external assets, one inline
 * `<script>` for the in-browser scorer.
 *
 * Hybrid stickers: a *hybrid* figma-svg (opaque Image/Icon/TextField/Slider layers
 * backed by `<image href="…figma-raster/…png">` crops) would render half-empty if
 * loaded straight via `<img>`, because browsers draw SVG-in-`<img>` in "secure
 * static mode" and never fetch those external references — which used to sink hybrid
 * scores far below their true fidelity. The scorer therefore **inlines** each raster
 * crop as a `data:` URI before rasterizing, so the opaque layers draw and the score
 * reflects the whole sticker. Such rows are still flagged `hybrid` for provenance.
 */

import { slug } from "./render-wireframe-svg.mjs";

/** Minimal HTML-escape for text interpolated into the page. */
function esc(s) {
  return String(s ?? "").replace(
    /[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c],
  );
}

/** A component's `ideal` capture images (excludes the `layout` wireframe variant). */
function idealImages(component) {
  return (component.images ?? []).filter((i) => i.variant === "ideal");
}

/** True for the label-only, resting render (no folded state, no content-axis props). */
function isDefault(image) {
  const hasProps = Object.keys(image.props ?? {}).length > 0;
  const state = image.state ?? "default";
  return !hasProps && state === "default";
}

/**
 * The PNG to pit against the figma-svg: the **default, light-themed** render, so
 * it matches the light-preferred vector `figmaSvgByFunction` carries. Falls back
 * through default → light → largest so a component always yields a PNG when it has
 * one. Mirrors the index's hero pick, but pins the light theme for a fair compare.
 */
function comparePng(component) {
  const ideal = idealImages(component);
  const pool = ideal.length ? ideal : component.images ?? [];
  const defaults = pool.filter(isDefault);
  const byDefault = defaults.length ? defaults : pool;
  const light = byDefault.filter((i) => i.theme === "light");
  const chooseFrom = light.length ? light : byDefault;
  return [...chooseFrom].sort((a, b) => (b.width ?? 0) - (a.width ?? 0))[0];
}

/**
 * The client-side scorer, inlined as a string so the page is self-contained. It
 * walks every `<tr data-png data-svg>`, rasterizes both images to a shared canvas
 * over white, writes a windowed-SSIM percentage into the row's score cell, then
 * re-orders the rows worst-match-first.
 */
const SCORER = String.raw`
(() => {
  "use strict";
  // SSIM constants for 8-bit luma (Wang et al.): (0.01*255)^2, (0.03*255)^2.
  const C1 = 6.5025, C2 = 58.5225;
  // Cap the raster size: downscaling box-averages the two engines' antialiasing,
  // which is what makes the score robust to a sub-pixel offset. 192px max side is
  // plenty of structure for SSIM while keeping the per-row work cheap.
  const MAX_SIDE = 192;

  function loadImage(src) {
    return new Promise((res, rej) => {
      const img = new Image();
      img.decoding = "async";
      // Request the pixels with CORS so drawing them to a canvas doesn't taint it. On htmlpreview the
      // page origin is htmlpreview.github.io while the images load cross-origin from
      // raw.githubusercontent.com (which allows cross-origin reads); without this the canvas taints
      // and every getImageData throws, so no row scores. Set for relative / http(s) srcs (a relative
      // images/... path resolves cross-origin against the injected base on htmlpreview) but NOT for
      // the same-origin-clean blob: URLs the hybrid path builds, nor data: URLs (some engines refuse
      // to load those under a crossOrigin request).
      if (!/^(data|blob):/i.test(src)) img.crossOrigin = "anonymous";
      img.onload = () => res(img);
      img.onerror = () => rej(new Error("load failed: " + src));
      img.src = src;
    });
  }

  // Inline a hybrid figma-svg's relative <image href="…figma-raster/…png"> crops as
  // data: URIs. A secure-static <img>-loaded SVG never fetches external refs, so
  // without this the opaque layers (TextField/Slider chrome, icons) drop out and the
  // sticker scores far below its true fidelity. Same-origin fetch each crop, base64
  // it in, and return the rewritten text; a vector-only SVG (no external image refs)
  // comes back unchanged so its cheap <img> path is untouched. A crop that fails to
  // fetch is left as-is — that one layer just won't draw.
  async function inlineRasters(svgText, baseUrl) {
    const re = /(<image\b[^>]*?\b(?:xlink:href|href)\s*=\s*")([^"]+)(")/gi;
    const hrefs = [];
    let m;
    while ((m = re.exec(svgText))) if (!/^data:/i.test(m[2])) hrefs.push(m[2]);
    if (!hrefs.length) return svgText;
    const dataFor = new Map();
    await Promise.all([...new Set(hrefs)].map(async (h) => {
      try {
        const blob = await fetch(new URL(h, baseUrl).href).then((r) => r.blob());
        const data = await new Promise((res, rej) => {
          const fr = new FileReader();
          fr.onload = () => res(fr.result);
          fr.onerror = () => rej(new Error("raster read failed"));
          fr.readAsDataURL(blob);
        });
        dataFor.set(h, data);
      } catch (_) { /* leave this layer un-inlined */ }
    }));
    return svgText.replace(re, (full, pre, href, post) =>
      dataFor.has(href) ? pre + dataFor.get(href) + post : full);
  }

  // Load an SVG given as a string (rasters already inlined) via a Blob URL, so it
  // carries no external refs and the canvas it's drawn into stays untainted.
  async function loadSvgString(svgText) {
    const url = URL.createObjectURL(new Blob([svgText], { type: "image/svg+xml" }));
    try {
      return await loadImage(url);
    } finally {
      // Revoke on a macrotask so the synchronous drawImage that follows the await
      // still has the decoded bitmap.
      setTimeout(() => URL.revokeObjectURL(url), 0);
    }
  }

  // The figma-svg's root translate. The export (FigmaSvgModel) pads the canvas by
  // DEFAULT_PADDING and draws the tree under translate(tx, ty) with tx = padding - minX,
  // in the same px scale as the render PNG (dp->px already applied). The render PNG is
  // padding-free with content at (0,0), so aligning means cropping that translate back
  // out — matching the daemon's FigmaSvgFidelity.alignToRender. Integer-only, first match,
  // exactly as the Kotlin harness parses it; defaults to (0,0) for an un-translated SVG.
  function translateOf(svgText) {
    const m = /translate\(\s*(-?\d+)\s*,\s*(-?\d+)\s*\)/.exec(svgText);
    return m ? { tx: parseInt(m[1], 10), ty: parseInt(m[2], 10) } : { tx: 0, ty: 0 };
  }

  // Draw into a tw×th canvas over white via the caller's draw(ctx) and return the
  // grayscale (luma over white) plane as a Float32Array.
  function grayFromDraw(draw, tw, th) {
    const c = document.createElement("canvas");
    c.width = tw; c.height = th;
    const ctx = c.getContext("2d", { willReadFrequently: true });
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = "high";
    ctx.fillStyle = "#ffffff";
    ctx.fillRect(0, 0, tw, th);
    draw(ctx);
    const { data } = ctx.getImageData(0, 0, tw, th);
    const g = new Float32Array(tw * th);
    for (let i = 0; i < tw * th; i++) {
      const a = data[i * 4 + 3] / 255;
      const r = data[i * 4] * a + 255 * (1 - a);
      const gr = data[i * 4 + 1] * a + 255 * (1 - a);
      const b = data[i * 4 + 2] * a + 255 * (1 - a);
      g[i] = 0.299 * r + 0.587 * gr + 0.114 * b;
    }
    return g;
  }

  // Separable 3-tap [1,2,1]/4 blur — a light Gaussian that, together with the
  // downscale, absorbs the half-pixel offset the two rasterizers disagree on.
  function blur(src, w, h) {
    const tmp = new Float32Array(w * h), out = new Float32Array(w * h);
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        const l = src[y * w + Math.max(0, x - 1)];
        const c = src[y * w + x];
        const r = src[y * w + Math.min(w - 1, x + 1)];
        tmp[y * w + x] = (l + 2 * c + r) / 4;
      }
    }
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        const u = tmp[Math.max(0, y - 1) * w + x];
        const c = tmp[y * w + x];
        const d = tmp[Math.min(h - 1, y + 1) * w + x];
        out[y * w + x] = (u + 2 * c + d) / 4;
      }
    }
    return out;
  }

  // Mean windowed SSIM over 8×8 windows, stride 4. Falls back to a single global
  // window when the raster is smaller than one window.
  function ssim(a, b, w, h) {
    const win = 8, stride = 4;
    if (w < win || h < win) return globalSsim(a, b);
    let total = 0, count = 0;
    for (let y = 0; y + win <= h; y += stride) {
      for (let x = 0; x + win <= w; x += stride) {
        let s1 = 0, s2 = 0, s11 = 0, s22 = 0, s12 = 0;
        for (let j = 0; j < win; j++) {
          for (let i = 0; i < win; i++) {
            const idx = (y + j) * w + (x + i);
            const va = a[idx], vb = b[idx];
            s1 += va; s2 += vb; s11 += va * va; s22 += vb * vb; s12 += va * vb;
          }
        }
        const n = win * win;
        const m1 = s1 / n, m2 = s2 / n;
        const v1 = s11 / n - m1 * m1, v2 = s22 / n - m2 * m2, cov = s12 / n - m1 * m2;
        const s = ((2 * m1 * m2 + C1) * (2 * cov + C2)) /
          ((m1 * m1 + m2 * m2 + C1) * (v1 + v2 + C2));
        total += s; count++;
      }
    }
    return count ? total / count : 1;
  }

  function globalSsim(a, b) {
    const n = a.length;
    let s1 = 0, s2 = 0, s11 = 0, s22 = 0, s12 = 0;
    for (let i = 0; i < n; i++) {
      s1 += a[i]; s2 += b[i]; s11 += a[i] * a[i]; s22 += b[i] * b[i]; s12 += a[i] * b[i];
    }
    const m1 = s1 / n, m2 = s2 / n;
    const v1 = s11 / n - m1 * m1, v2 = s22 / n - m2 * m2, cov = s12 / n - m1 * m2;
    return ((2 * m1 * m2 + C1) * (2 * cov + C2)) /
      ((m1 * m1 + m2 * m2 + C1) * (v1 + v2 + C2));
  }

  function grade(pct) {
    if (pct >= 90) return "good";
    if (pct >= 75) return "warn";
    return "bad";
  }

  async function scoreRow(tr) {
    const cell = tr.querySelector(".score");
    try {
      // PNG as an <img>; the SVG as text (to read its translate and inline any raster
      // crops). Both are same-origin and cache-shared.
      const [png, resp] = await Promise.all([
        loadImage(tr.dataset.png),
        fetch(tr.dataset.svg),
      ]);
      const svgText = await resp.text();
      // Inline hybrid raster crops so their opaque layers draw; a vector-only SVG is
      // returned unchanged and takes the plain <img> path. Resolve the crop hrefs against
      // the SVG's *resolved* URL (resp.url), NOT location.href: under htmlpreview the page
      // origin is htmlpreview.github.io while relative assets resolve from the injected
      // <base> (raw.githubusercontent). location.href would point the crop fetches at the
      // wrong host, so nothing inlines and hybrids fall back to half-empty scoring. resp.url
      // is the branch asset's real location (it followed the same base/redirects the fetch did).
      const inlined = await inlineRasters(svgText, resp.url);
      const svg = inlined === svgText ? await loadImage(tr.dataset.svg) : await loadSvgString(inlined);
      // The render PNG defines the aligned coordinate space (padding-free, content at
      // (0,0)); size the shared canvas from it, capped to MAX_SIDE for offset-robustness.
      const rw = png.naturalWidth || png.width, rh = png.naturalHeight || png.height;
      const scale = Math.min(1, MAX_SIDE / Math.max(rw, rh));
      const tw = Math.max(1, Math.round(rw * scale));
      const th = Math.max(1, Math.round(rh * scale));
      // PNG: drawn 1:1 into the canvas (only the shared downscale applied).
      const ga = blur(grayFromDraw((ctx) => ctx.drawImage(png, 0, 0, rw * scale, rh * scale), tw, th), tw, th);
      // SVG: drawn at its native px size but offset by (-tx, -ty) so its content origin
      // lands at (0,0) and the export's transparent padding is cropped — the same align
      // the daemon's FigmaSvgFidelity does before scoring. No independent scaling: SVG px
      // and PNG px are the same space, so a genuine size drift shows up as a real mismatch
      // rather than being hidden by a fit-to-box rescale.
      const { tx, ty } = translateOf(svgText);
      const sw = svg.naturalWidth || svg.width, sh = svg.naturalHeight || svg.height;
      const gb = blur(
        grayFromDraw(
          (ctx) => ctx.drawImage(svg, -tx * scale, -ty * scale, sw * scale, sh * scale),
          tw,
          th,
        ),
        tw,
        th,
      );
      const pct = Math.max(0, Math.min(100, ssim(ga, gb, tw, th) * 100));
      const shown = pct.toFixed(1);
      cell.textContent = shown + "%";
      cell.classList.add("score--" + grade(pct));
      tr.dataset.scoreValue = shown;
      return pct;
    } catch (err) {
      // A tainted canvas (SecurityError) or a broken image lands here.
      cell.textContent = "n/a";
      cell.classList.add("score--na");
      cell.title = String(err && err.message || err);
      return null;
    }
  }

  // Sort key: the match %, so ascending order puts the largest diff (lowest match)
  // first. Unscored / n/a rows have no scoreValue and sink to the bottom.
  function sortKey(tr) {
    const v = parseFloat(tr.dataset.scoreValue);
    return Number.isFinite(v) ? v : Infinity;
  }

  async function run() {
    const rows = Array.from(document.querySelectorAll("tr[data-png][data-svg]"));
    const scores = [];
    // Sequential: keeps memory flat and the summary counter ticking predictably.
    for (const tr of rows) {
      const s = await scoreRow(tr);
      if (s != null) scores.push(s);
      const done = document.getElementById("done");
      if (done) done.textContent = String(scores.length);
    }
    const avgEl = document.getElementById("avg");
    if (avgEl) {
      avgEl.textContent = scores.length
        ? (scores.reduce((a, b) => a + b, 0) / scores.length).toFixed(1) + "%"
        : "—";
    }
    // Re-order worst-match-first (largest diff at the top) so the biggest divergences
    // are the first thing you see; unscored rows sink to the bottom. Stable re-append
    // keeps the whole inventory in one table.
    const body = document.getElementById("rows");
    if (body) {
      for (const tr of Array.from(body.querySelectorAll("tr.crow")).sort((a, b) => sortKey(a) - sortKey(b))) {
        body.appendChild(tr);
      }
    }
    // Opened over file:// the canvas taints (opaque origin) and fetch() is blocked, so
    // every row fails — point the reader at the http path (htmlpreview / a local server)
    // where the scorer works.
    if (rows.length && scores.length === 0) {
      const b = document.getElementById("taintwarn");
      if (b) b.style.display = "block";
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", run);
  } else {
    run();
  }
})();
`;

/**
 * One `<tr>` per component. `data-png` / `data-svg` drive the scorer; the score
 * cell is filled in client-side. Components missing a PNG or a figma-svg still get
 * a row (so the table is a complete inventory) but with an inert "—" score that
 * sorts to the bottom.
 */
function componentRow(component, figmaSvgSlugs, hybridSlugs) {
  const id = component.componentId ?? "(unnamed)";
  const group = component.group ?? "Components";
  const s = slug(id);
  const png = comparePng(component);
  const hasSvg = figmaSvgSlugs && figmaSvgSlugs.has(s);
  const svgPath = hasSvg ? `figma/${s}.svg` : null;
  const hybrid = Boolean(hybridSlugs && hybridSlugs.has(s));

  const pngCell = png
    ? `<div class="shot"><img loading="lazy" src="${esc(png.path)}" alt="${esc(id)} PNG" /></div>`
    : `<div class="shot shot--missing">no PNG</div>`;
  const svgCell = svgPath
    ? `<div class="shot"><img loading="lazy" src="${esc(svgPath)}" alt="${esc(id)} SVG" />${
        hybrid ? `<span class="badge" title="hybrid sticker: opaque layers are raster crops, inlined for scoring">hybrid</span>` : ""
      }</div>`
    : `<div class="shot shot--missing">no figma-svg</div>`;

  const scoreCell =
    png && svgPath
      ? `<td class="score" aria-label="structural similarity">…</td>`
      : `<td class="score score--na" title="needs both a PNG and a figma-svg">—</td>`;

  const rowAttrs =
    png && svgPath ? ` data-png="${esc(png.path)}" data-svg="${esc(svgPath)}"` : "";

  return `<tr class="crow"${rowAttrs}>
  <th scope="row" class="rowhead"><span class="cid">${esc(id)}</span><span class="grp">${esc(group)}</span></th>
  <td class="col-png">${pngCell}</td>
  <td class="col-svg">${svgCell}</td>
  ${scoreCell}
</tr>`;
}

/**
 * Render the catalog to a complete PNG-vs-SVG comparison page.
 * @param {object} catalog the flattened manifest (system, title, components, …)
 * @param {object} [opts] { figmaSvgSlugs?: Set<string>, hybridSlugs?: Set<string> } — the slugs a
 *   figma-svg was written for, and the subset whose SVG is a hybrid (carries raster crop layers).
 * @returns {string} a self-contained compare.html
 */
export function renderCompareHtml(catalog, opts = {}) {
  const components = catalog.components ?? [];
  const figmaSvgSlugs = opts.figmaSvgSlugs;
  const hybridSlugs = opts.hybridSlugs;

  const comparable = components.filter(
    (c) => comparePng(c) && figmaSvgSlugs && figmaSvgSlugs.has(slug(c.componentId)),
  ).length;

  // One flat, client-sortable table — initial paint is catalog order; the scorer
  // re-orders worst-match-first once every row has a score.
  const body = components.map((c) => componentRow(c, figmaSvgSlugs, hybridSlugs)).join("\n");

  const meta = catalog.meta ?? catalog;
  const title = meta.title ?? meta.system ?? "Design catalog";
  const subtitleParts = [
    meta.system && `system <code>${esc(meta.system)}</code>`,
    meta.renderer && `rendered by ${esc(meta.renderer)}`,
    `${components.length} components`,
    `${comparable} comparable`,
  ].filter(Boolean);

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>${esc(title)} — PNG vs SVG compare</title>
<style>
  :root { color-scheme: light dark; --bg:#0f0f10; --panel:#1b1b1d; --fg:#e8e8ea; --muted:#9b9ba1; --line:#2a2a2d;
    --good:#7dd87d; --warn:#e0c060; --bad:#e08080; }
  * { box-sizing: border-box; }
  body { margin:0; font:14px/1.5 system-ui, -apple-system, Segoe UI, Roboto, sans-serif; background:var(--bg); color:var(--fg); }
  header.top { padding:24px clamp(16px,4vw,40px); border-bottom:1px solid var(--line); }
  header.top h1 { margin:0 0 6px; font-size:22px; }
  header.top .subtitle { color:var(--muted); font-size:13px; }
  header.top code { background:var(--panel); padding:1px 6px; border-radius:5px; }
  header.top .note { margin-top:10px; color:var(--muted); font-size:12px; max-width:72ch; }
  header.top .taintwarn { display:none; margin-top:10px; padding:8px 12px; border-radius:8px; font-size:12px;
    max-width:80ch; color:var(--warn); border:1px solid var(--warn); background:rgba(224,192,96,0.08); }
  header.top .taintwarn code { background:var(--panel); }
  .summary { margin-top:12px; display:flex; gap:20px; flex-wrap:wrap; font-size:13px; }
  .summary b { color:var(--fg); font-size:16px; }
  .summary .k { color:var(--muted); }
  main { padding:8px clamp(16px,4vw,40px) 64px; }
  table { border-collapse:collapse; width:100%; }
  thead th { position:sticky; top:0; background:var(--bg); text-align:left; font-size:12px; color:var(--muted);
    padding:10px 12px; border-bottom:1px solid var(--line); z-index:1; }
  thead th.col-score { text-align:right; }
  tbody#rows tr.crow { border-bottom:1px solid var(--line); }
  th.rowhead { text-align:left; font-weight:600; padding:12px; vertical-align:middle; width:22%; }
  th.rowhead .cid { display:block; word-break:break-word; }
  th.rowhead .grp { display:block; margin-top:3px; font-weight:400; font-size:11px; color:var(--muted); }
  td { padding:10px 12px; vertical-align:middle; }
  /* Checkerboard so transparent stickers read clearly in both columns. */
  .shot { position:relative; display:inline-grid; place-items:center; min-width:120px; min-height:80px; padding:10px; border-radius:10px;
    background-color:#161617;
    background-image:
      linear-gradient(45deg,#202022 25%,transparent 25%),
      linear-gradient(-45deg,#202022 25%,transparent 25%),
      linear-gradient(45deg,transparent 75%,#202022 75%),
      linear-gradient(-45deg,transparent 75%,#202022 75%);
    background-size:16px 16px; background-position:0 0,0 8px,8px -8px,-8px 0; }
  .shot img { max-width:260px; max-height:200px; height:auto; display:block; }
  .shot--missing { color:var(--muted); font-style:italic; background:var(--panel); }
  .badge { position:absolute; top:4px; right:4px; font-size:10px; padding:0 5px; border-radius:999px;
    background:rgba(0,0,0,0.6); color:var(--warn); border:1px solid var(--warn); }
  td.score { text-align:right; font-variant-numeric:tabular-nums; font-size:15px; font-weight:600; white-space:nowrap; width:96px; }
  td.score--good { color:var(--good); }
  td.score--warn { color:var(--warn); }
  td.score--bad { color:var(--bad); }
  td.score--na { color:var(--muted); font-weight:400; }
  @media (max-width:640px){ .shot img { max-width:150px; } th.rowhead { width:auto; } }
</style>
</head>
<body>
<header class="top">
  <h1>${esc(title)} — PNG vs SVG</h1>
  <div class="subtitle">${subtitleParts.join(" · ")}</div>
  <div class="summary">
    <span><span class="k">avg structural match</span> <b id="avg">…</b></span>
    <span><span class="k">scored</span> <b id="done">0</b> / ${comparable}</span>
  </div>
  <p class="note">Each row pairs the rendered <strong>PNG</strong> (raster source of truth) with the editable
  <strong>figma-svg</strong> re-rasterized <em>by your browser</em>. The match column is a windowed
  <strong>SSIM</strong> (structural similarity) computed live in the page — pre-blurred and downscaled so a
  half-pixel offset between the two rasterizers barely moves the score, unlike a per-pixel diff. The SVG is
  aligned to the PNG first (its export padding + root <code>translate</code> are cropped back out, matching the
  daemon's fidelity harness), so the score reflects real vector drift, not a constant inset. Hybrid stickers'
  raster crop layers are inlined so their score reflects the full sticker. <strong>Rows sort largest-diff-first
  once scored</strong>, so the worst offenders come to the top.</p>
  <p class="taintwarn" id="taintwarn">⚠ The match scores read pixels back from a canvas, which the browser
  blocks when the images can't be read cross-origin — over <code>file://</code> (opaque origin), or from a
  host that doesn't send CORS headers for the PNGs. Open this page over <strong>http</strong> from a
  CORS-enabled host: the README's htmlpreview link (images come from <code>raw.githubusercontent.com</code>,
  which allows it) or a local server (<code>python3 -m http.server</code> in the branch).</p>
</header>
<main>
  <table>
    <thead>
      <tr>
        <th scope="col">Component</th>
        <th scope="col">PNG</th>
        <th scope="col">SVG (browser-rendered)</th>
        <th scope="col" class="col-score">Match ↑</th>
      </tr>
    </thead>
    <tbody id="rows">${body}</tbody>
  </table>
</main>
<script>${SCORER}</script>
</body>
</html>
`;
}
