/**
 * Check a catalog spec against a **preview-id manifest**, without rendering (issue #5066).
 *
 * Nearly every failed import cycle is a config-shape error — a spec `preview` that names nothing,
 * an `excludePreviewIds` pattern written in the wrong id shape, an exclusion that removes too few
 * arms of a fan-out — and each one currently costs a full render (20–40 minutes on a real project)
 * to discover. Every one of them is decidable in seconds from a list of preview ids, which is what
 * this module does. Four reports, all from `(spec, previews[, exclusions])`:
 *
 *   - **`missing-preview`** — a spec `preview` (component or variant) that no manifest id's function
 *     answers to. The offline half of the completeness gate's `missing`, and the same check
 *     `validate-catalog-spec.mjs` runs against Kotlin *source* — except this one sees what discovery
 *     actually minted, so it also catches a preview that exists but was never discovered.
 *   - **`unmatched-exclusion`** — an exclusion pattern with a match count of 0 (issue #5064). The
 *     underscores-vs-spaces mistake: `@Preview(name = "Font scale 1.5x")` mints an id carrying
 *     spaces, and a pattern spelled with underscores silently excludes nothing.
 *   - **`excluded-claimed`** — every id of a function the spec claims is excluded, so that component
 *     can only publish as a missing sticker.
 *   - **`duplicate-output-axes`** — ids that survive exclusion and would collide on output axes
 *     (issue #5065): the three-arm locale fan-out whose catalog declares no locale axis, where an
 *     exclusion covering only `_ja` leaves two arms fighting over one sticker path. `foldVariants`
 *     throws on this at the very end of a render; here it is a report, and it names every colliding
 *     pair rather than the first.
 *
 * Plus, as information, the manifest ids no spec component claims — the inverse view, for a catalog
 * that is meant to cover a module.
 *
 * ## What a manifest is, and what the axis model can see
 *
 * Any `{ previews: [{ id, functionName, params, captures }] }` payload: a module's
 * `build/compose-previews/previews.json`, `compose-preview list --json`, or a bundle's manifest.
 * The manifest for a given import changes rarely while the spec and its exclusions change
 * constantly, so a cached snapshot of the slow-moving half is enough to iterate the fast-moving one
 * with no build at all.
 *
 * The output-axis check necessarily models what the render + candidate join WOULD produce, because
 * the real axes are read off rendered images. The model derives, per manifest capture:
 *
 *   - `state` from an `_VARIANT_<name>` id suffix (`variantStateFromId`, the same reader the join
 *     uses);
 *   - `theme` from the declared mode a trailing id segment names (`modeOfPreviewId`), else a
 *     `light`/`dark` id suffix, else `@Preview(uiMode = …)`'s night bits;
 *   - `size` from the spec's `breakpoints` (`breakpointMatcher`, shared with the join);
 *   - `props.fontScale` from a non-default `fontScale` param, mirroring `applyCatalogPreviewAxes`.
 *
 * An axis the join derives from pixels or from a source annotation this manifest does not carry is
 * invisible here, so the collision report is a **report**, not the authority: the render's own
 * `foldVariants` guard stays the gate. The bias is deliberately towards silence — two ids that
 * differ only in something the model cannot see are the one shape it can get wrong, and it is worth
 * saying that out loud rather than failing a spec that renders fine.
 *
 * Pure and dependency-free apart from its siblings (node built-ins only, no `@design-parity/*`, no
 * I/O) so it unit-tests without an `npm ci`. The `--spec`/`--previews` CLI wrapper at the bottom
 * only runs when this file is executed directly.
 */

import { readFileSync } from "node:fs";
import { parseArgs } from "node:util";

import { breakpointMatcher, catalogBreakpoints } from "./catalog-breakpoints.mjs";
import { modeOfPreviewId } from "./catalog-priority.mjs";
import { selectImages, selectLabel, selectOf } from "./catalog-select.mjs";
import { imageHasVariantAxes, outputAxisKey } from "./catalog-variants.mjs";
import { previewsFromJson } from "./deferred-preview-ids.mjs";
import { variantStateFromId } from "./variant-state.mjs";

/** Mirror of `PackPreviewIdExclusions.ANCHOR` / `PreviewNameFilter.ANCHOR`. */
export const ANCHOR = "=";

/**
 * Whether one `--exclude-preview-id` pattern matches one preview id.
 *
 * Mirrors `PackPreviewIdExclusions.matches` (CLI) and `PreviewNameFilter.matchesId` (plugin), which
 * are what actually skip the render: an `=`-anchored pattern is exact, a pattern carrying `*`/`?` is
 * an anchored glob, and a plain pattern matches by equality OR substring. Case-sensitive throughout.
 * A fourth implementation is a liability, so keep it in step with those two — a preflight that
 * matched more loosely than the render would report an exclusion as covered when the render will
 * still burn the time, and one that matched more tightly would invent `unmatched-exclusion`
 * findings for patterns that work.
 */
export function matchesExclusion(pattern, id) {
  const text = String(id ?? "");
  const glob = String(pattern ?? "");
  if (glob.length === 0) return false;
  if (glob.startsWith(ANCHOR)) return text === glob.slice(ANCHOR.length);
  if (glob.includes("*") || glob.includes("?")) return globToRegExp(glob).test(text);
  return text === glob || text.includes(glob);
}

/** Anchored regex for a `*`/`?` glob, every other character escaped so an id's `.` stays literal. */
function globToRegExp(glob) {
  const body = [...glob]
    .map((c) => (c === "*" ? ".*" : c === "?" ? "." : c.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")))
    .join("");
  return new RegExp(`^${body}$`);
}

/** Split a comma-separated exclusion list the way the CLI's `--exclude-preview-id` does. */
export function exclusionPatterns(raw) {
  return (Array.isArray(raw) ? raw : [raw])
    .filter((value) => typeof value === "string")
    .flatMap((value) => value.split(","))
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
}

/**
 * The `@Preview` function a manifest record belongs to. `functionName` when discovery recorded one
 * (every current payload does), else the id — the same fallback the candidate join uses.
 */
export function functionOf(preview) {
  return preview?.functionName ?? preview?.id;
}

/**
 * Whether a spec's `preview` names [fn].
 *
 * Exact, with one concession: a repository-wide catalog rewrites a colliding function name to
 * `<module>::<function>` (see multi-module-catalog.mjs), so a spec carrying the qualified spelling
 * still resolves against a single-module manifest that only knows the bare one.
 */
function claims(specPreview, fn) {
  if (typeof specPreview !== "string" || typeof fn !== "string") return false;
  return specPreview === fn || specPreview.endsWith(`::${fn}`);
}

/** Every component a spec declares, in spec order. */
function* specComponents(spec) {
  for (const group of spec?.groups ?? []) {
    for (const component of group?.components ?? []) {
      if (component && typeof component === "object") yield component;
    }
  }
}

/** Every `(component, entry)` pair a spec declares, components and their variants alike. */
function* specEntries(spec) {
  for (const component of specComponents(spec)) {
    yield { component, entry: component, kind: "component" };
    for (const variant of component.variants ?? []) {
      if (!variant || typeof variant !== "object") continue;
      yield { component, entry: variant, kind: "variant" };
    }
  }
}

/** The captures of a preview, or one implicit capture for a preview that declares none. */
function capturesOf(preview) {
  const captures = preview?.captures;
  return Array.isArray(captures) && captures.length > 0 ? captures : [{}];
}

/** UI_MODE_NIGHT mask / values from `android.content.res.Configuration`. */
const UI_MODE_NIGHT_MASK = 0x30;
const UI_MODE_NIGHT_NO = 0x10;
const UI_MODE_NIGHT_YES = 0x20;

/**
 * The theme the join would tag this render with, or null.
 *
 * Three readers, most specific first, because two catalog shapes put the theme in different places:
 * `@CatalogModes` mints a `Foo_Light`/`Foo_Dark` id (which `modeOfPreviewId` resolves against the
 * spec's declared `modes`, and a bare light/dark suffix answers even for a spec that declares
 * none — see `bridge-live-preview-ids.mjs`), while a hand-written `@Preview(uiMode = …)` carries it
 * only in the annotation.
 */
function themeOf(id, params, modes) {
  const declared = modeOfPreviewId(id, modes);
  if (declared) return declared;
  const lower = String(id ?? "").toLowerCase();
  if (lower.endsWith("dark")) return "dark";
  if (lower.endsWith("light")) return "light";
  const night = Number(params?.uiMode) & UI_MODE_NIGHT_MASK;
  if (night === UI_MODE_NIGHT_YES) return "dark";
  if (night === UI_MODE_NIGHT_NO) return "light";
  return null;
}

/**
 * Params that decide pixels: everything but `name`/`group`, which only label an annotation, and an
 * explicit `fontScale: 1`, which is the default written out.
 *
 * The `name`/`group` drop mirrors `applyCatalogPreviewAxes`. The `fontScale` normalisation is this
 * module's own, and it is what keeps the Wear shape quiet: stacking `@WearPreviewDevices` on
 * `@WearPreviewFontScales` emits the small-round render twice, once with the scale left implicit
 * and once with it spelled `1.0`. Those are the same picture, the join collapses them, and treating
 * the two spellings as different params would report every such catalog as colliding.
 */
function renderParams(params) {
  const { name: _name, group: _group, ...rendering } = params ?? {};
  if (Number(rendering.fontScale) === 1) delete rendering.fontScale;
  return rendering;
}

function stableJson(value) {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.entries(value)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([key, child]) => `${JSON.stringify(key)}:${stableJson(child)}`)
      .join(",")}}`;
  }
  return JSON.stringify(value);
}

/**
 * The images one manifest record would contribute to a catalog entry: one per capture, carrying the
 * axes the model can derive (see the file header for what it can and cannot see).
 *
 * `previewId` rides along for the report — a collision is only actionable if it names the ids that
 * collided — and is ignored by `outputAxisKey`, which reads the axes alone.
 */
export function previewImages(preview, { modes, sizeOf }) {
  const id = preview?.id;
  const state = variantStateFromId(id);
  return capturesOf(preview).map((capture) => {
    const params = { ...(preview?.params ?? {}), ...(capture?.params ?? {}) };
    const fontScale = params.fontScale;
    const props =
      typeof fontScale === "number" && Number.isFinite(fontScale) && fontScale !== 1
        ? { fontScale: Number.isInteger(fontScale) ? fontScale.toFixed(1) : String(fontScale) }
        : {};
    const size = sizeOf ? sizeOf(params) : undefined;
    return {
      previewId: id,
      variant: "ideal",
      state: state ?? "default",
      theme: themeOf(id, params, modes),
      ...(size !== undefined ? { size } : {}),
      props,
      params: renderParams(params),
    };
  });
}

/**
 * `functionName` → the images its surviving ids would contribute, exact duplicates collapsed.
 *
 * The collapse mirrors `applyCatalogPreviewAxes`: two renders with identical effective params AND
 * identical derived axes are the same picture (Wear stacking `@WearPreviewDevices` on
 * `@WearPreviewFontScales` produces one such pair per function), and the join drops the repeat
 * rather than colliding on it. Skipping it here would report every such catalog as broken.
 */
export function imagesByFunction(previews, options) {
  const byFunction = new Map();
  const seen = new Map();
  for (const preview of previews ?? []) {
    const fn = functionOf(preview);
    if (typeof fn !== "string" || fn.length === 0) continue;
    const images = byFunction.get(fn) ?? [];
    const claimed = seen.get(fn) ?? new Set();
    for (const image of previewImages(preview, options)) {
      const key = stableJson({ params: image.params, axes: outputAxisKey(image) });
      if (claimed.has(key)) continue;
      claimed.add(key);
      images.push(image);
    }
    byFunction.set(fn, images);
    seen.set(fn, claimed);
  }
  return byFunction;
}

/** Apply a variant's declared axes to an image, exactly as `foldVariants` re-tags it. */
function tagged(image, variant) {
  const out = { ...image };
  if (variant.state !== undefined) out.state = variant.state;
  if (variant.props) out.props = { ...image.props, ...variant.props };
  if (variant.theme !== undefined) out.theme = variant.theme;
  return out;
}

/**
 * The output-axis collisions one component's surviving ids would produce.
 *
 * Walks the same fold as `foldVariants` — default images first, then each variant's re-tagged
 * ones, a same-function variant already covered by a default image skipped — and keys them with the
 * very same `outputAxisKey`. The difference is that it collects every collision instead of throwing
 * on the first, which is what makes one preflight pass worth a render cycle.
 */
function collisionsOf(component, byFunction) {
  const seen = new Map();
  const collisions = [];
  const record = (image, source) => {
    const key = outputAxisKey(image);
    const first = seen.get(key);
    if (first === undefined) {
      seen.set(key, { image, source });
      return;
    }
    collisions.push({
      componentId: component.componentId,
      key,
      previewIds: [first.image.previewId, image.previewId],
      sources: [first.source, source],
    });
  };

  const defaultImages = selectImages(byFunction.get(component.preview) ?? [], selectOf(component));
  for (const image of defaultImages) record(image, component.preview);
  for (const variant of component.variants ?? []) {
    const images = selectImages(byFunction.get(variant.preview) ?? [], selectOf(variant));
    if (images.length === 0) continue;
    // A variant naming the component's OWN function whose axes the default images already carry is
    // satisfied by them — `foldVariants` folds nothing in that case, so neither does this.
    if (
      variant.preview === component.preview &&
      defaultImages.some((image) => imageHasVariantAxes(image, variant))
    ) {
      continue;
    }
    const label = `${variant.preview} [${variantLabelOf(variant)}]`;
    for (const image of images) record(tagged(image, variant), label);
  }
  return collisions;
}

/** A short label for a variant in a finding — its declared axes, or its preview name. */
function variantLabelOf(variant) {
  const parts = [
    ...(variant.state ? [`state=${variant.state}`] : []),
    ...Object.entries(variant.props ?? {}).map(([k, v]) => `${k}=${v}`),
    ...(variant.theme ? [`theme=${variant.theme}`] : []),
    ...(selectOf(variant) ? [`select ${selectLabel(selectOf(variant))}`] : []),
  ];
  return parts.join(", ") || variant.preview;
}

/** Severity ordering for the report's own summary line. */
export const ERROR = "error";
export const INFO = "info";

/**
 * Check [spec] against a preview-id manifest.
 *
 * @param {object} spec parsed `catalog.spec.json`.
 * @param {Array<{id: string, functionName?: string, params?: object, captures?: Array<object>}>} previews
 *   the manifest's previews (see `previewsFromJson`).
 * @param {{excludePatterns?: string[]}} [options] the `--exclude-preview-id` patterns the render
 *   will be given, if any.
 * @returns {{findings: Array<object>, patterns: Array<{pattern: string, matches: number}>,
 *   unclaimed: string[], counts: object}} findings in reported order, every exclusion pattern with
 *   its match count (whether or not it is a finding), the manifest ids no component claims, and the
 *   id counts the summary line is built from.
 */
export function preflightSpec(spec, previews, options = {}) {
  const patterns = exclusionPatterns(options.excludePatterns ?? []);
  const all = (previews ?? []).filter((preview) => typeof preview?.id === "string");
  const findings = [];

  // Exclusions first: every later check runs against the SURVIVORS, which is the set the render
  // actually produces. Counting matches per pattern (rather than over the union) is what makes a
  // dead pattern visible when a sibling pattern covers the same ids.
  const matchCounts = patterns.map((pattern) => ({
    pattern,
    matches: all.filter((preview) => matchesExclusion(pattern, preview.id)).length,
  }));
  const survivors = all.filter(
    (preview) => !patterns.some((pattern) => matchesExclusion(pattern, preview.id)),
  );
  for (const { pattern, matches } of matchCounts) {
    if (matches > 0) continue;
    findings.push({
      kind: "unmatched-exclusion",
      severity: ERROR,
      pattern,
      message:
        `exclusion pattern "${pattern}" matches none of the ${all.length} manifest id(s), so it ` +
        `excludes nothing. Check the id shape — a @Preview(name = …) with spaces mints an id with ` +
        `spaces, which an underscored pattern never matches.`,
    });
  }

  const byFunction = imagesByFunction(survivors, {
    modes: spec?.modes ?? [],
    sizeOf: breakpointMatcher(catalogBreakpoints(spec)),
  });
  const functionsBefore = new Set(all.map(functionOf));
  const claimed = new Set();

  for (const { component, entry, kind } of specEntries(spec)) {
    const preview = entry.preview;
    if (typeof preview !== "string" || preview.length === 0) continue;
    const resolved = [...byFunction.keys()].filter((fn) => claims(preview, fn));
    for (const fn of resolved) claimed.add(fn);
    if (resolved.length > 0) continue;
    const wasExcluded = [...functionsBefore].some((fn) => claims(preview, fn));
    findings.push({
      kind: wasExcluded ? "excluded-claimed" : "missing-preview",
      severity: ERROR,
      componentId: component.componentId,
      preview,
      entry: kind,
      message: wasExcluded
        ? `${component.componentId} (${kind} "${preview}"): every discovered id of this function ` +
          `is excluded, so the entry can only publish as a missing sticker. Narrow the exclusion ` +
          `or drop the entry.`
        : `${component.componentId} (${kind} "${preview}"): no manifest id belongs to a function ` +
          `of this name.`,
    });
  }

  for (const component of specComponents(spec)) {
    for (const collision of collisionsOf(component, byFunction)) {
      findings.push({
        ...collision,
        kind: "duplicate-output-axes",
        severity: ERROR,
        message:
          `${collision.componentId}: ${collision.previewIds.join(" and ")} both fold onto output ` +
          `axes ${collision.key} (from ${collision.sources.join(" / ")}), so one would overwrite ` +
          `the other's sticker. Give the arms an axis the catalog declares, or exclude all but ` +
          `one of them.`,
      });
    }
  }

  const unclaimed = [
    ...new Set(
      survivors.filter((preview) => !claimed.has(functionOf(preview))).map((preview) => preview.id),
    ),
  ].sort();

  return {
    findings,
    patterns: matchCounts,
    unclaimed,
    counts: {
      previews: all.length,
      excluded: all.length - survivors.length,
      survivors: survivors.length,
      claimedFunctions: claimed.size,
      unclaimed: unclaimed.length,
    },
  };
}

/** The findings that fail a run: everything the report is sure about. */
export function errorsOf(report) {
  return (report?.findings ?? []).filter((finding) => finding.severity === ERROR);
}

// --- CLI ----------------------------------------------------------------------
// `node spec-preflight.mjs --spec catalog.spec.json --previews previews.json
//    [--exclude-preview-id <patterns>…] [--exclude-preview-id-file <file>] [--warn-only] [--json]`
//
// Exit 0 when nothing is wrong, 1 on findings (0 with `--warn-only`), 2 on bad arguments. The
// manifest is any `{ previews: [...] }` payload: a module's `build/compose-previews/previews.json`,
// `compose-preview list --json`, or a bundle manifest — including one kept from the last good run,
// which is the point: the manifest changes rarely, the spec and its exclusions change constantly.
if (import.meta.url === `file://${process.argv[1]}`) {
  const { values } = parseArgs({
    options: {
      spec: { type: "string" },
      previews: { type: "string" },
      "exclude-preview-id": { type: "string", multiple: true },
      "exclude-preview-id-file": { type: "string" },
      // Report without failing — the posture of a shared pipeline adding this ahead of a render it
      // is not yet ready to gate on, and the counterpart of the workflow's `allow-incomplete`.
      "warn-only": { type: "boolean", default: false },
      json: { type: "boolean", default: false },
      help: { type: "boolean", default: false },
    },
  });
  if (values.help || !values.spec || !values.previews) {
    const usage =
      "usage: spec-preflight.mjs --spec <catalog.spec.json> --previews <previews.json> " +
      "[--exclude-preview-id <patterns>]… [--exclude-preview-id-file <file>] [--warn-only] [--json]";
    console.log(usage);
    process.exit(values.help ? 0 : 2);
  }
  const spec = JSON.parse(readFileSync(values.spec, "utf8"));
  const previews = previewsFromJson(JSON.parse(readFileSync(values.previews, "utf8")));
  const fromFile = values["exclude-preview-id-file"]
    ? readFileSync(values["exclude-preview-id-file"], "utf8").split("\n")
    : [];
  const report = preflightSpec(spec, previews, {
    excludePatterns: [...(values["exclude-preview-id"] ?? []), ...fromFile],
  });
  const errors = errorsOf(report);
  if (values.json) {
    console.log(JSON.stringify({ ...report, ok: errors.length === 0 }, null, 2));
  } else {
    const { counts } = report;
    console.log(
      `Preflight ${values.spec} against ${counts.previews} manifest preview id(s): ` +
        `${counts.excluded} excluded, ${counts.survivors} surviving, ` +
        `${counts.claimedFunctions} @Preview function(s) claimed by the spec.`,
    );
    for (const { pattern, matches } of report.patterns) {
      console.log(`  exclusion "${pattern}" matches ${matches} id(s)`);
    }
    for (const finding of report.findings) annotate(finding);
    if (report.unclaimed.length > 0) {
      // Information, not a finding: a catalog is allowed to curate, and a repository-wide publish
      // generates entries for exactly these.
      console.log(
        `  info: ${report.unclaimed.length} surviving id(s) no spec component claims: ` +
          `${report.unclaimed.slice(0, 10).join(", ")}${report.unclaimed.length > 10 ? ", …" : ""}`,
      );
    }
    console.log(
      errors.length === 0
        ? "OK — no config-shape problems decidable from this manifest."
        : `${values["warn-only"] ? "FINDINGS" : "FAILED"} — ${errors.length} finding(s).`,
    );
  }
  process.exit(errors.length > 0 && !values["warn-only"] ? 1 : 0);

  function annotate(finding) {
    const sink = finding.severity === ERROR ? console.error : console.log;
    if (process.env.GITHUB_ACTIONS === "true") {
      const escape = (value) =>
        String(value).replaceAll("%", "%25").replaceAll("\r", "%0D").replaceAll("\n", "%0A");
      const level = finding.severity === ERROR && !values["warn-only"] ? "error" : "warning";
      sink(`::${level} title=${escape(`Spec preflight: ${finding.kind}`)}::${escape(finding.message)}`);
    } else {
      sink(`  ${finding.severity}: ${finding.message}`);
    }
  }
}
