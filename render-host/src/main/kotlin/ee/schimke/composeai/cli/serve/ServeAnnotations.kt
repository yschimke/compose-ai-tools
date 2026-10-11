package ee.schimke.composeai.cli.serve

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

/**
 * Design annotations: the typography and layout facts behind a rendered frame (type style and size,
 * padding, gap), anchored to regions so the compare page can show why two frames differ, not just
 * that they do. Produced upstream — reference side from the design tool, actual side from the
 * Compose semantics tree; the serve host only transports and draws them.
 */
@Serializable
public data class AnnotationManifest(
  val schema: String = SCHEMA,
  /** Annotations over a preview's *rendered* frame, keyed by exact serve/catalog preview id. */
  val previews: Map<String, List<DesignAnnotation>> = emptyMap(),
  /** Annotations over a *reference* raster, keyed by [DesignReference.id]. */
  val references: Map<String, List<DesignAnnotation>> = emptyMap(),
) {
  public companion object {
    public const val SCHEMA: String = "compose-preview-annotations/v1"
  }
}

/** Which spec layer an annotation belongs to; the compare page toggles them independently. */
public object AnnotationKind {
  public const val TYPOGRAPHY: String = "typography"

  /**
   * Box geometry and the tokens that shaped it (size, padding, arrangement gap, `defaultMinSize`).
   * Authored for the reference side, and also derived live by [ServeDesignAnnotations] for the code
   * side.
   */
  public const val LAYOUT: String = "layout"

  /**
   * Resolved container theme attributes (fill / border colour, corner radius, shape), derived live
   * by [ServeDesignAnnotations]; never authored into a bundle.
   */
  public const val THEME: String = "theme"

  public val KNOWN: Set<String> = setOf(TYPOGRAPHY, LAYOUT, THEME)

  /**
   * The layers a published bundle can answer without a daemon: typography only. Published [LAYOUT]
   * is a reference-side redline ([ServeBundleHost.drawableAnnotations] drops it from the overlay)
   * and [THEME] is live-only, so answering those from published bytes would silently omit them. See
   * [publishedLayersSuffice].
   */
  public val PUBLISHABLE: Set<String> = setOf(TYPOGRAPHY)

  /**
   * Whether a `.annotations` request for exactly [layers] can be answered from a bundle's published
   * index without losing a layer. False for a null (unscoped) request, which asks for all layers.
   */
  public fun publishedLayersSuffice(layers: Set<String>?): Boolean =
    layers != null && layers.isNotEmpty() && PUBLISHABLE.containsAll(layers)

  /**
   * The `layers=` value as a validated kind set, or null (none named or none known), meaning every
   * layer.
   */
  public fun parseLayers(raw: String?): Set<String>? =
    raw?.split(',')?.map { it.trim() }?.filter { it in KNOWN }?.toSet()?.takeIf { it.isNotEmpty() }
}

/**
 * One annotation anchored to a region. [bounds] are in the annotated image's own pixel space; the
 * page scales each layer to its panel since frames usually differ in size.
 */
@Serializable
public data class DesignAnnotation(
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
public data class AnnotationPayload(
  val reference: List<DesignAnnotation> = emptyList(),
  val actual: List<DesignAnnotation> = emptyList(),
)

private val ANNOTATION_JSON = Json { encodeDefaults = false }

/**
 * Encode for a `<script type="application/json">` block. Not HTML-escaped (entities aren't decoded
 * in script); `<` is escaped so a `</script>` in a value can't end the block.
 */
public fun encodeAnnotationPayload(payload: AnnotationPayload): String =
  ANNOTATION_JSON.encodeToString(payload).replace("<", "\\u003c")

/** A region in the annotated image's pixel space. */
@Serializable
public data class AnnotationBounds(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Validated, read-only view of a bundle/catalog's `annotations/index.json`. Fail-soft like
 * [ServeDesignReferenceStore]: bad manifests, unknown kinds and nonsensical boxes are dropped.
 */
public class ServeAnnotationStore
private constructor(
  private val byPreview: Map<String, List<DesignAnnotation>>,
  private val byReference: Map<String, List<DesignAnnotation>>,
) {
  public fun forPreview(previewId: String): List<DesignAnnotation> = byPreview[previewId].orEmpty()

  public fun forReference(referenceId: String): List<DesignAnnotation> =
    byReference[referenceId].orEmpty()

  public val isEmpty: Boolean = byPreview.isEmpty() && byReference.isEmpty()

  public companion object {
    public const val DIRECTORY: String = "annotations"
    public const val INDEX_FILE: String = "index.json"
    private val JSON = Json { ignoreUnknownKeys = true }

    /** Empty store — a session that carries no annotations at all. */
    public val EMPTY: ServeAnnotationStore = ServeAnnotationStore(emptyMap(), emptyMap())

    public fun load(
      bundleDir: File,
      fileSystem: FileSystem = FileSystem.SYSTEM,
    ): ServeAnnotationStore = load(bundleDir.toOkioPath(), fileSystem)

    public fun load(bundleRoot: Path, fileSystem: FileSystem): ServeAnnotationStore {
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

    /** Zero-area boxes and negative origins indicate a producer bug. */
    private fun DesignAnnotation.isUsable(): Boolean =
      kind in AnnotationKind.KNOWN &&
        label.isNotBlank() &&
        bounds.width > 0 &&
        bounds.height > 0 &&
        bounds.x >= 0 &&
        bounds.y >= 0
  }
}
