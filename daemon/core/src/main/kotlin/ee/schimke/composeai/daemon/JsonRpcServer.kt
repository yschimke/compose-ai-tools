package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.history.HistoryEntry
import ee.schimke.composeai.daemon.history.HistoryFilter
import ee.schimke.composeai.daemon.history.HistoryManager
import ee.schimke.composeai.daemon.history.HistoryPruneConfig
import ee.schimke.composeai.daemon.history.PreviewMetadataSnapshot
import ee.schimke.composeai.daemon.history.PruneReason
import ee.schimke.composeai.daemon.protocol.ClasspathDirtyParams
import ee.schimke.composeai.daemon.protocol.ClasspathDirtyReason
import ee.schimke.composeai.daemon.protocol.DataFetchParams
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataSubscribeParams
import ee.schimke.composeai.daemon.protocol.DataSubscribeResult
import ee.schimke.composeai.daemon.protocol.DiscoveryUpdatedParams
import ee.schimke.composeai.daemon.protocol.FileChangedParams
import ee.schimke.composeai.daemon.protocol.FileKind
import ee.schimke.composeai.daemon.protocol.HistoryAddedParams
import ee.schimke.composeai.daemon.protocol.HistoryDiffMode
import ee.schimke.composeai.daemon.protocol.HistoryDiffParams
import ee.schimke.composeai.daemon.protocol.HistoryDiffResult
import ee.schimke.composeai.daemon.protocol.HistoryListParams
import ee.schimke.composeai.daemon.protocol.HistoryListResult
import ee.schimke.composeai.daemon.protocol.HistoryPruneParams
import ee.schimke.composeai.daemon.protocol.HistoryPruneResult
import ee.schimke.composeai.daemon.protocol.HistoryPruneSourceResult
import ee.schimke.composeai.daemon.protocol.HistoryPrunedParams
import ee.schimke.composeai.daemon.protocol.HistoryReadParams
import ee.schimke.composeai.daemon.protocol.HistoryReadResultDto
import ee.schimke.composeai.daemon.protocol.InitializeParams
import ee.schimke.composeai.daemon.protocol.InitializeResult
import ee.schimke.composeai.daemon.protocol.JsonRpcNotification
import ee.schimke.composeai.daemon.protocol.JsonRpcRequest
import ee.schimke.composeai.daemon.protocol.JsonRpcResponse
import ee.schimke.composeai.daemon.protocol.KnownDevice
import ee.schimke.composeai.daemon.protocol.LeakDetectionMode
import ee.schimke.composeai.daemon.protocol.Manifest
import ee.schimke.composeai.daemon.protocol.Orientation
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.PruneReasonWire
import ee.schimke.composeai.daemon.protocol.RejectedRender
import ee.schimke.composeai.daemon.protocol.RenderFinishedParams
import ee.schimke.composeai.daemon.protocol.RenderMetrics
import ee.schimke.composeai.daemon.protocol.RenderNowParams
import ee.schimke.composeai.daemon.protocol.RenderNowResult
import ee.schimke.composeai.daemon.protocol.RenderStartedParams
import ee.schimke.composeai.daemon.protocol.ServerCapabilities
import ee.schimke.composeai.daemon.protocol.SetFocusParams
import ee.schimke.composeai.daemon.protocol.SetVisibleParams
import ee.schimke.composeai.daemon.protocol.UiMode
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long

/**
 * JSON-RPC 2.0 server over stdio for the preview daemon.
 *
 * Wire format and dispatch semantics: docs/daemon/PROTOCOL.md (v1, locked).
 *
 * **Threading model.**
 * - One **read thread** (the thread that calls [run]) drains [input], parses envelopes, and
 *   dispatches them. Inline handlers (initialize, setVisible, setFocus, fileChanged, shutdown,
 *   exit) execute on this thread.
 * - One **write thread** (named `compose-ai-daemon-writer`) consumes a single outbound queue and
 *   writes framed bytes to [output]. This serialises every reply and notification so framing on the
 *   wire is always well-formed even when notifications race with responses.
 * - The **[RenderHost] render thread** (B1.3) is the only place renders execute. `renderNow`
 *   enqueues `RenderRequest.Render` items onto [host]; per-render notifications (`renderStarted`,
 *   `renderFinished`) are emitted from a dedicated **render-watcher thread** that polls completed
 *   results and forwards them to the writer queue.
 *
 * **No mid-render cancellation invariant** (DESIGN.md § 9, PROTOCOL.md § 3). Shutdown stops
 * accepting new `renderNow` work, then waits for every already-accepted render to complete before
 * responding. We never call `Thread.interrupt()` on the render thread; the host's poison-pill
 * `Shutdown` is enqueued only after the in-flight queue has drained.
 *
 * **Stub render bodies.** B1.4 (RenderEngine) replaces the body of [renderFinishedFromResult] with
 * the real Compose/Robolectric render. For B1.5 the host returns a synthetic [RenderResult] and we
 * materialise it as a placeholder PNG path of `${historyDir}/daemon-stub-${id}.png`. The
 * placeholder file is **not** written to disk — `pngPath` is a string field, not a postcondition
 * that the file must exist. B1.4 will both produce real bytes and make the path point at them.
 *
 * **B1.4 hook point.** When B1.4 introduces `RenderEngine`, the wiring change is: replace the body
 * of [renderFinishedFromResult] with a call into `RenderEngine.renderTookMs(result)` (or similar)
 * that materialises the PNG and returns timing/metrics. The render queue plumbing (submit → poll →
 * notify) does not need to change.
 */
class JsonRpcServer(
  private val input: InputStream,
  private val output: OutputStream,
  private val host: RenderHost,
  private val daemonVersion: String = DEFAULT_DAEMON_VERSION,
  private val historyDir: String = DEFAULT_HISTORY_DIR,
  private val idleTimeoutMs: Long =
    System.getProperty(IDLE_TIMEOUT_PROP)?.toLongOrNull() ?: DEFAULT_IDLE_TIMEOUT_MS,
  /**
   * B2.1 Tier-1 fingerprint detector. When non-null, the server captures a [Snapshot] at
   * construction time and re-checks it on every `fileChanged({ kind: "classpath" })` notification;
   * a mismatch triggers a one-shot `classpathDirty` notification and a graceful exit within
   * [classpathDirtyGraceMs]. When null (the harness's fake-mode scenarios, the in-process
   * integration tests) the classpath path stays a no-op — same as pre-B2.1 behaviour.
   */
  private val classpathFingerprint: ClasspathFingerprint? = null,
  /**
   * Grace window between emitting `classpathDirty` and calling [onExit]. PROTOCOL.md § 6 documents
   * this as `daemon.classpathDirtyGraceMs`, default 2000ms. Public so tests can shorten it.
   */
  private val classpathDirtyGraceMs: Long =
    System.getProperty(CLASSPATH_DIRTY_GRACE_PROP)?.toLongOrNull()
      ?: DEFAULT_CLASSPATH_DIRTY_GRACE_MS,
  /**
   * B2.2 phase 1 — the in-memory preview index, parsed from `previews.json` at daemon startup by
   * [DaemonMain]. Surfaced to clients via `initialize.manifest`. Defaults to [PreviewIndex.empty]
   * so existing in-process call sites (the integration tests, fake-mode harness scenarios) stay
   * source-compatible — the empty index reports `path = ""` and `previewCount = 0`, matching the
   * pre-B2.2 stub.
   *
   * B2.2 phase 2 — the index is now mutable. `fileChanged({kind: source})` runs the cheap-prefilter
   * → scoped-scan → diff → applyDiff cascade against [incrementalDiscovery] and emits
   * `discoveryUpdated` when the diff is non-empty.
   */
  private val previewIndex: PreviewIndex = PreviewIndex.empty(),
  /**
   * B2.2 phase 2 — when non-null, [handleFileChanged] for `kind: "source"` runs the cheap pre-
   * filter + scoped ClassGraph scan against this discovery instance, diffs against [previewIndex],
   * applies the diff in-place, and emits `discoveryUpdated`. When null (the in-process integration
   * tests, the pre-phase-2 default) the source path stays a classloader-swap-only no-op — same
   * behaviour as before phase 2 landed.
   */
  private val incrementalDiscovery: IncrementalDiscovery? = null,
  /**
   * Watchdog window for the deferred-discovery cascade — see [queueDiscoveryAfterRender]. A
   * `fileChanged({kind: source})` notification queues the file for a background scan + diff, but
   * holds the resulting `discoveryUpdated` notification until either (a) the next `renderFinished`
   * for any preview flushes the queue, or (b) this many milliseconds elapse and we drain anyway.
   * The point is that the user sees the new PNG first; the metadata reconcile arrives behind it and
   * is silent when the diff is empty.
   *
   * Configurable via the [DISCOVERY_WATCHDOG_PROP] sysprop; the harness lowers it to a few hundred
   * ms in scenarios that don't issue a render between the save and the assertion.
   */
  private val discoveryWatchdogMs: Long =
    System.getProperty(DISCOVERY_WATCHDOG_PROP)?.toLongOrNull() ?: DEFAULT_DISCOVERY_WATCHDOG_MS,
  /**
   * H1+H2 — when non-null, every successful render produces a sidecar + index entry on disk
   * (HISTORY.md § "What this PR lands § H1") and emits a `historyAdded` notification. The
   * `history/list` and `history/read` requests dispatch into this manager. When null (in-process
   * tests, fake-mode harness scenarios that don't opt in), history calls return empty / not-found
   * and `historyAdded` notifications never fire — pre-H1 behaviour.
   */
  private val historyManager: HistoryManager? = null,
  /**
   * H4 — initial delay for the auto-prune scheduler. Defaults to
   * [HistoryManager.DEFAULT_INITIAL_DELAY_MS] (5s — runs after sandbox bootstrap). Tests pass a
   * very small value (e.g. 50ms) to drive the schedule deterministically.
   */
  private val autoPruneInitialDelayMs: Long = HistoryManager.DEFAULT_INITIAL_DELAY_MS,
  /**
   * D1 — producer-side seam for the data-product surface. Defaults to [DataProductRegistry.Empty]
   * so pre-D2 callers keep `capabilities.dataProducts = []` and every `data/fetch` /
   * `data/subscribe` short-circuits to `DataProductUnknown`. The renderer-side a11y producer (D2,
   * in `renderer-android`) is the first concrete implementation that gets wired in by `DaemonMain`.
   */
  private val dataProducts: DataProductRegistry = DataProductRegistry.Empty,
  /**
   * D3 — per-request budget for `data/fetch` re-render-on-demand (DATA-PRODUCTS.md § "Re-render
   * semantics"). When the registry returns [DataProductRegistry.Outcome.RequiresRerender] the
   * dispatcher queues a fresh render in the required mode and waits at most this many milliseconds
   * for the payload to land. On timeout we return `DataProductBudgetExceeded` (-32023); the render
   * itself is **not** cancelled — Robolectric mid-render cancellation is unsafe (PROTOCOL.md § 8) —
   * the fetch just stops waiting and lets the regular `renderFinished` notification ship when the
   * render eventually completes.
   *
   * Default 30000ms per the spec. Overridable via the [DATA_FETCH_RERENDER_BUDGET_PROP] sysprop or
   * the constructor (tests pin it small).
   */
  private val dataFetchRerenderBudgetMs: Long =
    System.getProperty(DATA_FETCH_RERENDER_BUDGET_PROP)?.toLongOrNull()
      ?: DEFAULT_DATA_FETCH_RERENDER_BUDGET_MS,
  private val onExit: (Int) -> Unit = { code -> System.exit(code) },
) {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }

  init {
    // H4 — wire the manager's prune listener so non-empty prune passes (auto or manual) emit a
    // `historyPruned` JSON-RPC notification. The listener is invoked on whatever thread runs the
    // prune (the auto-prune scheduler thread, or the read thread for manual calls); both eventually
    // route through the writer queue, so frame ordering is preserved.
    historyManager?.setPruneListener { notif ->
      val wireReason =
        when (notif.reason) {
          PruneReason.AUTO -> PruneReasonWire.AUTO
          PruneReason.MANUAL -> PruneReasonWire.MANUAL
        }
      sendNotification(
        "historyPruned",
        encode(
          HistoryPrunedParams.serializer(),
          HistoryPrunedParams(
            removedIds = notif.removedIds,
            freedBytes = notif.freedBytes,
            reason = wireReason,
          ),
        ),
      )
    }
  }

  private val initialized = AtomicBoolean(false)
  private val shutdownRequested = AtomicBoolean(false)
  private val running = AtomicBoolean(true)
  private val exitInvoked = AtomicBoolean(false)

  /**
   * Fingerprint reference snapshot — captured once at construction (so it reflects the disk state
   * the daemon was launched against). Null when [classpathFingerprint] is null.
   */
  private val classpathSnapshot: ClasspathFingerprint.Snapshot? = classpathFingerprint?.snapshot()

  /**
   * One-shot guard so a flurry of `fileChanged({ kind: "classpath" })` notifications doesn't emit
   * `classpathDirty` repeatedly. PROTOCOL.md § 6: "Sent at most once per daemon lifetime."
   */
  private val classpathDirtyEmitted = AtomicBoolean(false)

  /** Startup-timeline guards so the "first renderNow / first renderFinished" marks fire once. */
  private val firstRenderNowSeen = AtomicBoolean(false)
  private val firstRenderFinishedSeen = AtomicBoolean(false)

  /** Outbound frame queue. SHUTDOWN_SENTINEL is the writer's poison pill. */
  private val outbound = LinkedBlockingQueue<ByteArray>()

  /** In-flight render IDs the host is currently working on. */
  private val inFlightRenders = ConcurrentHashMap.newKeySet<Long>()

  /** Per-protocol-id → enqueue wall-clock millis, for `renderStarted.queuedMs`. */
  private val acceptedAtMs = ConcurrentHashMap<Long, Long>()

  /** Mapping host-side internal request id → caller's preview id string. */
  private val hostIdToPreviewId = ConcurrentHashMap<Long, String>()

  /**
   * D1 — kinds the client wants attached to *every* render of *every* preview, supplied via
   * `initialize.options.attachDataProducts`. Pre-filtered against [DataProductRegistry.isKnown] +
   * each kind's `attachable` flag at handshake time so the per-render path doesn't re-check.
   */
  @Volatile private var globalAttachKinds: Set<String> = emptySet()

  /**
   * D1 — sticky `(previewId, kind)` subscriptions installed by `data/subscribe`. The map is keyed
   * by previewId so [setVisible] can drop entries for previews that are no longer on screen — see
   * [pruneSubscriptionsToVisible]. Inner sets are wrapped via [ConcurrentHashMap.newKeySet] so
   * subscribe / unsubscribe / attachment-build can race without locking.
   */
  private val subscriptions = ConcurrentHashMap<String, MutableSet<String>>()

  /**
   * D3 — per-render `data/fetch` waiters. When `data/fetch` triggers a re-render via
   * [DataProductRegistry.Outcome.RequiresRerender], the dispatcher registers a future against the
   * host-side render id; [emitRenderFinished] / [emitRenderFailed] complete it as soon as the
   * watcher loop processes the result. The waiter then re-invokes `dataProducts.fetch` to get the
   * payload. On budget timeout the waiter abandons the entry — but does NOT cancel the render
   * (PROTOCOL.md § 8 — no mid-render cancellation), so a stale entry may linger; the watcher cleans
   * it up when the render eventually finishes.
   */
  private val dataFetchWaiters =
    ConcurrentHashMap<Long, java.util.concurrent.CompletableFuture<RerenderOutcome>>()

  // ----------------------------------------------------------------------
  // Interactive (live-stream) mode state — see docs/daemon/INTERACTIVE.md.
  //
  // [interactiveTargets] is the set of currently-active interactive streams, keyed by
  // [InteractiveTarget.frameStreamId]. The wire shape is multi-target by design: each
  // `interactive/start` registers a fresh slot and returns a unique stream id; concurrent
  // streams targeting different (or even the same) preview ids coexist. Inputs route by
  // stream id, so a stale id from a `stop`'d or never-started stream is dropped without
  // touching live targets.
  //
  // The current panel UI is single-target — only one card carries `.live` at a time —
  // but the protocol does not require that. Lifting it on the wire keeps the daemon
  // useful for hypothetical programmatic clients (side-by-side comparison views, CI
  // agents driving multiple previews over one stdio pair) without any contract change.
  //
  // [lastFrameHashes] is a per-previewId cache of the SHA-256 of the most recently
  // notified PNG bytes. Populated on every `renderFinished` that the watcher actually
  // emits; consulted to set `unchanged: true` (and skip history) when the next render
  // produces byte-identical output. Always tracked, not just when interactive mode is
  // on — the dedup is generally useful (a save that doesn't move pixels shouldn't burn
  // IPC + a panel repaint). Per-preview key, not per-stream: two streams targeting the
  // same preview share dedup state, which is what we want — the bytes are the same
  // bytes regardless of which stream's input triggered the render.
  // ----------------------------------------------------------------------

  private data class InteractiveTarget(val previewId: String, val frameStreamId: String)

  private val interactiveTargets = ConcurrentHashMap<String, InteractiveTarget>()

  /**
   * v2 — held [InteractiveSession] per `frameStreamId` for hosts that support
   * [RenderHost.acquireInteractiveSession]. When present, [handleInteractiveInput] dispatches the
   * event into the session and renders through it (state survives across inputs). When absent (host
   * doesn't override the default no-op throw — `FakeHost`, the v1-era hosts), the v1 fall- back
   * path stays in force: each input enqueues a stateless `submitRenderAsync` that recomposes from
   * scratch. Per-stream key, not per-preview: the host decides whether to share state across
   * streams targeting the same preview (design § 9.7).
   *
   * See
   * [INTERACTIVE.md § 9](../../../../../../docs/daemon/INTERACTIVE.md#9-v2--click-dispatch-into-composition).
   */
  private val interactiveSessions = ConcurrentHashMap<String, InteractiveSession>()

  /**
   * v2 phase 3 — per-stream coalescing queue. Inputs arriving while a render is already in flight
   * for the same stream get appended here; the render-finishing path drains the whole queue and
   * dispatches it as a batch followed by a single render. See INTERACTIVE.md § 9.6.
   *
   * Caps the per-stream render rate at the renderer's natural cadence (typically 60 Hz on Skiko)
   * without dropping events — important when we wire pointer-move bursts in v3, modest payoff for
   * click-only v2 since clicks rarely arrive faster than renders complete. The coalescing path
   * still exists for v2 so the implementation is exercised end-to-end before move/drag work starts
   * asking it for more.
   *
   * Concurrent reads are safe because each stream's queue is only ever drained by the worker thread
   * that owns the in-flight render slot for that stream.
   */
  private val pendingInteractiveInputs =
    ConcurrentHashMap<
      String,
      java.util.concurrent.ConcurrentLinkedQueue<
        ee.schimke.composeai.daemon.protocol.InteractiveInputParams
      >,
    >()

  /**
   * v2 phase 3 — claim flag for "this stream has an interactive render in flight". Inputs that find
   * this flag already set queue themselves rather than spawning another worker; the worker that
   * holds the flag drains the queue at the start of each render iteration. The flag is cleared in
   * `finally` after the render finishes; if more inputs arrived in the meantime, the worker
   * re-claims and re-iterates.
   */
  private val interactiveRenderInFlight = ConcurrentHashMap<String, Boolean>()

  private val lastFrameHashes = ConcurrentHashMap<String, String>()

  private val nextStreamIdCounter = java.util.concurrent.atomic.AtomicLong(1)

  /**
   * Preview ids currently in-flight for an override-bearing render. PROTOCOL.md § 5
   * (`renderNow.overrides`) — when a second override-bearing `renderNow` arrives for a previewId
   * already in this set, we reject with `coalesced` rather than queue it. This is the daemon-side
   * half of "don't drown the queue when the user is dragging a slider"; the panel / MCP client is
   * responsible for retrying on the next `renderFinished` if the latest override values still
   * differ from what was rendered (see docs/daemon/INTERACTIVE.md § "Display overrides").
   */
  private val previewIdsWithOverridesInFlight = ConcurrentHashMap.newKeySet<String>()

  private val writerThread =
    Thread({ writerLoop() }, "compose-ai-daemon-writer").apply { isDaemon = false }

  private val renderWatcherThread =
    Thread({ renderWatcherLoop() }, "compose-ai-daemon-render-watcher").apply { isDaemon = false }

  /**
   * Reads from [input] until EOF, dispatches messages inline (or onto the host), and writes replies
   * via the writer thread. Blocks until either:
   *
   * 1. `exit` notification arrives → returns and calls [onExit] (0 if `shutdown` preceded it, else
   *    1).
   * 2. stdin EOF without `shutdown`+`exit` → waits up to [idleTimeoutMs], then exits with code 1.
   */
  fun run() {
    StartupTimings.mark("JsonRpcServer.run() entered")
    host.start()
    StartupTimings.mark("host.start() returned (sandbox ready)")
    // H4 — kick off the auto-prune scheduler now that the sandbox is up. The first pass fires
    // after `autoPruneInitialDelayMs` (5s default; small in tests). All-off configs short-circuit
    // inside `startAutoPrune` so we don't spin a thread for nothing.
    historyManager?.startAutoPrune(initialDelayMs = autoPruneInitialDelayMs)
    writerThread.start()
    renderWatcherThread.start()
    StartupTimings.mark("read loop entering")
    try {
      readLoop()
      // EOF without exit notification — PROTOCOL.md § 3 idle-timeout exit.
      if (!shutdownRequested.get()) {
        try {
          Thread.sleep(idleTimeoutMs)
        } catch (_: InterruptedException) {
          // Restore interrupt status; we're exiting anyway.
          Thread.currentThread().interrupt()
        }
        cleanShutdown()
        invokeExit(1)
      } else {
        // Saw shutdown but stdin closed before `exit`; treat as graceful.
        cleanShutdown()
        invokeExit(0)
      }
    } catch (e: Throwable) {
      System.err.println("compose-ai-daemon: fatal in JsonRpcServer.run: ${e.message}")
      e.printStackTrace(System.err)
      cleanShutdown()
      invokeExit(1)
    }
  }

  // --------------------------------------------------------------------------
  // Read loop
  // --------------------------------------------------------------------------

  private fun readLoop() {
    val reader = ContentLengthFramer(input)
    while (running.get()) {
      val bytes =
        try {
          reader.readFrame() ?: break // EOF
        } catch (e: FramingException) {
          // Per JSON-RPC § 4.2: a parse error is reported with id=null.
          sendErrorResponse(id = null, code = ERR_PARSE, message = e.message ?: "parse error")
          continue
        } catch (_: EOFException) {
          break
        } catch (_: IOException) {
          break
        }
      try {
        dispatchFrame(bytes)
      } catch (e: Throwable) {
        // Don't let one malformed message kill the read loop — surface to
        // stderr (free-form log per PROTOCOL.md § 1) and continue.
        System.err.println("compose-ai-daemon: dispatch error: ${e.message}")
        e.printStackTrace(System.err)
      }
    }
  }

  private fun dispatchFrame(bytes: ByteArray) {
    val text = bytes.toString(Charsets.UTF_8)
    val element =
      try {
        json.parseToJsonElement(text)
      } catch (e: Throwable) {
        sendErrorResponse(id = null, code = ERR_PARSE, message = "invalid JSON: ${e.message}")
        return
      }
    if (element !is JsonObject) {
      sendErrorResponse(id = null, code = ERR_INVALID_REQUEST, message = "envelope must be object")
      return
    }
    val hasId = element.containsKey("id") && element["id"] !is JsonNull
    if (hasId) {
      val request =
        try {
          json.decodeFromString(JsonRpcRequest.serializer(), text)
        } catch (e: Throwable) {
          val rawId = (element["id"] as? JsonPrimitive)?.long
          sendErrorResponse(
            id = rawId,
            code = ERR_INVALID_REQUEST,
            message = "invalid request: ${e.message}",
          )
          return
        }
      handleRequest(request)
    } else {
      val notification =
        try {
          json.decodeFromString(JsonRpcNotification.serializer(), text)
        } catch (e: Throwable) {
          // Notifications have no response per JSON-RPC; log and drop.
          System.err.println("compose-ai-daemon: invalid notification: ${e.message}")
          return
        }
      handleNotification(notification)
    }
  }

  // --------------------------------------------------------------------------
  // Request handlers
  // --------------------------------------------------------------------------

  private fun handleRequest(req: JsonRpcRequest) {
    if (req.method != "initialize" && !initialized.get()) {
      sendErrorResponse(
        id = req.id,
        code = ERR_NOT_INITIALIZED,
        message = "received '${req.method}' before 'initialized' notification",
      )
      return
    }
    when (req.method) {
      "initialize" -> handleInitialize(req)
      "renderNow" -> handleRenderNow(req)
      "shutdown" -> handleShutdown(req)
      "history/list" -> handleHistoryList(req)
      "history/read" -> handleHistoryRead(req)
      "history/diff" -> handleHistoryDiff(req)
      "history/prune" -> handleHistoryPrune(req)
      "data/fetch" -> handleDataFetch(req)
      "data/subscribe" -> handleDataSubscribe(req, subscribe = true)
      "data/unsubscribe" -> handleDataSubscribe(req, subscribe = false)
      "interactive/start" -> handleInteractiveStart(req)
      else ->
        sendErrorResponse(
          id = req.id,
          code = ERR_METHOD_NOT_FOUND,
          message = "method not found: ${req.method}",
        )
    }
  }

  private fun handleInitialize(req: JsonRpcRequest) {
    StartupTimings.mark("initialize received")
    val params =
      try {
        decodeParams(req.params, InitializeParams.serializer())
      } catch (e: Throwable) {
        sendErrorResponse(
          id = req.id,
          code = ERR_INVALID_PARAMS,
          message = "invalid initialize params: ${e.message}",
        )
        return
      }
    if (params.protocolVersion != PROTOCOL_VERSION) {
      sendErrorResponse(
        id = req.id,
        code = ERR_INVALID_REQUEST,
        message =
          "protocolVersion mismatch: client=${params.protocolVersion}, daemon=$PROTOCOL_VERSION",
      )
      // Per PROTOCOL.md § 3: daemon errors out on mismatch.
      shutdownRequested.set(true)
      running.set(false)
      return
    }
    // D1 — accept the client's `attachDataProducts` set, narrowed to kinds the registry
    // actually knows AND advertises as attachable. Anything outside that intersection is
    // silently dropped: pre-D2 daemons advertise nothing, so even an over-eager client falls
    // back to no global attachments rather than tripping `DataProductUnknown` on every
    // render.
    val attachableKinds = dataProducts.capabilities.filter { it.attachable }.map { it.kind }.toSet()
    globalAttachKinds =
      (params.options?.attachDataProducts ?: emptyList()).toSet().intersect(attachableKinds)
    val result =
      InitializeResult(
        protocolVersion = PROTOCOL_VERSION,
        daemonVersion = daemonVersion,
        pid = currentPid(),
        capabilities =
          ServerCapabilities(
            // B2.2 phase 2 — flipped to true when an [IncrementalDiscovery] is wired (the
            // production daemon-main path). In-process integration tests / fake-mode callers
            // that don't pass one still see false, matching the pre-phase-2 contract.
            incrementalDiscovery = incrementalDiscovery != null,
            sandboxRecycle = true,
            // Leak detection (B2.4) not wired yet — empty list = unavailable.
            leakDetection = emptyList<LeakDetectionMode>(),
            // D1 — advertised kinds. Empty when no producer was wired (pre-D2 default).
            dataProducts = dataProducts.capabilities,
            // INTERACTIVE.md § 9 — `true` when the host's `acquireInteractiveSession` returns a
            // real held-scene session (DesktopHost). `false` for hosts that inherit the throwing
            // default (FakeHost, RobolectricHost today) — clients fall back to v1 dispatch.
            interactive = host.supportsInteractive,
            // PROTOCOL.md § 3 — surface the daemon's `DeviceDimensions` catalog so clients can
            // build a `renderNow.overrides.device` picker without re-bundling the list. The
            // catalog itself lives in `:daemon:core/.../daemon/devices/DeviceDimensions.kt`;
            // we project each entry into a wire-friendly shape (id + dp dims + density) so
            // clients can render labels like "Pixel 5 — 393×851 dp @ 2.75x" without
            // re-resolving.
            knownDevices = buildKnownDevices(),
            // PROTOCOL.md § 3 — surface which `PreviewOverrides` fields this host actually
            // applies, so clients can grey out unsupported sliders and MCP can warn agents who
            // set fields the backend would silently ignore. Sorted for stable wire ordering;
            // pre-feature hosts inherit `emptySet()` from the interface, projected to `[]`.
            supportedOverrides = host.supportedOverrides.sorted(),
          ),
        // B2.1 — surface the authoritative SHA-256 to the client so VS Code can correlate later
        // `classpathDirty` notifications against the daemon's known-at-startup state. Empty
        // string when no fingerprint was wired (fake-mode / pre-B2.1 callers).
        classpathFingerprint = classpathSnapshot?.classpathHash ?: "",
        manifest =
          Manifest(
            // B2.2 phase 1 — the daemon owns its preview index now. Path is the absolute path of
            // the `previews.json` we loaded at startup ("" when no
            // `composeai.daemon.previewsJsonPath`
            // sysprop was supplied — fake-mode / in-process tests). B2.2 phase 2 keeps the count
            // live by mutating the index in-place from `discoveryUpdated` emissions; the path is
            // immutable for the daemon's lifetime.
            path = previewIndex.path?.toAbsolutePath()?.toString() ?: "",
            previewCount = previewIndex.size,
          ),
      )
    sendResponse(req.id, encode(InitializeResult.serializer(), result))
    StartupTimings.mark("initialize responded")
  }

  private fun handleRenderNow(req: JsonRpcRequest) {
    if (firstRenderNowSeen.compareAndSet(false, true)) {
      StartupTimings.mark("first renderNow received")
    }
    val params =
      try {
        decodeParams(req.params, RenderNowParams.serializer())
      } catch (e: Throwable) {
        sendErrorResponse(
          id = req.id,
          code = ERR_INVALID_PARAMS,
          message = "invalid renderNow params: ${e.message}",
        )
        return
      }
    if (classpathDirtyEmitted.get()) {
      // PROTOCOL.md § 5: after `classpathDirty` we refuse all further `renderNow` with the
      // documented error code, and the daemon proceeds toward exit.
      sendErrorResponse(
        id = req.id,
        code = ERR_CLASSPATH_DIRTY,
        message = "classpath fingerprint changed; daemon is exiting — re-bootstrap required",
      )
      return
    }
    if (shutdownRequested.get()) {
      sendErrorResponse(
        id = req.id,
        code = ERR_INTERNAL,
        message = "daemon is shutting down; not accepting new renderNow",
      )
      return
    }
    val queued = mutableListOf<String>()
    val rejected = mutableListOf<RejectedRender>()
    val now = System.currentTimeMillis()
    val overrides = params.overrides
    for (previewId in params.previews) {
      // Stub policy for B1.5: accept any non-blank id. UnknownPreview (-32004)
      // requires a real discovery set, which lands with B2.2.
      if (previewId.isBlank()) {
        rejected.add(RejectedRender(id = previewId, reason = "blank preview id"))
        continue
      }
      // Coalesce a slider-drag burst: if an override-bearing render is still in-flight for this
      // previewId, drop the new one and let the client resubmit on `renderFinished`. Without this
      // a fast drag fans out to N parallel sandbox renders that all serialise on the same slot.
      // No coalescing on plain (no-overrides) `renderNow` so the existing save-debounce loop is
      // unaffected.
      if (overrides != null && !previewIdsWithOverridesInFlight.add(previewId)) {
        rejected.add(
          RejectedRender(
            id = previewId,
            reason = "coalesced: override-bearing render already in flight for this previewId",
          )
        )
        continue
      }
      val hostId = RenderHost.nextRequestId()
      hostIdToPreviewId[hostId] = previewId
      acceptedAtMs[hostId] = now
      inFlightRenders.add(hostId)
      // Submit to host on a worker thread so we don't block the read loop.
      // submit() returns when the host returns a result; the watcher thread
      // demuxes the result back into renderFinished.
      submitRenderAsync(hostId, overrides)
      queued.add(previewId)
    }
    val result = RenderNowResult(queued = queued, rejected = rejected)
    sendResponse(req.id, encode(RenderNowResult.serializer(), result))
  }

  private fun submitRenderAsync(hostId: Long, overrides: PreviewOverrides? = null) {
    // Fire-and-forget: the watcher thread polls the result and emits the
    // notification. We use a fresh thread (cheap; we expect O(visible) renders
    // queued at a time) rather than a pool to keep wiring trivial — B1.4 will
    // revisit when it introduces a real RenderEngine.
    //
    // We propagate the protocol-level previewId via the existing
    // `RenderRequest.Render.payload` "previewId=<id>" channel (see
    // RenderHost.kt's KDoc on RenderRequest.payload, and FakeHost's
    // resolvePreviewId which reads the same convention). This lets fake-mode
    // harness backends — and any future host that needs to disambiguate
    // concurrent renders — recover the caller's preview id without widening
    // the RenderRequest shape. B-desktop.1.4 will replace this with a typed
    // field; until then this is the documented workaround.
    val previewId = hostIdToPreviewId[hostId] ?: ""
    val payload = encodeRenderPayload(previewId, overrides)
    Thread(
        {
          try {
            // 5-minute ceiling: covers cold sandbox bootstrap (~5–15s on
            // first render) plus B1.4's eventual real Compose render
            // (single-digit seconds). DaemonHost still uses its own 60s
            // default for direct callers; we override here because the
            // first render in a daemon's life sits behind the sandbox cold
            // boot.
            val raw =
              host.submit(
                RenderRequest.Render(id = hostId, payload = payload),
                timeoutMs = 5 * 60_000,
              )
            renderResultsQueue.put(raw)
          } catch (e: Throwable) {
            System.err.println("compose-ai-daemon: host.submit($hostId) failed: ${e.message}")
            renderResultsQueue.put(RenderResultOrFailure.Failure(hostId, e))
          }
        },
        "compose-ai-daemon-render-submit-$hostId",
      )
      .apply { isDaemon = true }
      .start()
  }

  /**
   * Encodes the host-bound `RenderRequest.Render.payload` string. Today this carries:
   *
   * - `previewId=<id>` — the discovery-time identifier the per-backend `PreviewManifestRouter`
   *   resolves into a [RenderSpec] before dispatch.
   * - Optional `widthPx`, `heightPx`, `density`, `localeTag`, `fontScale`, `uiMode`, `orientation`
   *   — the [PreviewOverrides] from this `renderNow` call. Routers preserve these when rewriting
   *   the payload, and they win over the manifest entry's per-preview defaults.
   *
   * `;`-delimited so the existing `RenderSpec.parseFromPayload(OrNull)` parsers read each pair
   * directly. See PROTOCOL.md § 5 (`renderNow.overrides`) for the wire-level shape.
   */
  private fun encodeRenderPayload(previewId: String, overrides: PreviewOverrides?): String {
    return buildString {
      if (previewId.isNotEmpty()) append("previewId=").append(previewId)
      if (overrides == null) return@buildString
      overrides.widthPx?.let {
        if (isNotEmpty()) append(';')
        append("widthPx=").append(it)
      }
      overrides.heightPx?.let {
        if (isNotEmpty()) append(';')
        append("heightPx=").append(it)
      }
      overrides.density?.let {
        if (isNotEmpty()) append(';')
        append("density=").append(it)
      }
      overrides.localeTag
        ?.takeIf { it.isNotBlank() }
        ?.let {
          if (isNotEmpty()) append(';')
          append("localeTag=").append(it)
        }
      overrides.fontScale?.let {
        if (isNotEmpty()) append(';')
        append("fontScale=").append(it)
      }
      overrides.uiMode?.let {
        if (isNotEmpty()) append(';')
        append("uiMode=")
        append(
          when (it) {
            UiMode.LIGHT -> "light"
            UiMode.DARK -> "dark"
          }
        )
      }
      overrides.orientation?.let {
        if (isNotEmpty()) append(';')
        append("orientation=")
        append(
          when (it) {
            Orientation.PORTRAIT -> "portrait"
            Orientation.LANDSCAPE -> "landscape"
          }
        )
      }
      overrides.device
        ?.takeIf { it.isNotBlank() }
        ?.let {
          if (isNotEmpty()) append(';')
          append("device=").append(it)
        }
    }
  }

  private val renderResultsQueue = LinkedBlockingQueue<Any>()

  private fun renderWatcherLoop() {
    while (true) {
      val item =
        try {
          renderResultsQueue.poll(200, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
          // We do not expect the watcher to be interrupted; treat as exit.
          Thread.currentThread().interrupt()
          return
        }
      if (item == null) {
        if (!running.get() && inFlightRenders.isEmpty()) return
        continue
      }
      when (item) {
        is RenderResult -> emitRenderFinished(item)
        is RenderResultOrFailure.Failure -> emitRenderFailed(item)
        else -> System.err.println("compose-ai-daemon: unexpected render result type: $item")
      }
    }
  }

  private fun emitRenderFinished(result: RenderResult) {
    val previewId = hostIdToPreviewId.remove(result.id) ?: result.id.toString()
    val acceptedAt = acceptedAtMs.remove(result.id) ?: System.currentTimeMillis()
    val now = System.currentTimeMillis()
    // Tier "fast" stub timing — actual wall-clock between accept and finish.
    sendNotification(
      "renderStarted",
      encode(
        RenderStartedParams.serializer(),
        RenderStartedParams(id = previewId, queuedMs = (now - acceptedAt).coerceAtLeast(0)),
      ),
    )
    // Pull `tookMs` from the host-supplied free-form metrics map. Hosts that actually time their
    // render bodies (DesktopHost via RenderEngine; the harness's FakeHost when its
    // `<previewId>.metrics.json` carries a `tookMs` entry) populate this; stub hosts return null
    // and we emit `tookMs = 0`. Other RenderMetrics fields (heap / native / sandbox-age) stay
    // null until B2.3 wires the cost-model collection path.
    val tookMs = result.metrics?.get("tookMs") ?: 0L
    val finished = renderFinishedFromResult(previewId, result, tookMs = tookMs)

    // INTERACTIVE.md § 5 / live-stream dedup: hash the rendered PNG bytes and compare against
    // the prior frame this preview emitted. On match, swap in `unchanged: true`, skip history,
    // and let the client short-circuit the file-read + base64 + repaint hop. Track always (not
    // just under interactive mode): a save that doesn't move pixels is just as redundant as an
    // input that doesn't change state.
    val frameHash = hashFrameBytes(result.pngPath)
    val isUnchanged =
      frameHash != null && lastFrameHashes[previewId] == frameHash && firstRenderFinishedSeen.get()
    val outboundFinished = if (isUnchanged) finished.copy(unchanged = true) else finished
    sendNotification("renderFinished", encode(RenderFinishedParams.serializer(), outboundFinished))
    if (firstRenderFinishedSeen.compareAndSet(false, true)) {
      StartupTimings.mark("first renderFinished sent")
      StartupTimings.summary()
    }
    if (frameHash != null) {
      lastFrameHashes[previewId] = frameHash
    }
    // H1 — record the render to history, if configured. Wrapped in a fail-open try/catch so a
    // history write failure never blocks the renderFinished wire-format. The render's notification
    // has already been sent above; history is observation, not state.
    //
    // Skip history when the frame is byte-identical to the prior one for this preview — there's
    // nothing new to archive, and a duplicate sidecar would inflate `history/list` results
    // without changing what the user sees on disk.
    if (!isUnchanged) {
      recordHistoryForRender(previewId = previewId, result = result, finished = finished)
    }
    inFlightRenders.remove(result.id)
    previewIdsWithOverridesInFlight.remove(previewId)
    // D3 — wake any `data/fetch` waiter that queued this render. The waiter re-invokes
    // `dataProducts.fetch` to materialise the payload. We complete the future regardless of
    // dedup (`unchanged: true`): the producer's payload may have changed even when the bytes
    // didn't (an a11y projection re-computed against the same view tree, etc.).
    dataFetchWaiters.remove(result.id)?.complete(RerenderOutcome.Ok(previewId))
    // Save-after-render invariant: a `fileChanged({kind:source})` queued discovery work that has
    // been waiting for this render to ship. The writer queue is FIFO and `renderFinished` is
    // already in it — kicking the discovery scan now guarantees its `discoveryUpdated`
    // notification (if any) lands strictly after the render the user just saw.
    drainPendingDiscovery()
  }

  /**
   * H1 — reads the render's PNG bytes off disk (when [RenderResult.pngPath] is non-null and the
   * file exists), sha256s them, and writes a sidecar + index entry via [historyManager]. Emits
   * `historyAdded` after the entry has been persisted.
   *
   * Skips when:
   * - [historyManager] is null or disabled (the pre-H1 default for in-process tests).
   * - `result.pngPath` is null or the file doesn't exist (B1.5-era stub hosts; the daemon-stub
   *   placeholder path that was never written to disk).
   *
   * Failures are logged to stderr and swallowed. The render itself is unaffected.
   */
  private fun recordHistoryForRender(
    previewId: String,
    result: RenderResult,
    finished: RenderFinishedParams,
  ) {
    val mgr = historyManager ?: return
    if (!mgr.isEnabled) return
    val pngPath = result.pngPath ?: return
    val pngFile = Path.of(pngPath)
    if (!Files.exists(pngFile)) {
      // Stub-host path — pngPath is the deterministic `daemon-stub-${id}.png` placeholder that
      // never actually lands on disk. Skip silently; this is the pre-H1 behaviour for stub hosts.
      return
    }
    val pngBytes =
      try {
        Files.readAllBytes(pngFile)
      } catch (t: Throwable) {
        System.err.println(
          "compose-ai-daemon: history: failed to read PNG bytes for $previewId at $pngPath " +
            "(${t.javaClass.simpleName}: ${t.message}); skipping history entry"
        )
        return
      }
    val previewMetadata =
      previewIndex.byId(previewId)?.let {
        PreviewMetadataSnapshot(
          displayName = it.displayName,
          group = it.group,
          sourceFile = it.sourceFile,
          config = null,
        )
      }
    val entry =
      try {
        mgr.recordRender(
          previewId = previewId,
          pngBytes = pngBytes,
          trigger = "renderNow",
          triggerDetail = null,
          renderTookMs = finished.tookMs,
          metrics = finished.metrics,
          previewMetadata = previewMetadata,
        )
      } catch (t: Throwable) {
        System.err.println(
          "compose-ai-daemon: history: HistoryManager.recordRender($previewId) threw " +
            "(${t.javaClass.simpleName}: ${t.message}); continuing"
        )
        return
      }
    if (entry != null) {
      sendNotification(
        "historyAdded",
        encode(
          HistoryAddedParams.serializer(),
          HistoryAddedParams(entry = encodeHistoryEntry(entry)),
        ),
      )
    }
  }

  private fun encodeHistoryEntry(entry: HistoryEntry): JsonElement =
    json.encodeToJsonElement(HistoryEntry.serializer(), entry)

  private fun handleHistoryList(req: JsonRpcRequest) {
    val params =
      try {
        decodeParams(req.params, HistoryListParams.serializer())
      } catch (e: Throwable) {
        sendErrorResponse(
          id = req.id,
          code = ERR_INVALID_PARAMS,
          message = "invalid history/list params: ${e.message}",
        )
        return
      }
    val mgr = historyManager
    if (mgr == null || !mgr.isEnabled) {
      sendResponse(
        req.id,
        encode(
          HistoryListResult.serializer(),
          HistoryListResult(entries = emptyList(), nextCursor = null, totalCount = 0),
        ),
      )
      return
    }
    val filter =
      HistoryFilter(
        previewId = params.previewId,
        since = params.since,
        until = params.until,
        limit = params.limit,
        cursor = params.cursor,
        branch = params.branch,
        branchPattern = params.branchPattern,
        commit = params.commit,
        worktreePath = params.worktreePath,
        agentId = params.agentId,
        sourceKind = params.sourceKind,
        sourceId = params.sourceId,
      )
    val page =
      try {
        mgr.list(filter)
      } catch (t: Throwable) {
        sendErrorResponse(
          id = req.id,
          code = ERR_INTERNAL,
          message = "history/list failed: ${t.message}",
        )
        return
      }
    val result =
      HistoryListResult(
        entries = page.entries.map { encodeHistoryEntry(it) },
        nextCursor = page.nextCursor,
        totalCount = page.totalCount,
      )
    sendResponse(req.id, encode(HistoryListResult.serializer(), result))
  }

  private fun handleHistoryRead(req: JsonRpcRequest) {
    val params =
      try {
        decodeParams(req.params, HistoryReadParams.serializer())
      } catch (e: Throwable) {
        sendErrorResponse(
          id = req.id,
          code = ERR_INVALID_PARAMS,
          message = "invalid history/read params: ${e.message}",
        )
        return
      }
    val mgr = historyManager
    if (mgr == null || !mgr.isEnabled) {
      sendErrorResponse(
        id = req.id,
        code = ERR_HISTORY_ENTRY_NOT_FOUND,
        message = "history not configured",
      )
      return
    }
    val read =
      try {
        mgr.read(params.id, includeBytes = params.inline)
      } catch (t: Throwable) {
        sendErrorResponse(
          id = req.id,
          code = ERR_INTERNAL,
          message = "history/read failed: ${t.message}",
        )
        return
      }
    if (read == null) {
      sendErrorResponse(
        id = req.id,
        code = ERR_HISTORY_ENTRY_NOT_FOUND,
        message = "history entry not found: ${params.id}",
      )
      return
    }
    val previewMetadataElem =
      read.previewMetadata?.let {
        json.encodeToJsonElement(PreviewMetadataSnapshot.serializer(), it)
      }
    val pngBase64 = read.pngBytes?.let { Base64.getEncoder().encodeToString(it) }
    val dto =
      HistoryReadResultDto(
        entry = encodeHistoryEntry(read.entry),
        previewMetadata = previewMetadataElem,
        pngPath = read.pngPath,
        pngBytes = pngBase64,
      )
    sendResponse(req.id, encode(HistoryReadResultDto.serializer(), dto))
  }

  /**
   * H3 — `history/diff` metadata mode. Resolves [from] and [to] entry ids via the [historyManager]
   * (which iterates configured sources in priority order, so a cross-source diff "LocalFs vs GitRef
   * preview/main" works the same as an intra-source diff). Emits:
   *
   * - `HistoryEntryNotFound` (-32010) when either id is missing.
   * - `HistoryDiffMismatch` (-32011) when the two entries belong to different previews.
   * - `ERR_HISTORY_PIXEL_NOT_IMPLEMENTED` (-32012) when the caller asks for `mode = pixel` (H5).
   *
   * The metadata-mode response is `pngHashChanged + fromMetadata + toMetadata` (full sidecars).
   * Pixel-mode fields (`diffPx`, `ssim`, `diffPngPath`) stay null in METADATA mode by design — H5
   * lands the pixel pass.
   */
  private fun handleHistoryDiff(req: JsonRpcRequest) {
    val params =
      try {
        decodeParams(req.params, HistoryDiffParams.serializer())
      } catch (e: Throwable) {
        sendErrorResponse(
          id = req.id,
          code = ERR_INVALID_PARAMS,
          message = "invalid history/diff params: ${e.message}",
        )
        return
      }
    val mgr = historyManager
    if (mgr == null || !mgr.isEnabled) {
      sendErrorResponse(
        id = req.id,
        code = ERR_HISTORY_ENTRY_NOT_FOUND,
        message = "history not configured",
      )
      return
    }
    if (params.mode == HistoryDiffMode.PIXEL) {
      sendErrorResponse(
        id = req.id,
        code = ERR_HISTORY_PIXEL_NOT_IMPLEMENTED,
        message =
          "history/diff: pixel mode is reserved for phase H5 and not implemented; " +
            "call with mode='metadata' (the default) for now",
      )
      return
    }
    val from =
      try {
        mgr.read(params.from, includeBytes = false)
      } catch (t: Throwable) {
        sendErrorResponse(
          id = req.id,
          code = ERR_INTERNAL,
          message = "history/diff: read(${params.from}) failed: ${t.message}",
        )
        return
      }
    if (from == null) {
      sendErrorResponse(
        id = req.id,
        code = ERR_HISTORY_ENTRY_NOT_FOUND,
        message = "history entry not found: ${params.from}",
      )
      return
    }
    val to =
      try {
        mgr.read(params.to, includeBytes = false)
      } catch (t: Throwable) {
        sendErrorResponse(
          id = req.id,
          code = ERR_INTERNAL,
          message = "history/diff: read(${params.to}) failed: ${t.message}",
        )
        return
      }
    if (to == null) {
      sendErrorResponse(
        id = req.id,
        code = ERR_HISTORY_ENTRY_NOT_FOUND,
        message = "history entry not found: ${params.to}",
      )
      return
    }
    if (from.entry.previewId != to.entry.previewId) {
      sendErrorResponse(
        id = req.id,
        code = ERR_HISTORY_DIFF_MISMATCH,
        message =
          "history/diff: from.previewId='${from.entry.previewId}' but " +
            "to.previewId='${to.entry.previewId}'; pixel diff would be meaningless",
      )
      return
    }
    val result =
      HistoryDiffResult(
        pngHashChanged = from.entry.pngHash != to.entry.pngHash,
        fromMetadata = encodeHistoryEntry(from.entry),
        toMetadata = encodeHistoryEntry(to.entry),
        diffPx = null,
        ssim = null,
        diffPngPath = null,
      )
    sendResponse(req.id, encode(HistoryDiffResult.serializer(), result))
  }

  /**
   * H4 — `history/prune` manual prune trigger. Resolves [HistoryPruneParams] over the daemon's
   * configured defaults (explicit param wins; null leaves the default). When
   * [HistoryPruneParams.dryRun] is true, returns the would-remove set without touching disk and
   * does NOT emit a `historyPruned` notification. Otherwise mutates and (if non-empty) emits
   * `historyPruned` with `reason: "manual"`.
   *
   * See HISTORY.md § "Pruning policy" for the order-of-passes contract.
   */
  private fun handleHistoryPrune(req: JsonRpcRequest) {
    val params =
      try {
        decodeParams(req.params, HistoryPruneParams.serializer())
      } catch (e: Throwable) {
        sendErrorResponse(
          id = req.id,
          code = ERR_INVALID_PARAMS,
          message = "invalid history/prune params: ${e.message}",
        )
        return
      }
    val mgr = historyManager
    if (mgr == null || !mgr.isEnabled) {
      // No history is configured → returns an empty result rather than a hard error. Mirrors how
      // history/list degrades: the consumer asked "did anything get pruned?" and the answer is
      // honestly "no" because nothing's recorded.
      sendResponse(
        req.id,
        encode(
          HistoryPruneResult.serializer(),
          HistoryPruneResult(
            removedEntries = emptyList(),
            freedBytes = 0L,
            sourceResults = emptyMap(),
          ),
        ),
      )
      return
    }
    // Compose effective config from the manager's defaults + per-call overrides.
    val baseConfig = mgr.pruneConfig
    val effective =
      HistoryPruneConfig(
        maxEntriesPerPreview = params.maxEntriesPerPreview ?: baseConfig.maxEntriesPerPreview,
        maxAgeDays = params.maxAgeDays ?: baseConfig.maxAgeDays,
        maxTotalSizeBytes = params.maxTotalSizeBytes ?: baseConfig.maxTotalSizeBytes,
        autoPruneIntervalMs = baseConfig.autoPruneIntervalMs,
      )
    val aggregate =
      try {
        mgr.pruneNow(
          config = effective,
          dryRun = params.dryRun,
          reason = if (params.dryRun) null else PruneReason.MANUAL,
        )
      } catch (t: Throwable) {
        sendErrorResponse(
          id = req.id,
          code = ERR_INTERNAL,
          message = "history/prune failed: ${t.message}",
        )
        return
      }
    val perSource =
      aggregate.sourceResults.mapValues { (_, r) ->
        HistoryPruneSourceResult(removedEntryIds = r.removedEntryIds, freedBytes = r.freedBytes)
      }
    sendResponse(
      req.id,
      encode(
        HistoryPruneResult.serializer(),
        HistoryPruneResult(
          removedEntries = aggregate.removedEntryIds,
          freedBytes = aggregate.freedBytes,
          sourceResults = perSource,
        ),
      ),
    )
  }

  private fun emitRenderFailed(failure: RenderResultOrFailure.Failure) {
    val previewId = hostIdToPreviewId.remove(failure.hostId) ?: failure.hostId.toString()
    acceptedAtMs.remove(failure.hostId)
    inFlightRenders.remove(failure.hostId)
    previewIdsWithOverridesInFlight.remove(previewId)
    // D3 — wake any data/fetch waiter that queued this render so it can return
    // DataProductFetchFailed rather than ride out its budget.
    dataFetchWaiters
      .remove(failure.hostId)
      ?.complete(
        RerenderOutcome.Failed(failure.cause.message ?: failure.cause.javaClass.simpleName)
      )
    // Minimal renderFailed; B1.4 widens this to the real RenderError shape.
    val payload = buildJsonObject {
      put("id", JsonPrimitive(previewId))
      put(
        "error",
        buildJsonObject {
          put("kind", JsonPrimitive("internal"))
          put("message", JsonPrimitive(failure.cause.message ?: failure.cause.javaClass.name))
        },
      )
    }
    sendNotification("renderFailed", payload)
  }

  /**
   * Builds the `renderFinished` payload from a host-returned [RenderResult]. For B1.5-era stub
   * hosts (no `pngPath` on the result) we emit a deterministic placeholder PNG path; the file is
   * **not** written to disk by the server. Hosts that actually produce bytes (e.g. `FakeHost` from
   * `:daemon:harness`, or `DesktopHost`/`RobolectricHost` once their real-render bodies land)
   * populate `pngPath` and we forward that string verbatim.
   *
   * `tookMs` is sourced from `result.metrics["tookMs"]` upstream by [emitRenderFinished]; stub
   * hosts that don't time their bodies pass `0L`.
   *
   * **B2.3 — structured [RenderMetrics].** When the host populates the four B2.3 keys
   * (`heapAfterGcMb`, `nativeHeapMb`, `sandboxAgeRenders`, `sandboxAgeMs`) on `result.metrics`,
   * [RenderMetrics.fromFlatMap] translates them into a structured [RenderMetrics] for the wire. A
   * partial map (some but not all four keys) emits a warn-level `log` notification so drift is
   * observable, and we still emit `metrics: null` — half-populated objects are misleading because
   * callers cannot tell "field was zero" from "field was missing". Hosts that return `null` metrics
   * (the B1.5-era stub hosts that don't measure anything) keep the pre-B2.3 `metrics: null`
   * behaviour.
   */
  /**
   * SHA-256 hex of the bytes at [pngPath], or null when the file is unreadable / missing / a
   * B1.5-era stub placeholder. Cheap on the typical preview size (sub-MB PNGs); the cost is
   * dominated by the file read which is already in OS page cache because the daemon just wrote it.
   * Returning null when the file isn't there is the right call — without bytes we can't dedup, so
   * we treat it as "definitely changed" and emit normally.
   */
  private fun hashFrameBytes(pngPath: String?): String? {
    if (pngPath == null) return null
    val path =
      try {
        Path.of(pngPath)
      } catch (_: Throwable) {
        return null
      }
    if (!Files.exists(path)) return null
    val bytes =
      try {
        Files.readAllBytes(path)
      } catch (_: Throwable) {
        return null
      }
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    return buildString(digest.size * 2) {
      for (b in digest) {
        val v = b.toInt() and 0xFF
        if (v < 0x10) append('0')
        append(v.toString(16))
      }
    }
  }

  private fun renderFinishedFromResult(
    previewId: String,
    result: RenderResult,
    tookMs: Long,
  ): RenderFinishedParams {
    val pngPath = result.pngPath ?: "$historyDir/daemon-stub-${result.id}.png"
    val metrics =
      when (val outcome = RenderMetrics.fromFlatMap(result.metrics)) {
        is RenderMetrics.FromFlatMapResult.AbsentSource -> null
        is RenderMetrics.FromFlatMapResult.PartialMap -> {
          // Drift signal — host emitted some but not all of the B2.3 keys. Warn so the caller
          // side observes the gap; still emit `metrics: null` because half-populated objects are
          // ambiguous on the wire.
          System.err.println(
            "compose-ai-daemon: RenderMetrics partial map for previewId='$previewId' " +
              "(missing keys: ${outcome.missingKeys.joinToString(",")}); emitting metrics=null"
          )
          null
        }
        is RenderMetrics.FromFlatMapResult.Populated -> outcome.metrics
      }
    // D1 — pull attachments from the registry for the union of (per-preview) subscribed kinds
    // and the global attach set. The registry returns an empty list for kinds that didn't land
    // anything for this render (e.g. an a11y producer on a render whose mode skipped a11y), so
    // a missing attachment never blocks the renderFinished. We omit the field entirely when the
    // resulting list is empty so pre-D1 fixtures keep round-tripping.
    val requestedKinds: Set<String> =
      (subscriptions[previewId]?.toSet() ?: emptySet()) + globalAttachKinds
    val attachments: List<DataProductAttachment> =
      if (requestedKinds.isEmpty()) emptyList()
      else dataProducts.attachmentsFor(previewId, requestedKinds)
    return RenderFinishedParams(
      id = previewId,
      pngPath = pngPath,
      tookMs = tookMs,
      metrics = metrics,
      dataProducts = attachments.takeIf { it.isNotEmpty() },
    )
  }

  // --------------------------------------------------------------------------
  // D1 — data product handlers. See docs/daemon/DATA-PRODUCTS.md.
  // --------------------------------------------------------------------------

  private fun handleDataFetch(req: JsonRpcRequest) {
    val params =
      try {
        decodeParams(req.params, DataFetchParams.serializer())
      } catch (e: Throwable) {
        sendErrorResponse(
          id = req.id,
          code = ERR_INVALID_PARAMS,
          message = "invalid data/fetch params: ${e.message}",
        )
        return
      }
    val outcome = dataProducts.fetch(params.previewId, params.kind, params.params, params.inline)
    if (outcome is DataProductRegistry.Outcome.RequiresRerender) {
      // D3 — kick the re-render off the read loop so other notifications / requests aren't
      // blocked while we wait out the budget. The worker thread submits the render, parks on a
      // future the watcher loop completes, then re-asks the registry for the payload.
      handleDataFetchWithRerender(req, params, outcome.mode)
      return
    }
    sendDataFetchOutcome(req, params, outcome)
  }

  /**
   * D3 — handler for the [DataProductRegistry.Outcome.RequiresRerender] branch. Spawns a worker
   * thread (mirrors [submitRenderAsync]'s "fresh thread per call" model — we expect at most a few
   * concurrent fetch-driven re-renders from a UI panel) that:
   *
   * 1. Submits a [RenderRequest.Render] keyed by a fresh host id, payload tagged with the required
   *    `mode=<mode>`. The submit goes through the regular [host] path so the watcher loop emits
   *    `renderStarted`/`renderFinished` exactly as for a `renderNow`-driven render — the panel UI
   *    repaints if the PNG changed (DATA-PRODUCTS.md:291-293).
   * 2. Parks on a [java.util.concurrent.CompletableFuture] keyed by that host id, with the
   *    per-request budget ([dataFetchRerenderBudgetMs]) as the deadline. The future is completed by
   *    [emitRenderFinished] / [emitRenderFailed] when the watcher processes the result.
   * 3. On `Ok`, re-invokes `dataProducts.fetch(...)` and routes the resulting [Outcome] to the wire
   *    via [sendDataFetchOutcome]. On `Failed`, returns `DataProductFetchFailed`. On budget
   *    timeout, returns `DataProductBudgetExceeded`; the render itself keeps going and the waiter
   *    entry is leaked into `dataFetchWaiters` until the watcher finally clears it (PROTOCOL.md § 8
   *    — no mid-render cancellation).
   */
  private fun handleDataFetchWithRerender(
    req: JsonRpcRequest,
    params: DataFetchParams,
    mode: String,
  ) {
    val hostId = RenderHost.nextRequestId()
    val previewId = params.previewId
    val future = java.util.concurrent.CompletableFuture<RerenderOutcome>()
    dataFetchWaiters[hostId] = future
    hostIdToPreviewId[hostId] = previewId
    acceptedAtMs[hostId] = System.currentTimeMillis()
    inFlightRenders.add(hostId)
    val payload = encodeRenderPayloadWithMode(previewId, mode)
    Thread(
        {
          // Submit goes through the same render thread as renderNow but on a worker so the
          // budget timer below is what bounds wall-clock — not the host's submit timeout.
          submitRerenderForFetch(hostId, payload)
          val budgetMs = dataFetchRerenderBudgetMs.coerceAtLeast(1L)
          val outcome =
            try {
              future.get(budgetMs, TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
              null
            } catch (t: Throwable) {
              RerenderOutcome.Failed(t.message ?: t.javaClass.simpleName)
            }
          when (outcome) {
            null -> {
              // Budget tripped. Don't cancel the render; just stop waiting. The watcher loop
              // will still fire `renderFinished`/`renderFailed` when the host completes the
              // render, and `dataFetchWaiters` is cleared at that point.
              sendErrorResponse(
                id = req.id,
                code = ERR_DATA_PRODUCT_BUDGET_EXCEEDED,
                message =
                  "data/fetch: re-render budget (${budgetMs}ms) exceeded for kind " +
                    "'${params.kind}'",
              )
            }
            is RerenderOutcome.Failed -> {
              sendErrorResponse(
                id = req.id,
                code = ERR_DATA_PRODUCT_FETCH_FAILED,
                message =
                  "data/fetch: re-render failed for kind '${params.kind}': ${outcome.message}",
              )
            }
            is RerenderOutcome.Ok -> {
              // Re-ask the registry. By this point the renderer has produced the artefact for
              // the requested mode, so a real producer should return `Ok` (or `FetchFailed` if
              // its projection step blew up). A second `RequiresRerender` is treated as a
              // producer bug — we don't recurse, we surface a fetch-failed so callers see it.
              val secondOutcome =
                try {
                  dataProducts.fetch(params.previewId, params.kind, params.params, params.inline)
                } catch (t: Throwable) {
                  DataProductRegistry.Outcome.FetchFailed(
                    "registry threw on post-rerender fetch: ${t.message ?: t.javaClass.simpleName}"
                  )
                }
              if (secondOutcome is DataProductRegistry.Outcome.RequiresRerender) {
                sendErrorResponse(
                  id = req.id,
                  code = ERR_DATA_PRODUCT_FETCH_FAILED,
                  message =
                    "data/fetch: producer requested a second re-render for kind " +
                      "'${params.kind}' after one already landed (mode='${secondOutcome.mode}')",
                )
              } else {
                sendDataFetchOutcome(req, params, secondOutcome)
              }
            }
          }
        },
        "compose-ai-daemon-data-fetch-$hostId",
      )
      .apply { isDaemon = true }
      .start()
  }

  /**
   * Submits the fetch-driven re-render onto the host. Mirrors [submitRenderAsync] but takes a
   * pre-encoded payload (with `mode=<mode>`) and routes failures into [renderResultsQueue] so the
   * watcher loop's existing `renderFailed` path handles them — and so the [dataFetchWaiters] future
   * is woken via [emitRenderFailed]'s completion call rather than by this thread.
   */
  private fun submitRerenderForFetch(hostId: Long, payload: String) {
    Thread(
        {
          try {
            val raw =
              host.submit(
                RenderRequest.Render(id = hostId, payload = payload),
                timeoutMs = 5 * 60_000,
              )
            renderResultsQueue.put(raw)
          } catch (e: Throwable) {
            System.err.println(
              "compose-ai-daemon: data/fetch host.submit($hostId) failed: ${e.message}"
            )
            renderResultsQueue.put(RenderResultOrFailure.Failure(hostId, e))
          }
        },
        "compose-ai-daemon-data-fetch-submit-$hostId",
      )
      .apply { isDaemon = true }
      .start()
  }

  /**
   * D3 — encodes a [RenderRequest.Render.payload] with `previewId=<id>;mode=<mode>`. The `mode` key
   * is consumed renderer-side (D2 / `renderer-android`) to pick the smallest render configuration
   * that produces the requested kind — the daemon stays kind-agnostic and just forwards the
   * producer-supplied tag. Fake-mode hosts (the test harness's `FakeHost`, `FakeRenderHost` in unit
   * tests) ignore unknown payload keys, so this is forward-compatible.
   */
  private fun encodeRenderPayloadWithMode(previewId: String, mode: String): String = buildString {
    if (previewId.isNotEmpty()) append("previewId=").append(previewId)
    if (mode.isNotEmpty()) {
      if (isNotEmpty()) append(';')
      append("mode=").append(mode)
    }
  }

  private fun sendDataFetchOutcome(
    req: JsonRpcRequest,
    params: DataFetchParams,
    outcome: DataProductRegistry.Outcome,
  ) {
    when (outcome) {
      is DataProductRegistry.Outcome.Ok ->
        sendResponse(req.id, encode(DataFetchResult.serializer(), outcome.result))
      DataProductRegistry.Outcome.Unknown ->
        sendErrorResponse(
          id = req.id,
          code = ERR_DATA_PRODUCT_UNKNOWN,
          message = "data/fetch: kind not advertised: ${params.kind}",
        )
      DataProductRegistry.Outcome.NotAvailable ->
        sendErrorResponse(
          id = req.id,
          code = ERR_DATA_PRODUCT_NOT_AVAILABLE,
          message = "data/fetch: preview '${params.previewId}' has no render available",
        )
      is DataProductRegistry.Outcome.FetchFailed ->
        sendErrorResponse(
          id = req.id,
          code = ERR_DATA_PRODUCT_FETCH_FAILED,
          message = "data/fetch: ${outcome.message}",
        )
      DataProductRegistry.Outcome.BudgetExceeded ->
        sendErrorResponse(
          id = req.id,
          code = ERR_DATA_PRODUCT_BUDGET_EXCEEDED,
          message = "data/fetch: re-render budget exceeded for kind '${params.kind}'",
        )
      is DataProductRegistry.Outcome.RequiresRerender ->
        // Should never reach here — the dispatch layer handles this branch upstream. Surface
        // as fetch-failed if it does so the caller sees a clean error rather than a hang.
        sendErrorResponse(
          id = req.id,
          code = ERR_DATA_PRODUCT_FETCH_FAILED,
          message =
            "data/fetch: registry returned RequiresRerender(${outcome.mode}) outside the " +
              "re-render dispatch path for kind '${params.kind}'",
        )
    }
  }

  /**
   * Handles `data/subscribe` (when [subscribe] is true) and `data/unsubscribe` (false) — they share
   * the same params shape, so the dispatch path is folded together. Idempotent on both sides:
   * re-subscribing returns ok, unsubscribing a kind that was never subscribed also returns ok.
   * Validates the kind against the registry on subscribe so unattachable / unknown kinds fail fast.
   */
  private fun handleDataSubscribe(req: JsonRpcRequest, subscribe: Boolean) {
    val method = if (subscribe) "data/subscribe" else "data/unsubscribe"
    val params =
      try {
        decodeParams(req.params, DataSubscribeParams.serializer())
      } catch (e: Throwable) {
        sendErrorResponse(
          id = req.id,
          code = ERR_INVALID_PARAMS,
          message = "invalid $method params: ${e.message}",
        )
        return
      }
    if (subscribe) {
      val capability = dataProducts.capabilities.firstOrNull { it.kind == params.kind }
      if (capability == null || !capability.attachable) {
        sendErrorResponse(
          id = req.id,
          code = ERR_DATA_PRODUCT_UNKNOWN,
          message =
            if (capability == null) "$method: kind not advertised: ${params.kind}"
            else "$method: kind '${params.kind}' is not attachable",
        )
        return
      }
      subscriptions
        .computeIfAbsent(params.previewId) { ConcurrentHashMap.newKeySet() }
        .add(params.kind)
      // Notify producers AFTER bookkeeping so a producer that throws during onSubscribe still
      // leaves the dispatcher state consistent with the client's view (a subsequent unsubscribe
      // will clear it). Re-subscribes pass the latest `params` through verbatim — producers that
      // care about reset-on-re-subscribe handle that themselves.
      dataProducts.onSubscribe(params.previewId, params.kind, params.params)
    } else {
      val existed = subscriptions[params.previewId]?.remove(params.kind) == true
      // Drop the inner set when its last subscription cleared so the bookkeeping doesn't
      // accumulate empty entries for previews the user has cycled through.
      subscriptions.computeIfPresent(params.previewId) { _, v -> if (v.isEmpty()) null else v }
      // Notify the producer — but only if there was actually a live subscription. Calling
      // onUnsubscribe for a never-subscribed pair would invite producers to log spurious
      // "no such subscription" warnings.
      if (existed) dataProducts.onUnsubscribe(params.previewId, params.kind)
    }
    sendResponse(req.id, encode(DataSubscribeResult.serializer(), DataSubscribeResult.OK))
  }

  /**
   * Per the design doc — subscriptions are sticky-while-visible only. When the client sends a fresh
   * `setVisible` set, drop subscription state for previews that fell out of it. The UI is invited
   * to re-subscribe when the preview returns to view rather than the daemon retaining state for
   * unseen cards.
   *
   * Notifies the registry via [DataProductRegistry.onUnsubscribe] for every dropped pair so
   * producers with per-subscription state (`compose/recomposition`'s delta counters, etc.) can tear
   * down even when the client doesn't send an explicit `data/unsubscribe`.
   */
  private fun pruneSubscriptionsToVisible(visible: Set<String>) {
    val toDrop = subscriptions.keys - visible
    for (id in toDrop) {
      val droppedKinds = subscriptions.remove(id) ?: continue
      for (kind in droppedKinds) dataProducts.onUnsubscribe(id, kind)
    }
  }

  // --------------------------------------------------------------------------
  // Interactive (live-stream) mode — docs/daemon/INTERACTIVE.md § 8.
  //
  // Multi-target on the wire: the server keeps a `streamId → (previewId, streamId)` map and
  // each `interactive/start` registers a fresh slot. Concurrent streams targeting different
  // (or even the same) preview ids coexist; inputs route by `frameStreamId` so a stop on
  // one stream leaves the others untouched. Inputs are fire-and-forget — we synthesise an
  // internal `renderNow` against the target preview so the client gets a fresh
  // `renderFinished` (subject to the byte-identical dedup applied in [emitRenderFinished]).
  // --------------------------------------------------------------------------

  private fun handleInteractiveStart(req: JsonRpcRequest) {
    val params =
      try {
        decodeParams(
          req.params,
          ee.schimke.composeai.daemon.protocol.InteractiveStartParams.serializer(),
        )
      } catch (e: Throwable) {
        sendErrorResponse(
          id = req.id,
          code = ERR_INVALID_PARAMS,
          message = "invalid interactive/start params: ${e.message}",
        )
        return
      }
    if (params.previewId.isBlank()) {
      sendErrorResponse(
        id = req.id,
        code = ERR_INVALID_PARAMS,
        message = "interactive/start: previewId is blank",
      )
      return
    }
    val streamId = "stream-${nextStreamIdCounter.getAndIncrement()}"
    // v2 — try to allocate a held InteractiveSession. Hosts that don't support interactive mode
    // (FakeHost, the v1-era hosts that haven't overridden the default) throw
    // UnsupportedOperationException, which we silently catch and fall through to the v1 dispatch
    // path. We do NOT propagate that failure to the wire as MethodNotFound: the v1 contract is
    // "interactive/start always succeeds and returns a streamId; inputs route through whatever
    // path the host supports". A v2-aware client gets the held-state semantics; a v1 client
    // doesn't notice the difference. See INTERACTIVE.md § 9 for the rollout rationale.
    val session: InteractiveSession? =
      try {
        val classLoader =
          host.userClassloaderHolder?.currentChildLoader()
            ?: this::class.java.classLoader
            ?: ClassLoader.getSystemClassLoader()
        host.acquireInteractiveSession(params.previewId, classLoader)
      } catch (_: UnsupportedOperationException) {
        null
      } catch (t: Throwable) {
        // Host overrode the method but failed to allocate (e.g. preview class not found, scene
        // setup threw). Surface as a log notification — the wire-level start still succeeds with
        // the v1 fall-back path so the client gets a usable streamId.
        System.err.println(
          "compose-ai-daemon: interactive/start: acquireInteractiveSession failed for " +
            "previewId='${params.previewId}': ${t.javaClass.simpleName}: ${t.message}; " +
            "falling back to v1 dispatch"
        )
        null
      }
    interactiveTargets[streamId] =
      InteractiveTarget(previewId = params.previewId, frameStreamId = streamId)
    if (session != null) interactiveSessions[streamId] = session
    // Wipe any cached hash for this preview so the first interactive frame always paints.
    // Without this a `start` after a previous identical render would suppress the bootstrap
    // frame and the client's LIVE chip would have nothing to paint until the user clicks.
    // Per-preview wipe (not per-stream): two streams targeting the same preview both want a
    // fresh first frame, and the dedup state is shared by design.
    lastFrameHashes.remove(params.previewId)
    sendResponse(
      req.id,
      encode(
        ee.schimke.composeai.daemon.protocol.InteractiveStartResult.serializer(),
        ee.schimke.composeai.daemon.protocol.InteractiveStartResult(frameStreamId = streamId),
      ),
    )
  }

  private fun handleInteractiveStop(
    params: ee.schimke.composeai.daemon.protocol.InteractiveStopParams
  ) {
    // Idempotent on stale or unknown stream ids per INTERACTIVE.md § 8 — `remove` is a no-op
    // when the key isn't present, which is exactly the contract we want.
    interactiveTargets.remove(params.frameStreamId)
    // Release any held v2 session. close() is idempotent; failures from a host's session
    // teardown shouldn't propagate to the wire (the stop notification is fire-and-forget).
    interactiveSessions.remove(params.frameStreamId)?.let { session ->
      try {
        session.close()
      } catch (t: Throwable) {
        System.err.println(
          "compose-ai-daemon: interactive/stop: session.close() threw " +
            "(${t.javaClass.simpleName}: ${t.message}); continuing"
        )
      }
    }
  }

  private fun handleInteractiveInput(
    params: ee.schimke.composeai.daemon.protocol.InteractiveInputParams
  ) {
    val target =
      interactiveTargets[params.frameStreamId]
        ?: return // Stale or unknown stream id (never started, already stopped, or typo'd
    // by a programmatic client). Drop silently — resending after stop is a documented
    // client-allowed pattern.
    if (classpathDirtyEmitted.get() || shutdownRequested.get()) {
      return
    }
    val session = interactiveSessions[params.frameStreamId]
    if (session != null) {
      // v2 dispatch path — feed the input into the held composition, render the next frame
      // through the same session. Coalesce: if a render for this stream is already in flight,
      // queue the input rather than spawning another render — the in-flight worker drains the
      // queue and dispatches everything as a batch before its render. See § 9.6 of the design.
      pendingInteractiveInputs
        .computeIfAbsent(params.frameStreamId) { java.util.concurrent.ConcurrentLinkedQueue() }
        .add(params)
      // Try to claim the in-flight slot. Whoever wins owns the render thread; whoever loses
      // already had their input queued and the winner will dispatch it.
      if (interactiveRenderInFlight.putIfAbsent(params.frameStreamId, true) == null) {
        submitInteractiveRenderAsync(params.frameStreamId, target.previewId, session)
      }
      return
    }
    // v1 fallback: each input enqueues a fresh stateless render of the target preview. The
    // render body composes from scratch each time (LocalInspectionMode = true), so clicks
    // don't yet mutate composition state on hosts without a session — but the wire shape
    // round-trip + frame dedup still work end-to-end.
    val hostId = RenderHost.nextRequestId()
    hostIdToPreviewId[hostId] = target.previewId
    acceptedAtMs[hostId] = System.currentTimeMillis()
    inFlightRenders.add(hostId)
    submitRenderAsync(hostId)
  }

  /**
   * v2 — drain pending interactive inputs for [streamId], dispatch the batch into [session], and
   * render once. Owns the in-flight slot for [streamId] until the render completes; on completion,
   * checks the queue and re-claims if more inputs arrived during the render. Mirrors
   * [submitRenderAsync] structurally — fresh worker thread, no read-loop blocking, results onto
   * [renderResultsQueue] for the existing watcher pipeline.
   *
   * Failures during dispatch or render are routed onto the queue as [RenderResultOrFailure.Failure]
   * for the watcher to demux into a `renderFailed` notification. The in-flight slot is always
   * cleared in `finally` even on failure, and any inputs queued during the failed render still get
   * a chance via the post-finally re-claim — a single bad render shouldn't strand subsequent ones.
   */
  private fun submitInteractiveRenderAsync(
    streamId: String,
    previewId: String,
    session: InteractiveSession,
  ) {
    val hostId = RenderHost.nextRequestId()
    hostIdToPreviewId[hostId] = previewId
    acceptedAtMs[hostId] = System.currentTimeMillis()
    inFlightRenders.add(hostId)
    Thread(
        {
          try {
            // Drain every input currently pending for this stream and dispatch them as a batch
            // before rendering. Inputs that arrive *during* dispatch + render get queued for the
            // next iteration via the post-finally re-claim below.
            val queue = pendingInteractiveInputs[streamId]
            while (queue != null) {
              val next = queue.poll() ?: break
              session.dispatch(next)
            }
            val result = session.render(hostId)
            renderResultsQueue.put(result)
          } catch (e: Throwable) {
            System.err.println(
              "compose-ai-daemon: interactive session render($hostId) failed: ${e.message}"
            )
            renderResultsQueue.put(RenderResultOrFailure.Failure(hostId, e))
          } finally {
            // Release the in-flight slot, then check if more inputs arrived during the render.
            // If so, re-claim and recurse (on a new worker thread) so the next batch is dispatched
            // and rendered. The recursion always converges because each iteration drains some
            // inputs from the queue; without new arrivals the queue empties and the next claim
            // skips re-spawning.
            interactiveRenderInFlight.remove(streamId)
            val pending = pendingInteractiveInputs[streamId]
            if (
              pending != null &&
                pending.isNotEmpty() &&
                interactiveRenderInFlight.putIfAbsent(streamId, true) == null
            ) {
              val curSession = interactiveSessions[streamId]
              val curTarget = interactiveTargets[streamId]
              if (curSession != null && curTarget != null) {
                submitInteractiveRenderAsync(streamId, curTarget.previewId, curSession)
              } else {
                // Session was stopped while we were rendering; drop the queued inputs and clear
                // the slot. Stale inputs against a stopped stream are dropped per the v1
                // contract (handleInteractiveInput would also short-circuit on the next poll).
                pendingInteractiveInputs.remove(streamId)
                interactiveRenderInFlight.remove(streamId)
              }
            }
          }
        },
        "compose-ai-daemon-interactive-render-$hostId",
      )
      .apply { isDaemon = true }
      .start()
  }

  private fun handleShutdown(req: JsonRpcRequest) {
    shutdownRequested.set(true)
    // Drain in-flight renders before responding, per PROTOCOL.md § 3 and
    // DESIGN.md § 9 ("No mid-render cancellation"). We poll rather than
    // wait/notify because renders complete on submit threads we don't own.
    val drainDeadlineMs = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
    while (inFlightRenders.isNotEmpty() && System.currentTimeMillis() < drainDeadlineMs) {
      Thread.sleep(50)
    }
    sendResponse(req.id, JsonNull)
  }

  // --------------------------------------------------------------------------
  // Notification handlers
  // --------------------------------------------------------------------------

  private fun handleNotification(n: JsonRpcNotification) {
    when (n.method) {
      "initialized" -> initialized.set(true)
      "exit" -> handleExit()
      "setVisible" ->
        tryDecode(SetVisibleParams.serializer(), n) { pruneSubscriptionsToVisible(it.ids.toSet()) }
      "setFocus" -> tryDecode(SetFocusParams.serializer(), n) { /* no-op for B1.5 */ }
      "fileChanged" -> tryDecode(FileChangedParams.serializer(), n) { handleFileChanged(it) }
      "interactive/stop" ->
        tryDecode(ee.schimke.composeai.daemon.protocol.InteractiveStopParams.serializer(), n) {
          handleInteractiveStop(it)
        }
      "interactive/input" ->
        tryDecode(ee.schimke.composeai.daemon.protocol.InteractiveInputParams.serializer(), n) {
          handleInteractiveInput(it)
        }
      else -> System.err.println("compose-ai-daemon: unknown notification method: ${n.method}")
    }
  }

  /**
   * Routes a `fileChanged` notification.
   *
   * - **`kind: "source"`** (B2.0 + B2.2 phase 2):
   *     1. Drops the strong reference to the host's current child classloader so the next render
   *        lazily allocates a fresh [java.net.URLClassLoader] reading the recompiled bytecode off
   *        disk (B2.0 — see [CLASSLOADER.md](../../../../../../docs/daemon/CLASSLOADER.md)).
   *     2. When [incrementalDiscovery] is wired, runs the cheap-prefilter → scoped-scan → diff →
   *        applyDiff cascade on a worker thread and emits `discoveryUpdated` if the diff is
   *        non-empty (B2.2 phase 2 — [DESIGN § 8 Tier 2](../../../../../../docs/daemon/DESIGN.md)).
   *
   *    Honours the no-mid-render-cancellation invariant: both steps are queue-time events, not
   *    preemption, so any in-flight render keeps using its already-resolved `Class<?>` and the
   *    already-snapshotted `PreviewIndex`.
   * - **`kind: "classpath"`** → Tier-1 fingerprint cascade ([handleClasspathFileChanged]).
   * - **`kind: "resource"`** → conservative v1: would mark all previews stale, but the daemon does
   *   not yet own its own preview index for resources, so this is a no-op for now. B2.0c lands the
   *   smart variant (per-preview resource-read tracking).
   *
   * Hosts that don't participate in the parent/child split (the harness's `FakeHost`, the B1.3
   * stub) return `null` from [RenderHost.userClassloaderHolder]; the swap is then skipped.
   */
  private fun handleFileChanged(params: FileChangedParams) {
    when (params.kind) {
      FileKind.SOURCE -> {
        // SANDBOX-POOL-FOLLOWUPS.md (#1) — broadcast to every slot's holder under sandboxCount>1.
        // For single-sandbox hosts this is the same `swap()` call the previous code made on
        // `userClassloaderHolder`; the default-no-op on hosts without holders (FakeHost, B1.3
        // stubs) keeps the v1 fake-mode scenarios unchanged.
        host.swapUserClassLoaders()
        queueDiscoveryAfterRender(params.path)
      }
      FileKind.CLASSPATH -> {
        handleClasspathFileChanged(params)
      }
      FileKind.RESOURCE -> {
        // B2.0 v1 conservative: mark all previews stale. The daemon does not yet own its preview
        // index for resources (B2.0c lands the per-preview resource-read tracking that gives us a
        // smart invalidation pass). Left as a no-op deliberately so the harness's existing
        // `S3RenderAfterEdit*Test` (fake-mode) "fileChanged is a no-op" assertion still holds.
      }
    }
  }

  /**
   * Paths waiting on a deferred discovery scan — populated by [queueDiscoveryAfterRender] from the
   * `fileChanged({kind:source})` handler, drained by either [emitRenderFinished] (after the next
   * render notification has flushed) or by a watchdog timer (when no render arrives within
   * [discoveryWatchdogMs]).
   *
   * Identity dedup isn't worth it: two saves of the same file in quick succession scan twice, but
   * each scan is scoped (one classpath element) and the daemon is bounded by the user's editor
   * cadence anyway. The queue is correctness-safe under contention thanks to the atomic poll.
   */
  private val pendingDiscoveryPaths = ConcurrentLinkedQueue<String>()

  /**
   * Queues [path] for a deferred discovery cascade and starts a watchdog so the work always runs
   * eventually — even when the save lands on a file with no focused previews and no `renderNow`
   * follows.
   *
   * The user-visible invariant this enforces (matching the editor save-loop design): a save NEVER
   * surfaces a metadata event before the corresponding render. Renders are what the user actually
   * looks at; the metadata reconcile is a quiet background pass that only paints the panel when the
   * preview set actually drifted (`discoveryUpdated` is silent on an empty diff — see
   * [runIncrementalDiscoveryNow]).
   *
   * No-op when [incrementalDiscovery] is null — preserves the pre-phase-2 contract for in-process
   * integration tests and the fake-mode harness scenarios that don't wire one.
   */
  private fun queueDiscoveryAfterRender(path: String) {
    if (incrementalDiscovery == null) return
    pendingDiscoveryPaths.add(path)
    Thread(
        {
          try {
            Thread.sleep(discoveryWatchdogMs)
          } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return@Thread
          }
          drainPendingDiscovery()
        },
        "compose-ai-daemon-discovery-watchdog",
      )
      .apply { isDaemon = true }
      .start()
  }

  /**
   * Drains [pendingDiscoveryPaths] and runs the discovery cascade for each path. Idempotent:
   * concurrent callers (the watchdog vs. [emitRenderFinished]) compete via the atomic queue poll,
   * so each path runs at most once.
   */
  private fun drainPendingDiscovery() {
    while (true) {
      val path = pendingDiscoveryPaths.poll() ?: return
      runIncrementalDiscoveryNow(path)
    }
  }

  /**
   * B2.2 phase 2 — runs the daemon-side cheap-prefilter / scoped-scan / diff cascade for one
   * source-file `fileChanged` notification, applies the resulting diff in-place to [previewIndex],
   * and emits `discoveryUpdated` if non-empty.
   *
   * Runs the heavy work on a fresh daemon thread so neither the JSON-RPC read loop nor the
   * render-watcher loop blocks on a scan. Mirrors the fire-and-forget pattern [submitRenderAsync]
   * uses for renders. We deliberately pick a fresh thread (rather than reusing an executor) for
   * symmetry with the render path; saved-file events arrive O(seconds) apart in a typical save
   * loop, so the cost of one short-lived `Thread` per save is negligible compared to a ClassGraph
   * scan.
   */
  private fun runIncrementalDiscoveryNow(path: String) {
    val discovery = incrementalDiscovery ?: return
    Thread(
        {
          try {
            val file =
              try {
                Path.of(path)
              } catch (t: Throwable) {
                System.err.println(
                  "compose-ai-daemon: incrementalDiscovery: invalid path '$path' " +
                    "(${t.javaClass.simpleName}: ${t.message}); skipping"
                )
                return@Thread
              }
            if (!discovery.cheapPrefilter(file, previewIndex)) return@Thread
            val scan = discovery.scanForFile(file)
            val diff = previewIndex.diff(newScanForFile = scan, sourceFile = file)
            if (discoveryDiffEmpty(diff)) return@Thread
            previewIndex.applyDiff(diff)
            emitDiscoveryUpdated(diff)
          } catch (t: Throwable) {
            System.err.println(
              "compose-ai-daemon: incrementalDiscovery worker failed " +
                "(${t.javaClass.simpleName}: ${t.message})"
            )
            t.printStackTrace(System.err)
          }
        },
        "compose-ai-daemon-incremental-discovery",
      )
      .apply { isDaemon = true }
      .start()
  }

  private fun emitDiscoveryUpdated(diff: DiscoveryDiff) {
    val params =
      DiscoveryUpdatedParams(
        added = diff.added.map { encode(PreviewInfoDto.serializer(), it) },
        removed = diff.removed,
        changed = diff.changed.map { encode(PreviewInfoDto.serializer(), it) },
        totalPreviews = diff.totalPreviews,
      )
    sendNotification("discoveryUpdated", encode(DiscoveryUpdatedParams.serializer(), params))
  }

  /**
   * Tier-1 dirty-detection (DESIGN § 8) — runs the cheap → authoritative cascade and emits
   * `classpathDirty` + initiates a graceful exit when the classpath has truly drifted.
   *
   * Cascade:
   * 1. **No fingerprint configured** (e.g. fake-mode harness, the in-process integration test) —
   *    no-op; pre-B2.1 behaviour.
   * 2. **Cheap hash unchanged** — the saved file's bytes match what they were at startup. Common
   *    case: editor saved a file but no actual content drift (timestamp-only touch). No-op.
   * 3. **Cheap hash drifted, authoritative classpath hash unchanged** — a build-script edit
   *    (comment, formatting) that doesn't affect the resolved JAR list. Update the stored cheap
   *    hash so we don't keep re-walking the classpath, and no-op. (We do NOT emit `classpathDirty`
   *    — the daemon's classloader is still valid.)
   * 4. **Both drifted** — emit `classpathDirty` and start the graceful-exit timer.
   */
  private fun handleClasspathFileChanged(params: FileChangedParams) {
    val fingerprint = classpathFingerprint ?: return
    val baseline = classpathSnapshot ?: return
    if (classpathDirtyEmitted.get()) {
      // Already firing — additional notifications collapse into the in-flight exit.
      return
    }
    val freshCheap = fingerprint.cheapHash()
    if (freshCheap == lastObservedCheapHash) {
      // Cheap signal stable since last check (or matches startup). False alarm.
      return
    }
    val freshAuthoritative = fingerprint.classpathHash()
    if (freshAuthoritative == baseline.classpathHash) {
      // Cheap drifted but the resolved classpath did not. Common case: the user edited a comment
      // in build.gradle.kts. Update the cheap baseline to avoid re-walking the classpath JAR list
      // on every subsequent fileChanged for this same edit, and no-op.
      lastObservedCheapHash = freshCheap
      return
    }
    // Both drifted — Tier-1 fired.
    triggerClasspathDirty(
      reason = ClasspathDirtyReason.FINGERPRINT_MISMATCH,
      detail =
        "cheapHash=$freshCheap (was=${baseline.cheapHash}); " +
          "classpathHash=$freshAuthoritative (was=${baseline.classpathHash})",
      changedPath = params.path,
    )
  }

  /**
   * Emits the one-shot `classpathDirty` notification and schedules a graceful exit. Honours the
   * no-mid-render-cancellation invariant: the exit thread waits for [inFlightRenders] to drain
   * (bounded by [classpathDirtyGraceMs]) before tearing the host down. Subsequent `renderNow`
   * requests are refused with [ERR_CLASSPATH_DIRTY].
   */
  private fun triggerClasspathDirty(
    reason: ClasspathDirtyReason,
    detail: String,
    changedPath: String? = null,
  ) {
    if (!classpathDirtyEmitted.compareAndSet(false, true)) return
    val params =
      ClasspathDirtyParams(
        reason = reason,
        detail = detail,
        changedPaths = changedPath?.let { listOf(it) },
      )
    sendNotification("classpathDirty", encode(ClasspathDirtyParams.serializer(), params))
    // Mark the queue closed so any racing `renderNow` is rejected with the documented error code.
    shutdownRequested.set(true)
    Thread(
        {
          // Wait up to the grace window for the writer to flush the notification and any
          // in-flight renders to drain. We deliberately do NOT abort renders — the
          // no-mid-render-cancellation invariant from DESIGN § 9 still applies.
          val deadline = System.currentTimeMillis() + classpathDirtyGraceMs
          while (System.currentTimeMillis() < deadline) {
            if (outbound.isEmpty() && inFlightRenders.isEmpty()) break
            try {
              Thread.sleep(20)
            } catch (_: InterruptedException) {
              Thread.currentThread().interrupt()
              break
            }
          }
          running.set(false)
          cleanShutdown()
          invokeExit(0)
        },
        "compose-ai-daemon-classpath-dirty-exit",
      )
      .apply { isDaemon = false }
      .start()
  }

  /**
   * Cached cheap hash from the most recent observation — starts at the startup snapshot's value and
   * is bumped only when a cheap-drifted-but-authoritative-stable false alarm fires (case 3 in
   * [handleClasspathFileChanged]). All other paths leave it alone, so a flurry of fileChanged
   * notifications for the same touch-but-no-drift event collapses into a single hash recompute.
   */
  @Volatile private var lastObservedCheapHash: String? = classpathSnapshot?.cheapHash

  private fun handleExit() {
    // 0 if the client orchestrated the exit (shutdown received) OR we already initiated a
    // graceful classpath-dirty exit. 1 otherwise (client sent `exit` without `shutdown` first,
    // PROTOCOL.md § 3 — that path is a protocol violation).
    val exitCode = if (shutdownRequested.get() || classpathDirtyEmitted.get()) 0 else 1
    // Close interactive sessions BEFORE flipping running=false: cleanShutdown's body guards
    // against double-execution via compareAndSet(running, true→false), so once we set
    // running=false here it would early-return without closing the sessions itself. The helper
    // is idempotent (clears the map), so the duplicate call inside cleanShutdown's body is a
    // safe no-op on this path.
    closeAllInteractiveSessions()
    running.set(false)
    cleanShutdown()
    invokeExit(exitCode)
  }

  /**
   * Drains every held [InteractiveSession] from [interactiveSessions], close()'s each one, and
   * clears the map. Idempotent — a second call after the map is empty is a no-op. Called from both
   * [handleExit] (to handle the explicit-exit path where [cleanShutdown]'s body would early-
   * return) and [cleanShutdown] (to handle the EOF / idle-timeout / classpath-dirty paths). The v2
   * design doc § 9.2 calls out that a session's `close()` must drain any in-flight render before
   * tearing down the scene; the [InteractiveSession.close] contract pushes that responsibility to
   * the implementation.
   */
  private fun closeAllInteractiveSessions() {
    val sessions = interactiveSessions.values.toList()
    interactiveSessions.clear()
    // v2 phase 3 — drop any pending coalesced inputs and in-flight claims. The render workers
    // they refer to may still be running; they observe the cleared sessions map on their post-
    // finally re-claim and exit cleanly without re-spawning.
    pendingInteractiveInputs.clear()
    interactiveRenderInFlight.clear()
    for (session in sessions) {
      try {
        session.close()
      } catch (e: Throwable) {
        System.err.println(
          "compose-ai-daemon: closeAllInteractiveSessions: session.close() " +
            "(${session.previewId}) threw ${e.javaClass.simpleName}: ${e.message}; continuing"
        )
      }
    }
  }

  private fun <T> tryDecode(
    serializer: KSerializer<T>,
    n: JsonRpcNotification,
    block: (T) -> Unit,
  ) {
    try {
      block(decodeParams(n.params, serializer))
    } catch (e: Throwable) {
      System.err.println("compose-ai-daemon: invalid params for ${n.method}: ${e.message}")
    }
  }

  private fun <T> decodeParams(params: JsonElement?, serializer: KSerializer<T>): T {
    val element = params ?: JsonObject(emptyMap())
    return json.decodeFromJsonElement(serializer, element)
  }

  // --------------------------------------------------------------------------
  // Outbound write path
  // --------------------------------------------------------------------------

  private fun sendResponse(id: Long, result: JsonElement) {
    val response = JsonRpcResponse(id = id, result = result, error = null)
    enqueueFrame(json.encodeToString(JsonRpcResponse.serializer(), response))
  }

  private fun sendErrorResponse(id: Long?, code: Int, message: String) {
    val element = buildJsonObject {
      put("jsonrpc", JsonPrimitive("2.0"))
      put("id", if (id == null) JsonNull else JsonPrimitive(id))
      put(
        "error",
        buildJsonObject {
          put("code", JsonPrimitive(code))
          put("message", JsonPrimitive(message))
        },
      )
    }
    enqueueFrame(json.encodeToString(JsonElement.serializer(), element))
  }

  private fun sendNotification(method: String, params: JsonElement) {
    if (!initialized.get() && method != "log") {
      // PROTOCOL.md § 3: daemon must not send notifications before
      // `initialized`. Silently drop pre-initialize notifications.
      return
    }
    val n = JsonRpcNotification(method = method, params = params)
    enqueueFrame(json.encodeToString(JsonRpcNotification.serializer(), n))
  }

  private fun enqueueFrame(jsonText: String) {
    val payload = jsonText.toByteArray(Charsets.UTF_8)
    val header = "Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
    val combined = ByteArray(header.size + payload.size)
    System.arraycopy(header, 0, combined, 0, header.size)
    System.arraycopy(payload, 0, combined, header.size, payload.size)
    outbound.put(combined)
  }

  private fun writerLoop() {
    try {
      while (true) {
        val frame = outbound.take()
        if (frame === SHUTDOWN_SENTINEL) return
        output.write(frame)
        output.flush()
      }
    } catch (e: Throwable) {
      System.err.println("compose-ai-daemon: writer loop error: ${e.message}")
    }
  }

  private fun invokeExit(code: Int) {
    if (exitInvoked.compareAndSet(false, true)) {
      onExit(code)
    }
  }

  private fun cleanShutdown() {
    if (!running.compareAndSet(true, false)) return
    // Wait for any outstanding renders to drain so we honour the
    // no-mid-render-cancellation invariant even on EOF / unexpected exit
    // paths. Bounded by DRAIN_TIMEOUT_MS to avoid hanging the process.
    val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
    while (inFlightRenders.isNotEmpty() && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(50)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        break
      }
    }
    // H4 — stop the auto-prune scheduler before the host. Idempotent; safe under racing exits.
    try {
      historyManager?.stopAutoPrune()
    } catch (e: Throwable) {
      System.err.println("compose-ai-daemon: historyManager.stopAutoPrune failed: ${e.message}")
    }
    closeAllInteractiveSessions()
    try {
      host.shutdown()
    } catch (e: Throwable) {
      System.err.println("compose-ai-daemon: host.shutdown failed: ${e.message}")
    }
    // Tell the writer to exit, then drain it so any pending bytes flush.
    outbound.put(SHUTDOWN_SENTINEL)
    try {
      writerThread.join(2_000)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
    // Watcher exits once running=false and inFlightRenders is empty.
    try {
      renderWatcherThread.join(2_000)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  // --------------------------------------------------------------------------
  // Helpers
  // --------------------------------------------------------------------------

  private fun <T> encode(serializer: KSerializer<T>, value: T): JsonElement =
    json.encodeToJsonElement(serializer, value)

  private fun currentPid(): Long =
    try {
      ProcessHandle.current().pid()
    } catch (_: Throwable) {
      // Fallback for environments where ProcessHandle is unavailable.
      ManagementFactory.getRuntimeMXBean().name.substringBefore('@').toLongOrNull() ?: -1L
    }

  /**
   * Project the daemon's `DeviceDimensions` catalog into the wire shape advertised on
   * `InitializeResult.capabilities.knownDevices`. Sorted by id so the client sees a stable order
   * across runs (the underlying map is insertion-ordered today, but spelling that out here keeps
   * the contract independent of catalog-edit ordering).
   */
  private fun buildKnownDevices(): List<KnownDevice> =
    ee.schimke.composeai.daemon.devices.DeviceDimensions.KNOWN_DEVICE_IDS.sorted().map { id ->
      val spec = ee.schimke.composeai.daemon.devices.DeviceDimensions.resolve(id)
      KnownDevice(id = id, widthDp = spec.widthDp, heightDp = spec.heightDp, density = spec.density)
    }

  /** Tagged failure carrier for the watcher loop. */
  private sealed interface RenderResultOrFailure {
    class Failure(val hostId: Long, val cause: Throwable) : RenderResultOrFailure
  }

  /**
   * D3 — completion signal for [dataFetchWaiters]. The watcher loop completes the future with [Ok]
   * when the underlying render lands and with [Failed] when the host rejects it; the data- fetch
   * worker thread reads which one it got and decides whether to re-call the registry, surface
   * `DataProductFetchFailed`, or — on its own timeout path — surface `DataProductBudgetExceeded`.
   */
  private sealed interface RerenderOutcome {
    data class Ok(val previewId: String) : RerenderOutcome

    data class Failed(val message: String) : RerenderOutcome
  }

  companion object {
    /** PROTOCOL.md § 7. */
    const val PROTOCOL_VERSION: Int = 1

    const val IDLE_TIMEOUT_PROP: String = "composeai.daemon.idleTimeoutMs"
    const val DEFAULT_IDLE_TIMEOUT_MS: Long = 5_000L

    /** PROTOCOL.md § 6 — `daemon.classpathDirtyGraceMs`, default 2000ms. */
    const val CLASSPATH_DIRTY_GRACE_PROP: String = "composeai.daemon.classpathDirtyGraceMs"
    const val DEFAULT_CLASSPATH_DIRTY_GRACE_MS: Long = 2_000L

    /**
     * DATA-PRODUCTS.md § "Re-render semantics" — `daemon.dataFetchRerenderBudgetMs`, default
     * 30000ms. The per-request ceiling on a `data/fetch` that triggered a re-render. Sysprop
     * override for tests; `JsonRpcServer` constructor param for in-process callers (the in-process
     * integration tests pin it sub-second so the timeout branch is fast).
     */
    const val DATA_FETCH_RERENDER_BUDGET_PROP: String = "composeai.daemon.dataFetchRerenderBudgetMs"
    const val DEFAULT_DATA_FETCH_RERENDER_BUDGET_MS: Long = 30_000L

    /**
     * Watchdog window between `fileChanged({kind:source})` and the (deferred) discovery scan. The
     * server prefers to drain the pending-discovery queue from `emitRenderFinished` so the user's
     * new PNG always lands before any `discoveryUpdated`; the watchdog is the fallback for saves
     * that don't drive a render (e.g. file changed but not focused). 1500ms in production keeps the
     * metadata reconcile responsive without racing fast renders. Tests override to a few hundred ms
     * via the sysprop.
     */
    const val DISCOVERY_WATCHDOG_PROP: String = "composeai.daemon.discoveryWatchdogMs"
    const val DEFAULT_DISCOVERY_WATCHDOG_MS: Long = 1_500L

    /**
     * Ceiling on shutdown drain. Renders that take longer than this are still allowed to finish —
     * but the shutdown response will be sent.
     */
    private const val DRAIN_TIMEOUT_MS: Long = 60_000L

    private const val DEFAULT_DAEMON_VERSION: String = "0.0.0-dev"
    private const val DEFAULT_HISTORY_DIR: String = ".compose-preview-history"

    // JSON-RPC error codes — PROTOCOL.md § 2.
    const val ERR_PARSE: Int = -32700
    const val ERR_INVALID_REQUEST: Int = -32600
    const val ERR_METHOD_NOT_FOUND: Int = -32601
    const val ERR_INVALID_PARAMS: Int = -32602
    const val ERR_INTERNAL: Int = -32603
    const val ERR_NOT_INITIALIZED: Int = -32001

    /** PROTOCOL.md § 5 — `renderNow` rejected because the daemon will exit imminently. */
    const val ERR_CLASSPATH_DIRTY: Int = -32002

    /** HISTORY.md § "Error codes" — `history/read` referenced a missing entry id. */
    const val ERR_HISTORY_ENTRY_NOT_FOUND: Int = -32010

    /**
     * HISTORY.md § "Error codes" — `history/diff` was given two entries from different previews.
     * Reserved in the wire enum even though `history/diff` itself isn't implemented in H1+H2 (lands
     * in H3+); pinned here so the code constant is part of the locked surface.
     */
    const val ERR_HISTORY_DIFF_MISMATCH: Int = -32011

    /**
     * HISTORY.md § "Error codes" — `history/diff` was called with `mode = pixel`, which is reserved
     * for phase H5 and not implemented in H3. We deliberately return a clean error code (rather
     * than silently leaving the pixel fields null in METADATA mode) so callers can tell "I asked
     * for pixel and the daemon isn't ready" apart from "I asked for metadata and got null pixel
     * fields by design."
     */
    const val ERR_HISTORY_PIXEL_NOT_IMPLEMENTED: Int = -32012

    /** DATA-PRODUCTS.md § "Error codes" — kind not advertised by the daemon. */
    const val ERR_DATA_PRODUCT_UNKNOWN: Int = -32020

    /** DATA-PRODUCTS.md § "Error codes" — preview has never rendered; caller should `renderNow`. */
    const val ERR_DATA_PRODUCT_NOT_AVAILABLE: Int = -32021

    /** DATA-PRODUCTS.md § "Error codes" — re-render or projection failed; details in `data`. */
    const val ERR_DATA_PRODUCT_FETCH_FAILED: Int = -32022

    /** DATA-PRODUCTS.md § "Error codes" — re-render budget tripped before the payload landed. */
    const val ERR_DATA_PRODUCT_BUDGET_EXCEEDED: Int = -32023

    private val SHUTDOWN_SENTINEL = ByteArray(0)
  }
}

// ---------------------------------------------------------------------------
// LSP-style Content-Length framer (~50 LOC, hand-rolled per PROTOCOL.md § 1).
// ---------------------------------------------------------------------------

internal class FramingException(message: String) : IOException(message)

internal class ContentLengthFramer(private val input: InputStream) {

  /**
   * Reads one framed message, returning its UTF-8 payload bytes. Returns null on clean EOF (i.e.
   * end-of-stream observed at a frame boundary).
   *
   * Header parsing tolerates `\r\n` or `\n` line endings, ignores `Content-Type` and any other
   * headers, and treats a missing `Content-Length` as a [FramingException].
   */
  fun readFrame(): ByteArray? {
    var contentLength = -1
    val headerBuf = ByteArrayOutputStream(64)
    var sawAny = false
    while (true) {
      val line =
        readHeaderLine(headerBuf)
          ?: return if (sawAny) {
            throw FramingException("EOF in headers")
          } else {
            null
          }
      sawAny = true
      if (line.isEmpty()) break
      val colon = line.indexOf(':')
      if (colon <= 0) throw FramingException("malformed header line: '$line'")
      val name = line.substring(0, colon).trim()
      val value = line.substring(colon + 1).trim()
      if (name.equals("Content-Length", ignoreCase = true)) {
        contentLength =
          value.toIntOrNull() ?: throw FramingException("non-integer Content-Length: '$value'")
        if (contentLength < 0) throw FramingException("negative Content-Length: $contentLength")
      }
      // Other headers (Content-Type, etc.) are explicitly ignored per
      // PROTOCOL.md § 1.
    }
    if (contentLength < 0) throw FramingException("missing Content-Length header")
    val payload = ByteArray(contentLength)
    var off = 0
    while (off < contentLength) {
      val n = input.read(payload, off, contentLength - off)
      if (n < 0) throw FramingException("EOF mid-payload after $off/$contentLength bytes")
      off += n
    }
    return payload
  }

  private fun readHeaderLine(buf: ByteArrayOutputStream): String? {
    buf.reset()
    while (true) {
      val b = input.read()
      if (b < 0) {
        return if (buf.size() == 0) null else buf.toString(Charsets.US_ASCII.name())
      }
      if (b == '\n'.code) {
        // Strip trailing \r if present.
        val bytes = buf.toByteArray()
        val end =
          if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1
          else bytes.size
        return String(bytes, 0, end, Charsets.US_ASCII)
      }
      buf.write(b)
    }
  }
}

// ---------------------------------------------------------------------------
// Tiny JSON object builder — avoids a kotlinx-serialization dependency on
// `kotlinx.serialization.json.buildJsonObject` import noise above. Kept local
// so the imports list at the top of the file stays compact.
// ---------------------------------------------------------------------------

private fun buildJsonObject(block: MutableMap<String, JsonElement>.() -> Unit): JsonObject {
  val map = LinkedHashMap<String, JsonElement>()
  map.block()
  return JsonObject(map)
}
