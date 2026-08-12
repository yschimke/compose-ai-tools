package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.designparity.BackdropPage
import ee.schimke.composeai.designparity.PageBackdropJson
import ee.schimke.composeai.designparity.PageBackdropManifest
import ee.schimke.composeai.designparity.Placement
import ee.schimke.composeai.designparity.PlacementLink
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

/**
 * The serve host's view of a **page backdrop** — one key screen imported from the design tool as a
 * flat image, plus the rectangle of every component instance on it, each linked back to the code
 * component that implements it.
 *
 * Where [ServeDesignReferenceStore] answers the per-component question ("does this Button match its
 * Figma node?"), this answers the whole-screen one: *here is the Upcoming screen — which of its
 * parts do we implement, where, and do our renders sit right on top of the design?* The parts
 * nothing implements are the point: an unlinked placement is a finding, not an omission.
 *
 * ## The shapes are not declared here
 *
 * They live in [PageBackdropManifest] (`:preview-data-api`), this repo's single mirror of a
 * **foreign contract** — design-parity's `@design-parity/page-backdrop`, released independently to
 * npm, whose schema is the source of truth and whose own fixture pins that mirror in
 * `PageBackdropParseTest`. Re-declaring the shapes here would give one wire contract two mirrors in
 * one repository, which is precisely what that module's header rules out. So this file adds only
 * what is *serve-specific*:
 *
 * - which pages this host is willing to **draw** (a route-safe id, a usable frame, a readable PNG);
 * - where the bytes live on disk, and how to read them without escaping the manifest's directory.
 *
 * Two properties of the contract the server leans on, restated because they are easy to "improve"
 * wrongly:
 *
 * - **A rect is in frame-local design units, never image pixels.** The backdrop PNG is exported at
 *   some scale, and pinning geometry to the unscaled frame means a re-export at another resolution
 *   doesn't invalidate the manifest. The viewer positions by ratio, so no density arithmetic
 *   happens anywhere on this side.
 * - **[Placement.previewId] exists for a renderer specifically.** It is what lets the server draw
 *   the component *at the placement's size* instead of scaling a screenshot. When a catalog
 *   publishes backdrops, that id is the **serve** preview id, which is what `design-pages.mjs`
 *   remaps on the way into the bundle.
 *
 * ## Failure posture and trust
 *
 * Fail-soft throughout, like [ServeDesignReferenceStore] and [ServeParityActivityStore]: a missing
 * file, an unsupported version, a malformed page, or a traversing image path drops that record — or
 * the whole manifest — and the catalog serves its grid exactly as before. A backdrop is an
 * enhancement; it must never cost a catalog its previews.
 *
 * A catalog is third-party data, and a manifest carries **free text authored in the design tool**:
 * layer names like `Button/Primary`. Nothing here is trusted. Every string is HTML-escaped at
 * render time by [ServeWeb], and the outbound Figma deep link is *reassembled* from a validated
 * file key and node id against a literal origin ([ServeFigmaSpec]) rather than taken from the file,
 * so a manifest declaring `javascript:…` yields no link instead of an attacker-chosen href.
 */
class ServePageBackdropStore
private constructor(
  private val root: Path,
  val pages: List<BackdropPage>,
  private val fileSystem: FileSystem,
  /** The manifest as read, so callers get its [PageBackdropManifest.refFor] fallback. */
  private val manifest: PageBackdropManifest? = null,
) {
  private val byId: Map<String, BackdropPage> = pages.associateBy { it.id }

  /** The Figma file the pages came from, or empty when the manifest named no well-formed one. */
  val fileKey: String = manifest?.fileKey?.takeIf(::isSafeFileKey).orEmpty()

  fun page(pageId: String): BackdropPage? = byId[pageId]

  /**
   * The design ref for [placement] — the producer's own, or the one it would have written.
   *
   * Delegates to [PageBackdropManifest.refFor] rather than reading [Placement.ref] directly: the
   * field is newer than the contract, so a manifest from an older producer carries none, and a
   * hotspot with no ref can't deep-link back into the design tool — most visibly for an *unlinked*
   * placement, where that link is the only one there is. Reconstructing it from the file key and
   * node id is exact, not a guess.
   */
  fun refFor(placement: Placement): String = manifest?.refFor(placement) ?: placement.ref.orEmpty()

  /** PNG bytes of a previously advertised page's backdrop, or null when it can't be read. */
  fun image(pageId: String): ByteArray? {
    val page = byId[pageId] ?: return null
    val path = imagePath(page) ?: return null
    return runCatching { fileSystem.read(path) { readByteArray() } }.getOrNull()
  }

  /** The image's on-disk path, resolved against the manifest's own directory as the schema says. */
  private fun imagePath(page: BackdropPage): Path? {
    if (!ServeDesignReferenceStore.isSafeRelativePath(page.image.uri)) return null
    val candidate = root / DIRECTORY / page.image.uri.toPath()
    return candidate.takeIf { fileSystem.exists(it) }
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

  companion object {
    /** Directory (bundle-relative) the manifest and its backdrop PNGs live in. */
    const val DIRECTORY = "pages"

    const val INDEX_FILE = "index.json"

    /**
     * How many instances one screen may carry. The densest real screen in the Material 3 kit has
     * eleven; this is orders of magnitude above any honest import and exists so a malformed or
     * hostile manifest can't turn one page into an enormous response.
     */
    const val MAX_PLACEMENTS_PER_PAGE = 500

    private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,160}")
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    /** An empty store — the state every host without a backdrop manifest is in. */
    fun empty(): ServePageBackdropStore =
      ServePageBackdropStore("/".toPath(), emptyList(), SystemFileSystem)

    fun load(bundleDir: File, fileSystem: FileSystem = SystemFileSystem): ServePageBackdropStore {
      val root = bundleDir.toOkioPath()
      val manifestPath = root / DIRECTORY / INDEX_FILE
      val manifest =
        runCatching {
            if (!fileSystem.exists(manifestPath)) return@runCatching null
            PageBackdropJson.decodeFromString<PageBackdropManifest>(
              fileSystem.read(manifestPath) { readUtf8() }
            )
          }
          .getOrNull()
          ?.takeIf { it.isSupported } ?: return empty()

      // Drop a page whose backdrop isn't a readable PNG. Checked at load rather than at draw time
      // so the viewer is never offered a page that can only paint a broken image.
      val staged = ServePageBackdropStore(root, drawablePages(manifest), fileSystem, manifest)
      return ServePageBackdropStore(
        root = root,
        pages = staged.pages.filter { staged.hasPng(it) },
        fileSystem = fileSystem,
        manifest = manifest,
      )
    }

    /**
     * The pages of [manifest] this server is willing to draw, in the producer's order.
     *
     * Shared with [ServeCatalogStore]'s staging path so a malformed page is rejected *before* it is
     * written into the staging tree, not only when it is read back — the same split
     * [ServeParityActivityStore.sanitize] uses.
     */
    fun drawablePages(manifest: PageBackdropManifest): List<BackdropPage> {
      if (!manifest.isSupported) return emptyList()
      val seen = HashSet<String>()
      return manifest.pages
        .filter { page -> isDrawable(page) && seen.add(page.id) }
        .map { page ->
          // Placements are capped too, not just pages. A page cap alone bounds the number of
          // backdrop PNGs but nothing about a single page's manifest entry — and every placement
          // becomes a hotspot, an overlay image and a list row, so an absurd count is a huge
          // response and a huge DOM rather than a useful screen. A real key screen has tens.
          page.copy(placements = page.placements.filter(::isDrawable).take(MAX_PLACEMENTS_PER_PAGE))
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
        page.id != "." &&
        page.id != ".." &&
        page.frame.width.isPositiveFinite() &&
        page.frame.height.isPositiveFinite() &&
        page.image.scale.isPositiveFinite() &&
        ServeDesignReferenceStore.isSafeRelativePath(page.image.uri)

    private fun isDrawable(placement: Placement): Boolean =
      placement.bounds.x.isFinite() &&
        placement.bounds.y.isFinite() &&
        placement.bounds.width.isPositiveFinite() &&
        placement.bounds.height.isPositiveFinite() &&
        placement.depth >= 0

    private fun Double.isPositiveFinite(): Boolean = isFinite() && this > 0.0
  }
}

/**
 * The contract's own spelling of a link method — `code-connect`, `manifest`, … — for the places the
 * *value* has to leave Kotlin: a `data-link` attribute the stylesheet colours on, and the legend
 * beside it. Taken from the enum's `@SerialName` rather than `name.lowercase()` so the CSS and the
 * wire can never drift apart on a hyphen.
 */
internal val PlacementLink.wire: String
  get() =
    when (this) {
      PlacementLink.CODE_CONNECT -> "code-connect"
      PlacementLink.MANIFEST -> "manifest"
      PlacementLink.CONVENTION -> "convention"
      PlacementLink.UNLINKED -> "unlinked"
    }

/** True when nothing in the repo claims this part of the screen. The interesting case. */
internal val Placement.isUnlinked: Boolean
  get() = link == PlacementLink.UNLINKED

/**
 * The preview this placement may be drawn with, or null.
 *
 * Gated on the **link** as well as the id, deliberately. A manifest can carry `link: unlinked`
 * alongside a stale `previewId`, and drawing that would put a render *and* a component-viewer link
 * on a rectangle the same page paints dashed-red and labels "no code behind this" — the two halves
 * of the page contradicting each other. The link is the claim; the id is only how to draw it.
 */
internal val Placement.renderablePreviewId: String?
  get() = if (isUnlinked) null else previewId
