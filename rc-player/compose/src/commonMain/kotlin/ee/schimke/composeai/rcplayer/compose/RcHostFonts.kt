package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/**
 * One host-supplied face: the font file's bytes plus the `(weight, italic)` it was registered for.
 *
 * The **bytes** are the point. A host could hand the player a ready-made [FontFamily], and that is
 * all a document naming a family needs — but a document may also name font-variation *axes*
 * (`wght`, `wdth`, `opsz`, …), and Compose carries those on a `Font`, not on a `TextStyle`. Once a
 * `FontFamily` exists its faces can no longer be re-instanced, so the axes could only be dropped.
 * Keeping the bytes is what lets [RcFontFaces] build the instance the document actually asked for.
 */
public class RcFontFace(
  public val identity: String,
  public val data: ByteArray,
  public val weight: Int = 400,
  public val italic: Boolean = false,
)

/**
 * The faces a host offers for one family, instanceable at a set of variation axes.
 *
 * [family] with no settings is the plain family — the faces as registered, Compose selecting
 * between them by weight/slant. With settings it is the same file(s) re-instanced at those axis
 * values, which for a variable font is a genuinely different shape (`wdth 25` is a narrower face,
 * not a scaled one) and for a static font is a no-op the font engine ignores.
 *
 * Instances are cached per axis set: a document draws the same family at the same axes on every
 * frame, and re-parsing the file per frame would be visible on a text-heavy watch face.
 */
public class RcFontFaces(private val faces: List<RcFontFace>) {

  public constructor(face: RcFontFace) : this(listOf(face))

  private fun instanceSuffix(axes: List<Pair<String, Float>>): String =
    axes.joinToString(separator = ",", prefix = "#") { (tag, value) -> "$tag=$value" }

  private val instances = mutableMapOf<List<Pair<String, Float>>, FontFamily>()

  /** The [FontFamily] for these faces at [settings], or null when there are no faces at all. */
  public fun family(settings: FontVariation.Settings? = null): FontFamily? {
    if (faces.isEmpty()) return null
    val key = settings?.settings.orEmpty().map { it.axisName to it.toVariationValue(null) }
    instances[key]?.let {
      return it
    }
    val built =
      runCatching {
        FontFamily(
          faces.map { face ->
            val weight = FontWeight(face.weight)
            val style = if (face.italic) FontStyle.Italic else FontStyle.Normal
            if (settings == null || settings.settings.isEmpty()) {
              Font(identity = face.identity, data = face.data, weight = weight, style = style)
            } else {
              Font(
                // The identity carries the axes, because Compose's font cache keys on it: two
                // instances of one file that share an identity are the *same* cached typeface, so
                // the first axis set drawn would silently be used for every later one (every line
                // of a `wght` ramp rendering at the first line's weight).
                identity = face.identity + instanceSuffix(key),
                data = face.data,
                weight = weight,
                style = style,
                variationSettings = settings,
              )
            }
          }
        )
      }
        .getOrNull() ?: return null
    instances[key] = built
    return built
  }
}
