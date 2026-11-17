package ee.schimke.composeai.rcjvm

import androidx.compose.ui.graphics.ImageBitmap
import ee.schimke.composeai.rccreation.dwf.DwfResourceResolver
import ee.schimke.composeai.rccreation.dwf.DwfVectorDrawable
import ee.schimke.composeai.rccreation.dwf.decodeDwfImageBitmap
import ee.schimke.composeai.rccreation.dwf.normalizeDwfResourceKey
import ee.schimke.composeai.rccreation.dwf.parseDwfColorArgb
import java.io.ByteArrayInputStream
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
public class DwfResourceDirectoryResolver
private constructor(
  private val resourceDirs: List<DwfResTree>,
  @Suppress("UNUSED_PARAMETER") tag: Unit,
) : DwfResourceResolver {

  public constructor(resourceDirs: List<File>) : this(resourceDirs.map(::DwfFileResTree), Unit)

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

  /**
   * Told about each `@color/…` reference that does not resolve, including the ones inside vector
   * drawables, which resolve against this resolver rather than whatever wraps it.
   */
  internal var onMissingColor: ((String) -> Unit)? = null

  override fun resolveColor(name: String): Int? =
    resolveColorInternal(name, mutableSetOf()).also {
      if (it == null && name.trim().startsWith("@color/")) onMissingColor?.invoke(name.trim())
    }

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
    val decoded = file?.let { runCatching { decodeDwfImageBitmap(it.read()) }.getOrNull() }
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
        val xml = xmlFile.read().toString(Charsets.UTF_8)
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
    return key.takeIf { findFontFile(key, listOf("ttf", "otf", "ttc", "xml")) != null }
  }

  override fun resolveFontBytes(name: String): ByteArray? {
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
    val key = normalizeDwfResourceKey(trimmed)
    if (key.isEmpty()) return null
    val file = findFontFile(key, listOf("ttf", "otf", "ttc")) ?: return null
    return runCatching { file.read() }.getOrNull()
  }

  /** Whether a font file (`ttf`/`otf`/`ttc`, or a font-family `xml`) exists for [name]. */
  internal fun hasFont(name: String): Boolean {
    val key = normalizeDwfResourceKey(name.trim())
    return key.isNotEmpty() && findFontFile(key, listOf("ttf", "otf", "ttc", "xml")) != null
  }

  private fun findFontFile(key: String, extensions: List<String>): DwfResFile? {
    for (resDir in resourceDirs) {
      val fontDirs = resDir.subdirs().filter { it == "font" || it.startsWith("font-") }
      for (dir in fontDirs) {
        for (ext in extensions) {
          resDir.file(dir, "$key.$ext")?.let {
            return it
          }
        }
      }
    }
    return null
  }

  private fun loadValueEntries(tagName: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (resDir in resourceDirs) {
      if (!resDir.exists) continue
      val valueDirs =
        resDir
          .subdirs()
          .filter { it == "values" || it.startsWith("values-") }
          .sortedBy { if (it == "values") 0 else 1 }
      for (valDir in valueDirs) {
        val xmlFiles = resDir.files(valDir).filter { it.endsWith(".xml", ignoreCase = true) }
        for (xmlName in xmlFiles) {
          val doc = parseXmlOrNull(resDir.file(valDir, xmlName) ?: continue) ?: continue
          val children = doc.childNodes
          for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            if (
              el.tagName == tagName || (el.tagName == "item" && el.getAttribute("type") == tagName)
            ) {
              val entryName = el.getAttribute("name").trim()
              if (entryName.isNotEmpty() && (valDir == "values" || entryName !in out)) {
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
      if (!resDir.exists) continue
      val colorDirs =
        resDir
          .subdirs()
          .filter { it == "color" || it.startsWith("color-") }
          .sortedBy { if (it == "color") 0 else 1 }
      for (dir in colorDirs) {
        val file = resDir.file(dir, "$key.xml") ?: continue
        val root = parseXmlOrNull(file) ?: continue
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

  private fun findRasterResourceFile(key: String): DwfResFile? {
    for (resDir in resourceDirs) {
      if (!resDir.exists) continue
      val direct = RASTER_EXTENSIONS.firstNotNullOfOrNull { ext -> resDir.file("", "$key.$ext") }
      if (direct != null) return direct
      val subdirs = resDir.subdirs().filter { isGraphicSubdir(it) }.sortedBy { densityRank(it) }
      for (sub in subdirs) {
        for (ext in RASTERS_IN_ORDER) {
          resDir.file(sub, "$key.$ext")?.let {
            return it
          }
        }
      }
    }
    return null
  }

  private fun findXmlResourceFile(key: String): DwfResFile? {
    for (resDir in resourceDirs) {
      if (!resDir.exists) continue
      val direct = resDir.file("", "$key.xml")
      if (direct != null) return direct
      val subdirs = resDir.subdirs().filter { isXmlSubdir(it) }.sortedBy { densityRank(it) }
      for (sub in subdirs) {
        resDir.file(sub, "$key.xml")?.let {
          return it
        }
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

  private fun parseXmlOrNull(file: DwfResFile): Element? = runCatching {
    val factory =
      DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      }
    val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(file.read()))
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

  internal companion object {
    /** A resolver over in-memory or on-disk trees (`WffResourceSource`). */
    fun over(trees: List<DwfResTree>): DwfResourceDirectoryResolver =
      DwfResourceDirectoryResolver(trees, Unit)

    private val RASTERS_IN_ORDER: List<String> = listOf("png", "webp", "jpg", "jpeg", "gif")
    private val RASTER_EXTENSIONS: List<String> = RASTERS_IN_ORDER
  }
}

/** A resource root (`res/`, `composeResources/`) the resolver reads, on disk or in memory. */
internal interface DwfResTree {
  /** `false` for a missing directory; the resolver skips the root. */
  val exists: Boolean

  /** Immediate sub-directory names (`drawable`, `values-fr`, ...), sorted. */
  fun subdirs(): List<String>

  /** File names directly in [dir] (`""` is the root itself), sorted. */
  fun files(dir: String): List<String>

  /** The file [name] in [dir], or `null` when absent. */
  fun file(dir: String, name: String): DwfResFile?
}

internal class DwfResFile(private val bytes: () -> ByteArray) {
  fun read(): ByteArray = bytes()
}

internal class DwfFileResTree(private val root: File) : DwfResTree {
  override val exists: Boolean
    get() = root.isDirectory

  override fun subdirs(): List<String> =
    root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty()

  override fun files(dir: String): List<String> =
    dirOf(dir).listFiles()?.filter { it.isFile }?.map { it.name }?.sorted().orEmpty()

  override fun file(dir: String, name: String): DwfResFile? =
    File(dirOf(dir), name).takeIf(File::isFile)?.let { f -> DwfResFile(f::readBytes) }

  private fun dirOf(dir: String): File = if (dir.isEmpty()) root else File(root, dir)
}

/**
 * An in-memory `res/` tree keyed by res-relative path (`drawable/hand.png`, `values/colors.xml`).
 * Anything nested deeper than one directory is ignored, as it is on disk.
 */
internal class DwfMapResTree(entries: Map<String, ByteArray>) : DwfResTree {
  private val byDir: Map<String, Map<String, ByteArray>> =
    entries.entries
      .map { (path, bytes) ->
        val clean = path.replace('\\', '/').trimStart('/').removePrefix("res/")
        clean.substringBeforeLast('/', "") to (clean.substringAfterLast('/') to bytes)
      }
      .groupBy({ it.first }, { it.second })
      .mapValues { (_, files) -> files.toMap() }

  override val exists: Boolean = true

  override fun subdirs(): List<String> =
    byDir.keys.filter { it.isNotEmpty() && '/' !in it }.sorted()

  override fun files(dir: String): List<String> = byDir[dir]?.keys?.sorted().orEmpty()

  override fun file(dir: String, name: String): DwfResFile? =
    byDir[dir]?.get(name)?.let { bytes -> DwfResFile { bytes } }
}
