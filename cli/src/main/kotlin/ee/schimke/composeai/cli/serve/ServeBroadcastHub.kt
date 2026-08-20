package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Opens one upstream daemon stream. Matches [ServeRenderHost.startStream]. */
fun interface StreamOpener {
  fun open(
    previewId: String,
    overrides: PreviewOverrides,
    codec: StreamCodec?,
    maxFps: Int?,
    onUnavailable: ((String) -> Unit)?,
    onFrame: (StreamFrameParams) -> Unit,
  ): StreamHandle?
}

/**
 * Shares **one** upstream daemon stream across every watcher of the same preview + overrides +
 * codec + fps. Without it, N browsers watching the same preview would open N held daemon sessions
 * (N `stream/start`s); with it they ride a single held session whose frames fan out to all of them,
 * and any watcher's input drives the shared composition.
 *
 * Keyed by [keyOf]: distinct overrides (or codec / fps) are distinct streams, so a viewer changing
 * theme transparently moves to its own shared lane without disturbing the others. The upstream is
 * opened lazily on the first subscriber for a key and torn down when the last one leaves
 * (ref-counted), so an idle hub holds no daemon sessions.
 *
 * Late joiners are replayed the last *painted* frame immediately, so a watcher that connects
 * between recompositions sees the current picture instead of a blank canvas until the next frame.
 *
 * Each [subscribe] returns a per-watcher [StreamHandle]: its [StreamHandle.input] forwards into the
 * shared session and its [StreamHandle.close] drops just that watcher (closing the shared upstream
 * only when it was the last).
 */
class ServeBroadcastHub(private val opener: StreamOpener) {

  private val lock = ReentrantLock()
  private val broadcasts = HashMap<String, Broadcast>()

  /**
   * Join the shared stream for [previewId] at these overrides/codec/fps, opening the upstream if
   * this is the first watcher. Returns `null` (and opens nothing) when the backend can't stream, so
   * the caller falls back to the snapshot lane — same contract as [ServeRenderHost.startStream].
   */
  fun subscribe(
    previewId: String,
    overrides: PreviewOverrides,
    codec: StreamCodec? = null,
    maxFps: Int? = null,
    onUnavailable: ((String) -> Unit)? = null,
    onFrame: (StreamFrameParams) -> Unit,
  ): StreamHandle? = lock.withLock {
    val key = keyOf(previewId, overrides, codec, maxFps)
    val broadcast =
      broadcasts[key]
        ?: run {
          val fresh = Broadcast(key)
          // Hold the lock across the open so two racing first-subscribers can't open two upstreams
          // for one key; opens are cheap relative to a dev server's client count. A failed open
          // reports its reason through [onUnavailable] (forwarded to the opener) before the null.
          val handle =
            opener.open(previewId, overrides, codec, maxFps, onUnavailable, fresh::onUpstreamFrame)
              ?: return@withLock null
          fresh.handle = handle
          broadcasts[key] = fresh
          fresh
        }
    broadcast.addWatcher(onFrame)
  }

  /** Live shared upstream streams (one per distinct key). For tests / diagnostics. */
  fun activeStreamCount(): Int = lock.withLock { broadcasts.size }

  private fun release(broadcast: Broadcast, watcher: Broadcast.Watcher) {
    lock.withLock {
      if (broadcast.removeWatcher(watcher) == 0) {
        broadcasts.remove(broadcast.key, broadcast)
        broadcast.handle?.close()
      } else {
        // The departing watcher may have been the last hidden one holding the shared stream down,
        // or the last visible one keeping it up.
        broadcast.syncVisibility()
      }
    }
  }

  private inner class Broadcast(val key: String) {
    @Volatile var handle: StreamHandle? = null
    private val watchers = CopyOnWriteArrayList<Watcher>()
    @Volatile private var lastPainted: StreamFrameParams? = null

    /**
     * The visibility last pushed upstream, so [syncVisibility] only sends on a real change — a grid
     * of twenty cards scrolling past sends twenty flips a second, and all but the ones that move
     * the shared answer are noise on the daemon's reader thread.
     */
    private var upstreamVisible: Boolean = true
    private var upstreamFps: Int? = null

    /** One watcher's frame sink plus the visibility it last reported. */
    inner class Watcher(val onFrame: (StreamFrameParams) -> Unit) {
      @Volatile var visible: Boolean = true
      @Volatile var fps: Int? = null
    }

    /**
     * Fan an upstream frame out to every watcher; cache it if it paints (for late-joiner replay).
     */
    fun onUpstreamFrame(frame: StreamFrameParams) {
      // Payload-less `unchanged` heartbeats don't paint, so they don't become the replay frame.
      if (frame.payloadBase64 != null) lastPainted = frame
      watchers.forEach { it.onFrame(frame) }
    }

    /** Add a watcher and replay the current picture to it. Caller holds [lock]. */
    fun addWatcher(onFrame: (StreamFrameParams) -> Unit): StreamHandle {
      val watcher = Watcher(onFrame)
      // Register *before* replaying: onUpstreamFrame is lock-free, so a frame painted between the
      // replay and the add would otherwise reach neither the live fan-out (not yet a watcher) nor
      // the replay (already read) and leave a static preview blank. Registering first means the
      // worst case is a harmless duplicate of the current frame (newest-wins paint), never a miss.
      watchers.add(watcher)
      // A new watcher is visible by definition, so it un-throttles a stream every existing watcher
      // had hidden.
      syncVisibility()
      lastPainted?.let(onFrame)
      return object : StreamHandle {
        private val closed = AtomicBoolean(false)

        override fun input(
          kind: InteractiveInputKind,
          pixelX: Int?,
          pixelY: Int?,
          pointerId: Int?,
          scrollDeltaY: Float?,
          keyCode: String?,
          text: String?,
          pointerType: String?,
        ) {
          if (closed.get()) return
          handle?.input(kind, pixelX, pixelY, pointerId, scrollDeltaY, keyCode, text, pointerType)
        }

        override fun visibility(visible: Boolean, fps: Int?) {
          if (closed.get()) return
          watcher.visible = visible
          watcher.fps = fps
          lock.withLock { syncVisibility() }
        }

        override fun close() {
          if (closed.compareAndSet(false, true)) release(this@Broadcast, watcher)
        }
      }
    }

    /**
     * Push the watchers' *aggregate* visibility upstream. One held session serves everyone on this
     * key, so it may only be throttled when nobody is looking: visible if **any** watcher is, and
     * when none are, at the fastest fps any of them asked for. Throttling on the first hidden
     * watcher would starve the tab still watching the same preview beside it.
     *
     * Caller holds [lock] (the send happens under it so two flips can't land out of order).
     */
    fun syncVisibility() {
      val current = watchers.toList()
      val visible = current.isEmpty() || current.any { it.visible }
      // `null` means "the daemon's default throttle" (1 fps) and is the slowest option, so an
      // explicit fps only wins when every hidden watcher named one.
      val fps =
        if (visible) null
        else
          current
            .map { it.fps }
            .reduceOrNull { a, b -> if (a == null || b == null) null else maxOf(a, b) }
      if (visible == upstreamVisible && fps == upstreamFps) return
      upstreamVisible = visible
      upstreamFps = fps
      handle?.visibility(visible, fps)
    }

    /** Remove a watcher; returns the remaining count. Caller holds [lock]. */
    fun removeWatcher(watcher: Watcher): Int {
      watchers.remove(watcher)
      return watchers.size
    }
  }

  private companion object {
    /** Same identity the snapshot cache uses, plus the stream-only knobs (codec, fps). */
    fun keyOf(
      previewId: String,
      overrides: PreviewOverrides,
      codec: StreamCodec?,
      maxFps: Int?,
    ): String =
      "${ServeOverrides.cacheKey(previewId, overrides)}|c=${codec?.name ?: "-"}|f=${maxFps ?: "-"}"
  }
}
