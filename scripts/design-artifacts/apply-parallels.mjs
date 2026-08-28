/**
 * Stamp each spec component's `parallel` (its counterpart in the `compareWith` sibling) onto the
 * built catalog manifest.
 *
 * `parallel` has been build-time-only in practice. It is on the *bundle* wire model
 * (`PreviewData.kt`'s `CatalogEntry.parallel`) and the generator reads it off the spec to build
 * `matches.html` — but the PUBLISHED `catalog.json` never carried it, because `toCatalogManifest`
 * allow-lists the component fields it knows and drops the rest with no error anywhere. The same
 * silent drop that hid `motion` for a day (see {@link file://./motion-carried.mjs}) and that
 * `compareWith` needs its own post-write stamp to escape.
 *
 * Measured, not assumed: `remote-m3`'s published catalog — a sheet whose entire purpose is the
 * cross-system pairing — listed 51 components and zero `parallel` fields.
 *
 * That is what keeps a consumer of the published catalog from resolving the pairing at all. The
 * manifest's `compareWith` says WHICH SYSTEM the counterpart lives in; `parallel` says WHICH
 * COMPONENT. Half a pairing resolves to nothing, so both have to travel for a preview server to
 * offer the sibling as a comparison source (issue #4621).
 *
 * Additive and idempotent, exactly like {@link file://./apply-spec-sections.mjs}:
 *  - only components whose spec entry declares a `parallel` are touched;
 *  - a component that already carries one (a future `toCatalogManifest` that learns the field) is
 *    left as-is, so bumping the pin makes this a no-op rather than a conflict.
 *
 * This carries the declaration verbatim and does NOT check that the sibling has such a component.
 * That resolution already happens where it can be done properly — against the sibling's own
 * inventory, when the compare page is built — and a `parallel` naming nothing there is reported
 * as an unpaired row there. Silently dropping it here would hide a spec typo behind an absent
 * field instead.
 *
 * @param {{components?: Array<{componentId: string, parallel?: string}>}} manifest
 *   The parsed `catalog.json`, mutated in place.
 * @param {{groups?: Array<{components?: Array<{componentId: string, parallel?: string}>}>}} spec
 *   The catalog spec the manifest was built from.
 * @returns {number} how many components had a `parallel` newly stamped.
 */
export function applyParallels(manifest, spec) {
  const parallelByComponentId = new Map();
  for (const group of spec?.groups ?? []) {
    for (const component of group.components ?? []) {
      // A blank declaration is the annotation default (`@CatalogComponent(parallel = "")`), which
      // means "no counterpart" — publishing it as an empty string would make an absent pairing
      // look like a declared one to every consumer that tests for the field's presence.
      const parallel =
        typeof component?.parallel === "string"
          ? component.parallel.trim()
          : "";
      if (parallel) parallelByComponentId.set(component.componentId, parallel);
    }
  }

  let stamped = 0;
  for (const component of manifest?.components ?? []) {
    if (component.parallel !== undefined) continue; // never clobber an exporter that carries it
    const parallel = parallelByComponentId.get(component.componentId);
    if (parallel !== undefined) {
      component.parallel = parallel;
      stamped += 1;
    }
  }
  return stamped;
}
