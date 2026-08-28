package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleClasspathHydration
import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.bundle.BundleVerifier
import ee.schimke.composeai.bundle.TrustStore
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration
import ee.schimke.composeai.designpages.DesignPagesJson
import ee.schimke.composeai.designpages.DesignPagesManifest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

data class PerPreviewBundleAccess(
  val available: (daemonId: String) -> Boolean,
  val fetch: (daemonId: String) -> File?,
)

/**
 * Serves the **design systems we publish** on a public preview server by fetching a
 * `design-artifacts/<system>` catalog (`catalog.json` + `images/`) from GitHub and registering it
 * as a read-only [ServeBundleHost] session — the second pillar of the public server, alongside
 * client-uploaded bundles ([ServeBundleStore]).
 *
 * Trust is by **origin**: the catalog isn't a signed bundle, it's content pulled from a branch the
 * operator listed in the [TrustStore]'s `branches`. When the `repo@branch` is trusted the session
 * carries [BundleVerifier.Verdict.Trusted] with a [BundleVerifier.Basis.Branch]; otherwise it
 * serves as `Unverified` (the images are still data — they execute no code — so they're shown
 * either way, just badged). This is what makes browsing `design-artifacts/<system>` and a live,
 * customisable preview the same render output.
 *
 * Fetch surface: a fixed `https://raw.githubusercontent.com/<repo>/<branch>/…` base derived from
 * the **operator-supplied** `--catalogs` / `--catalog-repo` flags — not client input — so there's
 * no SSRF lever here (unlike the `?url=` upload path). [fetch] is injected so tests can stub the
 * network. Catalog assets retain a tight per-file cap; executable live bundles use the same larger
 * envelope as uploaded/startup bundles.
 */
class ServeCatalogStore(
  private val root: File,
  private val register: (name: String, host: ServeBundleHost) -> Unit,
  /**
   * The producer-trust store, read **per fetch** rather than captured, so a producer added through
   * the admin API (or hand-edited into producers.json) is in force for the next catalog load and
   * the next branch refresh without a restart.
   */
  private val trust: () -> TrustStore,
  private val repo: String = DEFAULT_REPO,
  private val branchPrefix: String = DEFAULT_BRANCH_PREFIX,
  private val fetch: ((String) -> ByteArray?)? = null,
  /**
   * Runs the post-publish vector fills ([scheduleFigmaSvgFetch]). Single-threaded by default so the
   * background lane can never outweigh the request path, and daemon so it never holds up shutdown.
   * A test supplies a same-thread or capturing executor to make the pass deterministic.
   */
  private val figmaExecutor: java.util.concurrent.Executor =
    Executors.newSingleThreadExecutor { r ->
      Thread(r, "serve-catalog-figma").apply { isDaemon = true }
    },
  /**
   * The branch transport. Outcome-shaped rather than `ByteArray?` so that **every** lane reaches
   * the network through the same injected seam: the pinned lane needs to know *why* a read failed,
   * and a parallel outcome-only seam beside a bytes-only one is how one of them ends up bypassed —
   * a store given an authenticated, proxied or recorded transport would have issued a direct
   * request to GitHub for pins alone. One seam, one answer shape, nothing to forget to wire.
   */
  private val networkFetch: (url: String, maxBytes: Long) -> BranchFetch = ::httpFetchOutcome,
  /**
   * Existence probe (a `HEAD`), outcome-shaped like [networkFetch] so it is counted too. A probe
   * that answered a bare `Boolean` collapsed "absent" and "the branch refused us" into `false`, so
   * a throttled executable-bundle lane went quiet with `/status.json` still reading healthy — the
   * same blindness this telemetry exists to remove, one seam over.
   */
  private val networkProbe: (url: String) -> BranchFetch = ::httpProbeOutcome,
  private val maxImages: Int = DEFAULT_MAX_IMAGES,
  /**
   * Called when a catalog publishes: the system id → the local directory its in-browser Wasm app
   * (`webRender` in `catalog.json`) was written to, or **null when this generation has no usable
   * app**. The server then serves it at `/wasm/<system>/`, so the **CMP-Wasm tier rides the same
   * trusted branch as the catalog** — a deployed public server needs no local `--wasm-dir` build,
   * just `--catalogs`.
   *
   * Null is not the same as not calling this at all, and the difference is why it is invoked on
   * every publish. A refreshed catalog that dropped its `webRender`, or whose app failed to fetch,
   * leaves the previous generation's app on disk and readable — generation directories outlive
   * their host by a refresh — so a registration nobody withdrew would keep the viewer's "Run in
   * browser" toggle serving the *old* catalog's code beside the new catalog's pages, until the
   * sweep turned the same toggle into 404s. So the registration moves with the generation: it is
   * made at the moment the new host is published, and null withdraws it.
   */
  private val registerWasm: (system: String, dir: File?) -> Unit = { _, _ -> },
  /**
   * Invoked when a **post-publish** lane finished with a transient failure of its own.
   *
   * [Result.Ok.incomplete] can only speak for what the load itself read: the vector fills and the
   * rc-compare pull run on [figmaExecutor] *after* the catalog is published, so a throttle there
   * lands long after the result was handed back and the branch head recorded. This is how they say
   * so — the caller wires it to `ServeCatalogRefresher.forgetHeads`, which un-settles the revision
   * so the next tick re-reads it.
   *
   * After the fact rather than before, which used to leave a window: an invalidation arriving
   * between the load returning and the head being recorded — or before the startup loader seeded
   * any head at all — had nothing to remove. `ServeCatalogRefresher` closes it by leaving a
   * *pending* mark rather than only removing a head, and consuming that mark everywhere it would
   * otherwise record one.
   */
  private val onPostPublishIncomplete: (system: String) -> Unit = {},
  private val serverSideRenderEnabled: Boolean = false,
  /**
   * Trusted server-side re-render from a carried **executable bundle** (opt-in,
   * `--allow-render-trusted`). When a catalog is `Trusted` AND declares a `liveBundle` (`{path,
   * file}`), the bundle is fetched and this is invoked to stand up a daemon-backed, re-renderable
   * session built straight from it — no Gradle build, no worktree, no repo clone. It's handed the
   * catalog-id→daemon-id `alias` and a `bakedFallback` factory so it can front the baked catalog
   * with the daemon ([ServeCatalogLiveHost]) rather than replace it — the published `/p/<id>` links
   * keep resolving and unmapped ids fall back to baked PNGs. Preferred over [buildTrustedSource]
   * (tried first): returns true when it registered such a session (then both the Gradle source path
   * and the plain static registration are skipped); false ⇒ fall back to [buildTrustedSource], then
   * the static catalog. Default ⇒ never. The callback owns the `--allow-render-trusted` gate; this
   * store only reaches it for an already-`Trusted` catalog, and only after the whole declared
   * bundle file fetched cleanly (fail-closed, like [fetchWasmApp]). `externalResourcesDir` is the
   * rehydrated font/resource pool the daemon adds to its classpath (see
   * [rehydrateExternalResources]) — null when the bundle carried its resources inline (a
   * self-contained pack).
   *
   * `fetchPerPreviewBundle` lifts the **per-preview live lane** (the default render path, with the
   * monolithic bundle as fallback): given a daemon-preview id it fetches that preview's own FULL
   * split bundle (`<liveBundle.path>/previews/<daemon-id>.png` on the same trusted branch,
   * fail-closed) into a local file, or null when the branch ships none / the fetch fails. The
   * per-preview FULL bundles were split from the *externalised* monolithic bundle, so they share
   * its font pool — the caller re-uses the monolithic bundle's already-rehydrated
   * `externalResourcesDir` rather than re-fetching. The builder pools + materialises these on
   * demand and prefers them over the monolithic daemon; a null resolve falls back to it, so the
   * lane is exercised routinely without ever regressing.
   */
  private val buildTrustedBundle:
    (
      system: String,
      bundleFile: File,
      externalResourcesDir: File?,
      alias: Map<String, String>,
      bakedFallback: () -> ServeHost,
      perPreviewBundle: PerPreviewBundleAccess,
    ) -> Boolean =
    { _, _, _, _, _, _ ->
      false
    },
  /**
   * Multi-module counterpart of [buildTrustedBundle]. Each entry owns one classpath and alias set.
   */
  private val buildTrustedBundles:
    (
      system: String,
      bundles: List<TrustedModuleBundle>,
      bakedFallback: () -> ServeHost,
    ) -> Boolean =
    { _, _, _ ->
      false
    },
  /** Publish verified carried bundles to non-daemon consumers such as the playground compiler. */
  private val recordTrustedBundles: (system: String, bundles: List<VerifiedModuleBundle>) -> Unit =
    { _, _ ->
    },
  /** Clear compile targets when a successful refresh no longer carries a usable trusted bundle. */
  private val clearTrustedBundles: (system: String) -> Unit = {},
  /**
   * Trusted server-side re-render (opt-in, `--allow-render-trusted`). When a catalog is `Trusted`
   * AND declares a `source` (`{repo, ref, module}`), this is invoked to stand up a **daemon-backed,
   * re-renderable** session built from that source — so the viewer's controls re-render live at
   * full fidelity instead of replaying baked PNGs. Like [buildTrustedBundle] it's handed the
   * catalog-id→daemon-id `alias` + a `bakedFallback` factory and fronts the baked catalog with the
   * daemon rather than replacing it. Returns true when it registered such a session (then the plain
   * static registration is skipped); false ⇒ fall back to the static catalog. Default ⇒ never (the
   * safe default + what every public deploy uses). The callback owns the ref-allowlist + build
   * gates; this store only reaches it for an already-`Trusted` catalog.
   */
  private val buildTrustedSource:
    (
      system: String,
      source: CatalogSource,
      alias: Map<String, String>,
      bakedFallback: () -> ServeHost,
    ) -> Boolean =
    { _, _, _, _ ->
      false
    },
  /**
   * Durable, content-addressed home for the heavy bytes this store fetches — the executable
   * `liveBundle`, its per-preview splits, and the externalised resource pool. See
   * [CatalogBlobPool].
   *
   * Defaults to a pool rooted under [root], which is exactly the behaviour that existed before it
   * was configurable: shared across systems and reloads, discarded with the process. `serve
   * --catalog-cache-dir` supplies a durable root instead, and that is the whole of the difference —
   * nothing below asks which kind it was given.
   */
  private val blobs: CatalogBlobPool = CatalogBlobPool(File(root, BLOB_CACHE_DIR)),
) {

  /** A catalog's buildable source — where to check out + build to re-render it live. */
  data class CatalogSource(val repo: String, val ref: String, val module: String)

  data class TrustedModuleBundle(
    val module: String,
    val file: File,
    val externalResourcesDir: File?,
    val alias: Map<String, String>,
    val perPreviewBundle: PerPreviewBundleAccess,
  )

  /** Minimal verified carried-bundle identity for compile consumers that do not run its daemon. */
  data class VerifiedModuleBundle(val module: String, val file: File)

  /**
   * Build the **catalog-id → daemon-preview-id** alias from a catalog's images: each image's
   * route-safe id ([previewIdFor] of its `path`) mapped to the `previewId` the exporter recorded.
   * Images with no `previewId` (Android-only variants, older catalogs) are skipped — they have no
   * live lane. Later duplicates keep the first mapping (the theme/state variants are distinct ids,
   * so collisions shouldn't arise).
   *
   * The catalog's live-only [Catalog.deferred] records are aliased the same way. They have no baked
   * PNG at all, so the alias isn't an enhancement for them — it is the ONLY way they can be served,
   * and the store registers them only where this mapping exists.
   */
  private fun previewAliasFor(catalog: Catalog): Map<String, String> {
    val alias = LinkedHashMap<String, String>()
    for (component in catalog.components) {
      for (image in component.images) {
        val daemonId = image.previewId?.takeIf { it.isNotBlank() } ?: continue
        alias.putIfAbsent(previewIdFor(image.path), daemonId)
      }
    }
    for (record in catalog.deferred) {
      val id = deferredPreviewIdOf(record) ?: continue
      alias.putIfAbsent(id, record.daemonId ?: continue)
    }
    return alias
  }

  /**
   * The route-safe preview id a [Deferred] record would be served under, or null when it can't be
   * served: no recorded `path` (an older catalog, or one whose export detected naming drift), a
   * path outside `images/` or attempting traversal (same containment check the baked images get —
   * the branch is trusted, but a garbled catalog must not mint odd ids), or no daemon twin to
   * render it.
   */
  private fun deferredPreviewIdOf(record: Deferred): String? {
    val path = record.path?.takeIf { it.isNotBlank() } ?: return null
    if (!path.startsWith("$IMAGES_DIR/") || !path.endsWith(".png")) return null
    if (".." in path.split("/")) return null
    if (record.daemonId == null) return null
    return previewIdFor(path)
  }

  /**
   * The route-safe id a published capture is served under, or null when it can't be served.
   *
   * Same containment reasoning as the baked images and the deferred records: the branch is trusted,
   * but a garbled or compromised catalog must not mint odd ids or escape the staged dir. The
   * extension is checked against a closed list rather than merely being *some* suffix — these bytes
   * are handed to a browser, and an open-ended suffix taken from fetched JSON is how a catalog
   * would get to choose the content type it is served under.
   *
   * The id is derived exactly as a still's is, so a capture the export named from its sibling
   * sticker lands on that sticker's id plus whatever disambiguating segment it carried
   * (`__interaction` / `__anim`). Nothing here re-derives the export's naming rule — the pairing to
   * a card is done by `theme` in the load loop, not by taking the name apart.
   */
  private fun motionPreviewIdOf(path: String): String? {
    if (path.isBlank() || !path.startsWith("$MOTION_DIR/")) return null
    val extension = MOTION_EXTENSIONS.firstOrNull { path.endsWith(it) } ?: return null
    if (".." in path.split("/")) return null
    // Flattened exactly as a still's path is, so a capture named from its sibling sticker shares
    // that sticker's id — the two never collide because they are served from separate routes, and a
    // second capture on the same card keeps its own id via the `__interaction` / `__anim` segment
    // the export carried through.
    return path.removePrefix("$MOTION_DIR/").removeSuffix(extension).replace("/", "__")
  }

  /** The extension [path] carries, or null when it is not a servable capture. */
  private fun motionExtensionOf(path: String): String? = MOTION_EXTENSIONS.firstOrNull {
    path.endsWith(it)
  }

  /**
   * One declared baked image, resolved to everything the load loop needs *before* any fetch: its
   * route-safe [id], the staged [target] it writes to (null when that path escaped the previews dir
   * — planned anyway so it still counts as declared, then skipped), and the component context that
   * tags its variant metadata. Planning separately from fetching is what lets the fetch run
   * concurrently while the loop that consumes it stays sequential and order-preserving.
   */
  private data class PlannedImage(
    val path: String,
    val id: String,
    val target: File?,
    val image: Image,
    val componentId: String?,
    val section: String?,
    val group: String?,
    val componentSourceFile: String?,
    val componentSourceModule: String?,
    val componentBodyLine: Int?,
    /** The owning component's authored one-line description, tagged onto each of its renders. */
    val componentCaption: String?,
    /**
     * The owning component's published captures, paired to this image by theme in the load loop.
     */
    val componentMotion: List<Motion>,
  )

  /**
   * The declared hero resolved against [bakedIds], or null when the catalog declares none / it
   * matches nothing. Mirrors [ServeBundleHost.declaredHeroPreviewId]: an exact preview id wins,
   * else a `componentId` / preview-function name is matched against the slug head using the same
   * normalisation the exporter used, so a spec can name `"Template/TimeText"` and hit
   * `template-timetext__ideal__…`. Kept in step with that resolver — they must agree, or the image
   * fetched ahead of publishing is not the one the front door paints.
   */
  private fun heroPreviewIdFor(catalog: Catalog, bakedIds: Set<String>): String? {
    val hero = catalog.display?.hero?.takeIf { it.isNotBlank() } ?: return null
    if (hero in bakedIds) return hero
    val wanted = heroSlugOf(hero)
    return bakedIds.firstOrNull { heroSlugOf(it.substringBefore(SLUG_SEPARATOR)) == wanted }
  }

  sealed interface Result {
    data class Ok(
      val system: String,
      val previewCount: Int,
      val trust: String,
      val failedRenderCount: Int = 0,
      /**
       * Some optional artifact could not be fetched **right now** — throttled, the branch host
       * unwell, or no answer at all.
       *
       * The catalog is registered and served either way; every writer beside the required ones is
       * fail-soft by design, and a catalog missing its activity feed is far better than no catalog.
       * What this says is that the absence is not the producer's: asking again could answer
       * differently, so the caller must not record this revision as settled.
       *
       * Without it a single throttled request drops that catalog's parity issue index — or its
       * whole acceptance surface — until the branch head next moves, with nothing anywhere
       * reporting it. See `ServeCatalogRefresher.checkOne`, which is what declines to advance the
       * recorded head, and `seedInitialHeads`, which does the same at startup.
       */
      val incomplete: Boolean = false,
    ) : Result

    data class Failed(val system: String, val reason: String) : Result
  }

  /**
   * Fetch the `<branchPrefix><system>` catalog, lay its images out as previews, and register it.
   *
   * [sourceRepo] / [sourceBranchPrefix] override the store's defaults for this one system, so a
   * single server can serve catalogs published to *different* repos (e.g. `compose-m3` from
   * `yschimke/compose-ai-tools` and `meshcore-mobile` from `yschimke/meshcore-mobile`, each in its
   * own `design-artifacts/<system>` branch). Null ⇒ the store's [repo] / [branchPrefix]. The
   * branch-trust verdict is computed against whichever repo actually served the catalog.
   */
  fun load(system: String, sourceRepo: String? = null, sourceBranchPrefix: String? = null): Result =
    inFetchScope {
      load(system, sourceRepo, sourceBranchPrefix, it)
    }

  private fun load(
    system: String,
    sourceRepo: String?,
    sourceBranchPrefix: String?,
    scope: FetchScope,
  ): Result {
    val safe = ServeBundleStore.sanitizeName(system) ?: return Result.Failed(system, "invalid name")
    // Bumped per load so a background pass started for an earlier generation of this catalog stops
    // instead of writing its vectors into the refreshed one.
    val generation = generations.merge(system, 1, Int::plus)!!
    val repo = sourceRepo?.takeIf { it.isNotBlank() } ?: this.repo
    val branchPrefix = sourceBranchPrefix?.takeIf { it.isNotBlank() } ?: this.branchPrefix
    val branch = "$branchPrefix$system"

    // The branch's publish history, read BEFORE anything else — because its head decides what the
    // rest of this load reads. Its tail is what a visitor can pin back to. Best-effort: an empty
    // list leaves the catalog serving exactly as before, minus the permalink affordance.
    val revisions = fetchRevisions(repo, branch)
    val deliveryCommit = revisions.firstOrNull()?.commit

    // **A load reads one commit, not a branch.** Resolving the tip and then fetching by branch name
    // leaves a whole load's worth of requests — catalog.json, then hundreds of assets over several
    // minutes — free to straddle a publish landing mid-flight: the pages would advertise one
    // revision while serving a mixture of two, and a permalink minted from that page would name a
    // commit whose bytes the visitor never saw. Pinning the base to the sha we resolved makes the
    // load atomic by construction. It also means the ordinary served catalog and a pin to that same
    // revision read the identical URLs, so the two can never disagree.
    //
    // Falls back to the branch when the feed could not be read: an un-pinned load is what this did
    // before permalinks existed, and it is strictly better than not serving the catalog at all.
    val base =
      deliveryCommit?.let { "https://raw.githubusercontent.com/$repo/$it/" }
        ?: "https://raw.githubusercontent.com/$repo/$branch/"
    // Whether [base] names one immutable tree, and therefore whether the heavy executable bundles
    // this load fetches may be cached by URL. See [CatalogBlobPool] — an un-pinned base is the
    // branch ref, which is a moving target and must not populate a cache keyed on it.
    val pinned = deliveryCommit != null

    val catalogBytes =
      try {
        fetchCatalogAsset(base + CATALOG_FILE)
      } catch (e: Exception) {
        return Result.Failed(system, "could not fetch catalog.json: ${e.message}")
      } ?: return Result.Failed(system, "could not fetch $base$CATALOG_FILE")

    val catalog =
      try {
        json.decodeFromString(Catalog.serializer(), catalogBytes.toString(Charsets.UTF_8))
      } catch (e: Exception) {
        val detail = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "unknown error"
        return Result.Failed(system, "could not parse catalog.json: $detail")
      }

    // One compact file answers preview availability for the whole revision menu. Older branches
    // have no index and deliberately fail open; the pinned catalog remains authoritative on click.
    val revisionPreviewIds = runCatching {
      ServeRevisionPreviewIndex.parse(fetchCatalogAsset(base + ServeRevisionPreviewIndex.FILE_NAME))
        ?.previewsByCommit()
    }
      .getOrNull()

    // Stage the fetch so a re-load (ServeCatalogRefresher) can't turn a healthy catalog into 404s:
    // fetch the images into a sibling `.staging` dir and only swap it over the live `dir` once we
    // know we have a usable catalog (count > 0). A partial/failed fetch (e.g. images temporarily
    // unavailable) leaves the currently-served `dir` untouched. The wasm / figma / liveBundle steps
    // below run *after* the swap and are all fail-soft (they disable their tier or fall back to the
    // baked host), so they never leave the catalog broken — only the image fetch is a hard failure,
    // and that's what staging protects.
    //
    // **The live directory is generation-scoped**, `<root>/<system>/g<n>`, and a load never writes
    // into the one that is currently registered. The host and the bytes it reads lazily
    // (`knownDifferenceArtifact`, rasters, preview PNGs) therefore change together: the previous
    // generation keeps serving, from its own untouched files, until the new host is registered — at
    // which point both switch at once. Replacing a shared `<root>/<system>` in place is what made
    // the two divisible, and the gap was not small: everything between the swap and the
    // registration (the Wasm app, the vectors, the live bundles) is network work, and a read that
    // landed in it served the new generation's pixels beside the old generation's metadata, with
    // nothing to notice.
    //
    // Retirement is deferred to the *next* load's sweep rather than done here, which gives every
    // in-flight request against the outgoing host a full refresh interval to finish reading. See
    // [retireStaleGenerations].
    val systemRoot = File(root, safe)
    val dir = File(systemRoot, "$GENERATION_DIR_PREFIX$generation")
    val staging = File(systemRoot, STAGING_DIR)
    retireStaleGenerations(safe, systemRoot, keep = setOf(dir.name, staging.name))
    staging.deleteRecursively()
    // Only ever a leftover: the counter climbs within a process, so this name cannot be the live
    // generation — but it can be one a previous process left behind, and the rename below needs the
    // path free.
    dir.deleteRecursively()
    val previewsDir = File(staging, "previews")
    val previewsRoot = previewsDir.canonicalFile.toPath()
    var count = 0
    // How many baked images the catalog DECLARES (before any fetch) — see the check after the
    // loops.
    var declaredImages = 0
    // The component slugs whose baked figma-svg to fetch (a slug is the preview id up to `__`).
    val slugs = LinkedHashSet<String>()
    // Exact vectors mirror each successfully fetched images/<slug>/<variant>.png. Carrying these
    // preserves the baked theme/locale/size axis; the flat slug vector is only a legacy fallback.
    val variantSvgPaths = LinkedHashSet<String>()
    // Per-preview state/theme, carried to the host via `previews/variants.json` so the grid can
    // fold
    // non-default states into one card and the viewer can offer a state switcher. Only populated
    // for
    // renders that actually carry a state or theme; plain (stateless) previews stay out of the map.
    val variants = LinkedHashMap<String, VariantMeta>()
    // Every baked image the catalog declares, flattened into catalog order with the component
    // context each one needs, WITHOUT fetching anything yet. The same containment filter the
    // original loop applied runs here (so nothing extra is ever requested); an image whose
    // destination escapes the staged previews dir is planned with a null target so it still counts
    // toward `declaredImages` and is then skipped, exactly as before.
    // Captions are authored per COMPONENT, while live-only records and render failures are listed
    // per render — so they name a componentId and nothing else. This lets those rows borrow the
    // caption of the component they belong to instead of being the only cards that can't say what
    // they are.
    // A WHOLLY deferred component never reaches `components[]` at all, and the export writes its
    // caption onto the deferred record instead — so the lookup reads both, components first. That
    // also covers its deferred *variant* records, which carry no caption of their own and are
    // meant to inherit the entry's.
    val captionByComponentId: Map<String?, String?> = buildMap {
      catalog.deferred.forEach { d ->
        val id = d.componentId?.takeIf { it.isNotBlank() } ?: return@forEach
        val caption = d.caption?.takeIf { it.isNotBlank() } ?: return@forEach
        putIfAbsent(id, caption)
      }
      catalog.components.forEach { c ->
        val id = c.componentId?.takeIf { it.isNotBlank() } ?: return@forEach
        val caption = c.caption?.takeIf { it.isNotBlank() } ?: return@forEach
        put(id, caption)
      }
    }
    val plannedImages =
      catalog.components.flatMap { component ->
        // The component's section/group tag every one of its previews (a component maps to one
        // section + group), so the tabbed landing can bucket + sub-head + order them.
        val section = component.section?.takeIf { it.isNotBlank() }
        val group = component.group?.takeIf { it.isNotBlank() }
        val componentId = component.componentId?.takeIf { it.isNotBlank() }
        val componentSourceFile = component.sourceFile?.takeIf { it.isNotBlank() }
        val componentSourceModule = component.sourceModule?.takeIf { it.isNotBlank() }
        val componentBodyLine = component.bodyLine?.takeIf { it > 0 }
        val componentCaption = component.caption?.takeIf { it.isNotBlank() }
        component.images.mapNotNull { image ->
          val path = image.path
          // Only image-directory PNGs; reject traversal. The path is from a trusted branch, but a
          // containment check costs nothing and guards a compromised/garbled catalog.
          val segments = path.split("/")
          if (!path.startsWith("$IMAGES_DIR/") || !path.endsWith(".png") || ".." in segments)
            return@mapNotNull null
          val id = previewIdFor(path)
          val target =
            File(previewsDir, "$id.png").takeIf {
              it.canonicalFile.toPath().startsWith(previewsRoot)
            }
          PlannedImage(
            path,
            id,
            target,
            image,
            componentId,
            section,
            group,
            componentSourceFile,
            componentSourceModule,
            componentBodyLine,
            componentCaption,
            component.motion,
          )
        }
      }

    // Walk the plan for METADATA ONLY — no image is fetched here. `catalog.json` alone names every
    // card, its state/theme/section and its ordering, which is everything the grid needs to be
    // published; the pixels are what used to keep a catalog invisible for minutes after its
    // metadata had arrived. Each id is recorded as declared-baked and handed to the host, which
    // fetches its PNG on first use (see [ServeBundleHost.fetchBakedPng]). A visitor's first grid
    // paint therefore fills the default previews concurrently through the ordinary request path —
    // no separate background pass to schedule, cancel on refresh, or reason about.
    val bakedPathById = LinkedHashMap<String, String>()
    // Branch path per capture route id, for the host to fetch on demand. Captures are NOT staged
    // here, on the same reasoning that made the baked images lazy — and more so: a capture is one
    // to
    // two orders of magnitude heavier than the sticker beside it, and it is opt-in surface most
    // visitors never open. Fetching them at registration would make every catalog refresh pay for
    // pixels nobody asked to see.
    val motionPathById = LinkedHashMap<String, String>()
    for (planned in plannedImages) {
      if (count >= maxImages) break
      // Counted as the catalog CLAIMS to publish it, which is how the completeness check below
      // tells "this catalog bakes nothing" (legal, all-deferred) apart from "this catalog bakes
      // things and the branch can't serve them" (an outage — must not swap).
      declaredImages++
      if (planned.target == null) continue
      run {
        val id = planned.id
        val image = planned.image
        bakedPathById[id] = planned.path
        slugs.add(id.substringBefore(SLUG_SEPARATOR))
        planned.path
          .removePrefix("$IMAGES_DIR/")
          .removeSuffix(".png")
          .takeIf { it.count { char -> char == '/' } == 1 }
          ?.let { variantSvgPaths.add("$it.svg") }
        // Record variant metadata for any preview carrying state/theme OR a section/group tag. The
        // authored `order` (the image's 0-based position in the catalog's component list) rides
        // only
        // with the section/group tags — it exists purely to order the tabbed landing, so a plain
        // state/theme catalog (design systems) is unaffected and its manifest stays {state,theme}.
        // A catalog with neither state/theme nor a section records nothing and stays a flat grid.
        val hasSectionInfo = planned.section != null || planned.group != null
        val props = image.props?.takeIf { it.isNotEmpty() }
        val size = image.size?.takeIf { it.isNotBlank() }
        // Pair the component's captures to THIS card by theme, rather than by taking the published
        // filename apart. The export already resolved which themed sticker each capture accompanies
        // and recorded it; re-deriving that here from the name would be a second implementation of
        // the export's naming rule, and the two would eventually disagree. A capture with no theme
        // (an unthemed catalog) belongs to every card of its component.
        val motion =
          planned.componentMotion.mapNotNull { capture ->
            val theme = capture.theme?.takeIf { it.isNotBlank() }
            if (theme != null && !theme.equals(image.theme, ignoreCase = true))
              return@mapNotNull null
            val motionId = motionPreviewIdOf(capture.path) ?: return@mapNotNull null
            val extension = motionExtensionOf(capture.path) ?: return@mapNotNull null
            motionPathById[motionId] = capture.path
            MotionMeta(
              id = motionId,
              kind = capture.kind.takeIf { it.isNotBlank() },
              caption = capture.caption?.takeIf { it.isNotBlank() },
              extension = extension,
            )
          }
        if (
          motion.isNotEmpty() ||
            image.state != null ||
            image.theme != null ||
            props != null ||
            size != null ||
            planned.componentId != null ||
            hasSectionInfo ||
            planned.componentSourceFile != null ||
            planned.componentSourceModule != null ||
            image.overrides.isNotEmpty() ||
            image.remoteComposeKnobs.isNotEmpty() ||
            image.supportsFocus ||
            image.supportsGestures ||
            image.fixedTheme ||
            image.secondary
        ) {
          variants[id] =
            VariantMeta(
              state = image.state,
              theme = image.theme,
              props = props,
              size = size,
              componentId = planned.componentId,
              overrides = image.overrides,
              remoteComposeKnobs = image.remoteComposeKnobs,
              supportsFocus = image.supportsFocus,
              supportsGestures = image.supportsGestures,
              fixedTheme = image.fixedTheme,
              secondary = image.secondary,
              motion = motion,
              section = planned.section,
              group = planned.group,
              order = if (hasSectionInfo) count else null,
              sourceFile = planned.componentSourceFile,
              sourceModule = planned.componentSourceModule,
              bodyLine = planned.componentBodyLine,
              caption = planned.componentCaption,
              // The ground and the frame this render was captured with. Straight through from the
              // catalog's own image record — the export lifted them off the bundle's
              // `previews.json`, which is the only place they exist and which a published catalog
              // does not stage.
              previewParams = image.previewParams,
            )
        }
        count++
      }
    }
    // Live-only (deferred) coverage: previews the catalog declares but ships no PNG for, to be
    // rendered on demand. Their variant metadata is written into the SAME manifest as the baked
    // previews — so a live-only card lands in its tab/group, folds onto its component alongside the
    // baked states, and orders after the baked previews rather than jumping to the front — but the
    // ids themselves are handed to the host separately, and only where a live lane exists to render
    // them (see the `bakedFallback` call sites). An id that duplicates a baked preview is dropped:
    // the baked pixels win.
    val deferredIds = LinkedHashSet<String>()
    for (record in catalog.deferred) {
      if (deferredIds.size >= maxImages) break
      val id = deferredPreviewIdOf(record) ?: continue
      // Checked against the DECLARED baked set, not the previews dir: with images fetched lazily
      // nothing is on disk yet at this point, and a flat catalog's baked previews carry no variant
      // metadata either — so an on-disk test would let a deferred record shadow a baked preview.
      if (variants.containsKey(id) || bakedPathById.containsKey(id)) continue
      if (!deferredIds.add(id)) continue
      val section = record.section?.takeIf { it.isNotBlank() }
      val group = record.group?.takeIf { it.isNotBlank() }
      variants[id] =
        VariantMeta(
          state = record.state,
          theme = record.theme,
          props = record.props?.takeIf { it.isNotEmpty() },
          size = record.size?.takeIf { it.isNotBlank() },
          componentId = record.componentId?.takeIf { it.isNotBlank() },
          fixedTheme = record.fixedTheme,
          secondary = record.secondary,
          section = section,
          group = group,
          order = if (section != null || group != null) count + deferredIds.size - 1 else null,
          // A live-only record carries no caption of its own — the export writes one per COMPONENT
          // — so take the caption of the component it belongs to. Without this a component whose
          // only render is live is the one card on the sheet that cannot say what it is.
          caption =
            record.caption?.takeIf { it.isNotBlank() }
              ?: captionByComponentId[record.componentId?.takeIf { it.isNotBlank() }],
        )
    }

    // Failed renders are catalog coverage too: list a card with structured diagnostics even though
    // there are no pixels to fetch. They are deliberately not aliases/live-only ids — the failure
    // describes the published render, and a static catalog must never turn it into a silent 404.
    val failedIds = LinkedHashSet<String>()
    for ((index, failure) in catalog.failures.withIndex()) {
      if (count + deferredIds.size + failedIds.size >= maxImages) break
      // Catalog JSON is fetched input. Never reuse its id as a filesystem path: generate a
      // single-segment route id from descriptive fields, then suffix collisions deterministically.
      fun failureSlug(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.').ifBlank { "unknown" }
      val base =
        "render-failed--${failureSlug(failure.componentId.orEmpty())}--" +
          failureSlug(failure.preview ?: failure.id.ifBlank { index.toString() })
      var id = base
      var suffix = 2
      while (id in bakedPathById || id in deferredIds || id in failedIds) {
        id = "$base--${suffix++}"
      }
      failedIds.add(id)
      variants[id] =
        VariantMeta(
          state = failure.state,
          theme = failure.mode,
          props = failure.props,
          componentId = failure.componentId,
          section = failure.section,
          group = failure.group,
          order = count + deferredIds.size + failedIds.size - 1,
          sourceFile = failure.sourceFile,
          caption = captionByComponentId[failure.componentId?.takeIf { it.isNotBlank() }],
          renderFailure = failure,
        )
    }

    // Nothing to serve? Distinguish the two ways that happens, because only one is a failure:
    //
    //   - the catalog DECLARED baked images and none of them fetched — a transient outage (or a
    //     broken branch). Fail, leaving the currently-served `dir` untouched; that is exactly what
    //     the staging dance above exists for.
    //   - the catalog declares no baked images at all and its coverage is wholly live-only (every
    //     entry `priority: "deferred"`). That is a legal, if unusual, publish, and rejecting it
    // here
    //     would make the deferred lane unusable for the catalog that leans on it hardest. Let it
    //     through: the live builders below register the deferred ids, and a session with no live
    //     lane still lands on the terminal baked host — which then serves an empty (but explained,
    //     via `deferred-not-served`) sheet rather than a 404.
    if (count == 0 && failedIds.isEmpty() && (declaredImages > 0 || deferredIds.isEmpty())) {
      staging.deleteRecursively()
      return Result.Failed(system, "catalog had no usable images")
    }

    // The one image fetched before publishing: the catalog's hero, which the front-door card paints
    // from. Two jobs in one round-trip.
    //
    // It is also what is left of the old completeness check. The bulk fetch used to prove the
    // branch could actually serve pixels — "declared images, none fetched" meant an outage and the
    // staged dir was thrown away rather than swapped over a healthy one. Fetching lazily gives that
    // up unless something is fetched here, so a catalog that declares images must land its hero to
    // publish. One request, and a branch serving 404s still can't replace a working catalog.
    // Deliberately a small SAMPLE rather than one nominated image: keying the precondition on a
    // single file would fail a whole catalog because one image happens to be missing, which is
    // strictly worse than the bulk fetch it replaces (that one dropped the bad image and served the
    // rest). The hero leads the sample so the front-door card is the one certainly present.
    if (bakedPathById.isNotEmpty()) {
      val heroPath = heroPreviewIdFor(catalog, bakedPathById.keys)?.let(bakedPathById::get)
      val sample =
        (listOfNotNull(heroPath) + bakedPathById.values).distinct().take(PUBLISH_SAMPLE_IMAGES)
      val landed =
        fetchCatalogAssetsToFiles(
          sample.map { path -> (base + path) to File(previewsDir, "${previewIdFor(path)}.png") }
        )
      if (landed.isEmpty()) {
        staging.deleteRecursively()
        return Result.Failed(system, "catalog images could not be fetched from its branch")
      }
    }

    // Write the state/theme manifest into the staged previews dir *before* the atomic swap, so a
    // reader never sees a `dir` whose `variants.json` disagrees with its images. Absent when no
    // render carried state/theme (a plain catalog) — the host then treats every preview as default.
    if (variants.isNotEmpty()) {
      val manifest =
        json.encodeToString(MapSerializer(String.serializer(), VariantMeta.serializer()), variants)
      // A wholly-deferred catalog wrote no PNG, so the staged previews dir may not exist yet.
      previewsDir.mkdirs()
      File(previewsDir, VARIANTS_FILE).writeText(manifest)
    }

    // Design tools are deliberately normalized by the producer to inert PNGs. Fetch only those
    // declared rasters into the staged catalog, rewrite their paths to server-owned locations,
    // and retain the source fields as provenance. In particular, source.uri and artifact.path are
    // never fetched here: an HTML/Figma reference cannot turn catalog refresh into code execution
    // or an authenticated remote request.
    val manifestReferences = fetchDesignReferences(base)
    val referenceBranchPaths =
      writeDesignReferences(manifestReferences + catalog.references, base, staging)
    writeAnnotations(base, staging)
    writeTagIndex(base, staging)
    writeParityActivity(base, staging)
    writeParityIssues(base, staging)
    writeParityFindings(base, staging)
    writeDesignPages(base, staging)
    writeKnownDifferences(base, staging)

    // The staged catalog is usable — move it into place as this load's generation. Nothing serves
    // from `dir` yet (no host names it until [publishGeneration] below), so this is a rename onto a
    // free path rather than a mutation of what the registered host is reading.
    if (!staging.renameTo(dir)) {
      // Cross-device or a racing reader held a handle — copy then drop the staging dir.
      staging.copyRecursively(dir, overwrite = true)
      staging.deleteRecursively()
    }

    // Optional in-browser Wasm tier: when the catalog declares a `compose-wasm` webRender, fetch
    // its
    // app files from the same branch into `<dir>/web/wasm/` and register that as the system's Wasm
    // dir. Best-effort — a fetch failure just leaves the catalog without the in-browser tier (the
    // PNG + data tiers still serve). The file list is enumerated by the trusted catalog, not the
    // client, and each file is path-contained + size-capped like the images.
    // Fetched here, registered at publication ([publishGeneration]) — the tier belongs to this
    // generation, and nothing may point at it while the previous host is still the registered one.
    val wasmDir = fetchWasmApp(catalog.webRender, base, dir, safe)
    val wasmRegistered = wasmDir != null

    val verdict =
      if (trust().trustsBranch(repo, branch))
        BundleVerifier.Verdict.Trusted(listOf(BundleVerifier.Basis.Branch(repo, branch)))
      else BundleVerifier.Verdict.Unverified("branch $repo@$branch is not trusted")

    // Fetch the catalog's baked editable vectors (figma/<slug>.svg + crops) so the host can serve
    // an SVG per preview; null when the branch carried none (host then 404s the .svg lane).
    // The baked vectors are the last bulk fetch on the publish path — one per image plus one per
    // slug, ~240 for the largest catalog. They cannot be made lazy the way the PNGs were: nothing
    // ever *requests* an SVG (the browser doesn't; it is a server-side input to thumbnail cropping
    // and a download link), so fetching one on demand would simply mean never fetching it, and
    // every card would stay uncropped. So they move off the publish path instead of onto the
    // request path: probe one vector synchronously to learn whether this branch carries the lane at
    // all, then fill the rest in the background while the catalog is already serving. Cards render
    // uncropped for those few seconds and crop themselves once the pass lands.
    // Probe a handful of components, hero first — not just one. A vector is published per
    // component and any of them may legitimately have none, so a single miss says nothing about
    // the branch; the bulk path this replaces enabled the lane if *any* candidate landed. Spread
    // across distinct slugs so the sample is about the branch rather than one component.
    val figmaProbeIds =
      (listOfNotNull(heroPreviewIdFor(catalog, bakedPathById.keys)) + bakedPathById.keys)
        .distinctBy { it.substringBefore(SLUG_SEPARATOR) }
        .take(FIGMA_PROBE_SLUGS)
    val figmaDir = probeFigmaSvg(figmaProbeIds.flatMap(::heroSvgCandidates), base, dir)
    // Scheduled unconditionally: a probe that found nothing is not proof the branch carries none —
    // the vectors may simply start at a component the sample missed. Filling anyway means the lane
    // turns on at the next host build instead of being lost until someone republishes.
    scheduleFigmaSvgFetch(system, generation, slugs, variantSvgPaths, base, dir)

    // Sampled integrity audit of what the blob cache holds for this catalog. Only for a pinned
    // load: an un-pinned one caches nothing, so there is nothing to check. Runs on the same
    // single-threaded post-publish lane as the vector fill, for the same reason — it must never
    // outweigh the request path.
    if (pinned) {
      val sample = bakedPathById.values.take(AUDIT_SAMPLE_ASSETS).map { base + it }
      if (sample.isNotEmpty()) {
        figmaExecutor.execute { runCatching { auditCachedAssets(sample, safe) } }
      }
    }

    // The catalog's OWN palette, so its pages are framed in its own colours (see [ServeThemeCss]).
    // A few kB of JSON off the same branch as the images, and best-effort throughout: a missing /
    // unfetchable / unparseable token file just leaves the pages on the built-in chrome.
    val webThemeCss = fetchWebThemeCss(catalog.tokensFile, base)

    // The static baked-PNG host — the browse surface (grid, deep links, thumbnails), keyed by the
    // catalog ids. This is ALWAYS what a viewer sees; a live builder below fronts it with a daemon
    // rather than replacing it, so the published /p/<id> links keep resolving. Built lazily so the
    // registry can rebuild it on each resume of a live session.
    // [degradations] explains why the session is snapshot-only (surfaced by the viewer +
    // /api/previews). It is EMPTY when this baked host merely *fronts* a live daemon (the live
    // builders below call `bakedFallback(emptyList(), …)` — that session isn't degraded); it is
    // populated only at the terminal registration, where the baked host IS the session.
    // [liveOnly] carries the catalog's deferred (live-only) ids — passed ONLY by the live builders
    // below, which front this host with a daemon that can actually render them. The terminal
    // baked-only registration passes none, so a session with no live lane simply doesn't list them
    // (and says why, via a `deferred-not-served` degradation) instead of showing broken cards.
    // The catalog-id → daemon-preview-id bridge: a live daemon knows previews by their
    // function-based
    // descriptor id (`FilledButton_Dark`), but the published links/routes use the catalog id
    // (`button-filled__ideal__default__dark`). The exporter records each image's source daemon id
    // in
    // `previewId`; map it against the route-safe catalog id so a live host can answer the published
    // URLs (and unmapped ids — the Android-only variants — fall back to baked PNGs).
    //
    // Read before the hosts are built because it also decides whether the published-comparison lane
    // runs at all ([ServeRcCompare.stagesFor]), which the host has to know to tell "the lane has
    // not
    // landed yet" apart from "there is no lane".
    val alias = previewAliasFor(catalog)

    val bakedFallback: (List<ServeDegradation>, List<String>) -> ServeBundleHost =
      { degradations, liveOnly ->
        ServeBundleHost(
          dir,
          safe,
          verdict,
          // The one construction site with a `catalog.json` behind it. See
          // [ServeBundleHost.isCatalog].
          isCatalog = true,
          title = catalog.title?.takeIf { it.isNotBlank() },
          subtitle =
            catalog.library.filter { it.isNotBlank() }.take(2).joinToString(" · ").ifBlank { null },
          // Declared presentation hints (stage surface + hero preview), so the front door / grid
          // read
          // the system's own choice instead of inferring it.
          stageSurface = catalog.display?.surface?.takeIf { it.isNotBlank() },
          declaredHero = catalog.display?.hero?.takeIf { it.isNotBlank() },
          webThemeCss = webThemeCss,
          figmaDir = figmaDir,
          provenance =
            ServeWeb.CatalogProvenance(
              repo = repo,
              branch = branch,
              // The branch tip this catalog was fetched at — what a permalink pins to. Resolved
              // once per load (never per request), and null when the advertisement couldn't be
              // read, which only costs the pages their "permalink" affordance.
              commit = deliveryCommit,
              generatedAt = catalog.generatedAt?.takeIf { it.isNotBlank() },
              // `renderer` is `compose-preview <version>`; show just the version.
              toolVersion =
                catalog.renderer
                  ?.takeIf { it.isNotBlank() }
                  ?.removePrefix("compose-preview")
                  ?.trim()
                  ?.ifBlank { null },
              designParityVersion = catalog.designParity?.takeIf { it.isNotBlank() },
            ),
          // The catalog's SOURCE (repo/ref/module of the Kotlin) — distinct from the delivery
          // provenance above — so the viewer can link a preview to its source. Requires a real
          // repo + ref; module may be blank (then the link omits the module prefix).
          catalogSource =
            catalog.source
              ?.takeIf { it.repo.isNotBlank() && it.ref.isNotBlank() }
              ?.let { ServeWeb.CatalogSource(it.repo, it.ref, it.module) },
          // The cross-system pairing, carried as the two halves it actually is. Both are read
          // straight off `catalog.json`; a catalog that declares neither gets null + empty and the
          // viewer's second comparison source is simply never offered.
          compareWithSystem = catalog.compareWith?.system?.takeIf { it.isNotBlank() },
          // Read from BOTH lists, components last so they win — the same shape as
          // [captionByComponentId] and for the same reason. A wholly deferred component never
          // reaches `components[]`, and the export writes its pairing onto the deferred record
          // instead; reading only `components` discarded it and left the deferred card unable to
          // offer the sibling source. That also covers its deferred *variant* records, which
          // inherit the entry's pairing.
          parallelByComponentId =
            buildMap {
              catalog.deferred.forEach { deferred ->
                val id = deferred.componentId?.takeIf { it.isNotBlank() } ?: return@forEach
                val parallel = deferred.parallel?.takeIf { it.isNotBlank() } ?: return@forEach
                // First wins among the deferred records themselves, like [previewAliasFor]: a
                // catalog that somehow listed a component id twice gets the producer's own order
                // rather than a throw.
                putIfAbsent(id, parallel)
              }
              catalog.components.forEach { component ->
                val id = component.componentId?.takeIf { it.isNotBlank() } ?: return@forEach
                val parallel = component.parallel?.takeIf { it.isNotBlank() } ?: return@forEach
                // `put`, not `putIfAbsent`: a component that IS in `components[]` states its own
                // pairing, and a deferred *variant* record sharing its id only inherits one.
                put(id, parallel)
              }
            },
          degradations = degradations,
          liveOnly = liveOnly,
          // Kept in step with [scheduleRcCompareFetch]'s own guard through the shared helper: a
          // host that claims a lane which never runs stays "pending" forever and never caches.
          stagesRcCompare = ServeRcCompare.stagesFor(alias),
          // Every id the catalog bakes, so the grid is complete the moment `catalog.json` lands…
          declaredBaked = bakedPathById.keys.toList() + failedIds,
          // …and the pixels follow on first use. The store keeps ownership of the network here: the
          // host never builds a URL or applies a fetch policy, it just asks for an id it declared.
          fetchBakedPng = { id ->
            bakedPathById[id]?.let { path -> fetchCatalogAsset(base + path) }
          },
          // The same seam for the motion axis: ids the catalog publishes a capture for, and the
          // fetch that lands one. Captures are never staged at registration (see [motionPathById]),
          // so this lane is lazy all the way down — a catalog with motion costs nothing extra until
          // a reader actually asks to watch something.
          declaredMotion = motionPathById.keys.toList(),
          fetchMotion = { id ->
            motionPathById[id]?.let { path -> fetchCatalogAssetOutcome(base + path) }
              ?: BranchFetch.NotFound
          },
          motionBranchPaths = motionPathById.toMap(),
          // The branch path of every baked render, so a pinned (`?at=<sha>`) request can be
          // answered out of the same tree at an older commit — see [ServeCatalogRevision].
          bakedBranchPaths = bakedPathById.toMap(),
          referenceBranchPaths = referenceBranchPaths,
          revisions = revisions,
          revisionPreviewIds = revisionPreviewIds,
          // Which publishes changed one render, read lazily off the branch's path-scoped feed. The
          // branch (not the resolved head) is deliberate: this asks "when did these bytes move",
          // which is a question about the branch's history rather than about one tree, and pinning
          // it to the load's commit would hide every publish made since.
          fetchRenderChanges = { path -> fetchRenderChanges(repo, branch, path) },
          // Same seam as `fetchBakedPng`: the host names a commit and a published path, the store
          // builds the URL and applies the fetch policy. Null repo ⇒ no pinned lane at all.
          fetchPinnedAsset = { commit, path ->
            ServeCatalogRevision.assetUrl(repo, commit, path)?.let { fetchCatalogAsset(it) }
          },
          // The same read, but reporting WHY it failed — which is the only thing that makes the
          // host's permanent negative cache safe. See [ServeBundleHost.fetchPinnedAssetOutcome].
          fetchPinnedAssetOutcome = { commit, path ->
            ServeCatalogRevision.assetUrl(repo, commit, path)?.let { fetchCatalogAssetOutcome(it) }
              ?: BranchFetch.NotFound
          },
          // Ids are stable across publishes; the paths under them are not. So a pinned request
          // resolves its path from the manifests AT that commit, with the tip's maps above as the
          // fallback. Same seam again: the host names a commit and one of two declared manifest
          // files, the store builds the URL and applies the fetch policy.
          pinnedManifest =
            ServePinnedManifest(
              fetch = { commit, file ->
                ServeCatalogRevision.manifestUrl(repo, commit, file)?.let { fetchCatalogAsset(it) }
              }
            ),
        )
      }

    // The published Remote Compose player comparison (`rc-compare-summary.json` + the lane PNGs),
    // re-keyed through the same alias. Background, like the vectors: it is an enrichment the
    // compare page picks up once it lands, and the catalog must not wait on ~150 small PNGs.
    scheduleRcCompareFetch(system, generation, alias, base, dir)

    // Trusted server-side re-render from a carried EXECUTABLE BUNDLE (opt-in,
    // --allow-render-trusted) — tried FIRST, ahead of the Gradle `source` build below: no clone, no
    // worktree, no per-request Gradle invocation. Only a Trusted catalog that declares `liveBundle`
    // is even offered to the builder — an Unverified catalog NEVER reaches it — and only once the
    // whole declared bundle file has fetched cleanly (fail-closed, like fetchWasmApp above). The
    // builder fronts [bakedFallback] with the daemon (see ServeCatalogLiveHost), so the baked
    // catalog still serves browsing + the ids the daemon can't render.
    // Captures WHY a declared liveBundle didn't yield a live session, so the terminal baked host
    // can
    // explain it (instead of the generic "no live bundle"). Null unless the catalog declared a
    // liveBundle we then couldn't use.
    var liveBundleFallback: ServeDegradation? = null
    var trustedBundlesRecorded = false
    val declaredLiveBundles = catalog.liveBundles
    val multiLiveBundle = declaredLiveBundles.size > 1
    if (verdict is BundleVerifier.Verdict.Trusted && multiLiveBundle) {
      val nonEmptyPrefixes =
        declaredLiveBundles.map { it.previewIdPrefix }.filter { it.isNotEmpty() }
      val descriptorsValid =
        nonEmptyPrefixes.distinct().size == nonEmptyPrefixes.size &&
          declaredLiveBundles.count { it.previewIdPrefix.isEmpty() } == 1
      if (!descriptorsValid) {
        liveBundleFallback =
          ServeDegradation.liveBundleUnavailable("the module bundle identity map is invalid")
      } else {
        val prepared = mutableListOf<TrustedModuleBundle>()
        for (descriptor in declaredLiveBundles) {
          val moduleAlias = alias.filterValues { daemonId ->
            if (descriptor.previewIdPrefix.isNotEmpty()) {
              daemonId.startsWith(descriptor.previewIdPrefix)
            } else {
              nonEmptyPrefixes.none(daemonId::startsWith)
            }
          }
          val bundleFile = fetchLiveBundle(descriptor, base, dir, safe, pinned)
          if (bundleFile == null) {
            prepared.clear()
            liveBundleFallback =
              ServeDegradation.liveBundleUnavailable(
                "the ${descriptor.module.ifBlank { "primary" }} module bundle could not be fetched"
              )
            break
          }
          extractCatalogRcDocs(bundleFile, moduleAlias, dir)
          val resources =
            when (
              val res = rehydrateExternalResources(bundleFile, base, descriptor.path, dir, safe)
            ) {
              is ResRehydrate.Ready -> res.dir
              ResRehydrate.Unavailable -> {
                prepared.clear()
                liveBundleFallback =
                  ServeDegradation.liveBundleUnavailable(
                    "a resource for ${descriptor.module.ifBlank { "the primary module" }} could not be rehydrated"
                  )
                break
              }
            }
          val safeStems = uniquePerPreviewStems(moduleAlias.values)
          prepared +=
            TrustedModuleBundle(
              module = descriptor.module,
              file = bundleFile,
              externalResourcesDir = resources,
              alias = moduleAlias,
              perPreviewBundle =
                PerPreviewBundleAccess(
                  available = { daemonId ->
                    safeStems[daemonId]?.let { stem ->
                      perPreviewBundleAvailable(stem, descriptor, base, dir, pinned)
                    } ?: false
                  },
                  fetch = { daemonId ->
                    safeStems[daemonId]?.let { stem ->
                      fetchPerPreviewBundle(stem, descriptor, base, dir, safe, resources, pinned)
                    }
                  },
                ),
            )
        }
        val preparedCompletely =
          prepared.size == declaredLiveBundles.size && prepared.all { it.alias.isNotEmpty() }
        if (preparedCompletely) {
          recordTrustedBundles(
            safe,
            prepared.map { VerifiedModuleBundle(module = it.module, file = it.file) },
          )
          trustedBundlesRecorded = true
        }
        if (
          preparedCompletely &&
            buildTrustedBundles(
              safe,
              prepared,
              { bakedFallback(emptyList(), deferredIds.toList()) },
            )
        ) {
          publishGeneration(safe, dir, wasmDir)
          return Result.Ok(
            safe,
            count + deferredIds.size + failedIds.size,
            "${BundleVerifier.summary(verdict)} (${prepared.size} live module bundles)",
            failedIds.size,
            incomplete = scope.sawTransientFailure,
          )
        }
        if (liveBundleFallback == null) {
          liveBundleFallback =
            ServeDegradation.liveBundleUnavailable("the module bundle daemons could not be started")
        }
      }
    }
    // A multi-module declaration is atomic: never silently start only its primary legacy bundle.
    val liveBundle = if (multiLiveBundle) null else catalog.liveBundle
    if (verdict is BundleVerifier.Verdict.Trusted && liveBundle != null) {
      val bundleFile = fetchLiveBundle(liveBundle, base, dir, safe, pinned)
      if (bundleFile == null) {
        liveBundleFallback =
          ServeDegradation.liveBundleUnavailable(
            "the bundle could not be fetched from the delivery branch"
          )
      } else {
        recordTrustedBundles(
          safe,
          listOf(VerifiedModuleBundle(module = liveBundle.module, file = bundleFile)),
        )
        trustedBundlesRecorded = true
        // Materialise the captured Remote Compose documents (`ir/<daemon-id>.rc`) from the fetched
        // bundle, re-keyed to the published catalog ids, so the baked host's in-browser canvas lane
        // can serve them. Done regardless of the rehydrate/daemon outcome below — the client-side
        // `.rc` lane needs no daemon, so it must survive a live-tier fallback.
        extractCatalogRcDocs(bundleFile, alias, dir)
        // IR-backed previews have no class in app.jar by design. The bundle daemon replays them
        // from the extracted `ir/` document + bundle manifest, so they remain in this alias just
        // like class-backed previews and can expose Java / CMP Android renderer selection.
        // Rehydrate any resources the bundle externalized (fonts lifted out of classes/app.jar)
        // from
        // the branch's content-addressed pool into a shared cache + a materialized classpath dir.
        // Fail-closed: a declared-but-unfetchable resource means the daemon would render with the
        // fonts missing (the exact ExceptionInInitializerError this feature exists to avoid), so we
        // skip the live bundle and fall through to the source/static path rather than serve a
        // broken
        // live tier.
        when (val res = rehydrateExternalResources(bundleFile, base, liveBundle.path, dir, safe)) {
          is ResRehydrate.Ready -> {
            // The per-preview live lane (default render path): each daemon-preview id maps to its
            // own FULL split bundle beside the monolithic one on the trusted branch. Fetched on
            // demand + pooled by the builder; shares the monolithic bundle's rehydrated font pool
            // ([res.dir]) since both were split from the same externalised bundle.
            //
            // Collision safety: `bundle split` writes colliding sanitised ids as `<base>.png`,
            // `<base>-2.png`, … so a daemon id whose sanitised stem is NOT unique among the alias
            // values would fetch a sibling's bundle under the bare `<stem>.png`. We can't recover
            // which suffix maps to which id without the publisher's ordering, so we only serve the
            // per-preview lane for ids with an unambiguous stem; a colliding id resolves null and
            // falls back to the monolithic daemon (which serves every preview correctly).
            val safeStems = uniquePerPreviewStems(alias.values)
            val perPreviewBundle =
              PerPreviewBundleAccess(
                available = { daemonId ->
                  safeStems[daemonId]?.let { stem ->
                    perPreviewBundleAvailable(stem, liveBundle, base, dir, pinned)
                  } ?: false
                },
                fetch = { daemonId ->
                  safeStems[daemonId]?.let { stem ->
                    fetchPerPreviewBundle(stem, liveBundle, base, dir, safe, res.dir, pinned)
                  }
                },
              )
            if (
              alias.isNotEmpty() &&
                buildTrustedBundle(
                  safe,
                  bundleFile,
                  res.dir,
                  alias,
                  { bakedFallback(emptyList(), deferredIds.toList()) },
                  perPreviewBundle,
                )
            ) {
              publishGeneration(safe, dir, wasmDir)
              return Result.Ok(
                safe,
                count + deferredIds.size + failedIds.size,
                "${BundleVerifier.summary(verdict)} (live bundle)",
                failedIds.size,
                incomplete = scope.sawTransientFailure,
              )
            }
            // Declared + fetched + rehydrated, but the builder didn't stand a daemon up — most
            // often
            // because server-side re-render isn't enabled on this box (`--allow-render-trusted`
            // off), or the backend isn't runnable here. Fall through to source/static with a
            // reason.
            liveBundleFallback =
              ServeDegradation.liveBundleUnavailable(
                if (serverSideRenderEnabled) "the live bundle daemon could not be started"
                else "server-side re-render is not enabled on this server"
              )
          }
          // Fall through to source/static — a declared resource couldn't be rehydrated.
          ResRehydrate.Unavailable ->
            liveBundleFallback =
              ServeDegradation.liveBundleUnavailable(
                "a required font or resource could not be rehydrated"
              )
        }
      }
    }

    // Trusted server-side re-render from SOURCE (opt-in): only a Trusted catalog that declares a
    // source is even offered to the builder — an Unverified catalog NEVER reaches it, so a
    // compromised/spoofed catalog can't trigger a build. Like the bundle path, the builder fronts
    // the baked host with the daemon rather than replacing it.
    if (!trustedBundlesRecorded) clearTrustedBundles(safe)
    val src = catalog.source
    if (
      verdict is BundleVerifier.Verdict.Trusted &&
        src != null &&
        src.module.isNotBlank() &&
        buildTrustedSource(
          safe,
          CatalogSource(src.repo, src.ref, src.module),
          alias,
          { bakedFallback(emptyList(), deferredIds.toList()) },
        )
    ) {
      publishGeneration(safe, dir, wasmDir)
      return Result.Ok(
        safe,
        count + deferredIds.size + failedIds.size,
        "${BundleVerifier.summary(verdict)} (live)",
        failedIds.size,
        incomplete = scope.sawTransientFailure,
      )
    }

    // Terminal: no server-side live lane stood up, so register the baked host AND record why it's
    // snapshot-only — UNLESS the in-browser Wasm tier was registered, which IS a live lane (the
    // viewer's Live toggle switches to it). A Wasm-backed session isn't baked-only, so it carries
    // no
    // session-level degradation; the viewer's per-control `cp-note` already explains which
    // overrides
    // the Wasm tier can't cover (size/device/orientation).
    // Priority when we DO record one: a specific liveBundle failure (fetched/rehydrated/started) >
    // an
    // unverified catalog that DID declare a live lane (trust is the blocker) > the plain "no live
    // bundle published" case (meshcore's app catalog, …).
    // Recorded whenever the catalog declares live-only coverage this terminal (no server-side
    // daemon) registration can't produce: those previews are omitted rather than listed as cards
    // whose every request 404s, and this is what tells the visitor the sheet is thinner than the
    // catalog claims. It rides even on a Wasm-backed session — the in-browser tier renders the
    // catalog's previews, not the ones that were never baked into it.
    val deferredNote =
      deferredIds.takeIf { it.isNotEmpty() }?.let { ServeDegradation.deferredNotServed(it.size) }
    val degradations =
      if (wasmRegistered) listOfNotNull(deferredNote)
      else
        listOfNotNull(
          liveBundleFallback
            ?: when {
              verdict is BundleVerifier.Verdict.Unverified &&
                ((liveBundle != null || declaredLiveBundles.isNotEmpty()) ||
                  (src != null && src.module.isNotBlank())) ->
                ServeDegradation.unverifiedNoRerender()
              else -> ServeDegradation.catalogBakedOnly()
            },
          deferredNote,
        )
    // Same reasoning as the degradation: a session with no live lane lists no live-only previews.
    val host = bakedFallback(degradations, emptyList())
    publishGeneration(safe, dir, wasmDir)
    register(safe, host)
    return Result.Ok(
      safe,
      host.previews.size,
      BundleVerifier.summary(verdict),
      failedIds.size,
      incomplete = scope.sawTransientFailure,
    )
  }

  /**
   * Fetch a `compose-wasm` [WebRender] app's files from [base] into `<dir>/web/wasm/` and register
   * the dir. The file list comes from the trusted [render] (not a client); each entry is confined
   * to the declared `path`, rejected on traversal, and size-capped by [fetch]. Needs at least an
   * `index.html` to be usable. No-op for a null / non-`compose-wasm` descriptor.
   *
   * Returns the app's directory iff the in-browser Wasm tier is usable — the caller registers it
   * with the generation it belongs to (see [registerWasm]) and uses it to decide whether the
   * session still has a live (in-browser) lane, so it must NOT record a baked-only degradation even
   * when there's no server-side `liveBundle`. A declared-but-incomplete app (any fetch/traversal
   * failure) returns null, leaving the session genuinely snapshot-only.
   */
  private fun fetchWasmApp(render: WebRender?, base: String, dir: File, system: String): File? {
    if (render == null || render.kind != WEB_RENDER_COMPOSE_WASM) return null
    val prefix = render.path.trim('/')
    if (prefix.isEmpty() || render.files.isEmpty()) return null
    val wasmDir = File(dir, WEB_WASM_DIR)
    // **Fail closed, all-or-nothing.** Register the app only if *every* declared file is fetched
    // and
    // an index.html is present — a partial app (a 404/timeout on composeApp.wasm or skiko.wasm, a
    // traversal/escaping entry, or a list longer than the cap) would make the viewer advertise "Run
    // in browser (Wasm)" only for the iframe to 404 its module/wasm fetches. The file list is the
    // trusted catalog's complete manifest, so any missing/invalid entry means "don't offer it".
    fun fail(reason: String): File? {
      wasmDir.deleteRecursively()
      System.err.println("serve: $system web/wasm/ incomplete ($reason) — in-browser tier disabled")
      return null
    }
    if (render.files.size > MAX_WASM_FILES) return fail("more than $MAX_WASM_FILES files declared")
    val wasmRoot = wasmDir.canonicalFile.toPath()
    for (name in render.files) {
      val rel = name.trim('/')
      if (rel.isEmpty() || ".." in rel.split("/")) return fail("invalid entry '$name'")
      val target = File(wasmDir, rel)
      if (!target.canonicalFile.toPath().startsWith(wasmRoot)) return fail("escaping entry '$name'")
      val bytes =
        runCatching { fetchCatalogAsset("$base$prefix/$rel") }.getOrNull()
          ?: return fail("missing $rel")
      target.parentFile?.mkdirs()
      target.writeBytes(bytes)
    }
    if (!File(wasmDir, "index.html").isFile) return fail("no index.html")
    return wasmDir
  }

  /**
   * Fetch a catalog's `liveBundle` (`{path, file}`) — the executable preview bundle
   * (`<system>-bundle.png`) `design-artifacts.yml` carries alongside the baked PNGs — from
   * `<base><path>/<file>`. Fail-closed like [fetchWasmApp]: an invalid/escaping file entry or a
   * fetch miss aborts and returns null, so the caller falls back to the Gradle `source` build (or,
   * failing that, the static host). The file list is a single entry from the trusted catalog
   * itself, not client input.
   *
   * [pinned] says the load resolved a delivery commit, so [base] names one immutable tree. That is
   * the whole of what decides whether the ~100 MB download may be cached: a pinned URL identifies
   * exactly these bytes forever, so the bundle goes into the [blobs] pool and a reload — or, given
   * `--catalog-cache-dir`, a restart — re-reads it instead of pulling it again. An un-pinned load
   * (its revision feed could not be read, so [base] is the branch ref) addresses a moving target
   * and stages into `<dir>/$LIVE_BUNDLE_DIR/<file>` exactly as this did before the pool existed.
   */
  private fun fetchLiveBundle(
    liveBundle: LiveBundle,
    base: String,
    dir: File,
    system: String,
    pinned: Boolean,
  ): File? {
    val name = liveBundle.file.trim('/')
    if (name.isEmpty() || ".." in name.split("/")) {
      System.err.println("serve: $system liveBundle has an invalid file entry — skipping")
      return null
    }
    val prefix = liveBundle.path.trim('/')
    val url = if (prefix.isEmpty()) "$base$name" else "$base$prefix/$name"

    if (pinned) {
      val blob =
        blobs.keyed(url) { dest ->
          val bytes = runCatching { fetchExecutableBundle(url) }.getOrNull()
          if (bytes == null) false
          else {
            dest.writeBytes(bytes)
            true
          }
        }
      if (blob == null) {
        System.err.println("serve: $system liveBundle fetch failed ($url) — skipping")
      }
      return blob
    }

    val bundleDir = File(dir, LIVE_BUNDLE_DIR)
    val bundleRoot = bundleDir.canonicalFile.toPath()
    val target = File(bundleDir, name)
    if (!target.canonicalFile.toPath().startsWith(bundleRoot)) {
      System.err.println("serve: $system liveBundle escaping entry '$name' — skipping")
      return null
    }
    val bytes = runCatching { fetchExecutableBundle(url) }.getOrNull()
    if (bytes == null) {
      System.err.println("serve: $system liveBundle fetch failed ($url) — skipping")
      return null
    }
    target.parentFile?.mkdirs()
    target.writeBytes(bytes)
    return target
  }

  /**
   * Extract the captured Remote Compose documents from the fetched live [bundleFile] and
   * materialise them beside the baked previews so the baked host's in-browser canvas lane can serve
   * them.
   *
   * The packed bundle carries them as `ir/<daemon-id>.rc` (keyed by the daemon preview id — the
   * function-descriptor id the renderer packed), but the published routes use the catalog id
   * (`button-filled__ideal__default__dark`). [alias] is exactly that catalog-id → daemon-id map, so
   * re-key each entry through it and write `<dir>/ir/<catalog-id>.rc` — the sibling of
   * `<dir>/previews/` that [ServeBundleHost.remoteComposeDoc] reads. A catalog id whose daemon twin
   * has no `.rc` entry (a non-Remote-Compose preview) is simply skipped, so only the RC previews
   * advertise the lane. Best-effort: a malformed/unreadable bundle yields no docs (the lane stays
   * off) rather than failing the whole catalog load.
   */
  private fun extractCatalogRcDocs(bundleFile: File, alias: Map<String, String>, dir: File) {
    // Only the entries the alias actually maps to are worth decoding — a bundle padded with junk
    // `ir/` entries can't make us expand (or retain) anything the catalog will ever serve.
    val wantedDaemonIds = alias.values.toHashSet()
    if (wantedDaemonIds.isEmpty()) return
    // A real RC document is a few KB; these are generous headroom whose only job is to stop a
    // highly-compressed or oversized `ir/*.rc` entry from exhausting the heap (the 25 MB network
    // cap
    // bounds only the *compressed* bundle). Blowing either abandons the optional lane.
    val maxDocBytes = 8L * 1024 * 1024
    val maxTotalBytes = 64L * 1024 * 1024
    val docsByDaemonId =
      try {
        val zipBytes = BundleReader.extractZipBytes(bundleFile)
        val out = HashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
          var entry = zin.nextEntry
          while (entry != null) {
            val name = entry.name.replace('\\', '/')
            if (
              !entry.isDirectory &&
                name.startsWith("$IR_DOC_DIR/") &&
                name.endsWith(RC_DOC_SUFFIX) &&
                ".." !in name.split("/")
            ) {
              val daemonId = name.removePrefix("$IR_DOC_DIR/").removeSuffix(RC_DOC_SUFFIX)
              if (daemonId in wantedDaemonIds && daemonId !in out) {
                // A zip entry decompresses unbounded, so bound the read as it streams: cap this
                // doc AND the running total across docs, aborting the whole (optional) lane the
                // moment either is exceeded rather than buffering the rest.
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var docBytes = 0L
                while (true) {
                  val n = zin.read(chunk)
                  if (n < 0) break
                  docBytes += n
                  total += n
                  check(docBytes <= maxDocBytes && total <= maxTotalBytes) {
                    "rc docs exceed the decompression cap"
                  }
                  buf.write(chunk, 0, n)
                }
                out[daemonId] = buf.toByteArray()
              }
            }
            zin.closeEntry()
            entry = zin.nextEntry
          }
        }
        out
      } catch (e: Exception) {
        return
      }
    if (docsByDaemonId.isEmpty()) return
    val irDir = File(dir, IR_DOC_DIR)
    val irRoot = irDir.canonicalFile.toPath()
    for ((catalogId, daemonId) in alias) {
      val bytes = docsByDaemonId[daemonId] ?: continue
      val target = File(irDir, "$catalogId$RC_DOC_SUFFIX")
      // Containment: a crafted catalog id must not let the write escape the ir/ dir.
      if (!target.canonicalFile.toPath().startsWith(irRoot)) continue
      target.parentFile?.mkdirs()
      target.writeBytes(bytes)
    }
  }

  /**
   * The subset of [daemonIds] whose sanitised per-preview stem is **unambiguous** — mapped to that
   * stem. `bundle split` disambiguates colliding stems with `-2`/`-3`/… suffixes it derives from
   * the sheet's preview order, which the server can't reconstruct; so an id sharing a stem with
   * another is dropped here and the caller serves it from the monolithic daemon instead of fetching
   * a sibling's bundle under the bare `<stem>.png`. A blank stem (an id with no usable characters)
   * is dropped too.
   */
  private fun uniquePerPreviewStems(daemonIds: Collection<String>): Map<String, String> {
    val counts = HashMap<String, Int>()
    val stems = LinkedHashMap<String, String>()
    for (id in daemonIds) {
      val stem = sanitizePerPreviewName(id)
      if (stem.isEmpty()) continue
      stems[id] = stem
      counts[stem] = (counts[stem] ?: 0) + 1
    }
    return stems.filterValues { counts[it] == 1 }
  }

  /**
   * Fetch one preview's own **per-preview FULL bundle** (`<liveBundle.path>/previews/<stem>.png` on
   * the trusted branch) into `<dir>/$LIVE_BUNDLE_DIR/$PER_PREVIEW_DIR/<stem>.png` — the unit the
   * per-preview live lane materialises + pools. `design-artifacts.yml` splits the externalised
   * monolithic bundle into these (one re-renderable sticker per preview) beside it. Fail-closed
   * like [fetchLiveBundle]: an escaping [stem] or a fetch miss returns null and the caller simply
   * falls back to the monolithic daemon for that id (so a branch that ships no per-preview bundles
   * still serves live from the monolith).
   *
   * **What is cached is the HYDRATED bundle, not the thin download.** Hydration resolves the
   * classpath entries the thin bundle declares *by sha256*, so its output is a deterministic
   * function of this one URL — which means that when [pinned] holds, the finished bundle can be
   * keyed on that URL in the [blobs] pool and a later reload (or restart, given
   * `--catalog-cache-dir`) skips both the download and the repack. An un-pinned load keeps the
   * per-system staging this used before the pool existed.
   *
   * [stem] is the route-safe filename `bundle split` wrote the bundle under (a sanitised bundle
   * preview descriptor id), pre-resolved by [uniquePerPreviewStems] so it's unambiguous — the URL
   * stem matches the published filename exactly.
   */
  private fun fetchPerPreviewBundle(
    stem: String,
    liveBundle: LiveBundle,
    base: String,
    dir: File,
    system: String,
    externalResourcesDir: File?,
    pinned: Boolean,
  ): File? {
    val url = perPreviewBundleUrl(stem, liveBundle, base)
    val prefix = liveBundle.path.trim('/')
    val hydrate = { dest: File ->
      hydratePerPreviewBundle(url, dest, base, prefix, system, stem, externalResourcesDir)
    }

    if (pinned) return blobs.keyed(url) { dest -> hydrate(dest) }

    val previewsDir = File(File(dir, LIVE_BUNDLE_DIR), PER_PREVIEW_DIR)
    val previewsRoot = previewsDir.canonicalFile.toPath()
    val target = File(previewsDir, "$stem.png")
    if (!target.canonicalFile.toPath().startsWith(previewsRoot)) {
      System.err.println("serve: $system per-preview '$stem' escapes — skipping")
      return null
    }
    // Cached on disk from a prior request for the same id (the pool reopens lazily on eviction).
    if (target.isFile && target.length() > 0) {
      if (isCompleteExecutableBundle(target)) return target
      target.delete()
    }
    target.parentFile?.mkdirs()
    return target.takeIf { hydrate(it) }
  }

  /**
   * Download one per-preview split bundle and repack it into [dest] with its externalised classpath
   * and resources folded back in, reporting whether [dest] now holds a usable bundle.
   *
   * Boolean rather than `File?` because it is written to be handed a destination the caller chose —
   * the pool's scratch file on a pinned load, the staged per-system path otherwise — and the two
   * must not drift into separate hydration paths.
   */
  private fun hydratePerPreviewBundle(
    url: String,
    dest: File,
    base: String,
    prefix: String,
    system: String,
    stem: String,
    externalResourcesDir: File?,
  ): Boolean {
    val bytes = runCatching { fetchExecutableBundle(url) }.getOrNull()
    if (bytes == null) {
      // Expected when the branch ships no per-preview bundle for this id (older catalog, view-only
      // tier); the caller falls back to the monolithic daemon. Quiet — not an error.
      return false
    }
    val thin =
      java.nio.file.Files.createTempFile(dest.parentFile.toPath(), "$stem.", ".shared.png").toFile()
    val hydrated =
      try {
        thin.writeBytes(bytes)
        runCatching {
          BundleClasspathHydration.hydrate(
            source = thin,
            output = dest,
            resolveClasspath = { entry -> fetchExternalClasspathBlob(entry, base, prefix, system) },
            resolveResource = { entry ->
              readMaterializedExternalResource(entry, externalResourcesDir)
            },
          )
        }
          .onFailure {
            System.err.println(
              "serve: $system per-preview '$stem' classpath hydration failed (${it.message})"
            )
          }
          .getOrNull()
      } finally {
        thin.delete()
      }
    if (hydrated == null) return false
    if (!isCompleteExecutableBundle(hydrated)) {
      hydrated.delete()
      return false
    }
    return true
  }

  /** Check publication without downloading and hydrating the potentially 100 MB bundle. */
  private fun perPreviewBundleAvailable(
    stem: String,
    liveBundle: LiveBundle,
    base: String,
    dir: File,
    pinned: Boolean,
  ): Boolean {
    val url = perPreviewBundleUrl(stem, liveBundle, base)
    // Deliberately unverified — see [CatalogBlobPool.holds]. Hashing a multi-megabyte bundle to
    // answer "does this lane exist" would cost more than the network probe it replaces.
    if (pinned && blobs.holds(url)) return true
    val cached = File(File(File(dir, LIVE_BUNDLE_DIR), PER_PREVIEW_DIR), "$stem.png")
    if (cached.isFile && cached.length() > 0 && isCompleteExecutableBundle(cached)) return true
    return runCatching { branchProbe(url) }.getOrDefault(false)
  }

  private fun perPreviewBundleUrl(stem: String, liveBundle: LiveBundle, base: String): String {
    val prefix = liveBundle.path.trim('/')
    val rel = "$PER_PREVIEW_DIR/$stem.png"
    return if (prefix.isEmpty()) "$base$rel" else "$base$prefix/$rel"
  }

  /** A cached download is usable without a sibling pool and was not split as view-only. */
  private fun isCompleteExecutableBundle(bundle: File): Boolean = runCatching {
    BundleReader.readMetadata(bundle).manifest.let { manifest ->
      manifest.resolution != "view-only" &&
        manifest.externalClasspath.isEmpty() &&
        manifest.externalResources.isEmpty()
    }
  }
    .getOrDefault(false)

  /** Read one already-verified external resource from the monolithic bundle's materialized pool. */
  private fun readMaterializedExternalResource(
    entry: BundleReader.ExternalResource,
    externalResourcesDir: File?,
  ): ByteArray? {
    val root = externalResourcesDir?.canonicalFile?.toPath() ?: return null
    if (entry.path.isBlank() || entry.path.startsWith("/") || ".." in entry.path.split("/")) {
      return null
    }
    val file = File(externalResourcesDir, entry.path).canonicalFile
    if (!file.toPath().startsWith(root) || !file.isFile) return null
    return file.readBytes()
  }

  /** Fetch and verify one whole classpath entry into the shared content-addressed cache. */
  private fun fetchExternalClasspathBlob(
    entry: BundleReader.ExternalClasspath,
    base: String,
    bundlePathPrefix: String,
    system: String,
  ): ByteArray? {
    val sha = entry.sha256
    if (
      entry.path != "classes/app.jar" ||
        entry.size <= 0 ||
        entry.size > MAX_LIVE_BUNDLE_FETCH_BYTES ||
        sha.length != 64 ||
        sha.any { it !in '0'..'9' && it !in 'a'..'f' }
    ) {
      System.err.println("serve: $system external classpath declaration is invalid — skipping")
      return null
    }
    val prefix = bundlePathPrefix.trim('/')
    val url = if (prefix.isEmpty()) "$base$RES_POOL_DIR/$sha" else "$base$prefix/$RES_POOL_DIR/$sha"
    // Content-addressed: the pool returns a hit only once its bytes hash back to the digest the
    // manifest declared, so a truncated or corrupt entry is refetched rather than put on a
    // classpath. Being keyed by that digest rather than by a URL is also what makes it safe on an
    // un-pinned load — there is no moving address involved.
    val blob =
      blobs.contentAddressed(sha, entry.size) {
        runCatching { fetchExecutableBundle(url) }
          .getOrNull()
          ?.takeIf { it.size.toLong() == entry.size }
      }
    if (blob == null) {
      System.err.println("serve: $system external classpath could not be fetched ($url)")
      return null
    }
    return runCatching { blob.readBytes() }.getOrNull()
  }

  /** Filesystem/route-safe stem for a per-preview id (mirrors `bundle split`'s sanitiser). */
  private fun sanitizePerPreviewName(id: String): String = buildString {
    for (c in id) append(if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_')
  }

  /**
   * Outcome of rehydrating a live bundle's externalized resources. See
   * [rehydrateExternalResources].
   */
  private sealed interface ResRehydrate {
    /**
     * Ready to serve: [dir] is the materialized classpath dir, or null when nothing was external.
     */
    data class Ready(val dir: File?) : ResRehydrate

    /**
     * A resource was declared but couldn't be fetched/verified — the live bundle must be skipped.
     */
    data object Unavailable : ResRehydrate
  }

  /**
   * Rehydrate the resources [bundleFile]'s manifest lifted out with `bundle externalize` (fonts,
   * recorded in `externalResources` by name+sha256+size). Each is fetched — once, shared across
   * systems + catalog reloads — into a content-addressed cache under `<root>/$RES_CACHE_DIR/<sha>`,
   * verified against its sha256, then materialized at its recorded classpath [path] under
   * `<dir>/$RES_MATERIALIZED_DIR/` so the daemon's classloader resolves `/fonts/…` exactly as it
   * did with the fonts inline. The pool lives beside the bundle on the trusted branch
   * (`<liveBundle.path>/$RES_POOL_DIR/<sha>`), enumerated by the trusted manifest (not client
   * input) and each write path-contained.
   *
   * Returns [ResRehydrate.Ready] with a null dir when the bundle externalized nothing
   * (self-contained — the caller passes no extra classpath), the materialized dir when it did, or
   * [ResRehydrate.Unavailable] (fail-closed) if any declared resource has a bad sha/path or can't
   * be fetched/verified — the caller then skips the live bundle rather than run the daemon with the
   * fonts missing.
   */
  private fun rehydrateExternalResources(
    bundleFile: File,
    base: String,
    bundlePathPrefix: String,
    dir: File,
    system: String,
  ): ResRehydrate {
    val resources =
      runCatching { BundleReader.readMetadata(bundleFile).manifest.externalResources }.getOrNull()
        ?: emptyList()
    if (resources.isEmpty()) return ResRehydrate.Ready(null)

    val materialized = File(dir, RES_MATERIALIZED_DIR)
    val matRoot = materialized.canonicalFile.toPath()
    val prefix = bundlePathPrefix.trim('/')

    for (res in resources) {
      val sha = res.sha256
      if (sha.length != 64 || sha.any { it !in '0'..'9' && it !in 'a'..'f' }) {
        System.err.println(
          "serve: $system external resource '$sha' is not a sha256 — skipping live bundle"
        )
        return ResRehydrate.Unavailable
      }
      // Content-addressed cache: fetch once, reuse across systems, reloads and — given a durable
      // `--catalog-cache-dir` — restarts. The cache key IS the sha256, so a hit is only trusted
      // after its bytes hash back to that key: a same-length but corrupt entry (partial write,
      // disk fault) is refetched, not silently put on the classpath.
      val url =
        if (prefix.isEmpty()) "$base$RES_POOL_DIR/$sha" else "$base$prefix/$RES_POOL_DIR/$sha"
      val cached =
        blobs.contentAddressed(sha, res.size) { runCatching { fetchCatalogAsset(url) }.getOrNull() }
      if (cached == null) {
        System.err.println(
          "serve: $system external resource could not be fetched or verified ($url) — " +
            "skipping live bundle"
        )
        return ResRehydrate.Unavailable
      }
      // Materialize at the recorded classpath path (path-contained — reject traversal/absolute).
      if (res.path.isBlank() || res.path.startsWith("/") || ".." in res.path.split("/")) {
        System.err.println(
          "serve: $system external resource path '${res.path}' is invalid — skipping live bundle"
        )
        return ResRehydrate.Unavailable
      }
      val dest = File(materialized, res.path)
      if (!dest.canonicalFile.toPath().startsWith(matRoot)) {
        System.err.println(
          "serve: $system external resource path '${res.path}' escapes — skipping live bundle"
        )
        return ResRehydrate.Unavailable
      }
      dest.parentFile?.mkdirs()
      cached.copyTo(dest, overwrite = true)
    }
    return ResRehydrate.Ready(materialized)
  }

  /**
   * Fetch the catalog's baked `figma/<slug>.svg` exports (+ each hybrid SVG's external
   * `<slug>.figma-raster/<node>.png` crops) from [base] into `<dir>/figma/`, so the static host can
   * serve an editable vector per preview. Best-effort per slug (a missing SVG just means that
   * component carried none); each write is path-contained like the images. Returns the local
   * `figma/` dir when at least one SVG was written, else null.
   */
  private fun fetchFigmaSvgs(
    slugs: Set<String>,
    variantPaths: Set<String>,
    base: String,
    dir: File,
    stillCurrent: () -> Boolean = { true },
  ): File? {
    val figmaDir = File(dir, FIGMA_DIR)
    val figmaRoot = figmaDir.canonicalFile.toPath()
    var wrote = 0
    val candidates = buildList {
      addAll(variantPaths)
      addAll(slugs.map { "$it.svg" })
    }
    // Same concurrent prefetch the baked images get, in two waves because the second is discovered
    // by reading the first: a hybrid SVG names its `figma-raster/` crops inside its own markup, and
    // raw.githubusercontent has no directory listing to enumerate them from.
    val safeCandidates = candidates.filter { relativePath ->
      val segments = relativePath.split("/")
      relativePath.isNotEmpty() &&
        relativePath.endsWith(".svg") &&
        ".." !in segments &&
        segments.size in 1..2
    }
    // Wave 1: the vectors themselves, written straight to disk by the workers (so a catalog's
    // whole vector set is never resident at once) and path-contained before planning.
    val writtenSvgs =
      fetchCatalogAssetsToFiles(
        stillWanted = stillCurrent,
        plan =
          safeCandidates.mapNotNull { relativePath ->
            val svgFile = File(figmaDir, relativePath)
            if (!svgFile.canonicalFile.toPath().startsWith(figmaRoot)) return@mapNotNull null
            "$base$FIGMA_DIR/$relativePath" to svgFile
          },
      )
    // Wave 2: the crops, which can only be discovered by reading wave 1 — a hybrid SVG names its
    // `figma-raster/` crops inside its own markup, and raw.githubusercontent has no directory
    // listing. Each vector is re-read from the file just written (a local read, one at a time)
    // rather than held from the fetch.
    if (!stillCurrent()) return null
    val cropPlan = safeCandidates.flatMap { relativePath ->
      val svgFile = File(figmaDir, relativePath)
      if ("$base$FIGMA_DIR/$relativePath" !in writtenSvgs) return@flatMap emptyList()
      val svg = runCatching { svgFile.readText() }.getOrNull() ?: return@flatMap emptyList()
      val remoteParent = relativePath.substringBeforeLast('/', missingDelimiterValue = "")
      figmaRasterHrefs(svg).mapNotNull { href ->
        if (href.isEmpty() || ".." in href.split("/")) return@mapNotNull null
        val cropFile = File(svgFile.parentFile, href)
        if (!cropFile.canonicalFile.toPath().startsWith(figmaRoot)) return@mapNotNull null
        val remoteCrop = if (remoteParent.isEmpty()) href else "$remoteParent/$href"
        "$base$FIGMA_DIR/$remoteCrop" to cropFile
      }
    }
    fetchCatalogAssetsToFiles(cropPlan, stillWanted = stillCurrent)
    wrote = writtenSvgs.size
    return if (wrote > 0) figmaDir else null
  }

  /**
   * The vectors that would serve [heroId], most specific first: the per-variant
   * `figma/<slug>/<variant>.svg` the exporter emits per image, then the back-compat per-component
   * `figma/<slug>.svg`. Empty when the catalog named no hero.
   */
  private fun heroSvgCandidates(heroId: String?): List<String> {
    val id = heroId ?: return emptyList()
    val slug = id.substringBefore(SLUG_SEPARATOR)
    val variant = id.substringAfter(SLUG_SEPARATOR, missingDelimiterValue = "")
    return buildList {
      if (variant.isNotEmpty()) add("$slug/$variant.svg")
      add("$slug.svg")
    }
  }

  /**
   * Fetch the first of [candidates] that exists, to decide whether this branch carries the figma
   * lane at all. Returns the local `figma/` dir when one landed, else null — which is what keeps a
   * catalog with no published vectors from advertising an SVG control that would 404 on every
   * preview. One or two requests; the rest of the set follows in [scheduleFigmaSvgFetch].
   */
  private fun probeFigmaSvg(candidates: List<String>, base: String, dir: File): File? {
    val figmaDir = File(dir, FIGMA_DIR)
    val figmaRoot = figmaDir.canonicalFile.toPath()
    val plan = candidates.mapNotNull { relativePath ->
      val svgFile = File(figmaDir, relativePath)
      if (!svgFile.canonicalFile.toPath().startsWith(figmaRoot)) return@mapNotNull null
      "$base$FIGMA_DIR/$relativePath" to svgFile
    }
    // One concurrent wave, so a catalog that publishes no vectors pays a single round-trip rather
    // than one per candidate.
    return figmaDir.takeIf { fetchCatalogAssetsToFiles(plan).isNotEmpty() }
  }

  /**
   * Fill the catalog's remaining baked vectors off the publish path. Best-effort and fire-and-
   * forget: the catalog is already registered and serving, and every consumer of a vector degrades
   * to "no crop / no SVG download" until its file lands.
   *
   * Guarded by [generation]: a catalog refresh replaces `dir` under a new generation, so a pass
   * started for the old one stops rather than writing superseded vectors into the fresh catalog.
   */
  private fun scheduleFigmaSvgFetch(
    system: String,
    generation: Int,
    slugs: Set<String>,
    variantPaths: Set<String>,
    base: String,
    dir: File,
  ) {
    figmaExecutor.execute {
      if (generations[system] != generation) return@execute
      // Its own scope: this lane runs after the result was handed back, so it reports its own
      // incompleteness rather than being counted into anyone else's load.
      val incomplete = inFetchScope { scope ->
        runCatching {
          fetchFigmaSvgs(slugs, variantPaths, base, dir) { generations[system] == generation }
        }
          .onFailure { System.err.println("serve: catalog $system figma vectors: ${it.message}") }
        scope.sawTransientFailure
      }
      // Re-checked, not just checked on entry: a newer load of this system can finish while this
      // lane is still reading, and un-settling a revision this work was never about would cost the
      // fresh one a needless full reload. The new revision reports its own incompleteness.
      if (incomplete && generations[system] == generation) onPostPublishIncomplete(system)
    }
  }

  private fun scheduleRcCompareFetch(
    system: String,
    generation: Int,
    alias: Map<String, String>,
    base: String,
    dir: File,
  ) {
    if (!ServeRcCompare.stagesFor(alias)) return
    figmaExecutor.execute {
      if (generations[system] != generation) return@execute
      // Post-publish and self-reporting, like the vector fills beside it.
      val incomplete = inFetchScope { scope ->
        runCatching { fetchRcCompare(alias, base, dir) { generations[system] == generation } }
          .onFailure { System.err.println("serve: catalog $system rc-compare: ${it.message}") }
        scope.sawTransientFailure
      }
      // Re-checked, not just checked on entry: a newer load of this system can finish while this
      // lane is still reading, and un-settling a revision this work was never about would cost the
      // fresh one a needless full reload. The new revision reports its own incompleteness.
      if (incomplete && generations[system] == generation) onPostPublishIncomplete(system)
    }
  }

  /**
   * Stage the catalog's **published player comparison** — what `rc-compare.html` is built from — so
   * the compare page can show every Remote Compose player side by side without rendering anything
   * in the visitor's browser.
   *
   * One small JSON decides the whole lane: `rc-compare-summary.json` names every preview the
   * offline run scored, which players ran, and what each scored against the baked PNG. Everything
   * else follows from it — [ServeRcCompare.plan] re-keys those rows from daemon ids to catalog ids
   * through [alias] and lists the lane PNGs to fetch, and only *rendered* cells cost a round-trip.
   * A catalog that ships no `ir/<id>.rc` publishes no summary, so this is a single 404 and done.
   *
   * The manifest is written **last**, after the images: it is what [ServeRcCompareStore] gates on,
   * so a page can never be handed a row whose pixels are still in flight. Cells whose image didn't
   * arrive are dropped from it rather than served as broken `<img>`s.
   */
  private fun fetchRcCompare(
    alias: Map<String, String>,
    base: String,
    dir: File,
    stillWanted: () -> Boolean,
  ) {
    // Every terminal outcome writes the manifest, including "this catalog publishes none"
    // ([ServeRcCompare.NONE]). It is what tells the compare page the lane has SETTLED: until it
    // lands the page's shape is provisional and must not be edge-cached, and a catalog with nothing
    // to show would otherwise stay "pending" for the life of the host. See
    // [ServeRcCompareStore.pending].
    fun settle(manifest: RcCompareManifest) {
      if (!stillWanted()) return
      val root = File(dir, ServeRcCompare.DIRECTORY)
      root.mkdirs()
      File(root, ServeRcCompare.INDEX_FILE)
        .writeText(json.encodeToString(RcCompareManifest.serializer(), manifest))
    }

    val summaryBytes =
      runCatching { fetchCatalogAsset(base + ServeRcCompare.SUMMARY_FILE) }.getOrNull()
        ?: return settle(ServeRcCompare.NONE)
    val summary = ServeRcCompare.parseSummary(summaryBytes) ?: return settle(ServeRcCompare.NONE)
    val plan = ServeRcCompare.plan(summary, alias) ?: return settle(ServeRcCompare.NONE)
    if (!stillWanted()) return

    val root = File(dir, ServeRcCompare.DIRECTORY)
    val rootPath = root.canonicalFile.toPath()
    // Staged names come from a fixed lane vocabulary and an integer slot, so this is belt and
    // braces — but the write stays contained the same way every other staging lane's does.
    val fetchPlan =
      plan.assets.mapNotNull { (source, staged) ->
        val target = File(root, staged)
        if (!target.canonicalFile.toPath().startsWith(rootPath)) null else (base + source) to target
      }
    val fetched = fetchCatalogAssetsToFiles(fetchPlan, stillWanted)
    if (!stillWanted()) return
    val staged = plan.assets.filterKeys { (base + it) in fetched }.values.toSet()
    settle(ServeRcCompare.retainStaged(plan.manifest, staged) ?: ServeRcCompare.NONE)
  }

  /**
   * Stage the published annotation manifest, if the catalog has one.
   *
   * A served catalog is a fresh staging directory assembled from fetched parts, not the published
   * tree — so anything not copied here is invisible to [ServeBundleHost] no matter what the
   * producer published. Unlike design references there are no assets to fetch: annotations are pure
   * geometry keyed by preview and reference id, so staging is a straight copy of the manifest.
   *
   * Fail-soft like the rest of the staging path: a catalog with no manifest, or an unreadable one,
   * simply serves without annotation layers.
   */
  private fun writeAnnotations(base: String, staging: File) {
    val bytes =
      runCatching {
        fetchCatalogAsset(
          "$base${ServeAnnotationStore.DIRECTORY}/${ServeAnnotationStore.INDEX_FILE}"
        )
      }
        .getOrNull() ?: return
    val manifest =
      runCatching { json.decodeFromString(AnnotationManifest.serializer(), bytes.decodeToString()) }
        .getOrNull()
        ?.takeIf { it.schema == AnnotationManifest.SCHEMA } ?: return
    if (manifest.previews.isEmpty() && manifest.references.isEmpty()) return
    val dir = File(staging, ServeAnnotationStore.DIRECTORY)
    dir.mkdirs()
    File(dir, ServeAnnotationStore.INDEX_FILE)
      .writeText(json.encodeToString(AnnotationManifest.serializer(), manifest))
  }

  /**
   * Stage the published tag index, if the catalog has one.
   *
   * Exactly [writeAnnotations]' shape and for the same reason: a served catalog is a fresh staging
   * directory assembled from explicitly fetched parts, so a file nobody copies is invisible to
   * [ServeBundleHost] however faithfully the producer published it. Without this the index would be
   * published by every catalog build and read by nobody — and the element gates it exists for would
   * stay unreachable on published catalogs, which is the gap this whole path closes.
   *
   * One self-contained JSON document, no assets to fetch. Validated here before it is written
   * rather than only on read, so a malformed index never reaches the staging tree at all.
   */
  private fun writeTagIndex(base: String, staging: File) {
    val bytes =
      runCatching {
        fetchCatalogAsset("$base${ServeTagIndexStore.DIRECTORY}/${ServeTagIndexStore.INDEX_FILE}")
      }
        .getOrNull() ?: return
    val manifest =
      runCatching { json.decodeFromString(TagIndexManifest.serializer(), bytes.decodeToString()) }
        .getOrNull()
        ?.takeIf { it.schema == TagIndexManifest.SCHEMA } ?: return
    if (manifest.previews.isEmpty()) return
    val dir = File(staging, ServeTagIndexStore.DIRECTORY)
    dir.mkdirs()
    File(dir, ServeTagIndexStore.INDEX_FILE)
      .writeText(json.encodeToString(TagIndexManifest.serializer(), manifest))
  }

  /**
   * Stage the catalog's committed known differences: the document, verbatim, plus the artifacts it
   * names.
   *
   * Without this the whole acceptance surface is dead on every *published* catalog — which is every
   * catalog the feature is for. [ServeBundleHost.knownDifferences] reads the staging tree, so a
   * file nobody copies here is a catalog that "accepts nothing" as far as the comparison band, the
   * dashboard's audit, the `/parity` availability lane and the landing's link are concerned. That
   * failure is silent by construction: absence and "nothing accepted" are the same answer
   * downstream, which is why this went unnoticed until someone looked for the panel in production.
   *
   * **The document is copied byte for byte, and not parsed for judgement.** Its verdicts belong to
   * the engine, which runs from one shared implementation in the browser and in `design-parity`; a
   * stager that validated records would be a third implementation of the contract with no
   * conformance suite behind it. What this *does* parse is the one thing a copier cannot avoid
   * knowing — which artifact paths to fetch — and it does that leniently, exactly as the browser
   * adapter's prefetch does: a document this cannot read stages alone, and the engine still reaches
   * `document-unreadable` from the bytes.
   *
   * **An over-sized document — or artifact — is staged anyway**, because `document-too-large` and
   * `artifact-too-large` are verdicts the contract requires a consumer to be able to reach:
   * [ServeKnownDifferences.document] and [ServeKnownDifferences.artifact] answer `TooLarge` from
   * the file's length, the route serves 413, and the engine says so. Dropping either here would
   * substitute silence — a missing file, which is the *different* verdict `unreadable` — for a
   * refusal the reader already knows how to voice.
   *
   * Each artifact path is checked with the reader's own lexical rule before anything is fetched or
   * written ([ServeKnownDifferences.isLookupPath]), so a path the host would refuse to look up
   * never lands in the staging tree — and, since the rule admits only portable segments, cannot
   * escape the artifact root while being written. The paths are the document's own: unlike a design
   * page, an artifact cannot be re-rooted to a server-chosen name, because the record's hash is
   * bound to the path it names.
   *
   * Fail-soft like every writer beside it, and bounded like the contract: a document the reader
   * will refuse whole — past [ServeKnownDifferences.MAX_DOCUMENT_BYTES], or past
   * [ServeKnownDifferences.MAX_ACCEPTANCES] records — contributes no paths at all rather than the
   * first 256; an artifact that cannot be written is skipped rather than failing the refresh; and
   * the artifacts stream to disk through the same bounded helper every other bulk lane here uses,
   * so a refresh holds one artifact per worker rather than a whole wave.
   */
  private fun writeKnownDifferences(base: String, staging: File) {
    val dirName = ServeKnownDifferences.DIRECTORY
    val documentFile = File(staging, "$dirName/${ServeKnownDifferences.DOCUMENT_FILE}")
    val documentOutcome =
      runCatching {
        fetchCatalogAssetOutcome("$base$dirName/${ServeKnownDifferences.DOCUMENT_FILE}")
      }
        .getOrNull() ?: return
    // The transport's own ceiling is far above the contract's, so a document it refuses by size is
    // one the reader would refuse anyway — but it would refuse it as *absent*, because a fetch that
    // brings back no bytes and a branch that published no file are the same `null`. Staging a
    // marker past the contract's ceiling restores the verdict the producer earned: the reader
    // measures the file, answers `TooLarge`, the route serves 413 and the engine says
    // `document-too-large` rather than `document-unreadable`. Nothing reads the marker's bytes —
    // [ServeKnownDifferences.document] refuses from the length before opening it — so it is a
    // length, not a payload.
    if (documentOutcome is BranchFetch.TooLarge) {
      stageOversizeMarker(documentFile, ServeKnownDifferences.MAX_DOCUMENT_BYTES + 1L)
      return
    }
    val documentBytes = documentOutcome.bytesOrNull ?: return

    val artifactRoot = "$dirName/${ServeKnownDifferences.ARTIFACT_DIRECTORY}"
    // **Streamed to disk, never accumulated.** Each worker holds only the artifact it is writing,
    // which is what [fetchCatalogAssetsToFiles] exists for and why every other bulk lane in this
    // file goes through it. Collecting a wave into a map first would retain
    // [ASSET_FETCH_CONCURRENCY] whole artifacts at once — and the transport's own ceiling is far
    // above the contract's, so a wave of the 8–25 MiB files this writer now deliberately stages
    // (so the reader can answer `TooLarge` rather than 404) is hundreds of megabytes of heap held
    // to write bytes it already has. A refresh that ran the server out of memory instead of
    // eventually serving 413 would be the cap defeated by the code staging it.
    //
    // An over-sized artifact is still staged, for the reason the over-sized document is: the reader
    // refuses it from the file's length, the route serves 413, and the engine reaches
    // `artifact-too-large`. Dropping it would leave a missing file behind, which is
    // `artifact-unreadable` — a different verdict, and one that hides why. And a path whose
    // segments are all portable can still exceed what the serving filesystem will take: the helper
    // guards each write, so one unwriteable artifact is an artifact the engine calls unreadable
    // rather than a reason to abandon the refresh.
    fetchCatalogAssetsToFiles(
      knownDifferenceArtifactPlan(base, documentBytes).map { path ->
        "$base$artifactRoot/$path" to File(staging, "$artifactRoot/$path")
      },
      // An artifact the transport refuses by size gets the same marker treatment the document does,
      // for the same reason and against the artifact ceiling: `artifact-too-large`/413 is the
      // verdict the contract names, and dropping the file would leave `artifact-unreadable`/404 —
      // the answer that means the producer published nothing.
      oversizeMarkerBytes = ServeKnownDifferences.MAX_ARTIFACT_BYTES + 1L,
    )

    File(staging, dirName).mkdirs()
    documentFile.writeBytes(documentBytes)
  }

  /**
   * Write a placeholder of exactly [length] bytes, for an asset the transport refused by size.
   *
   * Set rather than written: [java.io.RandomAccessFile.setLength] extends the file without
   * producing its bytes, which every filesystem this runs on stores as a hole. The point is the
   * size — the readers this exists for ([ServeKnownDifferences.document],
   * [ServeKnownDifferences.artifact]) answer `TooLarge` from the metadata and never open the file —
   * so materialising 8 MiB of zeroes to say "too big" would be the ceiling defeated by the code
   * enforcing it.
   *
   * Fail-soft like every writer around it: a marker that cannot be written leaves the asset absent,
   * which is what it was before.
   */
  private fun stageOversizeMarker(target: File, length: Long): Boolean = runCatching {
    target.parentFile?.mkdirs()
    java.io.RandomAccessFile(target, "rw").use { it.setLength(length) }
    true
  }
    .getOrDefault(false)

  /**
   * Which artifact files to fetch: **the producer's list when it publishes one**, and only
   * otherwise the paths derived from the document.
   *
   * The published index ([ServeKnownDifferences.ARTIFACT_INDEX_FILE]) is written by the producer
   * that wrote the files, so it is the one source that cannot be wrong about what exists.
   * Preferring it retires the derivation below along with every rule the derivation had to mirror —
   * including the ones nobody has thought of yet.
   *
   * **A fetch plan, not an authority.** Three things follow, and all three are deliberate:
   * - every path still goes through [ServeKnownDifferences.isLookupPath] before it is fetched or
   *   written. A producer naming `../../secrets.png` in its index gets exactly the refusal it gets
   *   for naming it in the document. The index changes *where the list comes from*, never what this
   *   host will look up.
   * - a list that disagrees with the document is not an error to report. The document remains the
   *   contract: a file the index omits is not staged and evaluates as `artifact-unreadable`, which
   *   is already the verdict for a file a producer forgot to commit.
   * - the index is not consulted for whether the *document* is readable. It says what to copy, and
   *   a host that let it decide that would have swapped one derivation for another.
   *
   * **Absent means "this producer does not publish one", not "there is nothing".** So a catalog
   * published before the index existed falls back to the derivation and behaves exactly as it did —
   * which is what makes this purely additive. An index that parses and names nothing is a different
   * answer: an empty list, honoured as one.
   */
  private fun knownDifferenceArtifactPlan(base: String, documentBytes: ByteArray): List<String> {
    // **The document's own length gates everything, index or no index.**
    //
    // Past [ServeKnownDifferences.MAX_DOCUMENT_BYTES] the reader answers `TooLarge` and the engine
    // refuses the document whole — reading not one artifact. So every byte fetched for one is a
    // byte fetched for no verdict, and at the caps that is 512 individually legal files on every
    // refresh. The transport's ceiling is 25× the contract's, so a document in that band arrives
    // intact, its index arrives intact, and preferring the index walked straight past the guard
    // that made this cheap.
    //
    // This is **not** the derivation coming back. It is one property of a file already in hand,
    // measured rather than interpreted — the cheapest fact there is, and the only class of verdict
    // a fetch planner has to know in advance. The per-record rules stay where they belong.
    if (documentBytes.size > ServeKnownDifferences.MAX_DOCUMENT_BYTES) return emptyList()
    return oneWritePerFile(
      publishedArtifactIndex(base) ?: knownDifferenceArtifactPaths(documentBytes)
    )
  }

  /**
   * The plan with any path that would write a file an earlier path already claims removed.
   *
   * **A staging invariant, not a contract rule**: never schedule two writes that can land on one
   * file. `glyph/mask.png` and `glyph/MASK.PNG` are distinct strings, both portable, both fetched
   * from different URLs — and on Windows or a default macOS volume they are ONE file. The plan runs
   * concurrently, so staging both means last-finisher-wins: the bytes left behind are whichever
   * worker returned second, and the canonical spelling on disk may be the one
   * [ServeKnownDifferences.artifact] then rejects for case. A record's real artifact can be
   * overwritten by a sibling, differently on each refresh.
   *
   * [publishedArtifactIndex] already refuses such a list outright, and can: refusing it means
   * falling back to the derivation, so nothing is lost. The **derivation** has nothing below it, so
   * rejecting there would strip every legal record in the document of its artifacts — the
   * changed-verdict failure that whole path is written to avoid. Hence first-spelling-wins rather
   * than reject: it drops a path only when another path in the SAME plan already targets that file,
   * so no artifact is lost that was not already liable to be clobbered by its colliding sibling.
   * What it buys is that the outcome is a deterministic function of the document rather than of
   * which fetch returned last.
   *
   * Applied to the plan rather than to either source, because it is a property of executing the
   * plan — a future third source would need it just the same. It is a no-op on an index, which has
   * already been rejected if it collides.
   */
  private fun oneWritePerFile(paths: List<String>): List<String> {
    val claimed = HashSet<String>()
    return paths.filter { claimed.add(it.lowercase()) }
  }

  /**
   * The producer's artifact list, or null when this catalog publishes none.
   *
   * Fail-soft in the direction that costs least: anything unparseable, wrongly-schema'd or
   * wrongly-shaped is null, so the caller derives the list as it always did rather than staging
   * nothing. The alternative — treating a malformed index as an empty list — would let one bad file
   * silently strip every record of its artifacts, which is the changed-verdict failure this whole
   * change exists to remove.
   *
   * Bounded by the document ceiling. The index is a list of paths for a document that may name at
   * most [ServeKnownDifferences.MAX_ACCEPTANCES] records, so it has no business being larger than
   * the document itself, and a host must not read an unbounded file to find out how much to read.
   */
  private fun publishedArtifactIndex(base: String): List<String>? {
    val dirName = ServeKnownDifferences.DIRECTORY
    val url = "$base$dirName/${ServeKnownDifferences.ARTIFACT_INDEX_FILE}"
    val bytes = runCatching { fetchCatalogAsset(url) }.getOrNull() ?: return null
    if (bytes.size > ServeKnownDifferences.MAX_DOCUMENT_BYTES) return null
    val parsed =
      runCatching { json.parseToJsonElement(bytes.decodeToString()) }.getOrNull() as? JsonObject
        ?: return null
    val schema = (parsed["schema"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (schema != ServeKnownDifferences.ARTIFACT_INDEX_SCHEMA) return null
    val artifacts = parsed["artifacts"] as? JsonArray ?: return null
    if (artifacts.size > MAX_INDEXED_ARTIFACTS) return null

    val paths = LinkedHashSet<String>()
    for (entry in artifacts) {
      // **A wrongly-typed entry rejects the whole index**, rather than being skipped past.
      //
      // Skipping looks harmless and is the opposite: `{"artifacts":[null]}` would reduce to an
      // empty list, and an empty list is honoured as a producer saying "I carried nothing" — so a
      // document naming perfectly good artifacts would stage none of them and every record would
      // read as `artifact-unreadable`. That is the changed-verdict failure this file exists to
      // prevent, reached through the malformed-index fallback that was written to prevent it.
      //
      // Returning null instead hands the caller back to the derivation, which is what "this
      // producer published no usable index" has to mean for every other malformation here.
      val path = (entry as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
      // A path that parses but names something this host will not look up is a different case: the
      // reader's own lexical rule, applied to the producer's list exactly as it is applied to the
      // document's. One refused path does not make the list unreadable — a list is a convenience,
      // not a licence, and this is the licence half.
      if (ServeKnownDifferences.isLookupPath(path)) paths += path
    }

    // **Two spellings of one file is a malformed list, not a longer one.**
    //
    // `glyph/mask.png` and `glyph/MASK.PNG` are distinct strings, both portable, and on Windows or
    // a default macOS volume they are *one file*. The plan is executed concurrently, so staging
    // both schedules two workers writing the same path: whichever finishes last wins, and the
    // canonical spelling left on disk may be the one `ServeKnownDifferences.artifact` then rejects
    // for case. A record's real artifact can be overwritten by a sibling the document never named,
    // differently on each refresh.
    //
    // Reachable specifically through the index, which may legitimately carry siblings the document
    // does not name — so this is not a contract rule being mirrored, it is a staging invariant:
    // never schedule two writes that can land on one file. Rejecting the whole list rather than
    // dropping one spelling, because nothing here can know which the producer meant, and the
    // fallback is the honest answer for a list this host cannot execute safely.
    val collision = paths.groupingBy { it.lowercase() }.eachCount().any { it.value > 1 }
    if (collision) return null

    return paths.toList()
  }

  /**
   * The `<id>/<file>` paths a known-difference document names — none, when the engine will reject
   * the document whole.
   *
   * **The fallback, not the plan.** A producer that publishes an artifact index makes all of this
   * unnecessary; see [knownDifferenceArtifactPlan]. This remains for catalogs published before the
   * index existed, and it is worth keeping exactly as conservative as it is, because those catalogs
   * are the ones with nobody left to fix them.
   *
   * Two questions, and only the second is lenient.
   *
   * **Will the engine read anything at all?** A document-level rejection carries no `statuses` and
   * reads not one artifact, so every byte fetched for one is a byte fetched for no verdict — and at
   * the caps that is 256 × 2 × 8 MiB of individually legal files, on every refresh, which is the
   * resource exhaustion the caps exist to prevent reached through the guard itself. The document is
   * still staged: that is what lets the consumer voice the refusal. It simply names nothing to
   * fetch. [rejectsWholeDocument] is that question.
   *
   * **Which paths does it name?** Lenient, and the same shape the browser adapter's prefetch uses:
   * this is a fetch list, not a verdict. A record whose artifact field is not a string contributes
   * no path and the engine refuses it on its own terms once the document is served.
   */
  private fun knownDifferenceArtifactPaths(documentBytes: ByteArray): List<String> {
    if (documentBytes.size > ServeKnownDifferences.MAX_DOCUMENT_BYTES) return emptyList()
    val parsed =
      runCatching { json.parseToJsonElement(documentBytes.decodeToString()) }.getOrNull()
        as? JsonObject ?: return emptyList()
    val acceptances = parsed["acceptances"] as? JsonArray ?: return emptyList()
    if (rejectsWholeDocument(parsed, acceptances)) return emptyList()

    val paths = LinkedHashSet<String>()
    for (record in acceptances) {
      val fields = record as? JsonObject ?: continue
      val id = recordId(fields) ?: continue
      for (key in listOf("mask", "acceptedCandidate")) {
        val value = (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
        val path = "$id/$value"
        if (ServeKnownDifferences.isLookupPath(path)) paths += path
      }
    }
    return paths.toList()
  }

  /**
   * Whether the engine refuses this document before reading a single artifact.
   *
   * **A deliberate mirror of `known-differences.mjs`'s `parseDocument` + `identityFailures`**, and
   * the only place this host reads the document for meaning rather than for paths. It is not a
   * second opinion about any record: every branch below is a rejection of the *whole file*, which
   * is the one class of verdict a fetch planner has to know in advance, because the alternative is
   * fetching four gigabytes for a result that names nothing.
   *
   * **Wrong in only one direction, on purpose.** Saying "rejected" for a document the engine would
   * evaluate starves legal records of their artifacts and turns them into `artifact-unreadable` — a
   * changed verdict. Saying "not rejected" for one it refuses merely wastes a fetch.
   *
   * So the line is drawn at a property of the **file**. Those rules are few, stable, and exactly
   * decidable from the parsed JSON, and they are mirrored here. Three families of pre-read refusal
   * are deliberately **not**, and the next reader should not take their absence for an oversight:
   * - `documentTextRefusal` — a repeated member name, a non-integer geometry coordinate. Read off
   *   the bytes rather than the tree, so mirroring it means a third implementation of a JSON text
   *   scanner in a host that is not supposed to be parsing at all.
   * - `isSafeId`, and the case-folded `mask`/`acceptedCandidate` collision.
   * - `schemaReasons` — the field allow-list, the required strings, the tolerance ranges, the
   *   element block, the locator scope.
   *
   * The last two are per-**record** verdicts, and mirroring them would make this file a third
   * implementation of `compose-preview-known-differences/v1` with no conformance suite behind it —
   * the thing "two engines, one semantics" exists to prevent. Over that much detailed validation,
   * drifting stricter than the engine somewhere is not a risk but a matter of time, and stricter is
   * the direction that changes a verdict.
   *
   * What their absence costs is a wasted fetch, bounded by the ceiling a fully-populated *valid*
   * document already permits — 256 × 2 × 8 MiB is what the contract allows and what the engine
   * genuinely reads.
   *
   * That cost is now avoided rather than mitigated: a producer publishing an artifact index is
   * copied from rather than interpreted, and this whole path is skipped. What is left here runs
   * only for catalogs published before the index existed — which is why nothing was added to it,
   * and why the three families of refusal above stay deliberately unmirrored. Growing this to close
   * the remaining waste would be building the third implementation on the one path that no longer
   * needs to exist.
   */
  private fun rejectsWholeDocument(document: JsonObject, acceptances: JsonArray): Boolean {
    // The schema and the shape. `acceptances` is already known to be an array by the caller.
    val schema = (document["schema"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (schema != ServeKnownDifferences.SCHEMA) return true
    // `additionalProperties: false` at the document level: an unknown key is `document-unreadable`,
    // for the same reason an unknown record-level one is refused — a schema-first consumer rejects
    // bytes a required-fields-only consumer would evaluate normally.
    if (document.keys.any { it != "schema" && it != "acceptances" }) return true
    if (acceptances.size > ServeKnownDifferences.MAX_ACCEPTANCES) return true

    val seen = HashSet<String>()
    for (record in acceptances) {
      // A record with no usable key cannot be reported at all, so it rejects the document rather
      // than being skipped — which is what this used to do, leaving every other record's artifacts
      // to be fetched for a result that names none of them.
      val fields = record as? JsonObject ?: return true
      val id = recordId(fields) ?: return true
      // Case-folded, because `foo` and `FOO` are two map keys and one directory on Windows and on a
      // default macOS filesystem: a document carrying both cannot even be checked out intact.
      if (!seen.add(id.lowercase())) return true
    }
    return false
  }

  /**
   * A record's `id` as the engine keys it: a string that is not blank *to JavaScript*, or nothing.
   */
  private fun recordId(fields: JsonObject): String? =
    (fields["id"] as? JsonPrimitive)
      ?.takeIf { it.isString }
      ?.content
      ?.takeIf { !isEcmaScriptBlank(it) }

  /**
   * Whether `String.prototype.trim()` would empty this string — the engine's own test for an
   * unkeyable id, spelled out rather than borrowed from the JVM.
   *
   * `String.isBlank()` is the obvious Kotlin answer and it is the wrong one, in the direction this
   * mirror must never be wrong in. It delegates to `Character.isWhitespace`, which counts the four
   * separators U+001C..U+001F and U+0085 as whitespace; ECMAScript's `TrimString` does not. So an
   * id of a single U+001C is *keyable* to the engine — the record fails on its own as `id-not-safe`
   * while every other record in the document is read normally — and would have rejected the whole
   * document here, skipping the artifacts of every legal record beside it and turning them into
   * `artifact-unreadable`. That is a changed verdict on legal records, which is the one failure
   * direction [rejectsWholeDocument] exists to avoid.
   *
   * (It differs the other way too — `Character.isWhitespace` excludes the non-breaking U+00A0,
   * U+2007, U+202F and U+FEFF that `trim()` removes — but that direction only costs a fetch.)
   *
   * The set is `WhiteSpace` ∪ `LineTerminator` from the specification: the three format controls
   * below, the four line terminators, the Unicode `Zs` category, and the byte-order mark.
   */
  private fun isEcmaScriptBlank(value: String): Boolean = value.all {
    when (it) {
      '\u0009',
      '\u000B',
      '\u000C',
      '\uFEFF' -> true
      '\u000A',
      '\u000D',
      '\u2028',
      '\u2029' -> true
      else -> Character.getType(it) == Character.SPACE_SEPARATOR.toInt()
    }
  }

  /**
   * Stage the published design-parity activity feed, if the catalog has one.
   *
   * Same reason [writeAnnotations] exists, and the same shape: a served catalog is a fresh staging
   * directory assembled from explicitly fetched parts, so a file nobody copies is invisible to
   * [ServeBundleHost] however faithfully the producer published it. Without this the `/parity` view
   * would fall back to coverage-only on every *published* catalog — which is every catalog the
   * feature is actually for — and the code/Figma feed would only ever appear for a local bundle.
   *
   * No assets to fetch: the feed is one self-contained JSON document, so staging is a straight copy
   * of the manifest. It is validated here before it is written rather than only on read, so a
   * malformed feed never reaches the staging tree at all.
   *
   * Fail-soft like the rest of the staging path: no feed, an unfetchable one, or one that doesn't
   * survive validation simply serves the catalog without the activity lane.
   */
  private fun writeParityActivity(base: String, staging: File) {
    val bytes =
      runCatching { fetchCatalogAsset("$base${ParityActivity.DIRECTORY}/${ParityActivity.FILE}") }
        .getOrNull() ?: return
    val activity =
      runCatching { json.decodeFromString(ParityActivity.serializer(), bytes.decodeToString()) }
        .getOrNull()
        ?.let { ServeParityActivityStore.sanitize(it) } ?: return
    val dir = File(staging, ParityActivity.DIRECTORY)
    dir.mkdirs()
    File(dir, ParityActivity.FILE)
      .writeText(json.encodeToString(ParityActivity.serializer(), activity))
  }

  /** Stage the validated GitHub issue snapshot published beside the parity activity feed. */
  private fun writeParityIssues(base: String, staging: File) {
    val bytes =
      runCatching { fetchCatalogAsset("$base${ParityIssues.DIRECTORY}/${ParityIssues.FILE}") }
        .getOrNull() ?: return
    val issues =
      runCatching { json.decodeFromString(ParityIssues.serializer(), bytes.decodeToString()) }
        .getOrNull()
        ?.let { ServeParityIssuesStore.sanitize(it) } ?: return
    val dir = File(staging, ParityIssues.DIRECTORY)
    dir.mkdirs()
    File(dir, ParityIssues.FILE).writeText(json.encodeToString(ParityIssues.serializer(), issues))
  }

  /**
   * Stage the catalog's parity verdict (`parity/findings.json`).
   *
   * Shaped like [writeParityIssues]: a manifest with no assets, validated here and re-serialized so
   * the staged tree holds what the reader would have kept rather than what the branch said. A
   * catalog that publishes none, or one whose manifest cannot survive validation, simply serves its
   * comparisons without a verdict panel.
   *
   * Easy to forget and silent when it is: [ServeBundleHost] reads the staged directory, so a
   * manifest nothing fetches is a manifest the host correctly reports as absent — the panel would
   * be dark on every published catalog, which is the one environment the feature exists for, with
   * no error anywhere to say why.
   */
  private fun writeParityFindings(base: String, staging: File) {
    val bytes =
      runCatching {
        fetchCatalogAsset("$base${ParityFindings.DIRECTORY}/${ParityFindings.FILE}")
      }
        .getOrNull() ?: return
    val findings =
      runCatching { ServeParityFindingStore.sanitizeDocument(bytes.decodeToString()) }.getOrNull()
        ?: return
    val dir = File(staging, ParityFindings.DIRECTORY)
    dir.mkdirs()
    File(dir, ParityFindings.FILE)
      .writeText(json.encodeToString(ParityFindings.serializer(), findings))
  }

  /**
   * Stage the catalog's design pages: the manifest, plus one cached SVG per page it still declares
   * after validation.
   *
   * Shaped like [writeDesignReferences] rather than [writeParityActivity] because it has assets:
   * the manifest alone is useless without its exports, so a page whose SVG can't be fetched is
   * dropped from the staged manifest instead of being advertised and 404ing on open. Validation
   * runs *before* the write ([ServeDesignPageStore.drawablePages]) so nothing malformed reaches the
   * staging tree, and each export is re-pathed to a server-owned location (`pages/<id>.svg`) so a
   * manifest cannot dictate where bytes land.
   *
   * Fail-soft like the rest of the staging path: no manifest, an unfetchable one, or one that
   * doesn't survive validation simply serves the catalog with no page surface.
   */
  private fun writeDesignPages(base: String, staging: File) {
    val dirName = ServeDesignPageStore.DIRECTORY
    val manifestBytes =
      runCatching { fetchCatalogAsset("$base$dirName/${ServeDesignPageStore.INDEX_FILE}") }
        .getOrNull() ?: return
    val manifest =
      runCatching {
        DesignPagesJson.decodeFromString(
          DesignPagesManifest.serializer(),
          manifestBytes.decodeToString(),
        )
      }
        .getOrNull() ?: return
    // Capped before a single byte is fetched. A catalog branch is trusted-ish but not trusted to be
    // sane: without a ceiling, a branch declaring hundreds of pages would cost one refresh that
    // many requests and fill the staging disk, since each export may be as large as the per-file
    // cap. The same reasoning as `maxImages` for previews, at a size that fits the feature — a
    // catalog publishes the sheets it reproduces, not one page per component.
    val declared = ServeDesignPageStore.drawablePages(manifest)
    val pages = declared.take(MAX_DESIGN_PAGES)
    if (declared.size > pages.size) {
      System.err.println(
        "serve: catalog declares ${declared.size} design pages — staging the first $MAX_DESIGN_PAGES"
      )
    }
    if (pages.isEmpty()) return

    // One bounded wave, like the reference rasters. A specimen sheet is a large file — the Material
    // 3 kit's Shape page is most of a megabyte with its text outlined — so peak memory here is a
    // wave of sheets, which is why the wave is the same small width the rasters use.
    val accepted =
      pages.chunked(ASSET_FETCH_CONCURRENCY).flatMap { wave ->
        val fetched = fetchCatalogAssets(wave.map { "$base$dirName/${it.image.uri}" })
        wave.mapNotNull { page ->
          val bytes = fetched["$base$dirName/${page.image.uri}"] ?: return@mapNotNull null
          val localName = "${page.id}.svg"
          val file = File(staging, "$dirName/$localName")
          file.parentFile?.mkdirs()
          file.writeBytes(bytes)
          page.copy(image = page.image.copy(uri = localName))
        }
      }
    if (accepted.isEmpty()) return
    File(staging, dirName).mkdirs()
    File(staging, "$dirName/${ServeDesignPageStore.INDEX_FILE}")
      .writeText(
        DesignPagesJson.encodeToString(
          DesignPagesManifest.serializer(),
          manifest.copy(pages = accepted),
        )
      )
  }

  /**
   * Stage the accepted references and return **reference id → its path on the delivery branch**.
   *
   * The returned map is what a pinned (`?at=<sha>`) reference request resolves against. It cannot
   * be recovered from the staged manifest, which deliberately rewrites every raster to a
   * server-owned `references/<id>.png`: that rewrite is what contains the reference lane, and it
   * also erases the producer's own path — the only thing that addresses the raster on the branch,
   * at any commit.
   */
  private fun writeDesignReferences(
    references: List<DesignReference>,
    base: String,
    staging: File,
  ): Map<String, String> {
    if (references.isEmpty()) return emptyMap()
    val seen = HashSet<String>()
    // Rasters are the one lane that genuinely needs the bytes in hand — a reference is accepted
    // only if its declared dimensions and optional sha256 check out ([ServeDesignReferenceStore]) —
    // so this keeps the byte-returning fetch, run one bounded wave at a time. Peak memory is a
    // wave,
    // not the catalog's whole reference set.
    val branchPaths = LinkedHashMap<String, String>()
    val accepted =
      references
        .filter { ServeDesignReferenceStore.isSafeRelativePath(it.raster.path) }
        .chunked(ASSET_FETCH_CONCURRENCY)
        .flatMap { wave ->
          val fetched = fetchCatalogAssets(wave.map { "$base${it.raster.path}" })
          wave.mapNotNull { reference ->
            val bytes = fetched["$base${reference.raster.path}"] ?: return@mapNotNull null
            if (!ServeDesignReferenceStore.isValid(reference, bytes) || !seen.add(reference.id))
              return@mapNotNull null
            val localPath = "${ServeDesignReferenceStore.DIRECTORY}/${reference.id}.png"
            val file = File(staging, localPath)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            branchPaths[reference.id] = reference.raster.path
            reference.copy(raster = reference.raster.copy(path = localPath))
          }
        }
    if (accepted.isEmpty()) return emptyMap()
    val referenceDir = File(staging, ServeDesignReferenceStore.DIRECTORY)
    referenceDir.mkdirs()
    File(referenceDir, ServeDesignReferenceStore.INDEX_FILE)
      .writeText(
        json.encodeToString(
          DesignReferenceManifest.serializer(),
          DesignReferenceManifest(references = accepted),
        )
      )
    return branchPaths
  }

  /**
   * Read the published provider-neutral manifest. Catalogs originally carried references inline in
   * `catalog.json`, so [Catalog.references] remains a compatibility fallback at the call site.
   */
  private fun fetchDesignReferences(base: String): List<DesignReference> {
    val manifestBytes =
      runCatching {
        fetchCatalogAsset(
          "$base${ServeDesignReferenceStore.DIRECTORY}/${ServeDesignReferenceStore.INDEX_FILE}"
        )
      }
        .getOrNull() ?: return emptyList()
    return runCatching {
      json.decodeFromString(DesignReferenceManifest.serializer(), manifestBytes.decodeToString())
    }
      .getOrNull()
      ?.takeIf { it.schema == DesignReferenceManifest.SCHEMA }
      ?.references
      .orEmpty()
  }

  /**
   * Project the catalog's published design tokens onto the serve chrome (see [ServeThemeCss]), or
   * null when [tokensFile] is absent, points outside the branch root, or the file can't be fetched
   * / turned into a palette. Every failure mode is the same non-event: the system's pages fall back
   * to the built-in chrome, exactly as they did before the catalog published tokens.
   */
  private fun fetchWebThemeCss(tokensFile: String?, base: String): String? {
    val path = tokensFile?.trim()?.takeIf { it.isNotBlank() } ?: return null
    // Branch-relative only. The branch is trusted, but a garbled/hostile `tokensFile` must not be
    // able to point the fetch at another host or walk out of the catalog.
    if (path.startsWith("/") || "://" in path || ".." in path.split("/")) return null
    val bytes = runCatching { fetchCatalogAsset(base + path) }.getOrNull() ?: return null
    return runCatching { ServeThemeCss.fromDtcg(bytes.decodeToString()) }.getOrNull()
  }

  /**
   * Minimal mirror of the `design-parity-catalog/v1` schema — only the bits we serve are needed.
   */
  @Serializable
  private data class Catalog(
    /** Human display title (e.g. "Compose Material 3"); surfaced on the public home index. */
    val title: String? = null,
    /** Underlying library coordinate(s); shown as the one-line descriptor on a system card. */
    val library: List<String> = emptyList(),
    /**
     * ISO-8601 timestamp the delivery branch was generated (from `catalog.json`'s flattened meta,
     * written by `generate-design-catalog.mjs`). Surfaced on the catalog landing's provenance
     * strip.
     */
    val generatedAt: String? = null,
    /**
     * The renderer that produced the catalog (`compose-preview <version>`), i.e. the
     * compose-ai-tools version. Shown on the provenance strip (the `compose-preview ` prefix is
     * stripped for display).
     */
    val renderer: String? = null,
    /**
     * The `@design-parity/catalog-export` version that built the catalog, recorded by
     * `generate-design-catalog.mjs`. Shown on the provenance strip.
     */
    val designParity: String? = null,
    val components: List<Component> = emptyList(),
    /** Provider-neutral design references, each carrying a canonical PNG for exact comparison. */
    val references: List<DesignReference> = emptyList(),
    /**
     * Optional presentation hints the system declared (stage surface + hero preview), written by
     * `generate-design-catalog.mjs` from the spec's `display`. The server reads these instead of
     * inferring the stage / hero from the system name.
     */
    val display: CatalogDisplay? = null,
    /**
     * The sibling system this catalog is a parallel rendition of (`catalog.json`'s `compareWith`),
     * written by `generate-design-catalog.mjs`. Names WHICH SYSTEM a component's
     * [Component.parallel] counterpart lives in; the two are only useful together, since half a
     * pairing resolves to nothing. Absent for a catalog that declares no cross-system pairing,
     * which is most of them.
     */
    val compareWith: CatalogCompareWith? = null,
    /**
     * The branch-relative W3C DTCG token file (`tokens.dtcg.json`) holding the system's resolved
     * `MaterialTheme` palette, written by `generate-design-catalog.mjs` for any catalog whose
     * render carried theme tokens. Read by [fetchWebThemeCss] to theme this system's web pages in
     * its own colours; absent for a catalog that publishes none.
     */
    val tokensFile: String? = null,
    /** Optional in-browser render descriptor (the CMP-Wasm app carried in the branch). */
    val webRender: WebRender? = null,
    /** Optional buildable source for trusted server-side re-render (`--allow-render-trusted`). */
    val source: Source? = null,
    /**
     * Optional executable preview bundle carried alongside the baked PNGs (desktop-CMP systems only
     * — see `scripts/design-artifacts/generate-design-catalog.mjs`), preferred over [source] for
     * trusted server-side re-render: no Gradle build, no worktree.
     */
    val liveBundle: LiveBundle? = null,
    /**
     * One independently executable bundle per Gradle module; [liveBundle] remains the v1 primary.
     */
    val liveBundles: List<LiveBundle> = emptyList(),
    /**
     * Live-only coverage: the entries and image axes the spec declared `priority: "deferred"` (or
     * thinned out of the palette with `modePriority`), which CI deliberately did NOT rasterise —
     * recorded here rather than in `components[].images` so no consumer of the baked sticker set is
     * handed an image with no pixels. See [Deferred] for how the server serves them.
     */
    val deferred: List<Deferred> = emptyList(),
    /** Structured render failures retained even when their components have no images. */
    val failures: List<CatalogRenderFailure> = emptyList(),
  )

  /**
   * One `catalog.json` `deferred[]` record: a preview the catalog declares but publishes **no baked
   * PNG** for, to be rendered on demand by a live host instead.
   *
   * [path] is the `images/…` path the sticker WOULD have been written to, recorded by the exporter
   * (`catalog-image-path.mjs`) so the server and the published catalog agree on one id namespace —
   * [previewIdFor] flattens it into exactly the route a baked sticker would have had, which is what
   * makes flipping an entry between `required` and `deferred` invisible to its URL. [previewId] is
   * the daemon preview that renders it, resolved per-annotation by the exporter the same way a
   * baked image's is; [previewIds] is its `@Preview` function's full id list, kept as a fallback
   * for a catalog published before the per-annotation resolve existed (used only when unambiguous).
   *
   * The axes ([state] / [theme] / [props]) and placement ([section] / [group]) ride along so a
   * live-only card sits in the right tab, group and state switcher — the same `variants.json`
   * metadata a baked preview carries.
   *
   * A record with no [path], or none of [previewId]/[previewIds], is skipped: without a route it
   * has no id, and without a daemon twin nothing could ever render it.
   */
  @Serializable
  private data class Deferred(
    val path: String? = null,
    val previewId: String? = null,
    val previewIds: List<String> = emptyList(),
    val componentId: String? = null,
    val section: String? = null,
    val group: String? = null,
    /**
     * The authored caption, written straight onto the record for a **wholly** deferred component:
     * such a component short-circuits before it is ever added to `components[]`
     * (`generate-design-catalog.mjs`), so this is the only copy of it in the manifest. Its deferred
     * *variant* records carry none — they inherit the entry's, which is why the caption lookup
     * folds these records in.
     */
    val caption: String? = null,
    val state: String? = null,
    val theme: String? = null,
    val props: JsonObject? = null,
    /** The declared breakpoint this live-only record renders at — see [Image.size]. */
    val size: String? = null,
    /**
     * The counterpart component in the `compareWith` sibling, for the same reason [caption] is
     * here: a **wholly** deferred component short-circuits before it reaches `components[]`, so
     * this is the only copy of its pairing in the manifest. Without it the deferred card is the one
     * card that cannot offer the sibling comparison source, on a catalog that publishes
     * `compareWith` precisely to have it.
     */
    val parallel: String? = null,
    /** Why it was deferred (`entry` / `variant` / `mode`) — carried for diagnostics. */
    val reason: String? = null,
    /**
     * Discovery-time `@FixedTheme` — see [Image.fixedTheme]. A live-only specimen needs this more
     * than a baked one, not less: it has no baked PNG to fall back to, so its ONLY render is the
     * live one, and without the flag the browse surface would re-render it under every declared
     * theme with nothing to fall back to.
     */
    val fixedTheme: Boolean = false,
    /**
     * Discovery-time `@OverrideVariant(secondary = true)` — see [VariantMeta.secondary].
     *
     * Here for the same reason [fixedTheme] is. A second-tier cell CI declared live-only is
     * described by this record and by nothing else, so without the field the flag had no route to
     * the browse surface and the cell stayed listed in the variant tree — the flag going missing
     * for precisely the deferred coverage it exists to thin out.
     */
    val secondary: Boolean = false,
  ) {
    /** The daemon preview to render this record through, or null when it has no live twin. */
    val daemonId: String?
      get() =
        previewId?.takeIf { it.isNotBlank() }
          // An older catalog carries only the function's id list; take it only when the function
          // produced exactly one preview, since anything else would be a guess between annotations.
          ?: previewIds.singleOrNull()?.takeIf { it.isNotBlank() }
  }

  /**
   * `catalog.json`'s `display`: how the system wants to be presented — the stage [surface] its
   * stickers are drawn for (`light`/`dark`) and the [hero] preview (componentId or preview id) to
   * feature on the index. Both optional; the server falls back to its own defaults when absent.
   */
  @Serializable data class CatalogDisplay(val surface: String? = null, val hero: String? = null)

  /**
   * `catalog.json`'s `compareWith`: the sibling system this catalog reproduces.
   *
   * Deliberately just the two fields a CONSUMER can act on. [system] is the sibling's slug, which
   * is also its path on a server hosting both — so a preview server can resolve the counterpart's
   * render without leaving its own origin. [repo] is carried only when the spec declared one (the
   * common same-repo pairing declares none), and says where to look for a sibling this server does
   * not host. The producer's own `spec` / `designTitle` / `design` fields are publish-time layout
   * and never travel.
   */
  @Serializable data class CatalogCompareWith(val system: String = "", val repo: String? = null)

  /**
   * `catalog.json` live bundle descriptor. Prefix partitions collision-safe daemon ids by module.
   */
  @Serializable
  private data class LiveBundle(
    val path: String = "",
    val file: String = "",
    val module: String = "",
    val previewIdPrefix: String = "",
  )

  /** `catalog.json`'s `source`: the repo/ref/module to build to re-render this catalog live. */
  @Serializable
  private data class Source(val repo: String = "", val ref: String = "", val module: String = "")

  @Serializable
  private data class Component(
    val componentId: String? = null,
    val images: List<Image> = emptyList(),
    /**
     * The `componentId` of this component's counterpart in the [Catalog.compareWith] sibling
     * (`@CatalogComponent(parallel = …)`). The other half of the pairing: `compareWith` says which
     * system, this says which component in it. Null for a component with no declared counterpart,
     * and for every catalog that declares no pairing at all.
     */
    val parallel: String? = null,
    /**
     * The one-line description the catalog authored for this component (`@CatalogComponent(caption
     * = …)`, or the spec entry that overrides it) — what the component is FOR, in the design
     * system's own words. The export has always written it into `catalog.json`; the serve layer
     * simply never read it, so a browse surface could name a component but never say what it was.
     * Null for a catalog that authors none, and for a plain uploaded bundle.
     */
    val caption: String? = null,
    /**
     * Top-level **section** (the tab a preview host buckets this component under — `"Themes"`,
     * `"Components"`, `"Screens"`, `"Animations"`, …). Sits one level above [group]. Null ⇒ the
     * component is untabbed (a flat catalog).
     */
    val section: String? = null,
    /** Sub-heading group within a [section] (e.g. `"Buttons"`, `"Contacts"`). */
    val group: String? = null,
    /**
     * Module-relative source path of the `@Preview` this component renders (`src/main/kotlin/…`),
     * stamped by the design-artifacts export (`apply-source-files.mjs`) from discovery's
     * `previews.json`. Carried into `previews/variants.json` so the viewer can link the preview to
     * its source on GitHub. Null for an older catalog that predates the export change.
     */
    val sourceFile: String? = null,
    /**
     * Gradle project path that owns [sourceFile]. Set for repository-wide catalogs, where sibling
     * modules cannot share the catalog-level [Source.module]. Null for legacy single-module
     * catalogs, whose source module remains catalog-wide.
     */
    val sourceModule: String? = null,
    /**
     * A 1-based line inside the `@Preview` function's body within [sourceFile], stamped by the same
     * export pass from discovery's `previews.json`. Carried into `previews/variants.json` so the
     * playground handoff can open just that declaration rather than the whole section file. Null
     * for an older catalog, or a preview whose classfile carried no line numbers.
     */
    val bodyLine: Int? = null,
    /**
     * The component's animated captures (`@InteractionPreview` / `@AnimatedPreview`), published
     * beside its stills under `motion/` on the delivery branch.
     *
     * A sibling axis to [images] rather than more entries inside it, because every consumer of
     * [images] assumes a still: the grid lays them out as a sheet, a parity run diffs them against
     * a kit node. A 114-frame recording folded in there would publish its first frame and silently
     * drop the point. Empty for a catalog with no motion, and for one exported before the branch
     * carried these bytes at all.
     */
    val motion: List<Motion> = emptyList(),
  )

  /**
   * One published animated capture: what moved, why a reader should care, and which themed card it
   * belongs beside.
   *
   * [path] is a branch path (`motion/<slug>/<variant>.apng`) named from the sibling sticker by the
   * export, so it flattens to the same route id the still does — see [motionPreviewIdOf].
   */
  @Serializable
  private data class Motion(
    val path: String = "",
    /** `"interaction"` (a scripted gesture) or `"animation"` (a self-running animation). */
    val kind: String = "",
    /**
     * The caption the annotation declared. A motion capture without one is close to useless — the
     * reader can see *that* something moved, and this is what tells them which property they are
     * being shown — so the viewer surfaces it beside the capture rather than dropping it.
     */
    val caption: String? = null,
    /** The theme of the sticker this capture accompanies, used to pair it with the right card. */
    val theme: String? = null,
  )

  @Serializable
  private data class Image(
    val path: String,
    /**
     * The **daemon preview id** that produced this image (`FilledButton_Dark`), recorded by the
     * exporter so a live host can bridge the route-safe catalog id ([previewIdFor] of [path]) to
     * it. Null when the exporter couldn't map it (an older catalog, or an Android-only variant with
     * no runnable desktop preview) — then the id has no live lane and stays baked-PNG.
     */
    val previewId: String? = null,
    /**
     * The baked component **state** this render represents (`"unchecked"`, `"pressed"`,
     * `"disabled"`, `"unselected"`, …), or `"default"`/null for the default render. `foldVariants`
     * re-tags each folded variant's images with its `state`, so this is populated for every
     * state-bearing catalog. Carried into `previews/variants.json` for the serve host.
     */
    val state: String? = null,
    /**
     * The baked **theme** this render represents (`"light"`/`"dark"`), or null when unthemed. Used
     * to scope the viewer's state switcher to same-theme siblings.
     */
    val theme: String? = null,
    /**
     * The i18n / content / a11y **variant axis** this render varies — `{"locale":"ar-XB"}`,
     * `{"direction":"rtl"}`, `{"fontScale":"2.0"}`, `{"content":"icon+label"}`, … — or absent/empty
     * for the component's default render. `foldVariants` re-tags each folded props variant with its
     * axis. Carried into `previews/variants.json` so the serve grid can fold these variants onto
     * the component's one card (like [state]) instead of showing each as its own tile.
     */
    val props: JsonObject? = null,
    /**
     * The declared **breakpoint** this render was captured at (`"192dp"`, `"compact"`,
     * `"smallRound"`, …) — the `size` name from the catalog spec's `breakpoints` table, re-tagged
     * onto each image by the export. Absent for a catalog that declares no breakpoints, and for one
     * published before the export recorded them.
     *
     * Carried into `previews/variants.json` so the serve grid can fold a component's other
     * breakpoints onto its one card the way it folds [state] and [props] — a size is a different
     * rendering of the same component, not a different component, and a five-breakpoint catalog
     * otherwise publishes five identically-named cards for each of them.
     */
    val size: String? = null,
    /** Author-declared plain-Compose knobs, lifted from this preview's bundle sidecar in CI. */
    val overrides: List<PreviewOverrideDeclaration> = emptyList(),
    /** Author-declared Remote Compose named-value knobs, lifted from its bundle sidecar in CI. */
    val remoteComposeKnobs: List<RemoteComposeKnobDeclaration> = emptyList(),
    /** Discovery-time `@FocusedPreview` support, recorded without opening the live bundle. */
    val supportsFocus: Boolean = false,
    /** Discovery-time `@GestureHintPreview` support, recorded without opening the live bundle. */
    val supportsGestures: Boolean = false,
    /**
     * Discovery-time `@FixedTheme` (or a `@ThemeCatalog`-synthesised sheet): this render's subject
     * IS a theme, so the browse surface must not re-render it under a `themeProvider` override.
     * Recorded here so a specimen outside a `"Themes"` section is honoured before its per-preview
     * daemon is ever opened.
     */
    val fixedTheme: Boolean = false,
    /**
     * Whether this render is a **second-tier** variant cell — `@OverrideVariant(secondary = true)`,
     * lifted onto the image by the publisher. It renders, it is addressable and it pairs with its
     * design-kit node like any other cell; what it is kept out of is the browse surface's variant
     * tree. See [VariantMeta.secondary].
     */
    val secondary: Boolean = false,
    /**
     * This render's `@Preview` ground and device frame, lifted from the bundle's `previews.json` at
     * export time. See [PreviewParamsMeta] for why a published catalog cannot recover them
     * otherwise.
     */
    val previewParams: PreviewParamsMeta? = null,
  )

  /**
   * One entry of the `previews/variants.json` manifest: the baked [state]/[theme] a preview render
   * represents, plus the catalog [section]/[group] it belongs to and its authored [order]. Written
   * by the catalog fetch loop and read back by [ServeBundleHost]; null keys are omitted on write
   * and default to null on read.
   *
   * [section]/[group]/[order] carry the tabbed-catalog structure: a preview host groups previews by
   * [section] into tabs (Themes / Components / Screens / Animations / …), shows [group] as a
   * sub-heading within a tab, and orders both by [order] — the image's position in the catalog's
   * authored component list — because [ServeBundleHost] otherwise lists previews sorted by id. All
   * three are null for a plain (untabbed) catalog / uploaded bundle, preserving the flat layout.
   */
  /**
   * One animated capture offered beside a preview, as the served manifest carries it.
   *
   * [id] is the route the bytes are fetched under, not a branch path: the store owns URL assembly
   * and the size cap, exactly as it does for baked PNGs, so the host and the viewer only ever name
   * an id. [extension] rides along because the two formats are not interchangeable to a browser —
   * an APNG served as a GIF renders its first frame and stops.
   */
  @Serializable
  data class MotionMeta(
    val id: String,
    val kind: String? = null,
    val caption: String? = null,
    val extension: String = ".apng",
  )

  /**
   * The `@Preview` parameters a browse surface needs BEFORE any daemon is opened — what ground the
   * render sits on, and what device frame it was captured in.
   *
   * These live in a bundle's root `previews.json`, which a **published catalog does not stage**: it
   * carries per-preview metadata on `previews/variants.json` instead. So on the ordinary read-only
   * serving path — a published catalog with no trusted live daemon — every preview arrived with the
   * annotation defaults, and `PreviewBackdrop` fell back to the catalog's declared stage for all of
   * them. That is the per-preview half of the backdrop being silently inert exactly where a
   * published catalog is read, and it took the device clip down with it once
   * [ServeDeviceFrame][ee.schimke.composeai.cli.serve.ServeDeviceFrame] arrived: a round Wear
   * comparison was drawn on a square stage there and nowhere else.
   *
   * Carried as ONE nested record rather than five loose fields so the two halves cannot be wired up
   * separately and drift — the ground and the frame are the same question about the same render,
   * asked of the same annotation.
   *
   * Every field defaults, so a catalog published before this existed reads back as `null` and keeps
   * exactly its old behaviour rather than failing to parse.
   */
  @Serializable
  data class PreviewParamsMeta(
    /** `@Preview(uiMode = …)`, for the viewer's Day/Night default. */
    val uiMode: Int = 0,
    /** `@Preview(showBackground = …)`. */
    val showBackground: Boolean = false,
    /** `@Preview(backgroundColor = …)`; `0` is the annotation's own "unset". */
    val backgroundColor: Long = 0L,
    /** The raw `@Preview(device = …)` string; the shape is resolved from it, never from the dp. */
    val device: String? = null,
    /** `@Preview(widthDp = …)`, when the annotation states one. */
    val widthDp: Int? = null,
    /** `@Preview(heightDp = …)`, when the annotation states one. */
    val heightDp: Int? = null,
    /**
     * The preview's `@CaptureGutter`, in **render pixels** — the transparent margin the capture
     * added around the component so a shadow / focus ring drawn outside its bounds is not cropped
     * at the image edge.
     *
     * A browse surface has to know it: the render's canvas is `component + gutter`, so fitting the
     * whole canvas to a grid column draws the component smaller than its gutter-less siblings by
     * exactly that margin — 7% on the m3 catalog's button row, for a reason that has nothing to do
     * with the design (m3-catalog#179). Subtracting it is what makes the canvas mean the component
     * again. Pixels rather than dp because the exporter resolved them against the render's own
     * density, which a published catalog does not carry.
     */
    val captureGutter: CaptureGutterPx? = null,
  )

  /**
   * A published capture gutter, per **physical** edge, in render pixels.
   *
   * The annotation declares leading / trailing, and the renderer placed those against the layout
   * direction of the locale it composed in — so on an RTL capture the leading margin is the
   * right-hand one. A consumer of this record sees pixels, not a direction, so whoever writes it
   * resolves the mapping: the exporter for a published catalog, [asPreviewParamsMeta] for a
   * bundle's own manifest. What lands here is about the image.
   */
  @Serializable
  data class CaptureGutterPx(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
  ) {
    /** True when no edge carries a gutter — the same "equivalent to no annotation" rule. */
    fun isEmpty(): Boolean = left <= 0 && top <= 0 && right <= 0 && bottom <= 0
  }

  @Serializable
  data class VariantMeta(
    val state: String? = null,
    val theme: String? = null,
    /**
     * The owning component's authored one-line description, carried through
     * `previews/variants.json` so a browse surface can say what a component is rather than only
     * naming it. Null for a catalog that authors none.
     */
    val caption: String? = null,
    /**
     * The i18n / content / a11y variant axis this render varies (`{"locale":"ar-XB"}`,
     * `{"direction":"rtl"}`, `{"fontScale":"2.0"}`, `{"content":"icon+label"}`), or absent/empty
     * for the default render. Lets a preview host fold props variants onto the component's one card
     * (like [state]) and offer a variant switcher. Null for a catalog that varies on neither props.
     */
    val props: JsonObject? = null,
    /**
     * The declared breakpoint this render was captured at — see [Image.size]. Lets a preview host
     * fold a component's other breakpoints onto its one card and offer a size switcher. Null for a
     * catalog that declares no breakpoints.
     */
    val size: String? = null,
    /** Original catalog component id, retained for human-readable display labels. */
    val componentId: String? = null,
    /** Catalog-published controls used before a lazy per-preview daemon has been opened. */
    val overrides: List<PreviewOverrideDeclaration> = emptyList(),
    val remoteComposeKnobs: List<RemoteComposeKnobDeclaration> = emptyList(),
    val supportsFocus: Boolean = false,
    val supportsGestures: Boolean = false,
    /** Discovery-time `@FixedTheme` — see [Image.fixedTheme]. */
    val fixedTheme: Boolean = false,
    /**
     * Whether this render is a **second-tier** variant cell: listed nowhere in the component's
     * variant tree, but rendered, addressable by its own URL and paired with its design-kit node
     * like any other cell. From `@OverrideVariant(secondary = true)`, through the publisher's
     * per-preview declarations.
     *
     * It exists for the catalog that draws a kit set exhaustively: a 90-cell component is 90 real
     * comparisons and one unusable menu, and the axes that were already second-tier (theme,
     * breakpoint, font scale, locale) are second-tier for exactly this reason.
     */
    val secondary: Boolean = false,
    /**
     * Animated captures this preview can offer instead of its still. Empty for the overwhelming
     * majority of previews; a viewer shows the still exactly as before and surfaces these only as
     * an opt-in control, because motion is the answer to a question most readers aren't asking.
     */
    val motion: List<MotionMeta> = emptyList(),
    val section: String? = null,
    val group: String? = null,
    val order: Int? = null,
    /**
     * Module-relative source path of the preview's `@Preview` function (from the catalog
     * component's [Component.sourceFile]). Shared by every image of a component. Read back by
     * [ServeBundleHost] to populate `ServePreview.sourceFile` so the viewer can build a GitHub
     * source link. Null for a catalog with no recorded source (older export) or a deferred record.
     */
    val sourceFile: String? = null,
    /** Per-preview Gradle project path; overrides the catalog-wide source module when present. */
    val sourceModule: String? = null,
    /**
     * A 1-based line inside the preview function's body within [sourceFile] (from the catalog
     * component's [Component.bodyLine]). Shared by every image of a component, like [sourceFile].
     * Read back by [ServeBundleHost] to populate `ServePreview.bodyLine` so the playground handoff
     * seeds one declaration instead of the whole file. Null for a catalog that recorded none.
     */
    val bodyLine: Int? = null,
    /** Failure shown by the landing card instead of requesting a missing PNG. */
    val renderFailure: CatalogRenderFailure? = null,
    /**
     * This preview's `@Preview` ground and device frame, so the read-only catalog path can resolve
     * a per-preview backdrop and device clip without a live daemon. See [PreviewParamsMeta].
     */
    val previewParams: PreviewParamsMeta? = null,
  )

  /**
   * `catalog.json`'s `webRender`: an app under [path] (e.g. `web/wasm/`) with its [files] listed.
   */
  @Serializable
  private data class WebRender(
    val kind: String = "",
    val path: String = "",
    val files: List<String> = emptyList(),
  )

  companion object {
    const val DEFAULT_REPO = "yschimke/compose-ai-tools"
    const val DEFAULT_BRANCH_PREFIX = "design-artifacts/"
    const val CATALOG_FILE = "catalog.json"
    const val IMAGES_DIR = "images"

    /** The delivery branch's directory of published animated captures, beside `images/`. */
    const val MOTION_DIR = "motion"

    /**
     * Extensions a published capture can carry. Closed deliberately: these bytes are served to a
     * browser, and an open-ended suffix on a path from fetched JSON is how a catalog would get to
     * name the content type it is served under.
     */
    val MOTION_EXTENSIONS = listOf(".apng", ".gif")
    const val FIGMA_DIR = "figma"
    /** A preview id folds the component slug + variant as `<slug>__<variant>`. */
    const val SLUG_SEPARATOR = "__"
    const val WEB_WASM_DIR = "web/wasm"
    const val WEB_RENDER_COMPOSE_WASM = "compose-wasm"
    private const val MAX_WASM_FILES = 64
    /** Local subdir a catalog's `liveBundle` file is fetched into (`<dir>/bundle/<file>`). */
    const val LIVE_BUNDLE_DIR = "bundle"

    /**
     * Sibling of `previews/` holding the captured Remote Compose documents (`ir/<catalog-id>.rc`),
     * extracted from the live bundle's `ir/<daemon-id>.rc` entries by [extractCatalogRcDocs] and
     * read back by [ServeBundleHost.remoteComposeDoc]. Mirrors the bundle's own `ir/` prefix.
     */
    const val IR_DOC_DIR = "ir"
    const val RC_DOC_SUFFIX = ".rc"

    /**
     * Branch- and local-relative subdir (under `liveBundle.path` / [LIVE_BUNDLE_DIR]) holding the
     * per-preview FULL split bundles `design-artifacts.yml` writes (`previews/<daemon-id>.png`),
     * one re-renderable sticker per preview. Fetched by [fetchPerPreviewBundle] for the per-preview
     * live lane.
     */
    const val PER_PREVIEW_DIR = "previews"

    /**
     * Filename (under a staged/served catalog's `previews/` dir) of the per-preview state/theme
     * manifest — `{ "<preview-id>": { "state": "unchecked", "theme": "light" }, … }` (null keys
     * omitted). Written by the fetch loop from each catalog `Image`'s `state`/`theme`, read back by
     * [ServeBundleHost] to tag its previews. Absent for a plain (stateless) catalog.
     */
    const val VARIANTS_FILE = "variants.json"

    /**
     * Branch-relative subdir (under the `liveBundle.path`) holding the bundle's externalized
     * resources, content-addressed by sha256 (`<liveBundle.path>/res/<sha>`). Written by `bundle
     * externalize`'s `--res-out` publish step; fetched by [rehydrateExternalResources].
     */
    const val RES_POOL_DIR = "res"

    /**
     * Default root for the [CatalogBlobPool] — under the store root, so a store given no durable
     * pool behaves as it always did: shared across systems and reloads, discarded with the process.
     * `serve --catalog-cache-dir` roots the pool on a volume instead.
     */
    const val BLOB_CACHE_DIR = ".blobs"

    /**
     * Per-system subdir the rehydrated resources are materialized into at their classpath paths.
     */
    const val RES_MATERIALIZED_DIR = "bundle-res"

    /**
     * The single-path-segment preview id for a catalog image path. The serve routes (`/p/{name}`,
     * `/render/{name}.png`, `/ws/{name}`) capture one segment, so a catalog image's subdirectory
     * `/` (e.g. `images/button-filled/ideal__default__dark.png`) must be flattened or the preview
     * is listed but can't be opened/rendered. We drop the `images/` prefix + `.png` suffix and
     * replace `/` with `__` (the same separator the variant keys already use), giving a stable,
     * route-safe id like `button-filled__ideal__default__dark`. The design-parity catalog exporter
     * derives the `livePreview` deep link the same way so the link resolves to this id.
     */
    fun previewIdFor(imagePath: String): String =
      imagePath.removePrefix("$IMAGES_DIR/").removeSuffix(".png").replace("/", "__")

    /**
     * Maximum baked previews loaded from one published catalog unless the server overrides it.
     * Images are fetched lazily, so this bounds registered metadata/routes rather than eager
     * network or bitmap memory. Keep it above the largest first-party catalog (m3-catalog is
     * currently ~1,150 previews) so the ceiling remains a guard instead of silently truncating a
     * healthy catalog.
     */
    const val DEFAULT_MAX_IMAGES = 2000

    /**
     * How many design pages one catalog may stage. A page is a whole specimen sheet as SVG — most
     * of a megabyte for a dense one — so this is a disk and request ceiling rather than a display
     * one. A design file has tens of pages, not thousands; a branch declaring more than this is
     * malformed or hostile either way.
     */
    const val MAX_DESIGN_PAGES = 40
    private const val MAX_FETCH_BYTES = 25L * 1024 * 1024 // 25 MB per catalog asset

    /**
     * A catalog's live directories are `<root>/<system>/g<generation>`; the staging tree it is
     * assembled in is `<root>/<system>/.staging`.
     *
     * Nested under the system rather than named as siblings (`<system>.g3`) because a system id may
     * itself contain a dot — `ServeBundleStore.sanitizeName` admits one — so a sibling naming
     * scheme would let a system called `m3.g3` collide with a generation of one called `m3`. Under
     * the system's own directory the namespace is this store's alone, which also makes the sweep a
     * listing rather than a prefix match.
     */
    internal const val GENERATION_DIR_PREFIX = "g"

    internal const val STAGING_DIR = ".staging"

    /**
     * How many entries a published artifact index may name before it is ignored.
     *
     * The contract's own ceiling: at most [ServeKnownDifferences.MAX_ACCEPTANCES] records, each
     * naming a `mask` and an `acceptedCandidate`. A producer is free to publish siblings a record
     * does not name — the consumer decides which matter — so this is not a statement about what is
     * legal, only a bound on how long a *fetch plan* this host will act on before deciding the file
     * is not one.
     */
    private const val MAX_INDEXED_ARTIFACTS = ServeKnownDifferences.MAX_ACCEPTANCES * 2

    /**
     * Cap on a delivery branch's commit feed ([fetchRevisions]). Deliberately far smaller than a
     * catalog asset: the feed is ~20 commit entries and measures in tens of kilobytes. A body that
     * doesn't fit here is not one worth scanning.
     */
    private const val MAX_FEED_FETCH_BYTES = 1L * 1024 * 1024

    /**
     * How many catalog assets to fetch at once ([fetchCatalogAssets]). Twelve measured as the knee
     * against `raw.githubusercontent.com` — it takes the largest published catalog's 197 images
     * from ~92 s to ~8 s — while staying far short of anything a single origin would consider a
     * burst. Each worker holds one small response at a time, so the memory cost is bounded by this
     * count times the per-asset cap, not by the catalog's size.
     */
    private const val ASSET_FETCH_CONCURRENCY = 12

    /**
     * Images fetched before a catalog publishes: its hero (which the front door paints) plus a
     * couple of others, so "the branch can serve pixels" is proven without one missing file being
     * able to fail the catalog. Everything else arrives on first use.
     */
    /**
     * How many of a catalog's cached assets the post-publish audit re-reads from the branch.
     *
     * Small on purpose. This is checking for something the design says cannot happen, so its job is
     * to be running at all rather than to be exhaustive; a wide sample would spend real bandwidth
     * on every load to raise the odds of catching a fault that should never occur.
     */
    private const val AUDIT_SAMPLE_ASSETS = 3

    private const val PUBLISH_SAMPLE_IMAGES = 3

    /**
     * Components sampled when deciding whether a branch carries baked vectors at all. More than one
     * because any single component may legitimately publish none; small because the background fill
     * settles the question for real moments later.
     */
    private const val FIGMA_PROBE_SLUGS = 5

    /**
     * Slug normalisation shared with [ServeBundleHost]'s hero resolver — mirrors design-parity's
     * `slug()` (non-`[a-zA-Z0-9._-]` → `-`, trim, lowercase).
     */
    private fun heroSlugOf(value: String): String =
      value.replace(Regex("[^a-zA-Z0-9._-]+"), "-").trim('-').lowercase().ifBlank { "x" }

    internal const val MAX_LIVE_BUNDLE_FETCH_BYTES = 100L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: OkHttpClient by lazy {
      OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    }

    /**
     * One branch read, retried while the answer is a "right now" failure.
     *
     * The retry is the whole reason this exists: `raw.githubusercontent.com` rate-limits an
     * unauthenticated reader, and a server publishing twenty-odd catalogs meets that regularly. A
     * single 429 used to be indistinguishable from a missing file and cost the reader the asset
     * outright; two short, `Retry-After`-aware attempts turn most of them into a served response. A
     * [BranchFetch.NotFound] is never retried — the answer will not change, and asking again is how
     * a catalog of genuinely-absent assets turns into a thundering herd.
     */
    internal fun httpFetchOutcome(
      url: String,
      maxBytes: Long,
      sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): BranchFetch {
      var last: BranchFetch = httpFetchOnce(url, maxBytes)
      var attempt = 1
      while (true) {
        val delay = BranchFetch.retryDelayMillis(last, attempt) ?: return last
        try {
          sleep(delay)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          return last
        }
        last = httpFetchOnce(url, maxBytes)
        if (!last.isTransient) return last
        attempt++
      }
    }

    /**
     * A single attempt. Only [java.io.IOException] becomes [BranchFetch.Transport]; a body that
     * outgrows the envelope becomes [BranchFetch.TooLarge], which is not retried — the asset will
     * be exactly as oversized the second time.
     *
     * The size refusal is an *outcome* rather than a thrown exception because it is a fact about
     * the branch that one writer has to act on: known differences answers `too-large`/413 for an
     * asset past the contract's ceiling and `unreadable`/404 for an absent one, and a throw that
     * every call site catches into `null` erases exactly that distinction. Callers that only ever
     * wanted bytes are unaffected — [BranchFetch.bytesOrNull] is null either way.
     */
    private fun httpFetchOnce(url: String, maxBytes: Long): BranchFetch =
      try {
        httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
          if (!response.isSuccessful) {
            BranchFetch.ofStatus(
              response.code,
              BranchFetch.parseRetryAfter(response.header("Retry-After")),
            )
          } else {
            val body = response.body
            readCapped(body.byteStream(), maxBytes)?.let { BranchFetch.Ok(it) }
              ?: BranchFetch.TooLarge(maxBytes)
          }
        }
      } catch (e: java.io.IOException) {
        BranchFetch.Transport(e::class.simpleName ?: "IOException")
      }

    private fun httpFetch(url: String, maxBytes: Long): ByteArray? =
      httpFetchOutcome(url, maxBytes).bytesOrNull

    /**
     * A `HEAD` probe as an outcome. [BranchFetch.Ok] carries no bytes — nothing was asked for; it
     * means only "the branch has this". The point of the type here is the failure side: a `429` on
     * a probe is a throttle to count, not an absent file.
     */
    private fun httpProbeOutcome(url: String): BranchFetch =
      try {
        httpClient.newCall(Request.Builder().url(url).head().build()).execute().use { response ->
          if (response.isSuccessful) BranchFetch.Ok(ByteArray(0))
          else
            BranchFetch.ofStatus(
              response.code,
              BranchFetch.parseRetryAfter(response.header("Retry-After")),
            )
        }
      } catch (e: java.io.IOException) {
        BranchFetch.Transport(e::class.simpleName ?: "IOException")
      }

    /**
     * The body, or null once it has read past [max] — the caller turns that into
     * [BranchFetch.TooLarge].
     *
     * Abandons the read at the ceiling rather than counting to the end: the point of the cap is
     * that an over-sized body is never held, and knowing the exact length of one we are refusing
     * anyway would cost the whole download to learn.
     */
    private fun readCapped(input: InputStream, max: Long): ByteArray? {
      val out = ByteArrayOutputStream()
      val buffer = ByteArray(64 * 1024)
      var total = 0L
      while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        total += n
        if (total > max) return null
        out.write(buffer, 0, n)
      }
      return out.toByteArray()
    }
  }

  /** Current generation per system; see [scheduleFigmaSvgFetch]. */
  private val generations = ConcurrentHashMap<String, Int>()

  /**
   * The generation directory each system's **registered** host reads from.
   *
   * Written only where a host is published, never where one is built: a load that stages a
   * generation and then fails to stand a session up leaves the previous entry in place, because the
   * previous host is still the one serving.
   */
  private val liveDirs = ConcurrentHashMap<String, File>()

  /** Where [system]'s registered host reads its bytes from, or null if it has never published. */
  fun liveDir(system: String): File? = ServeBundleStore.sanitizeName(system)?.let { liveDirs[it] }

  /**
   * Record [dir] as [safe]'s live generation, at the moment the host reading it is registered.
   *
   * Called at every point a load publishes a session — the baked registration and each of the three
   * live-lane builders — because "which files is the current host reading?" is exactly "which host
   * did this load hand over?", and a generation marked live by a load that then returned without
   * registering would be swept while the host that owns it is still serving.
   */
  private fun publishGeneration(safe: String, dir: File, wasmDir: File?) {
    liveDirs[safe] = dir
    // Always, including with null: an in-browser app registered by an earlier generation outlives
    // its host on disk, so a publish that carries none has to withdraw it rather than simply not
    // replace it. See [registerWasm].
    registerWasm(safe, wasmDir)
  }

  /**
   * Delete every generation directory of [safe] except the live one and the names in [keep].
   *
   * Run at the **start** of a load rather than after the swap, and that is the whole of the grace
   * period: a request that began against the outgoing host has until the next refresh tick —
   * minutes — to finish reading, where retiring at the swap would pull the files out from under a
   * read already in flight. Nothing here is load-bearing for correctness; a directory this misses
   * is disk, not a wrong answer, and the next sweep takes it.
   *
   * The previous process's leftovers are swept by the same rule: on a fresh start nothing is live,
   * so every generation on disk is stale by definition.
   */
  private fun retireStaleGenerations(safe: String, systemRoot: File, keep: Set<String>) {
    val live = liveDirs[safe]?.name
    for (child in systemRoot.listFiles().orEmpty()) {
      if (child.name == live || child.name in keep) continue
      runCatching { child.deleteRecursively() }
    }
  }

  /**
   * The delivery branch's published revisions, newest first — its commit history, read from the
   * branch's own Atom feed ([ServeCatalogRevision.commitsFeedUrl]).
   *
   * This is the whole substrate the permalink feature stands on: the branch already carries one
   * commit per publish, so the versions exist and only need addressing. The head of this list is
   * the revision this load is reading (what a permalink pins to); the tail is what a visitor can go
   * back to.
   *
   * Runs through the same injected [fetch] seam as every other network call here, so a test stubs
   * it like any catalog asset. Best-effort by construction: every failure — an unreachable host, a
   * repository that publishes no feed, a branch with no history — returns an empty list, which
   * costs the catalog's pages their permalink affordance and nothing else.
   */
  private fun fetchRevisions(repo: String, branch: String): List<ServeCatalogRevision.Revision> =
    runCatching {
      val url = ServeCatalogRevision.commitsFeedUrl(repo, branch)
      val body =
        if (fetch != null) fetch.invoke(url) else branchRead(url, MAX_FEED_FETCH_BYTES).bytesOrNull
      body?.toString(Charsets.UTF_8)?.let { ServeCatalogRevision.parseCommitsFeed(it) }
    }
    .getOrNull()
    .orEmpty()

  /**
   * The publishes in which **one render's bytes changed**, as delivery-branch shas.
   *
   * Same feed, same parser, one path narrower ([ServeCatalogRevision.pathCommitsFeedUrl]) — which
   * is the entire trick behind the viewer's render-run markers. Git already collapses a branch to
   * the commits that touched a file, so the alternative (fetch every published PNG for a preview
   * and compare bytes) buys nothing and costs a dozen image reads per menu.
   *
   * Null, not empty, when the read fails — and **an empty parse counts as a failure**. The
   * distinction is load-bearing downstream: null means "the branch did not tell us" and draws no
   * markers, while an empty set would mean "no publish ever changed this render", which the runs
   * endpoint reports as every listed publish being pixel-identical. A path that a catalog publishes
   * necessarily has at least the commit that added it, so *zero* entries never describes a real
   * render: it is what a 200 carrying an HTML error page, a redirect, or a reshaped feed parses
   * down to. Treating that as "nothing changed" would state the confident wrong answer precisely
   * when the branch had told us nothing at all.
   */
  private fun fetchRenderChanges(repo: String, branch: String, path: String): Set<String>? =
    runCatching {
      val url = ServeCatalogRevision.pathCommitsFeedUrl(repo, branch, path) ?: return null
      val body =
        if (fetch != null) fetch.invoke(url) else branchRead(url, MAX_FEED_FETCH_BYTES).bytesOrNull
      body?.toString(Charsets.UTF_8)?.let { xml ->
        ServeCatalogRevision.parseCommitsFeed(xml, ServeCatalogRevision.MAX_PATH_REVISIONS)
          .map { it.commit }
          .toSet()
          .takeIf { it.isNotEmpty() }
      }
    }
    .getOrNull()

  /**
   * Delivery-branch read counters for this store (`/status.json` → `branchFetch`).
   *
   * Per-store and wrapped around [networkFetch] rather than recorded inside the default HTTP
   * transport, for two reasons that are the same reason: a counter on the companion would be
   * process-global mutable state shared by every store in a test JVM, **and** it would record
   * nothing at all for a store given an injected transport — telemetry that goes quiet exactly when
   * someone has configured something unusual is worse than none, because it reads as healthy.
   */
  val branchFetchStats = BranchFetchStats()

  /** [networkFetch] with its outcome counted. Every network read in this store goes through it. */
  private fun branchRead(url: String, maxBytes: Long): BranchFetch =
    networkFetch(url, maxBytes).also {
      branchFetchStats.record(it)
      // Attributed to whatever operation issued it; a read outside one belongs to nobody.
      if (it.isTransient) activeFetchScope.get()?.recordTransient()
    }

  /** [networkProbe] with its outcome counted; true only when the branch actually has the file. */
  private fun branchProbe(url: String): Boolean =
    networkProbe(url).also(branchFetchStats::record) is BranchFetch.Ok

  /**
   * One operation's tally of reads that ended in a **"right now"** failure — throttled, the branch
   * host unwell, or no answer at all.
   *
   * Scoped to the operation rather than counted store-wide, and the difference is not academic.
   * `fetchCatalogAsset` also serves *request-time* lazy reads — a baked PNG, a motion capture, a
   * pinned revision — which run continuously on a busy server. A shared total would let a client
   * retrying an unavailable asset mark an otherwise complete catalog revision incomplete, forcing a
   * full re-read every polling interval: more traffic at exactly the moment the branch host is
   * already unhealthy, which is a feedback loop rather than one wasted request.
   */
  private class FetchScope {
    private val transient = java.util.concurrent.atomic.AtomicLong()

    fun recordTransient() {
      transient.incrementAndGet()
    }

    val sawTransientFailure: Boolean
      get() = transient.get() > 0
  }

  /**
   * The scope reads on this thread belong to, or null for a read that belongs to no operation — a
   * request-time lazy fetch, which is nobody's load.
   */
  private val activeFetchScope = ThreadLocal<FetchScope?>()

  /** Run [body] with a fresh scope installed, restoring whatever was there before. */
  private fun <T> inFetchScope(body: (FetchScope) -> T): T {
    val scope = FetchScope()
    val previous = activeFetchScope.get()
    activeFetchScope.set(scope)
    return try {
      body(scope)
    } finally {
      activeFetchScope.set(previous)
    }
  }

  /** Fetch an ordinary catalog asset using the existing tight per-file envelope. */
  private fun fetchCatalogAsset(url: String): ByteArray? = cachedBranchRead(url).bytesOrNull

  /**
   * Every small-asset read, with the [blobs] pool in front of the transport: baked PNGs, motion
   * captures, figma vectors, design references and pages, and the `?at=<sha>` permalink reads that
   * resolve out of an older commit.
   *
   * Admission is decided by the URL, through [ServeCatalogRevision.isCommitPinned] — the one place
   * that owns what a commit is. That is the right seam here rather than a flag threaded from the
   * load, because these reads do **not** all come from the current load's `base`: a pinned request
   * addresses a commit the load never resolved, and it is exactly as immutable. What the rule
   * refuses is the un-pinned fallback, where `base` is the branch ref.
   *
   * Only [BranchFetch.Ok] is stored. A `NotFound` is a statement about one revision that a caller
   * may already cache permanently in its own terms ([ServeBundleHost.fetchPinnedAssetOutcome]), and
   * a throttle or a transport failure is a statement about *now* — caching either would turn a bad
   * minute into a permanent answer.
   *
   * The pool sits **outside** the injected [fetch] seam, exactly as the executable-bundle lane's
   * does, so a stubbed transport exercises the same caching a real one gets. Putting it on the
   * network side instead would leave the whole behaviour untested by the 48 tests that inject a
   * fetcher, which is how the two paths would drift.
   */
  private fun cachedBranchRead(url: String): BranchFetch {
    if (!ServeCatalogRevision.isCommitPinned(url)) return directBranchRead(url)
    blobs.read(url)?.let {
      branchFetchStats.recordCached()
      return BranchFetch.Ok(it)
    }
    return directBranchRead(url).also { if (it is BranchFetch.Ok) blobs.write(url, it.bytes) }
  }

  /**
   * The transport with no cache in front of it — [cachedBranchRead] minus the pool.
   *
   * Named and shared rather than inlined, because the audit ([auditCachedAssets]) needs exactly
   * this: bytes from the branch for a URL the pool almost certainly holds. Reaching for the
   * ordinary read there would compare the cache against itself and pass every time.
   */
  private fun directBranchRead(url: String): BranchFetch =
    if (fetch != null) fetch.invoke(url)?.let { BranchFetch.Ok(it) } ?: BranchFetch.NotFound
    else branchRead(url, MAX_FETCH_BYTES)

  /**
   * Re-read a small sample of this catalog's cached assets from the branch and check them against
   * what the pool would serve — see [CatalogBlobPool.audit] for the failure this exists to notice.
   *
   * Sampled and post-publish: a handful of requests per load, off the request path, and never a
   * gate. The sample is taken from the front of the catalog's own baked paths rather than at
   * random, so the same few entries are re-checked on every load of a given revision — which is
   * what makes a mis-filing show up repeatedly instead of once in a thousand loads.
   */
  private fun auditCachedAssets(urls: List<String>, system: String) {
    for (url in urls) {
      val fresh = runCatching { directBranchRead(url) }.getOrNull()?.bytesOrNull ?: continue
      if (blobs.audit(url, fresh) == CatalogBlobPool.AuditResult.MISMATCHED) {
        System.err.println(
          "serve: $system cached bytes for $url did not match the branch — entry dropped. " +
            "This should be impossible; check /status.json catalogCache.mismatched."
        )
      }
    }
  }

  /**
   * [fetchCatalogAsset], keeping the reason a failure failed.
   *
   * The injected [fetch] seam still answers `ByteArray?` — 48 tests build one, and widening it
   * would be a large diff for no test's benefit. A stub that answers null is reported as
   * [BranchFetch.NotFound], which is what those tests mean by it; only the real network path
   * distinguishes a throttle, and only the real network path can.
   */
  private fun fetchCatalogAssetOutcome(url: String): BranchFetch = cachedBranchRead(url)

  /**
   * Fetch [urls] **concurrently**, returning `url → bytes` for the ones that came back. A URL that
   * fails, throws, or 404s is simply absent from the map — exactly the `null` every call site
   * already treats as "skip this asset", so the fail-soft behaviour per asset is unchanged.
   *
   * Why this exists: a catalog's assets are individually tiny and numerous — jetsnack publishes 197
   * baked PNGs totalling 12 MB, plus a comparable number of figma vectors. Fetched one at a time
   * against `raw.githubusercontent.com` that is ~0.5 s of round-trip each and ~130 s for the
   * catalog; fetched twelve at a time it is ~8 s, because the cost was never bandwidth. Ordering is
   * preserved by the callers, which keep their original sequential loop and merely read bytes out
   * of this map instead of blocking on each request in turn — so preview ids, `count`, and the
   * authored tab/group ordering are all computed exactly as before.
   */
  /**
   * Concurrent fetch that **never accumulates**: each worker writes its bytes straight to the
   * planned destination and drops them, so peak memory is the in-flight assets (at most
   * [ASSET_FETCH_CONCURRENCY] of them) rather than the whole catalog. Returns the urls that were
   * both fetched and written.
   *
   * This is the form every bulk lane uses. Returning a `url → bytes` map instead would hold the
   * entire catalog resident — with the 1000-image ceiling and the 25 MB per-asset cap that is a
   * multi-gigabyte worst case, where the original sequential loop held exactly one asset at a time.
   * Destinations are path-contained by the caller before planning, so a worker never writes outside
   * the staged catalog.
   */
  private fun fetchCatalogAssetsToFiles(
    plan: List<Pair<String, File>>,
    stillWanted: () -> Boolean = { true },
    /**
     * When set, an asset the transport refuses by size is staged as a marker of this many bytes
     * instead of being skipped — see [stageOversizeMarker]. Only a lane whose reader distinguishes
     * "too large" from "absent" passes it; for everything else a size refusal really is nothing to
     * serve, and a marker would invent a file the branch never published.
     */
    oversizeMarkerBytes: Long? = null,
  ): Set<String> {
    if (plan.isEmpty()) return emptySet()
    val pool =
      Executors.newFixedThreadPool(minOf(ASSET_FETCH_CONCURRENCY, plan.size)) { r ->
        Thread(r, "serve-catalog-fetch").apply { isDaemon = true }
      }
    return try {
      // The scope these reads belong to is the caller's, and a `ThreadLocal` set here is invisible
      // to the workers — so it is captured at submission and re-established inside each task.
      val submitting = activeFetchScope.get()
      val inFlight = plan.map { (url, target) ->
        url to
          pool.submit<Boolean> {
            val previous = activeFetchScope.get()
            activeFetchScope.set(submitting)
            try {
              val outcome =
                runCatching { fetchCatalogAssetOutcome(url) }.getOrNull() ?: return@submit false
              // Re-checked immediately before the write, not just once per wave: a fetch started
              // for
              // one catalog generation must not land in the directory a refresh has since swapped
              // in.
              // Checking only between waves leaves every worker in the current wave free to write
              // after the swap, and nothing then removes what they wrote.
              if (outcome is BranchFetch.TooLarge && oversizeMarkerBytes != null) {
                if (!stillWanted()) return@submit false
                return@submit stageOversizeMarker(target, oversizeMarkerBytes)
              }
              val bytes = outcome.bytesOrNull ?: return@submit false
              if (!stillWanted()) return@submit false
              runCatching {
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
              }
                .isSuccess
            } finally {
              activeFetchScope.set(previous)
            }
          }
      }
      buildSet {
        for ((url, future) in inFlight) {
          if (runCatching { future.get() }.getOrNull() == true) add(url)
        }
      }
    } finally {
      pool.shutdown()
    }
  }

  private fun fetchCatalogAssets(urls: List<String>): Map<String, ByteArray> {
    val distinct = urls.distinct()
    if (distinct.size <= 1) {
      val only = distinct.firstOrNull() ?: return emptyMap()
      return runCatching { fetchCatalogAsset(only) }.getOrNull()?.let { mapOf(only to it) }
        ?: emptyMap()
    }
    val pool =
      Executors.newFixedThreadPool(minOf(ASSET_FETCH_CONCURRENCY, distinct.size)) { r ->
        Thread(r, "serve-catalog-fetch").apply { isDaemon = true }
      }
    val submitting = activeFetchScope.get()
    return try {
      val inFlight = distinct.map { url ->
        url to
          pool.submit<ByteArray?> {
            val previous = activeFetchScope.get()
            activeFetchScope.set(submitting)
            try {
              runCatching { fetchCatalogAsset(url) }.getOrNull()
            } finally {
              activeFetchScope.set(previous)
            }
          }
      }
      buildMap {
        for ((url, future) in inFlight) {
          runCatching { future.get() }.getOrNull()?.let { put(url, it) }
        }
      }
    } finally {
      pool.shutdown()
    }
  }

  /**
   * Fetch an executable bundle using the 100 MB envelope shared by uploaded and startup bundles. A
   * one-argument [fetch] override still intercepts every request, preserving the compact test seam
   * and callers that provide their own transport.
   */
  private fun fetchExecutableBundle(url: String): ByteArray? =
    if (fetch != null) fetch.invoke(url)
    else branchRead(url, MAX_LIVE_BUNDLE_FETCH_BYTES).bytesOrNull
}
