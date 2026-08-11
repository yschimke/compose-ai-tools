/**
 * Build a published catalog's `references/index.json` from the repo's `design-map.json`.
 *
 * The preview server has served design references — the **PNG ↔ Design reference** lane on
 * `/compare`, and the focused Reference / Diff / Actual page behind it — since
 * `ServeDesignReferences.kt` landed, reading a `compose-preview-references/v1` manifest from
 * `references/index.json` on the catalog's delivery branch. Nothing ever *wrote* one: the schema
 * string existed only in the server and its own tests, so `data-has-reference` was `0` on every
 * published catalog and the lane never appeared. This module is the missing producer.
 *
 * It is the join between two id namespaces that were never wired together:
 *
 * - `design-map.json` (design-parity's correspondence file) keys a reference by the **code handle**
 *   `path/File.kt#PreviewFunction`, plus the raw compose-preview discovery `previewId`.
 * - The published catalog keys a sticker by its **route-safe serve preview id**
 *   (`chat-contact__ideal__default__dark__compact`), derived from the image path — see
 *   `ServeCatalogStore.previewIdFor`, restated in [servePreviewId].
 *
 * `catalog.spec.json` is what bridges them: every spec `preview` (and `variants[].preview`) is an
 * exact `@Preview` **function name**, which is also the `#Member` of a design-map code handle. So
 * the mapping is `code handle → function name → spec component + variant slot → catalog image →
 * serve preview id`. Deliberately keyed off the function name rather than the discovery `previewId`,
 * because the discovery id is a rendering detail (it carries the `@Preview` `name=` and gets
 * filename-sanitised on the way into a bundle) while the function name is the stable identity both
 * files already agree on.
 *
 * Pure and dependency-free (no `@design-parity/*`, no I/O) so it unit-tests without an `npm ci`,
 * like its siblings `catalog-image-path.mjs` / `catalog-variants.mjs`. The rasterising half — turn
 * an HTML/Figma reference into the canonical PNG the server compares — lives in
 * `emit-design-references.mjs`, which drives this.
 */

import { imageHasVariantAxes } from "./catalog-variants.mjs";

/** The manifest `schema` the serve host requires; anything else is ignored wholesale. */
export const REFERENCES_SCHEMA = "compose-preview-references/v1";

/** Directory (bundle-relative) the manifest and its rasters live in. */
export const REFERENCES_DIR = "references";

/**
 * `ServeDesignReferenceStore.SAFE_ID` — a reference id is a URL path segment on
 * `/reference/{id}.png`, so it stays in the conservative slug alphabet and is length-capped.
 * A record whose id fails this is dropped by the server *silently*, so [referenceId] caps rather
 * than emitting something that would vanish on the box.
 */
const MAX_ID_LENGTH = 160;

/**
 * The route-safe serve preview id for a catalog image path. Mirrors
 * `ServeCatalogStore.previewIdFor`: drop the `images/` prefix and `.png` suffix, and flatten `/` to
 * `__` (the separator the variant keys already use).
 *
 * This restates a derivation that lives in Kotlin, which is exactly the drift risk this file has to
 * answer for. It does so by being **checkable**: the catalog the export already wrote records a
 * `livePreview` deep link per image, whose last path segment is this same id — so
 * [derivationMismatches] re-derives every image's id and compares. A scheme change surfaces as a
 * loud mismatch on the next catalog build rather than as references that 404 on the server.
 */
export function servePreviewId(imagePath) {
  return String(imagePath ?? "")
    .replace(/^images\//, "")
    .replace(/\.png$/, "")
    .replace(/\//g, "__");
}

/**
 * Images whose derived [servePreviewId] disagrees with the `livePreview` deep link the catalog
 * export recorded, as `"<path>: derived <a> but livePreview says <b>"`. Empty ⇒ the derivation
 * still matches the exporter. Images with no `livePreview` (most catalogs record it only where a
 * live lane exists) are skipped — there is nothing to check them against.
 */
export function derivationMismatches(catalog) {
  const problems = [];
  for (const component of catalog?.components ?? []) {
    for (const image of component?.images ?? []) {
      const link = image?.livePreview;
      if (typeof link !== "string" || link === "") continue;
      const recorded = link.split("?")[0].replace(/\/$/, "").split("/").pop();
      const derived = servePreviewId(image.path);
      if (recorded !== derived) {
        problems.push(`${image.path}: derived ${derived} but livePreview says ${recorded}`);
      }
    }
  }
  return problems;
}

/** The `#Member` of a design-map code handle (`ui/Foo.kt#Bar` → `Bar`), or null. */
export function functionNameOf(code) {
  const hash = String(code ?? "").indexOf("#");
  if (hash <= 0) return null;
  const member = String(code).slice(hash + 1);
  return member === "" ? null : member;
}

/**
 * Index a catalog's images by the `@Preview` function that produced them:
 * `functionName -> [{ componentId, image }]`.
 *
 * A spec component's images are partitioned between its default `preview` and its `variants`: an
 * image claimed by some variant belongs to that variant's function, and everything else to the
 * default. Order matters only in that a variant claim wins — the default is the residue, which is
 * what keeps a component whose light/dark are two separate `@Preview`s from assigning both stickers
 * to the light function.
 *
 * The claim test is `foldVariants`' own [imageHasVariantAxes], which requires EVERY axis a variant
 * declares. Matching on just the first declared axis would let two variants that share a `state`
 * but differ in `props` collide — the first would claim both stickers and publish its reference
 * against the wrong one, while the second went unmapped. Sharing the fold's predicate also keeps
 * the `fontScale` numeric coercion in one place.
 */
export function imagesByPreviewFunction(spec, catalog) {
  const byComponentId = new Map();
  for (const component of catalog?.components ?? []) {
    if (component?.componentId) byComponentId.set(component.componentId, component);
  }

  const index = new Map();
  const add = (fn, componentId, image) => {
    if (!fn) return;
    if (!index.has(fn)) index.set(fn, []);
    index.get(fn).push({ componentId, image });
  };

  for (const group of spec?.groups ?? []) {
    for (const specComponent of group?.components ?? []) {
      const component = byComponentId.get(specComponent?.componentId);
      if (!component) continue;
      const variants = Array.isArray(specComponent.variants) ? specComponent.variants : [];
      for (const image of component.images ?? []) {
        const variant = variants.find((v) => v?.preview && imageHasVariantAxes(image, v));
        if (variant) add(variant.preview, component.componentId, image);
        else add(specComponent.preview, component.componentId, image);
      }
    }
  }
  return index;
}

/**
 * Index a catalog's images by the daemon `previewId` that produced them:
 * `previewId -> [{ componentId, image }]`.
 *
 * The fallback join for an **annotation-led catalog**. [imagesByPreviewFunction] walks
 * `spec.groups` to learn which `@Preview` function produced which sticker — but a catalog whose
 * inventory lives in `@CatalogComponent` / `@CatalogVariant` annotations has no `groups` at all
 * (`catalog.spec.json` carries only cover-sheet fields), so that index comes back empty and every
 * design-map entry warns "matches no published sticker" while the catalog publishes happily around
 * it. The references simply never appear on the delivery branch, and the server's PNG ↔ Design
 * reference lane stays dark.
 *
 * Reintroducing `groups` purely to satisfy this join would restate the whole inventory in JSON —
 * the exact duplication the annotations exist to remove, and a second source of truth that can
 * drift from the first.
 *
 * `previewId` is the better key anyway: it is what the export already stamps on every catalog
 * image, and what a design-map entry already carries to disambiguate light from dark. Joining on it
 * is exact, where the function-name join needs the spec to say which function owns which sticker.
 *
 * ### Two id namespaces
 *
 * The two sides are NOT written in the same alphabet, and comparing them verbatim silently drops
 * references. A design-map entry records the **raw discovery id** — the one the daemon keys renders
 * on — while a catalog image's `previewId` comes from the bundle manifest, which carries the
 * **sanitised in-bundle form** (`BundleCommand.kt`: "The manifest's previewIds carry the sanitised
 * in-bundle form; the daemon keys renders on the RAW discovery id"). Anything outside
 * `[A-Za-z0-9._-]` becomes `_`, so a `@Preview(name = "Small Round")` is `…_Small Round` in the
 * design-map and `…_Small_Round` in the catalog.
 *
 * So the index is keyed by BOTH forms. An exact hit wins; otherwise the entry's id is sanitised and
 * matched against the same projection. Catalogs whose preview names need no sanitising (`Light` /
 * `Dark`) are unaffected either way — which is exactly why this gap survives casual testing.
 */
export function imagesByPreviewId(catalog) {
  const exact = new Map();
  const sanitised = new Map();
  for (const component of catalog?.components ?? []) {
    for (const image of component?.images ?? []) {
      if (typeof image?.previewId !== "string" || image.previewId === "") continue;
      const match = { componentId: component.componentId, image };
      if (!exact.has(image.previewId)) exact.set(image.previewId, []);
      exact.get(image.previewId).push(match);

      const key = sanitizeBundleEntryId(image.previewId);
      if (!sanitised.has(key)) sanitised.set(key, []);
      sanitised.get(key).push(match);
    }
  }
  const collisionBases = new Set();
  for (const id of exact.keys()) {
    const suffix = id.match(/^(.*)_([1-9][0-9]*)$/);
    if (suffix && exact.has(suffix[1])) collisionBases.add(suffix[1]);
  }
  return { exact, sanitised, collisionBases };
}

/**
 * Mirrors `sanitizeBundleEntryId` in `gradle-plugin/.../BundlePreviewTask.kt`, the transform that
 * turns a raw discovery id into its in-bundle entry name. Kept character-identical to that regex:
 * a divergence here reintroduces exactly the silent-drop bug this exists to close.
 *
 * Note it is idempotent — sanitising an already-sanitised id is a no-op — which is what lets the
 * lookup below sanitise both sides without caring which form it started from.
 */
export function sanitizeBundleEntryId(id) {
  return String(id).replace(/[^A-Za-z0-9._-]/g, "_");
}

/**
 * The base id of a bundle collision family containing [previewId], or null.
 *
 * `assignBundleEntryIds` lets the first sanitised claimant keep `Foo_A_B` and names later
 * claimants `Foo_A_B_1`, `Foo_A_B_2`, ... . A published catalog retains those bundle ids but not
 * the manifest's parallel raw-id aliases, so seeing the base plus a numeric suffix is the only
 * evidence available here that reversing the sanitisation would be unsafe.
 */
function collisionFamilyBase(publishedPreviewIds, previewId) {
  const ids = new Set(publishedPreviewIds);
  const bases = new Set();
  for (const id of ids) {
    const suffix = String(id).match(/^(.*)_([1-9][0-9]*)$/);
    if (suffix && ids.has(suffix[1])) bases.add(suffix[1]);
  }

  const mapped = sanitizeBundleEntryId(previewId);
  if (bases.has(mapped)) return mapped;
  const mappedSuffix = mapped.match(/^(.*)_([1-9][0-9]*)$/);
  return mappedSuffix && bases.has(mappedSuffix[1]) ? mappedSuffix[1] : null;
}

/**
 * Resolve a design-map entry's `previewId` against the catalog, across both id namespaces.
 *
 * Returns `[]` when nothing matches, and also when the published ids form a bundle collision family
 * (`Foo_A_B`, `Foo_A_B_1`, ...). The raw aliases that distinguish those entries are no longer
 * present in catalog.json, so even an apparent exact hit can belong to the colliding sibling.
 * Publishing a reference against a coin-flip is worse than publishing none, so the caller warns
 * instead.
 */
function matchesForPreviewId({ exact, sanitised, collisionBases }, previewId) {
  const mapped = sanitizeBundleEntryId(previewId);
  const suffix = mapped.match(/^(.*)_([1-9][0-9]*)$/);
  if (collisionBases.has(mapped) || (suffix && collisionBases.has(suffix[1]))) return [];
  const direct = exact.get(previewId);
  if (direct && direct.length > 0) return direct;
  const viaSanitised = sanitised.get(mapped) ?? [];
  return viaSanitised.length === 1 ? viaSanitised : [];
}

/**
 * The reference id for a serve preview id, disambiguated when one preview carries several
 * references (a Figma node *and* a committed HTML mock, say). Capped at the server's id length —
 * an over-long id is dropped silently on the box, so it must never be emitted.
 */
export function referenceId(previewId, ordinal) {
  const suffix = ordinal > 0 ? `--${ordinal + 1}` : "";
  const base = String(previewId).replace(/[^A-Za-z0-9._-]+/g, "-");
  return base.slice(0, MAX_ID_LENGTH - suffix.length) + suffix;
}

/**
 * The provider token recorded on a reference, from the design-map entry's `source`.
 * `claude-design` exports and plain committed PNGs are both `file`-ish, but keeping the source
 * verbatim is what makes the served "Source: …" line say where the design actually came from.
 */
function providerFor(source) {
  return typeof source === "string" && source !== "" ? source : "file";
}

/**
 * Narrow a function's matched stickers to the one the design-map entry's `previewId` names.
 *
 * [imagesByPreviewFunction] partitions a component's images between its default `preview` and its
 * declared `variants`, which separates light from dark when they are two *different* `@Preview`
 * functions. It cannot separate them when they are two `@Preview` annotations on the SAME function
 * — the multipreview shape (`@Preview(name = "Light")` + `@Preview(name = "Dark", uiMode = …)`),
 * where both stickers legitimately belong to one function name. A design-map entry maps one code
 * handle to one reference, so without this narrowing a light-only reference is published against
 * the dark sticker too, and the server scores a dark render against a light design.
 *
 * The entry's `previewId` is exactly the discriminator: it names the rendered preview, so a
 * component-level `…_Light` entry keeps the light sticker and drops the dark one.
 *
 * Applied only when the matched images actually carry `previewId`s. Renders folded in through the
 * generator's `--extra-renders` don't, and narrowing on an absent field would silently unmap every
 * one of them — so an unlabelled match set is passed through whole, exactly as before.
 */
function narrowToMappedPreviewId(matches, mappedPreviewId, fn, warnings) {
  if (typeof mappedPreviewId !== "string" || mappedPreviewId === "") return matches;
  const labelled = matches.filter(({ image }) => typeof image?.previewId === "string");
  if (labelled.length === 0) return matches;

  const collisionBase = collisionFamilyBase(
    labelled.map(({ image }) => image.previewId),
    mappedPreviewId,
  );
  if (collisionBase) {
    warnings.push(
      `design-map '${fn}' names previewId '${mappedPreviewId}', but published bundle ids in ` +
        `the '${collisionBase}' collision family cannot be reversed without raw-id aliases`,
    );
    return [];
  }

  const exact = labelled.filter(({ image }) => image.previewId === mappedPreviewId);
  if (exact.length > 0) {
    const unlabelled = matches.filter(({ image }) => typeof image?.previewId !== "string");
    return [...exact, ...unlabelled];
  }

  const sanitised = labelled.filter(
    ({ image }) => sanitizeBundleEntryId(image.previewId) === sanitizeBundleEntryId(mappedPreviewId),
  );
  // A sanitised collision without a published suffix is still ambiguous when this match set carries
  // several unsanitised ids. Keep exact matches as-is; accept the sanitised fallback only when it
  // names one sticker. Real bundle collision suffixes were rejected above.
  const narrowed = sanitised.length === 1 ? sanitised : [];
  if (narrowed.length === 0) {
    warnings.push(
      `design-map '${fn}' names previewId '${mappedPreviewId}', which matches none of its ` +
        `published stickers (${labelled.map(({ image }) => image.previewId).join(", ")})`,
    );
    return [];
  }
  // Anything the catalog left unlabelled stays in: it can't be ruled out, and dropping it would
  // lose a reference the old behaviour published.
  const unlabelled = matches.filter(({ image }) => typeof image?.previewId !== "string");
  return [...narrowed, ...unlabelled];
}

/**
 * Reduce a variant-aware design-map entry to the untagged binding the catalog overview publishes.
 *
 * design-parity accepts `ref` / `previewId` arrays so one code component can bind every authored
 * state, size and theme. The catalog reference lane currently publishes one inert raster per
 * component, however, and historically understood only scalar bindings. Treating an array as a
 * raster handle made the whole entry fail-soft out of the bundle, which made adding exact parity
 * coverage remove the component's previously published reference.
 *
 * The untagged item is the array form's default binding. Publish that one here; the parity workflow
 * continues to consume the complete arrays directly. Refuse ambiguous or all-tagged arrays instead
 * of guessing which state represents the component.
 */
function primaryDesignBinding(entry, warnings) {
  const primary = (value, field) => {
    if (!Array.isArray(value)) return value;
    const untagged = value.filter(
      (item) => item && !item.state && !item.theme && !item.size,
    );
    if (untagged.length !== 1) {
      warnings.push(
        `design-map '${functionNameOf(entry?.code) ?? entry?.code ?? "?"}' has ${untagged.length} ` +
          `untagged ${field} bindings; exactly one is required for catalog publication`,
      );
      return undefined;
    }
    return typeof untagged[0] === "string" ? untagged[0] : untagged[0]?.[field];
  };

  const warningCount = warnings.length;
  const ref = primary(entry?.ref, "ref");
  const refWarningAdded = warnings.length > warningCount;
  const previewId = primary(entry?.previewId, "previewId");
  if (typeof ref !== "string" || ref === "") {
    if (!refWarningAdded) {
      warnings.push(
        `design-map '${functionNameOf(entry?.code) ?? entry?.code ?? "?"}' has an invalid ref ` +
          `binding; expected a non-empty string`,
      );
    }
    return undefined;
  }
  if (Array.isArray(entry?.previewId) && (typeof previewId !== "string" || previewId === "")) {
    return undefined;
  }
  return { ...entry, ref, previewId };
}

/**
 * Plan the reference records for a repo: which design-map entries map onto which published
 * stickers, and what each one's raster has to look like.
 *
 * Returns `{ records, warnings }`. Each record is a manifest entry *minus* its raster bytes —
 * `raster.path` names where the driver must write the PNG, and `raster.width`/`height` are the
 * exact dimensions it must produce, taken from the catalog image it will be compared against. Those
 * dimensions are not advisory: the server's comparison refuses to score a pair whose sizes differ
 * ("Unavailable · reference and actual dimensions differ") rather than scale one into a misleading
 * number, so a reference that isn't resampled to its sticker's size shows up as a dead row.
 *
 * Entries that map to nothing (a code handle whose function isn't in the spec, or whose component
 * rendered no sticker) land in `warnings` rather than failing the export — a repo may map more
 * components for its own parity run than it publishes to the catalog, and that is not an error.
 */
export function planDesignReferences({ designMap, spec, catalog }) {
  const warnings = [];
  const records = [];
  const index = imagesByPreviewFunction(spec, catalog);
  // Only built when the spec-driven index yields nothing for an entry — see [imagesByPreviewId].
  const byPreviewId = imagesByPreviewId(catalog);
  const ordinals = new Map();

  for (const rawEntry of designMap?.components ?? []) {
    const entry = primaryDesignBinding(rawEntry, warnings);
    if (!entry) continue;
    const fn = functionNameOf(entry?.code);
    if (!fn) {
      warnings.push(`design-map entry has no 'path#Member' code handle: ${entry?.code ?? "?"}`);
      continue;
    }
    // Function name first — it is the join a spec-led catalog needs, and it stays authoritative
    // where a spec exists. An annotation-led catalog has no `groups` for that index to walk, so
    // fall back to the entry's own `previewId`, which the export stamps on every catalog image.
    let allMatches = index.get(fn);
    let joinedOnPreviewId = false;
    if ((!allMatches || allMatches.length === 0) && typeof entry?.previewId === "string") {
      allMatches = matchesForPreviewId(byPreviewId, entry.previewId);
      joinedOnPreviewId = allMatches.length > 0;
    }
    if (!allMatches || allMatches.length === 0) {
      warnings.push(
        `design-map '${fn}' matches no published sticker — no catalog.spec.json ` +
          `preview names that @Preview function, no published image carries its previewId, ` +
          `or its component rendered nothing`,
      );
      continue;
    }
    // A previewId join has already selected exactly the named sticker, so re-narrowing on the same
    // field is a no-op at best; skip it so the intent reads once.
    const matches = joinedOnPreviewId
      ? allMatches
      : narrowToMappedPreviewId(allMatches, entry?.previewId, fn, warnings);
    if (matches.length === 0) continue;
    for (const { componentId, image } of matches) {
      const previewId = servePreviewId(image.path);
      const ordinal = ordinals.get(previewId) ?? 0;
      ordinals.set(previewId, ordinal + 1);
      const id = referenceId(previewId, ordinal);
      const record = {
        id,
        previewId,
        label: `${componentId} — ${entry.source ?? "design"}`,
        raster: {
          path: `${REFERENCES_DIR}/${id}.png`,
          width: image.width,
          height: image.height,
          // Newer exporters may retain the @Preview renderer density on the image. It is
          // driver-only here and lets a Figma component be requested at the exact non-default
          // density that produced its Compose sticker.
          ...(typeof image.density === "number" ? { density: image.density } : {}),
        },
        source: {
          provider: providerFor(entry.source),
          attributes: { code: entry.code, preview: fn, componentId },
        },
        // How the driver is to obtain the pixels, plus what it needs to describe them. Not part of
        // the served manifest. `density` is the design-map author's statement of the reference
        // board's scale (source px per dp); it is what lets the annotation layer quote the design's
        // spacing and type in the same dp/sp the render resolved instead of the board's own pixels
        // (design-parity#279). Absent when the entry doesn't declare it, and never guessed.
        origin: {
          source: entry.source,
          ref: entry.ref,
          previewId: entry.previewId,
          density: entry.density,
          ...(typeof entry.referenceContentsOnly === "boolean"
            ? { referenceContentsOnly: entry.referenceContentsOnly }
            : {}),
        },
      };
      if (entry.ref) record.source.uri = entry.ref;
      records.push(record);
    }
  }
  return { records, warnings };
}

/**
 * The served `compose-preview-references/v1` document for a set of planned records. Drops each
 * record's driver-only `origin` and any record the driver couldn't rasterise (`rastered === false`),
 * so a Figma reference that needed a token the run didn't have leaves the rest of the manifest
 * intact instead of publishing a record whose PNG is missing — which the server would drop anyway,
 * just silently.
 */
export function referenceManifest(records) {
  return {
    schema: REFERENCES_SCHEMA,
    references: records
      .filter((r) => r.rastered !== false)
      .map(({ origin, rastered, ...reference }) => reference),
  };
}
