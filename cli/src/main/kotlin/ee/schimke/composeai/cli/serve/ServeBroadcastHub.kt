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
    onFrame: (StreamFrameParams) -> Unit,
  ): StreamHandle? = lock.withLock {
    val key = keyOf(previewId, overrides, codec, maxFps)
    val broadcast =
      broadcasts[key]
        ?: run {
          val fresh = Broadcast(key)
          // Hold the lock across the open so two racing first-subscribers can't open two upstreams
          // for one key; opens are cheap relative to a dev server's client count.
          val handle =
            opener.open(previewId, overrides, codec, maxFps, fresh::onUpstreamFrame)
              ?: return@withLock null
          fresh.handle = handle
          broadcasts[key] = fresh
          fresh
        }
    broadcast.addWatcher(onFrame)
  }

  /** Live shared upstream streams (one per distinct key). For tests / diagnostics. */
  fun activeStreamCount(): Int = lock.withLock { broadcasts.size }

  private fun release(broadcast: Broadcast, onFrame: (StreamFrameParams) -> Unit) {
    lock.withLock {
      if (broadcast.removeWatcher(onFrame) == 0) {
        broadcasts.remove(broadcast.key, broadcast)
        broadcast.handle?.close()
      }
    }
  }

  private inner class Broadcast(val key: String) {
    @Volatile var handle: StreamHandle? = null
    private val watchers = CopyOnWriteArrayList<(StreamFrameParams) -> Unit>()
    @Volatile private var lastPainted: StreamFrameParams? = null

    /**
     * Fan an upstream frame out to every watcher; cache it if it paints (for late-joiner replay).
     */
    fun onUpstreamFrame(frame: StreamFrameParams) {
      // Payload-less `unchanged` heartbeats don't paint, so they don't become the replay frame.
      if (frame.payloadBase64 != null) lastPainted = frame
      watchers.forEach { it(frame) }
    }

    /** Add a watcher and replay the current picture to it. Caller holds [lock]. */
    fun addWatcher(onFrame: (StreamFrameParams) -> Unit): StreamHandle {
      // Register *before* replaying: onUpstreamFrame is lock-free, so a frame painted between the
      // replay and the add would otherwise reach neither the live fan-out (not yet a watcher) nor
      // the replay (already read) and leave a static preview blank. Registering first means the
      // worst case is a harmless duplicate of the current frame (newest-wins paint), never a miss.
      watchers.add(onFrame)
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
        ) {
          if (closed.get()) return
          handle?.input(kind, pixelX, pixelY, pointerId, scrollDeltaY, keyCode)
        }

        override fun close() {
          if (closed.compareAndSet(false, true)) release(this@Broadcast, onFrame)
        }
      }
    }

    /** Remove a watcher; returns the remaining count. Caller holds [lock]. */
    fun removeWatcher(onFrame: (StreamFrameParams) -> Unit): Int {
      watchers.remove(onFrame)
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
