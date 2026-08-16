/**
 * Re-stamp each component's `motion` axis onto the built catalog manifest.
 *
 * The join sets `source.motion` before handing sources to `buildCatalog`, but
 * `@design-parity/catalog-export` builds its component entries from an
 * ALLOW-LIST — `buildComponent` copies the fields it knows, and
 * `toCatalogManifest` copies the fields it knows again — and `motion` is not
 * among them in the pinned release (0.1.46). So every declared capture was
 * dropped between the join and `catalog.json`, and dropped *silently*: the
 * publish pass downstream reads `manifest.components[].motion`, found nothing to
 * copy, reported nothing missing (there were no declarations left to miss), and
 * logged nothing at all. The delivery branch grew no `motion/` directory, the
 * serve host parsed no captures, and a preview page that had a 60fps recording
 * sitting in its render bundle showed no Motion chip.
 *
 * Everything either side of that gap already worked: discovery emits
 * `captures[].interaction`, the renderer writes the APNG, and `bundle pack`
 * carries it as `previews/<stem>.apng`. This closes the gap in the same
 * post-process pass that re-stamps `section` ({@link file://./apply-spec-sections.mjs})
 * and `sourceFile` ({@link file://./apply-source-files.mjs}) for exactly the same
 * reason.
 *
 * Additive and idempotent by design:
 *  - a component the join computed no captures for is untouched;
 *  - a component that ALREADY carries `motion` (a future catalog-export that
 *    learns the field, or a merged-in section written straight onto the JSON) is
 *    left exactly as-is — this never overwrites a declaration it didn't make.
 *
 * Once `@design-parity/catalog-export` propagates `motion` and the pin is bumped,
 * this becomes a redundant no-op and can be dropped.
 *
 * Pure and dependency-free so it unit-tests without an `npm ci`, like its sibling
 * axis modules.
 */

/**
 * Stamp the joined motion declarations onto [manifest].
 *
 * @param {{components?: Array<{componentId: string, motion?: Array<object>}>}} manifest
 *   The parsed `catalog.json`, mutated in place.
 * @param {Map<string, Array<object>>} motionByComponentId
 *   Component id → the captures {@link foldMotion} resolved for it.
 * @returns {{stamped: number, captures: number, unmatched: string[]}}
 *   How many components gained a `motion` axis, how many captures that was in
 *   total, and the component ids the join produced captures for that the manifest
 *   has no entry for — a drift signal, since a component that reached the join
 *   should have reached the manifest.
 */
export function applyMotion(manifest, motionByComponentId) {
  const pending = new Map(motionByComponentId ?? []);
  let stamped = 0;
  let captures = 0;
  for (const component of manifest?.components ?? []) {
    const motion = pending.get(component.componentId);
    if (motion === undefined) continue;
    pending.delete(component.componentId);
    if (component.motion !== undefined) continue; // never clobber an existing axis
    if (motion.length === 0) continue;
    component.motion = motion;
    stamped += 1;
    captures += motion.length;
  }
  return {
    stamped,
    captures,
    unmatched: [...pending.keys()].filter((id) => (pending.get(id)?.length ?? 0) > 0),
  };
}
