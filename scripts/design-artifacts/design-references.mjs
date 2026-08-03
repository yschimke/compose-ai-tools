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
  const ordinals = new Map();

  for (const entry of designMap?.components ?? []) {
    const fn = functionNameOf(entry?.code);
    if (!fn) {
      warnings.push(`design-map entry has no 'path#Member' code handle: ${entry?.code ?? "?"}`);
      continue;
    }
    const matches = index.get(fn);
    if (!matches || matches.length === 0) {
      warnings.push(
        `design-map '${fn}' matches no published sticker — no catalog.spec.json ` +
          `preview names that @Preview function, or its component rendered nothing`,
      );
      continue;
    }
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
        },
        source: {
          provider: providerFor(entry.source),
          attributes: { code: entry.code, preview: fn, componentId },
        },
        // How the driver is to obtain the pixels. Not part of the served manifest.
        origin: { source: entry.source, ref: entry.ref, previewId: entry.previewId },
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
