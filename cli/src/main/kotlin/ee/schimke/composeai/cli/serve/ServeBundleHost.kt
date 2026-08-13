package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.data.overrides.PreviewOverridesPayload
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.Serializable
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
   * The catalog's own colour palette, projected onto the serve chrome's CSS custom properties by
   * [ServeThemeCss] from the delivery branch's `tokens.dtcg.json` — so this system's pages are
   * framed in its own colours rather than the built-in indigo shell. Null for a plain uploaded
   * bundle, or a catalog that publishes no (usable) tokens; the pages then keep the built-in
   * chrome.
   */
  val webThemeCss: String? = null,
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
   * The catalog's **source** (repo/ref/module of the Kotlin), from `catalog.json`'s `source` — set
   * by [ServeCatalogStore]. Distinct from [provenance] (the delivery branch): this is what the
   * viewer builds a per-preview GitHub source link from, joining `module` + the preview's
   * module-relative `sourceFile`. Null for a plain uploaded bundle or a catalog that declared no
   * source.
   */
  val catalogSource: ServeWeb.CatalogSource? = null,
  /**
   * Why this session is snapshot-only, when it is — populated by [ServeCatalogStore] for the baked
   * host it terminally registers (e.g. a catalog with no `liveBundle`), and left empty for a plain
   * uploaded bundle or for the baked host that merely *fronts* a live daemon (that session isn't
   * degraded). Surfaced by the viewer banner + `/api/previews`. See [ServeDegradation].
   */
  override val degradations: List<ServeDegradation> = emptyList(),
  /**
   * Catalog previews to list that have **no baked PNG on disk** — the `catalog.json` `deferred[]`
   * records, which CI declared live-only instead of rasterising (issue #2965). Supplied by
   * [ServeCatalogStore] ONLY for the baked host that fronts a live daemon, so each of these ids has
   * a daemon twin that renders it on request; the terminally-registered baked-only host gets none
   * (a card whose every render 404s is worse than an absent one). They join [previews] with their
   * `previews/variants.json` metadata like any other catalog preview — so they sit in the right
   * tab, group and order — and are re-exposed as [liveOnlyPreviewIds] for the live composite's
   * routing. [render] still returns [RenderOutcome.NotFound] for them: this host has no pixels, and
   * it is the composite's job to reach the daemon.
   */
  liveOnly: List<String> = emptyList(),
  /**
   * Ids this catalog publishes a baked PNG for, **whether or not those pixels are local yet**.
   *
   * A plain uploaded bundle passes none and keeps the original identity model: its previews are
   * exactly the PNGs under `previews/`. A **catalog** passes its full declared set, because
   * [ServeCatalogStore] no longer downloads every image before publishing — `catalog.json` alone
   * names every card, and fetching a couple of hundred PNGs one round-trip at a time is what kept a
   * catalog invisible for minutes after its metadata had already arrived. Missing pixels arrive via
   * [fetchBakedPng] on first use.
   */
  declaredBaked: List<String> = emptyList(),
  /**
   * Fetch one declared preview's baked PNG from the catalog's delivery branch, or null when it
   * can't be had. Supplied by [ServeCatalogStore] so that network policy — the SSRF gate, the
   * per-asset size cap, the test seam — stays in the one place that owns it; this host only ever
   * calls it. Null for a plain bundle, whose pixels are all local already, which also keeps that
   * path free of any network dependency.
   */
  private val fetchBakedPng: ((String) -> ByteArray?)? = null,
  private val fileSystem: FileSystem = SystemFileSystem,
) : ServeHost {

  // A catalog bundle that carried baked `figma/<slug>.svg` vectors can serve an SVG per preview; a
  // plain uploaded bundle (no figmaDir) 404s the `.svg` lane, so it offers no SVG download link.
  override val hasSvgExport: Boolean = figmaDir != null

  private val previewsDir = File(bundleDir, PREVIEWS_SUBDIR)
  private val previewsRoot = previewsDir.canonicalFile.toPath()
  private val designReferences = ServeDesignReferenceStore.load(bundleDir, fileSystem)

  override fun designReferencesFor(previewId: String): List<DesignReference> =
    designReferences.forPreview(previewId)

  override fun designReferenceRaster(referenceId: String): ByteArray? =
    designReferences.raster(referenceId)

  // Whole-screen backdrops, read once at load like the reference manifest above. A bundle that
  // carries none yields an empty store and the viewer never offers the surface.
  private val designPages = ServeDesignPageStore.load(bundleDir, fileSystem)

  override fun designPages(): ServeDesignPageStore = designPages

  // The published player comparison, if the catalog's branch shipped one. Unlike the manifests
  // above this store resolves lazily: its lane PNGs land on the catalog's background fetch lane, so
  // a host built the moment `catalog.json` arrived must be able to see them once they do.
  private val rcCompare = ServeRcCompareStore.load(bundleDir, fileSystem)

  override fun rcCompare(): RcCompareManifest? = rcCompare.manifest()

  override fun rcCompareImage(name: String): ByteArray? = rcCompare.image(name)

  override fun rcComparePending(): Boolean = rcCompare.pending()

  // Read once at load, like the reference manifest: the feed is a published snapshot, so re-reading
  // it per request would buy nothing (a refresh reloads the whole catalog and rebuilds this host).
  private val parityActivity = ServeParityActivityStore.load(bundleDir, fileSystem)

  override fun parityActivity(): ParityActivity? = parityActivity

  private val annotations = ServeAnnotationStore.load(bundleDir, fileSystem)

  override fun annotationsForPreview(previewId: String): List<DesignAnnotation> =
    annotations.forPreview(previewId)

  override fun annotationsForReference(referenceId: String): List<DesignAnnotation> =
    annotations.forReference(referenceId)

  /**
   * Per-preview `state`/`theme` from the catalog's `previews/variants.json` manifest (written by
   * [ServeCatalogStore]). Empty for a plain uploaded bundle that carries no manifest — every
   * preview then stays stateless (null state/theme), preserving the pre-toggle behaviour.
   * Best-effort: an unreadable / malformed manifest degrades to empty rather than failing the host.
   */
  private val variantMeta: Map<String, ServeCatalogStore.VariantMeta> = readVariantMeta()

  /**
   * Per-preview `id → module-relative sourceFile`. A **catalog** carries this on each
   * `previews/variants.json` entry ([ServeCatalogStore.VariantMeta.sourceFile]); a plain **uploaded
   * bundle** may instead carry a root `previews.json` manifest. We read the variants map first (the
   * catalog path this feature targets) and fall back to `previews.json` for ids it didn't cover, so
   * both session shapes resolve. Empty when neither source records a path. Feeds
   * [ServePreview.sourceFile].
   */
  private val sourceFilesById: Map<String, String> = readSourceFiles()

  /**
   * Per-preview discovery params from the bundle's root `previews.json`. Besides sizing Remote
   * Compose replays, this preserves each baked preview's explicit `uiMode` for the viewer's
   * Day/Night default. Empty when the bundle carries no manifest.
   */
  private val previewParamsById: Map<String, ee.schimke.composeai.cli.PreviewParams> by lazy {
    val previewsJson = File(bundleDir, PREVIEWS_JSON).toOkioPath()
    if (!fileSystem.exists(previewsJson)) return@lazy emptyMap()
    try {
      val text = fileSystem.read(previewsJson) { readUtf8() }
      OVERRIDES_JSON.decodeFromString(ee.schimke.composeai.cli.PreviewManifest.serializer(), text)
        .previews
        .associate { it.id to it.params }
    } catch (e: Exception) {
      emptyMap()
    }
  }

  /**
   * Per-preview body-line anchors, feeding [ServePreview.bodyLine] so the playground handoff can
   * seed one declaration instead of a whole section file.
   *
   * Read exactly the way [sourceFilesById] is — the catalog's `previews/variants.json` first, then
   * a root `previews.json` for ids it didn't cover — and that is load-bearing rather than tidiness.
   * A **catalog** stages no root manifest at all and keys its previews by flattened route ids
   * (`button-filled__ideal__default__dark`), not the discovery ids a bundle manifest carries, so a
   * manifest-only read resolves nothing for exactly the case this feature exists to serve and the
   * handoff silently stays whole-file. The `variants.json` path is where a catalog's anchors live;
   * the manifest path is the plain uploaded bundle.
   *
   * A `VariantMeta` is per *image* and an anchor is per *function*, so this does restate the same
   * number across a component's themes and states — the same duplication `sourceFile` already
   * accepts there, for the same reason: it is the only per-preview record a catalog publishes.
   */
  private val bodyLinesById: Map<String, Int> by lazy {
    val out = LinkedHashMap<String, Int>()
    for ((id, meta) in variantMeta) {
      meta.bodyLine?.takeIf { it > 0 }?.let { out[id] = it }
    }
    val previewsJson = File(bundleDir, PREVIEWS_JSON).toOkioPath()
    if (fileSystem.exists(previewsJson)) {
      try {
        val text = fileSystem.read(previewsJson) { readUtf8() }
        val manifest =
          OVERRIDES_JSON.decodeFromString(
            ee.schimke.composeai.cli.PreviewManifest.serializer(),
            text,
          )
        for (p in manifest.previews) {
          if (p.id !in out) p.bodyLine?.takeIf { it > 0 }?.let { out[p.id] = it }
        }
      } catch (e: Exception) {
        // Leave whatever the variants map already contributed.
      }
    }
    out
  }

  /**
   * The live-only ids this host lists, minus any that turned out to have a baked PNG after all (a
   * catalog that both baked and deferred the same route — belt and braces: the baked pixels win, so
   * the id keeps its ordinary snapshot lane).
   */
  /**
   * The declared baked set. An id here is **not** live-only even while its file is missing — that
   * is the whole point of declaring it — so it takes precedence over [liveOnly] below.
   */
  private val declaredBakedIds: Set<String> =
    declaredBaked.filterTo(LinkedHashSet()) { previewFile(it, PNG_SUFFIX) != null }

  override val liveOnlyPreviewIds: Set<String> =
    liveOnly.filterTo(LinkedHashSet()) {
      val png = previewFile(it, PNG_SUFFIX)
      png != null && it !in declaredBakedIds && !png.isFile
    }

  override val previews: List<ServePreview> =
    // Three sources, deduped: the PNGs already on disk, the catalog's declared baked set (whose
    // pixels may still be remote — see [declaredBaked]), and the live-only (deferred) ids, which
    // carry no file by design. Walk recursively: a preview id may contain '/', stored as a nested
    // `previews/<id>.png`, and ids are reconstructed relative to `previews/` with '/' separators
    // (matching the bundle layout). From here the three are indistinguishable except in where
    // `render` finds the bytes.
    (previewsDir
        .walkTopDown()
        .filter { it.isFile && it.name.endsWith(PNG_SUFFIX) }
        .map { it.relativeTo(previewsDir).invariantSeparatorsPath.removeSuffix(PNG_SUFFIX) }
        .toList() +
        previewsDir
          .walkTopDown()
          .filter { it.isFile && it.name.endsWith(RENDER_ERROR_SUFFIX) }
          .map {
            it.relativeTo(previewsDir).invariantSeparatorsPath.removeSuffix(RENDER_ERROR_SUFFIX)
          }
          .toList() +
        declaredBakedIds +
        liveOnlyPreviewIds)
      .distinct()
      .sorted()
      .map { id ->
        val meta = variantMeta[id]
        ServePreview(
          id = id,
          label = id,
          componentId = meta?.componentId,
          renderFailure = meta?.renderFailure ?: readRenderFailure(id),
          // A packed sidecar remains authoritative for ordinary uploaded bundles. Published
          // catalogs additionally carry these declarations inline so a supplement-only preview's
          // controls are visible before its per-preview daemon is opened lazily.
          overrides = readOverrides(id).ifEmpty { meta?.overrides.orEmpty() },
          remoteComposeKnobs =
            readRemoteComposeKnobs(id).ifEmpty { meta?.remoteComposeKnobs.orEmpty() },
          supportsFocus = meta?.supportsFocus == true,
          supportsGestures = meta?.supportsGestures == true,
          fixedTheme = meta?.fixedTheme == true,
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
          sourceFile = sourceFilesById[id],
          bodyLine = bodyLinesById[id],
          uiMode = previewParamsById[id]?.uiMode ?: 0,
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
    val usable = previews.filter { it.renderFailure == null }
    val exact = usable.firstOrNull { it.id == hero }
    if (exact != null) return@lazy exact.id
    val wanted = heroSlug(hero)
    usable.firstOrNull { heroSlug(it.id.substringBefore(SLUG_SEPARATOR)) == wanted }?.id
  }

  /** Resolve one per-preview file without allowing an untrusted catalog id to escape previews/. */
  private fun previewFile(id: String, suffix: String): File? {
    if (
      id.isBlank() ||
        id.startsWith('/') ||
        '\\' in id ||
        id.split('/').any { it == "." || it == ".." }
    ) {
      return null
    }
    val file = File(previewsDir, id + suffix)
    return file.takeIf { it.canonicalFile.toPath().startsWith(previewsRoot) }
  }

  /** Renderer error sidecar for an error-only uploaded/URL bundle preview. */
  private fun readRenderFailure(id: String): CatalogRenderFailure? {
    val sidecar = previewFile(id, RENDER_ERROR_SUFFIX)?.toOkioPath() ?: return null
    if (!fileSystem.exists(sidecar)) return null
    return try {
      val error =
        OVERRIDES_JSON.decodeFromString(
          BundleRenderError.serializer(),
          fileSystem.read(sidecar) { readUtf8() },
        )
      if (error.schema != RENDER_ERROR_SCHEMA) return null
      CatalogRenderFailure(
        id = id,
        preview = id,
        errorClass = error.exception,
        message = error.message,
        stackTrace = error.stackTrace,
        topAppFrame = error.topAppFrame,
      )
    } catch (_: Exception) {
      null
    }
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
   * Best-effort read of `id → sourceFile`. Prefers the catalog's `previews/variants.json` (each
   * [ServeCatalogStore.VariantMeta.sourceFile], already parsed into [variantMeta]), then falls back
   * to a root `previews.json` manifest for any id the variants map didn't cover (the plain uploaded
   * bundle path). Fail-soft like [declaredThemes]: an absent / unreadable `previews.json` just
   * contributes nothing; entries without a `sourceFile` are dropped so `sourceFilesById[id]` is
   * null and no source link renders.
   */
  private fun readSourceFiles(): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for ((id, meta) in variantMeta) {
      meta.sourceFile?.takeIf { it.isNotBlank() }?.let { out[id] = it }
    }
    val previewsJson = File(bundleDir, PREVIEWS_JSON).toOkioPath()
    if (fileSystem.exists(previewsJson)) {
      try {
        val text = fileSystem.read(previewsJson) { readUtf8() }
        val manifest =
          OVERRIDES_JSON.decodeFromString(
            ee.schimke.composeai.cli.PreviewManifest.serializer(),
            text,
          )
        for (p in manifest.previews) {
          if (p.id !in out) p.sourceFile?.takeIf { it.isNotBlank() }?.let { out[p.id] = it }
        }
      } catch (e: Exception) {
        // Leave whatever the variants map already contributed.
      }
    }
    return out
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

  /**
   * Read the Remote Compose named-value knobs carried for [id] in the bundle's
   * `previews/<id>.remotecompose.json` sidecar (the `compose/remotecompose` declarations payload).
   * The RC counterpart of [readOverrides]: absent / unreadable → no knobs. A baked bundle can't
   * re-render, so the viewer shows these as informational controls until a live daemon backs them.
   */
  private fun readRemoteComposeKnobs(
    id: String
  ): List<ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration> {
    val sidecar = File(previewsDir, "$id$REMOTECOMPOSE_SUFFIX").toOkioPath()
    if (!fileSystem.exists(sidecar)) return emptyList()
    return try {
      val json = fileSystem.read(sidecar) { readUtf8() }
      OVERRIDES_JSON.decodeFromString(
          ee.schimke.composeai.data.remotecompose.RemoteComposeDeclarationsPayload.serializer(),
          json,
        )
        .declarations
    } catch (e: Exception) {
      emptyList()
    }
  }

  private val previewIds: Set<String> = previews.map { it.id }.toHashSet()

  // The captured Remote Compose documents ride in the bundle's `ir/<id>.rc` sidecars (a sibling
  // of `previews/`), the browser player's replayable input.
  private val irDir = File(bundleDir, IR_SUBDIR)

  /**
   * The local file holding [previewId]'s baked PNG, fetching it from the delivery branch first if
   * it isn't there yet — the single point every pixel reader on this host goes through ([render],
   * [readPngSize], [computeContentCrop]), so none of them can accidentally see a declared preview
   * as pixel-less.
   *
   * Returns null when there are no pixels to be had: an unknown id, a live-only (deferred) id, or a
   * declared one whose fetch failed. A failed fetch is not remembered — the next request retries,
   * which is what makes a transient branch blip self-heal instead of stranding a card for the life
   * of the host.
   *
   * Fetches are per-id serialised so a grid painting twenty cards at once issues one request per
   * preview rather than one per reader; the double-check inside the lock means the second caller
   * reads the file the first just wrote.
   */
  private fun bakedPngFile(previewId: String): okio.Path? {
    val path = previewFile(previewId, PNG_SUFFIX)?.toOkioPath() ?: return null
    if (fileSystem.exists(path)) return path
    val fetch = fetchBakedPng ?: return null
    if (previewId !in declaredBakedIds) return null
    synchronized(fillLocks.computeIfAbsent(previewId) { Any() }) {
      if (fileSystem.exists(path)) return path
      val bytes = runCatching { fetch(previewId) }.getOrNull() ?: return null
      // Written to a sibling and moved into place atomically. The existence check above is
      // deliberately outside this lock (a warm read must not queue behind a cold fetch), so the
      // destination must never exist in a half-written state — a reader that saw it would serve a
      // truncated PNG.
      return runCatching {
          path.parent?.let(fileSystem::createDirectories)
          // Named per destination, not a shared temp: two ids filling concurrently hold
          // different locks, so a single shared partial name would let one preview's bytes be
          // published under another's id.
          val partial = path.parent!!.resolve(path.name + PARTIAL_SUFFIX)
          fileSystem.write(partial) { write(bytes) }
          fileSystem.atomicMove(partial, path)
          path
        }
        .getOrNull()
    }
  }

  /** [previewId]'s baked PNG only if it is already local — never fetches. */
  private fun localBakedPng(previewId: String): okio.Path? =
    previewFile(previewId, PNG_SUFFIX)?.toOkioPath()?.takeIf(fileSystem::exists)

  private val fillLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

  /**
   * The local-pixels fast path. Deliberately [localBakedPng], not [bakedPngFile]: a declared
   * preview whose PNG hasn't arrived yet needs a fetch, and fetching is work that belongs behind
   * admission like any other. Answering null sends it down the ordinary [render] path, which fills
   * it.
   */
  override fun bakedRender(previewId: String, overrides: PreviewOverrides): RenderOutcome.Ok? {
    if (previewId !in previewIds) return null
    val png = localBakedPng(previewId) ?: return null
    return RenderOutcome.Ok(
      fileSystem.read(png) { readByteArray() },
      RenderOutcome.Generation.BAKED,
    )
  }

  // Deliberately [localBakedPng], for the same reason [bakedRender] is: measuring an image must
  // never trigger the fetch that would make it measurable. A declared-but-not-yet-local preview
  // reports no size, and the page omits the dimensions rather than paying a network round trip to
  // fill in an optimisation.
  override fun bakedRenderSize(previewId: String): Pair<Int, Int>? {
    if (previewId !in previewIds) return null
    return readPngSize(localBakedPng(previewId) ?: return null)
  }

  override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
    if (previewId !in previewIds) return RenderOutcome.NotFound
    val png = bakedPngFile(previewId) ?: return RenderOutcome.NotFound
    return RenderOutcome.Ok(
      fileSystem.read(png) { readByteArray() },
      RenderOutcome.Generation.BAKED,
    )
  }

  override fun remoteComposeDoc(previewId: String): ByteArray? {
    if (previewId !in previewIds) return null
    val doc = File(irDir, "$previewId$RC_SUFFIX").toOkioPath()
    if (!fileSystem.exists(doc)) return null
    return try {
      fileSystem.read(doc) { readByteArray() }
    } catch (e: Exception) {
      null
    }
  }

  // Cheap existence check (no read) so the per-preview page render can gate the client-side canvas
  // lane without pulling the whole document — the browser fetches the bytes over `/render/<id>.rc`.
  override fun hasRemoteComposeDoc(previewId: String): Boolean {
    if (previewId !in previewIds) return false
    return fileSystem.exists(File(irDir, "$previewId$RC_SUFFIX").toOkioPath())
  }

  // The cmp-jvm render is sized to the baked PNG's exact pixel dimensions — so the desktop-player
  // PNG lands at the same size the viewer shows the baked / View-player lane at — with the density
  // the capture used (from `previews.json`, else the renderer default). Null when the preview has
  // no
  // captured doc or no baked PNG to size against.
  override fun remoteComposeRenderSpec(previewId: String): RcJvmRenderSpec? {
    if (!hasRemoteComposeDoc(previewId)) return null
    // Sized against the baked PNG, so a declared-but-not-yet-local preview fills first.
    val (widthPx, heightPx) = readPngSize(bakedPngFile(previewId) ?: return null) ?: return null
    val density = previewParamsById[previewId]?.density ?: DEFAULT_RENDER_DENSITY
    return RcJvmRenderSpec(widthPx, heightPx, density)
  }

  /** Read a PNG's pixel dimensions from its IHDR without decoding the image; null if unreadable. */
  private fun readPngSize(path: okio.Path): Pair<Int, Int>? {
    return try {
      val header = fileSystem.read(path) { readByteArray(24) }
      // 8-byte PNG signature, 4-byte IHDR length, 4-byte "IHDR", then width + height, big-endian.
      fun be(off: Int): Int =
        ((header[off].toInt() and 0xff) shl 24) or
          ((header[off + 1].toInt() and 0xff) shl 16) or
          ((header[off + 2].toInt() and 0xff) shl 8) or
          (header[off + 3].toInt() and 0xff)
      val w = be(16)
      val h = be(20)
      if (w > 0 && h > 0) w to h else null
    } catch (e: Exception) {
      null
    }
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
  override fun hasSvgExportFor(previewId: String): Boolean = figmaSvgFileFor(previewId) != null

  /**
   * The baked figma-svg file serving [previewId], or null when the catalog carries none. The
   * catalog ships two shapes: the **per-variant** vector `figma/<slug>/<variant>.svg` (one per
   * `images[]` entry — the dark/light/locale/size variants), and the back-compat **per-component**
   * `figma/<slug>.svg` (one per slug, light-preferred). Prefer the per-variant file — serving the
   * slug vector for a `…__dark` id hands out the light theme — and fall back to the slug vector for
   * a catalog published before the per-variant emit existed.
   */
  private fun figmaSvgFileFor(previewId: String): okio.Path? {
    val figma = figmaDir ?: return null
    if (previewId !in previewIds) return null
    val slug = previewId.substringBefore(SLUG_SEPARATOR)
    val variant = previewId.substringAfter(SLUG_SEPARATOR, missingDelimiterValue = "")
    if (variant.isNotEmpty()) {
      val perVariant = File(File(figma, slug), "$variant$SVG_SUFFIX").toOkioPath()
      if (fileSystem.exists(perVariant)) return perVariant
    }
    return File(figma, "$slug$SVG_SUFFIX").toOkioPath().takeIf { fileSystem.exists(it) }
  }

  override fun renderSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome {
    val svgFile = figmaSvgFileFor(previewId) ?: return SvgOutcome.NotFound
    val svg = fileSystem.read(svgFile) { readUtf8() }
    // Crops resolve relative to the SVG's own dir: `<slug>.figma-raster/` next to the slug vector,
    // `<variant>.figma-raster/` next to a per-variant one.
    val dir = svgFile.parent ?: return SvgOutcome.NotFound
    return SvgOutcome.Ok(
      inlineFigmaRasters(fileSystem, dir, svg).encodeToByteArray(),
      RenderOutcome.Generation.BAKED,
    )
  }

  /**
   * Web/document variant of [renderSvg]: instead of base64-embedding the hybrid raster crops, link
   * them to their published home — the same files on the catalog's delivery branch
   * (`raw.githubusercontent.com/<repo>/<branch>/figma/…`), which [provenance] records from the
   * fetch. Keeps the web-served SVG at vector size while a document viewer resolves the crops over
   * HTTP (an `<img>`-loaded SVG can't, but that context gets the self-contained default instead).
   * Falls back to the embedded default when the catalog carries no provenance (a plain uploaded
   * bundle, a local `--bundles` dir) — there's no public home to link.
   */
  override fun renderSvgForWeb(previewId: String, overrides: PreviewOverrides): SvgOutcome {
    val prov = provenance ?: return renderSvg(previewId, overrides)
    val svgFile = figmaSvgFileFor(previewId) ?: return SvgOutcome.NotFound
    val svg = fileSystem.read(svgFile) { readUtf8() }
    // The crops' branch URL mirrors the SVG's on-disk dir relative to the catalog root (figmaDir's
    // parent): `figma` for the slug vector, `figma/<slug>` for a per-variant one.
    val catalogRoot = figmaDir?.parentFile ?: return renderSvg(previewId, overrides)
    val relDir =
      svgFile.parent?.toFile()?.relativeToOrNull(catalogRoot)?.invariantSeparatorsPath
        ?: return renderSvg(previewId, overrides)
    val base = "https://raw.githubusercontent.com/${prov.repo}/${prov.branch}/$relDir"
    return SvgOutcome.Ok(
      linkFigmaRasters(svg, base).encodeToByteArray(),
      RenderOutcome.Generation.BAKED,
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
  fun contentCrop(previewId: String): ContentCrop? {
    cropCache[previewId]?.let {
      return it.orElse(null)
    }
    // A crop needs both the PNG and the component's vector, and either can still be in flight: the
    // PNG fills on first use, and the vectors are filled by a background pass after the catalog
    // publishes. Answer null without memoising while either is outstanding, so the card starts
    // cropping as soon as they land rather than staying uncropped until the next catalog refresh.
    // Only a decision made against files that are actually present is cached.
    if (localBakedPng(previewId) == null && previewId in declaredBakedIds) return null
    if (figmaDir != null && figmaSvgFileFor(previewId) == null) return null
    val computed = java.util.Optional.ofNullable(computeContentCrop(previewId))
    cropCache[previewId] = computed
    return computed.orElse(null)
  }

  private val cropCache =
    java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<ContentCrop>>()

  private fun computeContentCrop(previewId: String): ContentCrop? {
    // Same per-variant-first resolution as `renderSvg` — a variant vector's viewBox reflects the
    // exact render this preview's PNG shows.
    val svgFile = figmaSvgFileFor(previewId) ?: return null
    // Deliberately the already-local file, NOT `bakedPngFile`: the landing page computes a crop for
    // every card while building its HTML, so filling here would serially download a whole cold
    // catalog on the first page request — the exact stall lazy fetching exists to remove, moved
    // onto the request thread. A cold card simply renders uncropped; the browser's own
    // `/render/<id>.png` request lands the file, and the next page build crops it.
    val png = localBakedPng(previewId) ?: return null
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
    private const val RENDER_ERROR_SUFFIX = ".error.json"
    private const val RENDER_ERROR_SCHEMA = "compose-preview-error/v1"
    /** Suffix of the sibling a lazy fill writes before moving it into place atomically. */
    private const val PARTIAL_SUFFIX = ".partial"

    // Fallback render density for a cmp-jvm render when `previews.json` declares none — the desktop
    // renderer's own default (a 200dp preview bakes to 525px), so an unspecified preview still
    // renders at the density its baked PNG was captured with.
    private const val DEFAULT_RENDER_DENSITY = 2.625f
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
    private const val REMOTECOMPOSE_SUFFIX = ".remotecompose.json"
    /** Sibling of `previews/` holding the captured Remote Compose docs (`ir/<id>.rc`). */
    private const val IR_SUBDIR = "ir"
    private const val RC_SUFFIX = ".rc"
    private const val PREVIEWS_JSON = "previews.json"
    private val OVERRIDES_JSON = Json { ignoreUnknownKeys = true }

    /** True when [dir] contains at least one baked preview or structured render failure. */
    fun looksLikeBundle(dir: File): Boolean {
      val previews = File(dir, PREVIEWS_SUBDIR)
      return previews.isDirectory &&
        previews.walkTopDown().any {
          it.isFile && (it.name.endsWith(PNG_SUFFIX) || it.name.endsWith(RENDER_ERROR_SUFFIX))
        }
    }
  }
}

@Serializable
private data class BundleRenderError(
  val schema: String = "",
  val exception: String = "RenderError",
  val message: String = "",
  val topAppFrame: RenderFailureFrame? = null,
  val stackTrace: String? = null,
)
