package ee.schimke.composeai.rcplayer.runtime

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
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
          val local =
            Instant.fromEpochMilliseconds(epochMillis)
              .toLocalDateTime(TimeZone.currentSystemDefault())
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
          )
        }
      }
  }
}
