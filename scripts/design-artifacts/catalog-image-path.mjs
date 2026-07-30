/**
 * The bundle-relative `images/…` path the catalog export names a sticker from (issue #2965).
 *
 * `buildCatalog` (in the pinned `@design-parity/catalog-export`) writes each rendered image to
 * `images/<component-slug>/<variant>__<state>[__theme][__size][__<k>-<v>…].png`, and every consumer
 * of a published catalog — the serve host's route id (`ServeCatalogStore.previewIdFor`), the
 * per-variant figma-svg emit, the `livePreview` deep link — is keyed off that path.
 *
 * A **deferred** (live-only) record has no rasterised PNG, so `buildCatalog` never names one for it;
 * but the serve host still needs a stable id to register the live-only card under, and it must be
 * the id the sticker *would* have had — otherwise flipping an entry from `deferred` back to
 * `required` silently moves its published URL. So the export derives the path here and records it on
 * the record.
 *
 * Deriving means restating a naming scheme that lives in another package, which is exactly the drift
 * risk this file has to answer for. It does so by being **checkable**: [assertDerivationMatches]
 * re-derives the path of every BAKED image on each export run and compares it against what
 * `buildCatalog` actually wrote. A scheme change therefore surfaces as a loud mismatch on the next
 * catalog build (and the export drops the derived paths rather than publishing wrong ones), instead
 * of as a silently-404ing live-only card.
 *
 * Pure and dependency-free (no `@design-parity/*`, no I/O) so it unit-tests without an `npm ci`,
 * like its sibling `catalog-priority.mjs` / `catalog-variants.mjs`.
 */

/**
 * The slug the exporter builds a path segment from: non-`[a-zA-Z0-9._-]` runs collapse to `-`, the
 * result is trimmed of `-` and lowercased. Mirrors `@design-parity`'s `slug()` — and note it keeps
 * `.` and `_`, which is why a `fontScale: "2.0"` variant lands on `…__fontscale-2.0.png` rather than
 * `…__fontscale-2-0.png`. `ServeBundleHost.heroSlug` is the Kotlin twin of the same function.
 */
export function catalogSlug(value) {
  return String(value ?? "")
    .replace(/[^a-zA-Z0-9._-]+/g, "-")
    .replace(/(^-+|-+$)/g, "")
    .toLowerCase();
}

/**
 * The `images/…` path [buildCatalog] names for one folded image of [componentId].
 *
 * Segment order is `variant`, `state`, then the optional `theme`, `size`, and finally one segment
 * per `props` entry (`<key>-<value>`, sorted by key so the name doesn't depend on object insertion
 * order). `variant` defaults to `ideal` and `state` to `default`, matching the export.
 */
export function catalogImagePath(componentId, image) {
  const segments = [
    catalogSlug(image?.variant ?? "ideal"),
    catalogSlug(image?.state ?? "default"),
  ];
  if (image?.theme) segments.push(catalogSlug(image.theme));
  if (image?.size) segments.push(catalogSlug(image.size));
  for (const [key, value] of Object.entries(image?.props ?? {}).sort(([a], [b]) =>
    a.localeCompare(b),
  )) {
    segments.push(catalogSlug(`${key}-${value}`));
  }
  return `images/${catalogSlug(componentId)}/${segments.join("__")}.png`;
}

/**
 * Check [catalogImagePath] against the paths `buildCatalog` actually wrote, over every baked image
 * of a built manifest. Returns the mismatches as `{ expected, actual }` pairs — empty when the
 * derivation still agrees with the exporter, which is the only state in which a derived deferred
 * path may be published.
 *
 * Images with no `path` (there shouldn't be any) are skipped rather than reported: they say nothing
 * about the naming scheme.
 */
export function derivationMismatches(manifest) {
  const mismatches = [];
  for (const component of manifest?.components ?? []) {
    for (const image of component?.images ?? []) {
      if (typeof image?.path !== "string" || image.path.length === 0) continue;
      const expected = catalogImagePath(component.componentId, image);
      if (expected !== image.path) {
        mismatches.push({ expected, actual: image.path });
      }
    }
  }
  return mismatches;
}
