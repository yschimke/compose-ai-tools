/**
 * Re-stamp each spec group's top-level `section` onto the built catalog manifest.
 *
 * The generator sets `source.section = group.section` before handing sources to
 * `buildCatalog`, but the pinned `@design-parity/catalog-export` (≤ 0.1.25)
 * doesn't carry a source's `section` through to the emitted component — it never
 * reads the field. So a spec that buckets its groups into top-level tabs
 * (meshcore-mobile: Themes / Components / Screens) would otherwise collapse into
 * a single untabbed "(none)" bucket on the preview server, while an
 * externally-merged section (e.g. the folded-in "Material 3") survives only
 * because it's written straight onto the manifest JSON.
 *
 * This closes that gap in the same post-process pass that injects `livePreview`
 * into the written `catalog.json`: read each group's `section` from the spec and
 * stamp it onto the manifest component with the matching `componentId`. It's the
 * manifest-level analogue of what {@link file://./merge-catalog-section.mjs}
 * already does for the borrowed section.
 *
 * Additive and idempotent by design:
 *  - only components whose spec group declares a `section` are touched;
 *  - a component that already carries a `section` (a future buildCatalog that
 *    learns to propagate it, or the merge step's borrowed components) is left
 *    exactly as-is — this never overwrites an existing tab assignment.
 *
 * Once `@design-parity/catalog-export` propagates `section` and the pin is
 * bumped, this becomes a redundant no-op and can be dropped.
 *
 * @param {{components?: Array<{componentId: string, section?: string}>}} manifest
 *   The parsed `catalog.json`, mutated in place.
 * @param {{groups?: Array<{section?: string, components?: Array<{componentId: string}>}>}} spec
 *   The catalog spec the manifest was built from.
 * @returns {number} how many components had a `section` newly stamped.
 */
export function applySpecSections(manifest, spec) {
  const sectionByComponentId = new Map();
  for (const group of spec?.groups ?? []) {
    if (group.section === undefined) continue;
    for (const component of group.components ?? []) {
      sectionByComponentId.set(component.componentId, group.section);
    }
  }

  let stamped = 0;
  for (const component of manifest?.components ?? []) {
    if (component.section !== undefined) continue; // never clobber an existing tab
    const section = sectionByComponentId.get(component.componentId);
    if (section !== undefined) {
      component.section = section;
      stamped += 1;
    }
  }
  return stamped;
}
