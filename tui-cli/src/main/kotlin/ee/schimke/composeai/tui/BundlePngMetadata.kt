package ee.schimke.composeai.tui

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Just enough of a `compose-preview` bundle's `bundle.json` for the image-only TUI to drive a live
 * session: which Gradle module produced it and which preview is the cover. A bundle is a PNG+ZIP
 * polyglot — a valid PNG up front (the cover render) with a standard zip appended. We read only the
 * `bundle.json` entry and ignore everything else (`classes/app.jar`, `previews.json`, `report.json`).
 *
 * The polyglot-extraction routine is duplicated here rather than shared with `:cli`'s `BundleReader`
 * or `:bundle-viewer`'s loader — both deliberately keep their own copy so neither module drags the
 * other onto its classpath, and the routine is tiny and stable. See `cli/BundleCommand.kt`.
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
              result =
                json.decodeFromString(serializer(), zin.readBytes().toString(Charsets.UTF_8))
              break
            }
            zin.closeEntry()
          }
        }
        result?.takeIf { it.modulePath.isNotEmpty() }
      } catch (_: Throwable) {
        null
      }

    /** The trailing zip of a PNG+ZIP polyglot, the whole file for a bare zip, or null otherwise. */
    private fun extractZipBytes(file: File): ByteArray? {
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
        if (type == "IEND") return if (offset <= bytes.size) bytes.copyOfRange(offset, bytes.size) else null
      }
      return null
    }

    private val PNG_SIG = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
  }
}
