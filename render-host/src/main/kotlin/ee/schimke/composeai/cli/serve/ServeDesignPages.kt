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
 * The serve host's view of a catalog's design pages: whole specimen sheets from the design file,
 * cached as SVG, with the node id of every component on them — the sheet-level counterpart of
 * [ServeDesignReferenceStore]. Unlinked nodes (shapes nothing implements) are findings.
 *
 * SVG is sanitized here ([SvgSanitizer], once at load) because this is the trust boundary: the
 * markup is inlined into served pages and catalogs are third-party data.
 *
 * Fail-soft like [ServeDesignReferenceStore]: any bad page, path or export drops that page (or the
 * manifest), never the catalog's previews. Manifest strings are untrusted: [ServeWeb] escapes them,
 * and Figma links are reassembled from a validated key and node id ([ServeFigmaSpec]), never taken
 * from the file.
 */
public class ServeDesignPageStore
private constructor(
  public val pages: List<DesignPage>,
  /** Sanitized markup per page id, ready to inline. Built at load; see the class comment. */
  private val markup: Map<String, String>,
  private val manifest: DesignPagesManifest? = null,
  /** Shared backplates whose files passed verification, by content hash ([verifiedAssets]). */
  private val assets: Map<String, PageAsset> = emptyMap(),
) {
  private val byId: Map<String, DesignPage> = pages.associateBy { it.id }

  /** The Figma file the pages came from, or empty when the manifest named no well-formed one. */
  public val fileKey: String = manifest?.fileKey?.takeIf(::isSafeFileKey).orEmpty()

  public fun page(pageId: String): DesignPage? = byId[pageId]

  /**
   * Sanitized SVG for an advertised page, or null. The asset route serves these same sanitized
   * bytes, so one URL never has two answers.
   */
  public fun svg(pageId: String): String? = markup[pageId]

  /**
   * The backplates to paint beneath [page], in order, minus any that failed verification. A page
   * missing a plate still draws.
   */
  public fun background(page: DesignPage): List<PageLayerPlacement> =
    page.background.filter { it.isWellFormed && assets.containsKey(it.asset) }

  /** A verified backplate by its content hash, or null. The asset route's only entry point. */
  public fun asset(id: String): PageAsset? = assets[id]

  /** Every verified backplate, for a caller staging or enumerating the bundle. */
  public fun assets(): Collection<PageAsset> = assets.values

  /**
   * The design ref for [node]: the producer's, or the one [DesignPagesManifest.refFor] derives, so
   * unlinked nodes can still deep-link into the design tool.
   */
  public fun refFor(node: PageNode): String = manifest?.refFor(node) ?: node.ref.orEmpty()

  public companion object {
    /** Directory (bundle-relative) the manifest and its cached SVGs live in. */
    public const val DIRECTORY: String = "pages"

    public const val INDEX_FILE: String = "index.json"

    /**
     * Max nodes per page (each becomes a row and a hotspot); above the densest kit sheet, far below
     * a huge response.
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

      // Sanitize at load and drop pages whose export doesn't survive, so the viewer never gets an
      // empty stage.
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
     * The manifest's backplates whose files match their records, by content hash. Checks, cheapest
     * first:
     * 1. the declaration ([PageAsset.isWellFormed]) and path, before any I/O;
     * 2. the format signature from the first bytes, so mislabelled payloads never reach a decoder;
     * 3. the size against the declaration (catches truncation).
     *
     * The content hash isn't recomputed (verified at publish; too costly per load). Fail-soft per
     * asset.
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
     * Whether [head] opens as [format]; only the admitted inert raster formats, unknown ones
     * refused.
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
      // Cheap regex before the DOM parse.
      if (!SVG_SIGNATURE.containsMatchIn(text)) return null
      return SvgSanitizer.sanitize(text)
    }

    /**
     * The pages of [manifest] this server will draw, in producer order. Shared with
     * [ServeCatalogStore]'s staging so bad pages are rejected before being staged.
     */
    public fun drawablePages(manifest: DesignPagesManifest): List<DesignPage> {
      if (!manifest.isSupported) return emptyList()
      val seen = HashSet<String>()
      return manifest.pages
        .filter { page -> isDrawable(page) && seen.add(page.id) }
        .map { page ->
          page
            .newBuilder()
            .also { it.nodes = page.nodes.filter(::isDrawable).take(MAX_NODES_PER_PAGE) }
            .build()
        }
    }

    /** A Figma file key is URL-safe alphanumerics; anything else is not a key we will link to. */
    public fun isSafeFileKey(value: String): Boolean = Regex("[A-Za-z0-9_-]{1,64}").matches(value)

    /**
     * `.svg` and `.json` suffixes are reserved for the export and data routes (`/pages/shape.svg`
     * is the export of page `shape`), so page ids ending in them are refused.
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

    /** Drawable only with a node id: it is the only geometry the contract carries. */
    private fun isDrawable(node: PageNode): Boolean = node.nodeId.isNotBlank() && node.depth >= 0

    private fun Double.isPositiveFinite(): Boolean = isFinite() && this > 0.0
  }
}

/**
 * The contract's spelling of a link method (`code-connect`, `manifest`, …) for `data-link`
 * attributes and the legend, taken from `@SerialName` so CSS and wire can't drift.
 */
// Public because `:server` call sites live in another module; not a widened API by intent.
public val PageNodeLink.wire: String
  get() =
    when (this) {
      PageNodeLink.CODE_CONNECT -> "code-connect"
      PageNodeLink.MANIFEST -> "manifest"
      PageNodeLink.CONVENTION -> "convention"
      PageNodeLink.UNLINKED -> "unlinked"
    }
