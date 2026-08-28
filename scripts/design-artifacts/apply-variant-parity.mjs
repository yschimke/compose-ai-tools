/**
 * Stamp each spec component's **compared variants** onto the built catalog manifest.
 *
 * The sibling of {@link file://./apply-parallels.mjs}, for the same reason and against the same
 * gap: `toCatalogManifest` allow-lists the component fields it knows and silently drops the rest,
 * so a published `catalog.json` carries neither `variants` nor the kit correspondence they now
 * declare. The compare page reads the manifest, so without this a variant is invisible to it.
 *
 * ### Why the manifest needs them at all
 *
 * A variant's *pixels* already reach the manifest: `catalog-variants.mjs` folds each variant's
 * render onto the parent's `images[]`, re-tagged with the variant's `state` / `props`. What does
 * not reach it is the variant's **identity** — that this particular tagged image is a distinct
 * render with a counterpart of its own. Pairing needs both: the image to show, and the `parallel`
 * to pair it against.
 *
 * ### Only variants with something to say
 *
 * A variant that declares no kit correspondence is left out. Its images are already on the parent
 * and nothing downstream would read the entry — carrying every variant would restate the fold in a
 * second shape and grow the published manifest for no consumer. A variant that declares any of
 * `parallel` / `reference` / `referenceSet` / `noReference` is carried whole, because those four
 * are exactly the fields something downstream joins on.
 *
 * Additive and idempotent, like its sibling: a component that already carries `variants` (a future
 * `toCatalogManifest` that learns the field) is left untouched, so bumping the pin makes this a
 * no-op rather than a conflict.
 *
 * Like `applyParallels`, this carries declarations verbatim and does NOT check that the sibling has
 * such a component. That resolution happens against the sibling's own inventory when the compare
 * page is built, where a `parallel` naming nothing is reported as an unpaired row. Dropping it here
 * would hide a spec typo behind an absent field instead.
 */

import { selectOf } from "./catalog-select.mjs";

/** The four fields that make a variant worth carrying into the manifest. */
const PARITY_FIELDS = ["parallel", "reference", "referenceSet", "noReference"];

/**
 * The subset of a spec variant the manifest carries: its identity (`preview`, and whichever of
 * `state` / `props` / `theme` / `select` distinguishes it) plus its kit correspondence.
 * Deliberately not the whole spec variant — `capture` and `priority` steer the render, and the
 * render has already happened by the time this runs.
 *
 * `select` was dropped for that same reason and should not have been: it steers the render AND
 * identifies it. A variant may be distinguished by `select` ALONE — `{ select: { size: "small" } }`
 * with no state, props or theme is a legal and useful entry — and the compare page picks a
 * variant's thumbnail back out of its parent's folded `images[]` by matching the axes carried here.
 * With none to match on, every default-state image qualified and the widest won: the large render,
 * captioned as the small variant. Carried as the spec spells it, so `selectImages` and
 * `imageHasVariantAxes` read it the same way downstream as they do upstream.
 *
 * @param {object} variant a spec variant
 * @returns {object|null} the manifest form, or null when it declares no kit correspondence
 */
export function comparedVariant(variant) {
  if (!variant || typeof variant !== "object") return null;
  const carried = {};
  for (const field of PARITY_FIELDS) {
    // A blank is the annotation default (`@CatalogVariant(parallel = "")`) and means "none".
    // Publishing it as an empty string would make an absent pairing look like a declared one to
    // every consumer that tests for the field's presence — the same rule `parallelIndex` applies.
    const value = typeof variant[field] === "string" ? variant[field].trim() : "";
    if (value) carried[field] = value;
  }
  if (Object.keys(carried).length === 0) return null;
  const out = { preview: variant.preview };
  if (variant.state !== undefined) out.state = variant.state;
  if (variant.props !== undefined) out.props = variant.props;
  if (variant.theme !== undefined) out.theme = variant.theme;
  // `selectOf` rather than a bare presence test: `select: {}` is "no selection" everywhere else,
  // and carrying it would make an empty object read as an axis the images must satisfy.
  if (selectOf(variant)) out.select = variant.select;
  if (variant.caption !== undefined) out.caption = variant.caption;
  if (variant.referenceContentsOnly === false) out.referenceContentsOnly = false;
  return { ...out, ...carried };
}

/**
 * `componentId -> compared variants`, for every spec component that has any.
 *
 * @param {{groups?: Array<{components?: Array<object>}>}} spec
 * @returns {Map<string, object[]>}
 */
export function comparedVariantIndex(spec) {
  const byComponentId = new Map();
  for (const group of spec?.groups ?? []) {
    for (const component of group.components ?? []) {
      const carried = (component?.variants ?? []).map(comparedVariant).filter(Boolean);
      if (carried.length) byComponentId.set(component.componentId, carried);
    }
  }
  return byComponentId;
}

/**
 * @param {{components?: Array<{componentId: string, variants?: object[]}>}} manifest
 *   The parsed `catalog.json`, mutated in place.
 * @param {{groups?: Array<{components?: Array<object>}>}} spec
 *   The catalog spec the manifest was built from.
 * @returns {number} how many components had `variants` newly stamped.
 */
export function applyVariantParity(manifest, spec) {
  const byComponentId = comparedVariantIndex(spec);

  let stamped = 0;
  for (const component of manifest?.components ?? []) {
    if (component.variants !== undefined) continue; // never clobber an exporter that carries them
    const variants = byComponentId.get(component.componentId);
    if (variants !== undefined) {
      component.variants = variants;
      stamped += 1;
    }
  }
  return stamped;
}
