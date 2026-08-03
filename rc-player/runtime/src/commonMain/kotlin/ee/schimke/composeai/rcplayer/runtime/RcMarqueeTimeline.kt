package ee.schimke.composeai.rcplayer.runtime

import kotlin.math.PI
import kotlin.math.cos

/** Alpha16's authoritative sinusoidal marquee offset, including its double initial delay. */
public fun androidXMarqueeOffset(
  overflowDistance: Float,
  density: Float,
  velocity: Float,
  initialDelayMillis: Float,
  timeSeconds: Float,
): Float {
  if (overflowDistance <= 0f) return 0f
  val elapsedSeconds = timeSeconds - initialDelayMillis * 2f / 1_000f
  if (elapsedSeconds <= 0f) return 0f
  val durationSeconds = overflowDistance / (density * velocity)
  if (!durationSeconds.isFinite() || durationSeconds <= 0f) return 0f
  val phase = (elapsedSeconds % durationSeconds) / durationSeconds
  return -overflowDistance * ((1f - cos(phase * 2f * PI.toFloat())) / 2f)
}
