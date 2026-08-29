package ee.schimke.composeai.mcp

import ee.schimke.composeai.daemon.client.DaemonClient
import ee.schimke.composeai.daemon.client.DaemonClientFactory
import ee.schimke.composeai.daemon.client.DaemonSpawn
import ee.schimke.composeai.daemon.client.WorkspaceId
import ee.schimke.composeai.daemon.protocol.BackendKind
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath

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
   * Concurrent render slots per (workspace, module) beyond the first. SANDBOX-POOL.md — the
   * supervisor passes `composeai.daemon.sandboxCount = 1 + replicasPerDaemon` as a sysprop on the
   * launch descriptor; the daemon's
   * [`RobolectricHost`][ee.schimke.composeai.daemon.RobolectricHost] then hosts one sandbox itself
   * and spawns a worker JVM for each remaining slot (Robolectric's native runtime allows exactly
   * one sandbox per process — issue #3072), dispatching concurrent `renderNow` requests across
   * them.
   *
   * **Default 4** — the daemon comes up with 5 sandboxes (1 + 4) so a typical preview grid can
   * render in parallel without the user opting in. Each slot beyond the first is a worker JVM the
   * daemon spawns and owns, so the marginal cost is a whole JVM: turn the knob down (or to `0`,
   * which keeps a single sandbox, bit-identical with the pre-pool path) on memory-constrained
   * hosts.
   *
   * Wire-protocol-visible behaviour (initialize, renderNow, fileChanged fan-out) is unchanged from
   * the consumer's perspective — the supervisor still talks to exactly one daemon process per
   * (workspace, module), and that daemon fans renders out to its workers internally.
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
  /**
   * Extension ids the supervisor enables on every spawned daemon right after `initialize` succeeds.
   * Maps onto the daemon's `extensions/enable` JSON-RPC method (PROTOCOL.md § 3a). Daemons start
   * with everything inactive; the supervisor opts in to the contributions this MCP session needs.
   *
   * Defaults to empty so a baseline daemon is as lean as possible. Production entry points
   * ([DaemonMcpMain]) and embedding consumers populate this with the kinds their tools require. The
   * supervisor passes them through verbatim — unknown ids land in the daemon's `extensions/enable`
   * response under `unknown` and are logged but not retried.
   */
  private val defaultExtensions: List<String> = emptyList(),
  private val fileSystem: FileSystem = SystemFileSystem,
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
    val canonical = runCatching {
      absolutePath.canonicalFile
    }
      .getOrDefault(absolutePath.absoluteFile)
    val name =
      rootProjectName?.takeIf { it.isNotBlank() }
        ?: canonical.name.takeIf { it.isNotBlank() }
        ?: "workspace"
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
    // SANDBOX-POOL.md — inject `composeai.daemon.sandboxCount = 1 + replicasPerDaemon` into the
    // descriptor's systemProperties so the spawned daemon owns that many sandboxes (one in its own
    // JVM, the rest in worker JVMs it spawns). DaemonMain reads the sysprop and passes it on. We
    // merge into a copy rather than mutating the original — the descriptor object is cached by
    // `DescriptorProvider.readingFromDisk` and shared across `daemonFor` calls.
    val descriptor = baseDescriptor.withSandboxCount(1 + replicasPerDaemon)
    val supervised = SupervisedDaemon(workspaceId = project.workspaceId, modulePath = modulePath)

    // Single synchronous spawn — the calling thread blocks on cold-start and the catalog is seeded
    // before `daemonFor` returns. With sandboxCount > 1 the daemon's per-sandbox bootstrap is
    // sequenced internally (RobolectricHost.start), so the wall-clock here is roughly
    // (1 + replicasPerDaemon) × per-sandbox-boot.
    val spawn = clientFactory.spawn(project.workspaceId, descriptor)
    spawn.client(
      onNotification = { method, params ->
        router.dispatch(supervised, method, params)
        // Fan out to any RenderSession listeners registered via `supervised.session
        // .onNotification(...)`. Mirrors the router dispatch but reaches a different
        // subscriber pool (the public-API consumers, not the MCP-internal routing).
        supervised.notificationFanout.dispatch(method, params)
      },
      onClose = {
        if (supervised.detachSpawn(spawn)) {
          router.dispatchClose(supervised)
        }
      },
    )
    supervised.attachSpawn(spawn)
    // Capture the workspace root before initialize so the `session` view's
    // `RenderSession.workspaceRoot` returns a real absolute path even if a caller reaches the
    // session getter before the runCatching block below populates `initializeResult`.
    supervised.workspaceRootPath = project.path.absolutePath
    runCatching {
      val result =
        spawn.client.initialize(
          workspaceRoot = project.path.absolutePath,
          moduleId = descriptor.modulePath,
          moduleProjectDir = descriptor.workingDirectory,
          attachDataProducts = globalAttachDataProducts.takeIf { it.isNotEmpty() },
        )
      // Cache the full result so the public RenderSession view (`supervised.session`) can
      // expose it through `RenderSession.initializeResult`. Subsequent successful re-spawns
      // (classpathDirty respawn path) overwrite this with the fresh handshake's result.
      supervised.initializeResult = result
      // PROTOCOL.md § 3a — the daemon comes up with every extension inactive so
      // `initialize.capabilities.dataProducts` / `dataExtensions` / `previewExtensions` are
      // empty.
      // Opt the daemon into the configured `defaultExtensions` set; the response carries the
      // updated public capability lists which we cache below so the MCP catalogue surfaces them
      // without a follow-up `extensions/list` round-trip.
      val initialDataProducts: List<DataProductCapability>
      val initialDataExtensions: List<ee.schimke.composeai.daemon.protocol.DataExtensionDescriptor>
      if (defaultExtensions.isNotEmpty()) {
        val enableResult = spawn.client.extensionsEnable(defaultExtensions)
        if (enableResult.unknown.isNotEmpty()) {
          System.err.println(
            "daemon ${project.workspaceId}/${descriptor.modulePath}: extensions/enable " +
              "skipped unknown ids ${enableResult.unknown}"
          )
        }
        initialDataProducts = enableResult.dataProducts
        initialDataExtensions = enableResult.dataExtensions
      } else {
        initialDataProducts = result.capabilities.dataProducts
        initialDataExtensions = result.capabilities.dataExtensions
      }
      supervised.dataProductCapabilities = initialDataProducts
      supervised.dataExtensionDescriptors = initialDataExtensions
      // PROTOCOL.md § 3 — cache the daemon's advertised supportedOverrides + knownDevice ids so
      // `DaemonMcpServer.toolRenderPreview` can validate inbound `overrides` against what this
      // backend will actually apply (instead of silently no-op'ing fields the backend ignores)
      // and
      // reject typo'd `device` ids before they fall back to the default. Pre-feature daemons
      // advertise `[]` for both, in which case validation falls open — clients are exactly where
      // they were before the capability landed.
      supervised.supportedOverrides = result.capabilities.supportedOverrides.toSet()
      supervised.knownDeviceIds = result.capabilities.knownDevices.map { it.id }.toSet()
      supervised.backendKind = result.capabilities.backend
      // RECORDING.md § "encoded formats" — same pattern. Empty list pre-feature; validation falls
      // open and `record_preview` calls round-trip without the diagnostic.
      supervised.recordingFormats = result.capabilities.recordingFormats.toSet()
      // Cache the manifest path so the MCP server's background poller can detect a Gradle
      // `composePreviewDiscover` re-run between renders and re-load the manifest into the catalog
      // (issue #834). Blank for backends that don't ship a `previews.json`.
      supervised.manifestPath = result.manifest.path.takeIf { it.isNotBlank() }
      // The daemon only emits `discoveryUpdated` for *deltas* — the initial preview set comes
      // via `initialize.manifest.path` (a `previews.json` written by the gradle plugin's
      // `composePreviewDiscover` task). Synthesise an initial `discoveryUpdated` notification by
      // reading that file and dispatching it through the router as if it were a wire-level
      // event.
      synthesiseInitialDiscovery(supervised, result.manifest.path)
      supervised.initialDiscoveryComplete = true
    }
      .onFailure { e ->
        System.err.println(
          "daemon initialize failed for ${project.workspaceId}/${descriptor.modulePath}: ${e.message}"
        )
      }

    return supervised
  }

  private fun synthesiseInitialDiscovery(daemon: SupervisedDaemon, manifestPath: String) {
    if (manifestPath.isBlank()) return
    val file = File(manifestPath)
    if (!file.isFile) return
    val previews =
      runCatching {
        val text = fileSystem.read(file.path.toPath()) { readUtf8() }
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
     * concurrently without the user opting in: 5 sandboxes per daemon (1 primary + 4 replicas). The
     * cost is one JVM per sandbox beyond the first (#3072 moved the pool out of process — a second
     * Robolectric sandbox cannot share a JVM); see SANDBOX-POOL.md. Override via the MCP CLI's
     * `--replicas-per-daemon N` flag or the `composeai.mcp.replicasPerDaemon` system property.
     */
    const val DEFAULT_REPLICAS_PER_DAEMON: Int = 4
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
 * A live daemon — owned by [DaemonSupervisor]. SANDBOX-POOL.md: one *supervised* daemon process per
 * (workspaceId, modulePath); concurrent render capacity comes from that daemon's own sandbox pool,
 * configured via `composeai.daemon.sandboxCount` on the launch descriptor (the supervisor passes
 * `1 + replicasPerDaemon`) and realised as one in-daemon sandbox plus N worker JVMs.
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
   * True once the supervisor has completed the initialize round-trip and attempted to seed the MCP
   * catalog from the daemon's initial manifest. A daemon can be discovery-complete with zero
   * previews; clients should pair this flag with the MCP catalog's preview count rather than
   * treating an empty resource list as "still warming".
   */
  @Volatile
  var initialDiscoveryComplete: Boolean = false
    internal set

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

  @Volatile
  var dataExtensionDescriptors: List<ee.schimke.composeai.daemon.protocol.DataExtensionDescriptor> =
    emptyList()
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
   * Path to `previews.json` (the per-module manifest written by the gradle plugin's
   * `composePreviewDiscover` task). Captured at `initialize` time from the daemon's
   * `InitializeResult.manifest.path`. The `DaemonMcpServer`'s background poller stats this file
   * each cycle so a `composePreviewDiscover` re-run between renders publishes new preview ids into
   * the MCP catalog without an MCP server restart — closes the "manifest doesn't auto-refresh" gap
   * reported in issue #834. Null/blank when the daemon doesn't advertise a manifest path (older
   * daemons / non-Gradle backends).
   */
  @Volatile
  var manifestPath: String? = null
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
   * Cached `initialize` round-trip result — backing for the [session] view's
   * [RenderSession.initializeResult]. Populated by [DaemonSupervisor.spawn] right after the
   * handshake; cleared by [detachSpawn]. `@Volatile` for the same reasons [spawn] is — read on the
   * caller thread, written on the spawn coroutine.
   */
  @Volatile
  internal var initializeResult: ee.schimke.composeai.daemon.protocol.InitializeResult? = null

  /**
   * Canonical workspace-root path the supervised daemon was spawned against — backing for the
   * [session] view's [ee.schimke.composeai.render.session.RenderSession.workspaceRoot]. Captured
   * from the [RegisteredProject.path] at spawn time so the public API returns a real absolute path
   * instead of a placeholder. Cleared by [detachSpawn] when the spawn tears down.
   */
  @Volatile internal var workspaceRootPath: String? = null

  /**
   * Notification fan-out installed by [DaemonSupervisor.spawn]. The supervisor's existing
   * `onNotification` callback dispatches both into its own [NotificationRouter] and into this
   * fanout; [session] consumers can register listeners via
   * [ee.schimke.composeai.render.session.RenderSession.onNotification] without disturbing the
   * router's own subscriber set.
   */
  internal val notificationFanout: NotificationFanout = NotificationFanout()

  /**
   * Public [RenderSession] view of this supervised daemon. Surface-only migration of `:mcp` onto
   * the published render-session library — third-party consumers that compile against
   * `:render-session-api` can drive the daemon through the same contract `:render-session-
   * subprocess` and `:render-session-embedded-desktop` expose, without seeing the internal
   * [DaemonClient].
   *
   * Lifecycle is owned by the supervisor: `close()` on the returned session is a no-op (other
   * callers may be sharing the same client). The supervisor's [shutdown] / [detachSpawn] is the
   * single seam that tears down the daemon JVM.
   *
   * Each access returns a fresh view object — the underlying state ([client], [initializeResult],
   * [notificationFanout]) is shared. Throws if the spawn / initialize handshake hasn't completed
   * yet (same precondition as [client]).
   */
  val session: ee.schimke.composeai.render.session.RenderSession
    get() {
      val s = spawn
      check(s != null) { "SupervisedDaemon($workspaceId/$modulePath): no spawn attached yet" }
      val init =
        initializeResult
          ?: error(
            "SupervisedDaemon($workspaceId/$modulePath): initialize handshake hasn't completed"
          )
      val root =
        workspaceRootPath
          ?: error(
            "SupervisedDaemon($workspaceId/$modulePath): workspaceRootPath not captured at spawn"
          )
      return DaemonClientRenderSession(
        workspaceRoot = root,
        modulePath = modulePath,
        initializeResult = init,
        client = s.client,
        notificationFanout = notificationFanout,
      )
    }

  /**
   * Snapshot of every active client — for fan-out APIs (e.g. `fileChanged`, `setVisible`). Always a
   * singleton list — one supervised daemon per module; kept as a list for source-compatibility with
   * callers that iterate it (they keep working unchanged).
   */
  fun allClients(): List<DaemonClient> = spawn?.let { listOf(it.client) } ?: emptyList()

  /**
   * Returns the client for a render keyed on [previewId]. Always the single client; the daemon-side
   * `RobolectricHost.submit` dispatches across its sandbox slots (in-process plus workers). The
   * [previewId] argument is informational — kept on the API so a future affinity-aware wire change
   * can use it without breaking callers.
   */
  fun clientForRender(@Suppress("UNUSED_PARAMETER") previewId: String): DaemonClient = client

  /**
   * Always 1 — one *supervised* subprocess per daemon. Concurrent render capacity is `1 +
   * replicasPerDaemon` and is realised by the daemon's own sandbox pool (whose worker JVMs the
   * supervisor neither spawns nor counts). Kept for source-compatibility with callers that asserted
   * "primary plus N replicas" — those assertions are now wrong, but the method itself doesn't lie.
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
    this.initializeResult = null
    this.workspaceRootPath = null
    notificationFanout.clear()
    return true
  }

  fun shutdown() {
    val s = spawn ?: return
    spawn = null
    initializeResult = null
    workspaceRootPath = null
    notificationFanout.clear()
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
    fun readingFromDisk(fileSystem: FileSystem = SystemFileSystem): DescriptorProvider {
      // Per-project-root index of modulePath -> descriptor file, populated on the first miss of the
      // layout fast-path below. Only *positive* results are cached: a lookup for a module absent
      // from the cached index rescans (a descriptor may have been written since — exactly what the
      // "run composePreviewDaemonStart first" error tells the user to do), so a long-lived server
      // picks up a newly-generated descriptor without a restart. The fast path spares normal
      // layouts the scan entirely, so the rescan-on-miss cost only lands on the error path.
      val scannedIndexByRoot = ConcurrentHashMap<String, Map<String, File>>()
      return DescriptorProvider { project, modulePath ->
        // Fast path: the Gradle path mirrors the directory layout (`:a:b` → <root>/a/b).
        val guessed =
          File(
            gradlePathToFile(project.path, modulePath),
            "build/compose-previews/daemon-launch.json",
          )
        val descriptorFile =
          if (guessed.isFile) {
            guessed
          } else {
            // Fallback: a project can remap projectDir in settings.gradle.kts (e.g. `:featureTasks`
            // → shared/features/tasks), so the Gradle path is not the on-disk layout. Locate the
            // descriptor by the modulePath recorded inside each daemon-launch.json. Reuse the
            // cached
            // index only if it already resolves this module; otherwise rebuild it (a descriptor may
            // have appeared) and cache the fresh scan before giving up.
            val root = project.path.absolutePath
            scannedIndexByRoot[root]?.get(modulePath)
              ?: run {
                val rescanned = indexDescriptorsByModulePath(project.path, fileSystem)
                scannedIndexByRoot[root] = rescanned
                rescanned[modulePath]
                  ?: error(
                    "Missing daemon launch descriptor for $modulePath under " +
                      "${project.path.absolutePath}. " +
                      "Run `./gradlew $modulePath:composePreviewDaemonStart` first."
                  )
              }
          }
        DaemonLaunchDescriptor.parse(fileSystem.read(descriptorFile.path.toPath()) { readUtf8() })
      }
    }

    private fun gradlePathToFile(projectRoot: File, modulePath: String): File {
      // ":" → root, ":a:b" → projectRoot/a/b
      val trimmed = modulePath.trimStart(':')
      if (trimmed.isEmpty()) return projectRoot
      val rel = trimmed.replace(':', File.separatorChar)
      return File(projectRoot, rel)
    }

    /**
     * Scans [projectRoot] for `build/compose-previews/daemon-launch.json` descriptors and indexes
     * each by the `modulePath` it records. Handles projects that remap `projectDir` in
     * settings.gradle.kts, where the Gradle module path is not the directory layout. Prunes VCS,
     * Gradle/IDE metadata, `node_modules`, `src`, and non-`compose-previews` `build/` subtrees so
     * the walk stays cheap.
     */
    private fun indexDescriptorsByModulePath(
      projectRoot: File,
      fileSystem: FileSystem,
    ): Map<String, File> {
      val index = HashMap<String, File>()
      projectRoot
        .walkTopDown()
        .onEnter { dir ->
          when {
            dir.name in setOf(".git", ".gradle", ".idea", "node_modules", "src") -> false
            dir.parentFile?.name == "build" && dir.name != "compose-previews" -> false
            else -> true
          }
        }
        .filter {
          it.isFile && it.name == "daemon-launch.json" && it.parentFile?.name == "compose-previews"
        }
        .forEach { file ->
          val recorded = runCatching {
            DaemonLaunchDescriptor.parse(fileSystem.read(file.path.toPath()) { readUtf8() })
              .modulePath
          }
            .getOrNull()
          if (recorded != null) index.putIfAbsent(recorded, file)
        }
      return index
    }
  }
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
