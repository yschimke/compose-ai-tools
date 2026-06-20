package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
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
)

/** Result of a snapshot render request. */
sealed interface RenderOutcome {
  data class Ok(val png: ByteArray) : RenderOutcome

  /** No such preview id in this session's module. */
  data object NotFound : RenderOutcome

  /** The render was attempted but rejected / failed / timed out. [reason] is human-readable. */
  data class Failed(val reason: String) : RenderOutcome
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
  val previews: List<ServePreview>,
  private val fileSystem: FileSystem = SystemFileSystem,
  private val onLog: (String) -> Unit = {},
  private val renderTimeoutSeconds: Long = RENDER_TIMEOUT_SECONDS,
) : AutoCloseable {

  private val previewIds: Set<String> = previews.map { it.id }.toHashSet()

  // Decodes streamFrame notification params for the live-stream lane (startStream).
  private val streamJson = Json { ignoreUnknownKeys = true }

  // Bounded LRU of rendered PNGs keyed by ServeOverrides.cacheKey. A dev-facing server fronting one
  // module won't accumulate many distinct (preview × overrides) combos, so a small cap is plenty.
  private val cache = LruByteCache(MAX_CACHE_ENTRIES)

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
  fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
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

      ack.rejected
        .firstOrNull { it.id == previewId }
        ?.let {
          val reason = "render rejected: ${it.reason}"
          onLog(reason)
          return@withLock RenderOutcome.Failed(reason)
        }

      if (!latch.await(renderTimeoutSeconds, TimeUnit.SECONDS)) {
        // The daemon still owes this queued render a `renderFinished`; record it so the late event
        // is drained instead of completing a future same-id render with a stale PNG.
        staleRenders.merge(previewId, 1, Int::plus)
        val reason = "timed out after ${renderTimeoutSeconds}s waiting for render"
        onLog(reason)
        return@withLock RenderOutcome.Failed(reason)
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
        session.streamStart(previewId = previewId, overrides = overrides)
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
        scrollDeltaY: Float?,
        keyCode: String?,
      ) {
        if (handleClosed.get()) return
        runCatching {
          session.interactiveInput(frameStreamId, kind, pixelX, pixelY, scrollDeltaY, keyCode)
        }
      }

      override fun close() {
        if (!handleClosed.compareAndSet(false, true)) return
        runCatching { listener.close() }
        runCatching { session.streamStop(frameStreamId) }
      }
    }
  }

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

    /** Per-render budget for the queued render to emit `renderFinished` (first pays cold start). */
    private const val RENDER_TIMEOUT_SECONDS = 180L

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
      return ServeRenderHost(session = session, previews = previews, onLog = onLog)
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
}
