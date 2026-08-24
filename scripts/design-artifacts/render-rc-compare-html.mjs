/**
 * Render a self-contained `rc-compare.html` for a design-artifact catalog that
 * ships Remote Compose documents (`ir/<id>.rc`): every preview on one row, with
 * **one column per player** — the baked **PNG** (the Robolectric/Skiko render,
 * source of truth) alongside the same document as each Remote Compose player
 * renders it.
 *
 * The page **does not diff by default**. It opens as a plain side-by-side wall of
 * every player's render, which is what you want when the question is "what does
 * each player draw?". Diffing is opt-in: pick **one** column as the reference in
 * the toolbar and every other column grows a pixel diff beneath its render plus a
 * mismatch chip in the row's meta cell. Picking the baked lane reuses the diffs the
 * driver already computed at build time (exact `pixelmatch` numbers, no work in
 * the browser); picking any *player* as the reference — e.g. "how far is cmp-wasm
 * from cmp-jvm?", a question the build-time lane-vs-baked diffs cannot answer —
 * diffs client-side on a `<canvas>` with pixelmatch's YIQ metric at the same
 * threshold.
 *
 * This is the RC counterpart of `render-compare-html.mjs` (PNG↔figma-svg). This
 * module stays a pure string emitter over the driver's result model — it never
 * touches a browser or the filesystem — so it remains unit-testable with a
 * hand-built model; the interactive part is the plain-JS snippet it inlines,
 * driven by the row model it also inlines as JSON.
 *
 * Player lanes, each its own column and its own build-time mismatch % against the
 * baked render (the summary header keeps reporting those, since they are the
 * recorded parity numbers):
 *
 * * **JS player** — the vendored TypeScript `RC.RcdPlayer` on a `<canvas>`, the
 *   browser render lane.
 * * **AndroidX Embedded · vendored Android** — the vendored AndroidX `RcPlayer`
 *   (`:third-party-rc-embedded-player`), a pure-Compose interpreter of the same
 *   document. This is the lane that differs from `remote-player-view`'s
 *   `RemoteComposePlayer` (an Android `View` painting to a framework `Canvas`),
 *   so it shows what a host embedding RC content *inside* a Compose tree gets.
 * * **cmp-jvm / cmp-wasm** — optional Compose Multiplatform / Skiko player lanes
 *   on desktop and in browser Wasm.
 *
 * A row is kept even when only one player could render it — the per-player
 * `rendered` flags are independent, and a player that could not decode the
 * document shows its note in place of the image.
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
 *       referenceBlank,      // true when the baked render is fully transparent — see below
 *
 *       embeddedRendered,    // false when the embedded player could not render it
 *       embeddedNote,        // optional reason when !embeddedRendered
 *       embeddedMismatchPct, // 0..100, null when !embeddedRendered
 *       embeddedMismatchPx,  // integer, null when !embeddedRendered
 *       embedded,            // out-relative path to the embedded render ('' when absent)
 *       embeddedDiff,        // out-relative path to its diff ('' when absent)
 *       androidxEmbeddedRendered, androidxEmbeddedNote,
 *       androidxEmbeddedMismatchPct, androidxEmbeddedMismatchPx,
 *       androidxEmbedded, androidxEmbeddedDiff, // same fields for the androidx.dev player
 *     }],
 *   }
 *
 * The embedded/cmp-jvm/cmp-wasm fields are optional: a model without them (an
 * older summary, or a run where the lane was skipped) omits that column entirely
 * rather than showing it empty, and the lane never appears in the reference picker.
 *
 * `referenceBlank` marks a preview whose baked render carries no opaque pixel at all
 * (a capture that produced nothing). Both sides flatten onto the same neutral
 * background before diffing, so a player that also draws nothing scores an exact
 * 0.00% — a green "good" band for a comparison that never happened. Such rows are
 * shown (the blank baked capture is itself the finding) but excluded from every
 * mean, sorted with the unrenderable rows, and score `no reference` whenever the
 * the baked lane is the selected reference. Choosing another player as the reference scores
 * them normally: two player renders are a real comparison even when the baked
 * capture is empty.
 */
import { BG } from "./rc-compare-pixels.mjs";

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

/** True when the androidx.dev embedded-player harness ran. */
export function hasAndroidxEmbeddedLane(rows = []) {
  return rows.some((r) => r.androidxEmbeddedRendered !== undefined || r.androidxEmbedded);
}

/** True when any row carries a cmp-jvm result, i.e. the desktop lane ran at all. */
export function hasEmbeddedJvmLane(rows = []) {
  return rows.some((r) => r.embeddedJvmRendered !== undefined || r.embeddedJvm);
}

/** True when any row carries a CMP/Wasm browser-player result. */
export function hasCmpWasmLane(rows = []) {
  return rows.some((r) => r.cmpWasmRendered !== undefined || r.cmpWasm);
}

/**
 * The player lanes the page can show, in column order. `baked` is not a player — it is the
 * reference the driver scored everything against — but it is a first-class column and a first-class
 * *reference choice*, so it lives in the same list.
 *
 * Each lane knows how to pull its own fields out of a row, which is what keeps `rowHtml` and the
 * client model from repeating the four near-identical field families the model carries.
 */
const LANES = [
  {
    id: "baked",
    // The catalog's own capture, rendered offline under Robolectric/Skiko. Named for the player
    // rather than the file it arrives as: "baked PNG" said how it got here, not what drew it.
    // That player is now the embedded `RcPlayer`: `RemoteOverridablePreview` defaults to
    // `RemoteComposePlayerKind.EMBEDDED`, so a capture goes through it unless the preview pins the
    // view-backed lane with `RemoteViewPreviewWrapper`. Neither the bundle nor the summary records
    // which renderer produced a row, so a catalog whose previews mix the two is mislabelled here
    // rather than detected. Carry provenance per row before scoring such a catalog.
    label: "AndroidX Embedded · baked",
    short: "baked",
    always: true,
    src: (r) => r.baked,
    rendered: (r) => Boolean(r.baked),
    note: () => "",
  },
  {
    id: "js",
    label: "RC · JS player",
    short: "js",
    present: () => true,
    src: (r) => r.rc,
    diff: (r) => r.diff,
    rendered: (r) => Boolean(r.rendered),
    pct: (r) => r.mismatchPct,
    px: (r) => r.mismatchPx,
    note: (r) => r.note,
  },
  {
    id: "embedded",
    // AndroidX's `RcPlayer`, vendored here as `:third-party-rc-embedded-player`, rasterized by
    // this repo's own Robolectric harness rather than by the catalog's capture. Same player as the
    // baked lane now draws with, so this column is a harness-vs-harness check on that player.
    label: "AndroidX Embedded · vendored Android",
    short: "vendored",
    present: hasEmbeddedLane,
    src: (r) => r.embedded,
    diff: (r) => r.embeddedDiff,
    rendered: (r) => Boolean(r.embeddedRendered),
    pct: (r) => r.embeddedMismatchPct,
    px: (r) => r.embeddedMismatchPx,
    note: (r) => r.embeddedNote,
  },
  {
    id: "androidx-embedded",
    label: "AndroidX Embedded · androidx.dev",
    short: "androidx.dev",
    present: hasAndroidxEmbeddedLane,
    src: (r) => r.androidxEmbedded,
    diff: (r) => r.androidxEmbeddedDiff,
    rendered: (r) => Boolean(r.androidxEmbeddedRendered),
    pct: (r) => r.androidxEmbeddedMismatchPct,
    px: (r) => r.androidxEmbeddedMismatchPx,
    note: (r) => r.androidxEmbeddedNote,
  },
  {
    id: "cmp-jvm",
    label: "RC · cmp-jvm player",
    short: "cmp-jvm",
    present: hasEmbeddedJvmLane,
    src: (r) => r.embeddedJvm,
    diff: (r) => r.embeddedJvmDiff,
    rendered: (r) => Boolean(r.embeddedJvmRendered),
    pct: (r) => r.embeddedJvmMismatchPct,
    px: (r) => r.embeddedJvmMismatchPx,
    note: (r) => r.embeddedJvmNote,
  },
  {
    id: "cmp-wasm",
    label: "RC · cmp-wasm player",
    short: "cmp-wasm",
    present: hasCmpWasmLane,
    src: (r) => r.cmpWasm,
    diff: (r) => r.cmpWasmDiff,
    rendered: (r) => Boolean(r.cmpWasmRendered),
    pct: (r) => r.cmpWasmMismatchPct,
    px: (r) => r.cmpWasmMismatchPx,
    note: (r) => r.cmpWasmNote,
    error: (r) => r.cmpWasmError,
  },
];

/** The lanes this model actually carries — `baked` + JS always, the optional players when they ran. */
export function activeLanes(rows = []) {
  return LANES.filter((lane) => lane.always || lane.present(rows));
}

/**
 * Aggregate stats over the rows — mean mismatch across *scored* rows, counts.
 *
 * These are the **build-time** numbers: every lane against the baked render, computed by the driver.
 * The page's reference picker re-scores rows in the browser, but the header keeps reporting these,
 * because they are what the run recorded and what the summary JSON and the CI gate use.
 *
 * A row whose baked render is fully transparent (`referenceBlank`) is rendered but **not scored**: with
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

  const androidxEmbRendered = rows.filter((r) => r.androidxEmbeddedRendered);
  const androidxEmbScored = androidxEmbRendered.filter((r) => !r.referenceBlank);
  const androidxEmbMeanPct =
    androidxEmbScored.length === 0
      ? null
      : androidxEmbScored.reduce((s, r) => s + (r.androidxEmbeddedMismatchPct ?? 0), 0) /
        androidxEmbScored.length;

  // cmp-jvm lane, summarized the same independent way as the embedded lane.
  const jvmRendered = rows.filter((r) => r.embeddedJvmRendered);
  const jvmScored = jvmRendered.filter((r) => !r.referenceBlank);
  const jvmMeanPct =
    jvmScored.length === 0
      ? null
      : jvmScored.reduce((s, r) => s + (r.embeddedJvmMismatchPct ?? 0), 0) / jvmScored.length;

  const wasmRendered = rows.filter((r) => r.cmpWasmRendered);
  const wasmScored = wasmRendered.filter((r) => !r.referenceBlank);
  const wasmMeanPct =
    wasmScored.length === 0
      ? null
      : wasmScored.reduce((s, r) => s + (r.cmpWasmMismatchPct ?? 0), 0) / wasmScored.length;

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
    androidxEmbeddedRendered: androidxEmbRendered.length,
    androidxEmbeddedScored: androidxEmbScored.length,
    androidxEmbeddedUnsupported: hasAndroidxEmbeddedLane(rows)
      ? rows.length - androidxEmbRendered.length
      : 0,
    androidxEmbeddedMeanPct: androidxEmbMeanPct,
    embeddedJvmRendered: jvmRendered.length,
    embeddedJvmScored: jvmScored.length,
    embeddedJvmUnsupported: hasEmbeddedJvmLane(rows) ? rows.length - jvmRendered.length : 0,
    embeddedJvmMeanPct: jvmMeanPct,
    cmpWasmRendered: wasmRendered.length,
    cmpWasmScored: wasmScored.length,
    cmpWasmUnsupported: hasCmpWasmLane(rows) ? rows.length - wasmRendered.length : 0,
    cmpWasmMeanPct: wasmMeanPct,
  };
}

/**
 * Worst score on a row — the worst of the players that ran, so a row where only one lane diverges
 * still sorts to the top rather than hiding behind a clean JS render. Returns null when no player
 * produced a render.
 */
function worstPct(r) {
  // An unscorable row has no percentage to sort on — it sinks with the unrenderable ones rather
  // than sitting at the top of the table on a 0% it never earned.
  if (r.referenceBlank) return null;
  const scores = [];
  if (r.rendered) scores.push(r.mismatchPct ?? 0);
  if (r.embeddedRendered) scores.push(r.embeddedMismatchPct ?? 0);
  if (r.androidxEmbeddedRendered) scores.push(r.androidxEmbeddedMismatchPct ?? 0);
  if (r.embeddedJvmRendered) scores.push(r.embeddedJvmMismatchPct ?? 0);
  if (r.cmpWasmRendered) scores.push(r.cmpWasmMismatchPct ?? 0);
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

/**
 * One lane's cell: the render, or the reason there isn't one. The diff slot below it stays empty
 * until a reference is picked — that is what "doesn't diff by default" means structurally, rather
 * than a diff that is merely collapsed.
 */
function laneCell(row, lane) {
  const src = lane.src(row) || "";
  const note = lane.note(row) || "";
  const errorHref = lane.error ? lane.error(row) || "" : "";
  const body = src
    ? `<img loading="lazy" src="${esc(src)}" alt="${esc(lane.label)}">`
    : `<div class="missing">${esc(note || "—")}${
        errorHref ? ` <a href="${esc(errorHref)}">details</a>` : ""
      }</div>`;
  return `<figure class="cell lane-${esc(lane.id)}" data-lane="${esc(lane.id)}">
      <figcaption>${esc(lane.label)}<span class="refbadge">reference</span></figcaption>
      ${body}
      <div class="diffslot" hidden></div>
    </figure>`;
}

function rowHtml(r, lanes, index) {
  const dims = r.width && r.height ? `<span class="dims">${r.width}×${r.height}</span>` : "";
  const anyRendered = lanes.some((lane) => lane.id !== "baked" && lane.rendered(r));
  const cells = lanes.map((lane) => `<td>${laneCell(r, lane)}</td>`).join("");
  const scorable = !r.referenceBlank;
  return `<tr class="row ${anyRendered ? "rendered" : "unsupported"}${
    r.referenceBlank ? " blank-reference" : ""
  }" data-row="${index}" data-pct="${scorable && r.rendered ? (r.mismatchPct ?? 0) : ""}" data-embedded-pct="${
    scorable && r.embeddedRendered ? (r.embeddedMismatchPct ?? 0) : ""
  }" data-androidx-embedded-pct="${
    scorable && r.androidxEmbeddedRendered ? (r.androidxEmbeddedMismatchPct ?? 0) : ""
  }" data-embedded-jvm-pct="${
    scorable && r.embeddedJvmRendered ? (r.embeddedJvmMismatchPct ?? 0) : ""
  }" data-cmp-wasm-pct="${
    scorable && r.cmpWasmRendered ? (r.cmpWasmMismatchPct ?? 0) : ""
  }">
  <th class="meta">
    <div class="name">${esc(r.name)}</div>
    ${r.group ? `<div class="group">${esc(r.group)}</div>` : ""}
    <div class="scores" data-scores></div>
    ${
      r.referenceBlank
        ? `<div class="blanknote">the baked render is fully transparent — nothing to compare against</div>`
        : ""
    }
    ${dims}
  </th>${cells}
</tr>`;
}

/**
 * The row model the inlined script diffs over: per lane, where its render lives, what the driver
 * already measured against the baked render, and whether it rendered at all. Keeping it as data (rather
 * than scraping the DOM) is what lets the client pick the exact build-time diff when the reference
 * *is* the baked lane and fall back to canvas only when it isn't.
 */
function clientModel(rows, lanes) {
  return {
    lanes: lanes.map((lane) => ({ id: lane.id, label: lane.label, short: lane.short })),
    rows: rows.map((r) => {
      const laneData = {};
      for (const lane of lanes) {
        laneData[lane.id] = {
          src: lane.src(r) || "",
          diff: lane.diff ? lane.diff(r) || "" : "",
          pct: lane.pct ? (lane.pct(r) ?? null) : null,
          px: lane.px ? (lane.px(r) ?? null) : null,
          rendered: lane.rendered(r),
          note: lane.note(r) || "",
        };
      }
      return { name: r.name ?? "", referenceBlank: Boolean(r.referenceBlank), lanes: laneData };
    }),
  };
}

/** Inline JSON that a `</script>` inside a note can't break out of. */
function jsonScript(id, value) {
  return `<script type="application/json" id="${id}">${JSON.stringify(value).replace(
    /</g,
    "\\u003c",
  )}</script>`;
}

/**
 * The interactive layer, inlined so the page stays a single self-contained file that works from a
 * `file://` open or a static host with no build step.
 *
 * Two diff paths, because they have very different costs and fidelities:
 *
 *  * reference = `baked` → the driver already diffed every lane against it with `pixelmatch`. Use
 *    those PNGs and those percentages: exact, free, and available even when canvas readback is
 *    blocked (a `file://` open taints the canvas, so `getImageData` throws).
 *  * reference = any player → nothing precomputed can answer it, so diff in the browser. The metric
 *    is pixelmatch's: YIQ colour distance against `threshold² · 35215`, minus the anti-aliasing
 *    detection, which makes browser numbers a touch *higher* than the driver's on text-heavy
 *    previews. The page says so rather than pretending they're interchangeable.
 *
 * Work is per-row and lazy (an IntersectionObserver kicks a row off when it scrolls in), and every
 * pass carries a token so switching references mid-scroll abandons the previous pass instead of
 * racing it.
 */
function clientScript(threshold) {
  return `<script>
(() => {
  const MODEL = JSON.parse(document.getElementById("rc-model").textContent);
  const THRESHOLD = ${JSON.stringify(threshold)};
  const MAX_DELTA = 35215; // pixelmatch's maximum YIQ difference, for the threshold scale
  // The neutral the build-time diffs flatten onto (rc-compare-pixels.mjs BG), as a CSS colour.
  const BG_CSS = ${JSON.stringify(`rgb(${BG[0]}, ${BG[1]}, ${BG[2]})`)};
  const select = document.getElementById("refselect");
  const status = document.getElementById("refstatus");
  const rows = Array.from(document.querySelectorAll("tr.row"));
  let token = 0;
  let tainted = false;

  const laneLabel = (id) => (MODEL.lanes.find((l) => l.id === id) || {}).short || id;
  const band = (pct) => (pct == null ? "na" : pct < 2 ? "good" : pct < 10 ? "ok" : "bad");

  function chip(label, text, pct, px) {
    const line = document.createElement("div");
    line.className = "scoreline";
    const name = document.createElement("span");
    name.className = "scorelabel";
    name.textContent = label;
    const score = document.createElement("span");
    score.className = "score " + band(pct);
    score.textContent = text;
    line.append(name, score);
    if (px != null) {
      const pxEl = document.createElement("span");
      pxEl.className = "px";
      pxEl.textContent = px.toLocaleString("en-US") + " px";
      line.append(pxEl);
    }
    return line;
  }

  function clear(row) {
    row.querySelector("[data-scores]").replaceChildren();
    for (const slot of row.querySelectorAll(".diffslot")) {
      slot.replaceChildren();
      slot.hidden = true;
    }
    for (const cell of row.querySelectorAll("figure.cell")) cell.classList.remove("is-reference");
  }

  function showDiff(row, laneId, src) {
    const slot = row.querySelector('figure.cell[data-lane="' + laneId + '"] .diffslot');
    if (!slot) return;
    const caption = document.createElement("div");
    caption.className = "difflabel";
    caption.textContent = "pixel diff vs " + laneLabel(select.value);
    const img = document.createElement("img");
    img.loading = "lazy";
    img.src = src;
    img.alt = "pixel diff";
    slot.replaceChildren(caption, img);
    slot.hidden = false;
  }

  const images = new Map();
  function load(src) {
    if (!images.has(src)) {
      images.set(
        src,
        new Promise((resolve, reject) => {
          const img = new Image();
          img.onload = () => resolve(img);
          img.onerror = () => reject(new Error("could not load " + src));
          img.src = src;
        }),
      );
    }
    return images.get(src);
  }

  /**
   * The image's pixels flattened onto the diff neutral, which is what makes [delta] meaningful.
   *
   * The published lane PNGs are the players' own captures, alpha and all — a sticker on
   * transparency, like the baked PNG. Reading them raw would score RGB the viewer never sees: a
   * transparent black pixel and an opaque black one are the same three channels, so a coverage
   * difference could vanish. Painting the neutral first and letting the draw composite over it is
   * the canvas equivalent of the build-time flattenOnto(BG), so a client-side player-vs-player
   * score means the same thing as the driver's lane-vs-baked one.
   */
  function pixels(img) {
    const canvas = document.createElement("canvas");
    canvas.width = img.naturalWidth;
    canvas.height = img.naturalHeight;
    const ctx = canvas.getContext("2d", { willReadFrequently: true });
    ctx.fillStyle = BG_CSS;
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(img, 0, 0);
    return ctx.getImageData(0, 0, canvas.width, canvas.height);
  }

  /** pixelmatch's YIQ metric, without its anti-aliasing pass. Both sides are already opaque. */
  function delta(a, b, i) {
    const y = (p, o) => p[o] * 0.29889531 + p[o + 1] * 0.58662247 + p[o + 2] * 0.11448223;
    const q = (p, o) => p[o] * 0.59597799 - p[o + 1] * 0.2741761 - p[o + 2] * 0.32180189;
    const v = (p, o) => p[o] * 0.21147017 - p[o + 1] * 0.52261711 + p[o + 2] * 0.31114694;
    const dy = y(a, i) - y(b, i);
    const di = q(a, i) - q(b, i);
    const dq = v(a, i) - v(b, i);
    return 0.5053 * dy * dy + 0.299 * di * di + 0.1957 * dq * dq;
  }

  function diff(refData, laneData) {
    const { width, height } = refData;
    const out = new ImageData(width, height);
    const limit = THRESHOLD * THRESHOLD * MAX_DELTA;
    let count = 0;
    for (let i = 0; i < refData.data.length; i += 4) {
      if (delta(refData.data, laneData.data, i) > limit) {
        out.data[i] = 255;
        out.data[i + 1] = 60;
        out.data[i + 2] = 60;
        out.data[i + 3] = 255;
        count++;
      } else {
        // pixelmatch's washed-out backdrop: the reference in grey at 10% so the flagged pixels read.
        const grey =
          255 +
          (refData.data[i] * 0.29889531 +
            refData.data[i + 1] * 0.58662247 +
            refData.data[i + 2] * 0.11448223 -
            255) *
            0.1;
        out.data[i] = out.data[i + 1] = out.data[i + 2] = grey;
        out.data[i + 3] = 255;
      }
    }
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    canvas.getContext("2d").putImageData(out, 0, 0);
    return { count, total: width * height, url: canvas.toDataURL("image/png") };
  }

  async function scoreRow(row, ref, pass) {
    const model = MODEL.rows[Number(row.dataset.row)];
    if (!model) return;
    const scores = row.querySelector("[data-scores]");
    const refLane = model.lanes[ref];
    row
      .querySelector('figure.cell[data-lane="' + ref + '"]')
      ?.classList.add("is-reference");
    if (!refLane || !refLane.rendered) {
      scores.replaceChildren(chip(laneLabel(ref), "no reference", null, null));
      return;
    }
    // A blank baked capture is no reference at all — but two *player* renders still compare, so the
    // short-circuit is scoped to the baked lane rather than the whole row.
    if (model.referenceBlank && ref === "baked") {
      for (const lane of MODEL.lanes) {
        if (lane.id === ref) continue;
        scores.append(chip(lane.short, "no reference", null, null));
      }
      return;
    }
    let refData = null;
    for (const lane of MODEL.lanes) {
      if (lane.id === ref) continue;
      const data = model.lanes[lane.id];
      if (!data) continue;
      if (!data.rendered || !data.src) {
        scores.append(chip(lane.short, data.note || "no render", null, null));
        continue;
      }
      // Build-time fast path: the driver's own pixelmatch result against the baked render.
      if (ref === "baked" && data.diff && data.pct != null) {
        scores.append(chip(lane.short, data.pct.toFixed(2) + "%", data.pct, data.px));
        showDiff(row, lane.id, data.diff);
        continue;
      }
      if (tainted) {
        scores.append(chip(lane.short, "diff needs http://", null, null));
        continue;
      }
      try {
        if (!refData) refData = pixels(await load(refLane.src));
        const laneData = pixels(await load(data.src));
        if (pass !== token) return;
        if (laneData.width !== refData.width || laneData.height !== refData.height) {
          scores.append(
            chip(
              lane.short,
              laneData.width + "×" + laneData.height + " ≠ " + refData.width + "×" + refData.height,
              null,
              null,
            ),
          );
          continue;
        }
        const result = diff(refData, laneData);
        if (pass !== token) return;
        const pct = (100 * result.count) / result.total;
        scores.append(chip(lane.short, pct.toFixed(2) + "%", pct, result.count));
        showDiff(row, lane.id, result.url);
      } catch (error) {
        // A file:// open taints the canvas, so readback throws for every row. Say it once, in the
        // toolbar, instead of writing the same failure into every chip.
        if (String(error).includes("SecurityError") || error.name === "SecurityError") {
          tainted = true;
          status.textContent =
            "client-side diffing needs the page served over http:// (canvas readback is blocked on file://)";
        }
        scores.append(chip(lane.short, tainted ? "diff needs http://" : "diff failed", null, null));
      }
    }
  }

  const pending = new WeakMap();
  const observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        const row = entry.target;
        const ref = select.value;
        if (ref === "none" || pending.get(row) === token) continue;
        pending.set(row, token);
        scoreRow(row, ref, token);
      }
    },
    { rootMargin: "400px 0px" },
  );

  function apply() {
    token++;
    const ref = select.value;
    document.body.dataset.reference = ref;
    // Unconditionally, *before* re-observing: observe() on an already-observed target is a no-op, so
    // switching straight from one reference to another would leave every on-screen row blank until it
    // scrolled out and back. Disconnecting first makes the re-observe queue a fresh initial callback.
    observer.disconnect();
    for (const row of rows) {
      clear(row);
      pending.delete(row);
    }
    if (ref === "none") {
      status.textContent = "";
      return;
    }
    status.textContent =
      ref === "baked"
        ? "showing the build-time pixelmatch diffs against the baked render"
        : "diffing in the browser against " + laneLabel(ref) + " — no anti-aliasing pass, so text-heavy previews read slightly higher than the build-time numbers";
    for (const row of rows) observer.observe(row);
  }

  select.addEventListener("change", apply);
  apply();
})();
</script>`;
}

export function renderRcCompareHtml(model, opts = {}) {
  const system = model.system ?? "";
  const title = model.title ?? system;
  const allRows = model.rows ?? [];
  const rows = sortRows(allRows);
  const stats = summarizeRcCompare(allRows);
  const meanTxt = stats.meanPct == null ? "n/a" : `${stats.meanPct.toFixed(2)}%`;
  const genNote = opts.generatedNote ? `<span class="note">${esc(opts.generatedNote)}</span>` : "";
  const threshold = opts.threshold ?? 0.1;

  const withEmbedded = hasEmbeddedLane(allRows);
  const withAndroidxEmbedded = hasAndroidxEmbeddedLane(allRows);
  const withEmbeddedJvm = hasEmbeddedJvmLane(allRows);
  const withCmpWasm = hasCmpWasmLane(allRows);
  const lanes = activeLanes(allRows);
  // "JS", "JS + embedded", "JS + embedded + cmp-jvm", … — the players this page actually shows.
  const laneNames = [
    "JS",
    withEmbedded && "embedded",
    withAndroidxEmbedded && "androidx.dev embedded",
    withEmbeddedJvm && "cmp-jvm",
    withCmpWasm && "cmp-wasm",
  ].filter(Boolean);
  const laneLabel = `${laneNames.join(" + ")} player${laneNames.length > 1 ? "s" : ""}`;
  const embMeanTxt =
    stats.embeddedMeanPct == null ? "n/a" : `${stats.embeddedMeanPct.toFixed(2)}%`;
  const androidxEmbMeanTxt =
    stats.androidxEmbeddedMeanPct == null
      ? "n/a"
      : `${stats.androidxEmbeddedMeanPct.toFixed(2)}%`;
  const jvmMeanTxt =
    stats.embeddedJvmMeanPct == null ? "n/a" : `${stats.embeddedJvmMeanPct.toFixed(2)}%`;
  const wasmMeanTxt =
    stats.cmpWasmMeanPct == null ? "n/a" : `${stats.cmpWasmMeanPct.toFixed(2)}%`;

  // Blank references are called out once, not per lane — the reference is shared, so a blank one
  // costs every player the same row.
  const blankTxt = stats.blankReference
    ? ` · <strong>${stats.blankReference}</strong> unscored (blank reference)`
    : "";

  const summary =
    `<strong>JS player:</strong> ${stats.scored} scored · mean mismatch <strong>${meanTxt}</strong>` +
    (stats.unsupported ? ` · ${stats.unsupported} not decodable` : "") +
    blankTxt +
    (withEmbedded
      ? `<br><strong>AndroidX Embedded · vendored Android:</strong> ${stats.embeddedScored} scored · mean mismatch <strong>${embMeanTxt}</strong>` +
        (stats.embeddedUnsupported ? ` · ${stats.embeddedUnsupported} not rendered` : "") +
        blankTxt
      : "") +
    (withAndroidxEmbedded
      ? `<br><strong>AndroidX Embedded · androidx.dev:</strong> ${stats.androidxEmbeddedScored} scored · mean mismatch <strong>${androidxEmbMeanTxt}</strong>` +
        (stats.androidxEmbeddedUnsupported
          ? ` · ${stats.androidxEmbeddedUnsupported} not rendered`
          : "") +
        blankTxt
      : "") +
    (withEmbeddedJvm
      ? `<br><strong>cmp-jvm player:</strong> ${stats.embeddedJvmScored} scored · mean mismatch <strong>${jvmMeanTxt}</strong>` +
        (stats.embeddedJvmUnsupported ? ` · ${stats.embeddedJvmUnsupported} not rendered` : "") +
        blankTxt
      : "") +
    (withCmpWasm
      ? `<br><strong>cmp-wasm player:</strong> ${stats.cmpWasmScored} scored · mean mismatch <strong>${wasmMeanTxt}</strong>` +
        (stats.cmpWasmUnsupported ? ` · ${stats.cmpWasmUnsupported} not rendered` : "") +
        blankTxt
      : "");

  // One column per lane — no standalone diff columns any more. A diff appears *inside* the column of
  // whichever player is being compared, and only once a reference is picked.
  const head = `<tr><th>preview</th>${lanes.map((l) => `<th>${esc(l.label)}</th>`).join("")}</tr>`;

  const picker = `<div class="toolbar">
    <label for="refselect">Diff against</label>
    <select id="refselect">
      <option value="none" selected>nothing (show renders only)</option>
${lanes.map((l) => `      <option value="${esc(l.id)}">${esc(l.label)}</option>`).join("\n")}
    </select>
    <span id="refstatus" class="refstatus"></span>
  </div>`;

  const body =
    rows.length === 0
      ? `<p class="empty">This catalog ships no Remote Compose documents (<code>ir/*.rc</code>), so there is nothing to compare.</p>`
      : `<table class="grid">
  <thead>${head}</thead>
  <tbody>
${rows.map((r, i) => rowHtml(r, lanes, i)).join("\n")}
  </tbody>
</table>`;

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)} — PNG vs Remote Compose (${esc(laneLabel)})</title>
<style>
  :root { color-scheme: light dark; --bg:#fff; --fg:#111; --muted:#666; --line:#e2e2e2; --card:#fafafa; --accent:#0969da; }
  @media (prefers-color-scheme: dark) {
    :root { --bg:#0e0e0e; --fg:#eee; --muted:#9a9a9a; --line:#2a2a2a; --card:#161616; --accent:#4493f8; }
  }
  * { box-sizing: border-box; }
  body { margin:0; font:14px/1.4 system-ui, sans-serif; color:var(--fg); background:var(--bg); }
  header { padding:16px 20px; border-bottom:1px solid var(--line); position:sticky; top:0; background:var(--bg); z-index:2; }
  h1 { margin:0 0 4px; font-size:18px; }
  .summary { color:var(--muted); }
  .note { display:block; margin-top:4px; color:var(--muted); font-size:12px; }
  .toolbar { margin-top:10px; display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
  .toolbar label { font-size:12px; text-transform:uppercase; letter-spacing:.04em; color:var(--muted); }
  .toolbar select { font:inherit; padding:3px 6px; border:1px solid var(--line); border-radius:6px; background:var(--card); color:var(--fg); }
  .refstatus { color:var(--muted); font-size:12px; }
  .lede { padding:12px 20px; color:var(--muted); max-width:70ch; }
  .wrap { overflow-x:auto; padding:0 20px 40px; }
  table.grid { border-collapse:collapse; width:100%; min-width:720px; }
  /* Not sticky: the .wrap overflow-x makes it the scrollport these cells would stick to, and it
     never scrolls vertically — so the old top:64px only ever parked them behind the header. Every
     cell repeats its lane in its own figcaption, so scrolling never loses which column is which. */
  thead th { text-align:left; font-size:12px; text-transform:uppercase; letter-spacing:.04em; color:var(--muted); padding:12px 8px; border-bottom:1px solid var(--line); background:var(--bg); }
  tbody tr { border-bottom:1px solid var(--line); }
  th.meta { text-align:left; vertical-align:top; padding:12px 8px; width:200px; }
  .name { font-weight:600; word-break:break-word; }
  .group { color:var(--muted); font-size:12px; margin-top:2px; }
  .scoreline { margin-top:8px; display:flex; align-items:flex-start; gap:6px; flex-wrap:wrap; }
  .scorelabel { color:var(--muted); font-size:11px; text-transform:uppercase; letter-spacing:.04em; min-width:56px; padding-top:2px; }
  .score { display:inline-block; min-width:0; max-width:100%; padding:2px 8px; border-radius:999px; font-variant-numeric:tabular-nums; font-weight:600; overflow-wrap:anywhere; }
  /* A reason ("player could not decode the document") is prose, not a score — it must not pretend to
     be a measurement, and it must wrap instead of bursting out of the pill. */
  .score.na { font-weight:400; font-size:11px; line-height:1.3; border-radius:6px; }
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
  /* Checkered on the *diff neutral* (#808080, the grey rc-compare-pixels.mjs flattens both sides
     onto), not on the page: the lane PNGs are stickers on transparency, and a light one — a white
     icon, a pale swatch — vanishes against a pale ground. The checker keeps transparency legible as
     transparency; the mid-grey keeps light and dark content contrasting the way the score does. */
  .cell img { display:block; max-width:280px; width:100%; height:auto; border:1px solid var(--line); border-radius:6px; background:
    repeating-conic-gradient(#8c8c8c 0% 25%, #747474 0% 50%) 50% / 20px 20px; }
  /* The chosen reference is called out rather than diffed against itself. */
  .refbadge { display:none; margin-left:6px; padding:1px 6px; border-radius:999px; background:var(--accent); color:#fff; font-size:10px; text-transform:uppercase; letter-spacing:.04em; }
  figure.cell.is-reference .refbadge { display:inline-block; }
  figure.cell.is-reference img { outline:2px solid var(--accent); outline-offset:1px; }
  .diffslot { margin-top:6px; }
  .difflabel { font-size:11px; color:var(--muted); margin-bottom:4px; }
  .diffslot img { display:block; max-width:280px; width:100%; height:auto; border:1px solid var(--line); border-radius:6px; background:#000; }
  .missing { min-width:120px; max-width:280px; min-height:80px; padding:8px; display:grid; place-items:center; text-align:center; color:var(--muted); font-size:11px; border:1px dashed var(--line); border-radius:6px; }
  .empty { padding:24px 20px; color:var(--muted); }
  code { background:var(--card); padding:1px 5px; border-radius:4px; }
</style>
</head>
<body>
<header>
  <h1>${esc(title)} — PNG vs Remote Compose <span style="font-weight:400;color:var(--muted)">(${esc(
    laneLabel,
  )})</span></h1>
  <div class="summary">${summary}</div>
  ${genNote}
  ${picker}
</header>
<p class="lede">Every preview across every player: the <strong>baked</strong> capture (the catalog's
own offline Robolectric/Skiko render, through AndroidX's embedded <code>RcPlayer</code> unless the
preview pins the view-backed lane) next to the same
<code>ir/*.rc</code> document as each player renders it.
${[
  `The <strong>JS player</strong> is the vendored TypeScript <code>RC.RcdPlayer</code> on a <code>&lt;canvas&gt;</code>`,
  withEmbedded &&
    `<strong>AndroidX Embedded · vendored Android</strong> is this repo's pinned and locally patched <code>RcPlayer</code>, rasterized by Robolectric`,
  withAndroidxEmbedded &&
    `<strong>AndroidX Embedded · androidx.dev</strong> is the independently compiled player published by the pinned AndroidX snapshot`,
  withEmbeddedJvm &&
    `the <strong>cmp-jvm player</strong> runs that same <code>RcPlayer</code> draw path on Compose Desktop / Skiko, rasterizing offscreen`,
  withCmpWasm &&
    `the <strong>cmp-wasm player</strong> runs the new Compose Multiplatform / Skiko player in browser Wasm`,
]
  .filter(Boolean)
  .join("; ")}${laneNames.length > 1 ? " — so they diverge wherever those differences show." : "."}
<strong>Nothing is diffed until you ask for it:</strong> pick a column in <em>Diff against</em> and every
other column grows a pixel diff plus a mismatch chip. Choosing the baked lane replays the build-time
<code>pixelmatch</code> diffs; choosing a player diffs in the browser, which is how you compare two
players directly. Rows sort worst-match-first${
    laneNames.length > 1 ? " on the worst-scoring player" : ""
  } using the build-time scores in the header. A preview whose baked render is
<strong>fully transparent</strong> is shown but not scored against it: with nothing in the reference, a
player that draws nothing would score a perfect 0% — so those rows read <code>no reference</code> and
stay out of the means.</p>
<div class="wrap">
${body}
</div>
${jsonScript("rc-model", clientModel(rows, lanes))}
${clientScript(threshold)}
</body>
</html>
`;
}
