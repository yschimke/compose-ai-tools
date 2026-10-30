package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WorkTimingTest {

  /** AlarmManager.ELAPSED_REALTIME_WAKEUP. */
  private static final int ELAPSED_WAKEUP = 2;

  @Test
  public void wallClockAndElapsedAlarmsReadTheirOwnClock() {
    assertTrue(WorkTiming.alarmDue(ELAPSED_WAKEUP, 1_000, 1_000, 0));
    assertFalse(WorkTiming.alarmDue(ELAPSED_WAKEUP, 1_001, 1_000, 5_000));
    assertTrue(WorkTiming.alarmDue(WorkTiming.RTC, 5_000, 0, 5_000));
    assertFalse(WorkTiming.alarmDue(WorkTiming.RTC_WAKEUP, 5_001, 9_999, 5_000));
    assertEquals(-200, WorkTiming.alarmIn(ELAPSED_WAKEUP, 800, 1_000, 0));
  }

  private static WorkTiming.DeviceState online() {
    WorkTiming.DeviceState device = new WorkTiming.DeviceState();
    device.online = true;
    return device;
  }

  @Test
  public void aJobWaitsOutItsLatency() {
    WorkTiming.JobNeeds job = new WorkTiming.JobNeeds();
    job.minLatencyMs = 500;
    assertEquals("latency", WorkTiming.waitingFor(job, online(), 1_000, 1_499));
    assertNull(WorkTiming.waitingFor(job, online(), 1_000, 1_500));
  }

  @Test
  public void constraintsHoldAJobUntilItsDeadline() {
    WorkTiming.JobNeeds job = new WorkTiming.JobNeeds();
    job.network = true;
    job.charging = true;
    job.deadlineMs = 10_000;
    WorkTiming.DeviceState offline = new WorkTiming.DeviceState();
    assertEquals("network", WorkTiming.waitingFor(job, offline, 0, 100));
    assertEquals("charging", WorkTiming.waitingFor(job, online(), 0, 100));
    WorkTiming.DeviceState plugged = online();
    plugged.charging = true;
    assertNull(WorkTiming.waitingFor(job, plugged, 0, 100));
    // Past its deadline a job runs whatever its constraints.
    assertNull(WorkTiming.waitingFor(job, offline, 0, 10_000));
  }

  @Test
  public void unmeteredAndIdleAreConstraintsToo() {
    WorkTiming.JobNeeds job = new WorkTiming.JobNeeds();
    job.network = true;
    job.unmetered = true;
    WorkTiming.DeviceState cellular = online();
    cellular.metered = true;
    assertEquals("unmetered network", WorkTiming.waitingFor(job, cellular, 0, 0));
    assertNull(WorkTiming.waitingFor(job, online(), 0, 0));
    WorkTiming.JobNeeds idle = new WorkTiming.JobNeeds();
    idle.idle = true;
    assertEquals("idle", WorkTiming.waitingFor(idle, online(), 0, 0));
  }

  @Test
  public void aCellularJobNeedsACellularNetwork() {
    WorkTiming.JobNeeds job = new WorkTiming.JobNeeds();
    job.network = true;
    job.cellular = true;
    assertEquals("cellular network", WorkTiming.waitingFor(job, online(), 0, 0));
    WorkTiming.DeviceState cellular = online();
    cellular.cellular = true;
    assertNull(WorkTiming.waitingFor(job, cellular, 0, 0));
  }

  @Test
  public void aRetryWaitsOutItsBackoff() {
    // Jobs puts a retry's scheduled time in the future, by its backoff.
    WorkTiming.JobNeeds job = new WorkTiming.JobNeeds();
    assertEquals("latency", WorkTiming.waitingFor(job, online(), 30_000, 10_000));
    assertNull(WorkTiming.waitingFor(job, online(), 30_000, 30_000));
  }

  @Test
  public void aPeriodicJobRunsOncePerInterval() {
    WorkTiming.JobNeeds job = new WorkTiming.JobNeeds();
    job.periodic = true;
    job.intervalMs = 900_000;
    assertEquals("latency", WorkTiming.waitingFor(job, online(), 0, 899_999));
    assertNull(WorkTiming.waitingFor(job, online(), 0, 900_000));
  }
}
