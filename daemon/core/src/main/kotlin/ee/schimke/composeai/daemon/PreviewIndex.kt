package ee.schimke.composeai.daemon

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Daemon-side parse target for the gradle plugin's `previews.json`.
 *
 * **Layer-2-only DTO.** [LAYERING.md](../../../../../../../docs/daemon/LAYERING.md) forbids
 * `:daemon:core` from depending on `:gradle-plugin`. The plugin owns the authoritative
 * [PreviewInfo] type (`gradle-plugin/.../PreviewData.kt`) and writes it to disk via
 * kotlinx-serialization; the daemon parses the same JSON shape with this minimal mirror, capturing
 * only the fields the daemon actually needs. Extra fields the plugin emits (params block,
 * captures list, accessibility report pointer, …) are ignored at parse time via
 * `ignoreUnknownKeys`, so adding new plugin-side fields does NOT break the daemon's parser.
 *
 * **Why duplicate instead of share.** Sharing the type would either pull `:gradle-plugin` onto the
 * daemon's classpath (heavy, and a layering inversion) or carve a third "shared protocol" module
 * out of the plugin. Phase 1 deliberately picks duplication: ~30 LOC of mirror keeps the layering
 * invariant and the daemon's parse surface scoped to fields it consumes today.
 *
 * Field naming follows the wire JSON, NOT the plugin's internal field names. The plugin emits
 * `functionName` (the `@Preview`-annotated function), so we read `functionName` here.
 */
@Serializable
data class PreviewInfoDto(
  val id: String,
  /** Fully-qualified class containing the `@Preview` function. */
  val className: String,
  /** Method name of the `@Preview` function. The plugin's JSON key is `functionName`. */
  @SerialName("functionName") val methodName: String,
  /**
   * Source file path captured by the discovery task (`ClassInfo.sourceFile`). Optional — older
   * `previews.json` files predate B2.0 and don't include it.
   */
  val sourceFile: String? = null,
)

/**
 * Wire-shape of `previews.json`'s top-level object — only the fields the index needs. The plugin
 * also writes `module`, `variant`, and `accessibilityReport`; those are ignored on parse.
 */
@Serializable
private data class PreviewManifestDto(val previews: List<PreviewInfoDto> = emptyList())

/**
 * In-memory, read-only preview index owned by the daemon.
 *
 * **B2.2 phase 1.** The daemon parses `previews.json` once at startup and exposes the resulting
 * map for `initialize.manifest.{path, previewCount}`. Phase 2 (incremental rescan +
 * `discoveryUpdated` emission, scoped to a `fileChanged({kind:"sources"})` trigger) is deliberately
 * out of scope here — the index is immutable for the daemon's lifetime in phase 1.
 *
 * **Degraded mode.** [loadFromFile] never throws on a malformed or missing input. It returns
 * [empty] and writes a single warn-level diagnostic to stderr (free-form log per
 * [PROTOCOL.md § 1](../../../../../../../docs/daemon/PROTOCOL.md)). The daemon should still come up
 * on a corrupt manifest; clients see `previewCount = 0` and can re-trigger discovery.
 */
class PreviewIndex
internal constructor(
  /**
   * Absolute path to the file the index was loaded from. `null` when the index is the empty
   * placeholder — i.e. no `composeai.daemon.previewsJsonPath` sysprop was set, or the file didn't
   * exist / was malformed.
   */
  val path: Path?,
  private val byId: Map<String, PreviewInfoDto>,
) {

  /** Total number of previews known to the daemon. */
  val size: Int
    get() = byId.size

  /** Lookup by `PreviewInfo.id`. `null` if the id is unknown. */
  fun byId(id: String): PreviewInfoDto? = byId[id]

  /** All known preview ids. Phase 2 will diff a fresh scan against this set. */
  fun ids(): Set<String> = byId.keys

  companion object {
    /**
     * The empty placeholder. Used when no `composeai.daemon.previewsJsonPath` was supplied — e.g.
     * fake-mode harness scenarios, the in-process integration tests, the pre-B2.2 default.
     * `path = null`, `size = 0`.
     */
    fun empty(): PreviewIndex = PreviewIndex(path = null, byId = emptyMap())

    /**
     * Parses [path] as a plugin-emitted `previews.json` and returns an index over its `previews`
     * array. Returns [empty] (and prints a warn-level diagnostic to stderr) if the file is
     * missing, unreadable, or malformed; never throws.
     */
    fun loadFromFile(path: Path): PreviewIndex {
      val absolute = path.toAbsolutePath()
      if (!Files.exists(absolute)) {
        System.err.println(
          "compose-ai-daemon: PreviewIndex.loadFromFile($absolute): file does not exist; " +
            "starting with empty index"
        )
        return empty()
      }
      val text =
        try {
          Files.readString(absolute)
        } catch (t: Throwable) {
          System.err.println(
            "compose-ai-daemon: PreviewIndex.loadFromFile($absolute): read failed " +
              "(${t.javaClass.simpleName}: ${t.message}); starting with empty index"
          )
          return empty()
        }
      val manifest =
        try {
          JSON.decodeFromString(PreviewManifestDto.serializer(), text)
        } catch (t: Throwable) {
          System.err.println(
            "compose-ai-daemon: PreviewIndex.loadFromFile($absolute): parse failed " +
              "(${t.javaClass.simpleName}: ${t.message}); starting with empty index"
          )
          return empty()
        }
      val byId = LinkedHashMap<String, PreviewInfoDto>(manifest.previews.size)
      for (preview in manifest.previews) {
        byId[preview.id] = preview
      }
      return PreviewIndex(path = absolute, byId = byId)
    }

    /**
     * System property the per-target [DaemonMain] reads to locate `previews.json`. The gradle
     * plugin emits this as part of `composePreviewDaemonStart`'s descriptor (see
     * [DaemonClasspathDescriptor.systemProperties]); when unset, the daemon comes up with
     * [empty] — preserves pre-B2.2 in-process / fake-mode behaviour.
     */
    const val PREVIEWS_JSON_PATH_PROP: String = "composeai.daemon.previewsJsonPath"

    private val JSON: Json = Json {
      ignoreUnknownKeys = true
      // Plugin-side `PreviewParams.fontScale = 1.0f` etc. are encoded with default values; we
      // don't decode them, but staying lenient about defaults keeps the parse path forgiving.
      isLenient = false
    }
  }
}
