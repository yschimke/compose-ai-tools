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

/** One representative image per component — the first `ideal` variant (largest
 *  first), falling back to whatever exists. Dedupes the size-duplicated entries. */
function heroImage(component) {
  const images = component.images ?? [];
  const ideal = images.filter((i) => i.variant === "ideal");
  const pool = ideal.length ? ideal : images;
  return [...pool].sort((a, b) => (b.width ?? 0) - (a.width ?? 0))[0];
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

function componentCard(component, wireframeSlugs) {
  const img = heroImage(component);
  const id = component.componentId ?? "(unnamed)";
  const dims = img ? `${img.width}×${img.height}` : "";
  const figure = img
    ? `<figure class="shot"><img loading="lazy" src="${esc(img.path)}" alt="${esc(id)}" /></figure>`
    : `<figure class="shot shot--missing">no render</figure>`;
  return `<article class="card" id="c-${slug(id)}">
  ${figure}
  <div class="meta">
    <h3>${esc(id)}</h3>
    <div class="sub">${esc(dims)}${wireframeLink(component, wireframeSlugs)}</div>
    ${greenlineChips(component)}
  </div>
</article>`;
}

/**
 * Render the catalog to a complete HTML document.
 * @param {object} catalog the in-memory catalog (system, title, components, …)
 * @param {object} [opts] { wireframeSlugs?: Set<string> } — the slugs the driver
 *   actually wrote a wireframe for (layout-inspector or greenline), so the
 *   `wireframe ↗` link reflects what exists rather than re-deriving it.
 * @returns {string} a self-contained index.html
 */
export function renderIndexHtml(catalog, opts = {}) {
  const components = catalog.components ?? [];
  const wireframeSlugs = opts.wireframeSlugs;

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
        .map((c) => componentCard(c, wireframeSlugs))
        .join("\n");
      return `<section class="group" id="g-${slug(g)}">
      <h2>${esc(g)}</h2>
      <div class="grid">${cards}</div>
    </section>`;
    })
    .join("\n");

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
  /* Checkerboard so transparent component stickers read clearly; full-screen
     stickers carry their own black round and just sit on top of it. */
  .shot { margin:0; display:grid; place-items:center; min-height:180px; padding:12px;
    background-color:#161617;
    background-image:
      linear-gradient(45deg,#202022 25%,transparent 25%),
      linear-gradient(-45deg,#202022 25%,transparent 25%),
      linear-gradient(45deg,transparent 75%,#202022 75%),
      linear-gradient(-45deg,transparent 75%,#202022 75%);
    background-size:16px 16px; background-position:0 0,0 8px,8px -8px,-8px 0; }
  .shot img { max-width:100%; max-height:240px; height:auto; display:block; }
  .shot--missing { color:var(--muted); font-style:italic; }
  .meta { padding:10px 12px 12px; border-top:1px solid var(--line); }
  .meta h3 { margin:0; font-size:13px; font-weight:600; word-break:break-word; }
  .meta .sub { color:var(--muted); font-size:12px; margin-top:2px; display:flex; justify-content:space-between; gap:8px; }
  .meta .sub .wf { color:#7dd87d; text-decoration:none; white-space:nowrap; }
  .meta .sub .wf:hover { text-decoration:underline; }
  .greenlines { margin-top:8px; display:flex; flex-wrap:wrap; gap:4px; }
  .chip { font-size:11px; padding:1px 7px; border-radius:999px; border:1px solid var(--accent); color:var(--accent); }
  .chip--warn { border-color:#e0c060; color:#e0c060; }
  .chip--error { border-color:#e08080; color:#e08080; }
  @media (max-width:720px){ .layout{ grid-template-columns:1fr; } nav.index{ position:static; max-height:none; border-right:0; border-bottom:1px solid var(--line);} }
</style>
</head>
<body>
<header class="top">
  <h1>${esc(title)}</h1>
  <div class="subtitle">${subtitleParts.join(" · ")}</div>
</header>
<div class="layout">
  <nav class="index">${nav}</nav>
  <main>${main}</main>
</div>
</body>
</html>
`;
}
