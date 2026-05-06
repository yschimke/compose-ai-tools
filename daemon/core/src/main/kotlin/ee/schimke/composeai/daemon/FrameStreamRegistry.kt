package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-daemon registry of live `stream/start` subscribers. Owns the per-stream state needed to make
 * the buttery client tick: dedup heartbeats, fps-cap throttling, visibility-driven downshift, and
 * monotonic per-stream sequence numbers.
 *
 * `JsonRpcServer` is the only caller. The split lets the streaming logic live next to its tests
 * (the server is already 3000 LoC) and keeps the `emitRenderFinished` path free of new conditionals
 * — all stream-side conditionals live in [consumeForPreview] and return a list of ready-to-send
 * [StreamFrameParams].
 */
internal class FrameStreamRegistry(
  private val clock: () -> Long = System::currentTimeMillis,
  private val pngBytesReader: (String) -> ByteArray? = { path -> readPngBytes(path) },
  private val supportedCodecs: Set<StreamCodec> = setOf(StreamCodec.PNG),
) {

  /**
   * Per-stream state machine. Mutated only inside [register], [unregister], [setVisibility], and
   * [consumeForPreview] — none of which races with another (the server dispatches each from its
   * single reader thread or its single render-watcher).
   */
  internal data class State(
    val frameStreamId: String,
    val previewId: String,
    val codec: StreamCodec,
    val maxFps: Int?,
    var visible: Boolean = true,
    var visibilityFps: Int? = null,
    var lastEmittedAtMs: Long = Long.MIN_VALUE,
    var lastHash: String? = null,
    var seq: Long = 0L,
    var keyframePending: Boolean = true,
  )

  private val states = ConcurrentHashMap<String, State>()
  private val nextStreamId = AtomicLong(1)

  /** Negotiate an emitting codec for a `stream/start` request. */
  fun negotiateCodec(requested: StreamCodec?): StreamCodec {
    if (requested != null && requested in supportedCodecs) return requested
    // Prefer PNG when the requested codec isn't supported — every renderer already produces PNG,
    // so the downgrade is always safe.
    if (StreamCodec.PNG in supportedCodecs) return StreamCodec.PNG
    return supportedCodecs.first()
  }

  fun mintStreamId(): String = "fstream-${nextStreamId.getAndIncrement()}"

  /**
   * Records a new subscriber. Returns the same [State] mutable instance held inside the registry so
   * the server-side handler can read [State.codec] for the reply. Idempotent: re-registering an
   * existing id replaces the prior state (the server allocates a fresh id on every `stream/start`,
   * so this branch is purely defensive).
   */
  fun register(frameStreamId: String, previewId: String, codec: StreamCodec, maxFps: Int?): State {
    val state =
      State(frameStreamId = frameStreamId, previewId = previewId, codec = codec, maxFps = maxFps)
    states[frameStreamId] = state
    return state
  }

  /** Drop a subscriber. Idempotent: a stop on a stream that's already gone is a no-op. */
  fun unregister(frameStreamId: String): State? = states.remove(frameStreamId)

  /**
   * Apply a `stream/visibility` notification. Idempotent and silent on unknown stream ids — the
   * client may race a visibility flip with a `stream/stop` and we don't want to error out. When
   * [visible] flips back to true the next emitted frame is marked as a keyframe so the client has
   * an explicit "paint me now" anchor (replaces the old "scroll-back-blank-then-fade").
   */
  fun setVisibility(frameStreamId: String, visible: Boolean, fps: Int?) {
    val s = states[frameStreamId] ?: return
    val wasVisible = s.visible
    s.visible = visible
    s.visibilityFps = if (!visible) (fps ?: 1).coerceAtLeast(1) else null
    if (visible && !wasVisible) {
      s.keyframePending = true
    }
  }

  /** Returns the snapshot view (used by tests). */
  internal fun stateOrNull(frameStreamId: String): State? = states[frameStreamId]

  /** True when at least one stream targets [previewId]. The server uses this to skip work. */
  fun hasStreamsFor(previewId: String): Boolean = states.values.any { it.previewId == previewId }

  /**
   * Materialise the per-stream `streamFrame` notifications produced by a render of [previewId].
   *
   * Logic, applied per stream:
   * 1. fps gate — drop the frame entirely if the elapsed-since-last is below the per-stream minimum
   *    interval (driven by [State.maxFps] or the visibility-throttled fps).
   * 2. dedup — if [pngHash] matches the prior frame's hash on this stream, emit an `unchanged`
   *    heartbeat (codec=null, payload=null). Saves the encode + ~50 KB of base64 on the wire.
   * 3. keyframe-pending — if the stream just started or just flipped visible, mark the frame as a
   *    keyframe so the client refreshes its paint anchor.
   *
   * Encoding: PNG bytes are read from [pngPath] only when the dedup branch fires "different" AND at
   * least one stream wants the bytes. Two streams targeting the same preview share the read.
   */
  fun consumeForPreview(
    previewId: String,
    pngPath: String?,
    pngHash: String?,
    widthPx: Int,
    heightPx: Int,
  ): List<StreamFrameParams> {
    val targets = states.values.filter { it.previewId == previewId }
    if (targets.isEmpty()) return emptyList()
    val now = clock()
    val out = mutableListOf<StreamFrameParams>()
    var cachedBytes: ByteArray? = null
    for (s in targets) {
      val minIntervalMs = effectiveMinIntervalMs(s)
      if (minIntervalMs > 0 && s.lastEmittedAtMs != Long.MIN_VALUE) {
        if (now - s.lastEmittedAtMs < minIntervalMs) continue
      }
      val isUnchanged = pngHash != null && s.lastHash == pngHash
      val keyframe = s.keyframePending
      val seq = ++s.seq
      val params: StreamFrameParams =
        if (isUnchanged) {
          StreamFrameParams(
            frameStreamId = s.frameStreamId,
            seq = seq,
            ptsMillis = now,
            widthPx = widthPx,
            heightPx = heightPx,
            codec = null,
            keyframe = false,
            final = false,
            payloadBase64 = null,
          )
        } else {
          val bytes = cachedBytes ?: pngPath?.let(pngBytesReader)?.also { cachedBytes = it }
          // Synthesise a heartbeat when the bytes are unreadable (file vanished, host is in
          // stub mode). The client treats this as "no new pixels"; the legacy renderFinished
          // path still flows for callers that read pngPath off disk on their own.
          if (bytes == null) {
            StreamFrameParams(
              frameStreamId = s.frameStreamId,
              seq = seq,
              ptsMillis = now,
              widthPx = widthPx,
              heightPx = heightPx,
              codec = null,
              keyframe = false,
              final = false,
              payloadBase64 = null,
            )
          } else {
            StreamFrameParams(
              frameStreamId = s.frameStreamId,
              seq = seq,
              ptsMillis = now,
              widthPx = widthPx,
              heightPx = heightPx,
              codec = s.codec,
              keyframe = keyframe,
              final = false,
              payloadBase64 = base64(bytes),
            )
          }
        }
      s.lastEmittedAtMs = now
      if (pngHash != null) s.lastHash = pngHash
      if (params.codec != null) s.keyframePending = false
      out += params
    }
    return out
  }

  /**
   * Emit a final frame for a stop-in-flight: returns the most-recent frame as a `final` marker so
   * the client can release its decoder state cleanly. Idempotent: returns null when [frameStreamId]
   * is unknown.
   */
  fun finalFrameOnStop(frameStreamId: String): StreamFrameParams? {
    val s = states[frameStreamId] ?: return null
    return StreamFrameParams(
      frameStreamId = frameStreamId,
      seq = ++s.seq,
      ptsMillis = clock(),
      widthPx = 0,
      heightPx = 0,
      codec = null,
      keyframe = false,
      final = true,
      payloadBase64 = null,
    )
  }

  private fun effectiveMinIntervalMs(s: State): Long {
    val visibilityCap = if (!s.visible) (s.visibilityFps ?: 1) else null
    val cap =
      when {
        visibilityCap != null && s.maxFps != null -> minOf(visibilityCap, s.maxFps)
        visibilityCap != null -> visibilityCap
        s.maxFps != null -> s.maxFps
        else -> return 0L
      }
    if (cap <= 0) return 0L
    return 1000L / cap
  }

  companion object {
    private fun readPngBytes(pngPath: String): ByteArray? {
      val path =
        try {
          Path.of(pngPath)
        } catch (_: Throwable) {
          return null
        }
      if (!Files.exists(path)) return null
      return try {
        Files.readAllBytes(path)
      } catch (_: Throwable) {
        null
      }
    }

    private fun base64(bytes: ByteArray): String =
      java.util.Base64.getEncoder().encodeToString(bytes)
  }
}
