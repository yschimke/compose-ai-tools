/**
 * Render a self-contained `rc-compare.html` for a design-artifact catalog that
 * ships Remote Compose documents (`ir/<id>.rc`): every preview on one row, its
 * baked **PNG** (the Robolectric/Skiko render, source of truth) in one column,
 * the same document **rendered client-side by the vendored TypeScript player**
 * (`RC.RcdPlayer` on a `<canvas>`, the browser render lane) in a second, and a
 * **pixel-diff** in a third. A per-row mismatch % (fraction of pixels the diff
 * flags, `pixelmatch` at the driver's threshold) says how close the JS player
 * gets to the baked capture; rows sort **worst-match-first** so the biggest
 * divergences surface first.
 *
 * This is the RC counterpart of `render-compare-html.mjs` (PNG↔figma-svg). Unlike
 * that page, the diff here is computed **at build time** by the driver
 * (`rc-compare.mjs`, headless Chromium + pixelmatch) — this module is a pure
 * string emitter over the driver's result model, so it stays unit-testable with
 * a hand-built model and never touches a browser or the filesystem.
 *
 * Model shape (produced by rc-compare.mjs):
 *   {
 *     system, title,
 *     rows: [{
 *       id, name, group,
 *       width, height,
 *       rendered,            // false when the player could not decode the doc
 *       note,                // optional reason when !rendered
 *       mismatchPct,         // 0..100, null when !rendered
 *       mismatchPx,          // integer, null when !rendered
 *       baked, rc, diff,     // out-relative image paths ('' when absent)
 *     }],
 *   }
 */

function esc(s) {
  return String(s ?? "").replace(
    /[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c],
  );
}

/** Aggregate stats over the rows — mean mismatch across *rendered* rows, counts. */
export function summarizeRcCompare(rows = []) {
  const rendered = rows.filter((r) => r.rendered);
  const unsupported = rows.length - rendered.length;
  const meanPct =
    rendered.length === 0
      ? null
      : rendered.reduce((s, r) => s + (r.mismatchPct ?? 0), 0) / rendered.length;
  return { total: rows.length, rendered: rendered.length, unsupported, meanPct };
}

/** Sort worst-match-first; unrenderable rows sink to the bottom, then by name. */
function sortRows(rows) {
  return [...rows].sort((a, b) => {
    if (a.rendered !== b.rendered) return a.rendered ? -1 : 1;
    if (a.rendered) return (b.mismatchPct ?? 0) - (a.mismatchPct ?? 0);
    return String(a.name).localeCompare(String(b.name));
  });
}

/** Colour band for a mismatch %: green (close) → amber → red (far). */
function band(pct) {
  if (pct == null) return "na";
  if (pct < 2) return "good";
  if (pct < 10) return "ok";
  return "bad";
}

function cell(label, src, extraClass = "") {
  const body = src
    ? `<img loading="lazy" src="${esc(src)}" alt="${esc(label)}">`
    : `<div class="missing">—</div>`;
  return `<figure class="cell ${extraClass}"><figcaption>${esc(label)}</figcaption>${body}</figure>`;
}

function rowHtml(r) {
  const pct = r.rendered ? `${(r.mismatchPct ?? 0).toFixed(2)}%` : (r.note || "no client render");
  const px =
    r.rendered && r.mismatchPx != null
      ? `<span class="px">${r.mismatchPx.toLocaleString("en-US")} px</span>`
      : "";
  const dims = r.width && r.height ? `<span class="dims">${r.width}×${r.height}</span>` : "";
  return `<tr class="row ${r.rendered ? "rendered" : "unsupported"}" data-pct="${
    r.rendered ? (r.mismatchPct ?? 0) : ""
  }">
  <th class="meta">
    <div class="name">${esc(r.name)}</div>
    ${r.group ? `<div class="group">${esc(r.group)}</div>` : ""}
    <div class="score ${band(r.rendered ? r.mismatchPct : null)}">${esc(pct)}</div>
    ${px} ${dims}
  </th>
  <td>${cell("baked PNG", r.baked)}</td>
  <td>${cell("RC · JS player", r.rc, "rc")}</td>
  <td>${cell("pixel diff", r.diff, "diff")}</td>
</tr>`;
}

export function renderRcCompareHtml(model, opts = {}) {
  const system = model.system ?? "";
  const title = model.title ?? system;
  const rows = sortRows(model.rows ?? []);
  const stats = summarizeRcCompare(model.rows ?? []);
  const meanTxt = stats.meanPct == null ? "n/a" : `${stats.meanPct.toFixed(2)}%`;
  const genNote = opts.generatedNote ? `<span class="note">${esc(opts.generatedNote)}</span>` : "";

  const summary =
    `<strong>${stats.rendered}</strong> rendered · mean mismatch <strong>${meanTxt}</strong>` +
    (stats.unsupported
      ? ` · <strong>${stats.unsupported}</strong> not decodable by the JS player`
      : "");

  const body =
    rows.length === 0
      ? `<p class="empty">This catalog ships no Remote Compose documents (<code>ir/*.rc</code>), so there is nothing to compare.</p>`
      : `<table class="grid">
  <thead><tr><th>preview</th><th>baked PNG</th><th>RC · JS player</th><th>pixel diff</th></tr></thead>
  <tbody>
${rows.map(rowHtml).join("\n")}
  </tbody>
</table>`;

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)} — PNG vs Remote Compose (JS player)</title>
<style>
  :root { color-scheme: light dark; --bg:#fff; --fg:#111; --muted:#666; --line:#e2e2e2; --card:#fafafa; }
  @media (prefers-color-scheme: dark) {
    :root { --bg:#0e0e0e; --fg:#eee; --muted:#9a9a9a; --line:#2a2a2a; --card:#161616; }
  }
  * { box-sizing: border-box; }
  body { margin:0; font:14px/1.4 system-ui, sans-serif; color:var(--fg); background:var(--bg); }
  header { padding:16px 20px; border-bottom:1px solid var(--line); position:sticky; top:0; background:var(--bg); z-index:2; }
  h1 { margin:0 0 4px; font-size:18px; }
  .summary { color:var(--muted); }
  .note { display:block; margin-top:4px; color:var(--muted); font-size:12px; }
  .lede { padding:12px 20px; color:var(--muted); max-width:70ch; }
  .wrap { overflow-x:auto; padding:0 20px 40px; }
  table.grid { border-collapse:collapse; width:100%; min-width:720px; }
  thead th { text-align:left; font-size:12px; text-transform:uppercase; letter-spacing:.04em; color:var(--muted); padding:12px 8px; border-bottom:1px solid var(--line); position:sticky; top:64px; background:var(--bg); }
  tbody tr { border-bottom:1px solid var(--line); }
  th.meta { text-align:left; vertical-align:top; padding:12px 8px; width:180px; }
  .name { font-weight:600; word-break:break-word; }
  .group { color:var(--muted); font-size:12px; margin-top:2px; }
  .score { display:inline-block; margin-top:8px; padding:2px 8px; border-radius:999px; font-variant-numeric:tabular-nums; font-weight:600; }
  .score.good { background:#1a7f37; color:#fff; }
  .score.ok   { background:#9a6700; color:#fff; }
  .score.bad  { background:#b32424; color:#fff; }
  .score.na   { background:#555; color:#fff; }
  .px { display:inline-block; margin-top:6px; color:var(--muted); font-size:12px; font-variant-numeric:tabular-nums; }
  .dims { display:inline-block; margin-top:6px; margin-left:6px; color:var(--muted); font-size:12px; }
  td { padding:12px 8px; vertical-align:top; }
  figure.cell { margin:0; }
  figcaption { font-size:11px; color:var(--muted); margin-bottom:4px; }
  .cell img { display:block; max-width:280px; width:100%; height:auto; border:1px solid var(--line); border-radius:6px; background:
    repeating-conic-gradient(#0000 0% 25%, color-mix(in srgb, var(--fg) 6%, transparent) 0% 50%) 50% / 20px 20px; }
  .cell.diff img { background:#000; }
  .missing { width:120px; height:80px; display:grid; place-items:center; color:var(--muted); border:1px dashed var(--line); border-radius:6px; }
  tr.unsupported .score { background:#555; }
  .empty { padding:24px 20px; color:var(--muted); }
  code { background:var(--card); padding:1px 5px; border-radius:4px; }
</style>
</head>
<body>
<header>
  <h1>${esc(title)} — PNG vs Remote Compose <span style="font-weight:400;color:var(--muted)">(client-side JS player)</span></h1>
  <div class="summary">${summary}</div>
  ${genNote}
</header>
<p class="lede">Each preview's baked <strong>PNG</strong> (the offline Robolectric/Skiko render) next to the
same <code>ir/*.rc</code> document <strong>rendered client-side</strong> by the vendored TypeScript
<code>RC.RcdPlayer</code> on a <code>&lt;canvas&gt;</code>, and their per-pixel diff. Mismatch % is the
fraction of pixels the diff flags; rows sort worst-match-first.</p>
<div class="wrap">
${body}
</div>
</body>
</html>
`;
}
