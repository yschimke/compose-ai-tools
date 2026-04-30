package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.ClasspathDirtyParams
import ee.schimke.composeai.daemon.protocol.ClasspathDirtyReason
import ee.schimke.composeai.daemon.protocol.DiscoveryUpdatedParams
import ee.schimke.composeai.daemon.protocol.FileChangedParams
import ee.schimke.composeai.daemon.protocol.FileKind
import ee.schimke.composeai.daemon.protocol.InitializeParams
import ee.schimke.composeai.daemon.protocol.InitializeResult
import ee.schimke.composeai.daemon.protocol.JsonRpcNotification
import ee.schimke.composeai.daemon.protocol.JsonRpcRequest
import ee.schimke.composeai.daemon.protocol.JsonRpcResponse
import ee.schimke.composeai.daemon.protocol.LeakDetectionMode
import ee.schimke.composeai.daemon.protocol.Manifest
import ee.schimke.composeai.daemon.protocol.RejectedRender
import ee.schimke.composeai.daemon.protocol.RenderFinishedParams
import ee.schimke.composeai.daemon.protocol.RenderMetrics
import ee.schimke.composeai.daemon.protocol.RenderNowParams
import ee.schimke.composeai.daemon.protocol.RenderNowResult
import ee.schimke.composeai.daemon.protocol.RenderStartedParams
import ee.schimke.composeai.daemon.protocol.ServerCapabilities
import ee.schimke.composeai.daemon.protocol.SetFocusParams
import ee.schimke.composeai.daemon.protocol.SetVisibleParams
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
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
  private val onExit: (Int) -> Unit = { code -> System.exit(code) },
) {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
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

  /** Outbound frame queue. SHUTDOWN_SENTINEL is the writer's poison pill. */
  private val outbound = LinkedBlockingQueue<ByteArray>()

  /** In-flight render IDs the host is currently working on. */
  private val inFlightRenders = ConcurrentHashMap.newKeySet<Long>()

  /** Per-protocol-id → enqueue wall-clock millis, for `renderStarted.queuedMs`. */
  private val acceptedAtMs = ConcurrentHashMap<Long, Long>()

  /** Mapping host-side internal request id → caller's preview id string. */
  private val hostIdToPreviewId = ConcurrentHashMap<Long, String>()

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
    host.start()
    writerThread.start()
    renderWatcherThread.start()
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
      else ->
        sendErrorResponse(
          id = req.id,
          code = ERR_METHOD_NOT_FOUND,
          message = "method not found: ${req.method}",
        )
    }
  }

  private fun handleInitialize(req: JsonRpcRequest) {
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
  }

  private fun handleRenderNow(req: JsonRpcRequest) {
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
    for (previewId in params.previews) {
      // Stub policy for B1.5: accept any non-blank id. UnknownPreview (-32004)
      // requires a real discovery set, which lands with B2.2.
      if (previewId.isBlank()) {
        rejected.add(RejectedRender(id = previewId, reason = "blank preview id"))
        continue
      }
      val hostId = RenderHost.nextRequestId()
      hostIdToPreviewId[hostId] = previewId
      acceptedAtMs[hostId] = now
      inFlightRenders.add(hostId)
      // Submit to host on a worker thread so we don't block the read loop.
      // submit() returns when the host returns a result; the watcher thread
      // demuxes the result back into renderFinished.
      submitRenderAsync(hostId)
      queued.add(previewId)
    }
    val result = RenderNowResult(queued = queued, rejected = rejected)
    sendResponse(req.id, encode(RenderNowResult.serializer(), result))
  }

  private fun submitRenderAsync(hostId: Long) {
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
    val payload = if (previewId.isNotEmpty()) "previewId=$previewId" else ""
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
    sendNotification("renderFinished", encode(RenderFinishedParams.serializer(), finished))
    inFlightRenders.remove(result.id)
  }

  private fun emitRenderFailed(failure: RenderResultOrFailure.Failure) {
    val previewId = hostIdToPreviewId.remove(failure.hostId) ?: failure.hostId.toString()
    acceptedAtMs.remove(failure.hostId)
    inFlightRenders.remove(failure.hostId)
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
    return RenderFinishedParams(
      id = previewId,
      pngPath = pngPath,
      tookMs = tookMs,
      metrics = metrics,
    )
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
      "setVisible" -> tryDecode(SetVisibleParams.serializer(), n) { /* no-op for B1.5 */ }
      "setFocus" -> tryDecode(SetFocusParams.serializer(), n) { /* no-op for B1.5 */ }
      "fileChanged" -> tryDecode(FileChangedParams.serializer(), n) { handleFileChanged(it) }
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
        host.userClassloaderHolder?.swap()
        runIncrementalDiscovery(params.path)
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
   * B2.2 phase 2 — runs the daemon-side cheap-prefilter / scoped-scan / diff cascade for one
   * source-file `fileChanged` notification, applies the resulting diff in-place to [previewIndex],
   * and emits `discoveryUpdated` if non-empty.
   *
   * Runs the heavy work on a fresh daemon thread so the JSON-RPC read loop never blocks on a scan.
   * Mirrors the fire-and-forget pattern [submitRenderAsync] uses for renders. We deliberately pick
   * a fresh thread (rather than reusing an executor) for symmetry with the render path; saved-file
   * events arrive O(seconds) apart in a typical save loop, so the cost of one short-lived `Thread`
   * per save is negligible compared to a ClassGraph scan.
   *
   * No-op when [incrementalDiscovery] is null — preserves the pre-phase-2 contract for in-process
   * integration tests.
   */
  private fun runIncrementalDiscovery(path: String) {
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
    running.set(false)
    cleanShutdown()
    invokeExit(exitCode)
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

  /** Tagged failure carrier for the watcher loop. */
  private sealed interface RenderResultOrFailure {
    class Failure(val hostId: Long, val cause: Throwable) : RenderResultOrFailure
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
