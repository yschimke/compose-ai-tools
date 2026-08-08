/**
 * Turn a `@PreviewAxis` cell's recorded axis assignment into catalog `props`.
 *
 * A hand-written `@OverrideVariant` has only a name, so the export can say no more about it than
 * `state: "xs-square"` — one opaque string. That string is what design-parity then pairs against a
 * reference's variant properties, so a cell matches the kit only if the name someone typed happens
 * to coincide with the kit's own naming.
 *
 * An axis cell knows what it *is*. Discovery records the full assignment on the variant spec
 * (`OverrideVariantSpec.props`), and this projects it onto the image as
 * `props: {size: "xs", shape: "square"}` — the same shape `@CatalogVariant(props = […])` already
 * produces, so everything downstream (the fold, the viewer's variant switcher, parity's pairing)
 * reads it without further change.
 *
 * **`state` is left alone when props are present.** The two describe the same cell, and stamping
 * both would double-count it: the fold would surface `state:xs-square` *and* a props axis for one
 * render. Props are the better of the two — structured, and matching by property rather than by
 * spelling — so they win, and `state` stays at its default. A cell whose spec carries no props
 * (every pre-existing `@OverrideVariant`) is untouched and keeps its `state` exactly as before.
 *
 * @param {Array<{previewId?: string, id?: string, images?: Array<object>}>} candidates
 *   Candidate renders, mutated in place.
 * @param {Map<string, {props?: Array<{key: string, value: string}>}>} overridesByPreviewId
 *   Preview id → its `overrides` spec from the discovery manifest.
 * @returns {{stamped: number, claimed: Set<object>}} how many images were stamped, and which —
 *   the caller uses `claimed` to leave exactly those images' `state` alone, rather than inferring
 *   it from whether an image happens to carry props from some other source.
 */
export function applyVariantAxisProps(candidates, overridesByPreviewId) {
  const claimed = new Set();
  if (!overridesByPreviewId || overridesByPreviewId.size === 0) return { stamped: 0, claimed };
  let stamped = 0;
  for (const candidate of candidates) {
    const id = candidate.previewId ?? candidate.id;
    const props = overridesByPreviewId.get(id)?.props;
    if (!Array.isArray(props) || props.length === 0) continue;
    const asObject = {};
    for (const { key, value } of props) {
      if (typeof key === "string" && key && typeof value === "string") asObject[key] = value;
    }
    if (Object.keys(asObject).length === 0) continue;
    for (const image of candidate.images ?? []) {
      // Merge under anything already there: a `fontScale` promoted from preview params, or a
      // spec-authored prop, is about a different axis than the knobs and must not be dropped.
      image.props = { ...asObject, ...(image.props ?? {}) };
      claimed.add(image);
      stamped += 1;
    }
  }
  return { stamped, claimed };
}

/**
 * `previewId → overrides spec` for every preview in the bundle that carries one.
 *
 * Keyed by the raw discovery id, which is what the candidate reader hands back — the same key
 * `variantStateFromId` parses its `_VARIANT_` suffix out of.
 *
 * @param {{previews?: Array<{id: string, overrides?: object}>}} bundle
 */
export function overridesByPreviewId(bundle) {
  const out = new Map();
  for (const preview of bundle?.previews ?? []) {
    if (preview?.id && preview.overrides) out.set(preview.id, preview.overrides);
  }
  return out;
}
