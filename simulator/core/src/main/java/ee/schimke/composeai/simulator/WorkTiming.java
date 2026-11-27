package ee.schimke.composeai.simulator;

/**
 * When an alarm or a job is due, decided as {@code AlarmManagerService} and {@code
 * JobSchedulerService} decide it, without the framework: plain Java, so the rules are tested
 * without a sandbox. {@link Alarms} and {@link Jobs} feed it the clocks and the device's state.
 */
final class WorkTiming {

  /** {@code AlarmManager.RTC_WAKEUP} / {@code RTC}: wall-clock alarm types; the others elapsed. */
  static final int RTC_WAKEUP = 0;

  static final int RTC = 1;

  /** Whether an alarm of [type] set for [triggerAt] is due at these clocks. */
  static boolean alarmDue(int type, long triggerAt, long elapsedNow, long wallNow) {
    boolean wall = type == RTC_WAKEUP || type == RTC;
    return triggerAt <= (wall ? wallNow : elapsedNow);
  }

  /** How long until that alarm is due, negative when overdue. */
  static long alarmIn(int type, long triggerAt, long elapsedNow, long wallNow) {
    boolean wall = type == RTC_WAKEUP || type == RTC;
    return triggerAt - (wall ? wallNow : elapsedNow);
  }

  /** What a job asks for, read off its {@code JobInfo}. */
  static final class JobNeeds {
    long minLatencyMs;
    /** {@code JobInfo.getMaxExecutionDelayMillis}, 0 for none. */
    long deadlineMs;
    boolean network;
    boolean unmetered;
    /** {@code NETWORK_TYPE_CELLULAR}: a cellular network, not merely any. */
    boolean cellular;
    boolean charging;
    boolean idle;
    boolean periodic;
    long intervalMs;
  }

  /** The device, as far as a job's constraints go. */
  static final class DeviceState {
    boolean online;
    boolean metered;
    boolean cellular;
    boolean charging;
    boolean idle;
  }

  /**
   * Why the job scheduled at [scheduledAt] does not run at [now], or null when it runs: its
   * latency first; then, until its deadline passes (which runs it whatever its constraints), each
   * constraint the device does not meet.
   */
  static String waitingFor(JobNeeds job, DeviceState device, long scheduledAt, long now) {
    long since = now - scheduledAt;
    long latency = job.periodic ? Math.max(job.minLatencyMs, job.intervalMs) : job.minLatencyMs;
    if (since < latency) return "latency";
    if (job.deadlineMs > 0 && since >= job.deadlineMs) return null;
    if (job.network && !device.online) return "network";
    if (job.unmetered && device.metered) return "unmetered network";
    if (job.cellular && !device.cellular) return "cellular network";
    if (job.charging && !device.charging) return "charging";
    if (job.idle && !device.idle) return "idle";
    return null;
  }

  private WorkTiming() {}
}
