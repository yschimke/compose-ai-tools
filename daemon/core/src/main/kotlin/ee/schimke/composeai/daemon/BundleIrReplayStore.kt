package ee.schimke.composeai.daemon

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Process-static lookup of a bundle's captured **intermediate representations** for IR replay
 * (schema v5). When `compose-preview bundle daemon` launches against a bundle that carries IR, the
 * CLI extracts the `ir/<id>.<ext>` bytes and the `bundle.json`, then passes:
 * - `composeai.daemon.bundleManifestPath` — the bundle.json whose `intermediateRepresentations` say
 *   which previews replay from IR and in what format;
 * - `composeai.daemon.irDir` — the directory holding the extracted IR bytes (flattened to leaf
 *   names, e.g. `<previewId>.tilelayout`).
 *
 * The render engine consults [lookup] by `previewId` before reflecting the preview's class: an
 * IR-backed preview has had its consumer bytecode dropped at pack time, so it is rendered by
 * inflating the IR via the matching runtime (`TileRenderer` for protolayout) instead.
 *
 * Loaded once, lazily, mirroring `PreviewIndex`'s sysprop-driven load — the engine needs no
 * constructor-time injection. Absent sysprops (every non-bundle daemon session) yield an empty map,
 * so [lookup] is always a cheap no-op there. Format strings are kept in lockstep with `IR_FORMAT_*`
 * in `:gradle-plugin` / `IrSidecarChannel` in `:data-render-core`.
 */
object BundleIrReplayStore {

  const val FORMAT_REMOTECOMPOSE: String = "remotecompose"

  const val FORMAT_PROTOLAYOUT: String = "protolayout"

  /** One preview's resolved IR. [resourcesBytes] is non-null only for protolayout. */
  class Entry(val format: String, val bytes: ByteArray, val resourcesBytes: ByteArray?)

  const val BUNDLE_MANIFEST_PATH_PROP: String = "composeai.daemon.bundleManifestPath"

  const val IR_DIR_PROP: String = "composeai.daemon.irDir"

  @Volatile private var cached: Map<String, Entry>? = null

  /** The resolved IR for [previewId], or `null` when this preview isn't IR-backed. */
  fun lookup(previewId: String?): Entry? {
    if (previewId == null) return null
    return entries()[previewId]
  }

  private fun entries(): Map<String, Entry> = cached ?: load().also { cached = it }

  /** Visible for tests — load from explicit paths instead of the system properties. */
  fun loadFrom(bundleManifestFile: File, irDir: File): Map<String, Entry> {
    val descriptors =
      runCatching {
          JSON.decodeFromString(BundleManifestLite.serializer(), bundleManifestFile.readText())
            .intermediateRepresentations
        }
        .getOrElse { emptyList() }
    val out = LinkedHashMap<String, Entry>()
    for (ir in descriptors) {
      val bytesFile = File(irDir, ir.path.substringAfterLast('/'))
      if (!bytesFile.isFile) continue
      val resourcesBytes =
        ir.resourcesPath
          ?.let { File(irDir, it.substringAfterLast('/')) }
          ?.takeIf { it.isFile }
          ?.readBytes()
      out[ir.previewId] = Entry(ir.format, bytesFile.readBytes(), resourcesBytes)
    }
    return out
  }

  /** Test seam — drop the cached map so a follow-up [lookup] reloads from the (new) sysprops. */
  fun resetForTest() {
    cached = null
  }

  private fun load(): Map<String, Entry> {
    val manifestPath = System.getProperty(BUNDLE_MANIFEST_PATH_PROP) ?: return emptyMap()
    val irDirPath = System.getProperty(IR_DIR_PROP) ?: return emptyMap()
    val manifestFile = File(manifestPath)
    val irDir = File(irDirPath)
    if (!manifestFile.isFile || !irDir.isDirectory) return emptyMap()
    return loadFrom(manifestFile, irDir)
  }

  private val JSON = Json { ignoreUnknownKeys = true }

  @Serializable
  private data class BundleManifestLite(
    val intermediateRepresentations: List<BundleIrLite> = emptyList()
  )

  @Serializable
  private data class BundleIrLite(
    val previewId: String,
    val format: String,
    val path: String,
    val resourcesPath: String? = null,
  )
}
