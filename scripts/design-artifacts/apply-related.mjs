/**
 * Stamp each spec component's `related` links — its counterparts in OTHER catalogs — onto
 * the built catalog manifest.
 *
 * Same post-write stamp, and the same reason, as {@link file://./apply-parallels.mjs}: the pinned
 * `@design-parity/catalog-export`'s `toCatalogManifest` allow-lists the component fields it knows
 * and drops the rest with no error anywhere, so a field it predates never reaches the published
 * `catalog.json` and nothing says otherwise.
 *
 * What it carries is deliberately NOT a second `parallel`:
 *
 *   - `parallel` (with the manifest's `compareWith`) says two renders are pictures of ONE CELL and
 *     should be diffed. It is one handle, because a parity comparison has one counterpart.
 *   - `related` says only that ANOTHER CATALOG is worth looking at from here. It is a LIST, and
 *     carries no parity semantics — nothing scores, diffs or gates on it.
 *
 * A list rather than a second scalar because the case that needs it has three catalogs, not two.
 * The AndroidX samples import (see `docs/design/ANDROIDX_SAMPLES.md` in yschimke/m3-catalog and
 * yschimke/wear-m3-catalog) publishes an `m3-samples` / `wear-m3-samples` catalog beside each kit
 * catalog, and `remote-m3` has already spent its single `compareWith` on `wear-m3-catalog` —
 * so no pairwise mechanism could also point it at the samples. Issue #5398.
 *
 * Additive and idempotent, exactly like `applyParallels`:
 *  - only components whose spec entry declares a non-empty `related` are touched;
 *  - a component that already carries one (a future `toCatalogManifest` that learns the field) is
 *    left as-is, so bumping the pin makes this a no-op rather than a conflict.
 *
 * Like `parallel`, this carries the declaration verbatim and does NOT check that the named system
 * exists or has such a component. The consumer is the only party that can: a preview server knows
 * which catalogs it serves, and one that does not serve the named system renders no link. Dropping
 * an unresolvable entry here would hide a spec typo behind an absent field instead of surfacing it
 * where the inventory is actually readable.
 */

/** A link is worth publishing only if it names where it points. */
function normaliseLink(link) {
  const system = typeof link?.system === "string" ? link.system.trim() : "";
  if (!system) return null;
  const componentId =
    typeof link?.componentId === "string" ? link.componentId.trim() : "";
  const label = typeof link?.label === "string" ? link.label.trim() : "";
  return {
    system,
    // Omitted rather than emitted blank: an absent `componentId` MEANS "same id as mine" (the
    // id-parity case), and an empty string would read as a declared id that matches nothing.
    ...(componentId ? { componentId } : {}),
    ...(label ? { label } : {}),
  };
}

/**
 * `componentId -> related[]`, for every spec component that declares at least one usable link.
 *
 * Exported for the same reason `parallelIndex` is: the manifest is stamped in two places — over
 * `manifest.components` here, and over the DEFERRED records at their own construction in
 * `generate-design-catalog.mjs`, which are built after this call and so cannot be reached from it.
 * One reader, so the two cannot come to disagree about which links are dropped.
 *
 * @param {{groups?: Array<{components?: Array<{componentId: string, related?: unknown}>}>}} spec
 * @returns {Map<string, Array<{system: string, componentId?: string, label?: string}>>}
 */
export function relatedIndex(spec) {
  const relatedByComponentId = new Map();
  for (const group of spec?.groups ?? []) {
    for (const component of group.components ?? []) {
      if (!Array.isArray(component?.related)) continue;
      const links = component.related.map(normaliseLink).filter(Boolean);
      // An empty list is not published: "declared, and empty" and "not declared" mean the same
      // thing to every consumer, and the shorter one does not make an absent link look declared.
      if (links.length > 0)
        relatedByComponentId.set(component.componentId, links);
    }
  }
  return relatedByComponentId;
}

/**
 * @param {{components?: Array<{componentId: string, related?: unknown}>}} manifest
 *   The parsed `catalog.json`, mutated in place.
 * @param {{groups?: Array<{components?: Array<{componentId: string, related?: unknown}>}>}} spec
 *   The catalog spec the manifest was built from.
 * @returns {number} how many components had `related` newly stamped.
 */
export function applyRelated(manifest, spec) {
  const relatedByComponentId = relatedIndex(spec);

  let stamped = 0;
  for (const component of manifest?.components ?? []) {
    if (component.related !== undefined) continue; // never clobber an exporter that carries it
    const related = relatedByComponentId.get(component.componentId);
    if (related !== undefined) {
      component.related = related;
      stamped += 1;
    }
  }
  return stamped;
}
