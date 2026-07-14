/**
 * Render a self-contained `matches.html` for a design-artifact catalog: every
 * component of THIS system (e.g. remote-m3) paired with its declared parallel in
 * a SIBLING system (e.g. wear-m3), side by side, so you can eyeball how a ported
 * component compares to its origin across the whole system at once.
 *
 * The pairing is authored, not guessed: each component in the system's
 * `catalog.spec.json` carries a `parallel` field naming its counterpart's
 * `componentId` in the other system (see samples/design-catalog-remote-m3). This
 * module takes the local catalog (the flattened manifest, same shape
 * `renderIndexHtml`/`renderCompareHtml` read), the `parallel` map lifted from the
 * spec, and the other system's component list (also from its committed spec), and
 * computes three buckets: paired, only-here, and only-there.
 *
 * The local (this-system) render is referenced by the catalog's own relative
 * `images/...` path, so it works straight from the branch checkout. The OTHER
 * system's render lives on its own `design-artifacts/<other>` branch, so the page
 * fetches that branch's `catalog.json` at view time (from the same
 * `raw.githubusercontent.com` origin the images already load from) and fills in
 * the parallel thumbnails by slug. That keeps the page self-contained at build
 * time and always fresh; if the fetch fails (offline, or opened over file://) the
 * pair still lists the parallel's name with a link to the other catalog.
 *
 * Pure + dependency-free: returns an HTML string. Slug derivation matches
 * render-wireframe-svg's `slug` so the client can map componentId → image path
 * the same way the export wrote them.
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
  return !hasProps && (image.state ?? "default") === "default";
}

/**
 * The hero PNG for a component: the default, light-themed, largest render — the
 * same pick the index/compare pages make, so the paired thumbnail matches what a
 * browser sees elsewhere. Returns the image record (with `.path`) or undefined.
 */
function heroImage(component) {
  const ideal = idealImages(component);
  const pool = ideal.length ? ideal : (component.images ?? []);
  const defaults = pool.filter(isDefault);
  const byDefault = defaults.length ? defaults : pool;
  const light = byDefault.filter((i) => i.theme === "light");
  const chooseFrom = light.length ? light : byDefault;
  return [...chooseFrom].sort((a, b) => (b.width ?? 0) - (a.width ?? 0))[0];
}

/**
 * Split the local catalog against the other system's components using the authored
 * `parallel` map (this-system componentId → other-system componentId).
 *
 * @param {object[]} localComponents the local manifest's `components`
 * @param {Record<string,string>} parallelById componentId → parallel componentId
 * @param {object[]} otherComponents the other system's spec components ({componentId, group, caption})
 * @returns {{paired: object[], onlyLocal: object[], onlyOther: object[]}}
 *   paired: { local, parallelId, other|null } — `other` is null when the parallel
 *     id isn't (yet) catalogued in the other system.
 *   onlyLocal: local components with no `parallel` declared.
 *   onlyOther: other components no local `parallel` points at.
 */
export function crossSystemMatches(localComponents, parallelById, otherComponents) {
  const otherById = new Map((otherComponents ?? []).map((c) => [c.componentId, c]));
  const referenced = new Set();
  const paired = [];
  const onlyLocal = [];
  for (const local of localComponents ?? []) {
    const parallelId = parallelById?.[local.componentId];
    if (!parallelId) {
      onlyLocal.push(local);
      continue;
    }
    const other = otherById.get(parallelId) ?? null;
    if (other) referenced.add(parallelId);
    paired.push({ local, parallelId, other });
  }
  const onlyOther = (otherComponents ?? []).filter((c) => !referenced.has(c.componentId));
  return { paired, onlyLocal, onlyOther };
}

/** The `<img>` (or placeholder) for the local render column. */
function localShot(component) {
  const hero = heroImage(component);
  const id = component.componentId ?? "(unnamed)";
  return hero
    ? `<div class="shot"><img loading="lazy" src="${esc(hero.path)}" alt="${esc(id)}" /></div>`
    : `<div class="shot shot--missing">no render</div>`;
}

/**
 * The other-system column: a slot the client fills from the other branch's
 * catalog.json (keyed by the parallel's slug via `data-parallel`), plus a link to
 * the other catalog. When the parallel isn't catalogued there yet, an inert note.
 */
function otherShot(parallelId, other, otherBranchBase, otherIndexUrl) {
  if (!other) {
    return `<div class="shot shot--missing">no <code>${esc(parallelId)}</code> sticker yet</div>`;
  }
  const s = slug(parallelId);
  return `<div class="shot shot--other" data-parallel="${esc(s)}">
    <span class="pending">loading ${esc(parallelId)}…</span>
    <a class="wf" href="${esc(otherIndexUrl)}" target="_blank" rel="noopener">open ↗</a>
  </div>`;
}

/**
 * One `<tr>` per pairing. The local render is baked in; the other render is
 * resolved client-side from the other branch's catalog.
 */
function pairRow(pair, opts) {
  const { local, parallelId, other } = pair;
  const id = local.componentId ?? "(unnamed)";
  const group = local.group ?? "Components";
  return `<tr class="crow">
  <th scope="row" class="rowhead"><span class="cid">${esc(id)}</span><span class="grp">${esc(group)}</span></th>
  <td class="col-a">${localShot(local)}</td>
  <td class="col-b">${otherShot(parallelId, other, opts.otherBranchBase, opts.otherIndexUrl)}</td>
  <td class="rel"><code>${esc(parallelId)}</code>${other ? "" : `<span class="badge" title="the parallel isn't in the ${esc(opts.otherSystem)} catalog yet">unpaired</span>`}</td>
</tr>`;
}

/** A short inventory row for the only-here / only-there sections. */
function soloRow(component) {
  const id = component.componentId ?? "(unnamed)";
  const group = component.group ?? "Components";
  const cap = component.caption ? `<span class="cap">${esc(component.caption)}</span>` : "";
  return `<li><span class="cid">${esc(id)}</span><span class="grp">${esc(group)}</span>${cap}</li>`;
}

const DEFAULT_REPO = "yschimke/compose-ai-tools";

/**
 * The client-side resolver, inlined so the page is self-contained. It fetches the
 * other branch's catalog.json once, maps componentId-slug → hero image path, and
 * fills each `[data-parallel]` slot with an <img> pointing at the other branch's
 * raw asset. Best-effort: on any failure the slots keep their name + link.
 */
const RESOLVER = (catalogUrl, branchBase) => String.raw`
(() => {
  "use strict";
  var CATALOG = ${JSON.stringify(catalogUrl)};
  var BASE = ${JSON.stringify(branchBase)};
  function slugOf(s) {
    return String(s == null ? "" : s).toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/(^-|-$)/g, "");
  }
  function heroPath(component) {
    var imgs = (component.images || []).filter(function (i) { return i.variant === "ideal"; });
    if (!imgs.length) imgs = component.images || [];
    var defaults = imgs.filter(function (i) {
      return Object.keys(i.props || {}).length === 0 && (i.state || "default") === "default";
    });
    var pool = defaults.length ? defaults : imgs;
    var light = pool.filter(function (i) { return i.theme === "light"; });
    var from = light.length ? light : pool;
    from = from.slice().sort(function (a, b) { return (b.width || 0) - (a.width || 0); });
    return from[0] && from[0].path;
  }
  fetch(CATALOG).then(function (r) { return r.json(); }).then(function (manifest) {
    var bySlug = {};
    (manifest.components || []).forEach(function (c) {
      var p = heroPath(c);
      if (p) bySlug[slugOf(c.componentId)] = p;
    });
    document.querySelectorAll(".shot--other[data-parallel]").forEach(function (slot) {
      var path = bySlug[slot.getAttribute("data-parallel")];
      if (!path) {
        // The sibling catalog loaded but carries no render for this parallel —
        // e.g. the other branch is stale (published before its matching sticker
        // landed) or that render was skipped. Don't leave "loading …" forever.
        var pending = slot.querySelector(".pending");
        if (pending) pending.textContent = "not published yet";
        slot.classList.add("shot--stale");
        return;
      }
      var img = new Image();
      img.loading = "lazy";
      img.decoding = "async";
      img.crossOrigin = "anonymous";
      img.alt = slot.getAttribute("data-parallel");
      img.src = BASE + path;
      img.onload = function () {
        var pending = slot.querySelector(".pending");
        if (pending) pending.remove();
        slot.insertBefore(img, slot.firstChild);
        slot.classList.add("shot--loaded");
      };
    });
  }).catch(function () { /* offline / file:// — keep the name + link fallback */ });
})();
`;

/**
 * Render the catalog to a complete cross-system comparison page.
 * @param {object} catalog the local flattened manifest (system, title, components, …)
 * @param {object} opts
 *   { parallelById, otherComponents, otherSystem, otherTitle?, repo? }
 * @returns {string} a self-contained matches.html
 */
export function renderCrossSystemHtml(catalog, opts = {}) {
  const components = catalog.components ?? [];
  const meta = catalog.meta ?? catalog;
  const system = meta.system ?? "catalog";
  const title = meta.title ?? system;
  const repo = opts.repo ?? DEFAULT_REPO;
  const otherSystem = opts.otherSystem ?? "other";
  const otherTitle = opts.otherTitle ?? otherSystem;
  const otherBranch = `design-artifacts/${otherSystem}`;
  const otherBranchBase = `https://raw.githubusercontent.com/${repo}/${otherBranch}/`;
  const otherCatalogUrl = `${otherBranchBase}catalog.json`;
  const otherIndexUrl = `https://htmlpreview.github.io/?https://github.com/${repo}/blob/${otherBranch}/index.html`;

  const { paired, onlyLocal, onlyOther } = crossSystemMatches(
    components,
    opts.parallelById ?? {},
    opts.otherComponents ?? [],
  );

  const rowOpts = { otherSystem, otherBranchBase, otherIndexUrl };
  const body = paired.map((p) => pairRow(p, rowOpts)).join("\n");
  const pairedReal = paired.filter((p) => p.other).length;

  const onlyLocalList = onlyLocal.length
    ? `<section class="solo"><h2>Only in ${esc(system)} <span>${onlyLocal.length}</span></h2>
       <ul>${onlyLocal.map(soloRow).join("\n")}</ul></section>`
    : "";
  const onlyOtherList = onlyOther.length
    ? `<section class="solo"><h2>Only in ${esc(otherSystem)} <span>${onlyOther.length}</span></h2>
       <ul>${onlyOther.map(soloRow).join("\n")}</ul></section>`
    : "";

  const subtitleParts = [
    `${title} <span class="arrow">↔</span> ${otherTitle}`,
    `${paired.length} paired`,
    `${pairedReal} rendered both sides`,
  ];

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>${esc(title)} ↔ ${esc(otherTitle)} — component matches</title>
<style>
  :root { color-scheme: light dark; --bg:#0f0f10; --panel:#1b1b1d; --fg:#e8e8ea; --muted:#9b9ba1; --line:#2a2a2d;
    --accent:#7dd87d; --warn:#e0c060; }
  * { box-sizing: border-box; }
  body { margin:0; font:14px/1.5 system-ui, -apple-system, Segoe UI, Roboto, sans-serif; background:var(--bg); color:var(--fg); }
  header.top { padding:24px clamp(16px,4vw,40px); border-bottom:1px solid var(--line); }
  header.top h1 { margin:0 0 6px; font-size:22px; }
  header.top .subtitle { color:var(--muted); font-size:13px; display:flex; gap:16px; flex-wrap:wrap; }
  header.top .arrow { color:var(--accent); }
  header.top .note { margin-top:10px; color:var(--muted); font-size:12px; max-width:78ch; }
  header.top code { background:var(--panel); padding:1px 6px; border-radius:5px; }
  main { padding:8px clamp(16px,4vw,40px) 64px; }
  table { border-collapse:collapse; width:100%; }
  thead th { position:sticky; top:0; background:var(--bg); text-align:left; font-size:12px; color:var(--muted);
    padding:10px 12px; border-bottom:1px solid var(--line); z-index:1; }
  tbody tr.crow { border-bottom:1px solid var(--line); }
  th.rowhead { text-align:left; font-weight:600; padding:12px; vertical-align:middle; width:22%; }
  th.rowhead .cid { display:block; word-break:break-word; }
  th.rowhead .grp { display:block; margin-top:3px; font-weight:400; font-size:11px; color:var(--muted); }
  td { padding:10px 12px; vertical-align:middle; }
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
  .shot--other .pending { color:var(--muted); font-size:12px; }
  .shot--stale .pending { color:var(--warn); font-style:italic; }
  .shot--other .wf { position:absolute; bottom:4px; right:6px; font-size:11px; }
  .rel code { font-size:12px; }
  a.wf { color:#8ab4f8; text-decoration:none; }
  a.wf:hover { text-decoration:underline; }
  .badge { display:inline-block; margin-left:6px; font-size:10px; padding:0 6px; border-radius:999px;
    background:rgba(224,192,96,0.12); color:var(--warn); border:1px solid var(--warn); }
  section.solo { margin-top:36px; }
  section.solo h2 { font-size:15px; border-bottom:1px solid var(--line); padding-bottom:6px; }
  section.solo h2 span { color:var(--muted); font-weight:400; }
  section.solo ul { list-style:none; padding:0; display:grid; grid-template-columns:repeat(auto-fill,minmax(240px,1fr)); gap:6px 20px; }
  section.solo li { padding:6px 0; border-bottom:1px solid var(--line); }
  section.solo .cid { font-weight:600; }
  section.solo .grp { color:var(--muted); font-size:11px; margin-left:8px; }
  section.solo .cap { display:block; color:var(--muted); font-size:12px; }
  @media (max-width:640px){ .shot img { max-width:150px; } th.rowhead { width:auto; } }
</style>
</head>
<body>
<header class="top">
  <h1>${esc(title)} ↔ ${esc(otherTitle)}</h1>
  <div class="subtitle">${subtitleParts.map((s) => `<span>${s}</span>`).join("")}</div>
  <p class="note">Each row pairs a <strong>${esc(system)}</strong> component with its declared
  <strong>${esc(otherSystem)}</strong> parallel (the <code>parallel</code> field in the catalog spec). The
  left render is this branch's baked PNG; the right one is fetched live from the
  <code>${esc(otherBranch)}</code> branch, so browsing this page shows both systems side by side. A parallel
  that isn't catalogued in ${esc(otherSystem)} yet is flagged <span class="badge">unpaired</span>. Components with
  no parallel on either side are listed below the table.</p>
</header>
<main>
  <table>
    <thead>
      <tr>
        <th scope="col">Component</th>
        <th scope="col">${esc(title)}</th>
        <th scope="col">${esc(otherTitle)}</th>
        <th scope="col">Parallel</th>
      </tr>
    </thead>
    <tbody id="rows">${body}</tbody>
  </table>
  ${onlyLocalList}
  ${onlyOtherList}
</main>
<script>${RESOLVER(otherCatalogUrl, otherBranchBase)}</script>
</body>
</html>
`;
}
