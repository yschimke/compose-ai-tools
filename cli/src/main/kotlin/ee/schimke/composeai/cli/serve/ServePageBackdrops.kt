package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

/**
 * A **page backdrop** — one key screen imported from the design tool as a flat image, plus the
 * rectangle of every component instance on it, each linked back to the code component that
 * implements it.
 *
 * Where [ServeDesignReferenceStore] answers the per-component question ("does this Button match its
 * Figma node?"), this answers the whole-screen one: *here is the Upcoming screen — which of its
 * parts do we implement, where, and do our renders sit right on top of the design?* The parts
 * nothing implements are the point: an `unlinked` placement is a finding, not an omission.
 *
 * ## This is a published contract, not a new schema
 *
 * The shape below is design-parity's `page-backdrop.schema.json` verbatim, whose own description
 * already names this consumer: *"the wire contract between the producer … and any consumer that
 * draws the result: a preview server, an IDE panel, or the bundled offline HTML viewer"*. So this
 * file deliberately invents nothing — it mirrors the producer's field names and semantics, and the
 * server's job is only to draw them. Two consequences worth stating because they are easy to
 * "improve" wrongly:
 *
 * - **[BackdropRect] is in frame-local design units, never image pixels.** The backdrop PNG is
 *   exported at some [BackdropImage.scale], and pinning geometry to the unscaled frame means a
 *   re-export at another resolution doesn't invalidate the manifest. The viewer positions by ratio
 *   (`x / frame.width`), so no density arithmetic happens anywhere on this side.
 * - **[BackdropPlacement.previewId] exists for this server specifically.** The producer resolves
 *   the code handle to a preview id so the consumer can render the component without re-deriving
 *   the mapping from `design-map.json`. When a catalog publishes backdrops, that id is the
 *   **serve** preview id (the `/render?preview=` key), which is what `design-pages.mjs` remaps on
 *   the way into the bundle.
 *
 * [version] is accepted rather than demanded equal: the producer releases independently of every
 * consumer, and additive optional fields never bump it. A future breaking bump is rejected
 * wholesale by [ServePageBackdropStore.sanitize] instead of being half-read.
 *
 * ## Failure posture and trust
 *
 * Fail-soft throughout, like [ServeDesignReferenceStore] and [ServeParityActivityStore]: a missing
 * file, a wrong version, a malformed page, or a traversing image path drops that record — or the
 * whole manifest — and the catalog serves its grid exactly as before. A backdrop is an enhancement;
 * it must never cost a catalog its previews.
 *
 * A catalog is third-party data, and this file carries **free text authored in the design tool**:
 * layer names like `Button/Primary`. Nothing here is trusted. Every string is HTML-escaped at
 * render time by [ServeWeb], and the outbound Figma deep link is *reassembled* from a validated
 * file key and node id against a literal origin ([ServeFigmaSpec]) rather than taken from the file,
 * so a manifest declaring `javascript:…` yields no link instead of an attacker-chosen href.
 */
@Serializable
data class PageBackdropManifest(
  /** Equals the producer's `PAGE_BACKDROP_VERSION`. Bumped only for a breaking shape change. */
  val version: Int = VERSION,
  /** The design source the pages came from; only `figma` exposes a page-level read API today. */
  val source: String = "figma",
  /** Design-tool file the pages were imported from. Validated before any deep link is built. */
  val fileKey: String = "",
  /** Imported pages, in the order the producer's config listed them — its own stated priority. */
  val pages: List<BackdropPage> = emptyList(),
) {
  companion object {
    /** The newest manifest version this server knows how to draw. */
    const val VERSION = 1

    /** Directory (bundle-relative) the manifest and its backdrop PNGs live in. */
    const val DIRECTORY = "pages"

    const val INDEX_FILE = "index.json"
  }
}

/** One imported key page: the design's own pixels, and everything sitting on them. */
@Serializable
data class BackdropPage(
  /** Stable slug, unique within the manifest, and a URL path segment on `/{system}/pages/{id}`. */
  val id: String,
  /** The frame's layer name in the design file, e.g. `Upcoming-Mobile`. */
  val name: String,
  /** Node id of the page frame, for the deep link back into the design tool. */
  val nodeId: String = "",
  /** The frame's size in design units — the coordinate space every placement's bounds live in. */
  val frame: BackdropFrame,
  val image: BackdropImage,
  /** Component instances on the page, ordered top-left first so a re-import diffs cleanly. */
  val placements: List<BackdropPlacement> = emptyList(),
)

@Serializable data class BackdropFrame(val width: Double, val height: Double)

/** The exported backdrop image: where it is, and what scale it was rasterized at. */
@Serializable data class BackdropImage(val uri: String, val scale: Double = 1.0)

/**
 * An axis-aligned rectangle in the page's frame-local design units, origin at the frame's top-left.
 */
@Serializable
data class BackdropRect(val x: Double, val y: Double, val width: Double, val height: Double)

/** One component instance found on the page, and the code it maps to. */
@Serializable
data class BackdropPlacement(
  /** Node id of the instance on the page. */
  val nodeId: String = "",
  /** The instance's layer name, e.g. `Button/Primary`. Free text — escaped at render time. */
  val name: String = "",
  val componentId: String? = null,
  val componentSetId: String? = null,
  val bounds: BackdropRect,
  /** Nesting depth below the page frame; 0 for a top-level instance. */
  val depth: Int = 0,
  /** The instance's own design ref, `figma:<fileKey>/<nodeId>`. Present linked or not. */
  val ref: String = "",
  /** Code handle, e.g. `ui/Button.kt#PrimaryButton`. Absent when unlinked. */
  val code: String? = null,
  /** The preview id this server renders the placement with. Absent when the producer named none. */
  val previewId: String? = null,
  /** How the placement was linked: `code-connect`, `manifest`, `convention`, or `unlinked`. */
  val link: String = LINK_UNLINKED,
  /** How much to trust [code]: `high` for a machine/manifest link, `low` for a name match. */
  val confidence: String? = null,
  /** The design ref the link actually matched on — the set, the component, or the instance. */
  val matchedRef: String? = null,
) {
  /** True when nothing in the repo claims this part of the screen. The interesting case. */
  val isUnlinked: Boolean
    get() = link == LINK_UNLINKED

  companion object {
    const val LINK_CODE_CONNECT = "code-connect"
    const val LINK_MANIFEST = "manifest"
    const val LINK_CONVENTION = "convention"
    const val LINK_UNLINKED = "unlinked"

    /** The four link methods the contract defines. Anything else is read as [LINK_UNLINKED]. */
    val LINK_METHODS = setOf(LINK_CODE_CONNECT, LINK_MANIFEST, LINK_CONVENTION, LINK_UNLINKED)
  }
}

/**
 * Validated, read-only view of a bundle/catalog's `pages/index.json` and the backdrop PNGs beside
 * it.
 *
 * Every failure is silent and local, matching [ServeDesignReferenceStore]. The one deliberate
 * difference from that store: a backdrop PNG is a whole screen at 2x, so it is **validated by its
 * signature and read on demand** rather than held in memory from load. A catalog carrying a handful
 * of screens shouldn't cost the box a few megabytes of resident image for a page nobody opened.
 */
class ServePageBackdropStore
private constructor(
  private val root: Path,
  val pages: List<BackdropPage>,
  private val fileSystem: FileSystem,
  /** The Figma file the pages came from, or empty when the manifest named no well-formed one. */
  val fileKey: String = "",
) {
  private val byId: Map<String, BackdropPage> = pages.associateBy { it.id }

  fun page(pageId: String): BackdropPage? = byId[pageId]

  /** PNG bytes of a previously advertised page's backdrop, or null when it can't be read. */
  fun image(pageId: String): ByteArray? {
    val page = byId[pageId] ?: return null
    val path = imagePath(page) ?: return null
    return runCatching { fileSystem.read(path) { readByteArray() } }.getOrNull()
  }

  /** The image's on-disk path, resolved against the manifest's own directory as the schema says. */
  private fun imagePath(page: BackdropPage): Path? {
    if (!ServeDesignReferenceStore.isSafeRelativePath(page.image.uri)) return null
    val candidate = root / PageBackdropManifest.DIRECTORY / page.image.uri.toPath()
    return candidate.takeIf { fileSystem.exists(it) }
  }

  companion object {
    private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,160}")
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    private val JSON = Json { ignoreUnknownKeys = true }

    /** An empty store — the state every host without a backdrop manifest is in. */
    fun empty(): ServePageBackdropStore =
      ServePageBackdropStore("/".toPath(), emptyList(), SystemFileSystem)

    fun load(bundleDir: File, fileSystem: FileSystem = SystemFileSystem): ServePageBackdropStore {
      val root = bundleDir.toOkioPath()
      val manifestPath = root / PageBackdropManifest.DIRECTORY / PageBackdropManifest.INDEX_FILE
      val manifest =
        runCatching {
            if (!fileSystem.exists(manifestPath)) return@runCatching null
            JSON.decodeFromString<PageBackdropManifest>(
              fileSystem.read(manifestPath) { readUtf8() }
            )
          }
          .getOrNull() ?: return empty()

      // Drop a page whose backdrop isn't a readable PNG. Checked at load rather than at draw time
      // so the viewer is never offered a page that can only paint a broken image.
      val staged = ServePageBackdropStore(root, sanitize(manifest), fileSystem)
      return ServePageBackdropStore(
        root = root,
        pages = staged.pages.filter { staged.hasPng(it) },
        fileSystem = fileSystem,
        fileKey = manifest.fileKey.takeIf(::isSafeFileKey).orEmpty(),
      )
    }

    /**
     * The pages of [manifest] this server is willing to draw, in the producer's order.
     *
     * Shared with [ServeCatalogStore]'s staging path so a malformed page is rejected *before* it is
     * written into the staging tree, not only when it is read back — the same split
     * [ServeParityActivityStore.sanitize] uses.
     */
    fun sanitize(manifest: PageBackdropManifest): List<BackdropPage> {
      if (manifest.version < 1 || manifest.version > PageBackdropManifest.VERSION)
        return emptyList()
      val seen = HashSet<String>()
      return manifest.pages
        .filter { page -> isDrawable(page) && seen.add(page.id) }
        .map { page ->
          page.copy(
            placements =
              page.placements.filter(::isDrawable).map { placement ->
                // An unrecognised link method reads as unlinked rather than as a trusted link. The
                // four methods are the contract; a fifth would otherwise be drawn in the "linked"
                // colour and counted as coverage this catalog can't actually claim.
                if (placement.link in BackdropPlacement.LINK_METHODS) placement
                else placement.copy(link = BackdropPlacement.LINK_UNLINKED)
              }
          )
        }
    }

    /** A Figma file key is URL-safe alphanumerics; anything else is not a key we will link to. */
    fun isSafeFileKey(value: String): Boolean = Regex("[A-Za-z0-9_-]{1,64}").matches(value)

    /**
     * `.png` is **reserved**, because the image comes off the same route as the view with that
     * suffix. A page legitimately id'd `home.png` would be unreachable — `/pages/home.png` reads as
     * "the image of the page `home`" — so it is refused here rather than published and half-broken.
     * Reserving the suffix keeps the URL shape; a separate asset path would only move the
     * ambiguity.
     */
    private fun isDrawable(page: BackdropPage): Boolean =
      SAFE_ID.matches(page.id) &&
        !page.id.endsWith(".png", ignoreCase = true) &&
        page.frame.width.isPositiveFinite() &&
        page.frame.height.isPositiveFinite() &&
        page.image.scale.isPositiveFinite() &&
        ServeDesignReferenceStore.isSafeRelativePath(page.image.uri)

    private fun isDrawable(placement: BackdropPlacement): Boolean =
      placement.bounds.x.isFinite() &&
        placement.bounds.y.isFinite() &&
        placement.bounds.width.isPositiveFinite() &&
        placement.bounds.height.isPositiveFinite() &&
        placement.depth >= 0

    private fun Double.isPositiveFinite(): Boolean = isFinite() && this > 0.0
  }

  /**
   * Whether the page's backdrop exists and starts with the PNG signature. Only the first bytes are
   * read: this runs for every page at load, and the whole image is wanted only when one is opened.
   */
  private fun hasPng(page: BackdropPage): Boolean {
    val path = imagePath(page) ?: return false
    val head =
      runCatching { fileSystem.read(path) { readByteArray(PNG_SIGNATURE.size.toLong()) } }
        .getOrNull() ?: return false
    return head.size == PNG_SIGNATURE.size &&
      PNG_SIGNATURE.indices.all { head[it] == PNG_SIGNATURE[it] }
  }
}
