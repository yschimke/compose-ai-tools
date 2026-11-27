package ee.schimke.composeai.simulator.shadows;

import ee.schimke.composeai.simulator.MainThread;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowPausedSystemClock;

/**
 * Robolectric's paused-mode clock, but a {@code SystemClock.sleep} off the main thread sleeps, as
 * it does on a device. Robolectric's advances the clock instead, at once: right for a unit test,
 * whose only thread is the main one, but in the simulator other threads sleep too (a test thread,
 * an app's worker), while the main thread keeps the clock in step with real time. A UiAutomator
 * {@code longClick} injects a down, sleeps the long-press timeout and injects an up: with no real
 * wait the up came at once, and Compose's long-press detection, timed by the wall clock, saw a
 * tap. Added to the shadow map by name, by {@code SimulatorSandbox}.
 */
@Implements(className = "android.os.SystemClock", isInAndroidSdk = false)
public class SimulatorShadowSystemClock extends ShadowPausedSystemClock {

  @Implementation
  protected static void sleep(long millis) {
    if (MainThread.isCurrent()) {
      ShadowPausedSystemClock.sleep(millis);
      return;
    }
    long deadline = System.nanoTime() + millis * 1_000_000L;
    boolean interrupted = false;
    // As the framework's: an interrupt does not cut the sleep short, it is kept for later.
    while (true) {
      long left = deadline - System.nanoTime();
      if (left <= 0) break;
      try {
        Thread.sleep(left / 1_000_000L, (int) (left % 1_000_000L));
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }
}
