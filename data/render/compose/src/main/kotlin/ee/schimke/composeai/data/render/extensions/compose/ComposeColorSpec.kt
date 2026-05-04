package ee.schimke.composeai.data.render.extensions.compose

import androidx.compose.ui.graphics.Color

/**
 * Shared parser for user/config supplied Compose colors.
 *
 * Keep color strings consistent across data extensions. This intentionally supports only stable,
 * host-independent values: ARGB/RGB hex and common Compose color constants. Theme-relative colors
 * require composition context and should be resolved by a product-specific extension before calling
 * this utility.
 */
object ComposeColorSpec {
  fun resolve(value: String): Color {
    val spec = value.trim()
    namedColor(spec)?.let {
      return it
    }
    return parseHex(spec)
  }

  private fun namedColor(spec: String): Color? =
    when (spec.removePrefix("Color.").lowercase()) {
      "black" -> Color.Black
      "white" -> Color.White
      "transparent" -> Color.Transparent
      "red" -> Color.Red
      "green" -> Color.Green
      "blue" -> Color.Blue
      "yellow" -> Color.Yellow
      "cyan" -> Color.Cyan
      "magenta" -> Color.Magenta
      "gray",
      "grey" -> Color.Gray
      else -> null
    }

  private fun parseHex(spec: String): Color {
    val raw = spec.removePrefix("#")
    val argb =
      when (raw.length) {
        6 -> "FF$raw"
        8 -> raw
        else ->
          error("Expected color as #RRGGBB, #AARRGGBB, or a known color constant; got '$spec'.")
      }
    val value = argb.toLong(16)
    return Color(
      red = ((value shr 16) and 0xFF).toInt(),
      green = ((value shr 8) and 0xFF).toInt(),
      blue = (value and 0xFF).toInt(),
      alpha = ((value shr 24) and 0xFF).toInt(),
    )
  }
}
