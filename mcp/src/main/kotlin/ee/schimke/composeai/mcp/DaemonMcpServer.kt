package ee.schimke.composeai.mcp

import ee.schimke.composeai.daemon.RecordingTestGenerator
import ee.schimke.composeai.daemon.client.DataProductWireException
import ee.schimke.composeai.daemon.client.WorkspaceId
import ee.schimke.composeai.daemon.protocol.AmbientOverride
import ee.schimke.composeai.daemon.protocol.ChangeType
import ee.schimke.composeai.daemon.protocol.FileKind
import ee.schimke.composeai.daemon.protocol.FocusOverride
import ee.schimke.composeai.daemon.protocol.KeyboardOverride
import ee.schimke.composeai.daemon.protocol.LauncherWidgetOverride
import ee.schimke.composeai.daemon.protocol.Material3ThemeOverrides
import ee.schimke.composeai.daemon.protocol.Orientation
import ee.schimke.composeai.daemon.protocol.PermissionsOverride
import ee.schimke.composeai.daemon.protocol.PreviewExtensionDescriptor
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RecordingScriptEventStatus
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RenderTier
import ee.schimke.composeai.daemon.protocol.SemanticsDelta
import ee.schimke.composeai.daemon.protocol.SemanticsInputTarget
import ee.schimke.composeai.daemon.protocol.UiMode
import ee.schimke.composeai.daemon.protocol.WallpaperOverride
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsProduct
import ee.schimke.composeai.data.layoutinspector.SemanticsBounds
import ee.schimke.composeai.data.layoutinspector.SemanticsDiff
import ee.schimke.composeai.data.layoutinspector.SemanticsTarget
import ee.schimke.composeai.data.layoutinspector.SemanticsTargets
import ee.schimke.composeai.data.layoutinspector.TargetResolution
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionCommandCatalog
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.mcp.protocol.CallToolResult
import ee.schimke.composeai.mcp.protocol.ContentBlock
import ee.schimke.composeai.mcp.protocol.ReadResourceResult
import ee.schimke.composeai.mcp.protocol.ResourceContents
import ee.schimke.composeai.mcp.protocol.ResourceDescriptor
import ee.schimke.composeai.mcp.protocol.ToolDef
import ee.schimke.composeai.render.matrix.ContactSheet
import ee.schimke.composeai.render.matrix.MatrixAxes
import ee.schimke.composeai.render.matrix.MatrixCell
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Which tool surface a [DaemonMcpServer] presents. [NATIVE] is the full compose-preview tool set;
 * [STORYBOOK] is the Storybook-MCP-compatible subset only (native tools hidden), so a
 * Storybook-MCP-trained agent sees a clean Storybook surface with no overlapping/duplicate tools.
 */
enum class McpToolProfile {
  NATIVE,
  STORYBOOK,
}

/**
 * The Storybook alias tool names (excludes `status`, which both profiles expose). Used to filter
 * the two profiles apart from the single `buildFullToolDefs` source so no `ToolDef` literal is
 * duplicated: NATIVE serves everything except these; STORYBOOK serves only these + `status`.
 *
 * Top-level (not an instance `val`) on purpose: the async full-tool-catalog loader reads it off the
 * `toolCatalogExecutor` thread during construction, before instance properties declared later would
 * have initialized — a top-level val is class-load-time, so there's no init-order race.
 */
private val STORYBOOK_ALIAS_NAMES =
  setOf(
    "list-all-documentation",
    "get-documentation-for-story",
    "preview-stories",
    "run-story-tests",
  )

/**
 * The load-bearing wiring layer. Owns:
 *
 * - The per-(workspace, module) **preview catalog** populated from daemon `discoveryUpdated`.
 * - The MCP **resources** surface (`list`, `read`, `subscribe`, `unsubscribe`).
 * - The MCP **tools** surface (`register_project`, `list_projects`, `unregister_project`,
 *   `render_preview`, `watch`, `unwatch`, `list_watches`).
 * - The translation of daemon `renderFinished` → `notifications/resources/updated` (for subscribed
 *   clients and for clients whose watch sets cover the URI).
 * - The translation of daemon `discoveryUpdated` → `notifications/resources/list_changed`.
 * - Watch propagation back to daemons via [WatchPropagator].
 * - History recording on every successful render via [HistoryStore].
 *
 * Renderer-agnostic: the catalog stores the daemon's preview ids verbatim (typically
 * `<className>.<methodName>` per `DiscoverPreviewsTask`), and the URI builder pairs them with the
 * (workspace, module) they came from.
 */
class DaemonMcpServer(
  private val supervisor: DaemonSupervisor,
  private val sessions: SessionRegistry = SessionRegistry(),
  private val subscriptions: Subscriptions = Subscriptions(),
  private val historyStore: HistoryStore = HistoryStore.NOOP,
  private val serverInfo: Implementation =
    Implementation(name = "compose-preview-mcp", version = "v0"),
  private val renderTimeoutMs: Long = 60_000,
  /**
   * Cadence (ms) of the background source-freshness poller. The poller walks the catalog and runs
   * the same `ensureSourceFreshBeforeRender` probe as the on-demand path, so edits land on the
   * daemon proactively even when no MCP `resources/read` arrives. `0` disables the poller — test
   * fixtures use `0` to keep tests deterministic; production defaults to 30 s, slow enough to be
   * cheap and fast enough that an interactive editor sees a refreshed render within the next
   * `renderNow`.
   */
  private val sourcePollIntervalMs: Long = DEFAULT_SOURCE_POLL_INTERVAL_MS,
  /**
   * Cadence (ms) of the random-sampling deterministic-render probe. The sampler picks a preview at
   * random whose render queue is empty, fires a `renderNow` past the freshness check (no
   * `fileChanged` is sent, so the daemon's classloader stays put), and reads the resulting
   * `renderFinished.unchanged` flag — a non-`true` reply indicates the preview's bytes drifted with
   * no source change (clock-reading composables, daemon classloader bugs, build-output drift). `0`
   * disables the sampler; production defaults to 10 minutes.
   */
  private val samplingIntervalMs: Long = DEFAULT_SAMPLING_INTERVAL_MS,
  private val fileSystem: FileSystem = SystemFileSystem,
  fullToolDefsLoader: (() -> List<ToolDef>)? = null,
  /**
   * Which tool surface this server presents. [McpToolProfile.NATIVE] (default) exposes the full
   * compose-preview tool set. [McpToolProfile.STORYBOOK] exposes ONLY the Storybook-compatible
   * tools ([storybookToolDefs]) with the native tools hidden, so a Storybook-MCP-trained agent sees
   * a clean Storybook surface without the overlapping/duplicate native tools (e.g. `render_preview`
   * alongside `preview-stories`). Both profiles route through the same handlers.
   */
  private val profile: McpToolProfile = McpToolProfile.NATIVE,
  /** Optional remote Design API facade; never gives MCP direct reducer or store access. */
  private val uiBuilderMcp: UiBuilderMcpAdapter? = null,
) {

  private val fullToolDefsLoader: () -> List<ToolDef> =
    fullToolDefsLoader ?: { effectiveFullToolDefs() }

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }

  private val imageSizeOverride: ImageSizeOverride = ImageSizeOverride.detect()

  /**
   * Counters surfaced via the `status` MCP tool: probe outcomes, polling cycles, and random
   * sampling determinism. Lets an operator answer "why does my agent see stale renders?" without
   * digging through wire traces.
   */
  private val freshnessMetrics = FreshnessMetrics()

  /**
   * Per-(workspace, module) catalog: preview-id → minimal metadata. Updated from `discoveryUpdated`
   * on the daemon's reader thread; read by `resources/list` and the watch propagator on session
   * threads.
   */
  private val catalog: ConcurrentHashMap<DaemonAddr, ConcurrentHashMap<String, PreviewEntry>> =
    ConcurrentHashMap()

  /**
   * Per-(workspace, module, previewId) FIFO of [PendingRenderGroup]s awaiting a render. The HEAD
   * group is the one whose `renderNow` has been sent to the daemon (in-flight); subsequent groups
   * wait for their predecessor's `renderFinished` before their own `renderNow` is sent. Groups are
   * created per distinct `PreviewOverrides` value: same-overrides waiters dedup onto the tail group
   * (multi-waiter dedup, preserving the pre-#432 contract for concurrent same-call reads),
   * different-overrides waiters serialize behind their predecessor (the load-bearing fix versus the
   * daemon-side coalesce rule, PROTOCOL.md § 5).
   *
   * Without this serialization, two concurrent override-bearing calls for the same URI would race
   * the daemon's coalesce: only one `renderNow` is accepted, the second is rejected, and the MCP
   * server's by-previewId fanout would wake both waiters with the FIRST render's bytes. Caller B
   * (with `O2`) silently received caller A's `O1` bytes — the real bug PR #432 papered over (its
   * kdoc said "hangs to renderTimeoutMs" but the actual symptom is wrong-bytes).
   */
  private val previewQueues = ConcurrentHashMap<PreviewIdKey, ArrayDeque<PendingRenderGroup>>()

  /**
   * Per-(workspace, module) counter of consecutive `classpathDirty` self-loops since the last clean
   * spawn. Reset to zero whenever a respawn succeeds without the new daemon also emitting
   * `classpathDirty`. See [onClasspathDirty] for the cap rationale.
   */
  private val respawnAttempts = ConcurrentHashMap<DaemonAddr, Int>()

  /**
   * D1 — `(workspace, module, previewId, kind) → latest payload from
   * `renderFinished.dataProducts``. Populated whenever a daemon ships attachments alongside a
   * render (which it only does for kinds the MCP server has subscribed to via
   * `subscribe_preview_data`, or that are in the global `attachDataProducts` set passed at
   * `initialize` time).
   *
   * Lets `get_preview_data` short-circuit to the cache for kinds that are already fresh — agents
   * that do `subscribe_preview_data` once and then `get_preview_data` repeatedly pay one wire
   * round-trip total instead of one per fetch.
   *
   * Eviction: each new `renderFinished` REPLACES every cached attachment for that `(uri)` —
   * anything the daemon didn't include this render is no longer fresh. Daemon-level wipes
   * (classpathDirty, onClose) drop every cached entry for the affected `(workspace, module)`.
   */
  private val dataProductCache = ConcurrentHashMap<DataAttachKey, DataAttachmentEntry>()

  private val watchPropagator =
    WatchPropagator(
      subscriptions = subscriptions,
      previewIdProvider = { daemon ->
        val byId =
          catalog[DaemonAddr(daemon.workspaceId, daemon.modulePath)]
            ?: return@WatchPropagator emptyList()
        byId.values.map { entry ->
          PreviewUri(
            workspaceId = daemon.workspaceId,
            modulePath = daemon.modulePath,
            previewFqn = entry.fqn,
            config = entry.config,
          )
        }
      },
    )

  /**
   * Worker for slow daemon-lifecycle work — replacement-daemon spawn after a `classpathDirty`
   * notification, and async first-spawn from the `watch` tool. Bounded multi-threaded so several
   * modules can cold-start in parallel — without this, a workspace with N preview modules paid `N ×
   * cold-start` (Robolectric: minutes on a cold Maven cache). The supervisor's `daemonFor` is
   * `computeIfAbsent`-safe so concurrent requests for the same module still de-dup. Daemon-flagged
   * so the executor never delays JVM shutdown.
   */
  private val daemonLifecycleExecutor: java.util.concurrent.ExecutorService =
    java.util.concurrent.Executors.newFixedThreadPool(DAEMON_LIFECYCLE_THREADS) { r ->
      Thread(r, "mcp-daemon-lifecycle").apply { isDaemon = true }
    }

  /**
   * Scheduled worker for periodic `notifications/progress` beats during slow renders. Pool size 1
   * is enough — beats fire at [PROGRESS_BEAT_INTERVAL_MS] and self-cancel as soon as the underlying
   * [renderAndReadBytes] future completes. Also owns the one-shot delayed check for slow tool
   * catalog loading.
   */
  private val progressBeatExecutor: java.util.concurrent.ScheduledExecutorService =
    java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
      Thread(r, "mcp-progress-beat").apply { isDaemon = true }
    }

  /**
   * Scheduled worker that owns the background source-freshness poller and the random-sampling
   * deterministic-render probe. Pool size 2 so a slow probe can't push the next polling tick;
   * daemon-flagged so neither delays JVM shutdown.
   */
  private val freshnessExecutor: java.util.concurrent.ScheduledExecutorService =
    java.util.concurrent.Executors.newScheduledThreadPool(2) { r ->
      Thread(r, "mcp-freshness").apply { isDaemon = true }
    }

  /**
   * Per-(workspace, module, previewId) count of in-flight sampling probes. Bumped before the
   * sampler issues `renderNow`; decremented (and removed when zero) by `onRenderFinished` so the
   * matching `renderFinished` can be classified as a probe and its `unchanged` flag fed into the
   * sampling counters. Race acceptable: a real user render arriving simultaneously with a probe may
   * attribute the probe outcome incorrectly, but the sampler only fires when [previewQueues] is
   * empty for the previewId, so the window is tiny.
   */
  private val pendingProbes =
    ConcurrentHashMap<PreviewIdKey, java.util.concurrent.atomic.AtomicInteger>()

  /**
   * Keep the MCP handshake off the full command-catalog path. Some clients enforce a tight startup
   * deadline; parsing every schema and loading extension command metadata before `initialize` risks
   * surfacing as "context deadline exceeded". Start with a compact core surface, build the full
   * surface in the background, and notify clients only when that background load was actually
   * delayed.
   */
  private val toolCatalogExecutor: java.util.concurrent.ExecutorService =
    java.util.concurrent.Executors.newSingleThreadExecutor { r ->
      Thread(r, "mcp-tool-catalog").apply { isDaemon = true }
    }

  private val fullToolCatalogWasDelayed = AtomicBoolean(false)
  @Volatile private var fullToolCatalogError: String? = null

  /**
   * Sessions that have been served [bootstrapToolDefs] from `tools/list` while [fullToolDefsFuture]
   * was still loading. Each such session must receive `notifications/tools/list_changed` once the
   * full catalog is ready, regardless of whether the load took more or less than
   * [TOOL_CATALOG_NOTIFY_DELAY_MS] — clients that rely on `listChanged` would otherwise permanently
   * miss the tools that only appear in the full catalog (see #670). Guarded by
   * [bootstrapNotifyLock] so the future-completion handler and `tools/list` callers cannot lose a
   * session through the isDone/add race.
   */
  private val bootstrapNotifyLock = Any()
  private val bootstrapServedSessions = mutableSetOf<Session>()

  private val fullToolDefsFuture: CompletableFuture<List<ToolDef>> =
    CompletableFuture.supplyAsync({ this.fullToolDefsLoader() }, toolCatalogExecutor).also { future
      ->
      progressBeatExecutor.schedule(
        {
          if (!future.isDone) {
            fullToolCatalogWasDelayed.set(true)
          }
        },
        TOOL_CATALOG_NOTIFY_DELAY_MS,
        TimeUnit.MILLISECONDS,
      )
      future.whenComplete { _, error ->
        if (error != null) {
          fullToolCatalogError = error.message ?: error::class.java.simpleName
          System.err.println("compose-preview-mcp: full tool catalog failed: ${error.message}")
        } else {
          val toNotify =
            synchronized(bootstrapNotifyLock) {
              val snapshot = bootstrapServedSessions.toList()
              bootstrapServedSessions.clear()
              snapshot
            }
          toNotify.forEach { runCatching { it.notifyToolListChanged() } }
        }
      }
    }

  init {
    val router = supervisor.router()
    router.on("discoveryUpdated") { daemon, params -> onDiscoveryUpdated(daemon, params) }
    router.on("renderFinished") { daemon, params -> onRenderFinished(daemon, params) }
    router.on("renderFailed") { daemon, params -> onRenderFailed(daemon, params) }
    router.on("classpathDirty") { daemon, params -> onClasspathDirty(daemon, params) }
    router.on("historyAdded") { daemon, params -> onHistoryAdded(daemon, params) }
    router.onClose { daemon ->
      catalog.remove(DaemonAddr(daemon.workspaceId, daemon.modulePath))
      evictDataProductsForDaemon(daemon.workspaceId, daemon.modulePath)
      watchPropagator.forget(daemon)
    }
    if (sourcePollIntervalMs > 0) {
      freshnessExecutor.scheduleWithFixedDelay(
        ::runSourceFreshnessPoll,
        sourcePollIntervalMs,
        sourcePollIntervalMs,
        TimeUnit.MILLISECONDS,
      )
    }
    if (samplingIntervalMs > 0) {
      freshnessExecutor.scheduleWithFixedDelay(
        ::runRandomSamplingProbe,
        samplingIntervalMs,
        samplingIntervalMs,
        TimeUnit.MILLISECONDS,
      )
    }
  }

  /**
   * Stops the freshness poller + sampler and shuts the executor down. Idempotent. Tests call this
   * from `tearDown` so background tasks don't stretch into the next test; production never calls it
   * because the executors are daemon-flagged and the JVM exits cleanly.
   */
  fun shutdown() {
    runCatching { freshnessExecutor.shutdownNow() }
  }

  // -------------------------------------------------------------------------
  // Public API consumed by the SDK-backed MCP session
  // -------------------------------------------------------------------------

  fun newSession(input: java.io.InputStream, output: java.io.OutputStream): McpSession {
    lateinit var session: McpSession
    session =
      McpSession(
        serverInfo = serverInfo,
        options = composePreviewServerOptions(),
        input = input,
        output = output,
        configure = { sdkSession ->
          installComposePreviewHandlers(
            sdkSession = sdkSession,
            session = session,
            listTools = { currentToolDefs(session) },
            callTool = { name, arguments -> handleCallTool(session, name, arguments) },
            listResources = { catalogResources() },
            readResource = { uri, progressToken ->
              handleReadResource(session, uri, progressToken)
            },
            subscribe = { uri -> subscriptions.subscribe(uri, session) },
            unsubscribe = { uri -> subscriptions.unsubscribe(uri, session) },
          )
        },
        onClose = { closeSession(session) },
      )
    sessions.register(session)
    return session
  }

  private fun closeSession(session: Session) {
    // Release the session's data-product subscriptions and tell the daemon to unsubscribe the keys
    // whose last reference just dropped — otherwise daemon-side subscriptions would leak until
    // `setVisible` churn drops them, and an interactive UI that wants to re-attach later would see
    // stale state. We forward unsubscribes best-effort: a daemon that's already gone
    // (classpathDirty respawn) or rejects the kind doesn't block session teardown.
    val released = subscriptions.forgetDataSubscriptions(session)
    released.forEach { key -> dispatchDataUnsubscribe(key) }
    subscriptions.forget(session)
    synchronized(bootstrapNotifyLock) { bootstrapServedSessions.remove(session) }
    sessions.unregister(session)
  }

  // -------------------------------------------------------------------------
  // Resource list / read
  // -------------------------------------------------------------------------

  private fun catalogResources(): List<ResourceDescriptor> {
    val out = mutableListOf<ResourceDescriptor>()
    for ((addr, byId) in catalog) {
      for (entry in byId.values) {
        val uri =
          PreviewUri(
            workspaceId = addr.workspaceId,
            modulePath = addr.modulePath,
            previewFqn = entry.fqn,
            config = entry.config,
          )
        out.add(
          ResourceDescriptor(
            uri = uri.toUri(),
            name = entry.fqn.substringAfterLast('.'),
            description = entry.displayName ?: entry.fqn,
            mimeType = "image/png",
          )
        )
      }
    }
    return out.sortedBy { it.uri }
  }

  private fun handleReadResource(
    session: Session,
    uri: String,
    progressToken: JsonElement?,
  ): ReadResourceResult {
    // History URIs short-circuit to `history/read` against the daemon — historical bytes are
    // immutable so there's no render path involved.
    HistoryUri.parseOrNull(uri)?.let { historyUri ->
      return readHistoryResource(uri, historyUri)
    }
    val parsed = PreviewUri.parseOrNull(uri) ?: error("Invalid compose-preview URI: '$uri'")
    val pngBytes = renderAndReadBytes(parsed, session, progressToken)
    val encoded = Base64.getEncoder().encodeToString(pngBytes)
    return ReadResourceResult(
      contents = listOf(ResourceContents.Blob(uri = uri, mimeType = "image/png", blob = encoded))
    )
  }

  /**
   * Reads a `compose-preview-history://…` resource by calling `history/read` on the matching daemon
   * with `inline = true`. The daemon's response carries the PNG bytes already base64- encoded; we
   * forward them verbatim to the MCP client.
   *
   * Falls back to reading the file from `pngPath` when `pngBytes` is unset (older daemons or non-FS
   * sources where inline is opportunistic).
   */
  private fun readHistoryResource(uriString: String, uri: HistoryUri): ReadResourceResult {
    val daemon = supervisor.daemonFor(uri.workspaceId, uri.modulePath)
    val result = daemon.client.historyRead(entryId = uri.entryId, inline = true)
    val blob =
      result.pngBytes
        ?: run {
          val file = File(result.pngPath)
          check(file.isFile) { "history/read pngPath does not exist: ${result.pngPath}" }
          Base64.getEncoder()
            .encodeToString(fileSystem.read(file.path.toPath()) { readByteArray() })
        }
    return ReadResourceResult(
      contents = listOf(ResourceContents.Blob(uri = uriString, mimeType = "image/png", blob = blob))
    )
  }

  private fun renderAndReadBytes(
    uri: PreviewUri,
    session: Session? = null,
    progressToken: JsonElement? = null,
    overrides: PreviewOverrides? = null,
  ): ByteArray {
    val outcome = awaitNextRender(uri, session, progressToken, overrides)
    val file = File(outcome.pngPath)
    check(file.isFile) { "renderAndReadBytes: pngPath does not exist: ${outcome.pngPath}" }
    return applyImageSizeOverride(fileSystem.read(file.path.toPath()) { readByteArray() })
  }

  /**
   * Like [renderAndReadBytes] but returns the rendered PNG at full resolution, **before** the host
   * image-size cap ([applyImageSizeOverride]). The crop path needs the un-downscaled bytes so its
   * pixel space matches `compose/semantics` `boundsInRoot`; it re-applies the cap to the small
   * crop.
   */
  private fun renderAndReadRawBytes(uri: PreviewUri, overrides: PreviewOverrides?): ByteArray {
    val outcome = awaitNextRender(uri, overrides = overrides)
    val file = File(outcome.pngPath)
    check(file.isFile) { "renderAndReadRawBytes: pngPath does not exist: ${outcome.pngPath}" }
    return fileSystem.read(file.path.toPath()) { readByteArray() }
  }

  /**
   * Submits a `renderNow` for [uri] and blocks until the matching `renderFinished` lands. Throws on
   * render failure or timeout. Used by [renderAndReadBytes] (which then reads the PNG) and by
   * `get_preview_data`'s auto-render fallback (which doesn't care about the bytes — it just needs
   * the daemon to have rendered SOMETHING so a follow-up `data/fetch` returns the kind instead of
   * `DataProductNotAvailable`).
   *
   * [overrides] forwards the per-call display-property overrides PROTOCOL.md § 5 documents on
   * `renderNow`. Defaults to null (use discovery-time RenderSpec); the auto-render fallback in
   * `get_preview_data` leaves it null on purpose since it just wants any render so the kind becomes
   * available. Concurrent calls for the same URI are serialized per-`previewId`: the head group's
   * `renderNow` is in flight, subsequent groups wait for the head's `renderFinished` before their
   * own `renderNow` is sent. Same-overrides callers dedup onto the tail group; different-overrides
   * callers append a new group. Without this, the daemon's coalesce rule (PROTOCOL.md § 5
   * `renderNow.overrides`) would reject the second `renderNow` and the by-previewId fanout would
   * wake caller B with caller A's bytes — the real bug PR #432's "known limitation" note papered
   * over.
   */
  private fun awaitNextRender(
    uri: PreviewUri,
    session: Session? = null,
    progressToken: JsonElement? = null,
    overrides: PreviewOverrides? = null,
  ): RenderOutcome.Finished {
    val daemon = supervisor.daemonFor(uri.workspaceId, uri.modulePath)
    ensureSourceFreshBeforeRender(uri, daemon)
    val key = PreviewIdKey(uri.workspaceId, uri.modulePath, uri.previewFqn)
    val future = java.util.concurrent.CompletableFuture<RenderOutcome>()
    // Atomically join the right group. `becameFront` (captured outside the compute lambda)
    // tracks whether we created a brand-new head group: in that case we own the `renderNow`
    // dispatch (must happen outside the per-key lock so we don't hold it across IPC). When we
    // dedup onto an existing group OR append a non-head group, no `renderNow` fires here — the
    // in-flight head will wake us via onRenderFinished, or the head's completion will promote
    // our group to the head and dispatch our `renderNow` then.
    var becameFront = false
    previewQueues.compute(key) { _, queue ->
      val q = queue ?: ArrayDeque()
      val tail = q.lastOrNull()
      if (tail != null && tail.overrides == overrides) {
        // Same-overrides dedup. If the tail is the head (in flight), we get the head's bytes.
        // If the tail is a queued non-head, we get woken when that group is dispatched and
        // completes. Either way, no fresh `renderNow`.
        tail.futures.add(future)
      } else {
        val group = PendingRenderGroup(overrides = overrides)
        group.futures.add(future)
        if (q.isEmpty()) {
          group.sent = true
          becameFront = true
        }
        q.addLast(group)
      }
      q
    }
    // Optional `notifications/progress` beat: when the client opted in via
    // `_meta.progressToken`, fire periodic monotonic progress notifications so a UI can show a
    // spinner / progress bar while the slow render completes. Beat thread is daemon-flagged
    // and exits as soon as the future completes (or the timeout cleanup path runs).
    val progressBeat = startProgressBeatIfNeeded(session, progressToken, future, uri)
    if (becameFront) {
      // Shard render fan-out across replicas: same previewFqn → same replica (cache locality +
      // dedup), different previewFqns → spread across replicas so concurrent renders run in
      // parallel. With replicasPerDaemon = 0 this collapses to the primary.
      daemon
        .clientForRender(uri.previewFqn)
        .renderNow(previews = listOf(uri.previewFqn), tier = RenderTier.FULL, overrides = overrides)
    }
    val outcome =
      try {
        future.get(renderTimeoutMs, TimeUnit.MILLISECONDS)
      } catch (e: java.util.concurrent.TimeoutException) {
        // Best-effort cleanup. Drop our future from its group; if the group becomes empty AND
        // it's not the in-flight head, drop the group from the queue. (An empty head stays —
        // the daemon's eventual renderFinished will pop it cleanly via popHeadAndPromoteNext.)
        previewQueues.computeIfPresent(key) { _, q ->
          val containing = q.firstOrNull { it.futures.contains(future) }
          containing?.futures?.remove(future)
          if (containing != null && containing.futures.isEmpty() && q.first() !== containing) {
            q.remove(containing)
          }
          if (q.isEmpty()) null else q
        }
        progressBeat?.cancel(true)
        error("awaitNextRender: timed out after ${renderTimeoutMs}ms for $uri")
      } finally {
        progressBeat?.cancel(false)
      }
    return when (outcome) {
      is RenderOutcome.Failed ->
        error(
          buildString {
            append("awaitNextRender failed for $uri: ${outcome.kind} ${outcome.message}")
            // #1789 — append the daemon's classified remediation so the agent gets the fix hint
            // inline rather than having to re-diagnose the failure from the message alone.
            outcome.suggestion?.let { append(" — suggestion: $it") }
          }
        )
      is RenderOutcome.Finished -> outcome
    }
  }

  /**
   * Decides whether the user has edited [uri]'s source since the last time the daemon was told
   * about it, and forwards a `fileChanged({kind: "source"})` notification when so. The daemon's
   * [`UserClassLoaderHolder.swap`][ee.schimke.composeai.daemon.UserClassLoaderHolder.swap] only
   * rotates the user classloader on `fileChanged`, so missing this signal is exactly what makes
   * agents perceive "stale renders" after an edit.
   *
   * Two-stage detection:
   * 1. **Fast path — mtime advanced.** Almost every editor advances the source's `lastModified` on
   *    save, so the cheap `stat` is enough. Fire `fileChanged`, refresh the cached mtime + a fresh
   *    content hash, return.
   * 2. **Slow path — mtime did not advance.** Same-millisecond writes on fast SSDs / tmpfs,
   *    mtime-preserving editors, and agent harnesses that touch files programmatically without
   *    bumping mtime all leave the file's mtime exactly where discovery saw it. Hash the bytes and
   *    compare against the cached hash; on mismatch, fire `fileChanged` and refresh the cache. The
   *    hash cost (one SHA-256 over a Kotlin source — a few KB to ~tens of KB) is in the noise next
   *    to the render itself (Robolectric: hundreds of ms).
   *
   * First-sighting (catalog entry has neither mtime nor hash) records both silently — the mtime is
   * what was captured at discovery, and the hash is computed on demand. Matches the pre-fix
   * behaviour of "first read after discovery is a no-op".
   */
  /**
   * @return `true` when this probe forwarded a `fileChanged` to the daemon, `false` otherwise. The
   *   polling path uses the return to bump `polling.changesDetected`; on-demand callers can ignore
   *   it.
   */
  private fun ensureSourceFreshBeforeRender(uri: PreviewUri, daemon: SupervisedDaemon): Boolean {
    freshnessMetrics.probesTotal.incrementAndGet()
    val addr = DaemonAddr(uri.workspaceId, uri.modulePath)
    val entry =
      catalog[addr]?.get(uri.previewFqn)
        ?: run {
          freshnessMetrics.probesNoEntry.incrementAndGet()
          return false
        }
    val sourceFile =
      resolvePreviewSourceFile(uri, entry.sourceFile)
        ?: run {
          freshnessMetrics.probesNoSource.incrementAndGet()
          return false
        }
    val currentModifiedMs =
      sourceFile.lastModified().takeIf { it > 0L }
        ?: run {
          freshnessMetrics.probesNoSource.incrementAndGet()
          return false
        }

    val mtimeAdvanced =
      entry.sourceLastModifiedMs?.let { currentModifiedMs > it }
        ?: run {
          // First sighting via mtime — record what we know and bail without firing. The hash
          // is filled in on the first slow-path probe so subsequent frozen-mtime edits get
          // caught on iteration two.
          catalog[addr]?.computeIfPresent(uri.previewFqn) { _, current ->
            current.copy(sourceLastModifiedMs = currentModifiedMs)
          }
          freshnessMetrics.probesFirstSighting.incrementAndGet()
          return false
        }

    val needsNotify =
      if (mtimeAdvanced) {
        freshnessMetrics.probesChangedByMtime.incrementAndGet()
        true
      } else {
        // mtime didn't move — confirm with a content hash. If we have nothing to compare
        // against (legacy entry / first probe after discovery without a hash), record the
        // current hash so the next probe has a baseline.
        val currentHash =
          runCatching { sha256Hex(sourceFile) }.getOrNull()
            ?: run {
              freshnessMetrics.probesNoSource.incrementAndGet()
              return false
            }
        val knownHash = entry.sourceContentHash
        if (knownHash == null) {
          catalog[addr]?.computeIfPresent(uri.previewFqn) { _, current ->
            current.copy(sourceContentHash = currentHash)
          }
          freshnessMetrics.probesUnchangedNoBaseline.incrementAndGet()
          return false
        }
        if (currentHash == knownHash) {
          freshnessMetrics.probesUnchangedByHash.incrementAndGet()
          false
        } else {
          freshnessMetrics.probesChangedByHash.incrementAndGet()
          true
        }
      }
    if (!needsNotify) return false

    daemon.allClients().forEach { client ->
      runCatching {
        client.fileChanged(
          path = sourceFile.absolutePath,
          kind = FileKind.SOURCE,
          changeType = ChangeType.MODIFIED,
        )
      }
    }
    val refreshedHash = runCatching { sha256Hex(sourceFile) }.getOrNull()
    catalog[addr]?.computeIfPresent(uri.previewFqn) { _, current ->
      current.copy(
        sourceLastModifiedMs = currentModifiedMs,
        sourceContentHash = refreshedHash ?: current.sourceContentHash,
      )
    }
    return true
  }

  /**
   * Background poller — walks every spawned daemon's catalog and runs the same
   * [ensureSourceFreshBeforeRender] probe as the on-demand path, so source edits land on the daemon
   * proactively instead of waiting for the next `resources/read`. Cheap: one stat per preview on
   * the fast path; one stat + one SHA-256 on the slow path. Wraps each per-preview probe in
   * `runCatching` so a single broken entry doesn't cancel the whole cycle.
   *
   * Module-internal so tests can trigger a poll deterministically (with `sourcePollIntervalMs = 0`
   * to disable the scheduled invocation) instead of racing the executor's cadence.
   */
  internal fun runSourceFreshnessPoll() {
    runCatching {
      freshnessMetrics.pollingCycles.incrementAndGet()
      supervisor.listProjects().forEach { project ->
        project.daemons.forEach { (modulePath, daemon) ->
          // Manifest stat first — a Gradle `composePreviewDiscover` re-run between renders
          // rewrites
          // `previews.json`. Picking that up here means new preview ids land in the catalog
          // (and `render_preview` works) before the user's next request, without the
          // restart-the-MCP-server escape hatch reported in issue #834.
          runCatching { reloadManifestIfChanged(daemon) }
            .onFailure {
              System.err.println(
                "compose-preview-mcp: manifest reload failed for ${daemon.workspaceId}/${daemon.modulePath}: ${it.message}"
              )
            }
          val byId = catalog[DaemonAddr(project.workspaceId, modulePath)] ?: return@forEach
          byId.values.forEach { entry ->
            freshnessMetrics.pollingPreviewsScanned.incrementAndGet()
            val uri =
              PreviewUri(
                workspaceId = project.workspaceId,
                modulePath = modulePath,
                previewFqn = entry.fqn,
                config = entry.config,
              )
            val fired = runCatching {
              ensureSourceFreshBeforeRender(uri, daemon)
            }
              .getOrDefault(false)
            if (fired) freshnessMetrics.pollingChangesDetected.incrementAndGet()
          }
        }
      }
    }
      .onFailure {
        // Defensive — the executor swallows uncaught throws and cancels future runs; we'd lose
        // the poller silently. Logging keeps the behaviour observable.
        System.err.println("compose-preview-mcp: source-freshness poll failed: ${it.message}")
      }
  }

  /**
   * Per-(workspace, module) last-seen mtime + content hash of the daemon's `previews.json`. The
   * fast path (mtime unchanged) skips re-reading the file; on mtime advance we hash to confirm a
   * real content change before incurring the parse + diff + dispatch cost, matching the
   * source-freshness probe's two-stage shape.
   */
  private val manifestState = ConcurrentHashMap<DaemonAddr, ManifestState>()

  private data class ManifestState(val mtimeMs: Long, val hash: String)

  /**
   * Stats the daemon's `previews.json`; if the file changed since the last cycle, parses it, diffs
   * the preview ids against the current catalog, and routes a synthetic `discoveryUpdated` through
   * [onDiscoveryUpdated] so the catalog + subscribers + watch-propagator all see the new ids
   * exactly as if the daemon had pushed the notification itself. Idempotent: a no-op when the
   * manifest hasn't changed since the last cycle.
   *
   * Internal so tests can drive it directly without racing the scheduled poll.
   */
  internal fun reloadManifestIfChanged(daemon: SupervisedDaemon) {
    val manifestPath = daemon.manifestPath?.takeIf { it.isNotBlank() } ?: return
    val file = File(manifestPath)
    if (!file.isFile) return
    freshnessMetrics.manifestStats.incrementAndGet()
    val mtime = file.lastModified().takeIf { it > 0L } ?: return
    val addr = DaemonAddr(daemon.workspaceId, daemon.modulePath)
    val previous = manifestState[addr]
    if (previous != null && mtime <= previous.mtimeMs) return

    val hash = runCatching { sha256Hex(file) }.getOrNull() ?: return
    if (previous != null && hash == previous.hash) {
      // mtime moved (e.g. `touch` on previews.json) but bytes didn't — refresh mtime so the
      // next cycle skips the hash, and bail out without redispatching.
      manifestState[addr] = ManifestState(mtime, hash)
      return
    }

    val text =
      runCatching { fileSystem.read(file.path.toPath()) { readUtf8() } }.getOrNull() ?: return
    val previews =
      runCatching {
        val obj = json.parseToJsonElement(text) as? JsonObject ?: return@runCatching null
        (obj["previews"] as? JsonArray)?.mapNotNull { it as? JsonObject }
      }
        .getOrNull() ?: return
    manifestState[addr] = ManifestState(mtime, hash)

    val incomingIds = previews.mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }.toSet()
    val currentIds = catalog[addr]?.keys?.toSet() ?: emptySet()
    val added = previews.filter { (it["id"]?.jsonPrimitive?.contentOrNull ?: "") !in currentIds }
    val changed = previews.filter { (it["id"]?.jsonPrimitive?.contentOrNull ?: "") in currentIds }
    val removed = currentIds.filter { it !in incomingIds }

    if (added.isEmpty() && removed.isEmpty()) return

    freshnessMetrics.manifestRereads.incrementAndGet()
    freshnessMetrics.manifestPreviewsAdded.addAndGet(added.size.toLong())
    freshnessMetrics.manifestPreviewsRemoved.addAndGet(removed.size.toLong())

    val params = buildJsonObject {
      put("added", JsonArray(added))
      put("removed", JsonArray(removed.map { JsonPrimitive(it) }))
      put("changed", JsonArray(changed))
      put("totalPreviews", JsonPrimitive(previews.size))
    }
    onDiscoveryUpdated(daemon, params)
  }

  /**
   * Random-sampling deterministic-render probe — picks a preview at random whose render queue is
   * empty (so we don't compete with a real user request) and fires a `renderNow` past the freshness
   * check. The daemon's frame-hash dedup (JsonRpcServer.kt:993) sets `unchanged: true` when the new
   * bytes match the prior frame for this preview; a missing or `false` flag with no source change
   * between probes means the preview drifted on its own — clock-reading composables, daemon
   * classloader bugs, or build-output drift. Rare by design; the cadence is configurable via the
   * constructor's `samplingIntervalMs`.
   *
   * Module-internal so tests can trigger a probe deterministically (with `samplingIntervalMs = 0`
   * to disable the scheduled invocation) instead of racing the executor's cadence.
   */
  internal fun runRandomSamplingProbe() {
    runCatching {
      val candidates = mutableListOf<Triple<SupervisedDaemon, DaemonAddr, PreviewEntry>>()
      supervisor.listProjects().forEach { project ->
        project.daemons.forEach { (modulePath, daemon) ->
          val addr = DaemonAddr(project.workspaceId, modulePath)
          catalog[addr]?.values?.forEach { entry -> candidates.add(Triple(daemon, addr, entry)) }
        }
      }
      if (candidates.isEmpty()) return@runCatching
      val pick = candidates.random()
      val (daemon, addr, entry) = pick
      val key = PreviewIdKey(addr.workspaceId, addr.modulePath, entry.fqn)
      if (previewQueues.containsKey(key)) {
        freshnessMetrics.samplingSkippedBusy.incrementAndGet()
        return@runCatching
      }
      pendingProbes
        .computeIfAbsent(key) { java.util.concurrent.atomic.AtomicInteger() }
        .incrementAndGet()
      freshnessMetrics.samplingProbes.incrementAndGet()
      runCatching {
        daemon
          .clientForRender(entry.fqn)
          .renderNow(
            previews = listOf(entry.fqn),
            tier = RenderTier.FULL,
            reason = "freshness:sampling",
          )
      }
        .onFailure {
          // Roll back the pending count on a wire failure so a future renderFinished isn't
          // misattributed to a probe that never went out.
          pendingProbes.computeIfPresent(key) { _, c -> if (c.decrementAndGet() <= 0) null else c }
        }
    }
      .onFailure {
        System.err.println("compose-preview-mcp: random-sampling probe failed: ${it.message}")
      }
  }

  private fun resolvePreviewSourceFile(uri: PreviewUri, sourceFile: String?): File? {
    if (sourceFile.isNullOrBlank()) return null
    val direct = File(sourceFile)
    if (direct.isFile) return direct
    val project = supervisor.project(uri.workspaceId) ?: return null
    val moduleDir = moduleDir(project.path, uri.modulePath)
    val fromModule = File(moduleDir, sourceFile)
    if (fromModule.isFile) return fromModule
    return null
  }

  private fun moduleDir(projectRoot: File, modulePath: String): File {
    val trimmed = modulePath.trimStart(':')
    if (trimmed.isEmpty()) return projectRoot
    return File(projectRoot, trimmed.replace(':', File.separatorChar))
  }

  /**
   * Pop the head group of [previewQueues]'s entry for [key], wake its waiters with [outcome], and
   * dispatch the next group's `renderNow` if one is queued. Called from `onRenderFinished` and
   * `onRenderFailed`. The dispatch happens outside the per-key compute lambda so we never hold the
   * lock across IPC. Returns silently if the queue is missing or empty (defensive — the daemon
   * could in principle emit a stray `renderFinished` for a previewId we never queued).
   */
  private fun popHeadAndPromoteNext(
    daemon: SupervisedDaemon,
    key: PreviewIdKey,
    outcome: RenderOutcome,
  ) {
    var poppedFutures: List<java.util.concurrent.CompletableFuture<RenderOutcome>> = emptyList()
    var nextHead: PendingRenderGroup? = null
    previewQueues.compute(key) { _, queue ->
      if (queue == null || queue.isEmpty()) return@compute queue
      poppedFutures = queue.removeFirst().futures.toList()
      nextHead = queue.firstOrNull()?.also { it.sent = true }
      if (queue.isEmpty()) null else queue
    }
    poppedFutures.forEach { it.complete(outcome) }
    val next = nextHead
    if (next != null) {
      // clientForRender's hash routes by previewFqn, same as the original dispatch in
      // awaitNextRender; preserves cache-locality / replica-affinity across promoted groups.
      daemon
        .clientForRender(key.previewId)
        .renderNow(
          previews = listOf(key.previewId),
          tier = RenderTier.FULL,
          overrides = next.overrides,
        )
    }
  }

  // -------------------------------------------------------------------------
  // Tool surface
  // -------------------------------------------------------------------------

  private fun currentToolDefs(session: Session): List<ToolDef> {
    if (fullToolDefsFuture.isDone) {
      return runCatching { fullToolDefsFuture.getNow(bootstrapToolDefs) }
        .getOrDefault(bootstrapToolDefs)
    }
    // Bootstrap path: enroll the session under the lock so the future-completion handler cannot
    // race past it. If the future completed between the outer isDone check and acquiring the lock,
    // serve the full list directly and skip enrollment — the transition has already happened.
    val servedBootstrap =
      synchronized(bootstrapNotifyLock) {
        if (fullToolDefsFuture.isDone) {
          false
        } else {
          bootstrapServedSessions.add(session)
          true
        }
      }
    return if (servedBootstrap) {
      bootstrapToolDefs
    } else {
      runCatching { fullToolDefsFuture.getNow(bootstrapToolDefs) }.getOrDefault(bootstrapToolDefs)
    }
  }

  /** Tools served in the [McpToolProfile.STORYBOOK] profile: the aliases plus `status`. */
  private fun storybookToolDefs(): List<ToolDef> =
    buildFullToolDefs().filter { it.name == "status" || it.name in STORYBOOK_ALIAS_NAMES }

  /** The full tool list for the active [profile] — native-only, or Storybook-only. */
  private fun effectiveFullToolDefs(): List<ToolDef> =
    when (profile) {
      McpToolProfile.NATIVE -> buildFullToolDefs().filterNot { it.name in STORYBOOK_ALIAS_NAMES }
      McpToolProfile.STORYBOOK -> storybookToolDefs()
    }

  /** Bootstrap tools served before the full catalog loads, for the active [profile]. */
  private val bootstrapToolDefs: List<ToolDef> by lazy {
    when (profile) {
      McpToolProfile.NATIVE -> nativeBootstrapToolDefs
      McpToolProfile.STORYBOOK -> storybookToolDefs()
    }
  }

  private val nativeBootstrapToolDefs: List<ToolDef> by lazy {
    listOf(
      ToolDef(
        name = "status",
        description =
          "Report MCP server readiness, tool-catalog loading state, registered projects, and spawned daemon discovery state. Available immediately after initialize.",
        inputSchema = parseSchema("""{"type":"object","properties":{}}"""),
      ),
      ToolDef(
        name = "register_project",
        description =
          "Register a project (workspace) so its previews can be listed and watched. Returns the assigned workspaceId.",
        inputSchema =
          parseSchema(
            """
            {
              "type": "object",
              "properties": {
                "path": {"type": "string", "description": "Absolute path to the project root."},
                "rootProjectName": {"type": "string", "description": "Optional override for the workspace's display name."},
                "modules": {"type": "array", "items": {"type": "string"}, "description": "Optional initial set of preview-eligible Gradle module paths."}
              },
              "required": ["path"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "list_projects",
        description = "List every registered project with its workspaceId, name, and path.",
        inputSchema = parseSchema("""{"type":"object","properties":{}}"""),
      ),
      ToolDef(
        name = "list_devices",
        description =
          "List the `@Preview(device = ...)` ids the daemon's catalog recognises, paired with resolved geometry.",
        inputSchema = parseSchema("""{"type":"object","properties":{}}"""),
      ),
      ToolDef(
        name = "render_preview",
        description =
          "Render a preview by URI, bypassing the in-memory render cache. Returns a token-frugal " +
            "structured observation by default — the compose/semantics snapshot + sha256 + " +
            "dimensions, NO base64 PNG (the snapshot-default for an agent loop; issue #1787). " +
            "Pass `observe=\"png\"` to get the rendered PNG inline when you actually need to see " +
            "pixels, or `observe=\"hash\"` for just the sha + dimensions. " +
            "Pass `force={reason}` only when the freshness probe missed a real edit (this should be rare); " +
            "report each use on https://github.com/yschimke/compose-ai-tools/issues/924. " +
            "Do NOT delete `build/classes/...` or run `./gradlew clean` to chase a stale render.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "uri":{"type":"string","description":"compose-preview://<workspace>/<module>/<fqn>?config=<qualifier>"},
                "observe":{"type":"string","enum":["png","semantics","hash"],"description":"Observation level (issue #1787). Default 'semantics' — the compose/semantics tree + sha256 + dimensions with NO base64, the token-frugal snapshot-default for an agent loop (fetch pixels only when you need them). 'png' returns the base64 image (request it when you need to see pixels); 'hash' returns just sha256 + dimensions."},
                "crop":{"type":"object","description":"Return only ONE element's rectangle instead of the full frame (issue #1817) — far fewer tokens, and it focuses the view on the region you care about (the natural partner to diff_semantics: 'ref X changed' -> crop ref X). Set EITHER a semantic target (ref | testTag | role/text, resolved against compose/semantics) OR explicit render-pixel bounds {left,top,right,bottom}. Honours 'observe': png returns the cropped image (+ region metadata), hash/semantics return the crop's sha + dimensions only.","properties":{"ref":{"type":"string"},"testTag":{"type":"string"},"role":{"type":"string"},"text":{"type":"string"},"left":{"type":"integer"},"top":{"type":"integer"},"right":{"type":"integer"},"bottom":{"type":"integer"}}},
                "overrides":{"type":"object","description":"Optional per-call display overrides."},
                "force":{"type":"object","description":"Sanctioned escape hatch when the freshness probe missed an edit. Forwards fileChanged({kind:\"classpath\"}) before rendering, dropping the daemon's user classloader. Each use is logged + counted; please report on issue #924.","properties":{"reason":{"type":"string","description":"Human-readable reason for needing force (required)."}},"required":["reason"]}
              },
              "required":["uri"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "watch",
        description =
          "Register an area of interest. The server keeps the matched previews warm and pushes notifications/resources/updated as they re-render.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "workspaceId":{"type":"string"},
                "module":{"type":"string","description":"Optional Gradle module path; null = every module in the workspace."},
                "fqnGlob":{"type":"string","description":"Optional FQN glob."},
                "awaitDiscovery":{"type":"boolean"},
                "awaitTimeoutMs":{"type":"integer"}
              },
              "required":["workspaceId"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "notify_file_changed",
        description =
          "Tell every daemon in the matched workspace that a file changed so it can re-run discovery or mark previews stale.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "workspaceId":{"type":"string"},
                "path":{"type":"string","description":"Absolute path of the changed file."},
                "kind":{"type":"string","enum":["source","resource","classpath"],"default":"source"},
                "changeType":{"type":"string","enum":["modified","created","deleted"],"default":"modified"}
              },
              "required":["workspaceId","path"]
            }
            """
              .trimIndent()
          ),
      ),
    )
  }

  private fun buildFullToolDefs(): List<ToolDef> =
    listOf(
      ToolDef(
        name = "status",
        description =
          "Report MCP server readiness, tool-catalog loading state, registered projects, and spawned daemon discovery state.",
        inputSchema = parseSchema("""{"type":"object","properties":{}}"""),
      ),
      ToolDef(
        name = "register_project",
        description =
          "Register a project (workspace) so its previews can be listed and watched. Returns the assigned workspaceId.",
        inputSchema =
          parseSchema(
            """
            {
              "type": "object",
              "properties": {
                "path": {"type": "string", "description": "Absolute path to the project root."},
                "rootProjectName": {"type": "string", "description": "Optional override for the workspace's display name."},
                "modules": {"type": "array", "items": {"type": "string"}, "description": "Optional initial set of preview-eligible Gradle module paths."}
              },
              "required": ["path"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "unregister_project",
        description = "Forget a registered project; tears down its daemons.",
        inputSchema =
          parseSchema(
            """
            {"type":"object","properties":{"workspaceId":{"type":"string"}},"required":["workspaceId"]}
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "list_projects",
        description = "List every registered project with its workspaceId, name, and path.",
        inputSchema = parseSchema("""{"type":"object","properties":{}}"""),
      ),
      ToolDef(
        name = "list_devices",
        description =
          "List the `@Preview(device = ...)` ids the daemon's catalog recognises, paired with " +
            "resolved geometry (widthDp/heightDp/density). Use these as the `device` field of " +
            "`render_preview.overrides` to flip a preview to any catalog device without editing " +
            "annotations. The free-form `spec:parent=…,width=…,height=…,dpi=…,orientation=…` " +
            "grammar is not enumerable and is not returned here — pass it as a `device` override " +
            "and the daemon parses it at resolve-time (`parent=` names one of these ids and " +
            "supplies whatever the string omits; `orientation=` rotates the resolved frame). " +
            "Mirror of every daemon's " +
            "`InitializeResult.capabilities.knownDevices`; read directly from the shared " +
            "`DeviceDimensions` rather than going through a daemon, so it works before any " +
            "daemon has spawned.",
        inputSchema = parseSchema("""{"type":"object","properties":{}}"""),
      ),
      ToolDef(
        name = "render_preview",
        description =
          "Render a preview by URI, bypassing the in-memory render cache. Returns a token-frugal " +
            "structured observation by default (`observe=\"semantics\"`: the compose/semantics tree " +
            "+ sha256 + dimensions, NO base64; issue #1787) — pass `observe=\"png\"` for the rendered " +
            "PNG inline, or `observe=\"hash\"` for just sha + dimensions. " +
            "Optional `overrides` apply per-call display-property overrides (size, density, " +
            "locale, fontScale, uiMode, orientation, device, inspectionMode) plus the connector- " +
            "driven extensions (material3Theme, wallpaper, ambient, focus, keyboard, touchOverlay, " +
            "permissions, remoteCompose, launcherWidget) — see PROTOCOL.md § 5 " +
            "(`renderNow.overrides`). The server validates each set field against the daemon's " +
            "advertised `supportedOverrides` and rejects ones the backend would silently ignore. " +
            "Optional `force={reason}` is the sanctioned escape hatch for when the freshness " +
            "probe missed a real edit — it forwards a `fileChanged({kind:\"classpath\"})` to every " +
            "replica before rendering, dropping the daemon's user classloader. Use of `force` is a " +
            "freshness-logic gap; please post a comment on " +
            "https://github.com/yschimke/compose-ai-tools/issues/924 with the URI, reason, and the " +
            "edit that didn't land. Do NOT delete `build/classes/...` or run `./gradlew clean` to " +
            "chase a stale render.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "uri":{"type":"string","description":"compose-preview://<workspace>/<module>/<fqn>?config=<qualifier>"},
                "observe":{"type":"string","enum":["png","semantics","hash"],"description":"Observation level (issue #1787). Default 'semantics' returns the compose/semantics tree + sha256 + width/height with NO base64 — the token-frugal snapshot-default for a multi-step agent loop (fetch pixels only when you need them). 'png' returns the base64 image (request it when you need to see pixels); 'hash' returns just sha256 + dimensions."},
                "overrides":{
                  "type":"object",
                  "description":"Per-call display overrides. Each field is optional; nulls fall back to the discovery-time RenderSpec. Backends that don't model a field (e.g. desktop has no Android resource qualifier system) ignore it.",
                  "properties":{
                    "widthPx":{"type":"integer","description":"Sandbox width in pixels."},
                    "heightPx":{"type":"integer","description":"Sandbox height in pixels."},
                    "density":{"type":"number","description":"Display density (1.0 = mdpi, 2.0 = xhdpi, etc.)."},
                    "localeTag":{"type":"string","description":"BCP-47 locale tag (e.g. 'en-US', 'fr', 'ja-JP')."},
                    "fontScale":{"type":"number","description":"Font scale multiplier (1.0 = system default)."},
                    "uiMode":{"type":"string","enum":["light","dark"],"description":"Light/dark mode override. Android-only today."},
                    "orientation":{"type":"string","enum":["portrait","landscape"],"description":"Portrait/landscape override. Android-only today."},
                    "device":{"type":"string","description":"@Preview(device=...) string — 'id:pixel_5', 'id:wearos_small_round', 'id:tv_1080p', or a 'spec:' string — 'spec:width=400dp,height=800dp,dpi=320', or 'spec:parent=pixel_tablet,orientation=portrait' where parent supplies whatever the string omits and orientation rotates the resolved frame. Resolved by the daemon's catalog into widthPx/heightPx/density; explicit width/height/density overrides on this same object take precedence."},
                    "captureAdvanceMs":{"type":"integer","description":"Paused-clock advance before capture. Android-only today."},
                    "inspectionMode":{"type":"boolean","description":"Override LocalInspectionMode for this one-shot render. Null/default keeps preview semantics."},
                    "material3Theme":{
                      "type":"object",
                      "description":"Material 3 theme token overrides applied as a normal MaterialTheme wrapper around the preview.",
                      "properties":{
                        "colorScheme":{"type":"object","additionalProperties":{"type":"string"},"description":"Color role names to #RRGGBB or #AARRGGBB, e.g. primary, onPrimary, surface."},
                        "typography":{"type":"object","additionalProperties":{"type":"object","properties":{"fontSizeSp":{"type":"number"},"lineHeightSp":{"type":"number"},"letterSpacingSp":{"type":"number"},"fontWeight":{"type":"integer"},"italic":{"type":"boolean"}}},"description":"Text style names to partial overrides, e.g. bodyLarge, titleMedium, labelSmall."},
                        "shapes":{"type":"object","additionalProperties":{"type":"number"},"description":"Shape token names to rounded corner size in dp, e.g. small, medium, extraLarge."}
                      }
                    },
                    "wallpaper":{
                      "type":"object",
                      "description":"Dynamic-color override. Derives a Material 3 scheme from a seed color and wraps the preview in MaterialTheme(colorScheme=…). material3Theme on the same call still wins per role.",
                      "properties":{
                        "seedColor":{"type":"string","description":"Seed color as #RRGGBB or #AARRGGBB."},
                        "isDark":{"type":"boolean","description":"Force the dark variant; null inherits the host theme's surface luminance."},
                        "paletteStyle":{"type":"string","enum":["tonalSpot","neutral","vibrant","expressive","rainbow","fruitSalad","monochrome","fidelity","content"],"description":"Algorithm variant; null = tonalSpot."},
                        "contrastLevel":{"type":"number","description":"Material 3 contrast in [-1.0,1.0]; 0.0 default, 0.5 medium, 1.0 high."}
                      },
                      "required":["seedColor"]
                    },
                    "ambient":{
                      "type":"object",
                      "description":"Wear OS ambient-state override. Drives the AmbientLifecycleObserver shadow so AmbientAware UI composes under the requested state. Wear-only; other backends ignore it.",
                      "properties":{
                        "state":{"type":"string","enum":["interactive","ambient","inactive"],"description":"Requested ambient state."},
                        "burnInProtectionRequired":{"type":"boolean","description":"Forwarded to onEnterAmbient(...); null = false."},
                        "deviceHasLowBitAmbient":{"type":"boolean","description":"Forwarded to onEnterAmbient(...); null = false."},
                        "updateTimeMillis":{"type":"integer","description":"Synthetic minute-tick timestamp; null uses render-time wall-clock."},
                        "idleTimeoutMs":{"type":"integer","description":"Idle-after-input timeout before restoring the requested state during interactive sessions; null = ~5000."}
                      },
                      "required":["state"]
                    },
                    "focus":{
                      "type":"object",
                      "description":"Focus / keyboard-traversal override. tabIndex focuses the n-th focusable; direction applies one directional step. Backends without a Compose focus owner (e.g. desktop CMP) ignore it.",
                      "properties":{
                        "tabIndex":{"type":"integer","description":"Focus the n-th focusable in tab order."},
                        "direction":{"type":"string","enum":["Next","Previous","Up","Down","Left","Right"],"description":"Single directional traversal step."},
                        "step":{"type":"integer","description":"1-based step index for overlay labels."},
                        "overlay":{"type":"boolean","description":"Draw a stroke + label over the focused element's bounds."},
                        "enterPlacesFocus":{"type":"boolean","description":"Skip the historical +1 Next compensation for focusGroup onEnter patterns."},
                        "pressed":{"type":"boolean","description":"Dispatch an indirect-pointer Press onto the focused element after the walk lands."}
                      }
                    },
                    "keyboard":{
                      "type":"object",
                      "description":"Soft-keyboard (IME) override. Forces band visibility and per-cap press highlight on top of the app's natural IME behaviour.",
                      "properties":{
                        "visible":{"type":"boolean","description":"Force the IME band visible/hidden; null observes the app's natural signals."},
                        "pressedKey":{"type":"string","description":"Highlight a key cap: a single lowercase letter or one of 'space','enter','shift','backspace','sym'."}
                      }
                    },
                    "touchOverlay":{"type":"boolean","description":"Opt-in touch-event visualization (Android 'Show touches' style) for live/recording sessions."},
                    "talkBack":{"type":"boolean","description":"Opt-in TalkBack focus-overlay visualization for recordings: a green focus rectangle, traversal-order badges, and the spoken-announcement caption, walked through the screen's focus stops."},
                    "permissions":{
                      "type":"object",
                      "description":"Android runtime-permissions override. Seeds Robolectric's grant state so checkSelfPermission reads see the requested values. Android-only; desktop ignores it.",
                      "properties":{
                        "grants":{"type":"object","additionalProperties":{"type":"string","enum":["granted","denied"]},"description":"Manifest.permission.* constant string -> grant state."}
                      }
                    },
                    "remoteCompose":{
                      "type":"object",
                      "description":"Remote Compose override. Seeds the profile and named values a RemotePreview{} block reads via LocalRemoteComposeHost. Android-only; desktop ignores it.",
                      "properties":{
                        "profile":{"type":"string","enum":["androidx","androidx7","androidx8","androidx9","widgetsV6","widgetsV7","wearWidgets"],"description":"RcPlatformProfiles variant to compile the remote document against."},
                        "namedValues":{"type":"object","additionalProperties":{"type":"object","properties":{"kind":{"type":"string","enum":["float","dp","int","string","bool","color"]}},"description":"Typed named value, e.g. {kind:'float',value:1.5} or {kind:'color',argb:'#FF0000FF'}."},"description":"Named state seeds keyed by the name user code binds."},
                        "acceptedHostActions":{"type":"array","items":{"type":"string"},"description":"Restrict which HostAction ids the connector captures; null captures all."}
                      }
                    },
                    "launcherWidget":{
                      "type":"object",
                      "description":"Launcher-widget container-size override. Lays the preview out at a whole-cell size on the host's launcher grid (defaults mirror the Pixel launcher: 72dp cells, 8dp gaps, 1×1..5×5).",
                      "properties":{
                        "cells":{"type":"object","properties":{"width":{"type":"integer"},"height":{"type":"integer"}},"required":["width","height"],"description":"Target whole-cell size, clamped into minCells..maxCells."},
                        "cellSizeDp":{"type":"integer","description":"One cell's edge length in dp; null = 72."},
                        "cellSpacingDp":{"type":"integer","description":"Gap between adjacent cells in dp; null = 8."},
                        "minCells":{"type":"object","properties":{"width":{"type":"integer"},"height":{"type":"integer"}},"required":["width","height"],"description":"Inclusive lower bound per axis; null = 1×1."},
                        "maxCells":{"type":"object","properties":{"width":{"type":"integer"},"height":{"type":"integer"}},"required":["width","height"],"description":"Inclusive upper bound per axis; null = 5×5."},
                        "resizeOrder":{"type":"string","enum":["diagonal","widthFirst","heightFirst"],"description":"Hint for a future resize-loop orchestrator; the single-shot connector ignores it."}
                      },
                      "required":["cells"]
                    }
                  }
                },
                "force":{
                  "type":"object",
                  "description":"Sanctioned escape hatch for stale renders. Forwards a fileChanged({kind:\"classpath\"}) to every replica of this URI's daemon before issuing renderNow, dropping the daemon's user classloader. Each use bumps a `forces.used` counter and is logged in `recent` (see `status`). Please report on https://github.com/yschimke/compose-ai-tools/issues/924.",
                  "properties":{
                    "reason":{"type":"string","description":"Human-readable reason for needing force (required). Stored in the recent-forces ring buffer for debugging."}
                  },
                  "required":["reason"]
                },
                "crop":{
                  "type":"object",
                  "description":"Return only ONE element's rectangle instead of the full-frame PNG (issue #1817). Far fewer tokens than a whole screenshot, and it focuses the view on the region you care about — the natural partner to diff_semantics: when the diff says ref X changed, crop just ref X to look. Set EITHER a semantic target (ref | testTag | role/text), resolved against the preview's compose/semantics tree in the same root-pixel space as the image, OR explicit render-pixel bounds {left,top,right,bottom}. Honours 'observe': png returns the cropped image plus a small metadata block (resolved region, ref, source dimensions, sha); hash/semantics return the crop's sha + dimensions only (a region-scoped change signal), and semantics also includes the matched node's semantics subtree.",
                  "properties":{
                    "ref":{"type":"string","description":"Stable ComposeSemanticsNode.ref (the unambiguous handle; survives content edits)."},
                    "testTag":{"type":"string","description":"Modifier.testTag(...) value (must be unique)."},
                    "role":{"type":"string","description":"Accessibility role to match, with or without text."},
                    "text":{"type":"string","description":"Visible text/label to match, with or without role."},
                    "left":{"type":"integer","description":"Explicit crop bounds in render pixels; set all four of left/top/right/bottom."},
                    "top":{"type":"integer"},
                    "right":{"type":"integer"},
                    "bottom":{"type":"integer"}
                  }
                }
              },
              "required":["uri"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "watch",
        description =
          "Register an area of interest. The server keeps the matched previews warm and pushes notifications/resources/updated as they re-render.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "workspaceId":{"type":"string"},
                "module":{"type":"string","description":"Optional Gradle module path; null = every module in the workspace."},
                "fqnGlob":{"type":"string","description":"Optional FQN glob; '*' matches non-dot, '**' matches anything, '?' one non-dot char."},
                "awaitDiscovery":{"type":"boolean","description":"When true, block until every matched daemon has completed initial discovery, then return per-module readiness. Default false preserves non-blocking watch."},
                "awaitTimeoutMs":{"type":"integer","description":"Maximum time to wait when awaitDiscovery=true. Defaults to the server render timeout."}
              },
              "required":["workspaceId"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "unwatch",
        description =
          "Remove watches for the current session. With no args, removes every watch the session registered.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "workspaceId":{"type":"string"},
                "module":{"type":"string"},
                "fqnGlob":{"type":"string"}
              }
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "list_watches",
        description = "List the watches registered by the current session.",
        inputSchema = parseSchema("""{"type":"object","properties":{}}"""),
      ),
      ToolDef(
        name = "notify_file_changed",
        description =
          "Tell every daemon in the matched workspace that a file changed. Forwards a `fileChanged` notification to the daemon so it can re-run discovery / mark previews stale. Use after editing source files outside the MCP server's view (e.g. via a coding agent that doesn't run a file watcher).",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "workspaceId":{"type":"string"},
                "path":{"type":"string","description":"Absolute path of the changed file."},
                "kind":{"type":"string","enum":["source","resource","classpath"],"default":"source"},
                "changeType":{"type":"string","enum":["modified","created","deleted"],"default":"modified"}
              },
              "required":["workspaceId","path"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "history_list",
        description =
          "List historical render entries for a workspace's daemon. Proxies the daemon's `history/list` JSON-RPC method (PROTOCOL.md § 5). Returns newest-first sidecar metadata; pair with `history_read` (or `resources/read` on a `compose-preview-history://` URI) to fetch bytes. Filters mirror the daemon: previewId / since / until / branch / commit / etc.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "workspaceId":{"type":"string"},
                "module":{"type":"string","description":"Gradle module path; required so the supervisor knows which daemon to ask."},
                "previewId":{"type":"string","description":"Optional preview FQN filter (e.g. com.example.RedSquare)."},
                "since":{"type":"string","description":"ISO-8601 lower bound, e.g. 2026-04-30T00:00:00Z."},
                "until":{"type":"string","description":"ISO-8601 upper bound."},
                "limit":{"type":"integer","description":"Default 50, max 500."},
                "cursor":{"type":"string","description":"Opaque pagination token from a previous response."},
                "branch":{"type":"string"},
                "branchPattern":{"type":"string","description":"Regex over branch."},
                "commit":{"type":"string","description":"Long or short SHA."},
                "worktreePath":{"type":"string"},
                "agentId":{"type":"string"},
                "sourceKind":{"type":"string","enum":["fs","git","http"]},
                "sourceId":{"type":"string"}
              },
              "required":["workspaceId","module"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "set_visible",
        description =
          "Override the daemon's visible-preview set for one (workspace, module) directly. The watch propagator's setVisible derives from registered watches; this tool lets an agent express \"these previews are on screen right now\" without a long-lived watch. Sets the daemon's visible filter to the given preview FQNs verbatim. The watch propagator's next recompute (e.g. on `discoveryUpdated` or `watch`/`unwatch`) will replace whatever set_visible set.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "workspaceId":{"type":"string"},
                "module":{"type":"string","description":"Gradle module path."},
                "ids":{"type":"array","items":{"type":"string"},"description":"Preview FQNs (e.g. com.example.PreviewsKt.RedSquare)."}
              },
              "required":["workspaceId","module","ids"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "set_focus",
        description =
          "Override the daemon's focused-preview set. Same shape as set_visible — focus is the higher-priority slice the daemon renders first when its queue drains. Use when an agent is about to read a specific preview and wants to express \"render this one ahead of others\".",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "workspaceId":{"type":"string"},
                "module":{"type":"string"},
                "ids":{"type":"array","items":{"type":"string"}}
              },
              "required":["workspaceId","module","ids"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "history_diff",
        description =
          "Diff two history entries by id (metadata mode only — pixel mode is reserved for daemon phase H5). Returns `{pngHashChanged, fromMetadata, toMetadata}`. Cross-source: `from` and `to` may live on different `HistorySource`s (LocalFs vs git-ref), so this is the load-bearing call for \"did my edit change rendered output vs the version on main?\".",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "workspaceId":{"type":"string"},
                "module":{"type":"string"},
                "from":{"type":"string","description":"Entry id (HistoryEntry.id)."},
                "to":{"type":"string"}
              },
              "required":["workspaceId","module","from","to"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "list_data_products",
        description =
          "Discover the data-product kinds (a11y findings, a11y hierarchy, layout tree, recomposition heat-map, theme resolution, …) the daemon can produce alongside each PNG. Returns one entry per (workspace, module) with the kinds the daemon advertised at initialize-time. Each entry carries `kind`, `schemaVersion`, `transport` (inline|path|both), and three flags: `attachable` (rides renderFinished when subscribed), `fetchable` (callable via get_preview_data), `requiresRerender` (true → fetching may pay a render cost). With no args, lists every spawned daemon; pass `workspaceId` and/or `module` to scope the answer. Empty list = pre-D2 daemon (no producers wired yet) — get_preview_data on such a daemon returns DataProductUnknown. See docs/daemon/DATA-PRODUCTS.md for the kind catalogue.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "workspaceId":{"type":"string","description":"Optional. Restrict the answer to one workspace."},
                "module":{"type":"string","description":"Optional Gradle module path; requires workspaceId."}
              }
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "list_extension_commands",
        description =
          "List preview-extension command ids exposed by the built-in command catalog. These are " +
            "shrinkwrapped shortcuts over generic tools such as get_preview_data, " +
            "render_preview_overlay, and render_preview. Use run_extension_command to invoke one by id.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "agentRecommended":{"type":"boolean","description":"When true, only return commands marked as useful defaults for agents."}
              }
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "enable_extensions",
        description =
          "Activate the named daemon extensions on every spawned daemon for the matching " +
            "(workspace, module). Daemons start with every extension inactive (PROTOCOL.md § 3a) — " +
            "this tool routes through the daemon's `extensions/enable` JSON-RPC and updates the MCP " +
            "supervisor's cached `dataProductCapabilities` / `dataExtensionDescriptors` so " +
            "subsequent `list_data_products` reflects the new public surface without a follow-up " +
            "`extensions/list` round-trip. Returns one entry per (workspace, module) carrying " +
            "`newlyEnabled`, `pulledIn` (deps activated as a side-effect), `alreadyEnabled`, and " +
            "`unknown` (ids not registered on this daemon). Idempotent — re-issuing with the same " +
            "ids reports them under `alreadyEnabled`. Pass `workspaceId` and/or `module` to scope; " +
            "omit both to fan out across every spawned daemon.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "ids":{"type":"array","items":{"type":"string"},"description":"Extension ids to activate (e.g. \"text/strings\", \"resources/used\", \"a11y\"). Use list_data_products afterwards to confirm the resulting public surface."},
                "workspaceId":{"type":"string","description":"Optional. Restrict the enable to one workspace."},
                "module":{"type":"string","description":"Optional Gradle module path; requires workspaceId."}
              },
              "required":["ids"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "run_extension_command",
        description =
          "Run a preview-extension command by id. This keeps high-level shortcuts discoverable " +
            "through list_extension_commands while routing execution through stable generic MCP " +
            "tools. Most commands require `uri`; render/data commands accept `inline`, `overrides`, " +
            "and `params` where the underlying tool supports them.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "commandId":{"type":"string","description":"Extension command id from list_extension_commands."},
                "uri":{"type":"string","description":"compose-preview://<workspace>/<module>/<fqn>?config=<qualifier>"},
                "inline":{"type":"boolean","description":"For data/media commands, return inline content when supported."},
                "params":{"type":"object","description":"Optional data/fetch params, forwarded for data commands."},
                "overrides":{"type":"object","description":"Optional render overrides for render/media commands. Same shape as render_preview.overrides."}
              },
              "required":["commandId"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "get_preview_data",
        description =
          "Fetch one data product (a11y findings, a11y hierarchy, layout tree, …) for a preview. The kind names a structured payload the daemon can produce alongside the PNG; call list_data_products first to see what each daemon advertises. Returns the JSON payload as a single text content block. Auto-renders the preview if it hasn't rendered yet (so the agent doesn't need to call render_preview first). Cache short-circuit: if the kind has been subscribed (subscribe_preview_data) or globally attached (--attach-data-product server flag), the latest renderFinished payload is served from an in-memory cache with zero daemon round-trip — the response carries `cached: true`. When the daemon's latest render didn't compute the kind and it's not cached, the daemon may queue a re-render in the right mode; this is bounded by the daemon's per-request budget (DataProductBudgetExceeded if exceeded). `inline` defaults to true so the agent gets JSON back rather than a path it may not be able to read; flip to false on local-FS callers that prefer to read sibling files directly.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "uri":{"type":"string","description":"compose-preview://<workspace>/<module>/<fqn>?config=<qualifier>"},
                "kind":{"type":"string","description":"Data-product kind, e.g. a11y/hierarchy, a11y/atf, layout/inspector, compose/semantics, test/failure."},
                "params":{"type":"object","description":"Optional per-kind parameters (e.g. {nodeId} for layout/inspector). Forwarded verbatim to the daemon's data/fetch."},
                "inline":{"type":"boolean","description":"Default true. When false, the daemon returns a `path` to a sibling JSON file instead of inlining the payload."}
              },
              "required":["uri","kind"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "diff_semantics",
        description =
          "Diff the Compose semantics trees of two live previews and report what changed semantically — the cheap, deterministic, pixel-free regression signal (the analogue of Playwright's aria-snapshot diff). Fetches compose/semantics for `baseUri` and `headUri` (two compose-preview:// URIs, auto-rendering each if needed), matches nodes by their stable `ref`, and returns added / removed / changed-field deltas as JSON plus a one-line summary. Use it to answer 'what changed?' between two rendered previews without reading two PNGs — e.g. the same preview before and after an edit, or two related previews. Text/label changes show up as field changes on the same ref (not remove+add); positional bounds churn is ignored (that's the pixel diff's job). Cheaper than reading screenshots — prefer it for copy/label/role/overflow regressions. (Diffing against a compose-preview-history:// baseline needs the semantics payload persisted per history entry — tracked as a follow-up.)",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "baseUri":{"type":"string","description":"Baseline live preview URI (compose-preview://<workspace>/<module>/<fqn>)."},
                "headUri":{"type":"string","description":"Candidate live preview URI (compose-preview://...) to compare against the baseline."}
              },
              "required":["baseUri","headUri"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "render_matrix",
        description =
          "Render one preview across a cross-product of display axes in a single call and return a token-frugal per-cell summary — for 'does this survive small screen + RTL + large font?' without looping render_preview and reading N PNGs (issue #1788). `axes` sets any of device / locale / uiMode / fontScale (each a non-empty array); the result has one cell per combination with its `overrides`, `label`, `sha256`, `widthPx`/`heightPx`, and `changed` (sha differs from the first cell — the quick 'which configs render differently?' signal). No base64 by default; fetch a specific cell's pixels with render_preview + those overrides when you need to look, or set `contactSheet:true` to also get one stitched grid image of every cell. Bounded at 24 cells; narrow the axes if you exceed it. Pairs with diff_semantics for per-cell structural diffs.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "uri":{"type":"string","description":"compose-preview://<workspace>/<module>/<fqn>"},
                "axes":{
                  "type":"object",
                  "description":"Cross-product axes; set at least one. Each is a non-empty array.",
                  "properties":{
                    "device":{"type":"array","items":{"type":"string"},"description":"@Preview(device=...) ids/specs, e.g. ['id:pixel_5','id:pixel_tablet']."},
                    "locale":{"type":"array","items":{"type":"string"},"description":"BCP-47 locale tags, e.g. ['en','ar','ja-JP']."},
                    "uiMode":{"type":"array","items":{"type":"string","enum":["light","dark"]}},
                    "fontScale":{"type":"array","items":{"type":"number"},"description":"Font-scale multipliers, e.g. [1.0, 2.0]."}
                  }
                },
                "contactSheet":{"type":"boolean","description":"When true, also return a single stitched contact-sheet PNG (one labelled tile per cell) alongside the per-cell summary. Default false (token-frugal: hashes only)."}
              },
              "required":["uri","axes"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "subscribe_preview_data",
        description =
          "Subscribe to a data-product kind for one preview. While subscribed, every renderFinished for the preview produces the kind alongside the PNG (subject to the daemon's producer wiring). Useful when the agent expects to ask repeatedly about the same preview — pre-computing on render avoids the get_preview_data re-render cost. Subscriptions are sticky-while-visible: the daemon drops them automatically when the preview leaves the most recent set_visible set, so re-subscribe when the preview returns to view. Idempotent. Errors: DataProductUnknown if the kind isn't advertised or isn't attachable. NOTE: today, MCP doesn't push the attached payload to clients automatically — agents still call get_preview_data to read it; the subscribe just primes the daemon-side cache.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "uri":{"type":"string","description":"compose-preview://<workspace>/<module>/<fqn>"},
                "kind":{"type":"string"}
              },
              "required":["uri","kind"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "unsubscribe_preview_data",
        description =
          "Drop a subscription installed by subscribe_preview_data. Idempotent — unsubscribing a kind that was never subscribed returns ok.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "uri":{"type":"string"},
                "kind":{"type":"string"}
              },
              "required":["uri","kind"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "render_preview_overlay",
        description =
          "Render a preview and return the annotated overlay PNG instead of (or alongside) the bare " +
            "screenshot. Drives the daemon's image-processor surface — when `kind` is `a11y/overlay` " +
            "(the default), the response carries a base64-encoded image with ATF findings and " +
            "Paparazzi-style accessibility legend painted on top. " +
            "Use this when you want a single tool call that (1) triggers a render in the right mode, " +
            "(2) lets the producer compose its derived image, and (3) hands you back the bytes — no " +
            "separate `render_preview` + `get_preview_data` round trip. The overlay PNG also lands on " +
            "disk under `<dataDir>/<previewId>/a11y-overlay.png` so callers that prefer the path can " +
            "set `inline=false`. " +
            "Errors: DataProductUnknown when the daemon has no producer for `kind` (for example, " +
            "the a11y data plugin is not enabled).",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "uri":{"type":"string","description":"compose-preview://<workspace>/<module>/<fqn>?config=<qualifier>"},
                "kind":{"type":"string","description":"Overlay kind. Default 'a11y/overlay'. Anything advertised by list_data_products with media-bearing extras can be used here.","default":"a11y/overlay"},
                "inline":{"type":"boolean","description":"Default true. When true, returns the overlay bytes as a base64 image content block. When false, returns the on-disk path only."},
                "overrides":{
                  "type":"object",
                  "description":"Per-call display overrides forwarded to render_preview. Same shape as render_preview.overrides.",
                  "properties":{
                    "widthPx":{"type":"integer"},
                    "heightPx":{"type":"integer"},
                    "density":{"type":"number"},
                    "localeTag":{"type":"string"},
                    "fontScale":{"type":"number"},
                    "uiMode":{"type":"string","enum":["light","dark"]},
                    "orientation":{"type":"string","enum":["portrait","landscape"]},
                    "device":{"type":"string"},
                    "captureAdvanceMs":{"type":"integer"},
                    "inspectionMode":{"type":"boolean"}
                  }
                }
              },
              "required":["uri"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "get_preview_extras",
        description =
          "List the extra (non-JSON) outputs the producer wrote alongside a data product — typically " +
            "PNGs like the a11y overlay. Returns one entry per extra: `{name, path, mediaType?, sizeBytes?}`. " +
            "Hits the in-memory cache when the kind is subscribed/attached, otherwise round-trips a " +
            "data/fetch with `inline=false` to pick up the path-shaped result and its `extras`. Use this " +
            "when a panel UI wants to enumerate everything a producer made available without committing " +
            "to one transport (e.g. show a thumbnail of `overlay` alongside the JSON viewer).",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "uri":{"type":"string"},
                "kind":{"type":"string","description":"Data-product kind whose extras to enumerate. Use list_data_products to discover candidates."}
              },
              "required":["uri","kind"]
            }
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "record_preview",
        description =
          "Record a scripted screen-recording of an interactive preview. Drives the daemon's " +
            "recording surface (RECORDING.md) end-to-end: open a " +
            "held-scene session at the requested fps + scale, post the script of `(tMs, kind, " +
            "pixelX, pixelY)` events, play back the timeline at virtual frame time, and encode to " +
            "APNG/MP4/WebM on disk. " +
            "**Token-frugal default (issue #1860).** `observe` defaults to `frames`: the result is " +
            "the structured per-frame observation (hashes + changed-frame indices + on-disk paths), " +
            "NOT the inline media — a recording's base64 bytes scale with fps × duration and can " +
            "dwarf a single PNG. Pass `observe=\"media\"` to also get the encoded bytes inline (the " +
            "pre-#1860 behaviour); the artifact is always on disk at `videoPath` either way. " +
            "**Why virtual time matters.** Pointer events and `scene.render` both key off the " +
            "session's virtual nanoTime, so a script of `(tMs=0, click) + (tMs=500, click)` always " +
            "produces 500ms of inter-click animation in the output regardless of how long the " +
            "agent took to assemble the script. `LaunchedEffect`, `withFrameNanos`, and " +
            "`rememberInfiniteTransition` advance with virtual time, not wall-clock — agents can " +
            "compose a multi-second timeline in milliseconds of agent latency and the video plays " +
            "back at human cadence. " +
            "**Verification metadata.** The text metadata block includes `frames[]` with per-frame " +
            "paths, SHA-256 hashes, and changed-pixel counts from the previous frame, plus " +
            "`changedFrameCount` / first-last changed-frame paths so agents can assert that a " +
            "click or scroll changed UI without decoding APNG/MP4/WebM bytes. " +
            "**Component previews.** Pass `overrides.{widthPx,heightPx,backgroundColor}` to record " +
            "a button-sized preview at native size with a custom background; raise `scale` to " +
            "upsample for legibility. Pointer coords always reference image-natural pixels, never " +
            "the scaled output canvas. " +
            "**Errors.** MethodNotFound when the daemon's host doesn't support recording (today: " +
            "Android backend, missing previewSpecResolver); InvalidParams on out-of-range fps / " +
            "scale or unknown previewId.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "uri":{"type":"string","description":"compose-preview://<workspace>/<module>/<fqn>?config=<qualifier>"},
                "fps":{"type":"integer","description":"Frames per second of the virtual clock. Default 30; range [1, 120]."},
                "scale":{"type":"number","description":"Output-frame size multiplier. Default 1.0; range (0, 8]. Pointer coords stay in image-natural pixel space."},
                "format":{"type":"string","enum":["apng","gif","mp4","webm"],"description":"Encoded recording format. Default 'apng'. 'apng' and 'gif' are always available (pure-JVM); 'gif' is the friendliest for inline playback in chat / GitHub comments. 'mp4' and 'webm' require an ffmpeg binary on the daemon's PATH; check ServerCapabilities.recordingFormats first or expect a clean rejection if unavailable."},
                "observe":{"type":"string","enum":["frames","media"],"description":"Observation level (issue #1860). Default 'frames' returns the structured per-frame observation — per-frame sha256 + changed-pixel counts, changedFrameCount, and the on-disk frame/video paths — with NO inline media (token-frugal; recording bytes scale with fps × duration). 'media' also returns the encoded APNG/MP4/WebM bytes inline (APNG as an image block, mp4/webm as an embedded resource). The artifact is on disk at 'videoPath' regardless of this flag."},
                "emitTest":{"type":"boolean","description":"Default false. When true, also return a runnable Compose UI test generated from this interaction (issue #1786) as an extra text block — each event with a testTag/role/text target becomes an onNodeWith…().performClick() step, and each recording.probe is diffed against the previous probe's captured semantics into assertExists()/assertDoesNotExist() assertions (a TODO stub when nothing assertable was captured). Write it to src/test and review the inferred probe assertions."},
                "events":{
                  "type":"array",
                  "description":"Scripted timeline. Empty array records a single bootstrap frame.",
                  "items":{
                    "type":"object",
                    "properties":{
                      "tMs":{"type":"integer","description":"Virtual time offset from recording/start, in milliseconds. Must be ≥ 0."},
                      "kind":{"type":"string","description":"Namespaced script-event id from `list_data_products`. Every event — input (`input.click`, `input.pointerDown`, `input.rotaryScroll`, …), accessibility actions (`a11y.action.click`, …), lifecycle (`lifecycle.pause`/`resume`/`stop`), state (`state.recreate`/`save`/`restore`), `preview.reload`, `recording.probe` — is advertised in the daemon's `dataExtensions[].recordingScriptEvents[]`. Only entries with `supported = true` are accepted; `supported = false` entries are roadmap and rejected up front."},
                      "pixelX":{"type":"integer","description":"X coord in image-natural pixel space (the preview's own widthPx)."},
                      "pixelY":{"type":"integer","description":"Y coord in image-natural pixel space."},
                      "target":{"type":"object","description":"For pointer events (`input.click`, `input.pointerDown`/`Move`/`Up`, `input.rotaryScroll`) — a stable semantic handle resolved server-side to the node's centre instead of pixel coordinates (issue #1784). Set exactly one of `ref` (the compose/semantics node ref), `testTag` (a Modifier.testTag value), or `role`/`text` (accessibility role and/or visible text). Explicit pixelX/pixelY win when both are present; an unresolved target surfaces as `unsupported` script evidence.","properties":{"ref":{"type":"string"},"testTag":{"type":"string"},"role":{"type":"string"},"text":{"type":"string"}}},
                      "scrollDeltaY":{"type":"number","description":"For 'rotaryScroll'."},
                      "keyCode":{"type":"string","description":"For 'keyDown'/'keyUp' (reserved; v1 dispatch is a no-op)."},
                      "label":{"type":"string","description":"Agent label copied into scriptEvents evidence for probes/checkpoints."},
                      "checkpointId":{"type":"string","description":"Checkpoint id for state save/restore audit events."},
                      "lifecycleEvent":{"type":"string","description":"Lifecycle transition for lifecycle script events, e.g. resume, pause, destroy."},
                      "tags":{"type":"array","items":{"type":"string"},"description":"Optional agent tags copied into scriptEvents evidence."},
                      "nodeContentDescription":{"type":"string","description":"For the *targeted* `a11y.action.*` kinds (click, activate, longClick, focus, expand, collapse, dismiss, scroll*) — visible content description of the target accessibility node (`Modifier.semantics { contentDescription = ... }` / `Icon(contentDescription = ...)`). The daemon resolves this against the held composition's semantics tree and dispatches the corresponding SemanticsActions action — same lookup a screen reader walks via AccessibilityNodeInfo.performAction. Not required (and ignored) for the targetless linear-navigation verbs `a11y.action.next` / `a11y.action.previous`, which walk a session focus cursor through the focus stops in traversal order, and for input/probe/state/lifecycle events."},
                      "selector":{"type":"object","description":"For `uia.*` kinds — multi-axis BySelector-style predicate matching androidx.test.uiautomator's `By` factory. Optional fields (every axis is missing-means-not-filtered): `text` / `desc` / `clazz` / `res` (exact match) plus `textMatches` / `descMatches` / `clazzMatches` / `resMatches` (regex); boolean state predicates `enabled` / `clickable` / `longClickable` / `checkable` / `checked` / `selected` / `focused` / `scrollable`; tree predicates `hasChild` / `hasDescendant` (arrays of nested selectors). Ignored for non-`uia.*` events. See data-uiautomator-core's `SelectorJson` for the full schema."},
                      "useUnmergedTree":{"type":"boolean","description":"For `uia.*` kinds — `false` (default) walks Compose's merged accessibility tree (matches on-device UIAutomator semantics: `By.text + click` targets `Button { Text(...) }` as one node); `true` walks the unmerged tree to reach inner Compose nodes."},
                      "inputText":{"type":"string","description":"For `uia.inputText` only — the text to type into the matched editable node via SemanticsActions.SetText (Compose) or ACTION_SET_TEXT (View). Required for `uia.inputText`; ignored for other kinds."}
                    },
                    "required":["tMs","kind"]
                  }
                },
                "overrides":{
                  "type":"object",
                  "description":"Per-call display overrides applied to the held scene. Same shape as render_preview.overrides.",
                  "properties":{
                    "widthPx":{"type":"integer"},
                    "heightPx":{"type":"integer"},
                    "density":{"type":"number"},
                    "localeTag":{"type":"string"},
                    "fontScale":{"type":"number"},
                    "uiMode":{"type":"string","enum":["light","dark"]},
                    "orientation":{"type":"string","enum":["portrait","landscape"]},
                    "device":{"type":"string"},
                    "captureAdvanceMs":{"type":"integer"},
                    "inspectionMode":{"type":"boolean"}
                  }
                }
              },
              "required":["uri","events"]
            }
            """
              .trimIndent()
          ),
      ),
      // ---------------------------------------------------------------------
      // Storybook-MCP-compatible aliases (issue: storybook downstream adoption)
      //
      // Storybook shipped an official MCP server (GA in Storybook 10.3) whose tool NAMES an agent's
      // harness learns. These kebab-named aliases map that vocabulary onto our catalog/render/a11y
      // capabilities and accept a Storybook **story id** (minted by [StorybookMcp]) wherever we'd
      // take a `compose-preview://` URI — so a Storybook-MCP-trained agent drives this server
      // unmodified. Each routes to an existing `tool…()` handler; a raw native URI is still
      // accepted.
      // ---------------------------------------------------------------------
      ToolDef(
        name = "list-all-documentation",
        description =
          "Storybook-compatible: list every catalogued preview as a Storybook story — its stable " +
            "`id` (title--name), `title`, `name`, synthetic `importPath`, and the native " +
            "compose-preview `uri`. The story-catalog equivalent of Storybook's " +
            "`list-all-documentation`; use the returned ids with `preview-stories`, " +
            "`get-documentation-for-story`, and `run-story-tests`.",
        inputSchema = parseSchema("""{"type":"object","properties":{}}"""),
      ),
      ToolDef(
        name = "get-documentation-for-story",
        description =
          "Storybook-compatible: return one story's metadata — id, title, name, the native " +
            "compose-preview `uri`, and its workspace/module/fqn. Accepts a story id from " +
            "`list-all-documentation` (or a raw compose-preview URI) as `storyId`/`id`. Render it " +
            "with `preview-stories`; check accessibility with `run-story-tests`.",
        inputSchema =
          parseSchema(
            """
            {"type":"object","properties":{"storyId":{"type":"string","description":"Story id from list-all-documentation, or a raw compose-preview:// URI."},"id":{"type":"string","description":"Alias for storyId."}}}
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "preview-stories",
        description =
          "Storybook-compatible: render one or more stories in isolation and return the images. " +
            "Maps to `render_preview` per story. Pass `storyIds` (array) or a single `storyId` " +
            "(ids from `list-all-documentation`, or raw compose-preview URIs). `observe` defaults " +
            "to 'png' (the rendered image); 'semantics'/'hash' return the token-frugal structured " +
            "observation instead. Optional `overrides` are the same per-call display overrides as " +
            "`render_preview.overrides`.",
        inputSchema =
          parseSchema(
            """
            {"type":"object","properties":{"storyIds":{"type":"array","items":{"type":"string"},"description":"Story ids from list-all-documentation, or raw compose-preview:// URIs."},"storyId":{"type":"string","description":"A single story id, if not using storyIds."},"observe":{"type":"string","enum":["png","semantics","hash"],"description":"Default 'png'."},"overrides":{"type":"object","description":"Optional per-call display overrides, same shape as render_preview.overrides."}}}
            """
              .trimIndent()
          ),
      ),
      ToolDef(
        name = "run-story-tests",
        description =
          "Storybook-compatible: run a story's tests and return structured results. Maps to our " +
            "scripted-recording assertions (record_preview) — the compose analogue of Storybook's " +
            "play-function + expect. Pass a `script`: a record_preview event timeline where " +
            "`input.*` events drive the UI and `assert.visible`/`assert.notVisible`/" +
            "`assert.textEquals`/`assert.a11y`/`assert.pixels` events check it (each records " +
            "APPLIED/FAILED). With NO script it runs an accessibility smoke (`preview.reload` + " +
            "`assert.a11y`). Set `emitTest` to also get a generated Compose UI test. Assertion " +
            "support is backend-dependent (desktop vs Android — see issue #2519). Accepts a story " +
            "id from `list-all-documentation` (or a raw compose-preview URI) as `storyId`/`id`.",
        inputSchema =
          parseSchema(
            """
            {"type":"object","properties":{"storyId":{"type":"string","description":"Story id from list-all-documentation, or a raw compose-preview:// URI."},"id":{"type":"string","description":"Alias for storyId."},"script":{"type":"array","description":"Optional record_preview event timeline (input.* to drive, assert.* to check). Omit to run an accessibility smoke test.","items":{"type":"object"}},"emitTest":{"type":"boolean","description":"Also return a runnable Compose UI test generated from the interaction."},"observe":{"type":"string","enum":["frames","media"],"description":"record_preview observation level; default 'frames' (structured per-frame + per-assertion evidence, no inline media)."},"format":{"type":"string","enum":["apng","gif","mp4","webm"],"description":"Recording format for the artifact; default 'apng'."}}}
            """
              .trimIndent()
          ),
      ),
    ) + (uiBuilderMcp?.toolDefs() ?: emptyList())

  private fun handleCallTool(
    session: Session,
    name: String,
    arguments: JsonElement?,
  ): CallToolResult {
    val args = (arguments as? JsonObject) ?: JsonObject(emptyMap())
    return when (name) {
      "status" -> toolStatus()
      "register_project" -> toolRegisterProject(args)
      "unregister_project" -> toolUnregisterProject(args)
      "list_projects" -> toolListProjects()
      "list_devices" -> toolListDevices()
      "render_preview" -> toolRenderPreview(args)
      "render_matrix" -> toolRenderMatrix(args)
      "watch" -> toolWatch(session, args)
      "unwatch" -> toolUnwatch(session, args)
      "list_watches" -> toolListWatches(session)
      "notify_file_changed" -> toolNotifyFileChanged(args)
      "set_visible" -> toolSetVisible(args)
      "set_focus" -> toolSetFocus(args)
      "history_list" -> toolHistoryList(args)
      "history_diff" -> toolHistoryDiff(args)
      "list_data_products" -> toolListDataProducts(args)
      "enable_extensions" -> toolEnableExtensions(args)
      "list_extension_commands" -> toolListExtensionCommands(args)
      "run_extension_command" -> toolRunExtensionCommand(args)
      "get_preview_data" -> toolGetPreviewData(args)
      "diff_semantics" -> toolDiffSemantics(args)
      "render_preview_overlay" -> toolRenderPreviewOverlay(args)
      "get_preview_extras" -> toolGetPreviewExtras(args)
      "subscribe_preview_data" -> toolDataSubOrUnsub(session, args, subscribe = true)
      "unsubscribe_preview_data" -> toolDataSubOrUnsub(session, args, subscribe = false)
      "record_preview" -> toolRecordPreview(args)
      // Storybook-MCP-compatible aliases → existing handlers via the story-id adapter.
      "list-all-documentation" -> toolStorybookListDocs()
      "get-documentation-for-story" -> toolStorybookGetDoc(args)
      "preview-stories" -> toolStorybookPreviewStories(args)
      "run-story-tests" -> toolStorybookRunTests(args)
      else ->
        if (profile == McpToolProfile.NATIVE) {
          uiBuilderMcp?.handle(name, args) ?: errorCallToolResult("unknown tool: $name")
        } else {
          errorCallToolResult("unknown tool: $name")
        }
    }
  }

  // -------------------------------------------------------------------------
  // Storybook-MCP-compatible alias handlers (see [StorybookMcp]). Each takes a Storybook story id
  // (or a raw compose-preview URI) and routes to an existing handler via the id→URI adapter.
  // -------------------------------------------------------------------------

  /** `list-all-documentation`: the whole catalog presented as Storybook stories. */
  private fun toolStorybookListDocs(): CallToolResult {
    val stories = StorybookMcp.stories(catalogResources())
    val payload = buildJsonObject {
      put("schema", "compose-preview-mcp-storybook/v1")
      put("count", stories.size)
      putJsonArray("stories") {
        stories.forEach { s ->
          add(
            buildJsonObject {
              put("id", s.storyId)
              put("title", s.title)
              put("name", s.name)
              put("type", "story")
              put("importPath", "virtual:compose-preview/${s.fqn}")
              put("uri", s.uri)
            }
          )
        }
      }
    }
    return textCallToolResult(payload.toString())
  }

  /** `get-documentation-for-story`: one story's metadata (id, title, name, native URI, coords). */
  private fun toolStorybookGetDoc(args: JsonObject): CallToolResult {
    val id =
      (args["storyId"] ?: args["id"])?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("get-documentation-for-story: missing 'storyId'")
    val story =
      StorybookMcp.stories(catalogResources()).firstOrNull { it.storyId == id || it.uri == id }
        ?: return errorCallToolResult("get-documentation-for-story: no such story: $id")
    val parsed = PreviewUri.parseOrNull(story.uri)
    val payload = buildJsonObject {
      put("schema", "compose-preview-mcp-storybook/v1")
      put("id", story.storyId)
      put("title", story.title)
      put("name", story.name)
      put("uri", story.uri)
      put("importPath", "virtual:compose-preview/${story.fqn}")
      if (parsed != null) {
        put("workspaceId", parsed.workspaceId.value)
        put("module", parsed.modulePath)
        put("fqn", parsed.previewFqn)
        parsed.config?.let { put("config", it) }
      }
      put(
        "note",
        "Render with preview-stories; check accessibility with run-story-tests. Native tools " +
          "(render_preview, get_preview_data, …) accept `uri` directly.",
      )
    }
    return textCallToolResult(payload.toString())
  }

  /** `preview-stories`: render one or more stories in isolation and return the images. */
  private fun toolStorybookPreviewStories(args: JsonObject): CallToolResult {
    val ids =
      storybookStoryIds(args)
        ?: return errorCallToolResult("preview-stories: provide 'storyIds' (array) or 'storyId'")
    val observe = args["observe"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "png"
    val overrides = args["overrides"]
    val resources = catalogResources()
    val blocks = mutableListOf<ContentBlock>()
    var anyOk = false
    var anyError = false
    for (id in ids) {
      val uri = StorybookMcp.resolveUri(id, resources)
      if (uri == null) {
        blocks.add(ContentBlock.Text("preview-stories: no such story: $id"))
        anyError = true
        continue
      }
      blocks.add(ContentBlock.Text("story: $id → $uri"))
      val sub = buildJsonObject {
        put("uri", uri)
        put("observe", observe)
        if (overrides != null) put("overrides", overrides)
      }
      val res = toolRenderPreview(sub)
      blocks.addAll(res.content)
      if (res.isError == true) anyError = true else anyOk = true
    }
    // Error only when nothing rendered — a partial success still returns the frames that worked.
    return CallToolResult(content = blocks, isError = !anyOk && anyError)
  }

  /**
   * `run-story-tests`: enable a11y, render, and return the ATF accessibility findings for a story.
   */
  private fun toolStorybookRunTests(args: JsonObject): CallToolResult {
    val id =
      (args["storyId"] ?: args["id"])?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("run-story-tests: missing 'storyId'")
    val uri =
      StorybookMcp.resolveUri(id, catalogResources())
        ?: return errorCallToolResult("run-story-tests: no such story: $id")
    // Our recording-assertion surface IS the play-function + expect equivalent: an `input.*` +
    // `assert.*` timeline evaluated against the held scene. Delegate to record_preview, which
    // drives
    // the script and records each assertion APPLIED/FAILED. With no script, run an a11y smoke so a
    // bare `run-story-tests <id>` still returns a meaningful check.
    val events = (args["script"] as? JsonArray) ?: defaultA11ySmokeScript
    return toolRecordPreview(
      buildJsonObject {
        put("uri", uri)
        put("events", events)
        put("observe", args["observe"]?.jsonPrimitive?.contentOrNull ?: "frames")
        args["emitTest"]?.let { put("emitTest", it) }
        args["format"]?.let { put("format", it) }
      }
    )
  }

  /**
   * Default `run-story-tests` script when the caller gives none: reload the scene, then assert no
   * accessibility findings — the a11y-smoke degenerate case of the interaction+assertion timeline.
   */
  private val defaultA11ySmokeScript: JsonArray = buildJsonArray {
    add(
      buildJsonObject {
        put("tMs", 0)
        put("kind", "preview.reload")
      }
    )
    add(
      buildJsonObject {
        put("tMs", 100)
        put("kind", "assert.a11y")
      }
    )
  }

  /**
   * One or many story ids from `storyIds` (array) or `storyId`/`id`. Null when neither is present.
   */
  private fun storybookStoryIds(args: JsonObject): List<String>? {
    (args["storyIds"] as? JsonArray)?.let { arr ->
      val ids = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it.isNotBlank() }
      return ids.ifEmpty { null }
    }
    val single =
      (args["storyId"] ?: args["id"])?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    return single?.let { listOf(it) }
  }

  private fun toolStatus(): CallToolResult {
    val catalogState =
      when {
        fullToolCatalogError != null -> "failed"
        fullToolDefsFuture.isDone -> "ready"
        else -> "loading"
      }
    val fullToolCount =
      if (catalogState == "ready") {
        runCatching { fullToolDefsFuture.getNow(emptyList()).size }.getOrDefault(0)
      } else {
        null
      }
    val payload = buildJsonObject {
      put("schema", "compose-preview-mcp-status/v1")
      put("ready", true)
      putJsonObject("toolCatalog") {
        put("status", catalogState)
        put("bootstrapToolCount", bootstrapToolDefs.size)
        fullToolCount?.let { put("fullToolCount", it) }
        put("delayed", fullToolCatalogWasDelayed.get())
        fullToolCatalogError?.let { put("error", it) }
      }
      putJsonArray("projects") {
        supervisor.listProjects().forEach { project ->
          add(
            buildJsonObject {
              put("workspaceId", project.workspaceId.value)
              put("rootProjectName", project.rootProjectName)
              put("path", project.path.absolutePath)
              putJsonArray("modules") {
                synchronized(project.knownModules) {
                  project.knownModules.forEach { add(JsonPrimitive(it)) }
                }
              }
              putJsonArray("daemons") {
                project.daemons.forEach { (module, daemon) ->
                  add(
                    buildJsonObject {
                      put("module", module)
                      put("spawned", daemon.replicaCount() > 0)
                      put("initialDiscoveryComplete", daemon.initialDiscoveryComplete)
                      put(
                        "previewCount",
                        catalog[DaemonAddr(project.workspaceId, module)]?.size ?: 0,
                      )
                    }
                  )
                }
              }
            }
          )
        }
      }
      put("freshness", freshnessMetrics.toJson())
    }
    return textCallToolResult(payload.toString())
  }

  private fun toolRegisterProject(args: JsonObject): CallToolResult {
    val path =
      args["path"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("register_project: missing 'path'")
    val rootName = args["rootProjectName"]?.jsonPrimitive?.contentOrNull
    val modules =
      (args["modules"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    val file = File(path)
    if (!file.isDirectory)
      return errorCallToolResult("register_project: '$path' is not a directory")
    val project = supervisor.registerProject(file, rootName, modules)
    val payload = buildJsonObject {
      put("workspaceId", project.workspaceId.value)
      put("rootProjectName", project.rootProjectName)
      put("path", project.path.absolutePath)
      putJsonArray("modules") { project.knownModules.forEach { add(JsonPrimitive(it)) } }
    }
    sessions.forEach { it.notifyResourceListChanged() }
    return CallToolResult(content = listOf(ContentBlock.Text(payload.toString())))
  }

  private fun toolUnregisterProject(args: JsonObject): CallToolResult {
    val ws =
      args["workspaceId"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("unregister_project: missing 'workspaceId'")
    val id = WorkspaceId(ws)
    supervisor.unregisterProject(id)
    catalog.keys.removeIf { it.workspaceId == id }
    sessions.forEach { it.notifyResourceListChanged() }
    return textCallToolResult("unregistered $id")
  }

  private fun toolListProjects(): CallToolResult {
    val payload = buildJsonObject {
      putJsonArray("projects") {
        supervisor.listProjects().forEach { project ->
          add(
            buildJsonObject {
              put("workspaceId", project.workspaceId.value)
              put("rootProjectName", project.rootProjectName)
              put("path", project.path.absolutePath)
              putJsonArray("modules") {
                synchronized(project.knownModules) {
                  project.knownModules.forEach { add(JsonPrimitive(it)) }
                }
              }
              put("branch", JsonPrimitive(detectBranch(project.path)))
            }
          )
        }
      }
    }
    return CallToolResult(content = listOf(ContentBlock.Text(payload.toString())))
  }

  /**
   * `list_devices` MCP tool — returns the daemon's `DeviceDimensions` catalog projected to `{id,
   * widthDp, heightDp, density}`. Reads directly from the shared `:daemon:core` `Device Dimensions`
   * object rather than round-tripping through a daemon's `InitializeResult.
   * capabilities.knownDevices`. Same data either way (the daemon's
   * `JsonRpcServer.buildKnownDevices` pulls from the same source); reading directly avoids forcing
   * a daemon spawn just to enumerate the catalog. If a future change makes the daemon-advertised
   * catalog backend-specific, this tool will need to consult a specific daemon —
   * `KNOWN_DEVICE_IDS`'s kdoc flags that.
   */
  private fun toolListDevices(): CallToolResult {
    val payload = buildJsonObject {
      putJsonArray("devices") {
        ee.schimke.composeai.daemon.devices.DeviceDimensions.KNOWN_DEVICE_IDS.sorted().forEach { id
          ->
          val spec = ee.schimke.composeai.daemon.devices.DeviceDimensions.resolve(id)
          add(
            buildJsonObject {
              put("id", id)
              put("widthDp", spec.widthDp)
              put("heightDp", spec.heightDp)
              put("density", spec.density.toDouble())
            }
          )
        }
      }
    }
    return CallToolResult(content = listOf(ContentBlock.Text(payload.toString())))
  }

  private fun toolRenderPreview(args: JsonObject): CallToolResult {
    val uriStr =
      args["uri"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("render_preview: missing 'uri'")
    val uri = PreviewUri.parseOrNull(uriStr) ?: return errorCallToolResult("invalid uri: $uriStr")
    val observe = args["observe"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "semantics"
    if (observe !in setOf("png", "semantics", "hash")) {
      return errorCallToolResult("render_preview: 'observe' must be one of png | semantics | hash")
    }
    val cropArg =
      args["crop"]?.let {
        it as? JsonObject
          ?: return errorCallToolResult(
            "render_preview: 'crop' must be an object (a target {ref|testTag|role/text} or " +
              "bounds {left,top,right,bottom})"
          )
      }
    val overrides =
      args["overrides"]?.let {
        runCatching { decodePreviewOverrides(it) }
          .getOrElse { e ->
            return errorCallToolResult("render_preview: invalid overrides: ${e.message}")
          }
      }
    val forceReason =
      args["force"]?.let { force ->
        val reason =
          (force as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull?.takeIf {
            it.isNotBlank()
          }
            ?: return errorCallToolResult(
              "render_preview: 'force' requires a non-empty 'reason' string"
            )
        reason
      }
    if (overrides != null) {
      val daemon = supervisor.daemonFor(uri.workspaceId, uri.modulePath)
      val violations = validateOverrides(overrides, daemon)
      if (violations.isNotEmpty()) {
        return errorCallToolResult("render_preview: ${violations.joinToString("; ")}")
      }
    }
    if (forceReason != null) invalidateClasspathForForce(uri, forceReason)
    return runCatching {
      if (cropArg != null) {
        renderCropped(uri, overrides, cropArg, observe)
      } else {
        val bytes = renderAndReadBytes(uri, overrides = overrides)
        if (observe == "png") {
          pngCallToolResult(Base64.getEncoder().encodeToString(bytes))
        } else {
          renderObservation(uri, bytes, includeSemantics = observe == "semantics")
        }
      }
    }
      .getOrElse { errorCallToolResult("render_preview failed: ${it.message}") }
  }

  /**
   * `render_preview.crop` (issue #1817) — render the full preview, then return only the rectangle
   * of a single element. The crop is resolved either from explicit `{left,top,right,bottom}` render
   * pixels or from a semantic target (`ref` / `testTag` / `role`+`text`) resolved against the
   * preview's `compose/semantics` tree — the same vocabulary as targeting and `diff_semantics`. Far
   * fewer tokens than a full-frame PNG, and it focuses the agent's eyes on the region the semantics
   * already flagged: the natural partner to `diff_semantics` ("ref X changed" → crop just ref X).
   *
   * Crop runs entirely in this agent-facing layer: it operates on the rendered PNG bytes plus the
   * `compose/semantics` product (both backends already emit), so the daemon's cached full-frame
   * artifact is untouched and Android/Desktop behave identically. Because `boundsInRoot` is in the
   * same root-pixel space as the rendered image, the crop is applied to the **full-resolution**
   * bytes *before* the host image-size cap, then the cap is re-applied to the (small) result.
   */
  private fun renderCropped(
    uri: PreviewUri,
    overrides: PreviewOverrides?,
    crop: JsonObject,
    observe: String,
  ): CallToolResult {
    val boundKeys = listOf("left", "top", "right", "bottom")
    val presentBounds = boundKeys.filter { crop[it] != null }
    val target = cropTargetOf(crop)
    if (presentBounds.isEmpty() && target == null) {
      return errorCallToolResult(
        "render_preview: 'crop' must set a target (ref | testTag | role/text) or explicit bounds " +
          "{left,top,right,bottom}"
      )
    }
    if (presentBounds.isNotEmpty() && presentBounds.size != 4) {
      return errorCallToolResult(
        "render_preview: 'crop' bounds need all of left,top,right,bottom (got " +
          "${presentBounds.joinToString(",")})"
      )
    }

    val rawBytes = renderAndReadRawBytes(uri, overrides)
    val dims =
      pngDimensions(rawBytes)
        ?: return errorCallToolResult("render_preview: could not read rendered PNG dimensions")
    val (imgW, imgH) = dims

    var node: ComposeSemanticsNode? = null
    val bounds: SemanticsBounds
    if (presentBounds.size == 4) {
      val ints = boundKeys.map { key ->
        crop[key]!!.jsonPrimitive.intOrNull
          ?: return errorCallToolResult("render_preview: 'crop.$key' must be an integer")
      }
      bounds = SemanticsBounds(ints[0], ints[1], ints[2], ints[3])
    } else {
      val (payload, err) = fetchSemanticsPayload(uri.toUri(), "crop")
      if (payload == null) {
        return errorCallToolResult("render_preview: crop by target needs compose/semantics — $err")
      }
      when (val res = SemanticsTargets.resolve(payload.root, target!!)) {
        is TargetResolution.Resolved -> {
          node = res.node
          bounds =
            SemanticsBounds.parse(res.node.boundsInRoot)
              ?: return errorCallToolResult(
                "render_preview: matched node '${res.node.ref}' has unparseable bounds " +
                  "'${res.node.boundsInRoot}'"
              )
        }
        TargetResolution.NotFound ->
          return errorCallToolResult(
            "render_preview: crop target matched no node in compose/semantics"
          )
        is TargetResolution.Ambiguous ->
          return errorCallToolResult(
            "render_preview: crop target matched ${res.candidates.size} nodes — pass a unique " +
              "'ref' to disambiguate (candidates: " +
              res.candidates.take(6).joinToString(", ") { it.ref ?: it.nodeId } +
              (if (res.candidates.size > 6) ", …" else "") +
              ")"
          )
      }
    }

    // Clamp the (possibly off-frame) bounds to the rendered image and reject an empty region.
    val left = bounds.left.coerceIn(0, imgW)
    val top = bounds.top.coerceIn(0, imgH)
    val right = bounds.right.coerceIn(left, imgW)
    val bottom = bounds.bottom.coerceIn(top, imgH)
    if (right - left <= 0 || bottom - top <= 0) {
      return errorCallToolResult(
        "render_preview: crop region ${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}" +
          " is empty after clamping to ${imgW}x${imgH}"
      )
    }

    val croppedRaw =
      cropPng(rawBytes, left, top, right - left, bottom - top)
        ?: return errorCallToolResult("render_preview: failed to crop the rendered PNG")
    // Re-apply the host image-size cap to the (small) crop so the same downscale contract holds.
    val cropped = applyImageSizeOverride(croppedRaw)

    return cropResult(uri, cropped, observe, intArrayOf(left, top, right, bottom), imgW, imgH, node)
  }

  /** Map a `crop` JSON object onto a [SemanticsTarget]; null when no target field is set. */
  private fun cropTargetOf(crop: JsonObject): SemanticsTarget? {
    fun str(key: String) = crop[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val ref = str("ref")
    val tag = str("testTag")
    val role = str("role")
    val text = str("text")
    return when {
      ref != null -> SemanticsTarget.Ref(ref)
      tag != null -> SemanticsTarget.Tag(tag)
      role != null || text != null -> SemanticsTarget.RoleText(role, text)
      else -> null
    }
  }

  /**
   * Crop a PNG to `(x, y, w, h)` (already clamped) and re-encode. `getSubimage` shares the parent
   * raster, so the sub-image is copied into a standalone buffer before encoding.
   */
  private fun cropPng(bytes: ByteArray, x: Int, y: Int, w: Int, h: Int): ByteArray? {
    val src = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull() ?: return null
    val cx = x.coerceIn(0, maxOf(0, src.width - 1))
    val cy = y.coerceIn(0, maxOf(0, src.height - 1))
    val cw = w.coerceIn(1, src.width - cx)
    val ch = h.coerceIn(1, src.height - cy)
    val copy = java.awt.image.BufferedImage(cw, ch, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val g = copy.createGraphics()
    try {
      g.drawImage(src.getSubimage(cx, cy, cw, ch), 0, 0, null)
    } finally {
      g.dispose()
    }
    val out = java.io.ByteArrayOutputStream()
    ImageIO.write(copy, "png", out)
    return out.toByteArray()
  }

  /**
   * Build the `crop` response. `observe="png"` returns the cropped image plus a small metadata
   * block (resolved region, ref, source dimensions, sha); `observe="hash"`/`"semantics"` returns
   * just the metadata (a region-scoped change signal, no base64), and `semantics` additionally
   * carries the matched node's own semantics subtree when the crop resolved a target.
   */
  private fun cropResult(
    uri: PreviewUri,
    croppedBytes: ByteArray,
    observe: String,
    region: IntArray,
    sourceWidthPx: Int,
    sourceHeightPx: Int,
    node: ComposeSemanticsNode?,
  ): CallToolResult {
    val dims = pngDimensions(croppedBytes)
    val meta = buildJsonObject {
      put("uri", uri.toUri())
      putJsonObject("crop") {
        put("left", region[0])
        put("top", region[1])
        put("right", region[2])
        put("bottom", region[3])
      }
      node?.ref?.let { put("ref", it) }
      put("sourceWidthPx", sourceWidthPx)
      put("sourceHeightPx", sourceHeightPx)
      put("sha256", sha256Hex(croppedBytes))
      put("sizeBytes", croppedBytes.size)
      dims?.let {
        put("widthPx", it.first)
        put("heightPx", it.second)
      }
      if (observe == "semantics" && node != null) {
        put("semantics", json.encodeToJsonElement(ComposeSemanticsNode.serializer(), node))
      }
    }
    val blocks = buildList {
      if (observe == "png") {
        add(
          ContentBlock.Image(
            data = Base64.getEncoder().encodeToString(croppedBytes),
            mimeType = "image/png",
          )
        )
      }
      add(ContentBlock.Text(meta.toString()))
    }
    return CallToolResult(content = blocks)
  }

  /**
   * `render_matrix` (issue #1788) — render one preview across a cross-product of display axes
   * (device × locale × uiMode × fontScale) and return a token-frugal per-cell summary (overrides +
   * label + sha256 + dimensions + `changed` vs the first cell). No base64 by default; the agent
   * fetches a specific cell's pixels with `render_preview` + those overrides when it needs to look,
   * or passes `contactSheet:true` to also receive one stitched grid image of every cell. Bounded so
   * a careless cross-product can't fan out unboundedly.
   */
  private fun toolRenderMatrix(args: JsonObject): CallToolResult {
    val uriStr =
      args["uri"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("render_matrix: missing 'uri'")
    val uri =
      PreviewUri.parseOrNull(uriStr)
        ?: return errorCallToolResult("render_matrix: invalid uri: $uriStr")
    val axes =
      args["axes"] as? JsonObject
        ?: return errorCallToolResult("render_matrix: missing 'axes' (object of arrays)")
    val contactSheet =
      args["contactSheet"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

    fun stringAxis(key: String): List<String>? =
      (axes[key] as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf { s -> s.isNotBlank() } }
        ?.takeIf { it.isNotEmpty() }
    fun floatAxis(key: String): List<Float>? =
      (axes[key] as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.toFloatOrNull() }
        ?.takeIf { it.isNotEmpty() }

    val devices = stringAxis("device")
    val locales = stringAxis("locale")
    val uiModes = stringAxis("uiMode")
    val fontScales = floatAxis("fontScale")
    if (devices == null && locales == null && uiModes == null && fontScales == null) {
      return errorCallToolResult(
        "render_matrix: 'axes' must set at least one of device | locale | uiMode | fontScale " +
          "(each a non-empty array)"
      )
    }
    val cellCount = MatrixAxes.cellCount(devices, locales, uiModes, fontScales)
    if (cellCount > MatrixAxes.CELL_CAP) {
      return errorCallToolResult(
        "render_matrix: $cellCount cells exceeds the cap of ${MatrixAxes.CELL_CAP}; narrow the axes"
      )
    }

    // One cell per axis combination, in stable device → locale → uiMode → fontScale order.
    val matrixCells = MatrixAxes.expand(devices, locales, uiModes, fontScales)

    // Decode + validate EVERY cell before rendering — validateOverrides also checks the `device`
    // catalog id, which varies per cell, so a typo in any cell (not just the first) must be caught.
    val daemon = runCatching {
      supervisor.daemonFor(uri.workspaceId, uri.modulePath)
    }
      .getOrElse {
        return errorCallToolResult("render_matrix: daemon spawn failed: ${it.message}")
      }
    val decodedCells = matrixCells.map { cell ->
      val overrides = runCatching {
        cell.toOverrides()
      }
        .getOrElse {
          return errorCallToolResult("render_matrix: invalid axis values: ${it.message}")
        }
      cell to overrides
    }
    val violations = decodedCells.flatMap { (_, overrides) -> validateOverrides(overrides, daemon) }
    if (violations.isNotEmpty()) {
      return errorCallToolResult("render_matrix: ${violations.distinct().joinToString("; ")}")
    }

    return runCatching {
      var baselineSha: String? = null
      // Render every cell, keeping the bytes around so an optional contact sheet can stitch them.
      val rendered = decodedCells.map { (cell, overrides) ->
        val bytes = renderAndReadBytes(uri, overrides = overrides)
        val sha = sha256Hex(bytes)
        if (baselineSha == null) baselineSha = sha
        RenderedCell(cell, bytes, sha, pngDimensions(bytes))
      }
      val cells = rendered.map { rc ->
        buildJsonObject {
          put("overrides", rc.cell.overridesJson())
          put("label", rc.cell.label)
          put("sha256", rc.sha)
          rc.dimensions?.let {
            put("widthPx", it.first)
            put("heightPx", it.second)
          }
          put("changed", rc.sha != baselineSha)
        }
      }
      val payload = buildJsonObject {
        put("schema", "compose-preview-matrix/v1")
        put("uri", uri.toUri())
        put("cellCount", cells.size)
        if (contactSheet) put("contactSheet", true)
        putJsonArray("cells") { cells.forEach { add(it) } }
      }
      val blocks = buildList {
        if (contactSheet) {
          val sheet =
            ContactSheet.stitch(rendered.map { ContactSheet.Cell(it.cell.label, it.bytes) })
          if (sheet != null) {
            add(
              ContentBlock.Image(
                data = Base64.getEncoder().encodeToString(sheet),
                mimeType = "image/png",
              )
            )
          }
        }
        add(ContentBlock.Text(payload.toString()))
      }
      CallToolResult(content = blocks)
    }
      .getOrElse { errorCallToolResult("render_matrix failed: ${it.message}") }
  }

  /** A rendered matrix cell held in memory so the optional contact sheet can stitch the bytes. */
  private class RenderedCell(
    val cell: MatrixCell,
    val bytes: ByteArray,
    val sha: String,
    val dimensions: Pair<Int, Int>?,
  )

  /**
   * Token-frugal `render_preview` response (issue #1787): a structured observation — sha256 + pixel
   * dimensions, and (for `observe="semantics"`) the compose/semantics tree — instead of a base64
   * PNG. Mirrors Playwright's snapshot-default / screenshot-on-demand split; an agent loop reads
   * pixels only when it actually needs them.
   */
  private fun renderObservation(
    uri: PreviewUri,
    pngBytes: ByteArray,
    includeSemantics: Boolean,
  ): CallToolResult {
    val dimensions = pngDimensions(pngBytes)
    val payload = buildJsonObject {
      put("observe", if (includeSemantics) "semantics" else "hash")
      put("uri", uri.toUri())
      put("sha256", sha256Hex(pngBytes))
      put("sizeBytes", pngBytes.size)
      dimensions?.let {
        put("widthPx", it.first)
        put("heightPx", it.second)
      }
      if (includeSemantics) {
        val (semantics, error) = fetchSemanticsPayload(uri.toUri(), "preview")
        if (semantics != null) {
          put(
            "semantics",
            json.encodeToJsonElement(ComposeSemanticsPayload.serializer(), semantics),
          )
        } else {
          put("semanticsUnavailable", error ?: "compose/semantics not available for this preview")
        }
      }
    }
    return CallToolResult(content = listOf(ContentBlock.Text(payload.toString())))
  }

  /**
   * Parse a PNG's IHDR width/height (big-endian, at byte offsets 16/20) without decoding pixels.
   */
  private fun pngDimensions(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 24) return null
    fun int32(offset: Int): Int =
      ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
    val width = int32(16)
    val height = int32(20)
    return if (width in 1..100_000 && height in 1..100_000) width to height else null
  }

  private fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
      "%02x".format(it)
    }

  /**
   * Sanctioned classpath invalidation for `render_preview.force`. Forwards a
   * `fileChanged({kind:"classpath"})` to every replica of the URI's daemon so the daemon's
   * `UserClassLoaderHolder` rotates before the next `renderNow` binds. Bumps `forces.used` and
   * keeps the reason in the recent-forces ring buffer so the operator can find it via `status`.
   *
   * Each call is a freshness-logic gap; report on
   * https://github.com/yschimke/compose-ai-tools/issues/924.
   */
  private fun invalidateClasspathForForce(uri: PreviewUri, reason: String) {
    val daemon = supervisor.daemonFor(uri.workspaceId, uri.modulePath)
    // Use the catalogued source file when we have one (gives the daemon a real path to log) and
    // fall back to a synthetic marker otherwise. The daemon doesn't gate the swap on path
    // existence — it only cares about `kind`.
    val path =
      catalog[DaemonAddr(uri.workspaceId, uri.modulePath)]?.get(uri.previewFqn)?.sourceFile
        ?: "force-render://${uri.previewFqn}"
    daemon.allClients().forEach { client ->
      runCatching {
        client.fileChanged(path = path, kind = FileKind.CLASSPATH, changeType = ChangeType.MODIFIED)
      }
    }
    freshnessMetrics.recordForce(uri.toUri(), reason)
    System.err.println(
      "render_preview.force: ${uri.toUri()} reason='$reason' — please report on " +
        "https://github.com/yschimke/compose-ai-tools/issues/924"
    )
  }

  /**
   * Validates [overrides] against the daemon's advertised
   * `InitializeResult.capabilities.supportedOverrides` and `knownDevices`. Returns a list of
   * human-readable violations (empty if everything checks out).
   *
   * **Falls open on pre-feature daemons.** When the daemon's `supportedOverrides` is empty (e.g.,
   * it predates PR #441), every set field is allowed — clients see exactly the silent- no-op
   * behaviour they had before the wire surface landed. Same for `knownDeviceIds` (#433): an empty
   * catalog means we can't tell which ids are valid, so we accept any. This is the safe-pre-feature
   * contract documented on `ServerCapabilities` itself.
   *
   * `device` ids that start with `spec:` (the inline geometry grammar) bypass the catalog check —
   * `KNOWN_DEVICE_IDS` deliberately doesn't enumerate `spec:` shapes per the `DeviceDimensions`
   * kdoc; the daemon parses them at resolve-time.
   */
  private fun validateOverrides(
    overrides: PreviewOverrides,
    daemon: SupervisedDaemon,
  ): List<String> {
    val violations = mutableListOf<String>()
    val supported = daemon.supportedOverrides
    if (supported.isNotEmpty()) {
      // Each set field must appear in the daemon's advertised supportedOverrides; otherwise
      // the backend would silently ignore it. Phrasing "this backend ignores it" so the agent
      // knows the recovery is "use a different daemon" or "drop the field", not "the value
      // was invalid".
      fun check(name: String, set: Boolean) {
        if (set && name !in supported) {
          violations += "this backend does not apply '$name' overrides (supported: $supported)"
        }
      }
      check("widthPx", overrides.widthPx != null)
      check("heightPx", overrides.heightPx != null)
      check("density", overrides.density != null)
      check("localeTag", overrides.localeTag != null)
      check("fontScale", overrides.fontScale != null)
      check("uiMode", overrides.uiMode != null)
      check("orientation", overrides.orientation != null)
      check("device", overrides.device != null)
      check("captureAdvanceMs", overrides.captureAdvanceMs != null)
      check("inspectionMode", overrides.inspectionMode != null)
      check("material3Theme", overrides.material3Theme != null)
      // Override-extension fields (#1606). #1603 made supportedOverrides advertise these, so the
      // validator can now warn before a backend silently drops them — e.g. desktop, which has no
      // Robolectric grant/IME/permission/RemoteCompose shadow, never lists `permissions` etc.
      check("wallpaper", overrides.wallpaper != null)
      check("ambient", overrides.ambient != null)
      check("focus", overrides.focus != null)
      check("keyboard", overrides.keyboard != null)
      check("touchOverlay", overrides.touchOverlay != null)
      check("talkBack", overrides.talkBack != null)
      check("launcherWidget", overrides.launcherWidget != null)
      check("permissions", overrides.permissions != null)
      check("remoteCompose", overrides.remoteCompose != null)
    }
    val deviceOverride = overrides.device
    val knownIds = daemon.knownDeviceIds
    if (
      deviceOverride != null &&
        knownIds.isNotEmpty() &&
        !deviceOverride.startsWith("spec:") &&
        deviceOverride !in knownIds
    ) {
      violations +=
        "device='$deviceOverride' is not in the daemon's catalog (call list_devices to see valid ids; " +
          "or use 'spec:width=…,height=…,dpi=…' for ad-hoc geometry)"
    }
    return violations
  }

  /**
   * Translates the MCP `render_preview.overrides` JSON sub-object into a typed [PreviewOverrides]
   * for the daemon RPC. Only the fields PROTOCOL.md § 5 documents are accepted; unknown keys are
   * ignored (forward-compatible with future fields). Throws on malformed primitives so the caller
   * surfaces "invalid overrides: …" rather than rendering with surprising defaults.
   */
  private fun decodePreviewOverrides(elem: JsonElement): PreviewOverrides {
    val obj = (elem as? JsonObject) ?: error("overrides must be an object")
    fun int(name: String): Int? =
      obj[name]
        ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
        ?.jsonPrimitive
        ?.content
        ?.toInt()
    fun float(name: String): Float? =
      obj[name]
        ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
        ?.jsonPrimitive
        ?.content
        ?.toFloat()
    fun str(name: String): String? =
      obj[name]
        ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
    fun bool(name: String): Boolean? =
      obj[name]
        ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
        ?.jsonPrimitive
        ?.contentOrNull
        ?.let { it.toBooleanStrictOrNull() ?: error("$name must be true or false, got '$it'") }
    val uiMode =
      str("uiMode")?.let {
        when (it.lowercase()) {
          "light" -> UiMode.LIGHT
          "dark" -> UiMode.DARK
          else -> error("uiMode must be 'light' or 'dark', got '$it'")
        }
      }
    val orientation =
      str("orientation")?.let {
        when (it.lowercase()) {
          "portrait" -> Orientation.PORTRAIT
          "landscape" -> Orientation.LANDSCAPE
          else -> error("orientation must be 'portrait' or 'landscape', got '$it'")
        }
      }
    // Override-extension fields (#1606). These drive the connector-side around-composable hooks
    // (focus, keyboard, permissions, RemoteCompose, wallpaper, ambient, launcher-widget) and the
    // touch-overlay developer toggle. They're advertised in `supportedOverrides` since #1603, so
    // `render_preview` can now forward them through `renderNow.overrides` and `validateOverrides`
    // warns when a backend (e.g. desktop) doesn't model the field. Each is decoded straight from
    // its `@Serializable` wire shape; a malformed object throws so the caller sees "invalid
    // overrides: …" rather than a silent drop.
    fun <T> nested(
      name: String,
      deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T? =
      obj[name]
        ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
        ?.let { json.decodeFromJsonElement(deserializer, it) }
    return PreviewOverrides(
      widthPx = int("widthPx"),
      heightPx = int("heightPx"),
      density = float("density"),
      localeTag = str("localeTag"),
      fontScale = float("fontScale"),
      uiMode = uiMode,
      orientation = orientation,
      device = str("device"),
      captureAdvanceMs = int("captureAdvanceMs")?.toLong(),
      inspectionMode = bool("inspectionMode"),
      material3Theme = nested("material3Theme", Material3ThemeOverrides.serializer()),
      wallpaper = nested("wallpaper", WallpaperOverride.serializer()),
      ambient = nested("ambient", AmbientOverride.serializer()),
      focus = nested("focus", FocusOverride.serializer()),
      keyboard = nested("keyboard", KeyboardOverride.serializer()),
      touchOverlay = bool("touchOverlay"),
      talkBack = bool("talkBack"),
      permissions = nested("permissions", PermissionsOverride.serializer()),
      remoteCompose = nested("remoteCompose", RemoteComposeOverride.serializer()),
      launcherWidget = nested("launcherWidget", LauncherWidgetOverride.serializer()),
    )
  }

  private fun toolWatch(session: Session, args: JsonObject): CallToolResult {
    val ws =
      args["workspaceId"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("watch: missing 'workspaceId'")
    val workspaceId = WorkspaceId(ws)
    val project =
      supervisor.project(workspaceId)
        ?: return errorCallToolResult(
          "watch: workspace '$ws' not registered. Call register_project first."
        )
    val module = args["module"]?.jsonPrimitive?.contentOrNull
    val glob = args["fqnGlob"]?.jsonPrimitive?.contentOrNull
    val awaitDiscovery =
      args["awaitDiscovery"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
    val awaitTimeoutMs =
      args["awaitTimeoutMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: renderTimeoutMs
    val entry = WatchEntry(workspaceId = workspaceId, modulePath = module, fqnGlobPattern = glob)
    subscriptions.watch(session, entry)
    // Eagerly spawn the daemons matching this watch so they begin emitting `discoveryUpdated`
    // and the catalog populates without the client having to make a speculative `read` first.
    // - With an explicit `module`, spawn just that one.
    // - Without `module`, spawn every `knownModules` entry the workspace declared (typically
    //   passed via `register_project`'s `modules` arg).
    val toSpawn =
      if (module != null) listOf(module)
      else synchronized(project.knownModules) { project.knownModules.toList() }
    // Spawn off-thread so the SDK session doesn't block on cold-start (Robolectric ~5–10s,
    // desktop ~600ms). The supervisor's `daemonFor` is `computeIfAbsent`-safe so duplicate watches
    // racing on the same module are fine. Each successful spawn calls
    // `synthesiseInitialDiscovery`, which fires `discoveryUpdated` → `onDiscoveryUpdated` →
    // `notifyResourceListChanged` + `watchPropagator.recompute(daemon)`, so the watch's set ends
    // up forwarded to the daemon as `setVisible`/`setFocus` without a synchronous round-trip here.
    val toSpawnSet = toSpawn.toSet()
    val alreadySpawned = toSpawnSet.filter { project.daemons.containsKey(it) }
    val pending = toSpawnSet - alreadySpawned.toSet()
    val pendingFutures = mutableMapOf<String, CompletableFuture<SupervisedDaemon>>()
    pending.forEach { mp ->
      pendingFutures[mp] =
        CompletableFuture.supplyAsync(
            { supervisor.daemonFor(workspaceId, mp) },
            daemonLifecycleExecutor,
          )
          .whenComplete { _, error ->
            if (error != null) {
              System.err.println("watch: async spawn failed for $mp: ${error.message}")
            }
          }
    }
    // For daemons that are ALREADY up, recompute synchronously — the propagator skips daemons
    // whose URI set didn't change. The async spawns above will recompute themselves once their
    // initial discovery lands in `onDiscoveryUpdated`.
    alreadySpawned.forEach { mp -> project.daemons[mp]?.let { watchPropagator.recompute(it) } }
    if (awaitDiscovery && pendingFutures.isNotEmpty()) {
      val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(awaitTimeoutMs)
      for ((mp, future) in pendingFutures) {
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
        if (remainingMs <= 0) break
        runCatching { future.get(remainingMs, TimeUnit.MILLISECONDS) }
          .onFailure { System.err.println("watch: awaitDiscovery failed for $mp: ${it.message}") }
      }
    }
    val readiness = watchReadiness(project, toSpawnSet)
    val readyCount = readiness.count { it.discoveryReady }
    val payload = buildJsonObject {
      put("message", "watching $entry")
      put("workspaceId", workspaceId.value)
      module?.let { put("module", it) }
      glob?.let { put("fqnGlob", it) }
      put("awaitDiscovery", awaitDiscovery)
      put("alreadyUp", alreadySpawned.size)
      put("spawning", pending.size)
      put("ready", readyCount == readiness.size)
      put("readyModules", readyCount)
      put("totalModules", readiness.size)
      if (readyCount < readiness.size) put("retryAfterMs", WATCH_DISCOVERY_RETRY_AFTER_MS)
      putJsonArray("modules") {
        readiness.forEach { state ->
          add(
            buildJsonObject {
              put("module", state.modulePath)
              put("spawned", state.spawned)
              put("discoveryReady", state.discoveryReady)
              put("previewCount", state.previewCount)
            }
          )
        }
      }
    }
    return CallToolResult(content = listOf(ContentBlock.Text(payload.toString())))
  }

  private fun watchReadiness(
    project: RegisteredProject,
    modulePaths: Set<String>,
  ): List<WatchReadiness> =
    modulePaths.sorted().map { mp ->
      val daemon = project.daemons[mp]
      val addr = DaemonAddr(project.workspaceId, mp)
      WatchReadiness(
        modulePath = mp,
        spawned = daemon != null,
        discoveryReady = daemon?.initialDiscoveryComplete == true,
        previewCount = catalog[addr]?.size ?: 0,
      )
    }

  private fun toolUnwatch(session: Session, args: JsonObject): CallToolResult {
    val workspaceId = args["workspaceId"]?.jsonPrimitive?.contentOrNull?.let(::WorkspaceId)
    val module = args["module"]?.jsonPrimitive?.contentOrNull
    val glob = args["fqnGlob"]?.jsonPrimitive?.contentOrNull
    val removed =
      subscriptions.unwatch(session) { e ->
        (workspaceId == null || e.workspaceId == workspaceId) &&
          (module == null || e.modulePath == module) &&
          (glob == null || e.fqnGlob == glob)
      }
    // After unwatch the visible/focus set may shrink; recompute every affected daemon.
    val workspaces =
      if (workspaceId != null) listOfNotNull(supervisor.project(workspaceId))
      else supervisor.listProjects()
    workspaces.forEach { project ->
      project.daemons.values.forEach { watchPropagator.recompute(it) }
    }
    return textCallToolResult("unwatched $removed entries")
  }

  private fun toolListWatches(session: Session): CallToolResult {
    val payload = buildJsonObject {
      putJsonArray("watches") {
        subscriptions.watchesFor(session).forEach { e ->
          add(
            buildJsonObject {
              put("workspaceId", e.workspaceId.value)
              if (e.modulePath != null) put("module", e.modulePath)
              if (e.fqnGlob != null) put("fqnGlob", e.fqnGlob)
            }
          )
        }
      }
    }
    return CallToolResult(content = listOf(ContentBlock.Text(payload.toString())))
  }

  private fun toolSetVisible(args: JsonObject): CallToolResult =
    forwardVisibilityCall(args, "set_visible") { daemon, ids -> daemon.client.setVisible(ids) }

  private fun toolSetFocus(args: JsonObject): CallToolResult =
    forwardVisibilityCall(args, "set_focus") { daemon, ids -> daemon.client.setFocus(ids) }

  /**
   * Shared body for [toolSetVisible] / [toolSetFocus]: parse + validate args, look up the daemon,
   * forward the wire call. The two tools differ only in which `setVisible` / `setFocus` method they
   * invoke on the daemon client.
   */
  private fun forwardVisibilityCall(
    args: JsonObject,
    toolName: String,
    forward: (SupervisedDaemon, List<String>) -> Unit,
  ): CallToolResult {
    val ws =
      args["workspaceId"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("$toolName: missing 'workspaceId'")
    val workspaceId = WorkspaceId(ws)
    if (supervisor.project(workspaceId) == null) {
      return errorCallToolResult("$toolName: workspace '$ws' not registered")
    }
    val module =
      args["module"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("$toolName: missing 'module'")
    val ids =
      (args["ids"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?: return errorCallToolResult("$toolName: missing 'ids' array")
    val daemon = runCatching {
      supervisor.daemonFor(workspaceId, module)
    }
      .getOrElse {
        return errorCallToolResult("$toolName: daemon spawn failed: ${it.message}")
      }
    runCatching { forward(daemon, ids) }
      .onFailure {
        return errorCallToolResult("$toolName: wire call failed: ${it.message}")
      }
    return textCallToolResult("$toolName: forwarded ${ids.size} id(s) to $module")
  }

  private fun toolHistoryList(args: JsonObject): CallToolResult {
    val ws =
      args["workspaceId"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("history_list: missing 'workspaceId'")
    val workspaceId = WorkspaceId(ws)
    if (supervisor.project(workspaceId) == null) {
      return errorCallToolResult("history_list: workspace '$ws' not registered")
    }
    val module =
      args["module"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("history_list: missing 'module'")
    val daemon = runCatching {
      supervisor.daemonFor(workspaceId, module)
    }
      .getOrElse {
        return errorCallToolResult("history_list: daemon spawn failed: ${it.message}")
      }
    val params =
      ee.schimke.composeai.daemon.protocol.HistoryListParams(
        previewId = args["previewId"]?.jsonPrimitive?.contentOrNull,
        since = args["since"]?.jsonPrimitive?.contentOrNull,
        until = args["until"]?.jsonPrimitive?.contentOrNull,
        limit = args["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        cursor = args["cursor"]?.jsonPrimitive?.contentOrNull,
        branch = args["branch"]?.jsonPrimitive?.contentOrNull,
        branchPattern = args["branchPattern"]?.jsonPrimitive?.contentOrNull,
        commit = args["commit"]?.jsonPrimitive?.contentOrNull,
        worktreePath = args["worktreePath"]?.jsonPrimitive?.contentOrNull,
        agentId = args["agentId"]?.jsonPrimitive?.contentOrNull,
        sourceKind = args["sourceKind"]?.jsonPrimitive?.contentOrNull,
        sourceId = args["sourceId"]?.jsonPrimitive?.contentOrNull,
      )
    val result = runCatching {
      daemon.client.historyList(params)
    }
      .getOrElse {
        return errorCallToolResult("history_list failed: ${it.message}")
      }

    // Decorate each entry with the matching `compose-preview-history://` URI so clients can
    // call `resources/read` on it directly.
    val annotated = buildJsonObject {
      put("totalCount", JsonPrimitive(result.totalCount))
      if (result.nextCursor != null) put("nextCursor", JsonPrimitive(result.nextCursor))
      putJsonArray("entries") {
        result.entries.forEach { entry ->
          val obj = entry as? JsonObject ?: return@forEach
          val previewId = obj["previewId"]?.jsonPrimitive?.contentOrNull
          val entryId = obj["id"]?.jsonPrimitive?.contentOrNull
          val uri =
            if (previewId != null && entryId != null)
              HistoryUri(workspaceId, module, previewId, entryId).toUri()
            else null
          add(
            buildJsonObject {
              obj.forEach { (k, v) -> put(k, v) }
              if (uri != null) put("resourceUri", JsonPrimitive(uri))
            }
          )
        }
      }
    }
    return CallToolResult(content = listOf(ContentBlock.Text(annotated.toString())))
  }

  private fun toolHistoryDiff(args: JsonObject): CallToolResult {
    val ws =
      args["workspaceId"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("history_diff: missing 'workspaceId'")
    val workspaceId = WorkspaceId(ws)
    if (supervisor.project(workspaceId) == null) {
      return errorCallToolResult("history_diff: workspace '$ws' not registered")
    }
    val module =
      args["module"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("history_diff: missing 'module'")
    val from =
      args["from"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("history_diff: missing 'from'")
    val to =
      args["to"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("history_diff: missing 'to'")
    val daemon = runCatching {
      supervisor.daemonFor(workspaceId, module)
    }
      .getOrElse {
        return errorCallToolResult("history_diff: daemon spawn failed: ${it.message}")
      }
    val result = runCatching {
      daemon.client.historyDiff(
        fromId = from,
        toId = to,
        mode = ee.schimke.composeai.daemon.protocol.HistoryDiffMode.METADATA,
      )
    }
      .getOrElse {
        return errorCallToolResult("history_diff failed: ${it.message}")
      }

    val payload = buildJsonObject {
      put("pngHashChanged", JsonPrimitive(result.pngHashChanged))
      put("fromMetadata", result.fromMetadata)
      put("toMetadata", result.toMetadata)
      // Pixel-mode fields are always null in METADATA mode by design (HISTORY.md § H3).
      // We expose them so a client written against the H5-shape doesn't choke on missing keys.
      if (result.diffPx != null) put("diffPx", JsonPrimitive(result.diffPx))
      if (result.ssim != null) put("ssim", JsonPrimitive(result.ssim))
      if (result.diffPngPath != null) put("diffPngPath", JsonPrimitive(result.diffPngPath))
    }
    return CallToolResult(content = listOf(ContentBlock.Text(payload.toString())))
  }

  // -------------------------------------------------------------------------
  // D1 — data product tools. See docs/daemon/DATA-PRODUCTS.md.
  //
  // The MCP surface is tool-shaped rather than resource-shaped because data
  // products are keyed on (previewId, kind) — a 2D space — and `resources/read`
  // can only return one content block per URI. Tools fit the shape exactly:
  // arguments → JSON return.
  // -------------------------------------------------------------------------

  private fun toolListDataProducts(args: JsonObject): CallToolResult {
    val ws = args["workspaceId"]?.jsonPrimitive?.contentOrNull
    val module = args["module"]?.jsonPrimitive?.contentOrNull
    if (module != null && ws == null) {
      return errorCallToolResult("list_data_products: 'module' requires 'workspaceId'")
    }
    val workspaceFilter = ws?.let(::WorkspaceId)
    if (workspaceFilter != null && supervisor.project(workspaceFilter) == null) {
      return errorCallToolResult("list_data_products: workspace '$ws' not registered")
    }
    val payload = buildJsonObject {
      putJsonArray("daemons") {
        for (project in supervisor.listProjects()) {
          if (workspaceFilter != null && project.workspaceId != workspaceFilter) continue
          for ((mp, daemon) in project.daemons) {
            if (module != null && mp != module) continue
            add(
              buildJsonObject {
                put("workspaceId", project.workspaceId.value)
                put("module", mp)
                putJsonArray("kinds") {
                  daemon.dataProductCapabilities.forEach { cap ->
                    add(
                      buildJsonObject {
                        put("kind", cap.kind)
                        put("schemaVersion", cap.schemaVersion)
                        put("transport", cap.transport.name.lowercase())
                        put("attachable", cap.attachable)
                        put("fetchable", cap.fetchable)
                        put("requiresRerender", cap.requiresRerender)
                      }
                    )
                  }
                }
                putJsonArray("dataExtensions") {
                  daemon.dataExtensionDescriptors.forEach { extension ->
                    add(
                      json.encodeToJsonElement(
                        ee.schimke.composeai.daemon.protocol.DataExtensionDescriptor.serializer(),
                        extension,
                      )
                    )
                  }
                }
              }
            )
          }
        }
      }
    }
    return CallToolResult(content = listOf(ContentBlock.Text(payload.toString())))
  }

  /**
   * Routes `tools/call enable_extensions` to the daemon's `extensions/enable` JSON-RPC method for
   * every (workspace, module) matching the optional filters, and refreshes the supervisor's cached
   * capability snapshots so the new public surface is visible to downstream tools (e.g.
   * `list_data_products`, `get_preview_data`) without a follow-up `extensions/list` round-trip.
   *
   * Background: PROTOCOL.md § 3a — daemons start with every extension registered as inactive so
   * `initialize.capabilities.dataProducts` is empty; clients must opt in. The supervisor exposes a
   * constructor-time `defaultExtensions` knob for embedders, but the standalone `compose-preview
   * mcp serve` entry point doesn't populate it (lean default), and there's no MCP tool agents can
   * call to enable extensions on a running daemon. This tool fills that gap and also unblocks
   * `run-agent-audit-samples.py` from hitting `DataProductUnknown: text/strings` the moment it asks
   * for any data-product kind.
   */
  private fun toolEnableExtensions(args: JsonObject): CallToolResult {
    val rawIds: JsonArray? = (args["ids"] as? JsonArray)
    if (rawIds == null || rawIds.size == 0) {
      return errorCallToolResult("enable_extensions: missing or empty 'ids'")
    }
    val ids: List<String> =
      rawIds
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .filter { it.isNotBlank() }
        .distinct()
    if (ids.isEmpty()) {
      return errorCallToolResult("enable_extensions: 'ids' must contain at least one extension id")
    }
    val ws = args["workspaceId"]?.jsonPrimitive?.contentOrNull
    val module = args["module"]?.jsonPrimitive?.contentOrNull
    if (module != null && ws == null) {
      return errorCallToolResult("enable_extensions: 'module' requires 'workspaceId'")
    }
    val workspaceFilter = ws?.let(::WorkspaceId)
    if (workspaceFilter != null && supervisor.project(workspaceFilter) == null) {
      return errorCallToolResult("enable_extensions: workspace '$ws' not registered")
    }
    val perDaemon = mutableListOf<JsonObject>()
    var anyMatched = false
    for (project in supervisor.listProjects()) {
      if (workspaceFilter != null && project.workspaceId != workspaceFilter) continue
      for ((mp, daemon) in project.daemons) {
        if (module != null && mp != module) continue
        anyMatched = true
        val outcome = runCatching {
          daemon.client.extensionsEnable(ids)
        }
          .onSuccess {
            daemon.dataProductCapabilities = it.dataProducts
            daemon.dataExtensionDescriptors = it.dataExtensions
          }
        perDaemon.add(
          buildJsonObject {
            put("workspaceId", project.workspaceId.value)
            put("module", mp)
            outcome.fold(
              onSuccess = { result ->
                putJsonArray("newlyEnabled") {
                  result.newlyEnabled.forEach { add(JsonPrimitive(it)) }
                }
                putJsonArray("pulledIn") { result.pulledIn.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("alreadyEnabled") {
                  result.alreadyEnabled.forEach { add(JsonPrimitive(it)) }
                }
                putJsonArray("unknown") { result.unknown.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("dataProducts") {
                  result.dataProducts.forEach { cap ->
                    add(
                      buildJsonObject {
                        put("kind", cap.kind)
                        put("schemaVersion", cap.schemaVersion)
                        put("transport", cap.transport.name.lowercase())
                        put("attachable", cap.attachable)
                        put("fetchable", cap.fetchable)
                        put("requiresRerender", cap.requiresRerender)
                      }
                    )
                  }
                }
              },
              onFailure = { e -> put("error", e.message ?: e::class.java.simpleName) },
            )
          }
        )
      }
    }
    if (!anyMatched) {
      val scope =
        when {
          ws != null && module != null -> "workspace '$ws' module '$module'"
          ws != null -> "workspace '$ws'"
          else -> "any workspace"
        }
      return errorCallToolResult(
        "enable_extensions: no spawned daemon matched $scope — register the project + render at " +
          "least once to spawn a daemon before enabling extensions"
      )
    }
    val payload = buildJsonObject { putJsonArray("daemons") { perDaemon.forEach { add(it) } } }
    return CallToolResult(content = listOf(ContentBlock.Text(payload.toString())))
  }

  private fun toolListExtensionCommands(args: JsonObject): CallToolResult {
    val agentRecommended =
      args["agentRecommended"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
    val extensions =
      if (agentRecommended) {
        PreviewExtensionCommandCatalog.extensions
          .map { extension ->
            extension.copy(cliCommands = extension.cliCommands.filter { it.agentRecommended })
          }
          .filter { it.cliCommands.isNotEmpty() }
      } else {
        PreviewExtensionCommandCatalog.extensions
      }
    val payload = buildJsonObject {
      put("schema", "compose-preview-extension-commands/v1")
      putJsonArray("extensions") {
        extensions.forEach { extension ->
          add(json.encodeToJsonElement(PreviewExtensionDescriptor.serializer(), extension))
        }
      }
      put("commandCount", extensions.sumOf { it.cliCommands.size })
    }
    return textCallToolResult(payload.toString())
  }

  private fun toolRunExtensionCommand(args: JsonObject): CallToolResult {
    val commandId =
      args["commandId"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("run_extension_command: missing 'commandId'")
    if (PreviewExtensionCommandCatalog.commandById(commandId) == null) {
      return errorCallToolResult("run_extension_command: unknown command '$commandId'")
    }
    fun data(kind: String, defaultInline: Boolean = true): CallToolResult {
      val routed = buildJsonObject {
        copyArg(args, "uri")
        put("kind", kind)
        args["params"]?.let { put("params", it) }
        put(
          "inline",
          JsonPrimitive(
            args["inline"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: defaultInline
          ),
        )
      }
      return toolGetPreviewData(routed)
    }
    fun overlay(kind: String): CallToolResult {
      val routed = buildJsonObject {
        copyArg(args, "uri")
        put("kind", kind)
        args["inline"]?.let { put("inline", it) }
        args["overrides"]?.let { put("overrides", it) }
      }
      return toolRenderPreviewOverlay(routed)
    }
    fun render(): CallToolResult {
      val routed = buildJsonObject {
        copyArg(args, "uri")
        args["overrides"]?.let { put("overrides", it) }
      }
      return toolRenderPreview(routed)
    }
    return when (commandId) {
      "render-device-clip.get" -> data("render/deviceClip")
      "render-device-background.get" -> data("render/deviceBackground")
      "render-trace.get" -> data("render/trace")
      "compose-trace.get" -> data("render/composeAiTrace")
      "a11y.hierarchy.get" -> data("a11y/hierarchy")
      "atf-checks.run",
      "atf-checks.get" -> data("a11y/atf")
      "a11y-overlay.get" -> overlay("a11y/overlay")
      "a11y-annotated-preview.render",
      "scrolling-preview-annotation.render" -> render()
      "scroll-long.get" -> data("render/scroll/long", defaultInline = false)
      "scroll-gif.get" -> data("render/scroll/gif", defaultInline = false)
      else ->
        errorCallToolResult("run_extension_command: command '$commandId' has no MCP runner yet")
    }
  }

  private fun JsonObjectBuilder.copyArg(source: JsonObject, name: String) {
    source[name]?.let { put(name, it) }
  }

  private fun toolGetPreviewData(args: JsonObject): CallToolResult {
    val uriStr =
      args["uri"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("get_preview_data: missing 'uri'")
    val uri =
      PreviewUri.parseOrNull(uriStr)
        ?: return errorCallToolResult("get_preview_data: invalid uri: $uriStr")
    val kind =
      args["kind"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("get_preview_data: missing 'kind'")
    // Default to inline=true so agents get JSON back rather than a sibling-file path they may not
    // be able to read. Local callers that prefer disk reads pass `inline: false` explicitly.
    val inline = args["inline"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
    val perKindParams = args["params"] as? JsonObject
    if (supervisor.project(uri.workspaceId) == null) {
      return errorCallToolResult(
        "get_preview_data: workspace '${uri.workspaceId.value}' not registered"
      )
    }
    val daemon = runCatching {
      supervisor.daemonFor(uri.workspaceId, uri.modulePath)
    }
      .getOrElse {
        return errorCallToolResult("get_preview_data: daemon spawn failed: ${it.message}")
      }
    // Cache hit short-circuit: if a previous renderFinished attached this kind (because someone
    // subscribed, or the kind is in the global attachDataProducts set), serve the cached payload
    // and skip the wire round-trip entirely. The cache mirrors the latest render; a new render
    // wipes stale entries via [refreshDataProductCache], so a hit is always fresh.
    //
    // Skip the cache when the caller asked for a path-shaped result (`inline = false`) but the
    // cached entry is payload-shaped (or vice versa) — the daemon would have returned a different
    // transport on a direct fetch, so falling through preserves the contract.
    //
    // Skip the cache when per-kind `params` are present — those select sub-views (e.g.
    // `{ nodeId }` for `layout/inspector`), and the cached entry is the no-params form.
    if (perKindParams == null) {
      val cached =
        dataProductCache[DataAttachKey(uri.workspaceId, uri.modulePath, uri.previewFqn, kind)]
      if (cached != null && transportMatches(cached, inline)) {
        return renderCachedAttachment(kind, cached, inline)
      }
    }
    return runCatching {
      // Try the fetch directly first — works whenever the preview has rendered at least once.
      // On `DataProductNotAvailable` (-32021) the daemon is telling us the preview has never
      // rendered; trigger a single render and retry. Folds the two-call agent dance ("render
      // first, then ask for data") into one tool call. Other wire errors propagate.
      val result =
        try {
          daemon.client.dataFetch(uri.previewFqn, kind, perKindParams, inline)
        } catch (e: DataProductWireException) {
          if (e.code != DataProductWireException.NOT_AVAILABLE) throw e
          awaitNextRender(uri)
          daemon.client.dataFetch(uri.previewFqn, kind, perKindParams, inline)
        }
      renderDataFetchResult(result)
    }
      .getOrElse { e ->
        when (e) {
          is DataProductWireException ->
            errorCallToolResult("get_preview_data: ${nameOf(e.code)}: ${e.wireMessage}")
          else -> errorCallToolResult("get_preview_data failed: ${e.message}")
        }
      }
  }

  /**
   * `true` iff the cached entry can satisfy a request with the given [inline] flag without
   * round-tripping the daemon. A `payload`-shaped cache entry serves any caller that asked for
   * inline (the default); a `path`-shaped entry serves callers that explicitly passed `inline =
   * false`. Mismatches fall through to a direct `data/fetch`, which lets the daemon pick the right
   * transport.
   */
  private fun transportMatches(entry: DataAttachmentEntry, inline: Boolean): Boolean =
    when {
      inline && entry.payload != null -> true
      !inline && entry.path != null -> true
      else -> false
    }

  private fun renderCachedAttachment(
    kind: String,
    entry: DataAttachmentEntry,
    inline: Boolean,
  ): CallToolResult {
    val payload = buildJsonObject {
      put("kind", kind)
      put("schemaVersion", entry.schemaVersion)
      put("cached", true)
      val attachedPayload = entry.payload
      val attachedPath = entry.path
      if (inline && attachedPayload != null) put("payload", attachedPayload)
      if (!inline && attachedPath != null) put("path", JsonPrimitive(attachedPath))
      val extras = entry.extras
      if (extras != null) put("extras", extras)
    }
    return CallToolResult(content = listOf(ContentBlock.Text(payload.toString())))
  }

  /**
   * D2.1 — `render_preview_overlay`. Triggers a render (so the producer's image processor runs) and
   * returns the resulting overlay PNG. Default `kind` is `a11y/overlay`; callers can target any
   * path-transport kind whose producer emits PNG-shaped extras.
   *
   * Flow: render → `data/fetch` for the overlay kind (cache short-circuited when possible) → read
   * PNG bytes → return as base64 image content. With `inline=false` the response stays text-shaped
   * and just carries the path the agent can read directly. Overrides forward to the underlying
   * `renderNow` exactly the same way `render_preview` does.
   */
  private fun toolRenderPreviewOverlay(args: JsonObject): CallToolResult {
    val uriStr =
      args["uri"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("render_preview_overlay: missing 'uri'")
    val uri =
      PreviewUri.parseOrNull(uriStr)
        ?: return errorCallToolResult("render_preview_overlay: invalid uri: $uriStr")
    val kind =
      args["kind"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: DEFAULT_OVERLAY_KIND
    val inline = args["inline"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
    val overrides =
      args["overrides"]?.let {
        runCatching { decodePreviewOverrides(it) }
          .getOrElse { e ->
            return errorCallToolResult("render_preview_overlay: invalid overrides: ${e.message}")
          }
      }
    if (supervisor.project(uri.workspaceId) == null) {
      return errorCallToolResult(
        "render_preview_overlay: workspace '${uri.workspaceId.value}' not registered"
      )
    }
    val daemon = runCatching {
      supervisor.daemonFor(uri.workspaceId, uri.modulePath)
    }
      .getOrElse {
        return errorCallToolResult("render_preview_overlay: daemon spawn failed: ${it.message}")
      }
    if (overrides != null) {
      val violations = validateOverrides(overrides, daemon)
      if (violations.isNotEmpty()) {
        return errorCallToolResult("render_preview_overlay: ${violations.joinToString("; ")}")
      }
    }
    if (daemon.dataProductCapabilities.none { it.kind == kind }) {
      return errorCallToolResult(
        "render_preview_overlay: DataProductUnknown: kind '$kind' not advertised by " +
          "${uri.workspaceId.value}/${uri.modulePath}"
      )
    }
    return runCatching {
      // Force a fresh render so the image processor runs against the current source state;
      // this is the "generate previews with an overlay" entry point that callers expect
      // to be deterministic vs. cached PNGs.
      awaitNextRender(uri, overrides = overrides)
      val fetchResult = daemon.client.dataFetch(uri.previewFqn, kind, params = null, inline = false)
      val pngPath =
        fetchResult.path
          ?: return@runCatching errorCallToolResult(
            "render_preview_overlay: producer for '$kind' returned no path; expected an " +
              "image-bearing kind"
          )
      if (inline) {
        val file = File(pngPath)
        if (!file.isFile) {
          return@runCatching errorCallToolResult(
            "render_preview_overlay: overlay PNG missing at $pngPath"
          )
        }
        pngCallToolResult(
          Base64.getEncoder()
            .encodeToString(fileSystem.read(file.path.toPath()) { readByteArray() })
        )
      } else {
        val payload = buildJsonObject {
          put("kind", kind)
          put("schemaVersion", fetchResult.schemaVersion)
          put("path", pngPath)
          val extras = fetchResult.extras
          if (!extras.isNullOrEmpty()) {
            putJsonArray("extras") {
              for (extra in extras) {
                add(
                  buildJsonObject {
                    put("name", extra.name)
                    put("path", extra.path)
                    if (extra.mediaType != null) put("mediaType", extra.mediaType)
                    if (extra.sizeBytes != null) put("sizeBytes", extra.sizeBytes)
                  }
                )
              }
            }
          }
        }
        textCallToolResult(payload.toString())
      }
    }
      .getOrElse { e ->
        when (e) {
          is DataProductWireException ->
            errorCallToolResult("render_preview_overlay: ${nameOf(e.code)}: ${e.wireMessage}")
          else -> errorCallToolResult("render_preview_overlay failed: ${e.message}")
        }
      }
  }

  /**
   * D2.1 — `get_preview_extras`. Enumerates the producer's extras for `(uri, kind)`. Same cache
   * short-circuit as `get_preview_data`; on a miss we round-trip a `data/fetch` with `inline=false`
   * to pick up the path-shaped result so the daemon hands back the extras list in one call instead
   * of forcing a re-render path. Returns an `extras` array (possibly empty); callers iterate to
   * find the `(name, path, mediaType?, sizeBytes?)` they want.
   */
  private fun toolGetPreviewExtras(args: JsonObject): CallToolResult {
    val uriStr =
      args["uri"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("get_preview_extras: missing 'uri'")
    val uri =
      PreviewUri.parseOrNull(uriStr)
        ?: return errorCallToolResult("get_preview_extras: invalid uri: $uriStr")
    val kind =
      args["kind"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("get_preview_extras: missing 'kind'")
    if (supervisor.project(uri.workspaceId) == null) {
      return errorCallToolResult(
        "get_preview_extras: workspace '${uri.workspaceId.value}' not registered"
      )
    }
    val daemon = runCatching {
      supervisor.daemonFor(uri.workspaceId, uri.modulePath)
    }
      .getOrElse {
        return errorCallToolResult("get_preview_extras: daemon spawn failed: ${it.message}")
      }
    val cached =
      dataProductCache[DataAttachKey(uri.workspaceId, uri.modulePath, uri.previewFqn, kind)]
    val cachedExtras = cached?.extras as? JsonArray
    val payload = buildJsonObject {
      put("kind", kind)
      put("uri", uriStr)
      if (cachedExtras != null) {
        put("cached", true)
        put("extras", cachedExtras)
      } else {
        val fetched = runCatching {
          try {
            daemon.client.dataFetch(uri.previewFqn, kind, params = null, inline = false)
          } catch (e: DataProductWireException) {
            if (e.code != DataProductWireException.NOT_AVAILABLE) throw e
            awaitNextRender(uri)
            daemon.client.dataFetch(uri.previewFqn, kind, params = null, inline = false)
          }
        }
          .getOrElse { e ->
            return when (e) {
              is DataProductWireException ->
                errorCallToolResult("get_preview_extras: ${nameOf(e.code)}: ${e.wireMessage}")
              else -> errorCallToolResult("get_preview_extras failed: ${e.message}")
            }
          }
        putJsonArray("extras") {
          for (extra in fetched.extras.orEmpty()) {
            add(
              buildJsonObject {
                put("name", extra.name)
                put("path", extra.path)
                if (extra.mediaType != null) put("mediaType", extra.mediaType)
                if (extra.sizeBytes != null) put("sizeBytes", extra.sizeBytes)
              }
            )
          }
        }
      }
    }
    return textCallToolResult(payload.toString())
  }

  /**
   * Forwards `data/unsubscribe` for a refcount-released `(uri, kind)` to the matching daemon.
   * Best-effort — failures are logged to stderr but never propagated, since this runs on session
   * teardown where we can't surface errors to the (already-gone) client. Returns silently when the
   * URI doesn't parse, the workspace was unregistered, or the daemon already exited.
   */
  private fun dispatchDataUnsubscribe(key: DataSubKey) {
    val uri = PreviewUri.parseOrNull(key.uri) ?: return
    val project = supervisor.project(uri.workspaceId) ?: return
    val daemon = project.daemons[uri.modulePath] ?: return
    runCatching { daemon.client.dataUnsubscribe(uri.previewFqn, key.kind) }
      .onFailure {
        System.err.println(
          "DaemonMcpServer: data/unsubscribe for ${key.uri} ($key.kind) failed: ${it.message}"
        )
      }
  }

  private fun renderDataFetchResult(
    result: ee.schimke.composeai.daemon.protocol.DataFetchResult
  ): CallToolResult {
    val resultPayload = result.payload
    val resultPath = result.path
    val resultBytes = result.bytes
    val resultExtras = result.extras
    val payload = buildJsonObject {
      put("kind", result.kind)
      put("schemaVersion", result.schemaVersion)
      if (resultPayload != null) put("payload", resultPayload)
      if (resultPath != null) put("path", JsonPrimitive(resultPath))
      if (resultBytes != null) put("bytes", JsonPrimitive(resultBytes))
      if (!resultExtras.isNullOrEmpty()) {
        putJsonArray("extras") {
          for (extra in resultExtras) {
            add(
              buildJsonObject {
                put("name", extra.name)
                put("path", extra.path)
                if (extra.mediaType != null) put("mediaType", extra.mediaType)
                if (extra.sizeBytes != null) put("sizeBytes", extra.sizeBytes)
              }
            )
          }
        }
      }
    }
    return CallToolResult(content = listOf(ContentBlock.Text(payload.toString())))
  }

  /**
   * `diff_semantics` — fetch `compose/semantics` for two preview URIs and report the structural
   * delta between their trees (issue #1785). The cheap, deterministic, pixel-free regression
   * signal: nodes are matched by their stable `ref`, so a copy edit is a field change on the same
   * ref rather than a remove + add. Returns `{ schema, summary, delta }` as a single text block.
   */
  private fun toolDiffSemantics(args: JsonObject): CallToolResult {
    val baseUriStr =
      args["baseUri"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("diff_semantics: missing 'baseUri'")
    val headUriStr =
      args["headUri"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("diff_semantics: missing 'headUri'")
    val (base, baseErr) = fetchSemanticsPayload(baseUriStr, "base")
    if (base == null) return errorCallToolResult("diff_semantics: $baseErr")
    val (head, headErr) = fetchSemanticsPayload(headUriStr, "head")
    if (head == null) return errorCallToolResult("diff_semantics: $headErr")
    val delta = SemanticsDiff.diff(base, head)
    val out = buildJsonObject {
      put("schema", delta.schema)
      put("summary", summarizeSemanticsDelta(delta))
      put("delta", json.encodeToJsonElement(SemanticsDelta.serializer(), delta))
    }
    return CallToolResult(content = listOf(ContentBlock.Text(out.toString())))
  }

  /**
   * Fetch and decode `compose/semantics` for one URI, auto-rendering once on the
   * `DataProductNotAvailable` path (same fold as [toolGetPreviewData]). Returns `(payload, null)`
   * on success or `(null, message)` with a [side]-prefixed diagnostic the caller wraps into a tool
   * error.
   */
  private fun fetchSemanticsPayload(
    uriStr: String,
    side: String,
  ): Pair<ComposeSemanticsPayload?, String?> {
    val uri =
      PreviewUri.parseOrNull(uriStr)
        ?: return null to
          if (uriStr.startsWith("${HistoryUri.SCHEME}://")) {
            "$side: history URIs aren't supported yet (compose/semantics isn't persisted per " +
              "history entry); pass a live compose-preview:// URI"
          } else {
            "invalid $side uri: $uriStr"
          }
    if (supervisor.project(uri.workspaceId) == null) {
      return null to "$side workspace '${uri.workspaceId.value}' not registered"
    }
    val daemon = runCatching {
      supervisor.daemonFor(uri.workspaceId, uri.modulePath)
    }
      .getOrElse {
        return null to "$side daemon spawn failed: ${it.message}"
      }
    val result = runCatching {
      try {
        daemon.client.dataFetch(
          uri.previewFqn,
          ComposeSemanticsProduct.KIND,
          null,
          inline = true,
        )
      } catch (e: DataProductWireException) {
        if (e.code != DataProductWireException.NOT_AVAILABLE) throw e
        awaitNextRender(uri)
        daemon.client.dataFetch(
          uri.previewFqn,
          ComposeSemanticsProduct.KIND,
          null,
          inline = true,
        )
      }
    }
      .getOrElse { e ->
        return null to
          when (e) {
            is DataProductWireException -> "$side ${nameOf(e.code)}: ${e.wireMessage}"
            else -> "$side fetch failed: ${e.message}"
          }
      }
    return runCatching { decodeSemanticsPayload(result) }
      .map { it to null }
      .getOrElse { null to "$side: could not read compose/semantics (${it.message})" }
  }

  /**
   * Decode a [DataFetchResult] into a [ComposeSemanticsPayload] from whichever transport it used.
   */
  private fun decodeSemanticsPayload(
    result: ee.schimke.composeai.daemon.protocol.DataFetchResult
  ): ComposeSemanticsPayload {
    result.payload?.let {
      return json.decodeFromJsonElement(ComposeSemanticsPayload.serializer(), it)
    }
    result.bytes?.let {
      val text = String(Base64.getDecoder().decode(it), Charsets.UTF_8)
      return json.decodeFromString(ComposeSemanticsPayload.serializer(), text)
    }
    result.path?.let { path ->
      val text = SystemFileSystem.read(path.toPath()) { readUtf8() }
      return json.decodeFromString(ComposeSemanticsPayload.serializer(), text)
    }
    error("empty data/fetch result (no payload, bytes, or path)")
  }

  /** One-line human summary of a [SemanticsDelta] for the tool response. */
  private fun summarizeSemanticsDelta(delta: SemanticsDelta): String {
    if (delta.isEmpty) return "no semantic changes"
    return buildString {
      append("${delta.added.size} added, ${delta.removed.size} removed, ")
      append("${delta.changed.size} changed")
      delta.changed.take(3).forEach { change ->
        val fields = change.changes.joinToString(", ") { it.field }
        append("; ${change.anchor ?: change.ref}: $fields")
      }
    }
  }

  /**
   * `record_preview` — drives the daemon's `recording/start | script | stop | encode` flow
   * end-to-end and returns the encoded video bytes inline. See RECORDING.md.
   *
   * The agent passes the URI, an optional `fps` / `scale` / `format`, the scripted timeline, and
   * optional per-render `overrides`. We resolve the URI to a daemon, validate `overrides` against
   * the daemon's advertised `supportedOverrides`, then run the four-call sequence. The script is
   * decoded into typed [RecordingScriptEvent]s with per-element validation so a malformed event
   * surfaces as a clean tool-level error rather than dying inside the daemon.
   *
   * Errors surface as `isError = true` text content blocks; success returns a single image content
   * block carrying the base64-encoded video bytes (mime `image/apng` for v1 — the only format the
   * daemon advertises today). The on-disk path is included in a sibling text block so an agent that
   * prefers a path can pick it up without re-decoding.
   *
   * The session is closed best-effort if any of the four daemon calls fail mid-flight, so the
   * daemon doesn't leak a held scene when the script is malformed or the encoder breaks. We
   * deliberately don't suppress the original error — tool callers see what actually went wrong.
   */
  private fun toolRecordPreview(args: JsonObject): CallToolResult {
    val uriStr =
      args["uri"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("record_preview: missing 'uri'")
    val uri =
      PreviewUri.parseOrNull(uriStr)
        ?: return errorCallToolResult("record_preview: invalid uri: $uriStr")
    val eventsRaw =
      (args["events"] as? JsonArray)
        ?: return errorCallToolResult("record_preview: missing 'events' (must be array)")
    val events = runCatching {
      decodeRecordingEvents(eventsRaw)
    }
      .getOrElse {
        return errorCallToolResult("record_preview: invalid events: ${it.message}")
      }
    // Strict numeric validation — distinguish "absent" (use daemon default) from "malformed"
    // (return a clean diagnostic). The previous lenient `toIntOrNull` / `toFloatOrNull` swallowed
    // typos like `"fps": "fast"` into a silently-defaulted recording, which is hard to trust
    // when a script's timing is wrong but no error surfaces.
    val fps = runCatching {
      decodeOptionalInt("fps", args["fps"])
    }
      .getOrElse {
        return errorCallToolResult("record_preview: invalid fps: ${it.message}")
      }
    val scale = runCatching {
      decodeOptionalFloat("scale", args["scale"])
    }
      .getOrElse {
        return errorCallToolResult("record_preview: invalid scale: ${it.message}")
      }
    val formatStr = args["format"]?.jsonPrimitive?.contentOrNull?.lowercase()
    val format =
      when (formatStr) {
        null,
        "apng" -> RecordingFormat.APNG
        "gif" -> RecordingFormat.GIF
        "mp4" -> RecordingFormat.MP4
        "webm" -> RecordingFormat.WEBM
        else ->
          return errorCallToolResult(
            "record_preview: unsupported 'format' '$formatStr' — supported: apng, gif, mp4, webm"
          )
      }
    // Issue #1860: token-frugal default. `frames` returns the structured per-frame observation
    // (hashes + changed-frame indices + the on-disk paths) with NO inline media; `media` opts into
    // the encoded APNG/MP4/WebM bytes inline (the pre-#1860 behaviour). Mirrors render_preview's
    // observe split: a recording's inline bytes scale with fps × duration and can dwarf a PNG.
    val observe = args["observe"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "frames"
    if (observe !in setOf("frames", "media")) {
      return errorCallToolResult("record_preview: 'observe' must be one of frames | media")
    }
    val overrides =
      args["overrides"]?.let {
        runCatching { decodePreviewOverrides(it) }
          .getOrElse { e ->
            return errorCallToolResult("record_preview: invalid overrides: ${e.message}")
          }
      }
    if (supervisor.project(uri.workspaceId) == null) {
      return errorCallToolResult(
        "record_preview: workspace '${uri.workspaceId.value}' not registered"
      )
    }
    val daemon = runCatching {
      supervisor.daemonFor(uri.workspaceId, uri.modulePath)
    }
      .getOrElse {
        return errorCallToolResult("record_preview: daemon spawn failed: ${it.message}")
      }
    if (overrides != null) {
      val violations = validateOverrides(overrides, daemon)
      if (violations.isNotEmpty()) {
        return errorCallToolResult("record_preview: ${violations.joinToString("; ")}")
      }
    }
    val scriptKindViolations = validateRecordingScriptKinds(events, daemon)
    if (scriptKindViolations.isNotEmpty()) {
      return errorCallToolResult("record_preview: ${scriptKindViolations.joinToString("; ")}")
    }
    // RECORDING.md § "encoded formats" — when the daemon advertises a non-empty `recordingFormats`
    // capability, reject formats outside the advertised set up front so the agent sees a clean
    // diagnostic instead of waiting on a `recording/encode` round-trip that would only fail.
    // Pre-feature daemons advertise an empty set; fall open so the request goes through and the
    // underlying error (whatever it is) surfaces naturally — same pattern `validateOverrides`
    // uses.
    val advertisedFormats = daemon.recordingFormats
    val formatWire =
      when (format) {
        RecordingFormat.APNG -> "apng"
        RecordingFormat.GIF -> "gif"
        RecordingFormat.MP4 -> "mp4"
        RecordingFormat.WEBM -> "webm"
      }
    if (advertisedFormats.isNotEmpty() && formatWire !in advertisedFormats) {
      return errorCallToolResult(
        "record_preview: format '$formatWire' not advertised by this daemon " +
          "(supported: ${advertisedFormats.sorted()}). " +
          "mp4/webm require an ffmpeg binary on the daemon's PATH."
      )
    }

    val started = runCatching {
      daemon.client.recordingStart(
        previewId = uri.previewFqn,
        fps = fps,
        scale = scale,
        overrides = overrides,
      )
    }
      .getOrElse {
        return errorCallToolResult("record_preview: recording/start failed: ${it.message}")
      }
    val recordingId = started.recordingId
    return runCatching {
      if (events.isNotEmpty()) {
        daemon.client.recordingScript(recordingId, events)
      }
      val stopResult = daemon.client.recordingStop(recordingId)
      val frameMetadata = inspectRecordingFrames(File(stopResult.framesDir))
      val encoded = daemon.client.recordingEncode(recordingId, format)
      // Only read the encoded bytes when the caller opted into inline media (observe="media");
      // the default frames observation never touches them (issue #1860).
      val videoBytes by lazy {
        fileSystem.read(File(encoded.videoPath).path.toPath()) { readByteArray() }
      }
      val payload = buildJsonObject {
        put("observe", observe)
        if (observe == "frames") {
          // The encoded artifact still exists on disk at `videoPath`; re-call with
          // observe="media" to get the bytes inline.
          put("mediaInline", false)
        }
        put("recordingId", recordingId)
        put("videoPath", encoded.videoPath)
        put("mimeType", encoded.mimeType)
        put("sizeBytes", encoded.sizeBytes)
        put("frameCount", stopResult.frameCount)
        put("durationMs", stopResult.durationMs)
        put("frameWidthPx", stopResult.frameWidthPx)
        put("frameHeightPx", stopResult.frameHeightPx)
        put("framesDir", stopResult.framesDir)
        put("changedFrameCount", frameMetadata.count { it.changedFromPrevious })
        frameMetadata.firstOrNull()?.let { put("firstFramePath", it.path) }
        frameMetadata.lastOrNull()?.let { put("lastFramePath", it.path) }
        frameMetadata
          .firstOrNull { it.changedFromPrevious }
          ?.let {
            put("firstChangedFramePath", it.path)
            put("firstChangedFrameIndex", it.index)
          }
        frameMetadata
          .lastOrNull { it.changedFromPrevious }
          ?.let {
            put("lastChangedFramePath", it.path)
            put("lastChangedFrameIndex", it.index)
          }
        putJsonArray("frames") {
          for (frame in frameMetadata) {
            add(
              buildJsonObject {
                put("index", frame.index)
                put("path", frame.path)
                put("sha256", frame.sha256)
                put("changedFromPrevious", frame.changedFromPrevious)
                frame.changedPixelsFromPrevious?.let { put("changedPixelsFromPrevious", it) }
                frame.dimensionChangedFromPrevious?.let { put("dimensionChangedFromPrevious", it) }
              }
            )
          }
        }
        putJsonArray("scriptEvents") {
          for (event in stopResult.scriptEvents) {
            add(
              buildJsonObject {
                put("tMs", event.tMs)
                put("kind", event.kind)
                put("status", event.status.wireName())
                event.label?.let { put("label", it) }
                event.checkpointId?.let { put("checkpointId", it) }
                event.lifecycleEvent?.let { put("lifecycleEvent", it) }
                if (event.tags.isNotEmpty()) {
                  putJsonArray("tags") { for (tag in event.tags) add(JsonPrimitive(tag)) }
                }
                event.message?.let { put("message", it) }
                // #1784 — structured semantic-target miss: code + matchCount + candidate nodes so
                // the agent disambiguates (picks a candidate `ref`) without re-rendering.
                event.targetUnresolvedReason?.let {
                  put(
                    "targetUnresolvedReason",
                    json.encodeToJsonElement(
                      ee.schimke.composeai.daemon.protocol.SemanticsTargetUnresolvedReason
                        .serializer(),
                      it,
                    ),
                  )
                }
              }
            )
          }
        }
      }
      // Per the MCP 2025-06-18 spec, only `image/*` mimeTypes belong in `ContentBlock.Image`;
      // strict clients reject mismatches. APNG (`image/apng`) round-trips as an image; mp4 /
      // webm route through `EmbeddedResource` wrapping a `Blob` so a client that already
      // understands `resources/read` reads them via the same code path.
      val mediaBlock: ContentBlock? =
        if (observe != "media") {
          null
        } else if (encoded.mimeType.startsWith("image/")) {
          ContentBlock.Image(
            data = Base64.getEncoder().encodeToString(videoBytes),
            mimeType = encoded.mimeType,
          )
        } else {
          ContentBlock.EmbeddedResource(
            resource =
              ResourceContents.Blob(
                uri = "compose-preview-recording://$recordingId",
                mimeType = encoded.mimeType,
                blob = Base64.getEncoder().encodeToString(videoBytes),
              )
          )
        }
      CallToolResult(
        content =
          buildList {
            // observe="media" (opt-in): the inline APNG/MP4/WebM bytes lead the result, as
            // before. observe="frames" (default): structured per-frame observation only.
            mediaBlock?.let { add(it) }
            add(ContentBlock.Text(payload.toString()))
            // #1786 — opt-in: turn the recorded interaction into a runnable Compose UI test
            // (the codegen analogue). Built from the recording's applied evidence so an
            // unresolved target is a skipped-step comment, not a fabricated performClick.
            if (args["emitTest"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true) {
              add(
                ContentBlock.Text(generateRecordingTestSource(uri, events, stopResult.scriptEvents))
              )
            }
          }
      )
    }
      .getOrElse { errorCallToolResult("record_preview failed: ${it.message}") }
  }

  /**
   * Generate a Compose UI test from a `record_preview` interaction (issue #1786). The `setContent {
   * <method>() }` call uses the preview's real `@Composable` method name resolved from the catalog
   * ([PreviewEntry.functionName], sourced from the daemon's `discoveryUpdated` `functionName`
   * field). That's load-bearing for named/variant previews (issue #1807): their id is a synthetic
   * `…WeatherForecast_Light`, so the old `previewFqn.substringAfterLast('.')` heuristic emitted
   * `WeatherForecast_Light()` — a call to a function that doesn't exist, yielding an uncompilable
   * test. We only fall back to that heuristic when the catalog has no entry (e.g. a synthetic uri
   * that never went through discovery). The generated header still tells the author to add the
   * composable's import. Steps are built from the recording's [evidence] so an `unsupported` event
   * becomes a skipped-step comment rather than a fabricated step; when the evidence can't be
   * aligned 1:1 we fall back to treating every event as applied.
   */
  private fun generateRecordingTestSource(
    uri: PreviewUri,
    events: List<ee.schimke.composeai.daemon.protocol.RecordingScriptEvent>,
    evidence: List<ee.schimke.composeai.daemon.protocol.RecordingScriptEvidence>,
  ): String {
    val resolvedFunctionName =
      catalog[DaemonAddr(uri.workspaceId, uri.modulePath)]
        ?.get(uri.previewFqn)
        ?.functionName
        ?.takeIf { it.isNotBlank() }
    val method =
      resolvedFunctionName ?: uri.previewFqn.substringAfterLast('.').ifBlank { "preview" }
    val pascal = method.replaceFirstChar { it.uppercaseChar() }
    val camel = method.replaceFirstChar { it.lowercaseChar() }
    // The recording dispatches script events in tMs order and appends one evidence each, so a
    // size-matched evidence list aligns by index with the tMs-sorted events.
    val sorted = events.sortedBy { it.tMs }
    val steps =
      if (evidence.size == sorted.size) {
        sorted.mapIndexed { i, event ->
          RecordingTestGenerator.Step(
            event,
            applied = evidence[i].status == RecordingScriptEventStatus.APPLIED,
            probeSemantics = evidence[i].probeSemantics,
          )
        }
      } else {
        RecordingTestGenerator.stepsOf(sorted)
      }
    return RecordingTestGenerator.generate(
      RecordingTestGenerator.Spec(
        className = "Generated${pascal}Test",
        methodName = "${camel}Interaction",
        composableInvocation = "$method()",
        steps = steps,
      )
    )
  }

  private data class RecordingFrameMetadata(
    val index: Int,
    val path: String,
    val sha256: String,
    val changedFromPrevious: Boolean,
    val changedPixelsFromPrevious: Int?,
    val dimensionChangedFromPrevious: Boolean?,
  )

  private fun inspectRecordingFrames(framesDir: File): List<RecordingFrameMetadata> {
    val frames =
      framesDir
        .listFiles { f -> f.isFile && f.extension.equals("png", ignoreCase = true) }
        ?.sortedBy { it.name }
        .orEmpty()
    var previous: java.awt.image.BufferedImage? = null
    return frames.mapIndexed { index, frame ->
      // Read the PNG bytes through Okio, then decode from memory (ImageIO is the codec boundary).
      val image = runCatching {
        val bytes = fileSystem.read(frame.path.toPath()) { readByteArray() }
        ImageIO.read(bytes.inputStream())
      }
        .getOrNull()
      val previousImage = previous
      val changedPixels =
        if (previousImage != null && image != null && sameDimensions(previousImage, image)) {
          countChangedPixels(previousImage, image)
        } else {
          null
        }
      val dimensionChanged =
        if (previousImage != null && image != null) !sameDimensions(previousImage, image) else null
      val changedFromPrevious =
        when {
          index == 0 -> false
          changedPixels != null -> changedPixels > 0
          dimensionChanged == true -> true
          else -> false
        }
      if (image != null) previous = image
      RecordingFrameMetadata(
        index = index,
        path = frame.absolutePath,
        sha256 = sha256Hex(frame),
        changedFromPrevious = changedFromPrevious,
        changedPixelsFromPrevious = changedPixels,
        dimensionChangedFromPrevious = dimensionChanged,
      )
    }
  }

  private fun sameDimensions(
    a: java.awt.image.BufferedImage,
    b: java.awt.image.BufferedImage,
  ): Boolean = a.width == b.width && a.height == b.height

  private fun countChangedPixels(
    a: java.awt.image.BufferedImage,
    b: java.awt.image.BufferedImage,
  ): Int {
    var changed = 0
    for (y in 0 until a.height) {
      for (x in 0 until a.width) {
        if (a.getRGB(x, y) != b.getRGB(x, y)) changed++
      }
    }
    return changed
  }

  private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  /**
   * Decode an optional integer arg from [elem]. Returns `null` when [elem] is `null` or JSON null
   * (caller falls back to the daemon's default); throws [IllegalStateException] when [elem] is
   * present but not parseable as an integer (e.g. `"fps": "fast"`). The throw maps to a
   * `record_preview: invalid <name>` tool-level error so an agent typo surfaces clearly instead of
   * silently producing a default-paced recording.
   */
  private fun decodeOptionalInt(name: String, elem: JsonElement?): Int? {
    if (elem == null || elem is kotlinx.serialization.json.JsonNull) return null
    val raw =
      elem.jsonPrimitive.contentOrNull ?: error("'$name' must be a number; got null primitive")
    return raw.toIntOrNull() ?: error("'$name' must be an integer; got '$raw'")
  }

  /** As [decodeOptionalInt] but for a floating-point arg (`scale`). */
  private fun decodeOptionalFloat(name: String, elem: JsonElement?): Float? {
    if (elem == null || elem is kotlinx.serialization.json.JsonNull) return null
    val raw =
      elem.jsonPrimitive.contentOrNull ?: error("'$name' must be a number; got null primitive")
    return raw.toFloatOrNull() ?: error("'$name' must be a number; got '$raw'")
  }

  /**
   * Translate the MCP `record_preview.events` JSON array into typed [RecordingScriptEvent]s.
   * Validates each entry has a non-negative `tMs` and a non-blank `kind`; throws on malformed input
   * so the wrapper surfaces "invalid events: …" rather than dying inside the daemon's notification
   * decoder. Unknown extra keys are tolerated for forward compatibility (same shape rule the
   * `decodePreviewOverrides` helper uses). Closed-set validation against the daemon's advertised
   * input + extension kinds happens later in [validateRecordingScriptKinds] once the daemon has
   * been resolved.
   */
  private fun decodeRecordingEvents(arr: JsonArray): List<RecordingScriptEvent> {
    return arr.mapIndexed { idx, elem ->
      val obj = (elem as? JsonObject) ?: error("event[$idx] must be an object")
      val tMs =
        obj["tMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
          ?: error("event[$idx] missing or invalid 'tMs'")
      require(tMs >= 0) { "event[$idx] tMs must be ≥ 0; got $tMs" }
      val kindStr = obj["kind"]?.jsonPrimitive?.contentOrNull ?: error("event[$idx] missing 'kind'")
      require(kindStr.isNotBlank()) { "event[$idx] kind must not be blank" }
      RecordingScriptEvent(
        tMs = tMs,
        kind = kindStr,
        pixelX = obj["pixelX"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        pixelY = obj["pixelY"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        target =
          (obj["target"] as? JsonObject)?.let {
            json.decodeFromJsonElement(SemanticsInputTarget.serializer(), it)
          },
        scrollDeltaY = obj["scrollDeltaY"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull(),
        keyCode = obj["keyCode"]?.jsonPrimitive?.contentOrNull,
        label = obj["label"]?.jsonPrimitive?.contentOrNull,
        checkpointId = obj["checkpointId"]?.jsonPrimitive?.contentOrNull,
        lifecycleEvent = obj["lifecycleEvent"]?.jsonPrimitive?.contentOrNull,
        tags =
          (obj["tags"] as? JsonArray)?.mapNotNull { tag -> tag.jsonPrimitive.contentOrNull }
            ?: emptyList(),
        nodeContentDescription = obj["nodeContentDescription"]?.jsonPrimitive?.contentOrNull,
        selector = obj["selector"] as? JsonObject,
        useUnmergedTree =
          obj["useUnmergedTree"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
        inputText = obj["inputText"]?.jsonPrimitive?.contentOrNull,
        deepLinkUri = obj["deepLinkUri"]?.jsonPrimitive?.contentOrNull,
        backProgress = obj["backProgress"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull(),
        backEdge = obj["backEdge"]?.jsonPrimitive?.contentOrNull,
      )
    }
  }

  private fun RecordingScriptEventStatus.wireName(): String =
    when (this) {
      RecordingScriptEventStatus.APPLIED -> "applied"
      RecordingScriptEventStatus.UNSUPPORTED -> "unsupported"
      RecordingScriptEventStatus.FAILED -> "failed"
    }

  /**
   * Per-event closed-set validation against the resolved daemon's advertised capabilities. Every
   * recording-script event id (input + extension events alike) is checked against
   * `ServerCapabilities.dataExtensions[].recordingScriptEvents[]`:
   *
   * - **`supported = true`** — accepted; the daemon will dispatch.
   * - **`supported = false`** — rejected with a precise diagnostic that points at
   *   `list_data_products` so the agent sees the roadmap shape rather than a quiet `unsupported`
   *   evidence trail. (The daemon-side fallback that emits `unsupported` evidence stays in place as
   *   defense-in-depth for older MCP servers + direct daemon clients.)
   * - **Not advertised** — rejected with "not advertised by this daemon".
   *
   * Input kinds (`input.click`, `input.pointerDown`, …) are advertised through
   * `InputTouchRecordingScriptEvents` / `InputKeyboardRecordingScriptEvents` /
   * `InputRsbRecordingScriptEvents` — same code path as every other extension. No special-case
   * branch.
   */
  private fun validateRecordingScriptKinds(
    events: List<RecordingScriptEvent>,
    daemon: SupervisedDaemon,
  ): List<String> {
    val supportedEventIds =
      daemon.dataExtensionDescriptors
        .flatMap { it.recordingScriptEvents }
        .filter { it.supported }
        .map { it.id }
        .toSet()
    val advertisedButUnsupported =
      daemon.dataExtensionDescriptors
        .flatMap { it.recordingScriptEvents }
        .filterNot { it.supported }
        .map { it.id }
        .toSet()
    return events.mapIndexedNotNull { index, event ->
      when {
        event.kind in supportedEventIds -> null
        event.kind in advertisedButUnsupported ->
          "event[$index] script event '${event.kind}' is advertised by this daemon but not yet " +
            "implemented (supported=false); list_data_products to inspect the roadmap"
        else -> {
          val hint = suggestionFor(event.kind, supportedEventIds)
          "event[$index] kind '${event.kind}' is not advertised by this daemon. Call " +
            "list_data_products to see the available script-event ids." +
            if (hint != null) " Did you mean '$hint'?" else ""
        }
      }
    }
  }

  /**
   * Catch the common "agent followed stale docs and dropped the namespace" mistake — e.g. `{
   * "kind": "click" }` instead of `{ "kind": "input.click" }`. When the unrecognised name matches a
   * supported id's tail past the dot, return that id; otherwise no hint (avoid misleading
   * suggestions for genuinely unknown kinds).
   */
  private fun suggestionFor(unknown: String, supported: Set<String>): String? {
    if (unknown.contains('.')) return null
    return supported.firstOrNull { it.substringAfter('.', "") == unknown }
  }

  private fun nameOf(code: Int): String =
    when (code) {
      DataProductWireException.UNKNOWN -> "DataProductUnknown"
      DataProductWireException.NOT_AVAILABLE -> "DataProductNotAvailable"
      DataProductWireException.FETCH_FAILED -> "DataProductFetchFailed"
      DataProductWireException.BUDGET_EXCEEDED -> "DataProductBudgetExceeded"
      else -> "wire-error-$code"
    }

  private fun toolDataSubOrUnsub(
    session: Session,
    args: JsonObject,
    subscribe: Boolean,
  ): CallToolResult {
    val toolName = if (subscribe) "subscribe_preview_data" else "unsubscribe_preview_data"
    val uriStr =
      args["uri"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("$toolName: missing 'uri'")
    val uri =
      PreviewUri.parseOrNull(uriStr)
        ?: return errorCallToolResult("$toolName: invalid uri: $uriStr")
    val kind =
      args["kind"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("$toolName: missing 'kind'")
    if (supervisor.project(uri.workspaceId) == null) {
      return errorCallToolResult("$toolName: workspace '${uri.workspaceId.value}' not registered")
    }
    val daemon = runCatching {
      supervisor.daemonFor(uri.workspaceId, uri.modulePath)
    }
      .getOrElse {
        return errorCallToolResult("$toolName: daemon spawn failed: ${it.message}")
      }
    // Refcount across MCP sessions so multiple agents subscribed to the same (uri, kind) only
    // pay one wire-level `data/subscribe`. The daemon doesn't multiplex per-session; one
    // subscribe is enough for as many MCP sessions as want it. Wire forwards happen only on
    // first-ref / last-ref transitions.
    return runCatching {
      if (subscribe) {
        val firstRef = subscriptions.subscribeData(uriStr, kind, session)
        if (firstRef) daemon.client.dataSubscribe(uri.previewFqn, kind)
        textCallToolResult(
          "$toolName: ok ($kind for ${uri.previewFqn}, " +
            if (firstRef) "first session)" else "shared with N≥2 sessions)"
        )
      } else {
        val lastRef = subscriptions.unsubscribeData(uriStr, kind, session)
        if (lastRef) daemon.client.dataUnsubscribe(uri.previewFqn, kind)
        textCallToolResult(
          "$toolName: ok ($kind for ${uri.previewFqn}, " +
            if (lastRef) "released)" else "still shared with other sessions)"
        )
      }
    }
      .getOrElse { errorCallToolResult("$toolName failed: ${it.message}") }
  }

  private fun toolNotifyFileChanged(args: JsonObject): CallToolResult {
    val ws =
      args["workspaceId"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("notify_file_changed: missing 'workspaceId'")
    val workspaceId = WorkspaceId(ws)
    val project =
      supervisor.project(workspaceId)
        ?: return errorCallToolResult("notify_file_changed: unknown workspace '$ws'")
    val path =
      args["path"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("notify_file_changed: missing 'path'")
    val kind =
      when (args["kind"]?.jsonPrimitive?.contentOrNull) {
        "resource" -> FileKind.RESOURCE
        "classpath" -> FileKind.CLASSPATH
        else -> FileKind.SOURCE
      }
    val changeType =
      when (args["changeType"]?.jsonPrimitive?.contentOrNull) {
        "created" -> ChangeType.CREATED
        "deleted" -> ChangeType.DELETED
        else -> ChangeType.MODIFIED
      }
    // Forward to every spawned daemon in the workspace. The daemon itself decides whether the
    // file is in its module's source set; the supervisor doesn't try to be clever about
    // dispatch. After the file change, also re-issue `renderNow` for every URI any session has
    // watched/subscribed in this workspace, so the daemon produces fresh bytes that get pushed
    // out via the existing `renderFinished` → `notifications/resources/updated` path.
    var forwarded = 0
    var rendered = 0
    project.daemons.values.forEach { daemon ->
      // File invalidation must reach EVERY replica — each replica has its own independent
      // discovery + render cache, so missing one would leave it serving stale bytes.
      daemon.allClients().forEach { client ->
        runCatching { client.fileChanged(path = path, kind = kind, changeType = changeType) }
          .onSuccess { forwarded++ }
      }
      val byId = catalog[DaemonAddr(daemon.workspaceId, daemon.modulePath)] ?: return@forEach
      // Build the candidate URI set for this daemon and intersect with current watches/subs.
      val candidates =
        byId.values.map { entry ->
          PreviewUri(
            workspaceId = daemon.workspaceId,
            modulePath = daemon.modulePath,
            previewFqn = entry.fqn,
            config = entry.config,
          )
        }
      val ofInterest = candidates.filter { uri ->
        subscriptions.sessionsWatching(uri).isNotEmpty() ||
          subscriptions.sessionsSubscribedTo(uri.toUri()).isNotEmpty()
      }
      if (ofInterest.isNotEmpty()) {
        // Group renders by their target replica so we issue one renderNow per replica with the
        // subset of previews it owns. Same hash function as `clientForRender` so the dispatch
        // here matches what `renderAndReadBytes` would do for the same previewFqn.
        val byReplica = ofInterest.groupBy { daemon.clientForRender(it.previewFqn) }
        byReplica.forEach { (client, group) ->
          runCatching {
            client.renderNow(
              previews = group.map { it.previewFqn },
              tier = RenderTier.FULL,
              reason = "notify_file_changed:$path",
            )
          }
          rendered += group.size
        }
      }
    }
    return textCallToolResult(
      "fileChanged forwarded to $forwarded daemon(s); re-rendered $rendered watched preview(s)"
    )
  }

  // -------------------------------------------------------------------------
  // Daemon notification handlers
  // -------------------------------------------------------------------------

  private fun onDiscoveryUpdated(daemon: SupervisedDaemon, params: JsonObject?) {
    daemon.initialDiscoveryComplete = true
    val addr = DaemonAddr(daemon.workspaceId, daemon.modulePath)
    val byId = catalog.computeIfAbsent(addr) { ConcurrentHashMap() }
    val added = (params?.get("added") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val changed = (params?.get("changed") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val removed =
      (params?.get("removed") as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        .orEmpty()
    for (entry in added + changed) {
      val id = entry["id"]?.jsonPrimitive?.contentOrNull ?: continue
      val sourceFile = entry["sourceFile"]?.jsonPrimitive?.contentOrNull
      val resolved =
        resolvePreviewSourceFile(
          PreviewUri(
            workspaceId = daemon.workspaceId,
            modulePath = daemon.modulePath,
            previewFqn = id,
            config = entry["config"]?.jsonPrimitive?.contentOrNull,
          ),
          sourceFile,
        )
      val sourceLastModifiedMs = resolved?.lastModified()?.takeIf { it > 0L }
      // Seed the content hash at discovery so the very first frozen-mtime edit is caught
      // against this baseline. Failures (unreadable file, permissions) just leave the hash
      // null — `ensureSourceFreshBeforeRender` falls back to the legacy mtime-only path.
      val sourceContentHash = resolved?.let { runCatching { sha256Hex(it) }.getOrNull() }
      byId[id] =
        PreviewEntry(
          fqn = id,
          displayName = entry["displayName"]?.jsonPrimitive?.contentOrNull,
          config = entry["config"]?.jsonPrimitive?.contentOrNull,
          sourceFile = sourceFile,
          functionName = entry["functionName"]?.jsonPrimitive?.contentOrNull,
          sourceLastModifiedMs = sourceLastModifiedMs,
          sourceContentHash = sourceContentHash,
        )
    }
    removed.forEach { byId.remove(it) }
    sessions.forEach { it.notifyResourceListChanged() }
    watchPropagator.recompute(daemon)
  }

  private fun onRenderFinished(daemon: SupervisedDaemon, params: JsonObject?) {
    val previewId = params?.get("id")?.jsonPrimitive?.contentOrNull ?: return
    val pngPath = params["pngPath"]?.jsonPrimitive?.contentOrNull ?: return
    val key = PreviewIdKey(daemon.workspaceId, daemon.modulePath, previewId)
    // 0. Sampling attribution. If a sampling probe was pending for this previewId, claim it and
    //    classify the render's `unchanged` flag as deterministic / non-deterministic. Probes
    //    never enqueue futures, so step 1's `popHeadAndPromoteNext` stays a no-op for them
    //    (empty queue) and they don't disturb the user-driven serialization.
    var probeClaimed = false
    pendingProbes.computeIfPresent(key) { _, counter ->
      probeClaimed = true
      if (counter.decrementAndGet() <= 0) null else counter
    }
    if (probeClaimed) {
      val unchanged = params["unchanged"]?.jsonPrimitive?.booleanOrNull == true
      if (unchanged) {
        freshnessMetrics.samplingDeterministic.incrementAndGet()
      } else {
        freshnessMetrics.samplingNondeterministic.incrementAndGet()
        val entryForUri = catalog[DaemonAddr(daemon.workspaceId, daemon.modulePath)]?.get(previewId)
        val probeUri =
          PreviewUri(
            workspaceId = daemon.workspaceId,
            modulePath = daemon.modulePath,
            previewFqn = previewId,
            config = entryForUri?.config,
          )
        freshnessMetrics.recordNondeterministic(probeUri.toUri())
      }
    }
    // 1. Pop the head group of this URI's queue, wake its waiters with the rendered bytes, and
    //    promote-and-dispatch the next group's renderNow if one is queued. This is the
    //    serialization core that PR #432's by-previewId fanout (now removed) tried to paper
    //    over — see `popHeadAndPromoteNext` and `awaitNextRender`'s kdoc for the rationale.
    popHeadAndPromoteNext(daemon, key, RenderOutcome.Finished(pngPath))
    // 2. Refresh the data-product attachment cache for this `(uri)`. Any kind the daemon attached
    //    on this render is the new fresh payload; any kind it didn't attach is stale and gets
    //    dropped (the daemon stops attaching kinds the MCP server unsubscribed from, so a missing
    //    entry means "no longer requested" — caching the previous payload would serve stale data
    //    to a future re-subscribe).
    refreshDataProductCache(daemon, previewId, params["dataProducts"])
    // 3. Build the matching URI and notify subscribers + watchers.
    val entry = catalog[DaemonAddr(daemon.workspaceId, daemon.modulePath)]?.get(previewId)
    val uri =
      PreviewUri(
        workspaceId = daemon.workspaceId,
        modulePath = daemon.modulePath,
        previewFqn = previewId,
        config = entry?.config,
      )
    val uriStr = uri.toUri()
    val targets = mutableSetOf<Session>()
    targets.addAll(subscriptions.sessionsSubscribedTo(uriStr))
    targets.addAll(subscriptions.sessionsWatching(uri))
    targets.forEach { it.notifyResourceUpdated(uriStr) }
    // 4. Record history (no-op default).
    runCatching { historyStore.record(uri, pngPath, Instant.now()) }
  }

  /**
   * Replaces the [dataProductCache] entries for `(daemon, previewId)` with whatever
   * [attachmentsField] carried. Tolerant of missing / malformed entries: a single broken entry
   * skips itself rather than poisoning the whole cache update. When [attachmentsField] is null or
   * empty (the common case — no client subscribed), every previously-cached entry for this `(uri)`
   * is evicted.
   */
  private fun refreshDataProductCache(
    daemon: SupervisedDaemon,
    previewId: String,
    attachmentsField: JsonElement?,
  ) {
    // Drop everything the cache had for this preview — the daemon's latest render is the truth.
    dataProductCache.keys.removeIf {
      it.workspaceId == daemon.workspaceId &&
        it.modulePath == daemon.modulePath &&
        it.previewId == previewId
    }
    val arr = attachmentsField as? kotlinx.serialization.json.JsonArray ?: return
    for (elem in arr) {
      val obj = elem as? JsonObject ?: continue
      val kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: continue
      val schemaVersion =
        obj["schemaVersion"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
      val payload = obj["payload"]
      val path = obj["path"]?.jsonPrimitive?.contentOrNull
      val extras = obj["extras"]
      val key = DataAttachKey(daemon.workspaceId, daemon.modulePath, previewId, kind)
      dataProductCache[key] = DataAttachmentEntry(schemaVersion, payload, path, extras)
    }
  }

  /**
   * Drops every cached attachment for `(workspace, module)`. Called from `onClose` and the
   * `classpathDirty` respawn path — after the daemon goes away, the cached payloads are tied to a
   * renderer state that no longer exists, so a follow-up `get_preview_data` should round-trip the
   * (possibly respawned) daemon rather than serving a possibly-stale payload.
   */
  private fun evictDataProductsForDaemon(workspaceId: WorkspaceId, modulePath: String) {
    dataProductCache.keys.removeIf { it.workspaceId == workspaceId && it.modulePath == modulePath }
  }

  private fun onRenderFailed(daemon: SupervisedDaemon, params: JsonObject?) {
    val previewId = params?.get("id")?.jsonPrimitive?.contentOrNull ?: return
    val errorObj = params["error"] as? JsonObject
    val kind = errorObj?.get("kind")?.jsonPrimitive?.contentOrNull ?: "unknown"
    val message = errorObj?.get("message")?.jsonPrimitive?.contentOrNull ?: "no message"
    // #1789 — carry the daemon's classified one-line remediation through to the agent-facing error.
    val suggestion = errorObj?.get("suggestion")?.jsonPrimitive?.contentOrNull
    // Same pop-and-promote shape as `onRenderFinished` — failure of the head group does NOT
    // sympathetically fail queued non-head groups. Different overrides could plausibly succeed
    // even when O1 throws (e.g., a composable that fails only at small widths), so we keep the
    // queue draining: pop the failed head, wake its waiters with the failure, and dispatch the
    // next group's renderNow normally. If a follow-up group's render also fails, the same path
    // surfaces it.
    val key = PreviewIdKey(daemon.workspaceId, daemon.modulePath, previewId)
    popHeadAndPromoteNext(daemon, key, RenderOutcome.Failed(kind, message, suggestion))
  }

  /**
   * Per PROTOCOL.md § 6, the daemon emits `classpathDirty` exactly once and then exits within
   * [`daemon.classpathDirtyGraceMs`][..] (default 2000ms). The MCP supervisor's job here is to
   *
   * 1. Forget the dying daemon so the next `daemonFor` for the same coordinates spawns afresh
   *    against (presumably) the refreshed descriptor.
   * 2. Purge cached state (catalog, propagator memo, in-flight render waiters) — the new daemon
   *    will re-emit its initial `discoveryUpdated` via the supervisor's
   *    `synthesiseInitialDiscovery` path, which repopulates the catalog.
   * 3. Tell connected clients the resource list is stale (`notifications/resources/list_changed`)
   *    so they re-list when ready.
   * 4. Schedule a respawn on the [daemonLifecycleExecutor] worker so the daemon's reader thread
   *    (which is about to die anyway) doesn't block on the new daemon's cold-start.
   *
   * If the descriptor on disk is itself stale (the user/VS Code hasn't re-run
   * `composePreviewDaemonStart`), the new daemon will hit `classpathDirty` again. We log that and
   * stop trying after one self-loop — repeated thrashing serves no one. Production users are
   * expected to re-bootstrap before the supervisor's respawn kicks in.
   */
  /**
   * The daemon's `historyAdded` notification carries one new [HistoryEntry] per render. Per
   * HISTORY.md § Subscriptions, only sessions that have expressed interest in the affected preview
   * should receive the list-grew signal:
   *
   * - subscribers to the matching live `compose-preview://…` URI ("subscribers to the live URI
   *   receive `list_changed` whenever a new history entry lands for it"),
   * - sessions whose watch set (workspace/module/glob) covers the URI.
   *
   * The previous implementation broadcast `list_changed` to every connected session on every
   * render. Clients with no interest in this preview were forced to filter their entire resource
   * list on every save — a significant noise multiplier with multiple workspaces or hot save loops.
   * The targeted form costs one extra parse (extract `entry.previewId`) per event.
   *
   * Falls back to a session-registry-wide broadcast when the entry payload is malformed (no
   * previewId field, or fails to parse) — a degraded but safe behaviour that ensures clients still
   * re-list on history events the supervisor can't classify.
   */
  private fun onHistoryAdded(daemon: SupervisedDaemon, params: JsonObject?) {
    val entry = params?.get("entry") as? JsonObject
    val previewFqn = entry?.get("previewId")?.jsonPrimitive?.contentOrNull
    if (previewFqn == null) {
      // Degraded fallback: tell everyone, the way we used to.
      sessions.forEach { it.notifyResourceListChanged() }
      return
    }
    val configValue =
      (entry["previewMetadata"] as? JsonObject)?.get("config")?.jsonPrimitive?.contentOrNull
    val liveUri =
      PreviewUri(
        workspaceId = daemon.workspaceId,
        modulePath = daemon.modulePath,
        previewFqn = previewFqn,
        config = configValue,
      )
    val liveUriStr = liveUri.toUri()
    val targets = mutableSetOf<Session>()
    targets.addAll(subscriptions.sessionsSubscribedTo(liveUriStr))
    targets.addAll(subscriptions.sessionsWatching(liveUri))
    targets.forEach { it.notifyResourceListChanged() }
  }

  private fun onClasspathDirty(daemon: SupervisedDaemon, params: JsonObject?) {
    val detail = params?.get("detail")?.jsonPrimitive?.contentOrNull ?: "<no detail>"
    val reason = params?.get("reason")?.jsonPrimitive?.contentOrNull ?: "<no reason>"
    System.err.println(
      "DaemonMcpServer: classpathDirty for ${daemon.workspaceId}/${daemon.modulePath} " +
        "(reason=$reason): $detail"
    )

    val workspaceId = daemon.workspaceId
    val modulePath = daemon.modulePath

    // Fail any in-flight render waiters for this daemon — the daemon is exiting and won't
    // produce `renderFinished` for them. Drain every group of every previewQueue belonging to
    // this (workspace, module): the head AND any queued follow-ups, since the next-group
    // dispatch in popHeadAndPromoteNext is only triggered by a daemon notification we'll
    // never receive.
    val matchingKeys =
      previewQueues.keys.filter { it.workspaceId == workspaceId && it.modulePath == modulePath }
    matchingKeys.forEach { key ->
      val drained = previewQueues.remove(key) ?: return@forEach
      val outcome = RenderOutcome.Failed("classpathDirty", "daemon exiting: $detail")
      drained.forEach { group -> group.futures.forEach { it.complete(outcome) } }
    }

    // Forget the daemon + cached state. With `replicasPerDaemon > 0`, multiple replicas of the
    // same group may race to emit `classpathDirty` (they all see the same stale classpath). The
    // first call wins — `forgetDaemon` returns false on subsequent calls so we skip the
    // respawn-counter bump and the respawn schedule, avoiding double-spawn under the race.
    val firstClassedDirty = supervisor.forgetDaemon(workspaceId, modulePath)
    catalog.remove(DaemonAddr(workspaceId, modulePath))
    evictDataProductsForDaemon(workspaceId, modulePath)
    watchPropagator.forget(daemon)
    sessions.forEach { it.notifyResourceListChanged() }
    if (!firstClassedDirty) return

    // Track respawn attempts so a permanently-stale descriptor (one whose own classpath
    // fingerprint disagrees with reality) doesn't loop forever.
    val attemptKey = DaemonAddr(workspaceId, modulePath)
    val attempts = respawnAttempts.merge(attemptKey, 1) { a, b -> a + b } ?: 1
    if (attempts > MAX_RESPAWN_ATTEMPTS_PER_LIFETIME) {
      System.err.println(
        "DaemonMcpServer: respawn attempt cap reached for $workspaceId/$modulePath " +
          "($attempts > $MAX_RESPAWN_ATTEMPTS_PER_LIFETIME); giving up. " +
          "Re-run `./gradlew $modulePath:composePreviewDaemonStart` and call `register_project` " +
          "again to retry."
      )
      return
    }

    daemonLifecycleExecutor.execute {
      val outcome = runCatching {
        supervisor.daemonFor(workspaceId, modulePath)
      }
        .onFailure {
          System.err.println(
            "DaemonMcpServer: classpathDirty respawn failed for $workspaceId/$modulePath: " +
              "${it.message}"
          )
        }
      if (outcome.isSuccess) {
        // Reset the attempt counter on a clean respawn — a future classpathDirty starts fresh.
        respawnAttempts.remove(attemptKey)
      }
    }
  }

  // -------------------------------------------------------------------------
  // Internals
  // -------------------------------------------------------------------------

  private data class DaemonAddr(val workspaceId: WorkspaceId, val modulePath: String)

  private data class PreviewEntry(
    val fqn: String,
    val displayName: String?,
    val config: String?,
    val sourceFile: String?,
    /**
     * Bare `@Composable` method name of the `@Preview` function (the wire field `functionName` on a
     * `discoveryUpdated` entry — `PreviewInfoDto.methodName`). Distinct from [fqn]/[displayName]: a
     * named or variant preview (`@Preview(name = "Light")`) carries a synthetic id like
     * `…WeatherForecast_Light` while every variant of the same function shares this base method
     * name. `null` for legacy entries / fakes that don't send the field. Used by
     * [generateRecordingTestSource] to emit a `setContent { <functionName>() }` call that actually
     * compiles. See issue #1807.
     */
    val functionName: String? = null,
    val sourceLastModifiedMs: Long? = null,
    /**
     * SHA-256 of the source file's bytes captured at discovery and refreshed on every
     * [ensureSourceFreshBeforeRender] call that fires a `fileChanged`. Lets the freshness check
     * detect content-only edits — same-millisecond writes on fast SSDs/tmpfs, mtime-preserving
     * editors, agent harnesses that touch files programmatically without bumping mtime — that the
     * mtime-only comparison misses. Null until the source is hashed for the first time (legacy
     * entries; null sourceFile; unreadable file).
     */
    val sourceContentHash: String? = null,
  )

  /**
   * Per-previewId queue key for [previewQueues]. `(workspace, module, previewId)` identifies the
   * render target; the queue's groups discriminate by `PreviewOverrides`. No `overrides` field on
   * the key itself — that's what makes serialization possible (see [awaitNextRender]).
   */
  private data class PreviewIdKey(
    val workspaceId: WorkspaceId,
    val modulePath: String,
    val previewId: String,
  )

  /**
   * One batch of waiters with shared `PreviewOverrides` queued behind the head group of a
   * [previewQueues] entry. `sent = true` when this group's `renderNow` has been issued to the
   * daemon (head group always has `sent = true` once the queue becomes non-empty). `futures` is
   * `CopyOnWriteArrayList` for the same reason the prior `pendingRenders` value type was — the
   * fanout-on-renderFinished happens outside the queue's compute lambda, and same-overrides dedup
   * adds inside `compute`, so iteration safety dominates over append throughput.
   */
  private class PendingRenderGroup(
    val overrides: PreviewOverrides?,
    val futures:
      java.util.concurrent.CopyOnWriteArrayList<
        java.util.concurrent.CompletableFuture<RenderOutcome>
      > =
      java.util.concurrent.CopyOnWriteArrayList(),
    @Volatile var sent: Boolean = false,
  )

  /**
   * Cache key for [dataProductCache]. `(workspace, module, previewId)` identifies the render-target
   * preview; `kind` discriminates the data-product attachment within that render.
   */
  private data class DataAttachKey(
    val workspaceId: WorkspaceId,
    val modulePath: String,
    val previewId: String,
    val kind: String,
  )

  /**
   * Cached `(payload | path)` from one `renderFinished.dataProducts[*]` entry. Mirrors the wire
   * shape; carries `schemaVersion` so a cache hit reports the same version the agent would see on a
   * direct `data/fetch`. `extras` carries the producer's derived files (e.g. the a11y overlay PNG)
   * so a cache hit on `get_preview_data` exposes the same paths the daemon would have returned.
   */
  private data class DataAttachmentEntry(
    val schemaVersion: Int,
    val payload: JsonElement?,
    val path: String?,
    val extras: JsonElement? = null,
  )

  private data class WatchReadiness(
    val modulePath: String,
    val spawned: Boolean,
    val discoveryReady: Boolean,
    val previewCount: Int,
  )

  private sealed interface RenderOutcome {
    data class Finished(val pngPath: String) : RenderOutcome

    data class Failed(
      val kind: String,
      val message: String,
      /**
       * One-line remediation the daemon classified for a recognised failure signature
       * (issue #1789), e.g. a classpath-skew or Robolectric SDK-mismatch fix hint. `null` when the
       * daemon had no specific suggestion (or pre-dates the field — tolerant decode).
       */
      val suggestion: String? = null,
    ) : RenderOutcome
  }

  private fun parseSchema(s: String): JsonElement = json.parseToJsonElement(s)

  /**
   * Schedules `notifications/progress` beats to [session] every [PROGRESS_BEAT_INTERVAL_MS] until
   * [future] completes. Returns the scheduled handle so the caller can cancel it on completion.
   *
   * No-op when [session] or [progressToken] is null — the client didn't opt in.
   *
   * The progress value is a wall-clock-elapsed-ms count rather than a render-progress estimate
   * because the daemon doesn't currently expose render progress. Total is left unset (unknown);
   * `message` carries a short status string the client can show as a tooltip / log line.
   */
  private fun startProgressBeatIfNeeded(
    session: Session?,
    progressToken: JsonElement?,
    future: java.util.concurrent.CompletableFuture<*>,
    uri: PreviewUri,
  ): java.util.concurrent.ScheduledFuture<*>? {
    if (session == null || progressToken == null) return null
    val start = System.currentTimeMillis()
    return progressBeatExecutor.scheduleAtFixedRate(
      {
        if (future.isDone) return@scheduleAtFixedRate
        runCatching {
          val elapsed = (System.currentTimeMillis() - start).toDouble()
          session.notifyProgress(
            token = progressToken,
            progress = elapsed,
            message = "rendering ${uri.previewFqn}",
          )
        }
      },
      PROGRESS_BEAT_INTERVAL_MS,
      PROGRESS_BEAT_INTERVAL_MS,
      TimeUnit.MILLISECONDS,
    )
  }

  private fun detectBranch(workspacePath: File): String? {
    val head = File(workspacePath, ".git/HEAD").takeIf { it.isFile } ?: return null
    val content =
      runCatching { fileSystem.read(head.path.toPath()) { readUtf8() }.trim() }.getOrNull()
        ?: return null
    return if (content.startsWith("ref:"))
      content.removePrefix("ref:").trim().substringAfterLast('/')
    else content.take(8)
  }

  private fun applyImageSizeOverride(pngBytes: ByteArray): ByteArray {
    val maxEdgePx = imageSizeOverride.maxEdgePx ?: return pngBytes
    val source = runCatching { ImageIO.read(pngBytes.inputStream()) }.getOrNull() ?: return pngBytes
    if (source.width <= maxEdgePx && source.height <= maxEdgePx) return pngBytes
    val scale = minOf(maxEdgePx.toDouble() / source.width, maxEdgePx.toDouble() / source.height)
    val targetWidth = maxOf(1, kotlin.math.floor(source.width * scale).toInt())
    val targetHeight = maxOf(1, kotlin.math.floor(source.height * scale).toInt())
    val target =
      java.awt.image.BufferedImage(
        targetWidth,
        targetHeight,
        java.awt.image.BufferedImage.TYPE_INT_ARGB,
      )
    val g = target.createGraphics()
    try {
      g.setRenderingHint(
        java.awt.RenderingHints.KEY_INTERPOLATION,
        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
      )
      g.setRenderingHint(
        java.awt.RenderingHints.KEY_RENDERING,
        java.awt.RenderingHints.VALUE_RENDER_QUALITY,
      )
      g.setRenderingHint(
        java.awt.RenderingHints.KEY_ANTIALIASING,
        java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
      )
      g.drawImage(source, 0, 0, targetWidth, targetHeight, null)
    } finally {
      g.dispose()
    }
    val out = java.io.ByteArrayOutputStream()
    ImageIO.write(target, "png", out)
    return out.toByteArray()
  }

  private data class ImageSizeOverride(val maxEdgePx: Int?) {
    companion object {
      fun detect(env: Map<String, String> = System.getenv()): ImageSizeOverride {
        // Claude Code frequently accumulates many screenshots in one request; Anthropic enforces a
        // 2000px/dimension cap on many-image requests there, so pre-scale defensively.
        if (
          !env["CLAUDE_CODE_SESSION_ID"].isNullOrBlank() || !env["CLAUDE_ENV_FILE"].isNullOrBlank()
        ) {
          return ImageSizeOverride(maxEdgePx = 2000)
        }
        if (
          env["__CFBundleIdentifier"] == "com.google.antigravity" ||
            !env["ANTIGRAVITY_CLI_ALIAS"].isNullOrBlank()
        ) {
          return ImageSizeOverride(maxEdgePx = 3072)
        }
        if (!env["CODEX_SANDBOX"].isNullOrBlank() || !env["CODEX_SESSION_ID"].isNullOrBlank()) {
          return ImageSizeOverride(maxEdgePx = 3072)
        }
        return ImageSizeOverride(maxEdgePx = null)
      }
    }
  }

  companion object {
    /**
     * Cap on consecutive `classpathDirty` self-loops before the supervisor stops respawning. One
     * legitimate retry covers the common case where the user/VS Code re-ran
     * `composePreviewDaemonStart` between the dirty event and the supervisor's worker firing.
     * Higher caps would just thrash if the descriptor is actually stale.
     */
    private const val MAX_RESPAWN_ATTEMPTS_PER_LIFETIME: Int = 1

    /**
     * Cadence for `notifications/progress` beats during a slow `resources/read`. 500ms strikes a
     * balance between "responsive UI updates" and "not flooding the wire on a fast render".
     */
    private const val PROGRESS_BEAT_INTERVAL_MS: Long = 500

    /**
     * If the full MCP tool catalog is still loading after this grace period, clients should keep
     * using the bootstrap tools and refresh `tools/list` after `notifications/tools/list_changed`.
     */
    private const val TOOL_CATALOG_NOTIFY_DELAY_MS: Long = 3_000

    /**
     * Worker count for [daemonLifecycleExecutor]. Sized so a few modules can cold-start in parallel
     * without forking enough JVMs to thrash the host; aligns with the supervisor's own
     * replica-spawn pool cap.
     */
    private const val DAEMON_LIFECYCLE_THREADS: Int = 4

    /** Suggested delay before polling `watch(awaitDiscovery=false)` readiness again. */
    private const val WATCH_DISCOVERY_RETRY_AFTER_MS: Long = 500

    /**
     * Default cadence for the background source-freshness poller. 30 s is slow enough to be cheap
     * (one stat + occasional SHA-256 per preview) and fast enough that an interactive editor sees a
     * refreshed render within the next render request. Override per-instance via the constructor's
     * `sourcePollIntervalMs`; pass `0` to disable.
     */
    const val DEFAULT_SOURCE_POLL_INTERVAL_MS: Long = 30_000

    /**
     * Default cadence for the random-sampling deterministic-render probe. 10 minutes keeps the
     * sampler well below 1 % of total render work in a normal session while giving operators enough
     * samples per hour to spot flaky previews. Override per-instance via the constructor's
     * `samplingIntervalMs`; pass `0` to disable.
     */
    const val DEFAULT_SAMPLING_INTERVAL_MS: Long = 10 * 60_000

    /**
     * D2.1 — default `kind` for `render_preview_overlay` when the caller doesn't specify one.
     * `a11y/overlay` is the only image-bearing kind in the catalogue today (it also serves as an
     * extra under `a11y/atf` and `a11y/hierarchy`); future kinds with PNG-shaped extras become
     * valid arguments without code changes here.
     */
    private const val DEFAULT_OVERLAY_KIND: String = "a11y/overlay"
  }
}
