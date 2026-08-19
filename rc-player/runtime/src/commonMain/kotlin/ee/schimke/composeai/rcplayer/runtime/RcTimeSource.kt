package ee.schimke.composeai.rcplayer.runtime

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime

/** Immutable wall-clock fields used by AndroidX `TimeAttribute` evaluation. */
public data class RcTimeSnapshot(
  val epochMillis: Long,
  val year: Int,
  val month: Int,
  val dayOfMonth: Int,
  val dayOfYear: Int,
  val hour: Int,
  val minute: Int,
  val second: Int,
  val isoDayOfWeek: Int,
  /**
   * The local zone's offset from UTC in seconds, which AndroidX publishes as `ID_OFFSET_TO_UTC`.
   *
   * Defaulted so a test source that only cares about the wall-clock fields keeps compiling; a
   * document that reads the offset and gets 0 is reading UTC, which is what a source that declined
   * to say has effectively claimed.
   */
  val offsetSeconds: Int = 0,
)

/** Injectable wall clock; tests can provide exact snapshots without changing the render clock. */
public interface RcTimeSource {
  public fun currentTimeMillis(): Long

  public fun snapshot(epochMillis: Long): RcTimeSnapshot

  public companion object {
    public val System: RcTimeSource =
      object : RcTimeSource {
        override fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

        override fun snapshot(epochMillis: Long): RcTimeSnapshot {
          val zone = TimeZone.currentSystemDefault()
          val instant = Instant.fromEpochMilliseconds(epochMillis)
          val local = instant.toLocalDateTime(zone)
          return RcTimeSnapshot(
            epochMillis = epochMillis,
            year = local.year,
            month = local.month.ordinal + 1,
            dayOfMonth = local.day,
            dayOfYear = local.dayOfYear,
            hour = local.hour,
            minute = local.minute,
            second = local.second,
            isoDayOfWeek = local.dayOfWeek.ordinal + 1,
            offsetSeconds = zone.offsetAt(instant).totalSeconds,
          )
        }
      }
  }
}
