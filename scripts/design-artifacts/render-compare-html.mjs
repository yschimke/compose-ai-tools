/**
 * Render a self-contained `compare.html` for a design-artifact catalog: every
 * component on one row, its editable **figma-svg** (re-rasterized *by the browser*)
 * in one column, its rendered **PNG** (the raster source of truth) in a second,
 * and a **structural-similarity score** in a third — so a designer can eyeball
 * every component's SVG↔PNG fidelity at once and spot which vectors drift. Rows
 * sort **worst-match-first** once scored, so the biggest divergences surface first.
 *
 * The design vector leads and the render follows, left to right, because that is
 * how every other surface that pairs the two draws them: the viewer's spec lane
 * (Spec / Diff / Render), its wipe seam, the focused Reference / Diff / Actual
 * page, the `figma-svg | diff | render` fidelity composite, and the Figma column
 * of the PR preview-diff comment.
 *
 * Override toolbar: the header carries a control bar (font scale, embedded-fonts
 * toggle, theme, backdrop) that re-applies each override to the *in-browser*
 * figma-svg and re-scores live. The figma-svg is a **fixed-geometry capture** — its
 * layer boxes were measured once at capture time and intentionally do **not**
 * reflow — so scaling the vector's `<text>` up while the boxes stay put is exactly
 * how you *see* that a static sticker can't represent a `fontScale` render: the
 * glyphs overflow their containers and the score drops. Dropping the embedded
 * `@font-face` shows the no-embed / custom-font-substitution case the same way.
 * Because the PNG column is the render captured at the catalog's own params (font
 * scale 1.0), any non-identity override makes the score a *probe* of the SVG's
 * sensitivity to that knob, not a like-for-like fidelity number — the header labels
 * that state so the reading isn't mistaken for baseline drift.
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
import { figmaVariantSvgPath } from "./figma-svg-emit.mjs";

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

/** The default, resting render pool for a component (ideal-only if any, else all). */
function defaultPool(component) {
  const ideal = idealImages(component);
  const pool = ideal.length ? ideal : component.images ?? [];
  const defaults = pool.filter(isDefault);
  return defaults.length ? defaults : pool;
}

/**
 * The PNG to pit against the figma-svg: the **default, light-themed** render, so
 * it matches the light-preferred vector `figmaSvgByFunction` carries. Falls back
 * through default → light → largest so a component always yields a PNG when it has
 * one. Mirrors the index's hero pick, but pins the light theme for a fair compare.
 */
function comparePng(component) {
  const byDefault = defaultPool(component);
  const light = byDefault.filter((i) => i.theme === "light");
  const chooseFrom = light.length ? light : byDefault;
  return [...chooseFrom].sort((a, b) => (b.width ?? 0) - (a.width ?? 0))[0];
}

/**
 * The **dark**, default render for the theme override, or `null` when the catalog
 * carries no dark capture for this component. The compare SVG is light-preferred,
 * so switching the PNG column to dark is itself a probe: the vector column stays
 * light, showing at a glance that the figma-svg has no dark variant.
 */
function compareDarkPng(component) {
  const dark = defaultPool(component).filter((i) => i.theme === "dark");
  if (!dark.length) return null;
  return [...dark].sort((a, b) => (b.width ?? 0) - (a.width ?? 0))[0];
}

/**
 * The client-side scorer, inlined as a string so the page is self-contained. It
 * walks every `<tr data-png data-svg>`, applies the active overrides to the SVG,
 * rasterizes both images to a shared canvas over white, writes a windowed-SSIM
 * percentage into the row's score cell, then re-orders the rows worst-match-first.
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

  // Active override state, read from the header control bar. Identity defaults
  // (fontScale 1, fonts embedded, light theme) reproduce the pre-toolbar behaviour
  // exactly, so the baseline score is unchanged.
  const OV = { fontScale: 1, fonts: true, theme: "light", bg: "checker" };

  function readControls() {
    const fs = document.getElementById("ov-fontScale");
    const fonts = document.getElementById("ov-fonts");
    const theme = document.getElementById("ov-theme");
    const bg = document.getElementById("ov-bg");
    OV.fontScale = fs ? (parseFloat(fs.value) || 1) : 1;
    OV.fonts = fonts ? fonts.checked : true;
    OV.theme = theme ? theme.value : "light";
    OV.bg = bg ? bg.value : "checker";
  }

  // Whether a vector mutation departs from identity. Theme selects a separately authored
  // PNG/SVG pair, so it remains a baseline comparison rather than an override probe.
  // When true the score is a *probe* of
  // the SVG's sensitivity to the knob (the PNG is the capture-time render), not a
  // baseline fidelity number — the header says so.
  function overridesActive() {
    return Math.abs(OV.fontScale - 1) > 1e-6 || !OV.fonts;
  }

  // Apply the active overrides to the figma-svg source text.
  //  - fontScale: multiply every <text>/<tspan> font-size and letter-spacing. The
  //    layer geometry (x/y/width/height) is a fixed capture and deliberately is NOT
  //    reflowed — glyphs overflowing their boxes at >1x is precisely the signal that
  //    a static sticker can't stand in for a re-rendered fontScale.
  //  - fonts off: drop the @font-face <style> so the browser substitutes its own
  //    face — the no-embed / custom-font case (Figma resolves by family name, not the
  //    embedded bytes, so this mirrors an importer without that font installed).
  // Identity overrides return the text unchanged so the cheap <img src=svg> path and
  // the exact baseline score are preserved.
  function applyOverrides(svgText) {
    let s = svgText;
    if (!OV.fonts) {
      s = s.replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, (m) =>
        /@font-face/i.test(m) ? "" : m);
    }
    const fs = OV.fontScale;
    if (fs && Math.abs(fs - 1) > 1e-6) {
      s = s.replace(/\b(font-size|letter-spacing)="(-?\d*\.?\d+)"/gi, (_, k, n) =>
        k + '="' + (parseFloat(n) * fs).toFixed(3) + '"');
    }
    return s;
  }

  // The PNG the current theme override selects — the dark capture if the row carries
  // a matching dark vector too, else the light default. Switching only one side would
  // manufacture the exact cross-variant diff this page exists to prevent.
  function currentPng(tr) {
    return OV.theme === "dark" && tr.dataset.pngDark && tr.dataset.svgDark
      ? tr.dataset.pngDark
      : tr.dataset.png;
  }

  // Keep the vector on the same authored variant as the PNG. A row without a dark vector falls
  // back to its light/base vector, matching currentPng's fallback for a missing dark capture.
  function currentSvg(tr) {
    return OV.theme === "dark" && tr.dataset.svgDark ? tr.dataset.svgDark : tr.dataset.svg;
  }

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
  // carries no external refs and the canvas it's drawn into stays untainted. The
  // caller may reuse the same blob to show the overridden vector in the display img.
  function svgBlobUrl(svgText) {
    return URL.createObjectURL(new Blob([svgText], { type: "image/svg+xml" }));
  }
  async function loadSvgString(svgText) {
    const url = svgBlobUrl(svgText);
    try {
      return await loadImage(url);
    } finally {
      // Revoke on a macrotask so the synchronous drawImage that follows the await
      // still has the decoded bitmap.
      setTimeout(() => URL.revokeObjectURL(url), 0);
    }
  }

  // Point a display <img> at the overridden SVG so the column shows what was scored
  // (glyphs overflowing at >1x, the substituted face with fonts off). Keep the blob
  // alive until this display image actually settles: loading="lazy" can defer its
  // request indefinitely while the row is below the fold. Marks the row so a later
  // return to identity knows to restore the original source.
  function showSvg(tr, svgText) {
    const img = tr.querySelector(".col-svg .shot img");
    if (!img) return;
    const url = svgBlobUrl(svgText);
    const release = () => {
      img.removeEventListener("load", release);
      img.removeEventListener("error", release);
      URL.revokeObjectURL(url);
    };
    img.addEventListener("load", release);
    img.addEventListener("error", release);
    img.src = url;
    tr.dataset.svgShown = "override";
  }

  // Put the display <img> back to the authored figma-svg once the overrides return to
  // identity or the selected theme changes — otherwise the column keeps showing the last
  // override's blob or the previous theme while the score uses a different authored vector.
  function restoreSvg(tr) {
    const img = tr.querySelector(".col-svg .shot img");
    const svgPath = currentSvg(tr);
    if (img && img.getAttribute("src") !== svgPath) img.src = svgPath;
    tr.dataset.svgShown = "base";
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

  // The grounds a comparison is scored on, worst result winning. One opaque ground is not a
  // neutral choice: it ANNIHILATES ink that matches it, and two planes flattened to the same
  // uniform field are identical — so SSIM answers 1.0 and the row reports a perfect match for a
  // pair that shares nothing but the colour of its ink. A dark-first system draws light ink onto a
  // transparent sticker, which is exactly that case. White and black together make it structural:
  // a pixel can only vanish on both when its alpha is zero on both sides, which is the one case
  // where "no evidence" is the honest answer. Same rule as the serve scorer's COMPARISON_GROUNDS.
  const GROUNDS = ["#ffffff", "#000000"];

  // Draw into a tw×th canvas over the given ground via the caller's draw(ctx), returning the
  // grayscale plane as a Float32Array. The canvas is filled edge to edge before drawing, so every
  // pixel read back is opaque and the luma is the composited colour outright.
  function grayFromDraw(draw, tw, th, ground) {
    const c = document.createElement("canvas");
    c.width = tw; c.height = th;
    const ctx = c.getContext("2d", { willReadFrequently: true });
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = "high";
    ctx.fillStyle = ground;
    ctx.fillRect(0, 0, tw, th);
    draw(ctx);
    const { data } = ctx.getImageData(0, 0, tw, th);
    const g = new Float32Array(tw * th);
    for (let i = 0; i < tw * th; i++) {
      g[i] = 0.299 * data[i * 4] + 0.587 * data[i * 4 + 1] + 0.114 * data[i * 4 + 2];
    }
    return g;
  }

  // Whether two planes are the same picture. The tolerance is for a nearly-opaque pixel: alpha 254
  // lets a sliver of ground through and moves a luminance by well under one unit.
  function samePlane(a, b) {
    if (a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) if (Math.abs(a[i] - b[i]) > 1) return false;
    return true;
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

  // Display both columns cropped to the component's content bbox — the region [(-tx,-ty), vw×vh]
  // of the render, where (tx,ty) is the figma-svg's root translate and (vw,vh) its viewBox. The
  // PNG tile is clipped to that window (a wear sticker rendered on a 454² device canvas shows just
  // the component, not the empty frame); the SVG column already *is* the component, so it's sized
  // to match. A no-op for close-cropped renders where the bbox already fills the frame.
  function frameToComponent(tr, rw, rh, tx, ty, vw, vh) {
    if (!(vw > 0 && vh > 0 && rw > 0 && rh > 0)) return;
    const cap = 240; // px — matches the old max-width/height envelope
    const scale = Math.min(1, cap / Math.max(vw, vh));
    const dw = Math.max(1, Math.round(vw * scale)), dh = Math.max(1, Math.round(vh * scale));
    const pngShot = tr.querySelector(".col-png .shot");
    if (pngShot && !pngShot.classList.contains("shot--missing")) {
      const img = pngShot.querySelector("img");
      pngShot.classList.add("shot--framed");
      pngShot.style.width = dw + "px";
      pngShot.style.height = dh + "px";
      if (img) {
        img.style.width = Math.round(rw * scale) + "px";
        img.style.height = Math.round(rh * scale) + "px";
        // (tx,ty) is negative for a centred component, so tx*scale shifts the render left/up to
        // bring the component's top-left to the clip origin.
        img.style.left = Math.round(tx * scale) + "px";
        img.style.top = Math.round(ty * scale) + "px";
      }
    }
    const svgShot = tr.querySelector(".col-svg .shot");
    if (svgShot && !svgShot.classList.contains("shot--missing")) {
      const img = svgShot.querySelector("img");
      svgShot.classList.add("shot--framed");
      svgShot.style.width = dw + "px";
      svgShot.style.height = dh + "px";
      if (img) {
        img.style.position = "static";
        img.style.width = dw + "px";
        img.style.height = dh + "px";
      }
    }
  }

  // ---------------------------------------------------------------- eyedropper
  // Per-row alignment, recorded by the scorer once a row has been framed. Only the export's
  // root translate: every other quantity is read back from the images the row is *showing*,
  // so nothing here retains a bitmap. A record per row is unavoidable (each component has its
  // own translate) and this one is two numbers.
  const SAMPLES = new WeakMap();

  // The image each column is displaying. Sampling these rather than the scorer's own decoded
  // copies keeps what is read identical to what is seen — including an overridden or hybrid
  // vector, which the scorer swapped into this element — and retains nothing the page was not
  // already holding.
  function displayImg(tr, side) {
    return tr.querySelector((side === "png" ? ".col-png" : ".col-svg") + " .shot img");
  }

  // One row's sampling canvases at a time. A scored row stays in the DOM forever, so keeping a
  // canvas per row would grow with every row the cursor visited — two full-size backing stores
  // each — until the page ran out. Zeroing the dimensions drops the old store immediately
  // rather than waiting for the context to be collected.
  const ACTIVE = { tr: null, png: null, svg: null, loading: null };

  // The last point hovered, so a sample that had to wait for an origin-clean load can be
  // completed where the cursor actually is rather than dropped.
  const LAST = { shot: null, x: 0, y: 0 };

  function releaseActive() {
    for (const side of ["png", "svg"]) {
      if (ACTIVE[side]) { ACTIVE[side].canvas.width = 0; ACTIVE[side].canvas.height = 0; }
      ACTIVE[side] = null;
    }
    ACTIVE.tr = null;
    ACTIVE.loading = null;
  }

  // Whether drawing this element would taint the canvas and make every read throw. The display
  // images carry no "crossorigin" attribute, and on the published htmlpreview report they resolve
  // cross-origin to raw.githubusercontent.com — including the relative "images/..." paths, which
  // resolve against the injected base. Adding the attribute to the markup would fix the read but
  // break the picture outright on any host that does not send the header, which trades a disabled
  // picker for a blank column; so the check is here and the fallback below re-fetches instead.
  function taints(img) {
    const src = img.currentSrc || img.src || "";
    if (/^(data|blob):/i.test(src)) return false;
    try {
      return new URL(src, document.baseURI).origin !== location.origin;
    } catch (err) {
      return false;
    }
  }

  function contextFor(img) {
    const c = document.createElement("canvas");
    c.width = img.naturalWidth; c.height = img.naturalHeight;
    const ctx = c.getContext("2d", { willReadFrequently: true });
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(img, 0, 0);
    return ctx;
  }

  // A 1:1 context over the row's image: native resolution, no ground fill, no smoothing, so a
  // read is the source pixel outright. The scorer's 192px downscale box-averages neighbours
  // together for offset-robust SSIM and would answer with a blend present in neither image —
  // exactly the wrong answer for "what colour is this".
  //
  // The displayed element is used whenever reading it cannot taint the canvas, which keeps the
  // sample identical to the picture — including an overridden or hybrid vector, whose blob the
  // scorer swapped into that element and which no reload could reproduce. Otherwise an
  // origin-clean copy is requested through the same CORS path the scorer loads by, once per row
  // per side; null until it arrives, and the hover is replayed when it does.
  function sampler(tr, side) {
    if (ACTIVE.tr !== tr) { releaseActive(); ACTIVE.tr = tr; }
    if (ACTIVE[side]) return ACTIVE[side];
    const img = displayImg(tr, side);
    if (!img || !img.complete || !img.naturalWidth) return null;
    if (!taints(img)) {
      ACTIVE[side] = contextFor(img);
      return ACTIVE[side];
    }
    const src = img.currentSrc || img.src;
    const key = side + " " + src;
    if (ACTIVE.loading !== key) {
      ACTIVE.loading = key;
      loadImage(src).then((clean) => {
        if (ACTIVE.tr !== tr || ACTIVE.loading !== key) return;
        ACTIVE.loading = null;
        ACTIVE[side] = contextFor(clean);
        replayPick(tr);
      }).catch(() => {
        // The host sends no CORS header, so these pixels are unreadable however they are
        // fetched — the same wall that leaves this row's score at "n/a".
        if (ACTIVE.loading === key) ACTIVE.loading = null;
      });
    }
    return null;
  }

  // The source pixel at (x, y), or null when the point falls outside that image. Null is a real
  // answer here: the two images have different extents, so a point inside the vector's bbox can
  // still be off the edge of the render.
  function samplePixel(tr, side, x, y) {
    const ctx = sampler(tr, side);
    if (!ctx) return null;
    const px = Math.floor(x), py = Math.floor(y);
    if (!(px >= 0 && py >= 0 && px < ctx.canvas.width && py < ctx.canvas.height)) return null;
    const d = ctx.getImageData(px, py, 1, 1).data;
    return { r: d[0], g: d[1], b: d[2], a: d[3] };
  }

  const HEX = (n) => n.toString(16).padStart(2, "0");
  function hex(c) { return "#" + HEX(c.r) + HEX(c.g) + HEX(c.b); }

  // The tile's own backdrop, so a partly transparent sample can be reported as what the eye
  // actually sees. Checker reports over its base square: the alternative is reporting a colour
  // that changes with which 8px square the cursor happens to be over.
  function backdrop() {
    if (OV.bg === "white") return { r: 255, g: 255, b: 255 };
    if (OV.bg === "dark") return { r: 11, g: 11, b: 12 };
    return { r: 22, g: 22, b: 23 };
  }

  // Straight (unmultiplied) colour composited over the tile ground. Comparing two straight
  // colours at different alphas answers a question nobody asked — a 10% state layer is a
  // white pixel at alpha 26 in one image and an opaque lightened container in the other, and
  // only the composite says whether they agree.
  function over(c, bg) {
    const a = c.a / 255;
    return {
      r: Math.round(c.r * a + bg.r * (1 - a)),
      g: Math.round(c.g * a + bg.g * (1 - a)),
      b: Math.round(c.b * a + bg.b * (1 - a)),
      a: 255,
    };
  }

  const PICK = { locked: false, blocked: false };

  function pickEl(id) { return document.getElementById(id); }

  // Paint one side of the readout: swatch, hex, and the straight rgba when the sample
  // carried alpha (so a translucent overlay is legible as an overlay, not as its composite).
  function showSwatch(side, raw, comp) {
    const sw = pickEl("pick-" + side + "-sw");
    const val = pickEl("pick-" + side + "-val");
    const sub = pickEl("pick-" + side + "-sub");
    if (!sw || !val || !sub) return;
    if (!raw) {
      sw.style.background = "transparent";
      sw.classList.add("pick-sw--none");
      val.textContent = "—";
      sub.textContent = "outside this image";
      return;
    }
    sw.classList.remove("pick-sw--none");
    sw.style.background = "rgb(" + comp.r + "," + comp.g + "," + comp.b + ")";
    val.textContent = hex(comp);
    sub.textContent = raw.a === 255
      ? "opaque"
      : "rgba(" + raw.r + "," + raw.g + "," + raw.b + "," + (raw.a / 255).toFixed(2) + ") over backdrop";
  }

  // The crosshair, placed at the same tile offset in BOTH columns of the row — the point of
  // the whole feature is that one hover names two pixels, so the marker has to appear twice.
  function marks(tr, fx, fy) {
    for (const shot of tr.querySelectorAll(".shot--framed")) {
      let m = shot.querySelector(".xh");
      if (!m) { m = document.createElement("i"); m.className = "xh"; shot.appendChild(m); }
      m.style.left = fx + "px";
      m.style.top = fy + "px";
      m.hidden = false;
    }
  }

  function clearMarks() {
    for (const m of document.querySelectorAll(".xh")) m.hidden = true;
  }

  function hidePick() {
    if (PICK.locked) return;
    // Abandon the pending hover too. An origin-clean copy still in flight resolves into a replay,
    // and without this the panel and crosshairs would reopen at a point the cursor has left.
    LAST.shot = null;
    const panel = pickEl("pick");
    if (panel) panel.hidden = true;
    clearMarks();
  }

  // Replay the last hover, if it is still the live one and still in this row. Used when an
  // origin-clean copy lands, and when a rescore replaces what the row is showing.
  function replayPick(tr) {
    if (!LAST.shot || !LAST.shot.isConnected) return;
    if (LAST.shot.closest("tr.crow") !== tr) return;
    pickAt(LAST.shot, LAST.x, LAST.y);
  }

  // Map a point in a displayed tile to a pixel in each source and read both.
  //
  // The tile is the component's content bbox — the vector's viewBox — at whatever scale it
  // was framed to, so the vector's own pixel is just the tile offset over that scale. The
  // render is the same space shifted by the export's root translate, which is the alignment
  // the scorer already does before measuring; reusing it is what makes the two reads the
  // same point rather than two points that merely look alike.
  function pickAt(shot, clientX, clientY) {
    const tr = shot.closest("tr.crow");
    const rec = tr && SAMPLES.get(tr);
    const panel = pickEl("pick");
    // An unscored row (or one whose pass failed) has no record and no framing, so there is
    // nothing to sample — drop the previous row's reading rather than leaving it up beside a
    // cursor that has moved on.
    if (!rec || !panel) { hidePick(); return; }
    const rect = shot.getBoundingClientRect();
    LAST.shot = shot; LAST.x = clientX; LAST.y = clientY;
    // The displayed scale comes from the vector as it is actually rendered, not from the tile it
    // sits in: the two agree once the tile is framed, and reading the image means a stray cap on
    // its width shows up as a wrong scale rather than as silently wrong pixels.
    //
    // Both axes, separately. frameToComponent rounds the displayed width and height
    // independently, so a capped non-square component — 300x101 framed to 240x81 — is scaled by
    // 0.800 across and 0.802 down. One scale for both drifts a row at the bottom edge and can
    // report a visible last row as outside the image.
    const svgImg = displayImg(tr, "svg");
    const svgRect = svgImg && svgImg.getBoundingClientRect();
    const nw = svgImg && svgImg.naturalWidth, nh = svgImg && svgImg.naturalHeight;
    if (!(rect.width > 0 && svgRect && svgRect.width > 0 && svgRect.height > 0 && nw > 0 && nh > 0)) {
      hidePick();
      return;
    }
    const sx = svgRect.width / nw, sy = svgRect.height / nh;
    const fx = clientX - rect.left, fy = clientY - rect.top;
    const svgX = fx / sx, svgY = fy / sy;
    const renderX = svgX - rec.tx, renderY = svgY - rec.ty;

    let svgRaw, pngRaw;
    try {
      svgRaw = samplePixel(tr, "svg", svgX, svgY);
      pngRaw = samplePixel(tr, "png", renderX, renderY);
    } catch (err) {
      // Same tainted-canvas wall the scorer hits; say so once and stop offering the picker.
      PICK.blocked = true;
      panel.hidden = true;
      const b = pickEl("taintwarn");
      if (b) b.style.display = "block";
      return;
    }

    const g = backdrop();
    const svgOver = svgRaw && over(svgRaw, g);
    const pngOver = pngRaw && over(pngRaw, g);
    showSwatch("svg", svgRaw, svgOver);
    showSwatch("png", pngRaw, pngOver);

    const id = tr.querySelector(".cid");
    const where = pickEl("pick-where");
    if (where) {
      where.textContent = (id ? id.textContent + " · " : "") +
        "render px " + Math.floor(renderX) + "," + Math.floor(renderY);
    }
    const verdict = pickEl("pick-verdict");
    if (verdict) {
      if (!svgOver || !pngOver) {
        verdict.textContent = "";
        verdict.className = "pick-verdict";
      } else {
        const d = Math.max(
          Math.abs(svgOver.r - pngOver.r),
          Math.abs(svgOver.g - pngOver.g),
          Math.abs(svgOver.b - pngOver.b),
        );
        verdict.textContent = d === 0 ? "identical" : "Δ " + d + "/255 max channel";
        verdict.className = "pick-verdict " + (d === 0 ? "ok" : d <= 4 ? "near" : "off");
      }
    }
    // Dock the readout to the side the cursor is not on. Fixed to one corner it sits over the
    // match column exactly when a bottom row is being read, which is the moment the number
    // matters most.
    panel.classList.toggle("pick--left", clientX > window.innerWidth / 2);
    panel.hidden = false;
    marks(tr, fx, fy);
  }

  function wirePicker() {
    const rows = document.getElementById("rows");
    if (!rows) return;
    rows.addEventListener("pointermove", (e) => {
      if (PICK.locked || PICK.blocked) return;
      const shot = e.target && e.target.closest && e.target.closest(".shot--framed");
      if (!shot) { hidePick(); return; }
      pickAt(shot, e.clientX, e.clientY);
    });
    rows.addEventListener("pointerleave", hidePick);
    // Click freezes the reading so it can be read and copied without the cursor moving off;
    // clicking again (or Escape) releases it.
    rows.addEventListener("click", (e) => {
      const shot = e.target && e.target.closest && e.target.closest(".shot--framed");
      if (!shot || PICK.blocked) return;
      if (PICK.locked) {
        PICK.locked = false;
        pickAt(shot, e.clientX, e.clientY);
      } else {
        pickAt(shot, e.clientX, e.clientY);
        PICK.locked = true;
      }
      const panel = pickEl("pick");
      if (panel) panel.classList.toggle("pick--locked", PICK.locked);
    });
    document.addEventListener("keydown", (e) => {
      if (e.key !== "Escape" || !PICK.locked) return;
      PICK.locked = false;
      const panel = pickEl("pick");
      if (panel) panel.classList.remove("pick--locked");
      hidePick();
    });
  }

  async function scoreRow(tr, seq) {
    const cell = tr.querySelector(".score");
    try {
      // PNG as an <img> (theme override picks light/dark); the SVG as text (to read its
      // translate, apply the active overrides, and inline any raster crops). Both are
      // same-origin and cache-shared.
      const pngPath = currentPng(tr);
      const svgPath = currentSvg(tr);
      const [png, resp] = await Promise.all([
        loadImage(pngPath),
        fetch(svgPath),
      ]);
      const rawSvg = await resp.text();
      // Apply the header overrides (font scale, drop embedded @font-face) to the vector
      // before anything else, so both the score and the shown column reflect the knob.
      const svgText = applyOverrides(rawSvg);
      // Inline hybrid raster crops so their opaque layers draw; a vector-only SVG is
      // returned unchanged and takes the plain <img> path. Resolve the crop hrefs against
      // the SVG's *resolved* URL (resp.url), NOT location.href: under htmlpreview the page
      // origin is htmlpreview.github.io while relative assets resolve from the injected
      // <base> (raw.githubusercontent). location.href would point the crop fetches at the
      // wrong host, so nothing inlines and hybrids fall back to half-empty scoring. resp.url
      // is the branch asset's real location (it followed the same base/redirects the fetch did).
      const inlined = await inlineRasters(svgText, resp.url);
      // Overridden or hybrid → rasterize the (blob) string; untouched vector-only → the
      // cheap <img src=svg> path.
      const changed = inlined !== rawSvg;
      const svg = changed ? await loadSvgString(inlined) : await loadImage(svgPath);
      // The render PNG defines the aligned coordinate space (padding-free, content at
      // (0,0)); size the shared canvas from it, capped to MAX_SIDE for offset-robustness.
      const rw = png.naturalWidth || png.width, rh = png.naturalHeight || png.height;
      const scale = Math.min(1, MAX_SIDE / Math.max(rw, rh));
      const tw = Math.max(1, Math.round(rw * scale));
      const th = Math.max(1, Math.round(rh * scale));
      // PNG: drawn 1:1 into the canvas (only the shared downscale applied).
      const drawPng = (ctx) => ctx.drawImage(png, 0, 0, rw * scale, rh * scale);
      // SVG: drawn at its native px size but offset by (-tx, -ty) so its content origin
      // lands at (0,0) and the export's transparent padding is cropped — the same align
      // the daemon's FigmaSvgFidelity does before scoring. No independent scaling: SVG px
      // and PNG px are the same space, so a genuine size drift shows up as a real mismatch
      // rather than being hidden by a fit-to-box rescale.
      const { tx, ty } = translateOf(rawSvg);
      const sw = svg.naturalWidth || svg.width, sh = svg.naturalHeight || svg.height;
      const drawSvg = (ctx) => ctx.drawImage(svg, -tx * scale, -ty * scale, sw * scale, sh * scale);
      // Every ground rasterised BEFORE any is scored, then the worst kept. The gate below is why
      // both are rasterised rather than scored one at a time: an opaque frame composites
      // identically onto every ground, so equal planes are how opacity is detected, for free.
      const planes = GROUNDS.map((ground) => ({
        a: blur(grayFromDraw(drawPng, tw, th, ground), tw, th),
        b: blur(grayFromDraw(drawSvg, tw, th, ground), tw, th),
      }));
      // A second ground only means something when there is alpha for it to show through. On a
      // MIXED pair — an opaque Figma export against a render with a transparent surround — nothing
      // about the export moves between grounds while all of the render's surround does, so the
      // black pass would report a difference that lives in the grounds rather than in the artwork,
      // and the minimum would take it.
      const varies = (side) => planes.some((p) => !samePlane(p[side], planes[0][side]));
      const scored = varies("a") && varies("b") ? planes : [planes[0]];
      let worst = 100;
      for (const p of scored) {
        worst = Math.min(worst, Math.max(0, Math.min(100, ssim(p.a, p.b, tw, th) * 100)));
      }
      const pct = worst;
      // A newer control change may have started while this row was awaiting its fetch /
      // decode. Everything above only read; from here down mutates the row, so bail now
      // if superseded — otherwise a stale pass could overwrite the current one's image
      // and score after the newer pass already finished this row.
      if (seq !== runSeq) return null;
      // Reflect the *scored* inputs in the display so what's shown matches what was
      // measured: the theme-selected PNG (only touch src when it actually changes, to
      // avoid a reload each rescore) and the overridden vector — or the authored SVG
      // back once the overrides return to identity.
      const pngImg = tr.querySelector(".col-png .shot img");
      const shownPng = tr.dataset.pngShown || tr.dataset.png;
      if (pngImg && shownPng !== pngPath) {
        pngImg.src = pngPath;
        tr.dataset.pngShown = pngPath;
      }
      if (changed) showSvg(tr, inlined);
      else restoreSvg(tr);
      // Crop both display columns to the component now that we've read its bbox from the SVG.
      frameToComponent(tr, rw, rh, tx, ty, sw, sh);
      // Hand the eyedropper the alignment this pass used. Only the translate: it reads the
      // pixels off the images the row is showing, which this pass has just put in sync with
      // what it scored, so there is nothing else to carry and no bitmap to retain.
      SAMPLES.set(tr, { tx, ty });
      // The row's displayed images have just been replaced or re-sized; drop any canvases
      // cached from the previous pass so the next sample redraws from what is on screen.
      if (ACTIVE.tr === tr) releaseActive();
      // Then re-read the point being shown. A frozen reading is the case that needs this: the
      // lock stops pointer moves from refreshing it, so a theme or backdrop change would leave
      // the panel asserting the previous pass's colours — over the previous pass's ground —
      // against a picture that has moved on.
      replayPick(tr);
      const shown = pct.toFixed(1);
      cell.textContent = shown + "%";
      cell.className = "score score--" + grade(pct);
      tr.dataset.scoreValue = shown;
      return pct;
    } catch (err) {
      // A tainted canvas (SecurityError) or a broken image lands here. A superseded pass
      // must not stamp "n/a" over the current pass's result either.
      if (seq !== runSeq) return null;
      cell.textContent = "n/a";
      cell.className = "score score--na";
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

  // Reflect the backdrop choice + the active-probe banner. The backdrop rides on the
  // body so the checkerboard CSS can be overridden per shot.
  function applyChrome() {
    document.body.dataset.bg = OV.bg;
    const banner = document.getElementById("ov-active");
    if (banner) {
      if (!overridesActive()) {
        banner.textContent = "";
        banner.hidden = true;
      } else {
        const bits = [];
        if (Math.abs(OV.fontScale - 1) > 1e-6) bits.push("font scale " + OV.fontScale.toFixed(1) + "×");
        if (!OV.fonts) bits.push("no embedded fonts");
        if (OV.theme !== "light") bits.push(OV.theme + " theme");
        banner.hidden = false;
        banner.textContent = "Probing SVG: " + bits.join(" · ") +
          " — the PNG is the capture-time render, so the match is this knob's SVG-vs-render divergence, not baseline drift.";
      }
    }
  }

  // A monotonic run token: a control change starts a fresh pass and supersedes any
  // still-running one, so rapid slider drags don't interleave stale scores.
  let runSeq = 0;

  async function run() {
    readControls();
    applyChrome();
    const mySeq = ++runSeq;
    const rows = Array.from(document.querySelectorAll("tr[data-png][data-svg]"));
    const scores = [];
    // Sequential: keeps memory flat and the summary counter ticking predictably.
    const done = document.getElementById("done");
    if (done) done.textContent = "0";
    for (const tr of rows) {
      if (mySeq !== runSeq) return; // a newer pass took over
      const s = await scoreRow(tr, mySeq);
      if (s != null) scores.push(s);
      if (done) done.textContent = String(scores.length);
    }
    if (mySeq !== runSeq) return;
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

  // Debounce the re-score so dragging the font-scale slider doesn't launch a pass per
  // pixel; the run token then makes the last drag win.
  let pending = 0;
  function scheduleRun() {
    clearTimeout(pending);
    pending = setTimeout(run, 120);
  }

  function updateFontScaleLabel() {
    const fs = document.getElementById("ov-fontScale");
    const out = document.getElementById("ov-fontScale-val");
    if (fs && out) out.textContent = (parseFloat(fs.value) || 1).toFixed(1) + "×";
  }

  function wireControls() {
    const fs = document.getElementById("ov-fontScale");
    if (fs) fs.addEventListener("input", () => { updateFontScaleLabel(); scheduleRun(); });
    for (const id of ["ov-fonts", "ov-theme", "ov-bg"]) {
      const el = document.getElementById(id);
      if (el) el.addEventListener("change", scheduleRun);
    }
    const reset = document.getElementById("ov-reset");
    if (reset) reset.addEventListener("click", () => {
      if (fs) fs.value = "1";
      const fonts = document.getElementById("ov-fonts");
      const theme = document.getElementById("ov-theme");
      const bg = document.getElementById("ov-bg");
      if (fonts) fonts.checked = true;
      if (theme) theme.value = "light";
      if (bg) bg.value = "checker";
      updateFontScaleLabel();
      run();
    });
    updateFontScaleLabel();
  }

  function start() {
    wireControls();
    wirePicker();
    run();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
`;

/**
 * One `<tr>` per component. `data-png` / `data-svg` drive the scorer; `data-png-dark`
 * (when present) feeds the theme override. The score cell is filled in client-side.
 * Components missing a PNG or a figma-svg still get a row (so the table is a complete
 * inventory) but with an inert "—" score that sorts to the bottom.
 */
function componentSvgPaths(component, figmaSvgSlugs, figmaVariantSvgPaths) {
  const s = slug(component.componentId ?? "(unnamed)");
  const fallback =
    (component.images ?? []).find((image) => image.figmaSvg)?.figmaSvg ??
    (figmaSvgSlugs?.has(s) ? `figma/${s}.svg` : null);
  const png = comparePng(component);
  const pngDark = compareDarkPng(component);
  const variantPath = (image) => {
    if (image?.figmaSvg) return image.figmaSvg;
    const path = image && figmaVariantSvgPath(image.path);
    return path && figmaVariantSvgPaths?.has(path) ? path : null;
  };
  return {
    light: variantPath(png) ?? fallback,
    // Callers predating per-variant vectors supplied no path set and retain the legacy flat
    // fallback. A supplied (even empty) set is authoritative: never call one flat SVG "dark".
    dark: variantPath(pngDark) ?? (figmaVariantSvgPaths === undefined ? fallback : null),
  };
}

function componentRow(component, figmaSvgSlugs, figmaVariantSvgPaths, hybridSlugs) {
  const id = component.componentId ?? "(unnamed)";
  const group = component.group ?? "Components";
  const s = slug(id);
  const png = comparePng(component);
  const pngDark = compareDarkPng(component);
  const { light: svgPath, dark: svgDarkPath } = componentSvgPaths(
    component,
    figmaSvgSlugs,
    figmaVariantSvgPaths,
  );
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
    png && svgPath
      ? ` data-png="${esc(png.path)}"${pngDark ? ` data-png-dark="${esc(pngDark.path)}"` : ""} data-svg="${esc(svgPath)}"${svgDarkPath ? ` data-svg-dark="${esc(svgDarkPath)}"` : ""}`
      : "";

  return `<tr class="crow"${rowAttrs}>
  <th scope="row" class="rowhead"><span class="cid">${esc(id)}</span><span class="grp">${esc(group)}</span></th>
  <td class="col-svg">${svgCell}</td>
  <td class="col-png">${pngCell}</td>
  ${scoreCell}
</tr>`;
}

/**
 * Render the catalog to a complete SVG-vs-PNG comparison page.
 * @param {object} catalog the flattened manifest (system, title, components, …)
 * @param {object} [opts] { figmaSvgSlugs?: Set<string>, figmaVariantSvgPaths?: Set<string>,
 *   hybridSlugs?: Set<string> } — the back-compat slugs and exact per-variant paths written, plus
 *   the subset whose SVG is a hybrid (carries raster crop layers).
 * @returns {string} a self-contained compare.html
 */
export function renderCompareHtml(catalog, opts = {}) {
  const components = catalog.components ?? [];
  const figmaSvgSlugs = opts.figmaSvgSlugs;
  const figmaVariantSvgPaths = opts.figmaVariantSvgPaths;
  const hybridSlugs = opts.hybridSlugs;

  const comparable = components.filter(
    (c) => comparePng(c) && componentSvgPaths(c, figmaSvgSlugs, figmaVariantSvgPaths).light,
  ).length;

  // Whether any comparable component carries a dark default capture — the theme
  // override is only offered when there's a dark PNG to switch to.
  const hasDark = components.some(
    (c) => {
      const paths = componentSvgPaths(c, figmaSvgSlugs, figmaVariantSvgPaths);
      return paths.light && paths.dark && comparePng(c) && compareDarkPng(c);
    },
  );

  // One flat, client-sortable table — initial paint is catalog order; the scorer
  // re-orders worst-match-first once every row has a score.
  const body = components
    .map((c) => componentRow(c, figmaSvgSlugs, figmaVariantSvgPaths, hybridSlugs))
    .join("\n");

  const meta = catalog.meta ?? catalog;
  const title = meta.title ?? meta.system ?? "Design catalog";
  const subtitleParts = [
    meta.system && `system <code>${esc(meta.system)}</code>`,
    meta.renderer && `rendered by ${esc(meta.renderer)}`,
    `${components.length} components`,
    `${comparable} comparable`,
  ].filter(Boolean);

  // Theme control only when there's a dark capture to switch the PNG column to.
  const themeControl = hasDark
    ? `<label class="ov">Theme
        <select id="ov-theme"><option value="light">Light</option><option value="dark">Dark</option></select></label>`
    : "";

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>${esc(title)} — SVG vs PNG compare</title>
<style>
  :root { color-scheme: light dark; --bg:#0f0f10; --panel:#1b1b1d; --fg:#e8e8ea; --muted:#9b9ba1; --line:#2a2a2d;
    --good:#7dd87d; --warn:#e0c060; --bad:#e08080; --accent:#6ea8fe; }
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
  /* Override control bar: the font scale / embedded-fonts / theme / backdrop knobs
     the scorer reads before re-rasterizing each SVG. */
  .controls { margin-top:14px; display:flex; gap:16px 20px; flex-wrap:wrap; align-items:center;
    padding:12px 14px; border:1px solid var(--line); border-radius:10px; background:var(--panel); }
  .controls .ov { display:flex; align-items:center; gap:8px; font-size:12px; color:var(--muted); }
  .controls .ov output { color:var(--fg); font-variant-numeric:tabular-nums; min-width:2.6em; }
  .controls .ov input[type=range] { accent-color:var(--accent); }
  .controls .ov select { background:var(--bg); color:var(--fg); border:1px solid var(--line); border-radius:6px; padding:3px 6px; }
  .controls .ov-check { color:var(--fg); }
  .controls #ov-reset { margin-left:auto; background:var(--bg); color:var(--fg); border:1px solid var(--line);
    border-radius:6px; padding:5px 12px; font-size:12px; cursor:pointer; }
  .controls #ov-reset:hover { border-color:var(--accent); }
  .ov-active { flex-basis:100%; margin-top:2px; color:var(--warn); font-size:12px; }
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
  /* Backdrop override (body[data-bg]) — flat white / dark to read glyph edges without the grid. */
  body[data-bg="white"] .shot:not(.shot--missing) { background-color:#ffffff; background-image:none; }
  body[data-bg="dark"] .shot:not(.shot--missing) { background-color:#0b0b0c; background-image:none; }
  .shot img { max-width:260px; max-height:200px; height:auto; display:block; }
  /* Framed mode: the scorer sizes the tile to the component's content bbox (read from the
     figma-svg's translate + viewBox) and absolutely-positions the PNG so only the component
     shows — so a wear sticker rendered on a 454² device canvas is displayed cropped to the
     component, matching the content-cropped SVG column instead of floating in an empty frame.
     A no-op for close-cropped renders (phone), where the bbox already fills the frame. */
  .shot--framed { overflow:hidden; padding:0; min-width:0; min-height:0; }
  /* Doubled class deliberately: ".shot--framed img" and ".shot img" tie on specificity, so the
     narrow-viewport cap below — declared later — would win and clamp a framed image to 150px
     inside a tile up to 240px wide. That breaks the framing outright (the PNG column's offset is
     computed from the unclamped scale, so the two columns stop lining up) and, with it, any
     mapping from a point in the tile back to a source pixel. */
  .shot.shot--framed img { position:absolute; max-width:none; max-height:none; }
  .shot--missing { color:var(--muted); font-style:italic; background:var(--panel); }
  /* Eyedropper: a framed tile is a live surface, and the crosshair marks the sampled point in
     BOTH columns at once so the pair is read as one point rather than two lookalikes. Double
     ring (dark core, light halo) so it stays visible on any colour underneath it. */
  .shot--framed { cursor:crosshair; }
  .shot .xh { position:absolute; width:9px; height:9px; margin:-5px 0 0 -5px; border-radius:50%;
    border:1px solid #000; box-shadow:0 0 0 1px rgba(255,255,255,0.9); pointer-events:none; z-index:2; }
  .pick { position:fixed; right:16px; bottom:16px; z-index:5; width:264px; padding:12px 14px;
    border:1px solid var(--line); border-radius:10px; background:var(--panel); box-shadow:0 8px 28px rgba(0,0,0,0.45);
    font-size:12px; }
  .pick[hidden] { display:none; }
  .pick--locked { border-color:var(--accent); }
  .pick--left { left:16px; right:auto; }
  .pick .pick-where { color:var(--muted); font-size:11px; word-break:break-word; }
  .pick .pick-row { display:flex; align-items:center; gap:9px; margin-top:9px; }
  .pick .pick-sw { width:26px; height:26px; border-radius:6px; border:1px solid var(--line); flex:none; }
  .pick .pick-sw--none { background-image:linear-gradient(45deg,transparent 45%,var(--muted) 45%,var(--muted) 55%,transparent 55%); }
  .pick .pick-who { color:var(--muted); font-size:10px; text-transform:uppercase; letter-spacing:0.04em; }
  .pick .pick-val { font-variant-numeric:tabular-nums; font-size:13px; }
  .pick .pick-sub { color:var(--muted); font-size:10px; }
  .pick .pick-verdict { margin-top:10px; font-size:11px; font-variant-numeric:tabular-nums; }
  .pick .pick-verdict.ok { color:var(--good); }
  .pick .pick-verdict.near { color:var(--warn); }
  .pick .pick-verdict.off { color:var(--bad); }
  .pick .pick-hint { margin-top:8px; color:var(--muted); font-size:10px; }
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
  <h1>${esc(title)} — SVG vs PNG</h1>
  <div class="subtitle">${subtitleParts.join(" · ")}</div>
  <div class="summary">
    <span><span class="k">avg structural match</span> <b id="avg">…</b></span>
    <span><span class="k">scored</span> <b id="done">0</b> / ${comparable}</span>
  </div>
  <div class="controls" role="group" aria-label="Override options">
    <label class="ov">Font scale <output id="ov-fontScale-val">1.0×</output>
      <input id="ov-fontScale" type="range" min="0.5" max="2" step="0.1" value="1" aria-label="Font scale" /></label>
    <label class="ov ov-check"><input id="ov-fonts" type="checkbox" checked /> Embedded fonts</label>
    ${themeControl}
    <label class="ov">Backdrop
      <select id="ov-bg"><option value="checker">Checker</option><option value="white">White</option><option value="dark">Dark</option></select></label>
    <button type="button" id="ov-reset">Reset</button>
    <span class="ov-active" id="ov-active" hidden></span>
  </div>
  <p class="note">Each row pairs the editable <strong>figma-svg</strong> re-rasterized <em>by your browser</em>
  with the rendered <strong>PNG</strong> (raster source of truth) it is measured against. The match column is a windowed
  <strong>SSIM</strong> (structural similarity) computed live in the page — pre-blurred and downscaled so a
  half-pixel offset between the two rasterizers barely moves the score, unlike a per-pixel diff. The SVG is
  aligned to the PNG first (its export padding + root <code>translate</code> are cropped back out, matching the
  daemon's fidelity harness), so the score reflects real vector drift, not a constant inset. Hybrid stickers'
  raster crop layers are inlined so their score reflects the full sticker. The <strong>override controls</strong>
  above re-apply a knob to the vector and re-score live: raising <strong>font scale</strong> grows the SVG's
  <code>&lt;text&gt;</code> while its captured layer boxes stay put — a fixed-geometry sticker can't reflow, so
  the glyphs overflow, showing why it can't stand in for a re-rendered <code>fontScale</code>; turning off
  <strong>embedded fonts</strong> drops the <code>@font-face</code> so the browser substitutes its own face.
  <strong>Rows sort largest-diff-first once scored</strong>, so the worst offenders come to the top.
  <strong>Hover either column</strong> to read the colour under the cursor: one hover samples the same
  point in <em>both</em> images — the vector's own pixel and, through the same alignment the score uses,
  the render's — and reports each as it composites over the tile's backdrop, which is the only form in
  which a translucent state layer and an opaque container are comparable at all. Click to freeze the
  reading, <kbd>Esc</kbd> to release.</p>
  <p class="taintwarn" id="taintwarn">⚠ The match scores read pixels back from a canvas, which the browser
  blocks when the images can't be read cross-origin — over <code>file://</code> (opaque origin), or from a
  host that doesn't send CORS headers for the PNGs. Open this page over <strong>http</strong> from a
  CORS-enabled host: the README's htmlpreview link (images come from <code>raw.githubusercontent.com</code>,
  which allows it) or a local server (<code>python3 -m http.server</code> in the branch).</p>
</header>
<aside class="pick" id="pick" hidden aria-live="polite">
  <div class="pick-where" id="pick-where"></div>
  <div class="pick-row">
    <span class="pick-sw" id="pick-svg-sw"></span>
    <span><span class="pick-who">figma-svg</span><br /><span class="pick-val" id="pick-svg-val">—</span><br /><span class="pick-sub" id="pick-svg-sub"></span></span>
  </div>
  <div class="pick-row">
    <span class="pick-sw" id="pick-png-sw"></span>
    <span><span class="pick-who">render</span><br /><span class="pick-val" id="pick-png-val">—</span><br /><span class="pick-sub" id="pick-png-sub"></span></span>
  </div>
  <div class="pick-verdict" id="pick-verdict"></div>
  <div class="pick-hint">click to freeze · esc to release</div>
</aside>
<main>
  <table>
    <thead>
      <tr>
        <th scope="col">Component</th>
        <th scope="col">SVG (browser-rendered)</th>
        <th scope="col">PNG</th>
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
