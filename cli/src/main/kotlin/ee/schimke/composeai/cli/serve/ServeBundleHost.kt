package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.data.overrides.PreviewOverridesPayload
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * A [ServeHost] backed by a **portable bundle** on disk (the `ServeBundle` / WebEmbed layout:
 * `previews/<id>.png` beside an `index.html`), not a daemon. This is the shared/public mode: a
 * pre-rendered bundle is uploaded once and served read-only, with no checkout, build, or render
 * session. Overrides are ignored (the bundle is whatever was baked); there is no live stream lane,
 * so connections transparently use the snapshot fallback that returns these PNGs.
 *
 * Cheap and stateless (just file reads), so the registry pins it resident rather than suspending
 * it.
 */
class ServeBundleHost(
  private val bundleDir: File,
  override val label: String,
  /**
   * Producer-trust verdict for this bundle, attached at ingestion ([ServeBundleStore]) so the API /
   * viewer can badge it. Defaults to `Unverified` for bundles registered without a check (e.g. a
   * `--bundles <dir>` directory, which has no original signed file to verify).
   */
  val trust: BundleVerifier.Verdict = BundleVerifier.Verdict.Unverified("not checked"),
  /**
   * Human display title for a design-system catalog (e.g. "Compose Material 3"), taken from
   * `catalog.json`'s `title`. Null for a plain uploaded bundle (no such metadata). Surfaced on the
   * public server's home index so each system card reads as a name, not a bare id.
   */
  val title: String? = null,
  /**
   * Short one-line descriptor for a catalog card — the underlying library coordinate(s) from
   * `catalog.json`'s `library`. Null when the catalog declares none (or for a plain bundle).
   */
  val subtitle: String? = null,
  /**
   * The stage background surface the catalog declared (`catalog.json`'s `display.surface`) —
   * `"light"` / `"dark"` / null. When `"dark"`, the front door and grid back this system's stickers
   * on a dark stage. Null ⇒ the server falls back to its name-based default.
   */
  val stageSurface: String? = null,
  /**
   * The hero preview the catalog declared (`display.hero`) — a `componentId` (e.g.
   * `"Template/TimeText"`) or a flattened preview id. Resolved against [previews] by
   * [declaredHeroPreviewId]; null ⇒ the server picks a representative itself.
   */
  val declaredHero: String? = null,
  /**
   * The local dir holding a catalog's `figma/<slug>.svg` exports (+ `<slug>.figma-raster/` crops),
   * populated by [ServeCatalogStore]. When set, {@link renderSvg} serves the baked editable vector
   * per preview; null for a plain uploaded bundle (which then 404s the `.svg` lane).
   */
  private val figmaDir: File? = null,
  /**
   * Provenance of a served design-system catalog (the trusted `repo@branch` it was fetched from,
   * when it was generated, and the compose-ai-tools + design-parity versions that produced it),
   * populated by [ServeCatalogStore] from the catalog's `catalog.json` + fetch origin. Null for a
   * plain uploaded bundle (no such metadata). Surfaced on the catalog landing's provenance strip.
   */
  val provenance: ServeWeb.CatalogProvenance? = null,
  /**
   * Why this session is snapshot-only, when it is — populated by [ServeCatalogStore] for the baked
   * host it terminally registers (e.g. a catalog with no `liveBundle`), and left empty for a plain
   * uploaded bundle or for the baked host that merely *fronts* a live daemon (that session isn't
   * degraded). Surfaced by the viewer banner + `/api/previews`. See [ServeDegradation].
   */
  override val degradations: List<ServeDegradation> = emptyList(),
  private val fileSystem: FileSystem = SystemFileSystem,
) : ServeHost {

  // A catalog bundle that carried baked `figma/<slug>.svg` vectors can serve an SVG per preview; a
  // plain uploaded bundle (no figmaDir) 404s the `.svg` lane, so it offers no SVG download link.
  override val hasSvgExport: Boolean = figmaDir != null

  private val previewsDir = File(bundleDir, PREVIEWS_SUBDIR)

  /**
   * Per-preview `state`/`theme` from the catalog's `previews/variants.json` manifest (written by
   * [ServeCatalogStore]). Empty for a plain uploaded bundle that carries no manifest — every
   * preview then stays stateless (null state/theme), preserving the pre-toggle behaviour.
   * Best-effort: an unreadable / malformed manifest degrades to empty rather than failing the host.
   */
  private val variantMeta: Map<String, ServeCatalogStore.VariantMeta> = readVariantMeta()

  override val previews: List<ServePreview> =
    // Walk recursively: a preview id may contain '/', stored as a nested `previews/<id>.png`. Ids
    // are reconstructed relative to `previews/` with '/' separators (matching the bundle layout).
    previewsDir
      .walkTopDown()
      .filter { it.isFile && it.name.endsWith(PNG_SUFFIX) }
      .map { it.relativeTo(previewsDir).invariantSeparatorsPath.removeSuffix(PNG_SUFFIX) }
      .sorted()
      .map { id ->
        val meta = variantMeta[id]
        ServePreview(
          id = id,
          label = id,
          overrides = readOverrides(id),
          // `state` comes only from a `catalog.json`-backed bundle's `variants.json`
          // (`meta.state`).
          // A plain module bundle has no manifest, so an `@OverrideVariant` synthetic preview
          // (`Foo_VARIANT_off`) stays stateless and shows as its own grid card. It is NOT folded
          // here
          // from the id: `ServeWeb`'s state grouping keys off the flattened `__<state>__` catalog
          // id,
          // which a raw `_VARIANT_<name>` id doesn't carry, so marking it as a state would fold it
          // out of the grid without a switcher link to reach it (it would vanish). Folding a
          // raw-bundle variant needs `ServeWeb`'s `baseKey`/`stateInvariantKey` to understand the
          // `_VARIANT_` suffix — a separate change. The catalog-served path already folds
          // correctly.
          state = meta?.state,
          theme = meta?.theme,
          props = meta?.props,
          section = meta?.section,
          group = meta?.group,
          catalogOrder = meta?.order,
        )
      }
      .toList()

  /**
   * The catalog's declared hero ([declaredHero]) resolved to one of this host's actual preview ids,
   * or null when nothing was declared / the declaration matches no preview. Accepts a full preview
   * id, or a `componentId` / preview-function name matched against a preview's slug head (the
   * segment before `__`) using the same slug normalisation the exporter used — so a spec can name
   * `"Template/TimeText"` and hit `template-timetext__ideal__…`. The server uses this as the front
   * door hero before falling back to its own representative pick.
   */
  val declaredHeroPreviewId: String? by lazy {
    val hero = declaredHero?.takeIf { it.isNotBlank() } ?: return@lazy null
    val exact = previews.firstOrNull { it.id == hero }
    if (exact != null) return@lazy exact.id
    val wanted = heroSlug(hero)
    previews.firstOrNull { heroSlug(it.id.substringBefore(SLUG_SEPARATOR)) == wanted }?.id
  }

  /**
   * Best-effort read of the catalog's `previews/variants.json` state/theme manifest. Mirrors
   * [readOverrides] / [declaredThemes]: absent or unparseable → empty map, so a plain bundle (no
   * manifest) simply has no state/theme metadata.
   */
  private fun readVariantMeta(): Map<String, ServeCatalogStore.VariantMeta> {
    val manifest = File(previewsDir, ServeCatalogStore.VARIANTS_FILE).toOkioPath()
    if (!fileSystem.exists(manifest)) return emptyMap()
    return try {
      val text = fileSystem.read(manifest) { readUtf8() }
      OVERRIDES_JSON.decodeFromString(
        MapSerializer(String.serializer(), ServeCatalogStore.VariantMeta.serializer()),
        text,
      )
    } catch (e: Exception) {
      emptyMap()
    }
  }

  /**
   * The app-declared `@ThemeCatalog` themes, read from the bundle's `previews.json` when it carries
   * one (the synthetic `THEME_CATALOG` entries discovery emits). A plain static bundle can't apply
   * a `themeProvider` (no daemon to load the provider), so the viewer shows the App theme selector
   * as a disabled, informational list — mirroring how declared knobs render on a static bundle.
   * Empty when the bundle carries no `previews.json` (a bare `previews/`-only WebEmbed) or declares
   * none.
   */
  override val declaredThemes: List<ServeTheme> = run {
    val previewsJson = File(bundleDir, PREVIEWS_JSON).toOkioPath()
    if (!fileSystem.exists(previewsJson)) return@run emptyList()
    try {
      val text = fileSystem.read(previewsJson) { readUtf8() }
      val manifest =
        OVERRIDES_JSON.decodeFromString(ee.schimke.composeai.cli.PreviewManifest.serializer(), text)
      declaredThemesFromPreviews(manifest.previews)
    } catch (e: Exception) {
      emptyList()
    }
  }

  /**
   * Read the editable knobs carried for [id] in the bundle's `previews/<id>.overrides.json` sidecar
   * (the `compose/overrides` payload the producer packed). Absent / unreadable → no knobs. The host
   * can't re-render (it replays baked PNGs), so [canApplyOverrides] stays false and the viewer
   * shows these as disabled, informational controls.
   */
  private fun readOverrides(
    id: String
  ): List<ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration> {
    val sidecar = File(previewsDir, "$id$OVERRIDES_SUFFIX").toOkioPath()
    if (!fileSystem.exists(sidecar)) return emptyList()
    return try {
      val json = fileSystem.read(sidecar) { readUtf8() }
      OVERRIDES_JSON.decodeFromString(PreviewOverridesPayload.serializer(), json).declarations
    } catch (e: Exception) {
      emptyList()
    }
  }

  private val previewIds: Set<String> = previews.map { it.id }.toHashSet()

  override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
    if (previewId !in previewIds) return RenderOutcome.NotFound
    val png = File(previewsDir, "$previewId$PNG_SUFFIX").toOkioPath()
    if (!fileSystem.exists(png)) return RenderOutcome.NotFound
    return RenderOutcome.Ok(fileSystem.read(png) { readByteArray() })
  }

  /**
   * Serve the baked `compose/figma-svg` export for [previewId] from the catalog's [figmaDir], with
   * its hybrid raster crops inlined so the SVG is self-contained. The SVG is per component **slug**
   * (`figma/<slug>.svg`) and a preview id folds the slug + variant (`<slug>__<variant>`), so the
   * slug is the id up to the first `__`. [SvgOutcome.NotFound] for a plain bundle (no [figmaDir]),
   * an unknown id, or a preview whose component carried no figma-svg. Overrides don't apply
   * (static).
   */
  // Per-preview SVG availability (issue #2352). `hasSvgExport` is true for the whole session as
  // soon
  // as the catalog carries a `figma/` dir, but a specific preview whose component slug has no baked
  // `figma/<slug>.svg` still 404s the `.svg` lane (see `renderSvg`). Gate the viewer's SVG control
  // on
  // the actual file so it isn't offered on a preview that would render "failed". Same slug lookup
  // as
  // `renderSvg`, minus the read.
  override fun hasSvgExportFor(previewId: String): Boolean {
    val figma = figmaDir ?: return false
    if (previewId !in previewIds) return false
    val svgFile =
      File(figma, "${previewId.substringBefore(SLUG_SEPARATOR)}$SVG_SUFFIX").toOkioPath()
    return fileSystem.exists(svgFile)
  }

  override fun renderSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome {
    val figma = figmaDir ?: return SvgOutcome.NotFound
    if (previewId !in previewIds) return SvgOutcome.NotFound
    val svgFile =
      File(figma, "${previewId.substringBefore(SLUG_SEPARATOR)}$SVG_SUFFIX").toOkioPath()
    if (!fileSystem.exists(svgFile)) return SvgOutcome.NotFound
    val svg = fileSystem.read(svgFile) { readUtf8() }
    return SvgOutcome.Ok(
      inlineFigmaRasters(fileSystem, figma.toOkioPath(), svg).encodeToByteArray()
    )
  }

  /**
   * The content-crop that frames [previewId]'s thumbnail to the component box, or `null` when the
   * card should show the raw render (no figma-svg for the slug, unknown id, unreadable files, or a
   * render already tight to the component — see [computeThumbCrop]). Read once from the baked
   * `figma/<slug>.svg` (its root `viewBox` + `translate`) and the render PNG's IHDR dimensions,
   * then memoised: a catalog's baked files don't change under a resident host, and a refresh
   * re-registers a fresh host (dropping this cache), so this stays a couple of small local reads
   * per preview across the whole life of a landing page — no daemon, no per-request re-read.
   */
  fun contentCrop(previewId: String): ContentCrop? =
    cropCache
      .computeIfAbsent(previewId) { java.util.Optional.ofNullable(computeContentCrop(it)) }
      .orElse(null)

  private val cropCache =
    java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<ContentCrop>>()

  private fun computeContentCrop(previewId: String): ContentCrop? {
    val figma = figmaDir ?: return null
    if (previewId !in previewIds) return null
    val svgFile =
      File(figma, "${previewId.substringBefore(SLUG_SEPARATOR)}$SVG_SUFFIX").toOkioPath()
    if (!fileSystem.exists(svgFile)) return null
    val png = File(previewsDir, "$previewId$PNG_SUFFIX").toOkioPath()
    if (!fileSystem.exists(png)) return null
    return try {
      val svg = fileSystem.read(svgFile) { readUtf8() }
      val bytes = fileSystem.read(png) { readByteArray() }
      val (rw, rh) = WebEscaping.pngDimensions(bytes.copyOf(PNG_HEADER_BYTES.toInt()))
      // Union the render's actual non-transparent extent into the crop box so a focus ring or
      // disabled outline drawn outside the layout-derived figma box is never clipped.
      computeThumbCrop(svg, rw, rh, contentBounds = pngAlphaBounds(bytes))
    } catch (e: Exception) {
      null
    }
  }

  /**
   * A bundle has no daemon, so no live lane — callers fall back to the snapshot ([render]) lane.
   */
  override fun subscribeStream(
    previewId: String,
    overrides: PreviewOverrides,
    codec: StreamCodec?,
    maxFps: Int?,
    onUnavailable: ((String) -> Unit)?,
    onFrame: (StreamFrameParams) -> Unit,
  ): StreamHandle? {
    onUnavailable?.invoke("this session serves baked snapshots only (no live daemon)")
    return null
  }

  override fun activeStreamCount(): Int = 0

  override fun close() {
    // Nothing to release — a bundle host owns no daemon or sockets.
  }

  companion object {
    private const val PREVIEWS_SUBDIR = "previews"
    private const val PNG_SUFFIX = ".png"
    /** Bytes of a PNG needed to read its IHDR width/height (8 sig + 4 len + 4 tag + 4 + 4). */
    private const val PNG_HEADER_BYTES = 24L
    private const val SVG_SUFFIX = ".svg"
    /** A preview id folds the component slug and variant as `<slug>__<variant>`. */
    private const val SLUG_SEPARATOR = "__"

    /**
     * Normalise a declared hero (a `componentId` like `"Template/TimeText"` or a preview-function
     * name) to the slug the exporter bakes into preview ids — mirrors `@design-parity`'s `slug()`
     * (non-`[a-zA-Z0-9._-]` → `-`, trim, lowercase), so `display.hero` resolves against the served
     * ids regardless of how the author wrote it.
     */
    private fun heroSlug(value: String): String =
      value.replace(Regex("[^a-zA-Z0-9._-]+"), "-").trim('-').lowercase().ifBlank { "x" }

    private const val OVERRIDES_SUFFIX = ".overrides.json"
    private const val PREVIEWS_JSON = "previews.json"
    private val OVERRIDES_JSON = Json { ignoreUnknownKeys = true }

    /** True when [dir] looks like a servable bundle (a `previews/` tree with at least one PNG). */
    fun looksLikeBundle(dir: File): Boolean {
      val previews = File(dir, PREVIEWS_SUBDIR)
      return previews.isDirectory &&
        previews.walkTopDown().any { it.isFile && it.name.endsWith(PNG_SUFFIX) }
    }
  }
}
