package ee.schimke.composeai.data.pseudolocale

/**
 * Whether a BCP-47 language tag denotes a right-to-left script. The renderer uses this to flip
 * `LayoutDirection` for a **real** RTL locale (`ar`, `he`, `fa`, `ur`, …) the same way it already
 * flips it for the `ar-XB` pseudolocale — otherwise a `locale = "ar"` render shapes Arabic glyphs
 * but leaves the container LTR (start/end padding, row order and chevrons unmirrored), which is not
 * what a real Arabic device shows.
 *
 * Kept as a plain subtag lookup (no `java.util.Locale` / `android.text.TextUtils`) so the exact
 * same decision holds on the Android/Robolectric path and the desktop `ImageComposeScene` path. The
 * sets mirror what ICU/`TextUtils.getLayoutDirectionFromLocale` recognise, including the legacy
 * ISO-639 codes (`iw` Hebrew, `ji` Yiddish) alongside their modern spellings.
 *
 * **An explicit script subtag wins over the language default**, the way ICU decides it. A language
 * code only implies a direction because it implies a *script*, so a tag that names the script
 * outright has already answered the question: `ar-Latn` (Arabic romanised) is LTR despite `ar`, and
 * `az-Arab` (Azerbaijani in Arabic script) is RTL despite `az`. Language is the fallback for the
 * common `ar` / `he-IL` shape where no script is written down.
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

  /** ISO 15924 codes for the right-to-left scripts, lowercased for comparison. */
  private val RTL_SCRIPTS =
    setOf(
      "adlm", // Adlam
      "arab", // Arabic
      "aran", // Nastaliq (Arabic variant)
      "hebr", // Hebrew
      "mand", // Mandaic
      "nkoo", // N'Ko
      "rohg", // Hanifi Rohingya
      "samr", // Samaritan
      "syrc", // Syriac
      "thaa", // Thaana
      "yezi", // Yezidi
    )

  /**
   * True when [tag] is written right-to-left — by its explicit script subtag when it carries one,
   * otherwise by its primary language subtag.
   */
  fun isRtl(tag: String?): Boolean {
    if (tag.isNullOrBlank()) return false
    val subtags = tag.replace('_', '-').split('-').filter { it.isNotEmpty() }
    val language = subtags.firstOrNull()?.lowercase() ?: return false
    // In BCP-47 the script is the subtag right after the language and is the only 4-alpha one
    // there: a region is 2 alpha or 3 digits, a variant is 5-8 alphanumeric. Reading strictly by
    // position keeps a 4-character variant further along the tag from being mistaken for a script.
    val script = subtags.getOrNull(1)?.takeIf { it.length == 4 && it.all(Char::isLetter) }
    if (script != null) return script.lowercase() in RTL_SCRIPTS
    return language in RTL_LANGUAGES
  }
}
