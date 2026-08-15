/**
 * Write a published catalog's `references/` directory — the producer for the preview server's
 * **PNG ↔ Design reference** lane.
 *
 *     node emit-design-references.mjs --out <bundle dir> --repo <repo root> \
 *       [--design-map design-map.json] [--spec catalog.spec.json] \
 *       [--reference-images <dir>] [--chromium <path>] [--strict]
 *
 * `--out` is the staged bundle the workflow is about to force-push to `design-artifacts/<system>`;
 * this adds `references/index.json` plus one normalised PNG per reference and leaves the rest of it
 * alone. Absent a `design-map.json` it is a no-op, so every catalog can run it unconditionally.
 *
 * ## Where the pixels come from
 *
 * A design reference may start life as HTML, a Figma node, or a committed PNG, but the server reads
 * ONLY an inert PNG — it never executes an artifact and never follows `source.uri`. Normalising at
 * publish time is what keeps serving reproducible. Three inputs, in precedence order per entry:
 *
 * 1. `--reference-images <dir>` — a PNG named after the design-map `ref`'s basename. This is the
 *    escape hatch for anything this script can't render itself, and the way a repo that already
 *    rasterises its references in an earlier job (meshcore-mobile's design-parity workflow does)
 *    feeds them in rather than rendering twice.
 * 2. an `.html` ref — rasterised here with Playwright's Chromium at the design's own CSS size and
 *    density. The workflow has already installed the catalog's branded faces into fontconfig by
 *    this point, so the reference picks up the same typefaces the candidate rendered with; without
 *    that every glyph drifts and dominates the diff.
 * 3. a `figma:<fileKey>/<nodeId>` ref — rendered through the Figma REST images endpoint when a
 *    token is present (`FIGMA_TOKEN` / `FIGMA_PAT` / `FIGMA_ACCESS_TOKEN`). No token ⇒ the entry is
 *    skipped with a warning, which is the normal state for a fork or a PR run.
 *
 * Every raster is then placed on a canvas matching the catalog sticker and hashed, so the server
 * can verify it before advertising the reference. Figma components are exported at the Compose
 * renderer's density and centred without enlargement; other rasters within rounding distance are
 * resampled, while genuinely different proportions are fitted and letterboxed. See
 * png-resample.mjs for why those distinctions matter.
 *
 * ## Failure posture
 *
 * Fail-soft by default, like the server's own reader: an entry that can't be rasterised is dropped
 * from the manifest with a `::warning::` and the catalog publishes without it, because a reference
 * lane is an enhancement and must never cost a catalog its render. `--strict` turns any dropped
 * entry into a non-zero exit, for a repo that wants its parity coverage gated.
 */
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { PNG } from "pngjs";
import { chromium } from "playwright";

import { stripComments } from "./catalog-spec.mjs";
import {
  REFERENCES_DIR,
  derivationMismatches,
  planDesignReferences,
  referenceManifest,
} from "./design-references.mjs";
import { fitRgba, isRoundingDelta, placeRgba, resampleRgba } from "./png-resample.mjs";
import { layoutFromNode } from "@design-parity/adapter-figma";
import { scaleTree } from "./reference-layout.mjs";
import { withReferenceAnnotations } from "@design-parity/catalog-export";
import { FigmaRestRasterizer, parseFigmaRef } from "./figma-rest-raster.mjs";
import { openScorer } from "./design-reference-score.mjs";

function arg(name, def = undefined) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : def;
}

const OUT = arg("out");
const REPO = path.resolve(arg("repo", "."));
const DESIGN_MAP = arg("design-map", "design-map.json");
const SPEC = arg("spec", "catalog.spec.json");
const REFERENCE_IMAGES = arg("reference-images");
const EXEC = arg("chromium", process.env.DESIGN_REFERENCES_CHROMIUM || undefined);
const STRICT = process.argv.includes("--strict");
const FIGMA_CONTENTS_ONLY = arg("figma-contents-only", "true") !== "false";

const FIGMA_TOKEN =
  process.env.FIGMA_TOKEN || process.env.FIGMA_PAT || process.env.FIGMA_ACCESS_TOKEN || "";

if (!OUT) {
  console.error("emit-design-references: --out <bundle dir> is required");
  process.exit(2);
}

const warnings = [];
const warn = (message) => {
  warnings.push(message);
  console.log(`::warning::design-references: ${message}`);
};

// Coverage notes: everything a SECONDARY reference has to say. Kept out of [warnings] because
// `--strict` gates on those, and the primary/secondary contract is that a variant cell can go
// missing without failing the export — otherwise one unrenderable size cell costs the catalog every
// reference it did resolve, which is the trade this lane was capped by to begin with.
const notes = [];
const note = (message) => {
  notes.push(message);
  console.log(`design-references: note: ${message}`);
};

/**
 * Report something about ONE record, routed by its tier: a primary's trouble is a warning the
 * export can be gated on, a secondary's is a note. Every per-record report goes through here so the
 * routing cannot be forgotten at one call site and silently re-gate the run.
 */
const warnFor = (record, message) =>
  record?.tier === "secondary" ? note(message) : warn(message);

function readJson(file, { comments = false } = {}) {
  const text = fs.readFileSync(file, "utf8");
  return JSON.parse(comments ? stripComments(text) : text);
}

const designMapPath = path.resolve(REPO, DESIGN_MAP);
if (!fs.existsSync(designMapPath)) {
  console.log(`design-references: no ${DESIGN_MAP} in ${REPO}; nothing to publish`);
  process.exit(0);
}

const catalogPath = path.join(OUT, "catalog.json");
if (!fs.existsSync(catalogPath)) {
  console.error(`design-references: ${catalogPath} is missing — run after the catalog export`);
  process.exit(2);
}

// The spec is what joins a design-map code handle to a published sticker, so without it there is
// nothing to map. Warn and stop rather than throwing ENOENT: a caller that points `--spec` at a
// path this repo doesn't have must not take the catalog's publish down with it.
const specPath = path.resolve(REPO, SPEC);
if (!fs.existsSync(specPath)) {
  warn(`no catalog spec at ${SPEC}; cannot map design references without one`);
  process.exit(STRICT ? 1 : 0);
}

const catalog = readJson(catalogPath);
const spec = readJson(specPath, { comments: true });
const designMap = readJson(designMapPath);

// The serve preview id is derived by restating a Kotlin function; check that restatement against
// the deep links the exporter itself wrote before minting ids from it. A mismatch means the naming
// scheme moved, and every reference we'd publish would 404 on the box.
const drift = derivationMismatches(catalog);
if (drift.length > 0) {
  console.error(
    `design-references: serve preview-id derivation no longer matches the catalog export ` +
      `(${drift.length} image(s)); refusing to publish references. First: ${drift[0]}`,
  );
  process.exit(1);
}

const {
  records,
  warnings: planWarnings,
  notes: planNotes,
} = planDesignReferences({ designMap, spec, catalog });
planWarnings.forEach(warn);
// Secondary-coverage observations. Printed, never counted as warnings: `--strict` exists to stop a
// catalog publishing a WRONG primary, and a size cell the kit has no node for is neither wrong nor
// the component's problem. Failing the export over one would cost the catalog every reference it
// did resolve, which is the trade that capped this lane at one reference per component to begin
// with.
planNotes.forEach(note);

if (records.length === 0) {
  console.log("design-references: no design-map entry maps to a published sticker; nothing to do");
  process.exit(STRICT && warnings.length > 0 ? 1 : 0);
}

/** Read a PNG file into `{ width, height, data }`. */
function readPng(file) {
  const png = PNG.sync.read(fs.readFileSync(file));
  return { width: png.width, height: png.height, data: png.data };
}

/** Encode an RGBA raster to PNG bytes. */
function writePng({ width, height, data }) {
  const png = new PNG({ width, height });
  data.copy(png.data);
  return PNG.sync.write(png);
}

/**
 * The `width`/`height` (CSS px) and `deviceScaleFactor` an HTML reference should rasterise at.
 * A Claude Design export declares its own viewport (`<meta name="viewport" content="width=411">`)
 * and sizes `body` to the device it targets, so honour that and let the resample take up the
 * rounding. Falls back to the target sticker's pixel size at 1x for an export that declares
 * nothing.
 */
function htmlViewport(html, target) {
  const width = Number(/<meta[^>]+name="viewport"[^>]+width=(\d+)/.exec(html)?.[1]);
  const height = Number(/body\s*\{[^}]*height:\s*(\d+)px/.exec(html)?.[1]);
  if (!width || !height) return { width: target.width, height: target.height, scale: 1 };
  // Density is whatever maps the declared CSS size onto the sticker we must match.
  return { width, height, scale: target.width / width };
}

let browser;
async function rasterizeHtml(file, target) {
  const html = fs.readFileSync(file, "utf8");
  const { width, height, scale } = htmlViewport(html, target);
  browser ??= await chromium.launch({
    headless: true,
    ...(EXEC ? { executablePath: EXEC } : {}),
    args: ["--no-sandbox"],
  });
  const context = await browser.newContext({
    viewport: { width, height },
    deviceScaleFactor: scale,
  });
  const page = await context.newPage();
  try {
    await page.goto(`file://${file}`, { waitUntil: "networkidle" });
    // The branded faces are installed into fontconfig by the workflow, but the page only
    // *resolves* them asynchronously; screenshotting before they land bakes a fallback sans.
    await page.evaluate(() => document.fonts.ready);
    const bytes = await page.screenshot({ type: "png" });
    const png = PNG.sync.read(bytes);
    return { width: png.width, height: png.height, data: png.data };
  } finally {
    await context.close();
  }
}

const figmaRasterizers = new Map();

function referenceContentsOnly(record) {
  return record.origin?.referenceContentsOnly ?? FIGMA_CONTENTS_ONLY;
}

function figmaRasterizerFor(contentsOnly) {
  let rasterizer = figmaRasterizers.get(contentsOnly);
  if (!rasterizer) {
    rasterizer = new FigmaRestRasterizer({ token: FIGMA_TOKEN, contentsOnly });
    figmaRasterizers.set(contentsOnly, rasterizer);
  }
  return rasterizer;
}

/** A pre-rendered PNG for this ref under `--reference-images`, or null. */
function suppliedRaster(ref) {
  if (!REFERENCE_IMAGES || !ref) return null;
  const base = path.basename(String(ref)).replace(/\.[^.]+$/, "");
  for (const name of [`${base}.png`, `${path.basename(String(ref))}.png`]) {
    const file = path.resolve(REFERENCE_IMAGES, name);
    if (fs.existsSync(file)) return file;
  }
  return null;
}

/** Obtain the raw raster for one planned record, or null with a warning. */
async function sourceRaster(record) {
  const { source, ref } = record.origin;
  const target = targetFor(record);

  const supplied = suppliedRaster(ref);
  if (supplied) {
    const raster = readPng(supplied);
    return parseFigmaRef(ref) ? { ...raster, preserveScale: true } : raster;
  }

  if (typeof ref === "string" && ref.toLowerCase().endsWith(".png")) {
    const file = path.resolve(REPO, ref);
    if (fs.existsSync(file)) return readPng(file);
    warnFor(record, `${record.id}: committed PNG ${ref} is missing`);
    return null;
  }

  if (typeof ref === "string" && ref.toLowerCase().endsWith(".html")) {
    const file = path.resolve(REPO, ref);
    if (!fs.existsSync(file)) {
      warnFor(record, `${record.id}: HTML reference ${ref} is missing`);
      return null;
    }
    return rasterizeHtml(file, target);
  }

  if (parseFigmaRef(ref)) {
    if (!FIGMA_TOKEN) {
      warnFor(record, `${record.id}: skipping figma reference ${ref} — no FIGMA_TOKEN in this run`);
      return null;
    }
    return figmaRasterizerFor(referenceContentsOnly(record)).rasterize(ref, target);
  }

  warnFor(record, `${record.id}: don't know how to rasterise a '${source}' reference from '${ref}'`);
  return null;
}

function targetFor(record) {
  const boardDensity = referenceDensity(record);
  return {
    width: record.raster.width,
    height: record.raster.height,
    ...(record.raster.density ? { density: record.raster.density } : {}),
    ...(boardDensity !== undefined ? { boardDensity } : {}),
  };
}

/**
 * The reference board's scale for one record — source pixels per dp — from the design-map entry
 * that planned it, or `undefined`.
 *
 * Only the design-map author knows this: a Figma file reports its own pixels and nothing in it says
 * what they are pixels *of*. Declared, the annotation layer quotes the design's spacing and type in
 * the same dp/sp the render resolved, and the two columns of the compare page can finally be read
 * against each other. Undeclared, the layer names the board's unit (`text 52.5px`) and leaves the
 * number checkable — which is where design-parity#277 left it.
 *
 * A non-positive or non-finite value is dropped with a warning rather than applied: it would divide
 * every spec on the reference side by nonsense, silently, and a wrong number that looks like dp is
 * worse than an honest px.
 */
function referenceDensity(record) {
  const density = record.origin?.density;
  if (density === undefined) return undefined;
  if (typeof density !== "number" || !Number.isFinite(density) || density <= 0) {
    warnFor(record, `${record.id}: ignoring density '${density}' — it must be a positive number`);
    return undefined;
  }
  return density;
}

const referencesDir = path.join(OUT, REFERENCES_DIR);
fs.mkdirSync(referencesDir, { recursive: true });

let written = 0;
/** Reference id -> a `{ layout }` shim; `referenceAnnotations` reads only that field. */
const annotatedReferences = {};
if (FIGMA_TOKEN) {
  for (const contentsOnly of [true, false]) {
    const requests = records
      .filter(
        (record) =>
          referenceContentsOnly(record) === contentsOnly &&
          parseFigmaRef(record.origin.ref) &&
          !suppliedRaster(record.origin.ref),
      )
      .map((record) => ({ ref: record.origin.ref, target: targetFor(record) }));
    if (requests.length > 0) await figmaRasterizerFor(contentsOnly).prepare(requests);
  }
}
for (const record of records) {
  const target = targetFor(record);
  let raster;
  try {
    raster = await sourceRaster(record);
  } catch (error) {
    warnFor(record, `${record.id}: ${error.message}`);
    raster = null;
  }
  if (!raster) {
    record.rastered = false;
    continue;
  }

  // Captured before the fit rewrites `raster`, then moved onto the published raster below.
  const capturedLayout = raster.document
    ? layoutFromNode(raster.document, {
        styles: raster.styles,
        density: referenceDensity(record),
      })
    : undefined;

  // Where the artwork ends up inside the published raster. Full-bleed unless it is placed/fitted.
  let placement = { width: target.width, height: target.height, x: 0, y: 0 };
  if (raster.width !== target.width || raster.height !== target.height) {
    if (raster.preserveScale) {
      // A Figma component is exported at the Compose renderer's density. Its artboard is normally
      // tight while the target sticker includes scaffold padding, so centre it at that natural
      // size. Scaling it up to fill the canvas is the bug that made correctly sized components
      // appear ~1.5x too large in the comparison lane.
      const placed = placeRgba(
        raster.data,
        raster.width,
        raster.height,
        target.width,
        target.height,
      );
      placement = placed.box;
      const reduced = placed.box.width !== raster.width || placed.box.height !== raster.height;
      const message =
        `${record.id}: placing density-matched reference ${raster.width}x${raster.height} ` +
        `on ${target.width}x${target.height} canvas`;
      if (reduced) warnFor(record, `${message} (too large; reduced to fit)`);
      else console.log(`design-references: ${message}`);
      raster = { width: target.width, height: target.height, data: placed.data };
    } else {
      const rounding = isRoundingDelta(raster.width, raster.height, target.width, target.height);
      const message =
        `${record.id}: ${rounding ? "resampling" : "fitting"} reference ` +
        `${raster.width}x${raster.height} -> ${target.width}x${target.height}`;
      if (rounding) {
        // Sub-pixel: the proportions already agree, so stretch and say nothing.
        console.log(`design-references: ${message}`);
        raster = {
          width: target.width,
          height: target.height,
          data: resampleRgba(raster.data, raster.width, raster.height, target.width, target.height),
        };
      } else {
        // A real size difference. Stretching here would republish the design at proportions its
        // author never drew, so scale it to fit and leave the remainder transparent — the
        // comparison normalises to the content box, so the padding costs nothing and the shape
        // survives.
        const fitted = fitRgba(raster.data, raster.width, raster.height, target.width, target.height);
        placement = fitted.box;
        if (fitted.box.width !== target.width || fitted.box.height !== target.height) {
          warnFor(
            record,
            `${message} (letterboxed to ${fitted.box.width}x${fitted.box.height} — the reference's ` +
              `proportions differ from the sticker's)`,
          );
        } else {
          console.log(`design-references: ${message}`);
        }
        raster = { width: target.width, height: target.height, data: fitted.data };
      }
    }
  }

  const bytes = writePng(raster);
  fs.writeFileSync(path.join(OUT, record.raster.path), bytes);
  // The server verifies this before advertising the reference, so a corrupted fetch on the box
  // drops one row instead of showing the wrong design.
  record.raster.sha256 = crypto.createHash("sha256").update(bytes).digest("hex");
  written++;

  const scaled = capturedLayout
    ? scaleTree(capturedLayout, placement.width, placement.x, placement.y)
    : undefined;
  if (scaled) annotatedReferences[record.id] = { layout: scaled };
}

// ---- The published verdict ---------------------------------------------------------------------
//
// Scored here rather than in the browser on page load because the pair is FIXED for a publish: the
// reference was just written, the sticker was baked by the render step, and neither can change
// until the next run. A number computed once at publish is on the chip at first paint, on every
// page, for nothing — where the same number computed per visit costs two decodes and an
// edge-tolerant walk before it can be shown, which is why the viewer only ever asked for it after
// the lane was entered.
//
// Every record is scored, primary and secondary alike: a variant's divergence is exactly as worth
// seeing at rest as its parent's, and it is the variant pages that carry the states nobody thinks
// to open.
async function scoreReferences() {
  const published = records.filter((record) => record.rastered !== false);
  if (published.length === 0) return;
  const scorer = await openScorer({ executablePath: EXEC, log: warn });
  // Absent a browser the manifest simply carries no `match`, and the viewer computes it live on
  // lane entry exactly as it does today. `openScorer` has already said why.
  if (!scorer) return;
  let scored = 0;
  try {
    for (const record of published) {
      const sticker = record.origin?.imagePath;
      if (typeof sticker !== "string" || sticker === "") continue;
      let match = null;
      try {
        match = await scorer.score(
          path.join(OUT, record.raster.path),
          path.join(OUT, sticker),
        );
      } catch (error) {
        warnFor(record, `${record.id}: could not be scored (${error.message})`);
      }
      if (match) {
        record.match = match;
        scored++;
      }
    }
  } finally {
    await scorer.close();
  }
  console.log(`design-references: scored ${scored} of ${published.length} published reference(s)`);
}

await scoreReferences();

/**
 * Merge the reference-side annotation layers into the bundle's `annotations/index.json`.
 *
 * `writeCatalog` may already have written that file with the *preview* (actual) side, so this reads
 * and merges rather than overwriting — dropping the other half would trade one annotated column for
 * the other instead of getting both.
 *
 * Fail-soft like everything else here: an unreadable existing manifest is replaced rather than
 * fatal, and no captured geometry simply leaves the file alone. A reference lane is an enhancement
 * and must never cost a catalog its render.
 */
function writeReferenceAnnotations() {
  if (Object.keys(annotatedReferences).length === 0) return;
  const dir = path.join(OUT, "annotations");
  const file = path.join(dir, "index.json");
  let existing = { schema: "compose-preview-annotations/v1", previews: {}, references: {} };
  if (fs.existsSync(file)) {
    try {
      const parsed = JSON.parse(fs.readFileSync(file, "utf8"));
      if (parsed?.schema === existing.schema) existing = parsed;
      else warn(`annotations/index.json has schema '${parsed?.schema}'; replacing it`);
    } catch (error) {
      warn(`annotations/index.json is unreadable (${error.message}); replacing it`);
    }
  }
  const merged = withReferenceAnnotations(existing, annotatedReferences);
  const count = Object.keys(merged.references).length;
  if (count === 0) return;
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(file, `${JSON.stringify(merged, null, 2)}\n`);
  console.log(`design-references: annotated ${count} reference(s) in annotations/index.json`);
}

const manifest = referenceManifest(records);
fs.writeFileSync(
  path.join(referencesDir, "index.json"),
  `${JSON.stringify(manifest, null, 2)}\n`,
);

writeReferenceAnnotations();

// Report the two lanes separately. A run that publishes 78 primaries and 300 secondaries has very
// different coverage from one that publishes 78 and none, and the single total hid that.
const tallyOf = (tier) => {
  const of = records.filter((r) => r.tier === tier);
  return { planned: of.length, published: of.filter((r) => r.rastered !== false).length };
};
const primary = tallyOf("primary");
const secondary = tallyOf("secondary");
console.log(
  `design-references: published ${written}/${records.length} reference(s) to ` +
    `${REFERENCES_DIR}/ — ${primary.published}/${primary.planned} primary, ` +
    `${secondary.published}/${secondary.planned} secondary ` +
    `(${warnings.length} warning(s), ${notes.length} coverage note(s))`,
);
if (secondary.planned === 0 && records.length > 0) {
  console.log(
    "design-references: no secondary references — every design-map entry binds a single " +
      "scalar ref, so only each component's default render has a spec to diff against.",
  );
}

if (written === 0) {
  // Nothing to serve: leave no half-empty directory behind for the branch to carry.
  fs.rmSync(referencesDir, { recursive: true, force: true });
}

process.exit(STRICT && warnings.length > 0 ? 1 : 0);
