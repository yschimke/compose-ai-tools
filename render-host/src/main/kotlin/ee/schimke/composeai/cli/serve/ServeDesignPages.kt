package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.designpages.DesignPage
import ee.schimke.composeai.designpages.DesignPagesJson
import ee.schimke.composeai.designpages.DesignPagesManifest
import ee.schimke.composeai.designpages.PageAsset
import ee.schimke.composeai.designpages.PageImage
import ee.schimke.composeai.designpages.PageLayerPlacement
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
public class ServeDesignPageStore
private constructor(
  public val pages: List<DesignPage>,
  /** Sanitized markup per page id, ready to inline. Built at load; see the class comment. */
  private val markup: Map<String, String>,
  private val manifest: DesignPagesManifest? = null,
  /**
   * Shared backplates that survived verification, by content hash.
   *
   * Only the assets whose FILES were checked — not merely the records the manifest declared. See
   * [verifiedAssets].
   */
  private val assets: Map<String, PageAsset> = emptyMap(),
) {
  private val byId: Map<String, DesignPage> = pages.associateBy { it.id }

  /** The Figma file the pages came from, or empty when the manifest named no well-formed one. */
  public val fileKey: String = manifest?.fileKey?.takeIf(::isSafeFileKey).orEmpty()

  public fun page(pageId: String): DesignPage? = byId[pageId]

  /**
   * Sanitized SVG for a previously advertised page, or null.
   *
   * The same string backs both the inline stage and the `/pages/<id>.svg` asset route. Serving the
   * *sanitized* bytes on the asset route as well is deliberate: a consumer that fetched the raw
   * export from the catalog branch would get markup this server has already judged unsafe to
   * inline, and shipping two different answers for one URL is how a check gets bypassed.
   */
  public fun svg(pageId: String): String? = markup[pageId]

  /**
   * The backplates to paint beneath [page]'s export, in paint order.
   *
   * Placements naming an asset that did not survive verification are dropped, so a caller can draw
   * this list without re-checking anything. A page whose backdrop went missing still draws — the
   * same fail-soft posture the rest of this surface takes, and the right one: a sheet missing a
   * plate is worth showing, a sheet that refuses to render because a plate is missing is not.
   */
  public fun background(page: DesignPage): List<PageLayerPlacement> =
    page.background.filter { it.isWellFormed && assets.containsKey(it.asset) }

  /** A verified backplate by its content hash, or null. The asset route's only entry point. */
  public fun asset(id: String): PageAsset? = assets[id]

  /** Every verified backplate, for a caller staging or enumerating the bundle. */
  public fun assets(): Collection<PageAsset> = assets.values

  /**
   * The design ref for [node] — the producer's own, or the one it would have written.
   *
   * Delegates to [DesignPagesManifest.refFor] rather than reading [PageNode.ref] directly, so a
   * node with no ref can still deep-link back into the design tool. That matters most for an
   * *unlinked* node, where the design-tool link is the only one there is.
   */
  public fun refFor(node: PageNode): String = manifest?.refFor(node) ?: node.ref.orEmpty()

  public companion object {
    /** Directory (bundle-relative) the manifest and its cached SVGs live in. */
    public const val DIRECTORY: String = "pages"

    public const val INDEX_FILE: String = "index.json"

    /**
     * How many nodes one page may carry. The kit's densest definition sheet — `Buttons` — holds a
     * few hundred component nodes; this is above that and far below anything that would turn one
     * page into an enormous response. Every node becomes a list row and a hotspot.
     */
    public const val MAX_NODES_PER_PAGE: Int = 500

    private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,160}")

    /** Suffixes that name something ABOUT a page on its own route. See [isDrawable]. */
    private val RESERVED_SUFFIXES = listOf(".svg", ".json")

    private val SVG_SIGNATURE =
      Regex("""\A\s*(?:<\?xml[^>]*\?>\s*)?(?:<!--.*?-->\s*)*<svg\b""", RegexOption.DOT_MATCHES_ALL)

    /** An empty store — the state every host without a page manifest is in. */
    public fun empty(): ServeDesignPageStore = ServeDesignPageStore(emptyList(), emptyMap())

    public fun load(
      bundleDir: File,
      fileSystem: FileSystem = SystemFileSystem,
    ): ServeDesignPageStore {
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
        assets = verifiedAssets(root, manifest, fileSystem),
      )
    }

    /**
     * The manifest's shared backplates whose FILES are what the records claim, by content hash.
     *
     * Three checks, and the order is the point — each one is cheaper than the next and refuses more
     * than it costs:
     *
     * 1. **The declaration** ([PageAsset.isWellFormed]) and the path, before any I/O. A record
     *    claiming 40000x40000, or naming `../../etc/passwd`, never reaches the filesystem.
     * 2. **The signature**, from the first bytes. A file that does not open as the format it claims
     *    is refused without being decoded — which is what keeps a mislabelled payload out of the
     *    image decoder rather than trusting it to cope.
     * 3. **The size**, against the declaration. Cheap, and it catches the truncated download that
     *    would otherwise reach a decoder as a malformed image.
     *
     * The content hash is deliberately NOT recomputed here. It is verified at publish time, where
     * the bytes are written, and hashing every backplate on every catalog load would cost megabytes
     * of I/O per page for a check that protects against a delivery branch disagreeing with itself
     * rather than against anything a reader can act on. The path, signature and size checks are
     * what stop this lane serving something it should not; see the class comment on where the trust
     * boundary sits.
     *
     * Fail-soft per asset, like everything else here: one bad plate costs its own placements, never
     * the page and never the manifest.
     */
    private fun verifiedAssets(
      root: Path,
      manifest: DesignPagesManifest,
      fileSystem: FileSystem,
    ): Map<String, PageAsset> {
      val verified = LinkedHashMap<String, PageAsset>()
      for (asset in manifest.assetsById.values) {
        if (!ServeDesignReferenceStore.isSafeRelativePath(asset.uri)) continue
        val path = root / DIRECTORY / asset.uri.toPath()
        if (!fileSystem.exists(path)) continue
        val head =
          runCatching { fileSystem.read(path) { readByteArray(SIGNATURE_BYTES.toLong()) } }
            .getOrNull() ?: continue
        if (!opensAs(asset.format, head)) continue
        val size = runCatching { fileSystem.metadata(path).size }.getOrNull() ?: continue
        if (size != asset.bytes) continue
        verified[asset.id] = asset
      }
      return verified
    }

    /** Enough bytes for every signature below; a WEBP header needs twelve. */
    private const val SIGNATURE_BYTES: Int = 12

    /**
     * Whether [head] opens as [format].
     *
     * Only the inert raster formats the contract admits. An unknown format is refused rather than
     * waved through — the same posture [isDrawable] takes on a page's own image format, and for the
     * same reason: a consumer that cannot verify what it is about to serve should not serve it.
     */
    private fun opensAs(format: String, head: ByteArray): Boolean =
      when (format.lowercase()) {
        PageAsset.PNG ->
          head.size >= 8 &&
            head[0] == 0x89.toByte() &&
            head[1] == 'P'.code.toByte() &&
            head[2] == 'N'.code.toByte() &&
            head[3] == 'G'.code.toByte() &&
            head[4] == 0x0D.toByte() &&
            head[5] == 0x0A.toByte() &&
            head[6] == 0x1A.toByte() &&
            head[7] == 0x0A.toByte()
        PageAsset.JPEG ->
          head.size >= 3 &&
            head[0] == 0xFF.toByte() &&
            head[1] == 0xD8.toByte() &&
            head[2] == 0xFF.toByte()
        PageAsset.WEBP ->
          head.size >= 12 &&
            String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(head, 8, 4, Charsets.US_ASCII) == "WEBP"
        else -> false
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
    public fun drawablePages(manifest: DesignPagesManifest): List<DesignPage> {
      if (!manifest.isSupported) return emptyList()
      val seen = HashSet<String>()
      return manifest.pages
        .filter { page -> isDrawable(page) && seen.add(page.id) }
        .map { page -> page.copy(nodes = page.nodes.filter(::isDrawable).take(MAX_NODES_PER_PAGE)) }
    }

    /** A Figma file key is URL-safe alphanumerics; anything else is not a key we will link to. */
    public fun isSafeFileKey(value: String): Boolean = Regex("[A-Za-z0-9_-]{1,64}").matches(value)

    /**
     * `.svg` and `.json` are **reserved**, because the export and the page's data come off the same
     * route as the view with those suffixes. A page legitimately id'd `shape.svg` would be
     * unreachable — `/pages/shape.svg` reads as "the export of the page `shape`" — so it is refused
     * here rather than published and half-broken. Reserving the suffixes keeps the URL shape; a
     * separate asset path would only move the ambiguity.
     */
    private fun isDrawable(page: DesignPage): Boolean =
      SAFE_ID.matches(page.id) &&
        RESERVED_SUFFIXES.none { page.id.endsWith(it, ignoreCase = true) } &&
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
// Public rather than `internal` since the move to `:render-host`: `internal` is module-scoped,
// and the `:server` call sites are in a different module now. Not a widened API by intent.
public val PageNodeLink.wire: String
  get() =
    when (this) {
      PageNodeLink.CODE_CONNECT -> "code-connect"
      PageNodeLink.MANIFEST -> "manifest"
      PageNodeLink.CONVENTION -> "convention"
      PageNodeLink.UNLINKED -> "unlinked"
    }
