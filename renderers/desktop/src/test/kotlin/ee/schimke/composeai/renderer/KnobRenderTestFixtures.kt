package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.KnobValue

/**
 * Fixtures for [DesktopKnobRendererTest] — previews written in the **secondary override format**,
 * where the editable knobs are the function's own defaulted value parameters rather than
 * `previewOverride*` lookups in the body.
 *
 * The point of the shape is what is *absent*: there is no harness import, no controller, and no
 * knob call. The body is the Compose a developer would write, and everything a renderer needs to
 * offer an editable control is in the signature.
 */

/**
 * Two knobs of different kinds on one sticker. [swatch] paints an area the size test can measure
 * and [dark] flips its colour, so a render can tell "the seed arrived" from "the seed was dropped
 * and the default rendered" by geometry and by colour independently.
 */
@Composable
fun KnobSticker(sizeDp: Int = 40, dark: Boolean = false) {
  Box(
    modifier =
      Modifier.size(sizeDp.dp).background(if (dark) Color(0xFF102027) else Color(0xFFB71C1C))
  )
}

/**
 * The production shape: a parameter the harness cannot build a value for sits *before* the one it
 * can. Renders the badge at [sizeDp], ignoring [tag] entirely — its only job is to occupy parameter
 * index 0 so a correct binding has to place `sizeDp`'s argument at index 1.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun OffsetKnobSticker(tag: List<String> = emptyList(), sizeDp: Int = 40) {
  Box(modifier = Modifier.size(sizeDp.dp).background(Color(0xFFB71C1C)))
}

/**
 * The closed-value-set knob: a parameter whose type is an `enum class`, which is the only shape
 * whose accepted values a viewer can enumerate and therefore the only one it can offer as a
 * **picker** rather than a text box.
 *
 * Each constant paints a different size *and* colour, so a render can tell which one bound without
 * reading anything but the pixels — and can tell a bound constant from a dropped seed falling back
 * to [Size.Small].
 */
enum class Size {
  Small,
  Medium,
  Large,
}

/**
 * The migration shape: constants whose seed text is the vocabulary a catalog already has written
 * down in its `@OverrideVariant` seeds and in the design kit it is compared against.
 *
 * `extra-large` is the case that forces the annotation rather than a rename — it is not a legal
 * Kotlin identifier, so "just name the constant after the value" is unavailable even in principle.
 */
enum class KitSize {
  @KnobValue("default") Default,
  @KnobValue("large") Large,
  @KnobValue("extra-large") ExtraLarge,
}

/** Paints [size] as an area a render test can measure, keyed by the kit's own spelling. */
@Composable
fun AliasedKnobSticker(size: KitSize = KitSize.Default) {
  val px =
    when (size) {
      KitSize.Default -> 40
      KitSize.Large -> 70
      KitSize.ExtraLarge -> 120
    }
  Box(modifier = Modifier.size(px.dp).background(Color(0xFF4A148C)))
}

/** Paints [size] as an area a render test can measure. */
@Composable
fun EnumKnobSticker(size: Size = Size.Small) {
  val px =
    when (size) {
      Size.Small -> 40
      Size.Medium -> 70
      Size.Large -> 120
    }
  Box(modifier = Modifier.size(px.dp).background(Color(0xFF1B5E20)))
}
