package ee.schimke.composeai.cli.serve

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import okio.FileSystem
import okio.Path

/**
 * Stage-1 orchestrator for the playground (`docs/design/PLAYGROUND.md` §2). Turns a
 * [PlaygroundRunRequest] into a [PlaygroundRunResponse]: stage the snippet to a temp dir, compile
 * it against the mode's catalog classpath, and — on a clean compile — mint an expiring preview
 * token that Stage 2 redeems into a live session. Compile errors return diagnostics and **no**
 * token.
 *
 * **Multi-file snippets** are a plain consequence of the staging step: every file in the request is
 * written into the same source dir and handed to **one** compile, so a snippet can split types
 * across files and reference them across file boundaries. Which `@Preview` then drives the render
 * is decided here rather than by the discoverer — sorted by id, so a snippet with several previews
 * renders the same one every time (see [compileAndMint]).
 *
 * [PlaygroundMode.REMOTE_COMPOSE] is the exception to the token model: instead of a live session it
 * captures the snippet's `.rc` document and publishes it as an expiring `/d/<id>` permalink
 * ([remoteComposeResult]), returning a [PlaygroundRunResponse.documentUrl] and no token — the
 * document plays client-side, so the server keeps no daemon for it (PLAYGROUND.md §3).
 *
 * Every collaborator that touches the daemon, the compiler, or the catalog is an **injected seam**,
 * so the orchestration (staging, gating, cleanup, token minting, response shaping) is unit-testable
 * without a real BTA classloader or a running daemon — the same split
 * `DefaultBtaCompileService.forSession` uses. The route handler (a follow-up) constructs this with
 * the real backends.
 *
 * The single knob with real design weight is [catalogClasspath]. Its production implementation is a
 * thin adapter over the **liveBundle** resolution the serve host already runs
 * ([ServeBundleDaemon.materialize]): the catalog's packed `.png`/zip bundle is extracted to its
 * `classes/app.jar` and its `manifest.classpath` Maven coordinates resolved to jars, yielding the
 * `userClassPath` the live daemon runs on. A snippet compiled against that classpath can `import`
 * both the resolved library (e.g. `androidx.compose.material3.*`, complete because it comes from
 * the unminimized library jar) and whatever of the catalog's own composables survived bundle
 * minimization. `compose-m3` is the clean first catalog precisely because its component surface
 * *is* the resolved library jar. See `docs/design/PLAYGROUND.md` §8.
 */
class PlaygroundCompileService(
  /**
   * Resolves the compile+render classpath for a mode against an optionally **named catalog**; null
   * ⇒ that pair isn't available here. A null catalog means the host's pinned `--playground-bundle`
   * default for the mode; a non-null one is the runtime selector's choice among the served catalogs
   * ([PlaygroundCatalogTargets]).
   */
  private val catalogClasspath: (PlaygroundMode, String?) -> Classpath?,
  private val compiler: Compiler,
  private val discoverer: PreviewDiscoverer,
  private val tokenStore: PlaygroundTokenStore,
  /**
   * Mints a fresh, empty temp work dir per run — the token store deletes it when the token drops.
   */
  private val newWorkDir: () -> Path,
  private val fileSystem: FileSystem = FileSystem.SYSTEM,
  /** Optional first-frame render; returns PNG bytes or null. Defaults to no image (wired later). */
  private val renderFirstFrame: (PlaygroundTokenStore.PlaygroundSnippet) -> ByteArray? = { null },
  /**
   * [PlaygroundMode.REMOTE_COMPOSE] capture: run the compiled snippet's `@Preview` under the
   * RC-capable render and return the serialized `.rc` document bytes, or null when the snippet
   * emitted none (a non-RC `@Preview`) or no capture engine is wired here. Like [renderFirstFrame],
   * defaults to no capture — the production Robolectric capture subprocess is wired later.
   */
  private val captureRemoteDocument: (PlaygroundTokenStore.PlaygroundSnippet) -> ByteArray? = {
    null
  },
  /**
   * Publishes captured `.rc` bytes to a document store and returns the `/d/<id>` permalink, or null
   * when no store is available (or it refused the bytes). The `Boolean` is the [isSecurityChecked]
   * audit marker forwarded from [run], matching [ServeDocStore.add]. Defaults to no publisher.
   */
  private val publishRemoteDocument: (String, ByteArray, Boolean) -> String? = { _, _, _ -> null },
  /**
   * The served catalogs a request may name in [PlaygroundRunRequest.catalog]. Read fresh per call —
   * catalogs load in the background after the lane is wired.
   *
   * **Null and "returns empty" are different states**, which is why this is nullable rather than
   * defaulting to `{ emptyList() }`: null means this host pins its bundles and offers no runtime
   * choice at all (the pre-selector behaviour), while a non-null seam returning nothing means
   * `--playground` is on and no catalog has loaded *yet*. The editor renders a selector for the
   * second and not the first, so collapsing them would leave a host that started before its
   * catalogs permanently without the control it was configured to have.
   */
  private val catalogTargets: (() -> List<PlaygroundCatalogTarget>)? = null,
  /**
   * The served-catalog system id a **pinned** mode compiles against, when its `--playground-bundle`
   * named one (`--playground-bundle compose-m3`) rather than a local file. Null for a local path,
   * an unconfigured mode, or a host that pins nothing.
   *
   * Exists so [compilesCatalog] can answer for the pinned entry too. The selector reports a pinned
   * default under the id `""` ([catalogChoices]) — it deliberately has no system id on the wire,
   * because a request names a mode rather than the pin — but "does this host compile `compose-m3`"
   * is exactly the question the browsing surfaces have to answer before offering a handoff, and on
   * a pin-only host the answer is yes for precisely this system and no for every other one.
   */
  private val pinnedCatalogSystem: (PlaygroundMode) -> String? = { null },
  /** Explicitly opt in the one-host-wide authenticated stateful editing lease. */
  val editLeasesEnabled: Boolean = false,
  private val editLeaseTtlMillis: Long = DEFAULT_EDIT_LEASE_TTL_MILLIS,
  private val nowMillis: () -> Long = System::currentTimeMillis,
  /** Schedules idle-lease reclamation; injectable so expiry behavior is deterministic in tests. */
  private val scheduleEditLeaseExpiry: (Long, () -> Unit) -> (() -> Unit) = { delayMillis, task ->
    val future = EDIT_LEASE_EXPIRY_EXECUTOR.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
    val cancel: () -> Unit = { future.cancel(false) }
    cancel
  },
) {

  private val editLock = Any()
  private var editLease: EditLease? = null
  private var cancelEditLeaseExpiry: (() -> Unit)? = null
  private var editLeaseAcquisitions = 0L
  private var editCompileAttempts = 0L
  private var editIncrementalCompiles = 0L
  private var editFullFallbacks = 0L
  private var editLastRevision: Long? = null
  private var editLastCompileMillis: Long? = null
  @Volatile
  private var editHealthSnapshot =
    PlaygroundHealth.Editing(
      enabled = editLeasesEnabled,
      active = false,
      acquisitions = 0,
      compileAttempts = 0,
      incrementalCompiles = 0,
      fullFallbacks = 0,
    )

  private data class EditLease(
    val id: String,
    val owner: String,
    val workDir: Path,
    var expiresAt: Long,
    var lastRevision: Long = 0,
    var config: Pair<PlaygroundMode, String?>? = null,
    var files: Map<String, String> = emptyMap(),
    var incrementalReady: Boolean = false,
    val holders: MutableSet<String> = mutableSetOf(),
  )

  /**
   * True when `--playground` put a runtime catalog selector on this host, whether or not any
   * catalog has loaded into it yet. Drives whether the editor renders the Catalog control at all.
   */
  val catalogSelectorEnabled: Boolean
    get() = catalogTargets != null

  /**
   * The modes this host can serve **on its pinned default** — the ones whose [catalogClasspath]
   * resolved to a real classpath with no catalog named. Drives the editor's mode selector for the
   * default entry so it never offers a mode that would immediately answer "mode … is not available"
   * (e.g. an Android-only host must not default to CMP). A host started with `--playground` and no
   * pinned bundle has none, and the editor's selector then starts on a served catalog instead.
   *
   * Computed per read, not captured once: a mode configured as a served catalog id
   * (`--playground-bundle compose-m3`, issue #3212) resolves on first use, because the catalog it
   * names is fetched in the background *after* the playground lane is wired. Reading this at
   * construction time would find every such mode unavailable. [catalogClasspath] memoizes, so a
   * read after the first resolve is a field access.
   */
  val availableModes: List<PlaygroundMode>
    get() = PlaygroundMode.entries.filter { catalogClasspath(it, null) != null }

  /**
   * What the editor's catalog selector offers: the host's pinned default (when it has one) followed
   * by every served catalog that can back a compile here.
   *
   * The **pinned** entry costs what [availableModes] costs — deciding whether a pinned bundle can
   * serve a mode means resolving it, and the first caller pays the unpack. That is unchanged from
   * how `GET /playground` has always rendered its mode list. The **served-catalog** entries are
   * free: each reports its memoized resolution state rather than forcing a resolve, so listing
   * twenty catalogs does not unpack twenty bundles.
   */
  fun catalogChoices(): List<PlaygroundCatalogInfo> {
    val pinned = availableModes
    val default =
      if (pinned.isEmpty()) null
      else
        PlaygroundCatalogInfo(
          id = "",
          label = "Server default",
          modes = pinned,
          // The pinned bundle resolved — that is exactly what `availableModes` just proved.
          resolved = true,
        )
    return listOfNotNull(default) +
      catalogTargets?.invoke().orEmpty().map {
        PlaygroundCatalogInfo(
          id = it.id,
          label =
            if (it.module.isBlank()) "${it.system} (${it.backend})"
            else "${it.system} · ${it.module} (${it.backend})",
          backend = it.backend,
          modes = it.modes,
          resolved = it.resolved,
          system = it.system,
          module = it.module,
        )
      }
  }

  /**
   * The served-catalog system ids this host's **pinned** default compiles against — one per mode
   * whose pin named a catalog and resolved. Empty on a host that pins nothing (the runtime
   * selector's own entries carry their system id in [catalogChoices]) or that pins local files.
   */
  val pinnedCatalogSystems: Set<String>
    get() = availableModes.mapNotNull { pinnedCatalogSystem(it) }.toSet()

  /**
   * Whether a snippet belonging to [system]'s catalog can actually be **compiled here** — as one of
   * the runtime selector's entries, or because this host's pinned default *is* that catalog.
   *
   * This is what the browsing surfaces ask before offering a "open this preview in the playground"
   * handoff, and getting it wrong is not cosmetic. Every catalog page can build a `?from=` link,
   * but only the catalogs in this set have a classpath here — so on a host pinned to `compose-m3`,
   * or one with no Robolectric sidecar and therefore no Android modes at all, the handoff from a
   * Wear/Android catalog used to open the editor on that preview's Kotlin and silently retarget it
   * at *someone else's* design system, where every reference in the buffer is unresolved. That
   * reads as "the playground is broken", when what actually happened is that the link should never
   * have been offered. Absent beats dead: [ServeHttpServer] omits the link when this is false, and
   * [ServeWeb.playgroundPage] says so outright for a link that was built before the answer changed.
   *
   * Note the asymmetry with [PlaygroundCatalogTargets.classpath]: this asks only whether the
   * pairing is *offerable*, never resolving a bundle to find out. A catalog that is offered and
   * then fails to resolve still answers "not available" per request, as it always did.
   */
  fun compilesCatalog(system: String): Boolean {
    if (system.isBlank()) return false
    if (catalogChoices().any { it.system == system && it.modes.isNotEmpty() }) return true
    return system in pinnedCatalogSystems
  }

  /**
   * The resolved classpath a snippet compiles and renders against. Backed in production by a
   * catalog's liveBundle `userClassPath` (see the class KDoc); [moduleName] matches the catalog's
   * Kotlin module so the snippet's classes carry a consistent `kotlin.Metadata`.
   */
  data class Classpath(val moduleName: String, val entries: List<Path>)

  /** Compiles [sources] against [classpath], emitting `.class` files into [outputDir]. */
  fun interface Compiler {
    fun compile(
      sources: List<Path>,
      classpath: List<Path>,
      outputDir: Path,
    ): List<PlaygroundDiagnostic>

    /**
     * Stateful variant. Implementations without BTA IC support retain correctness by doing a full
     * compile; callers surface [IncrementalCompileResult.incremental] so trials can distinguish it.
     */
    fun compileIncremental(
      sources: List<Path>,
      classpath: List<Path>,
      outputDir: Path,
      workingDir: Path,
      modified: List<Path>,
      removed: List<Path>,
      firstBuild: Boolean,
    ): IncrementalCompileResult =
      IncrementalCompileResult(compile(sources, classpath, outputDir), incremental = false)
  }

  data class IncrementalCompileResult(
    val diagnostics: List<PlaygroundDiagnostic>,
    val incremental: Boolean,
  )

  /**
   * Finds the `@Preview` id(s) in freshly compiled [classesDir]; empty ⇒ the snippet declared none.
   */
  fun interface PreviewDiscoverer {
    fun discover(classesDir: Path, classpath: List<Path>): List<String>
  }

  /**
   * Compile [request] and, on success, mint a preview token. [isSecurityChecked] is the greppable
   * audit marker forwarded to [PlaygroundTokenStore.add]: the route passes `true` only once the
   * request has cleared the playground gate.
   */
  fun run(
    request: PlaygroundRunRequest,
    isSecurityChecked: Boolean,
    authenticatedOwner: String? = null,
  ): PlaygroundRunResponse {
    if (request.editLease.isNotEmpty()) {
      return try {
        runWithEditLease(request, isSecurityChecked, authenticatedOwner)
      } catch (t: Throwable) {
        failure("playground live edit failed: ${t.message ?: t.javaClass.simpleName}")
      }
    }
    val files = request.files.filter { it.text.isNotBlank() }
    if (files.isEmpty()) return failure("no source files supplied")

    val mode = PlaygroundMode.fromConfType(request.confType)
    val catalog = request.catalog.trim().takeIf { it.isNotEmpty() }
    val classpath =
      catalogClasspath(mode, catalog)
        ?: return failure(
          if (catalog == null) "mode ${mode.name} is not available on this host"
          else "catalog '$catalog' cannot serve mode ${mode.name} on this host"
        )

    var workDir: Path? = null
    return try {
      workDir = newWorkDir()
      compileAndMint(request, files, mode, classpath, workDir, isSecurityChecked)
    } catch (t: Throwable) {
      // Any failure — including newWorkDir() itself throwing on a full/unwritable temp volume —
      // returns the JSON exception contract rather than escaping as a throwable. cleanup runs only
      // once a path exists; the token store owns a dir only after it accepts one, so an aborted run
      // clears its own.
      workDir?.let { cleanup(it) }
      failure("playground compile failed: ${t.message ?: t.javaClass.simpleName}")
    }
  }

  fun acquireEditLease(owner: String, client: String = ""): PlaygroundEditLeaseResponse =
    synchronized(editLock) {
      if (!editLeasesEnabled) {
        return@synchronized PlaygroundEditLeaseResponse(
          false,
          message = "Live editing is disabled.",
        )
      }
      if (owner.isBlank()) {
        return@synchronized PlaygroundEditLeaseResponse(
          false,
          message = "GitHub sign-in is required for live editing.",
        )
      }
      if (client.length > MAX_EDIT_LEASE_CLIENT_LENGTH) {
        return@synchronized PlaygroundEditLeaseResponse(
          false,
          message = "The live-edit client id is too long.",
        )
      }
      purgeExpiredEditLeaseLocked()
      val existing = editLease
      if (existing != null) {
        if (existing.owner == owner) {
          if (
            client.isNotBlank() &&
              client !in existing.holders &&
              existing.holders.size >= MAX_EDIT_LEASE_HOLDERS
          ) {
            return@synchronized PlaygroundEditLeaseResponse(
              false,
              message = "The live-edit lease already has too many browser tabs attached.",
            )
          }
          if (client.isNotBlank()) existing.holders += client
          renewEditLeaseLocked(existing)
          refreshEditHealthLocked()
          return@synchronized existing.response("You already hold the live-edit lease.")
        }
        return@synchronized PlaygroundEditLeaseResponse(
          acquired = false,
          expiresAtEpochMs = existing.expiresAt,
          message = "The live-edit lease is in use. Try again after it expires.",
        )
      }
      val workDir =
        try {
          newWorkDir().also(fileSystem::createDirectories)
        } catch (t: Throwable) {
          return@synchronized PlaygroundEditLeaseResponse(
            acquired = false,
            message =
              "Live editing could not allocate its workspace: ${t.message ?: t.javaClass.simpleName}",
          )
        }
      val lease =
        EditLease(
          id = newEditLeaseId(),
          owner = owner,
          workDir = workDir,
          expiresAt = nowMillis() + editLeaseTtlMillis,
          holders = mutableSetOf<String>().apply { if (client.isNotBlank()) add(client) },
        )
      editLease = lease
      editLeaseAcquisitions++
      scheduleEditLeaseExpiryLocked(lease)
      refreshEditHealthLocked()
      lease.response("Live editing acquired.")
    }

  /** Cheap, owner-free and lock-free projection: status polling never waits behind a compile. */
  fun editLeaseHealth(): PlaygroundHealth.Editing {
    val snapshot = editHealthSnapshot
    return if (
      snapshot.active &&
        snapshot.expiresAtEpochMs != null &&
        snapshot.expiresAtEpochMs <= nowMillis()
    ) {
      snapshot.copy(active = false, expiresAtEpochMs = null)
    } else {
      snapshot
    }
  }

  fun releaseEditLease(owner: String, id: String, client: String = ""): Boolean =
    synchronized(editLock) {
      purgeExpiredEditLeaseLocked()
      val lease = editLease ?: return@synchronized false
      if (lease.owner != owner || lease.id != id) return@synchronized false
      if (client.isNotBlank()) {
        if (!lease.holders.remove(client)) return@synchronized false
        if (lease.holders.isNotEmpty()) {
          refreshEditHealthLocked()
          return@synchronized true
        }
      }
      editLease = null
      cancelEditLeaseExpiryLocked()
      cleanup(lease.workDir)
      refreshEditHealthLocked()
      true
    }

  private fun EditLease.response(message: String) =
    PlaygroundEditLeaseResponse(
      acquired = true,
      lease = id,
      expiresAtEpochMs = expiresAt,
      revision = lastRevision,
      message = message,
    )

  private fun purgeExpiredEditLeaseLocked() {
    val lease = editLease ?: return
    if (lease.expiresAt > nowMillis()) return
    editLease = null
    cancelEditLeaseExpiryLocked()
    cleanup(lease.workDir)
    refreshEditHealthLocked()
  }

  private fun renewEditLeaseLocked(lease: EditLease) {
    lease.expiresAt = nowMillis() + editLeaseTtlMillis
    scheduleEditLeaseExpiryLocked(lease)
  }

  private fun scheduleEditLeaseExpiryLocked(lease: EditLease) {
    cancelEditLeaseExpiryLocked()
    val leaseId = lease.id
    val deadline = lease.expiresAt
    cancelEditLeaseExpiry =
      scheduleEditLeaseExpiry((deadline - nowMillis()).coerceAtLeast(0L)) {
        synchronized(editLock) {
          val current = editLease ?: return@synchronized
          // A release/reacquire or renewal supersedes this particular deadline's task.
          if (current.id != leaseId || current.expiresAt != deadline) return@synchronized
          cancelEditLeaseExpiry = null
          if (current.expiresAt <= nowMillis()) {
            purgeExpiredEditLeaseLocked()
          } else {
            // Wall-clock adjustment or an early scheduler wakeup: retain the lease and try again at
            // the remaining deadline rather than leaking its workspace.
            scheduleEditLeaseExpiryLocked(current)
          }
        }
      }
  }

  private fun cancelEditLeaseExpiryLocked() {
    cancelEditLeaseExpiry?.invoke()
    cancelEditLeaseExpiry = null
  }

  private fun refreshEditHealthLocked() {
    val lease = editLease?.takeIf { it.expiresAt > nowMillis() }
    editHealthSnapshot =
      PlaygroundHealth.Editing(
        enabled = editLeasesEnabled,
        active = lease != null,
        expiresAtEpochMs = lease?.expiresAt,
        // Process-lifetime soak telemetry, like the neighboring counters: retain the last accepted
        // revision after its lease releases or expires so operators can inspect a completed trial.
        lastRevision = editLastRevision,
        acquisitions = editLeaseAcquisitions,
        compileAttempts = editCompileAttempts,
        incrementalCompiles = editIncrementalCompiles,
        fullFallbacks = editFullFallbacks,
        lastCompileMillis = editLastCompileMillis,
      )
  }

  private fun runWithEditLease(
    request: PlaygroundRunRequest,
    isSecurityChecked: Boolean,
    owner: String?,
  ): PlaygroundRunResponse =
    synchronized(editLock) {
      if (!editLeasesEnabled) return@synchronized failure("Live editing is disabled.")
      purgeExpiredEditLeaseLocked()
      val lease = editLease ?: return@synchronized failure("The live-edit lease has expired.")
      if (owner.isNullOrBlank() || lease.owner != owner || lease.id != request.editLease) {
        return@synchronized failure("This live-edit lease is not yours.")
      }
      if (request.revision <= lease.lastRevision) {
        return@synchronized failure(
          "Revision ${request.revision} is stale; the lease is already at ${lease.lastRevision}."
        )
      }
      val files = request.files.filter { it.text.isNotBlank() }
      if (files.isEmpty()) return@synchronized failure("no source files supplied")

      val mode = PlaygroundMode.fromConfType(request.confType)
      val catalog = request.catalog.trim().takeIf { it.isNotEmpty() }
      val classpath =
        catalogClasspath(mode, catalog)
          ?: return@synchronized failure(
            if (catalog == null) "mode ${mode.name} is not available on this host"
            else "catalog '$catalog' cannot serve mode ${mode.name} on this host"
          )

      renewEditLeaseLocked(lease)
      refreshEditHealthLocked()
      val config = mode to catalog
      if (lease.config != null && lease.config != config) {
        cleanup(lease.workDir)
        fileSystem.createDirectories(lease.workDir)
        lease.files = emptyMap()
        lease.incrementalReady = false
      }
      lease.config = config

      val srcDir = lease.workDir / "src"
      val classesDir = lease.workDir / "classes"
      val icDir = lease.workDir / "bta-ic"
      fileSystem.createDirectories(srcDir)
      fileSystem.createDirectories(classesDir)
      val desired = stagedFileMap(files)
      val removed = (lease.files.keys - desired.keys).map { srcDir / it }
      val modified =
        desired
          .filter { (name, text) -> lease.files[name] != text }
          .map { (name, text) ->
            (srcDir / name).also { path -> fileSystem.write(path) { writeUtf8(text) } }
          }
      removed.forEach { fileSystem.delete(it, mustExist = false) }
      val sources = desired.keys.map { srcDir / it }
      val firstBuild = !lease.incrementalReady
      val compileStarted = System.nanoTime()
      editCompileAttempts++
      refreshEditHealthLocked()
      var compile =
        try {
          compiler.compileIncremental(
            sources = sources,
            classpath = classpath.entries,
            outputDir = classesDir,
            workingDir = icDir,
            modified = modified,
            removed = removed,
            firstBuild = firstBuild,
          )
        } catch (t: Throwable) {
          renewEditLeaseLocked(lease)
          refreshEditHealthLocked()
          throw t
        }
      // Admission happens before the jailed compiler is launched. A busy response therefore did
      // not compile or mutate IC state: report it without accepting the staged files/revision, so
      // the complete dirty set is retried on the next Run.
      if (compile.diagnostics.isCompileAdmissionFailure()) {
        restoreStagedFiles(srcDir, accepted = lease.files, staged = desired)
        editLastCompileMillis = (System.nanoTime() - compileStarted) / 1_000_000
        renewEditLeaseLocked(lease)
        refreshEditHealthLocked()
        return@synchronized PlaygroundRunResponse(
          diagnostics = compile.diagnostics,
          errors = PlaygroundErrorsWire.project(compile.diagnostics),
          editLease = lease.id,
          revision = lease.lastRevision.takeIf { it > 0 },
        )
      }
      // An internal BTA/IC failure has no source position. Reset only this lease and retry the same
      // revision through the proven full path; ordinary source diagnostics stay incremental.
      if (compile.diagnostics.isInfrastructureCompileFailure()) {
        editFullFallbacks++
        refreshEditHealthLocked()
        fileSystem.deleteRecursively(classesDir, mustExist = false)
        fileSystem.deleteRecursively(icDir, mustExist = false)
        fileSystem.createDirectories(classesDir)
        compile =
          IncrementalCompileResult(
            compiler.compile(sources, classpath.entries, classesDir),
            incremental = false,
          )
        lease.incrementalReady = false
      } else {
        lease.incrementalReady = true
      }
      if (compile.incremental) editIncrementalCompiles++
      editLastCompileMillis = (System.nanoTime() - compileStarted) / 1_000_000
      lease.files = desired
      lease.lastRevision = request.revision
      editLastRevision = request.revision
      refreshEditHealthLocked()

      val diagnostics = compile.diagnostics
      if (diagnostics.any { it.severity == PlaygroundSeverity.ERROR }) {
        renewEditLeaseLocked(lease)
        refreshEditHealthLocked()
        return@synchronized PlaygroundRunResponse(
          diagnostics = diagnostics,
          errors = PlaygroundErrorsWire.project(diagnostics),
          editLease = lease.id,
          revision = request.revision,
          incremental = compile.incremental,
        )
      }

      // BTA needs a mutable output directory for the next edit. Preview tokens need immutable
      // bytecode. Snapshot the successful revision into an ordinary token-owned work directory.
      val revisionWorkDir = newWorkDir()
      val revisionClasses = revisionWorkDir / "classes"
      val response =
        try {
          fileSystem.createDirectories(revisionClasses)
          copyDirectory(classesDir, revisionClasses)
          publishCompiled(
              mode = mode,
              classpath = classpath,
              classesDir = revisionClasses,
              workDir = revisionWorkDir,
              diagnostics = diagnostics,
              isSecurityChecked = isSecurityChecked,
            )
            .copy(
              editLease = lease.id,
              revision = request.revision,
              incremental = compile.incremental,
            )
        } catch (t: Throwable) {
          cleanup(revisionWorkDir)
          failure("playground revision snapshot failed: ${t.message ?: t.javaClass.simpleName}")
        }
      // The TTL is an idle timeout. Give a continuously active owner a full window after
      // compilation, discovery, snapshotting and first-frame rendering finish.
      renewEditLeaseLocked(lease)
      refreshEditHealthLocked()
      response
    }

  private fun compileAndMint(
    request: PlaygroundRunRequest,
    files: List<PlaygroundFile>,
    mode: PlaygroundMode,
    classpath: Classpath,
    workDir: Path,
    isSecurityChecked: Boolean,
  ): PlaygroundRunResponse {
    val srcDir = workDir / "src"
    val classesDir = workDir / "classes"
    fileSystem.createDirectories(srcDir)
    fileSystem.createDirectories(classesDir)
    val sources = stageSources(files, srcDir)

    val diagnostics = compiler.compile(sources, classpath.entries, classesDir)
    if (diagnostics.any { it.severity == PlaygroundSeverity.ERROR }) {
      cleanup(workDir)
      return PlaygroundRunResponse(
        diagnostics = diagnostics,
        errors = PlaygroundErrorsWire.project(diagnostics),
      )
    }

    return publishCompiled(
      mode = mode,
      classpath = classpath,
      classesDir = classesDir,
      workDir = workDir,
      diagnostics = diagnostics,
      isSecurityChecked = isSecurityChecked,
    )
  }

  private fun publishCompiled(
    mode: PlaygroundMode,
    classpath: Classpath,
    classesDir: Path,
    workDir: Path,
    diagnostics: List<PlaygroundDiagnostic>,
    isSecurityChecked: Boolean,
  ): PlaygroundRunResponse {
    val renderClasspath = classpath.entries + classesDir
    // Sorted, so the same snippet renders the same preview on every run: ClassGraph's scan order
    // over the snippet's classes is not a guaranteed order, and a multi-file snippet routinely
    // declares more than one @Preview. The full list rides the response, so the editor can say
    // which of them it drew.
    val previews = discoverer.discover(classesDir, renderClasspath).sorted()
    if (previews.isEmpty()) {
      cleanup(workDir)
      return PlaygroundRunResponse(
        diagnostics = diagnostics,
        errors = PlaygroundErrorsWire.project(diagnostics),
        exception = "no @Preview found — a playground snippet must declare one @Preview composable",
      )
    }

    val snippet =
      PlaygroundTokenStore.PlaygroundSnippet(
        mode = mode,
        workDir = workDir,
        classesDir = classesDir,
        classpath = renderClasspath,
        moduleName = classpath.moduleName,
        previewId = previews.first(),
        // Carry them ALL: the still frame draws the first, but the redeemed live session lists
        // every one so the viewer can navigate between them (a multi-file snippet routinely
        // declares several, and until now the rest were compiled and then unreachable).
        previewIds = previews,
      )

    if (mode == PlaygroundMode.REMOTE_COMPOSE) {
      return remoteComposeResult(snippet, previews, diagnostics, workDir, isSecurityChecked)
    }

    val image = renderFirstFrame(snippet)?.let(::toDataUri)
    // From here the token owns workDir; do NOT cleanup on this path.
    val token = tokenStore.add(snippet, isSecurityChecked = isSecurityChecked)
    return PlaygroundRunResponse(
      diagnostics = diagnostics,
      errors = PlaygroundErrorsWire.project(diagnostics),
      image = image,
      previewToken = token.id,
      previewUrl = token.path,
      previewId = snippet.previewId,
      previews = previews,
    )
  }

  /**
   * The [PlaygroundMode.REMOTE_COMPOSE] terminal: capture the snippet's `.rc` document and hand it
   * to the document store as an expiring `/d/<id>` permalink. Unlike the live CMP/Android modes, RC
   * needs no daemon session — the document, not a session, is the deliverable (PLAYGROUND.md §3).
   * So the work dir is released as soon as the one-shot capture is done and **no** token is minted;
   * a snippet that emits no document, or a store that refuses the bytes, is a clean failure with
   * neither a token nor a permalink.
   */
  private fun remoteComposeResult(
    snippet: PlaygroundTokenStore.PlaygroundSnippet,
    previews: List<String>,
    diagnostics: List<PlaygroundDiagnostic>,
    workDir: Path,
    isSecurityChecked: Boolean,
  ): PlaygroundRunResponse {
    val errors = PlaygroundErrorsWire.project(diagnostics)
    val bytes = captureRemoteDocument(snippet)
    // The capture reads the compiled classes, then RC is done with them — it never stands up a live
    // session — so the work dir goes now regardless of what the capture produced.
    cleanup(workDir)
    if (bytes == null) {
      return PlaygroundRunResponse(
        diagnostics = diagnostics,
        errors = errors,
        exception =
          "no Remote Compose document was captured — a remote-compose snippet must declare an " +
            "@Preview that emits a RemoteDocument",
      )
    }
    val url =
      publishRemoteDocument(documentLabel(snippet), bytes, isSecurityChecked)
        ?: return PlaygroundRunResponse(
          diagnostics = diagnostics,
          errors = errors,
          exception = "remote-compose documents are not accepted on this host",
        )
    return PlaygroundRunResponse(
      diagnostics = diagnostics,
      errors = errors,
      documentUrl = url,
      previewId = snippet.previewId,
      previews = previews,
    )
  }

  /**
   * A short, human label for the published document — the preview's simple name, `.rc`-suffixed.
   */
  private fun documentLabel(snippet: PlaygroundTokenStore.PlaygroundSnippet): String =
    snippet.previewId.substringAfterLast('.').ifBlank { "snippet" } + ".rc"

  /**
   * Write each file to [srcDir] under a safe, unique `.kt` name; return the staged source paths.
   */
  private fun stageSources(files: List<PlaygroundFile>, srcDir: Path): List<Path> {
    val used = mutableSetOf<String>()
    return files.map { file ->
      val name = uniqueName(safeKtName(file.name), used)
      val path = srcDir / name
      fileSystem.write(path) { writeUtf8(file.text) }
      path
    }
  }

  private fun stagedFileMap(files: List<PlaygroundFile>): Map<String, String> {
    val used = mutableSetOf<String>()
    return buildMap {
      files.forEach { file -> put(uniqueName(safeKtName(file.name), used), file.text) }
    }
  }

  /** Restore the source tree after admission rejects a request before compilation starts. */
  private fun restoreStagedFiles(
    srcDir: Path,
    accepted: Map<String, String>,
    staged: Map<String, String>,
  ) {
    (staged.keys - accepted.keys).forEach { name ->
      fileSystem.delete(srcDir / name, mustExist = false)
    }
    accepted.forEach { (name, text) ->
      if (staged[name] != text) fileSystem.write(srcDir / name) { writeUtf8(text) }
    }
  }

  private fun copyDirectory(from: Path, to: Path) {
    check(fileSystem.metadataOrNull(from)?.isDirectory == true) {
      "compiled classes directory is missing: $from"
    }
    fileSystem.createDirectories(to)
    fileSystem.list(from).forEach { source ->
      val target = to / source.name
      if (fileSystem.metadata(source).isDirectory) {
        copyDirectory(source, target)
      } else {
        fileSystem.copy(source, target)
      }
    }
  }

  private fun List<PlaygroundDiagnostic>.isInfrastructureCompileFailure(): Boolean =
    size == 1 &&
      single().file == null &&
      (single().message.startsWith("compilation failed:") ||
        single().message.startsWith("compile sandbox error:") ||
        single().message.startsWith("compile sandbox failed:") ||
        single().message.startsWith("could not launch the compile sandbox") ||
        single().message.startsWith("the compile sandbox produced no result") ||
        single().message.startsWith("unreadable compile report:"))

  private fun List<PlaygroundDiagnostic>.isCompileAdmissionFailure(): Boolean =
    size == 1 &&
      single().file == null &&
      single().message.startsWith("the playground is busy compiling")

  private fun cleanup(workDir: Path) {
    try {
      fileSystem.deleteRecursively(workDir, mustExist = false)
    } catch (_: Exception) {
      // Best-effort — leaking one temp dir beats throwing out of an error path.
    }
  }

  private fun failure(message: String) = PlaygroundRunResponse(exception = message)

  companion object {
    const val DEFAULT_EDIT_LEASE_TTL_MILLIS: Long = 15 * 60 * 1000L
    private const val MAX_EDIT_LEASE_CLIENT_LENGTH = 128
    private const val MAX_EDIT_LEASE_HOLDERS = 32

    private val EDIT_LEASE_RANDOM = SecureRandom()
    private val EDIT_LEASE_EXPIRY_EXECUTOR =
      ScheduledThreadPoolExecutor(1) { runnable ->
          Thread(runnable, "playground-edit-lease-expiry").apply { isDaemon = true }
        }
        .apply {
          // Renewals cancel the old deadline; remove it immediately rather than retaining a
          // cancelled task in the delayed queue until the original TTL elapses.
          removeOnCancelPolicy = true
        }

    private fun newEditLeaseId(): String {
      val bytes = ByteArray(18)
      EDIT_LEASE_RANDOM.nextBytes(bytes)
      return "pge_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Reduce a client-supplied name to a safe `.kt` filename (last segment, printable, non-empty).
     */
    internal fun safeKtName(raw: String): String {
      val base =
        raw
          .substringAfterLast('/')
          .substringAfterLast('\\')
          .filter { it.isLetterOrDigit() || it in "._-" }
          .removeSuffix(".kt")
          .takeIf { it.isNotBlank() } ?: "Snippet"
      return "$base.kt"
    }

    /**
     * Ensure the name is unique within a run, appending `_<n>` before `.kt` on collision. Collision
     * keys are **case-folded**: a case-insensitive target FS (Windows, default macOS) maps `A.kt`
     * and `a.kt` to the same file, so two case-only-distinct names must be disambiguated or the
     * second write silently overwrites the first while both paths still reach the compiler. Folding
     * is universally safe — on a case-sensitive FS it only ever renames a name that would otherwise
     * have been kept, never causing an overwrite.
     */
    private fun uniqueName(name: String, usedLowercase: MutableSet<String>): String {
      if (usedLowercase.add(name.lowercase())) return name
      val stem = name.removeSuffix(".kt")
      var i = 1
      while (true) {
        val candidate = "${stem}_$i.kt"
        if (usedLowercase.add(candidate.lowercase())) return candidate
        i++
      }
    }

    internal fun toDataUri(png: ByteArray): String =
      "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
  }
}
