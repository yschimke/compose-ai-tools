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
 * [ServeDesignReferenceStore].
 *
 * **What an acceptance does next is not "degrade to no element gate".** An earlier revision of this
 * sentence said so and called it the safe direction; it is only safe if the acceptance also stops
 * *suppressing*. An element-scoped acceptance whose gate cannot run and whose mask still joins the
 * valid union has silently become a plain ignore rectangle — and a tagged element that has
 * disappeared or moved goes on being hidden, which is precisely the failure the element gate was
 * added to catch. So the outcome is defined the other way: **an element-scoped acceptance that
 * cannot resolve its tag suppresses nothing.** The engine reaches that verdict already — an absent
 * entry resolves to no node, which is `element-moved`, which invalidates — and
 * `gate-element-vanished` in the conformance fixtures is what keeps both engines there.
 */
@Serializable
data class TagIndexManifest(
  val schema: String = SCHEMA,
  /** Keyed by the **served** preview id (`button-filled__ideal__default__light`). */
  val previews: Map<String, Map<String, WireTagEntry>> = emptyMap(),
) {
  companion object {
    const val SCHEMA = "compose-preview-tags/v1"
  }
}

/**
 * The on-the-wire shape of one entry, deliberately **not** [ServeSemanticsTags.TagEntry].
 *
 * The difference is [space], which is nullable here and defaulted there. Decoding straight into the
 * producer type would fill a missing `space` in with the Kotlin default, and the host could no
 * longer tell "the publisher declared render-pixels" from "the publisher declared nothing" — which
 * is precisely what the discriminator exists to distinguish. A later element gate reading an
 * undeclared index would then compare or transform bounds in a plane nobody stated.
 *
 * So the wire type keeps absence representable, [ServeTagIndexStore] rejects it, and only validated
 * entries are converted to the producer type.
 */
@Serializable
data class WireTagEntry(
  val count: Int = 0,
  val bounds: AnnotationBounds? = null,
  val space: String? = null,
)

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
          .mapValues { (_, tags) ->
            tags.mapNotNull { (tag, e) -> e.validated()?.let { tag to it } }.toMap()
          }
          .filterValues { it.isNotEmpty() }
      )
    }

    /**
     * The entry as [ServeSemanticsTags.TagEntry], or null when it is not usable.
     *
     * A count below 1 is not a tag anything carried, and a zero-area box is not geometry a gate can
     * measure against — both indicate a producer bug rather than something to resolve badly. Absent
     * bounds are *not* a rejection: a tag whose every node had unusable bounds still counts, which
     * is the point of `count`.
     *
     * An **absent or unrecognised `space` is** a rejection. Silently defaulting it would let an
     * index that declared no coordinate space read as though it had declared render pixels, and a
     * gate would then compare bounds in a plane nobody stated — the exact confusion the field was
     * added to prevent. An unknown value is refused for the same reason rather than assumed: a
     * future canonical-plane producer must not be read as render-pixel by an older host.
     */
    private fun WireTagEntry.validated(): ServeSemanticsTags.TagEntry? {
      if (count < 1) return null
      if (space != ServeSemanticsTags.RENDER_PIXELS) return null
      if (bounds != null && (bounds.width <= 0 || bounds.height <= 0)) return null
      return ServeSemanticsTags.TagEntry(count = count, bounds = bounds, space = space)
    }
  }
}
