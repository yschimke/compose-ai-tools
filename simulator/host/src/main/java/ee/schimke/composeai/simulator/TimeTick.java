package ee.schimke.composeai.simulator;

/**
 * When the system's {@code ACTION_TIME_TICK} is due: once each time the wall clock enters a new
 * minute, as {@code AlarmManagerService} sends it on a device.
 *
 * <p>Robolectric sends no time broadcasts, and a clock that redraws only on one stays where it
 * was first drawn: Wear Compose's {@code TimeText} (its {@code DefaultTimeSource} registers for
 * {@code TIME_TICK}) showed the launch's minute for the whole session. Pure Java, so it is tested
 * without the framework.
 */
final class TimeTick {
  private static final long MINUTE_MS = 60_000L;

  private boolean started;
  private long minute;

  /** True when [nowMillis] is in another minute than the last call's; false on the first call. */
  boolean due(long nowMillis) {
    long current = Math.floorDiv(nowMillis, MINUTE_MS);
    if (!started) {
      started = true;
      minute = current;
      return false;
    }
    if (current == minute) return false;
    minute = current;
    return true;
  }
}
