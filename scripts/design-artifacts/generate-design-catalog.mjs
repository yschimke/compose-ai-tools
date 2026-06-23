#!/usr/bin/env node
/**
 * Generate an importable design-artifact catalog from a rendered catalog module.
 *
 * Pipeline:  compose-preview render  →  this script  →  design-artifacts/<system>
 *
 *   node scripts/design-artifacts/generate-design-catalog.mjs \
 *     --spec    <path to catalog.spec.json> \
 *     --renders <compose-preview output dir or .zip (has previews.json + PNGs)> \
 *     --out     <output bundle dir> \
 *     [--renderer "compose-preview 0.16.2"]
 *
 * The export engine lives in the (private) design-parity repo, but its building
 * blocks are published to npm: this driver depends only on the public package
 * APIs `@design-parity/candidate` (`loadPreviewBundle`) and
 * `@design-parity/catalog-export` (`buildCatalog`, `writeCatalog`). Both are
 * installed from `scripts/design-artifacts/package.json` (pinned), so the weekly
 * workflow needs no checkout of the private repo and no cross-repo secret.
 *
 * `catalogFromCandidates` (the spec→candidate join) is vendored inline below
 * rather than imported, because the published `@design-parity/catalog-export`
 * (0.1.20) predates that export — it's a thin, pure wrapper over the published
 * `buildCatalog`. Keep it in sync with design-parity's
 * `packages/catalog-export/src/spec.ts`; once a catalog-export release exports
 * `catalogFromCandidates`, this inline copy can be dropped for the import.
 *
 * It reads the static preview bundle with `@design-parity/candidate`
 * (`loadPreviewBundle` → `CandidateRender[]`), joins it to the committed spec
 * (`catalogFromCandidates` → `buildCatalog`), and writes the importable bundle
 * (`catalog.json` + `tokens.dtcg.json` + `figma-variables.json` + `images/`).
 * The caller (the weekly workflow) commits the result to the system's
 * `design-artifacts/<system>` branch.
 */
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { parseArgs } from "node:util";

import { readPreviewBundle, bundleToCandidates } from "@design-parity/candidate";
import { buildCatalog, writeCatalog } from "@design-parity/catalog-export";

/**
 * Read a preview bundle into CandidateRenders, resolving each candidate's
 * componentId to its `@Preview` functionName so the join folds theme/size
 * variants (see the vendored join below).
 *
 * Split out of `loadPreviewBundle` to sanitize params first: the published
 * `@design-parity/core` normalizeSize (≤ 0.1.21) throws on a `null` `widthDp`,
 * which a `@Preview` with no pinned width serializes as JSON `null` (e.g. the
 * compose-m3 stickers; the wear catalog pins a device width and is unaffected).
 * Dropping the null width/height params is equivalent to "unset" and avoids the
 * crash. Fixed upstream (design-parity normalizeSize null-guard); once that
 * ships to npm and the pin is bumped, this can revert to a plain
 * `loadPreviewBundle(path, resolver)`.
 */
async function loadCandidates(path) {
  const bundle = await readPreviewBundle(path);
  for (const preview of bundle.previews) {
    sanitizeNullSizes(preview.params);
    for (const capture of preview.captures ?? []) sanitizeNullSizes(capture.params);
  }
  return bundleToCandidates(bundle, (entry) => entry.functionName ?? entry.id);
}

/** Drop `widthDp`/`heightDp` when serialized as JSON null so the published
 *  normalizeSize doesn't `.trim()` a null. */
function sanitizeNullSizes(params) {
  if (!params) return;
  if (params.widthDp == null) delete params.widthDp;
  if (params.heightDp == null) delete params.heightDp;
}

// --- vendored from design-parity packages/catalog-export/src/spec.ts ----------
// Pure join of rendered CandidateRenders to a catalog spec, wrapping the
// published `buildCatalog`. See the file header for why it's inlined.
//
// The bundle reader emits one candidate per multipreview variant — its id
// carries a `_<mode>` suffix (`FilledButton_Light`, `FilledButton_Dark`) that
// the spec's bare `preview` ("FilledButton") doesn't. To match, the caller
// resolves each candidate's componentId to its `functionName` (see the
// `loadPreviewBundle(..., resolver)` call below), so `functionOf` keys on the
// stable function name and a function's theme/size variants fold onto one
// sticker. The published `@design-parity/catalog-export` (0.1.20) predates the
// `functionName`-aware `catalogFromCandidates`; once a release ships it, this
// inline copy + the resolver can be dropped for the import.

/** The function name a spec component matches on. With the resolver below,
 *  `componentId` is the function name; `functionName` is preferred when a
 *  future bundle reader sets it directly on the candidate. */
function functionOf(candidate) {
  return candidate.functionName ?? candidate.componentId;
}

/** A semantics tree carries real signal (not the empty `{ root: {} }` fallback). */
function hasSemantics(candidate) {
  const tree = candidate.semantics;
  if (!tree) return false;
  if (tree.themeTokens) return true;
  const r = tree.root;
  return Boolean(
    r && ((r.children && r.children.length > 0) || r.role || r.label || r.bounds || r.tokens),
  );
}

/** Fold a function's theme/size variants into one render: concatenate images and
 *  keep a light-themed semantics tree (the token/greenline reader keys off one). */
function mergeByFunction(a, b) {
  const semantics =
    a.semantics?.theme === "light"
      ? a.semantics
      : b.semantics?.theme === "light"
        ? b.semantics
        : a.semantics;
  const merged = { componentId: a.componentId, images: [...a.images, ...b.images], semantics };
  if (a.previewId ?? b.previewId) merged.previewId = a.previewId ?? b.previewId;
  if (a.functionName ?? b.functionName) merged.functionName = a.functionName ?? b.functionName;
  return merged;
}

/**
 * Join rendered candidates to a catalog spec. Each spec component is matched to
 * the candidate whose preview function name equals its `preview`; a function's
 * theme/size variants are folded into one component, missing previews are
 * reported rather than dropped, and rendered-but-semantics-less components are
 * flagged so the completeness gate can refuse to publish.
 */
function catalogFromCandidates(candidates, spec, opts = {}) {
  const byFunction = new Map();
  for (const candidate of candidates) {
    const fn = functionOf(candidate);
    const existing = byFunction.get(fn);
    byFunction.set(fn, existing ? mergeByFunction(existing, candidate) : candidate);
  }

  const sources = [];
  const missing = [];
  const withoutSemantics = [];
  for (const group of spec.groups) {
    for (const component of group.components) {
      const candidate = byFunction.get(component.preview);
      if (!candidate || candidate.images.length === 0) {
        missing.push(component.componentId);
        continue;
      }
      if (!hasSemantics(candidate)) withoutSemantics.push(component.componentId);
      const source = {
        componentId: component.componentId,
        group: group.name,
        ideal: [...candidate.images],
      };
      if (component.caption !== undefined) source.caption = component.caption;
      if (component.reference !== undefined) source.reference = component.reference;
      if (candidate.semantics) source.semantics = candidate.semantics;
      sources.push(source);
    }
  }

  const meta = {
    system: spec.system,
    title: spec.title,
    ...(spec.library ? { library: spec.library } : {}),
    ...(opts.renderer ? { renderer: opts.renderer } : {}),
    generatedAt: opts.generatedAt ?? new Date().toISOString(),
  };

  const catalog = buildCatalog(meta, sources, opts.themeTokens);
  return { catalog, missing, withoutSemantics };
}
// --- end vendored join --------------------------------------------------------

const { values } = parseArgs({
  options: {
    spec: { type: "string" },
    renders: { type: "string" },
    out: { type: "string" },
    renderer: { type: "string" },
    // Publish even when the render is incomplete (missing previews or absent
    // semantics). Off by default so a degraded render fails the job rather than
    // force-pushing a tokens/greenline-less bundle over a good delivery branch.
    "allow-incomplete": { type: "boolean", default: false },
  },
});

if (!values.spec || !values.renders || !values.out) {
  console.error(
    "usage: generate-design-catalog --spec <catalog.spec.json> --renders <dir|zip> --out <dir> [--renderer <s>]",
  );
  process.exit(2);
}

const specPath = resolve(values.spec);
const rendersPath = resolve(values.renders);
const outPath = resolve(values.out);

const spec = JSON.parse(await readFile(specPath, "utf8"));
// Read candidates with componentId resolved to the `@Preview` function name so
// the join folds a function's theme/size multipreview variants (whose ids differ
// only by an appended `_<mode>`) onto one component. See `loadCandidates` (which
// also works around the published null-widthDp crash) and the vendored join.
const candidates = await loadCandidates(rendersPath);

const { catalog, missing, withoutSemantics } = catalogFromCandidates(candidates, spec, {
  ...(values.renderer ? { renderer: values.renderer } : {}),
});

// Completeness gate: `bundle pack --with-semantics` is best-effort and exits 0
// even when the daemon never started or captured zero semantics. For a scheduled
// job that force-pushes a delivery branch, refuse to publish an incomplete render
// (missing previews, or pixels with no semantics → no tokens/contrast/greenlines)
// so a transient failure can't clobber a good branch. `--allow-incomplete` opts out.
if (missing.length > 0) {
  console.warn(`[${spec.system}] missing renders for: ${missing.join(", ")}`);
}
if (withoutSemantics.length > 0) {
  console.warn(`[${spec.system}] no semantics for: ${withoutSemantics.join(", ")}`);
}
if (!values["allow-incomplete"] && (missing.length > 0 || withoutSemantics.length > 0)) {
  console.error(
    `[${spec.system}] incomplete render — refusing to publish. ` +
      `Re-run the render, or pass --allow-incomplete to override.`,
  );
  process.exit(1);
}

// Images in the bundle are relative to the render dir; resolve them from there.
const sourceRoot = rendersPath.endsWith(".zip") ? dirname(rendersPath) : rendersPath;
const result = await writeCatalog(catalog, outPath, { sourceRoot });

console.log(
  `[${spec.system}] ${catalog.components.length} component(s), ${result.imageCount} image(s) → ${result.manifestPath}`,
);
