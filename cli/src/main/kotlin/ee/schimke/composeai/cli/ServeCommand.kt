package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.serve.BundleVerifier
import ee.schimke.composeai.cli.serve.DaemonStartupLog
import ee.schimke.composeai.cli.serve.GitWorktrees
import ee.schimke.composeai.cli.serve.GradleRevisionBuilder
import ee.schimke.composeai.cli.serve.RenderOutcome
import ee.schimke.composeai.cli.serve.ServeBundle
import ee.schimke.composeai.cli.serve.ServeBundleDaemon
import ee.schimke.composeai.cli.serve.ServeBundleHost
import ee.schimke.composeai.cli.serve.ServeBundleStore
import ee.schimke.composeai.cli.serve.ServeCatalogLiveHost
import ee.schimke.composeai.cli.serve.ServeCatalogRefresher
import ee.schimke.composeai.cli.serve.ServeCatalogStore
import ee.schimke.composeai.cli.serve.ServeHost
import ee.schimke.composeai.cli.serve.ServeHttpServer
import ee.schimke.composeai.cli.serve.ServeMdnsAdvertiser
import ee.schimke.composeai.cli.serve.ServeModuleRef
import ee.schimke.composeai.cli.serve.ServePerPreviewDaemonPool
import ee.schimke.composeai.cli.serve.ServePreview
import ee.schimke.composeai.cli.serve.ServeRenderHost
import ee.schimke.composeai.cli.serve.ServeRevisionFactory
import ee.schimke.composeai.cli.serve.ServeSessionFactory
import ee.schimke.composeai.cli.serve.ServeSessionRegistry
import ee.schimke.composeai.cli.serve.ServeSessionState
import ee.schimke.composeai.cli.serve.ServeStartupBundles
import ee.schimke.composeai.cli.serve.ServeUrls
import ee.schimke.composeai.cli.serve.TrustStore
import ee.schimke.composeai.cli.serve.declaredThemesFromPreviews
import ee.schimke.composeai.cli.serve.detectedFeaturesOf
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.render.session.RenderSessionException
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * `compose-preview serve [--module M] [--id|--filter] [--host H] [--lan] [--port N] [--token T]`
 *
 * Starts a long-lived local HTTP server that renders one module's `@Preview`s on demand and serves
 * them as PNGs with display overrides, so a teammate on the network can open a link and look at a
 * specific preview. Tier 1 (read-only snapshot) of the Remote Session feature — works for both
 * Android/Robolectric and Desktop because it rides the same daemon `RenderSession` path the
 * `render-matrix` / `a11y` commands use.
 *
 * The server is multi-client (stateless HTTP fronting one shared, serialised render session) and
 * serves the module's whole preview set, so switching previews is just navigation.
 */
class ServeCommand(args: List<String>) : Command(args) {

  private val lan: Boolean = "--lan" in args
  private val host: String =
    when {
      lan -> ServeUrls.ALL_INTERFACES
      else -> args.flagValue("--host")?.takeIf { it.isNotBlank() } ?: ServeUrls.LOOPBACK
    }
  private val requestedPort: Int = args.flagValue("--port")?.toIntOrNull() ?: DEFAULT_PORT
  private val tokenOverride: String? = args.flagValue("--token")?.takeIf { it.isNotBlank() }

  /**
   * Cap on concurrent **live** (daemon-backed) stream sessions — the "live seats". `0` (default) is
   * unbounded (a local dev box); a small positive value bounds the JVM render daemons a constrained
   * public box (e.g. `--allow-render-trusted` on a 4 GB VM) will spawn, so an over-cap stream is
   * refused rather than risking the OOM killer. Only bites when a live daemon actually backs a
   * session; the snapshot + Wasm tiers never take a seat.
   */
  private val liveSeats: Int = args.flagValue("--live-seats")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
  private val exportPath: String? = args.flagValue("--export")?.takeIf { it.isNotBlank() }
  private val inlineBundle: Boolean = "--inline" in args

  /**
   * Project mode: besides the current checkout (the default session), fork a daemon-backed session
   * per git revision requested via `?session=<rev>`, each built in its own worktree and suspended /
   * resumed by the registry. Off by default (just the current module).
   */
  private val revisions: Boolean = "--revisions" in args

  /**
   * Opt in to local Gradle discovery + build. By default `serve` never runs Gradle: it hosts only
   * the fetched sources (`--bundle` / `--bundles` / `--catalogs` / uploaded bundles) as a pure
   * preview server, even when launched from inside a Gradle checkout. Passing `--discover` (or
   * scoping with `--module <path>`) opts into the old behaviour — discover the project's modules,
   * build their previews, and host one. Kept off by default because a stray `serve` at a repo root
   * would otherwise trigger a full module build (and, on a large multi-module tree, hang).
   */
  private val discover: Boolean = "--discover" in args

  /**
   * Trusted server-side re-render (SECURITY/RCE, opt-in, default off). When set, a `--catalogs`
   * catalog that verifies as `Trusted` AND declares a `source` is served by a **daemon-backed,
   * re-renderable** session built from that source (full-fidelity overrides) instead of static
   * baked PNGs. Building runs the source's Gradle = code execution, so it's gated three ways: the
   * catalog must be Trusted, its `source.ref` must clear the [revisionAllowRefs] allowlist
   * (fail-closed), and its `source.repo` must be the server's own [catalogRepo]. NEVER enable on a
   * box that can't build the catalog source (e.g. the desktop-only public image can't build the
   * Android catalogs) — leave it off there and let the in-browser Wasm tier carry CMP. Reuses
   * `--revisions-allow` as the ref allowlist.
   */
  private val allowRenderTrusted: Boolean = "--allow-render-trusted" in args

  /**
   * Optional git repo root the trusted-catalog builder ([buildTrustedCatalogSource]) and its
   * [GitWorktrees] use, instead of the served module's own project root ([findProjectRoot]). Lets a
   * box whose primary `--module` is a standalone project (e.g. the prebuilt `deploy/image`, which
   * serves a self-contained `sample-project`) still live-render a fetched catalog by pointing this
   * at a separate checkout of the catalog's `source.repo` (which the entrypoint clones). The
   * `source.repo == `[catalogRepo] and `--revisions-allow` gates are unchanged — this only moves
   * the worktree root. Off ⇒ the served module's project root, as before.
   */
  private val catalogSourceRoot: File? =
    args.flagValue("--catalog-source-root")?.takeIf { it.isNotBlank() }?.let { File(it) }

  /**
   * Project mode revision policy (SECURITY/RCE): comma-separated refs whose history a requested
   * `?session=<rev>` must be reachable from to be checked out and built. Empty = nothing builds
   * (fail closed), since building runs that revision's Gradle. e.g. `--revisions-allow
   * main,release`. Also gates the trusted-catalog source build ([allowRenderTrusted]).
   */
  private val revisionAllowRefs: List<String> =
    args.flagValue("--revisions-allow")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
      ?: emptyList()

  /**
   * Ephemeral mode: shut the whole server down once it's been idle — no open connections and no
   * requests — for [idleExitSeconds]. `--exit-when-idle` uses the default window;
   * `--exit-when-idle=<seconds>` sets it (a short value ≈ "exit shortly after the last client
   * disconnects"). Off by default (runs until Ctrl-C).
   */
  private val exitWhenIdle: Boolean = args.any {
    it == "--exit-when-idle" || it.startsWith("--exit-when-idle=")
  }
  private val idleExitSeconds: Long =
    args.flagValue("--exit-when-idle")?.toLongOrNull()?.takeIf { it > 0 }
      ?: DEFAULT_IDLE_EXIT_SECONDS

  /**
   * Seconds between re-checks of each `--catalogs` branch's head commit; when it has moved, the
   * catalog is re-fetched in place (no restart) — see [ServeCatalogRefresher]. Default
   * [DEFAULT_CATALOG_REFRESH_SECONDS]; `0` (or negative) disables polling (boot-snapshot only, the
   * pre-refresh behaviour). Wired from `SERVE_CATALOG_REFRESH` by the image entrypoint.
   */
  private val catalogRefreshSeconds: Long =
    args.flagValue("--catalog-refresh-interval")?.toLongOrNull() ?: DEFAULT_CATALOG_REFRESH_SECONDS

  /**
   * Shared mode: a directory of pre-rendered portable bundles (or a single bundle) to host
   * read-only alongside the live session, each reachable at `?session=<bundle-name>`. No checkout
   * or build — the bundle's `previews/<id>.png` files are served directly.
   */
  private val bundlesDir: String? = args.flagValue("--bundles")?.takeIf { it.isNotBlank() }

  /**
   * Serve one or more **fetched** preview bundles directly (`--bundle <url|path>` / `--bundle
   * <name>=<url|path>`, repeatable) — no `--module`, no local project, no Gradle build. A URL is
   * fetched at startup (operator-supplied, so no SSRF gate — same trust level as `--catalogs`); a
   * local path is read as-is. Each becomes a `/<name>/` session. A bundle that verifies `Trusted`
   * (Ed25519 signature, or fetched from a trusted branch origin) is served **live** from a render
   * daemon when `--allow-render-trusted` is set (desktop bundles); otherwise it's served read-only
   * as its baked PNGs. This is what lets a public server live-render any trusted bundle pulled from
   * a GitHub branch without knowing the module upfront.
   */
  private val bundleSpecs: List<ServeStartupBundles.Spec> by lazy {
    ServeStartupBundles.parse(args.flagValuesAll("--bundle"))
  }

  /**
   * Shared/public mode ingestion: enable `POST /bundles/{name}` so clients can contribute bundles
   * at runtime — upload a zip, or pass `?url=` to a build-results artifact. Off by default;
   * intended for a deployed shared instance (combine with `--lan` + a strong `--token`).
   */
  private val acceptBundles: Boolean = "--accept-bundles" in args

  /**
   * Public mode: serve every route **without** requiring the token (the deployed public preview
   * server, where browsing the published catalogs + uploaded bundles is the point). Safe by
   * construction — no server-side code execution, re-render of untrusted Compose refused, uploads
   * capped + SSRF-gated. Off by default so a normal `serve` stays token-gated.
   */
  private val public: Boolean = "--public" in args

  /**
   * SSRF allowlist for `POST /bundles/{name}?url=` fetches: comma-separated hostnames the server
   * may fetch a bundle from. Empty = no URL fetch is allowed (fail closed), so `--accept-bundles`
   * alone only accepts uploads; a host must be explicitly trusted before the server will reach out.
   */
  private val acceptBundlesFrom: List<String> =
    args
      .flagValue("--accept-bundles-from")
      ?.split(",")
      ?.map { it.trim() }
      ?.filter { it.isNotEmpty() } ?: emptyList()

  /**
   * Extra remote Maven repository base URLs the live-daemon classpath resolver may fetch from, on
   * top of Maven Central + Google Maven (`--extra-maven-repos <url>[,<url>…]`; env
   * `SERVE_EXTRA_MAVEN_REPOS`). A served catalog whose module pulls deps from a non-default repo —
   * e.g. `https://jitpack.io`, an Apollo/JetBrains snapshot repo — otherwise has those coordinates
   * skipped by the resolver, leaving the daemon's classpath incomplete so a class that references
   * them fails at bootstrap and the catalog falls back to baked PNGs (`livebundle-unavailable`).
   * Empty by default. Operator-curated: only repos the deployer trusts should be listed, since the
   * server will fetch artifacts from them when resolving a trusted catalog's live bundle.
   */
  private val extraMavenRepos: List<String> =
    args.flagValue("--extra-maven-repos")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
      ?: emptyList()

  /**
   * Path to the producer-trust store (`--trust-store <file>`): the JSON allowlist of trusted
   * signing keys / branches / CI identities ([TrustStore]). Uploaded bundles are verified against
   * it and the verdict is surfaced in the API + viewer. Absent ⇒ the empty, fail-closed store
   * (every upload `unverified`), which is correct for a private box; a public server points it at
   * `trust/producers.json`.
   */
  private val trustStorePath: String? = args.flagValue("--trust-store")

  /** Resolved once, reused by the upload store and the catalog store. */
  private val resolvedTrust: TrustStore by lazy { loadTrustStore() }

  /**
   * Design systems to serve from their published `design-artifacts/<system>` branches (`--catalogs
   * compose-m3,wear-m3`): each is fetched (catalog.json + images) and registered as a read-only
   * session reachable at `/<system>/` (and, for back-compat, `?session=<system>`),
   * trusted-by-origin when the branch is in the trust store.
   *
   * An entry may carry a **per-system source repo** as `<system>@<owner>/<repo>` so one server can
   * mix catalogs published to different repos (e.g. `meshcore-mobile@yschimke/meshcore-mobile`
   * alongside the default-repo `compose-m3`). Without `@…` the shared `--catalog-repo` is used.
   */
  private val catalogsRaw: String? = args.flagValue("--catalogs")
  /**
   * Like [catalogsRaw], but these systems are served **without** a front-page nav link — reachable
   * by path (`/<system>/`) / `?session=<system>` but hidden from the landing "Design systems" row
   * (`--catalogs-unlisted meshcore-mobile@yschimke/meshcore-mobile,…`). For app design systems we
   * publish but don't want on the public front door.
   */
  private val catalogsUnlistedRaw: String? = args.flagValue("--catalogs-unlisted")
  /**
   * The **listed** catalog systems that actually registered (one can fail to fetch). Filled by
   * [registerCatalogs]; surfaced on the landing page as nav links so the public front door lists
   * the served design systems instead of hiding them behind the query. Unlisted catalogs register
   * as sessions but never land here, so they stay off the nav.
   */
  private val registeredCatalogs = mutableListOf<String>()

  /**
   * The unlisted app catalogs (`--catalogs-unlisted`) that registered successfully. Served at
   * `/<system>/` like [registeredCatalogs] but surfaced under the front page's separate "Apps"
   * section instead of the "Design systems" nav.
   */
  private val registeredUnlistedCatalogs = mutableListOf<String>()

  /**
   * Recent daemon **startup failures** — the render/live daemon a session tried to (re)open but
   * couldn't. [openHost] (the single choke point every registry-driven relaunch funnels through)
   * records into this instead of silently dropping the exception, so `/status` + `/status.json` can
   * surface what has been going wrong without scraping stderr.
   */
  private val daemonLog = DaemonStartupLog()

  /**
   * Per-catalog per-preview daemon pools built by [buildTrustedCatalogBundle], keyed by system.
   * Each backs a live catalog's default (per-preview) render lane and outlives suspend/resume, so
   * it's owned here — torn down at server shutdown ([catalogPerPreviewPoolsCloseable] in the
   * [bringUpServer] closeables) rather than by the session host's [close][ServeHost.close] (the
   * pool is referenced by the state's closure, not the host). Keyed so a [ServeCatalogRefresher]
   * re-load closes the **previous** pool for that system instead of leaking its per-preview
   * daemons.
   */
  private val catalogPerPreviewPools =
    java.util.concurrent.ConcurrentHashMap<String, AutoCloseable>()

  /** Closes every live per-preview pool at shutdown; a live view of [catalogPerPreviewPools]. */
  private val catalogPerPreviewPoolsCloseable = AutoCloseable {
    catalogPerPreviewPools.values.forEach { runCatching { it.close() } }
  }
  /**
   * In-browser CMP tier (`--wasm-dir <system>=<dir>[,<system>=<dir>…]`): map a design system to the
   * assembled Wasm catalog app (`./gradlew :samples:cmp-wasm-catalog:wasmCatalogDist` →
   * `build/wasmDist`). Its viewer then offers a "Run in browser (Wasm)" toggle that mounts the app
   * client-side. Missing dirs are dropped with a warning. Empty ⇒ no Wasm tier.
   */
  private val wasmDirs: Map<String, File> =
    args
      .flagValue("--wasm-dir")
      ?.split(",")
      ?.mapNotNull { entry ->
        val eq = entry.indexOf('=')
        if (eq <= 0) null else entry.substring(0, eq).trim() to File(entry.substring(eq + 1).trim())
      }
      ?.toMap() ?: emptyMap()

  private val catalogRepo: String =
    args.flagValue("--catalog-repo")?.takeIf { it.isNotBlank() } ?: ServeCatalogStore.DEFAULT_REPO
  private val catalogBranchPrefix: String =
    args.flagValue("--catalog-branch-prefix")?.takeIf { it.isNotBlank() }
      ?: ServeCatalogStore.DEFAULT_BRANCH_PREFIX

  /**
   * A parsed `--catalogs` / `--catalogs-unlisted` entry: the [system] id, the [repo] its
   * `design-artifacts/<system>` branch lives in (the shared [catalogRepo] unless the entry gave an
   * `@<owner>/<repo>` override), and whether it's [listed] on the front-page nav.
   */
  private data class CatalogRef(val system: String, val repo: String, val listed: Boolean)

  /**
   * Parse one comma-separated flag value into [CatalogRef]s; `<system>@<owner>/<repo>` per entry.
   */
  private fun parseCatalogRefs(raw: String?, listed: Boolean): List<CatalogRef> =
    raw
      ?.split(",")
      ?.map { it.trim() }
      ?.filter { it.isNotEmpty() }
      ?.map { entry ->
        val at = entry.indexOf('@')
        if (at < 0) {
          CatalogRef(entry, catalogRepo, listed)
        } else {
          val system = entry.substring(0, at).trim()
          val repo = entry.substring(at + 1).trim().ifEmpty { catalogRepo }
          CatalogRef(system, repo, listed)
        }
      } ?: emptyList()

  /**
   * All catalog refs to serve — listed first, then unlisted; de-duplicated by system (first wins).
   */
  private val catalogRefs: List<CatalogRef> by lazy {
    (parseCatalogRefs(catalogsRaw, listed = true) +
        parseCatalogRefs(catalogsUnlistedRaw, listed = false))
      .distinctBy { it.system }
  }

  override fun run() {
    if ("--help" in args || "-h" in args) {
      printUsage()
      return
    }

    // Default (opt-in Gradle): unless something explicitly asks for local Gradle work, run as
    // a pure preview server — no discover/build, ever — hosting only the fetched sources
    // (`--bundle(s)` / `--catalogs` / uploaded bundles). This holds even inside a Gradle
    // checkout, so a stray `serve` at a repo root no longer kicks off a full multi-module
    // build (which could hang). `runBundleServer` prints a clear "nothing to serve / pass
    // --discover" error when there are no hosted sources.
    //
    // The opt-in signals are `--module` / `--discover` plus the modes that STRUCTURALLY need
    // the Gradle path (runBundleServer can't do any of them): `--export` (build + write a
    // bundle, consumed below after the build), `--catalog-source-root` (worktree-based trusted
    // catalog source-build), and `--revisions` (per-revision worktree forking). Each is an
    // explicit build request on its own, so it implies discovery — keeping existing callers
    // (e.g. the deploy image's `serve --export …` and `--catalog-source-root …`) working
    // without also having to pass `--discover`.
    val needsGradle =
      explicitModule != null ||
        discover ||
        exportPath != null ||
        catalogSourceRoot != null ||
        revisions
    if (!needsGradle) {
      runBundleServer()
      return
    }

    // Discover + build the module(s) so manifests exist and previews resolve. `--module` scopes it.
    val outcome = renderAllModules(silenceStdout = false)
    if (!outcome.buildOk) {
      System.err.println("serve: render build failed.")
      exitProcess(2)
    }
    if (outcome.manifests.isEmpty()) {
      System.err.println("serve: no previews discovered.")
      exitProcess(3)
    }
    if (outcome.manifests.size > 1) {
      System.err.println(
        "serve: ${outcome.manifests.size} modules discovered; a server hosts one module. " +
          "Narrow with --module <path>:"
      )
      outcome.manifests.forEach { (m, _) -> System.err.println("  ${m.gradlePath}") }
      exitProcess(1)
    }

    val (module, manifest) = outcome.manifests.single()
    val previews =
      manifest.previews
        .filter { matches(it.id) }
        .map {
          val (focus, gestures) = detectedFeaturesOf(it)
          ServePreview(
            id = it.id,
            label = it.functionName.ifBlank { it.id },
            supportsFocus = focus,
            supportsGestures = gestures,
          )
        }
    // The module's declared @ThemeCatalog themes — the Theme selector renders them so a preview can
    // be re-rendered under Brand Dark etc. Module-global, so unaffected by the --id/--filter above.
    val declaredThemes = declaredThemesFromPreviews(manifest.previews)
    if (previews.isEmpty()) {
      System.err.println("serve: no previews matched (--id/--filter excluded them all).")
      exitProcess(3)
    }

    if (!runDaemonStart(module)) {
      System.err.println("serve: composePreviewDaemonStart failed for ${module.gradlePath}.")
      exitProcess(2)
    }

    val descriptor = File(module.projectDir, "build/compose-previews/daemon-launch.json")
    if (!descriptor.isFile) {
      System.err.println("serve: missing daemon-launch.json at ${descriptor.path}")
      exitProcess(2)
    }

    val renderHost =
      try {
        ServeRenderHost.open(
          descriptorPath = descriptor,
          workspaceRoot = module.projectDir,
          workspaceName = module.projectDir.name,
          previews = previews,
          label = module.gradlePath,
          declaredThemes = declaredThemes,
          onLog = { System.err.println("[daemon serve] $it") },
        )
      } catch (e: RenderSessionException) {
        System.err.println("serve: failed to open render session (${e.message})")
        exitProcess(2)
      }

    // `--export` reuses the same render session to write a portable bundle (a WebEmbed gallery +
    // the rendered PNGs) and exits — no server. The live link and the offline bundle are then the
    // same render output.
    if (exportPath != null) {
      exportBundle(renderHost, module.gradlePath, exportPath)
      renderHost.close()
      return
    }

    val token = tokenOverride ?: ServeUrls.generateToken()
    // One shared server fronts a session registry rather than a single host. The current checkout
    // is
    // the default session; the registry suspends idle daemons and resumes them on demand from their
    // saved state, so a long-lived server doesn't keep daemons running forever.
    val openHost: (ServeSessionState) -> ServeHost? = ::openHost
    // Project mode forks a session per git revision behind the registry's factory; off by default
    // the factory yields nothing, so only the pinned current checkout is served. These worktrees
    // are
    // rooted at the served module's own project (`?session=<rev>` builds that module).
    val worktrees: GitWorktrees? = if (revisions) openWorktrees(module) else null
    // The trusted-catalog builder's worktrees. Rooted at --catalog-source-root when set (a separate
    // checkout of the catalog's source repo — e.g. a prebuilt image serving a standalone module),
    // else the served-project root (reusing [worktrees] when --revisions already opened one). Kept
    // SEPARATE from [worktrees] so combining --revisions with --catalog-source-root still roots
    // `?session=<rev>` at the served project rather than the catalog checkout. Both are gated by
    // the
    // same --revisions-allow ref allowlist.
    val catalogWorktrees: GitWorktrees? =
      when {
        !allowRenderTrusted -> null
        catalogSourceRoot != null -> openWorktrees(module, rootOverride = catalogSourceRoot)
        else -> worktrees ?: openWorktrees(module)
      }
    // The `?session=<rev>` factory (project mode) is gated on --revisions ONLY — NOT merely on
    // worktrees existing. Otherwise `--allow-render-trusted` (which also opens worktrees, but just
    // to
    // build a fixed catalog source) would silently let clients trigger Gradle builds for arbitrary
    // revisions reachable from the allowlist. The catalog builder uses `worktrees` directly, so it
    // doesn't need the factory.
    val factory =
      if (revisions && worktrees != null) revisionFactory(module, worktrees)
      else ServeSessionFactory { null }
    val registry = ServeSessionRegistry(open = openHost, factory = factory)
    val defaultState =
      ServeSessionState(
        descriptor = descriptor,
        workspaceRoot = module.projectDir,
        workspaceName = module.projectDir.name,
        previews = previews,
        label = module.gradlePath,
        // Carry the declared themes on the session state too — the registry suspends idle daemons
        // and reopens from this state, so without it the App theme selector would vanish after the
        // first idle suspend/resume.
        declaredThemes = declaredThemes,
      )
    registry.register(module.gradlePath, defaultState, host = renderHost)
    // Shared mode: register any pre-rendered portable bundles under `--bundles <dir>` as read-only
    // sessions (no daemon), reachable at ?session=<bundle-name>. Pinned — a bundle host is cheap
    // and
    // has nothing to reclaim, so it's never suspended.
    registerBundles().forEach { (id, bundleHost) ->
      registry.register(id, host = bundleHost, pinned = true)
    }
    // Serve any operator-supplied `--bundle <url|path>` fetched bundles alongside the module — live
    // from a daemon when Trusted + --allow-render-trusted, else read-only baked PNGs.
    registerStartupBundles(registry)
    // Serve our published design systems from their trusted `design-artifacts/<system>` branches.
    // A catalog that carries a `web/wasm/` app yields a system→dir entry so the in-browser tier
    // rides the same trusted branch (no local --wasm-dir build needed).
    val catalogReg =
      if (catalogRefs.isNotEmpty()) registerCatalogs(registry, catalogWorktrees, openHost) else null
    val catalogWasm = catalogReg?.wasm ?: emptyMap()
    // Keep the catalogs fresh against their (routinely-changing) branches without a restart.
    val catalogRefresher =
      catalogReg
        ?.let { buildCatalogRefresher(it.store) }
        ?.also {
          it.seedInitialHeads()
          it.start()
        }
    // Runtime ingestion (--accept-bundles): clients POST a bundle (or a ?url= to one) and it's
    // registered as a pinned session. Unpacked under a temp dir for this server's lifetime.
    val bundleStore = if (acceptBundles) openUploadStore(registry) else null
    // Merge the apps fetched from the catalog branches with the explicit `--wasm-dir` paths; a
    // local `--wasm-dir` wins for a system so an operator can override the published app.
    val wasmCatalogs = catalogWasm + filterLocalWasm()
    if (wasmCatalogs.isNotEmpty()) {
      System.err.println("serve: in-browser Wasm tier for: ${wasmCatalogs.keys.joinToString(", ")}")
    }
    bringUpServer(
      registry = registry,
      token = token,
      defaultSessionId = module.gradlePath,
      bundleStore = bundleStore,
      wasmCatalogs = wasmCatalogs,
      bannerLabel = module.gradlePath,
      bannerPreviewCount = previews.size,
      mdnsModuleLabel = module.gradlePath,
      mdnsPreviewIds = previews.map { it.id },
      closeables =
        listOf(
          worktrees,
          catalogWorktrees.takeIf { it !== worktrees },
          catalogRefresher,
          catalogPerPreviewPoolsCloseable,
        ),
    )
  }

  /**
   * Module-less mode: run a **pure preview server** — no `--module`, no local project, no Gradle
   * build. Reached from [run] when there's nothing to build locally but there are hosted sources
   * (`--bundle` / `--bundles` / `--catalogs` / `--accept-bundles`). This is the "render any fetched
   * bundle live from a trusted server" path: `serve --bundle <github-branch-url> --public
   * --allow-render-trusted` stands up a server that fetches the bundle and, if it verifies Trusted,
   * live-renders it from a daemon — without ever knowing the module upfront.
   *
   * Trusted-catalog *source* builds (the Gradle fallback) are unavailable here (no repo to worktree
   * from), so a `--catalogs` system that can't be served from its carried `liveBundle` falls back
   * to baked PNGs — fail-closed, exactly like the desktop-only public image.
   */
  private fun runBundleServer() {
    val token = tokenOverride ?: ServeUrls.generateToken()
    val registry = ServeSessionRegistry(open = ::openHost)

    registerBundles().forEach { (id, bundleHost) ->
      registry.register(id, host = bundleHost, pinned = true)
    }
    val registeredStartup = registerStartupBundles(registry)
    // No worktrees in module-less mode — catalogs live-render only from their carried `liveBundle`.
    val catalogReg =
      if (catalogRefs.isNotEmpty()) registerCatalogs(registry, worktrees = null, ::openHost)
      else null
    val catalogWasm = catalogReg?.wasm ?: emptyMap()
    // Keep the catalogs fresh against their (routinely-changing) branches without a restart — the
    // public preview server (preview.coo.ee) runs this module-less path.
    val catalogRefresher =
      catalogReg
        ?.let { buildCatalogRefresher(it.store) }
        ?.also {
          it.seedInitialHeads()
          it.start()
        }
    val bundleStore = if (acceptBundles) openUploadStore(registry) else null

    val localWasm = filterLocalWasm()
    val wasmCatalogs = catalogWasm + localWasm
    if (wasmCatalogs.isNotEmpty()) {
      System.err.println("serve: in-browser Wasm tier for: ${wasmCatalogs.keys.joinToString(", ")}")
    }

    // Pick a landing session so `/` resolves: the first registered catalog, else the first bundle.
    val defaultSessionId =
      registeredCatalogs.firstOrNull() ?: registeredStartup.firstOrNull() ?: registry.anySessionId()
    // An `--accept-bundles` server legitimately starts with no sessions — they arrive at runtime
    // via
    // POST /bundles — so only bail when there's genuinely nothing to serve and no way to add any.
    if (defaultSessionId == null && !acceptBundles) {
      System.err.println(
        "serve: nothing to serve — no --bundle / --bundles / --catalogs registered a session, and " +
          "--accept-bundles is off."
      )
      // Guide the common "ran serve in my project expecting a build" case: Gradle discovery is now
      // opt-in, so point at --discover / --module rather than leaving them staring at a bare error.
      if (findProjectRoot() != null) {
        System.err.println(
          "  This looks like a Gradle project. Local preview discovery/build is opt-in: pass " +
            "--discover to build all modules, or --module <path> to scope to one."
        )
      }
      exitProcess(3)
    }

    bringUpServer(
      registry = registry,
      token = token,
      // Upload-only server: no landing session yet (routes 404 until the first upload lands).
      defaultSessionId = defaultSessionId ?: "",
      bundleStore = bundleStore,
      wasmCatalogs = wasmCatalogs,
      bannerLabel = "(no module — hosting fetched bundles/catalogs)",
      bannerPreviewCount = registry.activeCount(),
      // No module previews to advertise; discovery is a module-session nicety, so skip it here.
      mdnsModuleLabel = null,
      mdnsPreviewIds = null,
      closeables = listOfNotNull(catalogPerPreviewPoolsCloseable, catalogRefresher),
    )
  }

  /**
   * Reopen a session's daemon-backed host from its [ServeSessionState] — the registry's `open`
   * callback, used by every serve mode. A trusted-catalog / live-bundle session carries a baked-PNG
   * fallback + a catalog-id→daemon-id alias, so the daemon is fronted by [ServeCatalogLiveHost]
   * (published deep links + thumbnails keep resolving, Android-only variants fall back to baked,
   * mapped ids gain a live lane). Rebuilt on every resume, so suspend/resume works unchanged. Plain
   * project / revision / plain-bundle sessions carry no fallback → the bare daemon.
   */
  private fun openHost(state: ServeSessionState): ServeHost? =
    runCatching {
        val daemon =
          ServeRenderHost.open(
            descriptorPath = state.descriptor,
            workspaceRoot = state.workspaceRoot,
            workspaceName = state.workspaceName,
            previews = state.previews,
            label = state.label,
            declaredThemes = state.declaredThemes,
            onLog = { System.err.println("[daemon serve] $it") },
          )
        val fallback = state.bakedFallback
        if (fallback != null)
          ServeCatalogLiveHost(
              alias = state.previewAliases,
              live = daemon,
              baked = fallback(),
              perPreviewResolve = state.perPreviewResolve,
              perPreviewStreamCount = state.perPreviewStreamCount,
              perPreviewRenderStats = state.perPreviewRenderStats,
            )
            // Warm the daemon off the request path so the first browse already gets the per-variant
            // SVG lane instead of the baked fallback — critical for a slow-cold-starting Android
            // daemon, where a lazy first render would otherwise take minutes.
            .also { it.prewarm() }
        else daemon
      }
      // Previously the exception was swallowed to a silent null; record it so the reason survives
      // on
      // the /status page instead of only reaching stderr. The host still degrades to null as
      // before.
      .onFailure { daemonLog.record(state.label, it.message ?: it.toString()) }
      .getOrNull()

  /** Build the `--accept-bundles` upload store (temp-dir backed), wired to [registry]. */
  private fun openUploadStore(registry: ServeSessionRegistry): ServeBundleStore {
    val uploads =
      java.nio.file.Files.createTempDirectory("serve-uploads").toFile().also { it.deleteOnExit() }
    if (acceptBundlesFrom.isEmpty()) {
      System.err.println(
        "serve: --accept-bundles accepts uploads only; no ?url= host is allowed (SSRF fail " +
          "closed). Pass --accept-bundles-from <host>[,<host>…] to permit URL fetches."
      )
    }
    return ServeBundleStore(
      root = uploads,
      register = { id, bundleHost -> registry.register(id, host = bundleHost, pinned = true) },
      allowedHosts = acceptBundlesFrom,
      trust = resolvedTrust,
    )
  }

  /**
   * Keep only `--wasm-dir` entries whose directory actually holds the assembled app (index.html).
   */
  private fun filterLocalWasm(): Map<String, File> = wasmDirs.filter { (system, dir) ->
    val ok = File(dir, "index.html").isFile
    if (!ok) {
      System.err.println(
        "serve: --wasm-dir $system=${dir.path} has no index.html — skipping (build it with " +
          ":samples:cmp-wasm-catalog:wasmCatalogDist)."
      )
    }
    ok
  }

  /**
   * Construct the [ServeHttpServer], start it, advertise over mDNS (when [mdnsPreviewIds] is
   * non-null and the bind is exposed), print the banner, and block until shutdown. Shared by the
   * module-backed [run] and the module-less [runBundleServer]. [closeables] are extra resources
   * (worktrees) closed on shutdown; nulls are ignored.
   */
  private fun bringUpServer(
    registry: ServeSessionRegistry,
    token: String,
    defaultSessionId: String,
    bundleStore: ServeBundleStore?,
    wasmCatalogs: Map<String, File>,
    bannerLabel: String,
    bannerPreviewCount: Int,
    mdnsModuleLabel: String?,
    mdnsPreviewIds: List<String>?,
    closeables: List<AutoCloseable?>,
  ) {
    val server =
      ServeHttpServer(
        host = host,
        requestedPort = requestedPort,
        token = token,
        sessions = registry,
        defaultSessionId = defaultSessionId,
        bundleStore = bundleStore,
        isPublic = public,
        wasmCatalogs = wasmCatalogs,
        catalogSessions = registeredCatalogs.toList(),
        appCatalogSessions = registeredUnlistedCatalogs.toList(),
        maxLiveSeats = liveSeats,
        daemonLog = daemonLog,
        allowRenderTrusted = allowRenderTrusted,
        trustStoreConfigured = trustStorePath != null,
        catalogRefreshSeconds = catalogRefreshSeconds,
        acceptBundlesEnabled = acceptBundles,
      )

    // Advertise on the LAN over mDNS when bound to a reachable interface (`--lan`), so the mobile /
    // wear session-viewer clients can discover this server without a typed URL. Best-effort: a null
    // advertiser (no multicast / sandbox) just means discovery stays dark — the server is fine.
    val advertiser =
      if (mdnsModuleLabel != null && mdnsPreviewIds != null && ServeUrls.isExposed(host)) {
        ServeMdnsAdvertiser.start(
          moduleLabel = mdnsModuleLabel,
          port = server.port,
          previewIds = mdnsPreviewIds,
          secure = false,
          onLog = { System.err.println("[serve] $it") },
        )
      } else {
        null
      }

    val done = CountDownLatch(1)
    Runtime.getRuntime()
      .addShutdownHook(
        Thread {
          System.err.println("\nserve: shutting down…")
          runCatching { advertiser?.close() }
          runCatching { server.stop() }
          runCatching { registry.close() }
          closeables.forEach { c -> runCatching { c?.close() } }
          done.countDown()
        }
      )

    server.start()
    printBanner(bannerLabel, server.port, token, bannerPreviewCount)
    val watchdog = if (exitWhenIdle) startIdleWatchdog(registry, done) else null
    done.await()
    watchdog?.shutdownNow()
  }

  /**
   * Poll the registry's server-level idle time; when it crosses [idleExitSeconds] with no open
   * connections, release [done] so [run] returns and the process exits (the shutdown hook tears the
   * server + daemons down). Returns the scheduler so the caller can stop it.
   */
  private fun startIdleWatchdog(
    registry: ServeSessionRegistry,
    done: CountDownLatch,
  ): ScheduledExecutorService {
    val timeoutMillis = idleExitSeconds * 1000
    val interval = (timeoutMillis / 4).coerceIn(1_000, 30_000)
    val exec = Executors.newSingleThreadScheduledExecutor { r ->
      Thread(r, "serve-idle-watchdog").apply { isDaemon = true }
    }
    exec.scheduleWithFixedDelay(
      {
        val idle = registry.idleMillis()
        if (idle != null && idle >= timeoutMillis) {
          System.err.println(
            "serve: idle ${idle / 1000}s (--exit-when-idle=${idleExitSeconds}s) — shutting down."
          )
          done.countDown()
        }
      },
      interval,
      interval,
      TimeUnit.MILLISECONDS,
    )
    return exec
  }

  /** Open the worktree manager rooted at the repo (project mode), gated to the allowed refs. */
  private fun openWorktrees(module: PreviewModule, rootOverride: File? = null): GitWorktrees {
    val repoRoot =
      rootOverride
        ?: findProjectRoot()
        ?: module.projectDir.absoluteFile.parentFile
        ?: module.projectDir
    if (revisionAllowRefs.isEmpty()) {
      System.err.println(
        "serve: --revisions has no --revisions-allow refs; no revision will build (fail closed). " +
          "Pass --revisions-allow <ref>[,<ref>…] (e.g. main,release/*) to enable trusted revs."
      )
    }
    return GitWorktrees(
      repoRoot = repoRoot,
      cacheRoot = File(repoRoot, "build/serve-worktrees"),
      allowedRefs = revisionAllowRefs,
      onLog = { System.err.println("[serve worktree] $it") },
    )
  }

  /** The project-mode factory: a git revision (`?session=<rev>`) → a built [ServeSessionState]. */
  private fun revisionFactory(
    module: PreviewModule,
    worktrees: GitWorktrees,
  ): ServeRevisionFactory {
    val repoRoot =
      findProjectRoot() ?: module.projectDir.absoluteFile.parentFile ?: module.projectDir
    val relativePath =
      module.projectDir.absoluteFile.relativeToOrNull(repoRoot.absoluteFile)?.path ?: ""
    // Match the bootstrap args the normal build path (Command.withGradle) applies, so a worktree
    // build sees the auto-injected plugin and the right variant — otherwise composePreviewDiscover
    // can run without the plugin/tasks or against the wrong variant and every ?session=<rev> fails.
    val bootstrapArgs =
      autoInjectInitScriptArgs(args, projectRoot = repoRoot) +
        variantGradleArgs() +
        gradleArgsWithForce()
    return ServeRevisionFactory(
      worktrees = worktrees,
      builder =
        GradleRevisionBuilder(
          extraArgs = bootstrapArgs,
          onLog = { System.err.println("[serve build] $it") },
        ),
      module = ServeModuleRef(module.gradlePath, relativePath),
      onLog = { System.err.println("[serve] $it") },
    )
  }

  /**
   * Discover portable bundles under `--bundles`. A directory that itself looks like a bundle is
   * served under its own name; otherwise each immediate sub-directory that looks like a bundle
   * becomes a session keyed by its name.
   */
  private fun registerBundles(): Map<String, ServeBundleHost> {
    val root = bundlesDir?.let { File(it) }?.takeIf { it.isDirectory } ?: return emptyMap()
    val result = LinkedHashMap<String, ServeBundleHost>()
    if (ServeBundleHost.looksLikeBundle(root)) {
      result[root.name] = ServeBundleHost(root, root.name)
    } else {
      root
        .listFiles { f -> f.isDirectory }
        ?.sortedBy { it.name }
        ?.forEach { sub ->
          if (ServeBundleHost.looksLikeBundle(sub))
            result[sub.name] = ServeBundleHost(sub, sub.name)
        }
    }
    if (result.isEmpty()) {
      System.err.println("serve: --bundles ${root.path} held no bundles (previews/*.png).")
    } else {
      System.err.println(
        "serve: serving ${result.size} bundle session(s): ${result.keys.joinToString(", ")}"
      )
    }
    return result
  }

  /**
   * Register every `--bundle <url|path>` bundle as its own session. Each is fetched (URL) or read
   * (local path), then served **live** from a render daemon when it verifies `Trusted` (signature
   * or trusted branch origin) AND `--allow-render-trusted` is set AND it's a desktop bundle
   * [ServeBundleDaemon.materialize] can stand up — otherwise served read-only as its baked PNGs
   * ([ServeBundleStore.add]). Returns the ids that registered, so the module-less landing can pick
   * a default session. Best-effort per bundle — one failing doesn't sink the others or the server.
   *
   * The live gate is the same fail-closed model as a catalog's `liveBundle`: an `Unverified` bundle
   * is never re-rendered server-side (no RCE lever), it just serves its baked images.
   */
  private fun registerStartupBundles(registry: ServeSessionRegistry): List<String> {
    if (bundleSpecs.isEmpty()) return emptyList()
    val root =
      java.nio.file.Files.createTempDirectory("serve-startup-bundles").toFile().also {
        it.deleteOnExit()
      }
    val bakedStore =
      ServeBundleStore(
        root = File(root, "baked").apply { mkdirs() },
        register = { id, host -> registry.register(id, host = host, pinned = true) },
        trust = resolvedTrust,
      )
    val registered = mutableListOf<String>()
    for (spec in bundleSpecs) {
      val bytes = obtainBundleBytes(spec) ?: continue
      // Branch-origin trust for a raw.githubusercontent.com URL (a bundle pulled from a trusted
      // branch); null for any other URL / a local path (then only a signature can make it Trusted).
      // A raw URL's ref can span slashes (`design-artifacts/compose-m3`), so try each candidate
      // split and prefer the one the trust store actually trusts; else fall back to the shortest
      // (harmless — an untrusted-branch origin just adds no basis).
      val origins = ServeStartupBundles.candidateOrigins(spec.source)
      val origin =
        origins.firstOrNull { resolvedTrust.trustsBranch(it.repo, it.branch) }
          ?: origins.firstOrNull()
      val bundleFile = File(root, "${spec.name}.bundle")
      try {
        bundleFile.writeBytes(bytes)
      } catch (e: Exception) {
        System.err.println("serve: bundle ${spec.name} could not be staged (${e.message})")
        continue
      }
      val verdict = BundleVerifier.verify(bundleFile, resolvedTrust, origin)
      // Live lane: Trusted + operator opt-in. A desktop bundle materialises a daemon straight from
      // the bundle (no build); a non-desktop/foreign/empty bundle returns null → falls to baked.
      if (allowRenderTrusted && verdict is BundleVerifier.Verdict.Trusted) {
        val destDir = File(root, "${spec.name}-live").apply { mkdirs() }
        val state =
          ServeBundleDaemon.materialize(
            bundleFile,
            destDir,
            spec.name,
            extraMavenRepos = extraMavenRepos,
          )
        val host = state?.let { openHost(it) }
        if (state != null && host != null) {
          registry.register(spec.name, state, host = host)
          System.err.println(
            "serve: bundle ${spec.name} → LIVE from bundle (no build), " +
              "trust=${BundleVerifier.summary(verdict)} (/${spec.name}/)"
          )
          registered += spec.name
          continue
        }
      } else if (allowRenderTrusted) {
        System.err.println(
          "serve: bundle ${spec.name} is ${BundleVerifier.summary(verdict)} — not live-rendering; " +
            "serving baked PNGs"
        )
      }
      // Read-only fallback: serve the bundle's baked previews/<id>.png (executes no code).
      when (val r = bakedStore.add(spec.name, bytes, isSecurityChecked = true, origin = origin)) {
        is ServeBundleStore.Result.Ok -> {
          registered += r.name
          System.err.println(
            "serve: bundle ${r.name} → ${r.previewCount} baked preview(s), trust=${r.trust} " +
              "(/${r.name}/)"
          )
        }
        is ServeBundleStore.Result.Failed ->
          System.err.println("serve: bundle ${spec.name} not served: ${r.reason}")
      }
    }
    return registered
  }

  /** Fetch (URL) or read (local path) a `--bundle` spec's bytes; null (logged) on any failure. */
  private fun obtainBundleBytes(spec: ServeStartupBundles.Spec): ByteArray? {
    if (ServeStartupBundles.isUrl(spec.source)) {
      val bytes = ServeStartupBundles.fetch(spec.source)
      if (bytes == null) {
        System.err.println("serve: bundle ${spec.name} fetch failed (${spec.source})")
      }
      return bytes
    }
    val f = File(spec.source)
    if (!f.isFile) {
      System.err.println("serve: bundle ${spec.name} path not found: ${spec.source}")
      return null
    }
    return try {
      f.readBytes()
    } catch (e: Exception) {
      System.err.println("serve: bundle ${spec.name} read failed (${e.message})")
      null
    }
  }

  /**
   * Fetch each `--catalogs` design system from its `design-artifacts/<system>` branch and register
   * it as a pinned `?session=<system>` session ([ServeCatalogStore]). Trusted-by-origin when the
   * branch is in the trust store; otherwise served as `unverified` (the images execute no code).
   * Best-effort per system — one catalog failing to fetch doesn't sink the others or the server.
   */
  /** [registerCatalogs] result: the wasm-app dirs plus the [store] a refresher re-loads from. */
  private class CatalogRegistration(val wasm: Map<String, File>, val store: ServeCatalogStore)

  private fun registerCatalogs(
    registry: ServeSessionRegistry,
    worktrees: GitWorktrees?,
    openHost: (ServeSessionState) -> ServeHost?,
  ): CatalogRegistration {
    val dir =
      java.nio.file.Files.createTempDirectory("serve-catalogs").toFile().also { it.deleteOnExit() }
    val wasm = linkedMapOf<String, File>()
    val store =
      ServeCatalogStore(
        root = dir,
        register = { id, host -> registry.register(id, host = host, pinned = true) },
        trust = resolvedTrust,
        repo = catalogRepo,
        branchPrefix = catalogBranchPrefix,
        registerWasm = { system, wasmDir ->
          wasm[system] = wasmDir
          System.err.println(
            "serve: catalog $system carries an in-browser Wasm app (/wasm/$system/)"
          )
        },
        buildTrustedBundle = {
          system,
          bundleFile,
          externalResourcesDir,
          alias,
          bakedFallback,
          fetchPerPreviewBundle ->
          buildTrustedCatalogBundle(
            system,
            bundleFile,
            externalResourcesDir,
            alias,
            bakedFallback,
            fetchPerPreviewBundle,
            registry,
            openHost,
          )
        },
        buildTrustedSource = { system, source, alias, bakedFallback ->
          buildTrustedCatalogSource(
            system,
            source,
            alias,
            bakedFallback,
            registry,
            worktrees,
            openHost,
          )
        },
      )
    for (ref in catalogRefs) {
      when (val r = store.load(ref.system, sourceRepo = ref.repo)) {
        is ServeCatalogStore.Result.Ok -> {
          // Listed catalogs feed the front-page "Design systems" nav; unlisted app catalogs feed
          // the separate "Apps" section (both reachable at /<system>/ and ?session=<system>).
          if (ref.listed) registeredCatalogs += r.system else registeredUnlistedCatalogs += r.system
          System.err.println(
            "serve: catalog ${r.system} → ${r.previewCount} preview(s), trust=${r.trust} " +
              "(/${r.system}/${if (ref.listed) "" else ", unlisted"})"
          )
        }
        is ServeCatalogStore.Result.Failed ->
          System.err.println("serve: catalog ${r.system} not served: ${r.reason}")
      }
    }
    return CatalogRegistration(wasm = wasm, store = store)
  }

  /**
   * Build the background poller that keeps a running server fresh against its catalog branches (see
   * [ServeCatalogRefresher]). Null when polling is disabled ([catalogRefreshSeconds] ≤ 0) or there
   * are no catalogs. The caller seeds heads + starts it, and adds it to the server's closeables so
   * the daemon thread stops on shutdown. A successful re-load re-registers the catalog host in
   * place (the registry closes the replaced daemon) and rewrites the on-disk `web/wasm/` dir the
   * `/wasm/<system>/` route serves.
   */
  private fun buildCatalogRefresher(store: ServeCatalogStore): ServeCatalogRefresher? {
    if (catalogRefreshSeconds <= 0 || catalogRefs.isEmpty()) return null
    val entries = catalogRefs.map {
      ServeCatalogRefresher.Entry(
        system = it.system,
        repo = it.repo,
        branch = "$catalogBranchPrefix${it.system}",
      )
    }
    return ServeCatalogRefresher(
      entries = entries,
      reload = { system, repo ->
        store.load(system, sourceRepo = repo) is ServeCatalogStore.Result.Ok
      },
      intervalMillis = catalogRefreshSeconds * 1000,
    )
  }

  /**
   * Build a `Trusted` catalog's carried `liveBundle` into a daemon-backed, re-renderable session —
   * the executable-bundle counterpart of [buildTrustedCatalogSource], and the store's preferred
   * path when a catalog declares one: [ServeBundleDaemon.materialize] extracts the fetched bundle,
   * resolves its classpath, and synthesises a `daemon-launch.json` directly — no Gradle build, no
   * worktree, no repo clone. The store only calls this for an already-`Trusted` catalog whose
   * bundle fetched cleanly; here we add the remaining fail-closed gate: `--allow-render-trusted`
   * must be set, same as the source path. Returns true once a daemon session is registered under
   * [system] (the store then skips both the Gradle source path and the static baked-PNG host);
   * false ⇒ caller falls back to `buildTrustedSource`, then the static host.
   */
  private fun buildTrustedCatalogBundle(
    system: String,
    bundleFile: File,
    externalResourcesDir: File?,
    alias: Map<String, String>,
    bakedFallback: () -> ServeHost,
    fetchPerPreviewBundle: (daemonId: String) -> File?,
    registry: ServeSessionRegistry,
    openHost: (ServeSessionState) -> ServeHost?,
  ): Boolean {
    if (!allowRenderTrusted) return false
    val destDir =
      java.nio.file.Files.createTempDirectory("serve-catalog-bundle-$system").toFile().also {
        it.deleteOnExit()
      }
    // The per-preview live lane (default render path, monolithic fallback): a bounded, idle-LRU
    // pool
    // of daemons, one per edited preview, each materialised from that preview's OWN FULL split
    // bundle fetched from the trusted branch. Shares the monolithic bundle's rehydrated font pool
    // ([externalResourcesDir]) — both were split from the same externalised bundle — so a
    // per-preview daemon rasterises text with the same faces without re-fetching. A per-preview
    // state carries no alias/bakedFallback, so openHost returns the bare single-preview daemon (not
    // another composite). When the fetch/materialise fails the pool yields null and
    // ServeCatalogLiveHost falls back to the monolithic daemon, so the lane never regresses.
    val perPreviewPool = ServePerPreviewDaemonPool { daemonId ->
      val ppFile = fetchPerPreviewBundle(daemonId) ?: return@ServePerPreviewDaemonPool null
      val ppDest =
        java.nio.file.Files.createTempDirectory("serve-catalog-preview-$system").toFile().also {
          it.deleteOnExit()
        }
      val ppState =
        ServeBundleDaemon.materialize(
          ppFile,
          ppDest,
          system,
          extraMavenRepos = extraMavenRepos,
          extraClasspathDirs = listOfNotNull(externalResourcesDir),
        ) ?: return@ServePerPreviewDaemonPool null
      openHost(ppState)
    }
    // Carry the catalog-id→daemon-id alias + the baked-PNG fallback + the per-preview lane on the
    // state so openHost fronts the daemon with the baked catalog: the published /p/<id> deep links
    // +
    // /render/<id>.png thumbnails keep resolving (Android-only variants fall back to baked), while
    // the mapped ids get a live lane. See ServeCatalogLiveHost. The rehydrated external-resource
    // pool (fonts lifted out of classes/app.jar) joins the daemon classpath so text rasterises with
    // the same faces.
    val state =
      ServeBundleDaemon.materialize(
          bundleFile,
          destDir,
          system,
          extraMavenRepos = extraMavenRepos,
          extraClasspathDirs = listOfNotNull(externalResourcesDir),
        )
        ?.copy(
          previewAliases = alias,
          bakedFallback = bakedFallback,
          perPreviewResolve = perPreviewPool::get,
          perPreviewStreamCount = perPreviewPool::activeStreamCount,
          perPreviewRenderStats = perPreviewPool::renderPerfStats,
        ) ?: return false
    val host = openHost(state) ?: return false
    registry.register(system, state, host = host)
    // Track the new pool and close the one it replaces — but only AFTER the fresh host is
    // registered (register already closed the old host), so a re-load never closes a pool the
    // still-live old host is mid-render on. First load for a system has no predecessor.
    catalogPerPreviewPools.put(system, perPreviewPool)?.let { runCatching { it.close() } }
    System.err.println("serve: catalog $system → LIVE from bundle (no build) (?session=$system)")
    return true
  }

  /**
   * Build a `Trusted` catalog's source into a daemon-backed, re-renderable session — the engine
   * behind `--allow-render-trusted`. The store only calls this for an already-`Trusted` catalog;
   * here we add the remaining fail-closed gates: the flag is set, the source repo (when given) is
   * the server's own [catalogRepo], and the source ref clears the worktree ref allowlist (enforced
   * inside [GitWorktrees.prepare]). Returns true once a daemon session is registered under [system]
   * (the store then skips the static baked-PNG host); false ⇒ fall back to baked PNGs.
   */
  private fun buildTrustedCatalogSource(
    system: String,
    source: ServeCatalogStore.CatalogSource,
    alias: Map<String, String>,
    bakedFallback: () -> ServeHost,
    registry: ServeSessionRegistry,
    worktrees: GitWorktrees?,
    openHost: (ServeSessionState) -> ServeHost?,
  ): Boolean {
    if (!allowRenderTrusted || worktrees == null) return false
    if (source.repo.isNotBlank() && source.repo != catalogRepo) {
      System.err.println(
        "serve: catalog $system source repo '${source.repo}' != '$catalogRepo' — not live-rendering"
      )
      return false
    }
    if (source.ref.isBlank() || source.module.isBlank()) return false
    val repoRoot = catalogSourceRoot ?: findProjectRoot() ?: return false
    // The ref allowlist is enforced here (fail-closed): null = unresolvable or not in
    // --revisions-allow.
    val worktree =
      worktrees.prepare(source.ref)
        ?: run {
          System.err.println(
            "serve: catalog $system ref '${source.ref}' not allowed/resolvable — serving baked PNGs"
          )
          return false
        }
    // GradleRevisionBuilder builds task names as ":${gradlePath}:…", so gradlePath must be the
    // colon-less form (e.g. `samples:design-catalog-m3`); a catalog's `source.module` is the
    // conventional `:samples:…` path, so strip the leading colon (a double `::` fails every build).
    val gradlePath = source.module.removePrefix(":")
    val relativePath = gradlePath.replace(":", "/")
    val bootstrapArgs =
      autoInjectInitScriptArgs(args, projectRoot = repoRoot) +
        variantGradleArgs() +
        gradleArgsWithForce()
    val builder =
      GradleRevisionBuilder(
        extraArgs = bootstrapArgs,
        onLog = { System.err.println("[serve build] $it") },
      )
    val built =
      builder.build(worktree, ServeModuleRef(gradlePath, relativePath), isSecurityChecked = true)
        ?: run {
          System.err.println(
            "serve: catalog $system build of ${source.module}@${source.ref} failed — serving baked PNGs"
          )
          return false
        }
    val state =
      ServeSessionState(
        descriptor = built.descriptor,
        workspaceRoot = built.moduleDir,
        workspaceName = built.moduleDir.name,
        previews = built.previews,
        label = "$system@${source.ref}",
        declaredThemes = built.declaredThemes,
        // Same catalog-id bridge + baked fallback as the bundle path (a source build's daemon uses
        // the same function-based ids), so a live source-rebuilt catalog also answers the published
        // URLs and falls back to baked PNGs for ids it can't render.
        previewAliases = alias,
        bakedFallback = bakedFallback,
        // A source-built Android/Robolectric catalog costs the same heavier live-seat weight as the
        // bundle path — read from the built daemon descriptor, since there's no bundle
        // manifest.backend here — so a from-source deployment keeps the OOM protection the
        // weighting
        // adds (a --live-seats budget can't admit two Android daemons thinking they're
        // desktop-cost).
        liveSeatWeight = ServeBundleDaemon.liveSeatWeightForDescriptor(built.descriptor),
      )
    val host = openHost(state) ?: return false
    registry.register(system, state, host = host)
    System.err.println(
      "serve: catalog $system → LIVE server-render from ${source.module}@${source.ref} " +
        "(?session=$system)"
    )
    return true
  }

  /**
   * Load the `--trust-store` JSON, or the empty fail-closed store when the flag is absent. A bad
   * path or unparseable file is a hard error: a public operator who *meant* to pin trusted
   * producers shouldn't silently fall back to trusting nothing (or, worse, think they configured it
   * when they didn't).
   */
  private fun loadTrustStore(): TrustStore {
    val path = trustStorePath ?: return TrustStore.EMPTY
    val f = File(path)
    if (!f.isFile) {
      System.err.println("serve: --trust-store not found: ${f.path}")
      exitProcess(1)
    }
    return try {
      TrustStore.load(f)
    } catch (e: Exception) {
      System.err.println("serve: could not parse --trust-store ${f.path}: ${e.message}")
      exitProcess(1)
    }
  }

  /**
   * Match a preview id against `--id` (exact) / `--filter` (substring); all when neither is set.
   */
  private fun matches(id: String): Boolean =
    when {
      exactId != null -> id == exactId
      filter != null -> id.contains(filter, ignoreCase = true)
      else -> true
    }

  private fun runDaemonStart(module: PreviewModule): Boolean {
    var ok = true
    withGradle(silenceStdout = false) { gradle ->
      ok =
        runGradle(
          gradle,
          ":${module.gradlePath}:composePreviewDaemonStart",
          arguments = gradleArgsWithForce(),
        )
    }
    return ok
  }

  private fun printBanner(moduleLabel: String, port: Int, token: String, previewCount: Int) {
    val exposed = ServeUrls.isExposed(host)
    val localHost = if (exposed || host == ServeUrls.LOOPBACK) ServeUrls.LOOPBACK else host
    // Public mode is open, so the link carries no token; otherwise the token gates every route.
    val localUrl =
      if (public) "${ServeUrls.origin(localHost, port)}/"
      else ServeUrls.landingUrl(ServeUrls.origin(localHost, port), token)

    System.err.println("compose-preview serve — module $moduleLabel")
    if (public) {
      System.err.println("  ⚠ Public mode — every route is open (no token required).")
    }
    System.err.println("  Local:   $localUrl")
    if (exposed) {
      val networks = ServeUrls.siteLocalIpv4Addresses()
      if (networks.isEmpty()) {
        System.err.println("  Network: (no site-local IPv4 address found)")
      } else {
        networks.forEach { ip ->
          System.err.println(
            "  Network: ${ServeUrls.landingUrl(ServeUrls.origin(ip, port), token)}"
          )
        }
      }
      System.err.println(
        "  ⚠ Bound to all interfaces — reachable by anyone on your LAN. The token in the link is " +
          "the only gate; share it only with people you'd let see these previews."
      )
    }
    System.err.println("  Previews: $previewCount")
    System.err.println("  Press Ctrl-C to stop.")
  }

  /**
   * Render every preview once through the held session and write a portable bundle to [path] — a
   * `.zip` when the path ends in `.zip`, otherwise a directory. `--inline` bakes the PNGs into the
   * gallery for a single self-contained `index.html`.
   */
  private fun exportBundle(host: ServeRenderHost, moduleLabel: String, path: String) {
    val built =
      ServeBundle.build(
        previews = host.previews,
        title = moduleLabel,
        modulePath = moduleLabel,
        inline = inlineBundle,
      ) { preview ->
        when (val outcome = host.render(preview.id, PreviewOverrides())) {
          is RenderOutcome.Ok -> outcome.png
          is RenderOutcome.Failed -> {
            System.err.println("serve: ${preview.id} failed to render (${outcome.reason})")
            null
          }
          RenderOutcome.NotFound -> null
          // Sequential single-thread export — the per-daemon lock is never contended, so Busy
          // shouldn't occur; treat it like a skip (no PNG) if it somehow does.
          RenderOutcome.Busy -> null
        }
      }

    val target = File(path)
    if (path.endsWith(".zip", ignoreCase = true)) {
      target.absoluteFile.parentFile?.mkdirs()
      target.writeBytes(ServeBundle.zip(built.files))
    } else {
      target.mkdirs()
      ServeBundle.writeDir(built.files, target)
    }

    System.err.println(
      "serve: wrote bundle to ${target.path} " +
        "(${built.renderedCount}/${built.previewCount} previews" +
        (if (built.failed.isEmpty()) "" else ", ${built.failed.size} failed") +
        ")"
    )
  }

  private fun printUsage() {
    println(
      """
      compose-preview serve [options]

      Start a local HTTP server that renders one module's @Preview functions on demand and serves
      them as PNGs with display overrides, so you can open (or share) a link to a specific preview.
      Read-only today; bound to loopback unless you opt into LAN exposure.

      Options:
        --module <path>   Module to serve, scoping local Gradle discovery + build to it. Implies
                          --discover. Omit it (and --discover) to run module-less — see below.
        --discover        Opt in to local Gradle discovery + build. By default serve NEVER runs
                          Gradle: it hosts only the fetched --bundle(s) / --catalogs / uploaded
                          bundles as a pure preview server, even inside a Gradle checkout (so a
                          stray `serve` at a repo root can't trigger a full — possibly hanging —
                          module build). Pass --discover to build every module's previews and host
                          one, or --module <path> to scope to a single module.
        --bundle <url|path>[, --bundle <name>=<url|path>, …]
                          Serve one or more fetched preview bundles directly — no --module, no
                          build. A URL is fetched at startup (operator-supplied, so no SSRF gate;
                          e.g. a raw.githubusercontent.com/<owner>/<repo>/<branch>/… link); a local
                          path is read. Each is served at /<name>/. A bundle that verifies Trusted
                          (Ed25519 signature, or fetched from a trusted branch in --trust-store) is
                          served LIVE from a render daemon when --allow-render-trusted is set
                          (desktop bundles); otherwise read-only as its baked PNGs. Repeatable.
        --id <exact>      Only serve this exact preview id.
        --filter <substr> Only serve previews whose id contains this substring.
        --host <addr>     Bind address (default 127.0.0.1 — loopback only).
        --lan             Bind all interfaces (0.0.0.0) so other devices on your network can
                          connect. Prints the token-gated network URL and a security warning.
        --port <n>        Preferred port (default $DEFAULT_PORT; auto-picks the next free one).
        --token <value>   Use a fixed token instead of a freshly generated one (stable links).
        --public          Serve every route WITHOUT a token (open). For a deployed public preview
                          server — browsing published catalogs / uploaded bundles is the point. Safe
                          by construction (no server-side code exec; untrusted re-render refused;
                          uploads capped + SSRF-gated). Off by default.
        --export <path>   Don't serve: render every preview once and write a portable bundle (a
                          self-contained web gallery + PNGs) to <path>. A '.zip' path writes a zip;
                          any other path writes a directory. The live server also offers this at
                          GET /bundle.zip.
        --inline          With --export, bake the PNGs into the gallery for a single self-contained
                          index.html (vs. separate previews/<id>.png files).
        --revisions       Project mode: also serve other git revisions of this repo on demand. A
                          request with ?session=<rev> checks that revision out into a worktree,
                          builds it, and serves it as its own session (suspended/resumed when idle).
        --revisions-allow <ref>[,<ref>…]
                          Project mode SECURITY gate: only revisions reachable from these trusted
                          refs (e.g. main,release) are checked out and built — building runs that
                          revision's own Gradle (code execution). Omitted/empty = nothing builds
                          (fail closed), so arbitrary ?session=<rev> can't run code on the server.
                          Also gates --allow-render-trusted (the catalog source ref allowlist).
        --allow-render-trusted
                          SECURITY gate, opt-in (default off): serve a --catalogs catalog that is
                          Trusted AND declares a source as a live, re-renderable session built from
                          that source (full-fidelity overrides) instead of static baked PNGs. Runs
                          the source's Gradle = code execution, so it's gated by trust + the
                          --revisions-allow ref allowlist + a same-repo check. NEVER set this on a
                          box that can't build the catalog source (e.g. the desktop-only public
                          image can't build the Android catalogs) — leave it off and let the
                          in-browser Wasm tier carry CMP.
        --catalog-source-root <dir>
                          Git repo root the trusted-catalog builder (--allow-render-trusted)
                          worktrees + builds from, instead of the served --module's own project. Use
                          when --module is a standalone project but the catalog's source.repo is a
                          separate checkout (e.g. a prebuilt image serving sample-project that clones
                          the CMP catalog repo for live render). The trust + same-repo + ref-allowlist
                          gates are unchanged.
        --live-seats <n>  Live (daemon-backed) stream PERMIT BUDGET. Each live session charges permits
                          by backend weight — a desktop CMP daemon costs 1, a heavier Robolectric
                          Android one costs 2 — so one heavy catalog can't hog a flat seat count and
                          starve the cheap CMP lanes. On a small box bound this (e.g. 2) when the live
                          tier is on (--allow-render-trusted); an over-budget stream is refused (WS
                          1013) rather than risking the OOM killer. Default 0 = unbounded. Snapshot +
                          Wasm sessions never take a permit.
        --exit-when-idle[=<seconds>]
                          Ephemeral mode: shut the server down once it's been idle (no open
                          connections and no requests) for <seconds> (default ${DEFAULT_IDLE_EXIT_SECONDS}s). Use a small
                          value to exit shortly after the last client disconnects.
        --bundles <dir>   Shared mode: also host pre-rendered portable bundles (no build/daemon). A
                          bundle dir, or a directory of them, is served read-only — each reachable at
                          ?session=<bundle-name>. Bundles are what --export / GET /bundle.zip produce.
        --accept-bundles  Shared mode (public): enable POST /bundles/<name> so clients can contribute
                          bundles at runtime — upload the zip as the body, or pass ?url=<link> to a
                          build-results artifact. Pair with --lan + a strong --token for a shared
                          instance. Uploads only by default; ?url= fetches need --accept-bundles-from.
        --accept-bundles-from <host>[,<host>…]
                          SSRF allowlist for POST /bundles?url=: hostnames the server may fetch a
                          bundle from. Omitted/empty = no URL fetch is allowed (fail closed), so a
                          client can't steer the server at an arbitrary or internal address.
        --trust-store <file>
                          Producer-trust allowlist (JSON: signing keys / branches / CI identities).
                          Uploaded bundles are verified against it and the verdict (signature /
                          branch / provenance / unverified) is returned + badged. Omitted = trust
                          nothing (every upload unverified); the data tiers serve either way.
        --catalogs <system>[@<owner>/<repo>][,…]
                          Serve our published design systems from their design-artifacts/<system>
                          branches (e.g. compose-m3,wear-m3): each is fetched (catalog.json + images)
                          and served read-only at /<system>/ (also ?session=<system>), listed on the
                          front-page nav, trusted-by-origin when the branch is in --trust-store. Add
                          @<owner>/<repo> to fetch a system from a different repo than --catalog-repo
                          (e.g. meshcore-mobile@yschimke/meshcore-mobile).
        --catalogs-unlisted <system>[@<owner>/<repo>][,…]
                          Like --catalogs, but served WITHOUT a front-page nav link — reachable at
                          /<system>/ and ?session=<system> but hidden from the landing "Design
                          systems" row. For app design systems we publish but keep off the front door.
        --catalog-repo <owner/repo>
                          Default repo the catalogs are fetched from (default
                          yschimke/compose-ai-tools); per-entry @<owner>/<repo> overrides it.
        --catalog-branch-prefix <prefix>
                          Branch prefix for --catalogs (default design-artifacts/).
        --catalog-refresh-interval <seconds>
                          Keep a running server fresh: re-check each --catalogs branch's head every
                          <seconds> and re-fetch (catalog.json + renders + web/wasm/ + liveBundle) in
                          place when it moved — so a regenerated branch is picked up with no restart
                          (default ${DEFAULT_CATALOG_REFRESH_SECONDS}s; 0 disables, serving the boot snapshot only). Uses
                          `git ls-remote` (no API rate limit), and skips a branch it can't resolve.
        --wasm-dir <system>=<dir>[,<system>=<dir>…]
                          In-browser CMP tier: map a design system to its assembled Kotlin/Wasm
                          catalog app (./gradlew :samples:cmp-wasm-catalog:wasmCatalogDist →
                          build/wasmDist). That session's viewer then offers a "Run in browser
                          (Wasm)" toggle that mounts the M3 components client-side (no server
                          round-trip), served read-only at /wasm/<system>/. Missing dirs are skipped.

      The shareable link carries an unguessable token; requests without it get 404.
      """
        .trimIndent()
    )
  }

  private companion object {
    const val DEFAULT_PORT = 8723
    const val DEFAULT_IDLE_EXIT_SECONDS = 60L

    /**
     * Default catalog re-check cadence (10 min). Fresh enough that a regenerated design-artifacts
     * branch reaches a running server within minutes, and — via `git ls-remote` (no API rate limit)
     * — cheap enough to poll every watched catalog at this cadence indefinitely.
     */
    const val DEFAULT_CATALOG_REFRESH_SECONDS = 600L
  }
}
