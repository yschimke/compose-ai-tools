/**
 * Fold a catalog spec component's state `variants` onto its default render.
 *
 * A catalog spec component names one default `preview` plus, optionally, a list
 * of `variants` — extra state renders (`pressed`, `focused`, `disabled`, `off`,
 * `unchecked`, …), each its own `@Preview` function. This helper joins them:
 * the default preview's images stay first (they keep their own `state`, usually
 * `"default"`, so the grid hero is the resting component), and every variant's
 * images are appended, **re-tagged** with the variant's `state`. The result is
 * one sticker that carries the default plus each state, distinguished by
 * `Image.state` — so the catalog manifest gives each a collision-free path
 * (`images/<id>/ideal__<state>[__theme][__size].png`) and the single-component
 * view can surface the states as secondary previews.
 *
 * Pure and dependency-free (no `@design-parity/*`, no I/O) so it unit-tests
 * without an `npm ci`. Consumed by the vendored `catalogFromCandidates` join in
 * `generate-design-catalog.mjs`.
 *
 * @param {Array<{state?: string}>} defaultImages the default preview's images.
 * @param {{componentId: string, variants?: Array<{state: string, preview: string}>}} component
 *   the spec component (its `variants` drive the fold).
 * @param {Map<string, {images: Array<object>}>} byFunction rendered candidates
 *   keyed by `@Preview` function name.
 * @returns {{ideal: Array<object>, missing: string[]}} the merged image list and
 *   any variant previews that produced no render (as `"<componentId> [<state>]"`),
 *   so the caller's completeness gate can still refuse a half-rendered sticker.
 */
export function foldVariants(defaultImages, component, byFunction) {
  const ideal = [...defaultImages];
  const missing = [];
  for (const variant of component.variants ?? []) {
    const candidate = byFunction.get(variant.preview);
    if (!candidate || candidate.images.length === 0) {
      missing.push(`${component.componentId} [${variant.state}]`);
      continue;
    }
    for (const image of candidate.images) ideal.push({ ...image, state: variant.state });
  }
  return { ideal, missing };
}
