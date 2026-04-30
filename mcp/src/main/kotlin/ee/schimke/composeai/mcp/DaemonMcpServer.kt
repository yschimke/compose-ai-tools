package ee.schimke.composeai.mcp

import ee.schimke.composeai.daemon.protocol.ChangeType
import ee.schimke.composeai.daemon.protocol.FileKind
import ee.schimke.composeai.daemon.protocol.RenderTier
import ee.schimke.composeai.mcp.protocol.CallToolResult
import ee.schimke.composeai.mcp.protocol.ContentBlock
import ee.schimke.composeai.mcp.protocol.Implementation
import ee.schimke.composeai.mcp.protocol.ListResourcesResult
import ee.schimke.composeai.mcp.protocol.ListToolsResult
import ee.schimke.composeai.mcp.protocol.ReadResourceResult
import ee.schimke.composeai.mcp.protocol.ResourceContents
import ee.schimke.composeai.mcp.protocol.ResourceDescriptor
import ee.schimke.composeai.mcp.protocol.ResourcesCapability
import ee.schimke.composeai.mcp.protocol.ServerCapabilities
import ee.schimke.composeai.mcp.protocol.ToolDef
import ee.schimke.composeai.mcp.protocol.ToolsCapability
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

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
   * Outstanding `resources/read` waiters, keyed by `(workspace, module, previewId)`. Awakened by
   * `renderFinished` notifications. We block at most [renderTimeoutMs] on the queue, then time out.
   */
  private val pendingRenders = ConcurrentHashMap<RenderKey, LinkedBlockingQueue<RenderOutcome>>()

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

  init {
    val router = supervisor.router()
    router.on("discoveryUpdated") { daemon, params -> onDiscoveryUpdated(daemon, params) }
    router.on("renderFinished") { daemon, params -> onRenderFinished(daemon, params) }
    router.on("renderFailed") { daemon, params -> onRenderFailed(daemon, params) }
    router.on("classpathDirty") { daemon, params ->
      System.err.println(
        "DaemonMcpServer: classpathDirty for ${daemon.workspaceId}/${daemon.modulePath}: " +
          (params?.get("detail")?.jsonPrimitive?.contentOrNull ?: "<no detail>")
      )
      // v0: log only. Respawn on next render. Future: push a logging notification + auto-respawn.
    }
    router.onClose { daemon ->
      catalog.remove(DaemonAddr(daemon.workspaceId, daemon.modulePath))
      watchPropagator.forget(daemon)
    }
  }

  // -------------------------------------------------------------------------
  // Public API consumed by McpSession via McpHandlers
  // -------------------------------------------------------------------------

  fun newSession(input: java.io.InputStream, output: java.io.OutputStream): McpSession {
    lateinit var session: McpSession
    val handlers =
      object : McpHandlers {
        override fun listTools(session: McpSession): JsonElement =
          json.encodeToJsonElement(ListToolsResult.serializer(), ListToolsResult(toolDefs))

        override fun callTool(
          session: McpSession,
          name: String,
          arguments: JsonElement?,
        ): CallToolResult = handleCallTool(session, name, arguments)

        override fun listResources(session: McpSession): JsonElement {
          val resources = catalogResources()
          return json.encodeToJsonElement(
            ListResourcesResult.serializer(),
            ListResourcesResult(resources),
          )
        }

        override fun readResource(session: McpSession, uri: String): ReadResourceResult =
          handleReadResource(uri)

        override fun subscribe(session: McpSession, uri: String) {
          subscriptions.subscribe(uri, session)
        }

        override fun unsubscribe(session: McpSession, uri: String) {
          subscriptions.unsubscribe(uri, session)
        }

        override fun onClose(session: McpSession) {
          subscriptions.forget(session)
          sessions.unregister(session)
        }
      }
    session =
      McpSession(
        input = input,
        output = output,
        handlers = handlers,
        serverInfo = serverInfo,
        capabilities =
          ServerCapabilities(
            tools = ToolsCapability(listChanged = false),
            resources = ResourcesCapability(subscribe = true, listChanged = true),
          ),
      )
    sessions.register(session)
    return session
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

  private fun handleReadResource(uri: String): ReadResourceResult {
    val parsed = PreviewUri.parseOrNull(uri) ?: error("Invalid compose-preview URI: '$uri'")
    val pngBytes = renderAndReadBytes(parsed)
    val encoded = Base64.getEncoder().encodeToString(pngBytes)
    return ReadResourceResult(
      contents = listOf(ResourceContents.Blob(uri = uri, mimeType = "image/png", blob = encoded))
    )
  }

  private fun renderAndReadBytes(uri: PreviewUri): ByteArray {
    val daemon = supervisor.daemonFor(uri.workspaceId, uri.modulePath)
    val key = RenderKey(uri.workspaceId, uri.modulePath, uri.previewFqn)
    val slot = pendingRenders.computeIfAbsent(key) { LinkedBlockingQueue() }
    daemon.client.renderNow(previews = listOf(uri.previewFqn), tier = RenderTier.FULL)
    val outcome =
      slot.poll(renderTimeoutMs, TimeUnit.MILLISECONDS)
        ?: run {
          pendingRenders.remove(key)
          error("renderAndReadBytes: timed out after ${renderTimeoutMs}ms for $uri")
        }
    pendingRenders.remove(key)
    when (outcome) {
      is RenderOutcome.Failed ->
        error("renderAndReadBytes failed for $uri: ${outcome.kind} ${outcome.message}")
      is RenderOutcome.Finished -> {
        val file = File(outcome.pngPath)
        check(file.isFile) { "renderAndReadBytes: pngPath does not exist: ${outcome.pngPath}" }
        return Files.readAllBytes(file.toPath())
      }
    }
  }

  // -------------------------------------------------------------------------
  // Tool surface
  // -------------------------------------------------------------------------

  private val toolDefs: List<ToolDef> =
    listOf(
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
        name = "render_preview",
        description =
          "Force-render a preview by URI, bypassing any cache. Returns the rendered PNG inline.",
        inputSchema =
          parseSchema(
            """
            {
              "type":"object",
              "properties":{
                "uri":{"type":"string","description":"compose-preview://<workspace>/<module>/<fqn>?config=<qualifier>"}
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
                "fqnGlob":{"type":"string","description":"Optional FQN glob; '*' matches non-dot, '**' matches anything, '?' one non-dot char."}
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
    )

  private fun handleCallTool(
    session: McpSession,
    name: String,
    arguments: JsonElement?,
  ): CallToolResult {
    val args = (arguments as? JsonObject) ?: JsonObject(emptyMap())
    return when (name) {
      "register_project" -> toolRegisterProject(args)
      "unregister_project" -> toolUnregisterProject(args)
      "list_projects" -> toolListProjects()
      "render_preview" -> toolRenderPreview(args)
      "watch" -> toolWatch(session, args)
      "unwatch" -> toolUnwatch(session, args)
      "list_watches" -> toolListWatches(session)
      "notify_file_changed" -> toolNotifyFileChanged(args)
      else -> errorCallToolResult("unknown tool: $name")
    }
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

  private fun toolRenderPreview(args: JsonObject): CallToolResult {
    val uriStr =
      args["uri"]?.jsonPrimitive?.contentOrNull
        ?: return errorCallToolResult("render_preview: missing 'uri'")
    val uri = PreviewUri.parseOrNull(uriStr) ?: return errorCallToolResult("invalid uri: $uriStr")
    return runCatching {
        val bytes = renderAndReadBytes(uri)
        pngCallToolResult(Base64.getEncoder().encodeToString(bytes))
      }
      .getOrElse { errorCallToolResult("render_preview failed: ${it.message}") }
  }

  private fun toolWatch(session: McpSession, args: JsonObject): CallToolResult {
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
    toSpawn.forEach { mp ->
      runCatching { supervisor.daemonFor(workspaceId, mp) }
        .onFailure { System.err.println("watch: lazy spawn failed for $mp: ${it.message}") }
    }
    // Recompute every daemon's view; the propagator skips daemons whose URI set didn't change.
    project.daemons.values.forEach { watchPropagator.recompute(it) }
    return textCallToolResult("watching $entry (spawned ${toSpawn.size} daemon(s))")
  }

  private fun toolUnwatch(session: McpSession, args: JsonObject): CallToolResult {
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

  private fun toolListWatches(session: McpSession): CallToolResult {
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
      runCatching { daemon.client.fileChanged(path = path, kind = kind, changeType = changeType) }
        .onSuccess { forwarded++ }
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
        runCatching {
          daemon.client.renderNow(
            previews = ofInterest.map { it.previewFqn },
            tier = RenderTier.FULL,
            reason = "notify_file_changed:$path",
          )
        }
        rendered += ofInterest.size
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
      byId[id] =
        PreviewEntry(
          fqn = id,
          displayName = entry["displayName"]?.jsonPrimitive?.contentOrNull,
          config = entry["config"]?.jsonPrimitive?.contentOrNull,
        )
    }
    removed.forEach { byId.remove(it) }
    sessions.forEach { it.notifyResourceListChanged() }
    watchPropagator.recompute(daemon)
  }

  private fun onRenderFinished(daemon: SupervisedDaemon, params: JsonObject?) {
    val previewId = params?.get("id")?.jsonPrimitive?.contentOrNull ?: return
    val pngPath = params["pngPath"]?.jsonPrimitive?.contentOrNull ?: return
    // 1. Wake any pending resources/read awaiter.
    val key = RenderKey(daemon.workspaceId, daemon.modulePath, previewId)
    pendingRenders[key]?.offer(RenderOutcome.Finished(pngPath))
    // 2. Build the matching URI and notify subscribers + watchers.
    val entry = catalog[DaemonAddr(daemon.workspaceId, daemon.modulePath)]?.get(previewId)
    val uri =
      PreviewUri(
        workspaceId = daemon.workspaceId,
        modulePath = daemon.modulePath,
        previewFqn = previewId,
        config = entry?.config,
      )
    val uriStr = uri.toUri()
    val targets = mutableSetOf<Any>()
    targets.addAll(subscriptions.sessionsSubscribedTo(uriStr))
    targets.addAll(subscriptions.sessionsWatching(uri))
    targets.forEach { (it as? McpSession)?.notifyResourceUpdated(uriStr) }
    // 3. Record history (no-op default).
    runCatching { historyStore.record(uri, pngPath, Instant.now()) }
  }

  private fun onRenderFailed(daemon: SupervisedDaemon, params: JsonObject?) {
    val previewId = params?.get("id")?.jsonPrimitive?.contentOrNull ?: return
    val errorObj = params["error"] as? JsonObject
    val kind = errorObj?.get("kind")?.jsonPrimitive?.contentOrNull ?: "unknown"
    val message = errorObj?.get("message")?.jsonPrimitive?.contentOrNull ?: "no message"
    val key = RenderKey(daemon.workspaceId, daemon.modulePath, previewId)
    pendingRenders[key]?.offer(RenderOutcome.Failed(kind, message))
  }

  // -------------------------------------------------------------------------
  // Internals
  // -------------------------------------------------------------------------

  private data class DaemonAddr(val workspaceId: WorkspaceId, val modulePath: String)

  private data class PreviewEntry(val fqn: String, val displayName: String?, val config: String?)

  private data class RenderKey(
    val workspaceId: WorkspaceId,
    val modulePath: String,
    val previewId: String,
  )

  private sealed interface RenderOutcome {
    data class Finished(val pngPath: String) : RenderOutcome

    data class Failed(val kind: String, val message: String) : RenderOutcome
  }

  private fun parseSchema(s: String): JsonElement = json.parseToJsonElement(s)

  private fun detectBranch(workspacePath: File): String? {
    val head = File(workspacePath, ".git/HEAD").takeIf { it.isFile } ?: return null
    val content = runCatching { head.readText().trim() }.getOrNull() ?: return null
    return if (content.startsWith("ref:"))
      content.removePrefix("ref:").trim().substringAfterLast('/')
    else content.take(8)
  }
}
