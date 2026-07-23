/**
 * Parse the `_VARIANT_<name>` suffix a `@OverrideVariant` synthetic preview id carries.
 *
 * Discovery mints a variant preview's id as `<baseId>_VARIANT_<name>` (see `overrideVariantPreview`
 * in the gradle plugin's `PreviewDiscovery.kt`), the same convention the render output uses. This is
 * the one place the suffix is turned back into a `state` for the catalog fold, so the regex stays in
 * lockstep with the Kotlin mint site. Returns the `<name>` (e.g. `"off"`, `"disabled"`) or `null`
 * for an ordinary preview id.
 *
 * @param {unknown} id preview id, possibly `_VARIANT_<name>`-suffixed.
 * @returns {string | null} the variant name, or null when the id carries no variant suffix.
 */
export function variantStateFromId(id) {
  if (typeof id !== "string") return null;
  const match = id.match(/_VARIANT_(.+)$/);
  return match ? match[1] : null;
}
