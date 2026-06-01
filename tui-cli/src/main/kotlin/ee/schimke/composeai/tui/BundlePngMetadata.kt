package ee.schimke.composeai.tui

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Just enough of a `compose-preview` bundle's `bundle.json` for the image-only TUI to drive a live
 * session: which Gradle module produced it and which preview is the cover. A bundle is a PNG+ZIP
 * polyglot — a valid PNG up front (the cover render) with a standard zip appended.
 *
 * Since bundle schema v2 the zip also carries one baked PNG per selected preview under the
 * well-known `previews/<previewId>.png` directory (see `gradle-plugin/PreviewBundleFormat.kt`), so
 * the TUI can page through every preview **without the originating project on disk** — opening a
 * bundle straight from `~/Downloads` shows all its images, not just the cover. [BundleContents]
 * exposes both the metadata and those baked PNGs from a single zip pass.
 *
 * The polyglot-extraction routine is duplicated here rather than shared with `:cli`'s
 * `BundleReader` or `:bundle-viewer`'s loader — both deliberately keep their own copy so neither
 * module drags the other onto its classpath, and the routine is tiny and stable. See
 * `cli/BundleCommand.kt`.
 */
@Serializable
data class BundlePngMetadata(val modulePath: String = "", val coverPreviewId: String? = null) {
  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    /** Returns the parsed `bundle.json`, or null if [file] isn't a readable bundle polyglot. */
    fun readOrNull(file: File): BundlePngMetadata? =
      try {
        val zipBytes = extractZipBytes(file) ?: return null
        var result: BundlePngMetadata? = null
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
          while (true) {
            val entry = zin.nextEntry ?: break
            if (entry.name == "bundle.json") {
              result = json.decodeFromString(serializer(), zin.readBytes().toString(Charsets.UTF_8))
              break
            }
            zin.closeEntry()
          }
        }
        result?.takeIf { it.modulePath.isNotEmpty() }
      } catch (_: Throwable) {
        null
      }

    /**
     * Reads the bundle's metadata **and** every baked preview PNG (`previews/<id>.png`) in one zip
     * pass. The returned [BundleContents.previews] is ordered cover-first (per
     * [BundlePngMetadata.coverPreviewId]) then the rest by id, so a viewer can page through them in
     * a stable order. Returns an empty [BundleContents] when [file] isn't a readable polyglot, so
     * callers can render a "couldn't read this file" hint without a try/catch.
     */
    fun readContents(file: File): BundleContents {
      val zipBytes = extractZipBytes(file) ?: return BundleContents(null, emptyList())
      var metadata: BundlePngMetadata? = null
      val pngs = LinkedHashMap<String, ByteArray>()
      try {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
          while (true) {
            val entry = zin.nextEntry ?: break
            val name = entry.name
            when {
              name == "bundle.json" ->
                metadata =
                  json.decodeFromString(serializer(), zin.readBytes().toString(Charsets.UTF_8))
              !entry.isDirectory && name.startsWith("previews/") && name.endsWith(".png") -> {
                val id = name.removePrefix("previews/").removeSuffix(".png")
                if (id.isNotEmpty()) pngs[id] = zin.readBytes()
              }
            }
            zin.closeEntry()
          }
        }
      } catch (_: Throwable) {
        return BundleContents(metadata, emptyList())
      }
      val cover = metadata?.coverPreviewId
      val ordered =
        pngs.entries.sortedWith(compareBy({ it.key != cover }, { it.key })).map {
          BundlePreview(id = it.key, pngBytes = it.value)
        }
      return BundleContents(metadata = metadata, previews = ordered)
    }

    /**
     * The trailing zip of a PNG+ZIP polyglot, the whole file for a bare zip, or null otherwise.
     * `internal` so [BundleExtractor] reuses the same polyglot scan instead of re-implementing it.
     */
    internal fun extractZipBytes(file: File): ByteArray? {
      val bytes = file.readBytes()
      if (bytes.size < PNG_SIG.size) return null
      if (bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) return bytes // bare zip "PK"
      for (i in PNG_SIG.indices) if (bytes[i] != PNG_SIG[i]) return null
      var offset = PNG_SIG.size
      while (offset + 8 <= bytes.size) {
        val length =
          ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
        val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
        offset += 4 + 4 + length + 4
        if (type == "IEND")
          return if (offset <= bytes.size) bytes.copyOfRange(offset, bytes.size) else null
      }
      return null
    }

    private val PNG_SIG = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
  }
}

/** A single baked preview pulled out of a bundle: its id and raw PNG bytes. */
data class BundlePreview(val id: String, val pngBytes: ByteArray) {
  // ByteArray breaks data-class equality/hashCode by identity; override so list comparisons in
  // tests behave structurally.
  override fun equals(other: Any?): Boolean =
    this === other ||
      (other is BundlePreview && id == other.id && pngBytes.contentEquals(other.pngBytes))

  override fun hashCode(): Int = 31 * id.hashCode() + pngBytes.contentHashCode()
}

/** Everything the TUI needs to render a bundle detached from its project: metadata + baked PNGs. */
data class BundleContents(val metadata: BundlePngMetadata?, val previews: List<BundlePreview>)
