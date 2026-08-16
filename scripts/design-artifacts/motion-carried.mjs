/**
 * Guards that the motion axis the join resolved actually survived into `catalog.json`.
 *
 * `buildCatalog` assembles each component from an ALLOW-LIST — `buildComponent` copies the fields
 * it knows, `toCatalogManifest` copies them again — so a field the export engine has not been
 * taught is dropped between the join and the written manifest with no error anywhere.
 * `@design-parity/catalog-export` learned `motion` in 0.1.52; before that it did not, and the
 * result was invisible rather than merely broken: the publish pass reads
 * `manifest.components[].motion`, found nothing to copy, reported nothing missing (there were no
 * declarations left to miss), and its log line is guarded on having done *something* — so a
 * catalog whose render bundle carried correctly rendered 60fps APNGs published with no Motion
 * section, on a green run, for a day, without one warning in the chain.
 *
 * The pin now carries the field, so nothing here re-stamps anything. What this does is make the
 * same failure loud if it ever returns — a downgraded pin, a regressed allow-list, a component the
 * join produced captures for that never reached the manifest. The join knows what it resolved and
 * the manifest is right there; comparing the two costs nothing and is the check whose absence was
 * the actual bug.
 *
 * Pure and dependency-free so it unit-tests without an `npm ci`, like its sibling axis modules.
 *
 * @param {{components?: Array<{componentId: string, motion?: Array<object>}>}} manifest
 *   The parsed `catalog.json`. Never mutated.
 * @param {Map<string, Array<object>>} motionByComponentId
 *   Component id → the captures `foldMotion` resolved for it, as handed to `buildCatalog`.
 * @returns {{declared: number, carried: number, captures: number, dropped: string[]}}
 *   How many components the join resolved captures for, how many the manifest carries them for,
 *   the total capture count carried, and the ids that were resolved but did not survive.
 */
export function checkMotionCarried(manifest, motionByComponentId) {
  const declared = new Map(
    [...(motionByComponentId ?? [])].filter(([, motion]) => motion?.length > 0),
  );
  const dropped = [];
  let carried = 0;
  let captures = 0;
  const byId = new Map(
    (manifest?.components ?? []).map((component) => [component.componentId, component]),
  );
  for (const id of declared.keys()) {
    const motion = byId.get(id)?.motion;
    if (motion?.length > 0) {
      carried += 1;
      captures += motion.length;
    } else {
      dropped.push(id);
    }
  }
  return { declared: declared.size, carried, captures, dropped };
}
