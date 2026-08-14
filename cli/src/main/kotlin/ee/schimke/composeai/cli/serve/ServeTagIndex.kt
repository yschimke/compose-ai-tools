package ee.schimke.composeai.cli.serve

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

/**
 * A catalog's **published** tag index — `served preview id → testTag → {count, bounds}`.
 *
 * The element identity a scoped parity acceptance resolves against
 * ([docs/design/COMPONENT_PARITY_WORKFLOW.md](../../../../../../../../docs/design/COMPONENT_PARITY_WORKFLOW.md)).
 *
 * ## Why this exists alongside [ServeSemanticsTags]
 *
 * They are the same projection with different producers, because a published catalog has no daemon.
 * [ServeSemanticsTags] projects the index live from a render this host just performed; that path
 * requires a semantics tree, which only a daemon produces. A catalog's renders happened in CI, at
 * catalog-generation time — so its index is computed *there* (`scripts/design-artifacts/
 * tag-index.mjs`, the JS twin) and published as `tags/index.json` beside the stickers. This class
 * is the reader for that file.
 *
 * The consequence worth stating: without this, the whole element-gate half of the parity workflow
 * was unreachable on exactly the surfaces the epic is about, since every published design catalog
 * is a static bundle.
 *
 * ## Fail-soft, like every other carried artifact
 *
 * A malformed index, an unknown schema token, or an oversized one drops **wholesale** and the
 * catalog serves exactly as before — matching [ServeAnnotationStore] and
 * [ServeDesignReferenceStore]. An acceptance that then finds no tag entry degrades to no element
 * gate, which is the safe direction: a missing gate costs a check, a wrong one produces a wrong
 * verdict.
 */
@Serializable
data class TagIndexManifest(
  val schema: String = SCHEMA,
  /** Keyed by the **served** preview id (`button-filled__ideal__default__light`). */
  val previews: Map<String, Map<String, ServeSemanticsTags.TagEntry>> = emptyMap(),
) {
  companion object {
    const val SCHEMA = "compose-preview-tags/v1"
  }
}

/** Validated, read-only view of a catalog's `tags/index.json`. */
class ServeTagIndexStore
private constructor(private val byPreview: Map<String, Map<String, ServeSemanticsTags.TagEntry>>) {

  /** The tag index for [previewId], empty when the catalog published none for it. */
  fun forPreview(previewId: String): Map<String, ServeSemanticsTags.TagEntry> =
    byPreview[previewId].orEmpty()

  val isEmpty: Boolean = byPreview.isEmpty()

  /** Previews the catalog published an index for. */
  val previewIds: Set<String> = byPreview.keys

  companion object {
    const val DIRECTORY = "tags"
    const val INDEX_FILE = "index.json"

    /**
     * Cap on indexed previews. A catalog is third-party data and this file is read at staging time
     * on a shared host, so it gets the same treatment as the acceptance budget: a bound that a
     * hostile or broken publisher cannot raise. Generous against real use — the largest published
     * catalog is in the hundreds of stickers.
     */
    const val MAX_PREVIEWS = 4096

    private val JSON = Json { ignoreUnknownKeys = true }

    /**
     * Empty store — a catalog that publishes no index at all, which is every catalog until it does.
     */
    val EMPTY = ServeTagIndexStore(emptyMap())

    fun load(bundleDir: File, fileSystem: FileSystem = FileSystem.SYSTEM): ServeTagIndexStore =
      load(bundleDir.toOkioPath(), fileSystem)

    fun load(bundleRoot: Path, fileSystem: FileSystem): ServeTagIndexStore {
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
          .mapValues { (_, tags) -> tags.filterValues { it.isUsable() } }
          .filterValues { it.isNotEmpty() }
      )
    }

    /**
     * A count below 1 is not a tag anything carried, and a zero-area box is not geometry a gate can
     * measure against — both indicate a producer bug rather than something to resolve badly. The
     * bounds may legitimately be absent (a tag whose every node had unusable bounds still counts,
     * which is the point of `count`), so absence is not a rejection.
     */
    private fun ServeSemanticsTags.TagEntry.isUsable(): Boolean =
      count >= 1 && bounds?.let { it.width > 0 && it.height > 0 } != false
  }
}
