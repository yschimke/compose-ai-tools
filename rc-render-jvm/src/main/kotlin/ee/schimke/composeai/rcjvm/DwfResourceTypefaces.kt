package ee.schimke.composeai.rcjvm

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import ee.schimke.composeai.rcplayer.compose.RcFontVariations
import ee.schimke.composeai.rcplayer.compose.RcTypefaceLoader
import java.io.File

/**
 * A watch face's own fonts (`res/font/<name>.ttf|otf|ttc`), served to the player ahead of [base].
 *
 * A `<Font family="digital7">` compiles to a typeface reference by name
 * (`RemoteTypeface.Named("digital7")`, the resource key [DwfResourceDirectoryResolver] resolves).
 * The player resolves names through its [RcTypefaceLoader], and the DWF renderers only gave it the
 * bundled system family (Roboto Flex), so every custom font was drawn in the system face: a
 * seven-segment LCD face showed proportional sans-serif digits, laid out for the narrow glyphs the
 * compiler measured from the real font.
 */
internal class DwfResourceTypefaces(
  resourceDirs: List<File>,
  private val base: RcTypefaceLoader?,
) : RcTypefaceLoader {

  /** Resource key → font file, first resource directory wins (as the resolver's lookup). */
  private val files: Map<String, File> = buildMap {
    for (resDir in resourceDirs) {
      val fontDirs =
        resDir
          .listFiles()
          ?.filter { it.isDirectory && (it.name == "font" || it.name.startsWith("font-")) }
          .orEmpty()
      for (dir in fontDirs) {
        for (file in dir.listFiles().orEmpty().sortedBy { it.name }) {
          if (file.isFile && file.extension.lowercase() in FONT_EXTENSIONS) {
            putIfAbsent(file.nameWithoutExtension, file)
          }
        }
      }
    }
  }

  private val loaded = HashMap<String, FontFamily?>()

  override val families: Set<String>
    get() = base?.families.orEmpty() + files.keys

  override fun typeface(family: String, variations: RcFontVariations?): FontFamily? =
    resourceFamily(family) ?: base?.typeface(family, variations)

  @Synchronized
  private fun resourceFamily(name: String): FontFamily? {
    val key = name.trim().removePrefix("@font/")
    val file = files[key] ?: return null
    return loaded.getOrPut(key) {
      runCatching { FontFamily(Font(identity = "dwf-res:$key", data = file.readBytes())) }
        .getOrNull()
    }
  }

  private companion object {
    val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")
  }
}

/** [dwfTypefaces] with the watch face's own fonts in [resourceDirs] ahead of them. */
internal fun dwfTypefaces(resourceDirs: List<File>): RcTypefaceLoader =
  DwfResourceTypefaces(resourceDirs, dwfTypefaces())
