package ee.schimke.composeai.simulator;

/**
 * When the framework's {@code BatteryService} sends {@code ACTION_BATTERY_LOW} and {@code
 * ACTION_BATTERY_OKAY}, free of Android types so it is unit-tested on a plain JVM.
 *
 * <p>LOW goes out when an unplugged battery reaches the warning level, either by draining across
 * it or by being unplugged at or below it. OKAY goes out once a LOW has been sent and the level
 * reaches the warning level plus the close-warning bump; plugging in alone does not send it.
 * The levels are AOSP's defaults ({@code config_lowBatteryWarningLevel} 15, {@code
 * config_lowBatteryCloseWarningBump} 5).
 */
final class BatteryWarnings {

  static final int LOW_LEVEL = 15;
  static final int CLOSE_LEVEL = LOW_LEVEL + 5;

  enum Broadcast {
    NONE,
    LOW,
    OKAY
  }

  private boolean sentLow;

  /** The warning to send for a change from [oldLevel] / [oldPlugged] to [level] / [plugged]. */
  Broadcast update(int oldLevel, boolean oldPlugged, int level, boolean plugged) {
    if (!plugged && level <= LOW_LEVEL && (oldPlugged || oldLevel > LOW_LEVEL)) {
      sentLow = true;
      return Broadcast.LOW;
    }
    if (sentLow && level >= CLOSE_LEVEL) {
      sentLow = false;
      return Broadcast.OKAY;
    }
    return Broadcast.NONE;
  }

  boolean low() {
    return sentLow;
  }
}
