package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.designpages.DesignPage
import ee.schimke.composeai.designpages.DesignPagesJson
import ee.schimke.composeai.designpages.DesignPagesManifest
import ee.schimke.composeai.designpages.PageImage
import ee.schimke.composeai.designpages.PageNode
import ee.schimke.composeai.designpages.PageNodeLink
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

/**
 * The serve host's view of the catalog's **design pages** — whole specimen sheets from the design
 * file, cached as SVG, with the node id of every component on them.
 *
 * Where [ServeDesignReferenceStore] answers the per-component question ("does this Button match its
 * Figma node?"), this answers the sheet-level one: *here is the kit's own Shape page — which of
 * these 35 shapes do we implement, and does our render sit right where the design drew it?* The
 * shapes nothing implements are the point: an unlinked node is a finding, not an omission.
 *
 * ## Why the SVG is sanitized here and not at publish time
 *
 * Because *here* is the trust boundary. The markup is inlined into a served page — that is what
 * lets the viewer hide the design's own drawing of a node and put our render in its place — and a
 * catalog is third-party data all the way down ([ServeCatalogStore] stages one on that assumption).
 * A publish-time check would protect only the catalogs this repo happens to publish; the server
 * reads branches it did not write. [SvgSanitizer] runs once at load rather than per request: a
 * specimen sheet is hundreds of kilobytes, and parsing it on every open would be the surface's
 * whole cost.
 *
 * ## Failure posture
 *
 * Fail-soft throughout, like [ServeDesignReferenceStore] and [ServeParityActivityStore]: a missing
 * file, an unsupported version, a malformed page, a traversing image path or an export that does
 * not survive sanitizing drops that page — or the whole manifest — and the catalog serves its grid
 * exactly as before. A page view is an enhancement; it must never cost a catalog its previews.
 *
 * A manifest carries **free text authored in the design tool**: layer names like `Shape=Circle`.
 * Nothing here is trusted. Every string is HTML-escaped at render time by [ServeWeb], and the
 * outbound Figma deep link is *reassembled* from a validated file key and node id against a literal
 * origin ([ServeFigmaSpec]) rather than taken from the file, so a manifest declaring `javascript:…`
 * yields no link instead of an attacker-chosen href.
 */
class ServeDesignPageStore
private constructor(
  val pages: List<DesignPage>,
  /** Sanitized markup per page id, ready to inline. Built at load; see the class comment. */
  private val markup: Map<String, String>,
  private val manifest: DesignPagesManifest? = null,
) {
  private val byId: Map<String, DesignPage> = pages.associateBy { it.id }

  /** The Figma file the pages came from, or empty when the manifest named no well-formed one. */
  val fileKey: String = manifest?.fileKey?.takeIf(::isSafeFileKey).orEmpty()

  fun page(pageId: String): DesignPage? = byId[pageId]

  /**
   * Sanitized SVG for a previously advertised page, or null.
   *
   * The same string backs both the inline stage and the `/pages/<id>.svg` asset route. Serving the
   * *sanitized* bytes on the asset route as well is deliberate: a consumer that fetched the raw
   * export from the catalog branch would get markup this server has already judged unsafe to
   * inline, and shipping two different answers for one URL is how a check gets bypassed.
   */
  fun svg(pageId: String): String? = markup[pageId]

  /**
   * The design ref for [node] — the producer's own, or the one it would have written.
   *
   * Delegates to [DesignPagesManifest.refFor] rather than reading [PageNode.ref] directly, so a
   * node with no ref can still deep-link back into the design tool. That matters most for an
   * *unlinked* node, where the design-tool link is the only one there is.
   */
  fun refFor(node: PageNode): String = manifest?.refFor(node) ?: node.ref.orEmpty()

  companion object {
    /** Directory (bundle-relative) the manifest and its cached SVGs live in. */
    const val DIRECTORY = "pages"

    const val INDEX_FILE = "index.json"

    /**
     * How many nodes one page may carry. The kit's densest definition sheet — `Buttons` — holds a
     * few hundred component nodes; this is above that and far below anything that would turn one
     * page into an enormous response. Every node becomes a list row and a hotspot.
     */
    const val MAX_NODES_PER_PAGE = 500

    private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,160}")
    private val SVG_SIGNATURE =
      Regex("""\A\s*(?:<\?xml[^>]*\?>\s*)?(?:<!--.*?-->\s*)*<svg\b""", RegexOption.DOT_MATCHES_ALL)

    /** An empty store — the state every host without a page manifest is in. */
    fun empty(): ServeDesignPageStore = ServeDesignPageStore(emptyList(), emptyMap())

    fun load(bundleDir: File, fileSystem: FileSystem = SystemFileSystem): ServeDesignPageStore {
      val root = bundleDir.toOkioPath()
      val manifestPath = root / DIRECTORY / INDEX_FILE
      val manifest =
        runCatching {
          if (!fileSystem.exists(manifestPath)) return@runCatching null
          DesignPagesJson.decodeFromString<DesignPagesManifest>(
            fileSystem.read(manifestPath) { readUtf8() }
          )
        }
          .getOrNull()
          ?.takeIf { it.isSupported } ?: return empty()

      // Sanitize at load, and drop a page whose export doesn't survive it. Checked here rather than
      // at draw time so the viewer is never offered a page that can only paint an empty stage.
      val markup = LinkedHashMap<String, String>()
      for (page in drawablePages(manifest)) {
        val svg = readSvg(root, page, fileSystem) ?: continue
        markup[page.id] = svg
      }
      return ServeDesignPageStore(
        pages = drawablePages(manifest).filter { markup.containsKey(it.id) },
        markup = markup,
        manifest = manifest,
      )
    }

    private fun readSvg(root: Path, page: DesignPage, fileSystem: FileSystem): String? {
      if (!ServeDesignReferenceStore.isSafeRelativePath(page.image.uri)) return null
      val path = root / DIRECTORY / page.image.uri.toPath()
      if (!fileSystem.exists(path)) return null
      val text = runCatching { fileSystem.read(path) { readUtf8() } }.getOrNull() ?: return null
      // Cheap shape check before the DOM parse, for the same reason the PNG lane checked its
      // signature: a file that isn't an SVG at all should cost a regex, not a parser.
      if (!SVG_SIGNATURE.containsMatchIn(text)) return null
      return SvgSanitizer.sanitize(text)
    }

    /**
     * The pages of [manifest] this server is willing to draw, in the producer's order.
     *
     * Shared with [ServeCatalogStore]'s staging path so a malformed page is rejected *before* it is
     * written into the staging tree, not only when it is read back — the same split
     * [ServeParityActivityStore.sanitize] uses.
     */
    fun drawablePages(manifest: DesignPagesManifest): List<DesignPage> {
      if (!manifest.isSupported) return emptyList()
      val seen = HashSet<String>()
      return manifest.pages
        .filter { page -> isDrawable(page) && seen.add(page.id) }
        .map { page -> page.copy(nodes = page.nodes.filter(::isDrawable).take(MAX_NODES_PER_PAGE)) }
    }

    /** A Figma file key is URL-safe alphanumerics; anything else is not a key we will link to. */
    fun isSafeFileKey(value: String): Boolean = Regex("[A-Za-z0-9_-]{1,64}").matches(value)

    /**
     * `.svg` is **reserved**, because the export comes off the same route as the view with that
     * suffix. A page legitimately id'd `shape.svg` would be unreachable — `/pages/shape.svg` reads
     * as "the export of the page `shape`" — so it is refused here rather than published and
     * half-broken. Reserving the suffix keeps the URL shape; a separate asset path would only move
     * the ambiguity.
     */
    private fun isDrawable(page: DesignPage): Boolean =
      SAFE_ID.matches(page.id) &&
        !page.id.endsWith(".svg", ignoreCase = true) &&
        page.id != "." &&
        page.id != ".." &&
        page.image.format.equals(PageImage.SVG, ignoreCase = true) &&
        page.frame.width.isPositiveFinite() &&
        page.frame.height.isPositiveFinite() &&
        ServeDesignReferenceStore.isSafeRelativePath(page.image.uri)

    /**
     * A node is drawable when it can be *found*: the node id is the only geometry this contract
     * carries, so a blank one names nothing in the export and could never be hidden, swapped or
     * pointed at.
     */
    private fun isDrawable(node: PageNode): Boolean = node.nodeId.isNotBlank() && node.depth >= 0

    private fun Double.isPositiveFinite(): Boolean = isFinite() && this > 0.0
  }
}

/**
 * The contract's own spelling of a link method — `code-connect`, `manifest`, … — for the places the
 * *value* has to leave Kotlin: a `data-link` attribute the stylesheet colours on, and the legend
 * beside it. Taken from the enum's `@SerialName` rather than `name.lowercase()` so the CSS and the
 * wire can never drift apart on a hyphen.
 */
internal val PageNodeLink.wire: String
  get() =
    when (this) {
      PageNodeLink.CODE_CONNECT -> "code-connect"
      PageNodeLink.MANIFEST -> "manifest"
      PageNodeLink.CONVENTION -> "convention"
      PageNodeLink.UNLINKED -> "unlinked"
    }
