package ee.schimke.composeai.mcp

import ee.schimke.composeai.daemon.protocol.BackendKind
import ee.schimke.composeai.daemon.protocol.DataProductCapability
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
  /**
   * Concurrent render slots per (workspace, module) beyond the first. SANDBOX-POOL.md Layer 3 — the
   * supervisor passes `composeai.daemon.sandboxCount = 1 + replicasPerDaemon` as a sysprop on the
   * launch descriptor; the daemon's
   * [`RobolectricHost`][ee.schimke.composeai.daemon.RobolectricHost] boots that many in-JVM
   * Robolectric sandboxes and dispatches concurrent `renderNow` requests across them.
   *
   * **Default 3** — the daemon comes up with **4** in-JVM sandboxes (1 + 3) so a typical preview
   * grid can render in parallel without the user opting in. This is cheap thanks to Layer 3: extra
   * sandboxes share the JVM baseline + native heap, so the marginal cost per slot is the sandbox
   * classloader's instrumented bytecode (~ a few hundred MB each at peak). `0` opts out and keeps a
   * single sandbox — bit-identical with the pre-pool path on disk.
   *
   * Pre-Layer-3 this knob spawned N additional **JVM subprocesses**; that wasted ~2 GB per replica
   * on shared per-JVM cost (native heap, JVM baseline, instrumented framework). Layer 3 collapses
   * those into a single daemon JVM with N sandbox classloaders. Wire-protocol-visible behaviour
   * (initialize, renderNow, fileChanged fan-out) is unchanged from the consumer's perspective.
   */
  private val replicasPerDaemon: Int = DEFAULT_REPLICAS_PER_DAEMON,
  /**
   * D1 — kinds the supervisor passes through `initialize.options.attachDataProducts` to every
   * spawned daemon. Configures "always-on" data products (e.g. `a11y/atf` for ambient diagnostic
   * squigglies). Empty list (the default) keeps the wire absent — no global attach.
   *
   * No production entry point sets this today: [DaemonMcpMain] used to expose a
   * `--attach-data-product KIND` CLI flag, but it was deemed speculative (the design doc reserves
   * `attachDataProducts` for "always-on everywhere" cases that no real operator deployment needs
   * yet) and dropped. The parameter stays so a future agent-driven negotiation tool — or embedding
   * consumer that wires a non-default config — can populate it without re-plumbing the supervisor →
   * `initialize` path. Tests use it directly (see `DaemonMcpServerTest`).
   */
  private val globalAttachDataProducts: List<String> = emptyList(),
) {

  init {
    require(replicasPerDaemon >= 0) { "replicasPerDaemon must be >= 0, got $replicasPerDaemon" }
  }

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
  }

  /** Returns the [NotificationRouter] so callers can register handlers. */
  fun router(): NotificationRouter = router

  // -------------------------------------------------------------------------
  // Internals
  // -------------------------------------------------------------------------

  private fun spawn(project: RegisteredProject, modulePath: String): SupervisedDaemon {
    val baseDescriptor = descriptorProvider.descriptorFor(project, modulePath)
    // SANDBOX-POOL.md Layer 3 — inject `composeai.daemon.sandboxCount = 1 + replicasPerDaemon`
    // into the descriptor's systemProperties so the spawned daemon JVM boots that many in-JVM
    // sandboxes. The daemon's DaemonMain reads this sysprop and passes it to RobolectricHost. We
    // merge into a copy rather than mutating the original — the descriptor object is cached by
    // `DescriptorProvider.readingFromDisk` and shared across `daemonFor` calls.
    val descriptor = baseDescriptor.withSandboxCount(1 + replicasPerDaemon)
    val supervised = SupervisedDaemon(workspaceId = project.workspaceId, modulePath = modulePath)

    // Single synchronous spawn — the calling thread blocks on cold-start and the catalog is seeded
    // before `daemonFor` returns. With sandboxCount > 1 the daemon's per-sandbox bootstrap is
    // sequenced internally (RobolectricHost.start), so the wall-clock here is roughly
    // (1 + replicasPerDaemon) × per-sandbox-boot.
    val spawn = clientFactory.spawn(project, descriptor)
    spawn.client(
      onNotification = { method, params -> router.dispatch(supervised, method, params) },
      onClose = {
        if (supervised.detachSpawn(spawn)) {
          router.dispatchClose(supervised)
        }
      },
    )
    supervised.attachSpawn(spawn)
    runCatching {
      val result =
        spawn.client.initialize(
          workspaceRoot = project.path.absolutePath,
          moduleId = descriptor.modulePath,
          moduleProjectDir = descriptor.workingDirectory,
          attachDataProducts = globalAttachDataProducts.takeIf { it.isNotEmpty() },
        )
      // D1 — surface the daemon's advertised data-product kinds so the MCP server's
      // `list_data_products` tool can answer without a wire round-trip. Empty list on pre-D2
      // daemons; the field is also `emptyList()` by default in [ServerCapabilities] so absent
      // and `[]` collapse the same way client-side.
      supervised.dataProductCapabilities = result.capabilities.dataProducts
      // PROTOCOL.md § 3 — cache the daemon's advertised supportedOverrides + knownDevice ids so
      // `DaemonMcpServer.toolRenderPreview` can validate inbound `overrides` against what this
      // backend will actually apply (instead of silently no-op'ing fields the backend ignores) and
      // reject typo'd `device` ids before they fall back to the default. Pre-feature daemons
      // advertise `[]` for both, in which case validation falls open — clients are exactly where
      // they were before the capability landed.
      supervised.supportedOverrides = result.capabilities.supportedOverrides.toSet()
      supervised.knownDeviceIds = result.capabilities.knownDevices.map { it.id }.toSet()
      supervised.backendKind = result.capabilities.backend
      // RECORDING.md § "encoded formats" — same pattern. Empty list pre-feature; validation falls
      // open and `record_preview` calls round-trip without the diagnostic.
      supervised.recordingFormats = result.capabilities.recordingFormats.toSet()
      // The daemon only emits `discoveryUpdated` for *deltas* — the initial preview set comes
      // via `initialize.manifest.path` (a `previews.json` written by the gradle plugin's
      // `discoverPreviews` task). Synthesise an initial `discoveryUpdated` notification by
      // reading that file and dispatching it through the router as if it were a wire-level
      // event.
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

  companion object {
    /**
     * Out-of-the-box value for [replicasPerDaemon]. Picked so a typical preview grid renders
     * concurrently without the user opting in: 4 sandboxes per daemon (1 primary + 3 replicas). The
     * marginal cost is per-sandbox instrumented bytecode in one shared JVM, not a whole extra JVM
     * each — see SANDBOX-POOL.md Layer 3 for the memory math. Override via the MCP CLI's
     * `--replicas-per-daemon N` flag or the `composeai.mcp.replicasPerDaemon` system property.
     */
    const val DEFAULT_REPLICAS_PER_DAEMON: Int = 3
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
 * A live daemon — owned by [DaemonSupervisor]. SANDBOX-POOL.md Layer 3: one daemon JVM per
 * (workspaceId, modulePath); concurrent render capacity comes from in-JVM sandbox pooling
 * configured via `composeai.daemon.sandboxCount` on the launch descriptor (the supervisor passes
 * `1 + replicasPerDaemon`).
 *
 * Pre-Layer-3 this class fronted N+1 separate JVM subprocesses; the public surface ([client],
 * [allClients], [clientForRender]) survives that change because the daemon-side slot dispatch
 * handles render affinity internally.
 */
class SupervisedDaemon(val workspaceId: WorkspaceId, val modulePath: String) {

  /**
   * The single [DaemonSpawn] backing this supervised daemon. `null` between construction and
   * [attachSpawn]; set once and cleared by [detachSpawn] / [shutdown]. `@Volatile` because the
   * onNotification / onClose callbacks fire on the spawn's reader thread and the supervisor's
   * caller thread reads this through [client] / [allClients] without external synchronisation.
   */
  @Volatile private var spawn: DaemonSpawn? = null

  /**
   * D1 — kinds the daemon advertised via `initialize.capabilities.dataProducts`. Populated by
   * [DaemonSupervisor.spawn] right after the initialize round-trip, before [attachSpawn] returns to
   * the caller. Empty list pre-D2 (no producers wired) — matches the daemon's default. Read by
   * `DaemonMcpServer.toolListDataProducts` to answer without a wire round-trip.
   */
  @Volatile
  var dataProductCapabilities: List<DataProductCapability> = emptyList()
    internal set

  /**
   * PROTOCOL.md § 3 — `PreviewOverrides` field names this daemon's host actually applies (see
   * `RenderHost.supportedOverrides`). Populated by [DaemonSupervisor.spawn] right after the
   * initialize round-trip. Read by `DaemonMcpServer.toolRenderPreview` to reject inbound
   * `overrides` fields the backend would silently ignore. Empty set on pre-feature daemons —
   * validation falls open and the request goes through unchanged (no behaviour change for old
   * daemons, the caller just doesn't get the new diagnostic).
   */
  @Volatile
  var supportedOverrides: Set<String> = emptySet()
    internal set

  /**
   * PROTOCOL.md § 3 — `device` ids the daemon's catalog recognises (see
   * `ServerCapabilities.knownDevices`). Populated by [DaemonSupervisor.spawn] right after the
   * initialize round-trip. Read by `DaemonMcpServer.toolRenderPreview` to reject typo'd `device`
   * overrides before they silently fall back to the default. The free-form `spec:width=…` grammar
   * is not enumerable and not stored here — the validator passes those through.
   */
  @Volatile
  var knownDeviceIds: Set<String> = emptySet()
    internal set

  /**
   * PROTOCOL.md § 3 — renderer backend advertised by the daemon. Populated from
   * `InitializeResult.capabilities.backend` during [DaemonSupervisor.spawn], alongside the other
   * capability-derived MCP validation inputs.
   */
  @Volatile
  var backendKind: BackendKind? = null
    internal set

  /**
   * RECORDING.md § "encoded formats" — wire format spellings the daemon's host can produce
   * (`"apng"`, `"mp4"`, `"webm"`). Populated by [DaemonSupervisor.spawn] right after the initialize
   * round-trip. Read by `DaemonMcpServer.toolRecordPreview` to reject formats the daemon doesn't
   * advertise before `record_preview` round-trips a request that would only fail. Empty set on
   * pre-feature daemons — validation falls open (assume any format might work; caller sees the
   * underlying error if it doesn't), matching the same pattern `supportedOverrides` uses.
   */
  @Volatile
  var recordingFormats: Set<String> = emptySet()
    internal set

  /**
   * The single [DaemonClient]. Used for everything — control-plane operations (`initialize`,
   * `history*`), render dispatch, and fan-out broadcasts. Throws if [attachSpawn] hasn't run yet
   * (only possible during the brief window before the synchronous spawn returns).
   */
  val client: DaemonClient
    get() {
      val s = spawn
      check(s != null) { "SupervisedDaemon($workspaceId/$modulePath): no spawn attached yet" }
      return s.client
    }

  /**
   * Snapshot of every active client — for fan-out APIs (e.g. `fileChanged`, `setVisible`). Always a
   * singleton list under Layer 3; kept as a list for source-compatibility with callers that iterate
   * it (they keep working unchanged).
   */
  fun allClients(): List<DaemonClient> = spawn?.let { listOf(it.client) } ?: emptyList()

  /**
   * Returns the client for a render keyed on [previewId]. Layer 3: always the single client; the
   * daemon-side `RobolectricHost.submit` dispatches across in-JVM sandbox slots internally. The
   * [previewId] argument is informational — kept on the API so a future affinity-aware wire change
   * can use it without breaking callers.
   */
  fun clientForRender(@Suppress("UNUSED_PARAMETER") previewId: String): DaemonClient = client

  /**
   * Always 1 under Layer 3 (one JVM subprocess per supervised daemon). Concurrent render capacity
   * is `1 + replicasPerDaemon` and is realised inside the daemon JVM's sandbox pool, not at the
   * subprocess level. Kept for source-compatibility with callers that previously asserted "primary
   * plus N replicas" — those assertions are now wrong, but the method itself doesn't lie.
   */
  fun replicaCount(): Int = if (spawn != null) 1 else 0

  internal fun attachSpawn(spawn: DaemonSpawn) {
    check(this.spawn == null) {
      "SupervisedDaemon($workspaceId/$modulePath): spawn already attached"
    }
    this.spawn = spawn
  }

  /**
   * Detaches [s] if it's the current spawn. Returns `true` if a spawn was actually removed —
   * callers use this to decide whether to fire group-level cleanup (e.g. dispatching `onClose` to
   * handlers that own per-(workspace, module) state).
   */
  internal fun detachSpawn(s: DaemonSpawn): Boolean {
    if (this.spawn !== s) return false
    this.spawn = null
    return true
  }

  fun shutdown() {
    val s = spawn ?: return
    spawn = null
    runCatching { s.shutdown() }
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

  /**
   * SANDBOX-POOL.md Layer 3 — returns a copy with `composeai.daemon.sandboxCount` merged into
   * [systemProperties]. The supervisor calls this on the descriptor read from disk before passing
   * it to [DaemonClientFactory.spawn] so the daemon JVM picks up the right pool size at boot.
   *
   * Idempotent at [count] = 1 (the daemon's default; the sysprop is omitted to keep the disk
   * descriptor trivially diffable across replicas-per-daemon settings of 0).
   */
  fun withSandboxCount(count: Int): DaemonLaunchDescriptor {
    require(count >= 1) { "sandboxCount must be >= 1, got $count" }
    if (count == 1) return this
    val merged = systemProperties.toMutableMap()
    merged[SANDBOX_COUNT_PROP] = count.toString()
    return copy(systemProperties = merged)
  }

  companion object {
    /**
     * Sysprop key the daemon reads to configure
     * [`RobolectricHost.sandboxCount`][ee.schimke.composeai.daemon.RobolectricHost.sandboxCount].
     * Mirrored on the daemon side as a private const in `DaemonMain.kt`; both sides MUST agree.
     */
    const val SANDBOX_COUNT_PROP: String = "composeai.daemon.sandboxCount"

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
