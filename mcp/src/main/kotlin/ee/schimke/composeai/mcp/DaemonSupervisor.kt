package ee.schimke.composeai.mcp

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
  /**
   * Extra in-process replicas to spawn per (workspace, module) once the primary is up. The primary
   * is always spawned synchronously and seeds the catalog via `synthesiseInitialDiscovery`; the
   * replicas come up off-thread, share the (now-warm) Maven cache, and absorb render fan-out so
   * concurrent `renderNow` requests for different previews don't queue behind each other on a
   * single render thread. `replicasPerDaemon = 0` (the default) preserves the original
   * 1-daemon-per-module behaviour. Total daemons per module = `1 + replicasPerDaemon`.
   */
  private val replicasPerDaemon: Int = 0,
) {

  init {
    require(replicasPerDaemon >= 0) { "replicasPerDaemon must be >= 0, got $replicasPerDaemon" }
  }

  private val projects = ConcurrentHashMap<WorkspaceId, RegisteredProject>()

  /**
   * Off-thread spawner for **additional replicas** beyond the primary. Sized to the configured
   * replica count so all replicas of one module can come up in parallel; capped so several modules
   * spawning concurrently don't fork an unbounded number of JVMs.
   */
  private val replicaSpawnExecutor: ExecutorService =
    Executors.newFixedThreadPool(
      maxOf(1, replicasPerDaemon).coerceAtMost(REPLICA_SPAWN_POOL_CAP)
    ) { r ->
      Thread(r, "mcp-daemon-replica-spawn").apply { isDaemon = true }
    }

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
   * Forgets the [SupervisedDaemon] for [workspaceId] + [modulePath] and tears down any peer
   * replicas. Intended for the `classpathDirty` respawn flow: the dirty replica is already exiting
   * on its own, but with `replicasPerDaemon > 0` the peers are still alive on the same stale
   * classpath and must be killed explicitly. Shutdown of the dirty replica is a no-op (its wire is
   * already closing); peer shutdowns send `shutdown`/`exit` per protocol.
   *
   * The next [daemonFor] for the same coordinates spawns afresh against the (presumably refreshed)
   * descriptor. Returns `true` if a daemon entry was actually removed — the second of two racing
   * `classpathDirty` events from different replicas of the same group sees `false` and can skip the
   * respawn-counter bump.
   */
  fun forgetDaemon(workspaceId: WorkspaceId, modulePath: String): Boolean {
    val project = projects[workspaceId] ?: return false
    val removed = project.daemons.remove(modulePath) ?: return false
    runCatching { removed.shutdown() }
    return true
  }

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
    replicaSpawnExecutor.shutdown()
    runCatching { replicaSpawnExecutor.awaitTermination(2, TimeUnit.SECONDS) }
  }

  /** Returns the [NotificationRouter] so callers can register handlers. */
  fun router(): NotificationRouter = router

  // -------------------------------------------------------------------------
  // Internals
  // -------------------------------------------------------------------------

  private fun spawn(project: RegisteredProject, modulePath: String): SupervisedDaemon {
    val descriptor = descriptorProvider.descriptorFor(project, modulePath)
    val supervised = SupervisedDaemon(workspaceId = project.workspaceId, modulePath = modulePath)

    // Primary spawn is synchronous so the calling thread blocks on cold-start and the catalog is
    // seeded before any subsequent `daemonFor` returns. Subsequent replicas piggy-back on the
    // (now-warm) Maven cache — see `replicasPerDaemon` KDoc.
    spawnReplica(project, descriptor, supervised, isPrimary = true)

    // Fire off replica spawns in parallel. They share the warmed Maven cache and the JIT'd
    // classloader cache from the primary, so cold-start cost is dominated by the primary; the
    // replicas come up in roughly max(replica) instead of sum(replicas).
    repeat(replicasPerDaemon) { idx ->
      replicaSpawnExecutor.execute {
        runCatching { spawnReplica(project, descriptor, supervised, isPrimary = false) }
          .onFailure {
            System.err.println(
              "DaemonSupervisor: replica spawn ${idx + 1} failed for " +
                "${project.workspaceId}/$modulePath: ${it.message}"
            )
          }
      }
    }

    return supervised
  }

  /**
   * Spawns one client (primary or replica) and attaches it to [supervised]. The primary blocks on
   * `initialize` and seeds the catalog via [synthesiseInitialDiscovery]; replicas only need to
   * `initialize` (catalog is already populated by the primary's manifest read).
   */
  private fun spawnReplica(
    project: RegisteredProject,
    descriptor: DaemonLaunchDescriptor,
    supervised: SupervisedDaemon,
    isPrimary: Boolean,
  ) {
    val spawn = clientFactory.spawn(project, descriptor)
    // Wire the client BEFORE publishing the spawn to the replica list — otherwise a concurrent
    // `clientForRender` reader could pick this index and call `.client` before the spawn's
    // internal lazy client field is initialised (DaemonSpawn implementations construct the
    // DaemonClient inside `client(onNotification, onClose)`).
    spawn.client(
      onNotification = { method, params -> router.dispatch(supervised, method, params) },
      onClose = {
        // Only fire dispatchClose to handlers (which drop catalog state etc.) when the *last*
        // replica is gone — otherwise a single replica's wire close would tear down state shared
        // with its still-alive peers.
        if (supervised.removeReplica(spawn)) {
          router.dispatchClose(supervised)
        }
      },
    )
    supervised.addReplica(spawn)
    runCatching {
      val result =
        spawn.client.initialize(
          workspaceRoot = project.path.absolutePath,
          moduleId = descriptor.modulePath,
          moduleProjectDir = descriptor.workingDirectory,
        )
      if (isPrimary) {
        // The daemon only emits `discoveryUpdated` for *deltas* — the initial preview set comes
        // via `initialize.manifest.path` (a `previews.json` written by the gradle plugin's
        // `discoverPreviews` task). Synthesise an initial `discoveryUpdated` notification by
        // reading that file and dispatching it through the router as if it were a wire-level
        // event. Replicas of the same module would synthesise the same set, so we skip them.
        synthesiseInitialDiscovery(supervised, result.manifest.path)
      }
    }
  }

  companion object {
    /**
     * Cap on the replica-spawn thread pool. With many modules each requesting `replicasPerDaemon`
     * extra replicas, an unbounded pool would fork tens of JVMs at once and trash the host. Cold
     * Robolectric bootstraps are CPU + I/O heavy, so eight concurrent forks is the comfortable
     * upper bound on a typical dev machine.
     */
    private const val REPLICA_SPAWN_POOL_CAP: Int = 8
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

/**
 * A live daemon — owned by [DaemonSupervisor]. May front 1+N underlying replicas (see
 * [DaemonSupervisor.replicasPerDaemon]). All replicas serve the same (workspaceId, modulePath); the
 * supervisor uses the group to fan out invalidations and shard render fan-out.
 */
class SupervisedDaemon(val workspaceId: WorkspaceId, val modulePath: String) {

  /**
   * Replicas in spawn order. The primary is at index 0, populated by the synchronous spawn path;
   * extra replicas append as their async spawns finish. Iterations take a snapshot — the list is
   * copy-on-write so concurrent appends + reads are safe.
   */
  private val replicas: CopyOnWriteArrayList<DaemonSpawn> = CopyOnWriteArrayList()

  /**
   * The primary [DaemonClient]. Used for control-plane operations (`initialize`, `history*`,
   * `setVisible`/`setFocus` — though propagation fans out to every replica, see [allClients]).
   * Throws if no replica has been registered yet (only possible during the brief window before the
   * primary's synchronous spawn returns).
   */
  val client: DaemonClient
    get() {
      val list = replicas
      check(list.isNotEmpty()) { "SupervisedDaemon($workspaceId/$modulePath): no replicas" }
      return list[0].client
    }

  /** Snapshot of every active replica's client — for fan-out (e.g. `fileChanged`, `setVisible`). */
  fun allClients(): List<DaemonClient> = replicas.map { it.client }

  /**
   * Picks a replica's client for a render keyed on [previewId]. Hash-based so the same preview
   * lands on the same replica every time (cache locality + render dedup); different previews spread
   * across replicas so concurrent renders run in parallel. Falls back to the primary when no
   * replica is yet registered.
   */
  fun clientForRender(previewId: String): DaemonClient {
    val list = replicas
    if (list.isEmpty()) error("SupervisedDaemon($workspaceId/$modulePath): no replicas")
    if (list.size == 1) return list[0].client
    // Math.floorMod gives a non-negative index even for negative hashCodes.
    val idx = Math.floorMod(previewId.hashCode(), list.size)
    return list[idx].client
  }

  /** Number of currently-active replicas (primary + extras). */
  fun replicaCount(): Int = replicas.size

  internal fun addReplica(spawn: DaemonSpawn) {
    replicas.add(spawn)
  }

  /**
   * Removes [spawn] from the replica set. Returns `true` if this was the *last* replica — callers
   * use this to decide whether to fire group-level cleanup (e.g. dispatching `onClose` to handlers
   * that own per-(workspace, module) state).
   */
  internal fun removeReplica(spawn: DaemonSpawn): Boolean {
    replicas.remove(spawn)
    return replicas.isEmpty()
  }

  fun shutdown() {
    val snapshot = replicas.toList()
    replicas.clear()
    snapshot.forEach { runCatching { it.shutdown() } }
  }
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
