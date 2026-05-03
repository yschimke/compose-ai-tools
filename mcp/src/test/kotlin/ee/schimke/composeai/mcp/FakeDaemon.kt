package ee.schimke.composeai.mcp

import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.InitializeResult
import ee.schimke.composeai.daemon.protocol.Manifest
import ee.schimke.composeai.daemon.protocol.RenderNowResult
import ee.schimke.composeai.daemon.protocol.ServerCapabilities
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Test-only fake daemon. Speaks the daemon protocol (PROTOCOL.md) over piped streams so the MCP
 * server can talk to it without a real subprocess. Just enough behavior to exercise:
 *
 * - `initialize` → returns a stub [InitializeResult] with empty capabilities.
 * - `renderNow(previews)` → returns `queued = previews` and emits a [`renderFinished`][..]
 *   notification for every queued id with a synthetic png path.
 * - `setVisible` / `setFocus` → recorded so tests can assert watch propagation.
 * - `shutdown` → returns null result; subsequent `exit` causes the reader to drain.
 *
 * The fake also lets the test push a [`discoveryUpdated`][..] notification on demand to trigger the
 * MCP server's catalog update + resources/list_changed signal.
 */
class FakeDaemon : DaemonSpawn {

  private val mcpToDaemon = PipedOutputStream()
  private val daemonReadIn = PipedInputStream(mcpToDaemon, BUFFER)
  private val daemonToMcp = PipedOutputStream()
  private val mcpReadIn = PipedInputStream(daemonToMcp, BUFFER)

  private val running = AtomicBoolean(true)
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }

  /** Sent by the fake's reader when the MCP shim issues `setVisible`. Tests assert ordering. */
  val visibleSets = java.util.concurrent.LinkedBlockingQueue<List<String>>()
  /** Same for `setFocus`. */
  val focusSets = java.util.concurrent.LinkedBlockingQueue<List<String>>()
  /** `renderNow` invocations the fake observed. */
  val renderRequests = java.util.concurrent.LinkedBlockingQueue<List<String>>()
  /** `fileChanged` notifications the fake observed. */
  val fileChanges = java.util.concurrent.LinkedBlockingQueue<JsonObject>()
  /**
   * `renderNow.overrides` payloads the fake observed (in lockstep with [renderRequests]).
   * `LinkedBlockingQueue` rejects null elements, so we use `CopyOnWriteArrayList` and tests poll by
   * index rather than by [LinkedBlockingQueue.poll] timeout.
   */
  val renderOverrides: MutableList<ee.schimke.composeai.daemon.protocol.PreviewOverrides?> =
    java.util.concurrent.CopyOnWriteArrayList()

  /**
   * D1 — kinds the fake advertises in `initialize.capabilities.dataProducts`. Tests assign before
   * the spawn calls `initialize` (synchronous in [DaemonSupervisor.spawn], so assign-then-spawn is
   * the natural order).
   */
  @Volatile var advertisedDataProducts: List<DataProductCapability> = emptyList()

  /**
   * `PreviewOverrides` field names the fake advertises in
   * `initialize.capabilities.supportedOverrides`. Tests assign before the spawn calls `initialize`.
   * Empty list (the default) keeps pre-feature behaviour — `DaemonMcpServer`'s validation falls
   * open on an empty advertised set.
   */
  @Volatile var advertisedSupportedOverrides: List<String> = emptyList()

  /**
   * Devices the fake advertises in `initialize.capabilities.knownDevices`. Tests assign before the
   * spawn calls `initialize`. Empty list keeps pre-feature behaviour.
   */
  @Volatile
  var advertisedKnownDevices: List<ee.schimke.composeai.daemon.protocol.KnownDevice> = emptyList()

  /**
   * Recording formats the fake advertises in `initialize.capabilities.recordingFormats`. Tests
   * assign before the spawn calls `initialize`. Empty list keeps pre-feature behaviour — the MCP
   * `record_preview` format-validation falls open and the request goes through.
   */
  @Volatile var advertisedRecordingFormats: List<String> = emptyList()

  /**
   * D1 — fake handler for `data/fetch`. When set, the lambda receives `(previewId, kind, params,
   * inline)` and returns either the [DataFetchOutcome] the daemon should respond with. Default
   * returns [DataFetchOutcome.Unknown] so unconfigured tests see the wire-error path.
   */
  @Volatile
  var dataFetchHandler:
    (previewId: String, kind: String, params: JsonObject?, inline: Boolean) -> DataFetchOutcome =
    { _, _, _, _ ->
      DataFetchOutcome.Unknown
    }

  /** Recorded `data/subscribe` calls — list of (previewId, kind) tuples. */
  val dataSubscribes = java.util.concurrent.LinkedBlockingQueue<Pair<String, String>>()
  /** Recorded `data/unsubscribe` calls — same shape. */
  val dataUnsubscribes = java.util.concurrent.LinkedBlockingQueue<Pair<String, String>>()

  // ---------------------------------------------------------------------------
  // Recording (RECORDING.md) — fake state for the four-call flow.
  // Tests assign the handler before the spawn calls `initialize` (synchronous in
  // [DaemonSupervisor.spawn]); the `record_preview` tool drives start/script/stop/encode in
  // sequence and the fake records each call into the maps below + the corresponding queue.
  // ---------------------------------------------------------------------------

  /** Recorded `recording/start` calls in arrival order — `(previewId, fps, scale, overrides?)`. */
  data class RecordingStartCall(
    val previewId: String,
    val fps: Int?,
    val scale: Float?,
    val overrides: ee.schimke.composeai.daemon.protocol.PreviewOverrides?,
  )

  val recordingStarts = java.util.concurrent.LinkedBlockingQueue<RecordingStartCall>()

  /** Recorded `recording/script` calls — `(recordingId, events)`. */
  data class RecordingScriptCall(
    val recordingId: String,
    val events: List<ee.schimke.composeai.daemon.protocol.RecordingScriptEvent>,
  )

  val recordingScripts = java.util.concurrent.LinkedBlockingQueue<RecordingScriptCall>()

  /** Recorded `recording/stop` calls — just the recordingId. */
  val recordingStops = java.util.concurrent.LinkedBlockingQueue<String>()

  /** Recorded `recording/encode` calls — `(recordingId, format)`. */
  data class RecordingEncodeCall(
    val recordingId: String,
    val format: ee.schimke.composeai.daemon.protocol.RecordingFormat,
  )

  val recordingEncodes = java.util.concurrent.LinkedBlockingQueue<RecordingEncodeCall>()

  /**
   * Fake byte payload returned by the next `recording/encode` call. Tests preload this with the
   * expected APNG bytes; the fake writes them to a temp file and responds with the path / size /
   * mime so [DaemonClient.recordingEncode] can return the file the MCP server then reads back into
   * a base64 image content block. Defaults to a tiny non-empty stub (the PNG signature) so tests
   * that don't care about exact bytes still get a non-zero `sizeBytes`.
   */
  @Volatile var recordingEncodedBytes: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

  /**
   * Optional override directory for the encoded video file. When null, the fake writes to a temp
   * file under `java.io.tmpdir`. Tests pass an explicit folder when they want predictable paths.
   */
  @Volatile var recordingEncodeDir: java.io.File? = null

  /**
   * Pre-canned response shape for `recording/stop`. Tests override per scenario; defaults match
   * "30fps × 500ms script" — 16 frames at 120×60.
   */
  @Volatile
  var recordingStopResult: ee.schimke.composeai.daemon.protocol.RecordingStopResult =
    ee.schimke.composeai.daemon.protocol.RecordingStopResult(
      frameCount = 16,
      durationMs = 500L,
      framesDir = "/tmp/fake-frames",
      frameWidthPx = 120,
      frameHeightPx = 60,
    )

  private val nextRecordingId = java.util.concurrent.atomic.AtomicLong(1)

  /**
   * Optional capture hook for the params object the fake received on `initialize`. Tests use this
   * to assert what the supervisor passes through (e.g. `options.attachDataProducts`). Default is a
   * no-op.
   */
  @Volatile var onInitializeReceived: (JsonObject) -> Unit = {}

  /**
   * Outcome the fake's `data/fetch` handler returns. Mirrors the daemon's
   * [`DataProductRegistry.Outcome`] but deliberately decouples — the fake doesn't depend on the
   * registry interface.
   */
  sealed interface DataFetchOutcome {
    /** Wire-success: the fake returns a `DataFetchResult` with the given fields. */
    data class Ok(
      val kind: String,
      val schemaVersion: Int,
      val payload: JsonElement? = null,
      val path: String? = null,
      val bytes: String? = null,
    ) : DataFetchOutcome

    /** -32020 DataProductUnknown. */
    data object Unknown : DataFetchOutcome

    /** -32021 DataProductNotAvailable. */
    data object NotAvailable : DataFetchOutcome

    /** -32022 DataProductFetchFailed. */
    data class FetchFailed(val message: String) : DataFetchOutcome

    /** -32023 DataProductBudgetExceeded. */
    data object BudgetExceeded : DataFetchOutcome
  }

  /**
   * When set, the fake auto-emits a `renderFinished` notification for every preview id in an
   * incoming `renderNow` whose path the lambda returns non-null. Removes the
   * spawn-a-thread-and-poll-renderRequests race in tests that just want "render → bytes back".
   */
  @Volatile var autoRenderPngPath: ((previewId: String) -> String?)? = null

  private lateinit var _client: DaemonClient

  override val client: DaemonClient
    get() = _client

  init {
    Thread({ runDaemonReader() }, "fake-daemon-reader").apply { isDaemon = true }.start()
  }

  override fun client(
    onNotification: (method: String, params: JsonObject?) -> Unit,
    onClose: () -> Unit,
  ): DaemonClient {
    _client =
      DaemonClient(
        input = mcpReadIn,
        output = mcpToDaemon,
        onNotification = onNotification,
        onClose = onClose,
        threadName = "mcp-fake-daemon-client",
      )
    return _client
  }

  override fun shutdown() {
    if (!running.compareAndSet(true, false)) return
    runCatching { daemonToMcp.close() }
    runCatching { daemonReadIn.close() }
  }

  /** Pushes a `discoveryUpdated` notification with one preview added. Test helper. */
  fun emitDiscovery(
    previewId: String,
    displayName: String = previewId,
    sourceFile: String? = null,
  ) {
    val params = buildJsonObject {
      putJsonArray("added") {
        add(
          buildJsonObject {
            put("id", previewId)
            put("className", previewId.substringBeforeLast('.'))
            put("methodName", previewId.substringAfterLast('.'))
            put("displayName", displayName)
            if (sourceFile != null) put("sourceFile", sourceFile)
          }
        )
      }
      putJsonArray("removed") {}
      putJsonArray("changed") {}
      put("totalPreviews", 1)
    }
    sendNotification("discoveryUpdated", params)
  }

  /**
   * Pushes a `classpathDirty` notification. PROTOCOL.md § 6 says this fires at most once per
   * lifetime; tests should treat the daemon as dying after this call.
   */
  fun emitClasspathDirty(
    reason: String = "fingerprintMismatch",
    detail: String = "test-driven classpathDirty",
  ) {
    val params = buildJsonObject {
      put("reason", reason)
      put("detail", detail)
    }
    sendNotification("classpathDirty", params)
  }

  /** Pushes a `renderFinished` notification. Returns the synthetic pngPath emitted. */
  fun emitRenderFinished(previewId: String, pngPath: String): String {
    val params = buildJsonObject {
      put("id", previewId)
      put("pngPath", pngPath)
      put("tookMs", 50L)
    }
    sendNotification("renderFinished", params)
    return pngPath
  }

  /**
   * Pushes a `renderFinished` notification carrying the given [attachments] under the wire's
   * `dataProducts` field. Each attachment is `(kind, schemaVersion, payload?, path?)` matching the
   * D1 wire shape (DATA-PRODUCTS.md). Used by tests that exercise the supervisor's
   * `dataProductCache` — the MCP server reads attachments off this notification, caches them, and
   * `get_preview_data` cache-hits them.
   */
  fun emitRenderFinishedWithDataProducts(
    previewId: String,
    pngPath: String,
    attachments: List<JsonObject>,
  ): String {
    val params = buildJsonObject {
      put("id", previewId)
      put("pngPath", pngPath)
      put("tookMs", 50L)
      putJsonArray("dataProducts") { attachments.forEach { add(it) } }
    }
    sendNotification("renderFinished", params)
    return pngPath
  }

  // -------------------------------------------------------------------------
  // Daemon-side reader: read framed JSON-RPC from the MCP shim, respond.
  // -------------------------------------------------------------------------

  private fun runDaemonReader() {
    try {
      while (running.get()) {
        val frame = readFrame(daemonReadIn) ?: return
        val obj = json.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject
        val responseId = obj["id"]?.jsonPrimitive?.long
        val method = obj["method"]?.jsonPrimitive?.contentOrNull
        val params = obj["params"] as? JsonObject
        if (responseId != null && method != null) {
          handleRequest(responseId, method, params)
        } else if (method != null) {
          handleNotification(method, params)
        }
      }
    } catch (_: IOException) {
      // EOF
    }
  }

  private fun handleRequest(id: Long, method: String, params: JsonObject?) {
    when (method) {
      "initialize" -> {
        if (params != null) runCatching { onInitializeReceived(params) }
        val result =
          InitializeResult(
            protocolVersion = 1,
            daemonVersion = "fake",
            pid = 0,
            capabilities =
              ServerCapabilities(
                incrementalDiscovery = true,
                sandboxRecycle = false,
                leakDetection = emptyList(),
                dataProducts = advertisedDataProducts,
                knownDevices = advertisedKnownDevices,
                supportedOverrides = advertisedSupportedOverrides,
                recordingFormats = advertisedRecordingFormats,
              ),
            classpathFingerprint = "fake-fingerprint",
            manifest = Manifest(path = "", previewCount = 0),
          )
        sendResponse(id, json.encodeToJsonElement(InitializeResult.serializer(), result))
      }
      "renderNow" -> {
        val previews =
          (params?.get("previews") as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            it.jsonPrimitive.contentOrNull
          } ?: emptyList()
        val overrides =
          params
            ?.get("overrides")
            ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
            ?.let {
              json.decodeFromJsonElement(
                ee.schimke.composeai.daemon.protocol.PreviewOverrides.serializer(),
                it,
              )
            }
        // Populate the overrides slot BEFORE offering on `renderRequests` — tests poll on
        // `renderRequests` and immediately check `renderOverrides`; reverse order opens a race
        // window between the offer (which wakes the polling thread) and the add to the
        // overrides list.
        renderOverrides.add(overrides)
        renderRequests.offer(previews)
        val result = RenderNowResult(queued = previews, rejected = emptyList())
        sendResponse(id, json.encodeToJsonElement(RenderNowResult.serializer(), result))
        // Auto-emit renderFinished for any preview whose path the test pre-registered. The
        // emission happens AFTER the response so the daemon-protocol ordering matches what a
        // real backend produces (queued → started → finished).
        autoRenderPngPath?.let { provider ->
          previews.forEach { pid ->
            val path = provider(pid)
            if (path != null) emitRenderFinished(pid, path)
          }
        }
      }
      "shutdown" -> {
        sendResponse(id, kotlinx.serialization.json.JsonNull)
      }
      "history/list" -> {
        // Tests preload `historyEntries` via `setHistory(...)`; we just echo them back wrapped
        // in the wire shape. Filtering + cursor support are deliberately stubbed — the H6
        // mapping forwards params verbatim, so unit tests don't need full filter coverage here.
        val payload = buildJsonObject {
          putJsonArray("entries") { historyEntries.forEach { add(it) } }
          put("totalCount", historyEntries.size)
        }
        sendResponse(id, payload)
      }
      "history/read" -> {
        val entryId = params?.get("id")?.jsonPrimitive?.contentOrNull
        val entry = historyEntries.firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == entryId }
        if (entry == null) {
          sendError(id, -32010, "HistoryEntryNotFound: $entryId")
        } else {
          val inline = params?.get("inline")?.jsonPrimitive?.contentOrNull == "true"
          val pngPath = entry["pngPath"]?.jsonPrimitive?.contentOrNull ?: "/tmp/missing.png"
          val payload = buildJsonObject {
            put("entry", entry)
            put("pngPath", pngPath)
            if (inline) {
              val bytes =
                historyInlineBytes[entryId] ?: byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
              put("pngBytes", java.util.Base64.getEncoder().encodeToString(bytes))
            }
          }
          sendResponse(id, payload)
        }
      }
      "history/diff" -> {
        val from = params?.get("from")?.jsonPrimitive?.contentOrNull
        val to = params?.get("to")?.jsonPrimitive?.contentOrNull
        val fromEntry = historyEntries.firstOrNull {
          it["id"]?.jsonPrimitive?.contentOrNull == from
        }
        val toEntry = historyEntries.firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == to }
        if (fromEntry == null || toEntry == null) {
          sendError(id, -32010, "HistoryEntryNotFound: from=$from to=$to")
        } else {
          val payload = buildJsonObject {
            put(
              "pngHashChanged",
              fromEntry["pngHash"]?.jsonPrimitive?.contentOrNull !=
                toEntry["pngHash"]?.jsonPrimitive?.contentOrNull,
            )
            put("fromMetadata", fromEntry)
            put("toMetadata", toEntry)
          }
          sendResponse(id, payload)
        }
      }
      "data/fetch" -> {
        val previewId = params?.get("previewId")?.jsonPrimitive?.contentOrNull ?: ""
        val kind = params?.get("kind")?.jsonPrimitive?.contentOrNull ?: ""
        val perKindParams = params?.get("params") as? JsonObject
        // The wire shape encodes booleans as actual JSON booleans, but kotlinx serialises a
        // `Boolean` as a primitive that prints as "true" / "false"; tolerate both forms.
        val inline =
          when (val raw = params?.get("inline")?.jsonPrimitive?.contentOrNull) {
            null -> false
            else -> raw == "true"
          }
        when (val outcome = dataFetchHandler(previewId, kind, perKindParams, inline)) {
          is DataFetchOutcome.Ok -> {
            val payload = buildJsonObject {
              put("kind", outcome.kind)
              put("schemaVersion", outcome.schemaVersion)
              if (outcome.payload != null) put("payload", outcome.payload)
              if (outcome.path != null) put("path", outcome.path)
              if (outcome.bytes != null) put("bytes", outcome.bytes)
            }
            sendResponse(id, payload)
          }
          DataFetchOutcome.Unknown ->
            sendError(id, -32020, "DataProductUnknown: kind not advertised: $kind")
          DataFetchOutcome.NotAvailable ->
            sendError(id, -32021, "DataProductNotAvailable: $previewId has no render available")
          is DataFetchOutcome.FetchFailed -> sendError(id, -32022, outcome.message)
          DataFetchOutcome.BudgetExceeded ->
            sendError(id, -32023, "DataProductBudgetExceeded for kind $kind")
        }
      }
      "data/subscribe",
      "data/unsubscribe" -> {
        val previewId = params?.get("previewId")?.jsonPrimitive?.contentOrNull ?: ""
        val kind = params?.get("kind")?.jsonPrimitive?.contentOrNull ?: ""
        // Validate the kind is in `advertisedDataProducts` and `attachable` — the daemon's real
        // handler does this. Tests that need the failure path can advertise a non-attachable
        // kind; tests that need success advertise an attachable one.
        val capability = advertisedDataProducts.firstOrNull { it.kind == kind }
        if (capability == null || !capability.attachable) {
          sendError(
            id,
            -32020,
            if (capability == null) "DataProductUnknown: kind not advertised: $kind"
            else "DataProductUnknown: kind '$kind' is not attachable",
          )
        } else {
          if (method == "data/subscribe") dataSubscribes.offer(previewId to kind)
          else dataUnsubscribes.offer(previewId to kind)
          sendResponse(id, buildJsonObject { put("ok", true) })
        }
      }
      "recording/start" -> {
        val previewId = params?.get("previewId")?.jsonPrimitive?.contentOrNull ?: ""
        val fps = params?.get("fps")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val scale = params?.get("scale")?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
        val overrides =
          params
            ?.get("overrides")
            ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
            ?.let {
              json.decodeFromJsonElement(
                ee.schimke.composeai.daemon.protocol.PreviewOverrides.serializer(),
                it,
              )
            }
        recordingStarts.offer(
          RecordingStartCall(previewId = previewId, fps = fps, scale = scale, overrides = overrides)
        )
        val recordingId = "fake-rec-${nextRecordingId.getAndIncrement()}"
        sendResponse(
          id,
          json.encodeToJsonElement(
            ee.schimke.composeai.daemon.protocol.RecordingStartResult.serializer(),
            ee.schimke.composeai.daemon.protocol.RecordingStartResult(recordingId = recordingId),
          ),
        )
      }
      "recording/stop" -> {
        val recordingId = params?.get("recordingId")?.jsonPrimitive?.contentOrNull ?: ""
        recordingStops.offer(recordingId)
        sendResponse(
          id,
          json.encodeToJsonElement(
            ee.schimke.composeai.daemon.protocol.RecordingStopResult.serializer(),
            recordingStopResult,
          ),
        )
      }
      "recording/encode" -> {
        val recordingId = params?.get("recordingId")?.jsonPrimitive?.contentOrNull ?: ""
        val format =
          when (params?.get("format")?.jsonPrimitive?.contentOrNull) {
            "mp4" -> ee.schimke.composeai.daemon.protocol.RecordingFormat.MP4
            "webm" -> ee.schimke.composeai.daemon.protocol.RecordingFormat.WEBM
            null,
            "apng" -> ee.schimke.composeai.daemon.protocol.RecordingFormat.APNG
            else -> ee.schimke.composeai.daemon.protocol.RecordingFormat.APNG
          }
        recordingEncodes.offer(RecordingEncodeCall(recordingId, format))
        val (extension, mime) =
          when (format) {
            ee.schimke.composeai.daemon.protocol.RecordingFormat.APNG -> "apng" to "image/apng"
            ee.schimke.composeai.daemon.protocol.RecordingFormat.MP4 -> "mp4" to "video/mp4"
            ee.schimke.composeai.daemon.protocol.RecordingFormat.WEBM -> "webm" to "video/webm"
          }
        // Materialise the canned bytes onto disk so DaemonMcpServer's `Files.readAllBytes` works.
        val dir = recordingEncodeDir ?: java.io.File(System.getProperty("java.io.tmpdir"))
        dir.mkdirs()
        val out = java.io.File(dir, "$recordingId.$extension")
        out.writeBytes(recordingEncodedBytes)
        sendResponse(
          id,
          json.encodeToJsonElement(
            ee.schimke.composeai.daemon.protocol.RecordingEncodeResult.serializer(),
            ee.schimke.composeai.daemon.protocol.RecordingEncodeResult(
              videoPath = out.absolutePath,
              mimeType = mime,
              sizeBytes = out.length(),
            ),
          ),
        )
      }
      else -> {
        // Unknown methods: error response so the client doesn't hang.
        sendError(id, -32601, "method not found: $method")
      }
    }
  }

  /** Test helper — preload `history/list`/`history/read`/`history/diff` results. */
  private val historyEntries: MutableList<JsonObject> = java.util.concurrent.CopyOnWriteArrayList()
  private val historyInlineBytes: MutableMap<String, ByteArray> =
    java.util.concurrent.ConcurrentHashMap()

  fun setHistory(entries: List<JsonObject>) {
    historyEntries.clear()
    historyEntries.addAll(entries)
  }

  fun setHistoryInlineBytes(entryId: String, bytes: ByteArray) {
    historyInlineBytes[entryId] = bytes
  }

  /** Pushes a `historyAdded` notification carrying [entry]. */
  fun emitHistoryAdded(entry: JsonObject) {
    val params = buildJsonObject { put("entry", entry) }
    sendNotification("historyAdded", params)
  }

  private fun handleNotification(method: String, params: JsonObject?) {
    when (method) {
      "initialized" -> {}
      "fileChanged" -> {
        if (params != null) fileChanges.offer(params)
      }
      "setVisible" -> {
        val ids =
          (params?.get("ids") as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            it.jsonPrimitive.contentOrNull
          } ?: emptyList()
        visibleSets.offer(ids)
      }
      "setFocus" -> {
        val ids =
          (params?.get("ids") as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            it.jsonPrimitive.contentOrNull
          } ?: emptyList()
        focusSets.offer(ids)
      }
      "recording/script" -> {
        val recordingId = params?.get("recordingId")?.jsonPrimitive?.contentOrNull ?: ""
        val eventsArr = params?.get("events") as? kotlinx.serialization.json.JsonArray
        val events =
          eventsArr?.map {
            json.decodeFromJsonElement(
              ee.schimke.composeai.daemon.protocol.RecordingScriptEvent.serializer(),
              it,
            )
          } ?: emptyList()
        recordingScripts.offer(RecordingScriptCall(recordingId, events))
      }
      "exit" -> running.set(false)
      else -> {}
    }
  }

  private fun sendResponse(id: Long, result: JsonElement) {
    val payload = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      put("result", result)
    }
    sendFrame(payload.toString())
  }

  private fun sendError(id: Long, code: Int, message: String) {
    val payload = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      putJsonObject("error") {
        put("code", code)
        put("message", message)
      }
    }
    sendFrame(payload.toString())
  }

  private fun sendNotification(method: String, params: JsonElement) {
    val payload = buildJsonObject {
      put("jsonrpc", "2.0")
      put("method", method)
      put("params", params)
    }
    sendFrame(payload.toString())
  }

  private fun sendFrame(jsonText: String) {
    val bytes = jsonText.toByteArray(Charsets.UTF_8)
    val header = "Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
    synchronized(daemonToMcp) {
      daemonToMcp.write(header)
      daemonToMcp.write(bytes)
      daemonToMcp.flush()
    }
  }

  private fun readFrame(input: InputStream): ByteArray? {
    var contentLength = -1
    val buf = ByteArrayOutputStream(64)
    var sawAny = false
    while (true) {
      val line = readHeaderLine(input, buf) ?: return if (sawAny) null else null
      sawAny = true
      if (line.isEmpty()) break
      val colon = line.indexOf(':')
      if (colon <= 0) error("malformed header line: '$line'")
      if (line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
        contentLength = line.substring(colon + 1).trim().toInt()
      }
    }
    if (contentLength < 0) error("missing Content-Length")
    val payload = ByteArray(contentLength)
    var off = 0
    while (off < contentLength) {
      val n = input.read(payload, off, contentLength - off)
      if (n < 0) return null
      off += n
    }
    return payload
  }

  private fun readHeaderLine(input: InputStream, buf: ByteArrayOutputStream): String? {
    buf.reset()
    while (true) {
      val b = input.read()
      if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.US_ASCII.name())
      if (b == '\n'.code) {
        val bytes = buf.toByteArray()
        val end =
          if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1
          else bytes.size
        return String(bytes, 0, end, Charsets.US_ASCII)
      }
      buf.write(b)
    }
  }

  companion object {
    private const val BUFFER = 64 * 1024
  }
}

/** Test [DaemonClientFactory] that always returns a [FakeDaemon]. */
class FakeDaemonClientFactory : DaemonClientFactory {
  /** Map of (workspaceId, modulePath) → most-recent fake. */
  val daemons: MutableMap<Pair<WorkspaceId, String>, FakeDaemon> = mutableMapOf()

  /**
   * Every fake ever spawned by this factory, in spawn order. Useful for respawn-shaped tests (e.g.
   * `classpathDirty` recovery): the first spawn lands at index 0 and the supervisor's replacement
   * after `classpathDirty` lands at index 1.
   */
  val spawnHistory: MutableList<FakeDaemon> = java.util.concurrent.CopyOnWriteArrayList()

  /**
   * Descriptors handed to [spawn], parallel-indexed with [spawnHistory]. SANDBOX-POOL.md Layer 3:
   * the supervisor mutates the descriptor's `systemProperties` to inject
   * `composeai.daemon.sandboxCount`; tests assert against this list to verify the supervisor passed
   * the right pool size to the daemon.
   */
  val spawnDescriptors: MutableList<DaemonLaunchDescriptor> =
    java.util.concurrent.CopyOnWriteArrayList()

  /**
   * Optional pre-`initialize` hook. Invoked synchronously inside [spawn], after the [FakeDaemon] is
   * constructed but before the supervisor wires the client and sends `initialize`. Tests use this
   * to configure per-spawn state (e.g. advertised data-product kinds) that needs to be in place
   * before the initialize round-trip reaches the daemon's reader thread.
   */
  @Volatile var daemonConfigurer: (FakeDaemon) -> Unit = {}

  override fun spawn(project: RegisteredProject, descriptor: DaemonLaunchDescriptor): DaemonSpawn {
    val daemon = FakeDaemon()
    runCatching { daemonConfigurer(daemon) }
    synchronized(this) {
      daemons[project.workspaceId to descriptor.modulePath] = daemon
      spawnHistory.add(daemon)
      spawnDescriptors.add(descriptor)
    }
    return daemon
  }
}

/** Test [DescriptorProvider] returning a stub descriptor for any module. */
class FakeDescriptorProvider : DescriptorProvider {
  override fun descriptorFor(
    project: RegisteredProject,
    modulePath: String,
  ): DaemonLaunchDescriptor =
    DaemonLaunchDescriptor(
      schemaVersion = 1,
      modulePath = modulePath,
      variant = "debug",
      enabled = true,
      mainClass = "fake.Main",
      classpath = emptyList(),
      jvmArgs = emptyList(),
      systemProperties = emptyMap(),
      workingDirectory = project.path.absolutePath,
      manifestPath = "",
    )
}
