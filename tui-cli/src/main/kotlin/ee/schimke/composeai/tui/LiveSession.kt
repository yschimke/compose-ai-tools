package ee.schimke.composeai.tui

import ee.schimke.composeai.cli.PreviewModule
import ee.schimke.composeai.daemon.protocol.FileKind
import ee.schimke.composeai.render.session.RenderSession
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.RenderSessionException
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Live-mode controller. Owns the optional [RenderSession] subscription that turns external file
 * edits (vim in a sibling terminal, VS Code, …) into automatic preview re-renders.
 *
 * ## Stickiness
 *
 * Live mode is sticky across preview navigation: once the user toggles it on, navigating to a
 * different preview keeps the session open and re-subscribes the visible-set to the new id.
 * Toggling live mode *off* is the only way to release the daemon — switching previews never
 * implicitly closes it.
 *
 * ## State surface
 *
 * Consumers observe [state] for UI updates and listen to [updates] for "re-read the PNG / a11y
 * findings now" pulses. Updates are coalesced: the controller only emits when the daemon reports
 * `renderFinished` or `dataProduct`, never on every keystroke.
 */
class LiveSession(private val scope: CoroutineScope) {
  enum class Status {
    OFF,
    OPENING,
    READY,
    FAILED,
  }

  data class State(
    val status: Status = Status.OFF,
    val modulePath: String? = null,
    val lastError: String? = null,
    /** Monotonic counter incremented on every daemon notification — drives recompose tick. */
    val tick: Long = 0,
    /**
     * Latest rendered PNG path per preview id, harvested from `renderFinished` notifications. This
     * is how a consumer finds the freshest frame **without assuming any on-disk project layout** —
     * the daemon reports exactly where it wrote each render (project mode and project-less bundle
     * mode alike).
     */
    val lastPng: Map<String, String> = emptyMap(),
  )

  private val _state = MutableStateFlow(State())
  val state: StateFlow<State> = _state.asStateFlow()

  private val sessionRef = AtomicReference<RenderSession?>(null)
  private val watcherRef = AtomicReference<FileWatcher?>(null)
  private var openJob: Job? = null
  private var currentVisible: String? = null

  /**
   * Returns the active session iff live mode is currently READY. Callers use this for ad-hoc
   * `fetchData` calls (e.g. pulling a11y findings on demand) when live mode is on; when it's off
   * the consumer falls back to disk-only reads.
   */
  fun activeSession(): RenderSession? =
    if (_state.value.status == Status.READY) sessionRef.get() else null

  /**
   * Turn live mode on against [module]. Idempotent: if a session is already open for the same
   * module path, this is a no-op. Switching modules tears down the prior session first.
   */
  fun enable(module: PreviewModule, extensions: Set<String>) {
    val current = _state.value
    if (current.status == Status.READY && current.modulePath == module.gradlePath) return
    if (current.status == Status.OPENING && current.modulePath == module.gradlePath) return

    closeQuietly()

    _state.value = State(status = Status.OPENING, modulePath = module.gradlePath, lastError = null)

    openJob =
      scope.launch(Dispatchers.IO) {
        val descriptor = File(module.projectDir, "build/compose-previews/daemon-launch.json")
        if (!descriptor.isFile) {
          _state.value =
            State(
              status = Status.FAILED,
              modulePath = module.gradlePath,
              lastError =
                "no daemon descriptor at ${descriptor.path} — run `composePreviewDaemonStart` first",
            )
          return@launch
        }

        val session: RenderSession =
          try {
            SubprocessRenderSessions.open(
              RenderSessionConfig(
                descriptorPath = descriptor,
                workspaceRoot = module.projectDir.parentFile ?: module.projectDir,
                workspaceName = module.projectDir.name,
                logSink = { /* swallow — surfaced via state on real failure */ },
                initializeTimeout = 60.seconds,
              )
            )
          } catch (e: RenderSessionException) {
            _state.value =
              State(
                status = Status.FAILED,
                modulePath = module.gradlePath,
                lastError = e.message ?: e.javaClass.simpleName,
              )
            return@launch
          }

        // Project mode: a filesystem watcher rooted at the module's `src/` forwards edits as
        // `fileChanged` so the daemon invalidates and re-renders.
        attachSession(
          session = session,
          modulePath = module.gradlePath,
          extensions = extensions,
          watchDir = module.projectDir.resolve("src"),
          priorTick = current.tick,
        )
      }
  }

  /**
   * Project-less live mode for a self-contained bundle. [opener] spawns a daemon straight from the
   * bundle (no `daemon-launch.json`, no source tree); the returned session is wired identically to
   * [enable] **except there is no file watcher** — there is no source to watch, so "live" here means
   * the daemon is held open for `r`-driven re-renders. [modulePath] is informational only.
   */
  fun enableBundle(modulePath: String, extensions: Set<String>, opener: () -> RenderSession) {
    closeQuietly()
    val priorTick = _state.value.tick
    _state.value = State(status = Status.OPENING, modulePath = modulePath, lastError = null)

    openJob =
      scope.launch(Dispatchers.IO) {
        val session: RenderSession =
          try {
            opener()
          } catch (e: Exception) {
            _state.value =
              State(
                status = Status.FAILED,
                modulePath = modulePath,
                lastError = e.message ?: e.javaClass.simpleName,
              )
            return@launch
          }
        attachSession(
          session = session,
          modulePath = modulePath,
          extensions = extensions,
          watchDir = null,
          priorTick = priorTick,
        )
      }
  }

  /**
   * Shared post-open wiring for [enable] and [enableBundle]: register the session, enable
   * extensions, install the notification listener (which harvests `renderFinished.pngPath` into
   * [State.lastPng]), optionally start a [FileWatcher] over [watchDir], and flip the state to READY.
   */
  private fun attachSession(
    session: RenderSession,
    modulePath: String,
    extensions: Set<String>,
    watchDir: File?,
    priorTick: Long,
  ) {
    sessionRef.set(session)

    if (extensions.isNotEmpty()) {
      try {
        session.enableExtensions(extensions.toList())
      } catch (_: RenderSessionException) {
        // Best-effort — a missing extension shouldn't kill live mode; the UI will show
        // a "no findings" state for that pane.
      }
    }

    // Tick on every notification so Compose recomposes. `renderFinished` additionally carries the
    // path the daemon wrote the frame to (`id` + `pngPath`); record it so consumers read the
    // freshest frame from the daemon's own report rather than guessing an on-disk project layout.
    val ticker =
      session.onNotification { method, params ->
        if (method == "renderFinished" && params != null) {
          val id = (params["id"] as? JsonPrimitive)?.contentOrNull
          val png = (params["pngPath"] as? JsonPrimitive)?.contentOrNull
          if (id != null && png != null) {
            _state.update { it.copy(tick = it.tick + 1, lastPng = it.lastPng + (id to png)) }
            return@onNotification
          }
        }
        _state.update { it.copy(tick = it.tick + 1) }
      }

    if (watchDir != null) {
      val watcher = FileWatcher(watchDir)
      watcherRef.set(watcher)
      watcher.start { changedPath ->
        val s = sessionRef.get() ?: return@start
        try {
          s.fileChanged(changedPath.toAbsolutePath().toString(), kind = FileKind.SOURCE)
        } catch (_: RenderSessionException) {
          // Transport went away (daemon died, etc.); the next user action will surface
          // the FAILED status when the listener's next call throws.
        }
      }
    }

    _state.value = State(status = Status.READY, modulePath = modulePath, tick = priorTick + 1)

    // Keep the listener registered for the lifetime of the session. `ticker` is closed in
    // [closeQuietly]; the AutoCloseable is parked here.
    sessionListenerHandle.set(ticker)
  }

  /** Drop the session and the file watcher. Idempotent. */
  fun disable() {
    closeQuietly()
    _state.value = State(status = Status.OFF)
  }

  /**
   * Tell the daemon which preview id the user is currently looking at. Drives subscription liveness
   * — only the visible preview gets data-product pushes, so the daemon doesn't keep regenerating
   * findings for previews the user can't see.
   */
  suspend fun setVisible(previewId: String) {
    if (currentVisible == previewId) return
    currentVisible = previewId
    val s = sessionRef.get() ?: return
    withContext(Dispatchers.IO) {
      try {
        s.setVisible(listOf(previewId))
        s.setFocus(listOf(previewId))
      } catch (_: RenderSessionException) {
        // see comment in enable() — non-fatal.
      }
    }
  }

  /** Force a synchronous render of [previewId] (the `r` key). */
  suspend fun forceRender(previewId: String) {
    val s = sessionRef.get() ?: return
    withContext(Dispatchers.IO) {
      try {
        s.renderNow(listOf(previewId), reason = "tui-cli force-render")
      } catch (_: RenderSessionException) {
        // surfaced via the next notification cycle / state inspection.
      }
    }
  }

  private val sessionListenerHandle = AtomicReference<AutoCloseable?>(null)

  private fun closeQuietly() {
    openJob?.cancel()
    openJob = null
    sessionListenerHandle.getAndSet(null)?.let { runCatching { it.close() } }
    watcherRef.getAndSet(null)?.close()
    sessionRef.getAndSet(null)?.let { runCatching { it.close() } }
    currentVisible = null
  }
}

private inline fun <T> MutableStateFlow<T>.update(block: (T) -> T) {
  while (true) {
    val cur = value
    val next = block(cur)
    if (compareAndSet(cur, next)) return
  }
}
