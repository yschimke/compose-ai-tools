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
 * Every raster is then fitted to the dimensions of the catalog sticker it is mapped to and hashed,
 * so the server can verify it before advertising the reference. Fitted, not stretched: a raster
 * within rounding distance is resampled, and one authored at genuinely different proportions is
 * scaled to fit and letterboxed rather than distorted into the sticker's shape. See
 * png-resample.mjs for why that distinction matters.
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
import { fitRgba, isRoundingDelta, resampleRgba } from "./png-resample.mjs";
import { layoutFromNode } from "@design-parity/adapter-figma";
import { scaleTree } from "./reference-layout.mjs";
import { withReferenceAnnotations } from "@design-parity/catalog-export";

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

const { records, warnings: planWarnings } = planDesignReferences({ designMap, spec, catalog });
planWarnings.forEach(warn);

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

/** `figma:<fileKey>/<nodeId>` → `{ fileKey, nodeId }`, or null when the ref isn't a Figma handle. */
function parseFigmaRef(ref) {
  const m = /^figma:([^/]+)\/(.+)$/.exec(String(ref ?? ""));
  return m ? { fileKey: m[1], nodeId: m[2] } : null;
}

/**
 * Render a Figma node to PNG over the REST images endpoint. Asked for at the density that lands on
 * the target sticker's width, so the resample stays a rounding correction — a blind `scale=2` is
 * what made this comparison meaningless before (a frame seeded from a device-pixel export came
 * back at twice the candidate's size).
 */
async function rasterizeFigma(ref, target) {
  const parsed = parseFigmaRef(ref);
  if (!parsed) throw new Error(`not a figma ref: ${ref}`);
  const headers = { "X-Figma-Token": FIGMA_TOKEN };
  const nodesUrl =
    `https://api.figma.com/v1/files/${encodeURIComponent(parsed.fileKey)}/nodes` +
    `?ids=${encodeURIComponent(parsed.nodeId)}`;
  const nodes = await fetch(nodesUrl, { headers });
  if (!nodes.ok) throw new Error(`figma nodes ${nodes.status}`);
  const nodeJson = await nodes.json();
  const box =
    nodeJson?.nodes?.[parsed.nodeId]?.document?.absoluteBoundingBox ??
    nodeJson?.nodes?.[parsed.nodeId.replace("-", ":")]?.document?.absoluteBoundingBox;
  // Figma caps `scale` at 4; clamp so an oversized ask doesn't 400 the whole run.
  const scale = box?.width ? Math.min(4, Math.max(0.01, target.width / box.width)) : 2;

  const imagesUrl =
    `https://api.figma.com/v1/images/${encodeURIComponent(parsed.fileKey)}` +
    `?ids=${encodeURIComponent(parsed.nodeId)}&format=png&scale=${scale}`;
  const images = await fetch(imagesUrl, { headers });
  if (!images.ok) throw new Error(`figma images ${images.status}`);
  const url = (await images.json())?.images?.[parsed.nodeId];
  if (!url) throw new Error("figma images returned no url for the node");
  const png = await fetch(url);
  if (!png.ok) throw new Error(`figma image download ${png.status}`);
  const decoded = PNG.sync.read(Buffer.from(await png.arrayBuffer()));
  // The node document rides along so the caller can capture layout geometry from the same fetch
  // that sized the raster — one round trip, and the geometry provably describes these pixels. The
  // file-level `styles` map rides along with it: a text node references a published style by id,
  // and only this map turns that id into the name the design itself uses (`Body/Large`), so the
  // reference column can say `body/large` rather than the anonymous `text`.
  const entry =
    nodeJson?.nodes?.[parsed.nodeId] ?? nodeJson?.nodes?.[parsed.nodeId.replace("-", ":")];
  return {
    width: decoded.width,
    height: decoded.height,
    data: decoded.data,
    document: entry?.document,
    styles: entry?.styles,
  };
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
  const target = { width: record.raster.width, height: record.raster.height };

  const supplied = suppliedRaster(ref);
  if (supplied) return readPng(supplied);

  if (typeof ref === "string" && ref.toLowerCase().endsWith(".png")) {
    const file = path.resolve(REPO, ref);
    if (fs.existsSync(file)) return readPng(file);
    warn(`${record.id}: committed PNG ${ref} is missing`);
    return null;
  }

  if (typeof ref === "string" && ref.toLowerCase().endsWith(".html")) {
    const file = path.resolve(REPO, ref);
    if (!fs.existsSync(file)) {
      warn(`${record.id}: HTML reference ${ref} is missing`);
      return null;
    }
    return rasterizeHtml(file, target);
  }

  if (parseFigmaRef(ref)) {
    if (!FIGMA_TOKEN) {
      warn(`${record.id}: skipping figma reference ${ref} — no FIGMA_TOKEN in this run`);
      return null;
    }
    return rasterizeFigma(ref, target);
  }

  warn(`${record.id}: don't know how to rasterise a '${source}' reference from '${ref}'`);
  return null;
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
    warn(`${record.id}: ignoring density '${density}' — it must be a positive number`);
    return undefined;
  }
  return density;
}

const referencesDir = path.join(OUT, REFERENCES_DIR);
fs.mkdirSync(referencesDir, { recursive: true });

let written = 0;
/** Reference id -> a `{ layout }` shim; `referenceAnnotations` reads only that field. */
const annotatedReferences = {};
for (const record of records) {
  const target = { width: record.raster.width, height: record.raster.height };
  let raster;
  try {
    raster = await sourceRaster(record);
  } catch (error) {
    warn(`${record.id}: ${error.message}`);
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

  // Where the artwork ends up inside the published raster. Full-bleed unless a fit letterboxes it.
  let placement = { width: target.width, height: target.height, x: 0, y: 0 };
  if (raster.width !== target.width || raster.height !== target.height) {
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
      // author never drew, so scale it to fit and leave the remainder transparent — the comparison
      // normalises to the content box, so the padding costs nothing and the shape survives.
      const fitted = fitRgba(raster.data, raster.width, raster.height, target.width, target.height);
      placement = fitted.box;
      if (fitted.box.width !== target.width || fitted.box.height !== target.height) {
        warn(
          `${message} (letterboxed to ${fitted.box.width}x${fitted.box.height} — the reference's ` +
            `proportions differ from the sticker's)`
        );
      } else {
        console.log(`design-references: ${message}`);
      }
      raster = { width: target.width, height: target.height, data: fitted.data };
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

console.log(
  `design-references: published ${written}/${records.length} reference(s) to ` +
    `${REFERENCES_DIR}/ (${warnings.length} warning(s))`,
);

if (written === 0) {
  // Nothing to serve: leave no half-empty directory behind for the branch to carry.
  fs.rmSync(referencesDir, { recursive: true, force: true });
}

process.exit(STRICT && warnings.length > 0 ? 1 : 0);
