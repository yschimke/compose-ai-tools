package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.ChangeType
import ee.schimke.composeai.daemon.protocol.DataFetchParams
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.DataSubscribeResult
import ee.schimke.composeai.daemon.protocol.ExtensionsDisableResult
import ee.schimke.composeai.daemon.protocol.ExtensionsEnableResult
import ee.schimke.composeai.daemon.protocol.ExtensionsListResult
import ee.schimke.composeai.daemon.protocol.FileKind
import ee.schimke.composeai.daemon.protocol.HistoryDiffMode
import ee.schimke.composeai.daemon.protocol.HistoryDiffResult
import ee.schimke.composeai.daemon.protocol.HistoryListParams
import ee.schimke.composeai.daemon.protocol.HistoryListResult
import ee.schimke.composeai.daemon.protocol.HistoryReadResultDto
import ee.schimke.composeai.daemon.protocol.InitializeResult
import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import ee.schimke.composeai.daemon.protocol.Manifest
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RecordingEncodeResult
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import ee.schimke.composeai.daemon.protocol.RecordingStartResult
import ee.schimke.composeai.daemon.protocol.RecordingStopResult
import ee.schimke.composeai.daemon.protocol.RejectedRender
import ee.schimke.composeai.daemon.protocol.RenderNowResult
import ee.schimke.composeai.daemon.protocol.RenderTier
import ee.schimke.composeai.daemon.protocol.ServerCapabilities
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.daemon.protocol.StreamStartResult
import ee.schimke.composeai.data.layoutinspector.ComposeFigmaSvgProduct
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsProduct
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTokens
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsTypography
import ee.schimke.composeai.data.layoutinspector.PreviewSlots
import ee.schimke.composeai.data.theme.Material3ThemeProduct
import ee.schimke.composeai.render.session.NotificationListener
import ee.schimke.composeai.render.session.RenderSession
import ee.schimke.composeai.render.session.RenderSessionBackend
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Shared test [RenderSession]: each [renderNow] writes a PNG (bytes encode the overrides, so
 * distinct overrides → distinct bytes) and synchronously emits the `renderFinished` callers await.
 *
 * [renderHook] (when set) overrides the default emit-immediately behaviour: it receives the 1-based
 * call index and an `emit` that writes given bytes to a fresh PNG and fires `renderFinished`. A
 * hook that emits nothing models a render that times out (the daemon owes a late event), which
 * drives the stale-event path. [rejectAll] rejects every render.
 */
public class FakeRenderSession(
  private val renderRoot: File,
  private val rejectAll: Boolean = false,
  private val renderHook: ((call: Int, emit: (ByteArray) -> Unit) -> Unit)? = null,
  /** When false (default), the streaming methods throw (mimicking a non-streaming backend). */
  private val streaming: Boolean = false,
  /**
   * `StreamStartResult.heldSession` value when [streaming]; false models a non-interactive host.
   */
  private val heldSession: Boolean = true,
  /**
   * `StreamStartResult.fallbackReason` — the daemon's real acquisition failure when [heldSession]
   * is false (e.g. `interactive session already held`). Null models a daemon that sent none.
   */
  private val heldFallbackReason: String? = null,
  /** When set, [streamStart] emits a keyframe with this base64 payload *before* it returns. */
  private val emitKeyframeOnStart: String? = null,
  /**
   * Reject the first N override-bearing [renderNow]s with the daemon's `coalesced` reason (then
   * serve normally), modelling the override-in-flight coalescing the serve host must retry through.
   */
  private val coalescedOverrideRejections: Int = 0,
  /**
   * When true, the figma-svg written per render is a hybrid: an `<image href="figma-raster/…">`
   * plus the sibling crop on disk, to exercise serve-side raster inlining.
   */
  private val hybridSvg: Boolean = false,
  /**
   * The overrides the modelled daemon advertises in its capabilities — drives
   * [ServeRenderHost.gesturesRenderable] (`"gestures" in supportedOverrides`). Empty by default (a
   * desktop-style backend that honours no feature overrides).
   */
  private val supportedOverrides: List<String> = emptyList(),
  /**
   * Whether the modelled daemon has the `compose/figma-svg` (+ `-long`) data products —
   * [ServeRenderHost] enables them on open and gates [ServeRenderHost.hasSvgExport] on the result.
   * True by default (a desktop-style backend that exports figma-svg); false models a backend
   * without it (the ids come back in [ExtensionsEnableResult.unknown]).
   */
  private val figmaSvgAvailable: Boolean = true,
  /**
   * Extension ids the modelled daemon does not carry — [enableExtensions] returns them in
   * [ExtensionsEnableResult.unknown] (e.g. a backend with no Remote Compose runtime rejects
   * `data/remotecompose`). Empty by default.
   */
  private val unknownExtensionIds: Set<String> = emptySet(),
  /**
   * Include named override values in fake PNG/SVG bytes so payload-bearing adapters are testable.
   */
  private val includeNamedOverridesInArtifacts: Boolean = false,
  /**
   * Stubs [fetchData] for kinds this fake doesn't model natively (e.g.
   * `compose/remotecompose-doc`). Consulted first; a non-null return short-circuits the built-in
   * figma-svg / semantics handling.
   */
  private val fetchDataHook: ((previewId: String, kind: String) -> DataFetchResult?)? = null,
) : RenderSession {
  val renderCount = AtomicInteger(0)

  /**
   * Count of `figma-svg-long` fetches + the `params` bag of the last one — for scroll-lane asserts.
   */
  val scrollFetchCount = AtomicInteger(0)
  @Volatile var lastScrollFetchParams: JsonElement? = null

  val scrollPngFetchCount = AtomicInteger(0)
  @Volatile var lastScrollPngFetchParams: JsonElement? = null

  /** Extension ids passed to [enableExtensions], in call order — for assertions. */
  val enabledExtensionIds = CopyOnWriteArrayList<String>()
  val subscribedDataKinds = CopyOnWriteArrayList<String>()
  val unsubscribedDataKinds = CopyOnWriteArrayList<String>()
  @Volatile var lastThemeFetchParams: JsonElement? = null
  private val coalesceRemaining = AtomicInteger(coalescedOverrideRejections)
  private val listeners = CopyOnWriteArrayList<NotificationListener>()
  private val counter = AtomicInteger(0)

  // Streaming spies (only meaningful when streaming = true).
  val streamStarts = AtomicInteger(0)
  val interactiveInputs = CopyOnWriteArrayList<InteractiveInputParams>()
  val streamStops = CopyOnWriteArrayList<String>()
  /** Every `stream/visibility` the lane sent: (frameStreamId, visible, fps). */
  val streamVisibilities = CopyOnWriteArrayList<Triple<String, Boolean, Int?>>()
  /**
   * The overrides the most recent `stream/start` carried — what the live lane actually asked the
   * daemon to compose, as opposed to what the viewer typed. The snapshot lane's twin is already
   * observable through [renderNow]'s PNG bytes; this is the streaming half.
   */
  @Volatile
  var lastStreamOverrides: PreviewOverrides? = null
    private set

  @Volatile
  var lastRenderOverrides: PreviewOverrides? = null
    private set

  @Volatile
  var lastFrameStreamId: String? = null
    private set

  @Volatile
  var lastCodec: StreamCodec? = null
    private set

  @Volatile
  var lastMaxFps: Int? = null
    private set

  private val streamJson = Json { ignoreUnknownKeys = true }

  /** Fire a `streamFrame` notification to registered listeners (test driver for the live lane). */
  fun emitStreamFrame(
    frameStreamId: String,
    seq: Long,
    payloadBase64: String?,
    codec: StreamCodec = StreamCodec.PNG,
  ) {
    val params =
      streamJson
        .encodeToJsonElement(
          StreamFrameParams.serializer(),
          StreamFrameParams(
            frameStreamId = frameStreamId,
            seq = seq,
            ptsMillis = 0,
            widthPx = 2,
            heightPx = 2,
            codec = codec,
            payloadBase64 = payloadBase64,
          ),
        )
        .jsonObject
    listeners.forEach { it.onNotification("streamFrame", params) }
  }

  private fun emitFinished(id: String, bytes: ByteArray) {
    renderRoot.mkdirs()
    val file = File(renderRoot, "$id-${counter.incrementAndGet()}.png").apply { writeBytes(bytes) }
    val params = buildJsonObject {
      put("id", id)
      put("pngPath", file.absolutePath)
    }
    listeners.forEach { it.onNotification("renderFinished", params) }
  }

  /**
   * Fire a `renderFailed` notification — the daemon's terminal event when the render body threw
   * (e.g. a preview whose composition NPEs). Wire shape mirrors the daemon's `RenderFailedParams`
   * (`{id, error: {kind, message}}`). Public so a [renderHook] can model a broken preview; the host
   * must fail that render immediately instead of sleeping out its render budget.
   */
  fun emitFailed(id: String, message: String) {
    val params = buildJsonObject {
      put("id", id)
      put(
        "error",
        buildJsonObject {
          put("kind", "renderBody")
          put("message", message)
        },
      )
    }
    listeners.forEach { it.onNotification("renderFailed", params) }
  }

  override val workspaceRoot: String = renderRoot.absolutePath
  override val modulePath: String = ":sample"
  override val initializeResult: InitializeResult =
    InitializeResult(
      protocolVersion = 2,
      daemonVersion = "fake",
      pid = 0,
      capabilities =
        ServerCapabilities(
          incrementalDiscovery = false,
          sandboxRecycle = false,
          leakDetection = emptyList(),
          supportedOverrides = supportedOverrides,
        ),
      classpathFingerprint = "",
      manifest = Manifest(path = "", previewCount = 0),
    )
  override val backendKind: RenderSessionBackend = RenderSessionBackend.Subprocess

  override fun setVisible(previewIds: List<String>) = Unit

  override fun setFocus(previewIds: List<String>) = Unit

  override fun fileChanged(path: String, kind: FileKind, changeType: ChangeType) = Unit

  override fun renderNow(
    previewIds: List<String>,
    tier: RenderTier,
    reason: String?,
    overrides: PreviewOverrides?,
    timeout: kotlin.time.Duration,
  ): RenderNowResult {
    lastRenderOverrides = overrides
    val call = renderCount.incrementAndGet()
    val id = previewIds.single()
    if (rejectAll) {
      return RenderNowResult(queued = emptyList(), rejected = listOf(RejectedRender(id, "nope")))
    }
    if (overrides != null && coalesceRemaining.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) {
      return RenderNowResult(
        queued = emptyList(),
        rejected =
          listOf(
            RejectedRender(
              id,
              "coalesced: override-bearing render already in flight for this previewId",
            )
          ),
      )
    }
    val hook = renderHook
    if (hook != null) {
      hook(call) { bytes -> emitFinished(id, bytes) }
    } else {
      val content =
        "png:${overrides?.uiMode}:${overrides?.localeTag}:${overrides?.device}" +
          namedOverrideSuffix(overrides)
      emitFinished(id, content.toByteArray())
      // Model the daemon writing the figma-svg to a shared per-preview path as a side effect of the
      // same render — overwritten each time, so distinct overrides overwrite one another's SVG.
      writeFigmaSvg(id, overrides)
      // Likewise the compose/semantics tree, carrying two `dp-slot:` markers so the slots lane has
      // something to extract.
      writeSemantics(id)
    }
    return RenderNowResult(queued = previewIds, rejected = emptyList())
  }

  private fun writeFigmaSvg(id: String, overrides: PreviewOverrides?) {
    val previewDir = File(renderRoot, id).apply { mkdirs() }
    if (hybridSvg) {
      File(previewDir, "figma-raster").mkdirs()
      File(previewDir, "figma-raster/node0.png").writeBytes(byteArrayOf(1, 2, 3))
      File(previewDir, ComposeFigmaSvgProduct.FILE_SVG)
        .writeText("<svg><image href=\"figma-raster/node0.png\"/></svg>")
    } else {
      val svg =
        "svg:${overrides?.uiMode}:${overrides?.localeTag}:${overrides?.device}" +
          namedOverrideSuffix(overrides)
      File(previewDir, ComposeFigmaSvgProduct.FILE_SVG).writeText(svg)
    }
  }

  private fun namedOverrideSuffix(overrides: PreviewOverrides?): String =
    if (includeNamedOverridesInArtifacts) ":${overrides?.namedOverrides.orEmpty()}" else ""

  private fun writeSemantics(id: String) {
    val previewDir = File(renderRoot, id).apply { mkdirs() }
    val payload =
      ComposeSemanticsPayload(
        root =
          ComposeSemanticsNode(
            nodeId = "0",
            boundsInRoot = "0,0,200,100",
            children =
              listOf(
                ComposeSemanticsNode(
                  nodeId = "1",
                  boundsInRoot = "8,8,40,40",
                  testTag = "${PreviewSlots.SLOT_TAG_PREFIX}leadingIcon",
                  // Container tokens: the theme inspection layer's input.
                  tokens =
                    ComposeSemanticsTokens(
                      backgroundColor = "#FF6750A4",
                      cornerRadius = "12.0dp",
                      borderColor = "#FF79747E",
                      borderWidth = "1.0dp",
                    ),
                ),
                ComposeSemanticsNode(
                  nodeId = "2",
                  boundsInRoot = "48,44,192,64",
                  testTag = "${PreviewSlots.SLOT_TAG_PREFIX}supporting",
                  // Resolved type: the typography inspection layer's input.
                  text = "Supporting text",
                  typography =
                    ComposeSemanticsTypography(
                      fontSize = "14.0sp",
                      lineHeight = "20.0sp",
                      fontFamily = "/fonts/Roboto-Medium.ttf",
                      fontWeight = 500,
                    ),
                ),
              ),
          )
      )
    File(previewDir, ComposeSemanticsProduct.FILE)
      .writeText(streamJson.encodeToString(ComposeSemanticsPayload.serializer(), payload))
  }

  override fun fetchData(
    previewId: String,
    kind: String,
    inline: Boolean,
    params: JsonElement?,
    timeout: kotlin.time.Duration,
  ): DataFetchResult {
    if (kind == Material3ThemeProduct.KIND) lastThemeFetchParams = params
    fetchDataHook?.invoke(previewId, kind)?.let {
      return it
    }
    if (kind == ComposeFigmaSvgProduct.KIND) {
      val file = File(renderRoot, "$previewId/${ComposeFigmaSvgProduct.FILE_SVG}")
      return DataFetchResult(
        kind = kind,
        schemaVersion = ComposeFigmaSvgProduct.SCHEMA_VERSION,
        path = file.absolutePath,
      )
    }
    if (kind == ComposeFigmaSvgProduct.KIND_LONG) {
      // Model the daemon's `requiresRerender` full-page export: the fetch itself produces the file,
      // reflecting the overrides threaded through the `params` bag (as the real re-render does) so
      // the serve host's override-awareness is observable.
      scrollFetchCount.incrementAndGet()
      lastScrollFetchParams = params
      val o =
        params?.jsonObject?.get(DataFetchParams.PARAM_OVERRIDES)?.let {
          Json.decodeFromJsonElement(PreviewOverrides.serializer(), it)
        }
      val previewDir = File(renderRoot, previewId).apply { mkdirs() }
      val file = File(previewDir, ComposeFigmaSvgProduct.FILE_SVG_LONG)
      file.writeText("svg-long:$previewId:${o?.uiMode}:${o?.localeTag}:${o?.device}")
      return DataFetchResult(
        kind = kind,
        schemaVersion = ComposeFigmaSvgProduct.SCHEMA_VERSION,
        path = file.absolutePath,
      )
    }
    if (kind == ServeRenderHost.SCROLL_LONG_KIND) {
      scrollPngFetchCount.incrementAndGet()
      lastScrollPngFetchParams = params
      val o =
        params?.jsonObject?.get(DataFetchParams.PARAM_OVERRIDES)?.let {
          Json.decodeFromJsonElement(PreviewOverrides.serializer(), it)
        }
      val file = File(renderRoot, "scroll-long/$previewId.png")
      file.parentFile.mkdirs()
      file.writeText("png-long:$previewId:${o?.uiMode}:${o?.localeTag}:${o?.device}")
      return DataFetchResult(kind = kind, schemaVersion = 1, path = file.absolutePath)
    }
    if (kind == ComposeSemanticsProduct.KIND) {
      val file = File(renderRoot, "$previewId/${ComposeSemanticsProduct.FILE}")
      return DataFetchResult(
        kind = kind,
        schemaVersion = ComposeSemanticsProduct.SCHEMA_VERSION,
        path = file.absolutePath,
      )
    }
    error("unused")
  }

  override fun subscribeData(
    previewId: String,
    kind: String,
    params: JsonElement?,
    timeout: kotlin.time.Duration,
  ): DataSubscribeResult {
    subscribedDataKinds += kind
    return DataSubscribeResult.OK
  }

  override fun unsubscribeData(
    previewId: String,
    kind: String,
    timeout: kotlin.time.Duration,
  ): DataSubscribeResult {
    subscribedDataKinds.remove(kind)
    unsubscribedDataKinds += kind
    return DataSubscribeResult.OK
  }

  override fun listExtensions(timeout: kotlin.time.Duration): ExtensionsListResult = error("unused")

  override fun enableExtensions(
    ids: List<String>,
    timeout: kotlin.time.Duration,
  ): ExtensionsEnableResult {
    enabledExtensionIds.addAll(ids)
    val figmaKinds = setOf(ComposeFigmaSvgProduct.KIND, ComposeFigmaSvgProduct.KIND_LONG)
    // A backend without figma-svg reports those ids as unknown, as does any id in
    // [unknownExtensionIds]; everything else enables.
    val unknown = ids.filter {
      it in unknownExtensionIds || (!figmaSvgAvailable && it in figmaKinds)
    }
    val enabled = ids - unknown.toSet()
    val dataProducts =
      if (ServeRenderHost.SCROLL_EXTENSION_ID in enabled) {
        listOf(
          DataProductCapability(
            kind = ServeRenderHost.SCROLL_LONG_KIND,
            schemaVersion = 1,
            transport = DataProductTransport.PATH,
            attachable = false,
            fetchable = true,
            requiresRerender = true,
          )
        )
      } else {
        emptyList()
      }
    return ExtensionsEnableResult(
      newlyEnabled = enabled,
      unknown = unknown,
      dataProducts = dataProducts,
    )
  }

  override fun disableExtensions(
    ids: List<String>,
    timeout: kotlin.time.Duration,
  ): ExtensionsDisableResult = error("unused")

  override fun historyList(
    params: HistoryListParams,
    timeout: kotlin.time.Duration,
  ): HistoryListResult = error("unused")

  override fun historyRead(
    entryId: String,
    inline: Boolean,
    timeout: kotlin.time.Duration,
  ): HistoryReadResultDto = error("unused")

  override fun historyDiff(
    fromId: String,
    toId: String,
    mode: HistoryDiffMode,
    timeout: kotlin.time.Duration,
  ): HistoryDiffResult = error("unused")

  override fun recordingStart(
    previewId: String,
    fps: Int?,
    scale: Float?,
    overrides: PreviewOverrides?,
    timeout: kotlin.time.Duration,
  ): RecordingStartResult = error("unused")

  override fun recordingScript(recordingId: String, events: List<RecordingScriptEvent>) =
    error("unused")

  override fun recordingStop(
    recordingId: String,
    timeout: kotlin.time.Duration,
  ): RecordingStopResult = error("unused")

  override fun recordingEncode(
    recordingId: String,
    format: RecordingFormat,
    timeout: kotlin.time.Duration,
  ): RecordingEncodeResult = error("unused")

  override fun streamStart(
    previewId: String,
    codec: StreamCodec?,
    maxFps: Int?,
    overrides: PreviewOverrides?,
    timeout: kotlin.time.Duration,
  ): StreamStartResult {
    if (!streaming) throw UnsupportedOperationException("streaming not supported")
    val fsid = "fs-${streamStarts.incrementAndGet()}"
    lastFrameStreamId = fsid
    lastCodec = codec
    lastMaxFps = maxFps
    lastStreamOverrides = overrides
    // Model a daemon that emits the initial keyframe before the RPC response returns.
    emitKeyframeOnStart?.let { emitStreamFrame(fsid, seq = 0, payloadBase64 = it) }
    return StreamStartResult(
      frameStreamId = fsid,
      codec = codec ?: StreamCodec.PNG,
      heldSession = heldSession,
      fallbackReason = heldFallbackReason,
    )
  }

  override fun streamStop(frameStreamId: String) {
    streamStops.add(frameStreamId)
  }

  override fun streamVisibility(frameStreamId: String, visible: Boolean, fps: Int?) {
    streamVisibilities.add(Triple(frameStreamId, visible, fps))
  }

  override fun interactiveInput(
    frameStreamId: String,
    kind: InteractiveInputKind,
    pixelX: Int?,
    pixelY: Int?,
    pointerId: Int?,
    scrollDeltaY: Float?,
    keyCode: String?,
    text: String?,
    pointerType: String?,
  ) {
    interactiveInputs.add(
      InteractiveInputParams(
        frameStreamId = frameStreamId,
        kind = kind,
        pixelX = pixelX,
        pixelY = pixelY,
        pointerId = pointerId,
        scrollDeltaY = scrollDeltaY,
        keyCode = keyCode,
        text = text,
        pointerType = pointerType,
      )
    )
  }

  override fun onNotification(listener: NotificationListener): AutoCloseable {
    listeners.add(listener)
    return AutoCloseable { listeners.remove(listener) }
  }

  /** Set by [close] so a test can assert the session was actually reaped. */
  @Volatile
  var closed: Boolean = false
    private set

  override fun close() {
    closed = true
  }
}
