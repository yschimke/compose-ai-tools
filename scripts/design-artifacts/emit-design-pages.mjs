/**
 * Write a published catalog's `pages/` directory — the producer for the preview server's
 * `/{system}/pages/` surface.
 *
 *     node emit-design-pages.mjs --out <bundle dir> --repo <repo root> \
 *       [--config design-pages.json] [--pages design/pages] [--spec catalog.spec.json] [--strict]
 *
 * `--out` is the staged bundle the workflow is about to publish to `design-artifacts/<system>`;
 * this adds `pages/index.json` plus one cached SVG per page and leaves the rest of it alone. Absent
 * an import it is a no-op, so every catalog can run it unconditionally — the same posture as
 * `emit-design-references.mjs`.
 *
 * ## Where the pixels come from
 *
 * From the repo's own committed import (m3-catalog's `scripts/import-figma-pages.mjs`), which is
 * the only thing that talks to Figma. That runs on its own manual cadence and commits its output,
 * so this script needs no token and makes no network call: it reads a manifest and copies SVGs.
 * Keeping the fetch out of here is what lets a repo republish its pages offline, and what keeps a
 * fork's catalog build working with no Figma credential at all.
 *
 * The re-keying — repo discovery preview ids to the catalog's serve preview ids — is
 * `design-pages.mjs`, which is pure and unit-tested. This file is the I/O around it.
 *
 * ## Failure posture
 *
 * Fail-soft by default, like the server's own reader: a page whose export can't be copied is
 * dropped with a `::warning::` and the catalog publishes without it, because a page view is an
 * enhancement and must never cost a catalog its render. `--strict` turns any warning into a
 * non-zero exit, for a repo that wants its page coverage gated.
 */
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

import { referenceKitFileKeys, stripComments } from "./catalog-spec.mjs";
import {
  ASSETS_DIR,
  PAGES_DIR,
  PAGES_INDEX,
  designPagesKitSkip,
  pageImageName,
  planDesignPages,
} from "./design-pages.mjs";

function arg(name, def = undefined) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : def;
}

const OUT = arg("out");
const REPO = path.resolve(arg("repo", "."));
const SPEC = arg("spec", "catalog.spec.json");
const STRICT = process.argv.includes("--strict");

/**
 * What the producer's own config says about its import: where it put its output, and which Figma
 * file it came from.
 *
 * The importer config already names the output directory, so read that rather than assuming the
 * default: a repo that set `outDir` elsewhere would otherwise publish nothing, silently, while the
 * import step reported success. It also names the `fileKey`, which is the only record of WHICH kit
 * the pages are — the question the guard below turns on. `--config` lets a multi-system repository
 * select a different committed cache for each kit; its default preserves the original
 * `design-pages.json` convention.
 */
function importerConfig() {
  const configPath = path.resolve(REPO, arg("config", "design-pages.json"));
  let config = {};
  if (fs.existsSync(configPath)) {
    try {
      config = JSON.parse(stripComments(fs.readFileSync(configPath, "utf8")));
    } catch {
      config = {};
    }
  }
  const dir = config?.outDir;
  return {
    // `--pages` still wins, for a caller pointing this at a directory the config does not name.
    outDir: arg("pages") || (typeof dir === "string" && dir !== "" ? dir : "design/pages"),
    // Which Figma file the import came from. Read even when `--pages` overrode the directory: the
    // kit a set of pages belongs to is a property of the import, not of where it was written.
    fileKey: typeof config?.fileKey === "string" ? config.fileKey : undefined,
  };
}

const { outDir: PAGES, fileKey: PAGES_FILE_KEY } = importerConfig();

if (!OUT) {
  console.error("emit-design-pages: --out <bundle dir> is required");
  process.exit(2);
}

const warnings = [];
const warn = (message) => {
  warnings.push(message);
  console.log(`::warning::design-pages: ${message}`);
};

function readJson(file, { comments = false } = {}) {
  const text = fs.readFileSync(file, "utf8");
  return JSON.parse(comments ? stripComments(text) : text);
}

const pagesDir = path.resolve(REPO, PAGES);
const manifestPath = path.join(pagesDir, "pages.json");
if (!fs.existsSync(manifestPath)) {
  console.log(`design-pages: no ${PAGES}/pages.json in ${REPO}; nothing to publish`);
  process.exit(0);
}

const catalogPath = path.join(OUT, "catalog.json");
if (!fs.existsSync(catalogPath)) {
  console.error(`design-pages: ${catalogPath} is missing — run after the catalog export`);
  process.exit(2);
}

// A spec-led catalog needs the spec to learn which `@Preview` produced which sticker. An
// annotation-led one (m3-catalog) joins on the preview id alone and works without it, so a missing
// spec is a warning rather than a stop — the manifest still publishes, with fewer renderable nodes
// at worst.
const specPath = path.resolve(REPO, SPEC);
if (!fs.existsSync(specPath)) {
  warn(`no catalog spec at ${SPEC}; nodes will be matched by preview id only`);
}

const catalog = readJson(catalogPath);
const spec = fs.existsSync(specPath) ? readJson(specPath, { comments: true }) : {};

// Fail-soft on the one input this lane owns. A truncated `pages.json` — a killed import, a bad
// committed edit — would otherwise throw out of this script, and the workflow's `set -e` would take
// the whole catalog publish down with it. The page view is an enhancement; it must never cost a
// catalog its render, which is the same posture the server's reader has for the same file.
let manifest;
try {
  manifest = readJson(manifestPath);
} catch (error) {
  warn(`${PAGES}/pages.json is not readable JSON (${error.message}); publishing without pages`);
  process.exit(STRICT ? 1 : 0);
}

// Whose kit are these pages? `design-pages.json` is repo-global and names one Figma file, while a
// repository can ship several systems reproducing different kits — so publishing unconditionally
// hands every system the one kit's sheets (yschimke/m3-catalog#398). The spec says which kit this
// system is compared against; publish only on a positive match.
//
// Keyed on the MANIFEST's own `fileKey`, not the config's. The manifest is the import's record of
// where its pixels actually came from, and the two can disagree: `--pages` may select a directory
// the config does not name, and a config edited without regenerating the import drifts from it.
// Trusting the config there would let a matching config authorise another kit's pages, and the
// reverse mismatch would suppress correct ones. The config is the fallback for an older manifest
// that carries no key.
//
// A plain log rather than `warn()`, deliberately: on a system that reproduces a different kit this
// skip is the CORRECT outcome, so it must not trip `--strict` and fail a build that is behaving.
const kitSkip = designPagesKitSkip({
  fileKey: typeof manifest?.fileKey === "string" ? manifest.fileKey : PAGES_FILE_KEY,
  kitKeys: referenceKitFileKeys(spec),
});
if (kitSkip) {
  console.log(`design-pages: not publishing ${PAGES} — ${kitSkip}`);
  process.exit(0);
}

// Planning is inside the guard too. The parse above catches a *syntax* error, but a structurally
// odd manifest can still surprise the planner, and any throw here reaches the workflow's `set -e`
// and takes the catalog's publish with it — the one outcome this lane must never cause.
let plan;
try {
  plan = planDesignPages({ manifest, spec, catalog });
} catch (error) {
  warn(`could not plan the design pages (${error.message}); publishing without pages`);
  process.exit(STRICT ? 1 : 0);
}
for (const message of plan.warnings) warn(message);

if (!plan.manifest) {
  console.log("design-pages: nothing publishable in the design-page import");
  process.exit(STRICT && warnings.length > 0 ? 1 : 0);
}

const outDir = path.join(OUT, PAGES_DIR);
fs.mkdirSync(outDir, { recursive: true });

/**
 * Whether the file opens as an SVG document.
 *
 * A shape check, NOT a safety check — the server sanitizes the markup itself, because it reads
 * branches this script never wrote and that is where the trust boundary belongs. What this catches
 * is the ordinary mistake: an import that wrote an error page, a raster left over from the old
 * screen backdrop, a truncated download. Publishing one of those would advertise a page the server
 * then drops, which reads as a server bug rather than a broken import.
 */
function isSvg(file) {
  const head = Buffer.alloc(1024);
  const fd = fs.openSync(file, "r");
  try {
    const read = fs.readSync(fd, head, 0, head.length, 0);
    return /<svg[\s>]/i.test(head.subarray(0, read).toString("utf8"));
  } finally {
    fs.closeSync(fd);
  }
}

/**
 * Resolve [from] inside the import directory, or null with a warning.
 *
 * Shared by the export lane and the shared-asset lane so the containment rule cannot drift between
 * them — an asset path is exactly as much of an input as an image path, and a plate escaping the
 * directory would publish an arbitrary file under a content-addressed name that claims to be
 * verified.
 */
function containedSource(from, label) {
  const realRoot = fs.realpathSync(pagesDir);
  let source;
  try {
    source = fs.realpathSync(path.resolve(pagesDir, from));
  } catch {
    warn(`${label}: ${from} is missing; skipped`);
    return null;
  }
  if (source !== realRoot && !source.startsWith(realRoot + path.sep)) {
    warn(`${label}: path ${from} resolves outside ${PAGES}; skipped`);
    return null;
  }
  if (!fs.statSync(source).isFile()) {
    warn(`${label}: ${from} is not a regular file; skipped`);
    return null;
  }
  return source;
}

/**
 * The magic bytes each inert raster format opens with.
 *
 * Checked because the manifest's `format` is a CLAIM and the consumer refuses anything that is not
 * the format it says it is. Publishing a mislabelled file would advertise a plate the server then
 * drops, which reads as a server bug rather than a broken import — the same reasoning as [isSvg].
 */
const ASSET_SIGNATURES = new Map([
  ["png", (b) => b.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))],
  ["jpeg", (b) => b.length > 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff],
  [
    "webp",
    (b) =>
      b.length > 12 &&
      b.subarray(0, 4).toString("latin1") === "RIFF" &&
      b.subarray(8, 12).toString("latin1") === "WEBP",
  ],
]);

const copied = new Set();
for (const { pageId, from } of plan.images) {
  // Contain the read to the producer's own directory. The manifest is generated, but it is still an
  // input: `../..` in an image uri must not pull arbitrary files into a published bundle. Resolved
  // with `realpathSync`, not a lexical prefix test — see [containedSource].
  const source = containedSource(from, `page ${pageId}: export`);
  if (source === null) continue;
  if (!isSvg(source)) {
    warn(`page ${pageId}: export ${from} is not an SVG; skipped`);
    continue;
  }
  fs.copyFileSync(source, path.join(outDir, pageImageName(pageId)));
  copied.add(pageId);
}

/**
 * Copy the shared backplates, verifying the bytes rather than trusting the record.
 *
 * Content addressing is only worth anything if someone checks it, and this is the only place that
 * can: the consumer sees a file and a manifest that agree with each other by construction. Three
 * things are proven here — the file opens as the format it claims, its length is the declared one,
 * and its SHA-256 IS its id. An asset failing any of them is dropped rather than published, because
 * a plate whose id does not address its bytes makes every downstream cache key a lie.
 */
const publishedAssets = [];
if (plan.assets.length > 0) {
  fs.mkdirSync(path.join(outDir, ASSETS_DIR), { recursive: true });
}
for (const { id, from, record } of plan.assets) {
  const label = `shared asset ${id}`;
  const source = containedSource(from, label);
  if (source === null) continue;

  const bytes = fs.readFileSync(source);
  const signature = ASSET_SIGNATURES.get(record.format);
  if (!signature || !signature(bytes)) {
    warn(`${label}: ${from} does not open as ${record.format}; skipped`);
    continue;
  }
  if (bytes.length !== record.bytes) {
    warn(
      `${label}: declares ${record.bytes} bytes but ${from} is ${bytes.length}; skipped`,
    );
    continue;
  }
  const digest = crypto.createHash("sha256").update(bytes).digest("hex");
  if (digest !== id) {
    // The id is the content address. A mismatch means the import's table and its files have drifted
    // — a hand-edited manifest, a half-finished re-import — and republishing it would hand the
    // server an immutable URL that does not name its own bytes.
    warn(`${label}: ${from} hashes to ${digest.slice(0, 12)}…; skipped`);
    continue;
  }
  fs.writeFileSync(path.join(outDir, record.uri), bytes);
  publishedAssets.push(record);
}

const published = plan.manifest.pages.filter((page) => copied.has(page.id));
if (published.length === 0) {
  console.log("design-pages: no page export could be published");
  process.exit(STRICT && warnings.length > 0 ? 1 : 0);
}

// Drop any placement whose plate did not survive the byte checks above, so the published manifest
// never names bytes the bundle does not carry. Without this the server would fail soft around a
// hole nobody could explain from the branch alone.
const availableAssets = new Set(publishedAssets.map((asset) => asset.id));
const pagesToPublish = published.map((page) => {
  if (!Array.isArray(page.background)) return page;
  const background = page.background.filter((layer) => availableAssets.has(layer.asset));
  if (background.length === page.background.length) return page;
  warn(
    `page ${page.id}: ${page.background.length - background.length} background layer(s) name a ` +
      `plate that could not be published; dropped`,
  );
  const { background: _dropped, ...rest } = page;
  return background.length > 0 ? { ...rest, background } : rest;
});

// Rebuilt rather than spread-over, so a plate the planner reached but the byte checks rejected
// cannot survive in the published table. `assets` is omitted entirely when empty: a catalog with no
// backplates publishes the manifest it always did, not one that says it has nothing.
const { assets: _planned, ...manifestRest } = plan.manifest;
fs.writeFileSync(
  path.join(outDir, PAGES_INDEX),
  `${JSON.stringify(
    {
      ...manifestRest,
      pages: pagesToPublish,
      ...(publishedAssets.length > 0 ? { assets: publishedAssets } : {}),
    },
    null,
    2,
  )}\n`,
);

const linked = published.reduce(
  (total, page) => total + page.nodes.filter((n) => n.link !== "unlinked").length,
  0,
);
const nodes = published.reduce((total, page) => total + page.nodes.length, 0);
const renderable = published.reduce(
  (total, page) => total + page.nodes.filter((n) => n.previewId).length,
  0,
);
console.log(
  `design-pages: published ${published.length} page(s), ${linked}/${nodes} nodes linked, ` +
    `${renderable} renderable on the server` +
    (publishedAssets.length > 0 ? `, ${publishedAssets.length} shared backplate(s)` : ""),
);

if (STRICT && warnings.length > 0) process.exit(1);
