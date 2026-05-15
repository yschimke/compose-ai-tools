package ee.schimke.composeai.plugin

import java.io.File
import kotlinx.serialization.Serializable

/**
 * On-disk format for `compose-preview` bundles — portable, self-contained artefacts that record one
 * or more `@Preview` composables together with the minimal classpath needed to re-render them.
 *
 * # File shape
 *
 * The bundle is a **PNG + ZIP polyglot**:
 * 1. Bytes `0..n` are a valid PNG (the cover image — the first selected preview's rendered output,
 *    or a placeholder when no render is available). Finder, Preview.app, browsers, GitHub, Slack —
 *    every PNG viewer renders the leading image.
 * 2. Bytes `n+1..EOF` are a standard ZIP archive. ZIP parsers scan backwards from EOF for the
 *    End-Of-Central-Directory signature (`PK\x05\x06`), so the leading PNG bytes are invisible to
 *    them. `unzip foo.png` works; so does any library zip reader.
 *
 * `file(1)` reports "PNG image data". The same file opened with `compose-preview bundle open` (or
 * via the VS Code extension) extracts the appended zip and loads the bundle.
 *
 * # ZIP layout
 *
 * ```
 * bundle.json              — manifest (this file's [BundleManifest])
 * previews.json            — filtered to selected preview ids; same shape as the original
 * classes/app.jar          — consumer module bytecode, MINIMIZED to classes reachable from the
 *                            selected previews (plus all module resources)
 * libs/<name>.jar          — third-party jars from the runtime classpath that contain ≥1 reachable
 *                            class. Whole jars; we don't strip inside them
 * renders/<preview-id>.png — optional pre-rendered cache, off by default in v1
 * report.json              — [MinimizationReport] for transparency on what was kept vs dropped
 * ```
 */
@Serializable
data class BundleManifest(
  val schemaVersion: Int,
  /** Backend the bundle was packed for. v1 = "desktop"; "android" follows. */
  val backend: String,
  /** Selected preview ids (matches `previews.json[].id`). First entry = cover. */
  val previewIds: List<String>,
  /** Preview id whose PNG forms the polyglot's leading bytes. Usually `previewIds[0]`. */
  val coverPreviewId: String?,
  /**
   * Classpath entries inside the bundle, in load order. Each is a posix-style relative path —
   * `classes/app.jar` first, then `libs/<name>.jar` for each kept third-party jar. The player
   * extracts the zip, resolves these to absolute paths, and hands them to the renderer.
   */
  val classpath: List<String>,
  /** Source Gradle path that produced the bundle, e.g. `:samples:cmp`. */
  val modulePath: String,
  /** `BUNDLE_VERSION`-shaped identifier of the producer for diagnostics. */
  val producedBy: String,
)

const val BUNDLE_SCHEMA_VERSION: Int = 1

/**
 * Diagnostic record describing how aggressive the minimization was. Always written into the bundle
 * as `report.json` so users can audit whether the closure walk was effective.
 */
@Serializable
data class MinimizationReport(
  val entryClassFqns: List<String>,
  val reachableClassCount: Int,
  val totalScannedClassCount: Int,
  val moduleClasses: ModuleClassesStats,
  val libraryJars: List<LibraryJarDecision>,
)

@Serializable
data class ModuleClassesStats(
  val totalClasses: Int,
  val reachableClasses: Int,
  val packedBytes: Long,
)

@Serializable
data class LibraryJarDecision(
  /** Original absolute path the jar was resolved from. Useful for forensic comparison. */
  val sourcePath: String,
  /** Posix relative path inside the bundle, or `null` when dropped. */
  val bundledAs: String?,
  val totalClasses: Int,
  val reachableClasses: Int,
  val originalBytes: Long,
  /** `true` when the jar contributed at least one class to the closure. */
  val kept: Boolean,
)

/**
 * Writes a PNG + ZIP polyglot. The leading bytes are [coverPng] verbatim; the appended bytes are
 * [zipBytes] verbatim. Both inputs must already be valid in their respective formats; this writer
 * does not reframe chunks or rewrite the zip's central directory.
 *
 * Most image viewers and zip readers tolerate trailing/leading extra bytes respectively, so the raw
 * concatenation is enough to satisfy both formats. ZIP's End-Of-Central-Directory record is
 * searched from EOF (which is in the appended zip), and PNG's chunk loop terminates at the IEND
 * record (which is inside [coverPng]). See: <https://en.wikipedia.org/wiki/Polyglot_(computing)>.
 */
internal fun writePngZipPolyglot(coverPng: ByteArray, zipBytes: ByteArray, out: File) {
  out.parentFile?.mkdirs()
  out.outputStream().use { stream ->
    stream.write(coverPng)
    stream.write(zipBytes)
  }
}

/**
 * Reads a bundle file produced by [writePngZipPolyglot] (or a plain `.zip`) and returns the zip
 * bytes. Detects the PNG signature on the leading bytes and seeks past the IEND chunk to find the
 * zip start; plain zips (signature `PK\x03\x04`) are returned as-is.
 *
 * Throws [IllegalArgumentException] when neither signature is found.
 */
internal fun extractZipBytes(file: File): ByteArray {
  val bytes = file.readBytes()
  if (bytes.size < 8) {
    throw IllegalArgumentException("not a bundle: ${file.path} is too small (${bytes.size}B)")
  }
  // Plain zip — return verbatim.
  if (bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
    return bytes
  }
  // PNG polyglot — walk chunks until IEND, return the trailing bytes as zip.
  if (isPngSignature(bytes)) {
    val zipStart = pngLength(bytes)
    return bytes.copyOfRange(zipStart, bytes.size)
  }
  throw IllegalArgumentException(
    "not a bundle: ${file.path} — leading bytes match neither PNG (\\x89PNG…) nor ZIP (PK\\x03\\x04)"
  )
}

private val PNG_SIGNATURE: ByteArray =
  byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) // 0x89 P N G \r \n SUB \n

private fun isPngSignature(bytes: ByteArray): Boolean {
  if (bytes.size < PNG_SIGNATURE.size) return false
  for (i in PNG_SIGNATURE.indices) if (bytes[i] != PNG_SIGNATURE[i]) return false
  return true
}

/**
 * Returns the byte offset of the first byte past the PNG's IEND chunk — equivalently, the length of
 * the leading PNG in the polyglot. Each chunk is `[length:4][type:4][data:length][crc:4]`; the
 * stream ends after IEND's CRC.
 */
private fun pngLength(bytes: ByteArray): Int {
  var offset = PNG_SIGNATURE.size
  while (offset < bytes.size) {
    val length =
      ((bytes[offset].toInt() and 0xff) shl 24) or
        ((bytes[offset + 1].toInt() and 0xff) shl 16) or
        ((bytes[offset + 2].toInt() and 0xff) shl 8) or
        (bytes[offset + 3].toInt() and 0xff)
    val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
    offset += 4 + 4 + length + 4
    if (type == "IEND") return offset
  }
  throw IllegalArgumentException("truncated PNG: IEND not found before EOF")
}
