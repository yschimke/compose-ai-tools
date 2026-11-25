package ee.schimke.composeai.simulator.shadows;

import ee.schimke.composeai.simulator.Displays;
import android.os.Handler;
import android.os.Looper;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowInputManager;

/**
 * Robolectric's {@code InputManager} shadow, with {@code injectInputEvent} delivering the event as
 * the simulator's {@code UiAutomation} does ({@link Displays#inject}). Robolectric's ignores the
 * event up to API 33 and, from 34, leaves it to {@code InputManagerGlobal}, which has no input
 * service behind it and fails. Espresso injects every touch and key this way, so without it no
 * {@code click()} reached a view. Added to the shadow map by name, by {@link SimulatorSandbox}.
 *
 * <p>Espresso injects from a thread of its own. The event is then dispatched on the main thread
 * from a copy, since the caller may recycle its own as soon as the call returns, and a synchronous
 * mode waits for that dispatch as a device's input manager does.
 */
@Implements(className = "android.hardware.input.InputManager")
public class SimulatorShadowInputManager extends ShadowInputManager {

  /** {@code InputManager.INJECT_INPUT_EVENT_MODE_ASYNC}, hidden: return without waiting. */
  static final int MODE_ASYNC = 0;

  /** How long a synchronous injection waits for the main thread. */
  static final long SYNC_TIMEOUT_SECONDS = 10;

  @Implementation
  protected boolean injectInputEvent(InputEvent event, int mode) {
    return inject(event, mode);
  }

  @Implementation(minSdk = 29)
  protected boolean injectInputEvent(InputEvent event, int mode, int targetUid) {
    return inject(event, mode);
  }

  static boolean inject(InputEvent event, int mode) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      return Displays.inject(event, Displays.routeOf(event));
    }
    InputEvent owned = copy(event);
    int display = Displays.routeOf(owned);
    AtomicBoolean handled = new AtomicBoolean();
    CountDownLatch done = new CountDownLatch(1);
    new Handler(Looper.getMainLooper())
        .post(
            () -> {
              try {
                handled.set(Displays.inject(owned, display));
              } finally {
                done.countDown();
              }
            });
    if (mode == MODE_ASYNC) return true;
    try {
      return done.await(SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS) && handled.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** A copy the main thread owns: the caller may recycle [event] once the call returns. */
  static InputEvent copy(InputEvent event) {
    if (event instanceof MotionEvent motion) return MotionEvent.obtain(motion);
    if (event instanceof KeyEvent key) return new KeyEvent(key);
    return event;
  }
}
