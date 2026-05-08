package ee.schimke.composeai.data.pseudolocale

/**
 * Pseudolocales recognised at runtime by the locale-override path.
 *
 * Both tags match Android Studio's locale dropdown / AAPT2's `--pseudo-localize` shapes:
 * - `en-XA` — accent / expansion: ASCII letters mapped to lookalike Unicode, expanded ~30% with `[
 *   ]` padding so layouts that don't budget for translation expansion become visible immediately.
 * - `ar-XB` — bidi: each word wrapped in `‮…‬` so RTL bugs surface without needing real RTL
 *   strings, and the layout flips via `LayoutDirection.Rtl`.
 *
 * The runtime path keeps `localeTag` = `en-XA` / `ar-XB` end-to-end as the protocol surface, but
 * the Robolectric resource qualifier resolves to the *base* locale (`en`) so the framework still
 * finds `values/` strings; the pseudolocalisation happens in [Pseudolocalizer] applied to whatever
 * the default locale resolved to.
 */
enum class Pseudolocale(val tag: String, val baseTag: String, val isRtl: Boolean) {
  ACCENT(tag = "en-XA", baseTag = "en", isRtl = false),
  BIDI(tag = "ar-XB", baseTag = "en", isRtl = true);

  companion object {
    /**
     * Recognise pseudolocale BCP-47 tags. Case-insensitive match, accepts `_` separators alongside
     * `-`. Returns `null` for any other tag — the renderer falls through to its standard locale
     * qualifier path.
     */
    fun fromTag(tag: String?): Pseudolocale? {
      if (tag.isNullOrBlank()) return null
      val normalized = tag.replace('_', '-').lowercase()
      return entries.firstOrNull { it.tag.lowercase() == normalized }
    }
  }
}
