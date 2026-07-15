package ee.schimke.composeai.overrides

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.overrides.PreviewOverrideType
import ee.schimke.composeai.data.overrides.PreviewOverrideValue

/**
 * Composition local exposing the live named-override surface to a preview. Wired to
 * [ControllerPreviewOverrideHost] by default — the process-static [PreviewOverrideController] — so
 * the `previewOverride*` lookups work in a plain Gradle render with no daemon (every lookup returns
 * its author default and records its declaration). The connector's around-composable
 * (`:data-preview-overrides-connector`) seeds the same controller before the preview composes, so
 * the lookups then return the daemon-supplied values. Tests can provide a fake host.
 */
val LocalPreviewOverrideHost:
  androidx.compose.runtime.ProvidableCompositionLocal<PreviewOverrideHost> =
  compositionLocalOf {
    ControllerPreviewOverrideHost
  }

/**
 * Resolves an author-declared, keyed editable knob to its effective value, recording the
 * declaration so a producer can enumerate "what is editable" on this preview. The
 * `previewOverride*` top-level helpers delegate here.
 */
interface PreviewOverrideHost {
  @Composable fun string(key: String, default: String, index: Int?): String

  @Composable fun int(key: String, default: Int, index: Int?): Int

  @Composable fun float(key: String, default: Float, index: Int?): Float

  @Composable fun boolean(key: String, default: Boolean, index: Int?): Boolean

  @Composable fun color(key: String, default: Color, index: Int?): Color

  @Composable fun dp(key: String, default: Dp, index: Int?): Dp

  /**
   * Declare and resolve a **font-family** string knob — a [string] knob a viewer renders as an
   * autocomplete over [suggestions] (typically the declared `@TypographyCatalog` names), optionally
   * splicing the full Google Fonts family list when [googleFonts]. The default implementation
   * ignores the extra metadata and behaves exactly like [string]; the controller-backed host
   * overrides it to record the richer declaration. Having a default body keeps existing
   * [PreviewOverrideHost] implementations (test fakes) source-compatible.
   */
  @Composable
  fun font(
    key: String,
    default: String,
    index: Int?,
    suggestions: List<String>,
    googleFonts: Boolean,
  ): String = string(key, default, index)
}

/**
 * Default [PreviewOverrideHost] backed by the process-static [PreviewOverrideController]. Each read
 * (a) resolves the controller's seeded value for the (key, index) — falling back to [default] when
 * none is bound or the bound value's type doesn't match — and (b) records the declaration (with its
 * resolved `current`) into the controller so the `compose/overrides` producer can surface it.
 */
object ControllerPreviewOverrideHost : PreviewOverrideHost {

  @Composable
  override fun string(key: String, default: String, index: Int?): String {
    val seeded by PreviewOverrideController.seededValues
    val effective =
      (seeded[seedKey(key, index)] as? PreviewOverrideValue.StringValue)?.value ?: default
    declare(
      key,
      index,
      PreviewOverrideType.STRING,
      PreviewOverrideValue.StringValue(default),
      PreviewOverrideValue.StringValue(effective),
    )
    return effective
  }

  @Composable
  override fun int(key: String, default: Int, index: Int?): Int {
    val seeded by PreviewOverrideController.seededValues
    val effective =
      (seeded[seedKey(key, index)] as? PreviewOverrideValue.IntValue)?.value ?: default
    declare(
      key,
      index,
      PreviewOverrideType.INT,
      PreviewOverrideValue.IntValue(default),
      PreviewOverrideValue.IntValue(effective),
    )
    return effective
  }

  @Composable
  override fun float(key: String, default: Float, index: Int?): Float {
    val seeded by PreviewOverrideController.seededValues
    val effective =
      (seeded[seedKey(key, index)] as? PreviewOverrideValue.FloatValue)?.value ?: default
    declare(
      key,
      index,
      PreviewOverrideType.FLOAT,
      PreviewOverrideValue.FloatValue(default),
      PreviewOverrideValue.FloatValue(effective),
    )
    return effective
  }

  @Composable
  override fun boolean(key: String, default: Boolean, index: Int?): Boolean {
    val seeded by PreviewOverrideController.seededValues
    val effective =
      (seeded[seedKey(key, index)] as? PreviewOverrideValue.BooleanValue)?.value ?: default
    declare(
      key,
      index,
      PreviewOverrideType.BOOL,
      PreviewOverrideValue.BooleanValue(default),
      PreviewOverrideValue.BooleanValue(effective),
    )
    return effective
  }

  @Composable
  override fun color(key: String, default: Color, index: Int?): Color {
    val seeded by PreviewOverrideController.seededValues
    val seededArgb = (seeded[seedKey(key, index)] as? PreviewOverrideValue.ColorValue)?.argb
    val effective = seededArgb?.let(::parseColorOrNull) ?: default
    declare(
      key,
      index,
      PreviewOverrideType.COLOR,
      PreviewOverrideValue.ColorValue(default.toArgbHex()),
      PreviewOverrideValue.ColorValue(effective.toArgbHex()),
    )
    return effective
  }

  @Composable
  override fun dp(key: String, default: Dp, index: Int?): Dp {
    // Dp carried as a plain float (its `.value`); no Remote-style dp wrapper.
    val seeded by PreviewOverrideController.seededValues
    val effective =
      (seeded[seedKey(key, index)] as? PreviewOverrideValue.FloatValue)?.value ?: default.value
    declare(
      key,
      index,
      PreviewOverrideType.FLOAT,
      PreviewOverrideValue.FloatValue(default.value),
      PreviewOverrideValue.FloatValue(effective),
    )
    return effective.dp
  }

  @Composable
  override fun font(
    key: String,
    default: String,
    index: Int?,
    suggestions: List<String>,
    googleFonts: Boolean,
  ): String {
    val seeded by PreviewOverrideController.seededValues
    val effective =
      (seeded[seedKey(key, index)] as? PreviewOverrideValue.StringValue)?.value ?: default
    declare(
      key,
      index,
      PreviewOverrideType.STRING,
      PreviewOverrideValue.StringValue(default),
      PreviewOverrideValue.StringValue(effective),
      suggestions = suggestions,
      googleFonts = googleFonts,
    )
    return effective
  }

  @Composable
  private fun declare(
    key: String,
    index: Int?,
    type: String,
    default: PreviewOverrideValue,
    current: PreviewOverrideValue,
    suggestions: List<String> = emptyList(),
    googleFonts: Boolean = false,
  ) {
    val declaration =
      PreviewOverrideDeclaration(
        key = key,
        type = type,
        label = key,
        default = default,
        current = current,
        index = index,
        suggestions = suggestions,
        googleFonts = googleFonts,
      )
    // Record on commit, not mid-composition: keeps the controller mutation a side effect of a
    // successful composition (idempotent — the controller de-dupes by seedKey).
    SideEffect { PreviewOverrideController.record(declaration) }
  }
}

private fun seedKey(key: String, index: Int?): String = if (index == null) key else "$key[$index]"

/** `#AARRGGBB` for a Compose [Color] (converted to sRGB by [toArgb]). */
internal fun Color.toArgbHex(): String = "#%08X".format(toArgb())

/** Parse `#AARRGGBB` / `#RRGGBB` (or without `#`) to a [Color], or null when malformed. */
internal fun parseColorOrNull(hex: String): Color? {
  val raw = hex.removePrefix("#")
  val v = raw.toLongOrNull(16) ?: return null
  return when (raw.length) {
    8 -> Color((v and 0xFFFFFFFF).toInt())
    6 -> Color((0xFF000000 or v).toInt())
    else -> null
  }
}

// --- Public opt-in lookups
// -------------------------------------------------------------------------

/**
 * Declare and resolve an editable **string** knob keyed by [key]. In a daemon-backed render the
 * daemon's `renderNow.overrides.namedOverrides` entry for [key] (or the bracket-indexed key when
 * [index] is set) replaces [default]; otherwise [default] is returned. Either way the knob is
 * recorded so a viewer can present an editable control. Opt-in: only previews that call this expose
 * the knob.
 *
 * Example (a list whose length and per-row label are both editable):
 * ```
 * val rows = previewOverrideInt("rowCount", 3)
 * Column { repeat(rows) { i -> Text(previewOverrideString("rowLabel", "Item ${i + 1}", index = i)) } }
 * ```
 */
@Composable
fun previewOverrideString(key: String, default: String, index: Int? = null): String =
  LocalPreviewOverrideHost.current.string(key, default, index)

/**
 * Editable **int** knob. The natural type for a list length / item count. See
 * [previewOverrideString].
 */
@Composable
fun previewOverrideInt(key: String, default: Int, index: Int? = null): Int =
  LocalPreviewOverrideHost.current.int(key, default, index)

/** Editable **float** knob. See [previewOverrideString]. */
@Composable
fun previewOverrideFloat(key: String, default: Float, index: Int? = null): Float =
  LocalPreviewOverrideHost.current.float(key, default, index)

/** Editable **boolean** knob. See [previewOverrideString]. */
@Composable
fun previewOverrideBoolean(key: String, default: Boolean, index: Int? = null): Boolean =
  LocalPreviewOverrideHost.current.boolean(key, default, index)

/** Editable **color** knob, carried on the wire as `#AARRGGBB`. See [previewOverrideString]. */
@Composable
fun previewOverrideColor(key: String, default: Color, index: Int? = null): Color =
  LocalPreviewOverrideHost.current.color(key, default, index)

/**
 * Editable **Dp** knob (e.g. a component size), carried on the wire as a float. See
 * [previewOverrideString].
 */
@Composable
fun previewOverrideDp(key: String, default: Dp, index: Int? = null): Dp =
  LocalPreviewOverrideHost.current.dp(key, default, index)

/**
 * Editable **font-family** knob: a string knob a viewer renders as an autocompleting text field
 * seeded with [suggestions] (typically the declared `@TypographyCatalog` names, shown first) and —
 * when [googleFonts] (the default) — the full fonts.google.com family list, while staying free-text
 * so any typed family resolves. Resolves like [previewOverrideString] (the daemon-seeded value, or
 * [default]); the extra metadata only shapes the control a viewer offers. See
 * [previewOverrideString].
 */
@Composable
fun previewOverrideFont(
  key: String,
  default: String,
  suggestions: List<String> = emptyList(),
  googleFonts: Boolean = true,
  index: Int? = null,
): String = LocalPreviewOverrideHost.current.font(key, default, index, suggestions, googleFonts)
