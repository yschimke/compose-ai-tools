package ee.schimke.composeai.cli.serve

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

/**
 * Design annotations — the typography and layout facts behind a rendered frame.
 *
 * The compare page can only diff pixels on its own: it shows that two frames differ, never *why*.
 * An annotation carries the spec a designer reads off the mock — the type style and size, the
 * padding and gap — anchored to a region so the page can draw it over either panel. With both sides
 * annotated, "these look slightly off" becomes "reference says bodyLarge/16dp, actual says
 * titleMedium/12dp".
 *
 * Producer-side, not inferred here. The reference layer comes from the design tool (the Figma
 * adapter's captured layout geometry); the actual layer is walked from the Compose semantics tree.
 * The serve host only transports and draws them, exactly as it does for design references — it
 * never measures pixels or fetches design URLs itself.
 */
@Serializable
data class AnnotationManifest(
  val schema: String = SCHEMA,
  /** Annotations over a preview's *rendered* frame, keyed by exact serve/catalog preview id. */
  val previews: Map<String, List<DesignAnnotation>> = emptyMap(),
  /** Annotations over a *reference* raster, keyed by [DesignReference.id]. */
  val references: Map<String, List<DesignAnnotation>> = emptyMap(),
) {
  companion object {
    const val SCHEMA = "compose-preview-annotations/v1"
  }
}

/** Which spec layer an annotation belongs to; the compare page toggles them independently. */
object AnnotationKind {
  const val TYPOGRAPHY = "typography"

  /**
   * Box geometry and the tokens that shaped it — size, padding, arrangement gap, `defaultMinSize`.
   * Authored by a producer for the reference side of the compare page, and since issue #4328 also
   * derived live from a render's own semantics tree by [ServeDesignAnnotations], so the viewer's
   * inspection layers can show the code side of the same redline.
   */
  const val LAYOUT = "layout"

  /**
   * Resolved theme attributes of a container — fill / border colour, corner radius, shape. Produced
   * live from the render's own semantics tree by [ServeDesignAnnotations] for the viewer's
   * inspection layers, rather than authored in a bundle's `annotations/index.json` (the compare
   * page's producer-side layers are [TYPOGRAPHY] / [LAYOUT] only).
   */
  const val THEME = "theme"

  val KNOWN = setOf(TYPOGRAPHY, LAYOUT, THEME)

  /**
   * The layers a **published bundle** can answer on its own, without a daemon.
   *
   * Typography alone, and the narrowness is the point. A producer authors [TYPOGRAPHY] and [LAYOUT]
   * into `annotations/index.json`, but the published layout layer is the compare page's
   * producer-side redline over a *reference* raster — [ServeBundleHost.drawableAnnotations] drops
   * it from the viewer's overlay for that reason — and [THEME] is derived live from a render's
   * semantics tree and is never authored into a bundle at all. So typography is the one layer for
   * which "the bundle already has it" is true, and answering a request for either of the others
   * from published bytes would return a payload silently missing what was asked for.
   *
   * [publishedLayersSuffice] is the predicate; this set is the reason it is not simply "whatever
   * the bundle happens to carry".
   */
  val PUBLISHABLE = setOf(TYPOGRAPHY)

  /**
   * Whether a `.annotations` request naming exactly [layers] can be answered from a bundle's
   * published index without losing a layer the caller asked for.
   *
   * False for a null (unscoped) request — a caller that named no layers is asking for all of them,
   * which is what every client did before `layers=` existed, and what a hand-typed URL still does.
   * Answering those from published bytes is precisely the regression #224 reverted.
   */
  fun publishedLayersSuffice(layers: Set<String>?): Boolean =
    layers != null && layers.isNotEmpty() && PUBLISHABLE.containsAll(layers)

  /**
   * The `layers=` query value parsed into a validated kind set, or null when the request named none
   * (or none that this build knows). Null means "every layer", the pre-`layers=` behaviour.
   */
  fun parseLayers(raw: String?): Set<String>? =
    raw?.split(',')?.map { it.trim() }?.filter { it in KNOWN }?.toSet()?.takeIf { it.isNotEmpty() }
}

/**
 * One annotation anchored to a region of the frame it describes.
 *
 * [bounds] are in the annotated image's own pixel space — the reference raster's for a reference
 * annotation, the render's for a preview one. The two frames are usually different sizes, so the
 * page scales each layer to its panel rather than assuming a shared coordinate space.
 */
@Serializable
data class DesignAnnotation(
  /** One of [AnnotationKind.KNOWN]. Unknown kinds are dropped on load. */
  val kind: String,
  val bounds: AnnotationBounds,
  /**
   * One-line spec as a designer would read it, e.g. `"bodyLarge 16sp/24"` or `"pad 16dp · gap
   * 8dp"`.
   */
  val label: String,
  /** Optional node/slot name, shown as the annotation's title. */
  val role: String? = null,
  /** Structured payload (token name, measured dp, …) for machine consumers and the hover card. */
  val detail: Map<String, String> = emptyMap(),
)

/** Both panels' layers, as embedded in the compare page for the client to draw. */
@Serializable
data class AnnotationPayload(
  val reference: List<DesignAnnotation> = emptyList(),
  val actual: List<DesignAnnotation> = emptyList(),
)

private val ANNOTATION_JSON = Json { encodeDefaults = false }

/**
 * Encode a payload for embedding in a `<script type="application/json">` block.
 *
 * HTML escaping would be wrong here — entities are not decoded inside a script element, so `&quot;`
 * would reach `JSON.parse` verbatim and throw. The only real hazard is a `</script>` inside a
 * string value, which ends the block early; `<` is a valid JSON escape for `<` and closes that hole
 * without touching the parsed value.
 */
fun encodeAnnotationPayload(payload: AnnotationPayload): String =
  ANNOTATION_JSON.encodeToString(payload).replace("<", "\\u003c")

/** A region in the annotated image's pixel space. */
@Serializable data class AnnotationBounds(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Validated, read-only view of a bundle/catalog's `annotations/index.json`.
 *
 * Fail-soft throughout, matching [ServeDesignReferenceStore]: a malformed manifest, an unknown
 * kind, or a nonsensical box is dropped while the rest of the session serves normally. An
 * annotation layer is a reading aid — losing one must never take the compare page down with it.
 */
class ServeAnnotationStore
private constructor(
  private val byPreview: Map<String, List<DesignAnnotation>>,
  private val byReference: Map<String, List<DesignAnnotation>>,
) {
  fun forPreview(previewId: String): List<DesignAnnotation> = byPreview[previewId].orEmpty()

  fun forReference(referenceId: String): List<DesignAnnotation> = byReference[referenceId].orEmpty()

  val isEmpty: Boolean = byPreview.isEmpty() && byReference.isEmpty()

  companion object {
    const val DIRECTORY = "annotations"
    const val INDEX_FILE = "index.json"
    private val JSON = Json { ignoreUnknownKeys = true }

    /** Empty store — a session that carries no annotations at all. */
    val EMPTY = ServeAnnotationStore(emptyMap(), emptyMap())

    fun load(bundleDir: File, fileSystem: FileSystem = FileSystem.SYSTEM): ServeAnnotationStore =
      load(bundleDir.toOkioPath(), fileSystem)

    fun load(bundleRoot: Path, fileSystem: FileSystem): ServeAnnotationStore {
      val index = bundleRoot / DIRECTORY.toPath() / INDEX_FILE.toPath()
      if (!fileSystem.exists(index)) return EMPTY
      val manifest =
        runCatching {
          JSON.decodeFromString<AnnotationManifest>(fileSystem.read(index) { readUtf8() })
        }
          .getOrNull() ?: return EMPTY
      if (manifest.schema != AnnotationManifest.SCHEMA) return EMPTY
      return ServeAnnotationStore(
        byPreview = manifest.previews.mapValues { (_, list) -> list.filter { it.isUsable() } },
        byReference = manifest.references.mapValues { (_, list) -> list.filter { it.isUsable() } },
      )
    }

    /**
     * A box with no area can't be drawn, and a negative origin would paint outside the panel. Both
     * indicate a producer bug rather than something to render badly.
     */
    private fun DesignAnnotation.isUsable(): Boolean =
      kind in AnnotationKind.KNOWN &&
        label.isNotBlank() &&
        bounds.width > 0 &&
        bounds.height > 0 &&
        bounds.x >= 0 &&
        bounds.y >= 0
  }
}
