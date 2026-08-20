package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.serve.BundleVerifier
import ee.schimke.composeai.cli.serve.CatalogBlobPool
import ee.schimke.composeai.cli.serve.CatalogLoadTracker
import ee.schimke.composeai.cli.serve.CatalogRefreshResult
import ee.schimke.composeai.cli.serve.CatalogThemeCache
import ee.schimke.composeai.cli.serve.DaemonStartupLog
import ee.schimke.composeai.cli.serve.FileOptimizerHostCoordinator
import ee.schimke.composeai.cli.serve.GenerationInputs
import ee.schimke.composeai.cli.serve.GitWorktrees
import ee.schimke.composeai.cli.serve.GradleRevisionBuilder
import ee.schimke.composeai.cli.serve.LinuxHostResourceSampler
import ee.schimke.composeai.cli.serve.LiveSeatLimiter
import ee.schimke.composeai.cli.serve.MutableTrustStore
import ee.schimke.composeai.cli.serve.OptimizerHostCoordinator
import ee.schimke.composeai.cli.serve.OptimizerPressureGate
import ee.schimke.composeai.cli.serve.PlaygroundAndroidRenderService
import ee.schimke.composeai.cli.serve.PlaygroundAndroidSessionOpener
import ee.schimke.composeai.cli.serve.PlaygroundBtaCompiler
import ee.schimke.composeai.cli.serve.PlaygroundBundleSource
import ee.schimke.composeai.cli.serve.PlaygroundCatalogAvailable
import ee.schimke.composeai.cli.serve.PlaygroundCatalogClasspath
import ee.schimke.composeai.cli.serve.PlaygroundCatalogTargets
import ee.schimke.composeai.cli.serve.PlaygroundClasspathSupplier
import ee.schimke.composeai.cli.serve.PlaygroundCompileService
import ee.schimke.composeai.cli.serve.PlaygroundHealth
import ee.schimke.composeai.cli.serve.PlaygroundJailedCompiler
import ee.schimke.composeai.cli.serve.PlaygroundMode
import ee.schimke.composeai.cli.serve.PlaygroundPreviewDiscoverer
import ee.schimke.composeai.cli.serve.PlaygroundPublicGate
import ee.schimke.composeai.cli.serve.PlaygroundRcCaptureService
import ee.schimke.composeai.cli.serve.PlaygroundRedeemService
import ee.schimke.composeai.cli.serve.PlaygroundSandbox
import ee.schimke.composeai.cli.serve.PlaygroundSandboxProbe
import ee.schimke.composeai.cli.serve.PlaygroundSeedResolver
import ee.schimke.composeai.cli.serve.PlaygroundTokenStore
import ee.schimke.composeai.cli.serve.RenderOutcome
import ee.schimke.composeai.cli.serve.ServeBackgroundWork
import ee.schimke.composeai.cli.serve.ServeBundle
import ee.schimke.composeai.cli.serve.ServeBundleDaemon
import ee.schimke.composeai.cli.serve.ServeBundleHost
import ee.schimke.composeai.cli.serve.ServeBundleStore
import ee.schimke.composeai.cli.serve.ServeCatalogAdmin
import ee.schimke.composeai.cli.serve.ServeCatalogChangeFeed
import ee.schimke.composeai.cli.serve.ServeCatalogLiveHost
import ee.schimke.composeai.cli.serve.ServeCatalogRefresher
import ee.schimke.composeai.cli.serve.ServeCatalogStore
import ee.schimke.composeai.cli.serve.ServeCatalogsConfig
import ee.schimke.composeai.cli.serve.ServeCatalogsConfigFile
import ee.schimke.composeai.cli.serve.ServeDocFormats
import ee.schimke.composeai.cli.serve.ServeDocStore
import ee.schimke.composeai.cli.serve.ServeEngagementStore
import ee.schimke.composeai.cli.serve.ServeGithubAuth
import ee.schimke.composeai.cli.serve.ServeGithubAuthConfig
import ee.schimke.composeai.cli.serve.ServeHost
import ee.schimke.composeai.cli.serve.ServeHttpServer
import ee.schimke.composeai.cli.serve.ServeMdnsAdvertiser
import ee.schimke.composeai.cli.serve.ServeModuleRef
import ee.schimke.composeai.cli.serve.ServeParameterRows
import ee.schimke.composeai.cli.serve.ServePerPreviewDaemonPool
import ee.schimke.composeai.cli.serve.ServePreview
import ee.schimke.composeai.cli.serve.ServeProjectHistory
import ee.schimke.composeai.cli.serve.ServeRateLimiter
import ee.schimke.composeai.cli.serve.ServeRenderHost
import ee.schimke.composeai.cli.serve.ServeRevisionFactory
import ee.schimke.composeai.cli.serve.ServeSessionFactory
import ee.schimke.composeai.cli.serve.ServeSessionRegistry
import ee.schimke.composeai.cli.serve.ServeSessionState
import ee.schimke.composeai.cli.serve.ServeSharedDaemonPool
import ee.schimke.composeai.cli.serve.ServeSiteAdmin
import ee.schimke.composeai.cli.serve.ServeSiteRegistry
import ee.schimke.composeai.cli.serve.ServeSites
import ee.schimke.composeai.cli.serve.ServeStartupBundles
import ee.schimke.composeai.cli.serve.ServeTrustAdmin
import ee.schimke.composeai.cli.serve.ServeTrustStoreFile
import ee.schimke.composeai.cli.serve.ServeUrls
import ee.schimke.composeai.cli.serve.ServeWeb
import ee.schimke.composeai.cli.serve.ThemeCacheFingerprint
import ee.schimke.composeai.cli.serve.ThemeCacheStore
import ee.schimke.composeai.cli.serve.TrustStore
import ee.schimke.composeai.cli.serve.declaredThemesFromPreviews
import ee.schimke.composeai.cli.serve.detectedFeaturesOf
import ee.schimke.composeai.cli.serve.openIsolatedSharedDaemonReplica
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.render.session.RenderSessionException
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toPath

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
class ServeCommand(args: List<String>, private val browseProject: Boolean = false) : Command(args) {

  /**
   * `serve` is the one command that turns a `@PreviewParameter` fan-out into addressable row ids
   * (issue #3786), so it is the one that wants module selection to keep previews whose rows *might*
   * match. It can afford to: the render below produces the fan-out, `ServeParameterRows` expands
   * it, and [modulesWithMatchingPreviews] then discards any module whose "maybe" didn't pan out —
   * before the one-module check that would otherwise choke on it.
   */
  override val rowAwareSelection: Boolean
    get() = true

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

  /**
   * The one live-seat budget for this server, shared by the HTTP stream lane and by every catalog
   * daemon pool. Built here rather than inside [ServeHttpServer] so the pools — which are
   * constructed while catalogs load, before the server exists — charge the same budget. Two
   * separate limiters would each believe it owned the whole box.
   */
  private val liveSeatLimiter: LiveSeatLimiter = LiveSeatLimiter(liveSeats)
  private val exportPath: String? = args.flagValue("--export")?.takeIf { it.isNotBlank() }
  private val inlineBundle: Boolean = "--inline" in args

  /**
   * Project mode: besides the current checkout (the default session), fork a daemon-backed session
   * per git revision requested via `?session=<rev>`, each built in its own worktree and suspended /
   * resumed by the registry. Off by default (just the current module).
   */
  private val revisions: Boolean = "--revisions" in args

  /**
   * Project mode's render-history timeline: the **baseline delivery branch**, as it exists in this
   * checkout, whose publishes the viewer's history strip is computed from ([ServeProjectHistory]).
   *
   * On by default and self-disabling: a clone that never fetched the branch resolves nothing and
   * the strip is simply omitted, so the default costs one `git rev-parse` per refresh window on a
   * project that doesn't publish baselines. `--history-branch <ref>` points it at another branch (a
   * fork's, or a fully-qualified `refs/…`); `--no-history` turns it off outright.
   */
  private val historyBranch: String? =
    if ("--no-history" in args) null
    else
      args.flagValue("--history-branch")?.trim()?.takeIf { it.isNotEmpty() }
        ?: ServeProjectHistory.DEFAULT_BRANCH

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
   * module-less box (e.g. the prebuilt `deploy/image`) live-render a fetched catalog by pointing
   * this at a separate checkout of the catalog's `source.repo` (which the entrypoint clones). The
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
   * How long an RSS reader keeps a catalog's background change-feed worker interested. Every
   * `feed.xml` request renews the lease; after this many quiet seconds the worker stops fetching
   * the delivery branch while retaining its last XML + shallow Git cache. `0` disables the feed
   * lane.
   */
  private val catalogFeedIdleSeconds: Long =
    args.flagValue("--catalog-feed-idle-timeout")?.toLongOrNull()
      ?: DEFAULT_CATALOG_FEED_IDLE_SECONDS

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
   * Streamlined, Storybook-like presentation. The routes and render products stay the same, but the
   * HTML pages expose only catalog browsing, visual variants, usage source and the small set of
   * controls useful while evaluating a component.
   */
  private val componentBrowser: Boolean = "--component-browser" in args

  /** Internal convenience used by [BrowseCommand]; full `serve` keeps its print-only behaviour. */
  private val openBrowser: Boolean = "--open-browser" in args

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
   * Document ingestion (`--accept-docs`): enable `GET /docs` + `POST /docs` so a client can hand
   * the server one **known document** (a Remote Compose `.rc`, a Lottie JSON — [ServeDocFormats])
   * and get back an **expiring permalink** (`/d/<id>`) that plays it in the browser. Off by
   * default.
   *
   * Independent of `--accept-bundles`: a bundle becomes a whole preview session, a document is one
   * file with a short-lived share link and no server-side render at all.
   */
  private val acceptDocs: Boolean = "--accept-docs" in args

  /** How long an ingested document's permalink lives (`--doc-ttl <seconds>`). */
  private val docTtlSeconds: Long =
    args.flagValue("--doc-ttl")?.toLongOrNull()?.takeIf { it > 0 }
      ?: ServeDocStore.DEFAULT_TTL_SECONDS

  /**
   * SSRF allowlist for `POST /docs?url=`: hostnames the server may fetch a document from. Empty =
   * uploads only (fail closed), exactly like [acceptBundlesFrom].
   */
  private val acceptDocsFrom: List<String> =
    args.flagValue("--accept-docs-from")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
      ?: emptyList()

  /**
   * Where each `--catalogs` system's fetched, trust-verified `liveBundle` landed on disk, filled in
   * by [registerCatalogs] as catalogs load (and refreshed in place when a branch head moves). Read
   * by the playground's `--playground-bundle <system>` form so a compile classpath can come from a
   * catalog this box already serves instead of a hand-placed copy (issue #3212). Concurrent:
   * written by catalog load / refresh threads, read from request threads.
   */
  private val catalogLiveBundles =
    java.util.concurrent.ConcurrentHashMap<String, List<CatalogLiveBundle>>()

  /**
   * A served catalog's verified liveBundle, as the playground sees it: where the bytes landed and
   * which renderer they declare. [backend] is read once at load time (it costs one bundle-metadata
   * read, off the request path) because the runtime catalog selector needs it to decide which modes
   * a catalog can offer *before* anyone pays for a full classpath resolve. Null when the bundle's
   * metadata could not be read at all — such a catalog is simply not offered.
   */
  private data class CatalogLiveBundle(
    val id: String,
    val module: String,
    val file: java.io.File,
    val backend: String?,
  )

  private fun catalogTargetId(system: String, module: String, primary: Boolean): String =
    if (primary) system else "$system@$module"

  /**
   * `--playground-bundle <path|system>`: enable the playground lane (`POST /api/{v}/compiler/run`),
   * resolving the CMP compile classpath from a catalog liveBundle. Takes either a local `.bundle`
   * path or — since issue #3212 — the id of a system this box already serves via `--catalogs`
   * (`--playground-bundle compose-m3`), which reuses that catalog's fetched, trust-verified bundle
   * instead of a hand-placed copy that would silently go stale. See [PlaygroundBundleSource].
   *
   * Under `--public` the lane still has to clear [PlaygroundPublicGate], which admits it either
   * behind a verified sandbox or behind GitHub repo-access gating — the compile runs
   * **user-supplied code**, so one of the two must bound who supplies it. See
   * docs/design/PLAYGROUND.md §6.
   */
  private val playgroundBundlePath: String? = args.flagValue("--playground-bundle")

  /**
   * `--playground-android-bundle <path|system>`: enable the playground's **Android / Remote
   * Compose** compile lane, resolving its classpath from an Android catalog liveBundle — a local
   * path or a served `--catalogs` system id, exactly like `--playground-bundle`. Snippets sent with
   * `confType=remote-compose` compile against it, render on the Robolectric daemon, and their
   * captured `.rc` is published as a `/d/<id>` permalink (needs the `lib-daemon-android` sidecar +
   * `android.jar` + the `/d/` document store). Gated under `--public` like `--playground-bundle`.
   */
  private val playgroundAndroidBundlePath: String? = args.flagValue("--playground-android-bundle")

  /**
   * `--playground` (env `SERVE_PLAYGROUND=1`): enable the playground lane with **nothing pinned**,
   * offering a runtime selector over the catalogs this host already serves
   * ([PlaygroundCatalogTargets]).
   *
   * The `--playground-bundle` flags pin one catalog per mode for the life of the process, which
   * makes "try this snippet against a different design system" an operator edit and a restart — on
   * a box already serving twenty verified catalogs. With this flag the choice moves to the request:
   * each catalog's bundle backend picks the renderer and its manifest supplies the dependencies, so
   * selecting a catalog selects the whole compile target. The pinned flags still work and become
   * the selector's preselected *default* entry, so an existing deployment is unchanged by adding
   * this.
   *
   * Needs `--catalogs` to be of any use — with no served catalogs there is nothing to select — so a
   * host with neither a pin nor a catalog is refused (loudly) rather than serving an empty
   * selector.
   */
  private val playgroundRuntimeSelection: Boolean = "--playground" in args

  /**
   * `--playground-catalog-limit <n>`: how many runtime-selected catalogs may hold a resolved
   * compile classpath at once. Each one is an unpacked bundle plus a resolved Maven classpath held
   * for the life of the process (they cannot be evicted while snippet JVMs hold their jars open),
   * so this is the knob that stops a public host from being walked into a full disk by a visitor
   * clicking through every entry in the selector.
   */
  private val playgroundCatalogLimit: Int =
    args.flagValue("--playground-catalog-limit")?.toIntOrNull()?.takeIf { it > 0 }
      ?: PlaygroundCatalogTargets.DEFAULT_LIMIT

  /**
   * `--playground-rate-limit <n>`: compiles per minute **per caller** (0 disables the limiter).
   *
   * Every other playground bound — compile slots, the compile timeout, the body cap, live seats,
   * the token store — is a whole-host one (issue #3214). None of them stops two callers from
   * holding every slot with back-to-back 180-second compiles while everyone else is told the
   * playground is busy. This is the fair-sharing half.
   */
  private val playgroundRateLimit: Int =
    args.flagValue("--playground-rate-limit")?.toIntOrNull()?.takeIf { it >= 0 }
      ?: DEFAULT_PLAYGROUND_RATE_LIMIT

  /**
   * `--playground-caller-concurrency <n>`: compiles one caller may hold at once. Default 1, which
   * is the knob that answers the complaint directly — with the host's `--playground-compile-slots`
   * at its default 2, one caller cannot hold both.
   */
  private val playgroundCallerConcurrency: Int =
    args.flagValue("--playground-caller-concurrency")?.toIntOrNull()?.takeIf { it > 0 } ?: 1

  /** Authenticated, explicitly acquired, single-host stateful BTA editing trial. Off by default. */
  private val playgroundEditing: Boolean = "--playground-editing" in args

  private val playgroundEditLeaseTtlSeconds: Long =
    args.flagValue("--playground-edit-lease-ttl")?.toLongOrNull()?.takeIf { it > 0 }
      ?: PlaygroundCompileService.DEFAULT_EDIT_LEASE_TTL_MILLIS / 1000

  /**
   * `--trust-forwarded-for`: rate-limit an anonymous caller by the **last** `X-Forwarded-For` entry
   * rather than the socket peer.
   *
   * Opt-in, because the header is client-supplied: on a directly-exposed host trusting it would let
   * a caller mint a fresh identity per request and walk straight past the limit. Set it only when
   * this server sits behind a reverse proxy you control that *appends* the peer address it saw
   * (nginx's `$proxy_add_x_forwarded_for`) — that appended last entry is the one a client can't
   * forge. Without it, every caller behind the proxy shares one bucket.
   */
  private val trustForwardedFor: Boolean = "--trust-forwarded-for" in args

  /**
   * `--playground-sandbox <profile>`: the **per-session sandbox** every playground snippet JVM runs
   * inside (`none` | `unshare` | `bwrap` | `systemd` | `strict` | `custom:<argv>`), plus its
   * resource knobs. This is Phase 4 of docs/design/PLAYGROUND.md — one of the two things that lets
   * the playground run under `--public`: with a verified sandbox the snippet no longer executes
   * unconfined on the serve host, so *anyone* may compile. The other is GitHub repo-access gating,
   * which bounds who may compile instead of what a compile can reach; with that configured a
   * sandbox here is defence in depth rather than the precondition. Default `none` — playground
   * allowed token-gated, and under `--public` only when repo-access-gated.
   */
  private val playgroundSandboxSpec: String? = args.flagValue("--playground-sandbox")

  private val playgroundSandboxMemoryMb: Int =
    args.flagValue("--playground-sandbox-memory-mb")?.toIntOrNull()
      ?: PlaygroundSandbox.DEFAULT_MEMORY_MB

  private val playgroundSandboxCpus: Double =
    args.flagValue("--playground-sandbox-cpus")?.toDoubleOrNull() ?: PlaygroundSandbox.DEFAULT_CPUS

  private val playgroundSandboxPids: Int =
    args.flagValue("--playground-sandbox-pids")?.toIntOrNull() ?: PlaygroundSandbox.DEFAULT_PIDS

  /**
   * `--playground-compile-slots <n>`: how many snippet compiles may hold a jailed JVM at once. The
   * compile-side counterpart to `--live-seats` — per-process caps bound one compile, this bounds
   * the aggregate, so peak compile memory is `slots × --playground-sandbox-memory-mb`.
   */
  private val playgroundCompileSlots: Int =
    args.flagValue("--playground-compile-slots")?.toIntOrNull()?.takeIf { it > 0 }
      ?: PlaygroundJailedCompiler.DEFAULT_COMPILE_SLOTS

  /** Hard wall-clock lifetime of one snippet JVM; the spawner kills it at the deadline. */
  private val playgroundSandboxTtlSeconds: Long =
    args.flagValue("--playground-sandbox-ttl")?.toLongOrNull()
      ?: PlaygroundSandbox.DEFAULT_TTL_SECONDS

  /**
   * `--playground-sandbox-ro <path>[,<path>…]`: extra host paths bound **read-only** into the jail.
   * The escape hatch for caches a render legitimately reads while having no network to fetch them —
   * the Robolectric `android-all` cache (`~/.m2/repository`) and the downloadable-font cache are
   * the two that matter in practice; prewarm them before going public.
   */
  private val playgroundSandboxReadOnlyPaths: List<String> =
    args
      .flagValue("--playground-sandbox-ro")
      ?.split(",")
      ?.map { it.trim() }
      ?.filter { it.isNotEmpty() } ?: emptyList()

  /**
   * The parsed, validated sandbox policy — or a startup failure. Parse errors are fatal rather than
   * fail-soft: an operator who asked for containment and got a typo must not silently be handed an
   * unsandboxed playground.
   */
  private val playgroundSandbox: Result<PlaygroundSandbox> =
    PlaygroundSandbox.parseProfile(playgroundSandboxSpec).mapCatching { parsed ->
      PlaygroundSandbox.validate(
          parsed.copy(
            memoryMb = playgroundSandboxMemoryMb,
            cpus = playgroundSandboxCpus,
            pids = playgroundSandboxPids,
            ttlSeconds = playgroundSandboxTtlSeconds,
            extraReadOnlyPaths = playgroundSandboxReadOnlyPaths,
          )
        )
        .getOrThrow()
    }

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

  /**
   * The live producer-trust store, shared by the upload store, the catalog store, and the trust
   * admin. Was a `by lazy` snapshot read once at startup, which meant an edit to producers.json —
   * or an admin change — needed a restart to take effect; consumers now read through this holder on
   * every verification instead.
   */
  private val trustStore: MutableTrustStore by lazy {
    MutableTrustStore(loadTrustStore(), source = trustStoreFile)
  }

  /**
   * The running branch poller, when there is one. Held so a trust revocation can invalidate the
   * remembered branch heads of the catalogs it just retired ([retireNewlyUntrusted]); the refresher
   * is built before the server and reaches it only as a closeable, so there's no other handle.
   */
  @Volatile private var activeRefresher: ServeCatalogRefresher? = null

  /** The producers.json document backing [trustStore], when `--trust-store` names one. */
  private val trustStoreFile: ServeTrustStoreFile? by lazy {
    trustStorePath?.let { ServeTrustStoreFile(File(it).absolutePath.toPath()) }
  }

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
   * **Top-level sites** (`--sites m3.preview.coo.ee=m3-catalog,…`; also `catalogs.json`'s `sites`):
   * host names on which one already-served catalog is presented as the whole server — its landing
   * at `/`, its links inside the custom domain, no front door and no neighbours. See [ServeSites];
   * it adds no catalog and no work, only a different reading of the same request.
   */
  private val sitesRaw: String? = args.flagValue("--sites")

  /**
   * The **catalog set as config** (`--catalogs-file <path>`; env `SERVE_CATALOGS_FILE` in the
   * container profiles): a `catalogs.json` ([ServeCatalogsConfig]) listing every catalog to serve,
   * where its delivery branch lives, whether it's on the front door, and which front-page section
   * it's published under. Meant to live on a mounted volume — *outside* the image — so publishing a
   * catalog is an operator config edit (or an admin-API call, which rewrites this same file) rather
   * than an image rebuild.
   *
   * Composes with `--catalogs` / `--catalogs-unlisted` rather than replacing them: file entries
   * come first (they carry the group declarations), then any flag entries the file didn't already
   * name.
   */
  private val catalogsFile: ServeCatalogsConfigFile? =
    args
      .flagValue("--catalogs-file")
      ?.takeIf { it.isNotBlank() }
      ?.let { ServeCatalogsConfigFile(it.toPath()) }

  /** Durable feed cache; defaults beside catalogs.json on deployed boxes, temp for local serve. */
  private val catalogFeedCacheDir: File by lazy {
    val preferred =
      args.flagValue("--catalog-feed-cache")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: catalogsFile
          ?.displayPath
          ?.let(::File)
          ?.absoluteFile
          ?.parentFile
          ?.resolve("catalog-feeds")
    if (
      preferred != null && (preferred.isDirectory || preferred.mkdirs()) && preferred.canWrite()
    ) {
      preferred
    } else {
      java.nio.file.Files.createTempDirectory("serve-catalog-feeds").toFile().also {
        it.deleteOnExit()
        if (preferred != null) {
          System.err.println(
            "serve: catalog feed cache ${preferred.absolutePath} is not writable; using ${it.absolutePath}"
          )
        }
      }
    }
  }

  /**
   * Shared secret for the runtime admin routes (`--admin-token`; env `SERVE_ADMIN_TOKEN`) — both
   * `/admin/catalogs` and `/admin/trust`. Absent ⇒ neither is registered at all, so a server that
   * didn't opt in has no admin surface. Deliberately distinct from the browse token: a `--public`
   * box hands that one out to every visitor.
   *
   * On a server running `--allow-render-trusted`, treat this as a code-execution credential:
   * `/admin/trust` can make a producer's Compose eligible for server-side re-render here.
   */
  private val adminToken: String? = args.flagValue("--admin-token")?.takeIf { it.isNotBlank() }

  /** Optional durable aggregate counters. Null keeps local serve sessions in-memory only. */
  private val engagementFile: File? =
    args.flagValue("--engagement-file")?.takeIf { it.isNotBlank() }?.let(::File)

  private val githubAuthClientId: String? =
    args.flagValue("--github-auth-client-id")?.takeIf { it.isNotBlank() }
  private val githubAuthClientSecret: String? =
    args.flagValue("--github-auth-client-secret")?.takeIf { it.isNotBlank() }
  private val githubAuthCookieSecret: String? =
    args.flagValue("--github-auth-cookie-secret")?.takeIf { it.isNotBlank() }
  private val githubAuthRepo: String? =
    args.flagValue("--github-auth-repo")?.takeIf { it.isNotBlank() }
  private val githubAuthCallbackBaseUrl: String? =
    args.flagValue("--github-auth-callback-base-url")?.takeIf { it.isNotBlank() }
  /**
   * Scopes the auth cookies to a parent domain so one sign-in covers it and every `--sites` host
   * under it (`preview.coo.ee` ⇒ valid on `m3.preview.coo.ee`). Unset keeps them host-only, which
   * is right for a single-hostname box; it is deliberately explicit rather than derived, since a
   * cookie domain is the blast radius of a session.
   */
  private val githubAuthCookieDomain: String? =
    args.flagValue("--github-auth-cookie-domain")?.takeIf { it.isNotBlank() }
  /**
   * Overrides the OAuth scope. Unset derives it from `--github-auth-repo`'s visibility, which is
   * what a deployment wants unless its GitHub App or org policy demands something specific.
   */
  private val githubAuthScope: String? =
    args.flagValue("--github-auth-scope")?.takeIf { it.isNotBlank() }
  private val githubAuthUsers: Set<String> =
    args
      .flagValue("--github-auth-users")
      ?.split(",")
      ?.map { it.trim().lowercase() }
      ?.filter { it.isNotEmpty() }
      ?.toSet() ?: emptySet()

  /**
   * The parsed `--catalogs-file`, or the empty config when none is set / it can't be read. A
   * malformed config is reported and treated as empty rather than fatal: a box whose config file
   * got truncated should still come up on its flag-supplied catalogs.
   */
  private val catalogsConfig: ServeCatalogsConfig by lazy {
    val file = catalogsFile ?: return@lazy ServeCatalogsConfig.EMPTY
    val parsed = runCatching {
      file.load()
    }
      .getOrElse {
        System.err.println("serve: could not read ${file.displayPath}: ${it.message}")
        ServeCatalogsConfig.EMPTY
      }
    parsed.problems().forEach { System.err.println("serve: catalogs config: $it") }
    parsed
  }
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
   * Serializes catalog session publication and retirement. The initial loader now runs after the
   * listener binds, so admin trust/catalog routes can otherwise interleave with a load that has
   * already computed trust but has not yet registered its host.
   */
  private val catalogRegistrationLock = Any()

  /**
   * Server-wide admission for the catalogs' background theme optimization: it parks while any
   * catalog is loading, and bounds how many of them render at once. Shared by every catalog host
   * this server opens — see [ServeBackgroundWork] for why both halves matter on a public box.
   *
   * The lane is derived from [liveSeatLimiter] because widening it is only safe where something
   * else bounds daemon count: an unbounded budget (the CLI default) keeps the single lane.
   */
  private val optimizerCoordinationDirectory: File? by lazy {
    val explicit =
      args.flagValue("--theme-optimizer-coordination-dir")?.takeIf { it.isNotBlank() }?.let(::File)
    explicit
      ?: catalogsFile
        ?.displayPath
        ?.let(::File)
        ?.absoluteFile
        ?.parentFile
        ?.resolve("optimizer-locks")
  }

  private val backgroundWork by lazy {
    val pressureSampler = LinuxHostResourceSampler()
    ServeBackgroundWork(
      maxConcurrentRenders = ServeBackgroundWork.renderLaneFor(liveSeatLimiter),
      hostCoordinator =
        optimizerCoordinationDirectory?.let {
          FileOptimizerHostCoordinator(
            directory = it,
            lanes = ServeBackgroundWork.DEFAULT_MAX_CONCURRENT_OPTIMIZERS,
          )
        } ?: OptimizerHostCoordinator.NONE,
      pressureGate = OptimizerPressureGate(sample = pressureSampler::sample),
    )
  }

  /**
   * Durable, content-addressed home for the heavy bytes a catalog load fetches — the executable
   * `liveBundle`, its per-preview splits, and the externalised resource pool ([CatalogBlobPool]).
   *
   * **Unlike the theme cache, unset is not off.** A temp-directory pool is exactly what this server
   * has always had — it is shared across systems and reloads and simply dies with the process — so
   * falling back to one costs nothing that was not already being paid. What `none` buys is the
   * ability to say "do not keep these bytes anywhere durable"; what a directory buys is that a
   * rolled container stops re-downloading ~100 MB per live catalog.
   *
   * **There is deliberately no derived default.** The feed and theme caches land beside
   * `catalogs.json`, which on the prebuilt image is the configuration volume — fine for a few MB of
   * feed XML, wrong for a pool that may hold several GB of executable bundles. So an unset flag
   * means the temp-dir pool this always had, a path means that path, and `none` forces the temp dir
   * back (which is how a deployment overrides an environment default without unsetting it).
   */
  private val catalogBlobPool: CatalogBlobPool by lazy {
    val requested = args.flagValue("--catalog-cache-dir")?.takeIf { it.isNotBlank() }
    val maxBytes =
      args.flagValue("--catalog-cache-max-bytes")?.toLongOrNull()?.takeIf { it > 0 }
        ?: CatalogBlobPool.DEFAULT_MAX_BYTES
    val preferred = requested?.takeIf { it != "none" }?.let(::File)
    if (
      preferred != null && (preferred.isDirectory || preferred.mkdirs()) && preferred.canWrite()
    ) {
      System.err.println(
        "serve: catalog blob cache at $preferred (cap ${maxBytes / (1024 * 1024)} MB)"
      )
      CatalogBlobPool(preferred, maxBytes = maxBytes)
    } else {
      if (preferred != null) {
        System.err.println("serve: catalog blob cache $preferred is not writable; using a temp dir")
      }
      val temp =
        java.nio.file.Files.createTempDirectory("serve-catalog-blobs").toFile().also {
          it.deleteOnExit()
        }
      CatalogBlobPool(temp, maxBytes = maxBytes)
    }
  }

  /**
   * Periodic enforcement of the catalog blob cache's ceiling.
   *
   * The publication-time sweeps below were sufficient while **every** writer sat on the load path:
   * a blob only ever arrived as part of a load, and a load is a publication. Caching request-path
   * reads ends that invariant — a lazily-fetched baked PNG or an `?at=<sha>` history read admits a
   * blob with no publication anywhere near it — so a box whose catalogs are not currently being
   * republished could admit indefinitely, with nothing enforcing `--catalog-cache-max-bytes` until
   * its next restart. On a public server holding a couple of dozen catalogs across twenty
   * addressable revisions each, that is a volume filling quietly.
   *
   * A plain daemon ticker rather than a check on the write path: enforcement is a directory census,
   * and the request path is exactly where that must not happen. [sweepCatalogBlobs]'s own rate
   * limit then makes a tick that lands soon after a publication's sweep free.
   */
  private fun startCatalogBlobSweeper(): AutoCloseable {
    val exec =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "serve-catalog-blob-sweep").apply { isDaemon = true }
      }
    exec.scheduleWithFixedDelay(
      { runCatching { sweepCatalogBlobs() } },
      CATALOG_BLOB_SWEEP_INTERVAL_MILLIS,
      CATALOG_BLOB_SWEEP_INTERVAL_MILLIS,
      java.util.concurrent.TimeUnit.MILLISECONDS,
    )
    return AutoCloseable { exec.shutdownNow() }
  }

  /**
   * Rate limiter for [sweepCatalogBlobs] — see there for why it is not a per-call-site decision.
   */
  private val lastCatalogBlobSweep = java.util.concurrent.atomic.AtomicLong()

  /**
   * Reclaim pooled blobs no longer worth their disk.
   *
   * Eviction is always safe here — the worst a reclaimed blob costs is the fetch that produces it
   * again — so unlike [sweepThemeCache] this needs to know nothing about which catalogs are live.
   * What it does need is to run after a **refresh** and not only after the startup pass: a box that
   * regenerates several times a day would otherwise accumulate every superseded revision's bundles
   * for the life of the process, which is exactly the disk this is supposed to bound.
   *
   * That makes it callable from every publication site, so the cost is bounded here rather than by
   * each caller remembering to: a sweep is a directory census, the grace window means a sweep
   * minutes after the last one is a near-certain no-op, and 23 catalogs publishing in sequence at
   * boot would otherwise run 23 of them. [force] is for the end of the startup pass, which reports
   * what the boot actually found — the one number that says whether the cache is working.
   */
  private fun sweepCatalogBlobs(force: Boolean = false) {
    val now = System.currentTimeMillis()
    val previous = lastCatalogBlobSweep.get()
    if (!force && now - previous < CATALOG_BLOB_SWEEP_INTERVAL_MILLIS) return
    if (!lastCatalogBlobSweep.compareAndSet(previous, now)) return
    val snapshot = runCatching { catalogBlobPool.sweep() }.getOrNull() ?: return
    // Otherwise silent: a periodic line saying nothing happened is noise, and the counters belong
    // on /status rather than in the log.
    if (force || snapshot.evicted > 0) {
      System.err.println(
        "serve: catalog blob cache — ${snapshot.blobs} blob(s), ${snapshot.bytes / (1024 * 1024)} MB, " +
          "${snapshot.hits} hit(s), ${snapshot.misses} miss(es), ${snapshot.evicted} reclaimed"
      )
    }
  }

  /**
   * Durable home for warmed theme renders (`--theme-cache-dir`), or null to keep the cache in
   * memory only.
   *
   * Defaults beside `catalogs.json` exactly as the feed cache does, because that is the directory a
   * deployment already mounts as a persistent volume — and persistence across container recreation
   * is the entire point.
   *
   * **Unlike the feed cache, there is deliberately no temp-directory fallback.** A theme cache in
   * `/tmp` would be written once, read never, and thrown away with the container: it would consume
   * disk and render time to buy exactly nothing, while reporting itself as working. Where there is
   * no durable location, the honest configuration is no disk tier at all.
   */
  private val themeCacheStore: ThemeCacheStore? by lazy {
    val requested = args.flagValue("--theme-cache-dir")?.takeIf { it.isNotBlank() }
    // `none` disables persistence outright, matching --trust-store's convention in this command.
    // A sentinel is needed because *unset* cannot mean "off": the derived default lands beside
    // --catalogs-file, which on the prebuilt image is the durable `preview_config` volume — so an
    // untouched deployment would quietly fill its configuration volume with an 8 GB render cache.
    if (requested == "none") return@lazy null
    val explicit = requested?.let(::File)
    val preferred =
      explicit
        ?: catalogsFile?.displayPath?.let(::File)?.absoluteFile?.parentFile?.resolve("theme-cache")
        ?: return@lazy null
    if (!(preferred.isDirectory || preferred.mkdirs()) || !preferred.canWrite()) {
      System.err.println("serve: theme cache disabled — $preferred is not writable")
      return@lazy null
    }
    val maxBytes =
      args.flagValue("--theme-cache-max-bytes")?.toLongOrNull()?.takeIf { it > 0 }
        ?: ThemeCacheStore.DEFAULT_MAX_BYTES
    System.err.println("serve: theme cache at $preferred (cap ${maxBytes / (1024 * 1024)} MB)")
    ThemeCacheStore(preferred, maxBytes = maxBytes)
  }

  /**
   * Reclaim theme-cache generations nothing can read any more.
   *
   * Run once the catalog pass has finished, which is the only moment the live set is actually
   * known: sweeping earlier would delete a generation a catalog three places down the list was
   * about to adopt. On a box regenerating several times a day this is where the disk is won back —
   * every superseded catalog revision and every previous server version leaves a generation behind.
   */
  private fun sweepThemeCache() {
    val store = themeCacheStore ?: return
    val live =
      liveThemeGenerations.entries
        .map { (system, fingerprint) -> ThemeCacheStore.GenerationId(system, fingerprint) }
        .toSet()
    // Three populations, and only the middle one is left alone:
    //  - loaded now: sweep it, so a refresh reclaims the fingerprint it just superseded;
    //  - configured but NOT loaded this pass: skip it. A transient fetch error or a shutdown before
    //    the loader reached it must not cost ~28 hours of re-warming;
    //  - no longer configured at all: sweep it, with no live generation to protect anything, so an
    //    operator removing a catalog actually gets the disk back. Passing null here — "sweep
    //    everything" — would collapse the first two together.
    val configuredButUnloaded = themeCacheConfiguredSystems().orEmpty() - liveThemeGenerations.keys
    val sweepable = runCatching { store.systems() }.getOrNull().orEmpty() - configuredButUnloaded
    val result =
      runCatching { store.sweep(live, onlySystems = sweepable + liveThemeGenerations.keys) }
        .getOrNull() ?: return
    if (result.deletedGenerations > 0) {
      System.err.println(
        "serve: theme cache swept ${result.deletedGenerations} stale generation(s), " +
          "${result.reclaimedBytes / (1024 * 1024)} MB reclaimed"
      )
    }
    if (result.overCap) {
      System.err.println(
        "serve: theme cache is ${result.bytes / (1024 * 1024)} MB, over its cap — every generation " +
          "still in use, so nothing was evicted. Raise --theme-cache-max-bytes or serve fewer catalogs."
      )
    }
  }

  /**
   * Systems this server is configured to serve, once the catalog tracker exists. Null before then,
   * which makes the sweep conservative rather than destructive.
   */
  @Volatile private var themeCacheConfiguredSystems: () -> Set<String>? = { null }

  /**
   * The generation currently in use **per system**, so a sweep knows what it must not reclaim.
   *
   * A map rather than a growing set, because a catalog refresh supersedes its own previous
   * fingerprint: an append-only set would keep protecting every generation the box had ever opened,
   * so a delivery branch regenerating a few times a day would accumulate multi-gigabyte generations
   * that the cap could never reclaim.
   */
  private val liveThemeGenerations = java.util.concurrent.ConcurrentHashMap<String, String>()

  /**
   * Build the disk tier for one catalog generation, or null when it has no durable identity.
   *
   * The fingerprint is computed from the daemon's own launch descriptor — the classpath it will
   * render with, and the variant it will render as — so nothing here has to be kept in step by hand
   * with what the renderer actually loads.
   */
  private fun themeCacheFor(
    system: String,
    alias: Map<String, String>,
    vararg descriptors: File,
  ): CatalogThemeCache {
    // Every bailout below names itself. A catalog that silently falls back to memory-only looks
    // exactly like one on a server with no cache directory, and the difference — the cache is
    // configured and this catalog alone is not using it — is the one an operator needs, because it
    // is permanent for the life of the host and nothing else reports it.
    val store = themeCacheStore ?: return CatalogThemeCache()
    val launches = descriptors.map {
      ServeBundleDaemon.readLaunchDescriptor(it)
        ?: return CatalogThemeCache(persistenceOffReason = "launch descriptor unreadable")
    }
    if (launches.isEmpty())
      return CatalogThemeCache(persistenceOffReason = "catalog has no launch descriptor")
    // The JVM the render runs in is part of what produced the pixels; the descriptor's system
    // properties are deliberately NOT, because they are dominated by absolute paths that a fresh
    // staging directory changes on every load — hashing those would make every load a new
    // generation and buy nothing.
    val renderConfig =
      launches.joinToString(" | ") {
        ThemeCacheFingerprint.renderConfig(it.systemProperties, it.jvmArgs)
      }
    val routing = ThemeCacheFingerprint.routingDigest(alias)
    val variant = launches.map { it.variant }.distinct().sorted().joinToString("+")
    // A multi-module catalog renders from several bundles at once and its generation is all of them
    // together — any one changing changes what a visitor sees.
    val fingerprint =
      ThemeCacheFingerprint.combine(
        launches.map { launch ->
          ThemeCacheFingerprint.of(
            classpath =
              ThemeCacheFingerprint.renderedClasspath(
                launch.classpath,
                launch.systemProperties,
                // Same preview manifest, reachable by a second route on descriptors that name it
                // here rather than in a system property.
                extraPayloads = listOfNotNull(launch.manifestPath),
              ),
            variant = launch.variant,
            toolVersion = BUNDLE_VERSION,
            renderConfig =
              ThemeCacheFingerprint.renderConfig(launch.systemProperties, launch.jvmArgs),
            routing = routing,
          )
            ?: return CatalogThemeCache(
              persistenceOffReason = "fingerprint unavailable (a classpath entry could not be read)"
            )
        }
      ) ?: return CatalogThemeCache(persistenceOffReason = "fingerprint unavailable")
    val inputs =
      GenerationInputs(
        system = system,
        fingerprint = fingerprint,
        toolVersion = BUNDLE_VERSION,
        variant = variant,
        renderConfig = "$renderConfig routing=$routing",
      )
    val generation =
      store.open(system, fingerprint, inputs)
        ?: return CatalogThemeCache(
          persistenceOffReason = "generation directory could not be opened"
        )
    // The map is updated here, but the SWEEP is not run here. This is called while a replacement
    // host is still being staged: `openHost` or publication can still fail and leave the previous
    // host serving from the registry — and reclaiming its generation at this point would delete a
    // warmed cache still in use, and leave its attached generation unable to write. Retirement
    // waits
    // for a successful publication (see [sweepThemeCache]'s callers).
    liveThemeGenerations[system] = fingerprint
    if (generation.loadedEntries > 0) {
      System.err.println(
        "serve: catalog $system → ${generation.loadedEntries} theme renders adopted from disk"
      )
    }
    return CatalogThemeCache(persistence = generation)
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

  /** Experimental AndroidX-conformant Remote Compose CMP/Wasm player distribution. */
  private val rcPlayerWasmDir: File? =
    args
      .flagValue("--rc-player-wasm-dir")
      ?.takeIf { it.isNotBlank() }
      ?.let(::File)
      ?.let { dir ->
        if (File(dir, "index.html").isFile) dir
        else {
          System.err.println(
            "serve: --rc-player-wasm-dir ${dir.path} has no index.html — skipping (build it with " +
              ":rc-player-wasm:wasmPlayerDist)."
          )
          null
        }
      }

  private val catalogRepo: String =
    args.flagValue("--catalog-repo")?.takeIf { it.isNotBlank() } ?: ServeCatalogStore.DEFAULT_REPO
  private val catalogBranchPrefix: String =
    args.flagValue("--catalog-branch-prefix")?.takeIf { it.isNotBlank() }
      ?: ServeCatalogStore.DEFAULT_BRANCH_PREFIX
  private val catalogMaxImages: Int =
    args.flagValue("--catalog-max-images")?.toIntOrNull()?.takeIf { it > 0 }
      ?: ServeCatalogStore.DEFAULT_MAX_IMAGES

  /**
   * A parsed `--catalogs` / `--catalogs-unlisted` entry: the [system] id, the [repo] its
   * `design-artifacts/<system>` branch lives in (the shared [catalogRepo] unless the entry gave an
   * `@<owner>/<repo>` override), and whether it's [listed] on the front-page nav.
   */
  private data class CatalogRef(
    val system: String,
    val repo: String,
    val listed: Boolean,
    /**
     * The front-page section this catalog is published under, with the repos allowed to claim it.
     * Only a `--catalogs-file` entry can carry one — a bare `--catalogs` flag entry declares no
     * publisher, so its card is grouped by its source repo's owner.
     */
    val group: ServeWeb.HomeGroup? = null,
    /**
     * Startup fetch order, highest first ([ServeCatalogsConfig.Entry.loadPriority]). Only a
     * `--catalogs-file` entry can raise it; a bare flag entry takes the default, which is the order
     * it was named in.
     */
    val loadPriority: Int = 0,
  )

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

  /** The `--catalogs-file` entries as refs, skipping any the config itself reports as malformed. */
  private fun configCatalogRefs(): List<CatalogRef> =
    catalogsConfig.catalogs
      .filter { ServeCatalogsConfig.validateEntry(it) == null }
      .map { entry ->
        val repo = entry.repo?.takeIf { it.isNotBlank() } ?: catalogRepo
        CatalogRef(
          system = entry.system,
          repo = repo,
          listed = entry.listed,
          group = ServeCatalogAdmin.homeGroup(entry, repo, catalogsConfig.groups),
          loadPriority = entry.loadPriority,
        )
      }

  /**
   * Whether this server needs the catalog machinery (store + load tracker) even with **no**
   * configured catalogs: an `--admin-token` server publishes its first catalog at runtime, so the
   * store it fetches through and the tracker it registers into have to exist before any request
   * arrives. Without this, a box started against an empty (or brand-new) `catalogs.json` couldn't
   * bootstrap itself — the admin routes it explicitly enabled would never be registered.
   */
  private val needsCatalogMachinery: Boolean
    get() = catalogRefs.isNotEmpty() || adminToken != null

  /**
   * All catalog refs to serve — the config file first (it carries the front-page grouping), then
   * the `--catalogs` / `--catalogs-unlisted` flag entries; de-duplicated by system (first wins), so
   * a flag can add a catalog the file doesn't name but never silently re-attributes one it does.
   */
  private val catalogRefs: List<CatalogRef> by lazy {
    (configCatalogRefs() +
        parseCatalogRefs(catalogsRaw, listed = true) +
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
      // `--id` / `--filter` / `--preview` select from a *discovered module's* manifest, and this
      // path never discovers one — the sessions come from bundles, catalogs and uploads. Ignoring
      // them silently is the exact shape issue #3744 was filed about: the user believes they
      // narrowed what is exposed and the server publishes everything. Say so instead.
      val selectors =
        listOfNotNull(
          exactId?.let { "--id" },
          filter?.let { "--filter" },
          previewRef?.let { "--preview" },
        )
      if (selectors.isNotEmpty()) {
        System.err.println(
          "serve: ${selectors.joinToString(" / ")} select previews from a discovered module, but " +
            "this server is bundle-backed — it hosts what --bundle / --bundles / --catalogs and " +
            "uploads provide, and no manifest to select against exists."
        )
        System.err.println(
          "  Drop the selector, or pass --module <path> / --discover to serve from a module."
        )
        exitProcess(64)
      }
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
    // Expand each module's `@PreviewParameter` fan-out BEFORE deciding how many modules are in play
    // (issue #3786 review follow-up). Module selection has to keep a parameterized preview whose
    // rows *might* match — the row ids don't exist until the render above wrote the fan-out — so
    // `--filter Crimson` can retain a module whose provider turns out to yield only Light/Dark.
    // That
    // module contributes nothing servable, and counting it here would abort a request that has
    // exactly one real answer. Resolving first turns the speculative keep back into a fact.
    val servable = modulesWithMatchingPreviews(outcome.manifests)
    if (servable.isEmpty()) {
      System.err.println("serve: no previews matched (--id/--filter excluded them all).")
      exitProcess(3)
    }
    if (browseProject && exportPath == null) {
      runProjectBrowser(servable, outcome.manifests)
      return
    }
    if (servable.size > 1) {
      System.err.println(
        "serve: ${servable.size} modules discovered; a server hosts one module. " +
          "Narrow with --module <path>:"
      )
      servable.forEach { (m, _) -> System.err.println("  ${m.gradlePath}") }
      exitProcess(1)
    }

    val (module, previews) = servable.single()
    val manifest = outcome.manifests.first { (m, _) -> m.gradlePath == module.gradlePath }.second
    // The module's declared @ThemeCatalog themes — the Theme selector renders them so a preview can
    // be re-rendered under Brand Dark etc. Module-global, so unaffected by the --id/--filter above.
    val declaredThemes = declaredThemesFromPreviews(manifest.previews)

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
      if (needsCatalogMachinery) registerCatalogs(registry, catalogWorktrees, openHost) else null
    // Keep the catalogs fresh against their (routinely-changing) branches without a restart.
    val catalogRefresher = catalogReg?.let { buildCatalogRefresher(it.store, it.loads) }
    // Make manual refresh + trust-revocation invalidation available as soon as any catalog page
    // can be served. The background cadence is still seeded and started after the loader finishes.
    activeRefresher = catalogRefresher
    // Runtime ingestion (--accept-bundles): clients POST a bundle (or a ?url= to one) and it's
    // registered as a pinned session. Unpacked under a temp dir for this server's lifetime.
    val bundleStore = if (acceptBundles) openUploadStore(registry) else null
    val wasmCatalogs = mergedWasmCatalogs(catalogReg)
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
          catalogReg?.loader,
          catalogRefresher,
          worktrees,
          catalogWorktrees.takeIf { it !== worktrees },
          catalogPerPreviewPoolsCloseable,
          catalogReg?.let { startCatalogBlobSweeper() },
        ),
      catalogLoads = catalogReg?.loads,
      catalogStore = catalogReg?.store,
      catalogRefresh = catalogRefresher?.let { refresher -> refresher::refresh },
      localSourceRoots =
        if (componentBrowser) mapOf(module.gradlePath to module.projectDir) else emptyMap(),
      // Project mode has the repository, so the viewer's history strip is computed from local git
      // instead of a published history.json — the same timeline the hosted viewer shows, sourced
      // the other way round. Only wired on this path: [runBundleServer] has no checkout to read.
      projectHistory =
        historyBranch?.let { ServeProjectHistory(repoRoot = projectRepoRoot(module), branch = it) },
      onStarted = {
        catalogReg?.loader?.start { loaded ->
          catalogRefresher?.let {
            it.seedInitialHeads(loaded)
            it.start()
          }
        }
      },
    )
  }

  /**
   * Browse every preview-bearing module behind one component-browser front door. A failed daemon
   * only removes that module; the other valid modules remain useful. This intentionally stays out
   * of the full `serve` path, whose revision/export/catalog options still describe one module.
   */
  private fun runProjectBrowser(
    servable: List<Pair<PreviewModule, List<ServePreview>>>,
    manifests: List<Pair<PreviewModule, PreviewManifest>>,
  ) {
    val registry = ServeSessionRegistry(open = ::openHost)
    val opened = mutableListOf<Pair<PreviewModule, List<ServePreview>>>()

    servable.forEach { (module, previews) ->
      if (!runDaemonStart(module)) {
        System.err.println(
          "browse: ${module.gradlePath} could not start its preview daemon — skipping it."
        )
        return@forEach
      }
      val descriptor = File(module.projectDir, "build/compose-previews/daemon-launch.json")
      if (!descriptor.isFile) {
        System.err.println(
          "browse: ${module.gradlePath} produced no daemon-launch.json — skipping it."
        )
        return@forEach
      }
      val manifest =
        manifests.first { (candidate, _) -> candidate.gradlePath == module.gradlePath }.second
      val declaredThemes = declaredThemesFromPreviews(manifest.previews)
      val host =
        try {
          ServeRenderHost.open(
            descriptorPath = descriptor,
            workspaceRoot = module.projectDir,
            workspaceName = module.projectDir.name,
            previews = previews,
            label = module.gradlePath,
            declaredThemes = declaredThemes,
            onLog = { System.err.println("[daemon browse ${module.gradlePath}] $it") },
          )
        } catch (e: RenderSessionException) {
          System.err.println(
            "browse: ${module.gradlePath} failed to open its render session (${e.message}) — skipping it."
          )
          return@forEach
        }
      registry.register(
        module.gradlePath,
        ServeSessionState(
          descriptor = descriptor,
          workspaceRoot = module.projectDir,
          workspaceName = module.projectDir.name,
          previews = previews,
          label = module.gradlePath,
          declaredThemes = declaredThemes,
        ),
        host = host,
      )
      opened += module to previews
    }

    if (opened.isEmpty()) {
      System.err.println("browse: no preview module could start a render session.")
      exitProcess(2)
    }

    val wasmCatalogs = mergedWasmCatalogs(null)
    val privateWasmCatalogs = mutableSetOf<String>()
    automaticWasmCatalogs(opened.map { it.first }).forEach { (module, dir) ->
      // An advanced explicit --wasm-dir remains an escape hatch, and wins when supplied.
      if (wasmCatalogs.putIfAbsent(module, dir) == null) privateWasmCatalogs += module
    }
    if (wasmCatalogs.isNotEmpty()) {
      System.err.println("browse: in-browser CMP Wasm for: ${wasmCatalogs.keys.joinToString(", ")}")
    }

    val first = opened.first()
    bringUpServer(
      registry = registry,
      token = tokenOverride ?: ServeUrls.generateToken(),
      defaultSessionId = first.first.gradlePath,
      bundleStore = null,
      wasmCatalogs = wasmCatalogs,
      privateWasmCatalogs = privateWasmCatalogs,
      bannerLabel = "${opened.size} component modules",
      bannerPreviewCount = opened.sumOf { it.second.size },
      mdnsModuleLabel = null,
      mdnsPreviewIds = null,
      closeables = emptyList(),
      catalogLoads = null,
      localCatalogSessions = opened.map { it.first.gradlePath },
      localSourceRoots = opened.associate { it.first.gradlePath to it.first.projectDir },
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
      if (needsCatalogMachinery) registerCatalogs(registry, worktrees = null, ::openHost) else null
    // Keep the catalogs fresh against their (routinely-changing) branches without a restart — the
    // public preview server (preview.coo.ee) runs this module-less path.
    val catalogRefresher = catalogReg?.let { buildCatalogRefresher(it.store, it.loads) }
    // A catalog registered early in the asynchronous startup load can already show Refresh; wire
    // its immediate check now instead of waiting for every configured catalog to finish loading.
    activeRefresher = catalogRefresher
    val bundleStore = if (acceptBundles) openUploadStore(registry) else null

    val wasmCatalogs = mergedWasmCatalogs(catalogReg)
    if (wasmCatalogs.isNotEmpty()) {
      System.err.println("serve: in-browser Wasm tier for: ${wasmCatalogs.keys.joinToString(", ")}")
    }

    // Pick a landing session so `/` resolves: the first configured catalog, else the first bundle.
    val defaultSessionId =
      catalogRefs.firstOrNull { it.listed }?.system
        ?: registeredStartup.firstOrNull()
        ?: registry.anySessionId()
    // An `--accept-bundles` server legitimately starts with no sessions — they arrive at runtime
    // via
    // POST /bundles — so only bail when there's genuinely nothing to serve and no way to add any.
    // `--accept-docs` is the same case (a pure document drop-box has no sessions at all, ever), as
    // is `--admin-token`: that server's catalogs arrive later via POST /admin/catalogs.
    if (
      defaultSessionId == null &&
        catalogRefs.isEmpty() &&
        !acceptBundles &&
        !acceptDocs &&
        adminToken == null
    ) {
      System.err.println(
        "serve: nothing to serve — no --bundle / --bundles / --catalogs registered a session, and " +
          "none of --accept-bundles / --accept-docs / --admin-token is set."
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
      closeables =
        listOfNotNull(
          catalogReg?.loader,
          catalogRefresher,
          catalogPerPreviewPoolsCloseable,
          catalogReg?.let { startCatalogBlobSweeper() },
        ),
      catalogLoads = catalogReg?.loads,
      catalogStore = catalogReg?.store,
      catalogRefresh = catalogRefresher?.let { refresher -> refresher::refresh },
      onStarted = {
        catalogReg?.loader?.start { loaded ->
          catalogRefresher?.let {
            it.seedInitialHeads(loaded)
            it.start()
          }
        }
      },
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
  private fun openHost(state: ServeSessionState): ServeHost? = runCatching {
    fun openDaemon(systemPropertyOverrides: Map<String, String> = emptyMap()): ServeRenderHost =
      ServeRenderHost.open(
        descriptorPath = state.descriptor,
        workspaceRoot = state.workspaceRoot,
        workspaceName = state.workspaceName,
        previews = state.previews,
        label = state.label,
        declaredThemes = state.declaredThemes,
        systemPropertyOverrides = systemPropertyOverrides,
        onLog = { System.err.println("[daemon serve] $it") },
      )
    val daemon = openDaemon()
    val fallback = state.bakedFallback
    if (fallback != null)
      ServeCatalogLiveHost(
          alias = state.previewAliases,
          live = daemon,
          baked = fallback(),
          perPreviewResolve = state.perPreviewResolve,
          executableBundleAvailable = state.executableBundleAvailable,
          executableBundleProvider = state.executableBundleProvider,
          perPreviewStreamCount = state.perPreviewStreamCount,
          perPreviewRenderStats = state.perPreviewRenderStats,
          perPreviewPoolStats = state.perPreviewPoolStats,
          perPreviewReapIdle = state.perPreviewReapIdle,
          sharedDaemonPool =
            ServeSharedDaemonPool(
              primary = daemon,
              liveSeats = liveSeatLimiter,
              seatWeight = { state.liveSeatWeight },
            ) {
              // Every daemon writes <outputBaseName>.png and its data products below the
              // descriptor's output root. Replicas therefore need separate roots even though
              // they share the catalog classpath; otherwise overlapping themes can overwrite
              // one another between a completion notification and ServeRenderHost reading the
              // file.
              openIsolatedSharedDaemonReplica(state.descriptor, ::openDaemon)
            },
          catalogThemeCache = state.catalogThemeCache ?: CatalogThemeCache(),
          serverIdleMillis = state.serverIdleMillis,
          backgroundWork = state.backgroundWork,
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

  /**
   * Build the `--accept-docs` document store, or null when the operator didn't opt in. In-memory
   * and TTL-bounded — an ingested document is a short-lived share, not a session, so there is
   * nothing to register with the session registry and nothing to clean up at shutdown.
   */
  private fun openDocStore(): ServeDocStore? {
    if (!acceptDocs) return null
    if (acceptDocsFrom.isEmpty()) {
      System.err.println(
        "serve: --accept-docs accepts uploads only; no ?url= host is allowed (SSRF fail closed). " +
          "Pass --accept-docs-from <host>[,<host>…] to permit URL fetches."
      )
    }
    System.err.println(
      "serve: document uploads enabled (/docs) — ${ServeDocFormats.knownSummary()}; " +
        "links expire after ${docTtlSeconds}s"
    )
    return ServeDocStore(ttlSeconds = docTtlSeconds, allowedHosts = acceptDocsFrom)
  }

  /**
   * Build the `--playground-bundle` compile service, or null when not opted in. Resolves the CMP
   * compile classpath from the catalog liveBundle once at startup and wires the in-process BTA
   * compiler from the CLI install's `lib-bta/`.
   *
   * **Under `--public` the lane needs one of two admission postures** ([PlaygroundPublicGate]):
   * either a verified per-session sandbox — the Phase-4 gate (docs/design/PLAYGROUND.md §6,
   * issue #3016), where `--playground-sandbox` selects the jail every snippet JVM launches inside
   * and a startup probe must come back showing that jail blocks egress, contains the filesystem,
   * and isolates the process namespace — or [repoAccessGated], meaning GitHub auth is configured so
   * the routes admit only users with access to `--github-auth-repo` (issue #3210). Anonymous *and*
   * uncontained is still refused. Fail-soft everywhere else: any missing piece (bundle
   * unresolvable, no `lib-bta/`) logs why and disables the lane rather than aborting serve.
   *
   * @param repoAccessGated GitHub auth is configured, so the playground routes' repo-access check
   *   actually rejects a caller instead of falling through (see `rejectMissingGithubRepoAccess`).
   */
  private fun openPlaygroundService(
    docStore: ServeDocStore?,
    registry: ServeSessionRegistry,
    repoAccessGated: Boolean,
  ): PlaygroundLane? {
    val cmpBundle = playgroundBundlePath
    val androidBundle = playgroundAndroidBundlePath
    if (cmpBundle == null && androidBundle == null && !playgroundRuntimeSelection) return null
    // `--playground` on its own means "select from what this host serves" — with nothing served
    // there is nothing to select, and a lane whose selector is permanently empty is worse than a
    // clear refusal at startup.
    if (cmpBundle == null && androidBundle == null && catalogRefs.isEmpty()) {
      System.err.println(
        "serve: --playground selects a catalog at runtime but no --catalogs are configured, and no " +
          "--playground-bundle is pinned; there is nothing to compile against. Playground disabled."
      )
      return null
    }

    val configuredSandbox = playgroundSandbox.getOrElse { e ->
      System.err.println("serve: ${e.message}. Playground disabled.")
      return null
    }
    val workRoot = java.nio.file.Files.createTempDirectory("compose-playground").toFile()

    // Phase 4 (docs/design/PLAYGROUND.md §6, issue #3016): under --public the playground serves
    // only behind a sandbox that has *demonstrated* containment — the preflight runs a throwaway
    // JVM inside the configured jail and reports whether it can still reach the network, the host
    // filesystem, or host processes. A profile's claims are never enough on their own.
    //
    // Run for ANY active sandbox, not just a public one. Its containment verdict only *gates* the
    // anonymous-public posture, but its can-this-jail-even-launch answer matters everywhere: a
    // token-gated host whose `unshare` is forbidden by the kernel is just as silently broken, and
    // that is what the fallback below repairs. One throwaway JVM at startup buys it.
    val probe =
      if (configuredSandbox.isActive) {
        System.err.println("serve: playground sandbox preflight (${configuredSandbox.describe()})…")
        PlaygroundSandboxProbe.run(
            sandbox = configuredSandbox,
            javaHome = java.io.File(System.getProperty("java.home")),
            classpath =
              System.getProperty("java.class.path")
                .orEmpty()
                .split(java.io.File.pathSeparator)
                .filter { it.isNotBlank() },
            workRoot = workRoot,
          )
          .also { System.err.println("serve: ${it.summary()}") }
      } else null
    // Kept, not just logged: `/status.json` reports which posture admitted the lane, so an
    // operator reading it later doesn't have to find the startup log to tell "admitted because
    // collaborators only" from "admitted because contained".
    val admittedBy: String
    when (
      val decision = PlaygroundPublicGate.decide(public, repoAccessGated, configuredSandbox, probe)
    ) {
      is PlaygroundPublicGate.Decision.Refuse -> {
        System.err.println("serve: ${decision.reason}")
        workRoot.deleteRecursively()
        return null
      }
      is PlaygroundPublicGate.Decision.Allow -> {
        System.err.println("serve: playground admitted — ${decision.detail}")
        admittedBy = decision.detail
      }
    }
    // A configured jail that CANNOT LAUNCH here would otherwise break the lane silently: the gate
    // already admitted it (on repo access, or because the host is token-gated), `/playground`
    // answers normally, and then every snippet JVM and every jailed compile fails to spawn behind
    // an argv that returns EPERM. Drop the jail and keep the caps — `-Xmx`, the CPU cap,
    // ExitOnOutOfMemoryError, the temp-dir confinement and the hard TTL all still apply, which is
    // the half that actually protects the box's memory (see PlaygroundSandbox.droppingJail).
    //
    // Safe by construction for the contained posture: an anonymous --public host whose probe never
    // ran is refused above, so this line is unreachable in the one case where the jail is what
    // admitted the lane.
    // …but NOT for a profile whose caps live in the argv being dropped. `systemd` and `strict`
    // enforce MemoryMax/CPUQuota/TasksMax through the `systemd-run` prefix, so dropping it leaves
    // only `-Xmx` (heap, not native memory) and `-XX:ActiveProcessorCount` (pool sizing, not a CPU
    // quota) — and no pid cap at all. Running an operator who asked for enforceable caps under
    // caps they cannot enforce is worse than not running: refuse, and say which knob to change.
    if (probe != null && !probe.ran && configuredSandbox.profile.declaresResourceCaps) {
      System.err.println(
        "serve: playground sandbox '${configuredSandbox.profile.id}' could not launch on this " +
          "host (${probe.detail}), and its CPU/memory/pid caps are enforced BY that command — " +
          "dropping it would leave the snippet effectively uncapped, so the playground is " +
          "disabled instead. Fix the jail (a container has no systemd to build a transient scope " +
          "against), or pick a profile whose caps are JVM-level (bwrap, unshare)."
      )
      workRoot.deleteRecursively()
      return null
    }
    val sandbox =
      if (probe != null && !probe.ran) {
        System.err.println(
          "serve: WARNING playground sandbox '${configuredSandbox.profile.id}' could not launch on this " +
            "host (${probe.detail}) — dropping the jail and keeping the JVM caps. Snippets run " +
            "capped but UNCONTAINED; the lane is admitted by ${if (public) "repo-access gating" else "the access token"}, not by containment." +
            (if (configuredSandbox.profile == PlaygroundSandbox.Profile.CUSTOM)
              " Any caps that custom argv supplied are gone with it — only the JVM-level ones remain."
            else "")
        )
        configuredSandbox.droppingJail()
      } else configuredSandbox
    // A repo-access-gated lane is admitted without consulting the probe, so a broken jail would
    // otherwise pass unremarked — the operator asked for defence in depth and isn't getting it.
    // Say so; the lane still serves, because admission never rested on the jail here.
    if (repoAccessGated && probe != null && (!probe.ran || probe.failedChecks().isNotEmpty())) {
      System.err.println(
        "serve: WARNING playground sandbox '${sandbox.profile.id}' is configured but did not " +
          "contain the preflight (" +
          (if (!probe.ran) probe.detail else probe.failedChecks().joinToString("; ")) +
          "). The lane serves because it is repo-access-gated, not because it is contained."
      )
    }

    // Each mode's classpath resolves on FIRST USE, not here (issue #3212): a `--playground-bundle
    // compose-m3` names a catalog that `InitialCatalogLoader` fetches in the background *after* the
    // server is up, so resolving at this point would find nothing and disable the mode forever. A
    // local path is deferred the same way, for one code path and one set of log lines.
    val cmpSupplier = cmpBundle?.let { playgroundClasspathSupplier(it, workRoot, "cmp") }
    val androidSupplier = androidBundle?.let {
      playgroundClasspathSupplier(it, workRoot, "android")
    }
    if (cmpSupplier == null && androidSupplier == null && !playgroundRuntimeSelection) {
      // Both configured sources were rejected outright (an unknown system id) — the specific reason
      // is already on stderr from the supplier factory.
      System.err.println("serve: playground has no usable bundle source; playground disabled.")
      return null
    }

    val inProcessCompiler =
      PlaygroundBtaCompiler.fromInstall(java.io.File(workRoot, "bta-ic").toPath())
    // Phase 4's residual (issue #3090): with a sandbox configured, the *compile* runs in the jail
    // too, so a pathological snippet burns a disposable child's CPU/heap budget instead of the
    // serve JVM's. Falls back to the in-process compiler (loudly) when it can't be jailed.
    val compiler = inProcessCompiler?.let {
      val (implJars, pluginJars) =
        PlaygroundBtaCompiler.installJars()
          ?: (emptyList<java.io.File>() to emptyList<java.io.File>())
      PlaygroundJailedCompiler.wrap(
        sandbox = sandbox,
        inProcess = it,
        btaImplJars = implJars,
        compilerPluginJars = pluginJars,
        slots = playgroundCompileSlots,
      )
    }
    if (compiler == null) {
      System.err.println(
        "serve: playground compiler unavailable — no lib-bta/ in the CLI install (run from an " +
          "installed distribution). Playground disabled."
      )
      return null
    }

    // The Android compile classpath plus the Robolectric daemon sidecar back both the live
    // first-frame render (ANDROID mode) and the remote-compose capture (REMOTE_COMPOSE mode). Build
    // the shared daemon opener once; absent the sidecar, both Android lanes stay unavailable while
    // CMP is unaffected. Remote-compose additionally needs the `/d/` document store to publish
    // into.
    //
    // Built for the runtime selector too, not just a pinned Android bundle: with `--playground` any
    // served catalog whose bundle declares `backend=android` is selectable, and whether this host
    // can honour that choice is exactly "did the Robolectric sidecar come up". Cheap to ask (it
    // locates jars and returns a lambda), and asking at startup is what lets the selector omit the
    // Android catalogs instead of offering them and refusing every run.
    val androidDaemonOpener =
      if (androidSupplier != null || playgroundRuntimeSelection)
        buildPlaygroundAndroidDaemonOpener(sandbox)
      else null
    val androidRender = androidDaemonOpener?.let { opener ->
      buildPlaygroundAndroidRenderService(workRoot, opener)
    }
    val rcCapture = androidDaemonOpener?.let { opener ->
      buildPlaygroundRcCaptureService(workRoot, docStore, opener)
    }

    // CMP mode's still first frame renders on the desktop (Skiko) daemon — the backend-agnostic
    // render service (same as Android) over a desktop opener. Absent the desktop sidecar, CMP
    // simply
    // carries no still image; its live `/pg/` redemption still renders on demand.
    val cmpDaemonOpener =
      if (cmpSupplier != null || playgroundRuntimeSelection)
        buildPlaygroundDesktopDaemonOpener(sandbox)
      else null
    val cmpRender = cmpDaemonOpener?.let { opener ->
      buildPlaygroundAndroidRenderService(workRoot, opener)
    }

    // The runtime selector (issue #3215 follow-up). A catalog is offerable once it has published a
    // verified liveBundle whose manifest declares a backend this host can render; the mode set
    // falls
    // straight out of that backend, intersected with the render backends that actually came up
    // above. Everything downstream of the choice — the classpath, the dependencies, the renderer —
    // is the catalog's own, so picking a catalog picks the whole compile target.
    val catalogTargets =
      if (!playgroundRuntimeSelection) null
      else
        PlaygroundCatalogTargets(
          available = {
            catalogLiveBundles.flatMap { (system, bundles) ->
              bundles.mapNotNull { live ->
                live.backend?.let { PlaygroundCatalogAvailable(live.id, system, live.module, it) }
              }
            }
          },
          modesForBackend = { backend ->
            PlaygroundCatalogTargets.naturalModes(backend).filter { mode ->
              when (mode) {
                // CMP compiles and streams without the desktop sidecar (it only adds the still
                // first frame), so a desktop catalog is always offerable.
                PlaygroundMode.CMP -> true
                PlaygroundMode.ANDROID -> androidRender != null
                PlaygroundMode.REMOTE_COMPOSE -> rcCapture != null
              }
            }
          },
          newSupplier = { id ->
            val system = id.substringBefore('@')
            PlaygroundClasspathSupplier(
              source = PlaygroundBundleSource.ServedCatalog(system),
              locateServedBundle = {
                catalogLiveBundles[system]?.firstOrNull { live -> live.id == id }?.file
              },
              resolve = { bundleFile -> resolvePlaygroundClasspath(bundleFile, workRoot, id) },
              onLog = { System.err.println("serve: playground catalog $id: $it") },
            )
          },
          limit = playgroundCatalogLimit,
          onLog = { System.err.println("serve: playground: $it") },
        )

    // Says which modes are WIRED, not which have already resolved a classpath — a served-catalog
    // source resolves on first use, well after this line. A mode whose bundle never materializes
    // answers "mode … is not available" per request and logs why there.
    System.err.println(
      "serve: playground enabled (POST /api/1/compiler/run) — " +
        listOfNotNull(
            cmpSupplier?.let { "cmp✓" },
            cmpRender?.let { "cmp-render✓" },
            androidSupplier?.let { "android✓" },
            androidRender?.let { "android-render✓" },
            rcCapture?.let { "remote-compose✓" },
            catalogTargets?.let { "catalog-selector✓(≤$playgroundCatalogLimit)" },
          )
          .joinToString(" ")
    )

    val snippetCounter = java.util.concurrent.atomic.AtomicLong()
    // Stage 1 (mint) and Stage 2 (redeem) share ONE token store, so a dropped token both deletes
    // its
    // work dir and releases any live session it stood up. onRemove closes over the redeem service —
    // which needs the store — so it's wired through a holder set once both exist below.
    val redeemRef = java.util.concurrent.atomic.AtomicReference<PlaygroundRedeemService?>()
    val tokenStore =
      PlaygroundTokenStore(onRemove = { token -> redeemRef.get()?.release(token.id) })
    val service =
      PlaygroundCompileService(
        catalogClasspath = { mode, catalog ->
          // A named catalog NEVER falls back to the pinned default: quietly compiling against a
          // different design system than the one the request asked for would report success for the
          // wrong thing. Unknown/unloaded/over-budget all route to "not available".
          if (catalog != null) catalogTargets?.classpath(catalog, mode)
          else
            when (mode) {
              PlaygroundMode.CMP -> cmpSupplier?.classpath()
              // Only advertise the Android modes when their daemon backend actually came up —
              // absent the sidecar/android.jar the host would otherwise accept the mode, run a full
              // Android compile, then mint a dead token with no image (ANDROID) / report the
              // preview
              // drew no document (REMOTE_COMPOSE), contradicting the "Android modes disabled"
              // startup log. A null classpath routes to the existing "mode … is not available"
              // response.
              PlaygroundMode.ANDROID ->
                androidSupplier?.classpath()?.takeIf { androidRender != null }
              PlaygroundMode.REMOTE_COMPOSE ->
                androidSupplier?.classpath()?.takeIf { rcCapture != null }
            }
        },
        // Null, not an empty lambda, when the selector is off — the editor tells "no selector here"
        // from "selector configured, nothing loaded yet" by exactly this.
        catalogTargets = catalogTargets?.let { targets -> { targets.targets() } },
        compiler = compiler,
        discoverer = PlaygroundPreviewDiscoverer(),
        tokenStore = tokenStore,
        newWorkDir = {
          java.io
            .File(workRoot, "snippet-${snippetCounter.incrementAndGet()}")
            .absolutePath
            .toPath()
        },
        // The still first frame renders on the mode's daemon: CMP on desktop (Skiko), Android on
        // Robolectric. REMOTE_COMPOSE never reaches this seam (it returns a documentUrl). A null
        // (no
        // sidecar for that mode) just omits the still image; it's never fatal to the run.
        renderFirstFrame = { snippet ->
          when (snippet.mode) {
            PlaygroundMode.CMP -> cmpRender?.render(snippet)
            PlaygroundMode.ANDROID -> androidRender?.render(snippet)
            PlaygroundMode.REMOTE_COMPOSE -> null
          }
        },
        // Which served catalog each pinned mode compiles against, so the browsing surfaces can ask
        // "does this host compile <system>?" and get a true answer on a pin-only host — where the
        // selector reports the pin under the anonymous id `""`. A `--playground-bundle` naming a
        // local file has no system id and answers null, which is correct: nothing on the site can
        // claim to be that bundle's catalog.
        pinnedCatalogSystem = { mode ->
          val source =
            when (mode) {
              PlaygroundMode.CMP -> cmpBundle
              PlaygroundMode.ANDROID,
              PlaygroundMode.REMOTE_COMPOSE -> androidBundle
            }
          (source as? PlaygroundBundleSource.ServedCatalog)?.system
        },
        captureRemoteDocument = { snippet -> rcCapture?.capture(snippet) },
        publishRemoteDocument = { name, bytes, checked ->
          (docStore?.add(name, bytes, isSecurityChecked = checked) as? ServeDocStore.Result.Ok)
            ?.doc
            ?.path
        },
        editLeasesEnabled = playgroundEditing && repoAccessGated,
        editLeaseTtlMillis = playgroundEditLeaseTtlSeconds * 1000,
      )
    if (playgroundEditing && !repoAccessGated) {
      System.err.println(
        "serve: --playground-editing requested without GitHub auth; the authenticated editing " +
          "lease is disabled."
      )
    } else if (service.editLeasesEnabled) {
      System.err.println(
        "serve: playground live editing trial enabled — one authenticated lease, " +
          "${playgroundEditLeaseTtlSeconds}s idle TTL"
      )
    }
    // No mode survived gating (e.g. an Android-only host whose daemon sidecar / android.jar is
    // absent, so every classpath gated to null): don't enable a lane that would render an empty
    // mode selector and mint dead tokens on Run. Disable it, like the no-source case above.
    //
    // Asks whether any mode is *wired*, mirroring the `catalogClasspath` gating above minus the
    // classpath itself — reading `service.availableModes` here would resolve every supplier at
    // startup, which is exactly what a served-catalog source cannot do yet (its catalog loads
    // later). A wired mode whose bundle never materializes answers "not available" per request.
    val wiredModes =
      listOfNotNull(
        cmpSupplier?.let { PlaygroundMode.CMP },
        androidSupplier?.takeIf { androidRender != null }?.let { PlaygroundMode.ANDROID },
        androidSupplier?.takeIf { rcCapture != null }?.let { PlaygroundMode.REMOTE_COMPOSE },
      )
    // A host running the runtime selector legitimately has no *pinned* mode — its modes come from
    // whichever catalog a request names, and no catalog has loaded yet at this point in startup. So
    // this guard only applies to the pinned configuration; the selector's own "nothing offerable"
    // case is a runtime condition (a catalog that never publishes a bundle, an Android-only catalog
    // set on a host with no Robolectric sidecar) and is reported on the page, not here.
    if (wiredModes.isEmpty() && catalogTargets == null) {
      System.err.println(
        "serve: playground resolved no runnable mode (a bundle source is configured but its " +
          "render backend is unavailable); playground disabled."
      )
      return null
    }
    // Stage-2 redemption: stand the snippet's compiled classes up as a live daemon session via the
    // registry, reusing the whole live/stream/input lane. materializePlaygroundSnippet self-gates —
    // it returns null (→ "live preview unavailable") when the mode's daemon backend is absent — so
    // this is always safe to enable alongside the compile lane.
    val redeem =
      PlaygroundRedeemService(
        tokenStore = tokenStore,
        registry = registry,
        materialize = { ServeBundleDaemon.materializePlaygroundSnippet(it, sandbox) },
      )
    redeemRef.set(redeem)

    // A redeemed /pg session lives in ServeSessionRegistry and is reached only via the viewer + WS
    // lanes, which never touch the token store — so the store's lazy purge (driven from mint / get
    // /
    // snapshot) would never fire onRemove for it, and the session (plus its work dir) would outlive
    // the token's TTL indefinitely. Sweep expired tokens on a timer so a redeemed session is torn
    // down at (roughly) its deadline even with no further playground requests.
    val purgePeriod = tokenStore.ttlSeconds.coerceIn(15L, 60L)
    java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "playground-token-purge").apply { isDaemon = true }
      }
      .scheduleWithFixedDelay(
        { runCatching { tokenStore.purgeExpired() } },
        purgePeriod,
        purgePeriod,
        java.util.concurrent.TimeUnit.SECONDS,
      )

    // Everything an operator needs to diagnose a half-up playground from `/status.json` — the
    // admission posture, whether the configured jail actually contains anything HERE, and each
    // mode's lazy-resolution state. Captured as a lambda so the mode rows are read fresh (a
    // deferred classpath resolves minutes after this point) while staying side-effect free:
    // `isResolved` reports the memo without forcing a resolve onto the status request path.
    val health = {
      PlaygroundHealth(
        admittedBy = admittedBy,
        sandboxProfile = sandbox.profile.id,
        sandboxActive = sandbox.isActive,
        jailDropped = sandbox.jailDropped,
        sandboxMemoryMb = sandbox.memoryMb,
        sandboxCpus = sandbox.cpus,
        sandboxTtlSeconds = sandbox.ttlSeconds,
        probe = probe,
        compilerJailed = compiler !== inProcessCompiler && !sandbox.jailDropped,
        compileSlots = playgroundCompileSlots,
        modes = {
          listOfNotNull(
            cmpSupplier?.let {
              PlaygroundHealth.Mode(PlaygroundMode.CMP.name, it.describeSource(), it.isResolved)
            },
            androidSupplier
              ?.takeIf { androidRender != null }
              ?.let {
                PlaygroundHealth.Mode(
                  PlaygroundMode.ANDROID.name,
                  it.describeSource(),
                  it.isResolved,
                )
              },
            androidSupplier
              ?.takeIf { rcCapture != null }
              ?.let {
                PlaygroundHealth.Mode(
                  PlaygroundMode.REMOTE_COMPOSE.name,
                  it.describeSource(),
                  it.isResolved,
                )
              },
          )
        },
        catalogSelector =
          catalogTargets?.let { targets ->
            {
              PlaygroundHealth.CatalogSelector(
                offered = targets.targets().map { it.system },
                resolved = targets.resolvedCount(),
                limit = playgroundCatalogLimit,
              )
            }
          },
        editing = { service.editLeaseHealth() },
      )
    }
    return PlaygroundLane(compile = service, redeem = redeem, health = health)
  }

  /**
   * The per-caller compile budget, or null when `--playground-rate-limit 0` turned it off.
   *
   * Only the **compile** lane is metered, not `/pg/` redemption: a redemption is only reachable
   * with a token a compile just minted, so limiting compiles transitively limits it — and
   * redemption already answers to the live-seat budget, the token store's cap, and the token TTL.
   * Metering it twice would refuse a caller the preview they already paid for.
   */
  private fun buildPlaygroundRateLimiter(): ServeRateLimiter? {
    if (playgroundRateLimit <= 0) {
      System.err.println(
        "serve: WARNING playground compile lane is UNMETERED (--playground-rate-limit 0). Its " +
          "remaining bounds are all whole-host ones, so one caller can hold every compile slot."
      )
      return null
    }
    System.err.println(
      "serve: playground compile budget — $playgroundRateLimit/min per caller, " +
        "$playgroundCallerConcurrency concurrent" +
        (if (trustForwardedFor) ", keyed by the last X-Forwarded-For entry when anonymous" else "")
    )
    return ServeRateLimiter(
      permitsPerWindow = playgroundRateLimit,
      windowSeconds = 60,
      maxConcurrent = playgroundCallerConcurrency,
    )
  }

  /** The playground's Stage-1 compile lane + Stage-2 redeem lane, sharing one token store. */
  private class PlaygroundLane(
    val compile: PlaygroundCompileService,
    val redeem: PlaygroundRedeemService,
    /** Read by `/status.json` to report why the lane is (or isn't) fully up. */
    val health: () -> PlaygroundHealth,
  )

  /**
   * Build the lazy classpath supplier for one playground mode from its `--playground-bundle` /
   * `--playground-android-bundle` value, or null (having said why) when the value can't name a
   * bundle at all.
   *
   * The only failure decided *here* is an unknown served-catalog id: naming a system this box
   * doesn't serve is a config error the operator should hear about at startup, with the list of
   * what is configured, rather than as a mode that quietly never works. Everything else — a path
   * that doesn't exist, a bundle that won't resolve, a catalog that hasn't loaded yet — is deferred
   * to [PlaygroundClasspathSupplier], because at this point in startup the catalogs have not been
   * fetched (issue #3212).
   */
  private fun playgroundClasspathSupplier(
    raw: String,
    workRoot: java.io.File,
    mode: String,
  ): PlaygroundClasspathSupplier? {
    val source = PlaygroundBundleSource.parse(raw)
    if (source is PlaygroundBundleSource.ServedCatalog) {
      // Compared against the CONFIGURED catalog set, not the loaded one: nothing has loaded yet.
      val configured = catalogRefs.map { it.system }
      if (source.system !in configured) {
        System.err.println(
          "serve: playground $mode bundle '${source.system}' is neither a readable file nor a " +
            "catalog this server is configured to serve" +
            (if (configured.isEmpty()) " (no --catalogs configured)"
            else " (configured: ${configured.sorted().joinToString(", ")})") +
            ". That mode is disabled — pass a .bundle path, or a served system id."
        )
        return null
      }
    }
    return PlaygroundClasspathSupplier(
      source = source,
      locateServedBundle = { catalogLiveBundles[it]?.firstOrNull()?.file },
      resolve = { bundleFile -> resolvePlaygroundClasspath(bundleFile, workRoot, mode) },
      onLog = { System.err.println("serve: playground $mode — $it") },
    )
  }

  /**
   * Unpack [bundleFile] into `<workRoot>/catalog-<label>` and resolve its compile classpath,
   * logging either outcome. Shared by the pinned `--playground-bundle` suppliers (where [label] is
   * the mode, `cmp`/`android`) and the runtime selector's per-catalog suppliers (where it is the
   * system id), so both pay the same resolve and report it the same way.
   *
   * `--extra-maven-repos` is honoured here as it is on the live-daemon path: the resolver fails
   * **closed** on an unresolved coordinate, so a catalog whose module pulls a dependency from a
   * non-default repo would otherwise be unusable in the playground while rendering fine live — and
   * the runtime selector puts exactly those catalogs one click away.
   */
  private fun resolvePlaygroundClasspath(
    bundleFile: java.io.File,
    workRoot: java.io.File,
    label: String,
  ): PlaygroundCompileService.Classpath? =
    PlaygroundCatalogClasspath.resolve(
        bundleFile = bundleFile,
        destDir = java.io.File(workRoot, "catalog-$label"),
        system = "playground-$label",
        extraMavenRepos = extraMavenRepos,
        onLog = { System.err.println("serve playground: $it") },
      )
      .also {
        if (it == null) {
          System.err.println(
            "serve: playground could not resolve a $label classpath from " +
              "${bundleFile.absolutePath}; that target is unavailable."
          )
        } else {
          System.err.println(
            "serve: playground $label classpath resolved from ${bundleFile.absolutePath}"
          )
        }
      }

  /**
   * Resolve the Android/Robolectric daemon opener shared by the playground's Android render lanes —
   * the `lib-daemon-android` sidecar + `android.jar` on the daemon classpath, the Robolectric
   * jvmArgs/sysprops, and a subprocess `openBundleDaemon`. Mirrors [ServeBundleDaemon]'s
   * `androidBundleDaemonLaunch`. Returns null (logging why) when the sidecar or `android.jar` is
   * missing — both Android lanes then report unavailable rather than compiling to a dead end.
   */
  private fun buildPlaygroundAndroidDaemonOpener(
    sandbox: PlaygroundSandbox
  ): PlaygroundAndroidSessionOpener? {
    val daemonJars = locateBundleSidecarJars("lib-daemon-android")
    if (daemonJars.isEmpty()) {
      System.err.println(
        "serve: playground Android modes need the Android daemon sidecar " +
          "(lib-daemon-android/), which ships separately as " +
          "compose-preview-android-daemon-<version>.zip; unpack it and set " +
          "-Dcomposeai.cli.libDaemonAndroidDir=<dir>/lib-daemon-android. Android modes disabled."
      )
      return null
    }
    val androidJar =
      AndroidBundleLaunch.resolveAndroidJar(localPropertiesFile = null)
        ?: run {
          System.err.println(
            "serve: playground Android modes need android.jar — set ANDROID_HOME / " +
              "ANDROID_SDK_ROOT. Android modes disabled."
          )
          return null
        }
    val launch = AndroidBundleLaunch()
    val daemonClasspath = (daemonJars + listOf(androidJar)).map { it.absolutePath }
    val jvmArgs = launch.jvmArgs()
    val sysprops = sandbox.robolectricSystemProperties(launch.robolectricSystemProperties())
    return { classesDir, previewsJson, workspaceRoot, userClasspath ->
      openPlaygroundFirstFrameDaemon(
        daemonClasspath,
        jvmArgs,
        sysprops,
        classesDir,
        previewsJson,
        workspaceRoot,
        userClasspath,
        sandbox,
      )
    }
  }

  /**
   * Open a bundle-less daemon for a first-frame render, partitioning the snippet's [userClasspath]
   * the way the live path ([ServeBundleDaemon.materializePlaygroundSnippet]) does: jars in the
   * namespaces `UserClassLoaderHolder` delegates to the parent (`androidx.*`, `kotlinx-coroutines`,
   * `kotlinx-io`) must precede the [sidecarClasspath] on the daemon (parent) `-cp`, or the daemon
   * loads its own sidecar versions and a snippet built against the catalog's newer shared ABI fails
   * with `NoSuchMethodError`/`NoSuchFieldError` (and the render service then silently returns no
   * image). The snippet's own classes stay isolated on the child (user) loader.
   */
  private fun openPlaygroundFirstFrameDaemon(
    sidecarClasspath: List<String>,
    jvmArgs: List<String>,
    extraSystemProperties: Map<String, String>,
    classesDir: java.io.File,
    previewsJson: java.io.File,
    workspaceRoot: java.io.File,
    userClasspath: List<String>,
    sandbox: PlaygroundSandbox,
  ) =
    SubprocessRenderSessions.openBundleDaemon(
      daemonClasspath =
        userClasspath.filter { ServeBundleDaemon.jarPrecedesDaemonSidecar(java.io.File(it)) } +
          sidecarClasspath,
      classesDir = classesDir,
      previewsJson = previewsJson,
      workspaceRoot = workspaceRoot,
      modulePath = ":playground",
      // The sandbox's JVM caps come last so they win over the backend defaults.
      jvmArgs = jvmArgs + sandbox.jvmArgs(workspaceRoot),
      extraSystemProperties = extraSystemProperties,
      userClasspath =
        userClasspath.filterNot { ServeBundleDaemon.jarPrecedesDaemonSidecar(java.io.File(it)) },
      // Stage-1's first frame and the RC capture run a stranger's snippet exactly as the live lane
      // does, so they are jailed identically — one JVM per snippet, killed at the hard TTL.
      jailCommand =
        sandbox.command(
          PlaygroundSandbox.Paths(
            workDir = workspaceRoot,
            readOnly =
              (sidecarClasspath + userClasspath).map { java.io.File(it) }.distinct() +
                classesDir +
                previewsJson,
            javaHome = java.io.File(System.getProperty("java.home")),
          )
        ),
      hardTtlSeconds = sandbox.ttlSeconds.takeIf { sandbox.isActive },
    )

  /**
   * The desktop (CMP/Skiko) daemon opener for the playground's CMP first-frame render — the
   * `lib-daemon-desktop` + `lib-renderer` sidecar on the daemon classpath and the desktop jvmArgs,
   * over a subprocess `openBundleDaemon`. Mirrors [ServeBundleDaemon]'s `desktopBundleDaemonLaunch`
   * (the desktop twin of [buildPlaygroundAndroidDaemonOpener]). Returns null (logging why) when the
   * sidecar jars are absent — CMP then simply carries no still first frame while its live `/pg/`
   * redemption keeps rendering on demand.
   */
  private fun buildPlaygroundDesktopDaemonOpener(
    sandbox: PlaygroundSandbox
  ): PlaygroundAndroidSessionOpener? {
    val daemonJars = locateBundleSidecarJars("lib-daemon-desktop")
    val rendererJars = locateBundleSidecarJars("lib-renderer")
    if (daemonJars.isEmpty() || rendererJars.isEmpty()) {
      System.err.println(
        "serve: playground CMP first-frame needs the desktop daemon sidecar (lib-daemon-desktop/ + " +
          "lib-renderer/) from an installed distribution; CMP renders no still frame (its live " +
          "preview still works)."
      )
      return null
    }
    val daemonClasspath = (daemonJars + rendererJars).map { it.absolutePath }
    // -Dapple.awt.UIElement=true keeps the desktop JVM a macOS background agent (no Dock/focus
    // steal); mirrors desktopBundleDaemonLaunch. No Robolectric sysprops on the desktop backend.
    val jvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Dapple.awt.UIElement=true")
    return { classesDir, previewsJson, workspaceRoot, userClasspath ->
      openPlaygroundFirstFrameDaemon(
        daemonClasspath,
        jvmArgs,
        emptyMap(),
        classesDir,
        previewsJson,
        workspaceRoot,
        userClasspath,
        sandbox,
      )
    }
  }

  /**
   * The playground's first-frame render backend: renders a compiled snippet on the shared [opener]
   * and returns the still PNG the Stage-1 response surfaces as its `image`. Backend-agnostic — the
   * [opener] selects desktop (CMP) or Robolectric (Android); this wires it for both modes.
   */
  private fun buildPlaygroundAndroidRenderService(
    workRoot: java.io.File,
    opener: PlaygroundAndroidSessionOpener,
  ): PlaygroundAndroidRenderService {
    val renderCounter = java.util.concurrent.atomic.AtomicLong()
    return PlaygroundAndroidRenderService(
      openSession = opener,
      newWorkDir = { java.io.File(workRoot, "android-render-${renderCounter.incrementAndGet()}") },
    )
  }

  /**
   * The playground's remote-compose capture backend (REMOTE_COMPOSE mode): renders a compiled
   * snippet on the shared [opener] and captures its `.rc` document. Returns null (logging why) when
   * the `/d/` document store is missing — remote-compose then reports unavailable rather than
   * compiling to a dead end.
   */
  private fun buildPlaygroundRcCaptureService(
    workRoot: java.io.File,
    docStore: ServeDocStore?,
    opener: PlaygroundAndroidSessionOpener,
  ): PlaygroundRcCaptureService? {
    if (docStore == null) {
      System.err.println(
        "serve: playground remote-compose mode needs the /d/ document store — enable it with " +
          "--accept-docs. Remote-compose mode disabled."
      )
      return null
    }
    val captureCounter = java.util.concurrent.atomic.AtomicLong()
    return PlaygroundRcCaptureService(
      openSession = opener,
      newWorkDir = { java.io.File(workRoot, "rc-capture-${captureCounter.incrementAndGet()}") },
    )
  }

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
      trust = { trustStore.get() },
    )
  }

  /**
   * The in-browser Wasm apps this server exposes: the ones carried by the served catalogs, plus the
   * explicit `--wasm-dir` overrides (which win, so an operator can serve a local build in place of
   * a catalog's published app).
   *
   * Returns the registration's **live** map rather than a merged copy, so the set tracks runtime
   * catalog changes: publish a Wasm-carrying catalog through the admin API and its
   * `/wasm/<system>/` route works immediately; retire one and its assets stop being served. A
   * snapshot here was the bug — the server would have been stuck with the boot-time set.
   */
  private fun mergedWasmCatalogs(reg: CatalogRegistration?): MutableMap<String, File> {
    val live = reg?.wasm ?: java.util.concurrent.ConcurrentHashMap()
    live.putAll(localWasm)
    return live
  }

  /**
   * The usable `--wasm-dir` overrides, resolved once: they're the operator's explicit choice, so
   * they win over a catalog's published app and must not be re-checked (or re-warned about) on
   * every catalog refresh.
   */
  private val localWasm: Map<String, File> by lazy { filterLocalWasm() }

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
   * Find conventional executable CMP/Wasm browser projects and associate them with the preview
   * modules they depend on. This covers the usual split (`:shared:ui` plus `:webApp`) while also
   * supporting a preview module that owns its own Wasm executable. Missing distributions are built
   * with the standard Kotlin task; failure is deliberately non-fatal because snapshots remain a
   * complete degraded browser.
   */
  private fun automaticWasmCatalogs(modules: List<PreviewModule>): Map<String, File> {
    val root = findProjectRoot() ?: return emptyMap()
    var gradleProjects = emptyList<PreviewModule>()
    withGradle { gradle -> gradleProjects = gradle.findGradleProjects(timeoutSeconds) }
    val projects = discoverWasmProjects(root, gradleProjects)
    if (projects.isEmpty()) return emptyMap()

    val assignments = modules.mapNotNull { module ->
      val directMatches = projects.filter { it.supports(module) }
      // Convention plugins can hide the dependency declaration from this project's build script.
      // A one-preview-module / one-Wasm-app build is still unambiguous, so keep that common shape
      // zero-config too.
      val matches =
        if (directMatches.isEmpty() && modules.size == 1 && projects.size == 1) projects
        else directMatches
      val selected =
        matches
          .sortedWith(
            compareByDescending<AutomaticWasmProject> { it.distribution() != null }
              .thenBy { it.gradlePath }
          )
          .firstOrNull() ?: return@mapNotNull null
      if (matches.size > 1) {
        System.err.println(
          "browse: several Wasm apps depend on ${module.gradlePath}; using ${selected.gradlePath}."
        )
      }
      module.gradlePath to selected
    }

    assignments
      .map { it.second }
      .distinctBy { it.gradlePath }
      .filter { it.distribution() == null }
      .forEach { project ->
        System.err.println("browse: building CMP Wasm app ${project.gradlePath}…")
        var ok = false
        withGradle { gradle ->
          ok =
            runGradle(
              gradle,
              ":${project.gradlePath}:wasmJsBrowserDistribution",
              arguments = gradleArgsWithForce(),
            )
        }
        if (!ok) {
          System.err.println(
            "browse: ${project.gradlePath} has no usable Wasm browser distribution; using snapshots."
          )
        }
      }

    return assignments
      .mapNotNull { (module, project) -> project.distribution()?.let { module to it } }
      .toMap()
  }

  internal data class AutomaticWasmProject(
    val gradlePath: String,
    val projectDir: File,
    val buildScript: String,
  ) {
    fun supports(module: PreviewModule): Boolean {
      if (projectDir.canonicalFile == module.projectDir.canonicalFile) return true
      val path = module.gradlePath.removePrefix(":")
      val colonPath = ":$path"
      val typeSafeAccessor =
        "projects." +
          path.split(':').joinToString(".") { segment ->
            segment.split('-', '_').let { words ->
              words.first() +
                words.drop(1).joinToString("") { word -> word.replaceFirstChar { it.uppercase() } }
            }
          }
      return buildScript.contains("project(\"$colonPath\")") ||
        buildScript.contains("project('$colonPath')") ||
        buildScript.contains(typeSafeAccessor) ||
        Regex("project\\s*\\(\\s*path\\s*=\\s*[\\\"']${Regex.escape(colonPath)}[\\\"']")
          .containsMatchIn(buildScript)
    }

    fun distribution(): File? =
      listOf(
          File(projectDir, "build/dist/wasmJs/productionExecutable"),
          File(projectDir, "build/wasmDist"),
          File(projectDir, "build/dist/wasmJs/developmentExecutable"),
        )
        .firstOrNull { File(it, "index.html").isFile && File(it, COMPONENT_PROTOCOL_MARKER).isFile }
  }

  internal fun discoverWasmProjects(
    root: File,
    gradleProjects: List<PreviewModule> = emptyList(),
  ): List<AutomaticWasmProject> =
    root
      .walkTopDown()
      .onEnter { it == root || (it.name != "build" && it.name != ".gradle" && it.name != ".git") }
      .filter { it.isFile && (it.name == "build.gradle.kts" || it.name == "build.gradle") }
      .mapNotNull { script ->
        val text = runCatching { script.readText() }.getOrNull() ?: return@mapNotNull null
        val dir = script.parentFile
        val hasDistribution =
          listOf(
              File(dir, "build/dist/wasmJs/productionExecutable/index.html"),
              File(dir, "build/wasmDist/index.html"),
              File(dir, "build/dist/wasmJs/developmentExecutable/index.html"),
            )
            .any { it.isFile }
        if (
          !hasDistribution && (!text.contains("wasmJs") || !text.contains("binaries.executable"))
        ) {
          return@mapNotNull null
        }
        val configuredProject = gradleProjects.firstOrNull {
          it.projectDir.canonicalFile == dir.canonicalFile
        }
        val relative = dir.relativeTo(root).invariantSeparatorsPath
        val path =
          configuredProject?.gradlePath
            ?: relative.split('/').filter { it.isNotEmpty() }.joinToString(":")
        if (path.isEmpty()) null else AutomaticWasmProject(path, dir, text)
      }
      .toList()

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
    /** Live (see [mergedWasmCatalogs]) so a runtime catalog's Wasm app is added/removed with it. */
    wasmCatalogs: MutableMap<String, File>,
    /** Auto-discovered local apps whose compiled project assets stay behind the session token. */
    privateWasmCatalogs: Set<String> = emptySet(),
    bannerLabel: String,
    bannerPreviewCount: Int,
    mdnsModuleLabel: String?,
    mdnsPreviewIds: List<String>?,
    closeables: List<AutoCloseable?>,
    catalogLoads: CatalogLoadTracker?,
    /** Local project sessions to list on the component-browser front door. */
    localCatalogSessions: List<String> = emptyList(),
    /** Module roots used to serve source for local component-browser sessions. */
    localSourceRoots: Map<String, File> = emptyMap(),
    /** The catalog store an admin registration fetches through; null ⇒ no runtime admin. */
    catalogStore: ServeCatalogStore? = null,
    /** Immediate branch-head check used by the Refresh control on catalog landing pages. */
    catalogRefresh: ((String) -> CatalogRefreshResult)? = null,
    /** Project mode's local-git render history; null on a box with no checkout to read. */
    projectHistory: ServeProjectHistory? = null,
    /** Called immediately after the HTTP listener binds, before the long blocking wait. */
    onStarted: () -> Unit = {},
  ) {
    val configuredCatalogs =
      localCatalogSessions +
        (catalogLoads?.snapshot()?.filter { it.config.listed }?.map { it.config.system }
          ?: registeredCatalogs.toList())
    val configuredApps =
      catalogLoads?.snapshot()?.filter { !it.config.listed }?.map { it.config.system }
        ?: registeredUnlistedCatalogs.toList()
    // Top-level sites: `catalogs.json`'s `sites` first (the operator config that lives beside the
    // catalog set), then any `--sites` flag entries for a host the file didn't already claim — the
    // same compose-don't-replace rule `--catalogs` follows. A site naming a system this server does
    // not serve is dropped with a startup warning rather than 404ing a whole hostname silently.
    //
    // Held in a [ServeSiteRegistry] rather than as a value, because `/admin/sites` publishes onto
    // the running server: the startup map below is the seed, not the whole story.
    val sites =
      ServeSiteRegistry(
        ServeSites.of(
          catalogsConfig.sites.map { it.host to it.system } +
            ServeSites.parse(sitesRaw, onProblem = { System.err.println("serve: $it") }).let {
              flagSites ->
              flagSites.hosts.map { it to flagSites.systemFor(it)!! }
            },
          knownSystems = (configuredCatalogs + configuredApps).toSet(),
          onProblem = { System.err.println("serve: $it") },
        )
      )
    // Runtime catalog administration: only when the operator supplied an admin token AND there's a
    // catalog store to fetch through. Both halves are opt-in, so a plain `serve` has no admin
    // surface at all.
    val catalogAdmin =
      if (adminToken != null && catalogStore != null && catalogLoads != null) {
        buildCatalogAdmin(registry, catalogStore, catalogLoads, wasmCatalogs, sites)
      } else {
        null
      }
    // Runtime site administration. Needs only the admin token and the live map: publishing a
    // hostname adds no catalog and fetches nothing, it re-points an existing one. What it does need
    // is the CURRENT served set, read through the tracker rather than captured here, so a site may
    // name a catalog that was itself published at runtime a moment earlier — which is exactly the
    // order a config reconcile applies them in.
    val siteAdmin =
      if (adminToken != null) {
        ServeSiteAdmin(
          registry = sites,
          servedSystems = {
            catalogLoads?.snapshot()?.map { it.config.system }?.toSet()
              ?: (configuredCatalogs + configuredApps).toSet()
          },
          configFile = catalogsFile,
        )
      } else {
        null
      }
    // Runtime producer-trust administration. Needs only the admin token: unlike the catalog admin
    // there's nothing to fetch, and a box with no trust store yet is exactly the one that most
    // needs
    // to be able to add its first producer without an image rebuild.
    val trustAdmin =
      if (adminToken != null) {
        ServeTrustAdmin(
          store = trustStore,
          file = trustStoreFile,
          // Revoking trust must retire what that trust was already buying. Each affected catalog's
          // session (and its live daemon, via unregister) is dropped and its tracker row marked
          // failed, so the branch refresher re-fetches it and it comes back re-verified — as
          // `unverified`, serving baked data tiers only, instead of keeping a stale Trusted
          // verdict.
          onRevoke = { updated -> retireNewlyUntrusted(updated, catalogLoads, registry) },
        )
      } else {
        null
      }
    // Resolved once so the playground's remote-compose lane publishes into the SAME store the `/d/`
    // route serves from — otherwise a minted `/d/<id>` link wouldn't resolve.
    val docStore = openDocStore()
    // Built BEFORE the playground lane: whether GitHub auth is configured is one of the two bases
    // the `--public` admission gate decides on (issue #3210), because it is what makes the routes'
    // repo-access check a real check instead of a no-op.
    val githubAuth = buildGithubAuth()
    val playgroundLane =
      openPlaygroundService(docStore, registry, repoAccessGated = githubAuth != null)
    val catalogFeed =
      if (catalogLoads != null && catalogFeedIdleSeconds > 0) {
        ServeCatalogChangeFeed(
          entries = { catalogLoads.snapshot().map { it.config } },
          cacheRoot = catalogFeedCacheDir,
          idleTimeoutMillis = catalogFeedIdleSeconds * 1000,
          // Feed polling is demand-gated, but while active it follows the same operational cadence
          // as catalog refresh. If ordinary refresh is disabled, retain the normal ten-minute feed
          // cadence: a subscribed feed is itself an explicit request to watch this branch.
          pollIntervalMillis =
            (catalogRefreshSeconds.takeIf { it > 0 } ?: DEFAULT_CATALOG_REFRESH_SECONDS) * 1000,
        )
      } else {
        null
      }
    val server =
      ServeHttpServer(
        host = host,
        requestedPort = requestedPort,
        token = token,
        sessions = registry,
        defaultSessionId = defaultSessionId,
        bundleStore = bundleStore,
        isPublic = public,
        componentBrowser = componentBrowser,
        wasmCatalogs = wasmCatalogs,
        privateWasmCatalogs = privateWasmCatalogs,
        rcPlayerWasmDir = rcPlayerWasmDir,
        // Preserve the CONFIGURED set, not only startup successes. Failed rows then stay visible on
        // /status, and a catalog recovered by the refresher appears on the home index immediately.
        catalogSessions = configuredCatalogs,
        appCatalogSessions = configuredApps,
        sites = sites,
        catalogLoads = catalogLoads,
        catalogRefresh = catalogRefresh,
        catalogFeed = catalogFeed,
        maxLiveSeats = liveSeats,
        liveSeatLimiter = liveSeatLimiter,
        daemonLog = daemonLog,
        allowRenderTrusted = allowRenderTrusted,
        trustStoreConfigured = trustStorePath != null,
        catalogRefreshSeconds = catalogRefreshSeconds,
        acceptBundlesEnabled = acceptBundles,
        catalogAdmin = catalogAdmin,
        siteAdmin = siteAdmin,
        trustAdmin = trustAdmin,
        adminToken = adminToken,
        docStore = docStore,
        playgroundService = playgroundLane?.compile,
        playgroundHealth = playgroundLane?.health,
        branchFetchStats = catalogStore?.let { store -> { store.branchFetchStats.snapshot() } },
        themeOptimizerStats = { backgroundWork.optimizerAdmissionSnapshot() },
        themeCacheStats = { themeCacheStore?.snapshot() },
        themeOptimizerAdmin = backgroundWork,
        playgroundRedeem = playgroundLane?.redeem,
        githubAuth = githubAuth,
        playgroundRateLimiter = playgroundLane?.let { buildPlaygroundRateLimiter() },
        // Reads a served preview's Kotlin, for two consumers with different requirements:
        // `/playground?from=<system>/<previewId>` (needs a playground to open it in) and the
        // viewer's Source panel (does not — it only shows the code).
        //
        // So this is wired unconditionally. It used to hang off `playgroundLane`, which was right
        // while the playground was the only consumer and became wrong the moment the Source panel
        // arrived: on a host with no playground the fetcher was null, the resolver with it, and
        // every viewer silently dropped the Source chip. Whether a *link* to the editor is offered
        // is decided separately, by `playgroundLinkFor`.
        playgroundSourceFetch = { url: String -> PlaygroundSeedResolver.httpFetch(url) },
        trustForwardedFor = trustForwardedFor,
        engagementStore = ServeEngagementStore(engagementFile),
        projectHistory = projectHistory,
        localSourceRoots = localSourceRoots,
      )
    if (trustAdmin != null) {
      System.err.println(
        "serve: trust admin API enabled at /admin/trust" +
          (trustStoreFile?.let { " (persisting to ${it.displayPath})" }
            ?: " (runtime only — pass --trust-store to persist)")
      )
    }
    if (catalogAdmin != null) {
      System.err.println(
        "serve: catalog admin API enabled at /admin/catalogs" +
          (catalogsFile?.let { " (persisting to ${it.displayPath})" }
            ?: " (runtime only — pass --catalogs-file to persist)")
      )
    }
    if (siteAdmin != null) {
      System.err.println(
        "serve: site admin API enabled at /admin/sites" +
          (catalogsFile?.let { " (persisting to ${it.displayPath})" }
            ?: " (runtime only — pass --catalogs-file to persist)")
      )
    }
    if (githubAuth != null) {
      System.err.println(
        "serve: GitHub auth enabled for live sessions and playground" +
          (githubAuthUsers.takeIf { it.isNotEmpty() }?.let { " (${it.size} allowed user(s))" }
            ?: "")
      )
    }

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
          runCatching { catalogFeed?.close() }
          runCatching { registry.close() }
          closeables.forEach { c -> runCatching { c?.close() } }
          done.countDown()
        }
      )

    server.start()
    onStarted()
    printBanner(bannerLabel, server.port, token, bannerPreviewCount)
    if (openBrowser) openBrowser(server.port, token)
    val watchdog = if (exitWhenIdle) startIdleWatchdog(registry, done) else null
    done.await()
    watchdog?.shutdownNow()
  }

  private fun openBrowser(port: Int, token: String) {
    val localHost =
      if (ServeUrls.isExposed(host) || host == ServeUrls.LOOPBACK) ServeUrls.LOOPBACK else host
    val url =
      if (public) "${ServeUrls.origin(localHost, port)}/"
      else ServeUrls.landingUrl(ServeUrls.origin(localHost, port), token)
    val opened = runCatching {
      if (!Desktop.isDesktopSupported()) return@runCatching false
      val desktop = Desktop.getDesktop()
      if (!desktop.isSupported(Desktop.Action.BROWSE)) return@runCatching false
      desktop.browse(URI(url))
      true
    }
      .getOrDefault(false)
    if (!opened) {
      System.err.println("browse: could not open a desktop browser; open the Local URL above.")
    }
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

  /**
   * The repository the served module lives in — the root every project-mode git surface works from
   * (worktrees, the revision factory, the render-history timeline). Falls back to the module's
   * parent directory when the project root can't be identified, which is what the git calls
   * themselves will then fail against, harmlessly.
   */
  private fun projectRepoRoot(module: PreviewModule): File =
    findProjectRoot() ?: module.projectDir.absoluteFile.parentFile ?: module.projectDir

  /** Open the worktree manager rooted at the repo (project mode), gated to the allowed refs. */
  private fun openWorktrees(module: PreviewModule, rootOverride: File? = null): GitWorktrees {
    val repoRoot = rootOverride ?: projectRepoRoot(module)
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
    val repoRoot = projectRepoRoot(module)
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
        trust = { trustStore.get() },
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
        origins.firstOrNull { trustStore.get().trustsBranch(it.repo, it.branch) }
          ?: origins.firstOrNull()
      val bundleFile = File(root, "${spec.name}.bundle")
      try {
        bundleFile.writeBytes(bytes)
      } catch (e: Exception) {
        System.err.println("serve: bundle ${spec.name} could not be staged (${e.message})")
        continue
      }
      val verdict = BundleVerifier.verify(bundleFile, trustStore.get(), origin)
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
   *
   * [registerCatalogs] result: wasm-app dirs, the store a refresher re-loads from, and the
   * configured/load state exposed through status.
   */
  private class CatalogRegistration(
    /**
     * The in-browser Wasm apps carried by the served catalogs, **live**: the server reads this same
     * map, so a catalog published at runtime gets its `/wasm/<system>/` route (and its viewer
     * toggle) as soon as its branch is fetched, and a retired one stops serving stale assets. A
     * plain snapshot would have frozen the boot-time set.
     */
    val wasm: MutableMap<String, File>,
    val store: ServeCatalogStore,
    val loads: CatalogLoadTracker,
    val loader: InitialCatalogLoader,
  )

  private inner class InitialCatalogLoader(
    private val store: ServeCatalogStore,
    private val loads: CatalogLoadTracker,
  ) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { r ->
      Thread(r, "serve-catalog-initial-load").apply { isDaemon = true }
    }
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
      // Claimed at construction rather than at [start], so the window between the listener binding
      // and the first catalog load isn't a gap the theme optimizer can start in.
      backgroundWork.expectInitialCatalogLoad()
    }

    fun start(onComplete: (Set<String>) -> Unit = {}) {
      if (!started.compareAndSet(false, true)) return
      executor.execute {
        val loaded = linkedSetOf<String>()
        try {
          // Fetch order, not front-page order: a box's load-bearing catalogs come back first
          // after a restart even though their cards stay where the operator put them (#4231).
          for (seed in loads.loadOrder().map { it.config }) {
            if (closed.get()) return@execute
            val (config, result) =
              synchronized(catalogRegistrationLock) {
                val current = loads.configFor(seed.system) ?: return@synchronized null
                val result = runCatching {
                  store.load(current.system, sourceRepo = current.repo)
                }
                  .getOrElse {
                    ServeCatalogStore.Result.Failed(
                      current.system,
                      it.message ?: it::class.simpleName ?: "load failed",
                    )
                  }
                loads.record(result)
                current to result
              } ?: continue
            when (val r = result) {
              is ServeCatalogStore.Result.Ok -> {
                if (config.listed) registeredCatalogs += r.system
                else registeredUnlistedCatalogs += r.system
                loaded += r.system
                System.err.println(
                  "serve: catalog ${r.system} → ${r.previewCount} preview(s), trust=${r.trust} " +
                    "(/${r.system}/${if (config.listed) "" else ", unlisted"})"
                )
              }
              is ServeCatalogStore.Result.Failed ->
                System.err.println("serve: catalog ${r.system} not served: ${r.reason}")
            }
          }
          System.err.println("serve: ${loads.startupSummary()}")
        } finally {
          // However the pass ended — loaded, failed, or shut down mid-pass — background catalog
          // work is free to start; leaving it claimed would park the optimizer forever.
          backgroundWork.initialCatalogLoadFinished()
          sweepThemeCache()
          sweepCatalogBlobs(force = true)
          if (!closed.get()) onComplete(loaded)
        }
      }
    }

    override fun close() {
      closed.set(true)
      backgroundWork.initialCatalogLoadFinished()
      executor.shutdownNow()
    }
  }

  private fun registerCatalogs(
    registry: ServeSessionRegistry,
    worktrees: GitWorktrees?,
    openHost: (ServeSessionState) -> ServeHost?,
  ): CatalogRegistration {
    val dir =
      java.nio.file.Files.createTempDirectory("serve-catalogs").toFile().also { it.deleteOnExit() }
    // Concurrent because it's read by request threads while a background catalog refresh — or an
    // admin registration — writes to it.
    val wasm = java.util.concurrent.ConcurrentHashMap<String, File>()
    val loads =
      CatalogLoadTracker(
        catalogRefs.map { ref ->
          CatalogLoadTracker.Config(
            system = ref.system,
            listed = ref.listed,
            repo = ref.repo,
            branch = "$catalogBranchPrefix${ref.system}",
            group = ref.group,
            loadPriority = ref.loadPriority,
          )
        }
      )
    // The sweeper needs configured-but-unloaded systems, which only the tracker knows.
    themeCacheConfiguredSystems = loads::configuredSystems
    val store =
      ServeCatalogStore(
        root = dir,
        register = { id, host -> publishStaticCatalog(id, host, registry) },
        trust = { trustStore.get() },
        repo = catalogRepo,
        branchPrefix = catalogBranchPrefix,
        maxImages = catalogMaxImages,
        blobs = catalogBlobPool,
        serverSideRenderEnabled = allowRenderTrusted,
        registerWasm = { system, wasmDir ->
          // A local `--wasm-dir` is the operator's explicit override, so a published app never
          // displaces it — including on a later branch refresh, which re-runs this callback.
          if (system !in localWasm) {
            wasm[system] = wasmDir
            System.err.println(
              "serve: catalog $system carries an in-browser Wasm app (/wasm/$system/)"
            )
          }
        },
        buildTrustedBundle = {
          system,
          bundleFile,
          externalResourcesDir,
          alias,
          bakedFallback,
          perPreviewBundle ->
          buildTrustedCatalogBundle(
            system,
            bundleFile,
            externalResourcesDir,
            alias,
            bakedFallback,
            perPreviewBundle,
            registry,
            openHost,
          )
        },
        buildTrustedBundles = { system, bundles, bakedFallback ->
          buildTrustedCatalogBundles(
            system,
            bundles,
            bakedFallback,
            registry,
            openHost,
          )
        },
        recordTrustedBundles = { system, bundles -> recordCatalogCompileTargets(system, bundles) },
        clearTrustedBundles = catalogLiveBundles::remove,
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
    return CatalogRegistration(
      wasm = wasm,
      store = store,
      loads = loads,
      loader = InitialCatalogLoader(store, loads),
    )
  }

  /**
   * Wire the runtime catalog admin ([ServeCatalogAdmin]) to this server's moving parts: a
   * registration fetches through the same [store] startup uses, lands in the same [loads] tracker
   * every consumer reads, and is written back to the operator's `--catalogs-file`. Retiring a
   * catalog drops its session (closing any live daemon) and its per-preview daemon pool.
   */
  private fun buildCatalogAdmin(
    registry: ServeSessionRegistry,
    store: ServeCatalogStore,
    loads: CatalogLoadTracker,
    wasmCatalogs: MutableMap<String, File>,
    /** So retiring a catalog a hostname is published as is refused rather than stranding it. */
    sites: ServeSiteRegistry,
  ): ServeCatalogAdmin =
    ServeCatalogAdmin(
      tracker = loads,
      sites = sites,
      defaultRepo = catalogRepo,
      branchPrefix = catalogBranchPrefix,
      configFile = catalogsFile,
      groups = catalogsConfig.groups,
      load = { system, repo ->
        backgroundWork.whileLoadingCatalog {
          synchronized(catalogRegistrationLock) {
            val result = store.load(system, sourceRepo = repo)
            loads.record(result)
            (result as? ServeCatalogStore.Result.Failed)?.reason
          }
        }
      },
      unload = { system ->
        synchronized(catalogRegistrationLock) {
          registry.unregister(system)
          catalogPerPreviewPools.remove(system)?.let { runCatching { it.close() } }
          catalogLiveBundles.remove(system)
          registeredCatalogs.remove(system)
          registeredUnlistedCatalogs.remove(system)
          // Stop serving the retired catalog's in-browser app too — but never drop a local
          // `--wasm-dir` the operator configured, which isn't the catalog's to remove.
          if (system !in localWasm) wasmCatalogs.remove(system)
        }
      },
    )

  /**
   * Build the background poller that keeps a running server fresh against its catalog branches (see
   * [ServeCatalogRefresher]). Null when polling is disabled ([catalogRefreshSeconds] ≤ 0) or there
   * are no catalogs. The caller seeds heads + starts it, and adds it to the server's closeables so
   * the daemon thread stops on shutdown. A successful re-load re-registers the catalog host in
   * place (the registry closes the replaced daemon) and rewrites the on-disk `web/wasm/` dir the
   * `/wasm/<system>/` route serves.
   */
  private fun buildCatalogRefresher(
    store: ServeCatalogStore,
    loads: CatalogLoadTracker,
  ): ServeCatalogRefresher? {
    // Also built for an admin-enabled server with no configured catalogs: the entries are read from
    // the tracker per pass, so a catalog published at runtime starts being polled without a
    // restart.
    if (catalogRefreshSeconds <= 0 || !needsCatalogMachinery) return null
    // Read from the tracker per pass, not from the startup refs: a catalog published through the
    // admin API must start being polled without a restart (and a retired one must stop).
    val entries = {
      loads.snapshot().map {
        ServeCatalogRefresher.Entry(
          system = it.config.system,
          repo = it.config.repo,
          branch = it.config.branch,
        )
      }
    }
    return ServeCatalogRefresher(
      entries = entries,
      reload = { system, repo ->
        val result = backgroundWork.whileLoadingCatalog {
          synchronized(catalogRegistrationLock) {
            if (loads.configFor(system) == null) return@synchronized null
            val result = store.load(system, sourceRepo = repo)
            loads.record(result)
            result
          }
        }
        if (result == null) {
          false
        } else if (result is ServeCatalogStore.Result.Failed) {
          System.err.println("serve: catalog $system refresh failed: ${result.reason}")
          false
        } else {
          true
        }
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
    perPreviewBundle: ee.schimke.composeai.cli.serve.PerPreviewBundleAccess,
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
    // The per-preview daemons cost whatever this catalog's backend costs, but the pool is built
    // before the bundle is materialised (the pool's opener is what materialises it), so the weight
    // is read through a holder set below rather than captured now.
    var perPreviewSeatWeight = 1
    val perPreviewPool =
      ServePerPreviewDaemonPool(
        liveSeats = liveSeatLimiter,
        seatWeight = { perPreviewSeatWeight },
      ) { daemonId ->
        val ppFile = perPreviewBundle.fetch(daemonId) ?: return@ServePerPreviewDaemonPool null
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
    val materialized =
      ServeBundleDaemon.materialize(
        bundleFile,
        destDir,
        system,
        extraMavenRepos = extraMavenRepos,
        extraClasspathDirs = listOfNotNull(externalResourcesDir),
      )
        ?: run {
          perPreviewPool.close()
          return false
        }
    val state =
      materialized.copy(
        previewAliases = alias,
        bakedFallback = bakedFallback,
        perPreviewResolve = perPreviewPool::get,
        executableBundleAvailable = perPreviewBundle.available,
        executableBundleProvider = { daemonId ->
          perPreviewBundle.fetch(daemonId)?.takeIf(File::isFile)?.readBytes()
        },
        perPreviewStreamCount = perPreviewPool::activeStreamCount,
        perPreviewRenderStats = perPreviewPool::renderPerfStats,
        perPreviewPoolStats = { listOf(perPreviewPool.snapshot()) },
        perPreviewReapIdle = perPreviewPool::reapIdle,
        catalogThemeCache = themeCacheFor(system, alias, materialized.descriptor),
        serverIdleMillis = backgroundWork.idleClock(registry::idleMillis),
        backgroundWork = backgroundWork,
      )
    // Now that the backend is known, the pool's daemons charge this catalog's real weight — an
    // Android/Robolectric per-preview daemon is not the same cost to the box as a desktop one.
    perPreviewSeatWeight = state.liveSeatWeight
    val host =
      openHost(state)
        ?: run {
          perPreviewPool.close()
          return false
        }
    if (!publishCatalogRuntime(system, state, host, perPreviewPool, registry)) {
      return false
    }
    System.err.println("serve: catalog $system → LIVE from bundle (no build) (?session=$system)")
    return true
  }

  /**
   * Publish a catalog host and the resources its state closures capture as one ownership transfer.
   * The ownership maps move first, then the registry entry becomes request-visible; on failure both
   * maps are restored and the unpublished host/resources are closed. The caller's catalog
   * registration lock makes this atomic with admin unload and branch refresh.
   */
  private fun publishCatalogRuntime(
    system: String,
    state: ServeSessionState,
    host: ServeHost,
    resources: AutoCloseable,
    registry: ServeSessionRegistry,
  ): Boolean {
    var previousResources: AutoCloseable? = null
    try {
      synchronized(catalogRegistrationLock) {
        previousResources = catalogPerPreviewPools.put(system, resources)
        try {
          registry.register(system, state, host = host)
        } catch (failure: Throwable) {
          previousResources?.let { catalogPerPreviewPools[system] = it }
            ?: catalogPerPreviewPools.remove(system, resources)
          throw failure
        }
      }
    } catch (failure: Throwable) {
      runCatching { host.close() }
      runCatching { resources.close() }
      System.err.println("serve: catalog $system publication failed (${failure.message})")
      return false
    }
    previousResources?.let { runCatching { it.close() } }
    // Publication succeeded, so any generation this catalog superseded is now genuinely retired and
    // safe to reclaim. Doing it here rather than when the replacement cache was opened is what
    // keeps
    // a failed `openHost` from deleting the cache of the host still serving.
    sweepThemeCache()
    sweepCatalogBlobs()
    return true
  }

  /** Record every verified module bundle as an independent lazy playground compile target. */
  private fun recordCatalogCompileTargets(
    system: String,
    bundles: List<ServeCatalogStore.VerifiedModuleBundle>,
  ) {
    catalogLiveBundles[system] = bundles.mapIndexed { index, bundle ->
      val metadata = runCatching { BundleReader.readMetadata(bundle.file).manifest }.getOrNull()
      CatalogLiveBundle(
        id = catalogTargetId(system, bundle.module, primary = index == 0),
        module = bundle.module.ifBlank { metadata?.modulePath.orEmpty() },
        file = bundle.file,
        backend = metadata?.backend,
      )
    }
  }

  /** Replace a formerly-live catalog without leaving stale pools or playground targets behind. */
  private fun publishStaticCatalog(
    system: String,
    host: ServeHost,
    registry: ServeSessionRegistry,
  ) {
    var previousResources: AutoCloseable? = null
    synchronized(catalogRegistrationLock) {
      previousResources = catalogPerPreviewPools.remove(system)
      try {
        registry.register(system, host = host, pinned = true)
      } catch (failure: Throwable) {
        previousResources?.let { catalogPerPreviewPools[system] = it }
        throw failure
      }
    }
    previousResources?.let { runCatching { it.close() } }
  }

  /** Stand up one independently materialised live runtime per module and route by namespaced id. */
  private fun buildTrustedCatalogBundles(
    system: String,
    bundles: List<ServeCatalogStore.TrustedModuleBundle>,
    bakedFallback: () -> ServeHost,
    registry: ServeSessionRegistry,
    openHost: (ServeSessionState) -> ServeHost?,
  ): Boolean {
    if (!allowRenderTrusted || bundles.isEmpty()) return false
    data class Runtime(
      val published: ServeCatalogStore.TrustedModuleBundle,
      val state: ServeSessionState,
      val pool: ServePerPreviewDaemonPool,
      var monolithic: ServeHost? = null,
    )

    val opened = mutableListOf<Runtime>()
    var publishedSuccessfully = false
    try {
      for ((index, published) in bundles.withIndex()) {
        var seatWeight = 1
        val pool =
          ServePerPreviewDaemonPool(
            liveSeats = liveSeatLimiter,
            seatWeight = { seatWeight },
          ) { daemonId ->
            val file =
              published.perPreviewBundle.fetch(daemonId) ?: return@ServePerPreviewDaemonPool null
            val dest =
              java.nio.file.Files.createTempDirectory("serve-catalog-preview-$system-$index")
                .toFile()
                .also { it.deleteOnExit() }
            val state =
              ServeBundleDaemon.materialize(
                file,
                dest,
                "$system:${published.module}",
                extraMavenRepos = extraMavenRepos,
                extraClasspathDirs = listOfNotNull(published.externalResourcesDir),
              ) ?: return@ServePerPreviewDaemonPool null
            openHost(state)
          }
        val dest =
          java.nio.file.Files.createTempDirectory("serve-catalog-module-$system-$index")
            .toFile()
            .also { it.deleteOnExit() }
        val state =
          ServeBundleDaemon.materialize(
            published.file,
            dest,
            "$system:${published.module}",
            extraMavenRepos = extraMavenRepos,
            extraClasspathDirs = listOfNotNull(published.externalResourcesDir),
          )
            ?: run {
              pool.close()
              return false
            }
        seatWeight = state.liveSeatWeight
        opened += Runtime(published, state, pool)
      }

      val primary = opened.first()
      for (runtime in opened.drop(1)) {
        runtime.monolithic = openHost(runtime.state) ?: return false
      }
      val ownerByDaemonId =
        opened
          .flatMap { runtime ->
            runtime.published.alias.values.map { daemonId -> daemonId to runtime }
          }
          .toMap()
      val alias = opened.flatMap { it.published.alias.entries }.associate { it.toPair() }
      val resolver: (String) -> ServeHost? = { daemonId ->
        val runtime = ownerByDaemonId[daemonId]
        when {
          runtime == null -> null
          runtime === primary -> runtime.pool.get(daemonId)
          else -> runtime.pool.get(daemonId) ?: runtime.monolithic
        }
      }
      val state =
        primary.state.copy(
          previewAliases = alias,
          bakedFallback = bakedFallback,
          perPreviewResolve = resolver,
          executableBundleAvailable = { daemonId ->
            ownerByDaemonId[daemonId]?.published?.perPreviewBundle?.available?.invoke(daemonId)
              ?: false
          },
          executableBundleProvider = { daemonId ->
            ownerByDaemonId[daemonId]
              ?.published
              ?.perPreviewBundle
              ?.fetch(daemonId)
              ?.takeIf(File::isFile)
              ?.readBytes()
          },
          perPreviewStreamCount = { opened.sumOf { it.pool.activeStreamCount() } },
          perPreviewRenderStats = {
            opened.flatMap { runtime ->
              buildList {
                addAll(runtime.pool.renderPerfStats())
                runtime.monolithic?.renderPerfStats()?.let(::add)
              }
            }
          },
          perPreviewPoolStats = { opened.map { it.pool.snapshot() } },
          perPreviewReapIdle = { idle -> opened.sumOf { it.pool.reapIdle(idle) } },
          catalogThemeCache =
            themeCacheFor(system, alias, *opened.map { it.state.descriptor }.toTypedArray()),
          serverIdleMillis = backgroundWork.idleClock(registry::idleMillis),
          backgroundWork = backgroundWork,
        )
      val host = openHost(state) ?: return false
      val resources = AutoCloseable {
        opened.forEach { runtime ->
          runCatching { runtime.pool.close() }
          runCatching { runtime.monolithic?.close() }
        }
      }
      if (!publishCatalogRuntime(system, state, host, resources, registry)) return false
      System.err.println(
        "serve: catalog $system → LIVE from ${opened.size} module bundles (no build) (?session=$system)"
      )
      publishedSuccessfully = true
      return true
    } finally {
      // Once registered, ownership moves to catalogPerPreviewPools. Otherwise unwind partial opens.
      if (!publishedSuccessfully) {
        opened.forEach { runtime ->
          runCatching { runtime.pool.close() }
          runCatching { runtime.monolithic?.close() }
        }
      }
    }
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
        catalogThemeCache = themeCacheFor(system, alias, built.descriptor),
        serverIdleMillis = backgroundWork.idleClock(registry::idleMillis),
        backgroundWork = backgroundWork,
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
    // The source-backed path registers directly rather than through `publishCatalogRuntime`, so it
    // needs its own post-publication sweep — without it a deployment of only source-backed catalogs
    // never reclaims a superseded generation, whatever the cap says.
    sweepThemeCache()
    sweepCatalogBlobs()
    System.err.println(
      "serve: catalog $system → LIVE server-render from ${source.module}@${source.ref} " +
        "(?session=$system)"
    )
    return true
  }

  /**
   * Drop every registered catalog whose source branch [updated] no longer trusts.
   *
   * Called after a trust revocation. Unregistering closes the session's host, which is what takes
   * down a live daemon started under the old verdict; marking the tracker row failed is what gets
   * the catalog re-fetched — [ServeCatalogRefresher] skips a reload while the branch SHA is
   * unchanged, so a revoked-but-still-loaded catalog would otherwise keep serving as `Trusted`
   * until its branch moved or the box restarted.
   */
  private fun retireNewlyUntrusted(
    updated: TrustStore,
    tracker: CatalogLoadTracker?,
    registry: ServeSessionRegistry,
  ) {
    val loads = tracker ?: return
    val retired = mutableListOf<String>()
    synchronized(catalogRegistrationLock) {
      for (state in loads.snapshot()) {
        val repo = state.config.repo
        val branch = state.config.branch
        if (updated.trustsBranch(repo, branch)) continue
        // Only a catalog that actually loaded under the old trust needs tearing down; a pending or
        // already-failed row has nothing serving to revoke.
        if (!state.available) continue
        registry.unregister(state.config.system)
        loads.recordFailure(state.config.system, "producer trust revoked; awaiting re-verification")
        retired += state.config.system
        System.err.println(
          "serve: retired ${state.config.system} — $repo@$branch is no longer trusted"
        )
      }
    }
    // Clear the remembered branch heads so the next refresh pass re-fetches these instead of
    // short-circuiting on an unchanged SHA — without this the teardown would be undone only by a
    // branch move or a restart.
    if (retired.isNotEmpty()) activeRefresher?.forgetHeads(retired)
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
      // With the trust admin armed, an absent file is a legitimate starting state: the operator is
      // about to create it through `POST /admin/trust`. Without it, a missing file is still fatal —
      // silently trusting nothing is exactly the failure the hard exit exists to prevent.
      if (adminToken != null) {
        System.err.println("serve: --trust-store ${f.path} does not exist yet; starting with no")
        System.err.println("serve: trusted producers (add them via POST /admin/trust)")
        return TrustStore.EMPTY
      }
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
   * Match a preview against `--id` (exact) / `--filter` (substring) / `--preview` (loose reference)
   * — the shared [previewIdMatchesRequest] rule, so every selector passed must hold; all previews
   * when none is set.
   *
   * This used to be a `--id` beats `--filter` precedence ladder, which read as the safer choice but
   * could never actually take effect: `renderAllModules` narrows the build through
   * `modulesMatchingPreviewRequest`, which intersects, so contradictory selectors have already
   * dropped every module by the time this runs. The ladder's only reachable outcome was to disagree
   * with the pass that had already decided.
   */
  /**
   * Each discovered module paired with the previews it can actually serve for this request, with
   * the modules that can serve none dropped (issue #3786 review follow-up).
   *
   * This is where a `@PreviewParameter` "maybe" from module selection becomes a fact. Module
   * selection cannot know a provider's rows — they don't exist until the render writes the fan-out
   * — so it keeps any parameterized preview that *might* match. By the time this runs the fan-out
   * is on disk and [ServeParameterRows] can enumerate it, so a module retained for a row that
   * turned out not to exist contributes an empty list and drops out before the one-module check.
   */
  private fun modulesWithMatchingPreviews(
    manifests: List<Pair<PreviewModule, PreviewManifest>>
  ): List<Pair<PreviewModule, List<ServePreview>>> =
    manifests
      .map { (module, manifest) -> module to servablePreviewsOf(module, manifest) }
      .filter { (_, previews) -> previews.isNotEmpty() }

  /**
   * The `@PreviewParameter` fan-out expansion (issue #3749). Discovery emits ONE entry per
   * parameterized function, so the manifest alone would list a screen whose states come from a
   * provider as a single card showing value 0 — the symptom that issue was filed about. The render
   * pass already wrote one file per value, and the daemon accepts those `<baseId>_<row>` ids, so
   * each on-disk row becomes its own servable preview. A preview with no provider, or whose fan-out
   * isn't on disk, keeps exactly its old single entry.
   */
  private fun servablePreviewsOf(
    module: PreviewModule,
    manifest: PreviewManifest,
  ): List<ServePreview> {
    val claimedOutputs = ServeParameterRows.claimedOutputs(manifest.previews)
    return manifest.previews.flatMap { info ->
      val (focus, gestures) = detectedFeaturesOf(info)
      fun serve(id: String, label: String) =
        ServePreview(
          id = id,
          label = info.catalog?.caption?.takeIf { it.isNotBlank() } ?: label,
          uiMode = info.params.uiMode,
          supportsFocus = focus,
          supportsGestures = gestures,
          fixedTheme = info.fixedTheme,
          state = info.catalog?.state,
          props =
            info.catalog
              ?.props
              ?.takeIf { it.isNotEmpty() }
              ?.associate { it.key to JsonPrimitive(it.value) }
              ?.let(::JsonObject),
          section = info.catalog?.section,
          group = info.catalog?.group,
          sourceFile = info.sourceFile,
          bodyLine = info.bodyLine,
          componentId = info.catalog?.componentId,
        )
      val baseLabel = info.functionName.ifBlank { info.id }
      val rows = ServeParameterRows.rowsFor(info, module.projectDir, claimedOutputs)
      // `--id` / `--filter` / `--preview` match the declared preview (so `--id Foo` serves all of
      // Foo's rows, which is what asking for a parameterized preview means) OR a row id directly,
      // so
      // a caller can narrow to one state. The declared preview is matched as a *row of the
      // manifest*, not as a bare id, because `--preview` also accepts `<Class>.<function>` and the
      // bare function name — forms only the manifest row can answer. A synthetic row id has no
      // manifest row of its own, so it is matched by id.
      when {
        rows.isEmpty() -> if (matches(info)) listOf(serve(info.id, baseLabel)) else emptyList()
        matches(info) -> rows.map { serve(it.id, "$baseLabel · ${it.label}") }
        else -> rows.filter { matches(it.id) }.map { serve(it.id, "$baseLabel · ${it.label}") }
      }
    }
  }

  private fun matches(preview: PreviewInfo): Boolean =
    previewIdMatchesRequest(
      preview.id,
      exactId = exactId,
      filter = filter,
      previewRef = previewRef,
      className = preview.className,
      functionName = preview.functionName,
    )

  /**
   * The same rule for something that has an id but no manifest row — a `@PreviewParameter` row id
   * (`<baseId>_<row>`), which discovery never declared. Only the id-shaped forms can apply, so
   * `--preview` degrades to the exact-or-substring half of [previewMatchesReference].
   */
  private fun matches(id: String): Boolean =
    previewIdMatchesRequest(id, exactId = exactId, filter = filter, previewRef = previewRef)

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

  private fun buildGithubAuth(): ServeGithubAuth? {
    val provided =
      listOf(githubAuthClientId, githubAuthClientSecret, githubAuthCookieSecret, githubAuthRepo)
        .count { it != null }
    if (provided == 0) return null
    if (provided != 4) {
      error(
        "GitHub auth needs --github-auth-client-id, --github-auth-client-secret, " +
          "--github-auth-cookie-secret, and --github-auth-repo"
      )
    }
    return ServeGithubAuth(
      ServeGithubAuthConfig(
        clientId = githubAuthClientId!!,
        clientSecret = githubAuthClientSecret!!,
        cookieSecret = githubAuthCookieSecret!!,
        repository = githubAuthRepo!!,
        allowedUsers = githubAuthUsers,
        callbackBaseUrl = githubAuthCallbackBaseUrl,
        cookieDomain = githubAuthCookieDomain,
        oauthScope = githubAuthScope,
      )
    )
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
    if (acceptDocs) {
      val docsUrl =
        if (public) "${ServeUrls.origin(localHost, port)}/docs"
        else "${ServeUrls.origin(localHost, port)}/docs?token=$token"
      System.err.println("  Documents: $docsUrl (drop a .rc / Lottie, get an expiring link)")
    }
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
        --preview <ref>   Only serve previews the reference selects: an id, a
                          `Class.function`, a bare function name, or a case-insensitive
                          substring of an id. Combined with --id / --filter it intersects:
                          every selector you pass has to match. Selecting needs a module
                          (--module / --discover) — a bundle-backed server has no manifest.
        --host <addr>     Bind address (default 127.0.0.1 — loopback only).
        --lan             Bind all interfaces (0.0.0.0) so other devices on your network can
                          connect. Prints the token-gated network URL and a security warning.
        --port <n>        Preferred port (default $DEFAULT_PORT; auto-picks the next free one).
        --token <value>   Use a fixed token instead of a freshly generated one (stable links).
        --public          Serve every route WITHOUT a token (open). For a deployed public preview
                          server — browsing published catalogs / uploaded bundles is the point. Safe
                          by construction (no server-side code exec; untrusted re-render refused;
                          uploads capped + SSRF-gated). Off by default.
        --component-browser
                          Use the streamlined Storybook-like catalog and component browser. Hides
                          administration, diagnostics, comparison and renderer tooling while
                          retaining visual navigation, variants, themes, authored controls,
                          sample source, locale/font scale and PNG/SVG downloads.
        --github-auth-client-id <id>
        --github-auth-client-secret <secret>
        --github-auth-cookie-secret <secret>
        --github-auth-repo <owner/repo>
                          Add GitHub OAuth on top of the browse gate for code-running surfaces:
                          live preview WebSockets and the playground. Live preview accepts any
                          signed-in GitHub user (unless --github-auth-users narrows sign-in);
                          playground additionally requires access to <owner/repo>. After sign-in
                          the server stores only a signed, expiring login cookie plus the repo
                          access verdict. The OAuth scope follows the repo's visibility: a public
                          <owner/repo> needs only read:user, a private one also needs repo (classic
                          OAuth apps have no read-only repository scope). All four flags are
                          required together.
        --github-auth-callback-base-url <url>
                          External origin for the OAuth callback, e.g. https://preview.example.com.
                          Omit for local use; reverse-proxied deploys should set it explicitly.
        --github-auth-cookie-domain <domain>
                          Scope the auth cookies to a parent domain, so one sign-in covers it and
                          every --sites hostname under it (preview.example.com also signs in
                          m3.preview.example.com). Required for sign-in to work on a top-level site
                          when --github-auth-callback-base-url is pinned: without it the cookies are
                          host-only, the state cookie never reaches the callback origin, and the
                          server withholds sign-in on site hosts. Omit on a single-hostname box.
                          Every host under <domain> is inside the session's reach, so name the
                          narrowest one that covers your sites.
        --github-auth-scope <scope>
                          Override the OAuth scope instead of deriving it from --github-auth-repo's
                          visibility. Only needed when a GitHub App or org policy demands a specific
                          one; the derived value is already the narrowest that works.
        --github-auth-users <login>[,<login>…]
                          Optional sign-in allowlist. Empty means any signed-in GitHub user may use
                          live sessions; playground still requires access to --github-auth-repo.
        --export <path>   Don't serve: render every preview once and write a portable bundle (a
                          self-contained web gallery + PNGs) to <path>. A '.zip' path writes a zip;
                          any other path writes a directory. The live server also offers this at
                          GET /bundle.zip.
        --inline          With --export, bake the PNGs into the gallery for a single self-contained
                          index.html (vs. separate previews/<id>.png files).
        --revisions       Project mode: also serve other git revisions of this repo on demand. A
                          request with ?session=<rev> checks that revision out into a worktree,
                          builds it, and serves it as its own session (suspended/resumed when idle).
        --history-branch <ref>
                          Project mode: the baseline delivery branch, as fetched in this checkout,
                          whose publishes the viewer's render-history strip is built from (default
                          ${ServeProjectHistory.DEFAULT_BRANCH}; 'origin/<ref>' is tried too). Each
                          entry opens that version's render, served from the local object store.
                          A ref this clone can't resolve simply means no strip.
        --no-history      Don't compute the render-history strip from local git.
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
                          when the server is module-less but the catalog's source.repo is a separate
                          checkout (e.g. a prebuilt image that clones the CMP catalog repo for live
                          render). The trust + same-repo + ref-allowlist gates are unchanged.
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
        --accept-docs     Enable the DOCUMENT lane: GET /docs (drop a file) + POST /docs ingest one
                          known document — Remote Compose (.rc) or Lottie (.json) — and hand back an
                          expiring permalink (/d/<id>) that plays it in the viewer's browser. Data
                          only: the server stores bytes and never renders them, so hosting an
                          anonymous document runs nothing. Off by default.
        --doc-ttl <seconds>
                          How long a /d/<id> document link lives (default ${ServeDocStore.DEFAULT_TTL_SECONDS}s). The document is
                          held in memory and dropped when it expires.
        --accept-docs-from <host>[,<host>…]
                          SSRF allowlist for POST /docs?url=: hostnames the server may fetch a
                          document from. Omitted/empty = uploads only (fail closed).
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
        --catalogs-file <path>
                          The catalog set as CONFIG, not flags: a catalogs.json listing every
                          catalog to serve ({"groups":[…],"catalogs":[{"system","repo","listed",
                          "group"}]}). Meant to live outside the container image (a mounted volume)
                          so publishing a catalog is a config edit, not an image rebuild. The
                          "group" names a front-page section from "groups" — a claim honoured only
                          when the catalog's bytes really came from the entry's repo (or one of its
                          "attributionRepos"), so an id like compose-m3 can't buy a section. Entries
                          here come first; --catalogs / --catalogs-unlisted add to them. May also
                          carry "sites" (see --sites).
        --sites <host>=<system>[,…]
                          Top-level sites: serve an already-published catalog on a hostname of its
                          own, where it looks like the whole server (e.g.
                          m3.preview.coo.ee=m3-catalog serves what /m3-catalog/ serves). On that
                          host the catalog's landing is /, every link stays inside the domain, the
                          front-door index and the "all design systems" back link are gone, /status
                          + /sitemap.xml cover that app only, and /<other-system>/ 404s while
                          /<this-system>/… 301s to the rooted URL. Same sessions, same baked pixels,
                          same daemons — a site is a view of the box, not a second one. The system
                          must be one this server already serves; catalogs.json's "sites" says the
                          same thing as config, and POST /admin/sites says it on a running server
                          (see --admin-token), writing it back to --catalogs-file. Two things this
                          does NOT do, because they are outside the app: DNS for the name must point
                          at this box, and the reverse proxy must match the name and hold a
                          certificate for it.
        --admin-token <value>
                          Enable the runtime admin API and gate it with this secret. Two surfaces:
                          the catalog set — GET /admin/catalogs, POST /admin/catalogs (a
                          catalogs.json entry as the body), DELETE /admin/catalogs/<system> — and
                          the producer-trust store — GET /admin/trust, POST /admin/trust
                          ({"kind":"branch"|"key"|"oidc",…}), DELETE /admin/trust?kind=&repo=…
                          (selectors ride the query string so an owner/repo needn't be escaped).
                          the front-page sections — GET /admin/groups, POST /admin/groups
                          ({"id","heading","noun"}), DELETE /admin/groups/<id>. Defining a section
                          also regroups catalogs already registered, and re-POSTing a published
                          catalog converges its listing (group / listed) in place. And the
                          top-level sites — GET /admin/sites, POST /admin/sites
                          ({"host","system"}), DELETE /admin/sites/<host> — so a hostname can be
                          published on a running box; re-POSTing one whose system changed re-points
                          it in place. The edge still has to route the name and hold a certificate
                          for it (see --sites).
                          Mutations are applied live AND written back to --catalogs-file /
                          --trust-store, so they survive a restart. Separate from --token on purpose
                          (a --public box hands that one to every visitor); omitted = the admin
                          routes don't exist at all. NB with --allow-render-trusted this token can
                          grant server-side execution, since trusting a branch makes that
                          producer's Compose eligible for re-render here.
        --engagement-file <path>
                          Persist privacy-minimal aggregate catalog/app and per-preview view counts
                          as JSON. No IPs, cookies, user agents, or referrers are stored. Omitted =
                          counters last only for this server process.
        --catalog-repo <owner/repo>
                          Default repo the catalogs are fetched from (default
                          yschimke/compose-ai-tools); per-entry @<owner>/<repo> overrides it.
        --catalog-branch-prefix <prefix>
                          Branch prefix for --catalogs (default design-artifacts/).
        --catalog-max-images <count>
                          Maximum baked previews loaded from one published catalog (default
                          ${ServeCatalogStore.DEFAULT_MAX_IMAGES}). Images are fetched lazily; this
                          bounds registered preview metadata and routes, not eager image downloads.
        --catalog-refresh-interval <seconds>
                          Keep a running server fresh: re-check each --catalogs branch's head every
                          <seconds> and re-fetch (catalog.json + renders + web/wasm/ + liveBundle) in
                          place when it moved — so a regenerated branch is picked up with no restart
                          (default ${DEFAULT_CATALOG_REFRESH_SECONDS}s; 0 disables, serving the boot snapshot only). Uses
                          `git ls-remote` (no API rate limit), and skips a branch it can't resolve.
        --catalog-feed-idle-timeout <seconds>
                          Publish /<catalog>/feed.xml. A request renews that feed's background
                          history-computation lease; after this many seconds without another request
                          it stops fetching while keeping the last generated feed and shallow Git
                          cache (default ${DEFAULT_CATALOG_FEED_IDLE_SECONDS}s; 0 disables).
        --catalog-feed-cache <dir>
                          Durable shallow-Git + generated-XML cache for catalog feeds. Defaults to a
                          catalog-feeds directory beside --catalogs-file, or a temp dir in local mode.
        --catalog-cache-dir <dir>|none
                          Durable, content-addressed store for the heavy bytes a catalog fetches —
                          the executable liveBundle, its per-preview splits and the externalised
                          resource pool — so a reload or a restart re-reads them instead of pulling
                          ~100 MB per live catalog again. Unset (and `none`) keeps the temp-dir pool
                          this always had, which dies with the process; there is no derived default,
                          because the obvious one is the config volume. Only bytes addressed by a
                          commit-pinned URL are cached, so a load that could not resolve its
                          delivery commit populates nothing.
        --catalog-cache-max-bytes <n>
                          Ceiling for that store (default
                          ${CatalogBlobPool.DEFAULT_MAX_BYTES / (1024L * 1024 * 1024)} GB).
                          Reclaimed oldest-first after the startup pass and after each later
                          catalog publication; blobs newer than an hour are spared so the replicas
                          that overlap during a rolling update cannot evict each other's.
        --theme-cache-dir <dir>|none
                          Durable store for warmed theme renders, so background warming survives a
                          restart and a catalog refresh. `none` disables it. Defaults to a
                          theme-cache directory beside --catalogs-file — note that on the prebuilt
                          image that is the persistent config volume, so pass `none` to keep it off;
                          with no durable location the cache stays in memory only
                          (there is deliberately no temp-dir fallback — it would be thrown away with
                          the process). Entries are keyed by a fingerprint of the render classpath,
                          daemon variant, tool version and render config, so a new catalog revision
                          or server build never reads the previous one's pixels.
        --theme-cache-max-bytes <n>
                          Ceiling for that store across every catalog (default
                          ${ThemeCacheStore.DEFAULT_MAX_BYTES / (1024L * 1024 * 1024)} GB). Superseded
                          generations are reclaimed; generations still in use are never evicted, so
                          exceeding this is reported rather than acted on.
        --theme-optimizer-coordination-dir <dir>
                          Shared directory used to coordinate background optimizer lanes across
                          server replicas. Defaults to optimizer-locks beside --catalogs-file; set
                          this explicitly when replicas do not share that directory.
        --wasm-dir <system>=<dir>[,<system>=<dir>…]
                          In-browser CMP tier: map a design system to its assembled Kotlin/Wasm
                          catalog app (./gradlew :samples:cmp-wasm-catalog:wasmCatalogDist →
                          build/wasmDist). That session's viewer then offers a "Run in browser
                          (Wasm)" toggle that mounts the M3 components client-side (no server
                          round-trip), served read-only at /wasm/<system>/. Missing dirs are skipped.
        --rc-player-wasm-dir <dir>
                          Experimental non-JVM Remote Compose player produced by
                          :rc-player-wasm:wasmPlayerDist. Serves it at /rc-player-wasm/ and enables
                          the "CMP Wasm" RC backend for previews carrying a captured .rc document.

      The shareable link carries an unguessable token; requests without it get 404.
      """
        .trimIndent()
    )
  }

  private companion object {
    const val COMPONENT_PROTOCOL_MARKER = "compose-preview-components.json"
    const val DEFAULT_PORT = 8723
    const val DEFAULT_IDLE_EXIT_SECONDS = 60L

    /**
     * Default catalog re-check cadence (10 min). Fresh enough that a regenerated design-artifacts
     * branch reaches a running server within minutes, and — via `git ls-remote` (no API rate limit)
     * — cheap enough to poll every watched catalog at this cadence indefinitely.
     */
    const val DEFAULT_CATALOG_REFRESH_SECONDS = 600L
    const val DEFAULT_CATALOG_FEED_IDLE_SECONDS = 7L * 24 * 60 * 60

    /**
     * Floor between catalog-blob sweeps. Comfortably shorter than the pool's own grace window (so a
     * sweep is never the thing that delays a reclaim) and long enough that a burst of catalog
     * publications runs one census rather than one each.
     */
    const val CATALOG_BLOB_SWEEP_INTERVAL_MILLIS = 5L * 60 * 1000

    /**
     * Compiles per minute per caller. Sized for a person using the editor, not for a script: a
     * deliberate Run every six seconds sustained is already brisk, and the bucket lets a burst of
     * ten through back-to-back before it starts pacing. Raise it for a busy shared host; 0 turns
     * the limiter off entirely.
     */
    const val DEFAULT_PLAYGROUND_RATE_LIMIT = 10
  }
}
