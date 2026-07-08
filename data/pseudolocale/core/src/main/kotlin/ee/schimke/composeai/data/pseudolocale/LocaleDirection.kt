package ee.schimke.composeai.data.pseudolocale

/**
 * Whether a BCP-47 language tag denotes a right-to-left script. The renderer uses this to flip
 * `LayoutDirection` for a **real** RTL locale (`ar`, `he`, `fa`, `ur`, …) the same way it already
 * flips it for the `ar-XB` pseudolocale — otherwise a `locale = "ar"` render shapes Arabic glyphs
 * but leaves the container LTR (start/end padding, row order and chevrons unmirrored), which is not
 * what a real Arabic device shows.
 *
 * Kept as a plain language-code lookup (no `java.util.Locale` / `android.text.TextUtils`) so the
 * exact same decision holds on the Android/Robolectric path and the desktop `ImageComposeScene`
 * path. The set mirrors the RTL languages ICU/`TextUtils.getLayoutDirectionFromLocale` recognise,
 * including the legacy ISO-639 codes (`iw` Hebrew, `ji` Yiddish) alongside their modern spellings.
 */
object LocaleDirection {
  private val RTL_LANGUAGES =
    setOf(
      "ar", // Arabic
      "arc", // Aramaic
      "ckb", // Central Kurdish (Sorani)
      "dv", // Divehi
      "fa", // Persian
      "he", // Hebrew
      "iw", // Hebrew (legacy)
      "ps", // Pashto
      "sd", // Sindhi
      "ug", // Uyghur
      "ur", // Urdu
      "yi", // Yiddish
      "ji", // Yiddish (legacy)
    )

  /** True when [tag]'s primary language subtag is written right-to-left. */
  fun isRtl(tag: String?): Boolean {
    if (tag.isNullOrBlank()) return false
    val language = tag.replace('_', '-').substringBefore('-').lowercase()
    return language in RTL_LANGUAGES
  }
}
