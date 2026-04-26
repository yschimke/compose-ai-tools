package ee.schimke.composeai.plugin

/**
 * Parses Android resource directory names like `drawable-night-xhdpi-v26` into the resource base
 * (`drawable`) and qualifier suffix (`night-xhdpi-v26`), and classifies individual qualifier tokens
 * into the dimensions the renderer cares about.
 *
 * Intentionally narrow surface — discovery uses these helpers to decide which capture-fan-out
 * dimensions to expand for a given resource (see `docs/ANDROID_RESOURCE_PREVIEWS.md`); the renderer
 * feeds [ParsedResourceDirectory.qualifierSuffix] back into Robolectric verbatim via
 * `RuntimeEnvironment.setQualifiers(...)`. We don't try to canonicalise qualifier ordering — AAPT's
 * resolver is forgiving enough that preserving the consumer's original order keeps filenames stable
 * when consumers add a qualifier mid-stream.
 */
data class ParsedResourceDirectory(
  /** `drawable`, `mipmap`, `values`, etc. — the part before the first dash. */
  val base: String,
  /**
   * The qualifier suffix as written in the directory name, sans the leading dash. `null` for
   * un-qualified directories (`drawable/`, `mipmap/`).
   */
  val qualifierSuffix: String?,
  /** [qualifierSuffix] split on `-`, in source order. Empty for the default qualifier. */
  val qualifierTokens: List<String>,
)

object ResourceQualifierParser {

  private val DENSITY_TOKENS =
    setOf("ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi", "tvdpi", "anydpi", "nodpi")

  private val NIGHT_TOKENS = setOf("night", "notnight")

  private val LAYOUT_DIRECTION_TOKENS = setOf("ldrtl", "ldltr")

  private val ORIENTATION_TOKENS = setOf("port", "land")

  private val ROUND_TOKENS = setOf("round", "notround")

  private val UI_MODE_TOKENS = setOf("car", "desk", "television", "appliance", "watch", "vrheadset")

  /** Parses a resource directory name (just the leaf — no path separators). */
  fun parse(directoryName: String): ParsedResourceDirectory {
    val firstDash = directoryName.indexOf('-')
    if (firstDash == -1) {
      return ParsedResourceDirectory(
        base = directoryName,
        qualifierSuffix = null,
        qualifierTokens = emptyList(),
      )
    }
    val base = directoryName.substring(0, firstDash)
    val suffix = directoryName.substring(firstDash + 1)
    return ParsedResourceDirectory(
      base = base,
      qualifierSuffix = suffix,
      qualifierTokens = suffix.split('-'),
    )
  }

  fun isDensityQualifier(token: String): Boolean = token in DENSITY_TOKENS

  fun isNightQualifier(token: String): Boolean = token in NIGHT_TOKENS

  fun isLayoutDirectionQualifier(token: String): Boolean = token in LAYOUT_DIRECTION_TOKENS

  fun isOrientationQualifier(token: String): Boolean = token in ORIENTATION_TOKENS

  fun isRoundQualifier(token: String): Boolean = token in ROUND_TOKENS

  fun isUiModeQualifier(token: String): Boolean = token in UI_MODE_TOKENS

  /**
   * `v26`, `v34`, etc. — gates AAPT file resolution but doesn't change how the picked file renders.
   */
  fun isVersionQualifier(token: String): Boolean =
    token.length >= 2 && token[0] == 'v' && token.substring(1).all { it.isDigit() }

  /**
   * Two-letter language codes (`en`, `de`, `ja`) and ISO-639-2 three-letter codes. Consumers also
   * write `b+lang+region+variant` BCP-47 form; we recognise that as a single locale token. We
   * deliberately treat `r<REGION>` as part of a locale rather than its own kind — AAPT pairs them
   * positionally.
   */
  fun isLocaleLanguageQualifier(token: String): Boolean =
    when {
      token.startsWith("b+") -> true
      token.length == 2 && token.all { it.isLowerCase() && it.isLetter() } -> true
      token.length == 3 && token.all { it.isLowerCase() && it.isLetter() } -> true
      else -> false
    }

  /** `rGB`, `rUS`, etc. — paired positionally with the preceding language token. */
  fun isLocaleRegionQualifier(token: String): Boolean =
    token.length == 3 &&
      token[0] == 'r' &&
      token.substring(1).all { it.isUpperCase() && it.isLetter() }

  /** `sw320dp`, `w480dp`, `h720dp`. */
  fun isScreenSizeQualifier(token: String): Boolean =
    (token.startsWith("sw") || token.startsWith("w") || token.startsWith("h")) &&
      token.endsWith("dp") &&
      run {
        val numberPart =
          token.removePrefix("sw").removePrefix("w").removePrefix("h").removeSuffix("dp")
        numberPart.isNotEmpty() && numberPart.all { it.isDigit() }
      }
}
