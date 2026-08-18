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
import { basename, dirname, resolve, join, relative } from "node:path";
import { parseArgs } from "node:util";

import {
  readPreviewBundle,
  bundleToCandidates,
  catalogTokensFromBundle,
  themeTokenSetsFromBundle,
} from "@design-parity/candidate";
import { buildCatalog, writeCatalog } from "@design-parity/catalog-export";

import { foldVariants, variantLabel } from "./catalog-variants.mjs";
import { foldMotion, motionArtifactsFor, motionPreviewFor } from "./catalog-motion.mjs";
import { publishMotionArtifacts } from "./catalog-motion-publish.mjs";
import { checkMotionCarried } from "./motion-carried.mjs";
import {
  DEFERRED,
  deferralPlan,
  entryPriority,
  modeOfPreviewId,
  modePriority,
  previewForImage,
  specDefersAnything,
  splitDeferredImages,
  splitDeferredVariants,
} from "./catalog-priority.mjs";
import {
  applyGroupOrder,
  inventoryFromPreviews,
  mergeCatalogGroups,
} from "./catalog-inventory.mjs";
import { catalogThemesFromBundle } from "./catalog-themes.mjs";
import { variantStateFromId } from "./variant-state.mjs";
import {
  applyVariantAxisProps,
  overridesByPreviewId,
} from "./variant-axis-props.mjs";
import { renderIndexHtml } from "./render-index-html.mjs";
import { renderFailuresFromBundles } from "./render-failures.mjs";
import { renderCompareHtml } from "./render-compare-html.mjs";
import { renderCrossSystemHtml } from "./render-cross-system-html.mjs";
import { renderReadmeMd } from "./render-readme-md.mjs";
import {
  figmaRastersForId,
  figmaSvgByFunctions,
  figmaSvgByIds,
  figmaVariantSvgPath,
  rewriteRasterHrefs,
} from "./figma-svg-emit.mjs";
import {
  describeMismatchedRenders,
  mismatchedVariantRenders,
} from "./variant-render-pairing.mjs";
import { buildCodeConnectManifest } from "./figma-code-connect-emit.mjs";
import { targetsByFunction } from "./figma-code-connect-target.mjs";
import { renderWireframeSvg, slug } from "./render-wireframe-svg.mjs";
import { renderLayoutWireframeSvg } from "./render-layout-wireframe-svg.mjs";
import { DEFAULT_PREVIEW_BASE, livePreviewUrl } from "./live-preview.mjs";
import { installedPackageVersion } from "./package-version.mjs";
import {
  buildFontsManifest,
  fontsPayloadsFromBundle,
} from "./render-fonts-manifest.mjs";
import {
  candidatePreviewBundle,
  daemonPreviewCellsByFunction,
  daemonPreviewIdsByFunction,
} from "./bundle-previews.mjs";

// CI warnings and fatal diagnostics should be visible as workflow annotations,
// not buried among catalog-generation milestones. Keep local output unchanged.
if (process.env.GITHUB_ACTIONS === "true") {
  const annotate = (level, title, sink) => (...parts) => {
    const escape = (value) =>
      String(value).replaceAll("%", "%25").replaceAll("\r", "%0D").replaceAll("\n", "%0A");
    sink(`::${level} title=${escape(title)}::${escape(parts.join(" "))}`);
  };
  console.warn = annotate("warning", "Design artifact warning", console.warn.bind(console));
  console.error = annotate("error", "Design artifact failure", console.error.bind(console));
}
import { exportsNoSticker } from "./capture-mode.mjs";
import {
  bridgeLivePreviewIds,
  expandDeferredRecords,
  stampPreviewDensities,
  resolveSemanticsIds,
} from "./bridge-live-preview-ids.mjs";
import { catalogTagIndex } from "./tag-index.mjs";
import {
  catalogImagePath,
  derivationMismatches,
} from "./catalog-image-path.mjs";
import {
  extraOnlyFunctions,
  unbridgeableFunctions,
} from "./extra-render-fold.mjs";
import { applySpecSections } from "./apply-spec-sections.mjs";
import { applySourceFiles } from "./apply-source-files.mjs";
import {
  applySpecBreakpoints,
  catalogBreakpoints,
  undeclaredBreakpointDevices,
} from "./catalog-breakpoints.mjs";
import { applyCatalogPreviewAxes } from "./catalog-preview-axes.mjs";
import { previewIdAliases } from "./preview-id-alias.mjs";
import { selectComponentImages, selectOf } from "./catalog-select.mjs";
import { applyCatalogPreviewDeclarations } from "./catalog-preview-declarations.mjs";
import { completenessFailure } from "./completeness-gate.mjs";
import {
  exemptSemanticsPatterns,
  partitionExemptSemantics,
} from "./completeness-exemptions.mjs";
import {
  additionalBundleLiveConflict,
  bundleModulePath,
  claimedComponentIds,
  claimedPreviewFunctions,
  combinedBundleMap,
  combinedBundleEntries,
  generatedFallbackGroups,
  moduleArtifactKey,
  moduleIdentityPrefix,
  namespaceModuleRecords,
} from "./multi-module-catalog.mjs";

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
  for (const entry of await readdir(dir, {
    recursive: true,
    withFileTypes: true,
  })) {
    if (entry.isFile()) {
      out.push(
        relative(dir, join(entry.parentPath ?? entry.path, entry.name))
          .split("\\")
          .join("/"),
      );
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
async function loadCandidates(path, breakpoints) {
  const bundle = await readPreviewBundle(path);
  for (const preview of bundle.previews) {
    sanitizeNullSizes(preview.params);
    for (const capture of preview.captures ?? [])
      sanitizeNullSizes(capture.params);
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
  const candidates = bundleToCandidates(
    candidateBundle,
    (entry) => entry.functionName ?? entry.id,
  );
  // `@OverrideVariant` synthetic previews share their base's `functionName`, so `mergeByFunction`
  // (below) folds their images into the parent candidate. Stamp each one's `_VARIANT_<name>` suffix
  // as `image.state` HERE — before the merge — so the fold surfaces it as a distinct secondary
  // sticker (`state:<name>`) rather than an invisible duplicate of the default. This is the id→state
  // linchpin: the corrected `image.state` flows into `catalog.json`, which the static site and the
  // live catalog server (`ServeCatalogStore` → `variants.json` → `ServeWeb`) both fold off, so no
  // downstream change is needed. Mirrors the spec-driven `foldVariants` state tag for the
  // hand-written pressed/disabled state variants.
  //
  // A `@PreviewAxis` cell is the exception: its spec carries the full axis assignment, so it
  // publishes structured `props` instead of an opaque `state` — matching a reference by property
  // rather than by whether a hand-typed name coincides with the kit's naming. Props win over state
  // for such a cell (stamping both would double-count one render), so the axis pass runs first and
  // the state pass skips any image it claimed.
  const axisProps = applyVariantAxisProps(candidates, overridesByPreviewId(candidateBundle));
  if (axisProps.stamped > 0) {
    console.log(`[${basename(path)}] stamped @PreviewAxis props on ${axisProps.stamped} image(s)`);
  }
  for (const candidate of candidates) {
    const state = variantStateFromId(candidate.previewId ?? candidate.id);
    if (state) {
      for (const image of candidate.images ?? []) {
        if (!axisProps.claimed.has(image)) image.state = state;
      }
    }
  }
  // Candidate images retain width-derived size but not `PreviewParams.fontScale`. Promote a
  // non-default scale to a props axis before candidates sharing one function are folded, and remove
  // the exact small-round/default-font duplicate emitted when Wear stacks its device + font-scale
  // multi-previews. Synthetic state tagging above intentionally runs first so equal display params
  // never collapse distinct override states.
  // `bundle pack` stores entries + both manifests under a sanitised id while the candidate reader
  // hands back the raw discovery id, so every preview whose `@Preview(name = …)` contains a space
  // needs the manifest's raw→sanitised mapping to be found at all (see preview-id-alias.mjs).
  const previewAliases = previewIdAliases(bundle.manifest);
  applyCatalogPreviewAxes(candidates, candidateBundle.previews, previewAliases);
  // The published candidate reader classifies every numeric width with Material
  // window classes. Give this catalog's declared names precedence so domain
  // breakpoints such as Wear's 192 dp `smallRound` and 227 dp `largeRound` remain
  // distinct output axes instead of both collapsing to `compact`.
  applySpecBreakpoints(
    candidates,
    candidateBundle.previews,
    breakpoints,
    previewAliases,
  );
  // A `@Preview(device = …)` the breakpoints don't name leaves its render on the generic width
  // class, where it is indistinguishable from the sibling expansion that DID match — the collapse
  // that otherwise surfaces much later as an overwritten sticker or a duplicate-axis failure. Say so
  // here, while the fix (one more `breakpoints` entry) is still obvious.
  const undeclaredDevices = undeclaredBreakpointDevices(candidateBundle.previews, breakpoints);
  if (undeclaredDevices.length > 0) {
    console.warn(
      `[${basename(path)}] ${undeclaredDevices.length} @Preview device id(s) match no declared ` +
        `breakpoint, so their renders keep the generic width class: ${undeclaredDevices.join(", ")}. ` +
        `Add them to the spec's \`breakpoints\` (\`{ "size": …, "device": … }\`) to give each its ` +
        `own size axis.`,
    );
  }
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
    if (tree)
      out.set(fn, { layout: tree, density: preview.params?.density ?? 1 });
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
  const module = bundleModulePath(bundle);
  for (const preview of bundle.previews ?? []) {
    const fn = preview.functionName ?? preview.id;
    if (out.has(fn) && !prefer(preview.id)) continue;
    if (preview.sourceFile)
      out.set(fn, { sourceFile: preview.sourceFile, bodyLine: preview.bodyLine, module });
    else if (!out.has(fn))
      out.set(fn, { sourceFile: undefined, bodyLine: undefined, module });
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

/**
 * Identity of one deferred sticker across every axis a `deferred[]` record can carry, so a
 * recovered-from-the-bundle record (issue #2966) is deduped against the image-derived one for the
 * SAME sticker and not against a sibling variant that merely shares its mode. `props` is
 * key-sorted so two equal prop sets always produce the same key.
 */
function deferralAxisKey(theme, state, props, size) {
  const propsPart = props
    ? Object.entries(props)
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([k, v]) => `${k}=${v}`)
        .join(",")
    : "";
  // NUL-joined (like `bridge-live-preview-ids`' `variantKey`) so a state or prop value that
  // contains the separator can never make two different stickers collide on one key.
  const NUL = String.fromCharCode(0);
  return [theme ?? "", state ?? "", propsPart, size ?? ""].join(NUL);
}

/** A semantics tree carries real signal (not the empty `{ root: {} }` fallback). */
function hasSemantics(candidate) {
  const tree = candidate.semantics;
  if (!tree) return false;
  if (tree.themeTokens) return true;
  const r = tree.root;
  return Boolean(
    r &&
    ((r.children && r.children.length > 0) ||
      r.role ||
      r.label ||
      r.bounds ||
      r.tokens),
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
  const merged = {
    componentId: a.componentId,
    images: [...a.images, ...b.images],
    semantics,
  };
  if (a.previewId ?? b.previewId) merged.previewId = a.previewId ?? b.previewId;
  if (a.functionName ?? b.functionName)
    merged.functionName = a.functionName ?? b.functionName;
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
 * flagged so the completeness gate can refuse to publish. An entry that declares
 * `"capture": "none"` and rendered no static sticker is reported on the separate
 * `noSticker` list instead of `missing` — a declared, non-blocking gap.
 */
function catalogFromCandidates(candidates, spec, opts = {}) {
  const byFunction = new Map();
  for (const candidate of candidates) {
    const fn = functionOf(candidate);
    const existing = byFunction.get(fn);
    byFunction.set(
      fn,
      existing ? mergeByFunction(existing, candidate) : candidate,
    );
  }

  // Preview id → the theme that id's candidate resolved, read BEFORE the per-function merge
  // flattens the candidates into one image list. This is what pairs a motion capture with the
  // themed card it accompanies: a capture knows its preview id, and its sibling still's theme is
  // the theme of the candidate carrying that id. Taken from the candidate rather than re-derived
  // from the id's `_Dark` / `_Light` suffix, so there stays exactly one implementation of the
  // mode-naming rule (`catalog-themes.mjs` owns it). See foldMotion.
  const themeByPreviewId = new Map();
  for (const candidate of candidates) {
    const id = candidate?.componentId;
    if (!id) continue;
    const theme = (candidate.images ?? []).find((image) => image?.theme)?.theme;
    if (theme !== undefined) themeByPreviewId.set(id, theme);
  }

  const sources = [];
  const missing = [];
  const noSticker = [];
  const withoutSemantics = [];
  // The motion axis, kept BESIDE the sources as well as on them, so the written manifest can be
  // checked against what the join actually resolved. `buildCatalog` builds its components from an
  // allow-list and drops any field it hasn't been taught — which is precisely how this axis went
  // missing, silently, before the pin carried it. See motion-carried.mjs.
  const motionByComponentId = new Map();
  // Live-only coverage: entries and image axes the spec deferred (issue #2950). Recorded so
  // catalog.json still declares them — they are not lost coverage, just not rasterised here — and
  // deliberately kept OUT of `missing` / `withoutSemantics`, which is the whole point: the gate
  // stays strict over the required inventory instead of being switched off wholesale with
  // `--allow-incomplete`.
  const deferred = [];
  for (const group of spec.groups) {
    for (const specComponent of group.components) {
      // A wholly-deferred entry is never rendered (its `@Preview` may not even have been packed
      // with a PNG), so it short-circuits before the candidate lookup — reporting it missing is
      // exactly the false failure this feature exists to remove.
      if (entryPriority(specComponent) === DEFERRED) {
        // `"capture": "none"` outranks deferral, for both the entry and its variants below. The two
        // axes answer different questions — deferral picks the LANE (bake now vs. render on the serve
        // host), `capture` says the preview yields no sticker on ANY lane — so a declared-uncapturable
        // preview has nothing for the live lane to serve either. Recording it live-only would invent
        // a card for coverage the spec says doesn't render, and the required path already classifies
        // the identical entry as `noSticker` (foldVariants); the two must agree.
        if (exportsNoSticker(specComponent)) {
          noSticker.push(specComponent.componentId);
        } else {
          deferred.push({
            componentId: specComponent.componentId,
            group: group.name,
            ...(group.section !== undefined ? { section: group.section } : {}),
            ...(specComponent.caption !== undefined ? { caption: specComponent.caption } : {}),
            preview: specComponent.preview,
            reason: "entry",
          });
        }
        // Its variants are deferred with it (`variantPriority` inherits), and each needs its OWN
        // record: a variant's sticker is normally folded onto the component's images, and there is no
        // `components[]` entry left to fold onto. Recording them here is what keeps them reachable on
        // the live lane instead of dropping out of the publish unnoticed — the short-circuit below
        // means nothing else in this loop will see them.
        for (const variant of specComponent.variants ?? []) {
          if (exportsNoSticker(variant)) {
            // Same label shape `foldVariants` uses for the required path, from the same helper, so a
            // reader can't tell from the report which lane the entry took.
            noSticker.push(`${specComponent.componentId} [${variantLabel(variant)}]`);
            continue;
          }
          deferred.push({
            componentId: specComponent.componentId,
            group: group.name,
            ...(group.section !== undefined ? { section: group.section } : {}),
            preview: variant.preview,
            reason: "variant",
            ...(variant.state !== undefined ? { state: variant.state } : {}),
            ...(variant.props !== undefined ? { props: variant.props } : {}),
            ...(variant.theme !== undefined ? { theme: variant.theme } : {}),
          });
        }
        continue;
      }
      // Fold only the REQUIRED variants; each deferred one is recorded live-only instead of being
      // looked up (and then reported missing) below.
      const { component, deferredVariants } = splitDeferredVariants(specComponent);
      for (const variant of deferredVariants) {
        deferred.push({
          componentId: component.componentId,
          group: group.name,
          preview: variant.preview,
          reason: "variant",
          ...(variant.state !== undefined ? { state: variant.state } : {}),
          ...(variant.props !== undefined ? { props: variant.props } : {}),
          ...(variant.theme !== undefined ? { theme: variant.theme } : {}),
        });
      }
      const candidate = byFunction.get(component.preview);
      if (!candidate || candidate.images.length === 0) {
        // `"capture": "none"` is the spec's way of declaring a preview that has no static sticker to
        // join on (an `AndroidView`-hosted composable, a scrolling GIF, …). The entry is still absent
        // from the sheet, but it is a DECLARED absence — reported separately so the completeness gate
        // doesn't sink the publish over it. See capture-mode.mjs / issue #2946.
        if (exportsNoSticker(component)) noSticker.push(component.componentId);
        else missing.push(component.componentId);
        continue;
      }
      // An entry may `select` ONE value of a multipreview's fan-out, so two entries can share a
      // `@Preview` function and still be separate cards with their own ids and captions — the
      // alternative being to split the function in the module (see catalog-select.mjs).
      const select = selectOf(component);
      const { images: selected, missing: unselected } = selectComponentImages(component, candidate);
      if (unselected) {
        missing.push(unselected);
        continue;
      }
      if (!hasSemantics(candidate))
        withoutSemantics.push(component.componentId);
      // Fold the component's state `variants` (pressed / focused / disabled / off
      // / …) onto the default render: the default images stay the grid hero, each
      // variant's render is appended re-tagged with its `state` so the single-
      // component view can show them as secondary previews. A variant preview that
      // didn't render is reported as missing so the completeness gate still fires.
      const {
        ideal,
        missing: missingVariants,
        noSticker: noStickerVariants,
      } = foldVariants(selected, component, byFunction);
      missing.push(...missingVariants);
      noSticker.push(...noStickerVariants);
      // Thin the palette fan-out per `modePriority`: a themed sticker whose mode is deferred is
      // dropped from the baked set (so no PNG is written and the Figma/static kit stays lean) and
      // recorded live-only. Only stickers that NAME a theme are eligible, so every component keeps
      // its untagged primary render.
      const { baked, deferred: deferredImages } = splitDeferredImages(ideal, spec);
      for (const image of deferredImages) {
        deferred.push({
          componentId: component.componentId,
          group: group.name,
          preview: previewForImage(component, image),
          reason: "mode",
          theme: image.theme,
          ...(image.state !== undefined ? { state: image.state } : {}),
          ...(image.props !== undefined ? { props: image.props } : {}),
          ...(image.size !== undefined ? { size: image.size } : {}),
        });
      }
      // Modes whose render was SKIPPED, not merely un-published (issue #2966). Once the render
      // filter drops a deferred palette, its images never reach the candidate join (the join only
      // sees previews that produced a PNG), so `splitDeferredImages` above has nothing to record and
      // the coverage would vanish from `catalog.json` instead of being declared live-only. Recover it
      // from the bundle's full preview list, which carries every SELECTED preview whether or not CI
      // rasterised it — the same listing the live lane resolves against. Deduped against the modes
      // already accounted for, so an unfiltered render (a local generate, say) records each once.
      //
      // Keyed by the FULL axis tuple, not by mode alone, and walked over the component's own
      // `@Preview` plus each REQUIRED variant's: a required state/props variant whose function also
      // fans out by mode has its deferred-mode ids excluded from the render too, and recording only
      // the base function's would drop that variant's `state`/`props` from the declaration (a record
      // an unfiltered run produced via `splitDeferredImages`). Deferred variants are already recorded
      // above, so they are deliberately not revisited here.
      const seenAxes = new Set(
        [...deferredImages, ...baked]
          .filter((image) => image.theme)
          .map((image) => deferralAxisKey(image.theme, image.state, image.props, image.size)),
      );
      // A `select`ed entry covers ONE breakpoint of its function, so its deferred-mode record has to
      // name that size — otherwise the sibling entry selecting the other breakpoint dedupes against
      // the same axis key and only one of the two is declared live-only.
      const modeSources = [
        { preview: component.preview, size: select?.size },
        ...(component.variants ?? []).map((v) => ({
          preview: v.preview,
          state: v.state,
          props: v.props,
          size: selectOf(v)?.size ?? v.size,
        })),
      ];
      for (const source of modeSources) {
        if (!source.preview) continue;
        for (const previewId of opts.previewIdsByFunction?.get(source.preview) ?? []) {
          const mode = modeOfPreviewId(previewId, spec.modes);
          if (!mode || modePriority(spec, mode) !== DEFERRED) continue;
          const key = deferralAxisKey(mode, source.state, source.props, source.size);
          if (seenAxes.has(key)) continue;
          seenAxes.add(key);
          deferred.push({
            componentId: component.componentId,
            group: group.name,
            preview: source.preview,
            reason: "mode",
            theme: mode,
            ...(source.state !== undefined ? { state: source.state } : {}),
            ...(source.props !== undefined ? { props: source.props } : {}),
            ...(source.size !== undefined ? { size: source.size } : {}),
          });
        }
      }
      if (baked.length === 0) {
        // Every one of this component's renders was mode-deferred — it would publish as a
        // component with no pixels at all. That is a misconfiguration (a `modePriority` that
        // defers the mode a component renders in exclusively), not a deferral, so fail it.
        missing.push(component.componentId);
        continue;
      }
      const source = {
        componentId: component.componentId,
        group: group.name,
        ideal: baked,
      };
      // The component's animated captures, alongside (never inside) its stills — see
      // catalog-motion.mjs for why `images[]` is the wrong home for a 114-frame recording. Read off
      // the component's own `@Preview` function by default, or its explicit `motionPreview` when
      // the static sticker and GIF need separate functions. A state variant's motion capture is
      // suppressed at discovery, so there is none to collect, and folding the variants' would
      // publish duplicates of one script anyway.
      const motion = foldMotion(
        baked,
        motionArtifactsFor(opts.motionBundle, motionPreviewFor(component)),
        opts.previewCellsByFunction?.get(component.preview),
        opts.previewCellsByFunction?.get(motionPreviewFor(component)),
        themeByPreviewId,
      );
      if (motion.length > 0) {
        source.motion = motion;
        motionByComponentId.set(component.componentId, motion);
      }
      // A group may declare a top-level `section` (the tab the preview server
      // buckets it under: Themes / Components / Screens / Animations / …). It sits
      // one level above `group`, which becomes the sub-heading inside a tab.
      // Absent ⇒ an untabbed flat catalog, as before.
      if (group.section !== undefined) source.section = group.section;
      if (component.caption !== undefined) source.caption = component.caption;
      if (component.reference !== undefined)
        source.reference = component.reference;
      // The component FAMILY `reference` is one variant of. `reference` stays the single node a
      // parity run diffs this sticker against; `referenceSet` is what a whole-screen import matches
      // an instance through, since a screen rarely uses the exact variant the catalog pictured.
      if (component.referenceSet !== undefined)
        source.referenceSet = component.referenceSet;
      if (component.referenceSet !== undefined)
        source.referenceSet = component.referenceSet;
      // The stated reason there is NO reference — distinct from an absent `reference`, which says
      // only that nobody has looked. Both this and `referenceSet` are preserved by
      // `@design-parity/catalog-export` from the release that added them; on an older pinned
      // package `buildCatalog` drops them and the fields stop here. Harmless (the catalog is
      // shaped exactly as before), but it does mean the annotation only reaches `catalog.json`
      // once package.json + the lockfile move — see the note on the dependency pin.
      if (component.noReference !== undefined)
        source.noReference = component.noReference;
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
    // Presentation hints the system declares (stage surface + hero preview),
    // carried through onto catalog.json so the preview server reads them instead
    // of inferring — see catalog.spec.schema.json `display`.
    ...(spec.display ? { display: spec.display } : {}),
  };

  // `opts.themes` is forwarded explicitly: this join is a VENDORED copy of the published
  // `catalogFromCandidates` (see the file header), so an option the package's version understands
  // is silently dropped here unless it is threaded through by hand. Missing it published a catalog
  // with no `themes[]` while the run logged that it was publishing them.
  const catalog = buildCatalog(meta, sources, opts.themeTokens, opts.themes);
  return { catalog, missing, noSticker, withoutSemantics, deferred, motionByComponentId };
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
    // Additional independent module bundles for repository-wide publication. Unlike
    // `--extra-renders`, these do not override the primary module: their candidates are appended,
    // duplicate function names are namespaced deterministically, and uncurated renders receive a
    // generated module/preview-group inventory.
    "additional-renders": { type: "string", multiple: true },
    "generate-fallbacks": { type: "boolean", default: false },
    // When set, copy the `--renders` executable bundle onto the branch under
    // `out/bundle/<file>` and record `liveBundle: {path, file}` in catalog.json.
    // `compose-preview serve --catalogs --allow-render-trusted` then fetches that
    // bundle and launches a render daemon straight from it (no source build /
    // checkout). Only for systems whose bundle is a DESKTOP bundle serve can run
    // (compose-m3); the Android catalogs (wear/remote) stay baked-PNG.
    "publish-live-bundle": { type: "boolean", default: false },
    // Give `--extra-renders` its own live lane instead of treating it as a
    // pixels-only supplement.
    //
    // Without this, EVERY function the extra bundle carries is marked overridden and
    // skipped by the live-preview bridge, so none of its stickers get a `previewId`
    // and `ServeCatalogStore` can build no alias for them — they browse as baked PNGs
    // and the viewer shows "Pre-rendered snapshot — overrides need the live server".
    // That is right for a function the extra bundle genuinely OVERRIDES (its pixels
    // differ from what the primary daemon would draw), but wrong for one it only ADDS:
    // nothing was overridden, and the extra bundle can render it itself.
    //
    // With this set, only the true overrides (functions present in BOTH bundles) stay
    // unbridged; extra-ONLY functions are aliased to their daemon ids in the extra
    // bundle, and the extra bundle is externalised alongside the primary so the
    // workflow can split it per preview with a runnable classpath. Serve reaches those
    // ids through the per-preview lane — its shared monolithic daemon answers
    // `NotFound` for an id it never listed and `renderDaemon` falls through to the
    // pool (see ServeCatalogLiveHost.renderDaemon).
    //
    // Requires --extra-renders and --publish-live-bundle: the per-preview bundles are
    // fetched from the same `bundle/` prefix the primary live bundle declares, so
    // without a declared liveBundle serve never opens the lane that would reach them.
    "extra-live-bundle": { type: "boolean", default: false },
    // Optional source provenance. Repo + ref write `source` for GitHub links; adding
    // --source-module also makes it buildable by a TRUSTED `serve --allow-render-trusted` box.
    // Repository-wide catalogs omit the one catalog-level module and stamp it per component.
    "source-repo": { type: "string" },
    "source-ref": { type: "string" },
    "source-module": { type: "string" },
    // Publish a partially complete render (missing previews or absent semantics).
    // A total render miss still fails so it cannot replace a good delivery branch
    // with an empty catalog.
    "allow-incomplete": { type: "boolean", default: false },
  },
});

if (!values.spec || !values.renders || !values.out) {
  console.error(
    "usage: generate-design-catalog --spec <catalog.spec.json> --renders <dir|zip> --out <dir> [--renderer <s>] [--preview-base <url>]",
  );
  process.exit(2);
}

const additionalLiveConflicts = additionalBundleLiveConflict(values);
if (additionalLiveConflicts) {
  console.error(
    `--additional-renders cannot be combined with ${additionalLiveConflicts.join(" or ")} — ` +
      `repository-wide catalogs publish baked module bundles and have no single executable ` +
      `live bundle or source module.`,
  );
  process.exit(2);
}

// Fail loudly rather than publish a catalog whose extra-only stickers claim a live lane
// nothing can serve. Both prerequisites are silent when violated: with no --extra-renders
// there is no second bundle to alias against, and with no --publish-live-bundle serve
// never opens the per-preview lane the aliases resolve through, so every one of those
// previews would 404 its live render instead of falling back to baked pixels.
if (values["extra-live-bundle"]) {
  const missing = [
    !values["extra-renders"] && "--extra-renders",
    !values["publish-live-bundle"] && "--publish-live-bundle",
  ].filter(Boolean);
  if (missing.length > 0) {
    console.error(
      `--extra-live-bundle requires ${missing.join(" and ")} — the extra bundle's per-preview ` +
        `splits are served from the primary liveBundle's prefix, so both must be present.`,
    );
    process.exit(2);
  }
}

const specPath = resolve(values.spec);
const rendersPath = resolve(values.renders);
const outPath = resolve(values.out);

const spec = JSON.parse(await readFile(specPath, "utf8"));
const effectiveBreakpoints = catalogBreakpoints(spec);
if (effectiveBreakpoints !== undefined) spec.breakpoints = effectiveBreakpoints;
// Read candidates with componentId resolved to the `@Preview` function name so
// the join folds a function's theme/size multipreview variants (whose ids differ
// only by an appended `_<mode>`) onto one component. See `loadCandidates` (which
// also works around the published null-widthDp crash) and the vendored join.
const primaryRecord = {
  ...(await loadCandidates(rendersPath, spec.breakpoints)),
  renderPath: rendersPath,
};
const additionalRecords = [];
for (const path of values["additional-renders"] ?? []) {
  const renderPath = resolve(path);
  additionalRecords.push({
    ...(await loadCandidates(renderPath, spec.breakpoints)),
    renderPath,
  });
}
const moduleRecords = namespaceModuleRecords(primaryRecord, additionalRecords);
let { candidates, bundle } = moduleRecords[0];
const additionalBundles = moduleRecords.slice(1).map((record) => record.bundle);
candidates = moduleRecords.flatMap((record) => record.candidates);

// Fold a supplementary render bundle in, overriding same-named functions. Lets an
// Android-only render (the material3 1.5.0-alpha inset focus ring, which CMP can't
// draw) replace the CMP module's render of that function — so a mostly-CMP catalog
// still carries the real focus-ring variant. The supplement's render + semantics win.
// Functions whose render was replaced by the Android-only supplement. Their baked pixels differ
// from what the desktop daemon (`bundle.previews`) would draw (e.g. the focus ring CMP can't paint),
// so the live-preview bridge below MUST NOT map them to a desktop preview — they stay baked-only.
const overriddenFunctions = new Set();
let extraBundle = null;
if (values["extra-renders"]) {
  const { candidates: extra, bundle: extraRenderBundle } = await loadCandidates(
    resolve(values["extra-renders"]),
    spec.breakpoints,
  );
  extraBundle = extraRenderBundle;
  const overridden = new Set(extra.map(functionOf));
  // Which of those the supplement actually REPLACED, as opposed to added — see
  // extra-render-fold.mjs. Read `candidates` before the splice below, while it is still
  // exactly the primary bundle's functions.
  const primaryFunctions = candidates.map(functionOf);
  const extraFunctions = [...overridden];
  const unbridgeable = unbridgeableFunctions(
    primaryFunctions,
    extraFunctions,
    values["extra-live-bundle"],
  );
  for (const fn of unbridgeable) overriddenFunctions.add(fn);
  for (let i = candidates.length - 1; i >= 0; i--) {
    if (overridden.has(functionOf(candidates[i]))) candidates.splice(i, 1);
  }
  candidates.push(...extra);
  console.log(
    `[${spec.system}] folded ${extra.length} extra render(s), overriding: ` +
      `${[...overridden].join(", ")}`,
  );
  if (values["extra-live-bundle"]) {
    const added = extraOnlyFunctions(primaryFunctions, extraFunctions);
    console.log(
      `[${spec.system}] extra live lane: ${added.length} extra-only function(s) keep a live ` +
        `lane, ${unbridgeable.size} true override(s) stay baked-only`,
    );
  }
}

const allBundles = [bundle, extraBundle, ...additionalBundles].filter(Boolean);

// Annotation-derived inventory (compose-ai-tools Phase 2): components discovery
// resolved from `@CatalogComponent` / `@CatalogVariant` / `@CatalogGroup` travel on
// each preview's `catalog` field in `previews.json`. Build the inventory from them
// and layer the committed `catalog.spec.json` on top as the override, so the spec
// only has to carry the cover-sheet fields plus any per-component tweak. The result
// is written back onto `spec.groups`, so the vendored join and every downstream
// consumer (section stamping, wireframes, code-connect) see one effective spec.
//
// A catalog whose module doesn't use the annotations yet (every catalog today —
// the annotations aren't in a released runtime) yields an empty inventory, so the
// merge is a strict no-op and `spec.groups` is untouched: zero behaviour change
// until a module opts in.
//
// Include the `--extra-renders` supplement's previews too: that bundle can carry an
// annotated component the primary bundle doesn't (an Android-only render whose
// function lives only in the supplement), and its candidate is already folded into
// `candidates` above — so its `@CatalogComponent` must reach the inventory as well,
// or the component would render but never enter `spec.groups`. Primary previews come
// first, so a component present in both dedupes to the primary's annotation.
const inventoryPreviews = allBundles.flatMap((renderBundle) => renderBundle.previews ?? []);
const { groups: annotationGroups, orphanVariants, withoutBreakpoints } =
  inventoryFromPreviews(inventoryPreviews, { breakpoints: catalogBreakpoints(spec) });
if (withoutBreakpoints.length > 0) {
  // `perBreakpoint` asked for a card per breakpoint but no render resolved to one — an undeclared
  // device, or a catalog with no `breakpoints` table at all. The component is kept WHOLE rather
  // than dropped, so this is a coverage warning, not a failure; `undeclaredBreakpointDevices`
  // above usually names the culprit device on the same run.
  console.warn(
    `[${spec.system}] ${withoutBreakpoints.length} @CatalogComponent(perBreakpoint = true) ` +
      `component(s) resolved no breakpoint, so each stays a single card: ` +
      `${withoutBreakpoints.join(", ")}. Declare the devices they render at in the spec's ` +
      `\`breakpoints\`.`,
  );
}
if (orphanVariants.length > 0) {
  console.warn(
    `[${spec.system}] ${orphanVariants.length} @CatalogVariant(s) name a parent component that ` +
      `carries no @CatalogComponent: ` +
      orphanVariants.map((v) => `${v.preview}→${v.parentId}`).join(", "),
  );
}
if (annotationGroups.length > 0) {
  const specComponentCount = (spec.groups ?? []).reduce(
    (n, g) => n + (g.components?.length ?? 0),
    0,
  );
  spec.groups = mergeCatalogGroups(annotationGroups, spec.groups ?? []);
  const mergedCount = spec.groups.reduce((n, g) => n + (g.components?.length ?? 0), 0);
  console.log(
    `[${spec.system}] merged annotation inventory: ${annotationGroups.reduce((n, g) => n + g.components.length, 0)} ` +
      `annotated component(s) + ${specComponentCount} spec component(s) → ${mergedCount} total`,
  );
}

// Repository-wide mode publishes every rendered preview, even when the hand-authored spec curates
// only a primary catalog module. Annotation/spec entries stay authoritative; generated entries are
// added only for function names neither inventory names. Single-module and legacy extra-module
// calls never enter this block, so their output remains byte-for-byte shaped as before.
if (values["generate-fallbacks"]) {
  const fallbackGroups = generatedFallbackGroups(
    moduleRecords,
    claimedPreviewFunctions(spec.groups ?? []),
    claimedComponentIds(spec.groups ?? []),
  );
  if (fallbackGroups.length > 0) {
    spec.groups = [...(spec.groups ?? []), ...fallbackGroups];
    const count = fallbackGroups.reduce((n, group) => n + group.components.length, 0);
    console.log(
      `[${spec.system}] generated ${count} fallback component(s) across ` +
        `${fallbackGroups.length} module/preview group(s)`,
    );
  }
}

// Zero effective inventory: the spec declares no `groups` AND the render carried no
// `@CatalogComponent` annotations (an author forgot them, or the render ran with a CLI/plugin that
// predates catalog metadata). Fail here with a clear, actionable message rather than letting the
// join iterate an undefined `spec.groups` and die with a late TypeError after the expensive render.
if (!Array.isArray(spec.groups) || spec.groups.length === 0) {
  console.error(
    `[${spec.system}] no catalog inventory: catalog.spec.json declares no \`groups\` and the ` +
      `render carried no @CatalogComponent annotations. Add @CatalogComponent / @CatalogVariant to ` +
      `the module's @Preview functions, or declare \`groups\` in the spec. (If the render is right ` +
      `but the metadata is missing, the CLI/plugin that produced this bundle may predate ` +
      `catalog-annotations discovery.)`,
  );
  process.exit(1);
}

// Order the groups by the spec's cover-sheet `groupOrder` (a list of group names), so a catalog can
// keep its whole inventory in annotations yet still control the group/tab display order — which is
// presentation config, not per-component code metadata. The annotation-derived order is source
// first-seen, which can't express the intended order when a module's source order differs. A no-op
// when `groupOrder` is absent, so catalogs that don't set it are unchanged.
spec.groups = applyGroupOrder(spec.groups, spec.groupOrder);

const renderFailures = renderFailuresFromBundles(allBundles, spec);
if (renderFailures.length > 0) {
  const signatures = new Set(renderFailures.map((f) => `${f.errorClass}\u0000${f.message}`));
  console.warn(
    `[${spec.system}] ${renderFailures.length} failed render(s), ${signatures.size} distinct ` +
      `error signature(s) — recorded in catalog.json`,
  );
}

// Resolve `display.hero` against the REAL inventory, now that annotation and spec are merged.
// The build-free pre-flight can only check the ids a source scan can see, and a `perBreakpoint`
// component's ids come from its renders — so this is the one place every id is known. A hero that
// matches nothing isn't fatal (the serve host falls back to another representative), but it is
// silently NOT the front door the catalog asked for, which is exactly the kind of thing that goes
// unnoticed for months.
const heroId = spec.display?.hero;
if (typeof heroId === "string" && heroId.length > 0) {
  const heroCandidates = new Set([
    ...(spec.groups ?? []).flatMap((group) =>
      (group.components ?? []).flatMap((c) => [c.componentId, c.preview]),
    ),
  ]);
  if (!heroCandidates.has(heroId)) {
    console.warn(
      `[${spec.system}] display.hero "${heroId}" matches no componentId or @Preview function in ` +
        `the merged inventory, so the serve host will pick its own hero. A per-breakpoint ` +
        `component is named "<id>/<breakpoint>" (e.g. "${heroId}/largeRound").`,
    );
  }
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

// The module's DECLARED themes (`@ThemeCatalog` / `@WearThemeCatalog`), each with the token set its
// own specimen render resolved — the axis above is the *system*'s one theme, this is every other
// one it ships. The renderer already wrote them into the bundle (compose-ai-tools#2179); the reader
// keys them by theme (design-parity#313); this publishes each as `themes/<slug>.dtcg.json` so a
// consumer can show what a theme IS — a picker chip painted in its own colours and typeface, a
// per-theme Figma variable mode — instead of only what it is called.
const declaredThemes = catalogThemesFromBundle(
  bundle,
  themeTokenSetsFromBundle(bundle),
  (previewId, theme) =>
    console.warn(
      `[${spec.system}] declared theme ${theme || "(unnamed)"} (${previewId}) resolved no provider ` +
        `FQN, so it is NOT published: a themes[] entry keyed on anything else cannot be joined to ` +
        `the theme a preview server is showing.`,
    ),
);
if (declaredThemes.length > 0) {
  console.log(
    `[${spec.system}] publishing ${declaredThemes.length} declared theme token set(s): ` +
      declaredThemes.map((t) => t.name || t.id).join(", "),
  );
}

// Resolve the installed `@design-parity/catalog-export` version so the catalog records the export
// engine that built it (surfaced on the serve host's provenance strip). Best-effort: a resolution
// failure just omits the field rather than sinking the render.
function designParityVersion() {
  return installedPackageVersion(
    "@design-parity/catalog-export",
    import.meta.url,
  );
}

// Render priority needs a live path or it isn't a cheaper build — it is coverage quietly missing
// from the published sheet. A deferred entry only resolves where the serve host can re-render it:
// a carried live bundle (`--publish-live-bundle`) or a buildable `source` (`--source-module`,
// which a `--allow-render-trusted` box builds). Refuse here rather than publish a thinner catalog
// than the spec describes. Mirrored (leniently, since it can't know the publish flags) by
// `validateSpec`'s `liveBundle` option in the build-free pre-flight.
if (specDefersAnything(spec) && !values["publish-live-bundle"] && !values["source-module"]) {
  console.error(
    `[${spec.system}] this spec defers coverage (\`priority: "deferred"\` / \`modePriority\`) but ` +
      `neither --publish-live-bundle nor --source-module was passed — the deferred entries would ` +
      `simply be absent from the published catalog, with no live path to produce them. Publish ` +
      `with a live bundle or a buildable source, or drop the deferral from catalog.spec.json.`,
  );
  process.exit(1);
}
if (specDefersAnything(spec)) {
  const plan = deferralPlan(spec);
  console.log(
    `[${spec.system}] render priority: ${plan.entries} deferred entry/entries, ` +
      `${plan.variants} deferred variant(s), deferred mode(s): ` +
      `${plan.modes.length > 0 ? plan.modes.join(", ") : "none"}` +
      (plan.deferredPreviews.length > 0
        ? `; @Preview function(s) droppable from the render: ${plan.deferredPreviews.join(", ")}`
        : ""),
  );
}

const { catalog, missing, noSticker, withoutSemantics, deferred, motionByComponentId } =
  catalogFromCandidates(candidates, spec, {
    ...(values.renderer ? { renderer: values.renderer } : {}),
    ...(designParityVersion() ? { designParity: designParityVersion() } : {}),
    ...(themeTokens ? { themeTokens } : {}),
    ...(declaredThemes.length > 0 ? { themes: declaredThemes } : {}),
    // Every daemon preview id per function, from the bundles' FULL preview lists — including the
    // deferred palettes whose render was skipped (#2966), which is how their live-only coverage still
    // gets declared even though no image of them exists to fold.
    previewIdsByFunction: daemonPreviewIdsByFunction(allBundles),
    // The same fan-outs with their render parameters. A separately named motion function may
    // declare annotations in a different order, so theme inheritance joins by axis identity rather
    // than zipping the two id arrays.
    previewCellsByFunction: daemonPreviewCellsByFunction(allBundles),
    // Motion artifacts are read from every catalog module. Keep the legacy extra-render supplement
    // out: it fills in stills the primary could not produce and does not own motion captures.
    motionBundle: [bundle, ...additionalBundles],
  });

// Completeness gate: `bundle pack --with-semantics` is best-effort and exits 0
// even when the daemon never started or captured zero semantics. For a scheduled
// job that force-pushes a delivery branch, refuse to publish an incomplete render
// (missing previews, or pixels with no semantics → no tokens/contrast/greenlines)
// so a transient failure can't clobber a good branch. `--allow-incomplete` permits
// partial output, but a total render miss always fails.
if (missing.length > 0) {
  console.warn(`[${spec.system}] missing renders for: ${missing.join(", ")}`);
  console.warn(
    `[${spec.system}] a component that legitimately has no static sticker (an AndroidView-hosted ` +
      `composable, a scrolling GIF, …) can declare \`"capture": "none"\` in the spec — it is then ` +
      `reported as a declared sticker-less entry instead of a missing render.`,
  );
}
// Declared sticker-less entries: excluded from the sticker sheet by design, but named on every run so
// the coverage gap stays visible rather than disappearing with the spec entry.
if (noSticker.length > 0) {
  console.warn(
    `[${spec.system}] declared no sticker (capture: "none"), none exported for: ` +
      noSticker.join(", "),
  );
}
// Semantics-less renders a catalog declared as expected (issue #4117): repository-wide discovery
// sweeps in synthetic Activity renders that capture no semantics by their nature, and there is no
// `components[]` line to withhold for an entry discovery invented. The exempt ids keep their place
// in the catalog — their pixels are fine — and are counted separately, so the gate stays strict over
// everything else instead of being switched off wholesale with `--allow-incomplete`.
const {
  counted: countedWithoutSemantics,
  exempt: exemptWithoutSemantics,
  unusedPatterns: unusedExemptions,
} = partitionExemptSemantics(withoutSemantics, exemptSemanticsPatterns(spec));
if (countedWithoutSemantics.length > 0) {
  console.warn(
    `[${spec.system}] no semantics for: ${countedWithoutSemantics.join(", ")}`,
  );
}
if (exemptWithoutSemantics.length > 0) {
  console.log(
    `[${spec.system}] ${exemptWithoutSemantics.length} render(s) exempt from the semantics gate ` +
      `(completeness.exemptSemantics): ${exemptWithoutSemantics.join(", ")} — kept in ` +
      `catalog.json, not counted against the completeness gate`,
  );
}
// A pattern matching nothing is named rather than tolerated: an exemption left behind by a renamed
// or deleted preview would otherwise sit there silently, ready to excuse a render it was never
// written for.
if (unusedExemptions.length > 0) {
  console.warn(
    `[${spec.system}] completeness.exemptSemantics pattern(s) matched no semantics-less render: ` +
      `${unusedExemptions.join(", ")} — every render they name either captured semantics or is no ` +
      `longer in the catalog, so the exemption can be dropped`,
  );
}
if (deferred.length > 0) {
  const byReason = deferred.reduce((acc, d) => {
    acc[d.reason] = (acc[d.reason] ?? 0) + 1;
    return acc;
  }, {});
  console.log(
    `[${spec.system}] ${deferred.length} deferred (live-only) render(s): ` +
      Object.entries(byReason)
        .map(([reason, n]) => `${n} by ${reason}`)
        .join(", ") +
      ` — recorded in catalog.json, not baked and not counted against the completeness gate`,
  );
}

const completenessFailureReason = completenessFailure({
  allowIncomplete: values["allow-incomplete"],
  resolvedCount: catalog.components.length,
  missingCount: missing.length,
  withoutSemanticsCount: countedWithoutSemantics.length,
});
if (completenessFailureReason === "empty") {
  console.error(
    `[${spec.system}] zero catalog components resolved a render — refusing to publish. ` +
      `--allow-incomplete permits a partial catalog, not an empty one.`,
  );
  process.exit(1);
}
if (completenessFailureReason === "incomplete") {
  console.error(
    `[${spec.system}] incomplete render — refusing to publish. ` +
      `Re-run the render, or pass --allow-incomplete to override.`,
  );
  // Point at the per-entry escape hatch before the wholesale one: a render that captures no
  // semantics *by its nature* (a synthetic Activity frame with no data and no network) is declarable,
  // and declaring it keeps the gate strict over everything else.
  if (countedWithoutSemantics.length > 0) {
    console.error(
      `[${spec.system}] a render that legitimately captures no semantics (a synthetic Activity ` +
        `frame, …) can be named in the spec's \`completeness.exemptSemantics\` — it then stays in ` +
        `the catalog and is reported separately instead of failing this gate.`,
    );
  }
  process.exit(1);
}

// Images in the bundle are relative to the render dir; resolve them from there.
const sourceRoot = rendersPath.endsWith(".zip")
  ? dirname(rendersPath)
  : rendersPath;
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
  values["preview-base"] ||
  process.env.PREVIEW_SERVER_BASE ||
  DEFAULT_PREVIEW_BASE;

// The repo whose `design-artifacts/<system>` branch this bundle publishes to — it
// owns the htmlpreview links (index/compare/matches) the README emits and the raw
// asset URLs the cross-system page bakes. Comes from `--source-repo` (each
// design-artifacts workflow passes its own `${GITHUB_REPOSITORY}`), then
// $GITHUB_REPOSITORY, and finally this repo. Getting this right is why a bundle
// published from a *sibling* repo (e.g. meshcore-mobile, which runs this generator
// against a compose-ai-tools checkout) links its README at its OWN branch instead
// of 404-ing against compose-ai-tools.
const repo =
  values["source-repo"] ||
  process.env.GITHUB_REPOSITORY ||
  repoFromGitRemote() ||
  fallbackRepo();

/**
 * `<owner>/<repo>` from the checkout's `origin` remote, or null.
 *
 * Covers the local/manual run: CI always exports $GITHUB_REPOSITORY, but a
 * developer or agent generating a catalog by hand from a consumer repo has
 * neither that nor `--source-repo`, and silently inheriting compose-ai-tools
 * bakes README/asset links that RESOLVE but point at the wrong repository —
 * worse than a 404, because nothing looks broken.
 *
 * owner/repo are the last two path segments of every GitHub remote form
 * (`git@github.com:owner/repo.git`, `https://github.com/owner/repo`), and of
 * the proxied remotes agent sandboxes rewrite to.
 */
function repoFromGitRemote() {
  try {
    const url = execFileSync("git", ["config", "--get", "remote.origin.url"], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    }).trim();
    const match = url.replace(/\.git$/, "").match(/[:/]([^/:]+)\/([^/]+)$/);
    return match ? `${match[1]}/${match[2]}` : null;
  } catch {
    // Not a git checkout, or no origin — fall through to the warned default.
    return null;
  }
}

/** Last resort. Warn: every link in the bundle is about to point at this repo. */
function fallbackRepo() {
  const fallback = "yschimke/compose-ai-tools";
  console.warn(
    `could not determine the source repo (no --source-repo, no ` +
      `$GITHUB_REPOSITORY, no git origin) — defaulting to ${fallback}. Every ` +
      `README and asset link in this bundle will point there. Pass ` +
      `--source-repo <owner>/<repo> if that is not where you publish.`,
  );
  return fallback;
}

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
    for (const warning of warnings)
      console.warn(`[${spec.system}] fonts: ${warning}`);
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
const liveBundles = [];
if (values["publish-live-bundle"]) {
  const file = basename(rendersPath);
  const dest = join(outPath, "bundle", file);
  await mkdir(dirname(dest), { recursive: true });
  await cp(rendersPath, dest);
  liveBundle = { path: "bundle/", file };
  liveBundles.push({
    ...liveBundle,
    module: bundleModulePath(moduleRecords[0].bundle),
    previewIdPrefix: "",
  });
  console.log(`[${spec.system}] carried live bundle → bundle/${file}`);

  // Lift the heavy font resources out of the carried bundle's classes/app.jar and publish them
  // content-addressed under bundle/res/<sha256>, so the ~600 KB bundle drops to ~30 KB and the
  // fonts (which rarely change and are identical across variants) are fetched once per branch. The
  // `bundle externalize` step records each in the bundle's own manifest by name+sha256+size; a
  // trusted `serve --allow-render-trusted` box rehydrates them from bundle/res/ into a shared cache
  // and back onto the daemon classpath. Non-fatal: if the CLI can't externalize (older CLI, no
  // fonts), the self-contained bundle is still published as-is.
  //
  // Only the PRIMARY bundle is externalised, and deliberately so — including under
  // `--extra-live-bundle`. `ServeCatalogStore` rehydrates exactly one `externalResources` manifest,
  // the one it reads from `liveBundle.file`, and hands that single materialized directory to every
  // per-preview daemon it pools. Externalising the supplement too would publish its blobs into the
  // same content-addressed pool with nobody materializing the ones the primary doesn't also
  // declare, so a supplement carrying its own faces would yield per-preview daemons that start with
  // a resource missing from their classpath — worse than the baked lane this replaces. The
  // workflow therefore splits the supplement RAW: each of its per-preview bundles keeps its
  // resources embedded and depends on no shared pool. That is the shape the primary lane already
  // ships in practice (meshcore-mobile's delivery branch has no bundle/res/ at all and its 110
  // per-preview bundles are self-contained); the cost is duplication for a font-heavy supplement,
  // against a live lane that otherwise intermittently can't start.
  try {
    const resOut = join(outPath, "bundle", "res");
    const out = execFileSync(
      "compose-preview",
      ["bundle", "externalize", dest, "--res-out", resOut, "--json"],
      { encoding: "utf8" },
    );
    const summary = JSON.parse(out);
    const total = (summary.externalized ?? []).reduce(
      (n, r) => n + (r.size ?? 0),
      0,
    );
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

  for (let index = 1; index < moduleRecords.length; index++) {
    const record = moduleRecords[index];
    const module = bundleModulePath(record.bundle);
    const path = `bundle/modules/${moduleArtifactKey(module)}/`;
    const file = basename(record.renderPath);
    const dest = join(outPath, path, file);
    await mkdir(dirname(dest), { recursive: true });
    await cp(record.renderPath, dest);
    liveBundles.push({ path, file, module, previewIdPrefix: moduleIdentityPrefix(module) });
    console.log(`[${spec.system}] carried module live bundle ${module} → ${path}${file}`);
  }
}

{
  const catalogJsonPath = join(outPath, "catalog.json");
  const manifest = JSON.parse(await readFile(catalogJsonPath, "utf8"));
  if (renderFailures.length > 0) manifest.failures = renderFailures;
  // Re-stamp each spec group's top-level `section` (the preview-server tab) onto
  // the manifest: the pinned buildCatalog drops `source.section`, so without this
  // a sectioned spec (meshcore's Themes / Components / Screens) collapses to one
  // untabbed bucket. No-op for specs with no group `section` (compose-m3 et al.).
  const stampedSections = applySpecSections(manifest, spec);
  if (stampedSections > 0) {
    console.log(
      `[${spec.system}] stamped section on ${stampedSections} component(s) from spec groups`,
    );
  }
  // Re-stamp each component's module-relative `sourceFile` (dropped by the pinned
  // buildCatalog) from the bundle's discovery previews, so the preview server can link a
  // preview to its source on GitHub. No-op when discovery recorded no paths.
  const sourcesByFunction = new Map();
  for (const renderBundle of allBundles) {
    for (const [fn, source] of sourceByFunction(renderBundle)) sourcesByFunction.set(fn, source);
  }
  const stampedSources = applySourceFiles(manifest, spec, sourcesByFunction);
  if (stampedSources > 0) {
    console.log(
      `[${spec.system}] stamped sourceFile on ${stampedSources} component(s) from discovery`,
    );
  }
  for (const component of manifest.components ?? []) {
    for (const image of component.images ?? []) {
      if (image.path)
        image.livePreview = livePreviewUrl(
          previewBase,
          manifest.system,
          image.path,
        );
    }
  }
  // Stamp the spec's `display` (stage surface + hero preview) onto the manifest. Like the
  // `livePreview`/`section` stamps above, this is a post-process because the pinned
  // `@design-parity/catalog-export` predates `display` in `toCatalogManifest`; once a release
  // carrying it lands and the dep is bumped, `buildCatalog` writes it and this can be dropped.
  if (spec.display) manifest.display = spec.display;
  if (webRender) manifest.webRender = webRender;
  if (liveBundle) manifest.liveBundle = liveBundle;
  if (liveBundles.length > 0) manifest.liveBundles = liveBundles;
  // Deferred (live-only) coverage, recorded alongside the baked components rather than inside
  // `components[].images` — an image with no `path` would reach every consumer that assumes
  // `images[]` is the baked sticker set (index.html, compare.html, matches.html, the per-variant
  // figma-svg emit, the Figma import). A sibling array declares the coverage without pretending
  // those pixels exist. Each record carries the daemon preview id(s) its `@Preview` function
  // produces, so a `serve --allow-render-trusted` host can render it on request: the previews are
  // in the bundle's `previews.json` whether or not CI rasterised them.
  //
  // Two more fields make the record *addressable* (issue #2965), which is what gives a deferred
  // entry a live lane on the serve host rather than leaving it declared-but-unreachable:
  //
  //   - `path` — the `images/…` path this sticker WOULD have been written to. The published route
  //     ids are `previewIdFor(image.path)`, so recording the path here (rather than making the
  //     server re-derive the exporter's naming scheme) keeps one id namespace, and means flipping an
  //     entry between `required` and `deferred` never moves its URL. Guarded against drift below.
  //   - `previewId` — the ONE daemon preview this record renders through. An entry- or
  //     variant-deferred spec record names no axes (nothing rendered, so nothing recorded that its
  //     function would have produced a light AND a dark sticker), so `expandDeferredRecords` splits
  //     it into one record per `@Preview` annotation and recovers each one's theme/size. A
  //     mode-deferred record already names its theme and stays 1:1. `previewIds` stays, as the
  //     function's full list, for a consumer that wants the wider view.
  if (deferred.length > 0) {
    const idsByFunction = daemonPreviewIdsByFunction(allBundles);
    // Drift guard: re-derive every BAKED image's path and compare against what `buildCatalog`
    // actually wrote. A mismatch means the exporter's naming has moved out from under
    // `catalogImagePath`, so the derived deferred paths would point at routes no sticker will ever
    // occupy — publish the records without a `path` (the serve host then skips them, as it does for
    // any older catalog) rather than publish wrong ones, and say so loudly.
    const mismatches = derivationMismatches(manifest);
    if (mismatches.length > 0) {
      const [first] = mismatches;
      console.warn(
        `[${spec.system}] catalog image naming has drifted from catalogImagePath ` +
          `(${mismatches.length} of the baked images disagree; e.g. expected ` +
          `${first.expected}, exporter wrote ${first.actual}) — publishing the deferred records ` +
          `WITHOUT a route path, so the preview server will skip them until the derivation is ` +
          `updated to match.`,
      );
    }
    const records = expandDeferredRecords(deferred, spec, allBundles);
    manifest.deferred = records.map((record) => {
      const ids = idsByFunction.get(record.preview) ?? [];
      return {
        ...record,
        ...(mismatches.length === 0
          ? { path: catalogImagePath(record.componentId, record) }
          : {}),
        ...(ids.length > 0 ? { previewIds: ids } : {}),
      };
    });
    const addressable = manifest.deferred.filter((r) => r.path && r.previewId).length;
    console.log(
      `[${spec.system}] ${addressable}/${manifest.deferred.length} deferred record(s) carry a ` +
        `route + daemon preview id (the live-only lane a trusted serve host registers them under)`,
    );
  }
  // Source provenance for links and, when module is non-empty, trusted server-side re-render.
  // Repository-wide catalogs carry the repo/ref here and retain the owning module per component.
  if (values["source-repo"] && values["source-ref"]) {
    manifest.source = {
      repo: values["source-repo"],
      ref: values["source-ref"],
      module: values["source-module"] ?? "",
    };
    console.log(
      `[${spec.system}] source → ${manifest.source.module || "<per-preview module>"}@${manifest.source.ref}`,
    );
  }
  // Emit the catalog-id → daemon-id bridge whenever a live path can serve this catalog — a carried
  // liveBundle OR a buildable source. A source-only catalog (wear-m3 / remote-m3) needs the aliases
  // too: `ServeCatalogStore` builds the alias solely from `image.previewId`, so without this a
  // `--allow-render-trusted` box would pay the Gradle build yet reach the daemon for no catalog id.
  if (liveBundle || values["source-module"]) {
    // Both bundles: an `--extra-renders`-only component's previews live solely in the
    // supplement, so passing just the primary left every one of its images with no
    // `previewId` — no live lane for it, and no per-variant figma-svg.
    bridgeLivePreviewIds(
      manifest,
      spec,
      allBundles,
      overriddenFunctions,
    );
    // The shared live daemon opens only the primary bundle. An extra-only image renders through a
    // per-preview supplement bundle that stays closed until first render, so the browse surface
    // cannot discover its authored knobs / focus / gesture controls from the daemon. Record those
    // small declarations beside the image while both bundles are already open in CI; serve can
    // advertise them without eagerly parsing every supplement bundle.
    const stampedDeclarations = applyCatalogPreviewDeclarations(
      manifest,
      allBundles,
    );
    if (stampedDeclarations > 0) {
      console.log(
        `[${spec.system}] stamped authored controls on ${stampedDeclarations} catalog image(s)`,
      );
    }
  }
  stampPreviewDensities(manifest, spec, allBundles);

  // Check the motion axis survived the export before trying to publish its bytes.
  //
  // `buildCatalog` copies a source's fields through an allow-list, and `motion` was not in it until
  // `@design-parity/catalog-export` 0.1.52 — so the declarations ended at the join and the publish
  // pass below had nothing to copy. That is exactly how five components which each shipped a
  // rendered 60fps APNG inside the bundle published a catalog with no Motion section at all, on
  // runs that reported nothing wrong for a day. The pin carries the field now; this says so out
  // loud, and says the opposite even louder, so a downgraded pin or a regressed allow-list cannot
  // go quiet again.
  {
    const { declared, carried, captures, dropped } = checkMotionCarried(
      manifest,
      motionByComponentId,
    );
    if (carried > 0) {
      console.log(
        `[${spec.system}] motion: ${carried} component(s) carry ${captures} capture(s)`,
      );
    }
    if (dropped.length > 0) {
      console.warn(
        `[${spec.system}] motion: the join resolved captures for ${declared} component(s) but ` +
          `catalog.json carries them for ${carried} — the export dropped the axis for ` +
          `${dropped.join(", ")}. Their bytes cannot be published; check the ` +
          `@design-parity/catalog-export pin (needs >= 0.1.52).`,
      );
    }
  }

  // Publish the motion axis' bytes onto the branch, under `motion/`, and repoint each declaration
  // at where they landed.
  //
  // `catalog-motion.mjs` records the artifact's home INSIDE the render bundle, because at join time
  // that is the only name it has. The bundle is not published — this branch is — so a catalog left
  // as-is declares captures whose bytes exist nowhere a reader can reach, and every consumer
  // downstream resolves `motion[].path` to a 404. Copying them here makes that field mean exactly
  // what `images[].path` already means: a file on the branch, relative to catalog.json.
  //
  // It sits in this block rather than beside the figma-svg emit below because it needs the
  // `previewId` that `bridgeLivePreviewIds` stamped a few lines up (the join to the sibling sticker
  // whose name each artifact inherits — see catalog-motion-publish.mjs), and because the rewritten
  // paths have to reach the `writeFile` that closes this block. Reading the bytes from the PRIMARY
  // bundle only mirrors `motionBundle` in the join above: a motion capture is published from the
  // bundle that rendered it. Repository-wide publication therefore merges the entry views with
  // deterministic primary-first precedence before copying the bytes.
  {
    const { published, unresolved, missing } = await publishMotionArtifacts(
      manifest,
      combinedBundleEntries(allBundles),
      outPath,
    );
    if (published > 0 || missing.length > 0) {
      console.log(
        `[${spec.system}] motion: published ${published} capture(s) under motion/` +
          (unresolved > 0
            ? `, ${unresolved} named from the artifact rather than a sibling sticker (no previewId ` +
              `to join on — the bytes are published, only the filename is unvariant)`
            : "") +
          (missing.length > 0
            ? `, dropped ${missing.length} declaration(s) whose bytes the bundle did not carry ` +
              `(${missing.join(", ")})`
            : ""),
      );
    }
  }

  await writeFile(
    catalogJsonPath,
    `${JSON.stringify(manifest, null, 2)}\n`,
    "utf8",
  );
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
// Wireframes + figma-svg read from BOTH the primary and the `--extra-renders` bundle: an extra render
// that ADDS a function (not just overrides a same-named one) carries its own layout tree + editable
// figma-svg, which must land in the catalog too — otherwise a screen rendered from a second module is
// PNG-only. Extra wins on a name clash, matching the candidate fold above.
const layoutByFn = new Map();
for (const renderBundle of allBundles) {
  for (const [fn, value] of layoutByFunction(renderBundle)) layoutByFn.set(fn, value);
}
const fnByComponentId = new Map(
  spec.groups.flatMap((g) =>
    g.components.map((c) => [c.componentId, c.preview]),
  ),
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
    svg = renderLayoutWireframeSvg(
      { ...component, layout: carried.layout },
      { density: carried.density },
    );
    if (svg) layoutWireframeCount += 1;
  }
  if (!svg) svg = renderWireframeSvg(component); // greenline fallback
  if (!svg) continue;
  await writeFile(
    join(wireframesDir, `${slug(component.componentId)}.svg`),
    svg,
    "utf8",
  );
  wireframeSlugs.add(slug(component.componentId));
  wireframeCount += 1;
}

// The written manifest (catalog.json), re-read from disk — NOT the in-memory
// `catalog`: `buildCatalog` keeps a component's captures under `variants.ideal`
// (each with a source `uri`), while the manifest carries the flattened `images[]`
// with the bundle-relative `path` every artifact must reference, plus the
// `previewId` the stamp pass above bridged onto each image.
//
// Read here rather than just before the index render because two consumers need
// it: the per-variant figma-svg emit below mirrors `images[]` path for path, and
// `renderIndexHtml` / `renderCompareHtml` / `renderCrossSystemHtml` read
// `component.images` to show the stickers (and to group them by `state` in the
// zoom view). Nothing between the write above and the renders below touches
// catalog.json, so moving the read earlier is behaviour-preserving.
const indexManifest = JSON.parse(
  await readFile(join(outPath, "catalog.json"), "utf8"),
);

// Editable, design-fidelity vectors next to the raster PNGs: the layered
// `compose/figma-svg` export (real fills / strokes / corner radii + editable
// text) carried per sticker in the bundle. Unlike the schematic wireframe above
// this is the *design* SVG — a designer imports it into Figma for an editable
// component rather than a flat screenshot. Written under figma/<slug>.svg; the
// index links it. Components whose render produced no drawing layers are skipped.
const figmaSvgByFn = figmaSvgByFunctions(allBundles);
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
  // A hybrid sticker's raster crops live in whichever bundle carried its figma-svg; an id is unique
  // to one bundle, so merging both is safe (the other returns empty).
  const rasters = new Map(
    allBundles.flatMap((renderBundle) => [
      ...figmaRastersForId(renderBundle, carried.id),
    ]),
  );
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

// Per-variant vectors, mirroring the raster set 1:1:
//
//   images/<slug>/ideal__default__dark__compact.png   (written by the export engine)
//   figma/<slug>/ideal__default__dark__compact.svg    (written here)
//
// The loop above ships exactly ONE vector per component (`figmaSvgByFunction` prefers
// the light preview), yet the bundle carries a `previews/<id>.figma.svg` for *every*
// rendered preview — the dark, locale and size vectors are rendered, carried, then
// dropped on the floor by that per-function collapse. This loop ships them.
//
// It's driven from the manifest's `images[]` rather than from a naming scheme of its
// own: each image already carries the `previewId` that keys the bundle's vectors plus
// the bundle-relative `path` the export engine chose for it, so mapping `images/` →
// `figma/` and `.png` → `.svg` inherits that collision-safe naming and guarantees a
// vector can't drift away from the PNG it depicts.
//
// The `previewId` it keys on is stamped onto each image by `bridgeLivePreviewIds`
// above, which runs for a carried liveBundle OR a `--source-module` (the workflow
// always passes the latter, so every published catalog is covered). An image it
// deliberately left unbridged — a state with no desktop source, or a function the
// Android-only supplement overrode — carries no previewId and is skipped silently:
// there is no daemon-rendered vector for it to be missing. An image that HAS a
// previewId but no carried vector is a *gap*, counted and reported below, because a
// silently missing vector is precisely the failure this emit exists to prevent.
//
// Vectors are folded across BOTH bundles (`figmaSvgByIds`), exactly like the
// per-function fold and the raster merge above: an `--extra-renders`-only preview
// exists in the supplementary bundle alone, so a primary-only read would emit no
// per-variant vector at all for every screen rendered from a second module.
//
// Purely additive: `figma/<slug>.svg` and its per-slug crop dir stay exactly where
// they are, since index.html, compare.html, design-parity's FIGMA_IMPORT.md and the
// meshcore-mobile seeding runbook all reference that path today. The per-variant set
// lives in a `figma/<slug>/` *directory*, so the two never collide.
const figmaSvgsById = figmaSvgByIds(allBundles);
let figmaVariantSvgCount = 0;
let figmaVariantGapCount = 0;
const figmaVariantSvgPaths = new Set();
// Components with at least one image carrying no `previewId`. Some of those are legitimate
// (`bridgeLivePreviewIds` deliberately skips a state with no desktop source, and any function
// the Android-only supplement overrode) — but a component where NONE of the images bridged is
// the signature of a whole bundle never reaching the bridge, which is how the
// `--extra-renders` module silently lost every per-variant vector once already. Silence is
// what made that expensive, so name them.
const unbridgedComponents = new Map();
for (const component of indexManifest.components ?? []) {
  for (const image of component.images ?? []) {
    const seen = unbridgedComponents.get(component.componentId) ?? {
      unbridged: 0,
      total: 0,
    };
    seen.total += 1;
    if (!image.previewId) seen.unbridged += 1;
    unbridgedComponents.set(component.componentId, seen);
    if (!image.previewId) continue;
    const target = figmaVariantSvgPath(image.path);
    if (!target) continue;
    const carried = figmaSvgsById.get(image.previewId);
    if (!carried) {
      figmaVariantGapCount += 1;
      continue;
    }
    let svg = carried;
    const variantPath = join(outPath, target);
    const variantDir = dirname(variantPath);
    const variantBase = basename(target, ".svg");
    // Same two-bundle merge as the back-compat loop: a hybrid sticker's crops live in
    // whichever bundle carried its figma-svg, and a preview id belongs to one bundle,
    // so merging is safe (the other side returns empty).
    const rasters = new Map(
      allBundles.flatMap((renderBundle) => [
        ...figmaRastersForId(renderBundle, image.previewId),
      ]),
    );
    if (rasters.size) {
      // Hybrid crops get a sibling dir keyed by the *variant* basename, not the slug:
      // a component's light and dark vectors share one `figma/<slug>/` dir and each
      // carries its own `<node>.png`, so a per-slug dir would have them overwrite each
      // other. Sitting next to the SVG, the rewritten relative href resolves as-is.
      svg = rewriteRasterHrefs(svg, variantBase);
      const rasterDir = join(variantDir, `${variantBase}.figma-raster`);
      await mkdir(rasterDir, { recursive: true });
      for (const [name, bytes] of rasters) {
        await writeFile(join(rasterDir, name), Buffer.from(bytes));
        figmaRasterCount += 1;
      }
    }
    await mkdir(variantDir, { recursive: true });
    await writeFile(variantPath, svg, "utf8");
    figmaVariantSvgPaths.add(target);
    figmaVariantSvgCount += 1;
  }
}

// The published tag index (`tags/index.json`) — `served preview id → {testTag: {count, bounds}}`,
// the element identity a scoped parity acceptance resolves against (compose-ai-tools#3680).
//
// It has to be published rather than projected on demand: the serve host derives this from a live
// daemon render, and a published catalog has no daemon, so for a catalog the projection can only
// happen HERE, where the semantics trees the render produced are still in hand. Same join as the
// per-variant figma-svg emit above and for the same reason — each image already carries the
// `previewId` that keys the bundle's sidecar and the `path` the served route id derives from, so
// neither namespace is re-derived.
{
  // The unfiltered id map covers images `bridgeLivePreviewIds` deliberately withheld a live alias
  // from (an Android-only supplement override): their pixels have a carried tree even though their
  // live lane does not exist.
  const tags = catalogTagIndex(
    indexManifest,
    allBundles,
    resolveSemanticsIds(indexManifest, spec, allBundles),
  );
  const tagsDir = join(outPath, "tags");
  await mkdir(tagsDir, { recursive: true });
  await writeFile(
    join(tagsDir, "index.json"),
    `${JSON.stringify({ schema: tags.schema, previews: tags.previews }, null, 2)}\n`,
    "utf8",
  );
  console.log(
    `[${spec.system}] tag index: ${tags.indexed} preview(s) indexed` +
      (tags.gaps > 0
        ? `, ${tags.gaps} bridged image(s) carried no semantics tree (pack with --with-semantics ` +
          `to close the gap — an unindexed preview simply gets no element gate)`
        : ""),
  );
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
      .map((c) => [
        c.componentId,
        { component: c.component, import: c.import, source: c.source },
      ]),
  ),
);
const codeConnect = buildCodeConnectManifest({
  components: catalog.components,
  fnByComponentId,
  componentByComponentId,
  targetByFn: combinedBundleMap(allBundles, targetsByFunction),
  slug,
  figmaSvgSlugs,
  sourceByFn: combinedBundleMap(allBundles, sourceByFunction),
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
        if (component.parallel)
          parallelById[component.componentId] = component.parallel;
      }
    }
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
    // The sibling inventory (componentId / group / caption for each parallel) comes from the
    // sibling's BUILT catalog.json when we could fetch it — that's the authoritative inventory
    // whether the sibling declares its components in `catalog.spec.json` OR (now) via
    // @CatalogComponent annotations, whose trimmed spec carries no `groups`. Falls back to the
    // sibling spec's `groups` when its catalog isn't published yet (a spec-driven sibling still
    // lists them there; an annotation-driven sibling then shows "not rendered yet" until its branch
    // publishes, one run behind at most). Reading spec `groups` alone would drop every parallel for a
    // migrated sibling like wear-m3.
    const otherComponents = otherManifest?.components?.length
      ? otherManifest.components.map((c) => ({
          componentId: c.componentId,
          group: c.group,
          caption: c.caption,
        }))
      : (otherSpec.groups ?? []).flatMap((group) =>
          (group.components ?? []).map((c) => ({
            componentId: c.componentId,
            group: group.name,
            caption: c.caption,
          })),
        );
    // A `parallel` naming no sibling component renders as an unpaired row — the compare page still
    // builds, so the broken link is invisible unless someone eyeballs it. That is exactly how a
    // componentId migration on the SIBLING side (a per-breakpoint fan-out, say) silently unpairs the
    // rows pointing at the old id, since no single-spec validator can resolve across systems and the
    // sibling's real inventory only exists here. Skipped when the sibling inventory is empty (its
    // branch hasn't published yet), where every parallel would look broken for the wrong reason.
    if (otherComponents.length > 0) {
      const otherIds = new Set(otherComponents.map((c) => c.componentId));
      const unresolved = Object.entries(parallelById)
        .filter(([, parallelId]) => !otherIds.has(parallelId))
        .map(([componentId, parallelId]) => `${componentId} → ${parallelId}`);
      if (unresolved.length > 0) {
        console.warn(
          `[${spec.system}] ${unresolved.length} parallel(s) name no component in ` +
            `${spec.compareWith}, so those rows pair against nothing: ${unresolved.join(", ")}.`,
        );
      }
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
    crossSystem = {
      system: spec.compareWith,
      title: otherSpec.title ?? spec.compareWith,
    };
    console.log(`[${spec.system}] matches → ${matchesPath}`);
  } catch (err) {
    console.warn(
      `[${spec.system}] cross-system page skipped (${err.message?.split("\n")[0] ?? err})`,
    );
  }
}

// Browsable index next to catalog.json + images/ — a designer can open this
// straight from the branch to skim every component (its a11y greenlines and the
// editable wireframe) before importing the tokens/images into a design tool.
// Rendered from `indexManifest` (the written manifest, read above), NOT the
// in-memory `catalog`: `renderIndexHtml` reads `component.images`, which only the
// manifest carries — passing the in-memory catalog makes every card fall back to
// the "no render" placeholder.
const indexPath = join(outPath, "index.html");
await writeFile(
  indexPath,
  renderIndexHtml(indexManifest, {
    wireframeSlugs,
    figmaSvgSlugs,
    crossSystem,
  }),
  "utf8",
);

// PNG-vs-SVG comparison page next to index.html: every component on one row, its
// rendered PNG beside its browser-rasterized figma-svg, and a live structural
// (SSIM) match score. The score runs in the page because it measures the
// *browser's* SVG rasterization against the PNG — see render-compare-html.mjs.
const comparePath = join(outPath, "compare.html");
await writeFile(
  comparePath,
  renderCompareHtml(indexManifest, {
    figmaSvgSlugs,
    figmaVariantSvgPaths,
    hybridSlugs: figmaSvgHybridSlugs,
  }),
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
    repo,
    previewBase,
    crossSystem,
  }),
  "utf8",
);

console.log(
  `[${spec.system}] ${catalog.components.length} component(s), ${result.imageCount} image(s), ` +
    `${wireframeCount} wireframe(s) (${layoutWireframeCount} from layout-inspector, ` +
    `${wireframeCount - layoutWireframeCount} greenline), ${figmaSvgCount} figma-svg ` +
    `(${figmaRasterCount} raster crop(s)), ${figmaVariantSvgCount} per-variant figma-svg ` +
    `→ ${result.manifestPath}`,
);
// An image with a previewId but no carried vector means the per-variant set is no longer 1:1 with
// the raster set — surface it rather than letting the vector vanish quietly.
if (figmaVariantGapCount) {
  console.warn(
    `[${spec.system}] ${figmaVariantGapCount} render(s) had no figma-svg carried for their ` +
      `previewId — no per-variant vector emitted for those`,
  );
}
// Two stickers stamped with ONE previewId but built from renders of different sizes cannot both
// have come from that render — so at least one of them is now paired with another variant's vector.
// This is the signature the catalog-breakpoint half of #2883 shipped under for four releases (see
// `variant-render-pairing.mjs`); print it so a regression shows up in the run log rather than only
// as a sunk compare score.
// Read from `indexManifest` (the written catalog.json), NOT the in-memory `catalog`: only the
// manifest carries the flattened `images[]` with the `previewId` the stamp pass bridged on — see
// its declaration above. Against `catalog.components` this check iterates nothing and reports
// nothing, whatever the export actually emitted, which is the one way a checker can fail worst.
const variantRenderMismatches = mismatchedVariantRenders(indexManifest.components);
if (variantRenderMismatches.length) {
  const shown = describeMismatchedRenders(variantRenderMismatches).slice(0, 8);
  const more =
    variantRenderMismatches.length > 8
      ? `, +${variantRenderMismatches.length - 8} more`
      : "";
  console.warn(
    `[${spec.system}] ${variantRenderMismatches.length} preview id(s) are stamped on stickers ` +
      `of differing render sizes — those variants carry another render's figma-svg: ` +
      `${shown.join("; ")}${more}`,
  );
}
// An image with NO previewId never even reaches the vector lookup. Some of that is deliberate
// (a state with no desktop source, a function the Android-only supplement overrode), so this is
// informational — but a component where EVERY image is unbridged means its previews never
// reached `bridgeLivePreviewIds` at all, which costs it both the live lane and its per-variant
// vectors. That failure shipped once precisely because it was silent, so name the components.
const fullyUnbridged = [...unbridgedComponents]
  .filter(([, c]) => c.unbridged === c.total)
  .map(([id]) => id);
const partiallyUnbridged = [...unbridgedComponents].filter(
  ([, c]) => c.unbridged > 0 && c.unbridged < c.total,
).length;
if (fullyUnbridged.length) {
  const shown = fullyUnbridged.slice(0, 8).join(", ");
  const more =
    fullyUnbridged.length > 8 ? `, +${fullyUnbridged.length - 8} more` : "";
  console.warn(
    `[${spec.system}] ${fullyUnbridged.length} component(s) had NO image bridged to a preview ` +
      `id — no live lane and no per-variant vectors for them: ${shown}${more}. If that is a whole ` +
      `module, check every render bundle is passed to bridgeLivePreviewIds.`,
  );
}
if (partiallyUnbridged) {
  console.log(
    `[${spec.system}] ${partiallyUnbridged} component(s) had some images unbridged ` +
      `(expected for states with no desktop source or overridden by the supplement)`,
  );
}
console.log(`[${spec.system}] index → ${indexPath}`);
console.log(`[${spec.system}] compare → ${comparePath}`);
console.log(`[${spec.system}] readme → ${readmePath}`);
