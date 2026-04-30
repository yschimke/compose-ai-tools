package ee.schimke.composeai.mcp

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Owner of every per-(workspace, module) [DaemonClient] in this MCP server process. Multi-workspace
 * by design — see the chat thread leading into this PR — so a single MCP server can host previews
 * from multiple distinct projects, including two worktrees of the same repo.
 *
 * **Workspace registration is explicit.** Clients call the `register_project` MCP tool (or the
 * server is started with `--project <path>` CLI args); the supervisor canonicalises the path,
 * derives a [WorkspaceId], and remembers the project. Daemons within the workspace are spawned
 * lazily on first `read`/`render_preview`/`watch` reference.
 *
 * **Notification routing.** The supervisor demultiplexes every daemon's notification stream by
 * method and dispatches via the [NotificationRouter] handlers. Callers register one router up
 * front; the supervisor never assumes only one client cares about a given event.
 */
class DaemonSupervisor(
  private val descriptorProvider: DescriptorProvider,
  private val clientFactory: DaemonClientFactory,
  private val router: NotificationRouter = NotificationRouter(),
) {

  private val projects = ConcurrentHashMap<WorkspaceId, RegisteredProject>()

  /**
   * Registers a project at [absolutePath] (must already exist on disk). Returns the assigned
   * [WorkspaceId]; idempotent — re-registering the same canonical path returns the existing id.
   *
   * [rootProjectName] may be supplied (e.g. parsed from `settings.gradle.kts`) for nicer ids; if
   * null, the directory's basename is used.
   *
   * [knownModules] is the optional initial set of preview-eligible Gradle module paths in this
   * project. The supervisor will not spawn daemons for them — that stays lazy — but `list_projects`
   * and the resource list can advertise them up front so a client doesn't have to probe.
   */
  fun registerProject(
    absolutePath: File,
    rootProjectName: String? = null,
    knownModules: List<String> = emptyList(),
  ): RegisteredProject {
    require(absolutePath.isDirectory) {
      "registerProject: path '${absolutePath.absolutePath}' is not a directory"
    }
    val canonical =
      runCatching { absolutePath.canonicalFile }.getOrDefault(absolutePath.absoluteFile)
    val name = rootProjectName ?: canonical.name
    val workspaceId = WorkspaceId.derive(name, canonical)
    val project =
      projects.computeIfAbsent(workspaceId) {
        RegisteredProject(
          workspaceId = workspaceId,
          rootProjectName = name,
          path = canonical,
          knownModules = knownModules.toMutableList(),
        )
      }
    // Idempotent: merge module hints if the second call learned more.
    if (knownModules.isNotEmpty()) {
      synchronized(project.knownModules) {
        for (m in knownModules) if (m !in project.knownModules) project.knownModules.add(m)
      }
    }
    return project
  }

  /**
   * Tears down every daemon for [workspaceId] and forgets the project. Idempotent — unregistering
   * an unknown id is a no-op.
   */
  fun unregisterProject(workspaceId: WorkspaceId) {
    val project = projects.remove(workspaceId) ?: return
    project.daemons.values.forEach { runCatching { it.shutdown() } }
    project.daemons.clear()
  }

  fun listProjects(): List<RegisteredProject> = projects.values.toList()

  fun project(workspaceId: WorkspaceId): RegisteredProject? = projects[workspaceId]

  /**
   * Returns (and lazily spawns) the daemon for [workspaceId] + [modulePath]. Throws when the
   * workspace isn't registered or the module's daemon descriptor is missing.
   *
   * Spawn cost is paid by the calling thread — typical first-request latency is the daemon's
   * cold-start time (3-10s for Robolectric, ~600ms for desktop).
   */
  fun daemonFor(workspaceId: WorkspaceId, modulePath: String): SupervisedDaemon {
    val project = projects[workspaceId] ?: error("workspace not registered: $workspaceId")
    return project.daemons.computeIfAbsent(modulePath) { spawn(project, modulePath) }
  }

  /** Closes every daemon. After this call the supervisor is unusable. */
  fun shutdown() {
    projects.values.forEach { project ->
      project.daemons.values.forEach { runCatching { it.shutdown() } }
      project.daemons.clear()
    }
    projects.clear()
  }

  /** Returns the [NotificationRouter] so callers can register handlers. */
  fun router(): NotificationRouter = router

  // -------------------------------------------------------------------------
  // Internals
  // -------------------------------------------------------------------------

  private fun spawn(project: RegisteredProject, modulePath: String): SupervisedDaemon {
    val descriptor = descriptorProvider.descriptorFor(project, modulePath)
    val spawn = clientFactory.spawn(project, descriptor)
    val supervised =
      SupervisedDaemon(workspaceId = project.workspaceId, modulePath = modulePath, spawn = spawn)
    spawn.client(
      onNotification = { method, params -> router.dispatch(supervised, method, params) },
      onClose = { router.dispatchClose(supervised) },
    )
    runCatching {
      val result =
        spawn.client.initialize(
          workspaceRoot = project.path.absolutePath,
          moduleId = modulePath,
          moduleProjectDir = descriptor.workingDirectory,
        )
      // The daemon only emits `discoveryUpdated` for *deltas* — the initial preview set comes via
      // `initialize.manifest.path` (a `previews.json` written by the gradle plugin's
      // `discoverPreviews` task). Synthesise an initial `discoveryUpdated` notification by reading
      // that file and dispatching it through the router as if it were a wire-level event. This
      // keeps every catalog-population code path (DaemonMcpServer.onDiscoveryUpdated) on a single
      // shape rather than splitting "initial seed" vs "incremental".
      synthesiseInitialDiscovery(supervised, result.manifest.path)
    }
    return supervised
  }

  private fun synthesiseInitialDiscovery(daemon: SupervisedDaemon, manifestPath: String) {
    if (manifestPath.isBlank()) return
    val file = File(manifestPath)
    if (!file.isFile) return
    val previews =
      runCatching {
          val text = file.readText()
          val arr =
            (Json.parseToJsonElement(text) as? JsonObject)?.get("previews")
              as? kotlinx.serialization.json.JsonArray ?: return@runCatching null
          arr.mapNotNull { it as? JsonObject }
        }
        .getOrNull() ?: return
    if (previews.isEmpty()) return
    val params =
      kotlinx.serialization.json.buildJsonObject {
        put("added", kotlinx.serialization.json.JsonArray(previews))
        put("removed", kotlinx.serialization.json.JsonArray(emptyList()))
        put("changed", kotlinx.serialization.json.JsonArray(emptyList()))
        put("totalPreviews", kotlinx.serialization.json.JsonPrimitive(previews.size))
      }
    router.dispatch(daemon, "discoveryUpdated", params)
  }
}

/**
 * One registered project — a workspace. Holds the canonical path, the assigned id, the (lazily
 * populated) daemon map, and the optional seed list of preview-eligible modules.
 */
data class RegisteredProject(
  val workspaceId: WorkspaceId,
  val rootProjectName: String,
  val path: File,
  val knownModules: MutableList<String>,
  val daemons: ConcurrentHashMap<String, SupervisedDaemon> = ConcurrentHashMap(),
)

/** A live daemon — owned by [DaemonSupervisor]. */
class SupervisedDaemon(
  val workspaceId: WorkspaceId,
  val modulePath: String,
  private val spawn: DaemonSpawn,
) {
  val client: DaemonClient
    get() = spawn.client

  fun shutdown() = spawn.shutdown()
}

/**
 * Pluggable seam for resolving the per-module daemon launch descriptor. The default implementation
 * reads `<workingDir>/build/compose-previews/daemon-launch.json` written by
 * [`composePreviewDaemonStart`][ee.schimke.composeai.plugin.daemon.DaemonBootstrapTask] in the
 * gradle plugin. Tests substitute an in-memory provider.
 */
fun interface DescriptorProvider {
  fun descriptorFor(project: RegisteredProject, modulePath: String): DaemonLaunchDescriptor

  companion object {
    /**
     * Returns a descriptor provider that reads `build/compose-previews/daemon-launch.json` for each
     * module from disk. The file is written by the user running `./gradlew
     * :<module>:composePreviewDaemonStart` — the supervisor surfaces a clear error if it's missing.
     * A future enhancement may invoke Gradle's Tooling API itself; for v0 we keep the seam clean
     * and let the user (or VS Code) drive the bootstrap.
     */
    fun readingFromDisk(): DescriptorProvider = DescriptorProvider { project, modulePath ->
      val moduleDir = gradlePathToFile(project.path, modulePath)
      val descriptorFile = File(moduleDir, "build/compose-previews/daemon-launch.json")
      check(descriptorFile.isFile) {
        "Missing daemon launch descriptor for $modulePath under ${project.path.absolutePath}. " +
          "Run `./gradlew $modulePath:composePreviewDaemonStart` first."
      }
      DaemonLaunchDescriptor.parse(descriptorFile.readText())
    }

    private fun gradlePathToFile(projectRoot: File, modulePath: String): File {
      // ":" → root, ":a:b" → projectRoot/a/b
      val trimmed = modulePath.trimStart(':')
      if (trimmed.isEmpty()) return projectRoot
      val rel = trimmed.replace(':', File.separatorChar)
      return File(projectRoot, rel)
    }
  }
}

/**
 * Trimmed parse of `build/compose-previews/daemon-launch.json`. Mirrors the field set written by
 * [`DaemonClasspathDescriptor`][ee.schimke.composeai.plugin.daemon.DaemonClasspathDescriptor] in
 * the gradle plugin; we re-declare the schema rather than depending on the plugin module so the MCP
 * module's runtime classpath stays free of the plugin's AGP/Gradle deps.
 */
@Serializable
data class DaemonLaunchDescriptor(
  val schemaVersion: Int,
  val modulePath: String,
  val variant: String,
  val enabled: Boolean,
  val mainClass: String,
  val javaLauncher: String? = null,
  val classpath: List<String>,
  val jvmArgs: List<String>,
  val systemProperties: Map<String, String>,
  val workingDirectory: String,
  val manifestPath: String,
) {
  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(jsonText: String): DaemonLaunchDescriptor =
      json.decodeFromString(serializer(), jsonText)
  }
}

/**
 * Pluggable spawn — production [SubprocessDaemonClientFactory] forks a JVM via [ProcessBuilder];
 * tests inject an in-memory factory that wires the [DaemonClient] to a fake daemon over piped
 * streams. The factory returns a [DaemonSpawn] that owns the underlying resource (subprocess or
 * coroutine).
 */
fun interface DaemonClientFactory {
  fun spawn(project: RegisteredProject, descriptor: DaemonLaunchDescriptor): DaemonSpawn
}

/**
 * Owns the resources behind a single live [DaemonClient]: the subprocess (in production) or the
 * fake daemon side of a piped pair (in tests). The supervisor calls [client] once after spawn to
 * wire the notification + close handlers.
 */
interface DaemonSpawn {
  val client: DaemonClient

  /**
   * Wires the supervisor's notification + close handlers onto the underlying [client]. Called
   * exactly once by [DaemonSupervisor.spawn] before any traffic flows. Implementations typically
   * delay creating the [client] until this call so the handlers are baked in from the first frame.
   */
  fun client(
    onNotification: (method: String, params: JsonObject?) -> Unit,
    onClose: () -> Unit,
  ): DaemonClient

  fun shutdown()
}

// -----------------------------------------------------------------------------
// Notification routing — keeps Subscriptions / WatchSets / classpathDirty handlers
// out of the core supervisor wiring.
// -----------------------------------------------------------------------------

/**
 * Demultiplexes daemon notifications by method name. Multiple handlers per method are supported;
 * each is called in registration order on the daemon's reader thread, so handlers must be cheap and
 * non-blocking.
 */
class NotificationRouter {
  private val handlers =
    ConcurrentHashMap<String, MutableList<(SupervisedDaemon, JsonObject?) -> Unit>>()
  private val closeHandlers = mutableListOf<(SupervisedDaemon) -> Unit>()

  fun on(method: String, handler: (SupervisedDaemon, JsonObject?) -> Unit) {
    val list = handlers.computeIfAbsent(method) { mutableListOf() }
    synchronized(list) { list.add(handler) }
  }

  fun onClose(handler: (SupervisedDaemon) -> Unit) {
    synchronized(closeHandlers) { closeHandlers.add(handler) }
  }

  internal fun dispatch(daemon: SupervisedDaemon, method: String, params: JsonObject?) {
    val list = handlers[method] ?: return
    synchronized(list) { list.toList() }.forEach { runCatching { it(daemon, params) } }
  }

  internal fun dispatchClose(daemon: SupervisedDaemon) {
    synchronized(closeHandlers) { closeHandlers.toList() }.forEach { runCatching { it(daemon) } }
  }

  /**
   * Convenience: extract `params.id` from a `renderFinished` / `renderStarted` envelope. Returns
   * null when missing so callers can treat malformed events as drops rather than throws.
   */
  fun previewIdOf(params: JsonObject?): String? = params?.get("id")?.jsonPrimitive?.contentOrNull

  /** Convenience: extract `params.pngPath` from a `renderFinished` envelope. */
  fun pngPathOf(params: JsonObject?): String? = params?.get("pngPath")?.jsonPrimitive?.contentOrNull

  /** Convenience: extract a renderer-specific string field from any envelope. */
  fun stringField(params: JsonObject?, name: String): String? =
    params?.get(name)?.jsonPrimitive?.contentOrNull

  /** Convenience: walk a `discoveryUpdated.added[]` / `discoveryUpdated.changed[]` array. */
  fun previewsArray(params: JsonObject?, key: String): List<JsonObject> =
    (params?.get(key) as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
      runCatching { it.jsonObject }.getOrNull()
    } ?: emptyList()
}
