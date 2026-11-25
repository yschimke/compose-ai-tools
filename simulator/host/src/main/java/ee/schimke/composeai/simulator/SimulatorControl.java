package ee.schimke.composeai.simulator;

import android.app.UiAutomation;
import android.os.Looper;
import java.time.Duration;
import javax.annotation.Priority;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.simulator.pluginapi.RemoteControl;

/**
 * Robolectric's simulator plugin hook, ranked above its no-op default. The simulator calls {@link
 * #connect} once the launcher activity has been requested and {@link #onCycle} on every turn of its
 * main loop, always on the sandbox's main thread; both hand off to {@link Session}.
 *
 * <p>Always active, control channel or not: without {@link Navigator}'s pump an app could not get
 * past its first screen, since Robolectric records {@code startActivity} instead of acting on it.
 */
@Priority(100)
public final class SimulatorControl implements RemoteControl {

  private Session session;

  public SimulatorControl() {}

  @Override
  public void connect(UiAutomation uiAutomation, Looper looper) {
    session = new Session(RuntimeEnvironment.getApplication(), uiAutomation, Protocol.create());
    session.start();
    // `--instrument`: the test APK's tests, on a thread of their own while this one loops.
    if (InstrumentationRun.enabled()) InstrumentationRun.start(RuntimeEnvironment.getApplication());
  }

  @Override
  public void onCycle(long uptimeNanos) {
    clock.keepUp(uptimeNanos);
    if (session != null) session.cycle();
  }

  private final WallClock clock = new WallClock();

  /**
   * Keeps the simulated clock up with real time. Robolectric's simulator loop advances it by the
   * time it spent waiting for the next task, not the time it spent running tasks and drawing, so a
   * busy main thread fell behind: three to four times slower while a Compose UI animated. Every
   * other thread measures real time (an instrumentation test's waits, a UiAutomator long press, an
   * app's network timeouts) while the app's own timers ({@code postDelayed}, {@code delay}, a
   * long-press timeout) run on the simulated clock; a long press then ended before the app's own
   * timeout had passed. Each cycle makes up what the last one lost; a clock already ahead (a
   * {@code SystemClock.sleep} on the main thread) is left as it is.
   */
  static final class WallClock {
    private final java.util.function.LongSupplier wallNanos;
    private final java.util.function.LongConsumer advance;
    private boolean started;
    private long lastWall;
    private long lastUptime;

    WallClock() {
      this(
          System::nanoTime,
          nanos -> org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofNanos(nanos)));
    }

    WallClock(java.util.function.LongSupplier wallNanos, java.util.function.LongConsumer advance) {
      this.wallNanos = wallNanos;
      this.advance = advance;
    }

    void keepUp(long uptimeNanos) {
      long wall = wallNanos.getAsLong();
      if (started) {
        long behind = (wall - lastWall) - (uptimeNanos - lastUptime);
        if (behind > 0) {
          advance.accept(behind);
          uptimeNanos += behind;
        }
      }
      started = true;
      lastWall = wall;
      lastUptime = uptimeNanos;
    }
  }

  @Override
  public void disconnect(Duration timeout) {
    session = null;
  }
}
