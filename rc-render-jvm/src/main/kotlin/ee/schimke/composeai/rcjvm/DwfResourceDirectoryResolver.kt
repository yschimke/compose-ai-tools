package ee.schimke.composeai.rcjvm

import androidx.compose.ui.graphics.ImageBitmap
import ee.schimke.composeai.rccreation.dwf.DwfResourceResolver
import ee.schimke.composeai.rccreation.dwf.DwfVectorDrawable
import ee.schimke.composeai.rccreation.dwf.decodeDwfImageBitmap
import ee.schimke.composeai.rccreation.dwf.normalizeDwfResourceKey
import ee.schimke.composeai.rccreation.dwf.parseDwfColorArgb
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * [DwfResourceResolver] backed by Android or Compose Multiplatform resource directories (`res/`,
 * `composeResources/`).
 *
 * Resolves `@drawable/<name>` / `@mipmap/<name>` / `@raw/<name>` raster bitmaps and `<vector>`
 * XMLs, `@color/<name>` values and selector XMLs, and `@string/<name>` values without requiring an
 * Android runtime.
 */
public class DwfResourceDirectoryResolver(private val resourceDirs: List<File>) :
  DwfResourceResolver {

  private val stringsByKey: Map<String, String> by lazy { loadValueEntries("string") }
  private val rawColorsByKey: Map<String, String> by lazy { loadColorEntries() }
  private val imageCache = HashMap<String, ImageBitmap?>()
  private val vectorCache = HashMap<String, DwfVectorDrawable?>()

  override fun resolveString(name: String): String? {
    val key = normalizeDwfResourceKey(name)
    if (key.isEmpty()) return null
    val raw = stringsByKey[key] ?: return null
    return if (raw.startsWith("@string/")) resolveString(raw) else raw
  }

  override fun resolveColor(name: String): Int? = resolveColorInternal(name, mutableSetOf())

  private fun resolveColorInternal(name: String, visited: MutableSet<String>): Int? {
    val key = normalizeDwfResourceKey(name)
    if (key.isEmpty() || !visited.add(key)) return null
    val raw = rawColorsByKey[key] ?: findColorSelectorValue(key) ?: return null
    val trimmed = raw.trim()
    if (trimmed.startsWith("#")) {
      return parseHexColor(trimmed)
    }
    if (trimmed.startsWith("@color/")) {
      return resolveColorInternal(trimmed, visited)
    }
    return null
  }

  override fun resolveImage(name: String): ImageBitmap? {
    val key = normalizeDwfResourceKey(name)
    if (key.isEmpty()) return null
    if (imageCache.containsKey(key)) return imageCache[key]
    val file = findRasterResourceFile(key)
    val decoded = file?.let { runCatching { decodeDwfImageBitmap(it.readBytes()) }.getOrNull() }
    imageCache[key] = decoded
    return decoded
  }

  override fun resolveVectorDrawable(name: String): DwfVectorDrawable? {
    val key = normalizeDwfResourceKey(name)
    if (key.isEmpty()) return null
    if (vectorCache.containsKey(key)) return vectorCache[key]
    val file = findXmlResourceFile(key)
    val parsed = file?.let { xmlFile ->
      runCatching {
        val xml = xmlFile.readText()
        DwfVectorDrawable.parseOrNull(xml) { colorRef -> parseDwfColorArgb(colorRef, this) }
      }
        .getOrNull()
    }
    vectorCache[key] = parsed
    return parsed
  }

  override fun resolveFontFamily(name: String): String? {
    val trimmed = name.trim()
    when (trimmed.uppercase()) {
      "SYNC_TO_DEVICE",
      "DEFAULT",
      "SANS-SERIF",
      "SANS_SERIF",
      "ROBOTO",
      "ROBOTO_FLEX",
      "ROBOTO FLEX",
      "MONOSPACE",
      "SERIF",
      "" -> return null
    }
    if (trimmed.startsWith("@font/")) {
      val key = normalizeDwfResourceKey(trimmed)
      return key.takeIf { it.isNotEmpty() }
    }
    val key = normalizeDwfResourceKey(trimmed)
    if (key.isEmpty()) return null
    val hasFontFile = resourceDirs.any { resDir ->
      val fontDirs =
        resDir
          .listFiles()
          ?.filter { it.isDirectory && (it.name == "font" || it.name.startsWith("font-")) }
          .orEmpty()
      fontDirs.any { dir ->
        listOf("ttf", "otf", "ttc", "xml").any { ext -> File(dir, "$key.$ext").isFile }
      }
    }
    return key.takeIf { hasFontFile }
  }

  private fun loadValueEntries(tagName: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (resDir in resourceDirs) {
      if (!resDir.isDirectory) continue
      val valueDirs =
        resDir
          .listFiles()
          ?.filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
          ?.sortedBy { if (it.name == "values") 0 else 1 }
          .orEmpty()
      for (valDir in valueDirs) {
        val xmlFiles =
          valDir.listFiles()?.filter { it.isFile && it.extension.equals("xml", true) }?.sorted()
            ?: continue
        for (xmlFile in xmlFiles) {
          val doc = parseXmlFileOrNull(xmlFile) ?: continue
          val children = doc.childNodes
          for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            if (
              el.tagName == tagName || (el.tagName == "item" && el.getAttribute("type") == tagName)
            ) {
              val entryName = el.getAttribute("name").trim()
              if (entryName.isNotEmpty() && (valDir.name == "values" || entryName !in out)) {
                out[entryName] = el.textContent.trim()
              }
            }
          }
        }
      }
    }
    return out
  }

  private fun loadColorEntries(): Map<String, String> = loadValueEntries("color")

  private fun findColorSelectorValue(key: String): String? {
    for (resDir in resourceDirs) {
      if (!resDir.isDirectory) continue
      val colorDirs =
        resDir
          .listFiles()
          ?.filter { it.isDirectory && (it.name == "color" || it.name.startsWith("color-")) }
          ?.sortedBy { if (it.name == "color") 0 else 1 }
          .orEmpty()
      for (dir in colorDirs) {
        val file = File(dir, "$key.xml")
        if (!file.isFile) continue
        val root = parseXmlFileOrNull(file) ?: continue
        val items = root.getElementsByTagName("item")
        for (i in 0 until items.length) {
          val item = items.item(i) as? Element ?: continue
          val colorAttr =
            item.getAttribute("android:color").ifBlank { item.getAttribute("color") }.trim()
          if (colorAttr.isNotEmpty()) return colorAttr
        }
      }
    }
    return null
  }

  private fun findRasterResourceFile(key: String): File? {
    for (resDir in resourceDirs) {
      if (!resDir.isDirectory) continue
      val direct = RASTER_EXTENSIONS.firstNotNullOfOrNull { ext ->
        File(resDir, "$key.$ext").takeIf(File::isFile)
      }
      if (direct != null) return direct
      val subdirs =
        resDir
          .listFiles()
          ?.filter { it.isDirectory && isGraphicSubdir(it.name) }
          ?.sortedBy { densityRank(it.name) }
          .orEmpty()
      for (sub in subdirs) {
        for (ext in RASTERS_IN_ORDER) {
          val candidate = File(sub, "$key.$ext")
          if (candidate.isFile) return candidate
        }
      }
    }
    return null
  }

  private fun findXmlResourceFile(key: String): File? {
    for (resDir in resourceDirs) {
      if (!resDir.isDirectory) continue
      val direct = File(resDir, "$key.xml").takeIf(File::isFile)
      if (direct != null) return direct
      val subdirs =
        resDir
          .listFiles()
          ?.filter { it.isDirectory && isXmlSubdir(it.name) }
          ?.sortedBy { densityRank(it.name) }
          .orEmpty()
      for (sub in subdirs) {
        val candidate = File(sub, "$key.xml")
        if (candidate.isFile) return candidate
      }
    }
    return null
  }

  private fun isGraphicSubdir(name: String): Boolean =
    name == "drawable" ||
      name.startsWith("drawable-") ||
      name == "mipmap" ||
      name.startsWith("mipmap-") ||
      name == "raw" ||
      name.startsWith("raw-") ||
      name == "files"

  private fun isXmlSubdir(name: String): Boolean =
    isGraphicSubdir(name) || name == "xml" || name.startsWith("xml-")

  private fun densityRank(dirName: String): Int =
    when {
      "xxxhdpi" in dirName -> 0
      "xxhdpi" in dirName -> 1
      "xhdpi" in dirName -> 2
      "hdpi" in dirName -> 3
      "mdpi" in dirName -> 4
      "anydpi" in dirName -> 5
      '-' !in dirName -> 6
      else -> 7
    }

  private fun parseXmlFileOrNull(file: File): Element? = runCatching {
    val factory =
      DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      }
    val doc = factory.newDocumentBuilder().parse(file)
    doc.documentElement?.also { it.normalize() }
  }
    .getOrNull()

  private fun parseHexColor(raw: String): Int? {
    val hex = raw.removePrefix("#")
    return when (hex.length) {
      6 -> (0xFF000000L or hex.toLong(16)).toInt()
      8 -> hex.toLong(16).toInt()
      3 -> {
        val r = hex[0].digitToIntOrNull(16) ?: return null
        val g = hex[1].digitToIntOrNull(16) ?: return null
        val b = hex[2].digitToIntOrNull(16) ?: return null
        (0xFF shl 24) or ((r * 17) shl 16) or ((g * 17) shl 8) or (b * 17)
      }
      4 -> {
        val a = hex[0].digitToIntOrNull(16) ?: return null
        val r = hex[1].digitToIntOrNull(16) ?: return null
        val g = hex[2].digitToIntOrNull(16) ?: return null
        val b = hex[3].digitToIntOrNull(16) ?: return null
        ((a * 17) shl 24) or ((r * 17) shl 16) or ((g * 17) shl 8) or (b * 17)
      }
      else -> null
    }
  }

  private companion object {
    val RASTERS_IN_ORDER: List<String> = listOf("png", "webp", "jpg", "jpeg", "gif")
    val RASTER_EXTENSIONS: List<String> = RASTERS_IN_ORDER
  }
}
