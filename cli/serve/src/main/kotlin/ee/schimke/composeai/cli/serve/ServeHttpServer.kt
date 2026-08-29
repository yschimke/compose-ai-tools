package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantProtocol
import ee.schimke.composeai.agentgrants.AgentGrantScope
import ee.schimke.composeai.bundle.BundleVerifier
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.data.layoutinspector.ComposeFigmaSvgProduct
import ee.schimke.composeai.data.layoutinspector.ExplodedSvg
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration
import ee.schimke.composeai.designpages.DesignPage
import ee.schimke.composeai.imagecrop.ContentCrop
import ee.schimke.composeai.web.WebEscaping
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLQueryComponent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The embedded Ktor (CIO) HTTP server fronting a [ServeSessionRegistry]. Thin IO shell: a token
 * gate, the routes, and the query-param → [ServeOverrides] → render → PNG glue. All shared,
 * concurrency-safe state lives in the per-tenant [ServeRenderHost]s; this class adds none of its
 * own per-request state, so it serves any number of clients.
 *
 * **Multi-tenant:** one server fronts many sessions instead of one per module. Every route resolves
 * a [ServeRenderHost] from the registry by the request's `?session=` (falling back to
 * [defaultSessionId]); the registry forks the tenant behind its factory on first use. Unknown
 * sessions 404 like a bad token.
 *
 * Endpoints (all token-gated except `/healthz`, `/readyz`, `/version`, and the `/wasm/` static
 * assets):
 * - `GET /` landing page, `GET /p/{id}` viewer page,
 * - `GET /{system}/feed.xml` demand-activated catalog change feed,
 * - `GET /render/{id}.png` PNG bytes, `GET /api/previews` JSON, `GET /healthz` liveness,
 * - `GET /hero/{system}/{hash}.png` a prebaked, immutable front-door thumbnail ([ServeHeroImages]),
 * - `GET /social/{hash}.png` the drawn link-unfurl card a page advertises ([ServeSocialCard]), and
 *   `GET /favicon.svg` / `/favicon.ico` / `/apple-touch-icon.png` the site icon ([ServeSiteIcon]) —
 *   all ungated, because a link unfurler presents no token when it fetches what a page pointed it
 *   at, and an icon fetcher never presents one at all,
 * - `GET /readyz` readiness (green only after a representative preview actually renders — the
 *   rolling-update gate),
 * - `GET /index.json` Storybook stories index, `GET /iframe.html?id=` isolated story render
 *   (`&format=svg` serves the vector export as an inert SVG image for DOM-capture tools),
 *   ([StorybookCompat]) — the drop-in surface downstream Storybook visual tools consume,
 * - `GET /version` host identity (CLI version, serve schema, public flag),
 * - `GET /bundle.zip` portable bundle, `WS /ws/{id}` streamed-frame lane.
 *
 * A bad/missing token returns **404** (not 401) so the server's existence isn't confirmed to a
 * scanner; the token is compared in constant time ([ServeUrls.tokensMatch]).
 */
class ServeHttpServer(
  private val host: String,
  requestedPort: Int,
  /** The operator's own browse token (`--token`). Read through [serverToken]. */
  token: String,
  private val sessions: ServeSessionRegistry,
  private val defaultSessionId: String,
  /** When non-null, enables `POST /bundles/{name}` for clients to contribute bundles at runtime. */
  private val bundleStore: ServeBundleStore? = null,
  /**
   * Public mode: serve **without** requiring the token — every route is open. For a deployed public
   * preview server (preview.coo.ee) where browsing the published catalogs / uploaded bundles is the
   * point. Safe by construction: rendering a bundle/catalog executes no code, re-rendering
   * untrusted Compose is refused, uploads are size-capped + the `?url=` fetch is SSRF-gated. Off by
   * default, so a normal `serve` stays token-gated (a bad/absent token still 404s).
   */
  private val isPublic: Boolean = false,
  /** Render the streamlined Storybook-like catalog/component browsing presentation. */
  private val componentBrowser: Boolean = false,
  /**
   * In-browser CMP tier: system id → the assembled Wasm app directory (the
   * `:samples:cmp-wasm-catalog:wasmCatalogDist` output). When a catalog session's id is a key here,
   * its viewer offers a "Run in browser (Wasm)" toggle that mounts `/wasm/<system>/?id=<component>`
   * in a sandboxed iframe. The assets are static, generic client code (the same app for everyone,
   * no session data), so the `/wasm/` route is **ungated** — letting the iframe's relative
   * `fetch('./composeApp.wasm')` work without threading the token through every sub-resource.
   */
  private val wasmCatalogs: Map<String, File> = emptyMap(),
  /** Local auto-discovered apps that must use the credential-carrying `/wasm-private/` route. */
  private val privateWasmCatalogs: Set<String> = emptySet(),
  /**
   * Experimental non-JVM Remote Compose player distribution. When present, its static files are
   * served from `/rc-player-wasm/` and RC previews advertise the `cmp-wasm` browser backend.
   */
  private val rcPlayerWasmDir: File? = null,
  /**
   * Design-system catalog sessions that registered (`--catalogs`), e.g. `["compose-m3","wear-m3"]`.
   * Surfaced as `?session=<system>` nav links on the landing page so the public front door lists
   * the served systems instead of hiding them behind the query param. Empty ⇒ no nav row (the
   * default).
   */
  private val catalogSessions: List<String> = emptyList(),
  /**
   * App catalogs registered UNLISTED (`--catalogs-unlisted`), e.g. `["meshcore-mobile","cadence"]`.
   * Served at `/<system>/` exactly like [catalogSessions], but kept OFF the front door: NOT listed
   * on the `/` systems index and NOT on the in-catalog "Design systems" nav row — reachable only by
   * their path / `?session=` (shareable by direct link). This lets an app catalog be published
   * without advertising it on the public landing. They still count toward whether a home index
   * exists, so an app's own landing keeps a "← back" link whenever the server also lists systems.
   */
  private val appCatalogSessions: List<String> = emptyList(),
  /**
   * **Top-level sites** (`--sites m3.preview.coo.ee=m3-catalog`, or `catalogs.json`'s `sites`):
   * host names that serve one already-published catalog as though it were the whole server. See
   * [ServeSites] — it's a routing/presentation view over the same session, not a second tenant, so
   * it costs a map lookup per request and nothing else. Empty (the default) leaves every request
   * behaving exactly as it did before sites existed.
   *
   * The **live** map ([ServeSiteRegistry]) rather than a startup snapshot, so `/admin/sites` can
   * publish a hostname on a running box ([ServeSiteAdmin]).
   */
  private val sites: ServeSiteRegistry = ServeSiteRegistry.empty(),
  /**
   * Configured catalog availability shared with startup + refresh. When present, `/status` includes
   * failed/pending catalogs instead of silently omitting them. Catalog loading remains best-effort:
   * `/readyz` validates a representative usable session, while this tracker makes partial service
   * explicit and recoverable. Null preserves the plain/test server behaviour.
   */
  private val catalogLoads: CatalogLoadTracker? = null,
  /** Immediate catalog branch check exposed by `POST /{system}/refresh`. */
  private val catalogRefresh: ((system: String, force: Boolean) -> CatalogRefreshResult)? = null,
  /** Demand-activated, expiring background RSS generator for published catalog history. */
  private val catalogFeed: ServeCatalogChangeFeed? = null,
  portRange: Int = DEFAULT_PORT_RANGE,
  /**
   * Max renders in flight across the HTTP `/render` lane. Defaults to the host's CPU count so a
   * small box (1–2 vCPU) sheds a render storm instead of thrashing; excess requests wait briefly
   * for a slot, then get `503 + Retry-After`. Renders also serialise inside [ServeRenderHost], so
   * this is a load-shedding bound on concurrent HTTP work, not a parallel-render knob.
   */
  maxConcurrentRenders: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
  /**
   * Live-seat **permit budget** for concurrent **live** (daemon-backed) stream sessions. Each live
   * session charges permits equal to its backend weight ([ServeSessionState.liveSeatWeight]): a
   * desktop CMP daemon costs 1, a heavier Android/Robolectric one costs more, so one heavy catalog
   * can't hog a flat seat count and starve several cheap ones. A session that can't get its permits
   * is refused with WebSocket close 1013 (Try Again Later) rather than spawning a daemon that would
   * risk the OOM killer. `0` (the default) is unbounded — the historical behaviour for a local
   * `serve` on a developer box. Static (snapshot/Wasm) sessions never consume a permit, so the
   * public server's default tiers are unaffected; this only bites when `--allow-render-trusted`
   * puts a live daemon behind a catalog. See [LiveSeatLimiter].
   */
  maxLiveSeats: Int = 0,
  /**
   * The seat budget to charge, when the caller needs to share one with something built earlier.
   * `serve` passes the same limiter it hands the catalog daemon pools, so a pooled render daemon
   * and a live stream draw on one budget instead of two independent ones — the whole point of the
   * budget being "what this box can run at once". Null builds a private limiter from
   * [maxLiveSeats], which is what every other entry point (and every test) wants.
   */
  liveSeatLimiter: LiveSeatLimiter? = null,
  /**
   * Recent daemon **startup failures** — the render/live daemon a session tried to (re)open but
   * couldn't. Populated by [ServeCommand.openHost] (the single choke point every registry-driven
   * relaunch passes through) and surfaced on `/status` + `/status.json`. Null ⇒ no log wired
   * (tests, or a build that doesn't record them); the status page then shows an empty failure list.
   */
  private val daemonLog: DaemonStartupLog? = null,
  /**
   * Whether `--allow-render-trusted` is set (trusted catalogs get a live server-side render lane).
   */
  private val allowRenderTrusted: Boolean = false,
  /**
   * Whether a producer-trust store was configured (`--trust-store`); shown in the status config.
   */
  private val trustStoreConfigured: Boolean = false,
  /** Catalog auto-refresh interval in seconds (`--catalog-refresh-interval`); `0` ⇒ disabled. */
  private val catalogRefreshSeconds: Long = 0,
  /** Whether `POST /bundles` runtime uploads are accepted (`--accept-bundles`). */
  private val acceptBundlesEnabled: Boolean = false,
  /**
   * Runtime catalog administration ([ServeCatalogAdmin]) — publishing and retiring catalogs without
   * recreating the container, persisted to the operator's `catalogs.json`. Null (the default) means
   * the `/admin/catalogs` routes are **not registered at all**, so they 404 like any unknown path.
   */
  private val catalogAdmin: ServeCatalogAdmin? = null,
  /**
   * Runtime producer-trust administration ([ServeTrustAdmin]) — adding and removing trusted
   * branches / keys / CI identities without an image rebuild. Gated by the same [adminToken] as
   * [catalogAdmin]; null ⇒ the `/admin/trust` routes are **not registered at all**.
   *
   * Note this token is more powerful than it looks on a box running `--allow-render-trusted`:
   * trusting a branch there makes that producer's Compose eligible for server-side execution.
   */
  private val trustAdmin: ServeTrustAdmin? = null,
  /**
   * Runtime **site** administration ([ServeSiteAdmin]) — publishing and retiring the hostnames in
   * [sites] without recreating the container, persisted to the same `catalogs.json`. Gated by the
   * same [adminToken]; null ⇒ the `/admin/sites` routes are **not registered at all**.
   */
  private val siteAdmin: ServeSiteAdmin? = null,
  /**
   * Shared secret for the `/admin/catalogs` routes (`--admin-token`). Separate from the browsing
   * [token] on purpose: a public box hands its browse URL to everyone, so admin needs its own
   * credential and must stay gated even when [isPublic] is set. Null/blank ⇒ no admin routes, so a
   * server that never opted in can't be administered at all.
   */
  private val adminToken: String? = null,
  /**
   * When non-null, enables the **document** lane: `GET /docs` (upload page), `POST /docs` (ingest a
   * known document format), and `GET /d/{id}` (the expiring permalink that plays it back). Supplied
   * by `--accept-docs`. Independent of [bundleStore] — a document is a single file with its own
   * short-lived link, not a preview session.
   */
  private val docStore: ServeDocStore? = null,
  /**
   * When non-null, enables the **image** lane: `POST /images` ingests a rendered preview PNG and
   * `GET /i/{id}.png` serves it back at an embeddable URL. Supplied by `--accept-images`.
   *
   * Its sibling above is an anonymous drop-box; this one is not. Uploading requires a GitHub
   * account with access to the operator's repository ([imageUploadAuth]), which `ServeCommand`
   * refuses to start the lane without — so the pair is always wired together, and "the store exists
   * but nothing gates it" is unrepresentable here rather than merely unlikely. Reads are open,
   * because the whole purpose is a URL GitHub's image proxy can fetch on behalf of a PR body; the
   * unguessable id is the access control. See [ServeImageStore].
   */
  private val imageStore: ServeImageStore? = null,
  /** Who may upload to [imageStore]. Non-null exactly when that store is; see its KDoc. */
  private val imageUploadAuth: ServeImageUploadAuth? = null,
  /**
   * Per-caller budget on `POST /images`, keyed by GitHub login. Bounds both the obvious abuse (one
   * account filling the store) and the less obvious one (each uncached upload costs the host two
   * GitHub API calls). Null ⇒ unlimited, which is right for a single-user local host.
   */
  private val imageUploadLimiter: ServeRateLimiter? = null,
  /**
   * When non-null, enables the **playground** lane: `POST /api/{version}/compiler/run` compiles a
   * snippet against a catalog classpath and returns diagnostics + an expiring preview token.
   * Supplied by `--playground-bundle`. Because the lane exists to run **user-supplied code**, it is
   * enabled under `--public` only behind a per-session sandbox that passed the startup containment
   * probe ([PlaygroundPublicGate]); `ServeCommand` decides and simply doesn't wire this in when the
   * gate refuses. See
   * [docs/design/PLAYGROUND.md](../../../../../../../../docs/design/PLAYGROUND.md).
   */
  private val playgroundService: PlaygroundCompileService? = null,
  /**
   * When non-null, enables Stage-2 redemption: `GET /pg/<token>` redeems a preview token into a
   * live streamed session (registered under the token id) and redirects to its viewer. Supplied by
   * `ServeCommand` alongside [playgroundService]; the two share one [PlaygroundTokenStore].
   */
  private val playgroundRedeem: PlaygroundRedeemService? = null,
  /**
   * Optional GitHub auth. When present, public browsing can stay open while code-running surfaces
   * (playground + live WebSocket sessions) require a signed-in GitHub account.
   */
  private val githubAuth: ServeGithubAuth? = null,
  /**
   * When non-null, enables **agent access grants**: `POST /agent-access/request` opens a request,
   * `GET /agent-access/{id}` is the human approval page, and `POST /agent-access/poll` hands the
   * minted bearer to the agent that asked. Supplied by `--agent-grants`. See
   * [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md).
   *
   * A grant satisfies the same three gates a human does — [rejectBadToken],
   * [rejectMissingGithubAuth], [rejectMissingGithubRepoAccess] — so enabling this adds one new way
   * to answer the existing questions and no new surface behind them.
   */
  private val agentGrants: ServeAgentGrantStore? = null,
  /**
   * Per-caller budget on the two **ungated** grant routes (`request` and `poll`), keyed by client
   * address. Ungated is the point — an agent with no credential must be able to ask — so this is
   * the only thing standing between the route and an anonymous caller filling the request map. Null
   * ⇒ unlimited, which is right for a single-user local host.
   */
  private val agentGrantLimiter: ServeRateLimiter? = null,
  /**
   * Observability for the playground lane on `/status.json` — which posture admitted it, whether
   * the configured jail actually contains anything on this host, and whether each mode's classpath
   * has resolved. Null when the lane isn't wired at all. See [PlaygroundHealth].
   */
  private val playgroundHealth: (() -> PlaygroundHealth)? = null,
  /**
   * Delivery-branch read counters for `/status.json`, read from the catalog store that owns them. A
   * provider rather than the snapshot itself because `/status.json` is polled and the numbers move;
   * null for a server with no catalog store, whose branch-read count is not zero but undefined.
   */
  private val branchFetchStats: (() -> BranchFetchSnapshot?)? = null,
  /** Cross-catalog optimizer admission for `/status.json`, read from the shared background-work. */
  private val themeOptimizerStats: (() -> ThemeOptimizerAdmissionSnapshot?)? = null,
  /** Disk tier for warmed theme renders, for `/status.json`. Null when persistence is off. */
  private val themeCacheStats: (() -> ThemeCacheStoreSnapshot?)? = null,
  /**
   * The catalog blob cache's occupancy and read outcomes for `/status.json`, read from the pool
   * that owns them. Null on a server with no catalogs, whose pool is not merely empty but unused.
   */
  private val catalogCacheStats: (() -> CatalogBlobPoolSnapshot?)? = null,
  /**
   * Drop everything the catalog blob cache holds, for `DELETE /admin/catalog-cache`. Separate from
   * [catalogCacheStats] for the reason the optimizer's pause is separate from its counters: reading
   * occupancy is safe on any server, and discarding it is an operator action that wants the token.
   */
  private val catalogCacheClear: (() -> CatalogBlobPoolSnapshot)? = null,
  /**
   * The shared background-work handle, for the admin pause/resume routes. Separate from
   * [themeOptimizerStats] because reading counters is safe on any server while standing the
   * optimizer down is an operator action and wants the admin token.
   */
  private val themeOptimizerAdmin: ServeBackgroundWork? = null,
  /**
   * Per-caller budget on the compile lane (issue #3214), or null to leave it unmetered. Every other
   * playground bound is a whole-host one, so without this two callers issuing back-to-back compiles
   * hold every slot and everyone else is told the playground is busy.
   */
  private val playgroundRateLimiter: ServeRateLimiter? = null,
  /**
   * Trust the **last** entry of `X-Forwarded-For` as the client address when rate-limiting an
   * anonymous caller, instead of the socket peer.
   *
   * Off by default and opt-in for a reason: the header is client-supplied, so trusting it on a
   * directly-exposed host lets a caller forge a fresh identity per request and bypass the limit
   * entirely. The *last* entry — not the first — is the one a single reverse proxy appended from
   * the peer address it actually saw (nginx's `$proxy_add_x_forwarded_for`), which a client cannot
   * forge. That is exactly one hop's worth of trust; behind two proxies this names the inner one.
   */
  private val trustForwardedFor: Boolean = false,
  /**
   * Reads a preview's Kotlin off GitHub so `/playground?from=…` can open it — the "try this preview
   * in the playground" handoff. Null disables the handoff (and its links); the playground itself is
   * unaffected. Injected rather than built here so tests never reach the network.
   */
  private val playgroundSourceFetch: ((String) -> ByteArray?)? = null,
  /** Aggregate view counts; pass a file-backed store to keep them across server restarts. */
  private val engagementStore: ServeEngagementStore = ServeEngagementStore(),
  /**
   * Project mode's render-history source, computed from the local repository. When non-null, a
   * viewer whose session has **no delivery provenance** (i.e. not a catalog) inlines that preview's
   * timeline and enables `GET /history/render/{blob}.png`, which serves an old render out of the
   * local object store. Null — every hosted/bundle-only box — leaves both out entirely, so the
   * route 404s rather than existing unwired.
   */
  private val projectHistory: ServeProjectHistory? = null,
  /** Trusted module roots for local browse sessions, keyed by their session ids. */
  private val localSourceRoots: Map<String, File> = emptyMap(),
) {

  /**
   * Resolves `/playground?from=<system>/<previewId>` to that preview's source. Built here rather
   * than injected because the lookup is this server's own registry: the request names a system and
   * a preview id, and everything that forms the fetch URL comes from the catalog metadata behind
   * them. Null when no fetcher was supplied, or the lane isn't wired at all.
   */
  private val playgroundSeeds: PlaygroundSeedResolver? = playgroundSourceFetch
  // Deliberately NOT gated on `playgroundService`. The resolver reads and cleans a preview's
  // source, which the viewer's Source panel wants on every host that can browse a catalog —
  // including the many that cannot compile it (a pin-only host, or one with no Robolectric
  // sidecar, which is every Android and Wear catalog on the public deployment). Only the
  // *handoff* needs a compiler, and `playgroundLinkFor` checks for one itself.
  ?.let { fetch ->
    PlaygroundSeedResolver(
      locate = ::sourceLocationFor,
      fetch = fetch,
      onLog = { System.err.println("serve: playground seed: $it") },
    )
  }

  /**
   * Answers the Dev-mode `uses:` filter — which previews call a given composable. Built on exactly
   * the metadata and fetcher the seed resolver uses, and for the same reason: a preview's source
   * location is this server's own registry, and one lookup should not disagree with the other.
   *
   * Null when no fetcher was supplied, which is also when the Source panel is absent — a host that
   * cannot read a preview's source cannot index its calls either.
   */
  private val previewUsage: PreviewUsageIndex? = playgroundSourceFetch?.let { fetch ->
    PreviewUsageIndex(
      locate = ::sourceLocationFor,
      fetch = fetch,
      onLog = { System.err.println("serve: usage index: $it") },
    )
  }

  /**
   * Where a preview's source lives, across the three states a session can be in.
   *
   * Shared by the seed resolver and the usage index rather than written twice: both are answering
   * "which file is this preview declared in", and a second copy of the resident/suspended/retired
   * ladder below is a place for them to drift apart.
   */
  private fun sourceLocationFor(
    system: String,
    previewId: String,
  ): PlaygroundSeedResolver.Location? {
    // peekHost, not lease: this is a metadata read, and leasing would stand a daemon up just
    // to answer where a file lives.
    val host = sessions.peekHost(system)
    return when {
      // Resident: authoritative, in BOTH directions. A live host that does not list this
      // preview — a catalog refreshed under the same id with it dropped, or its source
      // metadata cleared — has to answer "no". Falling through to a remembered location there
      // would serve the previous publication's source instead of a 404, so its negative
      // answer drops the stale entry too.
      host != null -> sourceLocationOf(host, previewId)
      // Suspended, but still a session this server serves: answer from the snapshot taken as
      // it was suspended. Taken from the host being suspended, so a catalog refreshed and then
      // idled out snapshots the REPLACEMENT — the case a lazily-primed map got wrong, since a
      // stale entry could answer a tab that loaded before the refresh.
      sessions.isKnownSession(system) -> catalogSourceLocationsSeen[system]?.get(previewId)
      // Retired: the catalog is gone and every other session-backed route for it 404s. A
      // remembered location would keep answering — and keep re-fetching the old repository
      // once the seed's TTL lapsed — long after the catalog was withdrawn.
      else -> null
    }
  }

  /** Where a preview's source lives according to [host], or null when it cannot say. */
  private fun sourceLocationOf(
    host: ServeHost,
    previewId: String,
  ): PlaygroundSeedResolver.Location? {
    val bundleHost = catalogBundleHost(host) ?: return null
    val source = bundleHost.catalogSource ?: return null
    val preview = bundleHost.previews.firstOrNull { it.id == previewId } ?: return null
    val sourceFile = preview.sourceFile?.takeIf { it.isNotBlank() } ?: return null
    return PlaygroundSeedResolver.Location(
      repo = source.repo,
      ref = source.ref,
      module = preview.sourceModule ?: source.module,
      sourceFile = sourceFile,
      // Absent on a catalog published before discovery recorded it, which is exactly the case the
      // resolver falls back to whole-file seeding for.
      bodyLine = preview.bodyLine,
    )
  }

  /** The actual bound port — may differ from the requested one if it was taken (auto-picked). */
  val port: Int = pickPort(host, requestedPort, portRange)

  /** Concurrent-render slot count (the `/render` load-shed bound), surfaced on `/status`. */
  private val renderSlots: Int = maxConcurrentRenders.coerceAtLeast(1)

  /** Wall-clock the server was constructed — the basis for the `/status` uptime figure. */
  private val startedAtMillis: Long = System.currentTimeMillis()

  /**
   * Frame counters for the live socket lane, reported on `/status.json` as `liveFrames`. Owned here
   * rather than by a host because a socket outlives the streams it opens and can move between
   * previews; the recorder follows the client, not the daemon. See [LiveFramePerfStats].
   */
  private val liveFrameStats = LiveFramePerfStats()

  private val renderSemaphore = Semaphore(renderSlots)
  private val unleasedThemeSemaphore = Semaphore(1)
  private val themeRenderLeases = ThemeRenderLeaseManager(renderSlots)

  /** Catalog ids with a manual branch check in flight; public callers coalesce at this boundary. */
  private val catalogRefreshesInFlight = ConcurrentHashMap.newKeySet<String>()

  /**
   * Readiness latch for `/readyz` (the rolling-update gate). Unlike `/healthz` — a static "ok" that
   * only proves the HTTP listener is up — readiness is `true` only once a representative preview
   * has *actually rendered* on this host, so docker-rollout won't drain traffic onto (and retire
   * the old replica for) a new container whose render pipeline is broken or whose catalogs failed
   * to load. Latches on the first success and stays set: the probe render is a baked, override-free
   * snapshot for a catalog session (cheap, never wakes the daemon — see
   * [ServeCatalogLiveHost.render]), but a plain daemon module would pay its cold render, so it runs
   * at most once (see [readinessProber]) and the poll only ever reads this flag.
   *
   * Set by the **server-owned** [readinessProber] thread, never inside a request coroutine: the
   * `/readyz` handler must stay instant so a health checker's short command timeout (the Docker
   * healthcheck allows 5s) can't cancel a slow first render mid-flight and discard the result — the
   * render happens off the request path, latches here when it lands, and the next poll sees it.
   */
  private val ready = AtomicBoolean(false)

  /** Starts [readinessProber] exactly once, on the first `/readyz` poll (idempotent). */
  private val readinessProbeStarted = AtomicBoolean(false)

  /**
   * The server-owned background thread that renders the representative preview until it succeeds,
   * then latches [ready]. Kicked off lazily by the first `/readyz` poll (so a plain `serve` that's
   * never health-checked pays no eager render) and interrupted on [stop]. Retries on failure so a
   * daemon still cold-starting eventually flips ready without the request path ever blocking.
   */
  @Volatile private var readinessProber: Thread? = null

  /**
   * Live-seat limiter: a permit **budget** ([maxLiveSeats]) charged per session by its backend
   * weight, so a heavy Android daemon costs more of the box than a cheap desktop CMP one. `<= 0` ⇒
   * unbounded. See [maxLiveSeats] and [LiveSeatLimiter].
   */
  private val liveSeats: LiveSeatLimiter = liveSeatLimiter ?: LiveSeatLimiter(maxLiveSeats)

  /**
   * Prebaked front-door hero thumbnails, served by the `/hero/` route. Baked once per catalog host
   * (see [rememberCatalogMeta]) so the public landing costs the server a handful of map lookups
   * rather than a dozen full-resolution renders. See [ServeHeroImages].
   */
  private val heroImages = ServeHeroImages()

  /**
   * Drawn link-unfurl cards, served by the `/social/` route. Composed from the hero thumbnails
   * above — so a card costs no render and no extra decode of a full-resolution PNG — and memoised
   * by its inputs. See [ServeSocialCard].
   */
  private val socialCards = ServeSocialCard()

  /**
   * Whether the `/admin/catalogs` routes exist on this server: both an administrator
   * ([catalogAdmin]) and an [adminToken] are required. Fail-closed by construction — an operator
   * who never set a token gets no admin surface, not an open one.
   */
  private val adminEnabled: Boolean = catalogAdmin != null && !adminToken.isNullOrBlank()

  /** As [adminEnabled], for the `/admin/trust` routes. Same token, separately supplied admin. */
  private val trustAdminEnabled: Boolean = trustAdmin != null && !adminToken.isNullOrBlank()

  /** As [adminEnabled], for the `/admin/sites` routes. Same token, separately supplied admin. */
  private val siteAdminEnabled: Boolean = siteAdmin != null && !adminToken.isNullOrBlank()

  /**
   * As [adminEnabled], for `DELETE /admin/catalog-cache`.
   *
   * Paired with the token like every sibling rather than registered on the handle alone: discarding
   * the cache is destructive-ish (it costs a re-fetch, not data), and a box whose operator never
   * configured a credential must not expose it at all.
   */
  private val catalogCacheAdminEnabled: Boolean =
    catalogCacheClear != null && !adminToken.isNullOrBlank()

  private val server: EmbeddedServer<*, *> =
    embeddedServer(CIO, host = host, port = port) {
      install(WebSockets)
      // Top-level sites ([ServeSites]): make the canonical `/<system>/…` spelling behave, on a site
      // host, as though this box served only that one catalog. Registered before routing (and only
      // when sites are configured, so an ordinary server has no interceptor at all) because it has
      // to answer INSTEAD of the `/{system}/…` handlers, not after them.
      //
      // Two cases, and both cost one map lookup on a request that would otherwise be served anyway:
      //   • this site's own system — 301 to the same page's rooted URL, so `m3.preview.coo.ee/`
      //     and `…/m3-catalog/` don't compete as two spellings of one page in a crawler's index;
      //   • another served catalog — 404, because a neighbour reachable through this domain is
      //     precisely what a top-level site exists not to be. Only ids this server actually serves
      //     are considered, so every constant route (`/p/…`, `/render/…`, `/assets/…`, `/healthz`)
      //     falls straight through untouched.
      // Installed when a site is configured OR when one could be published at runtime: the
      // interceptor is what makes a site host behave like a site at all, and a `/admin/sites`
      // registration on a box that started with none would otherwise take effect only on the next
      // restart — the exact staleness the route exists to remove. On a server with neither, there
      // is still no interceptor at all.
      if (!sites.isEmpty || siteAdminEnabled) {
        intercept(ApplicationCallPipeline.Plugins) {
          val current: ApplicationCall = context
          val system = current.siteSystem() ?: return@intercept
          val path = current.request.path()
          val first = decodeSegment(path.trimStart('/').substringBefore('/'))
          if (first.isEmpty()) return@intercept
          if (first == system) {
            // `trimStart` on the remainder as well as the head: `/<system>//evil.example` would
            // otherwise build `//evil.example`, which a browser reads as a protocol-relative URL
            // to another origin — an open redirect on every site host. The target must be exactly
            // one leading slash, i.e. same-origin by construction.
            val rest = path.trimStart('/').substringAfter('/', "").trimStart('/')
            val query = current.request.queryString().prefixedQuery()
            // 308, not 301: the canonical prefix also carries POST routes (`/{system}/refresh`,
            // `/{system}/api/presence`, the theme-lease pair), and a 301 is re-issued as GET by
            // most clients — the request would arrive at the rooted path with the wrong method.
            current.response.headers.append(HttpHeaders.Location, "/$rest$query")
            current.respond(HttpStatusCode.PermanentRedirect)
            finish()
          } else if (!isRootedRoute(first)) {
            // The site's OWN styled 404 — every mistyped path on a site hostname lands here, so a
            // plain string would undo the one-skin-per-hostname property for the page a visitor is
            // most likely to meet.
            //
            // …but ONLY for a caller who is already allowed to see this server. This interceptor
            // runs BEFORE the routes' own `rejectBadToken`, and `notFoundPage` threads the access
            // token through its links — so on a token-gated box the styled page would have handed
            // the secret to any unauthenticated request for a made-up path, which is every scanner.
            // An unauthorized caller gets the same bare 404 the token gate itself answers with.
            if (!current.isAuthorizedCall()) {
              current.respondText("not found", status = HttpStatusCode.NotFound)
              finish()
              return@intercept
            }
            val skin = current.siteSkin()
            current.markGeneration("static-page", current.pageCacheControl())
            current.respondText(
              ServeWeb.notFoundPage(
                "That page was not found on this site.",
                current.linkToken(),
                isPublic,
                version = SERVE_VERSION,
                siteName = skin.first,
                themeCss = skin.second,
                themeStorageKey = skin.third,
                componentBrowser = current.componentBrowserMode(),
                githubAuth =
                  githubAuth?.let { auth ->
                    ServeWeb.GitHubAuthStatus(
                      loginHref = auth.loginPath(current),
                      login = auth.currentLogin(current),
                      restrictedToAllowedUsers = auth.isRestrictedToAllowedUsers(),
                    )
                  },
              ),
              ContentType.Text.Html,
              HttpStatusCode.NotFound,
            )
            finish()
          }
        }
      }
      // Answer HEAD everywhere GET is answered. Every route on this server is registered with
      // `get`, so without this a HEAD got 405 where a constant path segment matched and 404 where
      // routing needed a `{system}` — the whole site was un-HEAD-able.
      //
      // That is what broke link unfurling: an unfurler probes a URL and its `og:image` with HEAD
      // before committing to a download, and a 4xx there reads as "this link is dead" rather than
      // "this server only speaks GET". The same probe is what link checkers, uptime monitors and
      // `curl -I` use, so all three were being told the site was broken.
      //
      // The plugin re-runs the GET pipeline and drops the body, so the headers a probe is asking
      // about (content type and length, `Cache-Control`, ETag, the generation marker) match the GET
      // exactly — which is the whole point of the probe.
      install(AutoHeadResponse)
      // Sliding sessions: any request carrying a session past its half-life gets a freshly signed
      // cookie, so a visitor who keeps coming back is never bounced through GitHub. Runs before
      // routing so it covers every response, and no-ops (no `Set-Cookie` at all) for a young
      // session or no session. See [ServeGithubAuth.refreshSession].
      githubAuth?.let { auth ->
        install(
          createApplicationPlugin("github-session-refresh") {
            onCall { call -> auth.refreshSession(call) }
          }
        )
      }
      // Compress the text-ish lanes only. Every page, `/status.json`, the figma-svg exports and
      // the baked CSS/JS are markup that gzips 3-8x, and the biggest of them (a vendored editor,
      // an SVG export) dominate their page's transfer.
      //
      // Deliberately an ALLOWLIST, not "everything except a few": this host's heavy lanes are
      // already-compressed bytes — catalog PNGs (`/render`, `/hero`), packed `.bundle` images,
      // and the multi-megabyte Wasm app tier. Gzip cannot shrink those, so compressing them would
      // burn CPU per request on a box that is also running render daemons, and re-encoding an
      // 8 MB Wasm payload on every visit is exactly the kind of cost this change is meant to
      // avoid. An unlisted type is served through untouched.
      //
      // `minimumSize` keeps the small stuff alone: `/healthz` ("ok") and `/readyz` are polled on a
      // ~10s healthcheck loop, and framing a 2-byte body costs more than it saves.
      install(Compression) {
        gzip {
          matchContentType(
            ContentType.Text.Html,
            ContentType.Text.CSS,
            ContentType.Text.Plain,
            ContentType.Text.JavaScript,
            ContentType.Application.JavaScript,
            ContentType.Application.Json,
            ContentType.Image.SVG,
          )
          minimumSize(1024)
        }
      }
      routing {
        // `/healthz` — ungated liveness: "ok" the moment the listener is up. Leaks nothing, and
        // proves nothing beyond "the process is answering HTTP". The rolling-update gate is
        // `/readyz` below, not this.
        get("/healthz") { call.respondText("ok") }

        // `/readyz` — ungated READINESS: "ready" only once a representative preview has actually
        // rendered on this host (see [ready]). This is the gate docker-rollout should wait on
        // before
        // it drains traffic onto a new replica and retires the old one — `/healthz` going green
        // only
        // means the port bound, so a replica whose render pipeline is broken (dead daemon, missing
        // baked fallback, empty/failed catalog load) would pass it and get promoted into a 500-ing
        // live server. 503 ("warming") until the first render succeeds; then it latches green.
        get("/readyz") { handleReadyz() }

        // `/version` — ungated machine-readable identity for the host: the CLI version, the serve
        // API schema, and whether this box runs open (public) or token-gated. Lets a deployer,
        // Watchtower check, or the design-artifacts gallery confirm which build is live without a
        // token, and keeps the released version OUT of the HTML goldens (it lives here, not in the
        // landing footer, so a release never churns the fixture diff).
        get("/version") {
          call.respondText(
            JSON.encodeToString(
              VersionResponse.serializer(),
              VersionResponse(version = SERVE_VERSION, public = isPublic),
            ),
            ContentType.Application.Json,
          )
        }

        githubAuth?.let { auth ->
          // The site hosts are the allowlist for the post-callback return redirect: the only
          // hostnames a sign-in started elsewhere may be sent back to. Passed in rather than known
          // to the auth object, so the site config keeps one home.
          get(ServeGithubAuth.START_PATH) { with(auth) { handleStart(sites.hosts) } }
          get(ServeGithubAuth.CALLBACK_PATH) { with(auth) { handleCallback(sites.hosts) } }
        }

        // The agent-grant lane (`--agent-grants`): an agent with no credential asks for one, a
        // human approves it in a browser, and the agent collects a short-lived bearer. See
        // [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md).
        //
        // `request` and `poll` are deliberately **ungated** — an agent that could already
        // authenticate would have no reason to be here — so both are per-address rate limited and
        // neither grants anything on its own: `request` only parks an entry a human must act on,
        // and `poll` answers only to the device secret it minted. The approval routes are the
        // opposite: they require a real operator identity and are the one place a grant is born.
        //
        // Constant first segments, so they outscore the `/{system}` catch-all.
        agentGrants?.let { store ->
          post(ServeAgentGrants.REQUEST_PATH) { handleAgentGrantRequest(store) }
          post(ServeAgentGrants.POLL_PATH) { handleAgentGrantPoll(store) }
          post(ServeAgentGrants.REVOKE_PATH) { handleAgentGrantRevoke(store) }
          get(ServeAgentGrants.WHOAMI_PATH) { handleAgentGrantWhoami(store) }
          get("${ServeAgentGrants.BASE_PATH}/{requestId}") { handleAgentGrantPage(store) }
          post("${ServeAgentGrants.BASE_PATH}/{requestId}") { handleAgentGrantDecision(store) }
          post("${ServeAgentGrants.BASE_PATH}/{grantId}/revoke") {
            handleAgentGrantRevokeFromStatus(store)
          }
        }

        // `/status` — the operator/observer view of this running host: published catalogs + their
        // trust/liveness/load errors, the render daemons up right now, the effective config, and
        // recent daemon startup failures. HTML by default (`?format=json` for the machine form);
        // `/status.json` is
        // the canonical JSON a monitor / Home Assistant sensor polls. Both are gated like the rest
        // (open in `--public`, else token-required) — the running-daemon + config detail is more
        // sensitive than `/version`/`/healthz`, so a private box keeps it behind the token.
        get("/status") { handleStatus(json = false) }
        get("/status.json") { handleStatus(json = true) }

        // `/report-bug` — file a bug against the repo that ships THIS SERVER, prefilled with the
        // diagnostics a triager would otherwise have to ask for. Distinct from the per-preview
        // "report an issue" affordance, which files a *preview* bug against the project whose code
        // declares it; see [ServeBugReport] for why the two are separate reports rather than one
        // with a repo switch. Gated exactly like `/status`, and for the same reason: it reports
        // the same catalog-load and daemon-failure detail, so a private box keeps it behind the
        // token.
        get(ServeBugReport.PATH) { handleBugReport() }

        // The crawler-facing pair (see [ServeSiteIndex]). Both are deliberately UNGATED even on a
        // token-gated host: a crawler has no token, and answering the styled HTML 404 — which is
        // what these paths did before they existed — tells it nothing. A private server's
        // `robots.txt` says "disallow everything" and its sitemap is simply absent, which is the
        // honest answer and the one that keeps its URLs out of an index.
        get("/robots.txt") { handleRobotsTxt() }
        get("/sitemap.xml") { handleSitemapXml() }

        // Shared frontend assets for ServeWeb pages. These are generic CSS/JS bytes baked into the
        // CLI jar, so they are ungated like the Wasm/RC players and cacheable with an ETag.
        get("/assets/serve/{name}") { handleServeWebAsset(versioned = false) }
        get("/assets/serve/{version}/{name}") { handleServeWebAsset(versioned = true) }

        // In-browser CMP tier: serve the static Wasm app for a registered system at
        // `/wasm/<system>/<file>`. Ungated (generic client code, no session data) so the viewer's
        // sandboxed iframe and its relative asset fetches work without a token.
        //
        // Registered UNCONDITIONALLY. [wasmCatalogs] is a live view of the served catalogs' apps,
        // but routes are installed once, at bind time — and since #3127 the listener binds BEFORE
        // the catalogs load, so a `wasmCatalogs.isNotEmpty()` guard here always saw an empty map
        // and dropped the route for the whole process lifetime. The viewer meanwhile reads the
        // same live map later (it offers "Run in browser (Wasm)" for any system present in it), so
        // the toggle appeared while every `/wasm/…` fetch 404'd — the serve-lanes E2E's "Wasm
        // iframe re-renders on knob override" failure. An unknown system 404s inside the handler
        // either way, so the route costs nothing when no app is ever registered.
        get("/wasm/{system}/{path...}") { handleWasmAsset(privateRoute = false) }
        // Auto-discovered local apps are project output, not the generic/published client assets
        // `/wasm/` was designed for. Put the token in the path so relative JS/Wasm requests retain
        // it, and reject the ordinary route for the same system to prevent a token-free bypass.
        get("/wasm-private/{access}/{system}/{path...}") { handleWasmAsset(privateRoute = true) }

        // The CMP/Wasm Remote Compose player is a single shared app rather than a per-catalog app.
        // Keep it opt-in while operation coverage is incomplete; an unset directory simply makes
        // this route 404 and leaves the selector chip disabled.
        get("/rc-player-wasm/{path...}") {
          val dir = rcPlayerWasmDir
          if (dir == null) {
            call.respondText("not found", status = HttpStatusCode.NotFound)
            return@get
          }
          val segments = call.parameters.getAll("path").orEmpty().filter { it.isNotEmpty() }
          val rel = if (segments.isEmpty()) "index.html" else segments.joinToString("/")
          val base = dir.toPath().toAbsolutePath().normalize()
          val resolved = base.resolve(rel).normalize()
          if (!resolved.startsWith(base) || !resolved.toFile().isFile) {
            call.respondText("not found", status = HttpStatusCode.NotFound)
            return@get
          }
          val file = resolved.toFile()
          val etag = "\"${file.length().toString(16)}-${file.lastModified().toString(16)}\""
          // These filenames are stable across preview-host releases. Revalidate every use so a
          // rollout cannot leave an already-open browser executing an older protocol decoder for
          // another hour; unchanged multi-megabyte assets still take the cheap ETag/304 path.
          call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
          call.response.headers.append(HttpHeaders.ETag, etag)
          if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
            call.respond(HttpStatusCode.NotModified)
            return@get
          }
          val bytes = withContext(Dispatchers.IO) { file.readBytes() }
          call.respondBytes(bytes, wasmContentType(file.name))
        }

        // Prebaked front-door hero thumbnails ([ServeHeroImages]). Deliberately NOT the `/render`
        // lane: the bytes are already cropped, downscaled and resident in memory, so this takes no
        // session lease and no render permit — it can't queue behind a catalog render, and it can't
        // wake an idle daemon. The name IS the content hash, so the response is `immutable`: a
        // repeat visitor's browser serves the whole front door's imagery from cache without asking.
        // A constant first segment, so it outscores the `/{system}` catch-all in Ktor routing.
        get("/hero/{system}/{name}") { handleHeroImage() }

        // The drawn link-unfurl card a page advertises as its `og:image` ([ServeSocialCard]).
        // Cached exactly like `/hero/` and for the same reason — the name is the content hash — but
        // the immutability matters more here: unfurlers key their caches by URL and several never
        // revalidate, so a card URL whose pixels could change would pin a stale picture in Slack
        // indefinitely. A constant first segment, so it outscores the `/{system}` catch-all.
        get("/social/{name}") { handleSocialCard() }

        // The site icon, in the three forms the web asks for ([ServeSiteIcon]). These are what an
        // unfurl card shows *beside* the picture — Slack, iMessage, Discord and Google all resolve
        // a site icon from the page's `<link rel="icon">` tags or by probing `/favicon.ico`, and
        // before these routes existed every one of them fell back to a generic globe. Ungated even
        // on a token-gated server: an icon carries no session data, and a favicon fetch never
        // carries the token anyway (the browser requests it outside the page's query string), so
        // gating it would only guarantee the blank tab it is here to fix.
        get(ServeSiteIcon.SVG_PATH) { respondSiteIcon(ServeSiteIcon.svg) }
        get(ServeSiteIcon.ICO_PATH) { respondSiteIcon(ServeSiteIcon.ico) }
        get(ServeSiteIcon.APPLE_TOUCH_PATH) { respondSiteIcon(ServeSiteIcon.appleTouchIcon) }

        // The in-browser Remote Compose player: a single shared IIFE bundle (global `RC`), baked
        // into the CLI jar as a classpath resource and served here so the viewer's client-side
        // `<canvas>` render lane (fetch `/render/<id>.rc` → `RC.RcdPlayer`) can load it. Always
        // available (unlike the operator-gated Wasm apps) — the bundle rides in the jar, not a
        // per-catalog dir. Ungated (generic client code, no session data) and CORS-open like the
        // Wasm assets so a sandboxed viewer iframe can pull it. A constant first segment, so it
        // outscores the `/{system}` catch-all in Ktor routing.
        get("/rc-player/bundle.js") { respondPlayerAsset(playerAsset(RC_PLAYER_RESOURCE)) }

        // The typefaces that player draws a document's *generic* families in ([ServeRcFonts]): the
        // generated `@font-face` stylesheet plus the vendored face files it points at. Registering
        // them is what makes the browser lane comparable to the baked PNG beside it — unregistered,
        // the player's `Roboto, sans-serif` request falls through to the viewer's own generics
        // (issue #3480). Ungated and CORS-open for the same reason as the player bundle: font bytes
        // baked into the jar, no session data.
        get("${ServeRcFonts.URL_BASE}/{name}") { handleRcFont() }

        // The document lane (`--accept-docs`): ingest one **known document format** (Remote Compose
        // or Lottie — see [ServeDocFormats]) and hand back an expiring permalink that plays it in
        // the browser. Registered only when the operator opts in. Constant first segments, so they
        // outscore the `/{system}` catch-all in Ktor routing.
        // The image lane (`--accept-images`): ingest a rendered preview PNG from an authenticated
        // GitHub collaborator and serve it back at an embeddable URL, so an agent can put real
        // before/after pixels in a PR body from a box with no `gh` and no push rights. Two routes
        // and no page: the caller is a script, and a browse surface over other people's uploads is
        // the one thing an unguessable-link store must not grow. Registered only when the operator
        // opts in; constant first segments, so they outscore the `/{system}` catch-all.
        if (imageStore != null && imageUploadAuth != null) {
          post("/images") { handleImageUpload(imageStore, imageUploadAuth) }
          get("/i/{id}") { handleImage(imageStore) }
        }

        docStore?.let { store ->
          get("/docs") { handleDocUploadPage(store) }
          post("/docs") { handleDocUpload(store) }
          get("/d/{id}") { handleDocPage(store) }
          get("/d/{id}/raw") { handleDocRaw(store) }
          // Each format's vendored browser player, looked up in the registry rather than routed
          // per-format. Ungated + CORS-open like `/rc-player/bundle.js` (generic client code, no
          // session data).
          get("/doc-player/{format}/bundle.js") { handleDocPlayer() }
        }

        // The playground lane (`--playground-bundle`): compile a snippet against a catalog
        // classpath
        // and return diagnostics + an expiring preview token. The frontend inserts a `{version}`
        // path segment (e.g. `/api/1/compiler/run`) which we capture and ignore. The constant
        // `/api`
        // first segment outscores the `/{system}` catch-all. Never registered under `--public`.
        if (playgroundService != null) {
          val svc = playgroundService
          post("/api/{version}/compiler/run") { handlePlaygroundRun(svc) }
          if (svc.editLeasesEnabled) {
            post("/api/{version}/compiler/edit-lease") { handlePlaygroundEditLeaseAcquire(svc) }
            post("/api/{version}/compiler/edit-lease/release") {
              handlePlaygroundEditLeaseRelease(svc)
            }
          }
          // The runtime catalog selector's list. Fetched by the editor on load rather than only
          // baked into the page: catalogs are fetched in the background *after* the server is up,
          // so
          // a page rendered during startup would otherwise show a short (or empty) selector and
          // never learn better without a manual reload.
          get("/api/{version}/compiler/catalogs") { handlePlaygroundCatalogs(svc) }
          // The Stage-1 editor page (`GET /playground`): the browser surface that POSTs to the run
          // route above and surfaces the diagnostics + first-frame + `/pg/` (live) or `/d/` (RC)
          // handoff. Only mounted when the lane is enabled, and — like the lane — never under
          // `--public`.
          get("/playground") { handlePlaygroundPage(svc) }
        } else {
          // Reserve the well-known page path even when the compile lane is disabled. Otherwise
          // public catalog hosts route `/playground` through the `/{system}` catch-all and report
          // that a design system named "playground" does not exist.
          get("/playground") { handlePlaygroundDisabledPage() }
        }

        // Stage-2 redemption (`GET /pg/<token>`): redeem a preview token into a live streamed
        // session and redirect to its viewer. Mounted with the playground lane; token-gated.
        // The path segment is named `{pgToken}`, NOT `{token}`: on a token-gated host the access
        // token rides as `?token=…`, and `call.parameters` merges path + query, so a `{token}` path
        // segment would collide with the access token and redeem the wrong id (a NotFound 404).
        playgroundRedeem?.let { redeem -> get("/pg/{pgToken}") { handlePlaygroundRedeem(redeem) } }

        // Shared/public mode ingestion: a client contributes a pre-rendered bundle (upload the zip
        // as the body, or pass `?url=` to a build-results artifact) and gets back a ?session= link.
        // Only registered when the operator opts in (a bundle store is supplied).
        bundleStore?.let { store ->
          post("/bundles/{name}") {
            if (rejectBadTokenForIngest()) return@post
            val name = call.parameters["name"]
            if (name.isNullOrBlank()) {
              call.respondText("missing bundle name", status = HttpStatusCode.BadRequest)
              return@post
            }
            val url = call.request.queryParameters["url"]
            // Cap the uploaded body as it streams in — receiving it whole into memory first would
            // let a client OOM the server regardless of the store's later extraction cap.
            val body =
              if (url == null) {
                withContext(Dispatchers.IO) {
                  call.receiveStream().use { readCapped(it, MAX_UPLOAD_BYTES) }
                }
                  ?: run {
                    call.respondText(
                      "bundle exceeds ${MAX_UPLOAD_BYTES / (1024 * 1024)}MB",
                      status = HttpStatusCode.PayloadTooLarge,
                    )
                    return@post
                  }
              } else {
                null
              }
            val result =
              withContext(Dispatchers.IO) {
                // isSecurityChecked = true: this route is token-gated (rejectBadToken above) and
                // the
                // store still defends in depth (name sanitisation, zip-slip, size cap; SSRF host
                // allowlist for the url case). The marker records the entry point was authorised.
                if (url != null) store.addFromUrl(name, url, isSecurityChecked = true)
                else store.add(name, body!!, isSecurityChecked = true)
              }
            when (result) {
              is ServeBundleStore.Result.Ok ->
                call.respondText(
                  JSON.encodeToString(
                    BundleAcceptedResponse.serializer(),
                    BundleAcceptedResponse(
                      session = result.name,
                      previews = result.previewCount,
                      path = "/?session=${result.name}",
                      trust = result.trust,
                    ),
                  ),
                  ContentType.Application.Json,
                  HttpStatusCode.Created,
                )
              is ServeBundleStore.Result.Failed ->
                call.respondText(result.reason, status = HttpStatusCode.BadRequest)
            }
          }
        }

        // Runtime catalog administration: publish a catalog (`POST /admin/catalogs`), retire one
        // (`DELETE /admin/catalogs/{system}`), or list what's configured (`GET /admin/catalogs`).
        // The catalog set is operator config, not image content — these routes are how it's edited
        // on a running box, and every mutation is written back to `catalogs.json` so it survives a
        // restart. Registered ONLY when both an admin implementation and an `--admin-token` are
        // present, so a server that didn't opt in has no admin surface to find.
        if (themeOptimizerAdmin != null && !adminToken.isNullOrBlank()) {
          val optimizer = themeOptimizerAdmin
          // Stand the optimizer down for a while, without a restart.
          //
          // Restarting was the only lever before, and it is the worst one available while a box is
          // struggling: it throws away every warm daemon and re-runs every catalog load, which is
          // precisely the work that made the box slow. `minutes` is bounded so a fat-fingered pause
          // cannot silently disable the cache for a week.
          post("/admin/theme-optimization/pause") {
            if (rejectBadAdminToken()) return@post
            val minutesText = call.request.queryParameters["minutes"]
            val minutes =
              if (minutesText == null) DEFAULT_OPTIMIZER_PAUSE_MINUTES
              else
                minutesText.toLongOrNull()
                  ?: run {
                    call.respondText(
                      "minutes must be an integer in 1..$MAX_OPTIMIZER_PAUSE_MINUTES",
                      status = HttpStatusCode.BadRequest,
                    )
                    return@post
                  }
            if (minutes <= 0 || minutes > MAX_OPTIMIZER_PAUSE_MINUTES) {
              call.respondText(
                "minutes must be 1..$MAX_OPTIMIZER_PAUSE_MINUTES",
                status = HttpStatusCode.BadRequest,
              )
              return@post
            }
            val reason = call.request.queryParameters["reason"].orEmpty().ifBlank { "admin" }
            val until = optimizer.pauseOptimizers(minutes * 60_000L, reason)
            call.respondText(
              Json.encodeToString(
                OptimizerPauseDto.serializer(),
                OptimizerPauseDto(paused = true, pausedUntilEpochMillis = until, reason = reason),
              ),
              ContentType.Application.Json,
            )
          }
          // Per-catalog cache control, the pair that answers "these pixels look wrong".
          //
          // Separate verbs because they cost very different things and the cheap one is almost
          // always right. `regenerate` marks the catalog's warmed renders for re-render and
          // deletes nothing, so every preview keeps serving while the background pass replaces
          // them — the answer for pixels *suspected* wrong by something no fingerprint sees, a
          // base image that changed the installed fonts being the case that motivated it. `drop`
          // takes them, and every preview for that catalog goes cold at once.
          post("/admin/catalogs/{system}/theme-cache/regenerate") {
            if (rejectBadAdminToken()) return@post
            val system = call.parameters["system"].orEmpty()
            // The retained STATE, not the live host. `peekHost` answers null for a suspended
            // session, and since the optimizer residency work that is most catalogs most of the
            // time — so peeking at hosts would 404 precisely the idle catalogs this action exists
            // to refresh, and only for being idle. The cache hangs off the state and outlives the
            // daemon, so this neither needs nor wakes one.
            val cache = sessions.peekState(system)?.catalogThemeCache
            if (cache == null) {
              call.respondText("no such catalog: $system", status = HttpStatusCode.NotFound)
              return@post
            }
            val queued = withContext(Dispatchers.IO) { cache.markPersistedDirty() }
            // Wake the pass that has to work the queue. A converged catalog's optimizer task has
            // already exited and its host is usually suspended as well, so marking alone would
            // answer `queued: true` with nobody coming — the mark is durable, but "durable" and
            // "being worked" are the two different promises this route makes and it has to keep
            // both. Best-effort: a catalog that cannot be revived still has its mark on disk and
            // is picked up by the ordinary resume rotation.
            if (queued > 0) withContext(Dispatchers.IO) { sessions.wakeOptimizer(system) }
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            if (queued < 0) {
              // Two different refusals, both of which must not read as a queued regeneration: the
              // pass has no targets to work (theme optimization switched off, so nothing would ever
              // be re-rendered) or the mark could not be persisted (a full or read-only volume,
              // where a restart would silently forget the request).
              call.respondText(
                Json.encodeToString(
                  ThemeCacheActionDto.serializer(),
                  ThemeCacheActionDto(
                    system = system,
                    action = "regenerate",
                    entries = 0,
                    queued = false,
                  ),
                ),
                ContentType.Application.Json,
                status = HttpStatusCode.Conflict,
              )
              return@post
            }
            call.respondText(
              Json.encodeToString(
                ThemeCacheActionDto.serializer(),
                ThemeCacheActionDto(
                  system = system,
                  action = "regenerate",
                  entries = queued,
                  queued = true,
                ),
              ),
              ContentType.Application.Json,
            )
          }
          post("/admin/catalogs/{system}/theme-cache/drop") {
            if (rejectBadAdminToken()) return@post
            val system = call.parameters["system"].orEmpty()
            // The state, for the reason the regenerate route above gives.
            val cache = sessions.peekState(system)?.catalogThemeCache
            if (cache == null) {
              call.respondText("no such catalog: $system", status = HttpStatusCode.NotFound)
              return@post
            }
            val dropped = withContext(Dispatchers.IO) { cache.dropPersisted() }
            // Wake the pass, exactly as the regenerate route does. A drop leaves a catalog that
            // was warm everywhere full of gaps, and a converged catalog's optimizer task has
            // already exited — so without this the 200 advertises a rewarming that waits on the
            // capacity-limited resume rotation, or on a visitor's heartbeat, before it starts. The
            // drop is the more urgent of the two, not the less: every preview is cold now.
            //
            // And exactly as the regenerate route does, only when there is a pass to wake.
            // `-Dcomposeai.serve.themeOptimization=false` leaves the catalog with no optimization
            // targets, and regenerate declines the whole action on that (`markPersistedDirty`
            // answers -1, so `queued > 0` is false). The drop still succeeds — throwing the bytes
            // away needs no pass — but the wake behind it would resume a suspended host and carry
            // `keepLiveWarm` on into `scheduleWarm`, cold-starting an Android daemon and taking a
            // live seat for a refill that cannot happen.
            if (dropped && cache.hasOptimizationTargets) {
              withContext(Dispatchers.IO) { sessions.wakeOptimizer(system) }
            }
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respondText(
              Json.encodeToString(
                ThemeCacheActionDto.serializer(),
                // `dropped = false` is a real answer, not an error: the generation write lock is
                // held by a render publishing right now, and the caller should try again rather
                // than believe the bytes are gone.
                ThemeCacheActionDto(
                  system = system,
                  action = "drop",
                  entries = 0,
                  dropped = dropped,
                ),
              ),
              ContentType.Application.Json,
              status = if (dropped) HttpStatusCode.OK else HttpStatusCode.Conflict,
            )
          }
          post("/admin/theme-optimization/resume") {
            if (rejectBadAdminToken()) return@post
            optimizer.resumeOptimizers()
            call.respondText(
              Json.encodeToString(
                OptimizerPauseDto.serializer(),
                OptimizerPauseDto(paused = false, pausedUntilEpochMillis = null, reason = null),
              ),
              ContentType.Application.Json,
            )
          }
        }

        // Discard the catalog blob cache. Whole-pool and not per catalog — blobs are named by
        // their own digest and deliberately shared between systems, so none has an owning catalog
        // to delete it by (see [CatalogBlobPool.clear]). Everything dropped is re-fetchable, so the
        // cost of using this unnecessarily is bandwidth. Responds with what the pool holds
        // afterwards, which is the same shape `/status.json` reports, so an operator can see it
        // took.
        if (catalogCacheAdminEnabled) {
          delete("/admin/catalog-cache") {
            if (rejectBadAdminToken()) return@delete
            val after = withContext(Dispatchers.IO) { catalogCacheClear!!.invoke() }
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respondText(
              // JSON, not the bare companion: that one leaves `encodeDefaults` off, which silently
              // drops exactly the fields whose default value is the alarming one — an operator
              // clearing a temp-backed pool would get a response with no `persistenceConfigured`
              // in it at all. Same encoder as `/status.json` so the two agree in shape.
              JSON.encodeToString(CatalogBlobPoolSnapshot.serializer(), after),
              ContentType.Application.Json,
            )
          }
        }
        if (adminEnabled) {
          val admin = catalogAdmin!!
          get("/admin/catalogs") {
            if (rejectBadAdminToken()) return@get
            respondAdminCatalogs(admin)
          }
          post("/admin/catalogs") {
            if (rejectBadAdminToken()) return@post
            handleAdminRegister(admin)
          }
          delete("/admin/catalogs/{system}") {
            if (rejectBadAdminToken()) return@delete
            val system = call.parameters["system"].orEmpty()
            val result = withContext(Dispatchers.IO) { admin.unregister(system) }
            respondAdminResult(result)
          }
          // Front-page sections. The last part of the catalog config with no runtime path: a
          // section
          // could only be added by editing the box's catalogs.json and restarting, and a catalog
          // claiming an undefined one was rejected — so a committed config could not converge.
          // Defining a section also re-resolves the claims of catalogs ALREADY registered, or
          // defining it would collect nothing.
          get("/admin/groups") {
            if (rejectBadAdminToken()) return@get
            respondAdminGroups(admin)
          }
          post("/admin/groups") {
            if (rejectBadAdminToken()) return@post
            handleAdminGroupUpsert(admin)
          }
          delete("/admin/groups/{id}") {
            if (rejectBadAdminToken()) return@delete
            val id = call.parameters["id"].orEmpty()
            respondAdminResult(withContext(Dispatchers.IO) { admin.removeGroup(id) })
          }
        }

        // Runtime site administration. `sites` was the last part of the deployment config with no
        // runtime path: a hostname committed to catalogs.json could not reach a running box at all,
        // so standing one up meant editing the host's untracked .env and recreating the container.
        // Registered separately from the catalog routes so a server can opt into one without the
        // other.
        if (siteAdminEnabled) {
          val admin = siteAdmin!!
          get("/admin/sites") {
            if (rejectBadAdminToken()) return@get
            respondAdminSites(admin)
          }
          post("/admin/sites") {
            if (rejectBadAdminToken()) return@post
            handleAdminSiteAdd(admin)
          }
          delete("/admin/sites/{host}") {
            if (rejectBadAdminToken()) return@delete
            val host = call.parameters["host"].orEmpty()
            respondAdminSiteResult(withContext(Dispatchers.IO) { admin.remove(host) })
          }
        }

        // Runtime producer-trust administration. The trust store is operator config on the same
        // volume as catalogs.json, so a producer can be trusted on a running box — which is what
        // makes runtime catalog registration useful at all: without it a catalog published via
        // `POST /admin/catalogs` serves, but badges `unverified` until an image rebuild ships a new
        // baked trust store. Removal is by the same discriminated entry shape as addition, with the
        // selector fields (`repo`, `keyId`, `identity`) carried as query parameters so an
        // `<owner>/<repo>` pattern needn't be path-escaped.
        if (trustAdminEnabled) {
          val admin = trustAdmin!!
          get("/admin/trust") {
            if (rejectBadAdminToken()) return@get
            respondAdminTrust(admin)
          }
          post("/admin/trust") {
            if (rejectBadAdminToken()) return@post
            handleAdminTrustAdd(admin)
          }
          delete("/admin/trust") {
            if (rejectBadAdminToken()) return@delete
            val q = call.request.queryParameters
            val entry =
              AdminTrustEntry(
                kind = q["kind"] ?: "branch",
                repo = q["repo"],
                branch = q["branch"],
                keyId = q["keyId"],
                identity = q["identity"],
              )
            respondAdminTrustResult(withContext(Dispatchers.IO) { admin.remove(entry) })
          }
        }

        // A persistent frame lane. The browser opens this, receives frames as JSON
        // ([ServeStreamProtocol]), and sends override / switch / input messages back. Token is
        // checked post-handshake (can't 404 after upgrade) — a bad token closes immediately. Two
        // routes share one handler: the query-param `?session=` form and the path-prefixed
        // `/{system}/ws/{name}` form (the session is then the `{system}` segment).
        webSocket("/ws/{name}") { serveStreamLane() }
        webSocket("/{system}/ws/{name}") { serveStreamLane() }

        // Session-selecting routes come in two forms that share one handler each: the query-param
        // `?session=` form (back-compat) and the path-prefixed `/{system}/…` form (the canonical
        // public URL — the `{system}` segment IS the session). `sessionInPath = true` picks the
        // latter. Constant first segments (`/healthz`, `/version`, `/bundle.zip`, `/wasm/…`, …)
        // score
        // higher than `/{system}` in Ktor routing, so they still win — only genuinely unknown
        // single
        // segments fall through to a session lookup (and 404 like a bad session).
        get("/") { handleLanding(sessionInPath = false) }
        get("/{system}") { handleLanding(sessionInPath = true) }
        get("/{system}/") { handleLanding(sessionInPath = true) }
        get("/compare") { handleFormatComparison(sessionInPath = false) }
        get("/{system}/compare") { handleFormatComparison(sessionInPath = true) }
        get("/compare/{name}") { handleReferenceComparison(sessionInPath = false) }
        get("/{system}/compare/{name}") { handleReferenceComparison(sessionInPath = true) }
        get("/reference/{name}") { handleDesignReferenceAsset(sessionInPath = false) }
        get("/{system}/reference/{name}") { handleDesignReferenceAsset(sessionInPath = true) }

        // The published element tag index (see [ServeTagIndex]). Per preview, like `/reference` and
        // `/pages` beside it, because that is what the artifact is: one index per render, published
        // with the stickers. It had no HTTP surface until the focused comparison's element selector
        // became its first consumer — inventing a route before a caller existed would have frozen a
        // guess.
        get("/tags/{name}") { handleTagIndex(sessionInPath = false) }
        get("/{system}/tags/{name}") { handleTagIndex(sessionInPath = true) }

        // Design pages (see [ServeDesignPages]). One route per level rather than a
        // separate asset path: `{name}` ending in `.png` is the backdrop image, anything else is
        // the screen's own view — the same suffix convention `/reference/{name}` already uses.
        get("/pages") { handleDesignPageIndex(sessionInPath = false) }
        get("/{system}/pages") { handleDesignPageIndex(sessionInPath = true) }
        get("/pages/{name}") { handleDesignPage(sessionInPath = false) }
        get("/{system}/pages/{name}") { handleDesignPage(sessionInPath = true) }

        // The published Remote Compose player renders + build-time diffs the compare page replays
        // (see [ServeRcCompare]). Content-addressed by lane and slot, never by a published id.
        get("/rc-compare/{lane}/{name}") { handleRcCompareAsset(sessionInPath = false) }
        get("/{system}/rc-compare/{lane}/{name}") { handleRcCompareAsset(sessionInPath = true) }

        // The design-parity dashboard. `?format=json` mirrors the `/status` convention; `.json` is
        // the canonical machine path a CI check polls.
        get("/parity") { handleParity(sessionInPath = false, json = false) }
        get("/{system}/parity") { handleParity(sessionInPath = true, json = false) }
        get("/parity.json") { handleParity(sessionInPath = false, json = true) }
        get("/{system}/parity.json") { handleParity(sessionInPath = true, json = true) }

        // The committed known differences (see [ServeKnownDifferences]). Two routes because the
        // engine needs two things and neither can be folded into the page: the document as **raw
        // text**, so `document-unreadable` and the byte cap stay reachable in the consumer that
        // owns
        // those verdicts, and each artifact as bytes, so the browser decodes the same PNG the
        // offline run does rather than an `<img>` the canvas has already normalised to RGBA.
        get("/parity/known-differences.json") { handleKnownDifferences(sessionInPath = false) }
        get("/{system}/parity/known-differences.json") {
          handleKnownDifferences(sessionInPath = true)
        }
        get("/parity/known-differences/{path...}") {
          handleKnownDifferenceArtifact(sessionInPath = false)
        }
        get("/{system}/parity/known-differences/{path...}") {
          handleKnownDifferenceArtifact(sessionInPath = true)
        }

        post("/refresh") { handleCatalogRefresh(sessionInPath = false) }
        post("/{system}/refresh") { handleCatalogRefresh(sessionInPath = true) }

        if (catalogFeed != null) {
          get("/feed.xml") { handleCatalogFeed(sessionInPath = false) }
          get("/{system}/feed.xml") { handleCatalogFeed(sessionInPath = true) }
        }

        get("/api/previews") { handleApiPreviews(sessionInPath = false) }
        get("/{system}/api/previews") { handleApiPreviews(sessionInPath = true) }
        get("/api/uses") { handleUsesSearch(sessionInPath = false) }
        get("/{system}/api/uses") { handleUsesSearch(sessionInPath = true) }
        // Which of a preview's published revisions actually differ. Fetched lazily by
        // `<cp-revision-runs>` when the revision menu is opened, never during page render.
        get("/api/render-runs/{name}") { handleRenderRuns(sessionInPath = false) }
        get("/{system}/api/render-runs/{name}") { handleRenderRuns(sessionInPath = true) }
        get("/api/components") { handleGlobalComponents() }
        get("/api/daemons") { handleDaemonStatus(sessionInPath = false) }
        get("/{system}/api/daemons") { handleDaemonStatus(sessionInPath = true) }
        post("/api/presence") { handlePresence(sessionInPath = false) }
        post("/{system}/api/presence") { handlePresence(sessionInPath = true) }
        post("/api/theme-render-lease") { handleThemeRenderLease(sessionInPath = false) }
        post("/{system}/api/theme-render-lease") { handleThemeRenderLease(sessionInPath = true) }
        post("/api/theme-render-lease/release") { handleThemeRenderLeaseRelease() }
        post("/{system}/api/theme-render-lease/release") { handleThemeRenderLeaseRelease() }

        // Storybook-compatibility surface (see [StorybookCompat]). `/index.json` is the stories
        // index every downstream visual tool (Chromatic, Percy, storycap/reg-suit, BackstopJS, the
        // test-runner) crawls to enumerate stories; `iframe.html?id=<storyId>` renders one story in
        // isolation for a screenshot tool. Both come in the query-`?session=` and
        // path-`/{system}/…`
        // forms like the rest; the constant first segment outscores `/{system}`.
        get("/index.json") { handleStorybookIndex(sessionInPath = false) }
        get("/{system}/index.json") { handleStorybookIndex(sessionInPath = true) }

        get("/iframe.html") { handleStorybookIframe(sessionInPath = false) }
        get("/{system}/iframe.html") { handleStorybookIframe(sessionInPath = true) }

        get("/bundle.zip") { handleBundleZip(sessionInPath = false) }
        get("/{system}/bundle.zip") { handleBundleZip(sessionInPath = true) }
        get("/bundle/{name}") { handleExecutableBundle(sessionInPath = false) }
        get("/{system}/bundle/{name}") { handleExecutableBundle(sessionInPath = true) }

        get("/p/{name}") { handleViewer(sessionInPath = false) }
        get("/{system}/p/{name}") { handleViewer(sessionInPath = true) }

        get("/usage/{name}") { handleUsage(sessionInPath = false) }
        get("/{system}/usage/{name}") { handleUsage(sessionInPath = true) }

        get("/render/{name}") { handleRender(sessionInPath = false) }
        get("/{system}/render/{name}") { handleRender(sessionInPath = true) }

        // The motion lane, beside `/render` rather than inside it: a capture is not a render of a
        // preview, it is a second artifact about the same component, and folding it into the render
        // route would mean that route's suffix decided the content type from a fetched path.
        // The motion browser: every capture this catalog publishes, on one page. A constant
        // first segment like `/pages` and `/parity`, and a sibling of the per-capture asset route
        // below — Ktor scores `/motion` and `/motion/{name}` as distinct paths, so the index does
        // not shadow the bytes.
        get("/motion") { handleMotionIndex(sessionInPath = false) }
        get("/{system}/motion") { handleMotionIndex(sessionInPath = true) }
        get("/motion/{name}") { handleMotion(sessionInPath = false) }
        get("/{system}/motion/{name}") { handleMotion(sessionInPath = true) }

        // Project mode only (see [projectHistory]): one version of a render, addressed by its
        // content sha, read straight out of the local repository. Registered conditionally like
        // every other optional lane, so a server without a repo has no such route at all.
        if (projectHistory != null) {
          get("/history/render/{name}") { handleHistoryRender() }
          get("/{system}/history/render/{name}") { handleHistoryRender() }
        }
      }
    }

  /** Start listening. Non-blocking; the caller keeps the process alive separately. */
  fun start() {
    server.start(wait = false)
  }

  /** Stop with a short grace period. Idempotent enough for a shutdown hook. */
  fun stop() {
    readinessProber?.interrupt()
    server.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
  }

  /**
   * The session id this request selects. In **path mode** ([sessionInPath]) it's the `{system}`
   * path segment (the canonical `/<system>/…` form); otherwise the `?session=` query param, falling
   * back to [defaultSessionId]. Returned even when unknown — the lease then 404s like a bad
   * session.
   */
  private fun RoutingContext.selectedSessionId(sessionInPath: Boolean): String =
    if (sessionInPath) call.parameters["system"] ?: defaultSessionId
    // A top-level site's host OUTRANKS `?session=`. It has to: the whole guarantee is one catalog
    // per hostname, and a query param that could re-point the session would hand a neighbour's
    // previews out through `/api/previews?session=wear-m3` while `/wear-m3/` 404s — the isolation
    // undone by the older spelling of the same request.
    else siteSystem() ?: call.request.queryParameters["session"] ?: defaultSessionId

  /**
   * A catalog's RSS document. The request itself is the subscription signal: [catalogFeed] renews
   * its interest lease and queues background catch-up, while this handler immediately returns the
   * last completed document (or a valid empty document on the first cold request).
   */
  private suspend fun RoutingContext.handleCatalogFeed(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val feed =
      catalogFeed
        ?: run {
          call.respondText("not found", status = HttpStatusCode.NotFound)
          return
        }
    val system = selectedSessionId(sessionInPath)
    val (webSessionId, basePath) = webSessionAndBase(sessionInPath)
    val linkQuery = feedLinkQuery(basePath, webSessionId)
    val result =
      withContext(Dispatchers.IO) { feed.request(system, externalOrigin() + basePath, linkQuery) }
        ?: run {
          call.respondText("not found", status = HttpStatusCode.NotFound)
          return
        }
    if (result.building) call.response.headers.append(HttpHeaders.RetryAfter, "30")
    markGeneration("catalog-feed", "no-cache")
    call.respondText(
      result.xml,
      ContentType.parse("application/rss+xml; charset=utf-8"),
    )
  }

  /**
   * The routing/authentication values a feed URL carries.
   *
   * Feed readers cannot replay an Authorization-style header for URLs embedded in RSS, so the
   * document's own links have to carry what the server controls — and only that: never let
   * arbitrary request parameters become durable feed state.
   */
  private fun RoutingContext.feedLinkQuery(basePath: String, webSessionId: String?): String =
    buildList {
      if (basePath.isEmpty() && siteSystem() == null && webSessionId != null) {
        add("session=${WebEscaping.urlEncodeSegment(webSessionId)}")
      }
      if (!isPublic) add("token=${WebEscaping.urlEncodeSegment(linkToken())}")
    }
    .joinToString("&")

  /**
   * The **Changelog** destination a catalog page's footer offers: this catalog's own `/feed.xml`.
   *
   * The feed is the published history of the design system the visitor is looking at, and until now
   * the only way to find it was to know the URL. Empty — so the footer drops the entry rather than
   * offering a 404 — on a server started with the feed lane off, and for a session the feed does
   * not serve (a plain local module has no delivery branch to have a history on).
   */
  private fun RoutingContext.changelogHref(
    system: String,
    basePath: String,
    webSessionId: String?,
  ): String {
    if (catalogFeed?.serves(system) != true) return ""
    val query = feedLinkQuery(basePath, webSessionId)
    return "$basePath/feed.xml" + query.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
  }

  /**
   * The catalog this request's **host** publishes as a top-level site, or null on the main host
   * (and on every server with no sites configured, which is the fast path). See [ServeSites].
   *
   * Read from the same forwarded headers [externalOrigin] trusts, because the only thing in front
   * of this server is the deployment's own reverse proxy — a request that reaches the listener
   * direct carries its `Host` verbatim, which is what a local `curl -H 'Host: m3.preview.coo.ee'`
   * needs.
   */
  private fun ApplicationCall.siteSystem(): String? {
    if (sites.isEmpty) return null
    val forwarded = request.headers["X-Forwarded-Host"]?.substringBefore(',')?.trim()
    return sites.systemFor(
      forwarded?.takeIf { it.isNotEmpty() } ?: request.headers[HttpHeaders.Host]
    )
  }

  private fun RoutingContext.siteSystem(): String? = call.siteSystem()

  /**
   * The catalog identity a **top-level site**'s non-catalog pages should wear — its name, its
   * palette, and the `localStorage` key its theme choice is remembered under. All three empty on
   * the main host, and on a site whose catalog has not loaded yet.
   *
   * A site hostname publishes one design system, so `/status` and the 404 on that host are that
   * system's pages too. Without this they rendered in the built-in chrome beside a themed landing —
   * one hostname wearing two skins. Read through [ServeSessionRegistry.peekHost], which never
   * resumes: a 404 must not wake a daemon to find out what colour to be.
   */
  private fun RoutingContext.siteSkin(): Triple<String, String, String> = call.siteSkin()

  /** As [siteSkin], from the call alone — the site interceptor has no [RoutingContext]. */
  private fun ApplicationCall.siteSkin(): Triple<String, String, String> {
    val system = siteSystem() ?: return Triple("", "", "")
    val host = sessions.peekHost(system)
    val bundle = host?.let { catalogBundleHost(it) }
    val name =
      bundle?.title?.takeIf { it.isNotBlank() }
        ?: catalogMetaSeen[system]?.title
        ?: host?.label
        ?: system
    // Same key the catalog's own pages use, so one choice follows a visitor across the hostname
    // instead of `/status` and the grid each remembering their own light/dark.
    // Palette from the resident bundle, else the last-known snapshot: residency, not registration,
    // is what the idle timer takes away, and a suspended site must keep its colours.
    val themeCss =
      bundle?.webThemeCss?.takeIf { it.isNotBlank() }
        ?: catalogMetaSeen[system]?.webThemeCss.orEmpty()
    return Triple(name, themeCss, "cp-theme:$system")
  }

  /**
   * Whether a GitHub sign-in started from *this* request's origin can actually come back to it.
   *
   * This used to be false on every top-level site whose box pins a callback base URL
   * (`--github-auth-callback-base-url`, which the deployment sets): the `cp_gh_state` cookie is
   * host-only, so it was written on the site host while GitHub returned to the pinned one, and the
   * callback saw no state and answered 401. The affordance was withheld rather than walk a visitor
   * into that, which left live and playground snapshot-only on every site.
   *
   * [ServeGithubAuthConfig.cookieDomain] fixes it at the root: written for the parent domain, both
   * cookies are sent to the pinned callback host and to every site host under it, so the CSRF check
   * works where it always did and one session covers the family. The state carries the originating
   * host purely so the callback knows where to send the visitor back to.
   *
   * What stays false is a host outside that domain — an unlisted vhost, or a site on a different
   * domain entirely. The cookies would not reach it, so a sign-in started there would land the
   * visitor back signed-out, and offering the link would still be advertising a dead end.
   */
  private fun RoutingContext.oauthCanRoundTrip(): Boolean =
    githubAuth?.canRoundTrip(requestHost(call), sites.hosts) ?: true

  /**
   * The session id to hand [ServeWeb] for nav-marking + link building, and the URL [basePath] its
   * same-session links get. Path mode → the `{system}` segment + `/<system>` base (links stay on
   * the path, no `?session=`); query mode → the raw `?session=` (null for the default session) +
   * empty base (links keep the legacy `&session=` behaviour). Kept separate from
   * [selectedSessionId] so the default module session renders with token-only links exactly as
   * before (byte-identical goldens).
   */
  private fun RoutingContext.webSessionAndBase(sessionInPath: Boolean): Pair<String?, String> {
    val system = if (sessionInPath) call.parameters["system"] else null
    if (system != null) return system to "/" + WebEscaping.urlEncodeSegment(system)
    // A top-level site: the session is this host's catalog, and its base path is EMPTY — that empty
    // string is the whole reason links stay on the custom domain instead of walking back to
    // `preview.coo.ee/<system>/`. The session id is still passed so nav-marking and the engagement
    // counters attribute to the right catalog; it is the same catalog either way, so a visit
    // through the site host and one through the canonical path count together.
    siteSystem()?.let {
      return it to ""
    }
    return call.request.queryParameters["session"] to ""
  }

  /**
   * The catalogs the playground may offer **this** request: every one on the main host, and only
   * its own on a top-level site.
   *
   * The playground lives at constant paths (`/playground`, `/api/<v>/compiler/catalogs`), so the
   * canonical-path interceptor never sees it — its first segment names no session. Without this, a
   * site host would list, preselect and compile against every catalog on the box, which is the
   * one-catalog-per-host contract broken by the one lane that runs code. The pinned "Server
   * default" entry (empty id) is not a catalog and is kept either way.
   */
  private fun RoutingContext.siteScopedCatalogChoices(
    service: PlaygroundCompileService
  ): List<PlaygroundCatalogInfo> {
    val choices = service.catalogChoices()
    val site = siteSystem() ?: return choices
    return choices.filter { it.id.isEmpty() && sitePinIsOwn(service) || it.system == site }
  }

  /**
   * Whether the host's **pinned** playground default compiles against this site's own catalog.
   *
   * The pinned entry carries an empty id and reads as "Server default", which sounds catalog-less
   * but is not: `--playground-bundle=wear-m3` on a box whose site is `compose-m3` makes that
   * default a *neighbour's* classpath. Keeping the option, or accepting `catalog: ""`, would let a
   * one-catalog hostname compile against another design system under a name that never says so. A
   * pin naming no catalog at all (local files) is nobody's neighbour and stays offered.
   */
  private fun RoutingContext.sitePinIsOwn(service: PlaygroundCompileService): Boolean {
    val site = siteSystem() ?: return true
    val pinned = service.pinnedCatalogSystems
    return pinned.isEmpty() || pinned == setOf(site)
  }

  /** `POST /{system}/refresh`: check the published catalog branch and reload it when newer. */
  private suspend fun RoutingContext.handleCatalogRefresh(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val system = selectedSessionId(sessionInPath)
    val refresh = catalogRefresh
    if (system.isEmpty() || refresh == null) {
      call.respondText(
        "{\"status\":\"unavailable\"}",
        ContentType.Application.Json,
        HttpStatusCode.NotFound,
      )
      return
    }
    if (!catalogRefreshesInFlight.add(system)) {
      call.response.headers.append(HttpHeaders.CacheControl, "no-store")
      call.response.headers.append(HttpHeaders.RetryAfter, "2")
      call.respondText(
        "{\"status\":\"checking\"}",
        ContentType.Application.Json,
        HttpStatusCode.Accepted,
      )
      return
    }
    // `?force=1` re-fetches even when the branch has not moved. The ordinary check short-circuits
    // on an unchanged head, which is what makes polling cheap and is right almost always — but it
    // also means there is otherwise no way to say "read it again anyway", which is exactly what an
    // operator wants after clearing the blob cache, or when they simply want the published bytes
    // re-read rather than reasoned about.
    // Gated by the ADMIN token, not the browse token this handler opens with. On a `--public` box
    // the browse gate authorizes everyone, and an ordinary refresh is safe to hand out because it
    // short-circuits on an unchanged head — a repeated call costs one `git ls-remote`. Forcing
    // removes that short-circuit, so an anonymous caller could drive a full re-stage (and, with a
    // cold pool, a bundle re-download) in a loop. Refused the way the admin surface refuses
    // everything, which also means a box with no configured admin token cannot be forced at all.
    val force = call.request.queryParameters["force"] == "1"
    if (force && rejectBadAdminToken()) {
      catalogRefreshesInFlight.remove(system)
      return
    }
    val result =
      try {
        withContext(Dispatchers.IO) { refresh(system, force) }
      } finally {
        catalogRefreshesInFlight.remove(system)
      }
    val (status, code) =
      when (result) {
        CatalogRefreshResult.UPDATED -> "updated" to HttpStatusCode.OK
        CatalogRefreshResult.CURRENT -> "current" to HttpStatusCode.OK
        CatalogRefreshResult.UNAVAILABLE -> "unavailable" to HttpStatusCode.ServiceUnavailable
        CatalogRefreshResult.FAILED -> "failed" to HttpStatusCode.BadGateway
        CatalogRefreshResult.NOT_FOUND -> "not-found" to HttpStatusCode.NotFound
      }
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    call.respondText("{\"status\":\"$status\"}", ContentType.Application.Json, code)
  }

  /**
   * Browser-visible origin for absolute Open Graph image URLs. Caddy preserves `Host` and supplies
   * `X-Forwarded-Proto` while terminating TLS; direct/local serve requests fall back to Ktor's
   * connection scheme and Host header. Only the first proxy value is relevant when a request
   * crossed more than one hop.
   */
  private fun RoutingContext.externalOrigin(): String {
    fun firstHeader(name: String): String? =
      call.request.headers[name]?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }

    val forwardedScheme = firstHeader("X-Forwarded-Proto")
    val scheme =
      forwardedScheme?.takeIf { it.equals("http", true) || it.equals("https", true) }
        ?: call.request.origin.scheme
    val authority =
      firstHeader("X-Forwarded-Host")
        ?: firstHeader(HttpHeaders.Host)
        ?: "${call.request.origin.serverHost}:${call.request.origin.serverPort}"
    return "${scheme.lowercase()}://$authority"
  }

  /** Raw request query, including its leading `?` only when non-empty. */
  private fun RoutingContext.requestQuerySuffix(): String =
    call.request.queryString().let { if (it.isEmpty()) "" else "?$it" }

  /** A pinned render accepts routing state and the pin itself, never viewer render overrides. */
  private fun RoutingContext.pinnedRenderQuerySuffix(): String =
    call.request.queryParameters
      .entries()
      .filter { (key, _) ->
        key == "token" || key == "session" || key == ServeCatalogRevision.PARAM
      }
      .flatMap { (key, values) -> values.map { key to it } }
      .joinToString("&") { (key, value) ->
        "${WebEscaping.urlEncodeSegment(key)}=${WebEscaping.urlEncodeSegment(value)}"
      }
      .let { if (it.isEmpty()) "" else "?$it" }

  /**
   * The global presentation selected in the sticky header. The command chooses the initial mode
   * (`browse` → Catalog, `serve` → Dev); the visitor's own choice, remembered in the
   * [ServeWeb.INTERFACE_MODE_COOKIE] cookie, wins over that; an explicit `?chrome=` on the URL wins
   * over both.
   *
   * The cookie is what makes this a *mode the visitor is in* rather than a property of each URL. It
   * rides along with every request to this host, so no link has to carry the choice — which is what
   * the previous scheme did, rewriting every same-origin `href` on the page to append `?chrome=`
   * and bouncing a bare URL through a `location.replace` to restore the value from `localStorage`.
   * That put a parameter nobody chose into every URL a visitor copied, shared, or bookmarked.
   *
   * `?chrome=` survives as a **permalink**: a link may pin the presentation it was written for, for
   * that request only. It deliberately does not write the cookie — following someone else's link
   * should not silently change which mode you are in afterwards.
   */
  private fun ApplicationCall.componentBrowserMode(): Boolean {
    interfaceMode(request.queryParameters[CHROME_PARAM])?.let {
      return it
    }
    // Only on this branch: the body now depends on the Cookie header, and without `Vary` a shared
    // cache would key one visitor's Catalog-mode HTML by URL alone and hand it to a Dev-mode
    // visitor. A pinned `?chrome=` never reads the cookie, so it keeps the wider cache key.
    varyOnCookie()
    return interfaceMode(request.cookies[ServeWeb.INTERFACE_MODE_COOKIE]) ?: componentBrowser
  }

  private fun RoutingContext.componentBrowserMode(): Boolean = call.componentBrowserMode()

  /**
   * The header's GitHub control, or null where there is nothing honest to offer.
   *
   * Withheld when the sign-in cannot come back to *this* origin ([oauthCanRoundTrip]) — a host
   * outside the cookie domain, or host-only cookies against a pinned callback. Following the link
   * there writes the CSRF state where the callback can never read it, so the visitor lands back
   * signed out; the card and viewer affordances have always been withheld on that predicate, and a
   * header button is the same dead end one page up. It only started mattering for the landing
   * because that page did not render this control at all before.
   *
   * A signed-in identity cannot be hidden by this in practice: cookies that reached this host are
   * cookies the callback could have written.
   */
  /**
   * [lane] names what the sign-in unlocks on the page asking for the control, which is what its
   * tooltip describes. Defaults to Live — the front door and `/status` stand above any one catalog
   * and answer for the broad case. A catalog landing whose only gated lane is the playground passes
   * [ServeWeb.GatedLane.PLAYGROUND], and only then is `--github-auth-repo` named: repository access
   * is the playground's gate, and naming it beside Live is the confusion this change removes.
   */
  private fun RoutingContext.githubAuthStatus(
    lane: ServeWeb.GatedLane = ServeWeb.GatedLane.LIVE
  ): ServeWeb.GitHubAuthStatus? =
    githubAuth
      ?.takeIf { oauthCanRoundTrip() }
      ?.let { auth ->
        ServeWeb.GitHubAuthStatus(
          loginHref = auth.loginPath(call),
          login = auth.currentLogin(call),
          restrictedToAllowedUsers = auth.isRestrictedToAllowedUsers(),
          lane = lane,
          accessRepository =
            auth.accessRepository().takeIf { lane == ServeWeb.GatedLane.PLAYGROUND },
        )
      }

  /** The two wire values of the Catalog / Dev switch; null for absent, empty, or anything else. */
  private fun interfaceMode(value: String?): Boolean? =
    when (value?.lowercase()) {
      "catalog" -> true
      "dev" -> false
      else -> null
    }

  /**
   * Declare that this response was chosen by a request cookie. Appended at most once: several
   * things on one response can depend on cookies ([markGeneration]'s cacheable HTML, the interface
   * mode above), and repeating the header buys nothing.
   */
  private fun ApplicationCall.varyOnCookie() {
    val already =
      response.headers.values(HttpHeaders.Vary).any {
        it.splitToSequence(',').any { part -> part.trim().equals(HttpHeaders.Cookie, true) }
      }
    if (!already) response.headers.append(HttpHeaders.Vary, HttpHeaders.Cookie)
  }

  private fun RoutingContext.varyOnCookie() = call.varyOnCookie()

  /** Absolute externally visible URL for the current page (including its query). */
  private fun RoutingContext.externalPageUrl(): String = externalOrigin() + call.request.origin.uri

  /**
   * Resolve the tenant for [sessionId] and run [block] with its host while holding a
   * [ServeSessionRegistry.Lease] for the request's whole duration — so the reaper can't suspend the
   * daemon mid-request (e.g. a long `/bundle.zip` that renders every preview). Responds 404 when
   * the session can't be created/opened. The lease is always released.
   */
  private suspend fun RoutingContext.withLeasedSession(
    sessionId: String,
    /**
     * How to respond when the session can't be created/opened. Defaults to the bare `text/plain`
     * 404 (correct for asset / API lanes); the HTML *page* routes (landing, viewer) pass an
     * [respondNotFoundHtml] so a dead link lands on the styled site rather than plain text.
     */
    onMissing: (suspend RoutingContext.() -> Unit)? = null,
    block: suspend (ServeHost) -> Unit,
  ) {
    val lease = withContext(Dispatchers.IO) { sessions.lease(sessionId) }
    if (lease == null) {
      if (onMissing != null) onMissing()
      else call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    try {
      block(lease.host)
    } finally {
      // Called directly, NOT through `withContext` — the same rule [withLeasedSessionOrNull]
      // already spells out, which this lane was missing.
      //
      // A `withContext` in a `finally` never runs once the job is cancelled: it checks the job on
      // entry and throws straight back out. Cancellation is exactly when this matters — a visitor
      // navigating away, a crawler abandoning a fetch, a socket dropped mid-render all cancel the
      // request coroutine here — and a skipped release leaves the lease count permanently
      // elevated. That is far worse than one resident daemon: a leaked lease keeps its session
      // resident for the life of the process, so its daemon is never suspended and the
      // `--exit-when-idle` watchdog (`connectionIdleMillis`) never fires. Since #4312 it no longer
      // also pins the theme optimizer's clock — a lease stops counting as busy once its holder goes
      // quiet — but that relaxation is a floor under the damage, not a licence to leak one.
      //
      // Safe to call inline: `Lease.close` is a non-suspending, idempotent compare-and-set.
      // This helper backs the page and asset lanes — the routes an aborted browse actually hits —
      // so it is the one that had to get this right.
      lease.close()
    }
  }

  /**
   * [withLeasedSession] for a lane that reads a **value** off the host rather than responding from
   * inside the lease — so the caller decides the status code once, with the lease already released.
   *
   * Null covers both "no such session" and "the host had nothing", which is all this route's two
   * callers distinguish. [block] runs on [Dispatchers.IO]: reading a published asset can miss the
   * staged copy and go to the delivery branch, and that is a network round trip which must not run
   * on a request thread.
   *
   * **Resumes, but never creates.** [ServeSessionRegistry.lease] falls through to the session
   * factory for an id it doesn't know, which in project mode with `--revisions` means checking out
   * a ref and running a Gradle build. That is the right behaviour for a render — the whole point of
   * a revision session — and exactly wrong here, where a revision host has no published captures
   * and the request can only end in 404 anyway. Gating on [isKnownSession] keeps what the fix is
   * for (an already-registered catalog that went idle) without turning a published-asset lane into
   * a way to make a stranger's server build arbitrary refs.
   */
  private suspend fun <T> RoutingContext.withLeasedSessionOrNull(
    sessionId: String,
    block: (ServeHost) -> T?,
  ): T? {
    if (!sessions.isKnownSession(sessionId)) return null
    val lease = withContext(Dispatchers.IO) { sessions.lease(sessionId) } ?: return null
    return try {
      withContext(Dispatchers.IO) { block(lease.host) }
    } finally {
      // Called directly, NOT through `withContext`. `Lease.close` is a non-suspending
      // compare-and-set, and a `withContext` in a `finally` is skipped outright once the job is
      // cancelled — which is precisely when this matters, since a client that disconnects during
      // the branch fetch cancels here. A skipped release leaves the lease count permanently
      // elevated, and a session with an open lease is never suspended: one aborted request would
      // pin that catalog's daemon resident for the life of the process.
      lease.close()
    }
  }

  /**
   * A styled HTML 404 for the browser-facing page routes (landing, viewer) — see
   * [ServeWeb.notFoundPage]. Asset/API lanes keep their bare `text/plain` 404.
   */
  private suspend fun RoutingContext.respondNotFoundHtml(message: String) {
    val skin = siteSkin()
    // These misses are not immutable: catalog refresh, admin registration, or asynchronous parity
    // staging can make the same URL valid without a deployment. Never let a browser or proxy keep
    // the old 404 after that state changes.
    markGeneration("static-page", DYNAMIC_RESOURCE_CACHE_CONTROL)
    call.respondText(
      ServeWeb.notFoundPage(
        message,
        linkToken(),
        isPublic,
        unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
        version = SERVE_VERSION,
        siteName = skin.first,
        themeCss = skin.second,
        themeStorageKey = skin.third,
        componentBrowser = componentBrowserMode(),
        githubAuth = githubAuthStatus(),
      ),
      ContentType.Text.Html,
      HttpStatusCode.NotFound,
    )
  }

  // ---- The document lane (`--accept-docs`) ---------------------------------------------------

  /** `GET /docs`: the upload surface — drop a known document, get an expiring permalink back. */
  private suspend fun RoutingContext.handleDocUploadPage(store: ServeDocStore) {
    if (rejectBadToken()) return
    markGeneration("static-page", pageCacheControl())
    call.respondText(
      ServeWeb.docUploadPage(
        token = linkToken(),
        isPublic = isPublic,
        ttlSeconds = store.ttlSeconds,
        urlUploadAllowed = store.urlFetchAllowed,
        unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
        version = SERVE_VERSION,
      ),
      ContentType.Text.Html,
    )
  }

  /**
   * `POST /docs` (body = the document, or `?url=` to fetch one): ingest a document and answer with
   * its expiring permalink. `?name=` is a display label only — never a path, never the format
   * decision (the store content-sniffs).
   */
  private suspend fun RoutingContext.handleDocUpload(store: ServeDocStore) {
    if (rejectBadTokenForIngest()) return
    val name = call.request.queryParameters["name"]
    val url = call.request.queryParameters["url"]
    // Cap the body as it streams in — receiving it whole first would let a client OOM the server
    // regardless of the store's own cap.
    val body =
      if (url == null) {
        withContext(Dispatchers.IO) { call.receiveStream().use { readCapped(it, MAX_DOC_BYTES) } }
          ?: run {
            call.respondText(
              "document exceeds ${MAX_DOC_BYTES / (1024 * 1024)}MB",
              status = HttpStatusCode.PayloadTooLarge,
            )
            return
          }
      } else {
        null
      }
    // isSecurityChecked = true: this route is token-gated (rejectBadToken above), and the store
    // still defends in depth (format sniff, size + count caps, TTL; SSRF allowlist for the url
    // case). The marker records that the entry point was authorised.
    val result =
      withContext(Dispatchers.IO) {
        if (url != null) store.addFromUrl(name, url, isSecurityChecked = true)
        else store.add(name, body!!, isSecurityChecked = true)
      }
    when (result) {
      is ServeDocStore.Result.Ok -> {
        val doc = result.doc
        call.respondText(
          JSON.encodeToString(
            DocAcceptedResponse.serializer(),
            DocAcceptedResponse(
              id = doc.id,
              name = doc.name,
              format = doc.format.label,
              formatId = doc.format.id,
              bytes = doc.sizeBytes,
              url = doc.path,
              expiresIn = ServeWeb.humanDuration(store.remainingSeconds(doc)),
              expiresAtEpochSeconds = doc.expiresAtMillis / 1000,
            ),
          ),
          ContentType.Application.Json,
          HttpStatusCode.Created,
        )
      }
      is ServeDocStore.Result.Failed ->
        call.respondText(result.reason, status = HttpStatusCode.BadRequest)
    }
  }

  /**
   * `POST /api/{version}/compiler/run`: compile a playground snippet and return the Stage-1 result
   * — diagnostics (both our shape and the stock `errors` map) plus, on a clean compile, an expiring
   * preview token. Token-gated; the compile runs **user-supplied code** in-process, which is why
   * the CLI refuses to enable this lane under `--public`. `isSecurityChecked = true`: the route
   * cleared its token gate (`rejectBadToken`); the service still bounds the work (size cap, token
   * TTL/caps).
   */
  /**
   * `GET /playground`: the Stage-1 editor page. Token-gated (the lane runs user code, so it is
   * never public); a static HTML page whose script POSTs to `/api/{v}/compiler/run` and follows the
   * returned `/pg/` or `/d/` handoff.
   */
  private suspend fun RoutingContext.handlePlaygroundPage(service: PlaygroundCompileService) {
    if (rejectBadToken()) return
    if (rejectMissingGithubAuth()) return
    if (rejectMissingGithubRepoAccess()) return
    // `?from=<system>/<previewId>` — the handoff from a viewer page: open that preview's own Kotlin
    // in the editor with its catalog preselected. `?catalog=<system>` is the lighter half from a
    // catalog landing: preselect the design system, keep the starter sample. Both resolve entirely
    // through this server's own registry, so a request never names a URL the host then fetches.
    //
    // A seed that can't be resolved is not an error page: the playground still works, so it opens
    // on
    // the sample. The startup log carries the reason.
    val seed =
      call.request.queryParameters["from"]?.let { raw ->
        val system = raw.substringBefore('/')
        val previewId = raw.substringAfter('/', "")
        // `?from=` is supplied by the caller, not by the selector this page renders — so narrowing
        // the selector does not narrow this. On a site host a `from` naming a neighbour would
        // otherwise seed the editor with that catalog's Kotlin source, which is the one-catalog
        // contract broken by a query parameter. Ignored rather than refused: the playground still
        // opens, on its sample.
        val siteSystem = siteSystem()
        if (system.isBlank() || previewId.isBlank()) null
        else if (siteSystem != null && system != siteSystem) null
        // On the IO dispatcher: an uncached seed is a synchronous GitHub GET with 10 s connect +
        // 10 s read, and running that on the routing dispatcher lets a handful of concurrent
        // handoffs during GitHub latency stall every other route on this host.
        else withContext(Dispatchers.IO) { playgroundSeeds?.seed(system, previewId) }
      }
    markGeneration("static-page", pageCacheControl())
    call.respondText(
      ServeWeb.playgroundPage(
        token = linkToken(),
        isPublic = isPublic,
        catalogs = siteScopedCatalogChoices(service),
        catalogSelectorEnabled = service.catalogSelectorEnabled,
        seed = seed,
        preselectCatalog = call.request.queryParameters["catalog"],
        pinnedCatalogSystems = service.pinnedCatalogSystems,
        unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
        version = SERVE_VERSION,
        editingLeaseEnabled = service.editLeasesEnabled && githubAuth?.currentLogin(call) != null,
      ),
      ContentType.Text.Html,
    )
  }

  /**
   * `GET /api/{version}/compiler/catalogs`: what the editor's catalog selector may offer — the
   * host's pinned default (when it has one) plus every served catalog that can back a compile here,
   * each with the modes its bundle backend supports. Gated exactly like the run route: it
   * enumerates what this host serves, and the playground's whole point is that only admitted
   * callers see it.
   */
  private suspend fun RoutingContext.handlePlaygroundCatalogs(service: PlaygroundCompileService) {
    if (rejectBadToken()) return
    if (rejectMissingGithubAuth(api = true)) return
    if (rejectMissingGithubRepoAccess(api = true)) return
    markGeneration("playground-catalogs", DYNAMIC_RESOURCE_CACHE_CONTROL)
    call.respondText(
      JSON.encodeToString(
        PlaygroundCatalogsResponse.serializer(),
        PlaygroundCatalogsResponse(siteScopedCatalogChoices(service)),
      ),
      ContentType.Application.Json,
    )
  }

  private suspend fun RoutingContext.handlePlaygroundDisabledPage() {
    markGeneration("static-page", DYNAMIC_RESOURCE_CACHE_CONTROL)
    call.respondText(
      ServeWeb.playgroundDisabledPage(
        token = linkToken(),
        isPublic = isPublic,
        unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
        version = SERVE_VERSION,
      ),
      ContentType.Text.Html,
      HttpStatusCode.ServiceUnavailable,
    )
  }

  /**
   * `GET /pg/<token>`: redeem a preview token into a live session and redirect to its viewer. An
   * unknown/expired token — or a well-formed one this host has no live backend for — is a styled
   * 404 that discloses neither. Token-gated (the redeemed session runs user code).
   */
  private suspend fun RoutingContext.handlePlaygroundRedeem(redeem: PlaygroundRedeemService) {
    if (rejectBadToken()) return
    if (rejectMissingGithubAuth()) return
    if (rejectMissingGithubRepoAccess()) return
    // Read the PATH segment explicitly (see the route mount): it's named `{pgToken}` so it can't be
    // shadowed by the `?token=` access token that `call.parameters` also carries on a gated host.
    val id = call.parameters["pgToken"].orEmpty()
    val gone = "That preview link has expired, or never existed."
    if (!PlaygroundTokenStore.isWellFormedId(id)) {
      respondNotFoundHtml(gone)
      return
    }
    // `?preview=<id>` opens the session on a specific one of the snippet's previews. Read from the
    // QUERY, not the path, so it can't collide with the access token the path already dodges; the
    // service validates it against the snippet's own set and falls back to the first.
    val preview = call.request.queryParameters["preview"]?.takeIf { it.isNotBlank() }
    when (val outcome = redeem.redeem(id, preview)) {
      PlaygroundRedeemService.Outcome.NotFound -> respondNotFoundHtml(gone)
      PlaygroundRedeemService.Outcome.Unavailable ->
        respondNotFoundHtml("Live preview isn't available on this host.")
      is PlaygroundRedeemService.Outcome.Live -> {
        // Hand off to the existing path-form viewer for the just-registered session; its
        // `/{session}/ws/{preview}` lane streams it and enforces the live-seat budget. Carry the
        // token so the token-gated viewer + WS accept the follow-on requests.
        val suffix =
          if (isPublic) "" else "?token=" + java.net.URLEncoder.encode(linkToken(), Charsets.UTF_8)
        call.respondRedirect("/${outcome.sessionId}/p/${outcome.previewId}$suffix")
      }
    }
  }

  /**
   * Who to charge for a request, for rate-limiting purposes.
   *
   * The **authenticated GitHub login** where there is one: it survives a changed address, it is the
   * identity the repo-access gate already admitted on, and on a repo-access-gated host it is what
   * every compile carries. Otherwise the client address, which is all a token-gated or local host
   * has. Prefixed so the two spaces can never collide — a login is not an address, and a caller who
   * signs in should not inherit the budget an anonymous neighbour behind the same NAT just spent.
   */
  private fun RoutingContext.rateLimitKey(): String {
    githubAuth
      ?.currentLogin(call)
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return "gh:$it"
      }
    return clientAddressKey()
  }

  /**
   * Who to charge **by address**, ignoring any signed-in identity — the key for work that happens
   * before this request has an identity to charge, or that is deliberately metered per address.
   *
   * Separate from [rateLimitKey] because a lane that charges a caller twice — once before it knows
   * who they are and once after — must not land both charges in the same bucket. It would halve the
   * budget an operator configured, and at `--image-rate-limit 1` refuse every request.
   */
  private fun RoutingContext.clientAddressKey(): String = "ip:" + clientAddress()

  /**
   * The caller's address under this server's forwarding policy — the trusted final
   * `X-Forwarded-For` hop when `--trust-forwarded-for` is set, else the socket peer.
   *
   * Extracted from [clientAddressKey] so the rate limiter and anything that *displays* an address
   * cannot answer the question differently. They did: the grant approval page rendered the raw
   * peer, which behind a proxy is the proxy, on every request.
   */
  private fun RoutingContext.clientAddress(): String {
    val forwarded =
      if (!trustForwardedFor) null
      else
        call.request.headers["X-Forwarded-For"]
          ?.split(',')
          ?.map { it.trim() }
          ?.lastOrNull { it.isNotEmpty() }
    return forwarded ?: call.request.origin.remoteHost
  }

  /**
   * Charge this request against its caller's budget, responding `429` + `Retry-After` and returning
   * null when they are over it. A non-null result MUST be released when the work finishes.
   */
  private suspend fun RoutingContext.acquirePlaygroundPermit():
    ServeRateLimiter.Decision.Admitted? {
    val limiter = playgroundRateLimiter ?: return ServeRateLimiter.Decision.Admitted {}
    return when (val decision = limiter.tryAcquire(rateLimitKey())) {
      is ServeRateLimiter.Decision.Admitted -> decision
      is ServeRateLimiter.Decision.Throttled -> {
        call.response.headers.append(HttpHeaders.RetryAfter, decision.retryAfterSeconds.toString())
        call.respondText(
          JSON.encodeToString(
            PlaygroundRunResponse.serializer(),
            PlaygroundRunResponse(exception = "Too many requests — ${decision.reason}."),
          ),
          ContentType.Application.Json,
          HttpStatusCode.TooManyRequests,
        )
        null
      }
    }
  }

  private suspend fun RoutingContext.handlePlaygroundRun(service: PlaygroundCompileService) {
    if (rejectBadToken()) return
    if (rejectMissingGithubAuth(api = true)) return
    if (rejectMissingGithubRepoAccess(api = true)) return
    // After the gates, before the body read: a throttled caller should cost this host a 429 and
    // nothing else — not 256 KB of buffered upload, and certainly not a compile slot.
    val permit = acquirePlaygroundPermit() ?: return
    try {
      handlePlaygroundRunAdmitted(service)
    } finally {
      permit.release()
    }
  }

  private suspend fun RoutingContext.handlePlaygroundRunAdmitted(
    service: PlaygroundCompileService
  ) {
    val body =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { readCapped(it, MAX_PLAYGROUND_BYTES) }
      }
        ?: run {
          call.respondText(
            "playground request exceeds ${MAX_PLAYGROUND_BYTES / 1024}KB",
            status = HttpStatusCode.PayloadTooLarge,
          )
          return
        }
    val request =
      try {
        JSON.decodeFromString(PlaygroundRunRequest.serializer(), body.decodeToString())
      } catch (e: Exception) {
        call.respondText(
          "invalid playground request: ${e.message}",
          status = HttpStatusCode.BadRequest,
        )
        return
      }
    // Not advertising a neighbour is not the same as refusing to compile against one: the id is a
    // request field, so a site host has to reject it outright or the selector's absence is
    // cosmetic. Empty stays legal — that is the host's pinned default, which is not a catalog.
    val site = siteSystem()
    val foreignCatalog = request.catalog.isNotEmpty() && request.catalog != site
    // An EMPTY catalog is the pinned default, which is only legitimate here when the pin is this
    // site's own — otherwise it is a neighbour's classpath wearing the name "Server default".
    val foreignPin = request.catalog.isEmpty() && !sitePinIsOwn(service)
    if (site != null && (foreignCatalog || foreignPin)) {
      call.respondText(
        "{\"error\":\"unknown catalog\"}",
        ContentType.Application.Json,
        HttpStatusCode.NotFound,
      )
      return
    }
    val response =
      withContext(Dispatchers.IO) {
        service.run(
          request,
          isSecurityChecked = true,
          authenticatedOwner = githubAuth?.currentLogin(call),
        )
      }
    call.respondText(
      JSON.encodeToString(PlaygroundRunResponse.serializer(), response),
      ContentType.Application.Json,
    )
  }

  private suspend fun RoutingContext.handlePlaygroundEditLeaseAcquire(
    service: PlaygroundCompileService
  ) {
    if (rejectBadToken()) return
    if (rejectMissingGithubAuth(api = true)) return
    if (rejectMissingGithubRepoAccess(api = true)) return
    val owner = githubAuth?.currentLogin(call)
    if (owner == null) {
      call.respondText(
        "GitHub sign-in is required for live editing.",
        status = HttpStatusCode.Unauthorized,
      )
      return
    }
    val body =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { readCapped(it, MAX_PLAYGROUND_BYTES) }
      }
        ?: run {
          call.respondText(
            "playground request exceeds ${MAX_PLAYGROUND_BYTES / 1024}KB",
            status = HttpStatusCode.PayloadTooLarge,
          )
          return
        }
    val request =
      if (body.isEmpty()) PlaygroundEditLeaseAcquireRequest()
      else
        runCatching {
          JSON.decodeFromString(
            PlaygroundEditLeaseAcquireRequest.serializer(),
            body.decodeToString(),
          )
        }
          .getOrNull()
    if (request == null) {
      call.respondText("Invalid live-edit lease request.", status = HttpStatusCode.BadRequest)
      return
    }
    val result = withContext(Dispatchers.IO) { service.acquireEditLease(owner, request.client) }
    call.respondText(
      JSON.encodeToString(PlaygroundEditLeaseResponse.serializer(), result),
      ContentType.Application.Json,
      if (result.acquired) HttpStatusCode.OK else HttpStatusCode.Conflict,
    )
  }

  private suspend fun RoutingContext.handlePlaygroundEditLeaseRelease(
    service: PlaygroundCompileService
  ) {
    if (rejectBadToken()) return
    if (rejectMissingGithubAuth(api = true)) return
    if (rejectMissingGithubRepoAccess(api = true)) return
    val owner = githubAuth?.currentLogin(call)
    if (owner == null) {
      call.respondText(
        "GitHub sign-in is required for live editing.",
        status = HttpStatusCode.Unauthorized,
      )
      return
    }
    val body =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { readCapped(it, MAX_PLAYGROUND_BYTES) }
      }
    val request = body?.let {
      runCatching {
        JSON.decodeFromString(
          PlaygroundEditLeaseReleaseRequest.serializer(),
          it.decodeToString(),
        )
      }
        .getOrNull()
    }
    if (
      request == null || !service.releaseEditLease(owner, request.lease, client = request.client)
    ) {
      call.respondText("Live-edit lease not found.", status = HttpStatusCode.NotFound)
      return
    }
    call.respondText("{\"released\":true}", ContentType.Application.Json)
  }

  /** `GET /d/{id}`: the permalink page. An expired (or unknown) id is a styled 404, not a hint. */
  private suspend fun RoutingContext.handleDocPage(store: ServeDocStore) {
    if (rejectBadToken()) return
    val doc = leaseDoc(store)
    if (doc == null) {
      respondNotFoundHtml("That document link has expired, or never existed.")
      return
    }
    val size = doc.format.size(doc.bytes)
    // An expiring capability URL must never be stored by a shared cache — no-store, always.
    markGeneration("document", "private, no-store")
    call.respondText(
      ServeWeb.docPage(
        ServeWeb.DocView(
          id = doc.id,
          name = doc.name,
          formatId = doc.format.id,
          formatLabel = doc.format.label,
          playerPath = doc.format.playerPath,
          rawPath = "${doc.path}/raw",
          facts = doc.format.describe(doc.bytes),
          sizeText = humanBytes(doc.sizeBytes),
          expiresInText = ServeWeb.humanDuration(store.remainingSeconds(doc)),
          expiresAtText = Instant.ofEpochMilli(doc.expiresAtMillis).toString(),
          width = size?.width,
          height = size?.height,
        ),
        token = linkToken(),
        isPublic = isPublic,
        unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
        version = SERVE_VERSION,
      ),
      ContentType.Text.Html,
    )
  }

  /** `GET /d/{id}/raw`: the document bytes the browser player fetches (and the download link). */
  private suspend fun RoutingContext.handleDocRaw(store: ServeDocStore) {
    if (rejectBadToken()) return
    val doc = leaseDoc(store)
    if (doc == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    markGeneration("document", "private, no-store")
    // Client-supplied bytes: pin the declared type so no browser can sniff them into something
    // active (e.g. HTML) served from this origin.
    call.response.headers.append("X-Content-Type-Options", "nosniff")
    call.respondBytes(doc.bytes, ContentType.parse(doc.format.contentType))
  }

  // ---- The image lane (`--accept-images`) -----------------------------------------------------

  /**
   * `POST /images?name=<label>` (body = the image bytes): ingest a rendered preview and answer with
   * the URL to embed.
   *
   * **Authenticated, always.** The caller presents `Authorization: Bearer <github-token>` and must
   * come back as a collaborator on the gating repository ([ServeImageUploadAuth]) — on a `--public`
   * host too, where every browsing surface is open. This is the one write surface on the server
   * that hands out hosting under the operator's own name, so "anonymous" is not one of its
   * postures.
   *
   * `?name=` is a display label only — never a path, never the format decision (the store
   * content-sniffs).
   */
  private suspend fun RoutingContext.handleImageUpload(
    store: ServeImageStore,
    auth: ServeImageUploadAuth,
  ) {
    if (rejectBadToken()) return
    // **Before** the identity check, keyed by address: verifying a token is a synchronous call to
    // GitHub, so an unauthenticated caller spraying unique invalid tokens would otherwise spend one
    // of this host's outbound requests and one of its threads per guess — and neither the
    // fingerprint cache (every value is new) nor the per-login budget below (never reached) bounds
    // that. This is the only budget an anonymous caller is ever charged against.
    // Address-only, never [rateLimitKey]: that one prefers a signed-in cookie login, which on a
    // GitHub-auth host can be the same string the post-verification charge below uses — the two
    // budgets would then share one bucket and halve what the operator configured.
    // A grant the operator ticked `images` on is an identity in its own right, and it is checked
    // BEFORE the GitHub round trip — not as a fallback after one fails. A human operator of this
    // box already made the access decision, by hand, minutes ago and for a bounded window; asking
    // GitHub again would be asking a second question nobody needs answered, and would make an
    // agent's upload depend on a credential the whole grant flow exists so it need not hold.
    grantedImageIdentity()?.let { granted ->
      val permit = acquireImagePermit(granted.budgetKey) ?: return
      try {
        acceptImageUpload(store, granted.login)
      } finally {
        permit.release()
      }
      return
    }
    val verifyPermit = acquireImagePermit(clientAddressKey()) ?: return
    val identity =
      try {
        authorizeImageUpload(auth)
      } finally {
        // Released as soon as the GitHub round-trip is done rather than at the end of the request:
        // it exists to bound *verification*, and the accepted upload below has its own budget.
        verifyPermit.release()
      } ?: return
    // Per-caller budget, charged to the *verified* identity rather than to the client address: the
    // address of an agent in CI is shared or ephemeral, and the identity is the thing we actually
    // know. The key comes from the gate, not from the login — see [Identity.Ok.budgetKey].
    val permit = acquireImagePermit(identity.budgetKey) ?: return
    try {
      acceptImageUpload(store, identity.login)
    } finally {
      permit.release()
    }
  }

  /**
   * Store the posted image and answer `201` with the line the caller pastes.
   *
   * Split out of [handleImageUpload] because there are now two ways to have been admitted — a
   * verified GitHub credential, or an agent grant the operator ticked `images` on — and exactly one
   * thing to do afterwards. [login] is whatever the admitting gate decided attribution should read
   * as, and is what `uploadedBy` reports.
   */
  private suspend fun RoutingContext.acceptImageUpload(store: ServeImageStore, login: String) {
    val name = call.request.queryParameters["name"]
    // Cap the body as it streams in — receiving it whole first would let a client OOM the server
    // regardless of the store's own cap.
    val body =
      withContext(Dispatchers.IO) { call.receiveStream().use { readCapped(it, MAX_IMAGE_BYTES) } }
        ?: run {
          call.respondText(
            "image exceeds ${MAX_IMAGE_BYTES / (1024 * 1024)}MB",
            status = HttpStatusCode.PayloadTooLarge,
          )
          return
        }
    // isSecurityChecked = true: the identity gate above cleared this caller. The store still
    // defends in depth (format sniff, size + count caps, TTL).
    when (
      val result =
        withContext(Dispatchers.IO) {
          store.add(name, body, uploadedBy = login, isSecurityChecked = true)
        }
    ) {
      is ServeImageStore.Result.Ok -> {
        val image = result.image
        // Absolute, because the caller is about to paste it somewhere this server will never see
        // — a PR body renders on github.com, where a relative path means nothing. Built from the
        // forwarded origin, so a host behind Caddy hands back its public https:// name.
        val url = externalOrigin() + image.path
        val size = image.dimensions
        call.respondText(
          JSON.encodeToString(
            ImageAcceptedResponse.serializer(),
            ImageAcceptedResponse(
              id = image.id,
              name = image.name,
              format = image.format.label,
              formatId = image.format.id,
              bytes = image.sizeBytes,
              width = size?.width,
              height = size?.height,
              path = image.path,
              url = url,
              // The line the caller actually wanted. Handing back the finished markdown is not a
              // convenience: an agent that assembles it itself is one backtick away from the
              // `![alt](`url`)` shape that renders as literal text and silently loses the
              // evidence the upload existed to provide.
              markdown = "![${image.name}]($url)",
              uploadedBy = image.uploadedBy,
              expiresIn = ServeWeb.humanDuration(store.remainingSeconds(image)),
              expiresAtEpochSeconds = image.expiresAtMillis / 1000,
            ),
          ),
          ContentType.Application.Json,
          HttpStatusCode.Created,
        )
      }
      is ServeImageStore.Result.Failed ->
        call.respondText(result.reason, status = HttpStatusCode.BadRequest)
    }
  }

  /**
   * Charge one unit of image-lane work to [key], answering `429` + `Retry-After` and returning null
   * when the caller is over budget. A non-null result MUST be released.
   *
   * Returns a no-op permit when the operator disabled the budget, so a call site never has to
   * distinguish "admitted" from "unmetered".
   */
  private suspend fun RoutingContext.acquireImagePermit(
    key: String
  ): ServeRateLimiter.Decision.Admitted? {
    val limiter = imageUploadLimiter ?: return ServeRateLimiter.Decision.Admitted {}
    return when (val decision = limiter.tryAcquire(key)) {
      is ServeRateLimiter.Decision.Admitted -> decision
      is ServeRateLimiter.Decision.Throttled -> {
        call.response.headers.append(HttpHeaders.RetryAfter, decision.retryAfterSeconds.toString())
        call.respondText(decision.reason, status = HttpStatusCode.TooManyRequests)
        null
      }
    }
  }

  /**
   * The upload identity of a live agent grant carrying [AgentGrantCapability.IMAGES], or null when
   * this call presents no such grant (in which case the GitHub gate has its say as before).
   *
   * This is the whole of the link between the grant lane and the image lane, and it is small on
   * purpose: a grant does not become a GitHub account here, it becomes *an admitted caller with a
   * name*. Two details carry the weight.
   *
   * **Attribution names the grant and the human behind it**, so `uploadedBy` on the stored image
   * and in the `201` reads `agent grant 682daf65 (approved by @yschimke)` rather than borrowing a
   * login nobody authenticated. An operator reading `/status` can tell a grant's upload from a
   * collaborator's at a glance, and the approver is on the record either way.
   *
   * **The budget key is the grant**, not the address and not a login: a grant is already bounded
   * (it expires, it is revocable, and the box caps how many are live), so per-grant is the bucket
   * that matches what was actually handed out. Two grants approved for two different tasks do not
   * throttle each other, and one grant cannot spend another's budget.
   */
  private fun RoutingContext.grantedImageIdentity(): ServeImageUploadAuth.Identity.Ok? {
    val grant = agentGrantFor(call) ?: return null
    if (!grant.allows(AgentGrantCapability.IMAGES)) return null
    return ServeImageUploadAuth.Identity.Ok(
      login = "agent grant ${grant.fingerprint} (approved by ${grant.approvedBy})",
      budgetKey = "grant:${grant.id}",
    )
  }

  /**
   * The verified identity behind this upload, or null once the refusal has been written. Split out
   * so the route reads as "who is this, then do the work" and the two refusal shapes (no credential
   * vs. not good enough) stay in one place.
   */
  private suspend fun RoutingContext.authorizeImageUpload(
    auth: ServeImageUploadAuth
  ): ServeImageUploadAuth.Identity.Ok? {
    val header = call.request.headers[HttpHeaders.Authorization]
    val bearer = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)
    return when (val identity = auth.identify(bearer)) {
      is ServeImageUploadAuth.Identity.Ok -> identity
      is ServeImageUploadAuth.Identity.Missing -> {
        call.response.headers.append(
          HttpHeaders.WWWAuthenticate,
          "Bearer realm=\"compose-preview\"",
        )
        call.respondText(
          "Uploading preview images requires a GitHub token with access to ${auth.repository}. " +
            "Send it as: Authorization: Bearer <token>  (e.g. \"\$(gh auth token)\").",
          status = HttpStatusCode.Unauthorized,
        )
        null
      }
      is ServeImageUploadAuth.Identity.Refused -> {
        call.respondText(
          identity.reason,
          status = HttpStatusCode.fromValue(identity.status),
        )
        null
      }
    }
  }

  /**
   * `GET /i/{id}.png`: the image itself.
   *
   * **Deliberately ungated, even on a token-gated host**, and this is the one asymmetry in the lane
   * worth stating plainly. The document lane appends the host token to the permalink it hands back,
   * which is right for a link pasted into a chat — but this URL's destination is a *pull request
   * body*, so the same trick would publish the server's browse token to everyone who can read the
   * PR. And GitHub fetches embedded images through its own proxy, anonymously: a gated URL would
   * never paint. So the 128-bit id carries the whole grant, exactly as it does for `/d/<id>`, and
   * the token stays out of it.
   */
  private suspend fun RoutingContext.handleImage(store: ServeImageStore) {
    val raw = call.parameters["id"] ?: ""
    // `<id>.png` is one path segment. Split the suffix off and hand it to the store, which decides
    // whether it is the right one for what it holds.
    val dot = raw.lastIndexOf('.')
    val id = if (dot > 0) raw.substring(0, dot) else raw
    val extension = if (dot > 0) raw.substring(dot) else null
    val image = if (ServeCapabilityId.isWellFormed(id)) store.get(id, extension) else null
    if (image == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    // The id is content-addressed enough to be immutable *while it lives*: the bytes behind it
    // never change, so a proxy may keep them — but only up to the link's own expiry, never past it.
    markGeneration("image", "public, max-age=${store.remainingSeconds(image).coerceAtLeast(1)}")
    // Client-supplied bytes: pin the declared type so no browser can sniff them into something
    // active (e.g. HTML) served from this origin.
    call.response.headers.append("X-Content-Type-Options", "nosniff")
    call.respondBytes(
      image.bytes,
      ContentType.parse(ServeImageFormats.contentTypeOf(image.format, image.bytes)),
    )
  }

  /** The live document this request addresses, or null when the id is malformed/expired/unknown. */
  private fun RoutingContext.leaseDoc(store: ServeDocStore): ServeDocStore.Doc? {
    val id = call.parameters["id"] ?: return null
    return if (ServeDocStore.isWellFormedId(id)) store.get(id) else null
  }

  /**
   * Serve a vendored player bundle: ungated (generic client code, no session data), CORS-open so a
   * sandboxed viewer iframe can pull it, cached with a content ETag so a repeat load is a
   * cheap 304.
   */
  private suspend fun RoutingContext.respondPlayerAsset(asset: PlayerAsset) {
    if (asset.bytes.isEmpty()) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
    call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=3600")
    call.response.headers.append(HttpHeaders.ETag, asset.etag)
    if (call.request.headers[HttpHeaders.IfNoneMatch] == asset.etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    call.respondBytes(asset.bytes, ContentType.parse("text/javascript"))
  }

  /**
   * `GET /doc-player/{format}/bundle.js`: a format's vendored browser player, from the registry.
   */
  private suspend fun RoutingContext.handleDocPlayer() {
    val format = call.parameters["format"]?.let { ServeDocFormats.byId(it) }
    if (format == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    respondPlayerAsset(playerAsset(format.playerResource))
  }

  /**
   * `GET /rc-fonts/{name}`: the generated `@font-face` stylesheet ([ServeRcFonts.STYLESHEET]) or
   * one of the vendored faces it declares. Anything else 404s — the route serves a fixed, declared
   * set, never an arbitrary classpath path.
   *
   * Cached like the player bundles (ETag + a short `max-age`): the bytes are fixed at build time,
   * so a repeat visitor revalidates cheaply instead of re-downloading a few hundred KB per face.
   */
  private suspend fun RoutingContext.handleRcFont() {
    val name = call.parameters["name"] ?: ""
    if (name == ServeRcFonts.STYLESHEET) {
      val css = ServeRcFonts.css()
      call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
      call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=3600")
      call.respondText(css, ContentType.parse("text/css; charset=utf-8"))
      return
    }
    val resource = ServeRcFonts.resourceFor(name)
    if (resource == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    val asset = playerAsset(resource)
    if (asset.bytes.isEmpty()) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
    call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=3600")
    call.response.headers.append(HttpHeaders.ETag, asset.etag)
    if (call.request.headers[HttpHeaders.IfNoneMatch] == asset.etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    call.respondBytes(asset.bytes, ContentType.parse("font/ttf"))
  }

  /** `GET /assets/serve/{version}/{name}`: static ServeWeb CSS/JS extracted from raw strings. */
  private suspend fun RoutingContext.handleServeWebAsset(versioned: Boolean) {
    val name = call.parameters["name"] ?: ""
    val asset = ServeWebAssets.load(name)
    val version = call.parameters["version"]
    if (asset == null || (versioned && version != asset.version)) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
    call.response.headers.append(
      HttpHeaders.CacheControl,
      if (versioned) "public, max-age=31536000, immutable" else "no-cache",
    )
    call.response.headers.append(HttpHeaders.ETag, asset.etag)
    if (call.request.headers[HttpHeaders.IfNoneMatch] == asset.etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    call.respondBytes(asset.bytes, ContentType.parse(asset.contentType))
  }

  /**
   * Describe the work behind a response in headers that survive a reverse proxy. This lets a
   * browser, curl, or an agent distinguish cheap published bytes from a daemon render without
   * access to the host logs. Static pages are cacheable only in public mode: token-bearing private
   * URLs must never be stored by a shared cache.
   */
  private fun ApplicationCall.markGeneration(generation: String, cacheControl: String? = null) {
    response.headers.append(GENERATION_HEADER, generation)
    cacheControl?.let { response.headers.append(HttpHeaders.CacheControl, it) }
    // Belt to `private, no-store`'s braces: an intermediary that under-honours the directive still
    // learns the body turns on the session cookie, rather than keying one visitor's HTML by URL
    // alone.
    // Load-bearing for ANON_PAGE_CACHE_CONTROL, not just belt-and-braces: that value invites a
    // shared cache to store the response, and without `Vary: Cookie` the cache would key one
    // anonymous rendering by URL alone and hand it to a signed-in visitor.
    if (cacheControl == SIGNED_IN_PAGE_CACHE_CONTROL || cacheControl == ANON_PAGE_CACHE_CONTROL) {
      varyOnCookie()
    }
  }

  private fun RoutingContext.markGeneration(generation: String, cacheControl: String? = null) =
    call.markGeneration(generation, cacheControl)

  private fun incrementPreviewViews(
    sessionId: String,
    previewId: String,
  ): ServeWeb.PreviewEngagement =
    ServeWeb.PreviewEngagement(engagementStore.incrementPreview(sessionId, previewId))

  private fun previewEngagement(sessionId: String, previews: List<ServePreview>) =
    engagementStore.previewViews(sessionId, previews.map { it.id }).mapValues {
      ServeWeb.PreviewEngagement(it.value)
    }

  /** `GET /` (query) and `GET /{system}[/]` (path): the session's preview-list landing page. */
  private suspend fun RoutingContext.handleLanding(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    // Front door: when this server publishes design-system catalogs, the bare `/` (no `?session=`,
    // no `/<system>` path) is an INDEX of those systems — each with a meaningful preview — rather
    // than an arbitrary default module's grid. A plain `serve` (no `--catalogs`) keeps the module
    // landing. A query `?session=` or a `/<system>` path still selects that session's landing
    // below.
    // …unless this request arrived on a top-level site host, whose `/` IS its catalog's landing.
    // A site that opened on an index of its neighbours would be advertising exactly what it exists
    // not to.
    if (
      !sessionInPath &&
        siteSystem() == null &&
        (listedCatalogs().isNotEmpty() || unlistedCatalogs().isNotEmpty()) &&
        call.request.queryParameters["session"] == null
    ) {
      handleHomeIndex()
      return
    }
    val (webSessionId, basePath) = webSessionAndBase(sessionInPath)
    val selectedSessionId = selectedSessionId(sessionInPath)
    withLeasedSession(
      selectedSessionId,
      onMissing = { respondNotFoundHtml("That design system was not found on this server.") },
    ) { renderHost ->
      val systemViews =
        if (isViewRequest()) engagementStore.incrementSystem(selectedSessionId)
        else engagementStore.systemViews(listOf(selectedSessionId)).getValue(selectedSessionId)
      val heroId =
        catalogBundleHost(renderHost)?.declaredHeroPreviewId
          ?: ServeWeb.representativePreviewId(renderHost.previews)
      val heroUrl = heroId?.let {
        externalOrigin() +
          basePath +
          "/render/${WebEscaping.urlEncodeSegment(it)}.png" +
          requestQuerySuffix()
      }
      // Measured off the PNG header, so the unfurl card can declare `og:image:width`/`height`
      // instead of making the fetcher download the render to find out. Skipped when the URL carries
      // overrides — `heroUrl` inherits the page's query, so the image would be a re-render at a
      // size
      // the bake doesn't describe (same reasoning as the viewer's `imageSize`).
      val heroSize =
        if (requestCarriesOverrides()) null else heroId?.let { renderHost.bakedRenderSize(it) }
      // …and, like the front door, prefer a **drawn** card over the render itself: this catalog's
      // hero thumbnail set into a 1200×630 layout with the catalog's name and preview count, rather
      // than a bare phone screenshot an unfurler has to crop to a band. Same reasoning and the same
      // baked pixels as [handleHomeIndex]; see [ServeSocialCard].
      //
      // Skipped when the request carries overrides, for the reason `heroSize` is: the page then
      // describes a re-render, and a card built from the baked hero would advertise the wrong
      // picture for that URL.
      //
      // The hero is baked here rather than read out of `catalogMetaSeen`, so a visitor who lands
      // straight on `/<system>/` — the shape of URL people actually share — gets a card without
      // having gone through the front door first. It is the same memoised call the front door makes
      // ([ServeHeroImages.heroFor] is per host instance), so this costs one decode per catalog for
      // the whole life of that host, not one per request.
      val bundle = catalogBundleHost(renderHost)
      val heading = ServeWeb.catalogHeading(bundle?.title, renderHost.label)
      // Hoisted out of the argument list because the header's sign-in control reads it too: a
      // catalog with neither a live lane nor a reachable playground has nothing behind a login.
      val catalogPlaygroundHref = playgroundLinkForCatalog(selectedSessionId)
      val card =
        if (requestCarriesOverrides() || bundle == null || heroId == null) null
        else
          withContext(Dispatchers.IO) {
            heroImages.heroFor(bundle, heroId, bundle.contentCrop(heroId))?.let { hero ->
              socialCards.cardFor(
                ServeSocialCard.Spec(
                  title = heading,
                  subtitle = ServeWeb.catalogCardSubtitle(renderHost.previews.size),
                  heroes = listOf(hero),
                  system = selectedSessionId,
                )
              )
            }
          }
      val unfurl =
        if (card != null)
          ServeWeb.UnfurlMetadata(
            pageUrl = externalPageUrl(),
            imageUrl = externalOrigin() + ServeSocialCard.PATH_PREFIX + "/" + card.fileName,
            imageWidth = card.width,
            imageHeight = card.height,
          )
        else
        // No baked hero for this catalog yet, or the request carries overrides. The render is a
        // worse picture but a real one, and `twitterCard` demotes it to the small card its shape
        // can fill rather than claiming a banner it cannot.
        ServeWeb.UnfurlMetadata(
            pageUrl = externalPageUrl(),
            imageUrl = heroUrl,
            imageWidth = heroSize?.first,
            imageHeight = heroSize?.second,
          )
      markGeneration("static-page", pageCacheControl())
      call.respondText(
        ServeWeb.landingPage(
          renderHost.label,
          renderHost.previews,
          linkToken(),
          webSessionId,
          trust = catalogBundleHost(renderHost)?.let { BundleVerifier.summary(it.trust) },
          isPublic = isPublic,
          componentBrowser = componentBrowserMode(),
          // A back-to-home button whenever this server publishes a front-door index — listed
          // catalogs OR unlisted app catalogs (mirrors handleLanding's home-index condition), so an
          // app-only server's landings still link home.
          // …and never on a top-level site: there is no front door on this hostname to go back to,
          // and a "← All design systems" button would either lie or leave the domain.
          hasHomeIndex =
            siteSystem() == null &&
              (listedCatalogs().isNotEmpty() || unlistedCatalogs().isNotEmpty()),
          basePath = basePath,
          changelogHref = changelogHref(selectedSessionId, basePath, webSessionId),
          // One action per comparable format, each gated on the same condition `comparisonPage`
          // turns that format on with — so "compare SVG" and "compare RC players" only appear when
          // there is something behind them.
          hasSvgComparison = renderHost.previews.any { renderHost.hasSvgExportFor(it.id) },
          hasRcComparison =
            renderHost.rcCompare() != null ||
              renderHost.previews.any { renderHost.hasRemoteComposeDoc(it.id) },
          // Same condition `comparisonPage` turns the `reference` format on with, so the deep link
          // never lands on a format the page does not offer.
          hasReferenceComparison =
            renderHost.previews.any { renderHost.designReferencesFor(it.id).isNotEmpty() },
          // Same condition `handleParity` serves on, so the link never leads to that route's 404 —
          // and, since the acceptance lane was added there, so the page it made reachable is not
          // reachable only by typing the URL. An orphan-only catalog is precisely the one whose
          // dashboard has something to say and whose landing would otherwise offer no way in.
          hasParityView =
            renderHost.parityActivity() != null ||
              renderHost.parityIssues() != null ||
              renderHost.knownDifferences() != null ||
              renderHost.previews.any { renderHost.designReferencesFor(it.id).isNotEmpty() },
          parityIssues = renderHost.parityIssues()?.issues.orEmpty(),
          // Same count `handleMotionIndex` gates on, so the chip never leads to that route's 404.
          motionCaptureCount = renderHost.previews.sumOf { it.motion.size },
          // Same condition `handleDesignPageIndex` serves on, for the same reason. Listed by name
          // in the navigation tree, so the landing has to know what they are called, not just how
          // many there are.
          designPages =
            renderHost.designPages().pages.map { page ->
              ServeWeb.PageLink(page.id, page.name, designPageSections(page))
            },
          // …and name that action after the design tool the catalog is specified by, read from the
          // references it published (or from the parity feed's Figma lane when the references are
          // rasters with no provider). Null ⇒ the generic "design parity" label.
          designToolLabel =
            renderHost.previews.firstNotNullOfOrNull { preview ->
              renderHost.designReferencesFor(preview.id).firstNotNullOfOrNull {
                ServeWeb.designToolLabel(it.source.provider)
              }
            } ?: renderHost.parityActivity()?.figma?.let { "Figma" },
          version = SERVE_VERSION,
          // Catalog provenance (delivery branch, generation date, tool versions) for the strip
          // under the header; null for a plain (non-catalog) module session.
          provenance = catalogBundleHost(renderHost)?.provenance,
          refreshUrl =
            if (catalogRefresh != null) "$basePath/refresh${requestQuerySuffix()}" else null,
          // "try in playground" — opens the editor with this design system preselected, so a
          // snippet compiles against the catalog you were just browsing. Omitted on a host with no
          // lane; the per-preview handoff is the viewer's `playgroundHref`.
          playgroundHref = catalogPlaygroundHref,
          // Crop each card's thumbnail to the component's figma-svg content box (cheap baked
          // reads),
          // so a Wear sticker shows the component, not the empty watch canvas around it.
          thumbCrop = { id -> catalogBundleHost(renderHost)?.contentCrop(id) },
          // …and point each card at a prebaked, downscaled copy of its render where one can be
          // baked from local pixels, so the page ships a few hundred kB of thumbnails instead of a
          // couple of MB of full-resolution PNGs. Baking reads only what is already on disk (see
          // [ServeHeroImages.gridThumbFor]) — this runs per card on the request thread, so it must
          // never fetch.
          thumbHash = { id ->
            heroImages
              .gridThumbFor(renderHost, id, catalogBundleHost(renderHost)?.contentCrop(id))
              ?.hash
          },
          // A heartbeat while the tab is open, so a visitor reading the grid keeps their session —
          // and its daemon — alive. Especially now: the cards above are cacheable, so browsing this
          // page can make no requests at all for as long as someone cares to read it.
          presenceUrl = "$basePath/api/presence${requestQuerySuffix()}",
          // The catalog's declared stage surface (`display.surface`), so a dark-first system's
          // unthemed cards sit on the dark stage instead of the default white.
          declaredSurface = catalogBundleHost(renderHost)?.stageSurface,
          // …and its own colour palette, so this system's pages are framed in its colours.
          themeCss = catalogBundleHost(renderHost)?.webThemeCss.orEmpty(),
          // Why the catalog is snapshot-only, when it is (no live bundle, unverified, …) — shown as
          // a banner under the header so a browser sees it before opening a preview.
          degradations = renderHost.degradations,
          // The module's declared @ThemeCatalog themes join the header's Theme control, so the grid
          // can be redrawn under any theme the catalog configures — not just baked Light/Dark. Only
          // a daemon-twinned card can actually re-render one, hence the per-preview predicate.
          declaredThemes = applicableThemes(renderHost),
          canRenderThemeFor = { id -> renderHost.canRenderOverridesFor(id) },
          // …and a twin that REPLAYS a captured document rather than recomposing can't honour a
          // theme provider either, however live it is: the render below refuses it with a terminal
          // 409. Same predicate that refusal is derived from, deliberately read here rather than
          // re-derived — the viewer greys the identical choice off `irReplay`, and a grid that
          // disagreed with either would offer chips that turn every card into an error.
          // A replayed card is theme-overridable exactly when it can apply every theme this page
          // offers. On a pure-replay catalog the chips below are already narrowed to the mapped
          // ones, so that is the whole declared set and nothing changes. In a **mixed** catalog the
          // chips are the union — one recomposing preview is enough to publish all of them — and a
          // replayed card mapped for only some would light up chips that 409 it.
          irReplayFor = { id ->
            isReplayedPreview(renderHost, id) && !everyThemeApplies(renderHost, id)
          },
          // Long-press a card to open a live daemon session inside it. Same two conditions the
          // viewer's Live toggle answers to — the session offers the stream lane, and this preview
          // has a daemon twin to stream — so a card only takes the gesture when the socket behind
          // it would deliver real frames rather than replaying baked pixels.
          canStreamLiveFor = { id ->
            renderHost.hasLiveStream && renderHost.canRenderOverridesFor(id)
          },
          // …and when the box gates its live lanes on GitHub, the press answers with the sign-in
          // rather than a socket that would close 1008.
          liveSignInHref =
            githubAuth
              ?.takeIf { renderHost.hasLiveStream }
              ?.takeUnless { it.isAuthenticated(call) }
              ?.takeIf { oauthCanRoundTrip() }
              ?.loginPath(call),
          themeRenderBurstCapacity = renderHost.themeRenderBurstCapacity,
          engagement = previewEngagement(selectedSessionId, renderHost.previews),
          systemViews = systemViews,
          unfurl = unfurl,
          displayTitle = bundle?.title,
          // A top-level site's pages carry their session in the ORIGIN, so same-session links
          // drop the `?session=` the rooted legacy form would add. See [ServeSites].
          sessionInOrigin = siteSystem() != null,
          // The header's sign-in control. `liveSignInHref` above already sends a long-press at
          // the login, but that gesture is undiscoverable: on a site host this landing IS the
          // front door, so without a visible control the only way to find the sign-in was
          // another hostname's index (wear-m3-catalog#68).
          //
          // Only where a login unlocks something on THIS catalog: a live lane to stream, or a
          // playground that compiles against it. A static bundle (or one whose live breaker has
          // opened) with no playground has nothing behind the control, and inviting a sign-in that
          // changes nothing is the dead affordance the viewer's chip already refuses to be. The
          // front door keeps its unconditional control — it stands above every catalog, so it
          // cannot answer for one, and any of them may offer a lane.
          //
          // The lane it speaks for is whichever this catalog actually has. With no live stream the
          // playground is the only thing behind the login, and its gate is repository access — so
          // the control says so instead of promising a Live lane this catalog cannot offer.
          githubAuth =
            when {
              renderHost.hasLiveStream -> githubAuthStatus()
              catalogPlaygroundHref != null -> githubAuthStatus(ServeWeb.GatedLane.PLAYGROUND)
              else -> null
            },
          // The catalog report on the page most visitors arrive on — what the floating launcher's
          // catalog half points at in Dev, and the only reporting affordance Catalog mode has at
          // all. Scoped to the page rather than to a card: the grid singles out no component, and a
          // report naming one the reporter never picked would be worse than one naming the catalog.
          reportIssue = pageScopedReportIssue(renderHost, selectedSessionId, "this catalog"),
        ),
        ContentType.Text.Html,
      )
    }
  }

  /** Join this page to its catalog's short-lived themed-thumbnail burst allocation. */
  private suspend fun RoutingContext.handleThemeRenderLease(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    // This route mints nothing but permission to run a *burst of live theme renders*. A `preview`
    // grant would be handed the lease and then refused every render it authorises, which is a
    // confusing way to say no; refuse the ticket instead. (The release counterpart is deliberately
    // ungated — handing capacity back is always welcome.)
    if (rejectGrantBelowScope(AgentGrantScope.LIVE, api = true)) return
    val sessionId = selectedSessionId(sessionInPath)
    withLeasedSession(sessionId) { renderHost ->
      val grant =
        themeRenderLeases.acquire(
          sessionId = sessionId,
          hostIdentity = renderHost,
          requestedCapacity = renderHost.themeRenderBurstCapacity,
        )
      if (grant == null) {
        call.response.headers.append(HttpHeaders.RetryAfter, "2")
        call.respondText("theme render burst unavailable", status = HttpStatusCode.Conflict)
      } else {
        call.respondText(
          """{"lease":"${grant.token}","concurrency":${grant.concurrency},"expiresAt":${grant.expiresAtMillis}}""",
          ContentType.Application.Json,
        )
      }
    }
  }

  /**
   * `POST /api/presence` (and its `/{system}/` form): a heartbeat from an open catalog tab.
   *
   * Sessions are reaped after an idle window ([ServeSessionRegistry.DEFAULT_IDLE_TIMEOUT_MILLIS]),
   * and idleness is measured in *requests*. A visitor reading one catalog page makes none — the
   * more so now that its thumbnails and heroes are `immutable` and repaint from cache — so a tab
   * that has been open for a quarter of an hour looks exactly like an abandoned one, and the daemon
   * behind it is shut down under a visitor who is still there. This is the signal that says
   * otherwise.
   *
   * Two things happen, and the cheap one is the important one:
   * - Leasing the session marks it as in use, which is what actually keeps it (and its daemon)
   *   resident, and resumes it if it had already been suspended.
   * - [ServeHost.keepLiveWarm] then gets its live lane ready, for the visitor who has only browsed
   *   prebaked pixels and so has never woken a daemon at all — the common case by design.
   *
   * Deliberately silent: 204 with no body, no error surface. A heartbeat is not something a page
   * can act on, and one that fails (offline, a catalog since removed) simply means the next one
   * tries again.
   */
  private suspend fun RoutingContext.handlePresence(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    // `keepLiveWarm()` below starts or retains a daemon — the definition of `live`, however small
    // each individual ping looks.
    if (rejectGrantBelowScope(AgentGrantScope.LIVE, api = true)) return
    withLeasedSession(
      selectedSessionId(sessionInPath),
      onMissing = { call.respond(HttpStatusCode.NoContent) },
    ) { renderHost ->
      // Warming is the only part that can cost anything, so it is gated on the live-seat budget: on
      // a busy box a browsing visitor's *convenience* ranks below someone else's actual request.
      // A load signal, not a reservation — the warm runs off the request path and takes no seat of
      // its own — but it is enough to stop a room full of idle tabs from each waking a daemon while
      // the box is already saturated. The keepalive's other half (the lease above) is
      // unconditional:
      // holding on to a daemon that is already up costs nothing and is the whole point.
      if (liveSeats.availablePermits() > 0) renderHost.keepLiveWarm()
      call.respond(HttpStatusCode.NoContent)
    }
  }

  /** Best-effort page/queue release; fixed lease expiry remains authoritative. */
  private suspend fun RoutingContext.handleThemeRenderLeaseRelease() {
    if (rejectBadToken()) return
    val lease = call.request.queryParameters["lease"]
    if (lease.isNullOrBlank()) {
      call.respondText("missing lease", status = HttpStatusCode.BadRequest)
      return
    }
    themeRenderLeases.release(lease)
    call.respond(HttpStatusCode.NoContent)
  }

  /** `GET /compare` and `GET /{system}/compare`: native format-fidelity comparison gallery. */
  private suspend fun RoutingContext.handleFormatComparison(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val sessionId = selectedSessionId(sessionInPath)
    val (webSessionId, basePath) = webSessionAndBase(sessionInPath)
    withLeasedSession(
      sessionId,
      onMissing = { respondNotFoundHtml("That design system was not found on this server.") },
    ) { renderHost ->
      // The published player comparison is itself a comparable format: a catalog can carry it even
      // where the per-preview `.rc` sidecars didn't make it into the served staging dir.
      val rcCompare = renderHost.rcCompare()
      val comparable =
        rcCompare != null ||
          renderHost.previews.any { preview ->
            renderHost.hasSvgExportFor(preview.id) ||
              renderHost.hasRemoteComposeDoc(preview.id) ||
              renderHost.designReferencesFor(preview.id).isNotEmpty()
          }
      if (!comparable) {
        respondNotFoundHtml("This session has no native formats or design references to compare.")
        return@withLeasedSession
      }
      // Uncacheable while the published player comparison is still staging: the page's shape
      // depends on a manifest that lands asynchronously, and a short edge cache would otherwise
      // serve the pre-manifest shape for minutes after the lanes were ready.
      markGeneration(
        "static-page",
        if (renderHost.rcComparePending()) DYNAMIC_RESOURCE_CACHE_CONTROL else pageCacheControl(),
      )
      // The wall's own "report a catalog issue" — the launcher's catalog half, which stays hidden
      // on a page that carries no `#cp-report` and left this page offering the SERVER tracker as
      // its only route (issue #4289). Page-scoped: the wall names no single preview, so neither
      // does the report — it carries the page (with the lane its query names), the catalog build
      // and the tool version, and drops the preview-shaped rows the way every other optional fact
      // is dropped. A row's own defect keeps the better route it already had: opening the focused
      // comparison, which files against that exact preview and reference.
      val reportIssue =
        pageScopedReportIssue(renderHost, sessionId, "these comparisons", pickable = true)
      call.respondText(
        ServeWeb.comparisonPage(
          moduleLabel = renderHost.label,
          previews = renderHost.previews,
          token = linkToken(),
          sessionId = webSessionId,
          basePath = basePath,
          changelogHref = changelogHref(sessionId, basePath, webSessionId),
          isPublic = isPublic,
          trust = catalogBundleHost(renderHost)?.let { BundleVerifier.summary(it.trust) },
          declaredSurface = catalogBundleHost(renderHost)?.stageSurface,
          // …and its own colour palette, so this system's pages are framed in its colours.
          themeCss = catalogBundleHost(renderHost)?.webThemeCss.orEmpty(),
          hasSvgFor = renderHost::hasSvgExportFor,
          hasRemoteComposeFor = renderHost::hasRemoteComposeDoc,
          rcCompare = rcCompare,
          referencesFor = renderHost::designReferencesFor,
          unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
          reportIssue = reportIssue,
          generation = catalogGeneration(renderHost),
          // The whole index, unfiltered: the wall joins it to every row itself, which is a join it
          // has to do per row anyway and one this handler cannot do for it.
          parityIssues = renderHost.parityIssues()?.issues.orEmpty(),
          version = SERVE_VERSION,
          displayTitle = catalogBundleHost(renderHost)?.title,
          // A top-level site's pages carry their session in the ORIGIN, so same-session links
          // drop the `?session=` the rooted legacy form would add. See [ServeSites].
          sessionInOrigin = siteSystem() != null,
        ),
        ContentType.Text.Html,
      )
    }
  }

  /**
   * `GET /<system>/parity` — the catalog's design-parity dashboard: coverage, the merged code ↔
   * Figma activity feed, and the mapping gaps.
   *
   * Offered for every session, not only ones publishing an activity feed: the coverage half is
   * computed live from the previews and their design references, so a catalog that has adopted
   * nothing new still gets an honest "N of M components are mapped" page. Only a session with
   * neither references nor a feed 404s, because there the page would be a table of zeroes.
   *
   * `?format=json` returns the same dashboard as data — the shape a CI check or a dashboard poller
   * wants, and the reason the view model is computed in [ServeParityDashboard] rather than inline
   * in the HTML.
   */
  private suspend fun RoutingContext.handleParity(sessionInPath: Boolean, json: Boolean) {
    if (rejectBadToken()) return
    @Suppress("NAME_SHADOWING")
    val json = json || call.request.queryParameters["format"].equals("json", ignoreCase = true)
    val sessionId = selectedSessionId(sessionInPath)
    val (webSessionId, basePath) = webSessionAndBase(sessionInPath)
    withLeasedSession(
      sessionId,
      onMissing = {
        if (json) call.respond(HttpStatusCode.NotFound)
        else respondNotFoundHtml("That design system was not found on this server.")
      },
    ) { renderHost ->
      val activity = renderHost.parityActivity()
      val hasReference = { id: String -> renderHost.designReferencesFor(id).isNotEmpty() }
      val mapped = renderHost.previews.any { hasReference(it.id) }
      val issues = renderHost.parityIssues()?.issues.orEmpty()
      // A published known-difference document keeps the page reachable on its own, alongside the
      // three lanes that already do. It is the one lane whose *interesting* state is a catalog with
      // nothing else left: every acceptance in it may name a preview or reference this session no
      // longer serves, which is exactly `orphaned-target` — and 404ing here would withhold the
      // panel
      // from the only catalog whose whole document is the finding.
      // **The HTML page only.** `ParityResponse` carries coverage, drift, activity and gaps — all
      // of
      // which are empty for such a catalog — and nothing about acceptances, because the host does
      // not
      // parse that document and the verdicts are the browser's. Admitting `?format=json` here would
      // answer 200 with a dashboard of zeroes whose one interesting fact is unrepresentable in the
      // schema, which reads as "this catalog is fine" to exactly the CI check that shape exists
      // for.
      val accepts = renderHost.knownDifferences() != null
      if (activity == null && !mapped && issues.isEmpty() && (json || !accepts)) {
        if (json) call.respond(HttpStatusCode.NotFound)
        else
          respondNotFoundHtml(
            "This session publishes no design references and no parity activity feed."
          )
        return@withLeasedSession
      }
      val dashboard =
        ServeParityDashboard.build(
          previews = renderHost.previews,
          hasReference = hasReference,
          activity = activity,
          referenceIdFor = { id -> renderHost.designReferencesFor(id).firstOrNull()?.id },
        )
      if (json) {
        markGeneration("parity", pageCacheControl())
        call.respondText(
          JSON.encodeToString(ParityResponse.serializer(), ParityResponse.of(dashboard, issues)),
          ContentType.Application.Json,
        )
        return@withLeasedSession
      }
      // **The audit-bearing page is not cacheable**, the way an `rcComparePending` comparison is
      // not.
      // The walk joins two things of different lifetimes: the preview inventory and the issue rows
      // are baked into this HTML, while the document it walks is fetched live at `no-store`. Served
      // from cache after an in-place catalog refresh, a *fresh* document would be resolved against
      // a
      // *stale* inventory — and a preview added or renamed in between reads as `orphaned-target`,
      // which is a false finding of exactly the kind this panel exists to make trustworthy. The
      // comparison band has no such gap: it is generation-bound by `referenceSha256`, and there is
      // no equivalent anchor for a walk over the whole catalog.
      markGeneration(
        "static-page",
        if (accepts) DYNAMIC_RESOURCE_CACHE_CONTROL else pageCacheControl(),
      )
      call.respondText(
        ServeWeb.parityPage(
          moduleLabel = renderHost.label,
          dashboard = dashboard,
          token = linkToken(),
          sessionId = webSessionId,
          basePath = basePath,
          changelogHref = changelogHref(sessionId, basePath, webSessionId),
          isPublic = isPublic,
          trust = catalogBundleHost(renderHost)?.let { BundleVerifier.summary(it.trust) },
          themeCss = catalogBundleHost(renderHost)?.webThemeCss.orEmpty(),
          unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
          version = SERVE_VERSION,
          displayTitle = catalogBundleHost(renderHost)?.title,
          hasReferenceFor = hasReference,
          parityIssues = issues,
          generation = catalogGeneration(renderHost),
          // The catalog-wide acceptance walk, offered only to a catalog that publishes a
          // known-difference document. This is the walk's target set, and every field is spelled
          // the
          // way the comparison page spells it in its locator — `system` from the mount, `component`
          // and `variant` from [ServeIssueReport] — because an acceptance matches on all of them
          // and
          // a second derivation here would report the whole document orphaned.
          //
          // The handler decides the identity; the page builds the URLs, which is the same split
          // [KnownDifferenceScope] draws and the reason a hand-rolled query never loses its token.
          acceptanceAudit =
            renderHost.knownDifferences()?.let {
              renderHost.previews.map { preview ->
                KnownDifferenceCatalogPreview(
                  // The **resolved session id**, not the base path's first segment. The two differ
                  // for a catalog whose name carries a character a URL segment escapes (`@` is
                  // legal in a session name and encodes to `%40`), and an identity that changed
                  // spelling with the route form would report a document healthy through
                  // `/<system>/parity` and orphaned through `?session=`, from the same bytes.
                  system = sessionId,
                  id = preview.id,
                  component = ServeIssueReport.componentIdFor(preview),
                  variant = ServeIssueReport.variantFor(preview),
                  referenceIds = renderHost.designReferencesFor(preview.id).map { it.id },
                )
              }
            },
          // Same derivation the landing uses to label its "compare to Figma" action, so the page a
          // visitor arrives on names the tool the same way the link that brought them here did.
          designToolLabel =
            renderHost.previews.firstNotNullOfOrNull { preview ->
              renderHost.designReferencesFor(preview.id).firstNotNullOfOrNull {
                ServeWeb.designToolLabel(it.source.provider)
              }
            } ?: activity?.figma?.let { "Figma" },
          // A top-level site's pages carry their session in the ORIGIN, so same-session links
          // drop the `?session=` the rooted legacy form would add. See [ServeSites].
          sessionInOrigin = siteSystem() != null,
        ),
        ContentType.Text.Html,
      )
    }
  }

  /**
   * Refuse a request whose `at=` is present but is not a commit sha.
   *
   * The alternative — ignoring it — is the one behaviour this feature must never have: it would
   * answer a request that explicitly asked for a particular publish with whatever is current, under
   * a URL whose whole promise is the opposite, and nothing about the response would say so. A
   * `?at=main` is a mistake worth naming rather than quietly satisfying (see
   * [ServeCatalogRevision.normalize] for why a ref is not a pin).
   */
  private suspend fun RoutingContext.rejectMalformedPin(): Boolean {
    val raw = call.request.queryParameters[ServeCatalogRevision.PARAM] ?: return false
    if (ServeCatalogRevision.normalize(raw) != null) return false
    call.respondText(
      "'${ServeCatalogRevision.PARAM}' must be a commit sha (7-40 hex), not a branch or tag",
      status = HttpStatusCode.BadRequest,
    )
    return true
  }

  /**
   * Refuse a request whose `gen=` is present but is not a commit sha.
   *
   * Same rule and same reason as [rejectMalformedPin]: a generation that isn't one resolves to "the
   * page could not name its own publish", and the only honest answers to that are a 400 or a
   * response that is explicitly uncacheable. A 400 is the one that shows up in a log.
   */
  private suspend fun RoutingContext.rejectMalformedGeneration(): Boolean {
    val raw = call.request.queryParameters[ServeCacheGeneration.PARAM] ?: return false
    if (ServeCacheGeneration.normalize(raw) != null) return false
    call.respondText(
      "'${ServeCacheGeneration.PARAM}' must be a commit sha (7-40 hex), not a branch or tag",
      status = HttpStatusCode.BadRequest,
    )
    return true
  }

  /**
   * The delivery-branch commit this session is serving — the generation every frame URL its pages
   * write is scoped to ([ServeCacheGeneration]), or null when there is nothing to name.
   *
   * Null for the same sessions that get no revision surface: an uploaded bundle, a local project, a
   * daemon-backed module. [ServeBundleHost.supportsPinnedRevisions] is the load-bearing half of the
   * condition rather than a tidy-up — scoping a frame commits the asset lane to answering for an
   * older generation out of the branch, and a host that cannot be pinned cannot do that, so a
   * scoped URL from one would 404 the moment the catalog moved.
   */
  private fun catalogGeneration(renderHost: ServeHost): String? =
    catalogBundleHost(renderHost)
      ?.takeIf { it.supportsPinnedRevisions }
      ?.provenance
      ?.commit
      ?.let(ServeCacheGeneration::normalize)

  /**
   * The generation a request names when it is **not** the one this host is serving, i.e. the page
   * that wrote this URL is a publish behind.
   *
   * Null in every other case, which is the common one: no `gen=` at all (an unscoped link, a
   * hand-typed URL, an unfurler), or a `gen=` naming exactly what is on disk. Both fall through to
   * the ordinary lane; only the third case has to go to the branch.
   *
   * Returning the sha rather than a boolean is what lets the caller feed it straight into the pin
   * path — reconciling a stale generation IS reading a published revision, and doing it through a
   * second mechanism would be a second set of rules about what may be fetched from where.
   */
  /**
   * Whether this request named the generation the host is serving — the case in which the URL is
   * content-addressed and the response may say so.
   *
   * Deliberately not "`gen=` is absent or matches": an unscoped URL is a moving target, and giving
   * it an `immutable` lifetime is precisely the drift this parameter exists to remove.
   */
  private fun RoutingContext.carriesCurrentGeneration(renderHost: ServeHost): Boolean {
    val asked =
      ServeCacheGeneration.normalize(call.request.queryParameters[ServeCacheGeneration.PARAM])
        ?: return false
    return asked == catalogGeneration(renderHost)
  }

  private fun RoutingContext.staleGeneration(renderHost: ServeHost): String? {
    val asked =
      ServeCacheGeneration.normalize(call.request.queryParameters[ServeCacheGeneration.PARAM])
        ?: return null
    return asked.takeIf { it != catalogGeneration(renderHost) }
  }

  /**
   * The revision state for a catalog page: the pin the request carries, and the delivery branch's
   * recent publishes to offer as destinations ([ServeWeb.CatalogRevisions]).
   *
   * A pin is honoured whether or not it appears in that list. The list is the tail of a feed —
   * about the last dozen publishes — while a permalink is meant to outlive them; refusing a sha
   * just because it has scrolled off would make every link expire on a schedule nobody chose. What
   * decides a pin is whether the branch still answers for it, which the asset lanes find out by
   * asking.
   *
   * A session with no delivery branch behind it (an uploaded bundle, a local project) gets no
   * revision surface at all rather than an empty control.
   */
  private fun RoutingContext.catalogRevisions(
    renderHost: ServeHost,
    previewId: String? = null,
  ): ServeWeb.CatalogRevisions {
    val host = catalogBundleHost(renderHost) ?: return ServeWeb.CatalogRevisions.NONE
    if (!host.supportsPinnedRevisions) return ServeWeb.CatalogRevisions.NONE
    return ServeWeb.CatalogRevisions(
      pinned =
        ServeCatalogRevision.normalize(call.request.queryParameters[ServeCatalogRevision.PARAM]),
      revisions = if (previewId == null) host.revisions else availableRevisions(host, previewId),
      repo = host.provenance?.repo,
      // The publish this page is being assembled FROM, which every frame on it is scoped to. It
      // rides with the pin because they answer one question between them: a pinned page's frames
      // take the pin, an unpinned page's take this ([ServeWeb.assetQuery]).
      generation = catalogGeneration(renderHost),
    )
  }

  /**
   * The catalog publishes as they apply to one preview, newest first.
   *
   * A delivery branch's feed is catalog-wide, so it includes commits from before a newly-added
   * preview existed. Offering those as this preview's versions only manufactures links to honest
   * 404s. The publisher rolls a compact preview index forward with every catalog generation, so
   * this is an in-memory lookup rather than one historical `catalog.json` fetch per row. A missing
   * index or entry fails open, keeping older publishers backward-compatible.
   */
  private fun availableRevisions(
    host: ServeBundleHost,
    previewId: String,
  ): List<ServeCatalogRevision.Revision> =
    host.revisions.filterIndexed { index, revision ->
      index == 0 || host.revisionContainsPreview(revision.commit, previewId) != false
    }

  /**
   * Answer one **pinned** image request: the published bytes at a delivery-branch commit, or a 404
   * naming why there are none.
   *
   * `(commit, path)` is immutable, so the response is `immutable` too — a pinned page reloads and a
   * shared link re-opens without touching the branch again — under exactly the same public/private
   * split the other content-addressed lanes use ([prebakedImageCacheControl]): on a token-gated box
   * the URL carries the bearer token, and licensing a shared proxy to keep private catalog imagery
   * for a year is not a trade a permalink is worth.
   */
  private suspend fun RoutingContext.respondPinnedAsset(
    outcome: ServeBundleHost.PinnedOutcome?,
    missing: String,
  ) {
    when (outcome) {
      is ServeBundleHost.PinnedOutcome.Ok -> {
        markGeneration("pinned-asset", prebakedImageCacheControl(isPublic))
        call.respondBytes(outcome.bytes, ContentType.Image.PNG)
      }
      // The lane is admission-bounded, and a shed request is not a dead link. Saying so with a 503
      // + Retry-After keeps a link checker (and a visitor) from concluding that a revision which
      // exists is gone, and tells a well-behaved client when to come back.
      ServeBundleHost.PinnedOutcome.Busy -> {
        call.response.headers.append(HttpHeaders.RetryAfter, "5")
        call.respondText(
          "busy reading that revision from the delivery branch; try again shortly",
          status = HttpStatusCode.ServiceUnavailable,
        )
      }
      else -> call.respondText(missing, status = HttpStatusCode.NotFound)
    }
  }

  /** Canonical, inert PNG for a design reference. Original HTML/Figma sources are never served. */
  private suspend fun RoutingContext.handleDesignReferenceAsset(sessionInPath: Boolean) {
    if (rejectBadToken() || rejectMalformedPin() || rejectMalformedGeneration()) return
    val sessionId = selectedSessionId(sessionInPath)
    val referenceId = call.parameters["name"]?.removeSuffix(".png").orEmpty()
    val requestedPin =
      ServeCatalogRevision.normalize(call.request.queryParameters[ServeCatalogRevision.PARAM])
    withLeasedSession(sessionId, onMissing = { call.respond(HttpStatusCode.NotFound) }) { renderHost
      ->
      // A reference is republished with the catalog, so this lane reads the branch for the same two
      // reasons the render lane does: an explicit `at=` pin, and a `gen=` naming a publish this
      // host is no longer serving ([ServeCacheGeneration]). The second is what keeps a comparison
      // page that a refresh overtook scoring its own generation's mock rather than today's.
      val pinnedCommit = requestedPin ?: staleGeneration(renderHost)
      // A pinned comparison has to pin BOTH panels. A reference is republished with the catalog
      // like everything else, so leaving this lane on the tip would score today's mock against a
      // historical render — a comparison of two moments rather than of two sides.
      if (pinnedCommit != null) {
        respondPinnedAsset(
          outcome =
            catalogBundleHost(renderHost)?.let {
              withContext(Dispatchers.IO) { it.pinnedReference(pinnedCommit, referenceId) }
            },
          missing = "no published design reference at that revision",
        )
        return@withLeasedSession
      }
      val bytes = renderHost.designReferenceRaster(referenceId)
      if (bytes == null) {
        call.respond(HttpStatusCode.NotFound)
      } else {
        // A `gen=` that reached here names the generation on disk, so these bytes are what that URL
        // will always answer with — content-addressed, and cacheable on the terms every other
        // content-addressed lane uses. Without one the URL is a moving target and keeps the short
        // private lifetime it always had.
        markGeneration(
          "design-reference",
          if (carriesCurrentGeneration(renderHost)) prebakedImageCacheControl(isPublic)
          else "private, max-age=300",
        )
        call.respondBytes(bytes, ContentType.Image.PNG)
      }
    }
  }

  /** The catalog's published design pages, or a 404 when it publishes none. */
  /**
   * The catalog-wide motion browser (see [ServeWeb.motionIndexPage]).
   *
   * 404s when the catalog records nothing, exactly as the design-page index does for a catalog that
   * publishes no pages: the landing gates its chip on the same count, so a reader only reaches this
   * by typing the URL, and a page reading "0 recordings" is a worse answer than "there are none".
   */
  private suspend fun RoutingContext.handleMotionIndex(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val sessionId = selectedSessionId(sessionInPath)
    val (webSessionId, basePath) = webSessionAndBase(sessionInPath)
    withLeasedSession(
      sessionId,
      onMissing = { respondNotFoundHtml("That design system was not found on this server.") },
    ) { renderHost ->
      val previews = renderHost.previews
      if (previews.none { it.motion.isNotEmpty() }) {
        respondNotFoundHtml("This design system publishes no motion captures.")
        return@withLeasedSession
      }
      markGeneration("static-page", pageCacheControl())
      call.respondText(
        ServeWeb.motionIndexPage(
          moduleLabel = renderHost.label,
          previews = previews,
          token = linkToken(),
          sessionId = webSessionId,
          basePath = basePath,
          changelogHref = changelogHref(sessionId, basePath, webSessionId),
          isPublic = isPublic,
          trust = catalogBundleHost(renderHost)?.let { BundleVerifier.summary(it.trust) },
          themeCss = catalogBundleHost(renderHost)?.webThemeCss.orEmpty(),
          unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
          version = SERVE_VERSION,
          displayTitle = catalogBundleHost(renderHost)?.title,
          sessionInOrigin = siteSystem() != null,
          reportIssue = pageScopedReportIssue(renderHost, sessionId, "this motion browser"),
        ),
        ContentType.Text.Html,
      )
    }
  }

  private suspend fun RoutingContext.handleDesignPageIndex(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val sessionId = selectedSessionId(sessionInPath)
    val (webSessionId, basePath) = webSessionAndBase(sessionInPath)
    withLeasedSession(
      sessionId,
      onMissing = { respondNotFoundHtml("That design system was not found on this server.") },
    ) { renderHost ->
      val pages = renderHost.designPages().pages
      if (pages.isEmpty()) {
        respondNotFoundHtml("This design system publishes no design pages.")
        return@withLeasedSession
      }
      markGeneration("static-page", pageCacheControl())
      call.respondText(
        ServeWeb.designPagesIndexPage(
          moduleLabel = renderHost.label,
          pages = pages,
          token = linkToken(),
          sessionId = webSessionId,
          basePath = basePath,
          changelogHref = changelogHref(sessionId, basePath, webSessionId),
          isPublic = isPublic,
          trust = catalogBundleHost(renderHost)?.let { BundleVerifier.summary(it.trust) },
          themeCss = catalogBundleHost(renderHost)?.webThemeCss.orEmpty(),
          unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
          version = SERVE_VERSION,
          displayTitle = catalogBundleHost(renderHost)?.title,
          // A top-level site's pages carry their session in the ORIGIN, so same-session links
          // drop the `?session=` the rooted legacy form would add. See [ServeSites].
          sessionInOrigin = siteSystem() != null,
          reportIssue = pageScopedReportIssue(renderHost, sessionId, "these design pages"),
        ),
        ContentType.Text.Html,
      )
    }
  }

  /**
   * One published design page: its view, or — when the name carries a `.svg` suffix — the cached
   * export itself. The export is the design's own, staged at catalog load and sanitized once by
   * [ServeDesignPageStore]; the server holds no Figma credential and never fetches it per request.
   *
   * The asset route answers the *same* sanitized markup the view inlines, deliberately. Serving the
   * branch's raw bytes here would publish markup this server has already judged unsafe to inline,
   * and two different answers for one URL is how a check gets bypassed.
   */
  private suspend fun RoutingContext.handleDesignPage(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val sessionId = selectedSessionId(sessionInPath)
    val (webSessionId, basePath) = webSessionAndBase(sessionInPath)
    val name = call.parameters["name"].orEmpty()
    val isImage = name.endsWith(".svg")
    val pageId = if (isImage) name.removeSuffix(".svg") else name
    withLeasedSession(
      sessionId,
      onMissing = {
        if (isImage) call.respond(HttpStatusCode.NotFound)
        else respondNotFoundHtml("That design system was not found on this server.")
      },
    ) { renderHost ->
      val store = renderHost.designPages()
      val page = store.page(pageId)
      val svg = page?.let { renderHost.designPageSvg(pageId) }
      if (page == null || svg == null) {
        if (isImage) call.respond(HttpStatusCode.NotFound)
        else respondNotFoundHtml("That page was not found in this design system.")
        return@withLeasedSession
      }
      if (isImage) {
        markGeneration("design-page", "private, max-age=300")
        call.respondText(svg, ContentType.Image.SVG)
        return@withLeasedSession
      }
      markGeneration("static-page", pageCacheControl())
      call.respondText(
        ServeWeb.designPage(
          moduleLabel = renderHost.label,
          page = page,
          svg = svg,
          fileKey = store.fileKey,
          // Resolved against what this session actually publishes, so a node mapped to a preview
          // the catalog dropped renders as a plain outline instead of a broken image.
          renderablePreviewIds = renderHost.previews.mapTo(HashSet()) { it.id },
          token = linkToken(),
          sessionId = webSessionId,
          basePath = basePath,
          changelogHref = changelogHref(sessionId, basePath, webSessionId),
          isPublic = isPublic,
          trust = catalogBundleHost(renderHost)?.let { BundleVerifier.summary(it.trust) },
          themeCss = catalogBundleHost(renderHost)?.webThemeCss.orEmpty(),
          unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
          version = SERVE_VERSION,
          displayTitle = catalogBundleHost(renderHost)?.title,
          // A top-level site's pages carry their session in the ORIGIN, so same-session links
          // drop the `?session=` the rooted legacy form would add. See [ServeSites].
          sessionInOrigin = siteSystem() != null,
          reportIssue = pageScopedReportIssue(renderHost, sessionId, "this design page"),
        ),
        ContentType.Text.Html,
      )
    }
  }

  /**
   * One staged rc-compare lane image — a player's published render of an `ir/<id>.rc` document, or
   * the build-time pixel diff of it against the baked PNG. Immutable for the life of a catalog
   * generation (a refresh restages them), so it caches like the baked PNGs do.
   */
  private suspend fun RoutingContext.handleRcCompareAsset(sessionInPath: Boolean) {
    if (rejectBadToken() || rejectMalformedGeneration()) return
    val sessionId = selectedSessionId(sessionInPath)
    val name = "${call.parameters["lane"].orEmpty()}/${call.parameters["name"].orEmpty()}"
    withLeasedSession(sessionId, onMissing = { call.respond(HttpStatusCode.NotFound) }) { renderHost
      ->
      // The catalog restages these on refresh and keeps only the current staging, so a wall from an
      // earlier publish has no answer here — and today's raster under that publish's printed
      // mismatch percentage is a confident number about two pictures that were never compared.
      // Refusing lets the page reload instead ([ServeCacheGeneration]).
      val stale = staleGeneration(renderHost)
      if (stale != null) {
        call.respondText(
          "this catalog has restaged its player comparison since " +
            "'${ServeCacheGeneration.PARAM}=${ServeCacheGeneration.short(stale)}' — reload the page",
          status = HttpStatusCode.Conflict,
        )
        return@withLeasedSession
      }
      val bytes = renderHost.rcCompareImage(name)
      if (bytes == null) {
        call.respond(HttpStatusCode.NotFound)
      } else {
        markGeneration("rc-compare", "private, max-age=300")
        call.respondBytes(bytes, ContentType.Image.PNG)
      }
    }
  }

  /** Focused Reference / Diff / Actual comparison for one exact preview-reference mapping. */
  private suspend fun RoutingContext.handleReferenceComparison(sessionInPath: Boolean) {
    if (rejectBadToken() || rejectMalformedPin()) return
    val sessionId = selectedSessionId(sessionInPath)
    val (webSessionId, basePath) = webSessionAndBase(sessionInPath)
    val previewId = call.parameters["name"].orEmpty()
    val requestedReference = call.request.queryParameters["reference"]
    withLeasedSession(
      sessionId,
      onMissing = { respondNotFoundHtml("That design system was not found on this server.") },
    ) { renderHost ->
      val preview = renderHost.previews.firstOrNull { it.id == previewId }
      val references = renderHost.designReferencesFor(previewId)
      val reference =
        if (requestedReference != null) references.firstOrNull { it.id == requestedReference }
        else references.firstOrNull()
      if (preview == null || reference == null) {
        respondNotFoundHtml("That preview has no matching design reference.")
        return@withLeasedSession
      }
      val overrideParams = requestOverrideParams(sessionId)
      val bundleHost = catalogBundleHost(renderHost)
      val sourceHref =
        bundleHost?.catalogSource?.let { source ->
          ServeUrls.githubBlobUrl(
            source.repo,
            source.ref,
            preview.sourceModule ?: source.module,
            preview.sourceFile,
          )
        }
      // The frame this page drew, named as the page names it: an override-free, unpinned
      // comparison carries the generation it was assembled from, so a report filed from here
      // embeds the pixels the verdict above it was measured on rather than whatever the catalog
      // publishes by the time someone opens the issue ([ServeCacheGeneration]).
      //
      // Both panels take it, exactly as the page's own `assetQuery` gives both of them one suffix:
      // a report that embedded this generation's render beside the reference lane's tip would be a
      // comparison across two publishes, which is the failure the shared scope exists to prevent.
      val assetQuerySuffix =
        if (
          overrideParams.isEmpty() &&
            ServeCatalogRevision.normalize(
              call.request.queryParameters[ServeCatalogRevision.PARAM]
            ) == null
        )
          ServeCacheGeneration.scope(requestQuerySuffix(), catalogGeneration(renderHost))
        else requestQuerySuffix()
      val reportContext =
        ServeIssueReport.Context(
          repo = ServeIssueReport.repoFor(bundleHost?.catalogSource, bundleHost?.provenance),
          previewId = preview.id,
          previewLabel = preview.label,
          system = sessionId,
          componentId = ServeIssueReport.componentIdFor(preview),
          referenceId = reference.id,
          variant = ServeIssueReport.variantFor(preview),
          overrides = overrideParams,
          sourceUrl = sourceHref,
          catalog = bundleHost?.provenance?.let { "${it.repo}@${it.branch}" },
          toolVersion = bundleHost?.provenance?.toolVersion,
          comparisonUrl = ServeIssueReport.withoutToken(externalPageUrl()),
          renderUrl =
            ServeIssueReport.withoutToken(
              "${externalOrigin()}$basePath/render/${WebEscaping.urlEncodeSegment(preview.id)}" +
                ".png$assetQuerySuffix"
            ),
          // …and the panel it is being compared against, so the issue opens showing the
          // disagreement rather than one side of it (#4765). The reference the PAGE resolved, not
          // the query's raw value: `?reference=` may be absent, in which case both this and the
          // panel above it are the preview's first.
          referenceUrl =
            ServeIssueReport.withoutToken(
              "${externalOrigin()}$basePath/reference/" +
                "${WebEscaping.urlEncodeSegment(reference.id)}.png$assetQuerySuffix"
            ),
          publicRender = isPublic,
        )
      val reportIssue =
        ServeWeb.ReportIssue(
          action = ServeIssueReport.action(reportContext.repo),
          body = ServeIssueReport.body(reportContext),
          // The template the page's JS fills. It carries the selection placeholder as well as the
          // render and score ones: what the reporter picked is decided by clicking, after the page
          // was served, and it belongs in the SAME locator block the server already wrote rather
          // than in a second block a producer would have to reconcile.
          bodyTemplate =
            ServeIssueReport.body(
              reportContext,
              renderPlaceholder = true,
              selectionPlaceholder = true,
            ),
          repo = reportContext.repo,
          login = githubAuth?.currentLogin(call),
        )
      val revisions = catalogRevisions(renderHost, preview.id)
      val pinned = revisions.pinned != null
      // Whether the frame on screen is the catalog's BAKED render, replayed rather than produced
      // for this request — the one condition under which the server can promise that a product
      // fetched by a SEPARATE request (`.png` vs `.annotations`) describes the same frame.
      // `canApplyOverrides` is false exactly for the hosts that replay baked pixels for an
      // override-free browse; a daemon-backed host renders per request, so its two products may
      // disagree wherever output varies (animation, conditional composition, live data). A pin or
      // an override re-renders on any host, and `tagIndexForPreview` is the published static index
      // measured in CI over the baked render.
      //
      // This used to be necessary but NOT sufficient, and the gap was caching: an override-free
      // baked `/render/<id>.png` was served on a lifetime of its own while this index was fetched
      // separately, so a client could pair one generation's pixels with another generation's
      // bounds. That matters because a tag selection persists the index's bounds as the acceptance
      // baseline — bounds from another frame surviving into a record that later reports an
      // unchanged element as moved.
      //
      // Closed by [ServeCacheGeneration] (issue #4695), and it took BOTH halves. The frame URL and
      // this page's `/tags/<id>` URL are scoped to the same publish, the frame lane answers for
      // that publish out of the delivery branch, and the tag lane — which can only ever describe
      // today's render — refuses a generation the catalog has moved on from rather than answering
      // with bounds measured on a different frame. So the pair on screen is one publish or the
      // picker fails closed. The condition below is now sufficient as well as necessary.
      val frameIsReplayedBaked =
        !pinned && overrideParams.isEmpty() && !renderHost.canApplyOverrides
      val tagIndex = renderHost.tagIndexForPreview(preview.id)
      val tagsDescribeFrame = frameIsReplayedBaked && tagIndex.isNotEmpty()
      val tagSelectionNote =
        when {
          // A pin or an override means the frame was produced for this request, so NEITHER the
          // published tag index nor the separately-fetched annotation layer describes it. Both
          // selectors are withheld together, and the note says so once.
          pinned ->
            "Element selection is off on a pinned revision: the tag index and the semantics " +
              "layers describe the current render, not this one. Drag a region instead."
          !frameIsReplayedBaked ->
            "Element selection is off while this frame is rendered for you: the tag index and " +
              "the semantics layers are fetched separately and may describe a different render. " +
              "Drag a region instead."
          tagIndex.isEmpty() -> "This catalog publishes no element tag index for this preview."
          else -> null
        }
      val allParityIssues = renderHost.parityIssues()?.issues.orEmpty()
      markGeneration("static-page", pageCacheControl())
      call.respondText(
        ServeWeb.referenceComparisonPage(
          moduleLabel = renderHost.label,
          preview = preview,
          reference = reference,
          references = references,
          token = linkToken(),
          sessionId = webSessionId,
          basePath = basePath,
          changelogHref = changelogHref(sessionId, basePath, webSessionId),
          isPublic = isPublic,
          trust = catalogBundleHost(renderHost)?.let { BundleVerifier.summary(it.trust) },
          // …and it must not drop the catalog's stage either: a dark-first system's sticker is
          // drawn for a dark ground, so comparing it on the default one hid the very pixels the
          // page was opened to inspect (yschimke/wear-m3-catalog#56).
          declaredSurface = catalogBundleHost(renderHost)?.stageSurface,
          // Stepping from the themed comparison table into its focused Reference/Diff/Actual view
          // must not drop back to the built-in chrome mid-journey.
          themeCss = catalogBundleHost(renderHost)?.webThemeCss.orEmpty(),
          unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
          version = SERVE_VERSION,
          displayTitle = catalogBundleHost(renderHost)?.title,
          // Annotation layers describe the CURRENT catalog's layout and typography. Drawing them
          // over a pinned pair would overlay today's bounds on historical pixels and label the
          // result as that revision's spec — the same "current output on a pinned page" the viewer
          // refuses, arriving through a payload rather than a lane. They are published per catalog
          // load, not per revision, so there is nothing historical to draw instead.
          referenceAnnotations =
            if (pinned) emptyList() else renderHost.annotationsForReference(reference.id),
          actualAnnotations =
            if (pinned) emptyList() else renderHost.annotationsForPreview(previewId),
          // Withheld on a pin for the same reason the layers above are, and more sharply: a
          // finding's anchors are bounds in the PUBLISHED render's pixel space, so drawing them
          // over a historical frame would point at whatever happens to sit at those coordinates
          // today and label it with a claim about a different render. The prose would be wrong too
          // — a padding the catalog has since fixed would read as an open defect on the revision
          // that still has it, which is the one page where that reading is least recoverable.
          //
          // …and withheld under an OVERRIDE, which the annotation layers above are not. A knob, a
          // font scale, a locale or a theme re-renders the Actual panel, and this verdict was
          // measured on the frame the catalog published: the boxes would land beside the elements
          // they name, and the sentences would assert a padding nobody is looking at. The redline
          // survives an override because it is a reading aid that degrades to being slightly out
          // of date; a verdict is a CLAIM, and a claim about pixels that are not on screen is
          // simply false.
          //
          // Deliberately NOT `frameIsReplayedBaked`, which also excludes every host that renders
          // per request: an override-free browse of a daemon-backed catalog draws the same
          // component at the same size, so gating on that would take the panel away from every
          // live catalog to buy nothing. The hazard is a frame the VIEWER moved, and these two
          // conditions are exactly that.
          parityFindings =
            if (pinned || overrideParams.isNotEmpty()) emptyList()
            else renderHost.parityFindingsFor(previewId, reference.id),
          // Same rule as the authored layers above, for the same reason: the derived ones are
          // projected from TODAY's render, so drawing them over a pinned frame would label
          // historical pixels with the current semantics tree.
          derivedAnnotations = !pinned && renderHost.hasDesignAnnotationsFor(preview.id),
          // The baked half of the same Typography layer, read here for the same reason the viewer
          // reads it: a published catalog measured typography off the frame it also published, so
          // the layer works on a host with no daemon at all. Without it this page's mount was
          // gated on the semantics lane alone — which no selectable host has — and the annotation
          // pick below could never be offered to anyone.
          publishedTypography = !pinned && renderHost.hasPublishedTypographyFor(preview.id),
          // The layers still DRAW on a re-rendered frame — they are a reading aid and being a
          // render out of date costs nothing there. Clicking one is different: it records a region
          // as an acceptance's authoring-time baseline, and `.annotations` is a separate request
          // from the PNG the client already decoded, so on a host that renders per request the two
          // can describe different frames wherever output varies. The drag is unaffected: it is
          // read off the displayed pixels, so it describes what the reporter saw by construction.
          //
          // `frameIsReplayedBaked` alone is NOT enough here, and the difference is the whole point:
          // it names the PNG lane, while both live catalog wrappers keep the PNG baked for an
          // override-free browse and still ask their daemon for annotations first. A baked frame
          // with live annotations is the same mismatch by another route, so the host states which
          // lane its annotations follow rather than having it inferred from a neighbouring flag.
          annotationsSelectable = frameIsReplayedBaked && renderHost.annotationsFollowBakedFrame,
          tagIndexAvailable = tagsDescribeFrame,
          tagSelectionNote = tagSelectionNote,
          // The acceptance band, and only on a catalog that has published a document. Absent rather
          // than empty: an empty band would appear on every comparison in every catalog, and the
          // page would also carry the engine's bundle — the heaviest asset on it — to evaluate
          // nothing.
          //
          // **And never on a pinned revision.** Both panels take the pin, so the pixels are
          // historical — while the document, and the `referenceSha256` below, come from the catalog
          // as it is *now*. The fingerprint gate would then be comparing today's metadata against
          // yesterday's bytes: it can pass, and an acceptance authored long after that revision
          // would be reported as `valid` and suppress pixels on a comparison it was never published
          // for. A historical revision's acceptances are not published, so there is nothing correct
          // to show here and the honest answer is to show nothing. The other layers on this page
          // are withheld on a pin for the same reason.
          //
          // The scope fields are read from the SAME `reportContext` the locator is written from,
          // not derived a second time. An acceptance matches on every recorded field, `system` and
          // `component` included, so two spellings of one identity would let a record miss the very
          // comparison it was authored on.
          knownDifferences =
            renderHost
              .knownDifferences()
              ?.takeIf { !pinned }
              ?.let {
                val system = reportContext.system
                val component = reportContext.componentId
                // Both are optional on a report — a page-scoped one names neither — and
                // **required**
                // by an acceptance's scope, which matches on every recorded field. A comparison
                // that
                // cannot name its system or its component can therefore never match a record, so
                // the
                // band and its bundle are left off rather than evaluating a document that has
                // nothing
                // to say about this page.
                if (system == null || component == null) return@let null
                KnownDifferenceScope(
                  system = system,
                  component = component,
                  previewId = preview.id,
                  referenceId = reference.id,
                  variant = reportContext.variant,
                  overrides = overrideParams,
                  // Null when the reference publishes no digest, which is `reference-hash-missing`
                  // and a **refusal**: the fingerprint gate has nothing to compare against, and a
                  // gate
                  // that cannot have fired must not be reported as having passed.
                  referenceSha256 = reference.raster.sha256,
                  // Empty unless the published index describes the frame on screen. That is the
                  // same
                  // gate the element *picker* is behind, and for a stronger reason here: an
                  // element-scoped acceptance whose gate cannot run suppresses nothing, so handing
                  // over an index measured on a different render would report an element that never
                  // moved as moved — a false invalidation with a plausible explanation attached.
                  tagIndex =
                    if (!tagsDescribeFrame) emptyMap()
                    else
                      tagIndex.mapValues { (_, entry) ->
                        WireTagEntry(
                          count = entry.count,
                          bounds = entry.bounds,
                          space = entry.space,
                        )
                      },
                )
              },
          parityIssues =
            allParityIssues.filter { issue ->
              preview.id in issue.previewIds ||
                reference.id in issue.referenceIds ||
                (preview.componentId != null && issue.component == preview.componentId)
            },
          acceptanceIssues = allParityIssues,
          revisions = revisions,
          overrides = overrideParams,
          reportIssue = reportIssue,
          // A top-level site's pages carry their session in the ORIGIN, so same-session links
          // drop the `?session=` the rooted legacy form would add. See [ServeSites].
          sessionInOrigin = siteSystem() != null,
        ),
        ContentType.Text.Html,
      )
    }
  }

  /**
   * Admin gate: the `/admin/catalogs` routes require [adminToken] — **never** the browse token, and
   * never open in `--public` mode (a public box publishes its browse URL to the world). Responds
   * 404 like the browse gate so the surface isn't confirmed to a scanner, and compares in constant
   * time.
   */
  private suspend fun RoutingContext.rejectBadAdminToken(): Boolean {
    val provided = call.request.queryParameters["token"] ?: call.request.headers[ADMIN_TOKEN_HEADER]
    // An unconfigured token is not a token everyone matches. `tokensMatch` compares bytes, so a
    // blank expected value is satisfied by `?token=` — which would turn "the operator never set a
    // credential" into "no credential is required", the exact inversion the `*Enabled` flags below
    // exist to prevent. They gate registration; this gates the check, so a route that forgets to
    // pair itself with one still fails closed rather than open.
    if (adminToken.isNullOrBlank()) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return true
    }
    if (ServeUrls.tokensMatch(adminToken, provided)) return false
    call.respondText("not found", status = HttpStatusCode.NotFound)
    return true
  }

  /** `GET /admin/catalogs`: the configured catalog set and each entry's latest load state. */
  private suspend fun RoutingContext.respondAdminCatalogs(admin: ServeCatalogAdmin) {
    val catalogs =
      withContext(Dispatchers.IO) { admin.list() }
        .map { state ->
          AdminCatalogDto(
            system = state.config.system,
            repo = state.config.repo,
            branch = state.config.branch,
            listed = state.config.listed,
            group = state.config.group?.heading,
            loadPriority = state.config.loadPriority,
            state = state.loadState,
            error = state.error,
          )
        }
    call.respondText(
      JSON.encodeToString(
        AdminCatalogsResponse.serializer(),
        AdminCatalogsResponse(catalogs = catalogs),
      ),
      ContentType.Application.Json,
    )
  }

  /** `POST /admin/catalogs`: publish one catalog from a [ServeCatalogsConfig.Entry] JSON body. */
  private suspend fun RoutingContext.handleAdminRegister(admin: ServeCatalogAdmin) {
    val body =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { readCapped(it, MAX_ADMIN_BODY_BYTES) }
      }
    if (body == null) {
      call.respondText("request body too large", status = HttpStatusCode.PayloadTooLarge)
      return
    }
    val entry = runCatching {
      JSON.decodeFromString(ServeCatalogsConfig.Entry.serializer(), body.decodeToString())
    }
      .getOrElse {
        call.respondText(
          "invalid catalog entry: ${it.message}",
          status = HttpStatusCode.BadRequest,
        )
        return
      }
    // The fetch runs off the request dispatcher: publishing a catalog clones a delivery branch.
    respondAdminResult(withContext(Dispatchers.IO) { admin.register(entry) })
  }

  /** Map a [ServeCatalogAdmin.Result] onto its HTTP status + JSON body. */
  private suspend fun RoutingContext.respondAdminResult(result: ServeCatalogAdmin.Result) {
    when (result) {
      is ServeCatalogAdmin.Result.Ok ->
        call.respondText(
          JSON.encodeToString(
            AdminCatalogResult.serializer(),
            AdminCatalogResult(system = result.system, status = "ok", warning = result.warning),
          ),
          ContentType.Application.Json,
        )
      is ServeCatalogAdmin.Result.Invalid ->
        call.respondText(result.reason, status = HttpStatusCode.BadRequest)
      is ServeCatalogAdmin.Result.Conflict ->
        call.respondText(result.reason, status = HttpStatusCode.Conflict)
      // The entry was well-formed but its branch wouldn't fetch — an upstream failure, not the
      // caller's mistake, so it reads as a bad gateway rather than a bad request.
      is ServeCatalogAdmin.Result.Failed ->
        call.respondText(
          "catalog ${result.system} not published: ${result.reason}",
          status = HttpStatusCode.BadGateway,
        )
    }
  }

  /** `GET /admin/groups`: the front-page sections a catalog entry may claim. */
  private suspend fun RoutingContext.respondAdminGroups(admin: ServeCatalogAdmin) {
    val groups = withContext(Dispatchers.IO) { admin.listGroups() }
    call.respondText(
      JSON.encodeToString(
        AdminGroupsResponse.serializer(),
        AdminGroupsResponse(
          groups = groups.map { AdminGroupDto(it.id, it.heading, it.noun, it.priority) }
        ),
      ),
      ContentType.Application.Json,
    )
  }

  /** `POST /admin/groups`: define a section, or restyle one that exists. */
  private suspend fun RoutingContext.handleAdminGroupUpsert(admin: ServeCatalogAdmin) {
    val body =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { readCapped(it, MAX_ADMIN_BODY_BYTES) }
      }
    if (body == null) {
      call.respondText("request body too large", status = HttpStatusCode.PayloadTooLarge)
      return
    }
    val group = runCatching {
      JSON.decodeFromString(ServeCatalogsConfig.Group.serializer(), body.decodeToString())
    }
      .getOrElse {
        call.respondText("invalid group: ${it.message}", status = HttpStatusCode.BadRequest)
        return
      }
    respondAdminResult(withContext(Dispatchers.IO) { admin.upsertGroup(group) })
  }

  /** `GET /admin/sites`: the hostnames this server serves a single catalog on. */
  private suspend fun RoutingContext.respondAdminSites(admin: ServeSiteAdmin) {
    val sites = withContext(Dispatchers.IO) { admin.list() }
    call.respondText(
      JSON.encodeToString(
        AdminSitesResponse.serializer(),
        AdminSitesResponse(sites = sites.map { AdminSiteDto(it.host, it.system) }),
      ),
      ContentType.Application.Json,
    )
  }

  /** `POST /admin/sites`: publish a hostname from a [ServeCatalogsConfig.Site] JSON body. */
  private suspend fun RoutingContext.handleAdminSiteAdd(admin: ServeSiteAdmin) {
    val body =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { readCapped(it, MAX_ADMIN_BODY_BYTES) }
      }
    if (body == null) {
      call.respondText("request body too large", status = HttpStatusCode.PayloadTooLarge)
      return
    }
    val site = runCatching {
      JSON.decodeFromString(ServeCatalogsConfig.Site.serializer(), body.decodeToString())
    }
      .getOrElse {
        call.respondText("invalid site: ${it.message}", status = HttpStatusCode.BadRequest)
        return
      }
    respondAdminSiteResult(withContext(Dispatchers.IO) { admin.add(site) })
  }

  /**
   * Map a [ServeSiteAdmin.Result] onto its HTTP status + JSON body.
   *
   * A 409 for "already exactly this" is what makes the reconcile additive and re-runnable: the
   * publish script treats it as success, so a config pushed twice converges instead of erroring.
   */
  private suspend fun RoutingContext.respondAdminSiteResult(result: ServeSiteAdmin.Result) {
    when (result) {
      is ServeSiteAdmin.Result.Ok ->
        call.respondText(
          JSON.encodeToString(
            AdminSiteResult.serializer(),
            AdminSiteResult(host = result.host, status = "ok", warning = result.warning),
          ),
          ContentType.Application.Json,
        )
      is ServeSiteAdmin.Result.Invalid ->
        call.respondText(result.reason, status = HttpStatusCode.BadRequest)
      is ServeSiteAdmin.Result.Conflict ->
        call.respondText(result.reason, status = HttpStatusCode.Conflict)
    }
  }

  /**
   * `GET /admin/trust`: the producers currently trusted.
   *
   * Pinned public keys are listed by id and name only — the key material itself is in the
   * operator's producers.json and there's no reason to echo it back over the network.
   */
  private suspend fun RoutingContext.respondAdminTrust(admin: ServeTrustAdmin) {
    val store = withContext(Dispatchers.IO) { admin.list() }
    call.respondText(
      JSON.encodeToString(
        AdminTrustResponse.serializer(),
        AdminTrustResponse(
          branches = store.branches.map { AdminTrustBranchDto(it.repo, it.branch) },
          keys = store.keys.map { AdminTrustKeyDto(it.keyId, it.name) },
          oidc = store.oidc.map { it.identity },
        ),
      ),
      ContentType.Application.Json,
    )
  }

  /** `POST /admin/trust`: trust one producer from an [AdminTrustEntry] JSON body. */
  private suspend fun RoutingContext.handleAdminTrustAdd(admin: ServeTrustAdmin) {
    val body =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { readCapped(it, MAX_ADMIN_BODY_BYTES) }
      }
    if (body == null) {
      call.respondText("request body too large", status = HttpStatusCode.PayloadTooLarge)
      return
    }
    val entry = runCatching {
      JSON.decodeFromString(AdminTrustEntry.serializer(), body.decodeToString())
    }
      .getOrElse {
        call.respondText("invalid trust entry: ${it.message}", status = HttpStatusCode.BadRequest)
        return
      }
    respondAdminTrustResult(withContext(Dispatchers.IO) { admin.add(entry) })
  }

  /** Map a [ServeTrustAdmin.Result] onto its HTTP status + JSON body. */
  private suspend fun RoutingContext.respondAdminTrustResult(result: ServeTrustAdmin.Result) {
    when (result) {
      is ServeTrustAdmin.Result.Ok ->
        call.respondText(
          JSON.encodeToString(
            AdminTrustResult.serializer(),
            AdminTrustResult(producer = result.summary, status = "ok", warning = result.warning),
          ),
          ContentType.Application.Json,
        )
      is ServeTrustAdmin.Result.Invalid ->
        call.respondText(result.reason, status = HttpStatusCode.BadRequest)
      is ServeTrustAdmin.Result.Conflict ->
        call.respondText(result.reason, status = HttpStatusCode.Conflict)
    }
  }

  /**
   * The [ServeBundleHost] carrying a catalog's browse metadata (title / subtitle / trust verdict) —
   * the host itself for a static catalog, or the baked host a [ServeCatalogLiveHost] fronts when
   * the catalog is served live. Null for a plain daemon module session (no bundle metadata). Lets
   * the trust badge + card title survive a catalog being fronted by the live composite.
   */
  /**
   * The catalogs on the front-page index **right now**. Read from [catalogLoads] when it's wired,
   * because the configured set is no longer fixed at startup: the admin API publishes and retires
   * catalogs on a running server, and the tracker is what those mutations land in. Falls back to
   * the constructor-supplied [catalogSessions] for a plain/test server with no tracker.
   */
  private fun listedCatalogs(): List<String> =
    catalogLoads?.snapshot()?.filter { it.config.listed }?.map { it.config.system }
      ?: catalogSessions

  /** The served-but-unlisted catalogs right now; the [listedCatalogs] counterpart. */
  private fun unlistedCatalogs(): List<String> =
    catalogLoads?.snapshot()?.filterNot { it.config.listed }?.map { it.config.system }
      ?: appCatalogSessions

  /**
   * Whether [first] — a request's first path segment on a **site host** — names one of the server's
   * own routes, and so is not a session at all. Everything else 404s there.
   *
   * This is an **allowlist**, deliberately. The gate used to enumerate what was *foreign* — catalog
   * ids, then registered sessions, then `<system>@<rev>` — and each review round found another way
   * for a session to exist that the enumeration had not met: an uploaded bundle, a suspended entry
   * that `peekHost` reports as absent, and finally a `--revisions` ref like `main`, which is not
   * registered at all until the generic route leases it and the factory *builds* it. A list of
   * things to refuse can only ever chase that. The set of constant first segments is closed and
   * already written down ([ServeSites.RESERVED_SYSTEMS]), so a site host now serves its own system,
   * serves the routes, and refuses everything else — including whatever the next session kind is.
   *
   * The cost is that a top-level route missing from that list 404s on a site host. That is a
   * visible, tested failure rather than a silent leak, which is the right way round for a feature
   * whose whole promise is that the hostname publishes one catalog.
   *
   * Letting a reserved segment through is only safe while **no session can be named one**, because
   * Ktor matches whole paths and this matches a prefix: a bundle uploaded as `api` would make
   * `/api/` (which no constant route matches) fall to `/{system}/` and serve that bundle. So the
   * invariant is enforced at the two places a session gets its name —
   * [ServeBundleStore.sanitizeName] refuses a reserved upload name, and [ServeSites] refuses a site
   * whose catalog id is one.
   */
  private fun isRootedRoute(first: String): Boolean {
    // The visitor's own redeemed playground session is the one session a site host must let
    // through: this server minted it seconds ago under an unguessable token id and is redirecting
    // them to it, so refusing it would 404 the last step of their own run.
    if (playgroundRedeem?.isRedeemedSession(first) == true) return true
    return first in ServeSites.RESERVED_SYSTEMS
  }

  /** `?a=b` for a non-empty query string, else `""` — for rebuilding a URL we are redirecting. */
  private fun String.prefixedQuery(): String = if (isEmpty()) "" else "?$this"

  /**
   * One percent-decoded path segment, or the segment verbatim when it isn't valid encoding. Used to
   * compare a first path segment against a catalog id; a bad escape simply won't match one.
   */
  private fun decodeSegment(raw: String): String = runCatching {
    java.net.URLDecoder.decode(raw, Charsets.UTF_8)
  }
    .getOrDefault(raw)

  /**
   * `/playground?from=<system>/<previewId>` for a preview, or null when this host would not honour
   * it — no playground lane, no source fetcher, a preview whose catalog never recorded a source
   * path, or a catalog this host cannot compile against. Checked here rather than left to the
   * target page so a dead link is never rendered.
   *
   * Carries the access token like every other link this server builds, so the handoff survives on a
   * token-gated host.
   */
  private fun RoutingContext.playgroundLinkFor(
    host: ServeHost,
    system: String,
    previewId: String,
    sourceFile: String?,
  ): String? {
    if (playgroundService == null || playgroundSeeds == null) return null
    // Same dead end the catalog-level handoff is withheld for: a site host whose OAuth cannot round
    // trip would offer an editor that ends in a 401.
    if (!playgroundReachable()) return null
    if (sourceFile.isNullOrBlank()) return null
    // The handoff is only an offer when THIS catalog is a compile target here. A serve host browses
    // far more catalogs than its playground can compile: a pin-only host compiles exactly its pin,
    // and a host with no Robolectric sidecar compiles no Android catalog at all — which is every
    // Wear and app catalog on the public deployment. Without this check the viewer offered the link
    // anyway, the editor opened that preview's Kotlin, and the compile silently retargeted at
    // whichever catalog happened to be first in the selector, so Run answered with a screen of
    // unresolved references against a design system the visitor never chose.
    if (!playgroundService.compilesCatalog(system)) return null
    // The SAME condition the resolver applies, not a proxy for it. A plain daemon session or an
    // uploaded bundle can carry a `sourceFile` from its own `previews.json` while having no catalog
    // source to resolve it against — the resolver then returns null and the click lands on the
    // generic sample, which is precisely the dead affordance this link is supposed to never be.
    if (catalogBundleHost(host)?.catalogSource == null) return null
    val from = WebEscaping.urlEncodeSegment(system) + "/" + WebEscaping.urlEncodeSegment(previewId)
    val token = if (isPublic) "" else "&token=" + WebEscaping.urlEncodeSegment(linkToken())
    return "/playground?from=$from$token"
  }

  /**
   * `/playground?catalog=<system>` for a catalog landing — the lighter half of the same handoff:
   * preselect this design system, keep the starter snippet. Null when there is no lane to open, or
   * when this host cannot compile against that design system (same reasoning as [playgroundLinkFor]
   * — a landing that offers "try this in the playground" for a catalog the playground can't select
   * is the same dead affordance, one page earlier).
   */
  /**
   * Whether a playground handoff can complete from *this* request's origin at all.
   *
   * The playground is gated on GitHub auth, so on a site host whose box pins an OAuth callback the
   * link leads into the same dance whose state cookie cannot reach the callback — a 401 at the end
   * of a button that promised an editor. The live-preview prompts were already withheld for this;
   * the handoff links are the same dead end and are withheld with them, rather than left as the one
   * affordance that still walks a visitor into it.
   */
  private fun RoutingContext.playgroundReachable(): Boolean =
    githubAuth == null || oauthCanRoundTrip()

  private fun RoutingContext.playgroundLinkForCatalog(system: String): String? {
    if (!playgroundReachable()) return null
    if (playgroundService == null) return null
    if (!playgroundService.compilesCatalog(system)) return null
    val token = if (isPublic) "" else "&token=" + WebEscaping.urlEncodeSegment(linkToken())
    return "/playground?catalog=${WebEscaping.urlEncodeSegment(system)}$token"
  }

  /**
   * The counterpart component's render in the `compareWith` sibling, as a second source for the
   * viewer's spec lane (issue #4621).
   *
   * The pairing is carried in two halves and BOTH have to resolve, because half of it means
   * nothing: the catalog's `compareWith` names the sibling SYSTEM, and the component's `parallel`
   * names the counterpart COMPONENT in it. From there this walks to the sibling's own preview id,
   * which is what a render URL needs.
   *
   * Every step is allowed to fail, and each failure simply means no second source — the lane then
   * offers exactly what it offered before. The chain is long because it spans two catalogs, not
   * because it is doing anything clever:
   *
   * 1. this hostname serves the whole box rather than ONE catalog — a top-level site ([ServeSites])
   *    answers a neighbour's `/{system}/…` with its own 404 on purpose, so a sibling reachable from
   *    `m3.example.test` is precisely what a site exists not to be;
   * 2. this catalog declares a `compareWith` system;
   * 3. this preview belongs to a component that declares a `parallel`;
   * 4. the sibling is served on THIS host (a pairing with a system we do not host is a fact about
   *    the spec, not a link we can offer);
   * 5. the sibling has a preview for that component id.
   *
   * [ServeSessionRegistry.peekHost], never `lease`: this is a metadata read while building a page,
   * and standing a suspended sibling's daemon up to decide whether to draw a button would be a
   * daemon per page view. A suspended sibling therefore offers no lane — fail-soft, like the rest
   * of this surface.
   */
  private fun RoutingContext.parallelSpecSource(
    host: ServeHost,
    preview: ServePreview,
  ): ServeWeb.SpecSource? {
    // One catalog per hostname: on a site host the interceptor above answers `/{system}/…` for any
    // system but this one with the site's own 404, so the sibling's render is unreachable from this
    // page however well the pairing resolves. Offering the source anyway would put a button on the
    // stage whose only possible outcome is "the design spec could not be loaded".
    if (siteSystem() != null) return null
    val bundle = catalogBundleHost(host) ?: return null
    val siblingSystem = bundle.compareWithSystem?.takeIf { it.isNotBlank() } ?: return null
    val componentId = preview.componentId?.takeIf { it.isNotBlank() } ?: return null
    val parallelId = bundle.parallelByComponentId[componentId] ?: return null
    val siblingHost = sessions.peekHost(siblingSystem) ?: return null
    // The sibling's OWN preview for that component. First match, like the design-reference lane
    // above: a component with several previews has one canonical sticker and the manifest's order
    // is the producer's.
    val siblingPreview =
      siblingHost.previews.firstOrNull { it.componentId == parallelId } ?: return null
    val siblingBundle = catalogBundleHost(siblingHost)
    val siblingLabel =
      siblingBundle?.title?.takeIf { it.isNotBlank() }
        ?: siblingHost.label.ifBlank { siblingSystem }
    return ServeWeb.SpecSource(
      id = "parallel",
      label = siblingLabel,
      // Same origin: this is the sibling catalog's ordinary render route on this very server,
      // which is the whole reason the pairing is cheap to offer here and expensive anywhere else
      // (a static compare page can only bake thumbnails at publish time), and is what satisfies
      // the lane's own same-origin guard in `viewer.ts` `specRasterSrc()`.
      rasterUrl =
        "/" +
          WebEscaping.urlEncodeSegment(siblingSystem) +
          "/render/" +
          WebEscaping.urlEncodeSegment(siblingPreview.id) +
          ".png" +
          // …but the same credential every other URL on this page carries. `/render/` is
          // token-gated like the rest of the box, so a bare path meets `rejectBadToken`'s own
          // 404 on every server that is not `--public` — which is every local `serve` and every
          // private deployment. `linkToken`, not the server's own: a caller holding an agent
          // grant gets pages wired with THEIR token, and this raster must not be the one link
          // that leaks the operator's.
          if (isPublic) "" else "?token=" + WebEscaping.urlEncodeSegment(linkToken()),
      // The caveat that keeps the pair honest. Unlike the kit reference, this panel is another
      // catalog's RENDER, produced under its own theme, knobs and overrides rather than the ones
      // that produced the render beside it. Saying so is the difference between a comparison and an
      // implied equivalence.
      provenance =
        "$siblingLabel's own render of ${siblingPreview.componentId ?: parallelId}, " +
          "under that catalog's theme and knobs — not this page's.",
    )
  }

  /**
   * The **page-scoped** "report a catalog issue" for a surface that names no single preview.
   *
   * Every catalog page belongs to a catalog and can therefore be wrong in that catalog's own
   * repository, but only the surfaces that draw one preview could say which one — so the landing
   * grid, the pages index, a design page and the motion browser carried no `#cp-report` at all, the
   * floating launcher's catalog half stayed hidden on them, and the SERVER tracker was the only
   * route out of a page whose whole subject is someone else's design system
   * ([#4704](https://github.com/yschimke/compose-ai-tools/issues/4704)). This is the report the
   * comparison wall introduced for exactly that reason (#4289), lifted out of it: it names the
   * **page** — its URL with the query it was served at, the catalog build and the tool version —
   * and drops every preview-shaped row the way [ServeIssueReport.body] already drops any optional
   * fact it wasn't given. [subject] is what the affordance calls what is wrong, in its own prose.
   *
   * A preview's own defect keeps the better route it already has wherever one exists: the viewer
   * and the focused comparison file against that exact preview.
   */
  /**
   * The page-scoped catalog report for this surface, or null when there is no catalog to file
   * against.
   *
   * Nullable deliberately, and every caller's parameter already says so — *"Null (a plain module,
   * or any caller that has nothing to file against) omits it entirely"*. The handlers passed it
   * unconditionally, so a `compose-preview serve` on a PLAIN MODULE offered to file a bug about
   * "this catalog": there is no catalog, `catalogBundleHost` is null, and
   * [ServeIssueReport.repoFor] fell back to compose-ai-tools — the tool's own tracker named as the
   * repo declaring a catalog the visitor does not have.
   *
   * The gate is whether a catalog EXISTS, not whether it names a repo. A catalog that declares
   * neither source nor provenance still has something to report about, and the fallback is the
   * right last resort for it — "better than nothing, and usually the same project". Gating on the
   * repo instead would silently drop the tracker from every such catalog, which
   * `ServeDesignPageRoutingTest` and `ServeHttpRoutingTest` both assert it must not.
   *
   * [ServeBundleHost.isCatalog] rather than the host type, because `ServeBundleHost` also backs a
   * `--bundles` directory and an uploaded portable bundle. Those are plain bundles with no
   * `catalog.json`, and the type alone read them as catalogs — so an upload was offered a report
   * about "this catalog" against the fallback repo, the same defect as the plain module one door
   * along (#4728 review).
   */
  private fun RoutingContext.pageScopedReportIssue(
    renderHost: ServeHost,
    sessionId: String,
    subject: String,
    /**
     * Whether this page can name comparisons the visitor picks — the comparison wall, and nothing
     * else so far.
     *
     * True adds the [ServeIssueReport.LOCATORS_PLACEHOLDER] line to the template and the two facts
     * a browser-written locator cannot derive from the row it is about (the system, and the
     * delivery revision). Left false everywhere else deliberately: a placeholder on a page with
     * nothing to fill it would be filed verbatim, and a design page or the motion browser has no
     * picker to fill it with.
     */
    pickable: Boolean = false,
  ): ServeWeb.ReportIssue? {
    val bundleHost = catalogBundleHost(renderHost)?.takeIf { it.isCatalog } ?: return null
    val context =
      ServeIssueReport.Context(
        repo = ServeIssueReport.repoFor(bundleHost.catalogSource, bundleHost.provenance),
        system = sessionId,
        catalog = bundleHost.provenance?.let { "${it.repo}@${it.branch}" },
        toolVersion = bundleHost.provenance?.toolVersion,
        pageUrl = ServeIssueReport.withoutToken(pageUrlPinningChrome()),
        publicRender = isPublic,
      )
    return ServeWeb.ReportIssue(
      action = ServeIssueReport.action(context.repo),
      body = ServeIssueReport.body(context),
      bodyTemplate =
        ServeIssueReport.body(
          context,
          renderPlaceholder = true,
          locatorsPlaceholder = pickable,
        ),
      repo = context.repo,
      login = githubAuth?.currentLogin(call),
      subject = subject,
      locatorSystem = if (pickable) context.system else null,
      locatorRevision = if (pickable) context.catalog else null,
    )
  }

  /**
   * This page's URL with the presentation mode the request RESOLVED pinned onto it.
   *
   * A report link is read by someone who does not have the reporter's cookie. The mode is
   * deliberately a property of the visitor rather than of each URL — the previous scheme appended
   * `?chrome=` to every same-origin href and was removed because it "put a parameter nobody chose
   * into every URL a visitor copied" — but `?chrome=` was kept for exactly this: *"a link may pin
   * the presentation it was written for"*. Without it a Catalog-mode report opens the Dev landing
   * for a triager whose own cookie says Dev, which is a different surface from the one reported.
   *
   * Both modes are pinned, not just Catalog: a Dev-mode report read by a Catalog-mode triager is
   * the same failure mirrored. A URL that already carries `?chrome=` is left alone — it pinned
   * itself, and that pin is what the request resolved anyway.
   */
  private fun RoutingContext.pageUrlPinningChrome(): String {
    val url = externalPageUrl()
    // A RECOGNISED pin is left alone: `componentBrowserMode` honours it, so the URL already names
    // the mode the page was served in. An UNRECOGNISED one is not — `interfaceMode` returns null
    // for anything but `catalog`/`dev`, and the request falls back to the cookie or the server
    // default. Carrying that value into the report would pin a mode the page was never in, which
    // is the wrong-surface failure this exists to prevent arriving through the one value nobody
    // validated. So it is REPLACED rather than kept or appended to — a second `chrome=` would
    // leave the reader's own parser to break the tie.
    if (interfaceMode(call.request.queryParameters[CHROME_PARAM]) != null) return url
    val mode = if (componentBrowserMode()) "catalog" else "dev"
    val base = url.substringBefore('?')
    val kept =
      url.substringAfter('?', "").split('&').filter {
        it.isNotEmpty() && queryParamName(it) != CHROME_PARAM
      }
    return "$base?" + (kept + "$CHROME_PARAM=$mode").joinToString("&")
  }

  /**
   * The DECODED name of a raw `k=v` query pair.
   *
   * Ktor decodes a parameter name before it reaches [io.ktor.http.Parameters], so `?%63hrome=x` is
   * `chrome` to every read in this file — including the [interfaceMode] check that decides this URL
   * carries an unrecognised pin. Comparing the raw text instead kept that pair and appended a
   * second `chrome=`, and a reader taking the FIRST value read the invalid one, ignored it and fell
   * back to their own mode: the wrong-surface failure the pin exists to prevent, restored by the
   * replacement meant to close it.
   *
   * `plusIsSpace` matches how Ktor reads a query component. A name that is not valid
   * percent-encoding cannot be what Ktor decoded to `chrome`, so it keeps its raw text and is
   * preserved rather than dropped — this only ever removes a pair the server itself reads as the
   * chrome pin.
   */
  private fun queryParamName(pair: String): String {
    val raw = pair.substringBefore('=')
    return runCatching { raw.decodeURLQueryComponent(plusIsSpace = true) }.getOrDefault(raw)
  }

  private fun catalogBundleHost(host: ServeHost): ServeBundleHost? =
    when (host) {
      is ServeBundleHost -> host
      is ServeCatalogLiveHost -> host.bakedHost as? ServeBundleHost
      is ServePerPreviewLiveHost -> host.bakedHost as? ServeBundleHost
      else -> null
    }

  /**
   * The public server's front-page index: the published design systems ([catalogSessions]) under a
   * "Design systems" section, each a card linking to its `/<system>/` catalog. The unlisted app
   * catalogs ([appCatalogSessions]) are intentionally NOT indexed here — they're served at
   * `/<system>/` but stay off the front door. See [homeSystemsFor].
   */
  private suspend fun RoutingContext.handleHomeIndex() {
    val systems = withContext(Dispatchers.IO) { homeSystemsFor(listedCatalogs()) }
    // Match the first card a visitor actually sees after the homepage's publisher grouping, not
    // merely the operator's input order.
    val featured =
      ServeWeb.homeSections(systems)
        .asSequence()
        .flatMap { it.systems.asSequence() }
        .firstOrNull { it.heroImage != null || it.heroPreviewId != null }
    val featuredPath =
      featured?.heroImage?.path
        ?: featured?.heroPreviewId?.let {
          "/${WebEscaping.urlEncodeSegment(featured.system)}/render/" +
            "${WebEscaping.urlEncodeSegment(it)}.png"
        }
    val featuredUrl = featuredPath?.let { externalOrigin() + it + requestQuerySuffix() }
    // The FULL render behind the featured card, kept only as the fallback unfurl image for when no
    // card can be drawn (see below). The full render rather than the `/hero/` thumbnail the page
    // lays out, because those are downscaled to card size (the front door's is 216×480) — under
    // every unfurler's floor for a large-image card and under Google's 512² guidance for using the
    // image at all. The page wants a small file; a link preview wants a big picture.
    // Read from the remembered metadata, not from a live host: `peekHost` is null for a suspended
    // catalog, and falling back would quietly re-advertise the undersized thumbnail exactly when
    // the
    // featured catalog is idle — the common case, not a rare one.
    val featuredRender =
      featured?.heroPreviewId?.let { id ->
        // Same rule as the viewer and the catalog landing: the URL below inherits the request's
        // query, so a shared `/?widthPx=1200` names a re-render the baked size does not describe.
        if (requestCarriesOverrides()) return@let null
        val size = catalogMetaSeen[featured.system]?.heroRenderSize ?: return@let null
        val path =
          "/${WebEscaping.urlEncodeSegment(featured.system)}/render/" +
            "${WebEscaping.urlEncodeSegment(id)}.png"
        (externalOrigin() + path + requestQuerySuffix()) to size
      }
    // …but what the front door actually advertises is a **drawn** card ([ServeSocialCard]): a
    // 1200×630 picture of the site, at the aspect every unfurler lays a large card out at. It has
    // to be drawn rather than picked, because no catalog render is that shape — the featured hero
    // is a 1078×2399 phone screenshot, so pointing at it meant the card showed a horizontal band
    // through the middle of one app scaffold and nothing that identified this site at all.
    //
    // Composed from the hero thumbnails the front door already baked, in the order a visitor meets
    // them, so it costs no render and no second decode of a full-resolution PNG. On
    // `Dispatchers.IO`
    // beside `homeSystemsFor` for the same reason that call is: the first visit after a catalog
    // changes pays a rasterize, and that is not work for the request thread.
    val card =
      withContext(Dispatchers.IO) {
        socialCards.cardFor(
          ServeSocialCard.Spec(
            title = ServeWeb.HOME_TITLE,
            subtitle = ServeWeb.homeCardSubtitle(systems),
            heroes =
              ServeWeb.homeSections(systems)
                .asSequence()
                .flatMap { it.systems.asSequence() }
                .mapNotNull { catalogMetaSeen[it.system]?.heroImage }
                .take(ServeSocialCard.MAX_HEROES)
                .toList(),
          )
        )
      }
    val unfurl =
      if (card != null)
        ServeWeb.UnfurlMetadata(
          pageUrl = externalPageUrl(),
          imageUrl = externalOrigin() + ServeSocialCard.PATH_PREFIX + "/" + card.fileName,
          imageWidth = card.width,
          imageHeight = card.height,
        )
      else
      // Only when the card couldn't be encoded at all. The featured render is a worse picture but
      // a real one, and `twitterCard` now demotes it to the small card its shape can fill.
      ServeWeb.UnfurlMetadata(
          pageUrl = externalPageUrl(),
          imageUrl = featuredRender?.first ?: featuredUrl,
          imageWidth = featuredRender?.second?.first,
          imageHeight = featuredRender?.second?.second,
        )
    markGeneration("static-page", pageCacheControl())
    call.respondText(
      ServeWeb.homeIndexPage(
        systems,
        linkToken(),
        isPublic = isPublic,
        componentBrowser = componentBrowserMode(),
        version = SERVE_VERSION,
        unfurl = unfurl,
        githubAuth = githubAuthStatus(),
      ),
      ContentType.Text.Html,
    )
  }

  /**
   * The catalogs a crawler may enumerate: the **listed** ones, each with its preview ids and its
   * generation date. Unlisted app catalogs are excluded for exactly the reason they're kept off the
   * front door — they're served, not published — so the sitemap and the home index agree on what
   * this server claims to offer.
   *
   * Reads through [ServeSessionRegistry.peekHost] + [catalogMetaSeen] rather than leasing, which is
   * the same trick `/status` and the home index use: enumerating every catalog's previews by lease
   * would resume every suspended daemon on the box, and a sitemap fetch is the last request that
   * should cost that. A catalog that has never been resident contributes nothing yet and appears
   * once it has been seen.
   *
   * Deliberately does **not** call [rememberCatalogMeta] to freshen a resident host, even though it
   * could: that path bakes the hero thumbnail ([ServeHeroImages.heroFor] renders, decodes and
   * rescales a PNG) and reads render sizes, and doing it here would put that work on the Ktor
   * request coroutine for every listed system — the home index moves the same work to
   * `Dispatchers.IO` precisely because it is not free. A sitemap needs two lightweight fields, so
   * it reads them straight off the resident host and falls back to whatever a previous page view
   * already remembered.
   */
  private fun crawlableCatalogs(onlySystem: String? = null): List<ServeSiteIndex.CatalogEntry> =
    (if (onlySystem != null) listOf(onlySystem) else listedCatalogs()).mapNotNull { system ->
      val host = sessions.peekHost(system)
      val meta = catalogMetaSeen[system]
      val previewIds = host?.previews?.map { it.id } ?: meta?.previewIds ?: return@mapNotNull null
      val generatedAt =
        host?.let { catalogBundleHost(it)?.provenance?.generatedAt }
          ?: meta?.provenance?.generatedAt
      ServeSiteIndex.CatalogEntry(
        system = system,
        previewIds = previewIds,
        lastModified = generatedAt,
      )
    }

  /** `GET /robots.txt`: what a crawler may ask this server for. See [ServeSiteIndex]. */
  private suspend fun RoutingContext.handleRobotsTxt() {
    // Advertised only when there is something in it — an empty sitemap is a broken promise, and a
    // token-gated host has no crawlable pages at all.
    // On a top-level site the sitemap covers that site's own catalog, so the advertisement is
    // gated on the same scoped set the sitemap itself is built from.
    val sitemapUrl =
      if (isPublic && crawlableCatalogs(siteSystem()).isNotEmpty())
        externalOrigin() + "/sitemap.xml"
      else null
    markGeneration("robots", STATIC_RESOURCE_CACHE_CONTROL)
    call.respondText(ServeSiteIndex.robotsTxt(isPublic, sitemapUrl), ContentType.Text.Plain)
  }

  /**
   * `GET /sitemap.xml`: every catalog landing and preview viewer, each stamped with the date its
   * catalog was generated. 404 on a token-gated host — its pages need a token the crawler doesn't
   * have, so publishing their URLs would only mint dead links.
   */
  private suspend fun RoutingContext.handleSitemapXml() {
    if (!isPublic) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    markGeneration("sitemap", STATIC_RESOURCE_CACHE_CONTROL)
    // A site host publishes ONE catalog, rooted: its landing is `/`, its viewers are `/p/<id>`, and
    // its neighbours are none of a crawler's business here.
    val site = siteSystem()
    call.respondText(
      ServeSiteIndex.sitemapXml(externalOrigin(), crawlableCatalogs(site), rootedSystem = site),
      ContentType.Application.Xml,
    )
  }

  /**
   * `GET /hero/{system}/{name}`: a prebaked front-door hero thumbnail ([ServeHeroImages]).
   *
   * The whole point of this lane is that it does none of what `/render` does — no session lease, no
   * render permit, no disk read, no chance of waking a suspended daemon. The bytes were cropped,
   * downscaled and hashed when the catalog was first seen, and live in memory; serving one is a map
   * lookup and a write.
   *
   * `{name}` is the content hash, so the bytes behind a URL can never change: the response is
   * `immutable` with a year-long `max-age`, and a repeat visitor's browser paints the whole index
   * from cache with no request at all. The `{system}` segment is only there to keep the URLs
   * readable — the hash alone identifies the image, which is also why a URL minted before a catalog
   * refresh keeps working. Gated like the rest (open in `--public`, else token-required).
   */
  private suspend fun RoutingContext.handleHeroImage() {
    if (rejectBadToken()) return
    // Scoped like `/wasm/<system>/…`, and for the same reason: the `{system}` segment is not the
    // first one, so the canonical-path interceptor never inspects it. Once a neighbour's hero has
    // been baked — loading the main index is enough — its thumbnail would stay fetchable through a
    // hostname that publishes one catalog.
    val site = call.siteSystem()
    val hero =
      if (site != null && call.parameters["system"] != site) null
      else call.parameters["name"]?.let { heroImages.byFileName(it) }
    if (hero == null) {
      call.respondText("no such hero image", status = HttpStatusCode.NotFound)
      return
    }
    call.response.headers.append(HttpHeaders.CacheControl, prebakedImageCacheControl())
    call.response.headers.append(HttpHeaders.ETag, hero.etag)
    if (call.request.headers[HttpHeaders.IfNoneMatch] == hero.etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    call.respondBytes(hero.bytes, ContentType.Image.PNG)
  }

  /**
   * `GET /social/{name}`: a drawn link-unfurl card ([ServeSocialCard]).
   *
   * Ungated, unlike `/hero/`. The card is only ever *named* by an `og:image` on a page the fetcher
   * has already been given, it is drawn from the site's own chrome plus thumbnails that are public
   * on the front door, and — decisively — a link unfurler does not replay a page's token when it
   * fetches the image it was pointed at. Gating this would leave a token-gated server advertising
   * an image every consumer 403s on, which is the failure this whole lane exists to remove.
   */
  private suspend fun RoutingContext.handleSocialCard() {
    val site = call.siteSystem()
    val card =
      call.parameters["name"]
        ?.let { socialCards.byFileName(it) }
        // A site hostname answers for one catalog, including in an unfurl. The file name is a
        // content hash, so a card baked for a neighbour (and published by its `og:image` on the
        // main host) would otherwise be fetchable back through this domain and hand a chat client
        // that catalog's title and thumbnails under this site's name. The front door's own card
        // (system == null) is nobody's catalog and is refused here for the same reason.
        ?.takeIf { site == null || site in it.systems }
    if (card == null) {
      call.respondText("no such card", status = HttpStatusCode.NotFound)
      return
    }
    call.response.headers.append(HttpHeaders.CacheControl, prebakedImageCacheControl())
    call.response.headers.append(HttpHeaders.ETag, card.etag)
    if (call.request.headers[HttpHeaders.IfNoneMatch] == card.etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    call.respondBytes(card.bytes, ContentType.Image.PNG)
  }

  /**
   * Respond one of the site icons ([ServeSiteIcon]).
   *
   * Not `immutable` like the hashed lanes: these live at well-known paths that an icon fetcher
   * guesses rather than reads, so the bytes behind them *do* change across a deploy. A day of
   * caching plus the ETag is the trade — an icon is a few hundred bytes, and pinning a stale one
   * for a year in every visitor's browser would be the worse mistake.
   */
  private suspend fun RoutingContext.respondSiteIcon(icon: ServeSiteIcon.Icon) {
    if (icon.bytes.isEmpty()) {
      call.respondText("icon unavailable", status = HttpStatusCode.NotFound)
      return
    }
    call.response.headers.append(HttpHeaders.CacheControl, SITE_ICON_CACHE_CONTROL)
    call.response.headers.append(HttpHeaders.ETag, icon.etag)
    if (call.request.headers[HttpHeaders.IfNoneMatch] == icon.etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    call.respondBytes(icon.bytes, ContentType.parse(icon.contentType))
  }

  /**
   * Whether this render request asks for the base preview and nothing else — the only shape a
   * prebaked grid thumbnail can answer. See the `?thumb=` lane in [handleRender] for why.
   */
  private fun RoutingContext.plainThumbRequest(): Boolean =
    call.request.queryParameters.entries().none { (key, _) ->
      ServeOverrides.isOverrideParam(key) ||
        key == "scroll" ||
        key == "rcPlayer" ||
        key == "mode" ||
        // A pin asks for a *different* version of the render, which the in-memory thumbnail is by
        // definition not — it is baked from the catalog on disk. Same rule as the overrides above:
        // anything that changes which pixels are being asked for leaves this lane.
        key == ServeCatalogRevision.PARAM ||
        key in ServeExplodedSvg.PARAMS
    }

  /**
   * Respond a prebaked grid thumbnail ([ServeHeroImages.gridThumbFor]). Cached exactly like the
   * `/hero/` lane and for the same reason: the URL carries the bytes' content hash, so what it
   * names can never change and the browser need not revalidate — a second visit to a catalog paints
   * its grid from cache.
   */
  private suspend fun RoutingContext.respondGridThumb(thumb: ServeHeroImages.Thumb) {
    call.response.headers.append(HttpHeaders.CacheControl, prebakedImageCacheControl())
    call.response.headers.append(HttpHeaders.ETag, thumb.etag)
    if (call.request.headers[HttpHeaders.IfNoneMatch] == thumb.etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    call.respondBytes(thumb.bytes, ContentType.Image.PNG)
  }

  /**
   * `GET /status` (HTML, or JSON with `?format=json`) and `GET /status.json` (JSON): a live
   * snapshot of this host — configured catalogs + their availability/trust/liveness, the render
   * daemons up right now, the effective config, and recent daemon startup failures. Gated like the
   * API routes (open in `--public`, else token-required). The JSON form is the canonical machine
   * surface for a monitor / Home Assistant sensor; the HTML form is its human face.
   */
  private suspend fun RoutingContext.handleStatus(json: Boolean) {
    if (rejectBadToken()) return
    val wantJson = json || call.request.queryParameters["format"].equals("json", ignoreCase = true)
    // Operational state must reach browsers and monitors immediately in both directions: neither
    // a healthy snapshot after failure nor a stale failure after recovery is useful status.
    markGeneration("status", DYNAMIC_RESOURCE_CACHE_CONTROL)
    // On a top-level site, `/status` reports on THAT app only: its catalog row, its daemons, and
    // the startup failures that name it. A visitor to `m3.preview.coo.ee/status` has no business
    // learning what else this box happens to run, and a monitor pointed at the site should alert on
    // the site. The same underlying snapshot is taken either way — the scoping is a filter over it,
    // not a second collection pass.
    val data = withContext(Dispatchers.IO) { buildStatusData(onlySystem = siteSystem()) }
    val skin = siteSkin()
    if (wantJson) {
      call.respondText(
        JSON.encodeToString(StatusResponse.serializer(), data.toResponse()),
        ContentType.Application.Json,
      )
    } else {
      call.respondText(
        ServeWeb.statusPage(
          data.toView(
            agentGrants = agentGrantStatusRows(),
            agentGrantRequests = agentGrantRequestRows(),
          ),
          linkToken(),
          unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
          version = SERVE_VERSION,
          siteName = skin.first,
          themeCss = skin.second,
          themeStorageKey = skin.third,
          componentBrowser = componentBrowserMode(),
          githubAuth = githubAuthStatus(),
        ),
        ContentType.Text.Html,
      )
    }
  }

  /**
   * `GET /report-bug`: the preview server's own bug-report page.
   *
   * Collects what a triager needs about *this deployment* — the running build, its posture and
   * uptime, the JVM and OS the renders happen on, any catalog that is not cleanly loaded, and the
   * most recent daemon-startup / render failures — then adds what the visitor was looking at,
   * resolved from the `from` path their footer form carried. [ServeWeb.bugReportPage] shows all of
   * it before anything is filed; [ServeBugReport] turns it into the issue body.
   *
   * The `from` value is browser-supplied and reaches HTML, a link, and a public issue body, so it
   * is accepted only as a same-origin path with its token stripped ([ServeBugReport.sanitizeFrom]),
   * and the preview it names is resolved by **matching an existing preview id** rather than being
   * trusted as one. A path that resolves to nothing costs the report its page section and nothing
   * else.
   */
  private suspend fun RoutingContext.handleBugReport() {
    if (rejectBadToken()) return
    val from = ServeBugReport.sanitizeFrom(call.request.queryParameters[ServeBugReport.FROM_PARAM])
    val ref = ServeBugReport.parsePath(from)
    val pathSystem = ref.system?.let(::decodeQueryValue)
    // Which session the reported page was showing. Three sources, most specific first:
    //  - a top-level site publishes exactly one system and its pages carry no `/{system}` prefix,
    //    so the host itself is the answer and the path could not have named it;
    //  - a `/{system}/…` path names it directly;
    //  - a ROOT-form viewer (`/p/Red`, the standard single-module shape) names none, and carries
    //    its session in `?session=` — else it is the default session. Without this fallback every
    //    report from a plain `compose-preview serve` lost its preview, catalog, render lane and
    //    screenshot, which is the most common way this affordance is reached.
    val system =
      siteSystem()
        ?: pathSystem?.takeIf { sessions.isKnownSession(it) }
        // An EXPLICIT `?session=` is the visitor's own page saying which catalog it was showing,
        // so it is honoured wherever it appears — the query-mode catalog routes (`/?session=…`,
        // `/pages/foo?session=…`, `/parity?session=…`) carry the footer too and have no system in
        // their path at all.
        ?: explicitSessionId(from)?.takeIf { sessions.isKnownSession(it) }
        // The DEFAULT session is a guess, not a statement, so it stays narrow: root-form viewers
        // only. `ref.system == null` keeps a path that DID name a system — an unknown or
        // misspelled one — from being silently re-attributed to the default catalog, which would
        // attach that catalog's provenance and trust and could even match a same-named preview in
        // it. `previewSegment != null` keeps the server's own pages (`/`, `/status`, `/docs/…`, a
        // 404) from claiming to belong to the default catalog at all: they belong to the box.
        ?: if (ref.system == null && ref.previewSegment != null) {
          defaultSessionId.takeIf { it.isNotBlank() && sessions.isKnownSession(it) }
        } else null
    // Resident host when there is one. `peekHost` deliberately never resumes a suspended catalog,
    // so an idle-timed-out session reads as null here — the last-known snapshot below is what keeps
    // the report from silently losing a still-registered catalog's provenance and trust.
    val host = system?.let { sessions.peekHost(it) }
    val bundle = host?.let { catalogBundleHost(it) }
    val seen = if (host == null) system?.let { catalogMetaSeen[it] } else null
    // Match, don't trust: the segment is browser-supplied, so it names a preview only if this
    // session actually has one that encodes to it. A suspended session answers from the ids its
    // snapshot recorded, which is the same list the resident host would have offered.
    val previewIds = host?.previews?.map { it.id } ?: seen?.previewIds.orEmpty()
    val previewId =
      ref.previewSegment?.let { segment ->
        previewIds.firstOrNull { it == segment || WebEscaping.urlEncodeSegment(it) == segment }
      }
    val preview = previewId?.let { id -> host?.previews?.firstOrNull { it.id == id } }
    val basePath =
      if (system == null || siteSystem() != null) "" else "/${WebEscaping.urlEncodeSegment(system)}"
    // The overrides the reporter actually had on screen. They live in `from`'s query (the viewer
    // rewrites it as the knobs change), and without carrying them the report embeds the DEFAULT
    // render rather than the one that prompted it — which is the whole evidentiary point.
    val overrideSuffix = renderOverrideSuffix(from, system)
    // Which design reference — if any — was on the stage BESIDE that render, and only where the
    // reporter's own path and query settle it rather than the server picking one (#4765).
    //
    // Two pages put a reference there and they are knowable to different depths. The focused
    // comparison names its pair in the URL, so the answer is exact. The viewer's spec lane names
    // its lane (`?mode=spec`) but keeps the SOURCE picker in the DOM, so a catalog that offers a
    // second source could have been showing that instead — there the reference is embedded only
    // when the lane has nothing to switch to. Every other page leaves this null and keeps the base
    // render alone, which is the same restraint `ServeBugReport.Page.view` was added for.
    val stageReference = previewId?.let { id ->
      val references = host?.designReferencesFor(id).orEmpty()
      when {
        ref.previewRoute == ServeBugReport.COMPARE_ROUTE -> {
          val named = ServeBugReport.referenceSegment(from)
          // Match, don't trust — and mirror `handleReferenceComparison` exactly: a `?reference=`
          // naming one this preview does not have is the page's own 404, so falling back to the
          // first here would embed a pair that page never drew.
          if (named == null) references.firstOrNull()
          else
            references.firstOrNull {
              it.id == named || WebEscaping.urlEncodeSegment(it.id) == named
            }
        }
        ref.previewRoute == ServeBugReport.VIEWER_ROUTE && ServeBugReport.onSpecLane(from) ->
          // The lane's own default is the first reference ([ServeWeb.SpecSource]); a second
          // source means the picker had somewhere to go and the URL does not say whether it went
          // there, so the report keeps the render alone rather than guessing which pair it was.
          if (host != null && preview != null && parallelSpecSource(host, preview) != null) null
          else references.firstOrNull()
        else -> null
      }
    }
    val status = withContext(Dispatchers.IO) { buildStatusData(onlySystem = siteSystem()) }
    val server =
      ServeBugReport.Server(
        version = SERVE_VERSION,
        public = isPublic,
        uptimeSeconds = status.uptimeSeconds,
        java = "${System.getProperty("java.version")} (${System.getProperty("java.vendor")})",
        os =
          "${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
            "(${System.getProperty("os.arch")})",
        unhealthyCatalogs =
          status.catalogs
            .filter { it.loadState != "loaded" }
            .map { catalog ->
              val why = catalog.loadError?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
              "`${catalog.id}`: ${catalog.loadState}$why"
            },
        recentFailures = recentFailureLines(status),
      )
    val page =
      ServeBugReport.Page(
        path = from,
        url = from?.let { ServeIssueReport.withoutToken(externalOrigin() + it) },
        system = system,
        previewId = previewId,
        catalog = (bundle?.provenance ?: seen?.provenance)?.let { "${it.repo}@${it.branch}" },
        catalogToolVersion = (bundle?.provenance ?: seen?.provenance)?.toolVersion,
        trust = bundle?.let { BundleVerifier.summary(it.trust) } ?: seen?.trust,
        // A suspended session is not a lane verdict — it is an idle daemon that resumes on the
        // next render — so it reports what it was rather than being called "baked".
        renderLane =
          when {
            host != null -> if (host.hasLiveStream) "live daemon" else "baked snapshots"
            seen != null -> "suspended (idle)"
            else -> null
          },
        // Read from the reporter's own query rather than from anything the server rendered: the
        // viewer rewrites `?mode=`/`?specView=` as the visitor moves between lanes, so the served
        // HTML knows the lane the page OPENED on and only the address bar knows the one they were
        // on when something looked wrong. See issue #4261.
        view = ServeBugReport.viewLabel(from),
        degradations = host?.degradations.orEmpty().map { "${it.code} — ${it.detail}" },
        renderUrl =
          previewId?.let {
            ServeIssueReport.withoutToken(
              "${externalOrigin()}$basePath/render/${WebEscaping.urlEncodeSegment(it)}.png" +
                overrideSuffix
            )
          },
        // The same suffix the render carries. The reference lane reads none of the overrides in it
        // — a knob does not move a design reference — but it does read the `at=` pin, and a pinned
        // comparison whose reference quietly came from the tip would put two moments side by side.
        referenceUrl =
          stageReference?.let {
            ServeIssueReport.withoutToken(
              "${externalOrigin()}$basePath/reference/${WebEscaping.urlEncodeSegment(it.id)}.png" +
                overrideSuffix
            )
          },
        publicRender = isPublic,
      )
    val skin = siteSkin()
    // Which catalog's tracker a *pixel* bug belongs in, so the page can name and link it instead of
    // telling the reporter to go and find the link on a preview. Always answerable on a top-level
    // site — the hostname is the catalog — and answerable for any `/{system}/…` page too. Skipped
    // when the only repo on offer is [ServeIssueReport.FALLBACK_REPO], which is this server's own:
    // a paragraph pointing at the tracker the form already files against says nothing.
    val catalogTarget = system?.let { id ->
      val provenance = bundle?.provenance ?: seen?.provenance
      val repo = ServeIssueReport.repoFor(bundle?.catalogSource, provenance)
      if (repo == ServeBugReport.REPO) null
      else
        ServeWeb.BugReportCatalog(
          system = id,
          title =
            bundle?.title?.takeIf { it.isNotBlank() }
              ?: catalogMetaSeen[id]?.title?.takeIf { it.isNotBlank() }
              ?: host?.label?.takeIf { it.isNotBlank() }
              ?: id,
          repo = repo,
          issuesUrl = ServeIssueReport.action(repo),
          site = siteSystem() != null,
        )
    }
    val report =
      ServeWeb.BugReport(
        action = ServeBugReport.action(),
        body = ServeBugReport.body(server, page),
        bodyTemplate = ServeBugReport.body(server, page, clientPlaceholder = true),
        repo = ServeBugReport.REPO,
        // The thumbnail is fetched by the visitor's own browser against this server, so it keeps
        // the token the report body strips — otherwise a gated box shows a broken image on the
        // page that is meant to prove what the reporter saw.
        renderUrl =
          previewId?.let {
            val gate =
              if (isPublic) ""
              else
                (if (overrideSuffix.isEmpty()) "?" else "&") +
                  "token=${WebEscaping.urlEncodeSegment(linkToken())}"
            "$basePath/render/${WebEscaping.urlEncodeSegment(it)}.png$overrideSuffix$gate"
          },
        // The other panel, on the same terms: the page shows what the body carries, so a
        // comparison-sourced report previews the pair rather than half of it.
        referenceUrl =
          stageReference?.let {
            val gate =
              if (isPublic) ""
              else
                (if (overrideSuffix.isEmpty()) "?" else "&") +
                  "token=${WebEscaping.urlEncodeSegment(linkToken())}"
            "$basePath/reference/${WebEscaping.urlEncodeSegment(it.id)}.png$overrideSuffix$gate"
          },
        login = githubAuth?.currentLogin(call),
        catalog = catalogTarget,
      )
    markGeneration("static-page", "no-store")
    call.respondText(
      ServeWeb.bugReportPage(
        report = report,
        sections = bugReportSections(server, page),
        // The bare route, NOT `externalPageUrl()`. This page's query is browser-supplied `from`,
        // and `og:url` would put it back into the document verbatim — echoing an unvalidated,
        // possibly off-origin URL into the markup, which is exactly what `sanitizeFrom` refuses to
        // do for every other use of the value. There is nothing to unfurl per-report anyway.
        unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalOrigin() + ServeBugReport.PATH),
        version = SERVE_VERSION,
        siteName = skin.first,
        themeCss = skin.second,
        themeStorageKey = skin.third,
        navSuffix = if (isPublic) "" else "?token=${WebEscaping.urlEncodeSegment(linkToken())}",
      ),
      ContentType.Text.Html,
    )
  }

  /**
   * The session a root-form viewer path was showing, from the `?session=` its links carry, else the
   * host's default. Only meaningful for a path with no `/{system}` prefix; the caller checks the
   * result is a session this server actually knows before using it.
   */
  private fun explicitSessionId(from: String?): String? {
    val query = from?.substringAfter('?', missingDelimiterValue = "").orEmpty()
    val raw =
      query
        .split('&')
        .firstOrNull { it.startsWith("session=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotBlank() } ?: return null
    // Decoded, because `ServeWeb.queryString` percent-encodes it on the way out: an on-demand
    // revision session (`feature/foo`) reaches us as `session=feature%2Ffoo` while the registry
    // stores the raw key, so looking up the encoded spelling silently finds nothing and the report
    // loses its catalog, preview, render lane and screenshot.
    return decodeQueryValue(raw)
  }

  /**
   * Percent-decode a query VALUE without the `+`-means-space rule.
   *
   * `URLDecoder` applies `application/x-www-form-urlencoded`, where a literal `+` decodes to a
   * space — but these values are produced by `WebEscaping.urlEncodeSegment`, which leaves `+`
   * alone. Pre-escaping the `+` keeps it literal through the decode, so a session or preview id
   * containing one survives instead of turning into a space that matches nothing.
   */
  private fun decodeQueryValue(value: String): String? = runCatching {
    URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
  }
    .getOrNull()

  /**
   * **This** request's override query, as the map a report or a render URL means by "the overrides
   * in force".
   *
   * Filtered to the params the render lane actually consumes ([ServeOverrides.isOverrideParam]) and
   * normalised exactly the way the page's own links are, so routing-only params (`session`,
   * `reference`) and the token never ride along into a caller that meant the *overrides*.
   *
   * Shared by the viewer and the focused comparison rather than written out at each: the two file
   * reports about the same preview, and an override normalised one way in one report and another
   * way in the other would make the same bug arrive looking like two.
   */
  /**
   * [requestOverrideParams], but empty whenever this page's picture cannot be showing them.
   *
   * Seeding the viewer's controls from the request is what stops a deep link's values from reaching
   * the snapshot and nothing else. It is only honest while the image beside those controls actually
   * carries the override; where it deliberately does not, a seeded control claims a state the
   * pixels never had — the same contradiction, drawn the other way round, and worse for being on a
   * *disabled* control the visitor cannot correct.
   *
   * A **pinned revision** (`?at=<sha>`) drops every seed: its image is the historical baked
   * artifact, and `pinnedRenderQuerySuffix` strips every render override from that URL for exactly
   * this reason.
   *
   * Otherwise only an accepted **baked fallback** (`?fallback=baked`) can answer with pixels that
   * ignored an override — `respondDroppedOverrides` returns them and names what it dropped — and
   * *which* seeds to withhold there is a question about **axes**, not about the host. Two ways an
   * axis fails to reach the pixels, and a page can be in either:
   * - the session has no lane that could apply one at all: neither serve-side render path, and no
   *   in-browser Wasm app ([wasmSrc]) that would mount the component with the override itself;
   * - the preview is **replayed** from a captured Remote Compose document rather than recomposed,
   *   so a `knob.*` (and a string `rc.*`) has no composition to reach, even though the host renders
   *   perfectly well. [CatalogLiveRouting.irReplayDroppedOverrideNames] is the authority on that
   *   set, and asking it — rather than a host-wide capability — is what keeps this guard and the
   *   render lane's own refusal ([droppedOverridesFor]) answering the same question.
   *
   * Everything the render can honour still seeds, including on those pages: withholding an axis the
   * pixels DID apply would recreate the disagreement pointing the other way.
   */
  private fun RoutingContext.seedableOverrideParams(
    renderHost: ServeHost,
    preview: ServePreview,
    sessionId: String,
    pinnedRevision: String?,
    wasmSrc: String?,
  ): OverrideSeeds {
    val params = requestOverrideParams(sessionId)
    fun seeds(seeded: Map<String, String>) = OverrideSeeds.of(params, seeded)
    if (pinnedRevision != null) return seeds(emptyMap())
    if (params.isEmpty() || !acceptsBakedFallback()) return seeds(params)
    val overrideCanReachThePixels =
      renderHost.canApplyOverrides ||
        renderHost.canRenderOverridesFor(preview.id) ||
        wasmSrc != null
    if (!overrideCanReachThePixels) return seeds(emptyMap())
    if (!isReplayedPreview(renderHost, preview.id)) return seeds(params)
    val parsed =
      ServeOverrides.parse(params, ServeOverrides.declaredKnobKinds(preview)) as? OverrideParse.Ok
        ?: return seeds(params)
    val dropped =
      CatalogLiveRouting.irReplayDroppedOverrideNames(preview.id, parsed.overrides).toSet()
    return seeds(if (dropped.isEmpty()) params else params.filterKeys { it !in dropped })
  }

  /**
   * A page's request overrides, split into the ones its controls may open on and the ones they may
   * not.
   *
   * Both halves are needed and neither is derivable from the other downstream: the seeded map
   * paints the markup, and the withheld set is what the page has to TELL the viewer, since
   * `hydrateFromUrl` reads `location.search` itself and would otherwise restore what the server
   * declined.
   */
  internal data class OverrideSeeds(
    val seeded: Map<String, String>,
    val withheld: Set<String>,
  ) {
    companion object {
      /**
       * [seeded] plus everything in [all] it left behind — narrowed to the per-axis prefixes the
       * viewer's controls hold, since the display axes (`fontScale`, `device`, …) are hydrated by
       * their own code paths and named by neither control.
       */
      fun of(all: Map<String, String>, seeded: Map<String, String>): OverrideSeeds =
        OverrideSeeds(
          seeded = seeded,
          withheld =
            all.keys
              .filter { it !in seeded }
              .filter {
                it.startsWith(ServeOverrides.KNOB_PREFIX) ||
                  it.startsWith(ServeOverrides.RC_NAMED_PREFIX)
              }
              .toSet(),
        )
    }
  }

  private fun RoutingContext.requestOverrideParams(sessionId: String): Map<String, String> =
    call.request.queryParameters
      .entries()
      .mapNotNull { (key, values) ->
        val value = values.firstOrNull() ?: return@mapNotNull null
        if (ServeOverrides.isOverrideParam(key)) key to value else null
      }
      .toMap()
      .let { ServeWeb.SystemDisplay.normalizeOverrideParams(sessionId, it) }

  /**
   * The reported page's own override query, re-emitted as a `?…` suffix for a `/render` URL.
   *
   * Taken from `from` rather than from this request, because `from` is the page whose pixels are
   * being reported. Filtered to the params the render lane actually consumes
   * ([ServeOverrides.isOverrideParam]) and normalised the same way the viewer's own links are, so
   * routing-only params (`session`, `reference`) and the token cannot ride along — the token is
   * added separately, and only to the on-page thumbnail.
   *
   * Empty when the page carried no overrides, which keeps the default render URL exactly as it was.
   */
  private fun renderOverrideSuffix(from: String?, system: String?): String {
    val query = from?.substringAfter('?', missingDelimiterValue = "").orEmpty()
    if (query.isEmpty()) return ""
    val pairs = query.split('&').filter { it.isNotEmpty() }
    val revision = pairs.firstNotNullOfOrNull { pair ->
      val key = pair.substringBefore('=')
      if (key != ServeCatalogRevision.PARAM) return@firstNotNullOfOrNull null
      ServeCatalogRevision.normalize(
        decodeQueryValue(pair.substringAfter('=', missingDelimiterValue = ""))
      )
    }
    val params =
      pairs
        .mapNotNull { pair ->
          val key = pair.substringBefore('=')
          val value = pair.substringAfter('=', missingDelimiterValue = "")
          if (ServeOverrides.isOverrideParam(key)) key to value else null
        }
        .toMap()
        .let { ServeWeb.SystemDisplay.normalizeOverrideParams(system ?: defaultSessionId, it) }
        .toMutableMap()
        .apply { revision?.let { put(ServeCatalogRevision.PARAM, it) } }
    if (params.isEmpty()) return ""
    return "?" + params.entries.joinToString("&") { "${it.key}=${it.value}" }
  }

  /**
   * The most recent failures worth carrying in a bug report, newest first: daemon startups that
   * never came up and renders that failed or timed out, merged and capped.
   *
   * Merged **by timestamp before the cap**, not concatenated. Concatenating let a run of old
   * startup failures consume the whole budget and drop a render failure from seconds ago — hiding
   * the very event that prompted the report. Sorting the raw records and cutting afterwards means
   * the cap always keeps the newest, whichever kind they are.
   *
   * Capped because an issue body is read by a human: a server that has been failing for a week has
   * hundreds of these and the tail says nothing the head doesn't. The full history stays on
   * `/status`.
   */
  private fun recentFailureLines(status: StatusData): List<String> {
    val startup =
      status.failures.map { failure ->
        failure.atEpochMillis to
          "${formatInstant(failure.atEpochMillis)}  ${failure.session}: ${failure.reason}"
      }
    val renders =
      status.running.flatMap { daemon ->
        daemon.renderStats?.recentFailures.orEmpty().map { failure ->
          failure.atEpochMillis to
            "${formatInstant(failure.atEpochMillis)}  ${daemon.label}: render failed after " +
              "${failure.durationMs}ms${if (failure.timedOut) " (timeout)" else ""} — " +
              failure.reason
        }
      }
    return (startup + renders)
      .sortedByDescending { it.first }
      .take(BUG_REPORT_FAILURE_LIMIT)
      .map { it.second }
  }

  /** The same facts [ServeBugReport.body] files, grouped for the page that shows them first. */
  private fun bugReportSections(
    server: ServeBugReport.Server,
    page: ServeBugReport.Page,
  ): List<ServeWeb.BugReportSection> = buildList {
    add(
      ServeWeb.BugReportSection(
        "Server",
        buildList {
          server.version?.let { add("compose-preview" to it) }
          add("Mode" to if (server.public) "public (open)" else "token-gated")
          server.uptimeSeconds?.let { add("Uptime" to ServeBugReport.duration(it)) }
          server.java?.let { add("Server JVM" to it) }
          server.os?.let { add("Server OS" to it) }
        },
      )
    )
    add(
      ServeWeb.BugReportSection(
        "Page",
        buildList {
          page.path?.let { add("Page" to it) }
          page.system?.let { add("Design system" to it) }
          page.previewId?.let { add("Preview" to it) }
          page.catalog?.let { add("Catalog" to it) }
          page.catalogToolVersion?.let { add("Catalog rendered by" to "compose-ai-tools $it") }
          page.trust?.let { add("Trust" to it) }
          page.renderLane?.let { add("Render lane" to it) }
          page.view?.let { add("View" to it) }
          page.degradations.forEach { add("Degraded" to it) }
        },
      )
    )
    add(
      ServeWeb.BugReportSection(
        "Catalogs not loaded",
        server.unhealthyCatalogs.map { "" to it },
      )
    )
    add(ServeWeb.BugReportSection("Recent failures", server.recentFailures.map { "" to it }))
    add(
      ServeWeb.BugReportSection(
        "Browser",
        listOf("User agent, viewport, pixel ratio, colour scheme" to "added by your browser"),
      )
    )
  }

  /**
   * `GET /readyz`: the rolling-update readiness gate. Instant and non-blocking — it only reads the
   * [ready] latch, returning `200 "ready"` once it's set and `503 "warming"` before. The render
   * that flips the latch runs on the server-owned [readinessProber], NOT in this request coroutine,
   * so a health checker's short command timeout (the Docker healthcheck allows 5s) can never cancel
   * a slow first render and discard its result — the first poll just kicks the prober off and
   * reports "warming"; a later poll sees the latched value. So the ~10s poll stays cheap even
   * against a daemon-backed module whose cold render runs for much longer than the poll timeout.
   */
  private suspend fun RoutingContext.handleReadyz() {
    if (ready.get()) {
      call.respondText("ready")
      return
    }
    // Upload-only server (`--accept-bundles`, no landing session, no configured catalogs): there's
    // no representative preview to render, so "ready" means the listener is up and waiting for
    // uploads. Catalog-only starts still wait for the first loaded catalog below.
    if (defaultSessionId.isBlank() && catalogLoads?.snapshot().isNullOrEmpty()) {
      ready.set(true)
      call.respondText("ready")
      return
    }
    ensureReadinessProbe()
    call.respondText("warming", status = HttpStatusCode.ServiceUnavailable)
  }

  /**
   * Start the server-owned readiness prober on the first `/readyz` poll (idempotent via
   * [readinessProbeStarted]). It renders the representative preview off the request path, retrying
   * on failure, and latches [ready] on the first success — so a client that times out mid-probe
   * never discards the work. A daemon thread (interrupted on [stop]); it exits as soon as [ready]
   * is set. Gated behind an actual `/readyz` hit so a plain `serve` that's never health-checked
   * pays no eager render.
   */
  private fun ensureReadinessProbe() {
    if (!readinessProbeStarted.compareAndSet(false, true)) return
    val prober =
      Thread(
          {
            while (!ready.get() && !Thread.currentThread().isInterrupted) {
              if (probeReadiness()) {
                ready.set(true)
                return@Thread
              }
              try {
                Thread.sleep(READINESS_PROBE_RETRY_MILLIS)
              } catch (e: InterruptedException) {
                return@Thread
              }
            }
          },
          "serve-readiness-probe",
        )
        .apply { isDaemon = true }
    readinessProber = prober
    prober.start()
  }

  /**
   * One readiness attempt: lease a representative session and render its first preview
   * override-free. A successful [RenderOutcome.Ok] means the render path works end-to-end —
   * catalogs loaded, a preview exists, and the host can produce bytes (baked for a catalog session,
   * a real daemon render for a plain module). Catalog-only starts can bind before their configured
   * default session is loaded, so fall forward to the first usable catalog the tracker sees. Any
   * failure — no session, no previews, a render error, or an exception — returns false so the
   * prober retries. Runs on the [readinessProber] thread (the lease + render are blocking). Never
   * throws.
   */
  private fun probeReadiness(): Boolean {
    val lease =
      readinessSessionIds().asSequence().mapNotNull { sessions.lease(it) }.firstOrNull()
        ?: return false
    return try {
      val preview = lease.host.previews.firstOrNull() ?: return false
      lease.host.render(preview.id, PreviewOverrides()) is RenderOutcome.Ok
    } catch (e: Exception) {
      System.err.println("[serve] readiness probe failed: ${e.message}")
      false
    } finally {
      lease.close()
    }
  }

  private fun readinessSessionIds(): List<String> =
    listOfNotNull(defaultSessionId.takeIf { it.isNotBlank() }, catalogLoads?.firstAvailableSystem())
      .distinct()

  /**
   * Raw catalog metadata for the status snapshot — projected to HTML rows and JSON by [StatusData].
   */
  private data class CatalogStat(
    val id: String,
    val listed: Boolean,
    val title: String?,
    val trust: String?,
    val previews: Int?,
    val failedRenders: Int,
    val deferredPreviews: Int,
    /** Has a live (daemon-backed) render lane — a running daemon, or a suspended live catalog. */
    val live: Boolean,
    /** A live daemon for this catalog is up right now. */
    val running: Boolean,
    val degradation: String?,
    val provenance: ServeWeb.CatalogProvenance?,
    /** A usable catalog session is currently registered (possibly the last good refresh copy). */
    val available: Boolean,
    /**
     * Latest startup/refresh error; may coexist with [available] when the old copy was retained.
     */
    val loadError: String?,
    val lastLoadAttemptEpochMillis: Long?,
    val themeOptimization: ThemeOptimizationSnapshot?,
    val renderCache: CatalogRenderCacheSnapshot?,
    /**
     * The metadata above is a **last-known snapshot** of a now-suspended catalog
     * ([catalogMetaSeen]) rather than a live read, because the session's daemon is idle. Facts a
     * suspension can't change — trust, provenance, preview count — so it's reported, just marked as
     * not-live.
     */
    val stale: Boolean = false,
  ) {
    val loadState: String
      get() =
        when {
          available && loadError == null -> "loaded"
          available -> "stale"
          loadError != null -> "failed"
          else -> "pending"
        }
  }

  /**
   * Last-known catalog metadata per session id, captured while the host was resident. A suspended
   * live catalog can't be read (`peekHost` never resumes, by design — a status poll must not wake
   * every idle daemon), which used to render it as a blank row: no title, no preview count, and an
   * empty trust cell **indistinguishable from untrusted**. These facts come from the signed/fetched
   * delivery branch, not from the daemon, so a suspension doesn't invalidate them — remembering
   * them keeps `/status` honest without costing a resume.
   *
   * Written on suspension (see the [sessions] listener below) and refreshed on every resident read,
   * so it tracks a catalog refresh that re-registers a system with new provenance.
   */
  private val catalogMetaSeen = ConcurrentHashMap<String, CatalogMeta>()

  /**
   * Where each preview's source lives, per system, so `/usage/<id>` can answer for a **suspended**
   * catalog — the snapshot [ServeSessionRegistry.peekHost] tells a caller to keep rather than read
   * absence as a verdict.
   *
   * Written and removed **only** by [ServeSessionRegistry.SessionSnapshots], under the registry's
   * own lock, as part of the detach and retire transitions. That single-writer rule is what makes
   * it correct rather than merely narrow: a reader sees the session resident (and uses the live
   * host) or suspended-with-a-snapshot, never the gap between, and a retirement cannot be overtaken
   * by a slower writer still holding the removed host.
   *
   * Two earlier shapes did not hold, and both failed the same way — the storage was the registry's
   * business but lived outside it. Refreshing this from resident reads gave it a second writer
   * outside the lock, which is how a retired catalog's entry came back. Publishing it from a
   * suspend listener only shortened the window, because listeners run after the lock is released.
   *
   * Nothing is written while a session is resident, and nothing needs to be: the live host answers
   * then, and the snapshot is taken from the host being detached, which is fresher than anything a
   * resident read could have left behind.
   */
  private val catalogSourceLocationsSeen =
    ConcurrentHashMap<String, Map<String, PlaygroundSeedResolver.Location>>()

  /** A resident-time snapshot of one catalog's status facts. See [catalogMetaSeen]. */
  private data class CatalogMeta(
    val title: String?,
    val subtitle: String?,
    val trust: String?,
    val previews: Int?,
    /**
     * The preview ids themselves, not just the [previews] count — the sitemap lists one URL per
     * viewer page and has to name them. Remembered here for the same reason everything else in this
     * snapshot is: `peekHost` never resumes a suspended catalog, so building the sitemap off live
     * hosts alone would publish only whichever catalogs happened to be warm, and the file would
     * change shape between two requests a minute apart.
     */
    val previewIds: List<String>,
    /** Component-card projection used by the home page's cross-catalog command palette. */
    val components: List<ServeWeb.ComponentSearchEntry>,
    val failedRenders: Int,
    val deferredPreviews: Int,
    val heroPreviewId: String?,
    val heroCrop: ContentCrop?,
    /**
     * The prebaked front-door thumbnail for [heroPreviewId] — cropped, downscaled and content
     * hashed once, served off the `/hero/` lane. Captured here (rather than looked up when the home
     * page renders) because this is where the bundle host that owns the pixels is in hand: a
     * suspended catalog then keeps its hero exactly like it keeps its trust badge. Null when the
     * catalog has no hero, or its PNG couldn't be decoded — the card falls back to `/render`.
     */
    val heroImage: ServeHeroImages.Hero?,
    /**
     * The pixel size of the hero preview's **full** render — what the front door advertises to link
     * unfurlers, as opposed to the card-sized [heroImage] thumbnail beside it.
     *
     * Remembered rather than looked up when the home page renders, for the same reason [heroImage]
     * is: `peekHost` returns null for a suspended catalog, so a live lookup would silently fall
     * back to advertising the downscaled thumbnail — the undersized image this stopped advertising
     * — whenever the featured catalog happened to be idle. Which catalog is featured depends only
     * on publisher grouping, so that would have been the *usual* state on a quiet server, not an
     * edge case.
     */
    val heroRenderSize: Pair<Int, Int>?,
    val darkStage: Boolean,
    /**
     * The catalog's own web palette ([ServeThemeCss]). Remembered for the same reason the trust
     * badge and hero are: a **site**'s `/status` and 404 wear this skin, and reading it live meant
     * the whole hostname reverted to the default palette the moment its daemon went idle. It comes
     * from the delivery branch, not the daemon, so a suspension cannot invalidate it.
     */
    val webThemeCss: String,
    val degradation: String?,
    val provenance: ServeWeb.CatalogProvenance?,
    val themeOptimization: ThemeOptimizationSnapshot?,
    val renderCache: CatalogRenderCacheSnapshot?,
    /**
     * This catalog publishes design references, so the front door can offer it a compare action
     * ([ServeWeb.HomeSystem.hasReferenceComparison]).
     *
     * Remembered here, rather than looked up when the home page renders, for the same reason the
     * hero and the trust badge are: `peekHost` never resumes a suspended catalog, so a live lookup
     * would drop the action off every card whose daemon happened to be idle — the usual state on a
     * quiet server, not an edge case. Design references come from the delivery branch, so a
     * suspension cannot invalidate the answer.
     */
    val hasReferenceComparison: Boolean,
    /**
     * The design tool those references name ("Figma", …), or null when they name none — a `png`, an
     * `svg`, an unmapped provider. Only the action's **label**; whether there is an action at all
     * is [hasReferenceComparison] above.
     */
    val designToolLabel: String?,
  )

  init {
    // Snapshot a catalog's facts as its daemon goes idle — the last moment they're readable.
    sessions.addSuspendListener { id, host -> rememberCatalogMeta(id, host) }
    // A retired catalog's status snapshot goes with it. Without this every published-then-retired
    // system on a churning host is retained for the life of the process. This one is a listener
    // rather than a registry transition because [catalogMetaSeen] is also written from resident
    // status reads, so it has other writers by design and cannot claim the guarantee below; the
    // eviction is still strictly better than never evicting.
    sessions.addUnregisterListener { id -> catalogMetaSeen.remove(id) }
    // The source locations ride the registry's own transitions instead — see
    // [catalogSourceLocationsSeen]. Both halves are pure map operations: no I/O, no blocking, no
    // re-entry into the registry, which is what the under-lock contract requires.
    sessions.setSessionSnapshots(
      object : ServeSessionRegistry.SessionSnapshots {
        override fun capture(sessionId: String, host: ServeHost) {
          // Nothing stored for a host with no catalog source — a forked revision session, a plain
          // module — rather than an empty map per session. The GC discards these anyway, but an
          // entry that can never answer is not worth holding in the first place.
          val locations = sourceLocationsOf(host)
          if (locations.isEmpty()) catalogSourceLocationsSeen.remove(sessionId)
          else catalogSourceLocationsSeen[sessionId] = locations
        }

        override fun discard(sessionId: String) {
          catalogSourceLocationsSeen.remove(sessionId)
        }
      }
    )
  }

  /**
   * Every preview's source location on [host], as the suspended-catalog snapshot keeps them.
   *
   * Runs under the registry lock (see [ServeSessionRegistry.SessionSnapshots]), so it walks the
   * preview list, builds a map, and does nothing else — no render, no decode, no I/O.
   */
  private fun sourceLocationsOf(host: ServeHost): Map<String, PlaygroundSeedResolver.Location> {
    val bundle = catalogBundleHost(host) ?: return emptyMap()
    val source = bundle.catalogSource ?: return emptyMap()
    return host.previews
      .mapNotNull { preview ->
        val file = preview.sourceFile?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        preview.id to
          PlaygroundSeedResolver.Location(
            repo = source.repo,
            ref = source.ref,
            module = preview.sourceModule ?: source.module,
            sourceFile = file,
            bodyLine = preview.bodyLine,
          )
      }
      .toMap()
  }

  /**
   * Record [host]'s browse-card facts under [id]. Bundle hosts contribute their richer publishing
   * metadata; local module sessions still contribute a title, preview count and representative
   * render so project-wide component browsing can use the same front door.
   */
  private fun rememberCatalogMeta(id: String, host: ServeHost) {
    val bundle = catalogBundleHost(host)
    val heroId = bundle?.declaredHeroPreviewId ?: ServeWeb.representativePreviewId(host.previews)
    val heroCrop = heroId?.let { bundle?.contentCrop(it) }
    val darkStage = ServeWeb.SystemDisplay.resolveDarkFirst(id, bundle?.stageSurface)
    catalogMetaSeen[id] =
      CatalogMeta(
        title = bundle?.title?.takeIf { it.isNotBlank() } ?: host.label,
        subtitle = bundle?.subtitle,
        trust = bundle?.let { BundleVerifier.summary(it.trust) },
        previews = host.previews.size,
        previewIds = host.previews.map { it.id },
        components = ServeWeb.componentSearchEntries(host.previews, darkStage),
        failedRenders = host.previews.count { it.renderFailure != null },
        deferredPreviews = host.liveOnlyPreviewIds.size,
        heroPreviewId = heroId,
        heroCrop = heroCrop,
        // Memoised per (host instance, preview): the decode + scale runs once per catalog, and a
        // refresh — which installs a fresh host — re-bakes under a new hash.
        heroImage =
          bundle?.let { owner -> heroId?.let { heroImages.heroFor(owner, it, heroCrop) } },
        heroRenderSize = heroId?.let { host.bakedRenderSize(it) },
        darkStage = darkStage,
        webThemeCss = bundle?.webThemeCss.orEmpty(),
        degradation = host.degradations.firstOrNull()?.detail,
        provenance = bundle?.provenance,
        themeOptimization = host.themeOptimizationSnapshot(),
        renderCache = host.catalogRenderCacheSnapshot(),
        // The same two reads the catalog landing gates and names its own compare chip with, so the
        // front door and the landing cannot disagree about whether a catalog compares — or about
        // what it compares against. Kept apart for the reason they are apart there: references
        // whose provider names no design tool (`png`, `svg`, an unmapped token) still have a
        // working `compare?format=reference`, they just get the neutral label.
        //
        // Availability is the same condition `comparisonPage` turns the `reference` format on with,
        // so the action can never deep-link a format that page does not offer. The parity feed's
        // Figma lane is deliberately NOT a fallback for either (it is on the landing, for the
        // "design parity" label): only published references put anything behind the route.
        hasReferenceComparison = host.previews.any { host.designReferencesFor(it.id).isNotEmpty() },
        designToolLabel =
          host.previews.firstNotNullOfOrNull { preview ->
            host.designReferencesFor(preview.id).firstNotNullOfOrNull {
              ServeWeb.designToolLabel(it.source.provider)
            }
          },
      )
  }

  /**
   * Raw status snapshot; the single source both the HTML page and the JSON response project from.
   */
  private inner class StatusData(
    val nowMillis: Long,
    val catalogs: List<CatalogStat>,
    val running: List<ServeSessionRegistry.RunningDaemon>,
    val failures: List<DaemonStartupLog.Failure>,
    /**
     * The one system this snapshot is about ([ServeSites]), or null for the whole box. Carried
     * rather than applied only to [catalogs] / [running] because a status page is more than two
     * lists: the session count and the playground's catalog selector are box-wide reads that would
     * have kept naming neighbours (and reporting another app's daemons) to a per-site monitor.
     */
    val onlySystem: String? = null,
  ) {
    val uptimeSeconds: Long = ((nowMillis - startedAtMillis) / 1000).coerceAtLeast(0)

    /**
     * Sessions known to this status view: every registered one for the box, or just this site's (0
     * or 1 — it is registered, or it has not loaded yet).
     */
    val knownSessions: Int =
      if (onlySystem == null) sessions.activeCount()
      // Registration, not residency: `peekHost` is null for a suspended-but-registered catalog, so
      // reading it here reported `known: 0` beside an available catalog every time the site's
      // daemon went idle — a per-site monitor would see its session vanish on a timer.
      else if (sessions.isKnownSession(onlySystem)) 1 else 0

    /** The playground's offered catalogs, narrowed to what this view is allowed to name. */
    fun offeredCatalogs(offered: List<String>): List<String> =
      if (onlySystem == null) offered else offered.filter { it == onlySystem }

    /** Live daemons (a render daemon is up), excluding pinned static baked hosts. */
    val liveDaemons: List<ServeSessionRegistry.RunningDaemon> = running.filter { it.hasLiveStream }
    val activeStreams: Int = liveDaemons.sumOf { it.activeStreams }

    /**
     * Cross-catalog optimizer admission, taken once and shared by the JSON and HTML projections so
     * the two cannot disagree about the same instant. Whole-box only: the counters are server-wide
     * and a single-system site has no business reading them.
     */
    val optimizerAdmission: ThemeOptimizerAdmissionSnapshot? =
      if (onlySystem == null) themeOptimizerStats?.invoke() else null

    /**
     * Sessions holding an open lease — what keeps a session resident. Scoped like `knownSessions`:
     * a top-level site names only its own.
     */
    val leasedSessions: List<String> =
      sessions.leasedSessions().let { held ->
        if (onlySystem == null) held else held.filter { it == onlySystem }
      }

    /**
     * The subset of [leasedSessions] whose holder has been active recently — the ones actually
     * making the server-wide idle clock read *busy* (#4312). Published beside the full list because
     * the difference is the diagnosis: leases held with none of them busy is an idle tab keeping a
     * daemon warm, which is fine; a busy one is someone genuinely being served.
     */
    val busyLeasedSessions: List<String> =
      sessions.busyLeasedSessions().let { held ->
        if (onlySystem == null) held else held.filter { it == onlySystem }
      }
    val openRenderBreakerCount: Int = running.count { it.renderStats?.breaker?.open == true }
    val currentLiveRenderFailureCount: Int = running.count {
      it.renderStats?.let { stats -> stats.breaker?.open != true && stats.lastRenderFailed } == true
    }
    val catalogLoadFailureCount: Int = catalogs.count { it.loadError != null }
    val overallOk: Boolean =
      failures.isEmpty() &&
        catalogLoadFailureCount == 0 &&
        openRenderBreakerCount == 0 &&
        currentLiveRenderFailureCount == 0

    private fun backendOf(weight: Int): String = if (weight >= 2) "android" else "desktop"

    /**
     * One line of live-lane cadence for the human status page: the fps a viewer actually got, the
     * median gap behind it, and what a frame costs on the wire. Heartbeats are named separately
     * because they are the difference between a quiet lane and a stalled one.
     */
    private fun liveFrameText(stats: LiveFramePerfSnapshot): String = buildString {
      append(stats.achievedFps?.let { "$it fps" } ?: "no frames yet")
      stats.p50IntervalMs?.let { append(" · p50 ${it}ms") }
      append(" · ${stats.frames} painted")
      if (stats.heartbeats > 0) append(" · ${stats.heartbeats} unchanged")
      stats.avgPayloadBytes?.let { append(" · ${humanBytes(it.toInt())}/frame") }
    }

    private fun countLabel(count: Int, singular: String): String =
      "$count $singular${if (count == 1) "" else "s"}"

    /**
     * One line saying whether the theme optimizer's quiet gate is open, and what is holding it shut
     * when it isn't.
     *
     * Worth a row of its own because a shut gate is otherwise indistinguishable from an idle one:
     * every catalog reports `theme optimization paused` either way, and the counters that would
     * separate them are server-wide, not per-catalog. The threshold is printed beside the reading
     * so "closed" always comes with the number it was compared against.
     */
    private fun optimizerGateText(admission: ThemeOptimizerAdmissionSnapshot): String {
      val needs = "needs ${admission.idleThresholdMillis / 1000}s quiet"
      // A host whose steady state sits on a stop threshold runs on the starvation cap's bounded
      // windows rather than on an open gate. Without saying so, `/status` reads as an ordinary
      // healthy gate that just happens to make very slow progress. Appended rather than returned
      // early, because the quiet gate still has its own say: a duty cycle answers host pressure,
      // not "is the server idle".
      val pressure = admission.pressure
      val cycles = pressure?.dutyCycles ?: 0
      val dutyCycles =
        when {
          pressure?.dutyCycleUntilEpochMillis != null ->
            " · duty cycle $cycles" + (pressure.reason?.let { " · $it" } ?: "")
          cycles > 0 -> " · ${countLabel(cycles, "duty cycle")}"
          else -> ""
        } +
          // Residency is the other half of the memory story the gate reads. A box with more
          // unfinished catalogs than lanes and `0 parked` is one whose daemons are still pinned by
          // their own backlog — the state that made the reading the gate trips on.
          if (admission.hostSuspensions > 0 || admission.hostResumes > 0)
            " · ${admission.hostSuspensions} parked / ${admission.hostResumes} resumed"
          else ""
      if (admission.paused) {
        return "paused" + (admission.pauseReason?.let { " · $it" } ?: "") + dutyCycles
      }
      val idle =
        admission.serverIdleMillis
          ?: run {
            val why =
              when (admission.idleBlockedBy) {
                ServeBackgroundWork.IDLE_BLOCKED_BY_SESSION_LEASE ->
                  // The *busy* holders, not every leaseholder: since #4312 an idle tab's lease
                  // keeps its session resident without shutting this gate, so naming it here would
                  // blame the one connection that is not the reason.
                  if (busyLeasedSessions.isEmpty()) "session lease held"
                  else "session lease held by ${busyLeasedSessions.joinToString(", ")}"
                ServeBackgroundWork.IDLE_BLOCKED_BY_CATALOG_LOAD -> "catalogs loading"
                else -> "server busy"
              }
            return "closed · $why · $needs$dutyCycles"
          }
      val open = idle >= admission.idleThresholdMillis
      return "${if (open) "open" else "closed"} · idle ${idle / 1000}s · $needs$dutyCycles"
    }

    fun toResponse(): StatusResponse {
      // One pass over the image store: it sweeps expired entries as it counts, so reading it twice
      // is both wasted work and two answers that can disagree.
      val imageOccupancy = imageStore?.occupancy()
      return StatusResponse(
        version = SERVE_VERSION,
        public = isPublic,
        status = if (overallOk) "ok" else "degraded",
        uptimeSeconds = uptimeSeconds,
        catalogs =
          CatalogSummaryDto(
            total = catalogs.size,
            listed = catalogs.count { it.listed },
            unlisted = catalogs.count { !it.listed },
            trusted = catalogs.count { it.trust != null && it.trust != "unverified" },
            degraded =
              catalogs.count {
                it.degradation != null || it.loadError != null || it.failedRenders > 0
              },
            loaded = catalogs.count { it.available },
            failed = catalogs.count { !it.available && it.loadError != null },
            pending = catalogs.count { !it.available && it.loadError == null },
          ),
        daemons =
          DaemonSummaryDto(
            known = knownSessions,
            running = liveDaemons.size,
            activeStreams = activeStreams,
            liveSeatsTotal = if (liveSeats.unbounded) 0 else liveSeats.totalPermits,
            liveSeatsAvailable = if (liveSeats.unbounded) -1 else liveSeats.availablePermits(),
            liveSeatsUnbounded = liveSeats.unbounded,
            perPreviewSeatsTotal = if (liveSeats.unbounded) 0 else liveSeats.perPreviewPermits,
            perPreviewSeatsAvailable =
              if (liveSeats.unbounded) -1 else liveSeats.perPreviewPermitsAvailable(),
            liveSeatRefusals = liveSeats.refusalCount(),
            liveSeatRefusalsUnverified = liveSeats.unverifiedRefusalCount(),
            leasedSessions = leasedSessions,
            busyLeasedSessions = busyLeasedSessions,
          ),
        config =
          ConfigDto(
            host = host,
            port = port,
            allowRenderTrusted = allowRenderTrusted,
            trustStore = trustStoreConfigured,
            acceptBundles = acceptBundlesEnabled,
            acceptDocs = docStore != null,
            docTtlSeconds = docStore?.ttlSeconds ?: 0,
            acceptImages = imageStore != null,
            imageTtlSeconds = imageStore?.ttlSeconds ?: 0,
            imageUploadRepository = imageUploadAuth?.repository,
            imagesHeld = imageOccupancy?.count ?: 0,
            imageBytesHeld = imageOccupancy?.totalBytes ?: 0,
            catalogRefreshSeconds = catalogRefreshSeconds,
            maxConcurrentRenders = renderSlots,
            liveSeats = liveSeats.totalPermits,
          ),
        catalogList =
          catalogs.map { c ->
            CatalogDto(
              id = c.id,
              listed = c.listed,
              title = c.title,
              trust = c.trust,
              previews = c.previews,
              failedRenders = c.failedRenders,
              deferredPreviews = c.deferredPreviews,
              live = c.live,
              running = c.running,
              degradation = c.degradation,
              repo = c.provenance?.repo,
              branch = c.provenance?.branch,
              generatedAt = c.provenance?.generatedAt,
              composeAiToolsVersion = c.provenance?.toolVersion,
              designParityVersion = c.provenance?.designParityVersion,
              path = "/${c.id}/",
              metaStale = c.stale,
              loadState = c.loadState,
              loadError = c.loadError,
              lastLoadAttemptEpochMillis = c.lastLoadAttemptEpochMillis,
              themeOptimization = c.themeOptimization,
              renderCache = c.renderCache,
            )
          },
        runningServers =
          running.map { d ->
            val static = d.pinned
            RunningServerDto(
              id = d.id,
              label = d.label,
              backend = if (static) "static" else backendOf(d.liveSeatWeight),
              seatWeight = if (static) 0 else d.liveSeatWeight,
              activeStreams = if (static) 0 else d.activeStreams,
              uptimeSeconds = d.startedAt?.let { ((nowMillis - it) / 1000).coerceAtLeast(0) },
              renderStats = d.renderStats,
              liveFrames = liveFrameStats.snapshot(d.id),
              daemonPools = d.daemonPools,
            )
          },
        recentDaemonFailures = failures.map { FailureDto(it.atEpochMillis, it.session, it.reason) },
        // Omitted on a site host. `/status` there reports on ONE app by design — "a monitor
        // pointed at the site alerts on the site, and a visitor learns nothing about what else the
        // box runs" — and these counters are box-wide with no per-system breakdown. Including them
        // would both fire a site's monitor on a neighbour's throttle and disclose that the
        // neighbour exists, which is the one thing a top-level site is for.
        branchFetch = if (onlySystem == null) branchFetchStats?.invoke() else null,
        // Box-wide and unattributed per system, so scoped out on a site host for the same reason
        // the branch counters are.
        themeOptimizer = optimizerAdmission,
        themeCache = if (onlySystem == null) themeCacheStats?.invoke() else null,
        // Whole-box like themeCache, and for the same reason: the pool is shared across every
        // catalog, so a site host scoped to one of them has nothing of its own to report here.
        catalogCache = if (onlySystem == null) catalogCacheStats?.invoke() else null,
        renderStats =
          RenderPerfSnapshot.aggregate(
            // A fresh daemon reports an all-zero snapshot; keep the roll-up null until something
            // has actually rendered so a quiet server doesn't advertise a block of zeros.
            running.mapNotNull { it.renderStats }.filter { it.renders + it.cacheHits + it.busy > 0 }
          ),
        // Scoped like everything else on a site host: a per-site monitor is told about its own
        // live lane, never a neighbour's.
        liveFrames = liveFrameStats.snapshot(onlySystem),
        // Omitted entirely on a site-scoped snapshot, like `branchFetch` above and for the same
        // reason: a site host answers for one app, and grants belong to the box.
        agentAccess =
          agentGrants
            ?.takeIf { onlySystem == null }
            ?.let { store ->
              val now = System.currentTimeMillis()
              val live = store.activeGrants()
              AgentAccessDto(
                activeGrants = live.size,
                pendingRequests = store.pendingRequests().size,
                maxScope = store.maxScope.wire,
                maxTtlSeconds = store.maxGrantTtlSeconds,
                maxCapabilities = AgentGrantCapability.wireNames(store.maxCapabilities),
                grants =
                  live.map { grant ->
                    AgentGrantDto(
                      fingerprint = grant.fingerprint,
                      scopes = grant.scopes.map { it.wire },
                      capabilities = AgentGrantCapability.wireNames(grant.capabilities),
                      approvedBy = grant.approvedBy,
                      expiresInSeconds = grant.secondsUntilExpiry(now),
                      label = grant.label,
                    )
                  },
              )
            },
        playground =
          playgroundHealth?.invoke()?.let { h ->
            PlaygroundDto(
              admittedBy = h.admittedBy,
              sandbox =
                SandboxDto(
                  profile = h.sandboxProfile,
                  active = h.sandboxActive,
                  jailDropped = h.jailDropped,
                  memoryMb = h.sandboxMemoryMb,
                  cpus = h.sandboxCpus,
                  ttlSeconds = h.sandboxTtlSeconds,
                  probe =
                    h.probe?.let { p ->
                      ProbeDto(
                        ran = p.ran,
                        detail = p.detail,
                        failedChecks = p.failedChecks(),
                        egressBlocked = p.egressBlocked,
                        filesystemContained = p.filesystemContained,
                        processIsolated = p.processIsolated,
                        workDirWritable = p.workDirWritable,
                      )
                    },
                ),
              compilerJailed = h.compilerJailed,
              compileSlots = h.compileSlots,
              modes =
                h.modes().map {
                  ModeDto(mode = it.mode, source = it.source, resolved = it.resolved)
                },
              catalogSelector =
                h.catalogSelector?.invoke()?.let {
                  // Scoped like everything else on a site's status: the selector must not name a
                  // neighbouring catalog through a hostname that publishes one app.
                  CatalogSelectorDto(
                    offered = offeredCatalogs(it.offered),
                    // Omitted rather than carried through when scoped: `resolved` counts how many
                    // of the BOX's catalogs hold a compile classpath, and there is no per-catalog
                    // breakdown to narrow it with. Reporting "1 offered, 5 resolved" would be
                    // internally inconsistent and would leak the neighbour count it exists to hide.
                    resolved = if (onlySystem == null) it.resolved else null,
                    limit = it.limit,
                  )
                },
              rateLimit =
                playgroundRateLimiter?.let {
                  RateLimitDto(
                    activeCallers = it.activeCallers(),
                    trackedCallers = it.trackedCallers(),
                  )
                },
              editing =
                h.editing?.invoke()?.let {
                  EditingDto(
                    enabled = it.enabled,
                    active = it.active,
                    expiresAtEpochMs = it.expiresAtEpochMs,
                    lastRevision = it.lastRevision,
                    acquisitions = it.acquisitions,
                    compileAttempts = it.compileAttempts,
                    incrementalCompiles = it.incrementalCompiles,
                    fullFallbacks = it.fullFallbacks,
                    lastCompileMillis = it.lastCompileMillis,
                  )
                },
            )
          },
      )
    }

    /**
     * [agentGrants] / [agentGrantRequests] are passed in rather than collected here: whether a row
     * gets a revoke button depends on who is *reading the page*, which is a routing-layer fact this
     * snapshot has no business knowing.
     */
    fun toView(
      agentGrants: List<ServeWeb.StatusAgentGrant> = emptyList(),
      agentGrantRequests: List<ServeWeb.StatusAgentRequest> = emptyList(),
    ): ServeWeb.StatusView {
      val seatsText =
        if (liveSeats.unbounded) "unbounded"
        else "${liveSeats.availablePermits()} free / ${liveSeats.totalPermits}"
      val renderAgg =
        RenderPerfSnapshot.aggregate(
          running.mapNotNull { it.renderStats }.filter { it.renders + it.cacheHits + it.busy > 0 }
        )
      val summary = buildList {
        val loadedCatalogs = catalogs.count { it.available }
        add(
          ServeWeb.Stat(
            "Catalogs",
            "$loadedCatalogs/${catalogs.size} loaded",
            ServeWeb.Meter(
              catalogs.size.toLong(),
              listOf(
                ServeWeb.MeterSegment("loaded", loadedCatalogs.toLong(), "primary"),
                ServeWeb.MeterSegment(
                  "unavailable",
                  (catalogs.size - loadedCatalogs).toLong(),
                  "warning",
                ),
              ),
            ),
          )
        )
        val published = catalogs.sumOf { it.previews ?: 0 }
        val publishedFailures = catalogs.sumOf { it.failedRenders }
        val publishedDeferred = catalogs.sumOf { it.deferredPreviews }
        val publishedRendered = (published - publishedFailures - publishedDeferred).coerceAtLeast(0)
        add(
          ServeWeb.Stat(
            "Published catalog renders",
            "$publishedRendered rendered · " +
              "$publishedFailures failed · $publishedDeferred deferred",
            ServeWeb.Meter(
              published.toLong(),
              listOf(
                ServeWeb.MeterSegment("rendered", publishedRendered.toLong(), "primary"),
                ServeWeb.MeterSegment("failed", publishedFailures.toLong(), "warning"),
                ServeWeb.MeterSegment("deferred", publishedDeferred.toLong(), "muted"),
              ),
            ),
          )
        )
        add(ServeWeb.Stat("Live daemons running", liveDaemons.size.toString()))
        add(ServeWeb.Stat("Active streams", activeStreams.toString()))
        // What those streams are actually achieving. "Active streams: 3" says three sockets are
        // open and nothing about whether they are painting at 15 fps or 0.5 (#4281).
        liveFrameStats.snapshot(onlySystem)?.let {
          add(ServeWeb.Stat("Live frames", liveFrameText(it)))
        }
        // The optimizer's *input*, next to the counters that describe its output. Every per-catalog
        // row already says "theme optimization paused"; none of them says whether that is the box
        // choosing to be polite or a gate that will never open, and those need different fixes.
        optimizerAdmission?.let {
          add(ServeWeb.Stat("Theme optimiser gate", optimizerGateText(it)))
        }
        add(
          ServeWeb.Stat(
            "Live seats",
            seatsText,
            if (liveSeats.unbounded) null
            else {
              val free = liveSeats.availablePermits().toLong()
              val total = liveSeats.totalPermits.toLong()
              ServeWeb.Meter(
                total,
                listOf(
                  ServeWeb.MeterSegment("in use", (total - free).coerceAtLeast(0), "secondary"),
                  ServeWeb.MeterSegment("free", free, "primary"),
                ),
              )
            },
          )
        )
        add(ServeWeb.Stat("Known sessions", knownSessions.toString()))
        add(ServeWeb.Stat("Uptime", formatDuration(uptimeSeconds)))
        if (renderAgg != null) {
          add(
            ServeWeb.Stat(
              "Live renders",
              "${renderAgg.ok} ok · ${renderAgg.failed} failed · " +
                "${renderAgg.cacheHits} cached",
              ServeWeb.Meter(
                renderAgg.ok + renderAgg.failed + renderAgg.cacheHits,
                listOf(
                  ServeWeb.MeterSegment("ok", renderAgg.ok, "primary"),
                  ServeWeb.MeterSegment("failed", renderAgg.failed, "warning"),
                  ServeWeb.MeterSegment("cached", renderAgg.cacheHits, "secondary"),
                ),
              ),
            )
          )
          renderAgg.avgMs?.let { add(ServeWeb.Stat("Average render latency", "${it}ms")) }
          renderAgg.firstRenderMs?.let { add(ServeWeb.Stat("Worst first render", "${it}ms")) }
        }
      }
      val config =
        listOf(
          ServeWeb.Stat("Access", if (isPublic) "public (open)" else "token-gated"),
          ServeWeb.Stat("Bind", "$host:$port"),
          ServeWeb.Stat("Trusted re-render", if (allowRenderTrusted) "on" else "off"),
          ServeWeb.Stat("Trust store", if (trustStoreConfigured) "configured" else "none"),
          ServeWeb.Stat(
            "Catalog refresh",
            if (catalogRefreshSeconds > 0) "${catalogRefreshSeconds}s" else "disabled",
          ),
          ServeWeb.Stat(
            "Live seats",
            if (liveSeats.unbounded) "unbounded" else liveSeats.totalPermits.toString(),
          ),
          ServeWeb.Stat("Render slots", renderSlots.toString()),
          ServeWeb.Stat("Accept uploads", if (acceptBundlesEnabled) "on" else "off"),
          ServeWeb.Stat(
            "Accept documents",
            if (docStore == null) "off"
            else "on (${ServeWeb.humanDuration(docStore.ttlSeconds)} links)",
          ),
          // Deliberately short: this column is narrow, and a value carrying the gating repository
          // overruns its own label. Who may upload is on `/status.json`, in the startup log, and in
          // the refusal an unauthenticated caller gets back.
          ServeWeb.Stat(
            "Accept images",
            if (imageStore == null || imageUploadAuth == null) "off"
            else "on (${ServeWeb.humanDuration(imageStore.ttlSeconds)} links)",
          ),
        )
      return ServeWeb.StatusView(
        agentGrants = agentGrants,
        agentGrantRequests = agentGrantRequests,
        version = SERVE_VERSION,
        public = isPublic,
        nowMillis = nowMillis,
        overallOk = overallOk,
        healthReason =
          buildList {
              if (catalogLoadFailureCount > 0)
                add(countLabel(catalogLoadFailureCount, "catalog load failure"))
              if (failures.isNotEmpty()) add(countLabel(failures.size, "daemon startup failure"))
              if (openRenderBreakerCount > 0)
                add(countLabel(openRenderBreakerCount, "open live render breaker"))
              if (currentLiveRenderFailureCount > 0)
                add(countLabel(currentLiveRenderFailureCount, "current live render failure"))
            }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" · "),
        healthHref =
          when {
            catalogLoadFailureCount > 0 -> "#catalogs"
            failures.isNotEmpty() -> "#recent-daemon-failures"
            openRenderBreakerCount > 0 -> "#recent-render-failures"
            currentLiveRenderFailureCount > 0 -> "#recent-render-failures"
            else -> null
          },
        summary = summary,
        config = config,
        catalogs =
          catalogs.map { c ->
            ServeWeb.StatusCatalog(
              id = c.id,
              title = c.title ?: c.id,
              listed = c.listed,
              trust = c.trust,
              previews = c.previews ?: 0,
              failedRenders = c.failedRenders,
              deferredPreviews = c.deferredPreviews,
              live = c.live,
              running = c.running,
              degradation = c.degradation,
              stale = c.stale,
              provenance = c.provenance,
              loadState = c.loadState,
              loadError = c.loadError,
              themeOptimization = c.themeOptimization,
              renderCache = c.renderCache,
            )
          },
        servers =
          running.map { d ->
            ServeWeb.StatusServer(
              id = d.id,
              label = d.label,
              backend = backendOf(d.liveSeatWeight),
              activeStreams = d.activeStreams,
              upForText =
                d.startedAt?.let { formatDuration(((nowMillis - it) / 1000).coerceAtLeast(0)) }
                  ?: "—",
            )
          },
        failures =
          failures.map { f ->
            ServeWeb.StatusFailure(
              whenText = formatInstant(f.atEpochMillis),
              session = f.session,
              reason = f.reason,
            )
          },
        renderFailures =
          running
            .flatMap { daemon ->
              daemon.renderStats?.recentFailures.orEmpty().map { failure -> daemon to failure }
            }
            .sortedByDescending { (_, failure) -> failure.atEpochMillis }
            .take(RenderPerfStats.FAILURE_WINDOW_SIZE)
            .map { (daemon, failure) ->
              ServeWeb.StatusRenderFailure(
                whenText = formatInstant(failure.atEpochMillis),
                session = daemon.label,
                durationText =
                  "${failure.durationMs}ms" + if (failure.timedOut) " (timeout)" else "",
                reason = failure.reason,
              )
            },
      )
    }
  }

  /**
   * Assemble the status snapshot. Catalog liveness is read purely from
   * [ServeSessionRegistry.runningDaemons] (a non-resuming snapshot) so a poll never wakes an idle
   * daemon: a pinned static baked host is always resident (present, no live stream); a live catalog
   * is present-with-live-stream when its daemon is up and **absent** when suspended. Catalog
   * metadata (title/trust/provenance) is read via [ServeSessionRegistry.peekHost] — also
   * non-resuming — so a suspended live catalog is reported from its last-known snapshot
   * ([catalogMetaSeen], flagged [CatalogStat.stale]) rather than being force-resumed. It is *not*
   * reported as blank: an empty trust cell reads as "untrusted", which is a different and wrong
   * claim about a catalog that merely has an idle daemon.
   */
  private fun buildStatusData(onlySystem: String? = null): StatusData {
    val allRunning = sessions.runningDaemons()
    val running = if (onlySystem == null) allRunning else allRunning.filter { it.id == onlySystem }
    val byId = allRunning.associateBy { it.id }
    val tracked = catalogLoads?.snapshot()?.associateBy { it.config.system }.orEmpty()
    val allEntries =
      if (tracked.isNotEmpty()) tracked.values.map { it.config.system to it.config.listed }
      else listedCatalogs().map { it to true } + unlistedCatalogs().map { it to false }
    val entries =
      if (onlySystem == null) allEntries else allEntries.filter { it.first == onlySystem }
    val catalogs = entries.map { (id, listed) ->
      val load = tracked[id]
      val daemon = byId[id]
      val host = sessions.peekHost(id)
      val bundle = host?.let { catalogBundleHost(it) }
      // Liveness from the resident snapshot only (never resume): absent ⇒ a suspended live
      // catalog; present-with-live-stream ⇒ its daemon is up; present-without ⇒ static baked host.
      val running = daemon?.hasLiveStream == true
      val available = load?.available ?: true
      val live = available && (daemon == null || daemon.hasLiveStream)
      // Resident: read live and refresh the snapshot (a catalog refresh can change provenance).
      if (host != null) rememberCatalogMeta(id, host)
      val seen = if (host == null) catalogMetaSeen[id] else null
      CatalogStat(
        id = id,
        listed = listed,
        title = bundle?.title?.takeIf { it.isNotBlank() } ?: host?.label ?: seen?.title,
        trust = bundle?.let { BundleVerifier.summary(it.trust) } ?: seen?.trust,
        previews = host?.previews?.size ?: seen?.previews,
        failedRenders =
          host?.previews?.count { it.renderFailure != null }
            ?: seen?.failedRenders
            ?: load?.failedRenders
            ?: 0,
        deferredPreviews = host?.liveOnlyPreviewIds?.size ?: seen?.deferredPreviews ?: 0,
        live = live,
        running = running,
        degradation = host?.degradations?.firstOrNull()?.detail ?: seen?.degradation,
        provenance =
          bundle?.provenance
            ?: seen?.provenance
            ?: load?.config?.let { ServeWeb.CatalogProvenance(it.repo, it.branch) },
        available = available,
        loadError = load?.error,
        lastLoadAttemptEpochMillis = load?.lastAttemptEpochMillis,
        themeOptimization = host?.themeOptimizationSnapshot() ?: seen?.themeOptimization,
        renderCache = host?.catalogRenderCacheSnapshot() ?: seen?.renderCache,
        stale = seen != null,
      )
    }
    return StatusData(
      nowMillis = System.currentTimeMillis(),
      catalogs = catalogs,
      running = running,
      failures =
        daemonLog?.recent().orEmpty().let { failures ->
          if (onlySystem == null) failures else failures.filter { it.session == onlySystem }
        },
      onlySystem = onlySystem,
    )
  }

  /**
   * Resolve a list of catalog [ids] into [ServeWeb.HomeSystem] cards for the front-page index.
   * Reads each resident host without leasing it, falling back to the metadata captured immediately
   * before a live catalog was suspended. In particular, rendering the front page must not resume
   * every idle catalog daemon: that made its latency and memory pressure grow with the catalog
   * count. A catalog with neither a resident host nor a last-known snapshot is skipped rather than
   * sinking the whole page.
   */
  private fun homeSystemsFor(ids: List<String>): List<ServeWeb.HomeSystem> {
    val views = engagementStore.systemViews(ids)
    return ids.mapNotNull { system ->
      sessions.peekHost(system)?.let { rememberCatalogMeta(system, it) }
      val meta = catalogMetaSeen[system] ?: return@mapNotNull null
      ServeWeb.HomeSystem(
        // The front-page section this catalog was published under, straight from the operator's
        // config — the page then checks the claim against the catalog's actual provenance.
        group = catalogLoads?.configFor(system)?.group,
        system = system,
        title = meta.title ?: system,
        subtitle = meta.subtitle,
        previewCount = meta.previews ?: 0,
        views = views.getValue(system),
        trust = meta.trust,
        sourceRepo = meta.provenance?.repo,
        heroPreviewId = meta.heroPreviewId,
        heroCrop = meta.heroCrop,
        // The prebaked thumbnail, when the catalog has one: the card then points at the static
        // `/hero/` lane (crop already in the pixels) instead of the live `/render` endpoint.
        heroImage =
          meta.heroImage?.let {
            ServeWeb.HeroImage(
              path = "${ServeHeroImages.PATH_PREFIX}/$system/${it.fileName}",
              width = it.cssWidth,
              height = it.cssHeight,
            )
          },
        darkStage = meta.darkStage,
        // Whether the card offers the compare action, and — separately — what it calls it.
        hasReferenceComparison = meta.hasReferenceComparison,
        designToolLabel = meta.designToolLabel,
      )
    }
  }

  /**
   * `GET /api/daemons` (query) and `GET /{system}/api/daemons` (path): whether this catalog is
   * currently backed by a live render server, and how many processes that amounts to.
   *
   * Deliberately reads through [ServeSessionRegistry.peekHost], which never resumes a suspended
   * session. A status probe that woke the daemon it is reporting on would defeat the lazy open it
   * exists to make visible — the page would create the very process it is asking about.
   */
  private suspend fun RoutingContext.handleDaemonStatus(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val host = sessions.peekHost(selectedSessionId(sessionInPath))
    val pools =
      host?.let { runCatching { it.daemonPoolStats() }.getOrDefault(emptyList()) }.orEmpty()
    val allRunning = sessions.runningDaemons().filter { it.hasLiveStream }
    val dto =
      DaemonStatusDto(
        // Counted from real subprocesses, not from `daemonStarted`: that is a host-level flag a
        // static baked bundle inherits as true, and it is already true for a catalog whose only
        // process is a pooled child. Either would have the page claim a render server that isn't
        // there.
        running = (host?.daemonProcessCount ?: 0) > 0,
        instances = host?.daemonProcessCount ?: 0,
        pooled = pools.sumOf { it.open },
        poolCapacity = pools.sumOf { it.maxOpen },
        activeStreams = host?.let { runCatching { it.activeStreamCount() }.getOrDefault(0) } ?: 0,
        overallRunning = allRunning.size,
        overallActiveStreams = allRunning.sumOf { it.activeStreams },
        liveSeatsTotal = if (liveSeats.unbounded) 0 else liveSeats.totalPermits,
        liveSeatsAvailable = if (liveSeats.unbounded) -1 else liveSeats.availablePermits(),
      )
    call.response.headers.append(HttpHeaders.CacheControl, DYNAMIC_RESOURCE_CACHE_CONTROL)
    call.respondText(
      JSON.encodeToString(DaemonStatusDto.serializer(), dto),
      ContentType.Application.Json,
    )
  }

  /**
   * `GET /api/render-runs/{name}`: one preview's publishes, collapsed into runs of identical pixels
   * ([ServeCatalogRevision.renderRuns]).
   *
   * Computed over [availableRevisions] — the very list the menu draws — rather than over the
   * catalog's full history, so every `head` names a row the reader can actually see. Reading the
   * two from different lists is how a marker ends up pointing at nothing.
   *
   * A branch that could not be asked is a `404`, not an empty list: no runs and "all twelve of
   * these are identical" are opposite claims, and the viewer must draw nothing rather than the
   * wrong one. Everything else here fails the same way — no delivery branch, an unknown preview, a
   * catalog with no revisions at all — because in each case there is no run structure to state.
   */
  private suspend fun RoutingContext.handleRenderRuns(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val previewId = call.parameters["name"].orEmpty()
    withLeasedSession(selectedSessionId(sessionInPath)) { renderHost ->
      val host = catalogBundleHost(renderHost)
      val revisions = host?.let { availableRevisions(it, previewId) }.orEmpty()
      // Every publish below the tip has to be one the generation-time index CONFIRMS carried this
      // preview. [availableRevisions] fails open for a branch that ships no `preview-index.json`,
      // which is right for the menu — an extra link that 404s beats hiding real history — and wrong
      // here: the window would then reach back past the preview's creation, where the path feed's
      // creation commit reads as a boundary and every row below it becomes a trailing run headed by
      // a publish that has no render at all. That row would be marked, counted as another distinct
      // render, and asked for a thumbnail that cannot exist.
      //
      // Not a real cost for a current publisher: the index is rolled forward to cover exactly the
      // window the menu lists ([ServeCatalogRevision.MAX_REVISIONS] minus the tip, whose inventory
      // is `current` rather than a `revisions` entry). It is the legacy branches — the ones we
      // cannot bound at all — that get no markers, which is the same answer this lane gives
      // everywhere else it does not know.
      val bounded =
        host != null &&
          revisions.drop(1).all { host.revisionContainsPreview(it.commit, previewId) == true }
      // `renderChangeCommits` is the read that can fail; the rest is arithmetic over it.
      //
      // On [Dispatchers.IO] because a cold call goes to the delivery branch, and
      // `withLeasedSession`
      // — unlike its `…OrNull` sibling — runs its block on the request coroutine. That fetch
      // carries
      // the branch client's 10s connect / 30s read timeouts, so leaving it here would let a handful
      // of cold menu opens hold Ktor request threads while GitHub is slow and stall unrelated
      // traffic. Same rule the published-asset lanes already follow.
      val changed = withContext(Dispatchers.IO) { host?.renderChangeCommits(previewId) }
      if (changed == null || revisions.isEmpty() || !bounded) {
        call.respondText("not found", status = HttpStatusCode.NotFound)
        return@withLeasedSession
      }
      val sourceShas = revisions.associate { it.commit to it.sourceSha }
      val dto =
        RenderRunsResponse(
          runs =
            ServeCatalogRevision.renderRuns(revisions, changed).map { run ->
              RenderRunDto(
                head = run.head,
                sourceSha = sourceShas[run.head],
                commits = run.commits,
                open = run.open,
              )
            },
          revisions = revisions.size,
        )
      call.response.headers.append(HttpHeaders.CacheControl, DYNAMIC_RESOURCE_CACHE_CONTROL)
      call.respondText(
        JSON.encodeToString(RenderRunsResponse.serializer(), dto),
        ContentType.Application.Json,
      )
    }
  }

  /**
   * `GET /tags/{name}` (query) and `GET /{system}/tags/{name}` (path): one preview's **published**
   * element tag index — `testTag → {count, bounds, space}` — as
   * [ServeAnnotationsPayload.encodeTags] writes it.
   *
   * `.json` is accepted as an **alias** so the path reads like the machine artifact it mirrors; the
   * bare id answers identically. Resolved by trying the name VERBATIM first and only then the
   * stripped form, because a preview id is unrestricted path-segment data and may itself end in
   * `.json` — unconditional suffix removal would answer such a preview with a 404, or worse, with
   * the index belonging to a different preview whose id is the stripped form. Neither form
   * re-renders: this reads the catalog's `tags/index.json` through [ServeHost.tagIndexForPreview]
   * and nothing else, which is exactly why it needs no live-scope gate and can be served from a
   * static bundle.
   *
   * **An empty index is `{}`, not a 404.** "This preview carries no tags" and "this server cannot
   * tell you" are different answers, and a consumer that cannot distinguish them has no way to
   * choose between offering no tag targets and offering none *yet*. A preview this session does not
   * serve at all is the 404.
   *
   * ## What this route does NOT establish
   *
   * That the index describes the frame the caller is looking at. It is the *published static*
   * index, computed in CI over the baked render, and both live host wrappers delegate
   * [ServeHost.tagIndexForPreview] to their baked host — so an override-bearing or pinned frame is
   * a different render than the one these bounds were measured on. Tag-derived selection therefore
   * has to be gated on the frame being the baked one ([ServeWeb.ReferenceComparison.tagSelection],
   * decided by the page that knows which frame it is showing). Recording bounds from another frame
   * into an acceptance is worse than having no element gate: it reports an element that never moved
   * as moved, with a plausible explanation attached.
   *
   * That gate is now paired with a **generation** one ([ServeCacheGeneration]): a caller may name
   * the publish it is asking about, and this route refuses one the catalog has moved on from rather
   * than answering with today's bounds. It cannot answer for an older publish — the index is read
   * from the catalog on disk and the branch publishes no per-revision copy — so refusing is the
   * whole of what it can honestly do, and it is enough: the pair is one publish or there is no
   * pair.
   */
  private suspend fun RoutingContext.handleTagIndex(sessionInPath: Boolean) {
    if (rejectBadToken() || rejectMalformedGeneration()) return
    val requested = call.parameters["name"].orEmpty()
    withLeasedSession(selectedSessionId(sessionInPath)) { renderHost ->
      // A comparison page scopes this URL to the publish it was assembled from, and this index is
      // measured over that publish's baked render. Once the catalog has moved on there is no
      // answer for the frame the caller is looking at — only today's bounds — and handing those
      // back is worse than handing back nothing: a selection made from them is persisted as an
      // acceptance baseline and later reports an element that never moved as moved. Refusing lets
      // the picker fail closed and the page reload ([ServeCacheGeneration]).
      val stale = staleGeneration(renderHost)
      if (stale != null) {
        call.respondText(
          "this catalog has moved on from " +
            "'${ServeCacheGeneration.PARAM}=${ServeCacheGeneration.short(stale)}'; the published " +
            "tag index describes the current render, not that one — reload the page",
          status = HttpStatusCode.Conflict,
        )
        return@withLeasedSession
      }
      // Verbatim wins over the alias, so a preview whose id really ends in `.json` keeps its own
      // index instead of being answered with another preview's.
      val previewId =
        when {
          renderHost.previews.any { it.id == requested } -> requested
          else ->
            requested.removeSuffix(".json").takeIf { alias ->
              renderHost.previews.any { it.id == alias }
            }
        }
      if (previewId == null) {
        call.respondText("not found", status = HttpStatusCode.NotFound)
        return@withLeasedSession
      }
      call.response.headers.append(HttpHeaders.CacheControl, DYNAMIC_RESOURCE_CACHE_CONTROL)
      call.respondBytes(
        ServeAnnotationsPayload.encodeTags(previewId, renderHost.tagIndexForPreview(previewId)),
        ContentType.Application.Json,
      )
    }
  }

  /**
   * `GET /parity/known-differences.json` (query) and `GET /{system}/…` (path): this catalog's
   * committed known-difference document, **verbatim**.
   *
   * Text, not a parsed and re-serialised object. `compose-preview-known-differences/v1`'s verdicts
   * belong to the engine — `document-unreadable`, `document-too-large`, a duplicated id, a schema
   * token from the future are all answers it must be able to reach — and it can only reach them if
   * the bytes arrive intact. A host that parsed on the way out would be a third implementation of
   * the contract with no conformance suite behind it, disagreeing about exactly the cases the
   * contract spends its length on. See [ServeKnownDifferences].
   *
   * **A catalog that publishes none answers 404**, which is the opposite of what `/tags/{name}`
   * does and deliberately so. There, an empty index is a *legal answer about a preview that
   * exists*, so `{}` is the truth and a 404 would lose it. Here there is no document at all, and
   * the only empty-ish body this route could invent — `{}`, or a document with an empty
   * `acceptances` array — would be a document **this host wrote**, which the engine would then
   * judge. `{}` is `document-unreadable`, an invented empty document is a clean bill of health, and
   * neither is a fact about the catalog. So absence is reported as absence and the consumer skips
   * the evaluation entirely.
   *
   * `no-store`, like the tag route and for the same reason: the element gate and the acceptance
   * gates resolve this against a frame, and a document served from a cache of unknown age is a
   * verdict about a catalog generation nobody can name.
   */
  private suspend fun RoutingContext.handleKnownDifferences(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    withLeasedSession(selectedSessionId(sessionInPath)) { renderHost ->
      call.response.headers.append(HttpHeaders.CacheControl, DYNAMIC_RESOURCE_CACHE_CONTROL)
      when (val document = renderHost.knownDifferences()) {
        null -> call.respondText("not found", status = HttpStatusCode.NotFound)
        is ServeKnownDifferences.Document.Text ->
          call.respondText(document.text, ContentType.Application.Json)
        // Refused from the file's length rather than read, so nothing here has allocated it. 413
        // rather than a body, because the consumer's verdict is `document-too-large` and handing it
        // a truncated document to reach that verdict would defeat the point of the ceiling.
        ServeKnownDifferences.Document.TooLarge ->
          call.respondText(
            "known-differences.json is over the ${ServeKnownDifferences.MAX_DOCUMENT_BYTES}-byte ceiling",
            status = HttpStatusCode.PayloadTooLarge,
          )
      }
    }
  }

  /**
   * `GET /parity/known-differences/{path...}` (query) and `GET /{system}/…` (path): one acceptance
   * artifact, as bytes.
   *
   * **The three failures are distinct statuses, not one 404**, because the engine turns each into a
   * different verdict for the record: `path-not-contained`, `artifact-too-large` and
   * `artifact-unreadable`. Collapsing them here would leave the browser unable to reach two of the
   * three, so a traversal and a typo would report identically — and the traversal is the one worth
   * seeing.
   *
   * Bytes rather than an image response the page could put in an `<img>`: the browser engine
   * decodes this with the same PNG reader the offline run uses, because a canvas decode normalises
   * every colour type to 8-bit RGBA and so cannot see the mask-encoding rules the contract
   * requires.
   */
  private suspend fun RoutingContext.handleKnownDifferenceArtifact(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val requested = call.parameters.getAll("path").orEmpty().joinToString("/")
    withLeasedSession(selectedSessionId(sessionInPath)) { renderHost ->
      call.response.headers.append(HttpHeaders.CacheControl, DYNAMIC_RESOURCE_CACHE_CONTROL)
      when (val artifact = renderHost.knownDifferenceArtifact(requested)) {
        is ServeKnownDifferences.Artifact.Bytes ->
          call.respondBytes(artifact.bytes, ContentType.Image.PNG)
        ServeKnownDifferences.Artifact.NotContained ->
          call.respondText("not contained", status = HttpStatusCode.Forbidden)
        ServeKnownDifferences.Artifact.TooLarge ->
          call.respondText("too large", status = HttpStatusCode.PayloadTooLarge)
        ServeKnownDifferences.Artifact.Unreadable ->
          call.respondText("not found", status = HttpStatusCode.NotFound)
      }
    }
  }

  /**
   * `GET /api/previews` (query) and `GET /{system}/api/previews` (path): the session's preview
   * JSON.
   */
  private suspend fun RoutingContext.handleApiPreviews(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val sessionId = selectedSessionId(sessionInPath)
    withLeasedSession(sessionId) { renderHost ->
      val previewEngagement = previewEngagement(sessionId, renderHost.previews)
      val dto =
        PreviewsResponse(
          module = renderHost.label,
          // Producer-trust verdict for a bundle/catalog session (signature / branch / provenance /
          // unverified); null for a live daemon-backed module session.
          trust = catalogBundleHost(renderHost)?.let { BundleVerifier.summary(it.trust) },
          // Why the session is snapshot-only (no live lane), when it is — read off the host so a
          // programmatic client sees the same reason the viewer banner shows.
          degradations = renderHost.degradations.map { DegradationDto(it.code, it.detail) },
          views = engagementStore.systemViews(sessionId),
          previews =
            renderHost.previews.map { p ->
              PreviewDto(
                id = p.id,
                label = p.label,
                modes = p.modes.map { it.wire },
                overrides = p.overrides,
                remoteComposeKnobs = p.remoteComposeKnobs,
                liveOnly = p.id in renderHost.liveOnlyPreviewIds,
                views = previewEngagement.getValue(p.id).views,
              )
            },
        )
      call.respondText(
        JSON.encodeToString(PreviewsResponse.serializer(), dto),
        ContentType.Application.Json,
      )
    }
  }

  /**
   * `GET /api/uses?q=<token>` (query) and `GET /{system}/api/uses?q=<token>` (path): the previews
   * whose declaration **calls** something matching the token — what the landing filter box answers
   * `uses:` with.
   *
   * ### Dev mode only, and 404 rather than empty
   *
   * Catalog mode is the streamlined component browser: a reader there is looking at a published
   * design system, and "which previews call `ButtonGroup`" is a question about this repository's
   * source, not about the system. So the route is withheld in that mode — the same presentation
   * gate the header switch selects, read through [componentBrowserMode]. It answers 404 rather than
   * an empty list because those mean different things to the caller, and an empty list would have
   * the filter quietly claim nothing matched.
   *
   * ### Reads, never resumes
   *
   * [ServeSessionRegistry.peekHost] for a resident session, and the location snapshot for a
   * suspended one — the same pair [sourceLocationFor] walks. Typing in a filter box must not stand
   * a daemon up, and the index needs only the ids and the metadata behind them.
   */
  private suspend fun RoutingContext.handleUsesSearch(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    if (componentBrowserMode()) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    val index = previewUsage
    if (index == null) {
      // No source fetcher on this host: the Source panel is absent for the same reason. Honest
      // "unavailable" so the filter can say so instead of showing an empty grid.
      call.respondText(
        JSON.encodeToString(UsesResponse.serializer(), UsesResponse(available = false)),
        ContentType.Application.Json,
      )
      return
    }
    val system = selectedSessionId(sessionInPath)
    val previewIds =
      sessions.peekHost(system)?.previews?.map { it.id }
        ?: catalogSourceLocationsSeen[system]?.keys?.toList()
        ?: run {
          call.respondText("not found", status = HttpStatusCode.NotFound)
          return
        }
    val token = call.request.queryParameters["q"].orEmpty()
    // Off the request thread. A cold catalog's first `uses:` search is up to `maxFiles` network
    // reads with the fetcher's own timeout on each, and running that inline blocks a thread Ktor
    // also serves renders on — so a handful of uncached searches could starve routes that have
    // nothing to do with this one.
    val match = withContext(Dispatchers.IO) { index.match(system, previewIds, token) }
    // The answer depends on the interface-mode cookie, so it must not be cached across visitors in
    // one mode and handed to a visitor in the other — the same reason `componentBrowserMode` marks
    // the HTML it gates.
    call.response.headers.append(HttpHeaders.CacheControl, DYNAMIC_RESOURCE_CACHE_CONTROL)
    call.respondText(
      JSON.encodeToString(
        UsesResponse.serializer(),
        UsesResponse(
          available = match.available,
          truncated = match.truncated,
          ids = match.ids.toList().sorted(),
        ),
      ),
      ContentType.Application.Json,
    )
  }

  /**
   * `GET /api/components`: the listed catalogs' component cards for home-page keyboard search.
   * Reads only resident or remembered metadata, so opening the palette never resumes every catalog
   * daemon. The browser fetches this lazily and keeps the result for the life of the page.
   */
  private suspend fun RoutingContext.handleGlobalComponents() {
    if (rejectBadToken()) return
    // This is a front-door-only index. A top-level site has no multi-catalog front door (its root
    // is the catalog landing), and exposing this constant `/api` route there would both reveal its
    // neighbouring catalogs and return canonical links that the site's isolation layer rejects.
    if (siteSystem() != null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    val suffix = if (isPublic) "" else "?token=" + WebEscaping.urlEncodeSegment(linkToken())
    val components =
      listedCatalogs().flatMap { system ->
        sessions.peekHost(system)?.let { rememberCatalogMeta(system, it) }
        val meta = catalogMetaSeen[system] ?: return@flatMap emptyList()
        val systemSegment = WebEscaping.urlEncodeSegment(system)
        meta.components.map { component ->
          GlobalComponentDto(
            label = component.label,
            catalog = system,
            catalogTitle = meta.title ?: system,
            href = "/$systemSegment/p/${WebEscaping.urlEncodeSegment(component.previewId)}$suffix",
            keywords = component.keywords,
          )
        }
      }
    call.response.headers.append(HttpHeaders.CacheControl, DYNAMIC_RESOURCE_CACHE_CONTROL)
    call.respondText(
      JSON.encodeToString(
        GlobalComponentsResponse.serializer(),
        GlobalComponentsResponse(components = components),
      ),
      ContentType.Application.Json,
    )
  }

  /**
   * `GET /index.json` (query) and `GET /{system}/index.json` (path): the session's previews as a
   * Storybook stories index ([StorybookCompat.Index]). This is the manifest a downstream visual
   * tool crawls to enumerate stories and their stable ids.
   */
  private suspend fun RoutingContext.handleStorybookIndex(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    withLeasedSession(selectedSessionId(sessionInPath)) { renderHost ->
      call.respondText(
        JSON.encodeToString(
          StorybookCompat.Index.serializer(),
          StorybookCompat.index(renderHost.previews),
        ),
        ContentType.Application.Json,
      )
    }
  }

  /**
   * `GET /iframe.html?id=<storyId>` (query) and `GET /{system}/iframe.html?id=<storyId>` (path):
   * render one story in isolation. Answers with a chrome-free HTML page embedding the freshly-
   * rendered preview — a raster PNG `data:` URI by default ([StorybookCompat.iframePage]), or with
   * `&format=svg` the figma-svg export as an **inert `<img src="data:image/svg+xml">`**
   * ([StorybookCompat.iframeSvgPage]): a still-vector, resolution-independent render for
   * DOM-capture visual tools (Percy/Chromatic/Applitools), kept in the browser's non-scripting
   * `<img>` mode so an unverified catalog's untrusted SVG can't execute. SVG is daemon-only, so a
   * static bundle 404s that lane. Honours the same override query params as `/render` (e.g.
   * `&uiMode=dark`), and load-sheds through the shared render semaphore.
   */
  private suspend fun RoutingContext.handleStorybookIframe(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    // Renders unconditionally — there is no baked lane here at all, so every request is live work.
    if (rejectGrantBelowScope(AgentGrantScope.LIVE, api = true)) return
    // Renders a story unconditionally — there is no baked lane here. A chrome-less frame for
    // screenshot tools is never an unfurl target (and is robots-disallowed), so a bodyless probe
    // gets nothing but the render bill.
    if (rejectHeadProbe()) return
    val sessionId = selectedSessionId(sessionInPath)
    withLeasedSession(sessionId) { renderHost ->
      val storyId = call.request.queryParameters["id"]
      if (storyId.isNullOrBlank()) {
        call.respondText("missing story id", status = HttpStatusCode.BadRequest)
        return@withLeasedSession
      }
      val previewId = StorybookCompat.resolvePreviewId(storyId, renderHost.previews)
      if (previewId == null) {
        call.respondText("no such story", status = HttpStatusCode.NotFound)
        return@withLeasedSession
      }
      val overrideParams =
        call.request.queryParameters
          .entries()
          .mapNotNull { (key, values) ->
            val value = values.firstOrNull() ?: return@mapNotNull null
            if (ServeOverrides.isOverrideParam(key)) key to value else null
          }
          .toMap()
      val themeSeeding = expandThemeProvider(renderHost, previewId, overrideParams)
      val normalizedOverrideParams =
        ServeWeb.SystemDisplay.normalizeOverrideParams(sessionId, themeSeeding.params)
      val knobKinds =
        ServeOverrides.declaredKnobKinds(renderHost.previews.firstOrNull { it.id == previewId })
      // Reject a themeProvider this catalog never declared instead of quietly rendering the
      // default theme under its name (see ServeOverrides.parse).
      val declaredThemeFqns = renderHost.declaredThemes.map { it.providerFqn }.toSet()
      // `?format=svg` serves the figma-svg export as an inert svg <img> (vector, for DOM-capture
      // visual tools); default (png) inlines the raster. SVG is daemon-only, so a static bundle
      // 404s.
      val wantSvg = call.request.queryParameters["format"]?.lowercase() == "svg"
      when (
        val parsed = ServeOverrides.parse(normalizedOverrideParams, knobKinds, declaredThemeFqns)
      ) {
        is OverrideParse.Invalid ->
          call.respondText(parsed.message, status = HttpStatusCode.BadRequest)
        is OverrideParse.Ok ->
          if (wantSvg) {
            storybookIframeSvg(renderHost, storyId, previewId, parsed.overrides)
          } else {
            storybookIframePng(renderHost, storyId, previewId, parsed.overrides)
          }
      }
    }
  }

  /** PNG lane of [handleStorybookIframe]: render, then inline the raster in the isolation page. */
  private suspend fun RoutingContext.storybookIframePng(
    renderHost: ServeHost,
    storyId: String,
    previewId: String,
    overrides: PreviewOverrides,
  ) {
    val outcome =
      withContext(Dispatchers.IO) {
        if (!renderSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
          null
        } else {
          try {
            renderHost.render(previewId, overrides)
          } finally {
            renderSemaphore.release()
          }
        }
      }
    when (outcome) {
      null -> {
        call.response.headers.append(HttpHeaders.RetryAfter, "2")
        call.respondText(
          "render queue saturated; retry shortly",
          status = HttpStatusCode.ServiceUnavailable,
        )
      }
      RenderOutcome.Busy -> {
        // The daemon was mid-render; the request backed off in ~DAEMON_BUSY_WAIT rather than pin
        // this render slot. Fast 503 + Retry-After (a catalog host would have served baked; a bare
        // bundle host has no baked fallback).
        call.response.headers.append(HttpHeaders.RetryAfter, "2")
        call.respondText("render busy; retry shortly", status = HttpStatusCode.ServiceUnavailable)
      }
      is RenderOutcome.Ok -> {
        // A story's args ride the same override params, and this lane is consumed by exactly the
        // tools #3449 is about — BackstopJS / reg-suit style PNG-diffing across arg values. Baked
        // pixels here would read as "this arg changes nothing".
        val dropped = droppedOverridesFor(renderHost, outcome.generation, previewId, overrides)
        if (dropped.isNotEmpty() && !acceptsBakedFallback()) {
          refuseDroppedOverrides(renderHost, previewId, dropped, overrides)
        } else {
          markDroppedOverrides(dropped)
          call.respondText(StorybookCompat.iframePage(storyId, outcome.png), ContentType.Text.Html)
        }
      }
      RenderOutcome.NotFound -> call.respondText("no such story", status = HttpStatusCode.NotFound)
      is RenderOutcome.Failed ->
        call.respondText(outcome.reason, status = HttpStatusCode.InternalServerError)
    }
  }

  /**
   * SVG lane of [handleStorybookIframe]: render the figma-svg export and serve it as an inert svg
   * `<img>` — a vector render for DOM-capture visual tools, safe even for an untrusted catalog's
   * SVG (see [StorybookCompat.iframeSvgPage]). Daemon-only, so a static bundle host 404s (like
   * `/render.svg`).
   */
  private suspend fun RoutingContext.storybookIframeSvg(
    renderHost: ServeHost,
    storyId: String,
    previewId: String,
    overrides: PreviewOverrides,
  ) {
    val outcome =
      withContext(Dispatchers.IO) {
        if (!renderSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
          null
        } else {
          try {
            renderHost.renderSvg(previewId, overrides)
          } finally {
            renderSemaphore.release()
          }
        }
      }
    when (outcome) {
      null -> {
        call.response.headers.append(HttpHeaders.RetryAfter, "2")
        call.respondText(
          "render queue saturated; retry shortly",
          status = HttpStatusCode.ServiceUnavailable,
        )
      }
      is SvgOutcome.Ok -> {
        val dropped = droppedOverridesFor(renderHost, outcome.generation, previewId, overrides)
        if (dropped.isNotEmpty() && !acceptsBakedFallback()) {
          refuseDroppedOverrides(renderHost, previewId, dropped, overrides)
        } else {
          markDroppedOverrides(dropped)
          call.respondText(
            StorybookCompat.iframeSvgPage(storyId, outcome.svg),
            ContentType.Text.Html,
          )
        }
      }
      SvgOutcome.NotFound ->
        call.respondText(
          "svg unavailable for this story (no daemon-backed SVG export)",
          status = HttpStatusCode.NotFound,
        )
      is SvgOutcome.Failed ->
        call.respondText(outcome.reason, status = HttpStatusCode.InternalServerError)
    }
  }

  /**
   * `GET /bundle.zip` (query) and `GET /{system}/bundle.zip` (path): the session as a portable zip.
   */
  private suspend fun RoutingContext.handleBundleZip(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    // Renders every preview in the catalog and packs a zip. Never probed for an unfurl, and the
    // most expensive thing a HEAD could otherwise trigger anonymously.
    if (rejectHeadProbe()) return
    // …and by the same token the most expensive thing a grant could trigger, so it wants `live`.
    // Not named in the review that caught the `/render` case, but it is the same rule and the
    // larger bill: leaving the sibling hole open while closing the named one would be theatre.
    if (rejectGrantBelowScope(AgentGrantScope.LIVE, api = true)) return
    withLeasedSession(selectedSessionId(sessionInPath)) { renderHost ->
      // Render the whole module once (cache-backed) into the portable WebEmbed gallery and stream
      // it
      // as a zip — the same render output as the live links, downloadable offline.
      val zip =
        withContext(Dispatchers.IO) {
          val built =
            ServeBundle.build(
              previews = renderHost.previews,
              title = renderHost.label,
              modulePath = renderHost.label,
            ) { preview ->
              (renderHost.render(preview.id, PreviewOverrides()) as? RenderOutcome.Ok)?.png
            }
          ServeBundle.zip(built.files)
        }
      call.respondBytes(zip, ContentType.Application.Zip)
    }
  }

  /** Return one server-hydrated, self-contained executable PNG+ZIP preview bundle. */
  private suspend fun RoutingContext.handleExecutableBundle(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    // Materialises a per-preview bundle, which can reach the network.
    if (rejectHeadProbe()) return
    withLeasedSession(selectedSessionId(sessionInPath)) { renderHost ->
      val previewId = call.parameters["name"]
      val available =
        previewId?.let {
          withContext(Dispatchers.IO) { renderHost.canDownloadExecutableBundle(it) }
        } == true
      if (!available) {
        call.respondText("executable bundle unavailable", status = HttpStatusCode.NotFound)
        return@withLeasedSession
      }
      val bytes = withContext(Dispatchers.IO) { renderHost.executableBundle(previewId) }
      if (bytes == null) {
        call.respondText("executable bundle unavailable", status = HttpStatusCode.NotFound)
        return@withLeasedSession
      }
      val filename =
        previewId.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("") +
          ".png"
      call.response.headers.append(
        HttpHeaders.ContentDisposition,
        "attachment; filename=\"$filename\"",
      )
      call.respondBytes(bytes, ContentType.Image.PNG)
    }
  }

  /**
   * `GET /usage/{name}` and `GET /{system}/usage/{name}`: the plain-Compose usage code behind one
   * preview, as JSON, for the viewer's **Source** panel.
   *
   * Its own resource rather than a field on the viewer page, because producing it may cost a GitHub
   * read on a cold catalog cache or a local source read, and most visitors never open the panel —
   * the panel fetches on first entry, so a page load pays nothing.
   *
   * **No session lease.** This is a source read served from the catalog registry/cache or a trusted
   * local module root; leasing would stand a render daemon up to answer a question about source
   * text. Same reasoning as the resolver's own `locate`, which peeks rather than leases.
   *
   * 404 covers every "there is nothing to show" case — no resolver, an unknown preview, a catalog
   * with no recorded source, or source the cleaner declined — so the panel has exactly one branch
   * to handle and never renders a half-answer.
   */
  /**
   * `/usage/<previewId>` for a preview, or null when this host has nothing to serve there — no
   * source fetcher, a preview whose catalog never recorded a source path, or a session with no
   * catalog source to resolve against. Checked here so the Source chip is never rendered dead.
   *
   * Unlike [playgroundLinkFor] this does **not** require a playground: reading the usage code is
   * useful on any host that can browse the catalog, and only running it needs a compiler.
   */
  private fun RoutingContext.usageLinkFor(
    system: String,
    previewId: String,
    sourceFile: String?,
    basePath: String,
  ): String? {
    if (sourceFile.isNullOrBlank()) return null
    if (localSourceFile(system, previewId) != null) {
      return "$basePath/usage/${WebEscaping.urlEncodeSegment(previewId)}${requestQuerySuffix()}"
    }
    if (playgroundSeeds == null) return null
    // The same condition the resolver applies rather than a proxy for it: a plain daemon session or
    // an uploaded bundle can carry a `sourceFile` from its own `previews.json` while having no
    // catalog source to resolve it against, and the chip would then open on an error.
    if (sessions.peekHost(system)?.let { catalogBundleHost(it) }?.catalogSource == null) return null
    return "$basePath/usage/${WebEscaping.urlEncodeSegment(previewId)}${requestQuerySuffix()}"
  }

  private suspend fun RoutingContext.respondNoUsage() {
    markGeneration("usage-snippet", DYNAMIC_RESOURCE_CACHE_CONTROL)
    call.respondText(
      "{\"status\":\"no-usage\"}",
      ContentType.Application.Json,
      HttpStatusCode.NotFound,
    )
  }

  private suspend fun RoutingContext.handleUsage(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val sessionId = selectedSessionId(sessionInPath)
    // Ktor hands route parameters over already decoded, which is why every neighbouring handler
    // (`handleViewer`, `handleRender`) reads this straight. Decoding a second time turned a `%2B`
    // inside a legitimately-escaped preview id into a space and a `%2F` into a separator, so the
    // resolver could not find a preview whose viewer page rendered perfectly.
    val previewId = call.parameters["name"]
    if (previewId.isNullOrBlank()) {
      respondNoUsage()
      return
    }
    // Off the request dispatcher: this is either a local file read or an uncached synchronous
    // GitHub GET with 10 s connect + 10 s read. Neither should hold Ktor's request threads.
    val localSeed = withContext(Dispatchers.IO) { localUsageSeed(sessionId, previewId) }
    val seed =
      localSeed ?: withContext(Dispatchers.IO) { playgroundSeeds?.seed(sessionId, previewId) }
    // Hosted catalogs retain the usage-only contract: if cleaning declined, their existing GitHub
    // source link is the honest fallback. A local browse session has no published blob URL, so its
    // authored file is itself the useful degraded Source experience.
    if (seed == null || (!seed.cleaned && localSeed == null)) {
      respondNoUsage()
      return
    }
    val host = sessions.peekHost(sessionId)
    val sourceFile = host?.previews?.firstOrNull { it.id == previewId }?.sourceFile
    markGeneration("usage-snippet", DYNAMIC_RESOURCE_CACHE_CONTROL)
    call.respondText(
      JSON.encodeToString(
        UsageSnippetResponse.serializer(),
        UsageSnippetResponse(
          text = seed.text,
          entryFunction = seed.previewId.substringAfterLast('.').takeIf { it.isNotBlank() },
          scaffoldsDeclared = seed.scaffoldsDeclared,
          residue = seed.residue,
          blobUrl = seed.blobUrl,
          playgroundHref = host?.let { playgroundLinkFor(it, sessionId, previewId, sourceFile) },
          // Derived here rather than in the cleaner: the links are a projection OF the snippet the
          // cleaner produced, and every other consumer of a seed (the playground handoff, the theme
          // replay) wants the code without them.
          apiDocs =
            ApiDocLinks.of(seed.text).map {
              ApiDocLink(
                name = it.name,
                fqn = it.fqn,
                composable = it.composable,
                url = it.url,
              )
            },
        ),
      ),
      ContentType.Application.Json,
    )
  }

  /**
   * Resolve a manifest source path inside its trusted local module root; never follow it outside.
   */
  private fun localSourceFile(system: String, previewId: String): Pair<ServePreview, File>? {
    val root = localSourceRoots[system] ?: return null
    val preview =
      sessions.peekHost(system)?.previews?.firstOrNull { it.id == previewId } ?: return null
    val relative = preview.sourceFile?.takeIf { it.isNotBlank() } ?: return null
    if (File(relative).isAbsolute) return null
    return try {
      val canonicalRoot = root.canonicalFile
      val source = File(canonicalRoot, relative).canonicalFile
      if (!source.toPath().startsWith(canonicalRoot.toPath()) || !source.isFile) null
      else preview to source
    } catch (_: Exception) {
      null
    }
  }

  /** Read and clean a local browse preview, falling back to its authored source when needed. */
  private fun localUsageSeed(system: String, previewId: String): PlaygroundSeed? {
    val (preview, source) = localSourceFile(system, previewId) ?: return null
    if (source.length() > LOCAL_SOURCE_MAX_BYTES) return null
    val text = source.readBytes().decodeToString()
    if (text.contains('�')) return null
    val cleaned =
      try {
        PlaygroundSourceCleaner.clean(text, preview.bodyLine, UsageRules.GENERIC)
      } catch (_: Exception) {
        null
      }
    return PlaygroundSeed(
      catalog = system,
      previewId = previewId,
      fileName = source.name,
      text = cleaned?.text ?: text,
      blobUrl = null,
      sliced = cleaned != null,
      cleaned = cleaned != null,
      residue = cleaned?.residue.orEmpty(),
    )
  }

  /** `GET /p/{name}` (query) and `GET /{system}/p/{name}` (path): one preview's viewer page. */
  private suspend fun RoutingContext.handleViewer(sessionInPath: Boolean) {
    if (rejectBadToken() || rejectMalformedPin()) return
    val sessionId = selectedSessionId(sessionInPath)
    val (webSessionId, basePath) = webSessionAndBase(sessionInPath)
    withLeasedSession(
      sessionId,
      onMissing = { respondNotFoundHtml("That design system was not found on this server.") },
    ) { renderHost ->
      val previewId = call.parameters["name"]
      val revisions = catalogRevisions(renderHost, previewId)
      // Which catalog decides what this page is *about*. Unpinned it is the session's own list;
      // under a pin it is the revision's own catalog, asked FIRST — the same authority rule the
      // asset lanes follow, and for the same reason. Asking the tip first looks harmless while a
      // preview merely moved, but it hands back today's metadata for an id that revision never
      // published (whose render correctly 404s, so the page would render around a broken image),
      // and today's component name for a route id that has since moved between components.
      //
      // Off the request dispatcher, because a cold lookup fetches that revision's manifests: the
      // route takes any syntactically valid sha, so leaving it here would let concurrent requests
      // for distinct shas hold Ktor's request threads through several round trips each.
      val preview = previewId?.let { id ->
        val host = catalogBundleHost(renderHost)
        val currentPreview = renderHost.previews.firstOrNull { it.id == id }
        val pinnedPreview =
          revisions.pinned?.let { pin ->
            withContext(Dispatchers.IO) { host?.pinnedPreview(pin, id) }
          }
        // The fallback is for a revision whose catalog could not be READ — never for one that
        // was read and does not list this id. [ServeBundleHost.pinnedCatalogIsAuthoritative]
        // tells those apart; without it, "the manifest says no" would quietly become "ask the
        // tip", which is the failure #3769 removed from the asset lanes.
        val revisionAnswers =
          revisions.pinned?.let { pin ->
            withContext(Dispatchers.IO) { host?.pinnedCatalogIsAuthoritative(pin) }
          } == true
        when {
          // The revision still owns the route. While the id survives, enrich its historical
          // component identity with the tip's state/props/source metadata; those fields are not in
          // catalog.json, and dropping them is what made the same variant lose half its label and
          // toolbar. Keep the revision's componentId when it had one, so a genuine historical move
          // between components is still represented. A retired id has no current record and uses
          // the historical placeholder on its own.
          pinnedPreview != null ->
            currentPreview?.copy(
              componentId = pinnedPreview.componentId ?: currentPreview.componentId,
              // The caption takes NO tip fallback, unlike the fields around it. Those are enriched
              // from the tip because `catalog.json` does not carry them; the caption it does carry,
              // so that revision's answer is authoritative including its silence. Falling back
              // would print today's sentence on a historical page and, for a caption that has since
              // been rewritten, describe the render on screen in words its publish never used.
              caption = pinnedPreview.caption,
              theme = pinnedPreview.theme ?: currentPreview.theme,
            ) ?: pinnedPreview
          revisionAnswers -> null
          else -> currentPreview
        }
      }
      if (preview == null) {
        if (revisions.pinned != null && previewId != null) {
          // The selected publish answered authoritatively that this id was absent. Keep the 404 —
          // serving today's preview here would lie about the pin — but retain the revision menu so
          // a catalog-wide publish that predates this preview is not a navigation dead end.
          val skin = siteSkin()
          call.respondText(
            ServeWeb.unavailablePreviewRevisionPage(
              previewId = previewId,
              token = linkToken(),
              sessionId = webSessionId,
              basePath = basePath,
              isPublic = isPublic,
              revisions = revisions,
              unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
              version = SERVE_VERSION,
              siteName = skin.first,
              themeCss = skin.second,
              themeStorageKey = skin.third,
              sessionInOrigin = siteSystem() != null,
              changelogHref = changelogHref(sessionId, basePath, webSessionId),
            ),
            ContentType.Text.Html,
            HttpStatusCode.NotFound,
          )
        } else {
          respondNotFoundHtml("That preview does not exist in this catalog.")
        }
        return@withLeasedSession
      }
      // Offer the in-browser Wasm tier when this catalog session has a Wasm app registered.
      // ServeUrls.wasmAppSrc strips the variant to the component slug the Wasm registry keys by,
      // and
      // bakes the variant's theme into `uiMode` so the live render opens on the same theme as the
      // baked snapshot the visitor deep-linked to.
      val wasmSrc =
        if (!wasmCatalogs.containsKey(sessionId)) null
        else if (!isPublic && sessionId in privateWasmCatalogs)
          ServeUrls.privateWasmAppSrc(sessionId, preview.id, linkToken())
        else ServeUrls.wasmAppSrc(sessionId, preview.id)
      // Grant the Wasm iframe its real origin only for a TRUSTED catalog's app — an unverified
      // catalog's `/wasm/` app stays opaque-origin sandboxed so it can't reach the parent viewer.
      // Fail-closed: any session without a verifiable trusted verdict gets opaque (false).
      val wasmSameOrigin =
        catalogBundleHost(renderHost)?.let { it.trust is BundleVerifier.Verdict.Trusted } ?: false
      val origin = externalOrigin()
      // A pinned page's render URL keeps only the pin; an unpinned one carries the page's own query
      // plus the generation it was assembled from, so the frame the unfurl card and the issue
      // report point at is the frame this page drew ([ServeCacheGeneration]). Not scoped under an
      // override: the URL then names a render made to order, which is `no-store` and belongs to no
      // publish.
      //
      // `requestQuerySuffix()` is the page's RAW query, so this URL also inherits whatever page
      // state the visitor's link carried. That is deliberate for an override — the card should show
      // what they are looking at — and harmless for the rest, because the raster lane reads none of
      // it and its own generation test is scoped to the parameters that actually change pixels.
      val imageQuerySuffix =
        if (revisions.pinned != null) pinnedRenderQuerySuffix()
        else if (requestCarriesOverrides()) requestQuerySuffix()
        else ServeCacheGeneration.scope(requestQuerySuffix(), catalogGeneration(renderHost))
      val imageUrl =
        "$origin$basePath/render/${WebEscaping.urlEncodeSegment(preview.id)}.png$imageQuerySuffix"
      // PNG-header read, so the unfurl card carries the render's real size rather than making the
      // fetcher download it to measure. Also what stops a 300×210 component from claiming a
      // large-image card it can't fill (see [ServeWeb.twitterCard]).
      //
      // Only when the URL carries no overrides. `imageUrl` inherits the page's query suffix, so a
      // link shared from a viewer with `?device=` / `?widthPx=` / `?orientation=` points at a
      // re-render whose pixel size is not the baked one — declaring the baked dimensions there
      // would have the card lay out against a size the image doesn't have. Omitting them is always
      // safe: the fetcher measures the image itself.
      val imageSize =
        if (requestCarriesOverrides()) null else renderHost.bakedRenderSize(preview.id)
      val engagement =
        if (isViewRequest()) incrementPreviewViews(sessionId, preview.id)
        else previewEngagement(sessionId, listOf(preview)).getValue(preview.id)
      val bundleHost = catalogBundleHost(renderHost)
      // Link the preview to its source file on GitHub, built from the catalog's SOURCE (repo/ref/
      // module of the Kotlin — NOT the delivery branch) joined with the preview's module-relative
      // sourceFile. Null when the session has no catalog source or the preview recorded no path.
      val sourceHref =
        bundleHost
          ?.catalogSource
          ?.takeIf { revisions.pinned == null }
          ?.let { src ->
            ServeUrls.githubBlobUrl(
              src.repo,
              src.ref,
              preview.sourceModule ?: src.module,
              preview.sourceFile,
            )
          }
      // The prefilled "report an issue" report for the preview on screen, filed against the repo
      // that owns its Kotlin.
      //
      // Built here rather than only on the focused comparison. Every fact a *preview* bug turns on
      // is concrete on this page — which preview, which component, which variant, the overrides in
      // force, the catalog build it came from, and the PNG at those exact settings — and the viewer
      // is where someone actually notices a button rendering wrongly. Only the two reference-scoped
      // facts are unavailable, and both are optional by construction: with `referenceId` left null
      // the body drops its "Raw comparison" row and [ServeIssueReport.locator] returns null, so no
      // parity-locator fence is emitted. Those stay exclusive to the comparison, which is the only
      // page that can honestly name a design reference or a parity score.
      val reportContext =
        ServeIssueReport.Context(
          repo = ServeIssueReport.repoFor(bundleHost?.catalogSource, bundleHost?.provenance),
          previewId = preview.id,
          previewLabel = preview.label,
          system = sessionId,
          componentId = ServeIssueReport.componentIdFor(preview),
          variant = ServeIssueReport.variantFor(preview),
          overrides = requestOverrideParams(sessionId),
          sourceUrl = sourceHref,
          catalog = bundleHost?.provenance?.let { "${it.repo}@${it.branch}" },
          toolVersion = bundleHost?.provenance?.toolVersion,
          viewerUrl = ServeIssueReport.withoutToken(externalPageUrl()),
          // `imageUrl` already carries this page's override suffix, so the report links the render
          // the visitor is looking at rather than the preview's defaults. Token-stripped, like
          // every URL that reaches an issue body.
          renderUrl = ServeIssueReport.withoutToken(imageUrl),
          publicRender = isPublic,
        )
      val reportIssue =
        ServeWeb.ReportIssue(
          action = ServeIssueReport.action(reportContext.repo),
          body = ServeIssueReport.body(reportContext),
          bodyTemplate = ServeIssueReport.body(reportContext, renderPlaceholder = true),
          repo = reportContext.repo,
          login = githubAuth?.currentLogin(call),
        )
      val liveAuthPrompt =
        githubAuth
          ?.takeIf { renderHost.hasLiveStream }
          ?.takeUnless { it.isAuthenticated(call) }
          ?.takeIf { oauthCanRoundTrip() }
          ?.let {
            ServeWeb.LiveAuthPrompt(
              loginHref = it.loginPath(call),
              restrictedToAllowedUsers = it.isRestrictedToAllowedUsers(),
            )
          }
      // Project mode's timeline, computed from the local repo rather than fetched from a delivery
      // branch. Gated on the session having no delivery provenance — exactly the condition that
      // leaves `historyManifestUrl` null — because a catalog served from a delivery branch already
      // ships the published manifest, and that, not this box's checkout, is the truth about what it
      // has rendered. Off the event loop: the first call per refresh window shells out to git.
      val localHistoryJson =
        projectHistory
          ?.takeIf { catalogBundleHost(renderHost)?.provenance == null }
          ?.let { history -> withContext(Dispatchers.IO) { history.timelineJsonFor(preview.id) } }
      // The request's override params, split into what this page's controls may open on and what
      // they must not — see [seedableOverrideParams]. Both halves reach the page: the seeds paint
      // the markup, the rest is published so the viewer's own URL restore defers on them too.
      val overrideSeeds =
        seedableOverrideParams(renderHost, preview, sessionId, revisions.pinned, wasmSrc)
      // The publication-aware HEAD probe is network I/O; keep it off Ktor's request dispatcher.
      val executableBundleAvailable =
        withContext(Dispatchers.IO) { renderHost.canDownloadExecutableBundle(preview.id) }
      markGeneration(
        "static-page",
        viewerCacheControl(
          githubAuthConfigured = githubAuth != null,
          isPublic = isPublic,
          signedIn = requestIsSignedIn(),
          stagedCapabilitiesPending = renderHost.rcComparePending(),
        ),
      )
      call.respondText(
        ServeWeb.viewerPage(
          preview,
          linkToken(),
          webSessionId,
          canApplyOverrides = renderHost.canApplyOverrides,
          // Per-preview: a catalog-live host can only re-render an override on a daemon-twinned
          // preview, so an unaliased (Android-only) variant reports false and its override controls
          // (knobs, App theme) render disabled/informational rather than enabled-but-dead.
          canRenderOverrides = renderHost.canRenderOverridesFor(preview.id),
          // The knob values THIS request asked for, so the controls open on them rather than on the
          // preview's declaration — unless this page's picture cannot be showing them, in which
          // case seeding is the very disagreement the parameter exists to remove, pointed the other
          // way. See `seedableOverrideParams`.
          requestOverrides = overrideSeeds.seeded,
          // …and the axes it declined, so `hydrateFromUrl` defers on them instead of putting them
          // straight back a frame after load.
          unseededOverrides = overrideSeeds.withheld,
          // Per-preview: a catalog advertises SVG globally as soon as it carries a `figma/` dir,
          // but
          // a preview whose slug has no baked `figma/<slug>.svg` still 404s the `.svg` lane, so
          // gate
          // the SVG control on this preview's actual availability rather than the session-wide
          // flag.
          hasSvgExport = renderHost.hasSvgExportFor(preview.id),
          hasScrollExport = renderHost.hasScrollExportFor(preview.id),
          executableBundleHref =
            if (executableBundleAvailable)
              "$basePath/bundle/${WebEscaping.urlEncodeSegment(preview.id)}${requestQuerySuffix()}"
            else null,
          // The inspection layers: the accessibility focus map needs an a11y-capable daemon, the
          // typography / theme layers a semantics-capturing one. Both are asked per preview, not
          // session-wide: a catalog host fronts the whole catalog but only its daemon-twinned ids
          // can be inspected, so an unmapped (Android-only) variant must omit the controls rather
          // than offer ones whose fetch can only 404.
          hasA11yOverlay = renderHost.hasA11yOverlayFor(preview.id),
          hasDesignAnnotations = renderHost.hasDesignAnnotationsFor(preview.id),
          // The baked half of the same Typography layer: a published catalog measured it off the
          // frame it also published, so the overlay works on a host with no daemon at all.
          hasPublishedTypography = renderHost.hasPublishedTypographyFor(preview.id),
          hasLiveStream = renderHost.hasLiveStream,
          trust = catalogBundleHost(renderHost)?.let { BundleVerifier.summary(it.trust) },
          // Per-preview: offer the in-browser Remote Compose canvas lane only when this preview
          // carries a captured `.rc` document to replay (the browser fetches it from
          // `/render/<id>.rc`).
          hasRemoteComposeDoc = renderHost.hasRemoteComposeDoc(preview.id),
          // Per-preview: a server render replays the captured document instead of recomposing, so
          // the viewer greys the controls [droppedOverridesFor] would answer with a 409. Same host
          // question that predicate asks, deliberately read here rather than derived from
          // `hasRemoteComposeDoc` on the client — the two must not drift apart.
          irReplay = isReplayedPreview(renderHost, preview.id),
          // …but a replayed preview can still take a declared theme when the session publishes its
          // colours, so the viewer greys the recomposition-only controls without greying this one.
          replayThemes = applicableThemes(renderHost, preview.id).isNotEmpty(),
          // Per-preview: the Remote Compose backend selector's enabled lanes. The host advertises
          // its server/client lanes; the opt-in CMP/Wasm distribution contributes the browser
          // lane when this preview has an RC document. Empty for a non-RC preview ⇒ no selector.
          enabledRcPlayers =
            buildList {
              addAll(renderHost.enabledRcPlayersFor(preview.id).map { it.wire })
              if (rcPlayerWasmDir != null && renderHost.hasRemoteComposeDoc(preview.id)) {
                add(RcPlayerBackend.CMP_WASM.wire)
              }
            },
          wasmSrc = wasmSrc,
          wasmSameOrigin = wasmSameOrigin,
          basePath = basePath,
          changelogHref = changelogHref(sessionId, basePath, webSessionId),
          isPublic = isPublic,
          componentBrowser = componentBrowserMode(),
          declaredThemes = applicableThemes(renderHost, preview.id),
          // Android-daemon-only: gates the "Show gesture hints" row so a `@GestureHintPreview`
          // doesn't show a toggle that would do nothing on a desktop-backed session.
          gesturesRenderable = renderHost.gesturesRenderable,
          // The session's full preview list feeds the left-hand component nav drawer.
          siblings = renderHost.previews,
          // The catalog's declared stage surface (`display.surface`), so an unthemed preview backs
          // on the dark stage for a dark-first system instead of the default white.
          declaredSurface = catalogBundleHost(renderHost)?.stageSurface,
          // …and its own colour palette, so this system's pages are framed in its colours.
          themeCss = catalogBundleHost(renderHost)?.webThemeCss.orEmpty(),
          // The header bar names the catalog this preview belongs to — the viewer's own <h1>
          // is the preview, so without this the page never says which system it is from.
          catalogName =
            ServeWeb.catalogHeading(catalogBundleHost(renderHost)?.title, renderHost.label),
          // Why this session is snapshot-only, when it is — the banner under the header explains
          // the
          // catalog-level reason (no live bundle, unverified, …) alongside the per-control note.
          degradations = renderHost.degradations,
          engagement = engagement,
          unfurl =
            ServeWeb.UnfurlMetadata(
              pageUrl = externalPageUrl(),
              imageUrl = imageUrl,
              imageWidth = imageSize?.first,
              imageHeight = imageSize?.second,
            ),
          version = SERVE_VERSION,
          sourceHref = sourceHref,
          reportIssue = reportIssue,
          // The Figma node this preview is specified by, when the catalog publishes a Figma-backed
          // design reference for it. Resolved from data the catalog already carries — nothing is
          // fetched from Figma, here or anywhere else in serve.
          figmaSpec = ServeFigmaSpec.of(renderHost.designReferencesFor(preview.id)),
          // …and the spec itself, as a lane the viewer can put on the stage beside the players.
          // First reference, the same precedence [ServeFigmaSpec] uses: a preview with several has
          // one canonical spec, and the manifest's order is the producer's own. Absent for every
          // catalog that publishes no references, which omits the lane entirely.
          designReference = renderHost.designReferencesFor(preview.id).firstOrNull(),
          // …and the counterpart in the `compareWith` sibling, when this catalog declares a pairing
          // and we host the other side of it. A second SOURCE for that same lane rather than a mode
          // of its own, so the four views are unchanged (issue #4621).
          parallelSource = parallelSpecSource(renderHost, preview),
          referenceAnnotations =
            if (revisions.pinned != null) emptyList()
            else
              renderHost
                .designReferencesFor(preview.id)
                .firstOrNull()
                ?.let { renderHost.annotationsForReference(it.id) }
                .orEmpty(),
          // "open in playground" — offered only when this host has the lane AND this preview
          // records a source path, so the link never lands on a page that opens the generic
          // sample and quietly ignores what was asked for.
          playgroundHref =
            if (revisions.pinned == null)
              playgroundLinkFor(renderHost, sessionId, preview.id, preview.sourceFile)
            else null,
          usageHref = usageLinkFor(sessionId, preview.id, preview.sourceFile, basePath),
          liveAuthPrompt = liveAuthPrompt,
          catalogTitle = catalogBundleHost(renderHost)?.title,
          // The same heartbeat the grid sends. The viewer needs it at least as much: it is where a
          // visitor settles on one preview and reads, making no further requests, and where the
          // theme and knob actions that want a warm daemon are actually taken.
          presenceUrl = "$basePath/api/presence${requestQuerySuffix()}",
          // Delivery-branch provenance is what makes a timeline possible: it names the repo and
          // branch carrying history.json, and the same repo addresses each historical render by
          // commit. A plain uploaded bundle has none, so both stay null and the strip is omitted.
          historyManifestUrl =
            catalogBundleHost(renderHost)?.provenance?.let {
              ServeUrls.historyManifestUrl(it.repo, it.branch)
            },
          historyRepo = catalogBundleHost(renderHost)?.provenance?.repo,
          // …and its project-mode twin: the timeline inlined rather than fetched, with its entries
          // pointing back at this server's `/history/render/` lane. Mutually exclusive with the
          // pair above by construction — a session has catalog provenance or it has a local repo,
          // never both.
          historyInlineJson = localHistoryJson,
          historyLocalRenders = localHistoryJson != null,
          revisions = revisions,
          revisionQuery =
            requestOverrideParams(sessionId)
              .filterKeys { it == "themeProvider" || it == "uiMode" }
              .entries
              .joinToString("&") { (key, value) ->
                "${WebEscaping.urlEncodeSegment(key)}=${WebEscaping.urlEncodeSegment(value)}"
              },
          parityIssues =
            renderHost.parityIssues()?.issues.orEmpty().filter { issue ->
              preview.id in issue.previewIds ||
                (preview.componentId != null && issue.component == preview.componentId)
            },
          // A top-level site's pages carry their session in the ORIGIN, so same-session links
          // drop the `?session=` the rooted legacy form would add. See [ServeSites].
          sessionInOrigin = siteSystem() != null,
        ),
        ContentType.Text.Html,
      )
    }
  }

  /**
   * `GET /history/render/{blob}.png`: one historical render, read out of the local repository by
   * content sha — what a project-mode timeline chip links to (see [ServeProjectHistory]).
   *
   * Content-addressed rather than `<commit>/<path>` addressed, so there is no path to traverse and
   * no ref to steer: the sha is either one the current timeline names or it is a 404. Deliberately
   * session-independent — the blob belongs to the repository, not to a session — but registered in
   * both URL forms so the viewer can link relative to whichever prefix it was served under.
   */
  private suspend fun RoutingContext.handleHistoryRender() {
    if (rejectBadToken()) return
    // Reads an old render out of the local git object store, per request.
    if (rejectHeadProbe()) return
    val history = projectHistory
    val name = call.parameters["name"].orEmpty().removeSuffix(".png")
    val bytes =
      if (history == null) null else withContext(Dispatchers.IO) { history.renderBytes(name) }
    if (bytes == null) {
      call.respondText("no such render", status = HttpStatusCode.NotFound)
      return
    }
    // Immutable by construction — the URL *is* the content hash — but this is a token-gated
    // response on a private box, so it follows the same no-store rule as every other one.
    markGeneration("history-render", DYNAMIC_RESOURCE_CACHE_CONTROL)
    call.respondBytes(bytes, ContentType.Image.PNG)
  }

  /**
   * `GET /render/{name}` (query) and `GET /{system}/render/{name}` (path): a preview's rendered
   * bytes — a PNG for `<id>.png` (or no suffix), the figma-svg export for `<id>.svg`, the declared
   * preview slots as JSON for `<id>.slots`, the merged accessibility products as JSON for
   * `<id>.a11y`, the typography + theme inspection layers for `<id>.annotations`, or the captured
   * Remote Compose document for `<id>.rc`. All but `.rc` take the same override query params; SVG
   * and slots are only produced by a daemon-backed host, and `.rc` only by a bundle host that
   * carries `ir/` sidecars (each 404s where unavailable).
   */
  private suspend fun RoutingContext.handleRender(sessionInPath: Boolean) {
    if (rejectBadToken() || rejectMalformedPin() || rejectMalformedGeneration()) return
    // An override or a daemon-only product turns this route from a replay into a **live render**,
    // which is what `live` means and what a `preview` grant was not given. The two other gates
    // learned this rule; this one is reached without them, so it has to state it itself.
    //
    // Deliberately keyed on the caller's own request — an override param, a non-PNG suffix — and
    // not on whether the bytes happen to be baked. A bare `/render/<id>.png` for a preview this
    // host has no baked copy of is the catalog serving its own content, at the same cost any
    // anonymous visitor imposes on a public box; refusing that would break ordinary browsing every
    // time a session was not resident, which is not what anyone approved or withheld.
    if (
      (requestCarriesOverrides() || wantsDaemonOnlyRenderProduct()) &&
        rejectGrantBelowScope(AgentGrantScope.LIVE, api = true)
    )
      return
    // A bare `/render/<id>.png` replays a baked file and IS what an unfurler probes for `og:image`,
    // so it must keep answering HEAD. Everything else on this route reaches a daemon or a bundle
    // host, and amplifying a bodyless probe into one is the same trade as `/bundle.zip` at smaller
    // scale: an override turns the replay into a live render, and the non-PNG products
    // ([DAEMON_ONLY_RENDER_SUFFIXES]) are produced on demand whether or not a query is present.
    // The override half is keyed on param names rather than a parse — the check has to be cheap,
    // and erring toward refusing costs a probe nothing (the caller GETs instead).
    if (
      (requestCarriesOverrides() ||
        wantsDaemonOnlyRenderProduct() ||
        !renderWouldReplayBakedBytes(sessionInPath)) && rejectHeadProbe()
    )
      return
    val sessionId = selectedSessionId(sessionInPath)
    withLeasedSession(sessionId) { renderHost ->
      val rawName = call.parameters["name"]
      if (rawName.isNullOrBlank()) {
        call.respondText("missing preview id", status = HttpStatusCode.BadRequest)
        return@withLeasedSession
      }
      val wantSvg = rawName.endsWith(".svg")
      val wantSlots = rawName.endsWith(".slots")
      val wantA11y = rawName.endsWith(".a11y")
      val wantAnnotations = rawName.endsWith(".annotations")
      val wantRcDoc = rawName.endsWith(".rc")
      val previewId =
        rawName
          .removeSuffix(".png")
          .removeSuffix(".svg")
          .removeSuffix(".slots")
          .removeSuffix(".a11y")
          .removeSuffix(".annotations")
          .removeSuffix(".rc")
      // The prebaked grid-thumbnail lane: a catalog card asks for `?thumb=<hash>` and gets a
      // downscaled copy of its render straight out of memory — no override parse, no admission, no
      // disk read, no chance of waking a daemon. This is the whole point of the lane: a catalog
      // page is ~42 cards, and serving each of them a full-resolution PNG is both the page's bulk
      // and 42 trips through the render machinery.
      //
      // The hash must match what this host bakes today. A stale URL (the catalog was republished
      // under the visitor's open tab) simply falls through to the normal render, which is correct
      // rather than merely safe: the bytes behind a `thumb=` URL never change, which is what makes
      // the response `immutable`.
      //
      // Only a request that asks for *nothing else* can be answered here. A thumbnail is the base
      // render at a smaller size, so anything that shapes the pixels — a declared theme or any
      // other override, a full-page `scroll=` export, the cmp-jvm player lane — has to go the
      // normal way. Those params ride on the same URL by design (the grid appends `themeProvider=`
      // to the card's `src` when the visitor picks a theme), so this check is what keeps a themed
      // render from being answered with an unthemed thumbnail.
      //
      // A **stale generation** leaves the lane for the same reason a pin does, and it has to be
      // asked here rather than inside [plainThumbRequest]: the thumbnail is downscaled from the
      // catalog on disk, so it is by definition this generation's, and the fast path sits ahead of
      // the routing that would otherwise fetch the named publish's bytes. Answering would be the
      // worst shape available — a 200, `immutable`, from a generation the URL says it is not
      // (#4714 review). A `gen=` naming the generation on disk is no obstacle at all and stays on
      // the fast path, which is what keeps a scoped card cheap.
      val thumbHash = call.request.queryParameters[ServeHeroImages.THUMB_PARAM]
      if (
        thumbHash != null &&
          !wantSvg &&
          !wantSlots &&
          !wantA11y &&
          !wantAnnotations &&
          !wantRcDoc &&
          plainThumbRequest() &&
          staleGeneration(renderHost) == null
      ) {
        val thumb =
          heroImages.gridThumbFor(
            renderHost,
            previewId,
            catalogBundleHost(renderHost)?.contentCrop(previewId),
          )
        if (thumb != null && thumb.hash == thumbHash) {
          respondGridThumb(thumb)
          return@withLeasedSession
        }
      }
      // A product can be selected by *query* rather than by suffix: `?scroll=long` is a full-page
      // capture, `?rcPlayer=cmp-jvm` is a different player's raster, `mode=` presents the SVG
      // export, and any override (`fontScale`, `device`, `knob.…`) asks for pixels rendered to
      // order. None of those is a published byte, so a **pin** naming one of them is a URL claiming
      // two contradictory things and is refused below. The generation lane asks a narrower version
      // of the same question ([madeToOrder]) and reaches the opposite conclusion; both are stated
      // where they are decided rather than shared, because they are not the same rule.
      val onDemand =
        requestCarriesOverrides() ||
          call.request.queryParameters["scroll"] != null ||
          call.request.queryParameters["rcPlayer"] != null ||
          call.request.queryParameters["mode"] != null ||
          ServeExplodedSvg.PARAMS.any { call.request.queryParameters[it] != null }
      // A **pinned** render (`?at=<sha>`): the bytes this preview had at that delivery-branch
      // commit, read from the branch rather than from the catalog on disk. This is what makes a
      // published URL a permalink (issue #3723) — see [ServeCatalogRevision].
      //
      // It short-circuits ahead of everything below because a pin and a live render are mutually
      // exclusive by definition: the daemon renders today's code, so honouring an override here
      // would answer a request for the past with the present. Only the raster product is pinnable
      // (the branch publishes PNGs, not slot trees or `.rc` documents), and a pin the branch cannot
      // answer is a 404 rather than a fall-through to the current bytes — silently serving today's
      // render under a permalink is the exact failure this feature exists to prevent, and it would
      // be invisible to whoever followed the link.
      val requestedPin =
        ServeCatalogRevision.normalize(call.request.queryParameters[ServeCatalogRevision.PARAM])
      // The same lane answers a **stale generation** ([ServeCacheGeneration]): a page assembled one
      // publish ago writes `gen=<that publish>` on the frames it draws, and the frame it needs is
      // that publish's, not today's. Reconciled here rather than beside the pin because it IS a
      // read of a published revision — a second mechanism would be a second set of rules about
      // which trees may be fetched, and the two would eventually disagree. A `gen=` naming the
      // generation on disk falls through, which is the ordinary browse: it changes nothing but the
      // response's cache lifetime, decided further down.
      //
      // Exactly one thing makes a stale generation step aside: a render **made to order**. An
      // override, a scroll capture, a player selection, an exploded projection — the visitor asked
      // for pixels that reflect no published bytes at all, the response is `no-store`, and there is
      // no pair for a cache to hold wrongly. Refusing there would break the one interaction this
      // coupling must not cost: turning a knob on a page a refresh overtook.
      //
      // Deliberately NARROWER than the pin's [onDemand] in one place, and the difference is
      // load-bearing: `mode=` selects a presentation of the SVG export and does nothing whatever on
      // the raster lane, while it is ordinary page state that a viewer's shared link carries and
      // that the frame URL inherits. Reading it as "made to order" here would opt the commonest
      // shared link straight back out of the coupling, with a 200 and no sign of it.
      val madeToOrder =
        requestCarriesOverrides() ||
          call.request.queryParameters["scroll"] != null ||
          call.request.queryParameters["rcPlayer"] != null ||
          (wantSvg && call.request.queryParameters["mode"] != null) ||
          ServeExplodedSvg.PARAMS.any { call.request.queryParameters[it] != null }
      val staleGeneration = if (madeToOrder) null else staleGeneration(renderHost)
      // A stale generation on a **non-raster product** refuses, exactly as a pin does. These are
      // the products whose whole purpose is to *describe* the frame — a semantics tree, an a11y
      // pass, the typography and layout annotations the redline draws and an element selection
      // records as an acceptance baseline — and the server can only describe today's. Answering
      // would hand a page from one publish a measurement of another's, which is the corruption this
      // parameter exists to prevent, arriving through the one door it left open: stepping aside
      // here was silently the same bug pointing the other way (#4714 review).
      if (
        staleGeneration != null &&
          (wantSvg || wantSlots || wantA11y || wantAnnotations || wantRcDoc)
      ) {
        call.respondText(
          "only the baked render is published per generation; this catalog has moved on from " +
            "'${ServeCacheGeneration.PARAM}=${ServeCacheGeneration.short(staleGeneration)}', so " +
            "there is no answer for that frame — reload the page",
          status = HttpStatusCode.Conflict,
        )
        return@withLeasedSession
      }
      val pinnedCommit = requestedPin ?: staleGeneration
      if (pinnedCommit != null) {
        // The non-raster products are made on demand by the daemon — an SVG export, a slot tree,
        // an a11y or annotation pass, a captured Remote Compose document. The branch publishes
        // none of them per revision, so there is nothing historical to serve, and *falling through*
        // would be the worst of the three options: `/render/<id>.svg?at=<sha>` would answer with
        // today's export under a URL that names an old publish. Refusing says so.
        if (wantSvg || wantSlots || wantA11y || wantAnnotations || wantRcDoc) {
          call.respondText(
            "only the baked render is published per revision; drop " +
              "'${ServeCatalogRevision.PARAM}' to ask the daemon for this product",
            status = HttpStatusCode.NotFound,
          )
          return@withLeasedSession
        }
        if (onDemand) {
          call.respondText(
            "'${ServeCatalogRevision.PARAM}' pins the published render, which cannot be " +
              "re-rendered to order; drop the pin or drop the render parameters",
            status = HttpStatusCode.BadRequest,
          )
          return@withLeasedSession
        }
        respondPinnedAsset(
          outcome =
            catalogBundleHost(renderHost)?.let {
              withContext(Dispatchers.IO) { it.pinnedRender(pinnedCommit, previewId) }
            },
          missing = "no published render for that preview at that revision",
        )
        return@withLeasedSession
      }
      // The `.rc` lane serves the captured Remote Compose document bytes verbatim (no override
      // pass — the in-browser player replays the doc and applies knob edits client-side), so it
      // short-circuits ahead of the override parse. A host with no `ir/<id>.rc` sidecar (a
      // daemon-only host, or an unknown id) returns null → 404.
      if (wantRcDoc) {
        val bytes = renderHost.remoteComposeDoc(previewId)
        if (bytes == null) {
          call.respondText("no such remote compose document", status = HttpStatusCode.NotFound)
        } else {
          call.respondBytes(bytes, ContentType.Application.OctetStream)
        }
        return@withLeasedSession
      }
      // A **bare** cmp-jvm raster the parity run already staged, answered before the subprocess is
      // considered at all. This lane has to be caught here rather than in the `cached` chain below,
      // because the short-circuit under it returns before the override parse the chain reads — and
      // spawning a one-shot desktop JVM (measured at ~4.3s) to redraw a document that was already
      // drawn and published is the same waste the daemon lanes were paying, minus the daemon.
      //
      // "Bare" is read straight off the query here, since the parsed overrides do not exist yet: no
      // override param other than `rcPlayer` may be present. `.svg` is excluded because the staged
      // artifact is a raster and the structural export is a different product.
      if (!wantSvg && !wantSlots && !wantA11y && !wantAnnotations && bareRcPlayerRequest()) {
        val staged =
          renderHost.publishedRcPlayerRender(previewId, RcPlayerBackend.CMP_JVM).takeIf {
            call.request.queryParameters["rcPlayer"]?.lowercase() == RcPlayerBackend.CMP_JVM.wire
          }
        if (staged != null) {
          markGeneration(RenderOutcome.Generation.RC_PUBLISHED.wire, DYNAMIC_RESOURCE_CACHE_CONTROL)
          call.respondBytes(staged, ContentType.Image.PNG)
          return@withLeasedSession
        }
      }
      // The cmp-jvm lane renders the captured document server-side with the embedded desktop player
      // (an isolated subprocess), not through the daemon. It supports both the pixel `.png` and the
      // structural `.svg` product; slots remain a host/daemon product and continue below.
      if (
        !wantSlots &&
          !wantA11y &&
          !wantAnnotations &&
          call.request.queryParameters["rcPlayer"]?.lowercase() == RcPlayerBackend.CMP_JVM.wire
      ) {
        val format = if (wantSvg) RcJvmServerRenderer.Format.SVG else RcJvmServerRenderer.Format.PNG
        val webMode = wantSvg && call.request.queryParameters["mode"]?.lowercase() == "web"
        renderCmpJvmResponse(renderHost, previewId, format, webMode, sessionId)
        return@withLeasedSession
      }
      // Forward the fixed render axes plus any dynamic override params (`knob.<key>=…` knobs and
      // `rc.<name>=…` Remote Compose seeds, neither in SUPPORTED_KEYS) so a live knob / Remote
      // Compose edit reaches ServeOverrides.parse instead of being silently dropped.
      val overrideParams =
        call.request.queryParameters
          .entries()
          .mapNotNull { (key, values) ->
            val value = values.firstOrNull() ?: return@mapNotNull null
            if (ServeOverrides.isOverrideParam(key)) key to value else null
          }
          .toMap()
      val themeSeeding = expandThemeProvider(renderHost, previewId, overrideParams)
      val normalizedOverrideParams =
        ServeWeb.SystemDisplay.normalizeOverrideParams(sessionId, themeSeeding.params)
      // Type a bare `knob.<key>=<value>` from the preview's declared knobs (an explicit
      // `<kind>:<value>` still wins) so the viewer never has to spell the type in the URL.
      val knobKinds =
        ServeOverrides.declaredKnobKinds(renderHost.previews.firstOrNull { it.id == previewId })
      // Reject a themeProvider this catalog never declared instead of quietly rendering the
      // default theme under its name (see ServeOverrides.parse).
      val declaredThemeFqns = renderHost.declaredThemes.map { it.providerFqn }.toSet()
      when (
        val parsed = ServeOverrides.parse(normalizedOverrideParams, knobKinds, declaredThemeFqns)
      ) {
        is OverrideParse.Invalid ->
          call.respondText(parsed.message, status = HttpStatusCode.BadRequest)
        is OverrideParse.Ok -> {
          // Wear/watch surfaces are always dark. Ignore a generic or hand-authored uiMode query so
          // it cannot wake the live daemon and produce another render for an unsupported mode.
          val overrides = parsed.overrides
          val scroll =
            call.request.queryParameters["scroll"]?.lowercase() in setOf("long", "full", "page")
          if (wantSvg) {
            // `?scroll=long` (or `full`/`page`) asks for the full-page export of a scrolling
            // preview (compose/figma-svg-long) instead of the viewport-sized one.
            // `?mode=web` serves a web/document variant: the base64 `@font-face` blocks are swapped
            // for an external Google Fonts `@import`, so a browser viewing the `.svg` directly
            // pulls
            // the faces from Google instead of the SVG carrying their bytes. The default (no
            // `mode`,
            // or `mode=figma`) stays fully self-contained — right for `<img>`/Figma import, where
            // external references don't load.
            // `?exploded=1` (plus its tilt / spin / gap / depth knobs) is a second rewrite of the
            // same bytes: the layered export pulled apart into one sheet per composable nesting
            // level. It composes with `mode=web` and `scroll=long` because all three are
            // post-processing steps over one render.
            val webMode = call.request.queryParameters["mode"]?.lowercase() == "web"
            renderSvgResponse(
              renderHost,
              previewId,
              overrides,
              scroll = scroll,
              webMode = webMode,
              exploded = explodedOptions(),
            )
            return@withLeasedSession
          }
          if (wantSlots) {
            renderSlotsResponse(renderHost, previewId, overrides)
            return@withLeasedSession
          }
          if (wantA11y) {
            renderA11yResponse(renderHost, previewId, overrides)
            return@withLeasedSession
          }
          if (wantAnnotations) {
            renderAnnotationsResponse(renderHost, previewId, overrides)
            return@withLeasedSession
          }
          // The render is blocking (renderNow + await); keep it off the request dispatcher. Cap
          // concurrent renders (default = CPU count) so a small box sheds a storm instead of
          // thrashing: wait briefly for a slot, else 503 + Retry-After. A null outcome signals the
          // wait timed out.
          // Catalog-host theme cache hits are memory reads and must not be rejected merely because
          // unrelated live renders occupy every global slot. [render] rechecks after admission to
          // close the race with a render that completes between these two calls.
          // A full-page request is a distinct daemon data product; a cached viewport PNG cannot
          // satisfy it even when the preview + overrides key is otherwise identical.
          // Answerable without entering admission: a completed theme-cache entry, or pixels
          // already baked on disk. This is what lets a mostly-browsing box stay responsive under
          // load — otherwise every thumbnail read competes for the same handful of global render
          // slots as the daemon renders, and a few cold ones (which can take a minute each)
          // head-of-line block dozens of readers whose answer was a local file, until they 503.
          // A `?scroll=` request is a distinct full-page product that baked pixels cannot satisfy.
          val cached =
            if (scroll) null
            else
              renderHost.cachedRender(previewId, overrides)
                // BEFORE the baked snapshot, not after. `bakedRender` answers from the preview's
                // published PNG without consulting the overrides at all, so a host that has both
                // local baked pixels and staged rc-compare rasters would return the baked bytes for
                // a bare `?rcPlayer=…` and never reach this lane — and those bytes are the *Java*
                // player's capture, so the request would then be refused for dropping `rcPlayer`
                // while the very raster it asked for sat unread. A player selection is the more
                // specific answer, so it wins; everything else falls through to baked as before.
                ?: publishedRcPlayerRender(renderHost, previewId, overrides)
                ?: renderHost.bakedRender(previewId, overrides)
          // A "pure declared-theme render" — the classification the burst lease admits on. Read
          // from the request rather than the parsed overrides, because an expanded provider is no
          // longer in them: `themeSeeding.provider` is what the expansion consumed, and the request
          // was still *only* a theme selection when `themeProvider` was its sole override param.
          val pureThemeProvider =
            overrides.themeProvider?.takeIf {
              overrides == PreviewOverrides(themeProvider = it) &&
                renderHost.declaredThemes.any { theme -> theme.providerFqn == it }
            }
              ?: themeSeeding.provider?.takeIf {
                overrideParams.keys.all { key -> key == "themeProvider" } &&
                  renderHost.declaredThemes.any { theme -> theme.providerFqn == it }
              }
          // A preview this catalog has permanently failed to render is answered here, before any
          // lease or render slot is taken. 409 rather than 503/500: the request will never succeed,
          // so the page must stop retrying it. Retrying a latched preview is exactly what kept the
          // grid's workers busy and the daemon's render lock contended.
          val latchedFailure =
            if (cached == null) renderHost.renderFailureLatch(previewId, overrides) else null
          if (latchedFailure != null) {
            call.respondText(latchedFailure, status = HttpStatusCode.Conflict)
            return@withLeasedSession
          }
          val leaseToken = call.request.queryParameters["_themeLease"]
          val admission =
            if (cached == null && pureThemeProvider != null && leaseToken != null) {
              themeRenderLeases.admission(leaseToken, sessionId, renderHost)
            } else {
              null
            }
          // Saturated is the only refusal. The claim is alive and its width is momentarily full, so
          // `Retry-After` is a true statement and the page's backoff is the right response.
          if (admission is ThemeRenderLeaseManager.Admission.Saturated) {
            call.response.headers.append(HttpHeaders.RetryAfter, "2")
            call.respondText(
              "theme render lease saturated",
              status = HttpStatusCode.TooManyRequests,
            )
            return@withLeasedSession
          }
          val leasePermit = (admission as? ThemeRenderLeaseManager.Admission.Admitted)?.permit
          // A token this manager does not know — released, expired, or minted for another catalog —
          // is treated exactly like a request that carried none: the render still happens, on the
          // serial unleased lane. Refusing it instead (what a `429` did) is unrecoverable by the
          // caller, because no amount of retrying makes a reaped claim valid again, and it stranded
          // whole themed grids on the previous theme's pixels.
          val needsSerialThemePermit =
            cached == null && pureThemeProvider != null && leasePermit == null
          val outcome =
            try {
              cached
                ?: withContext(Dispatchers.IO) {
                  val serialAcquired =
                    !needsSerialThemePermit ||
                      unleasedThemeSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)
                  if (!serialAcquired) {
                    null
                  } else if (
                    !renderSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)
                  ) {
                    if (needsSerialThemePermit) unleasedThemeSemaphore.release()
                    null
                  } else {
                    try {
                      if (scroll) renderHost.renderScrollPng(previewId, overrides)
                      else if (leasePermit != null) renderHost.renderLeased(previewId, overrides)
                      else renderHost.render(previewId, overrides)
                    } finally {
                      renderSemaphore.release()
                      if (needsSerialThemePermit) unleasedThemeSemaphore.release()
                    }
                  }
                }
            } finally {
              leasePermit?.close()
            }
          when (outcome) {
            null -> {
              call.response.headers.append(HttpHeaders.RetryAfter, "2")
              call.respondText(
                "render queue saturated; retry shortly",
                status = HttpStatusCode.ServiceUnavailable,
              )
            }
            RenderOutcome.Busy -> {
              // Daemon mid-render; backed off in ~DAEMON_BUSY_WAIT instead of pinning this slot.
              // Pure catalog-theme requests also use this retry signal: serving baked pixels with
              // a successful status would leave that thumbnail on the wrong theme permanently.
              call.response.headers.append(HttpHeaders.RetryAfter, "2")
              call.respondText(
                "render busy; retry shortly",
                status = HttpStatusCode.ServiceUnavailable,
              )
            }
            is RenderOutcome.Ok -> {
              // Baked pixels answering an override-bearing request are pixels that do NOT reflect
              // the override — byte-identical to the un-overridden snapshot, under a 200 (#3449).
              // Every other generation is a real render keyed by these overrides.
              val dropped =
                droppedOverridesFor(renderHost, outcome.generation, previewId, overrides)
              if (dropped.isNotEmpty()) {
                respondDroppedOverrides(
                  renderHost,
                  previewId,
                  dropped,
                  overrides,
                  outcome.png,
                  ContentType.Image.PNG,
                  outcome.generation,
                )
              } else {
                markGeneration(
                  outcome.generation.wire,
                  if (
                    outcome.generation == RenderOutcome.Generation.BAKED &&
                      overrideParams.isEmpty() &&
                      isPublic
                  ) {
                    // A frame URL that names its generation is content-addressed: these exact
                    // bytes are what it answers with for as long as it resolves at all, because a
                    // republish moves the page's generation and therefore the URL. That is what
                    // lets it take the `immutable` lifetime the other content-addressed lanes take
                    // — and, more to the point, what stops it outliving the page that drew it.
                    //
                    // An unscoped URL keeps the old short public lifetime. It is still the moving
                    // target it always was; the fix for that is to have the page name a
                    // generation, not to cache the ambiguity for longer.
                    if (carriesCurrentGeneration(renderHost)) prebakedImageCacheControl(isPublic)
                    else STATIC_RESOURCE_CACHE_CONTROL
                  } else {
                    DYNAMIC_RESOURCE_CACHE_CONTROL
                  },
                )
                call.respondBytes(outcome.png, ContentType.Image.PNG)
              }
            }
            RenderOutcome.NotFound ->
              call.respondText("no such preview", status = HttpStatusCode.NotFound)
            is RenderOutcome.Failed ->
              call.respondText(outcome.reason, status = HttpStatusCode.InternalServerError)
          }
        }
      }
    }
  }

  /**
   * The validated overrides a [generation] artifact for [previewId] does **not** reflect — empty
   * unless the bytes came straight off a published bundle ([RenderOutcome.Generation.BAKED] — no
   * renderer was involved in this request). Every other generation is a real render keyed by these
   * overrides, cache hit or not.
   */
  /**
   * [previewId]'s published render by the player a **bare** `?rcPlayer=` names, as a
   * [RenderOutcome] for the pre-admission `cached` chain — or null when this request is not that.
   *
   * The catalog's offline parity run already drew every `ir/<id>.rc` document with every player, so
   * the commonest Remote Compose page view there is — a viewer opening on its default player, with
   * nothing else selected — is answerable from published bytes. Before this it went to the daemon:
   * `?rcPlayer=cmp-android` measured ~0.75s warm on a warm public box, and on a cold one it fell
   * back to baked pixels and refused. Note the catalog's ordinary baked PNG cannot stand in,
   * because it is the **Java** player's capture — the reference the other lanes are scored against.
   *
   * "Bare" is the whole safety condition. Any other override — a font scale, a device, a knob, a
   * theme — asks for pixels the parity run never drew, so the player selection is stripped and what
   * remains must be something the baked snapshot would itself satisfy
   * ([CatalogLiveRouting.overridesAffectRender]). Otherwise this returns null and the request
   * routes to the renderer exactly as before.
   */
  private fun publishedRcPlayerRender(
    renderHost: ServeHost,
    previewId: String,
    overrides: PreviewOverrides,
  ): RenderOutcome.Ok? {
    val rc = overrides.remoteCompose ?: return null
    val player = rc.player ?: return null
    val backend = RcPlayerBackend.entries.firstOrNull { it.playerKind == player } ?: return null
    // Everything the request asks for beyond "draw it with this player".
    val withoutPlayer =
      overrides.copy(
        remoteCompose =
          rc.copy(player = null).takeIf { it.profile != null || it.namedValues.isNotEmpty() }
      )
    if (CatalogLiveRouting.overridesAffectRender(previewId, withoutPlayer)) return null
    val bytes = renderHost.publishedRcPlayerRender(previewId, backend) ?: return null
    return RenderOutcome.Ok(bytes, RenderOutcome.Generation.RC_PUBLISHED)
  }

  private fun droppedOverridesFor(
    renderHost: ServeHost,
    generation: RenderOutcome.Generation,
    previewId: String,
    overrides: PreviewOverrides,
  ): List<String> =
    if (generation == RenderOutcome.Generation.BAKED) {
      CatalogLiveRouting.droppedOverrideNames(previewId, overrides)
    } else if (renderHost.hasRemoteComposeDoc(previewId)) {
      // A real render happened — and still could not apply everything, because this preview is
      // replayed from its captured document rather than recomposed. See
      // [CatalogLiveRouting.irReplayDroppedOverrideNames] for which axes that costs and why the
      // list is narrow.
      CatalogLiveRouting.irReplayDroppedOverrideNames(previewId, overrides)
    } else {
      emptyList()
    }

  /**
   * The dropped overrides a refusal for [previewId] must call **terminal** — the axes retrying can
   * never apply, because a replay has no composition to re-run, today or in a minute. Exactly
   * [CatalogLiveRouting.irReplayDroppedOverrideNames], and empty for a preview that recomposes.
   *
   * Terminality is a property of the **axis**, not of the preview, and conflating the two is the
   * bug this replaced. The old predicate was "does this preview carry a captured document?", which
   * is true of *every* Remote Compose preview — including the ones whose daemon twin renders the
   * axis perfectly well. So a transient baked fallback (a cold daemon, a busy one, no free seat) on
   * e.g. `?rcPlayer=java` was answered `409` + "the override can never apply", when the same URL
   * against the warm daemon is a `200`. The viewer treats `409` as final, so it gave up on a lane
   * that was seconds from working, and the message told the visitor something false about it.
   *
   * `rcPlayer` is the sharpest case: choosing which player replays the document is precisely what
   * the replay path reads, so it is never terminal — `IrReplayDroppedOverridesTest` has asserted
   * that about the predicate all along; only this caller disagreed.
   */
  private fun terminalDroppedOverrides(
    renderHost: ServeHost,
    previewId: String,
    overrides: PreviewOverrides,
  ): List<String> =
    if (isReplayedPreview(renderHost, previewId)) {
      CatalogLiveRouting.irReplayDroppedOverrideNames(previewId, overrides)
    } else {
      emptyList()
    }

  /**
   * The declared themes [previewId] can actually be rendered under: all of them when it recomposes,
   * else only those with a published replay mapping.
   *
   * Offering a theme no lane can apply is the failure this filter exists for: the control looks
   * live, the click 409s, and the visitor is told the preview "can't render live" for a theme the
   * catalog advertised.
   */
  private fun applicableThemes(renderHost: ServeHost, previewId: String): List<ServeTheme> =
    if (isReplayedPreview(renderHost, previewId)) renderHost.replayableThemes()
    else renderHost.declaredThemes

  /**
   * The declared themes a **page showing many previews** can offer — the union over its previews,
   * since one control drives cards of both kinds.
   *
   * A union is only honest paired with a per-card gate, and it is [everyThemeApplies] that carries
   * it: a mixed catalog offering a theme mapped for its replayed cards and a second one that isn't
   * would otherwise light up every chip on every card, and the unmapped chip would 409 the replayed
   * ones. Per-theme narrowing isn't expressible per card here, so a replayed card that can't take
   * the whole offered set is gated out of the control entirely rather than into an error.
   */
  private fun applicableThemes(renderHost: ServeHost): List<ServeTheme> =
    if (renderHost.previews.any { !isReplayedPreview(renderHost, it.id) }) {
      renderHost.declaredThemes
    } else {
      renderHost.replayableThemes()
    }

  /** Whether [previewId] can be rendered under every theme [applicableThemes] offers this page. */
  private fun everyThemeApplies(renderHost: ServeHost, previewId: String): Boolean =
    applicableThemes(renderHost, previewId)
      .map { it.providerFqn }
      .containsAll(applicableThemes(renderHost).map { it.providerFqn })

  /**
   * Whether [previewId] is redrawn by **replaying** its captured document rather than by
   * recomposing. A per-preview fact about the lane, and the axis the theme controls narrow on — not
   * a statement that a refusal is final, which is [terminalDroppedOverrides]' job.
   */
  private fun isReplayedPreview(renderHost: ServeHost, previewId: String): Boolean =
    ServeThemeReplay.isReplayed(renderHost, previewId)

  /**
   * [ServeThemeReplay.expand], as a member so the render handlers read the same way they did when
   * the expansion lived here. The logic moved out because the WebSocket lanes need it too — see
   * [ServeThemeReplay].
   */
  private fun expandThemeProvider(
    renderHost: ServeHost,
    previewId: String,
    params: Map<String, String>,
  ): ServeThemeReplay.Seeding = ServeThemeReplay.expand(renderHost, previewId, params)

  /**
   * Answer a render whose **validated overrides were not applied** — [bytes] are the preview's
   * baked artifact, produced without a renderer, while the request asked for [dropped]. Shared by
   * the PNG and SVG lanes so the two can't drift: a vector read off the delivery branch ignores a
   * `fontScale` exactly as thoroughly as a baked PNG does.
   *
   * The old behaviour was `200` with those bytes, which is a wrong answer delivered confidently:
   * the response is indistinguishable from a render where the override genuinely changed nothing,
   * so a diff bot, a parity check, or an agent iterating on a theme concludes "no visual
   * difference" for an artifact that was never rendered (#3449). Every override kind agrees here —
   * `fontScale`, `uiMode`, and `themeProvider` alike — rather than one path 503ing while the
   * commoner ones quietly returned the snapshot.
   *
   * Three outcomes, all naming the dropped params in [DROPPED_OVERRIDES_HEADER] so even a `curl -I`
   * can tell:
   * - `?fallback=baked` — the caller explicitly accepted the snapshot (the viewer asks for this
   *   when it would rather show published pixels than a broken image). 200, plus
   *   `X-Compose-Preview-Render: baked-fallback`, since the bytes carry no signal of their own.
   * - the preview HAS a live lane, just not right now (daemon down, cold, no free seat) — 503 +
   *   `Retry-After`, matching what a pure `themeProvider` request already returned in this state. A
   *   replayed preview reaches this branch too, for every axis its document can answer: being
   *   replayed is not by itself a reason retrying won't help (see [refuseDroppedOverrides]).
   * - the preview has NO live lane at all (a static/untrusted catalog, an unmapped Android-only
   *   variant), or the request names an axis no replay can ever honour — 409: retrying can't help,
   *   and the viewer already treats 409 as terminal.
   */
  private suspend fun RoutingContext.respondDroppedOverrides(
    renderHost: ServeHost,
    previewId: String,
    dropped: List<String>,
    overrides: PreviewOverrides,
    bytes: ByteArray,
    contentType: ContentType,
    generation: RenderOutcome.Generation,
  ) {
    if (acceptsBakedFallback()) {
      markDroppedOverrides(dropped)
      markGeneration(generation.wire, DYNAMIC_RESOURCE_CACHE_CONTROL)
      call.respondBytes(bytes, contentType)
      return
    }
    refuseDroppedOverrides(renderHost, previewId, dropped, overrides)
  }

  /**
   * Whether this request selects a Remote Compose player and asks for **nothing else** — read off
   * the raw query, for the one caller that runs before [ServeOverrides.parse].
   *
   * The same condition the parsed-override path applies: anything beyond the player selection wants
   * pixels the offline parity run never drew, so it must reach the renderer.
   */
  private fun RoutingContext.bareRcPlayerRequest(): Boolean =
    call.request.queryParameters.entries().none { (key, _) ->
      ServeOverrides.isOverrideParam(key) && key != "rcPlayer"
    }

  /** Whether the caller passed `?fallback=baked` — an explicit "serve the snapshot anyway". */
  private fun RoutingContext.acceptsBakedFallback(): Boolean =
    call.request.queryParameters[FALLBACK_PARAM]?.lowercase() == FALLBACK_BAKED

  /**
   * Name the un-applied overrides on a response that carries the baked artifact regardless — the
   * accepted `?fallback=baked`, and the Storybook isolation pages, whose consumers asked for an
   * HTML wrapper this server has no refusal shape for beyond a status. A no-op when nothing was
   * dropped, so an honest render carries no stray header.
   */
  private fun RoutingContext.markDroppedOverrides(dropped: List<String>) {
    if (dropped.isEmpty()) return
    call.response.headers.append(DROPPED_OVERRIDES_HEADER, dropped.joinToString(","))
    call.response.headers.append(RENDER_HEADER, RENDER_BAKED_FALLBACK)
  }

  /**
   * The refusal half of [respondDroppedOverrides]: 409 when at least one dropped axis is
   * **terminal**, else 503 when a live lane exists, else 409 because there is no lane at all.
   *
   * The terminal test is per-axis ([terminalDroppedOverrides]), not per-preview. It used to be the
   * latter — "this preview replays a captured document" — which is true of every Remote Compose
   * preview and so swallowed the 503 branch entirely for them: a cold or busy daemon fell back to
   * baked pixels, and the visitor was told the override "can never apply" about a lane that answers
   * `200` once warm. A 409 is final to the viewer, so it stopped retrying a lane that was about to
   * work.
   *
   * One terminal axis decides the whole response even when retryable ones ride along: the request
   * as written can never be satisfied in full, so `Retry-After` would be an invitation to loop. The
   * message names the terminal subset rather than everything dropped, so the reason given is about
   * the axis that is actually hopeless.
   */
  private suspend fun RoutingContext.refuseDroppedOverrides(
    renderHost: ServeHost,
    previewId: String,
    dropped: List<String>,
    overrides: PreviewOverrides,
  ) {
    call.response.headers.append(DROPPED_OVERRIDES_HEADER, dropped.joinToString(","))
    val params = dropped.joinToString(", ")
    val terminal = terminalDroppedOverrides(renderHost, previewId, overrides)
    if (terminal.isNotEmpty()) {
      call.respondText(
        "override not applied: ${terminal.joinToString(", ")} — this preview is replayed from its " +
          "captured document, which cannot be recomposed, so the override can never apply; add " +
          "&$FALLBACK_PARAM=$FALLBACK_BAKED to accept the published snapshot (which ignores it)",
        status = HttpStatusCode.Conflict,
      )
    } else if (renderHost.canRenderOverridesFor(previewId)) {
      call.response.headers.append(HttpHeaders.RetryAfter, "2")
      call.respondText(
        "override not applied: $params — this preview's live render lane is unavailable; " +
          "retry shortly, or add &$FALLBACK_PARAM=$FALLBACK_BAKED to accept the baked snapshot " +
          "(which ignores the override)",
        status = HttpStatusCode.ServiceUnavailable,
      )
    } else {
      call.respondText(
        "override not applied: $params — this preview has no live render lane, so only its baked " +
          "snapshot can be served; add &$FALLBACK_PARAM=$FALLBACK_BAKED to accept it (which " +
          "ignores the override)",
        status = HttpStatusCode.Conflict,
      )
    }
  }

  /**
   * cmp-jvm lane of [handleRender]: render the captured document with the embedded desktop player
   * in an isolated subprocess and respond with [format]. Load-shed through the same
   * [renderSemaphore] as the daemon lane. 404 when the preview carries no captured doc / render
   * spec; 503 when the desktop player sidecar isn't installed or the queue is saturated; 500 when
   * the player could not produce the artifact.
   */
  private suspend fun RoutingContext.renderCmpJvmResponse(
    renderHost: ServeHost,
    previewId: String,
    format: RcJvmServerRenderer.Format,
    webMode: Boolean,
    sessionId: String,
  ) {
    val doc = renderHost.remoteComposeDoc(previewId)
    val spec = renderHost.remoteComposeRenderSpec(previewId)
    if (doc == null || spec == null) {
      call.respondText("no cmp-jvm render for this preview", status = HttpStatusCode.NotFound)
      return
    }
    // Apply the live `rc.<name>=…` knob edits the viewer sends, leniently parsed (a malformed seed
    // drops to the authored default rather than failing the render) — the server-side counterpart
    // of
    // the JS lane's in-browser knob application.
    val seeds =
      ServeOverrides.rcNamedValueSeeds(
        expandThemeProvider(
            renderHost,
            previewId,
            call.request.queryParameters.entries().associate { (key, values) ->
              key to (values.firstOrNull() ?: "")
            },
          )
          .params
      )
    // `?uiMode=dark` selects the document's dark `ColorTheme` branch. This lane replays stored
    // `.rc`
    // bytes rather than waking the daemon, so the mode is a *player* setting here — it is the only
    // thing `uiMode` can mean for an already-captured document, and without it a dark request would
    // silently render the light branch.
    val theme =
      cmpJvmRenderTheme(
        call.request.queryParameters["uiMode"],
        renderHost.previews.firstOrNull { it.id == previewId }?.uiMode ?: 0,
        ServeWeb.SystemDisplay.resolveDarkFirst(
          sessionId,
          catalogBundleHost(renderHost)?.stageSurface,
        ),
      )
    val result =
      withContext(Dispatchers.IO) {
        if (!renderSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
          null
        } else {
          try {
            RcJvmServerRenderer.render(doc, spec, seeds, format, theme)
          } finally {
            renderSemaphore.release()
          }
        }
      }
    when (result) {
      null -> {
        call.response.headers.append(HttpHeaders.RetryAfter, "2")
        call.respondText(
          "render queue saturated; retry shortly",
          status = HttpStatusCode.ServiceUnavailable,
        )
      }
      is RcJvmServerRenderer.RenderResult.Ok -> {
        call.response.headers.append(HttpHeaders.CacheControl, DYNAMIC_RESOURCE_CACHE_CONTROL)
        val bytes =
          if (format == RcJvmServerRenderer.Format.SVG && webMode) {
            webModeSvg(result.bytes.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
          } else {
            result.bytes
          }
        val contentType =
          if (format == RcJvmServerRenderer.Format.SVG) {
            ContentType.parse(ComposeFigmaSvgProduct.MEDIA_TYPE_SVG)
          } else {
            ContentType.Image.PNG
          }
        call.respondBytes(bytes, contentType)
      }
      is RcJvmServerRenderer.RenderResult.Unavailable -> {
        // The chip is only offered when the sidecar is present, so this is a torn-down install
        // rather than a user error; a retryable 503 with the search paths beats a hard 500.
        call.response.headers.append(HttpHeaders.RetryAfter, "5")
        call.respondText(result.reason, status = HttpStatusCode.ServiceUnavailable)
      }
      is RcJvmServerRenderer.RenderResult.Failed ->
        call.respondText(result.reason, status = HttpStatusCode.InternalServerError)
    }
  }

  /**
   * Whether *this* request is a signed-in one — it presented a valid session cookie. The input that
   * decides between [SIGNED_IN_PAGE_CACHE_CONTROL] and [ANON_PAGE_CACHE_CONTROL], and the reason
   * both cache-control helpers are request-scoped rather than server-scoped: "this server has auth
   * configured" is not the same claim as "this response is personal", and only the second one
   * justifies refusing to store it.
   */
  /**
   * Whether this request should count as somebody *looking at* the page.
   *
   * False for a HEAD. [io.ktor.server.plugins.autohead.AutoHeadResponse] answers a HEAD by running
   * the GET pipeline and discarding the body, so without this the view tallies would count the
   * probe an unfurler sends before it fetches — and then count the fetch too, double-counting every
   * shared link and inflating the numbers with traffic that never rendered a pixel for anyone.
   */
  private fun RoutingContext.isViewRequest(): Boolean = call.request.local.method != HttpMethod.Head

  /**
   * Refuse a `HEAD` on a lane whose GET handler does real work, answering `405` + `Allow: GET`
   * instead. True when the request was refused and the caller must return.
   *
   * [io.ktor.server.plugins.autohead.AutoHeadResponse] answers a HEAD by running the **whole** GET
   * handler and discarding the body, which is exactly right for a page or a baked PNG and exactly
   * wrong here. `HEAD /bundle.zip` would render every preview in the catalog and pack the zip only
   * to throw it away, so on a public host an unauthenticated `curl -I` — or a link checker, or an
   * uptime monitor — could cold-start a catalog's daemons and burn its render capacity repeatedly
   * while downloading nothing.
   *
   * Scoped to the work lanes, deliberately not applied by default: the whole point of installing
   * the plugin is that an unfurler's probe of a page and its `og:image` succeeds, and both of those
   * are a map lookup or a file read. A caller that wants the bytes can still GET; nothing shares
   * links to a zip and expects a card.
   */
  /**
   * Whether the request's query names any render override (`fontScale`, `device`, `themeProvider`,
   * `knob.<key>`, …) — i.e. whether the response could be anything other than the published pixels.
   *
   * Deliberately the cheap key-level test rather than [ServeOverrides.parse]: both callers only
   * need "could this differ from the bake?", and both of them fail safe when it over-reports. A
   * render that would have replayed baked pixels anyway refuses a HEAD (the caller GETs, costing
   * nothing), and an unfurl card omits dimensions it could have declared (the fetcher measures the
   * image).
   */
  private fun RoutingContext.requestCarriesOverrides(): Boolean =
    call.request.queryParameters.entries().any { (key, _) -> ServeOverrides.isOverrideParam(key) }

  /**
   * The `/render/{name}` suffixes that are **never** a baked replay: the figma-svg export, the slot
   * / accessibility / annotation products (all daemon-produced), and the captured Remote Compose
   * document (bundle-host, read per request). Only `<id>.png` — or no suffix — serves published
   * bytes off disk.
   */
  private val DAEMON_ONLY_RENDER_SUFFIXES = listOf(".svg", ".slots", ".a11y", ".annotations", ".rc")

  /**
   * Whether `/render/{name}` names one of [DAEMON_ONLY_RENDER_SUFFIXES] — i.e. a product this route
   * has to *make*, with or without a query string. Without this, an override-free `HEAD
   * /{system}/render/{id}.svg` still ran the full handler, took the render semaphore and possibly
   * started a daemon, purely to have the body discarded.
   */
  private fun RoutingContext.wantsDaemonOnlyRenderProduct(): Boolean {
    val name = call.parameters["name"] ?: return false
    return DAEMON_ONLY_RENDER_SUFFIXES.any { name.endsWith(it) }
  }

  /**
   * Whether a bare `/render/{name}` can be answered from published bytes alone — the only case
   * where replaying the GET pipeline for a HEAD is free.
   *
   * "It ends in `.png`" is not enough. A plain [ServeRenderHost] has no bake at all, and a
   * catalog's **deferred** previews ([ServeHost.liveOnlyPreviewIds]) are published without one, so
   * those go to the daemon even with no query string — the probe would take the render semaphore
   * and start a daemon purely to have the body discarded. [ServeHost.bakedRenderSize] answers
   * exactly this question for the cost of a PNG header read, and is null for both.
   *
   * Also requires the session to be **resident**: [ServeSessionRegistry.peekHost] never resumes, so
   * a probe for a suspended catalog is refused rather than waking it. Both refusals are safe for
   * the unfurl case — an image fetcher issues a GET, and the page URLs an unfurler probes do not
   * come through this route.
   */
  private fun RoutingContext.renderWouldReplayBakedBytes(sessionInPath: Boolean): Boolean {
    // Only a HEAD pays for this lookup; a GET is going to do the work regardless.
    if (call.request.local.method != HttpMethod.Head) return true
    // A pinned render answers HEAD, which it did not before, and the reason it now can is that the
    // lane became admission-bounded: a probe costs at most one permitted branch read, and the GET
    // that follows it is served from the cache that read filled. Refusing was the wrong trade — an
    // unfurler probes an `og:image` before fetching it, so a blanket 405 dropped the preview card
    // on exactly the historical links this feature exists to share.
    // Free when the bytes are already resident; otherwise one permitted branch read, which is the
    // same thing the GET behind the probe would spend and which the cache then serves.
    if (call.request.queryParameters[ServeCatalogRevision.PARAM] != null) return true
    val name = call.parameters["name"]?.removeSuffix(".png") ?: return false
    val host = sessions.peekHost(selectedSessionId(sessionInPath)) ?: return false
    return host.bakedRenderSize(name) != null
  }

  /**
   * Serve one published animated capture.
   *
   * The extension is read off the request and matched against the closed set the host accepts, then
   * passed to it so the lookup and the `Content-Type` are decided by the same string. Anything else
   * — an unknown suffix, an id the catalog never declared — is a flat 404: this route reaches bytes
   * fetched from a delivery branch, so it must never be able to serve them under a type the
   * requester chose.
   *
   * Leased, NOT peeked. [ServeSessionRegistry.peekHost] never resumes a suspended session, so
   * peeking here answered 404 for every catalog the idle timer had put to sleep — which on a
   * long-running server is most of them, most of the time. The fixtures never caught it because a
   * test registers its catalog `pinned = true` and a pinned session is never suspended; the failure
   * only exists once an idle clock does. The lease is the same one `/render` takes for the sibling
   * still, and it costs no render seat: a capture is read off the staged branch asset, so what
   * resuming buys is the host that owns the bytes, not a daemon.
   */
  private suspend fun RoutingContext.handleMotion(sessionInPath: Boolean) {
    // Token-gated like every sibling asset lane. `/render` has always opened with this and the
    // motion lane never did — harmless-looking while the route was only reachable at
    // `/{system}/motion/…`, and not harmless at all: on a token-gated box that spelling was already
    // servable to an unauthenticated caller, and rooting the segment for site hosts would have
    // widened it to a second URL rather than introducing it.
    if (rejectBadToken()) return
    if (rejectHeadProbe()) return
    val name = call.parameters["name"].orEmpty()
    val extension = MOTION_CONTENT_TYPES.keys.firstOrNull { name.endsWith(it) }
    if (extension == null) {
      call.respondText("", status = HttpStatusCode.NotFound)
      return
    }
    val motionId = name.removeSuffix(extension)
    val outcome =
      withLeasedSessionOrNull(selectedSessionId(sessionInPath)) { host ->
        host.motionRead(motionId, extension)
      } ?: BranchFetch.NotFound
    val bytes = outcome.bytesOrNull
    if (bytes == null) {
      // A capture the catalog never published is a 404 and always was. A capture the delivery
      // branch is currently refusing us is NOT — answering 404 there tells the reader the recording
      // does not exist, which is what "The recorded interaction could not be loaded" meant for both
      // cases and what made diagnosing this lane a manual exercise. 503 with `Retry-After` says the
      // true thing to a browser, a monitor and a person reading a log alike.
      //
      // And a capture past the transport's envelope is a third thing again: it exists, it is not
      // coming, and asking again will not shrink it. `TooLarge` carries no bytes and is not
      // transient, so it lands in neither branch above by default — 404 for a file the branch is
      // holding, which is the absence-versus-refusal confusion this block exists to end, arriving
      // through the outcome added to end it elsewhere. 413 is the answer the rest of this server
      // already gives for a body past a ceiling.
      if (outcome is BranchFetch.TooLarge) {
        call.respondText(outcome.summary, status = HttpStatusCode.PayloadTooLarge)
        return
      }
      if (outcome.isTransient) {
        call.response.headers.append(
          HttpHeaders.RetryAfter,
          motionRetryAfterSeconds(outcome).toString(),
        )
        call.respondText(outcome.summary, status = HttpStatusCode.ServiceUnavailable)
      } else {
        call.respondText("", status = HttpStatusCode.NotFound)
      }
      return
    }
    // Revalidated, NOT `immutable` — and the distinction is the whole point of this block.
    //
    // Every other user of [prebakedImageCacheControl] is content-addressed: its URL carries the
    // bytes' own hash, which is what earns the year-long `immutable` promise that what the URL
    // names can never change. A capture's URL is derived from the STICKER it accompanies
    // (`motion/switch-on/ideal__default__dark.apng`), so a re-publish replaces the recording behind
    // the same path — and a client that watched the old one would have been told to keep it for a
    // year with nothing to revalidate against.
    //
    // The ETag is what makes revalidating cheap enough to be the right answer here rather than a
    // compromise: a capture is many frames, so a 304 saves far more on this route than on a still.
    val etag = "\"" + motionEtag(bytes) + "\""
    call.response.headers.append(
      HttpHeaders.CacheControl,
      if (isPublic) MOTION_CACHE_CONTROL else DYNAMIC_RESOURCE_CACHE_CONTROL,
    )
    call.response.headers.append(HttpHeaders.ETag, etag)
    if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    call.respondBytes(bytes, ContentType.parse(MOTION_CONTENT_TYPES.getValue(extension)))
  }

  /**
   * The `Retry-After` a refused capture advertises: what the branch host itself asked for when it
   * said so, else a short default. Clamped to the same ceiling the fetch policy honours, so the
   * header can never promise a wait longer than the server would itself wait.
   */
  private fun motionRetryAfterSeconds(outcome: BranchFetch): Long {
    val asked =
      when (outcome) {
        is BranchFetch.Throttled -> outcome.retryAfterSeconds
        is BranchFetch.Unavailable -> outcome.retryAfterSeconds
        else -> null
      }
    return (asked ?: MOTION_DEFAULT_RETRY_AFTER_SECONDS).coerceIn(
      1L,
      BranchFetch.MAX_RETRY_AFTER_SECONDS,
    )
  }

  private suspend fun RoutingContext.rejectHeadProbe(): Boolean {
    if (call.request.local.method != HttpMethod.Head) return false
    call.response.headers.append(HttpHeaders.Allow, HttpMethod.Get.value)
    call.respondText("", status = HttpStatusCode.MethodNotAllowed)
    return true
  }

  private fun ApplicationCall.requestIsSignedIn(): Boolean = githubAuth?.currentLogin(this) != null

  private fun RoutingContext.requestIsSignedIn(): Boolean = call.requestIsSignedIn()

  private fun ApplicationCall.pageCacheControl(): String =
    pageCacheControl(
      githubAuthConfigured = githubAuth != null,
      isPublic = isPublic,
      signedIn = requestIsSignedIn(),
    )

  private fun RoutingContext.pageCacheControl(): String = call.pageCacheControl()

  /**
   * The **major sections** of a design page, for the catalog sidebar's Pages tree.
   *
   * A specimen sheet's grouping nodes — Figma `COMPONENT_SET`s — are what a reader means by its
   * sections: the `Shape` page's grid of shapes, the `Buttons` page's rows of button families.
   * Every other node on the sheet is one concrete component, and there are hundreds of them;
   * listing those in a sidebar would rebuild the wall of rows the pane exists to avoid.
   *
   * Two guards, both about the sheet being third-party data:
   * - **Unnamed sets are dropped.** `name` is free text and may be blank; a row with no label is a
   *   row a reader cannot choose, and it would still cost a line.
   * - **Capped.** A manifest may carry up to `MAX_NODES_PER_PAGE` nodes and nothing says how many
   *   of them are sets. The cap keeps one hostile (or merely enormous) page from turning the
   *   sidebar into the thing it replaced; past it the page's own row still leads to the whole
   *   sheet, which is where every section is anyway.
   */
  private fun designPageSections(page: DesignPage): List<ServeWeb.PageSection> =
    page.nodes
      .asSequence()
      .filter { it.isContainer && it.name.isNotBlank() }
      .map { ServeWeb.PageSection(it.nodeId, it.name) }
      .take(MAX_PAGE_SECTIONS)
      .toList()

  private fun prebakedImageCacheControl(): String = prebakedImageCacheControl(isPublic)

  /**
   * SVG lane of [handleRender]: load-shed like the PNG lane, then respond the figma-svg bytes. When
   * [scroll] is set, serves the full-page (`compose/figma-svg-long`) export of a scrolling preview
   * instead of the viewport-sized one.
   */
  /**
   * The exploded-view options this request asks for, or null when it didn't ask. Reading them off
   * the raw query (rather than through `ServeOverrides`) is deliberate: like `mode=web`, these
   * describe how the produced SVG is *presented*, not what gets rendered, so they must not join the
   * override set that decides cache identity or gets reported as "dropped".
   */
  private fun RoutingContext.explodedOptions(): ExplodedSvg.Options? {
    val params = { key: String -> call.request.queryParameters[key] }
    return if (ServeExplodedSvg.enabled(params)) ServeExplodedSvg.optionsFrom(params) else null
  }

  private suspend fun RoutingContext.renderSvgResponse(
    renderHost: ServeHost,
    previewId: String,
    overrides: PreviewOverrides,
    scroll: Boolean = false,
    webMode: Boolean = false,
    exploded: ExplodedSvg.Options? = null,
  ) {
    val outcome =
      withContext(Dispatchers.IO) {
        if (!renderSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
          null
        } else {
          try {
            val produced =
              when {
                scroll -> renderHost.renderScrollSvg(previewId, overrides)
                // Web mode routes through the host's web variant: a catalog-backed host links its
                // raster crops to their published branch files instead of embedding them (the
                // default host keeps the self-contained bytes). The font-`@import` rewrite below
                // applies either way.
                webMode -> renderHost.renderSvgForWeb(previewId, overrides)
                else -> renderHost.renderSvg(previewId, overrides)
              }
            // Both post-render rewrites run with the permit still held. The `mode=web` one is a
            // regex pass over a string, but the exploded projection parses the whole SVG into a
            // DOM, structurally copies it once per sheet and re-serializes — comparable to a
            // render on a large catalog export. Outside the semaphore, a public host could be
            // asked for a hundred different `explodeTilt=` values at once and would run all of
            // them in parallel, which is precisely what this route's load shedding exists to
            // prevent. Inside it, the burst queues like any other render and sheds with a 503.
            if (produced is SvgOutcome.Ok && (webMode || exploded != null)) {
              var text = produced.svg.toString(Charsets.UTF_8)
              if (webMode) text = webModeSvg(text)
              if (exploded != null) text = ExplodedSvg.render(text, exploded)
              produced.copy(svg = text.toByteArray(Charsets.UTF_8))
            } else produced
          } finally {
            renderSemaphore.release()
          }
        }
      }
    when (outcome) {
      null -> {
        call.response.headers.append(HttpHeaders.RetryAfter, "2")
        call.respondText(
          "render queue saturated; retry shortly",
          status = HttpStatusCode.ServiceUnavailable,
        )
      }
      is SvgOutcome.Ok -> {
        // The render host produces the self-contained (embedded) SVG and caches it; the web and
        // exploded variants are per-response rewrites of those bytes, so every mode shares one
        // render + cache. Both were already applied above, inside the semaphore — web mode first,
        // since it rewrites the `@font-face` block the exploded view then carries through
        // untouched, whereas the reverse order would have it hunting for that block inside a
        // reserialized document.
        val svg = outcome.svg
        val contentType = ContentType.parse(ComposeFigmaSvgProduct.MEDIA_TYPE_SVG)
        // The vector lane drops overrides exactly like the PNG one: a `figma/<slug>.svg` read off
        // the delivery branch was drawn at the preview's discovery-time axes, so serving it for a
        // `?fontScale=2.0` export is the same silent wrong answer (#3449). `?scroll=long` is
        // daemon-only (a bundle has no full-page vector), so it never lands here baked.
        val dropped = droppedOverridesFor(renderHost, outcome.generation, previewId, overrides)
        if (dropped.isNotEmpty()) {
          respondDroppedOverrides(
            renderHost,
            previewId,
            dropped,
            overrides,
            svg,
            contentType,
            outcome.generation,
          )
        } else {
          markGeneration(outcome.generation.wire)
          call.respondBytes(svg, contentType)
        }
      }
      SvgOutcome.NotFound -> call.respondText("no such preview", status = HttpStatusCode.NotFound)
      is SvgOutcome.Failed ->
        call.respondText(outcome.reason, status = HttpStatusCode.InternalServerError)
    }
  }

  /** Slots lane of [handleRender]: load-shed like the PNG lane, then respond the slots JSON. */
  private suspend fun RoutingContext.renderSlotsResponse(
    renderHost: ServeHost,
    previewId: String,
    overrides: PreviewOverrides,
  ) {
    val outcome =
      withContext(Dispatchers.IO) {
        if (!renderSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
          null
        } else {
          try {
            renderHost.renderSlots(previewId, overrides)
          } finally {
            renderSemaphore.release()
          }
        }
      }
    when (outcome) {
      null -> {
        call.response.headers.append(HttpHeaders.RetryAfter, "2")
        call.respondText(
          "render queue saturated; retry shortly",
          status = HttpStatusCode.ServiceUnavailable,
        )
      }
      is SlotsOutcome.Ok -> call.respondBytes(outcome.json, ContentType.Application.Json)
      SlotsOutcome.NotFound -> call.respondText("no such preview", status = HttpStatusCode.NotFound)
      is SlotsOutcome.Failed ->
        call.respondText(outcome.reason, status = HttpStatusCode.InternalServerError)
    }
  }

  /**
   * Accessibility lane of [handleRender]: load-shed like the PNG lane, then respond the merged
   * `a11y/hierarchy` + `a11y/atf` + `a11y/touchTargets` JSON the viewer's overlay + legend read.
   * Like the slots lane this can force a daemon re-render (the products are only written by an
   * `a11y`-mode render), so it goes through the same render admission.
   */
  private suspend fun RoutingContext.renderA11yResponse(
    renderHost: ServeHost,
    previewId: String,
    overrides: PreviewOverrides,
  ) {
    val outcome =
      withContext(Dispatchers.IO) {
        if (!renderSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
          null
        } else {
          try {
            renderHost.renderA11y(previewId, overrides)
          } finally {
            renderSemaphore.release()
          }
        }
      }
    when (outcome) {
      null -> {
        call.response.headers.append(HttpHeaders.RetryAfter, "2")
        call.respondText(
          "render queue saturated; retry shortly",
          status = HttpStatusCode.ServiceUnavailable,
        )
      }
      is A11yOutcome.Ok -> call.respondBytes(outcome.json, ContentType.Application.Json)
      A11yOutcome.NotFound -> call.respondText("no such preview", status = HttpStatusCode.NotFound)
      is A11yOutcome.Failed ->
        call.respondText(outcome.reason, status = HttpStatusCode.InternalServerError)
    }
  }

  /**
   * Design-annotation lane of [handleRender]: load-shed like the PNG lane, then respond the
   * typography + theme inspection layers the viewer draws over the frame.
   */
  private suspend fun RoutingContext.renderAnnotationsResponse(
    renderHost: ServeHost,
    previewId: String,
    overrides: PreviewOverrides,
  ) {
    val outcome =
      withContext(Dispatchers.IO) {
        if (!renderSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
          null
        } else {
          try {
            renderHost.renderAnnotations(previewId, overrides)
          } finally {
            renderSemaphore.release()
          }
        }
      }
    when (outcome) {
      null -> {
        call.response.headers.append(HttpHeaders.RetryAfter, "2")
        call.respondText(
          "render queue saturated; retry shortly",
          status = HttpStatusCode.ServiceUnavailable,
        )
      }
      is AnnotationsOutcome.Ok -> call.respondBytes(outcome.json, ContentType.Application.Json)
      AnnotationsOutcome.NotFound ->
        call.respondText("no such preview", status = HttpStatusCode.NotFound)
      is AnnotationsOutcome.Failed ->
        call.respondText(outcome.reason, status = HttpStatusCode.InternalServerError)
    }
  }

  /**
   * The persistent frame lane, shared by `WS /ws/{name}` (query `?session=`) and `WS
   * /{system}/ws/{name}` (path — the `{system}` segment IS the session). Token is checked
   * post-handshake (can't 404 after upgrade) — a bad token closes immediately.
   */
  private suspend fun DefaultWebSocketServerSession.serveStreamLane() {
    if (!call.isAuthorizedCall()) {
      close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
      return
    }
    // Same rule as [rejectMissingGithubAuth], restated because a socket can't be redirected to a
    // sign-in — and in the same order, for the same reason: a presented grant is judged on its own
    // scope whether or not this box configures GitHub auth.
    val socketGrant = agentGrantFor(call)
    if (socketGrant != null) {
      if (!socketGrant.allows(AgentGrantScope.LIVE)) {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "live not approved for this grant"))
        return
      }
    } else if (githubAuth?.isAuthenticated(call) == false) {
      close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "github auth required"))
      return
    }
    val sessionId =
      call.parameters["system"]
        // Same precedence as [selectedSessionId]: on a site host the origin decides, so a
        // `?session=` on the socket URL can't stream a catalog the site doesn't publish.
        ?: call.siteSystem()
        ?: call.request.queryParameters["session"]
        ?: defaultSessionId
    // Reserve live-seat permits BEFORE opening the session: leasing resumes a suspended/forked
    // host,
    // which spawns the JVM render daemon, so a post-lease check would let an over-budget burst
    // spawn
    // the very daemons this budget bounds. A known-static (pinned bundle/catalog) session takes no
    // permit (weight 0); a daemon-backed one charges its backend weight (desktop 1, Android
    // heavier),
    // read from the session state without opening the daemon. A lazily-forked session (e.g.
    // --revisions), unregistered until its build runs, defaults to weight 1 — a desktop-cost
    // daemon.
    val weight = if (sessions.isKnownStatic(sessionId)) 0 else sessions.liveSeatWeight(sessionId)
    // The reservation happens before the lease, so a bogus id reaches the budget too. A refusal for
    // a session the registry doesn't have is counted separately rather than as demand: an
    // inflatable counter is not evidence, but a `--revisions` session is legitimately unknown until
    // its first lease builds it, so the number is kept rather than dropped.
    val seatTicket = liveSeats.acquire(weight, verified = sessions.isKnownSession(sessionId))
    if (seatTicket == null) {
      close(CloseReason(1013.toShort(), "live preview at capacity — try again shortly"))
      return
    }
    try {
      // Lease (not just acquire) the tenant for the socket's whole life: a fallback-lane socket
      // opens
      // no stream, so without a lease the reaper could close its host mid-connection.
      //
      // `connection = true`: this is the one hold that outlives any unit of work, so it is the one
      // that has to earn its "busy" from activity rather than from being open (#4312). Every other
      // caller takes the default request-scoped lease, which counts as busy until it is released.
      val lease = withContext(Dispatchers.IO) { sessions.lease(sessionId, connection = true) }
      if (lease == null) {
        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no such session"))
        return
      }
      try {
        val renderHost = lease.host
        val previewId = call.parameters["name"]
        if (previewId == null || renderHost.previews.none { it.id == previewId }) {
          close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no such preview"))
          return
        }
        val initialOverrides =
          call.request.queryParameters
            .entries()
            .mapNotNull { (key, values) ->
              val value = values.firstOrNull() ?: return@mapNotNull null
              if (ServeOverrides.isOverrideParam(key)) key to value else null
            }
            .toMap()
            .let { ServeWeb.SystemDisplay.normalizeOverrideParams(sessionId, it) }
        // Non-suspending hand-off to the socket; drop frames a slow client can't keep up with.
        val send: (String) -> Unit = { text -> outgoing.trySend(Frame.Text(text)) }
        // Optional stream tuning: codec (WebP is ~30–60% smaller; the daemon downgrades to PNG if
        // it
        // can't encode WebP) and an fps cap.
        val codec =
          when (call.request.queryParameters["codec"]?.lowercase()) {
            "webp" -> StreamCodec.WEBP
            "png" -> StreamCodec.PNG
            else -> null
          }
        val maxFps = call.request.queryParameters["maxFps"]?.toIntOrNull()?.takeIf { it > 0 }
        // Prefer the daemon's live stream lane (frames pushed, input dispatched); fall back to the
        // snapshot re-render lane when the backend doesn't support streaming. Capture the live
        // lane's original failure so the snapshot session can explain why input isn't live. The
        // callback fires synchronously inside tryStart (before it returns), so a plain var is safe.
        var liveUnavailableReason: String? = null
        val live =
          withContext(Dispatchers.IO) {
            ServeLiveSession.tryStart(
              renderHost,
              previewId,
              initialOverrides,
              codec,
              maxFps,
              send,
              system = sessionId,
              frameStats = liveFrameStats,
            ) { reason ->
              if (liveUnavailableReason == null) liveUnavailableReason = reason
            }
          }
        if (live != null) {
          try {
            for (frame in incoming) {
              if (frame is Frame.Text) {
                val text = frame.readText()
                // The lease keeps the session resident for the socket's whole life; THIS is what
                // says someone is using it (#4312). Without it an open-but-untouched tab reads as
                // "being served" forever and holds the theme optimizer's quiet gate shut.
                lease.touch()
                withContext(Dispatchers.IO) { live.onClientMessage(text) }
              }
            }
          } finally {
            // `NonCancellable`, because the whole point of this close is to run when the socket
            // dies — and a socket dying cancels this coroutine, which would make a plain
            // `withContext` throw on entry and skip the close entirely. A leaked live stream keeps
            // `activeStreamCount()` above zero, and the reaper never suspends a session with an
            // open stream, so the daemon and its live seat stay held for the life of the process.
            //
            // Unlike `Lease.close` this one genuinely blocks (it tears a render stream down), so
            // it keeps its IO dispatch rather than being called inline.
            withContext(Dispatchers.IO + NonCancellable) { live.close() }
          }
        } else {
          val session =
            ServeStreamSession(
              renderHost,
              previewId,
              initialOverrides,
              send,
              system = sessionId,
              liveUnavailableReason = liveUnavailableReason,
            )
          // Renders block (renderNow + await); keep them off the socket's event-loop thread.
          withContext(Dispatchers.IO) { session.onOpen() }
          for (frame in incoming) {
            if (frame is Frame.Text) {
              val text = frame.readText()
              lease.touch() // see the live lane above
              withContext(Dispatchers.IO) { session.onClientMessage(text) }
            }
          }
        }
      } finally {
        lease.close()
      }
    } finally {
      seatTicket.close()
    }
  }

  /**
   * Token gate: respond 404 (not 401 — don't confirm the server to a scanner) and return true when
   * the request's `?token=` / `X-Compose-Preview-Token` doesn't match. Constant-time compare.
   *
   * A live **agent grant** ([ServeAgentGrantStore]) presented in the same place is the other way to
   * pass, at [AgentGrantScope.PREVIEW] — the lowest rung, which every grant carries. That is the
   * whole integration: an agent presents its bearer exactly where the operator token goes, and no
   * route learns anything new.
   */
  private suspend fun RoutingContext.rejectBadToken(): Boolean {
    if (callIsAuthorized()) return false
    call.respondText("not found", status = HttpStatusCode.NotFound)
    return true
  }

  /**
   * Whether this call may see the server at all — the operator token, a public box, or a live agent
   * grant. Split out of [rejectBadToken] because the site-404 interceptor asks the same question
   * before routing, and two copies of an authorisation rule is one copy too many.
   */
  private fun RoutingContext.callIsAuthorized(): Boolean = call.isAuthorizedCall()

  private fun ApplicationCall.isAuthorizedCall(): Boolean {
    val provided = request.queryParameters["token"] ?: request.headers[TOKEN_HEADER]
    if (isAuthorized(serverToken, provided, isPublic)) return true
    return agentGrantFor(this)?.allows(AgentGrantScope.PREVIEW) == true
  }

  /**
   * The operator's own browse token, under a name that makes its two legitimate uses obvious: an
   * authorisation compare, or a deliberate decision to put the operator's own credential into a
   * page. The second is almost never what a handler wants — see [linkToken], which is why this is
   * spelled differently from the constructor parameter rather than shadowing it.
   */
  private val serverToken: String = token

  /**
   * The credential to thread into the links, form actions and asset `src`s of a page this request
   * is about to be answered with.
   *
   * This exists because of a hole the agent-grant lane would otherwise open. On a token-gated
   * server every generated link carries `?token=<the operator's own token>` — that is simply how
   * the UI stays navigable. So the moment a grant could load *any* HTML page, it would read the
   * operator's permanent, unscoped, unrevocable credential straight out of the markup: an agent
   * handed twenty minutes of `preview` would walk away with the keys to the box, which is precisely
   * the trade this whole feature exists to abolish.
   *
   * The fix is to stop treating "the server's token" and "the token this page's links should carry"
   * as the same thing. A caller presenting a live grant gets pages wired with **their own** grant
   * token — which passes every gate they are entitled to pass, so the UI is fully navigable — and
   * everyone else gets [serverToken] exactly as before. A `--public` server puts no token in its
   * links at all, so none of this applies there.
   *
   * See also [isAuthorizedAccessParam], the one place a credential arrives in a *path* segment
   * rather than a query string, which has to accept the same two answers.
   */
  private fun RoutingContext.linkToken(): String = call.linkToken()

  private fun ApplicationCall.linkToken(): String = agentGrantFor(this)?.token ?: serverToken

  /**
   * Whether a credential arriving as a **path** segment (`/wasm-private/{access}/…`) is one this
   * server accepts. The operator's token, or a live grant that reaches [AgentGrantScope.PREVIEW] —
   * the same two answers [linkToken] can produce, because the page that builds these URLs embeds
   * whichever one its reader presented.
   */
  private fun isAuthorizedAccessParam(value: String?): Boolean =
    ServeUrls.tokensMatch(serverToken, value) ||
      agentGrants?.grantForToken(value)?.allows(AgentGrantScope.PREVIEW) == true

  /**
   * The live grant this call presents, or null. Reads the same two places the operator token is
   * read from, plus `Authorization: Bearer` — an agent's HTTP client reaches for that header
   * without being told to, and refusing it would be a papercut with no security value: the bearer
   * is checked identically wherever it arrives.
   */
  private fun agentGrantFor(call: ApplicationCall): ServeAgentGrantStore.Grant? {
    val store = agentGrants ?: return null
    val bearer =
      call.request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
        ?.substring(BEARER_PREFIX.length)
        ?.trim()
    // Each source is resolved **independently** rather than by precedence. A single `?:` chain
    // meant an unrelated `Authorization: Bearer` shadowed a real grant sitting in `?token=` — which
    // is exactly the shape `share-preview --mechanism serve` sends: the host credential in the
    // query, a GitHub token in the bearer for the upload's own gate. First source that resolves to
    // a live grant wins; one carrying something else simply does not answer.
    return sequenceOf(
        call.request.headers[TOKEN_HEADER],
        bearer,
        call.request.queryParameters["token"],
      )
      .firstNotNullOfOrNull { store.grantForToken(it) }
  }

  /**
   * The token gate for the **ingest** lanes — `POST /bundles/{name}`, `POST /docs`. Identical to
   * [rejectBadToken] except that an agent grant never satisfies it.
   *
   * The consent page tells a human that `preview` means "browse this server's catalogs and their
   * rendered previews". On a box that also opted into the ingest lanes, a `preview` grant would
   * have satisfied every `rejectBadToken()` on the server — including these — so an agent granted
   * read access could publish a document or replace a named runtime bundle. That is a mutation
   * nobody agreed to, and no wording on the page would have made it agreeable.
   *
   * Rather than growing a fourth scope for it, these lanes simply stay outside the grant system:
   * they are for a client contributing content to someone else's box, which is the operator's
   * business and not a capability an agent should be able to be *given* by this flow at all. The
   * image lane already works this way for its own reasons — it wants a real GitHub credential.
   */
  private suspend fun RoutingContext.rejectBadTokenForIngest(): Boolean {
    val provided = call.request.queryParameters["token"] ?: call.request.headers[TOKEN_HEADER]
    if (isAuthorized(serverToken, provided, isPublic) && agentGrantFor(call) == null) return false
    call.respondText("not found", status = HttpStatusCode.NotFound)
    return true
  }

  private suspend fun RoutingContext.handleWasmAsset(privateRoute: Boolean) {
    val system = call.parameters["system"]
    val privateSystem = system in privateWasmCatalogs
    if (
      (privateRoute && (!privateSystem || !isAuthorizedAccessParam(call.parameters["access"]))) ||
        (!privateRoute && privateSystem && !isPublic)
    ) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    // A site hostname serves ONE catalog's app. These constant-prefix routes bypass canonical-path
    // isolation, so enforce the same one-catalog projection here.
    val site = call.siteSystem()
    val dir = if (site != null && system != site) null else system?.let { wasmCatalogs[it] }
    if (dir == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    val segments = call.parameters.getAll("path").orEmpty().filter { it.isNotEmpty() }
    val rel = if (segments.isEmpty()) "index.html" else segments.joinToString("/")
    val base = dir.toPath().toAbsolutePath().normalize()
    val resolved = base.resolve(rel).normalize()
    if (!resolved.startsWith(base) || !resolved.toFile().isFile) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    val file = resolved.toFile()
    // The sandboxed iframe has an opaque origin, so its ES-module and Wasm requests require CORS.
    call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
    val etag = "\"${file.length().toString(16)}-${file.lastModified().toString(16)}\""
    call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=3600")
    call.response.headers.append(HttpHeaders.ETag, etag)
    if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    val bytes = withContext(Dispatchers.IO) { file.readBytes() }
    call.respondBytes(bytes, wasmContentType(file.name))
  }

  // ------------------------------------------------------------ agent grants

  /**
   * The live-grant rows for `/status`, with a revoke seal **only** when this reader is an operator.
   *
   * A grant-bearing agent that fetches `/status` sees the table (it passes the token gate, so it
   * would see the page regardless) but gets no seals, so it cannot revoke anything — including its
   * neighbours. Nothing here ever carries a token: [ServeAgentGrantStore.Grant.fingerprint] is the
   * only form of one this page knows.
   */
  private fun RoutingContext.agentGrantStatusRows(): List<ServeWeb.StatusAgentGrant> {
    // A top-level site's `/status` reports on THAT app only — every other box-wide field is already
    // filtered out of it. Grants belong to the box, not to a catalog, so on a site host they are
    // omitted rather than filtered: there is no per-site subset of them to show.
    if (siteSystem() != null) return emptyList()
    val store = agentGrants ?: return emptyList()
    val approver = agentGrantApprover(store)
    val now = System.currentTimeMillis()
    return store.activeGrants().map { grant ->
      ServeWeb.StatusAgentGrant(
        id = grant.id,
        fingerprint = grant.fingerprint,
        scopes = grant.scopes.joinToString(", ") { it.wire },
        capabilities = AgentGrantCapability.wireNames(grant.capabilities).joinToString(", "),
        label = grant.label,
        approvedBy = grant.approvedBy,
        expiresInText = AgentGrantProtocol.formatDuration(grant.secondsUntilExpiry(now)),
        revokeCsrf =
          approver?.let {
            agentGrantCsrf.seal(grant.id, it.name, ServeAgentGrants.Csrf.ACTION_DENY)
          } ?: "",
      )
    }
  }

  /**
   * Requests still waiting on a human — shown **only to an operator**, because this table is a list
   * of decisions to make and a "Review →" link straight into the approval page.
   *
   * It also solves the token-gated box's awkward moment: the agent's printed link has no `?token=`,
   * so an operator can instead reach the request from the `/status` they already have open with the
   * token in the URL, verification code and all.
   */
  private fun RoutingContext.agentGrantRequestRows(): List<ServeWeb.StatusAgentRequest> {
    if (siteSystem() != null) return emptyList()
    val store = agentGrants ?: return emptyList()
    agentGrantApprover(store) ?: return emptyList()
    val now = System.currentTimeMillis()
    return store.pendingRequests().map { request ->
      ServeWeb.StatusAgentRequest(
        id = request.id,
        userCode = request.userCode,
        label = request.label,
        client = request.client,
        requestedScope = request.requestedScope.wire,
        expiresInText = AgentGrantProtocol.formatDuration(request.secondsUntilExpiry(now)),
      )
    }
  }

  /**
   * `POST /agent-access/request` — an agent, holding nothing, asks for access.
   *
   * Ungated by design and therefore the most exposed route on the box, so what it can actually do
   * is kept deliberately small: it parks one bounded entry in a bounded map and returns two random
   * strings. It reads no session, touches no daemon, and confers nothing — a human still has to act
   * before any credential exists.
   */
  /**
   * The per-process seal on the approval form. Constructed here rather than injected because it has
   * no configuration and no lifetime beyond this server: a restart drops every grant request, so
   * seals minted before it have nothing left to protect.
   */
  private val agentGrantCsrf = ServeAgentGrants.Csrf()

  private suspend fun RoutingContext.handleAgentGrantRequest(store: ServeAgentGrantStore) {
    val permit = acquireAgentGrantPermit() ?: return
    try {
      val body =
        withContext(Dispatchers.IO) {
          call.receiveStream().use { readCapped(it, MAX_AGENT_GRANT_BYTES) }
        }
      if (body == null) {
        call.respondText("request too large", status = HttpStatusCode.PayloadTooLarge)
        return
      }
      // An empty body is the honest minimum ask — `curl -X POST .../request` should work — so it
      // means "the defaults", not "malformed".
      val text = body.decodeToString().trim()
      val parsed =
        if (text.isEmpty()) ServeAgentGrants.OpenRequest()
        else
          try {
            JSON.decodeFromString(ServeAgentGrants.OpenRequest.serializer(), text)
          } catch (e: Exception) {
            call.respondText("invalid request: ${e.message}", status = HttpStatusCode.BadRequest)
            return
          }
      val scope = AgentGrantScope.parse(parsed.scope) ?: AgentGrantScope.DEFAULT_REQUEST
      // Unknown names are dropped rather than refused — see [OpenRequest.capabilities]. The store
      // narrows what survives to this box's ceiling, so asking for `images` on a box that offers
      // none is not an error, it simply is not in the request that comes back.
      val capabilities = parsed.capabilities.mapNotNull { AgentGrantCapability.parse(it) }.toSet()
      val ttl =
        parsed.ttlSeconds.takeIf { it > 0 } ?: ServeAgentGrantStore.DEFAULT_GRANT_TTL_SECONDS
      val request =
        store.openRequest(
          label = parsed.label,
          // The address, not a name the caller chose: the approval page's "who is asking" must be
          // something the asker cannot write. The label right above it is theirs to write, and is
          // presented as such.
          // The SAME trusted-forwarding policy the rate limiter uses, not the raw socket peer.
          // Behind a reverse proxy the peer is the proxy, so "Asked from" showed Caddy's address
          // for every request on the one deployment where the line matters — a signal the approver
          // is meant to weigh, reading identically for the agent that asked and for anyone else.
          client = clientAddress(),
          requestedScope = scope,
          requestedTtlSeconds = ttl,
          requestedCapabilities = capabilities,
        )
      if (request == null) {
        call.response.headers.append(HttpHeaders.RetryAfter, "60")
        call.respondText(
          "too many pending access requests on this server; try again shortly",
          status = HttpStatusCode.TooManyRequests,
        )
        return
      }
      val origin = externalOrigin()
      call.respondText(
        JSON.encodeToString(
          ServeAgentGrants.OpenResponse.serializer(),
          ServeAgentGrants.OpenResponse(
            requestId = request.id,
            deviceSecret = request.deviceSecret,
            userCode = request.userCode,
            approveUrl = origin + ServeAgentGrants.approvalPath(request.id),
            pollUrl = origin + ServeAgentGrants.POLL_PATH,
            expiresInSeconds = request.secondsUntilExpiry(System.currentTimeMillis()),
            pollIntervalSeconds = ServeAgentGrantStore.POLL_INTERVAL_SECONDS,
            requestedScope = request.requestedScope.wire,
            requestedTtlSeconds = request.requestedTtlSeconds,
            maxScope = store.maxScope.wire,
            maxTtlSeconds = store.maxGrantTtlSeconds,
            requestedCapabilities = AgentGrantCapability.wireNames(request.requestedCapabilities),
            maxCapabilities = AgentGrantCapability.wireNames(store.maxCapabilities),
          ),
        ),
        ContentType.Application.Json,
      )
    } finally {
      permit.release()
    }
  }

  /**
   * `POST /agent-access/poll` — the agent collects the outcome, proving possession of the device
   * secret it was issued.
   *
   * Every negative answer is a 200 with a status field rather than an HTTP error, because "not yet"
   * is the expected case and a poller should not have to distinguish a pending grant from a broken
   * server by status code. An unknown id and a wrong secret give the same answer, deliberately.
   */
  private suspend fun RoutingContext.handleAgentGrantPoll(store: ServeAgentGrantStore) {
    val permit = acquireAgentGrantPermit() ?: return
    try {
      val body =
        withContext(Dispatchers.IO) {
          call.receiveStream().use { readCapped(it, MAX_AGENT_GRANT_BYTES) }
        }
      val parsed =
        try {
          JSON.decodeFromString(
            ServeAgentGrants.PollRequest.serializer(),
            body?.decodeToString()?.trim().orEmpty().ifEmpty { "{}" },
          )
        } catch (e: Exception) {
          call.respondText("invalid poll: ${e.message}", status = HttpStatusCode.BadRequest)
          return
        }
      val response =
        when (val outcome = store.poll(parsed.requestId, parsed.deviceSecret)) {
          is ServeAgentGrantStore.Poll.Pending ->
            ServeAgentGrants.PollResponse(
              status = ServeAgentGrants.PollResponse.PENDING,
              retryAfterSeconds = ServeAgentGrantStore.POLL_INTERVAL_SECONDS,
              expiresInSeconds = outcome.secondsUntilExpiry,
              message = "waiting for a human to approve this request",
            )
          is ServeAgentGrantStore.Poll.Approved ->
            ServeAgentGrants.PollResponse(
              status = ServeAgentGrants.PollResponse.APPROVED,
              token = outcome.grant.token,
              tokenHeader = TOKEN_HEADER,
              scopes = outcome.grant.scopes.map { it.wire },
              capabilities = AgentGrantCapability.wireNames(outcome.grant.capabilities),
              expiresInSeconds = outcome.grant.secondsUntilExpiry(System.currentTimeMillis()),
              approvedBy = outcome.grant.approvedBy,
              message = "approved by ${outcome.grant.approvedBy}",
            )
          is ServeAgentGrantStore.Poll.Denied ->
            ServeAgentGrants.PollResponse(
              status = ServeAgentGrants.PollResponse.DENIED,
              approvedBy = outcome.by,
              message = "the request was declined",
            )
          ServeAgentGrantStore.Poll.Expired ->
            ServeAgentGrants.PollResponse(
              status = ServeAgentGrants.PollResponse.EXPIRED,
              message = "the request expired before it was approved",
            )
          ServeAgentGrantStore.Poll.Unknown ->
            ServeAgentGrants.PollResponse(
              status = ServeAgentGrants.PollResponse.UNKNOWN,
              message = "no such access request",
            )
        }
      call.respondText(
        JSON.encodeToString(ServeAgentGrants.PollResponse.serializer(), response),
        ContentType.Application.Json,
      )
    } finally {
      permit.release()
    }
  }

  /**
   * `GET /agent-access/whoami` — what the presented bearer is, without echoing it.
   *
   * Exists so an agent can answer "do I still have access, and for how long?" without provoking a
   * 404 from a real lane and guessing at what it meant. A caller with no (or a dead) grant gets a
   * 200 with `active: false`, because that is an answer rather than an error.
   */
  private suspend fun RoutingContext.handleAgentGrantWhoami(store: ServeAgentGrantStore) {
    val grant = agentGrantFor(call)
    val response =
      if (grant == null) ServeAgentGrants.WhoamiResponse(active = false)
      else
        ServeAgentGrants.WhoamiResponse(
          active = true,
          scopes = grant.scopes.map { it.wire },
          capabilities = AgentGrantCapability.wireNames(grant.capabilities),
          expiresInSeconds = grant.secondsUntilExpiry(System.currentTimeMillis()),
          approvedBy = grant.approvedBy,
          label = grant.label,
          fingerprint = grant.fingerprint,
        )
    call.respondText(
      JSON.encodeToString(ServeAgentGrants.WhoamiResponse.serializer(), response),
      ContentType.Application.Json,
    )
  }

  /** `POST /agent-access/revoke` — an agent hands its own access back early. */
  private suspend fun RoutingContext.handleAgentGrantRevoke(store: ServeAgentGrantStore) {
    val grant = agentGrantFor(call)
    val revoked = grant != null && store.revoke(grant.id, "the agent itself")
    call.respondText(
      JSON.encodeToString(
        ServeAgentGrants.RevokeResponse.serializer(),
        ServeAgentGrants.RevokeResponse(
          revoked = revoked,
          message = if (revoked) "access revoked" else "no live grant was presented",
        ),
      ),
      ContentType.Application.Json,
    )
  }

  /**
   * `POST /agent-access/{grantId}/revoke` — the `/status` page's revoke button. Requires an
   * operator identity, exactly like approving does; a grant may not revoke another grant.
   */
  private suspend fun RoutingContext.handleAgentGrantRevokeFromStatus(store: ServeAgentGrantStore) {
    val approver = agentGrantApprover(store)
    if (approver == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    val grantId = call.parameters["grantId"].orEmpty()
    val csrf = call.receiveFormField("csrf")
    if (!agentGrantCsrf.verify(grantId, approver.name, ServeAgentGrants.Csrf.ACTION_DENY, csrf)) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    store.revoke(grantId, approver.name)
    call.respondRedirect("/status" + agentGrantTokenQuery())
  }

  /**
   * `GET /agent-access/{requestId}` — the approval page.
   *
   * Ordered authenticate-then-resolve: an anonymous caller learns nothing about whether the id is
   * real, which matters because this URL is going to be pasted into places that log it.
   */
  private suspend fun RoutingContext.handleAgentGrantPage(store: ServeAgentGrantStore) {
    val approver = agentGrantApprover(store)
    if (approver == null) {
      respondAgentGrantSignIn()
      return
    }
    val request = store.request(call.parameters["requestId"])
    if (request == null || request.state != ServeAgentGrantStore.Request.State.PENDING) {
      respondAgentGrantNotice(
        heading = "Nothing to approve",
        message =
          "That access request is not waiting for a decision — it expired, or it has already " +
            "been approved or declined. Ask the agent to request access again.",
        status = HttpStatusCode.NotFound,
      )
      return
    }
    val selectable =
      ServeAgentGrants.selectableScopes(request.requestedScope, approver, store.maxScope)
    val withheld = AgentGrantScope.upTo(request.requestedScope).filterNot { it in selectable }
    val selectableCapabilities =
      ServeAgentGrants.selectableCapabilities(
        request.requestedCapabilities,
        approver,
        store.maxCapabilities,
      )
    // Named separately from the scope's withheld list because the reason differs and the page says
    // so: a capability the agent asked for that this approver may not pass on.
    val withheldCapabilities =
      request.requestedCapabilities.filterNot { it in selectableCapabilities }
    val skin = call.siteSkin()
    markGeneration("static-page", "no-store")
    call.respondText(
      ServeWeb.agentGrantApprovalPage(
        requestId = request.id,
        userCode = request.userCode,
        label = request.label,
        client = request.client,
        requestedScope = request.requestedScope,
        requestedTtlSeconds = request.requestedTtlSeconds,
        expiresInSeconds = request.secondsUntilExpiry(System.currentTimeMillis()),
        approver = approver.name,
        selectableScopes = selectable,
        maxTtlSeconds = minOf(store.maxGrantTtlSeconds, request.requestedTtlSeconds),
        approveCsrf =
          agentGrantCsrf.seal(request.id, approver.name, ServeAgentGrants.Csrf.ACTION_APPROVE),
        denyCsrf =
          agentGrantCsrf.seal(request.id, approver.name, ServeAgentGrants.Csrf.ACTION_DENY),
        formAction = ServeAgentGrants.approvalPath(request.id) + agentGrantTokenQuery(),
        navSuffix = agentGrantTokenQuery(),
        version = SERVE_VERSION,
        siteName = skin.first,
        themeCss = skin.second,
        selectableCapabilities =
          AgentGrantCapability.entries.filter { it in selectableCapabilities },
        withheldScopes = withheld,
        withheldCapabilities = withheldCapabilities,
        withheldReason = "you do not hold it yourself on this server, so you cannot pass it on",
      ),
      ContentType.Text.Html,
    )
  }

  /**
   * `POST /agent-access/{requestId}` — the decision.
   *
   * Three locks, and the comment is here because each one alone has a hole. `SameSite=Lax` stops a
   * cross-site form post from carrying the session cookie, but says nothing about a token-gated box
   * where the credential rides in the URL. The `?token=` stops a stranger, but not a page the
   * operator was tricked into opening from a bookmark that has it. The CSRF seal binds the POST to
   * this request, this approver, and this action, and is the one that does not depend on anything
   * outside this process.
   */
  private suspend fun RoutingContext.handleAgentGrantDecision(store: ServeAgentGrantStore) {
    val approver = agentGrantApprover(store)
    if (approver == null) {
      respondAgentGrantSignIn()
      return
    }
    val requestId = call.parameters["requestId"].orEmpty()
    val form = call.receiveFormParameters()
    val action = form["action"]?.firstOrNull().orEmpty()
    val deny = action == ServeAgentGrants.Csrf.ACTION_DENY
    val seal = if (deny) form["denyCsrf"]?.firstOrNull() else form["csrf"]?.firstOrNull()
    val expected =
      if (deny) ServeAgentGrants.Csrf.ACTION_DENY else ServeAgentGrants.Csrf.ACTION_APPROVE
    if (!agentGrantCsrf.verify(requestId, approver.name, expected, seal)) {
      respondAgentGrantNotice(
        heading = "That form went stale",
        message =
          "This approval form was not issued to you, or the server restarted since it was drawn. " +
            "Open the link again and re-check the verification code.",
        status = HttpStatusCode.Forbidden,
      )
      return
    }
    if (deny) {
      // The return value matters: two operators can hold the page at once, and if one approved
      // first this denial does nothing. Saying "nothing was granted" there would hand the second
      // operator an explicit assurance that is false while the bearer is live.
      if (store.deny(requestId, approver.name)) {
        respondAgentGrantNotice(
          heading = "Access declined",
          message = "Nothing was granted. The agent has been told its request was declined.",
        )
      } else {
        respondAgentGrantNotice(
          heading = "Already decided",
          message =
            "This request was resolved before your decision arrived — most likely approved by " +
              "someone else holding the same page. Nothing was declined. If a grant is live and " +
              "you want it stopped, revoke it from the server status page.",
          status = HttpStatusCode.Conflict,
        )
      }
      return
    }
    // The approver's ticks, capped again here rather than trusted: the form is client-side state
    // and the store clamps to the request and this box's ceiling regardless, but refusing to *ask*
    // for something outside the approver's own ceiling keeps the audit line honest about what was
    // actually chosen.
    // The page posts ONE value (a radio — see [ServeWeb.agentGrantApprovalPage] for why it is not a
    // set of checkboxes), but `maxOrNull` is kept rather than `single()`: the form is client state,
    // and a caller that posts several is asking for the highest, which the ceilings then clamp.
    val ticked =
      form["scope"].orEmpty().mapNotNull { AgentGrantScope.parse(it) }.maxOrNull()
        ?: AgentGrantScope.PREVIEW
    val chosen = minOf(ticked, approver.ceiling)
    // Capabilities ARE checkboxes — they are independent, so a box per capability describes the
    // outcome honestly (the scopes' radio is the opposite case, see above). Absent means unticked
    // means not granted, which is why nothing here defaults to the request.
    val tickedCapabilities =
      form["capability"].orEmpty().mapNotNull { AgentGrantCapability.parse(it) }.toSet()
    val chosenCapabilities = tickedCapabilities intersect approver.capabilityCeiling
    val ttl =
      form["ttl"]?.firstOrNull()?.let { AgentGrantProtocol.parseDurationSeconds(it) }
        ?: ServeAgentGrantStore.DEFAULT_GRANT_TTL_SECONDS
    val grant = store.approve(requestId, approver.name, chosen, ttl, chosenCapabilities)
    if (grant == null) {
      respondAgentGrantNotice(
        heading = "Nothing to approve",
        message =
          "That access request is no longer waiting for a decision — it expired, or it was " +
            "already resolved.",
        status = HttpStatusCode.NotFound,
      )
      return
    }
    respondAgentGrantNotice(
      heading = "Access granted",
      message =
        "The agent can now use this server for " +
          AgentGrantProtocol.formatDuration(grant.secondsUntilExpiry(System.currentTimeMillis())) +
          ". You can end it early from the server status page at any time.",
      detail =
        buildString {
          append("Scopes: ${grant.scopes.joinToString(", ") { it.wire }}")
          if (grant.capabilities.isNotEmpty()) {
            val names = AgentGrantCapability.wireNames(grant.capabilities).joinToString(", ")
            append(" · also: $names")
          }
          append(" · grant ${grant.fingerprint}")
        },
    )
  }

  /**
   * Who is approving, or null when this call carries no operator identity.
   *
   * An **agent grant is never an approver**, and that falls out of the two checks rather than
   * needing its own: a GitHub session lives in a cookie an agent has no way to hold, and the
   * operator branch compares against `--token` specifically, which no minted bearer can equal.
   */
  private fun RoutingContext.agentGrantApprover(
    store: ServeAgentGrantStore
  ): ServeAgentGrants.Approver? {
    // The server's own front door comes FIRST, and a GitHub session is not a substitute for it.
    // On a **private** box that also configures OAuth, checking only the session would have let any
    // GitHub account the (by default empty) `--github-auth-users` allowlist accepts open a request
    // and approve it themselves — minting a grant into a server whose browse token they never had.
    // A private box's approver must hold that token; a `--public` box has no such door to pass.
    val provided = call.request.queryParameters["token"] ?: call.request.headers[TOKEN_HEADER]
    if (!isPublic && !ServeUrls.tokensMatch(serverToken, provided)) return null
    // …and then the identity, when there is one to have. Note neither branch can be satisfied by an
    // agent grant: a GitHub session lives in a cookie no agent holds, and the token compare above
    // is against `--token` specifically, which no minted bearer can equal. So a grant can never
    // approve or revoke another.
    val auth = githubAuth
    if (auth != null) {
      val login = auth.currentLogin(call) ?: return null
      return ServeAgentGrants.Approver.github(
        login,
        auth.hasRepositoryAccess(call),
        store.maxScope,
        store.maxCapabilities,
      )
    }
    return ServeAgentGrants.Approver.operator(store.maxScope, store.maxCapabilities)
  }

  /**
   * What to tell someone who reached an approval route without an operator identity: the GitHub
   * sign-in when there is one to offer, and otherwise the plain truth about the token — the person
   * on the other end of this link is the operator, so telling them how to present the credential
   * they already have is help, not disclosure.
   */
  private suspend fun RoutingContext.respondAgentGrantSignIn() {
    // A private box wants BOTH the browse token and an identity. Sending a visitor to OAuth when
    // the *token* is what is missing produces a loop: the callback returns to the same tokenless
    // URL, which asks for OAuth again, forever. So a missing front door is answered with
    // instructions, and only a genuinely-missing session is answered with a sign-in.
    val provided = call.request.queryParameters["token"] ?: call.request.headers[TOKEN_HEADER]
    val hasFrontDoor = isPublic || ServeUrls.tokensMatch(serverToken, provided)
    if (hasFrontDoor) {
      githubAuth?.let { auth ->
        call.respondRedirect(auth.loginPath(call))
        return
      }
    }
    respondAgentGrantNotice(
      heading = "Sign in to approve",
      message =
        "Only this server's operator can approve an access request. Open this same link from a " +
          "browser that carries the server's access token — append ?token=… to the URL — and the " +
          "approval page will appear" +
          (if (githubAuth != null) ", after signing in with GitHub." else ".") +
          " The server status page lists every waiting request with a link that already carries " +
          "the token.",
      status = HttpStatusCode.Unauthorized,
    )
  }

  private suspend fun RoutingContext.respondAgentGrantNotice(
    heading: String,
    message: String,
    detail: String = "",
    status: HttpStatusCode = HttpStatusCode.OK,
  ) {
    val skin = call.siteSkin()
    markGeneration("static-page", "no-store")
    call.respondText(
      ServeWeb.agentGrantNoticePage(
        heading = heading,
        message = message,
        detail = detail,
        navSuffix = agentGrantTokenQuery(),
        version = SERVE_VERSION,
        siteName = skin.first,
        themeCss = skin.second,
      ),
      ContentType.Text.Html,
      status,
    )
  }

  /**
   * The `?token=…` an approval page's own links and form must carry forward on a token-gated box,
   * echoing back exactly what this request presented rather than the configured value — so a page
   * reached without one never mints one into its markup.
   */
  private fun RoutingContext.agentGrantTokenQuery(): String {
    if (isPublic) return ""
    val provided = call.request.queryParameters["token"] ?: return ""
    if (!ServeUrls.tokensMatch(serverToken, provided)) return ""
    return "?token=" + WebEscaping.urlEncodeSegment(provided)
  }

  /**
   * Charge an ungated grant route against its caller's address budget. Address, not identity: these
   * are the two routes reached *before* the caller has one.
   */
  private suspend fun RoutingContext.acquireAgentGrantPermit():
    ServeRateLimiter.Decision.Admitted? {
    val limiter = agentGrantLimiter ?: return ServeRateLimiter.Decision.Admitted {}
    return when (val decision = limiter.tryAcquire(clientAddressKey())) {
      is ServeRateLimiter.Decision.Admitted -> decision
      is ServeRateLimiter.Decision.Throttled -> {
        call.response.headers.append(HttpHeaders.RetryAfter, decision.retryAfterSeconds.toString())
        call.respondText(
          "Too many requests — ${decision.reason}.",
          status = HttpStatusCode.TooManyRequests,
        )
        null
      }
    }
  }

  /**
   * Read one `application/x-www-form-urlencoded` field. Parsed here rather than through Ktor's
   * content negotiation because these forms are three fields drawn by this server for this server,
   * and the body cap is the point: a form post is not a place an unauthenticated caller should be
   * able to hand this process a megabyte.
   */
  private suspend fun ApplicationCall.receiveFormField(name: String): String? =
    receiveFormParameters()[name]?.firstOrNull()

  private suspend fun ApplicationCall.receiveFormParameters(): Map<String, List<String>> {
    val body =
      withContext(Dispatchers.IO) { receiveStream().use { readCapped(it, MAX_AGENT_GRANT_BYTES) } }
        ?: return emptyMap()
    return parseFormBody(body.decodeToString())
  }

  private suspend fun RoutingContext.rejectMissingGithubAuth(api: Boolean = false): Boolean {
    // A presented grant is judged on its own scope FIRST, and independently of whether GitHub auth
    // exists. That order is the whole point. Written the other way round — `githubAuth ?: return
    // false` first — a private box with no OAuth configured let every grant through this gate
    // unread, so a `preview` grant opened live daemon sessions the human never agreed to. The gate
    // is "is this caller allowed to run this lane", and for a grant holder the answer comes from
    // the grant, on every deployment shape.
    if (rejectGrantBelowScope(AgentGrantScope.LIVE, api)) return true
    if (agentGrantFor(call) != null) return false
    val auth = githubAuth ?: return false
    if (auth.isAuthenticated(call)) return false
    if (api) {
      call.respondText("GitHub sign-in required.", status = HttpStatusCode.Unauthorized)
    } else {
      call.respondRedirect(auth.loginPath(call))
    }
    return true
  }

  /**
   * Refuse a presented grant that does not reach [required], whatever else the request carries.
   *
   * Returns false — "nothing to say" — both when no grant was presented (the caller falls through
   * to its ordinary human gate) and when the grant is good enough. Only a grant that is *present
   * and too small* answers here, and it answers 403 rather than a sign-in redirect: an agent has no
   * browser to be redirected in, and its remedy is to ask for a wider grant, not to sign in.
   */
  private suspend fun RoutingContext.rejectGrantBelowScope(
    required: AgentGrantScope,
    api: Boolean,
  ): Boolean {
    val grant = agentGrantFor(call) ?: return false
    if (grant.allows(required)) return false
    val message =
      "This agent grant covers ${grant.scopes.joinToString(", ") { it.wire }}; " +
        "'${required.wire}' was not approved for it. Ask for a wider grant " +
        "(compose-preview auth request --scope ${required.wire})."
    if (api) {
      call.respondText(message, status = HttpStatusCode.Forbidden)
    } else {
      call.respondText(message, ContentType.Text.Plain, HttpStatusCode.Forbidden)
    }
    return true
  }

  private suspend fun RoutingContext.rejectMissingGithubRepoAccess(api: Boolean = false): Boolean {
    // Scope first, and independently of GitHub auth — see [rejectMissingGithubAuth] for why the
    // other order was a hole. `playground` is never in a default grant and can only be approved by
    // someone holding repository access themselves, so a grant that reaches it means a human with
    // this exact right said yes.
    if (rejectGrantBelowScope(AgentGrantScope.PLAYGROUND, api)) return true
    if (agentGrantFor(call) != null) return false
    val auth = githubAuth ?: return false
    if (auth.hasRepositoryAccess(call)) return false
    val message =
      "Playground requires access to ${auth.accessRepository()}. Live preview is available to any " +
        "signed-in GitHub user."
    if (api) {
      call.respondText(message, status = HttpStatusCode.Forbidden)
    } else {
      call.respondText(
        ServeWeb.notFoundPage(
          message,
          linkToken(),
          isPublic,
          unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
          version = SERVE_VERSION,
          componentBrowser = componentBrowserMode(),
          githubAuth = githubAuthStatus(),
        ),
        ContentType.Text.Html,
        HttpStatusCode.Forbidden,
      )
    }
    return true
  }

  companion object {
    private const val UI_MODE_NIGHT_MASK = 0x30
    private const val UI_MODE_NIGHT_NO = 0x10
    private const val UI_MODE_NIGHT_YES = 0x20

    /** Resolve an explicit cmp-jvm override, falling back to the preview's baked mode. */
    internal fun cmpJvmRenderTheme(
      requestedUiMode: String?,
      bakedUiMode: Int,
      darkFirst: Boolean = false,
    ): RcJvmServerRenderer.RenderTheme =
      when (requestedUiMode?.lowercase()) {
        "dark" -> RcJvmServerRenderer.RenderTheme.DARK
        "light" -> RcJvmServerRenderer.RenderTheme.LIGHT
        else -> {
          val bakedNight = bakedUiMode and UI_MODE_NIGHT_MASK
          when (bakedNight) {
            UI_MODE_NIGHT_YES -> RcJvmServerRenderer.RenderTheme.DARK
            UI_MODE_NIGHT_NO -> RcJvmServerRenderer.RenderTheme.LIGHT
            else ->
              if (darkFirst) RcJvmServerRenderer.RenderTheme.DARK
              else RcJvmServerRenderer.RenderTheme.LIGHT
          }
        }
      }

    /**
     * How many sections one page contributes to the sidebar tree. Above the number of grouping
     * nodes on the kit's densest sheet, and far below anything that would make the Pages pane the
     * wall of rows it replaced.
     */
    const val MAX_PAGE_SECTIONS = 24

    /**
     * Extensions the motion route serves, and the type each is served as. A closed map rather than
     * a suffix-to-mime derivation: the key set IS the allowlist, so one place decides both "may
     * this be served" and "as what", and the two cannot drift apart.
     *
     * APNG is deliberately `image/apng`, not `image/png`. Both decode, but the distinction is what
     * tells a browser — and a reader saving the file — that there is more here than one frame.
     */
    val MOTION_CONTENT_TYPES: Map<String, String> =
      linkedMapOf(".apng" to "image/apng", ".gif" to "image/gif")

    const val TOKEN_HEADER: String = "X-Compose-Preview-Token"

    /** `Authorization: Bearer <grant>` — the other place an agent's HTTP client puts a token. */
    private const val BEARER_PREFIX: String = "Bearer "

    /**
     * Body cap for the agent-grant routes. Two of them are ungated, and every legitimate body here
     * is a JSON object with three short fields or a three-field form — so the cap is set at what
     * those need with room to spare, and anything larger is a caller doing something else.
     */
    private const val MAX_AGENT_GRANT_BYTES = 8L * 1024

    /**
     * `application/x-www-form-urlencoded` → name → values, `+` decoded as a space.
     *
     * Hand-rolled rather than routed through Ktor's `receiveParameters` for one reason: the body is
     * already read under [readCapped], and re-reading it through content negotiation would mean
     * buffering an uncapped body first. A malformed pair is skipped rather than failing the parse —
     * the fields that matter are then simply absent, and every caller here treats absent as
     * invalid.
     */
    internal fun parseFormBody(body: String): Map<String, List<String>> {
      val out = LinkedHashMap<String, MutableList<String>>()
      for (pair in body.split('&')) {
        if (pair.isEmpty()) continue
        val index = pair.indexOf('=')
        val rawName = if (index < 0) pair else pair.substring(0, index)
        val rawValue = if (index < 0) "" else pair.substring(index + 1)
        val name = runCatching { URLDecoder.decode(rawName, StandardCharsets.UTF_8) }.getOrNull()
        val value = runCatching { URLDecoder.decode(rawValue, StandardCharsets.UTF_8) }.getOrNull()
        if (name.isNullOrEmpty() || value == null) continue
        out.getOrPut(name) { mutableListOf() }.add(value)
      }
      return out
    }

    /** Header carrying the `--admin-token` for the `/admin/catalogs` routes. */
    const val ADMIN_TOKEN_HEADER: String = "X-Compose-Preview-Admin-Token"

    /** A catalog registration is a few hundred bytes of JSON; cap it well short of a payload. */
    private const val MAX_ADMIN_BODY_BYTES = 64L * 1024

    /** Local preview source is display-only and should never make an HTTP request buffer huge. */
    private const val LOCAL_SOURCE_MAX_BYTES = 1024L * 1024

    const val GENERATION_HEADER: String = "X-Compose-Preview-Generation"

    /**
     * How a `/render` response relates to what was asked for, when that needs saying at all. Only
     * value today: [RENDER_BAKED_FALLBACK] — the caller asked for an override, accepted a baked
     * snapshot via `?fallback=baked`, and these pixels do not reflect it. Absent on an ordinary
     * render (see [GENERATION_HEADER] for how the pixels were produced).
     */
    const val RENDER_HEADER: String = "X-Compose-Preview-Render"

    /** [RENDER_HEADER] value: baked pixels standing in for a render that could not be made. */
    const val RENDER_BAKED_FALLBACK: String = "baked-fallback"

    /**
     * Comma-separated names of the validated override params a `/render` response does NOT reflect
     * (`fontScale,uiMode`, `knob.label`, …). Present on the refusals *and* on an accepted
     * `?fallback=baked` 200, because the pixels themselves carry no signal.
     */
    const val DROPPED_OVERRIDES_HEADER: String = "X-Compose-Preview-Dropped-Overrides"

    /**
     * `?fallback=baked` — opt in to the un-overridden snapshot instead of a refusal when the live
     * render lane can't honour the request. Not an override param (never reaches
     * [ServeOverrides.parse]).
     */
    const val FALLBACK_PARAM: String = "fallback"

    const val FALLBACK_BAKED: String = "baked"

    /**
     * `?chrome=catalog|dev` — a permalink that pins the Catalog / Dev presentation for one request,
     * outranking the visitor's remembered mode ([ServeWeb.INTERFACE_MODE_COOKIE]). See
     * `componentBrowserMode`.
     */
    const val CHROME_PARAM: String = "chrome"

    private const val DEFAULT_PORT_RANGE = 32

    /** Short edge/browser caching for HTML assembled entirely from published catalog metadata. */
    private const val STATIC_PAGE_CACHE_CONTROL = "public, max-age=60, stale-while-revalidate=300"

    /** Published preview paths are stable but may change when a catalog refreshes in place. */
    private const val STATIC_RESOURCE_CACHE_CONTROL =
      "public, max-age=300, stale-while-revalidate=3600"

    /** Variant renders and all token-gated responses stay out of shared and browser caches. */
    private const val DYNAMIC_RESOURCE_CACHE_CONTROL = "no-store"

    /**
     * Caching for HTML whose body depends on *who is asking* — every page on a server with
     * `--github-auth-*` configured, because they all render the sign-in chip (signed out → "Sign
     * in"; signed in → the visitor's login), and some render more besides (the live-preview auth
     * prompt, the issue-reporter's "filed as @you" tooltip).
     *
     * It cannot be [STATIC_PAGE_CACHE_CONTROL]. `public` licenses the CDN in front of the deployed
     * server to store one visitor's HTML and hand it to the next, so a signed-in visitor's login
     * leaks to strangers and a stranger's signed-out page comes back to them. And even with no
     * shared cache at all, `max-age=60, stale-while-revalidate=300` means the *browser's own* cache
     * replays the pre-sign-in HTML for a minute — served stale for five more while it revalidates
     * behind the scenes — so returning from the GitHub callback to a page visited moments earlier
     * paints it signed-out. That is the "it says I'm logged out until I hit refresh" report: a
     * reload revalidates, which is why the state looks right the moment you ask for it again.
     *
     * `no-store` rather than `no-cache` on purpose: these pages carry no ETag, so a revalidation
     * costs a full re-render anyway, and `no-store` additionally keeps the signed-out HTML out of
     * the back/forward cache, where a plain Back would otherwise resurrect it.
     */
    internal const val SIGNED_IN_PAGE_CACHE_CONTROL = "private, no-store"

    /**
     * Caching for a page on a GitHub-auth server that the request proves is **not** personal — no
     * session cookie, so the HTML is the signed-out rendering every anonymous visitor gets.
     *
     * [SIGNED_IN_PAGE_CACHE_CONTROL] used to cover this case too, and `no-store` on an anonymous
     * public page is a stronger claim than the page deserves: it tells every intermediary and every
     * link-preview service that this response must not be retained at all, which is a poor thing to
     * say about a page whose whole purpose is to be shared into a chat and unfurled.
     *
     * `max-age=0` keeps the *browser* exactly where `no-store` had it — every visit revalidates, so
     * the sign-in chip can never be replayed stale, which is the regression the constant above
     * exists to prevent. `s-maxage` licenses only shared caches, and only in combination with the
     * `Vary: Cookie` that [markGeneration] appends alongside this value: a request carrying a
     * session key is a different cache entry and never reaches these bytes. Deliberately no
     * `stale-while-revalidate` — that is precisely the directive that would let a browser paint the
     * pre-sign-in HTML after the visitor has signed in.
     *
     * One thing `no-store` did that this doesn't: block the back/forward cache. A visitor who signs
     * in and then presses Back can see the signed-out chrome until they reload. That is a cosmetic
     * wart for the few who sign in, traded against every shared link on the server being storable.
     */
    internal const val ANON_PAGE_CACHE_CONTROL = "public, max-age=0, s-maxage=300, must-revalidate"

    /**
     * Caching for an assembled HTML page. Public and auth-free ⇒ short edge caching; a token-gated
     * or signed-in response is personal and is not stored at all; an anonymous page on an auth
     * server is public bytes that shared caches may keep ([ANON_PAGE_CACHE_CONTROL]).
     *
     * [signedIn] is only consulted when auth is configured *and* the server is public. A
     * token-gated host stays on `no-store` whoever is asking: its URLs carry a credential, and
     * "nobody is signed in" says nothing about whether the response may be stored.
     */
    internal fun pageCacheControl(
      githubAuthConfigured: Boolean,
      isPublic: Boolean,
      signedIn: Boolean = true,
    ): String =
      when {
        !githubAuthConfigured ->
          if (isPublic) STATIC_PAGE_CACHE_CONTROL else DYNAMIC_RESOURCE_CACHE_CONTROL
        !isPublic || signedIn -> SIGNED_IN_PAGE_CACHE_CONTROL
        else -> ANON_PAGE_CACHE_CONTROL
      }

    /**
     * The viewer page follows [pageCacheControl]. It used to drop to `no-store` only for a
     * *live-streaming* preview under GitHub auth, on the theory that the live lane was the only
     * personalised thing on the page — but the sign-in chip is on every viewer, live or not, so the
     * non-live viewer was being cached with one visitor's identity baked in.
     */
    internal fun viewerCacheControl(
      githubAuthConfigured: Boolean,
      isPublic: Boolean,
      signedIn: Boolean = true,
      stagedCapabilitiesPending: Boolean = false,
    ): String =
      if (stagedCapabilitiesPending) DYNAMIC_RESOURCE_CACHE_CONTROL
      else pageCacheControl(githubAuthConfigured, isPublic, signedIn)

    /** Classpath location of the vendored Remote Compose player IIFE bundle (global `RC`). */
    private const val RC_PLAYER_RESOURCE = "/rc-player/bundle.js"

    /**
     * A vendored browser player bundle baked into the CLI jar: its bytes plus a content-hash ETag.
     * The bundles are fixed at build time, so a strong hash makes conditional requests cheap (a 304
     * after the cache window instead of re-downloading hundreds of KB) and stays stable across
     * restarts and replicas. [bytes] is empty when the resource is somehow absent (a broken jar) —
     * the route then 404s instead of serving nothing.
     */
    internal class PlayerAsset(val bytes: ByteArray, val etag: String)

    private val playerAssets = java.util.concurrent.ConcurrentHashMap<String, PlayerAsset>()

    /** Load (once per classpath resource) a vendored player bundle. */
    internal fun playerAsset(resource: String): PlayerAsset =
      playerAssets.computeIfAbsent(resource) { path ->
        val bytes =
          ServeHttpServer::class.java.getResourceAsStream(path)?.use { it.readBytes() }
            ?: ByteArray(0)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val etag =
          "\"" +
            bytes.size.toString(16) +
            "-" +
            digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) } +
            "\""
        PlayerAsset(bytes, etag)
      }

    /** The Remote Compose player, kept as a named handle for the preview viewer's canvas lane. */
    internal val rcPlayerBundle: ByteArray
      get() = playerAsset(RC_PLAYER_RESOURCE).bytes

    internal val rcPlayerEtag: String
      get() = playerAsset(RC_PLAYER_RESOURCE).etag

    /** Max accepted upload-body size for `POST /docs` (matches the document store's own cap). */
    private val MAX_DOC_BYTES: Long = ServeDocStore.DEFAULT_MAX_DOC_BYTES.toLong()

    /**
     * Request-body ceiling on `POST /images`, enforced as the body streams in — the store's own
     * per-image cap is the same number, but it only sees bytes that were already buffered.
     */
    private val MAX_IMAGE_BYTES: Long = ServeImageStore.DEFAULT_MAX_IMAGE_BYTES.toLong()

    /** Max accepted body size for `POST /api/{v}/compiler/run` — a snippet is small. */
    private val MAX_PLAYGROUND_BYTES: Long = 256L * 1024

    /** `1234567` → `1.2 MB`; the size line on a document page. */
    internal fun humanBytes(bytes: Int): String =
      when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "${bytes / 1024} kB"
        else -> "$bytes B"
      }

    /**
     * How long the readiness prober waits between failed render attempts before retrying (short, so
     * a daemon that's still warming latches `ready` promptly once it can render). Only matters
     * while the latch is cold; the loop exits on first success.
     */
    private const val READINESS_PROBE_RETRY_MILLIS = 2000L

    /**
     * How many recent failures a bug report carries. An issue body is read by a human: a server
     * that has been failing all week has hundreds, and the tail repeats the head. `/status` keeps
     * the full window.
     */
    private const val BUG_REPORT_FAILURE_LIMIT = 8

    /**
     * Authorisation decision for a request: open when [isPublic], otherwise the [provided] token
     * must match [token] (constant-time). Pure so the gate is unit-testable without standing up the
     * server. A bad/absent token in non-public mode is rejected (the caller 404s for obscurity).
     */
    fun isAuthorized(token: String, provided: String?, isPublic: Boolean): Boolean =
      isPublic || ServeUrls.tokensMatch(token, provided)

    /**
     * How long a `/render` request waits for a concurrency slot before getting 503 + Retry-After.
     */
    private const val RENDER_QUEUE_WAIT_SECONDS = 30L

    /** Max accepted upload-body size for `POST /bundles` (matches the store's extraction cap). */
    private const val MAX_UPLOAD_BYTES = 100L * 1024 * 1024

    /**
     * Caching for the `/hero/` lane. The file name is the content hash, so the bytes behind a URL
     * are fixed for all time — `immutable` tells the browser not to even revalidate, which is what
     * makes a repeat visit to the front door paint its imagery with zero requests. A republished
     * catalog changes the hash, hence the URL, so there is nothing to invalidate.
     */
    private const val HERO_CACHE_CONTROL = "public, max-age=31536000, immutable"

    /**
     * A published capture. Public and cacheable by a shared proxy for a few minutes, but always
     * revalidated by the client, because this route's URL is derived from the sticker the capture
     * accompanies rather than from its bytes — see [handleMotion]. On a token-gated catalog the
     * bytes follow the same `no-store` policy as every other private response.
     */
    internal const val MOTION_CACHE_CONTROL = "public, max-age=0, s-maxage=300, must-revalidate"

    /** The bytes' own hash, so a re-published capture under the same id revalidates to a miss. */
    internal fun motionEtag(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
        .take(16)

    /**
     * Caching for the site icons ([ServeSiteIcon]). Public on every server, token-gated or not:
     * these are drawn from the build's own chrome and carry nothing a token protects — and a
     * favicon request doesn't present the token anyway. A day rather than `immutable`, because
     * unlike the hashed lanes these live at well-known paths whose bytes change across a deploy.
     */
    private const val SITE_ICON_CACHE_CONTROL = "public, max-age=86400"

    /** `Retry-After` for a refused capture whose branch host named no interval of its own. */
    private const val MOTION_DEFAULT_RETRY_AFTER_SECONDS = 5L

    /** Pause length when the caller names none. Long enough to ride out a burst of traffic. */
    private const val DEFAULT_OPTIMIZER_PAUSE_MINUTES = 30L

    /**
     * Longest pause the route will take. A pause is a *deferral*, not a disable — turning the cache
     * off for good is `--no-theme-optimization`, which survives a restart and is visible in the
     * config rather than as an unexplained quiet server days later.
     */
    private const val MAX_OPTIMIZER_PAUSE_MINUTES = 24L * 60L

    /**
     * Caching for the prebaked image lanes: `/hero/` and `?thumb=` on the render lane.
     *
     * Content-addressed and therefore `immutable` — but only on a **public** server. On a
     * token-gated one those URLs carry the bearer token, and `public, immutable` would license a
     * shared proxy to keep the pixels for a year and hand them to anyone presenting the URL, long
     * after the token was revoked. Private catalog imagery is exactly what the token exists to
     * gate, so it follows the same `no-store` policy as every other private response
     * ([pageCacheControl]). The ETag is still sent; a private response simply isn't stored to
     * revalidate against.
     *
     * Pure, like [isAuthorized], so the policy is unit-testable without standing up a server.
     */
    internal fun prebakedImageCacheControl(isPublic: Boolean): String =
      if (isPublic) HERO_CACHE_CONTROL else DYNAMIC_RESOURCE_CACHE_CONTROL

    private val JSON = Json { encodeDefaults = true }

    /**
     * A compact human duration for the status page (`3d 4h`, `12m 5s`, `42s`). Deterministic given
     * [seconds], so a fixture that passes fixed inputs renders a stable golden.
     */
    internal fun formatDuration(seconds: Long): String {
      val s = seconds.coerceAtLeast(0)
      val d = s / 86_400
      val h = (s % 86_400) / 3_600
      val m = (s % 3_600) / 60
      val sec = s % 60
      return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${sec}s"
        else -> "${sec}s"
      }
    }

    /** An epoch-millis instant as `YYYY-MM-DD HH:MM UTC` for the status page's failure table. */
    internal fun formatInstant(epochMillis: Long): String {
      val dt =
        java.time.OffsetDateTime.ofInstant(
          java.time.Instant.ofEpochMilli(epochMillis),
          java.time.ZoneOffset.UTC,
        )
      return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").format(dt)
    }

    /**
     * Content type for a Wasm-app asset by extension. `application/wasm` matters: the browser's
     * `WebAssembly.instantiateStreaming` rejects a wasm served as `octet-stream`. `.mjs`/`.js` must
     * be a JS type so the ES-module loader runs.
     */
    internal fun wasmContentType(name: String): ContentType =
      when {
        name.endsWith(".html") -> ContentType.Text.Html
        name.endsWith(".mjs") || name.endsWith(".js") -> ContentType.parse("text/javascript")
        name.endsWith(".wasm") -> ContentType.parse("application/wasm")
        name.endsWith(".json") || name.endsWith(".map") -> ContentType.Application.Json
        else -> ContentType.Application.OctetStream
      }

    /**
     * Read [input] fully, or `null` once it exceeds [max] bytes (without buffering past the cap).
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

    /**
     * Pick a bindable port: try [requested], then increment up to [range] times when it's taken.
     * Probes with a short-lived [ServerSocket] on the target host; there's a small TOCTOU window
     * before Ktor binds, acceptable for a developer-facing local server. Falls back to an ephemeral
     * port (0 → OS-assigned) if nothing in the range is free.
     */
    private fun pickPort(host: String, requested: Int, range: Int): Int {
      val bindAddr = if (host == ServeUrls.ALL_INTERFACES) null else InetAddress.getByName(host)
      for (candidate in requested until (requested + range)) {
        try {
          ServerSocket(candidate, 0, bindAddr).use {
            return it.localPort
          }
        } catch (_: Exception) {
          // taken — try the next
        }
      }
      ServerSocket(0, 0, bindAddr).use {
        return it.localPort
      }
    }
  }
}

@Serializable
private data class VersionResponse(
  val schema: String = "compose-preview-serve/version/v1",
  /** The host CLI's released version ([SERVE_VERSION]). */
  val version: String,
  /**
   * The schema id the `/api/previews` + page surface speaks, so a client can feature-detect. `v2`
   * adds per-preview `overrides` plus best-effort engagement counts to `/api/previews`.
   */
  val serveSchema: String = "compose-preview-serve/v2",
  /** True when the box serves token-free (public preview server); false for a token-gated serve. */
  val public: Boolean,
)

/**
 * `GET /status.json` (and `GET /status?format=json`): the machine-readable server-status snapshot a
 * monitor or a Home Assistant REST sensor polls. Flat-ish on purpose so `status` and the grouped
 * counts (`catalogs`, `daemons`) map cleanly onto sensor states/attributes; the detail lives in the
 * `catalogList` / `runningServers` / `recentDaemonFailures` arrays.
 */
@Serializable
private data class StatusResponse(
  val schema: String = "compose-preview-serve/status/v1",
  /** The host CLI's released version ([SERVE_VERSION]). */
  val version: String,
  /** True when the box serves token-free (public preview server). */
  val public: Boolean,
  /** `ok` when there are no catalog load or recent daemon startup failures, else `degraded`. */
  val status: String,
  /** Seconds since the server started. */
  val uptimeSeconds: Long,
  val catalogs: CatalogSummaryDto,
  val daemons: DaemonSummaryDto,
  val config: ConfigDto,
  val catalogList: List<CatalogDto>,
  val runningServers: List<RunningServerDto>,
  val recentDaemonFailures: List<FailureDto>,
  /**
   * Delivery-branch read counters ([BranchFetchSnapshot]). Null until this server has read a branch
   * at all. Additive on `compose-preview-serve/status/v1`.
   *
   * This is what makes "is GitHub rate-limiting us?" a question you answer by looking rather than
   * by reproducing it with `curl` — `throttled` climbing while `notFound` holds still is a rate
   * limit, and `notFound` on its own is the ordinary case of a catalog declaring an asset a given
   * revision never published.
   */
  val branchFetch: BranchFetchSnapshot? = null,
  /**
   * Cross-catalog optimizer admission ([ThemeOptimizerAdmissionSnapshot]).
   *
   * Sits beside the per-catalog `catalogList[].themeOptimization` rows and answers what those
   * cannot: how many passes are *inside* the door versus parked at it. A box where every catalog
   * reports "running" and nothing progresses looks identical, per catalog, to a box doing fine.
   */
  val themeOptimizer: ThemeOptimizerAdmissionSnapshot? = null,
  /**
   * The theme cache's disk tier ([ThemeCacheStoreSnapshot]), or null when it is memory-only.
   *
   * Answers the question the per-catalog rows could not: whether warming is *accumulating*. A box
   * whose `cached` counts keep returning to zero and whose `themeCache` is absent is not slow — it
   * is starting over, which is what m3-catalog did 7-10 times a day before this existed.
   */
  val themeCache: ThemeCacheStoreSnapshot? = null,
  /**
   * The catalog blob cache ([CatalogBlobPoolSnapshot]), or null on a server that publishes no
   * catalogs.
   *
   * Read `persistenceConfigured` and `adopted` first — but read them for what they are. The first
   * says only that an operator named a directory, which is not proof the storage outlives the
   * container: `--catalog-cache-dir /var/cache/x` inside an image with no volume there is
   * configured and just as ephemeral. `adopted` is the evidence — blobs found at open, so non-zero
   * after a restart is the pool actually having survived one.
   *
   * `hits` here is the **aggregate** across all three lanes the pool serves — small assets, the
   * executable bundles, and the content-addressed resource pool — while `branchFetch.cached` counts
   * only the small-asset subset. So `hits` is normally the larger of the two and the gap is the
   * executable tier; they are not the same number, and reading them as one would make a healthy
   * warm start look inconsistent. What says the feature is working is either of them climbing while
   * `branchFetch.attempted` flattens across a restart.
   *
   * `blobs`/`bytes` against `maxBytes` says whether the sweeper is keeping up — both are published
   * by the last sweep rather than censused per request, so they lag a write by at most one sweep
   * interval. `corrupt` above zero says a volume is losing bytes, since every blob is named by its
   * own digest and re-verified on read.
   */
  val catalogCache: CatalogBlobPoolSnapshot? = null,
  /**
   * Server-wide render-latency roll-up across the running live daemons (see
   * [RenderPerfSnapshot.aggregate] — counts sum, `firstRenderMs` is the worst first render,
   * percentiles stay per-daemon). Null when no live daemon is up or none has stats yet. Additive on
   * `compose-preview-serve/status/v1`; per-daemon detail is on `runningServers[].renderStats`.
   */
  val renderStats: RenderPerfSnapshot? = null,
  /**
   * Live-lane frame counters across every open stream socket ([LiveFramePerfSnapshot]) — achieved
   * fps, the painted/heartbeat split, and payload bytes. Null until a live socket has opened.
   * Additive on `compose-preview-serve/status/v1`, like [renderStats], and beside it deliberately:
   * `renderStats` measures `/render` round-trips and cannot see streamed frames at all.
   */
  val liveFrames: LiveFramePerfSnapshot? = null,
  /**
   * Playground lane health, or null when the lane isn't wired. Additive on
   * `compose-preview-serve/status/v1`, like [renderStats]. See [PlaygroundHealth] for why each
   * field is here — in short, the playground can be half-up in several ways that were previously
   * invisible without shell access to the box.
   */
  val playground: PlaygroundDto? = null,
  /**
   * Agent access grants, or null when the lane isn't enabled. Additive on
   * `compose-preview-serve/status/v1`, like [renderStats] and [playground].
   *
   * Counts and fingerprints only. A monitor should be able to alert on "an agent grant is live on
   * the production box" without the alerting pipeline becoming somewhere a credential is stored.
   */
  val agentAccess: AgentAccessDto? = null,
)

@Serializable
private data class AgentAccessDto(
  /** Live grants right now. */
  val activeGrants: Int,
  /** Requests waiting for a human. */
  val pendingRequests: Int,
  /** The operator's ceiling — the most privileged scope this box will ever grant. */
  val maxScope: String,
  val maxTtlSeconds: Long,
  /** Every capability this box will grant at all. Empty unless the operator opted in. */
  val maxCapabilities: List<String> = emptyList(),
  /** One entry per live grant: fingerprint, scopes, approver, seconds left. Never a token. */
  val grants: List<AgentGrantDto> = emptyList(),
)

@Serializable
private data class AgentGrantDto(
  val fingerprint: String,
  val scopes: List<String>,
  val capabilities: List<String> = emptyList(),
  val approvedBy: String,
  val expiresInSeconds: Long,
  val label: String,
)

@Serializable
private data class PlaygroundDto(
  /** Which admission posture let the lane serve (the gate's own words). */
  val admittedBy: String,
  val sandbox: SandboxDto,
  /** True when compiles run in a jailed child rather than in the serve JVM. */
  val compilerJailed: Boolean,
  /** Concurrent jailed compiles allowed; inert unless [compilerJailed]. */
  val compileSlots: Int,
  val modes: List<ModeDto>,
  /** The runtime catalog selector (`--playground`), or null when this host pins its bundles. */
  val catalogSelector: CatalogSelectorDto? = null,
  /** The per-caller compile budget, or null when the lane is unmetered. */
  val rateLimit: RateLimitDto? = null,
  /** Authenticated single-lease incremental editing trial, or null on older/unwired lanes. */
  val editing: EditingDto? = null,
)

@Serializable
private data class EditingDto(
  val enabled: Boolean,
  val active: Boolean,
  val expiresAtEpochMs: Long? = null,
  val lastRevision: Long? = null,
  val acquisitions: Long,
  val compileAttempts: Long,
  val incrementalCompiles: Long,
  val fullFallbacks: Long,
  val lastCompileMillis: Long? = null,
)

@Serializable
private data class RateLimitDto(
  /** Callers holding a compile permit right now. */
  val activeCallers: Int,
  /**
   * Distinct callers the limiter is tracking. A number pinned near its cap on a public host is the
   * signature of a key-space spray, not of an audience.
   */
  val trackedCallers: Int,
)

@Serializable
private data class CatalogSelectorDto(
  /**
   * Catalogs the selector offers right now. Empty on a freshly started host (nothing has loaded
   * yet) and on one whose catalogs all declare a backend this host cannot render — `modes` and the
   * startup log tell those apart.
   */
  val offered: List<String>,
  /**
   * How many of them hold a resolved compile classpath, against [limit]. Null on a
   * [ServeSites]-scoped status: the count is box-wide with no per-catalog breakdown, so a scoped
   * response omits it rather than pairing a filtered [offered] with a total that contradicts it.
   */
  val resolved: Int? = null,
  /** `--playground-catalog-limit`; at [resolved] == this, a run naming a new catalog is refused. */
  val limit: Int,
)

@Serializable
private data class SandboxDto(
  val profile: String,
  /**
   * False ⇒ `none`: no jail, and **no `-Xmx`, CPU cap, or hard TTL on snippet JVMs either**. On a
   * host with a large cgroup limit that matters — an uncapped JVM sizes its default max heap at a
   * quarter of the limit.
   */
  val active: Boolean,
  /**
   * True ⇒ the configured jail could not launch on this host and was dropped; the JVM caps still
   * apply but the snippet is not contained. Look at `probe.detail` for why it couldn't launch.
   */
  val jailDropped: Boolean = false,
  val memoryMb: Int,
  val cpus: Double,
  val ttlSeconds: Long,
  /** Null when no preflight ran (token-gated host, or no sandbox configured). */
  val probe: ProbeDto? = null,
)

@Serializable
private data class ProbeDto(
  /** False ⇒ the jail could not even launch here; [detail] says why. */
  val ran: Boolean,
  val detail: String,
  /** Empty ⇒ the jail contained the preflight on every measured axis. */
  val failedChecks: List<String>,
  val egressBlocked: Boolean,
  val filesystemContained: Boolean,
  val processIsolated: Boolean,
  val workDirWritable: Boolean,
)

@Serializable
private data class ModeDto(val mode: String, val source: String, val resolved: Boolean)

@Serializable
private data class CatalogSummaryDto(
  val total: Int,
  val listed: Int,
  val unlisted: Int,
  val trusted: Int,
  val degraded: Int,
  /** Configured catalogs with a usable registered copy. */
  val loaded: Int,
  /** Configured catalogs whose latest attempt failed before any usable copy registered. */
  val failed: Int,
  /** Configured catalogs not attempted yet (normally only visible during concurrent startup). */
  val pending: Int,
)

@Serializable
private data class DaemonSummaryDto(
  /** Total known sessions (resident + suspended). */
  val known: Int,
  /** Live (daemon-backed) render sessions up right now. */
  val running: Int,
  val activeStreams: Int,
  /** Live-seat permit budget; `0` ⇒ unbounded. */
  val liveSeatsTotal: Int,
  /** Free permits; `-1` ⇒ unbounded. */
  val liveSeatsAvailable: Int,
  val liveSeatsUnbounded: Boolean,
  /**
   * The slice of [liveSeatsTotal] only the per-preview daemon lane may draw on, and how much of it
   * is free. Published because its absence is exactly what made a total starvation invisible: with
   * every general permit held by resident catalog daemons, `liveSeatsAvailable` read `0 / 8` and
   * `liveSeatRefusals` read `0` (the background path never counted one), while every
   * supplement-module preview on the box answered `503 render busy` forever.
   */
  val perPreviewSeatsTotal: Int = 0,
  val perPreviewSeatsAvailable: Int = 0,
  /**
   * Live sessions turned away for want of seats since startup, monotonic. A counter rather than a
   * gauge because a refusal is an event: [liveSeatsAvailable] beside it is a level, and on a
   * lightly-used box sampling that level almost never coincides with the pressure. Zero over a long
   * uptime is the evidence that the seat budget is comfortable; a climbing figure is what would
   * justify raising it, or evicting an idle daemon in favour of an active one.
   */
  val liveSeatRefusals: Long = 0,
  /**
   * Refusals for a session id the registry did not have — see
   * [LiveSeatLimiter.unverifiedRefusalCount]. Kept apart from [liveSeatRefusals] because anyone can
   * generate these against a public box, while on a `--revisions` box they are genuine
   * first-request demand.
   */
  val liveSeatRefusalsUnverified: Long = 0,
  /**
   * Sessions holding an open lease — see [ServeSessionRegistry.leasedSessions].
   *
   * Non-empty means those sessions are held resident: their daemons will not be suspended and the
   * `--exit-when-idle` watchdog will not fire. Normally short-lived (a WebSocket, an in-flight
   * asset fetch); an entry that persists across polls on a box serving no traffic is either an open
   * browser tab or a leaked lease, and [busyLeasedSessions] tells those two apart.
   */
  val leasedSessions: List<String> = emptyList(),
  /**
   * The holders among [leasedSessions] that have been active recently — see
   * [ServeSessionRegistry.busyLeasedSessions]. Non-empty is what makes the server-wide idle clock
   * read *busy*, which is the state that stands the theme optimizer down.
   */
  val busyLeasedSessions: List<String> = emptyList(),
)

@Serializable
private data class ConfigDto(
  val host: String,
  val port: Int,
  val allowRenderTrusted: Boolean,
  val trustStore: Boolean,
  val acceptBundles: Boolean,
  /** Whether the document lane (`POST /docs` → `/d/<id>`) is enabled on this host. */
  val acceptDocs: Boolean = false,
  /** TTL of a document permalink in seconds; `0` when the document lane is off. */
  val docTtlSeconds: Long = 0,
  /** Whether `POST /images` exists on this host at all (`--accept-images`). */
  val acceptImages: Boolean = false,
  /** TTL of an uploaded image link in seconds; `0` when the image lane is off. */
  val imageTtlSeconds: Long = 0,
  /**
   * The repository an uploader must have access to. Non-null exactly when [acceptImages] is set —
   * the lane cannot start without one, so this doubles as the answer to "gated on what?".
   */
  val imageUploadRepository: String? = null,
  /** Live uploaded images, and what they occupy — the lane's whole footprint is heap. */
  val imagesHeld: Int = 0,
  val imageBytesHeld: Long = 0,
  /** Catalog auto-refresh interval; `0` ⇒ disabled. */
  val catalogRefreshSeconds: Long,
  val maxConcurrentRenders: Int,
  /** Live-seat permit budget; `0` ⇒ unbounded. */
  val liveSeats: Int,
)

@Serializable
private data class CatalogDto(
  val id: String,
  val listed: Boolean,
  val title: String? = null,
  /**
   * [BundleVerifier.summary] verdict. For a suspended live catalog this is the last-known verdict
   * (with [metaStale] set) — null means genuinely unknown: a non-catalog session, or a catalog this
   * server has not yet had resident. Never read a null as "untrusted"; the verdict string
   * `unverified` is what says that.
   */
  val trust: String? = null,
  val previews: Int? = null,
  /** Number of catalogued previews whose published render failed. */
  val failedRenders: Int = 0,
  /** Number of catalogued previews deliberately deferred to the live render lane. */
  val deferredPreviews: Int = 0,
  /** Has a live daemon-backed render lane (running now, or a suspended live catalog). */
  val live: Boolean,
  /** A live daemon for this catalog is up right now. */
  val running: Boolean,
  val degradation: String? = null,
  val repo: String? = null,
  val branch: String? = null,
  val generatedAt: String? = null,
  /** compose-ai-tools / compose-preview version that rendered this catalog. */
  val composeAiToolsVersion: String? = null,
  /** @design-parity/catalog-export version, when that producer recorded one. */
  val designParityVersion: String? = null,
  /** Canonical catalog path (`/<id>/`). */
  val path: String,
  /**
   * This row's `title`/`trust`/`previews`/`degradation`/provenance are a **last-known snapshot**
   * taken while the catalog was resident, not a live read — its daemon is idle and `/status` never
   * resumes one. The facts are branch-derived, so a suspension doesn't invalidate them; a monitor
   * that wants only live-read rows can filter on this. Additive on
   * `compose-preview-serve/status/v1`.
   */
  val metaStale: Boolean = false,
  /** `pending`, `loaded`, `failed`, or `stale` (last good copy + latest refresh error). */
  val loadState: String = "loaded",
  /** Latest catalog fetch/parse/image error, null after a successful latest attempt. */
  val loadError: String? = null,
  val lastLoadAttemptEpochMillis: Long? = null,
  /** Server-side idle theme-cache fill progress for this catalog generation. */
  val themeOptimization: ThemeOptimizationSnapshot? = null,
  /** Bounded rendered-preview cache occupancy for this catalog generation. */
  val renderCache: CatalogRenderCacheSnapshot? = null,
)

@Serializable
private data class RunningServerDto(
  val id: String,
  val label: String,
  /** `static`, or `desktop` / `android` derived from the live-seat weight. */
  val backend: String,
  val seatWeight: Int,
  val activeStreams: Int,
  val uptimeSeconds: Long? = null,
  /**
   * Serve-side render-latency counters for this daemon's live lane ([RenderPerfSnapshot]) — cold vs
   * warm counts, first-render latency, and recent p50/p95. Null while no render has been attempted,
   * or for hosts without a measurable live lane.
   */
  val renderStats: RenderPerfSnapshot? = null,
  /**
   * This catalog's live-lane frame counters — the per-daemon companion to [activeStreams], which
   * says how many sockets are open but nothing about what they are achieving. Null until one has.
   */
  val liveFrames: LiveFramePerfSnapshot? = null,
  /** Child daemon pools owned by this server, e.g. per-preview bundles for trusted catalogs. */
  val daemonPools: List<DaemonPoolSnapshot> = emptyList(),
)

@Serializable
private data class FailureDto(val atEpochMillis: Long, val session: String, val reason: String)

@Serializable
private data class UsesResponse(
  /**
   * False when the catalog could not be indexed at all — no parser sidecar, no source metadata, or
   * no fetcher on this host. Distinct from an empty [ids], which means the index ran and nothing
   * calls the token.
   */
  val available: Boolean,
  /**
   * Whether the index stopped short of every source file, so absence is not evidence of absence.
   */
  val truncated: Boolean = false,
  val ids: List<String> = emptyList(),
)

@Serializable
private data class PreviewsResponse(
  val schema: String = "compose-preview-serve/v2",
  val module: String,
  /**
   * Producer-trust verdict for this session ([BundleVerifier.summary]) — `signature:<keyId>`,
   * `branch:<repo>@<branch>`, `provenance:<id>`, or `unverified`. Null for a live daemon-backed
   * module (trust applies to detached bundles/catalogs, not the operator's own served module).
   */
  val trust: String? = null,
  /**
   * Why this session is snapshot-only, when it is — an interactive/live lane the viewer would
   * otherwise offer is unavailable and the server fell back to baked PNGs (e.g. the catalog
   * publishes no `liveBundle`). Empty for a fully-live session. Each entry carries a stable [code]
   * plus a human [detail]. Additive since `compose-preview-serve/v2`. See [ServeDegradation].
   */
  val degradations: List<DegradationDto> = emptyList(),
  /** Aggregate landing-page visits for this catalog/app. */
  val views: Long = 0,
  val previews: List<PreviewDto>,
)

@Serializable private data class DegradationDto(val code: String, val detail: String)

/**
 * `GET /<system>/parity?format=json`: the design-parity dashboard as data.
 *
 * Same numbers the HTML page shows, so a CI check can gate on `coverage.percent` or on
 * `drift`/`gaps` being empty without scraping a page. Deliberately the *derived* view rather than a
 * passthrough of the published `activity.json`: the coverage half doesn't exist in that file (the
 * server computes it live), and the preview ids here have already been filtered to ones this
 * session actually serves.
 */
@Serializable
private data class ParityResponse(
  val schema: String = "compose-preview-serve/parity/v1",
  val generatedAt: String? = null,
  val windowDays: Int? = null,
  val coverage: ParityCoverageDto,
  /** Components that moved on one side only — the actionable subset of the correlation. */
  val drift: List<ParityDriftDto> = emptyList(),
  val activity: List<ParityEventDto> = emptyList(),
  val gaps: List<ParityGapDto> = emptyList(),
  /** Validated GitHub issue rows published by the catalog, including closed rows. */
  val issues: List<ParityIssue> = emptyList(),
) {
  companion object {
    fun of(
      dashboard: ServeParityDashboard.Dashboard,
      issues: List<ParityIssue> = emptyList(),
    ): ParityResponse =
      ParityResponse(
        generatedAt = dashboard.generatedAt,
        windowDays = dashboard.windowDays,
        coverage =
          ParityCoverageDto(
            components = dashboard.coverage.components,
            mapped = dashboard.coverage.mapped,
            unmapped = dashboard.coverage.unmappedCount,
            percent = dashboard.coverage.percent,
          ),
        drift =
          dashboard.components
            .filter { it.correlation != ServeParityDashboard.Correlation.BOTH }
            .map {
              ParityDriftDto(
                component = it.name,
                side =
                  if (it.correlation == ServeParityDashboard.Correlation.CODE_ONLY) "code"
                  else "design",
                lastChangeAt = it.lastAt,
                previewId = it.previewId,
              )
            },
        activity =
          dashboard.feed.map {
            ParityEventDto(
              lane =
                when (it.lane) {
                  ServeParityDashboard.Lane.CODE -> "code"
                  ServeParityDashboard.Lane.FIGMA_VERSION -> "figma-version"
                  ServeParityDashboard.Lane.FIGMA_COMMENT -> "figma-comment"
                },
              at = it.at,
              title = it.title,
              author = it.author,
              url = it.href,
              previewIds = it.previewIds,
              components = it.components,
              resolved = it.resolved,
            )
          },
        gaps =
          dashboard.gaps.map {
            ParityGapDto(
              kind = it.kind,
              detail = it.detail,
              code = it.code,
              ref = it.ref,
              previewId = it.previewId,
              component = it.component,
            )
          },
        issues = issues,
      )
  }
}

@Serializable
private data class ParityCoverageDto(
  val components: Int,
  val mapped: Int,
  val unmapped: Int,
  /** 0–100, rounded. */
  val percent: Int,
)

@Serializable
private data class ParityDriftDto(
  val component: String,
  /** `code` (render moved, reference didn't) or `design` (the reverse). */
  val side: String,
  val lastChangeAt: String,
  val previewId: String? = null,
)

@Serializable
private data class ParityEventDto(
  /** `code`, `figma-version`, or `figma-comment`. */
  val lane: String,
  val at: String,
  val title: String,
  val author: String? = null,
  val url: String? = null,
  val previewIds: List<String> = emptyList(),
  val components: List<String> = emptyList(),
  val resolved: Boolean = false,
)

@Serializable
private data class ParityGapDto(
  val kind: String,
  val detail: String,
  val code: String? = null,
  val ref: String? = null,
  val previewId: String? = null,
  val component: String? = null,
)

/** What `/api/daemons` reports: is a render server up for this catalog, and how many processes. */
@Serializable
private data class DaemonStatusDto(
  val running: Boolean,
  val instances: Int,
  val pooled: Int,
  val poolCapacity: Int,
  val activeStreams: Int,
  val overallRunning: Int,
  val overallActiveStreams: Int,
  val liveSeatsTotal: Int,
  val liveSeatsAvailable: Int,
)

@Serializable
private data class PreviewDto(
  val id: String,
  val label: String,
  val modes: List<String>,
  /**
   * The author-declared editable knobs this preview exposes (`compose/overrides`) — key, type,
   * label, default/current value, and repeat index. Lets a programmatic client (the Figma plugin's
   * override editor) present the controls without scraping the viewer HTML. Empty when the preview
   * declares none (or the host doesn't carry them). Additive since `compose-preview-serve/v2`.
   */
  val overrides: List<PreviewOverrideDeclaration> = emptyList(),
  /**
   * The Remote Compose named-value knobs this preview declared (`compose/remotecompose`) — name +
   * typed author default (float / dp / int / string / bool / color). The auto-capture counterpart
   * of [overrides]: a programmatic client renders a control per entry and writes an edit back
   * through the `rc.<name>=<kind>:<value>` render param. Empty when the preview binds no named
   * values through the declaring `rememberOverridableRemote*` wrappers (or the host doesn't carry
   * them). Additive since `compose-preview-serve/v2`.
   */
  val remoteComposeKnobs: List<RemoteComposeKnobDeclaration> = emptyList(),
  /**
   * True when this preview is **live-only**: the catalog declares it (`deferred[]`) but publishes
   * no baked PNG for it, so every render is produced on demand by the session's live daemon. A
   * client can badge it and expect a slower, daemon-backed first render; false (the default) is
   * every ordinary baked preview. Additive since `compose-preview-serve/v2`.
   */
  val liveOnly: Boolean = false,
  /** Number of viewer page opens for this preview since this server process started. */
  val views: Long = 0,
)

/**
 * `GET /{system}/api/render-runs/{previewId}`: this preview's published revisions collapsed into
 * stretches that share their pixels, so the viewer can mark which of them actually differ.
 *
 * Its own lane rather than a field on the viewer page for one reason: it costs a delivery-branch
 * read, and the question is only asked when a reader opens the revision menu. Answering it during
 * page render would put a network round trip in front of every preview page to decorate a control
 * most visits never open.
 */
@Serializable
private data class RenderRunsResponse(
  val schema: String = "compose-preview-render-runs/v1",
  /** Newest first, aligned with the revision menu's own order. */
  val runs: List<RenderRunDto>,
  /** Publishes considered — the same window the menu lists. */
  val revisions: Int,
)

@Serializable
private data class RenderRunDto(
  /** Delivery sha of the newest publish in this run; the row the viewer marks. */
  val head: String,
  /** Source sha for that publish when its subject recorded one — what the menu row shows. */
  val sourceSha: String? = null,
  val commits: Int,
  /** True when the run runs off the end of the window and may be longer than [commits]. */
  val open: Boolean = false,
)

@Serializable
private data class GlobalComponentsResponse(
  val schema: String = "compose-preview-components/v1",
  val components: List<GlobalComponentDto>,
)

@Serializable
private data class GlobalComponentDto(
  val label: String,
  val catalog: String,
  val catalogTitle: String,
  val href: String,
  val keywords: String,
)

/** One configured catalog on `GET /admin/catalogs`: its config plus its latest load outcome. */
@Serializable
private data class AdminCatalogDto(
  val system: String,
  val repo: String,
  /** The delivery branch watched for this catalog (`design-artifacts/<system>`). */
  val branch: String,
  /** On the front-page index (vs. served-but-unlisted). */
  val listed: Boolean,
  /** The front-page section heading this catalog is published under; null ⇒ grouped by owner. */
  val group: String? = null,
  /**
   * Startup fetch order, highest first ([ServeCatalogsConfig.Entry.loadPriority]). Reported so a
   * deployment reconcile can see what the box will actually load first on its next boot, rather
   * than having to read the box's `catalogs.json`.
   */
  val loadPriority: Int = 0,
  /** `pending` / `loaded` / `failed` / `stale` ([CatalogLoadTracker.State.loadState]). */
  val state: String,
  val error: String? = null,
)

@Serializable
private data class AdminCatalogsResponse(
  val schema: String = "compose-preview-serve/admin-catalogs/v1",
  val catalogs: List<AdminCatalogDto>,
)

/**
 * The result of an admin mutation. [warning] is set when the catalog is serving but the change
 * couldn't be written back to `catalogs.json` — it will not survive a restart.
 */
@Serializable
private data class AdminCatalogResult(
  val schema: String = "compose-preview-serve/admin-catalog/v1",
  val system: String,
  val status: String,
  val warning: String? = null,
)

/** One front-page section on `GET /admin/groups`. */
@Serializable
private data class AdminGroupDto(
  val id: String,
  val heading: String,
  val noun: String,
  /** Section order on the front page, highest first ([ServeCatalogsConfig.Group.priority]). */
  val priority: Int = 0,
)

@Serializable
private data class AdminGroupsResponse(
  val schema: String = "compose-preview-serve/admin-groups/v1",
  val groups: List<AdminGroupDto> = emptyList(),
)

/** One configured hostname on `GET /admin/sites`. */
@Serializable private data class AdminSiteDto(val host: String, val system: String)

@Serializable
private data class AdminSitesResponse(
  val schema: String = "compose-preview-serve/admin-sites/v1",
  val sites: List<AdminSiteDto> = emptyList(),
)

/**
 * The result of a site mutation. [warning] is set when the hostname is in force on the running
 * server but couldn't be written back to catalogs.json — it will not survive a restart.
 */
@Serializable
private data class AdminSiteResult(
  val schema: String = "compose-preview-serve/admin-site-result/v1",
  val host: String,
  val status: String,
  val warning: String? = null,
)

/** One trusted branch on `GET /admin/trust`. */
@Serializable private data class AdminTrustBranchDto(val repo: String, val branch: String)

/**
 * One pinned key on `GET /admin/trust` — id and label only. The key material stays in the
 * operator's producers.json rather than being echoed back over the network.
 */
@Serializable private data class AdminTrustKeyDto(val keyId: String, val name: String? = null)

@Serializable
private data class AdminTrustResponse(
  val schema: String = "compose-preview-serve/admin-trust/v1",
  val branches: List<AdminTrustBranchDto> = emptyList(),
  val keys: List<AdminTrustKeyDto> = emptyList(),
  val oidc: List<String> = emptyList(),
)

/**
 * The result of a trust mutation. [warning] is set when the change is in force on the running
 * server but couldn't be written back to producers.json — it will not survive a restart.
 */
@Serializable
private data class AdminTrustResult(
  val schema: String = "compose-preview-serve/admin-trust-result/v1",
  val producer: String,
  val status: String,
  val warning: String? = null,
)

@Serializable
private data class DocAcceptedResponse(
  val schema: String = "compose-preview-serve/doc/v1",
  /** The permalink id — the capability. */
  val id: String,
  /** The display label the page shows (the sanitised upload name). */
  val name: String,
  /** Human format name ([ServeDocFormat.label]). */
  val format: String,
  /** Wire format id ([ServeDocFormat.id]) for a programmatic client. */
  val formatId: String,
  val bytes: Int,
  /** Relative permalink (`/d/<id>`) — absolute-ise against the host you posted to. */
  val url: String,
  /** Human time left on the link, e.g. `1h`. */
  val expiresIn: String,
  val expiresAtEpochSeconds: Long,
)

/**
 * What `POST /images` answers with. Two URL fields on purpose: [path] for a client that wants to
 * address this host itself, and [url] — absolute, built from the forwarded origin — for the case
 * the lane exists for, where the string is about to be pasted somewhere this server is a stranger.
 */
@Serializable
private data class ImageAcceptedResponse(
  val schema: String = "compose-preview-serve/image/v1",
  /** The link id — the capability. */
  val id: String,
  /** The display label (the sanitised upload name), also the alt text in [markdown]. */
  val name: String,
  /** Human format name ([ServeImageFormat.label]). */
  val format: String,
  /** Wire format id ([ServeImageFormat.id]) for a programmatic client. */
  val formatId: String,
  val bytes: Int,
  /** Intrinsic pixel size when the image's header declared one. */
  val width: Int? = null,
  val height: Int? = null,
  /** Relative link (`/i/<id>.png`). */
  val path: String,
  /** Absolute link — what goes in a PR body. */
  val url: String,
  /** The finished embed line, ready to paste: `![name](url)`. */
  val markdown: String,
  /** The GitHub login this upload was attributed to. */
  val uploadedBy: String,
  /** Human time left on the link, e.g. `7d`. */
  val expiresIn: String,
  val expiresAtEpochSeconds: Long,
)

@Serializable
private data class BundleAcceptedResponse(
  val schema: String = "compose-preview-serve/bundle/v1",
  val session: String,
  val previews: Int,
  /** Relative viewer link for the new session (append your token). */
  val path: String,
  /**
   * Producer-trust verdict for the upload ([BundleVerifier.summary]): `signature:<keyId>`,
   * `branch:<repo>@<branch>`, `provenance:<id>`, or `unverified`. The data tiers serve either way;
   * this tells the uploader whether the server would treat the bundle as trusted.
   */
  val trust: String,
)

/**
 * Reply from the per-catalog theme-cache admin routes.
 *
 * [entries] is what `regenerate` queued — zero is a legitimate answer for a catalog with no
 * persistent cache, or one already fully re-rendered. [dropped] reports whether `drop` actually
 * took the bytes: false means the generation write lock was held by a render publishing at that
 * moment, so the caller should retry rather than believe the store is empty.
 */
@Serializable
private data class ThemeCacheActionDto(
  val system: String,
  val action: String,
  val entries: Int = 0,
  val dropped: Boolean? = null,
  /**
   * Whether `regenerate` actually queued anything. False with a 409 means it could not: theme
   * optimization is switched off for this deployment, so no pass would ever work the queue, or the
   * mark could not be written to the volume and a restart would forget it.
   */
  val queued: Boolean? = null,
)

/** Reply from the optimizer pause/resume admin routes. */
@Serializable
private data class OptimizerPauseDto(
  val paused: Boolean,
  val pausedUntilEpochMillis: Long? = null,
  val reason: String? = null,
)
