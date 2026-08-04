/**
 * How an `--extra-renders` supplement folds into the primary render — specifically, which of its
 * functions the live-preview bridge must refuse to alias.
 *
 * Extracted from the driver so it is unit-testable (the driver runs top-level on import), and
 * because the distinction it draws is easy to get wrong: the supplement carries two kinds of
 * function, and only one of them has to lose its live lane.
 *
 *   OVERRIDE — the function exists in BOTH bundles and the supplement's pixels win. An Android-only
 *              render replacing a CMP one (the material3 inset focus ring CMP can't draw) is the
 *              canonical case. The daemon that serves this catalog runs the PRIMARY bundle, so it
 *              would redraw the ring-less version: aliasing it would make the live lane disagree
 *              with the baked sticker beside it. Stays baked-only, always.
 *
 *   ADDITION — the function exists ONLY in the supplement. Nothing was overridden and there is no
 *              disagreement to protect against; the supplement can render it itself. Historically
 *              these were lumped in with the overrides, which is why meshcore-mobile's
 *              `:meshcore-components` screens (32 of 70 components) browsed as baked PNGs and the
 *              viewer told visitors to "Enable a local preview server" for a catalog that has one.
 *              Bridgeable once the supplement is published with its own per-preview live lane.
 */

/**
 * Functions the live-preview bridge must NOT map to a daemon preview id.
 *
 * @param primaryFunctions function names carried by the `--renders` bundle
 * @param extraFunctions   function names carried by the `--extra-renders` bundle
 * @param extraIsLive      whether the supplement is published with its own live lane
 *                         (`--extra-live-bundle`). When false — every catalog published before that
 *                         flag existed — the whole supplement stays unbridged, exactly as before.
 * @returns a Set of function names to withhold from the bridge
 */
export function unbridgeableFunctions(
  primaryFunctions,
  extraFunctions,
  extraIsLive,
) {
  const extra = new Set(extraFunctions);
  if (!extraIsLive) return extra;
  const primary = new Set(primaryFunctions);
  return new Set([...extra].filter((fn) => primary.has(fn)));
}

/**
 * The supplement's ADDITIONS — functions it carries that the primary bundle does not. These are the
 * ones that gain a live lane under `--extra-live-bundle`; reported so a render's log says how much
 * of the supplement actually became live rather than leaving it to be inferred from the catalog.
 */
export function extraOnlyFunctions(primaryFunctions, extraFunctions) {
  const primary = new Set(primaryFunctions);
  return [...new Set(extraFunctions)].filter((fn) => !primary.has(fn));
}
