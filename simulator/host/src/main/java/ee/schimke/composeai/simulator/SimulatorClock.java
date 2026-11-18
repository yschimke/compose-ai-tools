package ee.schimke.composeai.simulator;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The simulated clock as an app's {@code ZonedDateTime.now()} sees it.
 *
 * <p>{@link ApkClassConverter} rewrites {@code ZonedDateTime.now()} in converted APK code to {@link
 * #zonedNow}, so a face that reads the date and time from {@code now()} (Wear OS 6's {@code
 * DeclarativeWatchFaceRuntime} does, on its live draw path) follows a pinned, frozen or scaled
 * {@code clock} instead of the host's. Without a simulated clock it is the real one.
 */
public final class SimulatorClock {
  private SimulatorClock() {}

  /** A time to read in place of the simulated clock's, for one face's pinned time; or null. */
  private static volatile Long override;

  static void setOverride(Long timeMillis) {
    override = timeMillis;
  }

  static Long override() {
    return override;
  }

  /** The current time in epoch milliseconds: the override, else the simulated clock. */
  static long nowMillis() {
    Long pinned = override;
    return pinned != null ? pinned.longValue() : WatchState.currentTimeMillis();
  }

  /** Replacement for {@code ZonedDateTime.now()} in converted APK code. */
  public static ZonedDateTime zonedNow() {
    return ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis()), ZoneId.systemDefault());
  }
}
