package ee.schimke.composeai.mcp

import ee.schimke.composeai.daemon.protocol.ChangeType
import ee.schimke.composeai.daemon.protocol.FileKind
import ee.schimke.composeai.daemon.protocol.Material3ThemeOverrides
import ee.schimke.composeai.daemon.protocol.Orientation
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RecordingScriptEventStatus
import ee.schimke.composeai.daemon.protocol.RenderTier
import ee.schimke.composeai.daemon.protocol.UiMode
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionCommandCatalog
import ee.schimke.composeai.data.render.pipeline.PreviewExtensionDescriptor
import ee.schimke.composeai.mcp.protocol.CallToolResult
import ee.schimke.composeai.mcp.protocol.ContentBlock
import ee.schimke.composeai.mcp.protocol.ReadResourceResult
import ee.schimke.composeai.mcp.protocol.ResourceContents
import ee.schimke.composeai.mcp.protocol.ResourceDescriptor
import ee.schimke.composeai.mcp.protocol.ToolDef
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.io.File
import java.nio.file.Files
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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
) {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }

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

  private val fullToolDefsFuture: CompletableFuture<List<ToolDef>> =
    CompletableFuture.supplyAsync({ buildFullToolDefs() }, toolCatalogExecutor).also { future ->
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
        } else if (fullToolCatalogWasDelayed.get()) {
          sessions.forEach { it.notifyToolListChanged() }
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
  }

  // -------------------------------------------------------------------------
  // Public API consumed by the SDK-backed MCP session
  // -------------------------------------------------------------------------

  fun newSession(input: java.io.InputStream, output: java.io.OutputStream): McpSession {
    lateinit var session: McpSession
    val sdkServer = composePreviewSdkServer(serverInfo)
    session =
      McpSession(
        server = sdkServer,
        input = input,
        output = output,
        configure = { sdkSession ->
          sdkServer.installComposePreviewHandlers(
            sdkSession = sdkSession,
            session = session,
            listTools = { currentToolDefs() },
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
          Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath()))
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
    return Files.readAllBytes(file.toPath())
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
        error("awaitNextRender failed for $uri: ${outcome.kind} ${outcome.message}")
      is RenderOutcome.Finished -> outcome
    }
  }

  private fun ensureSourceFreshBeforeRender(uri: PreviewUri, daemon: SupervisedDaemon) {
    val addr = DaemonAddr(uri.workspaceId, uri.modulePath)
    val entry = catalog[addr]?.get(uri.previewFqn) ?: return
    val sourceFile = resolvePreviewSourceFile(uri, entry.sourceFile) ?: return
    val currentModifiedMs = sourceFile.lastModified().takeIf { it > 0L } ?: return
    val knownModifiedMs =
      entry.sourceLastModifiedMs
        ?: run {
          catalog[addr]?.computeIfPresent(uri.previewFqn) { _, current ->
            current.copy(sourceLastModifiedMs = currentModifiedMs)
          }
          return
        }
    if (currentModifiedMs <= knownModifiedMs) return

    daemon.allClients().forEach { client ->
      runCatching {
        client.fileChanged(
          path = sourceFile.absolutePath,
          kind = FileKind.SOURCE,
          changeType = ChangeType.MODIFIED,
        )
      }
    }
    catalog[addr]?.computeIfPresent(uri.previewFqn) { _, current ->
      current.copy(sourceLastModifiedMs = currentModifiedMs)
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

  private fun currentToolDefs(): List<ToolDef> =
    if (!fullToolDefsFuture.isDone) {
      bootstrapToolDefs
    } else {
      runCatching { fullToolDefsFuture.getNow(bootstrapToolDefs) }.getOrDefault(bootstrapToolDefs)
    }

  private val bootstrapToolDefs: List<ToolDef> by lazy {
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
        description = "Force-render a preview by URI, bypassing any cache. Returns the PNG inline.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "uri":{"type":"string","description":"compose-preview://<workspace>/<module>/<fqn>?config=<qualifier>"},
                "overrides":{"type":"object","description":"Optional per-call display overrides."}
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
            "annotations. The free-form `spec:width=…,height=…,dpi=…` grammar is not enumerable " +
            "and is not returned here — pass it as a `device` override and the daemon parses it " +
            "at resolve-time. Mirror of every daemon's " +
            "`InitializeResult.capabilities.knownDevices`; read directly from the shared " +
            "`DeviceDimensions` rather than going through a daemon, so it works before any " +
            "daemon has spawned.",
        inputSchema = parseSchema("""{"type":"object","properties":{}}"""),
      ),
      ToolDef(
        name = "render_preview",
        description =
          "Force-render a preview by URI, bypassing any cache. Returns the rendered PNG inline. " +
            "Optional `overrides` apply per-call display-property overrides (size, density, " +
            "locale, fontScale, uiMode, orientation, device, inspectionMode) — see PROTOCOL.md " +
            "§ 5 (`renderNow.overrides`).",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "uri":{"type":"string","description":"compose-preview://<workspace>/<module>/<fqn>?config=<qualifier>"},
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
                    "device":{"type":"string","description":"@Preview(device=...) string — 'id:pixel_5', 'id:wearos_small_round', 'id:tv_1080p', or full 'spec:width=400dp,height=800dp,dpi=320'. Resolved by the daemon's catalog into widthPx/heightPx/density; explicit width/height/density overrides on this same object take precedence."},
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
                    }
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
          "Record a scripted screen-recording of an interactive preview and return the encoded " +
            "video bytes. Drives the daemon's recording surface (RECORDING.md) end-to-end: open a " +
            "held-scene session at the requested fps + scale, post the script of `(tMs, kind, " +
            "pixelX, pixelY)` events, play back the timeline at virtual frame time, encode to APNG " +
            "and return the bytes inline as a base64 image content block. " +
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
                "format":{"type":"string","enum":["apng","mp4","webm"],"description":"Encoded video format. Default 'apng' (always available, pure-JVM). 'mp4' and 'webm' require an ffmpeg binary on the daemon's PATH; check ServerCapabilities.recordingFormats first or expect a clean rejection if unavailable."},
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
                      "scrollDeltaY":{"type":"number","description":"For 'rotaryScroll'."},
                      "keyCode":{"type":"string","description":"For 'keyDown'/'keyUp' (reserved; v1 dispatch is a no-op)."},
                      "label":{"type":"string","description":"Agent label copied into scriptEvents evidence for probes/checkpoints."},
                      "checkpointId":{"type":"string","description":"Checkpoint id for state save/restore audit events."},
                      "lifecycleEvent":{"type":"string","description":"Lifecycle transition for lifecycle script events, e.g. resume, pause, destroy."},
                      "tags":{"type":"array","items":{"type":"string"},"description":"Optional agent tags copied into scriptEvents evidence."},
                      "nodeContentDescription":{"type":"string","description":"For `a11y.action.*` kinds — visible content description of the target accessibility node (`Modifier.semantics { contentDescription = ... }` / `Icon(contentDescription = ...)`). The daemon resolves this against the held composition's semantics tree and dispatches the corresponding SemanticsActions action — same lookup a screen reader walks via AccessibilityNodeInfo.performAction. Ignored for input/probe/state/lifecycle events."}
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
    )

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
      "watch" -> toolWatch(session, args)
      "unwatch" -> toolUnwatch(session, args)
      "list_watches" -> toolListWatches(session)
      "notify_file_changed" -> toolNotifyFileChanged(args)
      "set_visible" -> toolSetVisible(args)
      "set_focus" -> toolSetFocus(args)
      "history_list" -> toolHistoryList(args)
      "history_diff" -> toolHistoryDiff(args)
      "list_data_products" -> toolListDataProducts(args)
      "list_extension_commands" -> toolListExtensionCommands(args)
      "run_extension_command" -> toolRunExtensionCommand(args)
      "get_preview_data" -> toolGetPreviewData(args)
      "render_preview_overlay" -> toolRenderPreviewOverlay(args)
      "get_preview_extras" -> toolGetPreviewExtras(args)
      "subscribe_preview_data" -> toolDataSubOrUnsub(session, args, subscribe = true)
      "unsubscribe_preview_data" -> toolDataSubOrUnsub(session, args, subscribe = false)
      "record_preview" -> toolRecordPreview(args)
      else -> errorCallToolResult("unknown tool: $name")
    }
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
    val overrides =
      args["overrides"]?.let {
        runCatching { decodePreviewOverrides(it) }
          .getOrElse { e ->
            return errorCallToolResult("render_preview: invalid overrides: ${e.message}")
          }
      }
    if (overrides != null) {
      val daemon = supervisor.daemonFor(uri.workspaceId, uri.modulePath)
      val violations = validateOverrides(overrides, daemon)
      if (violations.isNotEmpty()) {
        return errorCallToolResult("render_preview: ${violations.joinToString("; ")}")
      }
    }
    return runCatching {
        val bytes = renderAndReadBytes(uri, overrides = overrides)
        pngCallToolResult(Base64.getEncoder().encodeToString(bytes))
      }
      .getOrElse { errorCallToolResult("render_preview failed: ${it.message}") }
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
      material3Theme =
        obj["material3Theme"]
          ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
          ?.let { json.decodeFromJsonElement(Material3ThemeOverrides.serializer(), it) },
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

  /**
   * Resolves the (workspaceId, module) pair the history tools need plus the live daemon — the
   * daemon owns the configured `HistoryManager`, so every history call goes through it.
   */
  private fun resolveHistoryDaemon(
    args: JsonObject,
    toolName: String,
  ): Pair<SupervisedDaemon, String>? {
    val ws =
      args["workspaceId"]?.jsonPrimitive?.contentOrNull
        ?: return null.also {
          // Caller wraps this null into an errorCallToolResult; we surface the missing-field
          // message there.
        }
    val workspaceId = WorkspaceId(ws)
    if (supervisor.project(workspaceId) == null) return null
    val module = args["module"]?.jsonPrimitive?.contentOrNull ?: return null
    val daemon =
      runCatching { supervisor.daemonFor(workspaceId, module) }.getOrNull() ?: return null
    return daemon to module
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
    val daemon =
      runCatching { supervisor.daemonFor(workspaceId, module) }
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
    val daemon =
      runCatching { supervisor.daemonFor(workspaceId, module) }
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
    val result =
      runCatching { daemon.client.historyList(params) }
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
    val daemon =
      runCatching { supervisor.daemonFor(workspaceId, module) }
        .getOrElse {
          return errorCallToolResult("history_diff: daemon spawn failed: ${it.message}")
        }
    val result =
      runCatching {
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
                        ee.schimke.composeai.data.render.extensions.DataExtensionDescriptor
                          .serializer(),
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
    val daemon =
      runCatching { supervisor.daemonFor(uri.workspaceId, uri.modulePath) }
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
    val daemon =
      runCatching { supervisor.daemonFor(uri.workspaceId, uri.modulePath) }
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
        val fetchResult =
          daemon.client.dataFetch(uri.previewFqn, kind, params = null, inline = false)
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
          pngCallToolResult(Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath())))
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
    val daemon =
      runCatching { supervisor.daemonFor(uri.workspaceId, uri.modulePath) }
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
        val fetched =
          runCatching {
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
    val events =
      runCatching { decodeRecordingEvents(eventsRaw) }
        .getOrElse {
          return errorCallToolResult("record_preview: invalid events: ${it.message}")
        }
    // Strict numeric validation — distinguish "absent" (use daemon default) from "malformed"
    // (return a clean diagnostic). The previous lenient `toIntOrNull` / `toFloatOrNull` swallowed
    // typos like `"fps": "fast"` into a silently-defaulted recording, which is hard to trust
    // when a script's timing is wrong but no error surfaces.
    val fps =
      runCatching { decodeOptionalInt("fps", args["fps"]) }
        .getOrElse {
          return errorCallToolResult("record_preview: invalid fps: ${it.message}")
        }
    val scale =
      runCatching { decodeOptionalFloat("scale", args["scale"]) }
        .getOrElse {
          return errorCallToolResult("record_preview: invalid scale: ${it.message}")
        }
    val formatStr = args["format"]?.jsonPrimitive?.contentOrNull?.lowercase()
    val format =
      when (formatStr) {
        null,
        "apng" -> RecordingFormat.APNG
        "mp4" -> RecordingFormat.MP4
        "webm" -> RecordingFormat.WEBM
        else ->
          return errorCallToolResult(
            "record_preview: unsupported 'format' '$formatStr' — supported: apng, mp4, webm"
          )
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
    val daemon =
      runCatching { supervisor.daemonFor(uri.workspaceId, uri.modulePath) }
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

    val started =
      runCatching {
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
        val videoBytes = Files.readAllBytes(File(encoded.videoPath).toPath())
        val payload = buildJsonObject {
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
                  frame.dimensionChangedFromPrevious?.let {
                    put("dimensionChangedFromPrevious", it)
                  }
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
                }
              )
            }
          }
        }
        // Per the MCP 2025-06-18 spec, only `image/*` mimeTypes belong in `ContentBlock.Image`;
        // strict clients reject mismatches. APNG (`image/apng`) round-trips as an image; mp4 /
        // webm route through `EmbeddedResource` wrapping a `Blob` so a client that already
        // understands `resources/read` reads them via the same code path.
        val base64 = Base64.getEncoder().encodeToString(videoBytes)
        val mediaBlock: ContentBlock =
          if (encoded.mimeType.startsWith("image/")) {
            ContentBlock.Image(data = base64, mimeType = encoded.mimeType)
          } else {
            ContentBlock.EmbeddedResource(
              resource =
                ResourceContents.Blob(
                  uri = "compose-preview-recording://$recordingId",
                  mimeType = encoded.mimeType,
                  blob = base64,
                )
            )
          }
        CallToolResult(content = listOf(mediaBlock, ContentBlock.Text(payload.toString())))
      }
      .getOrElse { errorCallToolResult("record_preview failed: ${it.message}") }
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
      val image = runCatching { ImageIO.read(frame) }.getOrNull()
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
        scrollDeltaY = obj["scrollDeltaY"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull(),
        keyCode = obj["keyCode"]?.jsonPrimitive?.contentOrNull,
        label = obj["label"]?.jsonPrimitive?.contentOrNull,
        checkpointId = obj["checkpointId"]?.jsonPrimitive?.contentOrNull,
        lifecycleEvent = obj["lifecycleEvent"]?.jsonPrimitive?.contentOrNull,
        tags =
          (obj["tags"] as? JsonArray)?.mapNotNull { tag -> tag.jsonPrimitive.contentOrNull }
            ?: emptyList(),
        nodeContentDescription = obj["nodeContentDescription"]?.jsonPrimitive?.contentOrNull,
      )
    }
  }

  private fun RecordingScriptEventStatus.wireName(): String =
    when (this) {
      RecordingScriptEventStatus.APPLIED -> "applied"
      RecordingScriptEventStatus.UNSUPPORTED -> "unsupported"
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
        else ->
          "event[$index] kind '${event.kind}' is not advertised by this daemon. Call " +
            "list_data_products to see the available script-event ids."
      }
    }
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
    val daemon =
      runCatching { supervisor.daemonFor(uri.workspaceId, uri.modulePath) }
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
      val sourceLastModifiedMs =
        resolvePreviewSourceFile(
            PreviewUri(
              workspaceId = daemon.workspaceId,
              modulePath = daemon.modulePath,
              previewFqn = id,
              config = entry["config"]?.jsonPrimitive?.contentOrNull,
            ),
            sourceFile,
          )
          ?.lastModified()
          ?.takeIf { it > 0L }
      byId[id] =
        PreviewEntry(
          fqn = id,
          displayName = entry["displayName"]?.jsonPrimitive?.contentOrNull,
          config = entry["config"]?.jsonPrimitive?.contentOrNull,
          sourceFile = sourceFile,
          sourceLastModifiedMs = sourceLastModifiedMs,
        )
    }
    removed.forEach { byId.remove(it) }
    sessions.forEach { it.notifyResourceListChanged() }
    watchPropagator.recompute(daemon)
  }

  private fun onRenderFinished(daemon: SupervisedDaemon, params: JsonObject?) {
    val previewId = params?.get("id")?.jsonPrimitive?.contentOrNull ?: return
    val pngPath = params["pngPath"]?.jsonPrimitive?.contentOrNull ?: return
    // 1. Pop the head group of this URI's queue, wake its waiters with the rendered bytes, and
    //    promote-and-dispatch the next group's renderNow if one is queued. This is the
    //    serialization core that PR #432's by-previewId fanout (now removed) tried to paper
    //    over — see `popHeadAndPromoteNext` and `awaitNextRender`'s kdoc for the rationale.
    val key = PreviewIdKey(daemon.workspaceId, daemon.modulePath, previewId)
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
    // Same pop-and-promote shape as `onRenderFinished` — failure of the head group does NOT
    // sympathetically fail queued non-head groups. Different overrides could plausibly succeed
    // even when O1 throws (e.g., a composable that fails only at small widths), so we keep the
    // queue draining: pop the failed head, wake its waiters with the failure, and dispatch the
    // next group's renderNow normally. If a follow-up group's render also fails, the same path
    // surfaces it.
    val key = PreviewIdKey(daemon.workspaceId, daemon.modulePath, previewId)
    popHeadAndPromoteNext(daemon, key, RenderOutcome.Failed(kind, message))
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
      val outcome =
        runCatching { supervisor.daemonFor(workspaceId, modulePath) }
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
    val sourceLastModifiedMs: Long? = null,
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

    data class Failed(val kind: String, val message: String) : RenderOutcome
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
    val content = runCatching { head.readText().trim() }.getOrNull() ?: return null
    return if (content.startsWith("ref:"))
      content.removePrefix("ref:").trim().substringAfterLast('/')
    else content.take(8)
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
     * D2.1 — default `kind` for `render_preview_overlay` when the caller doesn't specify one.
     * `a11y/overlay` is the only image-bearing kind in the catalogue today (it also serves as an
     * extra under `a11y/atf` and `a11y/hierarchy`); future kinds with PNG-shaped extras become
     * valid arguments without code changes here.
     */
    private const val DEFAULT_OVERLAY_KIND: String = "a11y/overlay"
  }
}
