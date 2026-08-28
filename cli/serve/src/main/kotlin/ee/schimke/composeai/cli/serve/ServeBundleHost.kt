package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleVerifier
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.data.overrides.PreviewOverridesPayload
import ee.schimke.composeai.data.pseudolocale.LocaleDirection
import ee.schimke.composeai.data.pseudolocale.Pseudolocale
import ee.schimke.composeai.imagecrop.ContentCrop
import ee.schimke.composeai.imagecrop.computeGutterCrop
import ee.schimke.composeai.imagecrop.computeThumbCrop
import ee.schimke.composeai.imagecrop.pngAlphaBounds
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.web.WebEscaping
import java.io.File
import kotlin.math.roundToInt
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
   * Whether this host serves a **design-system catalog** rather than a plain bundle.
   *
   * The type cannot answer this. `ServeBundleHost` backs three different things — a catalog
   * published by [ServeCatalogStore], a `--bundles` directory ([ServeRunner]), and an uploaded
   * portable bundle ([ServeBundleStore]) — and only the first has a `catalog.json` behind it. The
   * other two are plain bundles that happen to share the implementation, so an `is ServeBundleHost`
   * test reads them as catalogs too.
   *
   * Set true only where a catalog is actually being built. Callers that speak *about a catalog* —
   * the page-scoped issue report says "this catalog" — need this rather than the type, or a plain
   * uploaded bundle is offered a report naming a catalog it does not have (#4728 review).
   *
   * Not inferred from [title] / [provenance] / [catalogSource]: those are all individually optional
   * on a real catalog, so absence proves nothing and a catalog declaring none of them would be
   * misread as plain.
   */
  val isCatalog: Boolean = false,
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
   * The sibling system this catalog is a parallel rendition of (`catalog.json`'s `compareWith`),
   * populated by [ServeCatalogStore]. With [parallelByComponentId] it is what lets the viewer's
   * spec lane offer the counterpart's render as a second comparison source: this says WHICH SYSTEM,
   * that says WHICH COMPONENT in it, and neither half resolves alone.
   *
   * Null for a plain uploaded bundle and for every catalog that declares no pairing.
   */
  val compareWithSystem: String? = null,
  /**
   * `componentId` → the counterpart's `componentId` in [compareWithSystem], from each published
   * component's `parallel`. Empty for a catalog that declares no pairing, or one published before
   * `parallel` reached the manifest (yschimke/compose-ai-tools#4631) — in which case the lane
   * simply isn't offered, exactly as it wasn't before.
   */
  val parallelByComponentId: Map<String, String> = emptyMap(),
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
   * Whether a background lane will stage this session's published Remote Compose player comparison.
   *
   * This is what makes [rcComparePending] mean "the lane has not landed yet" rather than "there is
   * no manifest on disk". Only [ServeCatalogStore] schedules that lane, and only for a catalog with
   * previews to re-key — every other session (a plain uploaded bundle, a served directory) has no
   * lane to wait for. Gating on the file alone made `pending()` permanently true for those, which
   * dropped every one of their viewer pages to `no-store` for the life of the host: the pages are
   * fully baked and exactly the ones edge caching is for.
   */
  private val stagesRcCompare: Boolean = false,
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
  /**
   * Ids this catalog publishes an animated capture for.
   *
   * Separate from [declaredBaked] because a capture is not a preview: it never appears in the grid,
   * owns no card, and is only ever reachable from the still it accompanies. Empty for a plain
   * uploaded bundle and for any catalog exported before the branch carried these bytes.
   */
  declaredMotion: List<String> = emptyList(),
  /**
   * Fetch one declared capture's bytes from the delivery branch, or null when they can't be had.
   * Same seam as [fetchBakedPng], for the same reason: the store owns URL assembly, the SSRF gate,
   * the size cap and the test seam, and this host only names an id it was told about.
   */
  /**
   * Fetches one declared capture off the delivery branch, reporting **why** a failure failed.
   *
   * Outcome-shaped rather than `ByteArray?` for the reason the transport seam is: a second seam
   * beside a bytes-shaped one is a lane waiting to be forgotten. The reason travels because the
   * route needs it — a throttled capture is a `503` the reader can retry, and a `404` says the
   * catalog never published it.
   */
  private val fetchMotion: ((String) -> BranchFetch)? = null,
  /** Each declared capture's branch path, so a pinned (`?at=<sha>`) request can resolve one. */
  private val motionBranchPaths: Map<String, String> = emptyMap(),
  /**
   * Each declared preview's path on the delivery branch (`images/<slug>/<variant>.png`), which is
   * what a **pinned** request resolves against: the same tree, read at an older commit. Empty for a
   * plain uploaded bundle (nothing to pin to) and for any host with no delivery branch behind it.
   */
  private val bakedBranchPaths: Map<String, String> = emptyMap(),
  /**
   * The delivery branch's published revisions, newest first — the catalog's own version history,
   * read from the branch when it was loaded ([ServeCatalogStore.fetchRevisions]). Its head is the
   * revision being served; the rest are what a page offers as pinnable destinations. Empty for an
   * uploaded bundle, and for a catalog whose branch history couldn't be read.
   */
  val revisions: List<ServeCatalogRevision.Revision> = emptyList(),
  /** Preview inventories precomputed by the publisher, keyed by historic delivery commit. */
  private val revisionPreviewIds: Map<String, Set<String>>? = null,
  /**
   * The publishes in which one render's bytes changed, by branch path — supplied by
   * [ServeCatalogStore], null for a host with no delivery branch.
   *
   * Its own seam rather than a use of [fetchPinnedAsset] because it reads a different surface for a
   * different question: that one fetches bytes at a commit, this one asks the branch's history when
   * those bytes last moved. Returning null means "could not ask", which [renderChangeCommits] is
   * careful to keep distinct from the empty set.
   */
  private val fetchRenderChanges: ((path: String) -> Set<String>?)? = null,
  /**
   * Each design reference's path **on the delivery branch**, which is not the path the served
   * manifest carries: catalog import rewrites every raster to a server-owned `references/<id>.png`.
   * That rewrite is what contains the lane, and it is also why the branch path has to be handed
   * over separately — it is the only string that addresses the raster at an older commit.
   */
  private val referenceBranchPaths: Map<String, String> = emptyMap(),
  /**
   * Fetch one published asset from the delivery branch **at a given commit**, or null when it can't
   * be had. Supplied by [ServeCatalogStore] for the same reason [fetchBakedPng] is: this host names
   * a commit and a path, and the store owns URL assembly, the size cap and the test seam. Null ⇒
   * the host serves no pinned revisions ([supportsPinnedRevisions]).
   */
  private val fetchPinnedAsset: ((commit: String, path: String) -> ByteArray?)? = null,
  /**
   * [fetchPinnedAsset], but reporting **why** a read failed.
   *
   * Preferred over [fetchPinnedAsset] when supplied; the plain seam remains for callers (and the
   * fixtures) that have no way to tell a throttle from an absence. This is what makes
   * [pinnedMisses] safe to keep forever — see the reasoning there.
   */
  private val fetchPinnedAssetOutcome: ((commit: String, path: String) -> BranchFetch)? = null,
  /**
   * Resolves ids to branch paths **as they were at a given commit** ([ServePinnedManifest]). Null
   * for a host with no delivery branch; when present it takes precedence over the tip's maps below,
   * which remain the fallback for a commit whose manifests can't be read.
   */
  private val pinnedManifest: ServePinnedManifest? = null,
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

  override fun rcComparePending(): Boolean = stagesRcCompare && rcCompare.pending()

  // Read once at load, like the reference manifest: the feed is a published snapshot, so re-reading
  // it per request would buy nothing (a refresh reloads the whole catalog and rebuilds this host).
  private val parityActivity = ServeParityActivityStore.load(bundleDir, fileSystem)

  override fun parityActivity(): ParityActivity? = parityActivity

  private val parityIssues = ServeParityIssuesStore.load(bundleDir, fileSystem)

  override fun parityIssues(): ParityIssues? = parityIssues

  // Same read-once rule as the feeds around it: a published verdict describes the catalog this
  // host was built from, so re-reading it per request could only ever pair a newer verdict with an
  // older inventory.
  private val parityFindings = ServeParityFindingStore.load(bundleDir, fileSystem)

  override fun parityFindingsFor(previewId: String, referenceId: String): List<ParityFindingSet> =
    parityFindings.forComparison(previewId, referenceId)

  // Read once at load, like the feeds above — and for a sharper reason than saving a file read.
  //
  // A catalog refresh swaps the staged directory over `bundleDir` and only *then* finishes its
  // post-swap work (the Wasm app, vectors, themes, live bundles) before registering a rebuilt host.
  // Everything else this host serves — `previews`, `parityIssues`, the design references — was read
  // when the host was built, so a per-call read of this one file would put a **new** document
  // beside an **old** inventory for the whole of that window. That is not a stale number: the
  // dashboard's walk joins the two, so an acceptance naming a preview the new catalog has and the
  // old host does not reads as `orphaned-target`, and the panel reports a problem that does not
  // exist. A false finding is worse than a late one.
  //
  // Nothing is lost by caching it. A refresh rebuilds this host from the swapped directory, so a
  // delivery-branch commit still reaches a serving host within one refresh tick; the per-call read
  // only ever differed from that inside the window where it was wrong.
  private val knownDifferences = ServeKnownDifferences.document(bundleDir, fileSystem)

  override fun knownDifferences(): ServeKnownDifferences.Document? = knownDifferences

  override fun knownDifferenceArtifact(relativePath: String): ServeKnownDifferences.Artifact =
    ServeKnownDifferences.artifact(bundleDir, relativePath, fileSystem)

  private val annotations = ServeAnnotationStore.load(bundleDir, fileSystem)

  override fun annotationsForPreview(previewId: String): List<DesignAnnotation> =
    annotations.forPreview(previewId)

  /**
   * The kinds of published annotation the viewer's inspection layers actually draw.
   *
   * `layout` is published alongside these and belongs to the compare page, which reads the same
   * manifest for a different surface. Handing it to the overlay would put boxes in the legend under
   * no heading at all — `<cp-inspect-layers>` groups by the kind a layer declares, and there is no
   * layout layer.
   */
  private fun drawableAnnotations(previewId: String): List<DesignAnnotation> =
    annotationsForPreview(previewId).filter {
      it.kind == AnnotationKind.TYPOGRAPHY || it.kind == AnnotationKind.THEME
    }

  /**
   * Whether the catalog published typography over **this preview's own baked frame**, so the
   * Typography layer has something to draw without a daemon. See [renderAnnotations].
   */
  override fun hasPublishedTypographyFor(previewId: String): Boolean =
    previewId in previewIds &&
      annotationsForPreview(previewId).any { it.kind == AnnotationKind.TYPOGRAPHY }

  /**
   * Replay the catalog's **published** annotations for [previewId] as the `.annotations` product.
   *
   * A static bundle has no daemon to capture a semantics tree from, which is why [ServeHost]'s
   * default is `NotFound` — but a published catalog carries `annotations/index.json`, whose preview
   * layer is exactly these facts measured over the very PNG this host serves. Answering from it is
   * not an approximation: this host never re-renders, so [overrides] cannot move the pixels the
   * bounds describe (an override-bearing request gets the same baked frame, and the HTTP layer
   * reports what it dropped). That makes the overlay work on a plain published catalog instead of
   * ticking a checkbox that fetches a 404 and silently draws nothing.
   *
   * Typography only, in practice: the theme layer is derived live from a render's semantics tree
   * ([ServeDesignAnnotations]) and no producer authors it into a bundle. `tags` comes from the
   * bundle's own published index, so the two halves still describe one frame.
   */
  /**
   * This host has no daemon, so its annotations are the catalog's published ones replayed over the
   * catalog's baked frame — the one lane where the layers and the PNG describe the same render.
   */
  override val annotationsFollowBakedFrame: Boolean = true

  override fun renderAnnotations(
    previewId: String,
    overrides: PreviewOverrides,
  ): AnnotationsOutcome {
    if (previewId !in previewIds) return AnnotationsOutcome.NotFound
    val published = drawableAnnotations(previewId)
    if (published.isEmpty()) return AnnotationsOutcome.NotFound
    return AnnotationsOutcome.Ok(
      ServeAnnotationsPayload.encode(previewId, published, tagIndexForPreview(previewId))
    )
  }

  override fun annotationsForReference(referenceId: String): List<DesignAnnotation> =
    annotations.forReference(referenceId)

  private val tagIndex = ServeTagIndexStore.load(bundleDir, fileSystem)

  override fun tagIndexForPreview(previewId: String): Map<String, ServeSemanticsTags.TagEntry> =
    tagIndex.forPreview(previewId)

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
  private val previewParamsById:
    Map<String, ee.schimke.composeai.previewdata.PreviewParams> by lazy {
    val previewsJson = File(bundleDir, PREVIEWS_JSON).toOkioPath()
    if (!fileSystem.exists(previewsJson)) return@lazy emptyMap()
    try {
      val text = fileSystem.read(previewsJson) { readUtf8() }
      OVERRIDES_JSON.decodeFromString(
          ee.schimke.composeai.previewdata.PreviewManifest.serializer(),
          text,
        )
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
            ee.schimke.composeai.previewdata.PreviewManifest.serializer(),
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
  /**
   * Ids this catalog publishes a capture for, as a set for the containment check on request.
   *
   * Declared here rather than beside the motion fill below because the preview list is built during
   * construction and reads it — a property initialised later would be empty at that point, and
   * every capture would be filtered out of the manifest it is supposed to appear in.
   */
  private val declaredMotionIds: Set<String> = declaredMotion.toSet()

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
        val previewParams = previewParamsById[id]?.asPreviewParamsMeta() ?: meta?.previewParams
        ServePreview(
          id = id,
          label = id,
          componentId = meta?.componentId,
          // Only captures this host can actually land. A manifest entry with no fetch seam behind
          // it (a plain bundle, or a catalog whose store didn't register the lane) would offer the
          // reader a control that 404s, which is worse than not offering it.
          motion =
            if (fetchMotion == null) emptyList()
            else
              meta
                ?.motion
                .orEmpty()
                .filter { it.id in declaredMotionIds && it.extension in MOTION_EXTENSIONS }
                .map {
                  ServeMotion(
                    id = it.id,
                    kind = it.kind,
                    caption = it.caption,
                    extension = it.extension,
                  )
                },
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
          secondary = meta?.secondary == true,
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
          // Like `state`, only a `catalog.json`-backed bundle carries this: a plain module bundle's
          // device fan-out has no manifest to name the breakpoints, so its renders stay size-less
          // and each keeps its own card rather than being folded out with no switcher to reach it.
          size = meta?.size,
          section = meta?.section,
          group = meta?.group,
          caption = meta?.caption,
          catalogOrder = meta?.order,
          sourceFile = sourceFilesById[id],
          sourceModule = meta?.sourceModule,
          bodyLine = bodyLinesById[id],
          // The `@Preview` ground and device frame, from whichever source this session actually
          // has. An uploaded bundle carries a root `previews.json` and answers directly; a
          // published CATALOG does not stage one, and its metadata rides on
          // `previews/variants.json` instead. Without the second source every catalog preview
          // arrived with the annotation defaults, so `PreviewBackdrop` fell back to the catalog's
          // declared stage for all of them and the device clip never resolved — on the ordinary
          // read-only path, which is how a published catalog is normally read.
          //
          // The bundle wins where both exist: it is this render's own manifest, while the catalog
          // record was written by an export that may predate the bundle in front of us.
          uiMode = previewParams?.uiMode ?: 0,
          showBackground = previewParams?.showBackground == true,
          backgroundColor = previewParams?.backgroundColor ?: 0L,
          deviceFrame =
            previewParams?.let { ServeDeviceFrame.from(it.device, it.widthDp, it.heightDp) },
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
            ee.schimke.composeai.previewdata.PreviewManifest.serializer(),
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
        OVERRIDES_JSON.decodeFromString(
          ee.schimke.composeai.previewdata.PreviewManifest.serializer(),
          text,
        )
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

  /**
   * Whether this host can answer a `?at=<sha>` pin — it has a delivery branch to read older commits
   * from. False for a plain uploaded bundle, whose bytes exist nowhere but this disk.
   */
  val supportsPinnedRevisions: Boolean
    get() = fetchPinnedAsset != null || fetchPinnedAssetOutcome != null

  /**
   * [previewId]'s baked render **as published at [commit]**, or null when there is no such thing.
   *
   * Null is the only honest answer to a pin this host can't satisfy — an id the catalog never
   * baked, a commit predating the preview, a fetch that failed. It must never fall back to the
   * current bytes: a permalink that silently answers with today's render is the bug this whole
   * feature exists to fix, and it would be undetectable from the outside.
   */
  fun pinnedRender(commit: String, previewId: String): PinnedOutcome =
    pinnedAsset(
      commit,
      branchPath(commit, previewId, bakedBranchPaths, { it.catalogRead }, { it.renders }),
    )

  /**
   * A preview this catalog published at [commit] but does **not** list today, as a record the
   * viewer can page.
   *
   * This is the other half of resolving a retired id. [pinnedRender] finds its pixels; without this
   * the *page* around them still 404s, because the session's preview list is built from the branch
   * tip and a renamed-away id is not in it — so a permalink made before the rename would answer
   * with an image but not with the page a person actually opened.
   *
   * Null for an id this revision didn't publish either, and null when its catalog can't be read:
   * inventing a page for an id nothing confirms would be worse than admitting we don't have it.
   * Deliberately minimal — an id and whatever component identity that revision gave it. Everything
   * else the viewer draws (axes, siblings, references, knobs) describes the *current* catalog, and
   * a pinned page has all of those lanes off anyway.
   */
  fun pinnedPreview(commit: String, previewId: String): ServePreview? {
    val paths = pinnedManifest?.forCommit(commit) ?: return null
    if (!paths.catalogRead || previewId !in paths.renders) return null
    return ServePreview(
      id = previewId,
      label = previewId,
      componentId = paths.labels[previewId],
      caption = paths.captions[previewId],
      theme = paths.themes[previewId],
    )
  }

  /**
   * Whether [commit]'s own catalog could be read — i.e. whether it is entitled to answer for what
   * that revision published.
   *
   * The page lookup needs this separately from [pinnedPreview], because "no such preview then" and
   * "I could not ask" must lead to different pages: the first is a 404, the second falls back to
   * the tip. Returning null from [pinnedPreview] alone cannot say which happened.
   */
  fun pinnedCatalogIsAuthoritative(commit: String): Boolean =
    pinnedManifest?.forCommit(commit)?.catalogRead == true

  /** Null means this branch or revision predates the generation-time index, so menus fail open. */
  fun revisionContainsPreview(commit: String, previewId: String): Boolean? =
    ServeCatalogRevision.normalize(commit)?.let { revisionPreviewIds?.get(it)?.contains(previewId) }

  /**
   * The delivery-branch publishes in which [previewId]'s render actually changed, or null when the
   * branch could not be asked.
   *
   * Null and the empty set are different answers and both are real: empty means the branch answered
   * and named no change — every publish in the window carries identical pixels, which is the
   * *interesting* case this feature exists to show — while null means the read failed and the
   * viewer must draw no markers rather than claim everything is identical.
   *
   * Cached per preview and never invalidated within a load, which is exactly as fresh as the rest
   * of the page: a load reads one commit ([ServeCatalogStore.load]), so the branch history behind
   * it is fixed for the life of this host and a later publish arrives with the next load.
   *
   * Shares [pinnedPermits] with the pinned-asset lane rather than taking a pool of its own. They
   * bound the same scarce thing — concurrent reads of the delivery branch — and this route is
   * likewise reachable by an anonymous caller naming a preview per request, so leaving it unbounded
   * would reopen precisely the hole that semaphore was added to close.
   *
   * Misses are serialized **per preview** on [fillLocks], the same way a cold baked-PNG fill is,
   * and that is load-bearing rather than tidiness: the semaphore counts permits, it does not
   * deduplicate, so without this a single preview whose menu is opened by four readers at once
   * would take all four branch-read permits and issue four identical feed requests — starving the
   * pinned-asset lane that shares them. Re-checking the cache under the permit is not enough on its
   * own, because it only helps once the first fetch has already finished.
   *
   * Blocking, and called from [Dispatchers.IO][kotlinx.coroutines.Dispatchers.IO] rather than a
   * request thread — see the route.
   *
   * ### Known limitation: the TIP path only
   *
   * The feed is read for [bakedBranchPaths]`[previewId]` — the path this preview has *now*. Ids are
   * stable across publishes and the paths under them are not, which is why [pinnedRender] resolves
   * through [branchPath] against the manifests at each commit instead. This lane does not: if a
   * preview's published path changed inside the window, publishes that touched the *former* path
   * are invisible here, and the revisions below the new path's first commit collapse into one run
   * even when the old PNG changed several times — understating the count rather than overstating
   * it.
   *
   * Not resolved per revision on purpose. Doing so costs a manifest read to learn the old path plus
   * a second feed read to cover it, on a lane whose whole argument is that one cheap read replaces
   * downloading and hashing a dozen PNGs — so it would roughly triple the cost of every cold menu
   * open to correct a case that needs a publisher to move a stable id's path mid-window. If that
   * turns out to happen in practice, the fix is to resolve the path at the window's oldest revision
   * and union the two feeds; see the PR discussion.
   */
  fun renderChangeCommits(previewId: String): Set<String>? {
    val fetch = fetchRenderChanges ?: return null
    // The warm read stays OUTSIDE the lock, exactly as [bakedPngFile]'s does: an answer this host
    // already has must never queue behind someone else's cold fetch.
    renderChangeCache[previewId]?.let {
      return it
    }
    val path = bakedBranchPaths[previewId] ?: return null
    synchronized(fillLocks.computeIfAbsent("$RENDER_CHANGE_LOCK_PREFIX$previewId") { Any() }) {
      // Re-checked under the lock: whoever we queued behind was fetching exactly this answer.
      renderChangeCache[previewId]?.let {
        return it
      }
      if (
        !pinnedPermits.tryAcquire(PINNED_FETCH_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
      )
        return null
      val changes =
        try {
          runCatching { fetch(path) }.getOrNull()?.map(String::lowercase)?.toSet()
        } finally {
          pinnedPermits.release()
        }
      // Only a real answer is remembered. A failed read says nothing about the branch, and caching
      // it would strand the markers off for the life of the host over one blip.
      if (changes != null) {
        synchronized(renderChangeCache) {
          if (renderChangeCache.size >= MAX_RENDER_CHANGE_ENTRIES) {
            renderChangeCache.keys.firstOrNull()?.let(renderChangeCache::remove)
          }
          renderChangeCache[previewId] = changes
        }
      }
      return changes
    }
  }

  private val renderChangeCache = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

  /** [referenceId]'s canonical reference raster as published at [commit]. See [pinnedRender]. */
  fun pinnedReference(commit: String, referenceId: String): PinnedOutcome =
    pinnedAsset(
      commit,
      branchPath(
        commit,
        referenceId,
        referenceBranchPaths,
        { it.referencesRead },
        { it.references },
      ),
    )

  /**
   * What came of a pinned read. [Missing] and [Busy] are kept apart because they are different
   * statements about the world — "that revision published no such asset", which is permanent and
   * belongs in a 404, versus "this server would not go and look right now", which is temporary and
   * belongs in a 503. Collapsing them would teach a visitor (or a link checker) that a perfectly
   * good permalink is dead.
   */
  sealed interface PinnedOutcome {
    data class Ok(val bytes: ByteArray) : PinnedOutcome {
      // Arrays get identity equals/hashCode, which a data class would silently inherit. Nothing
      // compares these today; defining them keeps that from becoming a surprise if anything does.
      override fun equals(other: Any?): Boolean =
        this === other || (other is Ok && bytes.contentEquals(other.bytes))

      override fun hashCode(): Int = bytes.contentHashCode()
    }

    data object Missing : PinnedOutcome

    data object Busy : PinnedOutcome
  }

  /**
   * Where [id]'s asset lived **at [commit]**, preferring that commit's own manifest over the tip's
   * map.
   *
   * The order is the whole point: the tip's map answers a historical question with a current
   * answer. What that costs differs by lane, and both are real:
   * - a **render** id is *derived* from its path, so a moved file is a different id — and the id a
   *   permalink names is then one the live catalog no longer contains at all. The tip's map cannot
   *   resolve it under any path, so every link made before a rename 404s;
   * - a **reference** carries its id and its raster path independently, so the id survives while
   *   the path moves. The tip's map then resolves confidently to a path that commit never had.
   *
   * The tip's map is the fallback for **an absent manifest only**, not for an id the manifest
   * doesn't list. A readable manifest is authoritative about its own revision: if it doesn't name
   * the id, that revision did not publish it, and the honest answer is nothing. Falling back there
   * would serve whatever happens to sit at today's path in that commit — a file the revision may
   * well contain, under an id its own manifest says it never published.
   */
  private fun branchPath(
    commit: String,
    id: String,
    tip: Map<String, String>,
    wasRead: (ServePinnedManifest.Paths) -> Boolean,
    select: (ServePinnedManifest.Paths) -> Map<String, String>,
  ): String? {
    val paths = pinnedManifest?.forCommit(commit)
    if (paths != null && wasRead(paths)) return select(paths)[id]
    return tip[id]
  }

  /**
   * One published asset at one commit, memoised.
   *
   * `(commit, path)` addresses immutable bytes — that is what makes the whole feature work — so a
   * hit never has to be revalidated, and the cache is what keeps a pinned page from re-fetching the
   * branch on every reload. Its value is in the same link being opened twice (a page and its
   * reload, a chat unfurl and the click that follows) rather than in holding a working set, so at
   * capacity it simply drops an arbitrary entry: with a long tail of one-off links there is no
   * recency order worth maintaining, and the cost of a miss is one small fetch.
   */
  private fun pinnedAsset(commit: String, path: String?): PinnedOutcome {
    // Either seam is enough to have a pinned lane; the outcome-reporting one is preferred
    // wherever it is wired, and the plain one remains for callers that cannot distinguish.
    if (fetchPinnedAssetOutcome == null && fetchPinnedAsset == null) return PinnedOutcome.Missing
    val safePath = ServeCatalogRevision.normalizePath(path) ?: return PinnedOutcome.Missing
    val pin = ServeCatalogRevision.normalize(commit) ?: return PinnedOutcome.Missing
    val key = "$pin/$safePath"
    pinnedCache[key]?.let {
      return PinnedOutcome.Ok(it)
    }
    // A URL this branch has already refused is refused again from memory. Without it, a page whose
    // images all 404 re-asks the branch once per image, and a visitor reloading it does so again —
    // the same wasted round trips a *successful* pin only pays once for.
    if (key in pinnedMisses) return PinnedOutcome.Missing
    // Admission. Every other lane that reaches out is bounded; this one was not, and it is the only
    // lane whose target a *request* chooses — `?at=<any syntactically valid sha>` names a fetch, so
    // an anonymous caller could otherwise open as many concurrent branch reads as it liked and hold
    // an IO worker for each. A bounded permit turns that into a queue with a ceiling, and a caller
    // that cannot get a permit in time is told the server is busy rather than told the revision
    // does not exist — those are different answers and a permalink must not confuse them.
    if (!pinnedPermits.tryAcquire(PINNED_FETCH_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS))
      return PinnedOutcome.Busy
    val outcome =
      try {
        // Re-checked under the permit: while this caller waited, the fetch it is queued behind may
        // have been for exactly this URL — the common case on a page whose images share a commit.
        pinnedCache[key]?.let { BranchFetch.Ok(it) }
          ?: runCatching {
            fetchPinnedAssetOutcome?.invoke(pin, safePath)
              ?: fetchPinnedAsset?.invoke(pin, safePath)?.let { BranchFetch.Ok(it) }
              ?: BranchFetch.NotFound
          }
            .getOrElse { BranchFetch.Transport(it::class.simpleName ?: "error") }
      } finally {
        pinnedPermits.release()
      }
    val bytes = outcome.bytesOrNull
    if (bytes == null) {
      // ONLY a real absence is remembered. `(commit, path)` is immutable, so "that revision has no
      // such file" is permanent and worth keeping — but a throttle or a 503 says nothing about the
      // revision, and memoising one turns a blip into a hole that outlives it. That was the
      // accepted cost of not being able to tell them apart; [BranchFetch] removes the excuse.
      if (outcome == BranchFetch.NotFound) remember(pinnedMisses, key, MAX_PINNED_MISS_ENTRIES)
      return PinnedOutcome.Missing
    }
    synchronized(pinnedCache) {
      if (pinnedCache.size >= MAX_PINNED_CACHE_ENTRIES) {
        pinnedCache.keys.firstOrNull()?.let(pinnedCache::remove)
      }
      pinnedCache[key] = bytes
    }
    return PinnedOutcome.Ok(bytes)
  }

  private fun remember(set: MutableSet<String>, key: String, max: Int) {
    synchronized(set) {
      if (set.size >= max) set.firstOrNull()?.let(set::remove)
      set.add(key)
    }
  }

  private val pinnedCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

  /**
   * URLs this branch answered nothing for. Deliberately keyed like [pinnedCache] and deliberately
   * *not* time-bounded: `(commit, path)` is immutable, so "that revision has no such file" is a
   * permanent fact — unlike a transient failure, which this cannot tell apart and therefore
   * remembers too. That is the accepted cost: the set is small and drops entries under pressure, so
   * a blip strands a pin until eviction rather than for the life of the process, and the far more
   * common case (a genuinely absent asset, asked for repeatedly) stops costing round trips.
   */
  private val pinnedMisses: MutableSet<String> =
    java.util.Collections.synchronizedSet(LinkedHashSet())

  private val pinnedPermits = java.util.concurrent.Semaphore(MAX_CONCURRENT_PINNED_FETCHES)

  /** [previewId]'s baked PNG only if it is already local — never fetches. */
  private fun localBakedPng(previewId: String): okio.Path? =
    previewFile(previewId, PNG_SUFFIX)?.toOkioPath()?.takeIf(fileSystem::exists)

  private val fillLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

  /**
   * The staged file for one animated capture, fetching it on first request.
   *
   * Deliberately a near-copy of [bakedPngFile] rather than a shared generic: the two lanes differ
   * in the one place that matters (the extension, which a browser is not free to guess) and share
   * the part that is merely mechanical. Folding them together would mean threading a suffix through
   * the fill path, which is how a capture ends up written under a still's name.
   */
  private fun motionFile(motionId: String, extension: String): BranchFetch {
    if (motionId !in declaredMotionIds) return BranchFetch.NotFound
    if (extension !in MOTION_EXTENSIONS) return BranchFetch.NotFound
    // The requested suffix must be the one THIS capture was published as, not merely a format the
    // lane supports. Checking only the allowlist would let `<id>.gif` serve an APNG's bytes typed
    // as
    // a GIF: the same bytes, a content type the requester chose, and a browser that renders one
    // frame and stops. The declared branch path is the authority on which it is.
    if (motionBranchPaths[motionId]?.endsWith(extension) != true) return BranchFetch.NotFound
    val path = previewFile(motionId, extension)?.toOkioPath() ?: return BranchFetch.NotFound
    if (fileSystem.exists(path)) return readStagedMotion(path)
    val fetch = fetchMotion ?: return BranchFetch.NotFound
    // Keyed distinctly from the baked lane: a capture and its sibling still share an id, so one
    // lock namespace would have a cold capture fetch block a warm sticker read on the same card.
    synchronized(fillLocks.computeIfAbsent("$MOTION_LOCK_PREFIX$motionId") { Any() }) {
      if (fileSystem.exists(path)) return readStagedMotion(path)
      val outcome = runCatching {
        fetch(motionId)
      }
        .getOrElse { BranchFetch.Transport(it::class.simpleName ?: "error") }
      // A failure is returned AS ITSELF rather than flattened: a throttle here is what makes the
      // route answer 503 instead of telling the reader the capture was never published.
      val bytes = outcome.bytesOrNull ?: return outcome
      // Staging failing is not the branch's fault and not a missing asset either — the bytes are in
      // hand, so serve them and let the next request try the disk again.
      runCatching {
        path.parent?.let(fileSystem::createDirectories)
        val partial = path.parent!!.resolve(path.name + PARTIAL_SUFFIX)
        fileSystem.write(partial) { write(bytes) }
        fileSystem.atomicMove(partial, path)
      }
      return BranchFetch.Ok(bytes)
    }
  }

  /** A staged capture read back off disk; an unreadable stage is transient, not a missing asset. */
  private fun readStagedMotion(path: okio.Path): BranchFetch = runCatching {
    BranchFetch.Ok(fileSystem.read(path) { readByteArray() })
  }
    .getOrElse { BranchFetch.Transport(it::class.simpleName ?: "error") }

  /**
   * The bytes of one published capture, or null when this host can't serve it.
   *
   * The only motion entry point: a caller names an id and an extension it read off the served
   * manifest, and gets bytes or nothing. Both are checked against what the catalog declared, so a
   * request can neither invent an id nor choose the suffix its response is typed with.
   */
  override fun motionRead(motionId: String, extension: String): BranchFetch =
    motionFile(motionId, extension)

  /** The branch path of a declared capture, for a pinned request. */
  fun motionBranchPath(motionId: String): String? = motionBranchPaths[motionId]

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
    // A declared capture gutter answers on its own — it needs no vector, so a preview the figma
    // pass hasn't reached (or never will) still gets its gutter trimmed rather than waiting on a
    // file that decides a different question.
    val gutter = declaredCaptureGutter(previewId)
    val svgOutstanding = figmaDir != null && figmaSvgFileFor(previewId) == null
    // While a vector may still be landing, a gutter crop is a provisional answer: serve it (a card
    // that waits is a card drawn at the wrong size) but do NOT memoise it, or the vector would
    // never be reconsidered until the host is rebuilt. Only a decision made against files that are
    // actually present is cached — the same rule the guard above states.
    if (svgOutstanding) return if (gutter == null) null else computeContentCrop(previewId, gutter)
    val computed = java.util.Optional.ofNullable(computeContentCrop(previewId, gutter))
    cropCache[previewId] = computed
    return computed.orElse(null)
  }

  /**
   * The `@CaptureGutter` this preview declared, in render pixels, from whichever manifest this
   * session has: an uploaded bundle's root `previews.json` (dp, resolved against its own density)
   * or a published catalog's `previews/variants.json` (already pixels). Null when it declares none.
   */
  private fun declaredCaptureGutter(previewId: String): ServeCatalogStore.CaptureGutterPx? =
    (previewParamsById[previewId]?.asPreviewParamsMeta() ?: variantMeta[previewId]?.previewParams)
      ?.captureGutter
      ?.takeUnless { it.isEmpty() }

  private val cropCache =
    java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<ContentCrop>>()

  private fun computeContentCrop(
    previewId: String,
    gutter: ServeCatalogStore.CaptureGutterPx?,
  ): ContentCrop? {
    // Same per-variant-first resolution as `renderSvg` — a variant vector's viewBox reflects the
    // exact render this preview's PNG shows.
    val svgFile = figmaSvgFileFor(previewId)
    // Deliberately the already-local file, NOT `bakedPngFile`: the landing page computes a crop for
    // every card while building its HTML, so filling here would serially download a whole cold
    // catalog on the first page request — the exact stall lazy fetching exists to remove, moved
    // onto the request thread. A cold card simply renders uncropped; the browser's own
    // `/render/<id>.png` request lands the file, and the next page build crops it.
    val png = localBakedPng(previewId) ?: return null
    return try {
      val bytes = fileSystem.read(png) { readByteArray() }
      val (rw, rh) = WebEscaping.pngDimensions(bytes.copyOf(PNG_HEADER_BYTES.toInt()))
      // Union the render's actual non-transparent extent into the crop box so a focus ring or
      // disabled outline drawn outside the layout-derived figma box is never clipped.
      val fromSvg =
        svgFile
          ?.let {
            // The drawn extent is unioned in so a focus ring or disabled outline OUTSIDE the
            // layout-derived figma box is never clipped — but on a guttered render those same
            // pixels are the shadow the gutter reserved room for, and they are going to bleed
            // rather than be clipped. Unioning them there would grow the window past the component
            // and draw it smaller than its siblings, which is the whole complaint.
            val bounds = if (gutter == null) pngAlphaBounds(bytes) else null
            computeThumbCrop(fileSystem.read(it) { readUtf8() }, rw, rh, bounds)
          }
          // A vector crop on a GUTTERED render must not hide its overflow either: the box it frames
          // is the component, and the pixels the gutter holds are that component's shadow. Clipping
          // them is the bug the gutter exists to prevent, whichever crop decided the box.
          ?.let { if (gutter == null) it else it.copy(clip = false) }
      // The vector wins where it applies: it frames the component inside a canvas the render was
      // drawn on (a Wear watch face), which is a tighter question than "how much margin did the
      // capture add", and it already accounts for the gutter's pixels by unioning the drawn extent.
      fromSvg ?: gutter?.let { computeGutterCrop(it.left, it.top, it.right, it.bottom, rw, rh) }
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

    /**
     * Extensions a published capture may be served under — closed, and checked on every request.
     */
    val MOTION_EXTENSIONS = listOf(".apng", ".gif")

    /** Namespaces the motion fill locks apart from the baked ones, which share an id space. */
    private const val MOTION_LOCK_PREFIX = "motion:"

    /** Namespaces the render-change locks in the shared [fillLocks] map. */
    private const val RENDER_CHANGE_LOCK_PREFIX = "render-changes:"
    private const val RENDER_ERROR_SUFFIX = ".error.json"
    private const val RENDER_ERROR_SCHEMA = "compose-preview-error/v1"
    /** Suffix of the sibling a lazy fill writes before moving it into place atomically. */
    private const val PARTIAL_SUFFIX = ".partial"

    /**
     * How many pinned (`?at=<sha>`) assets one catalog host keeps resident. Small on purpose: this
     * is a de-duplicator for the same permalink being opened again, not a working set — a pinned
     * URL is by nature a one-off link into the past, and holding hundreds of historical renders
     * would trade a real memory cost against traffic that mostly never repeats.
     */
    private const val MAX_PINNED_CACHE_ENTRIES = 32

    /**
     * How many URLs this branch has already refused are remembered, so a page of absent pinned
     * images stops costing round trips. Larger than the hit cache because a miss costs bytes to
     * remember and a hit costs a whole PNG.
     */
    private const val MAX_PINNED_MISS_ENTRIES = 256

    /**
     * How many previews' render-change sets stay resident. Generous next to the pinned caches
     * because an entry is a handful of shas rather than a PNG, and the access pattern is the
     * opposite of a permalink's: a reader browsing a catalog opens the revision menu on preview
     * after preview, and every repeat within a load is an answer that cannot have changed.
     */
    private const val MAX_RENDER_CHANGE_ENTRIES = 512

    /**
     * Concurrent branch reads the pinned lane may have in flight. Small: these are small files off
     * a CDN, the caches absorb repeats, and the number exists to bound what an anonymous caller can
     * make this server do — not to make pinned pages fast.
     */
    private const val MAX_CONCURRENT_PINNED_FETCHES = 4

    /**
     * How long a pinned read waits for a permit before answering "busy". Long enough that an
     * ordinary page's images queue through rather than failing, short enough that a flood is shed
     * instead of parking request threads.
     */
    private const val PINNED_FETCH_WAIT_SECONDS = 5L

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

// Fallback render density for a cmp-jvm render when `previews.json` declares none — the desktop
// renderer's own default (a 200dp preview bakes to 525px), so an unspecified preview still renders
// at the density its baked PNG was captured with. File-level rather than on the companion because
// the params→meta mapping below resolves a capture gutter's dp against it too.
private const val DEFAULT_RENDER_DENSITY = 2.625f

/**
 * Whether a `@Preview(locale = …)` render was composed right-to-left — the direction the renderer
 * resolved a capture gutter's leading / trailing edges against.
 *
 * The renderer's own rule, out of the renderer's own module: the bidi pseudolocale first (`ar-XB`
 * mirrors, `en-XA` does not), then the real language table. A second copy of that table here would
 * be a thing to drift.
 */
private fun rendersRightToLeft(locale: String?): Boolean {
  if (locale.isNullOrBlank()) return false
  Pseudolocale.fromTag(locale)?.let {
    return it.isRtl
  }
  return LocaleDirection.isRtl(locale)
}

/**
 * A bundle manifest's `@Preview` params in the shape a catalog publishes them.
 *
 * The two sources describe the same annotation and are reduced to one type here rather than being
 * read separately at the call site, so "which fields does a ground need?" is answered once. Only
 * the fields a browse surface consults before opening a daemon cross over — the rest of
 * `PreviewParams` (locale, font scale, density) belongs to the render, not to how it is presented.
 */
private fun ee.schimke.composeai.previewdata.PreviewParams.asPreviewParamsMeta():
  ServeCatalogStore.PreviewParamsMeta =
  ServeCatalogStore.PreviewParamsMeta(
    uiMode = uiMode,
    showBackground = showBackground,
    backgroundColor = backgroundColor,
    device = device,
    widthDp = widthDp,
    heightDp = heightDp,
    // The one field that is derived rather than copied: the annotation states dp, and every
    // consumer of this record works in the render's pixels. `density` is on this manifest, so the
    // bundle path resolves it here the same way the exporter resolves it for a published catalog —
    // per edge, rounded on its own, which is what the renderer did when it grew the canvas.
    captureGutter =
      captureGutter?.let { gutter ->
        val scale = density?.takeIf { it > 0f } ?: DEFAULT_RENDER_DENSITY
        fun px(dp: Int) = (dp.coerceAtLeast(0) * scale).roundToInt()
        // Leading/trailing → left/right against the direction this render was composed in — the
        // same resolution the renderer performed when it placed the component inset.
        val rtl = rendersRightToLeft(locale)
        ServeCatalogStore.CaptureGutterPx(
            left = px(if (rtl) gutter.end else gutter.start),
            top = px(gutter.top),
            right = px(if (rtl) gutter.start else gutter.end),
            bottom = px(gutter.bottom),
          )
          .takeUnless { it.isEmpty() }
      },
  )

@Serializable
private data class BundleRenderError(
  val schema: String = "",
  val exception: String = "RenderError",
  val message: String = "",
  val topAppFrame: RenderFailureFrame? = null,
  val stackTrace: String? = null,
)
