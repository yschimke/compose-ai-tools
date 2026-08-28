package ee.schimke.composeai.cli.serve

import kotlinx.serialization.Serializable

/**
 * Frame-level counters for the **live** lane — the serve-side companion to [RenderPerfStats],
 * measured where the frames actually reach a browser ([ServeLiveSession.onFrame]).
 *
 * [RenderPerfStats] cannot see this traffic: it is recorded around [ServeRenderHost.render], and a
 * streamed frame never goes through that path — it arrives as a daemon `streamFrame` notification
 * and fans out through the broadcast hub. So `/status.json` could say how many live streams were
 * open but not what fps any of them achieved, which left every cadence question on the live lane
 * (#4159) answerable only by attaching a profiler to a running server.
 *
 * What a socket contributes, and why:
 * - **frames** — frames carrying pixels, the numerator of the achieved fps.
 * - **heartbeats** — `unchanged` dedup frames, which cost a message and no paint. The split is the
 *   diagnosis for a low fps: heartbeats dominating means the idle backoff is working, whereas a low
 *   frame count with no heartbeats means the render loop itself is the constraint.
 * - **inter-frame intervals** — percentiles over a bounded ring, so a long-lived server reports
 *   recent cadence rather than an all-time blur. Measured per socket between *painted* frames.
 * - **payload bytes** — what the wire actually carries: the base64 text of the frame as sent, not
 *   the PNG's on-disk size (~3/4 of it). That is the number #4286 (WebP / deltas) has to beat
 *   before it is worth spending.
 *
 * Thread-safe; every method takes one short critical section. One instance per server, keyed by
 * catalog id so a site-scoped `/status` reports its own lane and not a neighbour's.
 */
class LiveFramePerfStats(private val clock: () -> Long = System::currentTimeMillis) {
  private val lock = Any()

  private val bySystem = HashMap<String, Aggregate>()
  private val open = LinkedHashSet<Socket>()

  /** Per-catalog totals. Sockets come and go; these outlive them. */
  private class Aggregate {
    var socketsOpened = 0L
    var frames = 0L
    var heartbeats = 0L
    var payloadBytes = 0L
    var maxPayloadBytes = 0L
    var lastFrameAtEpochMillis: Long? = null
    val intervals = LongArray(WINDOW_SIZE)
    var intervalCount = 0
    var intervalIdx = 0

    fun recordInterval(ms: Long) {
      intervals[intervalIdx] = ms
      intervalIdx = (intervalIdx + 1) % intervals.size
      if (intervalCount < intervals.size) intervalCount++
    }
  }

  /**
   * One open socket's recorder. Handed to [ServeLiveSession], which owns exactly one for its whole
   * life — deliberately not one per daemon stream: a socket restarts its upstream on every override
   * change and `switch`, and the client's experienced cadence is continuous across those.
   */
  inner class Socket internal constructor(internal val system: String, previewId: String) {
    @Volatile internal var previewId: String = previewId
    internal var frames = 0L
    internal var heartbeats = 0L
    internal var payloadBytes = 0L
    internal val openedAtMs = clock()
    internal var lastFrameAtMs: Long? = null
    internal var lastIntervalMs: Long? = null

    /** The preview this socket is watching now — a `switch` moves it without reopening. */
    fun watching(previewId: String) {
      this.previewId = previewId
    }

    /** A frame went out to this client, [payloadBytes] of encoded pixels as sent (base64). */
    fun recordFrame(payloadBytes: Int): Unit =
      synchronized(lock) {
        val now = clock()
        val agg = aggregateFor(system)
        frames++
        agg.frames++
        this.payloadBytes += payloadBytes
        agg.payloadBytes += payloadBytes
        if (payloadBytes > agg.maxPayloadBytes) agg.maxPayloadBytes = payloadBytes.toLong()
        lastFrameAtMs?.let {
          val interval = (now - it).coerceAtLeast(0)
          lastIntervalMs = interval
          agg.recordInterval(interval)
        }
        lastFrameAtMs = now
        agg.lastFrameAtEpochMillis = now
      }

    /** An `unchanged` heartbeat — a message with nothing to paint. */
    fun recordHeartbeat(): Unit =
      synchronized(lock) {
        heartbeats++
        aggregateFor(system).heartbeats++
      }

    /** Idempotent: the socket leaves the open set, its totals stay in its catalog's aggregate. */
    fun close(): Unit = synchronized(lock) { open.remove(this) }
  }

  /** Start recording for a socket that just opened. The caller must [Socket.close] it. */
  fun openSocket(system: String, previewId: String): Socket =
    synchronized(lock) {
      aggregateFor(system).socketsOpened++
      Socket(system, previewId).also { open += it }
    }

  /**
   * Point-in-time projection, for the whole box or for one catalog. Null when that scope has never
   * seen a live socket — a server nobody has streamed from reports nothing rather than a block of
   * zeros, matching how `/status.json` treats [RenderPerfSnapshot].
   */
  fun snapshot(system: String? = null): LiveFramePerfSnapshot? =
    synchronized(lock) {
      val aggregates =
        if (system == null) bySystem.values.toList() else listOfNotNull(bySystem[system])
      if (aggregates.isEmpty()) return null
      val sockets = open.filter { system == null || it.system == system }
      val intervals =
        aggregates.flatMap { agg -> (0 until agg.intervalCount).map { agg.intervals[it] } }.sorted()
      fun pct(p: Double): Long? =
        if (intervals.isEmpty()) null else intervals[((intervals.size - 1) * p).toInt()]
      val frames = aggregates.sumOf { it.frames }
      val payloadBytes = aggregates.sumOf { it.payloadBytes }
      val p50 = pct(0.5)
      val now = clock()
      LiveFramePerfSnapshot(
        openSockets = sockets.size,
        socketsOpened = aggregates.sumOf { it.socketsOpened },
        frames = frames,
        heartbeats = aggregates.sumOf { it.heartbeats },
        payloadBytes = payloadBytes,
        avgPayloadBytes = if (frames > 0) payloadBytes / frames else null,
        maxPayloadBytes = aggregates.maxOfOrNull { it.maxPayloadBytes }?.takeIf { it > 0 },
        minIntervalMs = intervals.firstOrNull(),
        p50IntervalMs = p50,
        p95IntervalMs = pct(0.95),
        maxIntervalMs = intervals.lastOrNull(),
        // The cadence a client actually experienced, from the median gap between painted frames —
        // not `1000 / INTERACTIVE_FRAME_INTERVAL_MS`, which is what the loop asks for and what
        // everything below it (render time, the fps gate, the idle backoff) then modifies.
        achievedFps = p50?.takeIf { it > 0 }?.let { (1000.0 / it).roundedToTenths() },
        intervalWindow = intervals.size,
        lastFrameAtEpochMillis = aggregates.mapNotNull { it.lastFrameAtEpochMillis }.maxOrNull(),
        streams =
          sockets.map { s ->
            LiveStreamSample(
              system = s.system,
              previewId = s.previewId,
              frames = s.frames,
              heartbeats = s.heartbeats,
              payloadBytes = s.payloadBytes,
              openSeconds = ((now - s.openedAtMs) / 1000).coerceAtLeast(0),
              lastIntervalMs = s.lastIntervalMs,
            )
          },
      )
    }

  private fun aggregateFor(system: String): Aggregate = bySystem.getOrPut(system) { Aggregate() }

  companion object {
    /** Ring size for the inter-frame interval percentile window, per catalog. */
    const val WINDOW_SIZE: Int = 256

    private fun Double.roundedToTenths(): Double = Math.round(this * 10.0) / 10.0
  }
}

/** One currently-open live socket, for the `/status.json` live-lane detail. */
@Serializable
data class LiveStreamSample(
  val system: String,
  val previewId: String,
  val frames: Long,
  val heartbeats: Long,
  val payloadBytes: Long,
  val openSeconds: Long,
  /** Gap before this socket's most recent painted frame — its current cadence, un-averaged. */
  val lastIntervalMs: Long? = null,
)

/**
 * Point-in-time projection of [LiveFramePerfStats], serialized verbatim onto `/status.json`
 * (`liveFrames`, and `runningServers[].liveFrames` per catalog). Durations are wall-clock
 * milliseconds between frames as the server emitted them; null means "no sample yet". Additive on
 * `compose-preview-serve/status/v1`.
 */
@Serializable
data class LiveFramePerfSnapshot(
  /** Live sockets open right now — the population [streams] describes. */
  val openSockets: Int,
  /** Live sockets opened since start, closed ones included. */
  val socketsOpened: Long,
  /** Frames that carried pixels. */
  val frames: Long,
  /** `unchanged` dedup frames: a message, no paint. */
  val heartbeats: Long,
  val payloadBytes: Long,
  val avgPayloadBytes: Long? = null,
  val maxPayloadBytes: Long? = null,
  val minIntervalMs: Long? = null,
  val p50IntervalMs: Long? = null,
  val p95IntervalMs: Long? = null,
  val maxIntervalMs: Long? = null,
  /** Frames per second implied by [p50IntervalMs] — what a viewer got, not what the loop asked. */
  val achievedFps: Double? = null,
  /** Samples behind the percentiles. */
  val intervalWindow: Int = 0,
  val lastFrameAtEpochMillis: Long? = null,
  val streams: List<LiveStreamSample> = emptyList(),
)
