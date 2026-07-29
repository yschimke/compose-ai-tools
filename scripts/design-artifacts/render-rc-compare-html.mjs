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
 * Two players are compared against the same baked PNG, each with its own diff
 * and its own mismatch %:
 *
 * * **JS player** — the vendored TypeScript `RC.RcdPlayer` on a `<canvas>`, the
 *   browser render lane.
 * * **embedded player** — the vendored AndroidX `RcPlayer`
 *   (`:third-party-rc-embedded-player`), a pure-Compose interpreter of the same
 *   document. This is the lane that differs from `remote-player-view`'s
 *   `RemoteComposePlayer` (an Android `View` painting to a framework `Canvas`),
 *   so it shows what a host embedding RC content *inside* a Compose tree gets.
 *
 * A row is kept even when only one of the two players could render it — the
 * per-player `rendered` flags are independent, and a player that could not
 * decode the document shows its note in place of a percentage.
 *
 * Model shape (produced by rc-compare.mjs):
 *   {
 *     system, title,
 *     rows: [{
 *       id, name, group,
 *       width, height,
 *       rendered,            // false when the JS player could not decode the doc
 *       note,                // optional reason when !rendered
 *       mismatchPct,         // 0..100, null when !rendered
 *       mismatchPx,          // integer, null when !rendered
 *       baked, rc, diff,     // out-relative image paths ('' when absent)
 *       referenceBlank,      // true when the baked PNG is fully transparent — see below
 *
 *       embeddedRendered,    // false when the embedded player could not render it
 *       embeddedNote,        // optional reason when !embeddedRendered
 *       embeddedMismatchPct, // 0..100, null when !embeddedRendered
 *       embeddedMismatchPx,  // integer, null when !embeddedRendered
 *       embedded,            // out-relative path to the embedded render ('' when absent)
 *       embeddedDiff,        // out-relative path to its diff ('' when absent)
 *     }],
 *   }
 *
 * The embedded fields are optional: a model without them (an older summary, or a
 * run where the embedded lane was skipped) renders the JS-only page unchanged,
 * with the embedded columns omitted entirely rather than shown empty.
 *
 * `referenceBlank` marks a preview whose baked PNG carries no opaque pixel at all
 * (a capture that produced nothing). Both sides flatten onto the same neutral
 * background before diffing, so a player that also draws nothing scores an exact
 * 0.00% — a green "good" band for a comparison that never happened. Such rows are
 * shown (the blank baked capture is itself the finding) but excluded from every
 * mean and sorted with the unrenderable rows, and both score chips read
 * `no reference` instead of a percentage.
 */

function esc(s) {
  return String(s ?? "").replace(
    /[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c],
  );
}

/** True when any row carries an embedded-player result, i.e. the lane ran at all. */
export function hasEmbeddedLane(rows = []) {
  return rows.some((r) => r.embeddedRendered !== undefined || r.embedded);
}

/**
 * Aggregate stats over the rows — mean mismatch across *scored* rows, counts.
 *
 * A row whose baked PNG is fully transparent (`referenceBlank`) is rendered but **not scored**: with
 * nothing in the reference, a player that draws nothing flattens to the same neutral background and
 * scores a perfect 0.00%, which reads as a green "good" band for a comparison that never happened.
 * Those rows are excluded from the mean and counted separately, so a catalog that bakes blanks can't
 * inflate the parity numbers.
 */
export function summarizeRcCompare(rows = []) {
  const rendered = rows.filter((r) => r.rendered);
  const unsupported = rows.length - rendered.length;
  const blankReference = rows.filter((r) => r.referenceBlank).length;
  const scored = rendered.filter((r) => !r.referenceBlank);
  const meanPct =
    scored.length === 0 ? null : scored.reduce((s, r) => s + (r.mismatchPct ?? 0), 0) / scored.length;

  // Embedded lane is summarized independently: its render can succeed on a document the JS player
  // chokes on and vice versa, so counting them together would hide which player is behind.
  const embRendered = rows.filter((r) => r.embeddedRendered);
  const embScored = embRendered.filter((r) => !r.referenceBlank);
  const embMeanPct =
    embScored.length === 0
      ? null
      : embScored.reduce((s, r) => s + (r.embeddedMismatchPct ?? 0), 0) / embScored.length;

  return {
    total: rows.length,
    rendered: rendered.length,
    scored: scored.length,
    blankReference,
    unsupported,
    meanPct,
    embeddedRendered: embRendered.length,
    embeddedScored: embScored.length,
    embeddedUnsupported: hasEmbeddedLane(rows) ? rows.length - embRendered.length : 0,
    embeddedMeanPct: embMeanPct,
  };
}

/**
 * Worst score on a row — the *worse* of the two players when both ran, so a row where only the
 * embedded lane diverges still sorts to the top rather than hiding behind a clean JS render.
 * Returns null when neither player produced a render.
 */
function worstPct(r) {
  // An unscorable row has no percentage to sort on — it sinks with the unrenderable ones rather
  // than sitting at the top of the table on a 0% it never earned.
  if (r.referenceBlank) return null;
  const scores = [];
  if (r.rendered) scores.push(r.mismatchPct ?? 0);
  if (r.embeddedRendered) scores.push(r.embeddedMismatchPct ?? 0);
  return scores.length ? Math.max(...scores) : null;
}

/** Sort worst-match-first; rows no player could render sink to the bottom, then by name. */
function sortRows(rows) {
  return [...rows].sort((a, b) => {
    const aw = worstPct(a);
    const bw = worstPct(b);
    if ((aw == null) !== (bw == null)) return aw == null ? 1 : -1;
    if (aw != null) return bw - aw;
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

/**
 * One player's score chip: `NN.NN%` + pixel count, or the reason it produced nothing.
 *
 * `referenceBlank` short-circuits both players: the baked PNG is empty, so there is no comparison to
 * report and any number here would be a lie in either direction.
 */
function scoreBlock(label, rendered, pct, px, note, referenceBlank = false) {
  const scorable = rendered && !referenceBlank;
  const text = scorable
    ? `${(pct ?? 0).toFixed(2)}%`
    : referenceBlank
      ? "no reference"
      : note || "no render";
  const pxTxt =
    scorable && px != null ? `<span class="px">${px.toLocaleString("en-US")} px</span>` : "";
  return `<div class="scoreline">
      <span class="scorelabel">${esc(label)}</span>
      <span class="score ${band(scorable ? pct : null)}">${esc(text)}</span>
      ${pxTxt}
    </div>`;
}

function rowHtml(r, withEmbedded) {
  const dims = r.width && r.height ? `<span class="dims">${r.width}×${r.height}</span>` : "";
  const anyRendered = r.rendered || r.embeddedRendered;
  const scores =
    scoreBlock("js", r.rendered, r.mismatchPct, r.mismatchPx, r.note, r.referenceBlank) +
    (withEmbedded
      ? scoreBlock(
          "embedded",
          r.embeddedRendered,
          r.embeddedMismatchPct,
          r.embeddedMismatchPx,
          r.embeddedNote,
          r.referenceBlank,
        )
      : "") +
    (r.referenceBlank
      ? `<div class="blanknote">baked PNG is fully transparent — nothing to compare against</div>`
      : "");

  const embeddedCells = withEmbedded
    ? `
  <td>${cell("RC · embedded player", r.embedded, "rc")}</td>
  <td>${cell("pixel diff", r.embeddedDiff, "diff")}</td>`
    : "";

  const scorable = !r.referenceBlank;
  return `<tr class="row ${anyRendered ? "rendered" : "unsupported"}${
    r.referenceBlank ? " blank-reference" : ""
  }" data-pct="${scorable && r.rendered ? (r.mismatchPct ?? 0) : ""}" data-embedded-pct="${
    scorable && r.embeddedRendered ? (r.embeddedMismatchPct ?? 0) : ""
  }">
  <th class="meta">
    <div class="name">${esc(r.name)}</div>
    ${r.group ? `<div class="group">${esc(r.group)}</div>` : ""}
    ${scores}
    ${dims}
  </th>
  <td>${cell("baked PNG", r.baked)}</td>
  <td>${cell("RC · JS player", r.rc, "rc")}</td>
  <td>${cell("pixel diff", r.diff, "diff")}</td>${embeddedCells}
</tr>`;
}

export function renderRcCompareHtml(model, opts = {}) {
  const system = model.system ?? "";
  const title = model.title ?? system;
  const rows = sortRows(model.rows ?? []);
  const stats = summarizeRcCompare(model.rows ?? []);
  const meanTxt = stats.meanPct == null ? "n/a" : `${stats.meanPct.toFixed(2)}%`;
  const genNote = opts.generatedNote ? `<span class="note">${esc(opts.generatedNote)}</span>` : "";

  const withEmbedded = hasEmbeddedLane(model.rows ?? []);
  const embMeanTxt =
    stats.embeddedMeanPct == null ? "n/a" : `${stats.embeddedMeanPct.toFixed(2)}%`;

  // Blank references are called out once, not per lane — the reference is shared, so a blank one
  // costs both players the same row.
  const blankTxt = stats.blankReference
    ? ` · <strong>${stats.blankReference}</strong> unscored (blank reference)`
    : "";

  const summary =
    `<strong>JS player:</strong> ${stats.scored} scored · mean mismatch <strong>${meanTxt}</strong>` +
    (stats.unsupported ? ` · ${stats.unsupported} not decodable` : "") +
    blankTxt +
    (withEmbedded
      ? `<br><strong>embedded player:</strong> ${stats.embeddedScored} scored · mean mismatch <strong>${embMeanTxt}</strong>` +
        (stats.embeddedUnsupported ? ` · ${stats.embeddedUnsupported} not rendered` : "") +
        blankTxt
      : "");

  const head = withEmbedded
    ? `<tr><th>preview</th><th>baked PNG</th><th>RC · JS player</th><th>pixel diff</th><th>RC · embedded player</th><th>pixel diff</th></tr>`
    : `<tr><th>preview</th><th>baked PNG</th><th>RC · JS player</th><th>pixel diff</th></tr>`;

  const body =
    rows.length === 0
      ? `<p class="empty">This catalog ships no Remote Compose documents (<code>ir/*.rc</code>), so there is nothing to compare.</p>`
      : `<table class="grid">
  <thead>${head}</thead>
  <tbody>
${rows.map((r) => rowHtml(r, withEmbedded)).join("\n")}
  </tbody>
</table>`;

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)} — PNG vs Remote Compose${withEmbedded ? " (JS + embedded players)" : " (JS player)"}</title>
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
  th.meta { text-align:left; vertical-align:top; padding:12px 8px; width:200px; }
  .name { font-weight:600; word-break:break-word; }
  .group { color:var(--muted); font-size:12px; margin-top:2px; }
  .scoreline { margin-top:8px; display:flex; align-items:center; gap:6px; flex-wrap:wrap; }
  .scorelabel { color:var(--muted); font-size:11px; text-transform:uppercase; letter-spacing:.04em; min-width:56px; }
  .score { display:inline-block; padding:2px 8px; border-radius:999px; font-variant-numeric:tabular-nums; font-weight:600; }
  .score.good { background:#1a7f37; color:#fff; }
  .score.ok   { background:#9a6700; color:#fff; }
  .score.bad  { background:#b32424; color:#fff; }
  .score.na   { background:#555; color:#fff; }
  .px { display:inline-block; color:var(--muted); font-size:12px; font-variant-numeric:tabular-nums; }
  .dims { display:inline-block; margin-top:8px; color:var(--muted); font-size:12px; }
  .blanknote { margin-top:6px; color:var(--muted); font-size:11px; line-height:1.3; }
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
  <h1>${esc(title)} — PNG vs Remote Compose <span style="font-weight:400;color:var(--muted)">(${
    withEmbedded ? "JS + embedded players" : "client-side JS player"
  })</span></h1>
  <div class="summary">${summary}</div>
  ${genNote}
</header>
<p class="lede">Each preview's baked <strong>PNG</strong> (the offline Robolectric/Skiko render) next to the
same <code>ir/*.rc</code> document as each player renders it, with a per-pixel diff after each.
${
  withEmbedded
    ? `The <strong>JS player</strong> is the vendored TypeScript <code>RC.RcdPlayer</code> on a
<code>&lt;canvas&gt;</code>; the <strong>embedded player</strong> is AndroidX's
<code>RcPlayer</code>, which interprets the document into Compose layout and draw nodes rather than
painting into an Android <code>View</code> the way <code>remote-player-view</code> does — so the two
diverge wherever that difference shows.`
    : `The player is the vendored TypeScript <code>RC.RcdPlayer</code> on a <code>&lt;canvas&gt;</code>.`
}
Mismatch % is the fraction of pixels each diff flags; rows sort worst-match-first on the worse of the
two players. A preview whose baked PNG is <strong>fully transparent</strong> is shown but not scored:
with nothing in the reference, a player that draws nothing would score a perfect 0% — so those rows
read <code>no reference</code> and stay out of the means.</p>
<div class="wrap">
${body}
</div>
</body>
</html>
`;
}
