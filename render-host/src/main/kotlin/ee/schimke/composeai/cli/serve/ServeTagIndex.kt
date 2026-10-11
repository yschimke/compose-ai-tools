package ee.schimke.composeai.cli.serve

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

/**
 * A catalog's published tag index (`served preview id → testTag → {count, bounds}`), the element
 * identity a scoped parity acceptance resolves against
 * ([docs/design/COMPONENT_PARITY_WORKFLOW.md](../../../../../../../../docs/design/COMPONENT_PARITY_WORKFLOW.md)).
 * The same projection as [ServeSemanticsTags], but computed in CI (`tag-index.mjs`) and published
 * as `tags/index.json`, since a static catalog has no daemon.
 *
 * Fail-soft like [ServeAnnotationStore]: a malformed, unknown or oversized index is dropped
 * wholesale. An element-scoped acceptance that then can't resolve its tag suppresses nothing
 * (absent entry → `element-moved` → invalid), so it never degrades into a plain ignore rectangle;
 * `gate-element-vanished` in the conformance fixtures keeps both engines there.
 */
@Serializable
public data class TagIndexManifest(
  val schema: String = SCHEMA,
  /** Keyed by the **served** preview id (`button-filled__ideal__default__light`). */
  val previews: Map<String, Map<String, WireTagEntry>> = emptyMap(),
) {
  public companion object {
    public const val SCHEMA: String = "compose-preview-tags/v1"
  }
}

/**
 * The wire shape of one entry, not [ServeSemanticsTags.TagEntry], because [space] must be nullable
 * here: decoding into the producer type would default a missing `space` and hide that the publisher
 * declared nothing. [ServeTagIndexStore] rejects that, and only validated entries are converted.
 */
@Serializable
public data class WireTagEntry(
  val count: Int = 0,
  val bounds: AnnotationBounds? = null,
  val space: String? = null,
)

/** Validated, read-only view of a catalog's `tags/index.json`. */
public class ServeTagIndexStore
private constructor(private val byPreview: Map<String, Map<String, ServeSemanticsTags.TagEntry>>) {

  /** The tag index for [previewId], empty when the catalog published none for it. */
  public fun forPreview(previewId: String): Map<String, ServeSemanticsTags.TagEntry> =
    byPreview[previewId].orEmpty()

  public val isEmpty: Boolean = byPreview.isEmpty()

  /** Previews the catalog published an index for. */
  public val previewIds: Set<String> = byPreview.keys

  public companion object {
    public const val DIRECTORY: String = "tags"
    public const val INDEX_FILE: String = "index.json"

    /**
     * Cap on indexed previews: third-party data read on a shared host, so bounded; far above real
     * catalogs.
     */
    public const val MAX_PREVIEWS: Int = 4096

    private val JSON = Json { ignoreUnknownKeys = true }

    /** Empty store, for a catalog that publishes no index. */
    public val EMPTY: ServeTagIndexStore = ServeTagIndexStore(emptyMap())

    public fun load(
      bundleDir: File,
      fileSystem: FileSystem = FileSystem.SYSTEM,
    ): ServeTagIndexStore = load(bundleDir.toOkioPath(), fileSystem)

    public fun load(bundleRoot: Path, fileSystem: FileSystem): ServeTagIndexStore {
      val index = bundleRoot / DIRECTORY.toPath() / INDEX_FILE.toPath()
      if (!fileSystem.exists(index)) return EMPTY
      val manifest =
        runCatching {
          JSON.decodeFromString<TagIndexManifest>(fileSystem.read(index) { readUtf8() })
        }
          .getOrNull() ?: return EMPTY
      if (manifest.schema != TagIndexManifest.SCHEMA) return EMPTY
      if (manifest.previews.size > MAX_PREVIEWS) return EMPTY
      return ServeTagIndexStore(
        manifest.previews
          .mapValues { (_, tags) ->
            tags.mapNotNull { (tag, e) -> e.validated()?.let { tag to it } }.toMap()
          }
          .filterValues { it.isNotEmpty() }
      )
    }

    /**
     * The entry as [ServeSemanticsTags.TagEntry], or null when unusable: count below 1 or a
     * zero-area box (absent bounds are fine; `count` still matters). An absent or unrecognised
     * `space` is also rejected rather than assumed to be render pixels.
     */
    private fun WireTagEntry.validated(): ServeSemanticsTags.TagEntry? {
      if (count < 1) return null
      if (space != ServeSemanticsTags.RENDER_PIXELS) return null
      if (bounds != null && (bounds.width <= 0 || bounds.height <= 0)) return null
      return ServeSemanticsTags.TagEntry(count = count, bounds = bounds, space = space)
    }
  }
}
