package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.data.layoutinspector.ComposeFigmaSvgProduct
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsProduct
import ee.schimke.composeai.data.layoutinspector.PreviewSlots
import ee.schimke.composeai.data.layoutinspector.PreviewSlotsPayload
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.render.session.RenderSession
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.RenderSessionException
import ee.schimke.composeai.render.session.RenderSessionFactory
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * A live daemon-backed frame stream. Forward input into the held composition via [input]; [close]
 * tears the stream down. Obtained from [ServeRenderHost.startStream].
 */
interface StreamHandle : AutoCloseable {
  fun input(
    kind: InteractiveInputKind,
    pixelX: Int? = null,
    pixelY: Int? = null,
    pointerId: Int? = null,
    scrollDeltaY: Float? = null,
    keyCode: String? = null,
  )
}

/** One servable preview: its id, a human label, and which delivery modes it supports. */
data class ServePreview(
  val id: String,
  val label: String,
  /** Delivery transports available for this preview. Tier 1 is always [PreviewMode.SNAPSHOT]. */
  val modes: List<PreviewMode> = listOf(PreviewMode.SNAPSHOT),
  /**
   * The author-declared editable knobs this preview exposed via `previewOverride*` (the
   * `compose/overrides` payload). Populated from a bundle's `previews/<id>.overrides.json` sidecar
   * so the viewer can present editable controls (label / list length / per-item indexed values).
   * Empty when the preview declared none (or the host doesn't carry them).
   */
  val overrides: List<ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration> = emptyList(),
  /**
   * Whether this preview supports **keyboard focus** — it carries `@FocusedPreview` (discovery
   * emits a `focus` capture). Lets the viewer offer a "Keyboard focus" control *only* for previews
   * that actually have something focusable, rather than as a dead control everywhere. Applied live
   * via the `focus` override (daemon-only); the desktop daemon honours it.
   */
  val supportsFocus: Boolean = false,
  /**
   * Whether this preview supports **one-handed (wear) gestures** — it carries `@GestureHintPreview`
   * (discovery emits a `gestureHint` capture). Surfaced for detection, but the gesture *override*
   * is Android-only (the desktop daemon behind `serve` ignores `overrides.gestures`), so the viewer
   * gates the control to Android-backed sessions.
   */
  val supportsGestures: Boolean = false,
)

/**
 * Detected per-preview feature support, folded across a discovery
 * [ee.schimke.composeai.cli.PreviewInfo]'s captures: keyboard focus (`@FocusedPreview` → a
 * `focus`/`focusGif` capture) and one-handed gestures (`@GestureHintPreview` → a `gestureHint`
 * capture). Returns the two booleans the viewer gates its feature controls on. A preview with
 * neither annotation yields `(false, false)`.
 */
fun detectedFeaturesOf(preview: ee.schimke.composeai.cli.PreviewInfo): Pair<Boolean, Boolean> {
  val focus = preview.captures.any { it.focus != null || it.focusGif != null }
  val gestures = preview.captures.any { it.gestureHint != null }
  return focus to gestures
}

/**
 * One app-declared `@ThemeCatalog` theme this session can render an arbitrary preview under — the
 * discrete-theme counterpart of the built-in light/dark axis. Discovered as a module-global set (a
 * theme applies to every preview, not one), so it hangs off [ServeHost.declaredThemes] rather than
 * [ServePreview]. [providerFqn] is the `PreviewWrapperProvider` FQN sent verbatim as the
 * `themeProvider` override; [name] is the human label; [group] buckets related themes (a brand).
 */
data class ServeTheme(val name: String, val providerFqn: String, val group: String? = null)

/**
 * Extract the module's declared `@ThemeCatalog` themes from a discovery manifest's preview list.
 * Discovery materializes each annotated `PreviewWrapperProvider` as a synthetic `THEME_CATALOG`
 * preview carrying the provider FQN on `params.wrapperClassName` plus its `name` / `group`; this
 * lifts those into [ServeTheme]s (the module-global theme options) without disturbing the ordinary
 * preview cards. Entries missing a provider FQN are skipped (nothing to apply). Deduped by FQN.
 */
fun declaredThemesFromPreviews(
  previews: List<ee.schimke.composeai.cli.PreviewInfo>
): List<ServeTheme> =
  previews
    .filter { it.params.kind == "THEME_CATALOG" }
    .mapNotNull { p ->
      val fqn = p.params.wrapperClassName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
      ServeTheme(
        name = p.params.name?.takeIf { it.isNotBlank() } ?: p.functionName.ifBlank { p.id },
        providerFqn = fqn,
        group = p.params.group?.takeIf { it.isNotBlank() },
      )
    }
    .distinctBy { it.providerFqn }

/** Result of a snapshot render request. */
sealed interface RenderOutcome {
  data class Ok(val png: ByteArray) : RenderOutcome

  /** No such preview id in this session's module. */
  data object NotFound : RenderOutcome

  /** The render was attempted but rejected / failed / timed out. [reason] is human-readable. */
  data class Failed(val reason: String) : RenderOutcome
}

/** Result of a figma-svg render request — the SVG counterpart of [RenderOutcome]. */
sealed interface SvgOutcome {
  data class Ok(val svg: ByteArray) : SvgOutcome

  /** No such preview id, or this host can't produce SVG (a static bundle has no daemon). */
  data object NotFound : SvgOutcome

  /** The render or SVG export was attempted but failed. [reason] is human-readable. */
  data class Failed(val reason: String) : SvgOutcome
}

/**
 * Result of a preview-slots request — the [PreviewSlotsPayload] JSON counterpart of
 * [RenderOutcome].
 */
sealed interface SlotsOutcome {
  data class Ok(val json: ByteArray) : SlotsOutcome

  /** No such preview id, or this host can't extract slots (a static bundle has no daemon). */
  data object NotFound : SlotsOutcome

  /** The render or semantics fetch was attempted but failed. [reason] is human-readable. */
  data class Failed(val reason: String) : SlotsOutcome
}

/**
 * Long-lived, thread-safe wrapper around **one** [RenderSession], fronting it for the
 * `compose-preview serve` HTTP server. The long-lived sibling of
 * [ee.schimke.composeai.cli.MatrixRenderFetcher]: same `renderNow` + await-`renderFinished` +
 * read-PNG sequence, but the session is held for the server's lifetime and shared across all
 * connected clients.
 *
 * ## Multi-client + serialisation
 *
 * The host holds **no per-client state** — any number of browsers can hit it concurrently. The
 * daemon renders one-at-a-time per session and [RenderSession] is not promised thread-safe, so all
 * renders funnel through one [renderLock]; a [cache] keyed by `(previewId, overrides)` means
 * identical concurrent requests coalesce to a single render and every later request is a cache hit.
 *
 * ## Preview switching
 *
 * Bound to a module, not a single preview: [previews] is the whole servable set and [render] takes
 * any id in it, so switching previews is just a different request — no session churn.
 */
class ServeRenderHost
internal constructor(
  private val session: RenderSession,
  override val previews: List<ServePreview>,
  /** Human label for this tenant (e.g. the module's Gradle path); shown in the served pages. */
  override val label: String = "",
  /** App-declared `@ThemeCatalog` themes discovered for this module (module-global). */
  override val declaredThemes: List<ServeTheme> = emptyList(),
  private val fileSystem: FileSystem = SystemFileSystem,
  private val onLog: (String) -> Unit = {},
  private val renderTimeoutSeconds: Long = RENDER_TIMEOUT_SECONDS,
  private val frameRenderTimeoutSeconds: Long = FRAME_RENDER_TIMEOUT_SECONDS,
) : ServeHost {

  // A daemon backs this host, so an override edit actually re-renders (unlike a static bundle).
  override val canApplyOverrides: Boolean = true

  // The daemon can export a `compose/figma-svg` for any preview, so the viewer can offer an SVG
  // download link alongside the PNG.
  override val hasSvgExport: Boolean = true

  // The one-handed gesture override is honoured only by the Android (Robolectric) backend — the
  // desktop backend ignores `overrides.gestures`. Read the daemon's advertised capabilities so the
  // viewer offers the "Show gesture hints" control only when it would actually re-render.
  override val gesturesRenderable: Boolean =
    "gestures" in session.initializeResult.capabilities.supportedOverrides

  private val previewIds: Set<String> = previews.map { it.id }.toHashSet()

  // Decodes streamFrame notification params for the live-stream lane (startStream).
  private val streamJson = Json { ignoreUnknownKeys = true }

  // Bounded LRU of rendered PNGs keyed by ServeOverrides.cacheKey. A dev-facing server fronting one
  // module won't accumulate many distinct (preview × overrides) combos, so a small cap is plenty.
  private val cache = LruByteCache(MAX_CACHE_ENTRIES)

  // The figma-svg counterpart of [cache], keyed the same way (previewId × overrides).
  private val svgCache = LruByteCache(MAX_CACHE_ENTRIES)

  // The full-page (scrolling) figma-svg counterpart of [svgCache], keyed the same way.
  private val scrollSvgCache = LruByteCache(MAX_CACHE_ENTRIES)

  // The preview-slots counterpart of [cache], keyed the same way (previewId × overrides).
  private val slotsCache = LruByteCache(MAX_CACHE_ENTRIES)

  // Decodes a fetched compose/semantics payload and encodes the slots response; tolerant of the
  // schema's additive fields (a v7 file read by this v-agnostic slot extractor).
  private val dataJson = Json { ignoreUnknownKeys = true }

  private val renderLock = ReentrantLock()

  // Set under renderLock immediately before each renderNow; the (single) in-flight render's
  // renderFinished notification fills pngPath and trips the latch. Safe because the lock guarantees
  // exactly one render in flight at a time.
  private val pendingLatch = AtomicReference<CountDownLatch?>(null)
  private val pendingPreviewId = AtomicReference<String?>(null)
  private val pendingPngPath = AtomicReference<String?>(null)

  // Count of timed-out renders per preview id whose `renderFinished` is still outstanding. A render
  // that timed out releases the lock, but the daemon still emits that render's `renderFinished`
  // later; since the notification carries only the preview id (no per-render correlation id), a
  // stale event for the same id would otherwise complete the *next* same-id render's latch and
  // cache
  // the wrong PNG under the new override key. The daemon delivers `renderFinished` reliably and in
  // order per session (the S4 harness tests assert none are lost / reordered), so we drain exactly
  // one outstanding event per timed-out render here before honouring a fresh one.
  private val staleRenders = ConcurrentHashMap<String, Int>()

  // The first render after the session opens pays Skiko/JVM cold start, so it gets the generous
  // [renderTimeoutSeconds] budget; once one render has succeeded, each subsequent frame is capped
  // at
  // [frameRenderTimeoutSeconds] so a single wedged render can't hold the only render slot.
  private val warmedUp = AtomicBoolean(false)

  // Fans one upstream daemon stream out to all watchers of the same preview/overrides/codec/fps, so
  // many browsers cost one held session instead of one each. Built on [startStream]; shared because
  // there's one host per server.
  private val broadcast = ServeBroadcastHub(::startStream)

  private val closed = AtomicBoolean(false)
  private val notificationHandle: AutoCloseable = session.onNotification { method, params ->
    if (method != "renderFinished" || params == null) return@onNotification
    val id = params["id"]?.jsonPrimitive?.contentOrNull ?: return@onNotification
    // Drain the late event of a previously timed-out render (FIFO: it arrives before the current
    // render's own event) so it can't complete a fresh same-id render's latch with a stale PNG.
    if ((staleRenders[id] ?: 0) > 0) {
      staleRenders.compute(id) { _, v -> ((v ?: 0) - 1).takeIf { it > 0 } }
      return@onNotification
    }
    if (id != pendingPreviewId.get()) return@onNotification
    // `unchanged` renders still carry a (re-used) pngPath, so this captures bytes either way.
    params["pngPath"]?.jsonPrimitive?.contentOrNull?.let { pendingPngPath.set(it) }
    pendingLatch.get()?.countDown()
  }

  /** Render [previewId] at [overrides], serving a cached result when one exists. Thread-safe. */
  override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
    check(!closed.get()) { "ServeRenderHost is closed" }
    if (previewId !in previewIds) return RenderOutcome.NotFound

    val key = ServeOverrides.cacheKey(previewId, overrides)
    cache.get(key)?.let {
      return RenderOutcome.Ok(it)
    }

    return renderLock.withLock {
      // Double-check: another request may have filled the cache while we waited for the lock.
      cache.get(key)?.let {
        return@withLock RenderOutcome.Ok(it)
      }

      // The daemon coalesces an override-bearing `renderNow` whose previewId already has one in
      // flight, expecting the client to resubmit once it clears. Because the daemon clears that
      // flag
      // on (just after) `renderFinished`, the very next serialised render here can momentarily race
      // the not-yet-cleared flag and get rejected. Honour the daemon's retry contract with a
      // bounded
      // backoff instead of surfacing it to the browser as a 500.
      var attempt = 0
      while (true) {
        val latch = CountDownLatch(1)
        pendingLatch.set(latch)
        pendingPreviewId.set(previewId)
        pendingPngPath.set(null)

        val ack =
          try {
            session.renderNow(
              previewIds = listOf(previewId),
              reason = "serve",
              overrides = overrides,
              timeout = RENDER_ACK_TIMEOUT,
            )
          } catch (e: RenderSessionException) {
            val reason = "renderNow failed: ${e.message}"
            onLog(reason)
            return@withLock RenderOutcome.Failed(reason)
          }

        val rejected = ack.rejected.firstOrNull { it.id == previewId }
        if (rejected != null) {
          if (rejected.reason.startsWith("coalesced") && attempt++ < MAX_COALESCED_RETRIES) {
            Thread.sleep(COALESCED_RETRY_BACKOFF_MS)
            continue
          }
          val reason = "render rejected: ${rejected.reason}"
          onLog(reason)
          return@withLock RenderOutcome.Failed(reason)
        }

        // Cold start gets the generous budget; every frame after the first is capped so a wedged
        // render can't pin the slot.
        val budget = if (warmedUp.get()) frameRenderTimeoutSeconds else renderTimeoutSeconds
        if (!latch.await(budget, TimeUnit.SECONDS)) {
          // The daemon still owes this queued render a `renderFinished`; record it so the late
          // event
          // is drained instead of completing a future same-id render with a stale PNG.
          staleRenders.merge(previewId, 1, Int::plus)
          val reason = "timed out after ${budget}s waiting for render"
          onLog(reason)
          return@withLock RenderOutcome.Failed(reason)
        }
        warmedUp.set(true)
        break
      }

      val path = pendingPngPath.get()
      val bytes =
        path
          ?.toPath()
          ?.takeIf { fileSystem.exists(it) }
          ?.let { p -> fileSystem.read(p) { readByteArray() } }
      if (bytes == null) {
        val reason = "render produced no PNG"
        onLog(reason)
        return@withLock RenderOutcome.Failed(reason)
      }

      cache.put(key, bytes)
      RenderOutcome.Ok(bytes)
    }
  }

  /**
   * Render [previewId] at [overrides] and return its **figma-svg** export (`compose/figma-svg`),
   * serving a cached result when one exists. Thread-safe.
   *
   * The daemon writes the SVG to a per-preview path as a side effect of the *same* render that
   * produces the PNG, so this renders (reusing [render] and its retry/timeout handling) and then
   * fetches the just-written SVG. Both happen under [renderLock] with the PNG cache entry evicted
   * first: the SVG file is shared per preview and overwritten by every render, so it must be
   * fetched in the same critical section as the render that produced it — a PNG cache hit would
   * otherwise skip the render and leave a prior render's (stale) SVG on disk.
   */
  override fun renderSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome {
    check(!closed.get()) { "ServeRenderHost is closed" }
    if (previewId !in previewIds) return SvgOutcome.NotFound

    val key = ServeOverrides.cacheKey(previewId, overrides)
    svgCache.get(key)?.let {
      return SvgOutcome.Ok(it)
    }

    return renderLock.withLock {
      svgCache.get(key)?.let {
        return@withLock SvgOutcome.Ok(it)
      }

      // Force a fresh render of these overrides so the shared per-preview SVG file on disk is
      // theirs; the held lock keeps any other render from overwriting it before the fetch below.
      cache.remove(key)
      when (val pngOutcome = render(previewId, overrides)) {
        RenderOutcome.NotFound -> return@withLock SvgOutcome.NotFound
        is RenderOutcome.Failed -> return@withLock SvgOutcome.Failed(pngOutcome.reason)
        is RenderOutcome.Ok -> {} // rendered; the SVG for these overrides is now on disk
      }

      val svgPath =
        try {
          session.fetchData(previewId, ComposeFigmaSvgProduct.KIND).path?.toPath()
        } catch (e: Exception) {
          val reason = "figma-svg fetch failed: ${e.message}"
          onLog(reason)
          return@withLock SvgOutcome.Failed(reason)
        }
      val raw =
        svgPath
          ?.takeIf { fileSystem.exists(it) }
          ?.let { p -> fileSystem.read(p) { readByteArray() } }
      if (raw == null || svgPath == null) {
        val reason = "render produced no SVG"
        onLog(reason)
        return@withLock SvgOutcome.Failed(reason)
      }

      // Inline any hybrid figma-raster crops so the served SVG is self-contained (a vector-only SVG
      // passes through untouched); Figma's importer can't resolve external hrefs.
      val bytes = inlineRasters(svgPath, raw)
      svgCache.put(key, bytes)
      SvgOutcome.Ok(bytes)
    }
  }

  /**
   * Render [previewId]'s **full-page** figma-svg (`compose/figma-svg-long`) at [overrides], serving
   * a cached result when one exists. Thread-safe.
   *
   * Unlike [renderSvg] this fetches the `requiresRerender = true` long kind directly:
   * `session.fetchData` drives the daemon's `figma-svg-long` re-render (an expanded-viewport render
   * that composes the whole list) and returns the written SVG path — so there's no separate PNG
   * render to force first. Still serialised through [renderLock] with the fetch reading the file
   * the re-render just wrote.
   */
  override fun renderScrollSvg(previewId: String, overrides: PreviewOverrides): SvgOutcome {
    check(!closed.get()) { "ServeRenderHost is closed" }
    if (previewId !in previewIds) return SvgOutcome.NotFound

    val key = ServeOverrides.cacheKey(previewId, overrides)
    scrollSvgCache.get(key)?.let {
      return SvgOutcome.Ok(it)
    }

    return renderLock.withLock {
      scrollSvgCache.get(key)?.let {
        return@withLock SvgOutcome.Ok(it)
      }

      val svgPath =
        try {
          session.fetchData(previewId, ComposeFigmaSvgProduct.KIND_LONG).path?.toPath()
        } catch (e: Exception) {
          val reason = "figma-svg-long fetch failed: ${e.message}"
          onLog(reason)
          return@withLock SvgOutcome.Failed(reason)
        }
      val raw =
        svgPath
          ?.takeIf { fileSystem.exists(it) }
          ?.let { p -> fileSystem.read(p) { readByteArray() } }
      if (raw == null || svgPath == null) {
        val reason = "render produced no full-page SVG"
        onLog(reason)
        return@withLock SvgOutcome.Failed(reason)
      }

      val bytes = inlineRasters(svgPath, raw)
      scrollSvgCache.put(key, bytes)
      SvgOutcome.Ok(bytes)
    }
  }

  /**
   * Inline a hybrid SVG's sibling `figma-raster/<node>.png` crops as `data:` URIs so the served SVG
   * is self-contained — the Figma importer (and any consumer that can't resolve external hrefs)
   * needs every layer embedded. A vector-only SVG has no such refs and passes through. Shares the
   * inlining with the static catalog path via {@link inlineFigmaRasters}.
   */
  private fun inlineRasters(svgPath: okio.Path, raw: ByteArray): ByteArray {
    val dir = svgPath.parent ?: return raw
    return inlineFigmaRasters(fileSystem, dir, raw.decodeToString()).encodeToByteArray()
  }

  /**
   * Render [previewId] at [overrides] and return its declared **preview slots** as
   * [PreviewSlotsPayload] JSON, serving a cached result when one exists. Thread-safe.
   *
   * The slots are the `dp-slot:<name>` markers the preview's author placed (see [PreviewSlots]);
   * they're captured into the `compose/semantics` tree of the *same* render that produces the PNG,
   * with their absolute-to-root bounds. Like [renderSvg] this renders (reusing [render] and its
   * retry/timeout handling) then fetches the just-written product, both under [renderLock] with the
   * PNG cache entry evicted first — the semantics file is shared per preview and overwritten by
   * every render, so it must be read in the same critical section as the render that produced it.
   */
  override fun renderSlots(previewId: String, overrides: PreviewOverrides): SlotsOutcome {
    check(!closed.get()) { "ServeRenderHost is closed" }
    if (previewId !in previewIds) return SlotsOutcome.NotFound

    val key = ServeOverrides.cacheKey(previewId, overrides)
    slotsCache.get(key)?.let {
      return SlotsOutcome.Ok(it)
    }

    return renderLock.withLock {
      slotsCache.get(key)?.let {
        return@withLock SlotsOutcome.Ok(it)
      }

      // Force a fresh render of these overrides so the shared per-preview semantics file on disk is
      // theirs; the held lock keeps any other render from overwriting it before the fetch below.
      cache.remove(key)
      when (val pngOutcome = render(previewId, overrides)) {
        RenderOutcome.NotFound -> return@withLock SlotsOutcome.NotFound
        is RenderOutcome.Failed -> return@withLock SlotsOutcome.Failed(pngOutcome.reason)
        is RenderOutcome.Ok -> {} // rendered; the semantics for these overrides is now on disk
      }

      val payload =
        try {
          fetchSemantics(previewId)
        } catch (e: Exception) {
          val reason = "compose/semantics fetch failed: ${e.message}"
          onLog(reason)
          return@withLock SlotsOutcome.Failed(reason)
        } ?: return@withLock SlotsOutcome.Failed("render produced no semantics")

      val slots = PreviewSlots.extractSlots(payload)
      val json =
        dataJson
          .encodeToString(PreviewSlotsPayload.serializer(), PreviewSlotsPayload(previewId, slots))
          .encodeToByteArray()
      slotsCache.put(key, json)
      SlotsOutcome.Ok(json)
    }
  }

  /**
   * Fetch and decode the freshly written `compose/semantics` tree for [previewId] from whichever
   * transport the session used (inline payload or an on-disk path); null when the fetch yielded
   * neither. Callers hold [renderLock] so the file read matches the render that produced it.
   */
  private fun fetchSemantics(previewId: String): ComposeSemanticsPayload? {
    val result = session.fetchData(previewId, ComposeSemanticsProduct.KIND)
    result.payload?.let {
      return dataJson.decodeFromJsonElement(ComposeSemanticsPayload.serializer(), it)
    }
    val path = result.path?.toPath()?.takeIf { fileSystem.exists(it) } ?: return null
    val text = fileSystem.read(path) { readUtf8() }
    return dataJson.decodeFromString(ComposeSemanticsPayload.serializer(), text)
  }

  /**
   * Try to open a daemon-backed live stream for [previewId] (tier-2). On success the daemon pushes
   * `streamFrame` notifications; each is decoded and handed to [onFrame], and the returned
   * [StreamHandle] forwards input + tears the stream down on close. Returns **null** when streaming
   * is unsupported (older daemon / backend without held compositions, or a `stream/start` that
   * couldn't allocate a held session) so the caller falls back to the [render]-per-frame lane.
   * Independent of the snapshot render lock — a held stream runs concurrently with snapshot
   * renders.
   */
  fun startStream(
    previewId: String,
    overrides: PreviewOverrides,
    codec: StreamCodec? = null,
    maxFps: Int? = null,
    onFrame: (StreamFrameParams) -> Unit,
  ): StreamHandle? {
    check(!closed.get()) { "ServeRenderHost is closed" }
    if (previewId !in previewIds) return null

    // Register the listener BEFORE stream/start: the daemon's frame loop can emit the initial
    // keyframe before the RPC response returns, and missing it leaves static previews blank (later
    // frames are payload-less `unchanged` heartbeats). We don't know the frameStreamId yet, so
    // buffer frames until it's known, then replay the matching ones.
    val frameStreamIdRef = AtomicReference<String?>(null)
    val pending = ArrayList<StreamFrameParams>()
    val listener = session.onNotification { method, params ->
      if (method != "streamFrame" || params == null) return@onNotification
      val frame =
        try {
          streamJson.decodeFromJsonElement(StreamFrameParams.serializer(), params)
        } catch (_: Exception) {
          return@onNotification
        }
      val known = frameStreamIdRef.get()
      if (known != null) {
        if (frame.frameStreamId == known) onFrame(frame)
        return@onNotification
      }
      // id not yet known — buffer under lock, re-checking in case it was just set.
      synchronized(pending) {
        if (frameStreamIdRef.get() == null) {
          pending.add(frame)
          return@onNotification
        }
      }
      if (frame.frameStreamId == frameStreamIdRef.get()) onFrame(frame)
    }

    val result =
      try {
        session.streamStart(
          previewId = previewId,
          codec = codec,
          maxFps = maxFps,
          overrides = overrides,
        )
      } catch (e: Exception) {
        // UnsupportedOperationException (no streaming on this backend) or a daemon error — degrade.
        onLog("stream/start unavailable for $previewId (${e.message}); falling back to snapshots")
        runCatching { listener.close() }
        return null
      }

    if (!result.heldSession) {
      // The daemon accepted stream/start but couldn't hold an interactive session, so it won't run
      // the live frame loop — fall back to the snapshot lane rather than open a frameless stream.
      onLog("stream/start for $previewId has no held session; falling back to snapshots")
      runCatching { listener.close() }
      runCatching { session.streamStop(result.frameStreamId) }
      return null
    }

    val frameStreamId = result.frameStreamId
    // Publish the id and replay any frames that arrived before it was known.
    val replay: List<StreamFrameParams>
    synchronized(pending) {
      frameStreamIdRef.set(frameStreamId)
      replay = pending.filter { it.frameStreamId == frameStreamId }
      pending.clear()
    }
    replay.forEach(onFrame)

    return object : StreamHandle {
      private val handleClosed = AtomicBoolean(false)

      override fun input(
        kind: InteractiveInputKind,
        pixelX: Int?,
        pixelY: Int?,
        pointerId: Int?,
        scrollDeltaY: Float?,
        keyCode: String?,
      ) {
        if (handleClosed.get()) return
        runCatching {
          session.interactiveInput(
            frameStreamId,
            kind,
            pixelX,
            pixelY,
            pointerId,
            scrollDeltaY,
            keyCode,
          )
        }
      }

      override fun close() {
        if (!handleClosed.compareAndSet(false, true)) return
        runCatching { listener.close() }
        runCatching { session.streamStop(frameStreamId) }
      }
    }
  }

  /**
   * Join the shared live stream for [previewId] (tier-2), opening one upstream daemon stream per
   * distinct preview + overrides + codec + fps and fanning its frames out to every watcher. This is
   * the multi-client front door to [startStream]: prefer it over [startStream] for client
   * connections so N viewers of the same preview ride one held session. Returns `null` when
   * streaming is unsupported (caller falls back to the snapshot lane).
   */
  override fun subscribeStream(
    previewId: String,
    overrides: PreviewOverrides,
    codec: StreamCodec?,
    maxFps: Int?,
    onFrame: (StreamFrameParams) -> Unit,
  ): StreamHandle? {
    check(!closed.get()) { "ServeRenderHost is closed" }
    return broadcast.subscribe(previewId, overrides, codec, maxFps, onFrame)
  }

  /** Live shared upstream streams (one per distinct preview/overrides/codec/fps). Diagnostics. */
  override fun activeStreamCount(): Int = broadcast.activeStreamCount()

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    try {
      notificationHandle.close()
    } catch (_: Exception) {
      // best effort
    }
    session.close()
  }

  companion object {
    /** RPC ack budget for the (fast, queue-only) `renderNow` call itself. */
    private val RENDER_ACK_TIMEOUT = 60.seconds

    /** Cold-start render budget — the first render pays Skiko/JVM warm-up. */
    private const val RENDER_TIMEOUT_SECONDS = 180L

    /** Per-frame render budget once warm; a wedged render can't hold the slot past this. */
    private const val FRAME_RENDER_TIMEOUT_SECONDS = 10L

    /**
     * Bounded retries when the daemon coalesces an override-bearing render already in flight. The
     * window only needs to outlast the daemon clearing its in-flight flag right after
     * `renderFinished`, so a handful of short backoffs is ample.
     */
    private const val MAX_COALESCED_RETRIES = 50
    private const val COALESCED_RETRY_BACKOFF_MS = 100L

    private const val MAX_CACHE_ENTRIES = 256

    /**
     * Open a long-lived session against a daemon launch descriptor and wrap it. Mirrors
     * [ee.schimke.composeai.cli.MatrixRenderFetcher] config; the caller supplies the servable
     * [previews] read from the module manifest. Throws [RenderSessionException] on open failure.
     */
    fun open(
      descriptorPath: File,
      workspaceRoot: File,
      workspaceName: String,
      previews: List<ServePreview>,
      label: String = "",
      declaredThemes: List<ServeTheme> = emptyList(),
      onLog: (String) -> Unit = {},
      factory: RenderSessionFactory = SubprocessRenderSessions,
    ): ServeRenderHost {
      val session =
        factory.open(
          RenderSessionConfig(
            descriptorPath = descriptorPath,
            workspaceRoot = workspaceRoot.absoluteFile,
            workspaceName = workspaceName.ifBlank { workspaceRoot.name },
            logSink = onLog,
          )
        )
      return ServeRenderHost(
        session = session,
        previews = previews,
        label = label,
        declaredThemes = declaredThemes,
        onLog = onLog,
      )
    }
  }
}

/** Minimal thread-safe LRU byte cache (access-order [LinkedHashMap] under a lock). */
private class LruByteCache(private val maxEntries: Int) {
  private val map =
    object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
        size > maxEntries
    }

  @Synchronized fun get(key: String): ByteArray? = map[key]

  @Synchronized
  fun put(key: String, value: ByteArray) {
    map[key] = value
  }

  @Synchronized
  fun remove(key: String) {
    map.remove(key)
  }
}
