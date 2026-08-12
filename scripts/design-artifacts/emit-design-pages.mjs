/**
 * Write a published catalog's `pages/` directory — the producer for the preview server's
 * whole-screen `/{system}/pages/` surface.
 *
 *     node emit-design-pages.mjs --out <bundle dir> --repo <repo root> \
 *       [--pages design/pages] [--spec catalog.spec.json] [--strict]
 *
 * `--out` is the staged bundle the workflow is about to publish to `design-artifacts/<system>`;
 * this adds `pages/index.json` plus one backdrop PNG per screen and leaves the rest of it alone.
 * Absent a page-backdrop manifest it is a no-op, so every catalog can run it unconditionally — the
 * same posture as `emit-design-references.mjs`.
 *
 * ## Where the pixels come from
 *
 * From `design-parity-pages import`, which is the only thing here that talks to Figma. It is run
 * by the workflow *before* this script (or by hand, into a committed `design/pages/`), so this one
 * needs no token and makes no network call: it reads a manifest and copies PNGs. Keeping the fetch
 * out of here is what lets a repo commit its backdrops and republish them offline, and what keeps
 * a fork's catalog build working with no Figma credential at all.
 *
 * The re-keying — repo discovery preview ids to the catalog's serve preview ids — is
 * `design-pages.mjs`, which is pure and unit-tested. This file is the I/O around it.
 *
 * ## Failure posture
 *
 * Fail-soft by default, like the server's own reader: a screen whose backdrop can't be copied is
 * dropped with a `::warning::` and the catalog publishes without it, because a whole-screen view is
 * an enhancement and must never cost a catalog its render. `--strict` turns any warning into a
 * non-zero exit, for a repo that wants its screen coverage gated.
 */
import fs from "node:fs";
import path from "node:path";

import { stripComments } from "./catalog-spec.mjs";
import { PAGES_DIR, PAGES_INDEX, pageImageName, planPageBackdrops } from "./design-pages.mjs";

function arg(name, def = undefined) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : def;
}

const OUT = arg("out");
const REPO = path.resolve(arg("repo", "."));
const SPEC = arg("spec", "catalog.spec.json");
const STRICT = process.argv.includes("--strict");

/**
 * Where the importer put its output. `design-pages.json` is the producer's own config and already
 * names it, so read that rather than assuming the default: a repo that set `outDir` elsewhere would
 * otherwise publish nothing, silently, while the import step reported success.
 */
function importerOutDir() {
  const explicit = arg("pages");
  if (explicit) return explicit;
  const configPath = path.resolve(REPO, "design-pages.json");
  if (!fs.existsSync(configPath)) return "design/pages";
  try {
    const config = JSON.parse(stripComments(fs.readFileSync(configPath, "utf8")));
    const dir = config?.outDir;
    return typeof dir === "string" && dir !== "" ? dir : "design/pages";
  } catch {
    return "design/pages";
  }
}

const PAGES = importerOutDir();

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
// spec is a warning rather than a stop — the manifest still publishes, with fewer renderable
// placements at worst.
const specPath = path.resolve(REPO, SPEC);
if (!fs.existsSync(specPath)) {
  warn(`no catalog spec at ${SPEC}; placements will be matched by preview id only`);
}

const catalog = readJson(catalogPath);
const spec = fs.existsSync(specPath) ? readJson(specPath, { comments: true }) : {};
const manifest = readJson(manifestPath);

const plan = planPageBackdrops({ manifest, spec, catalog });
for (const message of plan.warnings) warn(message);

if (!plan.manifest) {
  console.log("design-pages: nothing publishable in the page-backdrop manifest");
  process.exit(STRICT && warnings.length > 0 ? 1 : 0);
}

const outDir = path.join(OUT, PAGES_DIR);
fs.mkdirSync(outDir, { recursive: true });

// Copy each backdrop under its page id. A screen whose PNG is missing is dropped from the manifest
// rather than advertised: the server would 404 the image and paint an empty stage.
const copied = new Set();
for (const { pageId, from } of plan.images) {
  const source = path.resolve(pagesDir, from);
  // Contain the read to the producer's own directory — the manifest is generated, but it is still
  // an input, and `../..` in an image uri must not read arbitrary files into the published bundle.
  if (!source.startsWith(pagesDir + path.sep)) {
    warn(`page ${pageId}: backdrop path ${from} escapes ${PAGES}; skipped`);
    continue;
  }
  if (!fs.existsSync(source)) {
    warn(`page ${pageId}: backdrop ${from} is missing; skipped`);
    continue;
  }
  fs.copyFileSync(source, path.join(outDir, pageImageName(pageId)));
  copied.add(pageId);
}

const published = plan.manifest.pages.filter((page) => copied.has(page.id));
if (published.length === 0) {
  console.log("design-pages: no backdrop image could be published");
  process.exit(STRICT && warnings.length > 0 ? 1 : 0);
}

fs.writeFileSync(
  path.join(outDir, PAGES_INDEX),
  `${JSON.stringify({ ...plan.manifest, pages: published }, null, 2)}\n`,
);

const linked = published.reduce(
  (total, page) => total + page.placements.filter((p) => p.link !== "unlinked").length,
  0,
);
const placements = published.reduce((total, page) => total + page.placements.length, 0);
const renderable = published.reduce(
  (total, page) => total + page.placements.filter((p) => p.previewId).length,
  0,
);
console.log(
  `design-pages: published ${published.length} screen(s), ${linked}/${placements} placements ` +
    `linked, ${renderable} renderable on the server`,
);

if (STRICT && warnings.length > 0) process.exit(1);
