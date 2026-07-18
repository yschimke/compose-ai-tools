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
import { execFileSync } from "node:child_process";
import { cp, mkdir, readFile, readdir, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { basename, dirname, resolve, join, relative } from "node:path";
import { parseArgs } from "node:util";

import {
  readPreviewBundle,
  bundleToCandidates,
  catalogTokensFromBundle,
} from "@design-parity/candidate";
import { buildCatalog, writeCatalog } from "@design-parity/catalog-export";

import { foldVariants } from "./catalog-variants.mjs";
import { renderIndexHtml } from "./render-index-html.mjs";
import { renderCompareHtml } from "./render-compare-html.mjs";
import { renderCrossSystemHtml } from "./render-cross-system-html.mjs";
import { renderReadmeMd } from "./render-readme-md.mjs";
import {
  figmaRastersForId,
  figmaSvgByFunction,
  rewriteRasterHrefs,
} from "./figma-svg-emit.mjs";
import { buildCodeConnectManifest } from "./figma-code-connect-emit.mjs";
import { targetsByFunction } from "./figma-code-connect-target.mjs";
import { renderWireframeSvg, slug } from "./render-wireframe-svg.mjs";
import { renderLayoutWireframeSvg } from "./render-layout-wireframe-svg.mjs";
import { DEFAULT_PREVIEW_BASE, livePreviewUrl } from "./live-preview.mjs";
import { buildFontsManifest, fontsPayloadsFromBundle } from "./render-fonts-manifest.mjs";
import { candidatePreviewBundle } from "./bundle-previews.mjs";
import { bridgeLivePreviewIds } from "./bridge-live-preview-ids.mjs";

/**
 * Best-effort fetch + parse of a JSON URL, with a short timeout. Returns null on
 * any failure (offline, 404, non-JSON, timeout) so callers degrade gracefully.
 * Used to resolve a sibling `design-artifacts/<system>` branch's `catalog.json`
 * at BUILD time, so the cross-system `matches.html` can bake static sibling
 * thumbnails (which render anywhere) instead of fetching them at view time
 * (which htmlpreview's CSP silently blocks, leaving cells stuck on "loading …").
 */
async function fetchJsonBestEffort(url, { timeoutMs = 15000 } = {}) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(url, { signal: ctrl.signal });
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

/** Relative paths of every file under `dir` (forward-slashed), for the webRender manifest. */
async function listFilesRecursive(dir) {
  const out = [];
  for (const entry of await readdir(dir, { recursive: true, withFileTypes: true })) {
    if (entry.isFile()) {
      out.push(relative(dir, join(entry.parentPath ?? entry.path, entry.name)).split("\\").join("/"));
    }
  }
  return out;
}

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
  // `bundleToCandidates` represents every preview as a static PNG sticker — it looks up
  // `previews/<id>.png` and throws `InvalidBundleError` if it's missing. Feed it a filtered *view*
  // (animated `ScrollMode.GIF` previews and PNG-less catalog-token sheets removed) rather than
  // mutating `bundle`, so the original keeps those records: the catalog-token sheets are read
  // separately by `catalogTokensFromBundle(bundle)` below, and layout/fonts iterate the full list.
  const { bundle: candidateBundle, dropped } = candidatePreviewBundle(bundle);
  if (dropped.length > 0) {
    console.warn(
      `[${basename(path)}] excluded ${dropped.length} preview(s) with no static PNG from the ` +
        `candidate join (animated GIF / metadata sheet): ${dropped.join(", ")}`,
    );
  }
  const candidates = bundleToCandidates(candidateBundle, (entry) => entry.functionName ?? entry.id);
  // Return the ORIGINAL (unfiltered) bundle: its raw `entries` carry the per-preview
  // `previews/<id>.layout.json` (layout-inspector tree) the wireframe is built from, and its full
  // `previews` list carries the catalog-token sheets `catalogTokensFromBundle` needs.
  return { candidates, bundle };
}

/**
 * Build a `functionName → { layout, density }` lookup from the bundle's raw
 * entries. The layout-inspector tree (`previews/<id>.layout.json`) is carried by
 * `bundle pack --with-semantics`; it's keyed by the full preview id, so we prefer
 * each function's light variant (matching the catalog's light-themed sticker +
 * semantics) and fall back to whatever variant carried a tree. `density` (from the
 * preview's params) converts the tree's dp tokens to the px space its bounds live
 * in. Functions with no carried tree are simply absent — the wireframe falls back
 * to the a11y-greenline renderer for them.
 */
function layoutByFunction(bundle) {
  const out = new Map();
  const prefer = (id) => /(_|\b)light$/i.test(id);
  for (const preview of bundle.previews) {
    const bytes = bundle.entries?.[`previews/${preview.id}.layout.json`];
    if (!bytes) continue;
    const fn = preview.functionName ?? preview.id;
    if (out.has(fn) && !prefer(preview.id)) continue;
    let tree;
    try {
      tree = JSON.parse(new TextDecoder().decode(bytes)).root;
    } catch {
      continue;
    }
    if (tree) out.set(fn, { layout: tree, density: preview.params?.density ?? 1 });
  }
  return out;
}


/**
 * Build a `functionName → { sourceFile }` lookup from the bundle's previews, so a Code Connect
 * mapping can point at the exact `@Preview` source file rather than the bare module. `sourceFile`
 * (module-root-relative) is carried per preview when discovery recorded it; prefer the light variant
 * for a deterministic pick, and fall through to `undefined` when no variant carried a path (the
 * mapping then falls back to the module directory).
 */
function sourceByFunction(bundle) {
  const out = new Map();
  const prefer = (id) => /(_|\b)light$/i.test(id);
  for (const preview of bundle.previews ?? []) {
    const fn = preview.functionName ?? preview.id;
    if (out.has(fn) && !prefer(preview.id)) continue;
    if (preview.sourceFile) out.set(fn, { sourceFile: preview.sourceFile });
    else if (!out.has(fn)) out.set(fn, { sourceFile: undefined });
  }
  return out;
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
 * Deep-merge two token sets, layering `extra` over `base` per category (spacing /
 * colors / radius / typography). Folds `@ColorCatalog`/`@TypographyCatalog` tokens
 * on top of the MaterialTheme table lifted from component semantics, so a bundle
 * carrying both keeps every token instead of one replacing the other. Returns the
 * single defined side when only one exists, or `undefined` when neither does.
 */
function mergeDesignTokens(base, extra) {
  if (!base) return extra;
  if (!extra) return base;
  const out = { ...base };
  for (const cat of ["spacing", "colors", "radius", "typography"]) {
    if (base[cat] || extra[cat]) {
      out[cat] = { ...(base[cat] ?? {}), ...(extra[cat] ?? {}) };
    }
  }
  return out;
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
      // Fold the component's state `variants` (pressed / focused / disabled / off
      // / …) onto the default render: the default images stay the grid hero, each
      // variant's render is appended re-tagged with its `state` so the single-
      // component view can show them as secondary previews. A variant preview that
      // didn't render is reported as missing so the completeness gate still fires.
      const { ideal, missing: missingVariants } = foldVariants(
        candidate.images,
        component,
        byFunction,
      );
      missing.push(...missingVariants);
      const source = {
        componentId: component.componentId,
        group: group.name,
        ideal,
      };
      // A group may declare a top-level `section` (the tab the preview server
      // buckets it under: Themes / Components / Screens / Animations / …). It sits
      // one level above `group`, which becomes the sub-heading inside a tab.
      // Absent ⇒ an untabbed flat catalog, as before.
      if (group.section !== undefined) source.section = group.section;
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
    ...(opts.designParity ? { designParity: opts.designParity } : {}),
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
    // Base URL of the live preview server the catalog deep-links into (the
    // `livePreview` fields + README "Customise live" links). Falls back to
    // $PREVIEW_SERVER_BASE, then the public default.
    "preview-base": { type: "string" },
    // Optional assembled in-browser CMP Wasm app (the
    // `:samples:cmp-wasm-catalog:wasmCatalogDist` output, e.g. `build/wasmDist`).
    // When given, its files are copied under `out/web/wasm/` and a `webRender`
    // descriptor is written into catalog.json — so the branch carries the live
    // renderer and `serve --catalogs` fetches it from the same trusted origin.
    "wasm-dist": { type: "string" },
    // Optional supplementary render bundle whose previews OVERRIDE same-named
    // functions in --renders. Used to fold an Android-only render into an
    // otherwise-CMP catalog: e.g. `:samples:design-catalog-m3-android` renders the
    // material3 1.5.0-alpha inset focus ring (no CMP equivalent) for
    // `FilledButtonFocused`, and this replaces the CMP module's ring-less render of
    // that function so the keyboard-focus variant shows the real ring.
    "extra-renders": { type: "string" },
    // When set, copy the `--renders` executable bundle onto the branch under
    // `out/bundle/<file>` and record `liveBundle: {path, file}` in catalog.json.
    // `compose-preview serve --catalogs --allow-render-trusted` then fetches that
    // bundle and launches a render daemon straight from it (no source build /
    // checkout). Only for systems whose bundle is a DESKTOP bundle serve can run
    // (compose-m3); the Android catalogs (wear/remote) stay baked-PNG.
    "publish-live-bundle": { type: "boolean", default: false },
    // Optional buildable source for TRUSTED server-side re-render. When
    // --source-module is given, a `source: {repo, ref, module}` is written into
    // catalog.json so a `serve --allow-render-trusted` box (one with the toolchain
    // to build it) can stand up a live, full-fidelity re-render of this catalog
    // instead of replaying baked PNGs. Inert on the public desktop box (which never
    // sets that flag).
    "source-repo": { type: "string" },
    "source-ref": { type: "string" },
    "source-module": { type: "string" },
    // Publish even when the render is incomplete (missing previews or absent
    // semantics). Off by default so a degraded render fails the job rather than
    // force-pushing a tokens/greenline-less bundle over a good delivery branch.
    "allow-incomplete": { type: "boolean", default: false },
  },
});

if (!values.spec || !values.renders || !values.out) {
  console.error(
    "usage: generate-design-catalog --spec <catalog.spec.json> --renders <dir|zip> --out <dir> [--renderer <s>] [--preview-base <url>]",
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
const { candidates, bundle } = await loadCandidates(rendersPath);

// Fold a supplementary render bundle in, overriding same-named functions. Lets an
// Android-only render (the material3 1.5.0-alpha inset focus ring, which CMP can't
// draw) replace the CMP module's render of that function — so a mostly-CMP catalog
// still carries the real focus-ring variant. The supplement's render + semantics win.
// Functions whose render was replaced by the Android-only supplement. Their baked pixels differ
// from what the desktop daemon (`bundle.previews`) would draw (e.g. the focus ring CMP can't paint),
// so the live-preview bridge below MUST NOT map them to a desktop preview — they stay baked-only.
const overriddenFunctions = new Set();
if (values["extra-renders"]) {
  const { candidates: extra } = await loadCandidates(resolve(values["extra-renders"]));
  const overridden = new Set(extra.map(functionOf));
  for (const fn of overridden) overriddenFunctions.add(fn);
  for (let i = candidates.length - 1; i >= 0; i--) {
    if (overridden.has(functionOf(candidates[i]))) candidates.splice(i, 1);
  }
  candidates.push(...extra);
  console.log(
    `[${spec.system}] folded ${extra.length} extra render(s), overriding: ` +
      `${[...overridden].join(", ")}`,
  );
}

// System tokens declared via `@ColorCatalog` / `@TypographyCatalog` — carried in the
// bundle as `previews/<id>.catalog.json` sidecars (compose-ai-tools#2167), which the
// screen-render `compose/theme` never sees (an ad-hoc palette / type scale has no
// MaterialTheme). Fold them into the catalog's exported `themeTokens` so they land in
// the DTCG token set + sticker kit.
//
// When a bundle carries BOTH — a MaterialTheme system (whose tokens `buildCatalog` lifts
// from component `semantics.themeTokens`) AND catalog sidecars — the two are MERGED, not
// replaced: lift the semantic table here (the same first-component-with-themeTokens rule
// `buildCatalog` uses) and layer the catalog tokens on top, so adding a catalog sheet to
// an M3/Wear module augments its token set instead of dropping the MaterialTheme one.
// When there are no catalog tokens we pass nothing, so `buildCatalog` lifts exactly as
// before — zero behaviour change for today's `@CatalogModes` design-catalog modules.
const catalogTokens = catalogTokensFromBundle(bundle);
const themeTokens = catalogTokens
  ? mergeDesignTokens(
      candidates.find((c) => c.semantics?.themeTokens)?.semantics?.themeTokens,
      catalogTokens,
    )
  : undefined;

// Resolve the installed `@design-parity/catalog-export` version so the catalog records the export
// engine that built it (surfaced on the serve host's provenance strip). Best-effort: a resolution
// failure just omits the field rather than sinking the render.
function designParityVersion() {
  try {
    const require = createRequire(import.meta.url);
    return require("@design-parity/catalog-export/package.json").version || undefined;
  } catch {
    return undefined;
  }
}

const { catalog, missing, withoutSemantics } = catalogFromCandidates(candidates, spec, {
  ...(values.renderer ? { renderer: values.renderer } : {}),
  ...(designParityVersion() ? { designParity: designParityVersion() } : {}),
  ...(themeTokens ? { themeTokens } : {}),
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

// Inject the live-preview deep links into catalog.json. Done as a post-process
// (re-read → annotate → re-write) rather than via writeCatalog's options because
// the pinned `@design-parity/catalog-export` predates `previewServer`; once a
// release carrying it lands and the dep is bumped, this can move into the
// writeCatalog call. Each image gets `livePreview` = the URL that opens its exact
// variant on `compose-preview serve --catalogs <system>` — the same id derivation
// the server uses, so browsing this branch and customising the live render are
// two ends of one workflow.
const previewBase =
  values["preview-base"] || process.env.PREVIEW_SERVER_BASE || DEFAULT_PREVIEW_BASE;

// Bundle the in-browser CMP Wasm app into the branch (out/web/wasm/) and record
// a `webRender` descriptor in catalog.json. `compose-preview serve --catalogs`
// reads that descriptor and fetches these exact files from the same trusted
// branch — so the live renderer rides the catalog's origin, no separate hosting.
// The `files` list IS the manifest the server fetches; an incomplete fetch fails
// closed there, so list every file the app needs.
let webRender = null;
if (values["wasm-dist"]) {
  const dest = join(outPath, "web", "wasm");
  await cp(resolve(values["wasm-dist"]), dest, { recursive: true });
  // Regenerate the app's fonts.json from the recorded `fonts/used` sidecars, so the published
  // manifest reflects what this catalog's previews actually resolved (the manifest committed in
  // the dist is the dev-time fallback). No sidecars (older CLI / non-Android backend) ⇒ keep it.
  const fontsDir = join(dest, "fonts");
  const fontsPayloads = fontsPayloadsFromBundle(bundle);
  if (fontsPayloads.length > 0) {
    const available = new Set(await readdir(fontsDir).catch(() => []));
    // The dist's committed fonts.json (just cp'd into place) carries the catalog's declared
    // theme-override typefaces, which clean previews never record; pass it so regeneration keeps
    // them instead of dropping the override faces.
    const committed = await readFile(join(fontsDir, "fonts.json"), "utf8")
      .then((s) => JSON.parse(s))
      .catch(() => null);
    const { manifest: fontsManifest, warnings } = buildFontsManifest(
      fontsPayloads,
      available,
      committed,
    );
    for (const warning of warnings) console.warn(`[${spec.system}] fonts: ${warning}`);
    if (fontsManifest) {
      await writeFile(
        join(fontsDir, "fonts.json"),
        JSON.stringify(fontsManifest, null, 2) + "\n",
      );
      console.log(
        `[${spec.system}] generated fonts.json from ${fontsPayloads.length} preview font ` +
          `record(s): ${fontsManifest.families.map((f) => f.name).join(", ")}`,
      );
    }
  }
  const files = (await listFilesRecursive(dest)).sort();
  webRender = { kind: "compose-wasm", path: "web/wasm/", files };
  console.log(`[${spec.system}] bundled web/wasm/ (${files.length} files)`);
}

// Carry the executable bundle (the `--renders` portable bundle: minimized module
// classes + previews.json + classpath manifest) onto the branch so a trusted
// `serve --catalogs --allow-render-trusted` can fetch it and launch a render
// daemon straight from it — no source checkout / Gradle build. Only when the
// caller opts in (`--publish-live-bundle`), which the pipeline sets only for
// systems whose bundle is a DESKTOP bundle serve can run (compose-m3); the
// Android catalogs stay baked-PNG.
let liveBundle = null;
if (values["publish-live-bundle"]) {
  const file = basename(rendersPath);
  const dest = join(outPath, "bundle", file);
  await mkdir(dirname(dest), { recursive: true });
  await cp(rendersPath, dest);
  liveBundle = { path: "bundle/", file };
  console.log(`[${spec.system}] carried live bundle → bundle/${file}`);

  // Lift the heavy font resources out of the carried bundle's classes/app.jar and publish them
  // content-addressed under bundle/res/<sha256>, so the ~600 KB bundle drops to ~30 KB and the
  // fonts (which rarely change and are identical across variants) are fetched once per branch. The
  // `bundle externalize` step records each in the bundle's own manifest by name+sha256+size; a
  // trusted `serve --allow-render-trusted` box rehydrates them from bundle/res/ into a shared cache
  // and back onto the daemon classpath. Non-fatal: if the CLI can't externalize (older CLI, no
  // fonts), the self-contained bundle is still published as-is.
  try {
    const resOut = join(outPath, "bundle", "res");
    const out = execFileSync(
      "compose-preview",
      ["bundle", "externalize", dest, "--res-out", resOut, "--json"],
      { encoding: "utf8" },
    );
    const summary = JSON.parse(out);
    const total = (summary.externalized ?? []).reduce((n, r) => n + (r.size ?? 0), 0);
    console.log(
      `[${spec.system}] externalized ${(summary.externalized ?? []).length} font resource(s) ` +
        `(${total} B) → bundle/res/  (bundle now ${summary.size} B)`,
    );
  } catch (err) {
    console.warn(
      `[${spec.system}] bundle externalize skipped (${err.message?.split("\n")[0] ?? err}) — ` +
        `publishing the self-contained bundle`,
    );
  }
}

{
  const catalogJsonPath = join(outPath, "catalog.json");
  const manifest = JSON.parse(await readFile(catalogJsonPath, "utf8"));
  for (const component of manifest.components ?? []) {
    for (const image of component.images ?? []) {
      if (image.path) image.livePreview = livePreviewUrl(previewBase, manifest.system, image.path);
    }
  }
  if (webRender) manifest.webRender = webRender;
  if (liveBundle) manifest.liveBundle = liveBundle;
  // Buildable source for trusted server-side re-render (opt-in consumer side).
  if (values["source-module"]) {
    manifest.source = {
      repo: values["source-repo"] ?? "",
      ref: values["source-ref"] ?? "",
      module: values["source-module"],
    };
    console.log(`[${spec.system}] source → ${manifest.source.module}@${manifest.source.ref}`);
  }
  // Emit the catalog-id → daemon-id bridge whenever a live path can serve this catalog — a carried
  // liveBundle OR a buildable source. A source-only catalog (wear-m3 / remote-m3) needs the aliases
  // too: `ServeCatalogStore` builds the alias solely from `image.previewId`, so without this a
  // `--allow-render-trusted` box would pay the Gradle build yet reach the daemon for no catalog id.
  if (liveBundle || values["source-module"]) {
    bridgeLivePreviewIds(manifest, spec, bundle, overriddenFunctions);
  }
  await writeFile(catalogJsonPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
}

// Editable SVG wireframes next to the raster PNGs: one labelled shape per layout
// region, in any-vector-tool-editable form, so a developer can adopt the
// structure rather than trace a screenshot. Written under wireframes/<slug>.svg;
// the index links them. Components with no drawable regions are skipped.
//
// Source preference: the layout-inspector tree (`previews/<id>.layout.json`,
// carried by `bundle pack --with-semantics`) — it walks every LayoutNode, so it
// captures the slot containers + resolved design tokens (background / border /
// corner / padding) a redline needs. Where no tree was carried, fall back to the
// a11y-greenline wireframe (touch-target rects only).
const layoutByFn = layoutByFunction(bundle);
const fnByComponentId = new Map(
  spec.groups.flatMap((g) => g.components.map((c) => [c.componentId, c.preview])),
);
const wireframesDir = join(outPath, "wireframes");
await mkdir(wireframesDir, { recursive: true });
// The slugs we actually wrote a wireframe for — passed to the index renderer so
// its `wireframe ↗` link reflects what exists (a layout-only wireframe has no
// greenlines, so the index can't re-derive the link from the a11y predicate).
const wireframeSlugs = new Set();
let wireframeCount = 0;
let layoutWireframeCount = 0;
for (const component of catalog.components) {
  const fn = fnByComponentId.get(component.componentId);
  const carried = fn ? layoutByFn.get(fn) : undefined;
  let svg = null;
  if (carried) {
    svg = renderLayoutWireframeSvg({ ...component, layout: carried.layout }, { density: carried.density });
    if (svg) layoutWireframeCount += 1;
  }
  if (!svg) svg = renderWireframeSvg(component); // greenline fallback
  if (!svg) continue;
  await writeFile(join(wireframesDir, `${slug(component.componentId)}.svg`), svg, "utf8");
  wireframeSlugs.add(slug(component.componentId));
  wireframeCount += 1;
}

// Editable, design-fidelity vectors next to the raster PNGs: the layered
// `compose/figma-svg` export (real fills / strokes / corner radii + editable
// text) carried per sticker in the bundle. Unlike the schematic wireframe above
// this is the *design* SVG — a designer imports it into Figma for an editable
// component rather than a flat screenshot. Written under figma/<slug>.svg; the
// index links it. Components whose render produced no drawing layers are skipped.
const figmaSvgByFn = figmaSvgByFunction(bundle);
const figmaDir = join(outPath, "figma");
await mkdir(figmaDir, { recursive: true });
const figmaSvgSlugs = new Set();
// Slugs whose figma-svg is a *hybrid* (carries `<image href>` raster crop layers).
// The compare page flags these: an <img>-loaded SVG renders in secure-static mode,
// so those raster layers don't draw there (or in its SSIM score).
const figmaSvgHybridSlugs = new Set();
let figmaSvgCount = 0;
let figmaRasterCount = 0;
for (const component of catalog.components) {
  const fn = fnByComponentId.get(component.componentId);
  const carried = fn ? figmaSvgByFn.get(fn) : undefined;
  if (!carried) continue;
  const componentSlug = slug(component.componentId);
  let svg = carried.svg;
  // A hybrid sticker's `<image href="figma-raster/<node>.png">` layers reference crops carried in
  // the bundle. Copy them under a per-slug dir and rewrite the href prefix so they resolve next to
  // figma/<slug>.svg (per-slug avoids <node>.png name collisions across components).
  const rasters = figmaRastersForId(bundle, carried.id);
  if (rasters.size) {
    figmaSvgHybridSlugs.add(componentSlug);
    const rasterDir = `${componentSlug}.figma-raster`;
    svg = rewriteRasterHrefs(svg, componentSlug);
    await mkdir(join(figmaDir, rasterDir), { recursive: true });
    for (const [name, bytes] of rasters) {
      await writeFile(join(figmaDir, rasterDir, name), Buffer.from(bytes));
      figmaRasterCount += 1;
    }
  }
  await writeFile(join(figmaDir, `${componentSlug}.svg`), svg, "utf8");
  figmaSvgSlugs.add(componentSlug);
  figmaSvgCount += 1;
}

// Figma Code Connect manifest next to the figma-svg vectors: one mapping per component binding its
// Figma layer (by componentId) to the **production composable** it renders, plus the repo source. It
// carries everything `send_code_connect_mappings` needs except the node id, which only exists once a
// designer imports the catalog — `publish-code-connect.mjs` resolves layer-name → node id against
// the imported file and produces the send payload. Emitting the manifest is plan-agnostic; only
// publishing needs an Org/Enterprise Dev/Full seat. Written for every catalog so the surface is
// covered automatically.
//
// componentName/source resolve to the real component, not the zero-arg @Preview wrapper: an explicit
// `component` authored on the spec entry wins; else discovery's inferred `PreviewTarget`
// (`targetsByFunction`); else the preview function as a marked fallback.
const componentByComponentId = new Map(
  spec.groups.flatMap((g) =>
    g.components
      .filter((c) => c.component)
      .map((c) => [c.componentId, { component: c.component, import: c.import, source: c.source }]),
  ),
);
const codeConnect = buildCodeConnectManifest({
  components: catalog.components,
  fnByComponentId,
  componentByComponentId,
  targetByFn: targetsByFunction(bundle),
  slug,
  figmaSvgSlugs,
  sourceByFn: sourceByFunction(bundle),
  system: spec.system,
  title: spec.title,
  source: {
    repo: values["source-repo"],
    ref: values["source-ref"],
    module: values["source-module"],
  },
  generatedAt: new Date().toISOString(),
});
await writeFile(
  join(outPath, "code-connect.json"),
  `${JSON.stringify(codeConnect, null, 2)}\n`,
  "utf8",
);
console.log(
  `[${spec.system}] code-connect → ${codeConnect.mappings.length} mapping(s) (code-connect.json)`,
);

// Browsable index next to catalog.json + images/ — a designer can open this
// straight from the branch to skim every component (its a11y greenlines and the
// editable wireframe) before importing the tokens/images into a design tool.
//
// Render from the written manifest (catalog.json), NOT the in-memory `catalog`:
// `buildCatalog` keeps a component's captures under `variants.ideal` (each with a
// source `uri`), while the manifest carries the flattened `images[]` with the
// bundle-relative `path` the page must reference. `renderIndexHtml` reads
// `component.images`, so passing the manifest is what makes the stickers actually
// show (and lets the zoom view group them by `state`).
const indexManifest = JSON.parse(await readFile(join(outPath, "catalog.json"), "utf8"));

// Cross-system component-parallel page (matches.html): pair every component with
// its declared counterpart in the sibling system named by `spec.compareWith`,
// side by side, so a reader sees how the two design systems line up. The pairing
// is authored per component via the `parallel` field; the other system's spec
// (its componentId list + captions) comes from the same repo checkout, and its
// renders are fetched from its own design-artifacts branch at view time (see
// render-cross-system-html.mjs). Best-effort: a missing/broken sibling spec just
// skips the page rather than failing the publish.
let crossSystem = null;
if (spec.compareWith) {
  try {
    const otherSpecPath = join(
      dirname(dirname(specPath)),
      `design-catalog-${spec.compareWith}`,
      "catalog.spec.json",
    );
    const otherSpec = JSON.parse(await readFile(otherSpecPath, "utf8"));
    const parallelById = {};
    for (const group of spec.groups ?? []) {
      for (const component of group.components ?? []) {
        if (component.parallel) parallelById[component.componentId] = component.parallel;
      }
    }
    const otherComponents = (otherSpec.groups ?? []).flatMap((group) =>
      (group.components ?? []).map((c) => ({
        componentId: c.componentId,
        group: group.name,
        caption: c.caption,
      })),
    );
    // Resolve the sibling branch's rendered catalog.json now, so each paired
    // thumbnail can be baked to a static PNG URL on the sibling's own
    // `design-artifacts/<other>` branch (raw.githubusercontent.com) — no runtime
    // fetch, so the thumbnails render on htmlpreview / file:// / the raw branch
    // alike. Best-effort: if the sibling branch isn't published yet (first run) or
    // is unreachable, `otherManifest` is null and those cells fall back to a
    // "not rendered yet" note with a link, never a perpetual "loading …". The
    // fetched manifest may be one generation stale (both branches regenerate in the
    // same workflow run), but the baked image URLs point at the branch tip, so the
    // pixels stay current — only a brand-new sibling component waits a run.
    const repo = values["source-repo"] || process.env.GITHUB_REPOSITORY || "yschimke/compose-ai-tools";
    const otherCatalogUrl = `https://raw.githubusercontent.com/${repo}/design-artifacts/${spec.compareWith}/catalog.json`;
    const otherManifest = await fetchJsonBestEffort(otherCatalogUrl);
    if (otherManifest) {
      console.log(
        `[${spec.system}] resolved ${otherManifest.components?.length ?? 0} sibling render(s) ` +
          `from ${spec.compareWith} for matches thumbnails`,
      );
    } else {
      console.warn(
        `[${spec.system}] sibling ${spec.compareWith} catalog not fetched — matches thumbnails ` +
          `show "not rendered yet" until it publishes`,
      );
    }
    const matchesPath = join(outPath, "matches.html");
    await writeFile(
      matchesPath,
      renderCrossSystemHtml(indexManifest, {
        parallelById,
        otherComponents,
        otherManifest,
        otherSystem: spec.compareWith,
        otherTitle: otherSpec.title,
        repo,
        previewBase,
      }),
      "utf8",
    );
    crossSystem = { system: spec.compareWith, title: otherSpec.title ?? spec.compareWith };
    console.log(`[${spec.system}] matches → ${matchesPath}`);
  } catch (err) {
    console.warn(
      `[${spec.system}] cross-system page skipped (${err.message?.split("\n")[0] ?? err})`,
    );
  }
}

const indexPath = join(outPath, "index.html");
await writeFile(
  indexPath,
  renderIndexHtml(indexManifest, { wireframeSlugs, figmaSvgSlugs, crossSystem }),
  "utf8",
);

// PNG-vs-SVG comparison page next to index.html: every component on one row, its
// rendered PNG beside its browser-rasterized figma-svg, and a live structural
// (SSIM) match score. The score runs in the page because it measures the
// *browser's* SVG rasterization against the PNG — see render-compare-html.mjs.
const comparePath = join(outPath, "compare.html");
await writeFile(
  comparePath,
  renderCompareHtml(indexManifest, { figmaSvgSlugs, hybridSlugs: figmaSvgHybridSlugs }),
  "utf8",
);

// Branch landing page: htmlpreview link to index.html + a summary table. Written
// into out/ so the publish step's force-push republishes it every run — the
// README rides along with each regeneration instead of being clobbered.
const readmePath = join(outPath, "README.md");
await writeFile(
  readmePath,
  renderReadmeMd(catalog, {
    imageCount: result.imageCount,
    wireframeCount,
    figmaSvgCount,
    previewBase,
    crossSystem,
  }),
  "utf8",
);

console.log(
  `[${spec.system}] ${catalog.components.length} component(s), ${result.imageCount} image(s), ` +
    `${wireframeCount} wireframe(s) (${layoutWireframeCount} from layout-inspector, ` +
    `${wireframeCount - layoutWireframeCount} greenline), ${figmaSvgCount} figma-svg ` +
    `(${figmaRasterCount} raster crop(s)) → ${result.manifestPath}`,
);
console.log(`[${spec.system}] index → ${indexPath}`);
console.log(`[${spec.system}] compare → ${comparePath}`);
console.log(`[${spec.system}] readme → ${readmePath}`);
