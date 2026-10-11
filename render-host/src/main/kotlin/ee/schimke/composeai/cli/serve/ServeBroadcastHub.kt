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
public fun interface StreamOpener {
  public fun open(
    previewId: String,
    overrides: PreviewOverrides,
    codec: StreamCodec?,
    maxFps: Int?,
    onUnavailable: ((String) -> Unit)?,
    onFrame: (StreamFrameParams) -> Unit,
  ): StreamHandle?
}

/**
 * Shares one upstream daemon stream across every watcher of the same preview + overrides + codec +
 * fps, so N browsers ride one held session; any watcher's input drives it. Keyed by [keyOf]. The
 * upstream opens lazily on the first subscriber and closes with the last (ref-counted). Late
 * joiners get the last painted frame immediately. Each [subscribe] returns a per-watcher
 * [StreamHandle].
 */
public class ServeBroadcastHub(private val opener: StreamOpener) {

  private val lock = ReentrantLock()
  private val broadcasts = HashMap<String, Broadcast>()

  /**
   * Join the shared stream for [previewId], opening the upstream for the first watcher. Null
   * (opening nothing) when the backend can't stream, as [ServeRenderHost.startStream].
   */
  public fun subscribe(
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
          // Hold the lock across the open so racing first subscribers can't open two upstreams.
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
  public fun activeStreamCount(): Int = lock.withLock { broadcasts.size }

  private fun release(broadcast: Broadcast, watcher: Broadcast.Watcher) {
    lock.withLock {
      if (broadcast.removeWatcher(watcher) == 0) {
        broadcasts.remove(broadcast.key, broadcast)
        broadcast.handle?.close()
      } else {
        // The departing watcher may have changed the aggregate visibility.
        broadcast.syncVisibility()
      }
    }
  }

  private inner class Broadcast(val key: String) {
    @Volatile var handle: StreamHandle? = null
    private val watchers = CopyOnWriteArrayList<Watcher>()
    @Volatile private var lastPainted: StreamFrameParams? = null

    /** Last visibility sent upstream, so [syncVisibility] only sends on a real change. */
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
      // Register before replaying: fan-out is lock-free, so a frame painted in between would
      // otherwise be missed. The worst case is a harmless duplicate.
      watchers.add(watcher)
      // A new watcher is visible, which un-throttles a hidden stream.
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
     * Push the watchers' aggregate visibility upstream: visible if any watcher is; otherwise
     * throttled to the fastest fps any asked for. Caller holds [lock] so flips stay ordered.
     */
    fun syncVisibility() {
      val current = watchers.toList()
      val visible = current.isEmpty() || current.any { it.visible }
      // `null` is the daemon default (1 fps, the slowest), so an explicit fps wins only when every
      // hidden watcher named one.
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
