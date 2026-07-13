/**
 * Render a self-contained `index.html` for a design-artifact catalog: every
 * component grouped, with its rendered sticker, an a11y "greenline" summary, and
 * a jump-to index. Committed into each `design-artifacts/<system>` branch next to
 * `catalog.json` and `images/`, so a designer can browse the system in a browser
 * before importing into Figma / Stitch / Claude Design.
 *
 * Pure + dependency-free: takes the in-memory `catalog` object the driver already
 * built (see generate-design-catalog.mjs) and returns an HTML string. Image refs
 * are the catalog's own relative `images/...` paths, so the page works straight
 * from the branch checkout. No external assets, no script — just inline CSS.
 */

import { renderWireframeSvg, slug } from "./render-wireframe-svg.mjs";

/** Minimal HTML-escape for text interpolated into the page. */
function esc(s) {
  return String(s ?? "").replace(
    /[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c],
  );
}

/**
 * A link to the component's editable wireframe SVG, when one was written. The
 * driver writes a wireframe from the layout-inspector tree *or* the a11y
 * greenlines, so the link must reflect what was actually written — keyed by the
 * `wireframeSlugs` set the driver passes. Falls back to the greenline predicate
 * when no set is supplied (e.g. a standalone call), preserving prior behaviour.
 */
function wireframeLink(component, wireframeSlugs) {
  const has = wireframeSlugs
    ? wireframeSlugs.has(slug(component.componentId))
    : Boolean(renderWireframeSvg(component));
  if (!has) return "";
  return `<a class="wf" href="wireframes/${slug(component.componentId)}.svg" target="_blank" rel="noopener">wireframe ↗</a>`;
}

/**
 * A link to the component's editable, design-fidelity `compose/figma-svg` vector, when one was
 * carried for it (keyed by the `figmaSvgSlugs` set the driver passes). This is the SVG a designer
 * imports into Figma for an editable component — distinct from the schematic `wireframe ↗`.
 */
function figmaSvgLink(component, figmaSvgSlugs) {
  if (!figmaSvgSlugs || !figmaSvgSlugs.has(slug(component.componentId))) return "";
  return `<a class="wf" href="figma/${slug(component.componentId)}.svg" target="_blank" rel="noopener">figma svg ↗</a>`;
}

/** A component's `ideal` capture images (excludes the `layout` wireframe variant). */
function idealImages(component) {
  return (component.images ?? []).filter((i) => i.variant === "ideal");
}

/** The variant axis an image belongs to — a folded `state` (pressed / disabled / …)
 *  or a content-axis `props` set (e.g. `content: icon+label`). The label-only,
 *  resting render is `state:default` with no props; every other axis is a secondary
 *  variant. `key` groups images; `label` captions the group; `kind` drives wording. */
function variantOf(image) {
  const entries = Object.entries(image.props ?? {}).sort(([a], [b]) => a.localeCompare(b));
  if (entries.length) {
    const label = entries.map(([k, v]) => `${k}=${v}`).join(", ");
    return { key: `props:${label}`, label, kind: "props" };
  }
  const state = image.state ?? "default";
  return { key: `state:${state}`, label: state, kind: "state" };
}

const DEFAULT_KEY = "state:default";

/** One representative image for the grid — the **default** render (largest first),
 *  so neither a folded state (pressed / …) nor a content variant (icon+label, which
 *  can be wider) ever wins the hero. Falls back to the largest ideal, then whatever. */
function heroImage(component) {
  const ideal = idealImages(component);
  const pool = ideal.length ? ideal : component.images ?? [];
  const defaults = pool.filter((i) => variantOf(i).key === DEFAULT_KEY);
  const chooseFrom = defaults.length ? defaults : pool;
  return [...chooseFrom].sort((a, b) => (b.width ?? 0) - (a.width ?? 0))[0];
}

/** The component's ideal images grouped by variant (state or props), default first,
 *  each group's theme/size captures kept together — the single-component zoom view
 *  walks this. Each group carries `{ key, label, kind, images }`. */
function variantGroups(component) {
  const order = [];
  const byKey = new Map();
  for (const image of idealImages(component)) {
    const v = variantOf(image);
    if (!byKey.has(v.key)) {
      byKey.set(v.key, { key: v.key, label: v.label, kind: v.kind, images: [] });
      order.push(v.key);
    }
    byKey.get(v.key).images.push(image);
  }
  order.sort((a, b) => (a === DEFAULT_KEY ? -1 : b === DEFAULT_KEY ? 1 : 0));
  return order.map((k) => byKey.get(k));
}

/** A short per-image label — the theme, then size, else the state. */
function imageLabel(image) {
  return esc(image.theme ?? image.size ?? image.state ?? "");
}

/**
 * The checkerboard backing for a capture, chosen so a transparent sticker reads on the backing that
 * matches its baked theme — a light-theme component (dark strokes/text) sits on a LIGHT check, a
 * dark-theme (or theme-less) one on the default dark check. Emitted as a `data-bg` attribute rather
 * than a class so the `.shot` / `.state-shot` class hooks stay intact.
 */
function bgThemeAttr(image) {
  return String(image?.theme ?? "").toLowerCase() === "light" ? ' data-bg="light"' : "";
}

/** The component's a11y greenlines collapsed to short role/severity chips. */
function greenlineChips(component) {
  const lines = component.greenlines ?? [];
  if (!lines.length) return "";
  const chips = lines
    .map((g) => {
      const role = g.detail?.role ?? g.message ?? g.kind ?? "a11y";
      return `<span class="chip chip--${esc(g.severity ?? "info")}">${esc(role)}</span>`;
    })
    .join("");
  return `<div class="greenlines" title="accessibility greenlines">${chips}</div>`;
}

function componentCard(component, wireframeSlugs, figmaSvgSlugs) {
  const img = heroImage(component);
  const id = component.componentId ?? "(unnamed)";
  const dims = img ? `${img.width}×${img.height}` : "";
  // Variants beyond the default — folded states (pressed / focused / disabled / …)
  // and content axes (icon+label) declared by the spec's `variants`. The grid shows
  // only the default; the extras surface in the zoom view, hinted here by a chip.
  // Wording stays "state(s)" when they're all states, else the general "variant(s)".
  const extras = variantGroups(component).filter((g) => g.key !== DEFAULT_KEY);
  const noun = extras.every((g) => g.kind === "state") ? "state" : "variant";
  const stateChip = extras.length
    ? `<a class="statechip" href="#d-${slug(id)}">+${extras.length} ${noun}${extras.length > 1 ? "s" : ""}</a>`
    : "";
  const figure = img
    ? `<a class="shot"${bgThemeAttr(img)} href="#d-${slug(id)}" aria-label="Zoom ${esc(id)}"><img loading="lazy" src="${esc(img.path)}" alt="${esc(id)}" /></a>`
    : `<div class="shot shot--missing">no render</div>`;
  return `<article class="card" id="c-${slug(id)}">
  ${figure}
  <div class="meta">
    <h3><a class="ctitle" href="#d-${slug(id)}">${esc(id)}</a></h3>
    <div class="sub">${esc(dims)}${stateChip}${wireframeLink(component, wireframeSlugs)}${figmaSvgLink(component, figmaSvgSlugs)}</div>
    ${greenlineChips(component)}
  </div>
</article>`;
}

/**
 * The single-component zoom view — a `:target`-driven overlay (no JS) opened by
 * clicking a card. Shows the component's default render plus every folded state
 * variant as a secondary preview, each labelled by state and theme/size. Closing
 * links back to the card anchor so the page keeps its scroll position.
 */
function componentDetail(component) {
  const id = component.componentId ?? "(unnamed)";
  const close = `#c-${slug(id)}`;
  const caption = component.caption
    ? `<div class="detail-caption">${esc(component.caption)}</div>`
    : "";
  const stateBlocks = variantGroups(component)
    .map((group) => {
      const shots = group.images
        .map(
          (image) =>
            `<div class="state-shot"${bgThemeAttr(image)}><img loading="lazy" src="${esc(image.path)}" alt="${esc(id)} ${esc(group.label)}" /><span>${imageLabel(image)}</span></div>`,
        )
        .join("");
      return `<figure class="state"><figcaption>${esc(group.label)}</figcaption><div class="state-shots">${shots}</div></figure>`;
    })
    .join("");
  return `<div class="detail" id="d-${slug(id)}" role="dialog" aria-label="${esc(id)}">
  <a class="detail-scrim" href="${close}" aria-label="Close"></a>
  <div class="detail-panel">
    <a class="detail-x" href="${close}" aria-label="Close">×</a>
    <h3>${esc(id)}</h3>
    ${caption}
    <div class="detail-states">${stateBlocks}</div>
  </div>
</div>`;
}

/**
 * Render the catalog to a complete HTML document.
 * @param {object} catalog the in-memory catalog (system, title, components, …)
 * @param {object} [opts] { wireframeSlugs?: Set<string>, figmaSvgSlugs?: Set<string> } — the slugs
 *   the driver actually wrote a wireframe / figma-svg for, so the `wireframe ↗` and `figma svg ↗`
 *   links reflect what exists rather than re-deriving them.
 * @returns {string} a self-contained index.html
 */
export function renderIndexHtml(catalog, opts = {}) {
  const components = catalog.components ?? [];
  const wireframeSlugs = opts.wireframeSlugs;
  const figmaSvgSlugs = opts.figmaSvgSlugs;
  const crossSystem = opts.crossSystem;

  // Group preserving first-seen group order.
  const groupOrder = [];
  const byGroup = new Map();
  for (const c of components) {
    const g = c.group ?? "Components";
    if (!byGroup.has(g)) {
      byGroup.set(g, []);
      groupOrder.push(g);
    }
    byGroup.get(g).push(c);
  }

  const nav = groupOrder
    .map((g) => {
      const items = byGroup
        .get(g)
        .map(
          (c) =>
            `<li><a href="#c-${slug(c.componentId)}">${esc(c.componentId)}</a></li>`,
        )
        .join("");
      return `<section class="nav-group">
      <a class="nav-head" href="#g-${slug(g)}">${esc(g)} <span>${byGroup.get(g).length}</span></a>
      <ul>${items}</ul>
    </section>`;
    })
    .join("");

  const main = groupOrder
    .map((g) => {
      const cards = byGroup
        .get(g)
        .map((c) => componentCard(c, wireframeSlugs, figmaSvgSlugs))
        .join("\n");
      return `<section class="group" id="g-${slug(g)}">
      <h2>${esc(g)}</h2>
      <div class="grid">${cards}</div>
    </section>`;
    })
    .join("\n");

  // One zoom overlay per component (opened by clicking its card), appended once at
  // the end of the document so the `:target` overlay sits above everything.
  const details = components.map(componentDetail).join("\n");

  // buildCatalog() returns { meta, components }; writeCatalog() flattens meta
  // into catalog.json. Normalise so the header works on either shape.
  const meta = catalog.meta ?? catalog;
  const library = [].concat(meta.library ?? []).filter(Boolean);
  const title = meta.title ?? meta.system ?? "Design catalog";
  const subtitleParts = [
    meta.system && `system <code>${esc(meta.system)}</code>`,
    library.length && esc(library.join(", ")),
    meta.renderer && `rendered by ${esc(meta.renderer)}`,
    meta.generatedAt && `${esc(meta.generatedAt)}`,
    `${components.length} components`,
  ].filter(Boolean);

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>${esc(title)} — design catalog</title>
<style>
  :root { color-scheme: light dark; --bg:#0f0f10; --panel:#1b1b1d; --fg:#e8e8ea; --muted:#9b9ba1; --line:#2a2a2d; --accent:#7dd87d; }
  * { box-sizing: border-box; }
  body { margin:0; font:14px/1.5 system-ui, -apple-system, Segoe UI, Roboto, sans-serif; background:var(--bg); color:var(--fg); }
  header.top { padding:24px clamp(16px,4vw,40px); border-bottom:1px solid var(--line); }
  header.top h1 { margin:0 0 6px; font-size:22px; }
  header.top .subtitle { color:var(--muted); font-size:13px; }
  header.top code { background:var(--panel); padding:1px 6px; border-radius:5px; }
  header.top .pagelink { color:var(--accent); text-decoration:none; font-size:13px; }
  header.top .pagelink:hover { text-decoration:underline; }
  .layout { display:grid; grid-template-columns:240px 1fr; align-items:start; }
  nav.index { position:sticky; top:0; align-self:start; max-height:100vh; overflow:auto; padding:20px 12px; border-right:1px solid var(--line); }
  nav.index .nav-head { display:flex; justify-content:space-between; font-weight:600; text-decoration:none; color:var(--fg); padding:6px 8px; border-radius:6px; }
  nav.index .nav-head span { color:var(--muted); font-weight:400; }
  nav.index .nav-head:hover { background:var(--panel); }
  nav.index ul { list-style:none; margin:2px 0 14px; padding:0 0 0 8px; }
  nav.index li a { display:block; color:var(--muted); text-decoration:none; padding:3px 8px; border-radius:5px; font-size:13px; }
  nav.index li a:hover { color:var(--fg); background:var(--panel); }
  main { padding:8px clamp(16px,4vw,40px) 64px; min-width:0; }
  .group { padding-top:24px; }
  .group h2 { font-size:16px; border-bottom:1px solid var(--line); padding-bottom:8px; position:sticky; top:0; background:var(--bg); }
  .grid { display:grid; grid-template-columns:repeat(auto-fill, minmax(180px,1fr)); gap:16px; }
  .card { background:var(--panel); border:1px solid var(--line); border-radius:12px; overflow:hidden; }
  /* Theme-aware checkerboard so a transparent component sticker reads on the backing that matches
     its baked theme — a light-theme component (dark strokes/text) on a LIGHT check, a dark-theme (or
     theme-less) one on the default dark check — instead of light-mode content washing out on a fixed
     dark backing. --ck-a / --ck-b are the two check colours; [data-bg=light] swaps them. Full-screen
     stickers carry their own round and just sit on top. Shared by the grid hero and zoom variants. */
  .shot, .state-shot { --ck-a:#161617; --ck-b:#202022;
    background-color:var(--ck-a);
    background-image:
      linear-gradient(45deg,var(--ck-b) 25%,transparent 25%),
      linear-gradient(-45deg,var(--ck-b) 25%,transparent 25%),
      linear-gradient(45deg,transparent 75%,var(--ck-b) 75%),
      linear-gradient(-45deg,transparent 75%,var(--ck-b) 75%);
    background-size:16px 16px; background-position:0 0,0 8px,8px -8px,-8px 0; }
  .shot[data-bg="light"], .state-shot[data-bg="light"] { --ck-a:#e9e9ef; --ck-b:#ffffff; }
  .shot { margin:0; display:grid; place-items:center; min-height:120px; padding:12px; }
  .shot img { max-width:100%; max-height:240px; height:auto; display:block; }
  a.shot { cursor:zoom-in; text-decoration:none; }
  /* Framed mode (set by the crop script below): the hero is sized to the component's content bbox
     — read from the figma-svg's translate + viewBox — and the PNG is absolutely-positioned so only
     the component shows. A wear sticker rendered on a 454² device canvas is displayed cropped to
     the component instead of floating in an empty frame; a no-op for close-cropped (phone) renders
     where the bbox already fills the frame. */
  .shot--framed { overflow:hidden; padding:0; min-height:0; position:relative; place-items:stretch;
    margin:16px auto; border-radius:8px; }
  .shot--framed img { position:absolute; max-width:none; max-height:none; }
  .shot--missing { color:var(--muted); font-style:italic; }
  .meta { padding:10px 12px 12px; border-top:1px solid var(--line); }
  .meta h3 { margin:0; font-size:13px; font-weight:600; word-break:break-word; }
  .meta h3 a.ctitle { color:inherit; text-decoration:none; }
  .meta h3 a.ctitle:hover { text-decoration:underline; }
  .meta .sub { color:var(--muted); font-size:12px; margin-top:2px; display:flex; flex-wrap:wrap; justify-content:space-between; gap:8px; }
  .meta .sub .wf { color:#7dd87d; text-decoration:none; white-space:nowrap; }
  .meta .sub .wf:hover { text-decoration:underline; }
  .statechip { font-size:11px; padding:1px 7px; border-radius:999px; border:1px solid var(--line); color:var(--muted); text-decoration:none; white-space:nowrap; }
  .statechip:hover { color:var(--fg); border-color:var(--muted); }
  .greenlines { margin-top:8px; display:flex; flex-wrap:wrap; gap:4px; }
  .chip { font-size:11px; padding:1px 7px; border-radius:999px; border:1px solid var(--accent); color:var(--accent); }
  .chip--warn { border-color:#e0c060; color:#e0c060; }
  .chip--error { border-color:#e08080; color:#e08080; }
  /* Single-component zoom view — a pure-CSS :target overlay, one per component. */
  .detail { display:none; }
  .detail:target { display:flex; position:fixed; inset:0; z-index:50; align-items:center; justify-content:center; padding:clamp(12px,4vw,40px); }
  .detail-scrim { position:absolute; inset:0; background:rgba(0,0,0,0.72); }
  .detail-panel { position:relative; z-index:1; background:var(--panel); border:1px solid var(--line); border-radius:14px;
    max-width:min(960px,94vw); max-height:88vh; overflow:auto; padding:20px 24px; }
  .detail-panel h3 { margin:0 0 4px; font-size:15px; word-break:break-word; }
  .detail-caption { color:var(--muted); font-size:13px; margin-bottom:14px; }
  .detail-x { position:absolute; top:8px; right:14px; color:var(--muted); text-decoration:none; font-size:24px; line-height:1; }
  .detail-x:hover { color:var(--fg); }
  .detail-states { display:flex; flex-wrap:wrap; gap:18px; }
  figure.state { margin:0; }
  figure.state figcaption { font-size:12px; color:var(--muted); margin-bottom:6px; text-transform:capitalize; }
  .state-shots { display:flex; flex-wrap:wrap; gap:10px; }
  .state-shot { display:flex; flex-direction:column; align-items:center; gap:5px;
    border:1px solid var(--line); border-radius:10px; padding:12px; }
  .state-shot img { max-width:220px; max-height:220px; height:auto; display:block; }
  .state-shot span { font-size:11px; color:var(--muted); text-transform:capitalize; }
  @media (max-width:720px){ .layout{ grid-template-columns:1fr; } nav.index{ position:static; max-height:none; border-right:0; border-bottom:1px solid var(--line);} }
</style>
</head>
<body>
<header class="top">
  <h1>${esc(title)}</h1>
  <div class="subtitle">${subtitleParts.join(" · ")}</div>
  <div class="subtitle"><a class="pagelink" href="compare.html">PNG vs SVG compare ↗</a>${
    crossSystem
      ? ` <a class="pagelink" href="matches.html">↔ ${esc(crossSystem.title)} matches ↗</a>`
      : ""
  }</div>
</header>
<div class="layout">
  <nav class="index">${nav}</nav>
  <main>${main}</main>
</div>
${details}
<script>
// Crop each component's hero (and its zoom-view default capture) to the component's content bbox,
// read from its figma-svg's root translate + viewBox. A wear sticker rendered on a 454² device
// canvas then displays cropped to the component instead of floating in an empty frame. A no-op for
// close-cropped renders (the bbox already fills the frame) and for components with no figma-svg.
(function () {
  function parseBox(svgText) {
    var t = /translate\\(\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\)/.exec(svgText);
    var v = /viewBox="0 0 (\\d+(?:\\.\\d+)?) (\\d+(?:\\.\\d+)?)"/.exec(svgText);
    if (!v) return null;
    return { tx: t ? +t[1] : 0, ty: t ? +t[2] : 0, vw: +v[1], vh: +v[2] };
  }
  function frame(shot, box) {
    var img = shot.querySelector("img");
    if (!img) return;
    function apply() {
      var rw = img.naturalWidth || img.width, rh = img.naturalHeight || img.height;
      if (!(rw > 0 && rh > 0)) return;
      if (box.vw >= rw * 0.9 && box.vh >= rh * 0.9) return; // already close-cropped
      var cap = 240, scale = Math.min(1, cap / Math.max(box.vw, box.vh));
      var dw = Math.max(1, Math.round(box.vw * scale)), dh = Math.max(1, Math.round(box.vh * scale));
      shot.classList.add("shot--framed");
      shot.style.width = dw + "px";
      shot.style.height = dh + "px";
      img.style.width = Math.round(rw * scale) + "px";
      img.style.height = Math.round(rh * scale) + "px";
      // (tx,ty) is negative for a centred component, so tx*scale shifts the render to bring the
      // component's top-left to the clip origin.
      img.style.left = Math.round(box.tx * scale) + "px";
      img.style.top = Math.round(box.ty * scale) + "px";
    }
    if (img.complete) apply();
    else img.addEventListener("load", apply);
  }
  document.querySelectorAll(".card").forEach(function (card) {
    var link = card.querySelector('a.wf[href^="figma/"]');
    if (!link) return;
    var hero = card.querySelector("a.shot");
    if (!hero) return;
    fetch(link.getAttribute("href"))
      .then(function (r) { return r.ok ? r.text() : null; })
      .then(function (txt) {
        if (!txt) return;
        var box = parseBox(txt);
        if (box) frame(hero, box);
      })
      .catch(function () {});
  });
})();
</script>
</body>
</html>
`;
}
