package ee.schimke.composeai.simulator.shadows;

import android.graphics.Bitmap;
import android.view.Display;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import ee.schimke.composeai.simulator.Displays;
import ee.schimke.composeai.simulator.MainThread;
import ee.schimke.composeai.simulator.Session;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowUiAutomation;

/**
 * Robolectric's {@code UiAutomation} shadow, one display at a time and from any thread.
 *
 * <p>Robolectric's own composes every window into one screenshot and gives a touch to the
 * top-most window, whatever display either is on, so an app's {@code Presentation} on a second
 * display would cover the phone's screen and take its touches. Here a screenshot — which is also
 * what Robolectric's window paints — is the default display's alone, and input goes to the display
 * {@link Displays#routeOf} names; {@link Displays} draws and drives the others.
 *
 * <p>Robolectric's window queries ({@code getRootInActiveWindow}, which UiAutomator's {@code
 * UiDevice} reads for every {@code findObject} and {@code wait}) and {@code setRotation} run on
 * the calling thread unless the looper is in instrumentation-test mode; in the simulator's paused
 * mode a test's own thread then reads the activity monitor, which androidx.test refuses off the
 * main thread. Here they run on the main thread ({@link MainThread}), as a device's system server
 * answers them while the app keeps running. Added to the shadow map by name, by {@link
 * SimulatorSandbox}.
 */
@Implements(className = "android.app.UiAutomation")
public class SimulatorShadowUiAutomation extends ShadowUiAutomation {

  @Implementation
  @Override
  protected Bitmap takeScreenshot() throws Exception {
    return Displays.capture(Display.DEFAULT_DISPLAY);
  }

  @Implementation
  @Override
  protected boolean injectInputEvent(InputEvent event, boolean sync) {
    int display = Displays.routeOf(event);
    if (event instanceof KeyEvent key
        && MainThread.get(() -> Session.systemKey(key, display))) {
      return true;
    }
    return Displays.inject(event, display);
  }

  @Implementation
  @Override
  protected boolean setRotation(int rotation) {
    return MainThread.get(() -> super.setRotation(rotation));
  }

  @Implementation
  protected AccessibilityNodeInfo getRootInActiveWindow() {
    return MainThread.get(() -> activeRoot(() -> superCall("getRootInActiveWindow")));
  }

  @Implementation
  protected AccessibilityNodeInfo getRootInActiveWindow(int prefetchingStrategy) {
    return MainThread.get(
        () ->
            activeRoot(() -> superCall("getRootInActiveWindow", int.class, prefetchingStrategy)));
  }

  /**
   * The node of {@link Displays#activeWindow}'s view, read in-process as Robolectric's own is
   * ({@code AccessibilityNodeInfo(View)}, then {@code setQueryFromAppProcessEnabled}): the window
   * in front in the simulator's back stack, so a home screen brought forward (a widget pin sheet)
   * or a dialog over the app is what UiAutomator searches. [fallback] when there is none, or this
   * platform lacks those hidden methods.
   */
  private static AccessibilityNodeInfo activeRoot(
      java.util.concurrent.Callable<Object> fallback) throws Exception {
    android.view.View view = Displays.activeWindow(Display.DEFAULT_DISPLAY);
    if (view != null && view.isAttachedToWindow()) {
      try {
        AccessibilityNodeInfo node =
            AccessibilityNodeInfo.class
                .getDeclaredConstructor(android.view.View.class)
                .newInstance(view);
        AccessibilityNodeInfo.class
            .getMethod("setQueryFromAppProcessEnabled", android.view.View.class, boolean.class)
            .invoke(node, view, true);
        node.refresh();
        return node;
      } catch (ReflectiveOperationException e) {
        // Below API 34 there is no in-process query: Robolectric's answer stands.
      }
    }
    return (AccessibilityNodeInfo) fallback.call();
  }

  /**
   * Waits for the screen to settle, as a device's {@code UiAutomation} waits for its accessibility
   * events to go quiet: UiAutomator calls it before every search, so a node it finds is not still
   * sliding into place (a bottom sheet's items, tapped mid-animation, were missed). The simulator
   * has no accessibility events; a window drawing a frame stands in for one. Quiet means no window
   * on the display drew for [idleTimeoutMillis], at most {@value #QUIET_MILLIS} ms; the wait gives
   * up, without the {@code TimeoutException} a device would throw and UiAutomator ignores, after
   * {@value #SETTLE_MILLIS} ms at most, so a spinner or a blinking cursor costs a bounded wait.
   */
  @Implementation
  protected void waitForIdle(long idleTimeoutMillis, long globalTimeoutMillis) {
    if (MainThread.isCurrent()) return;
    long quiet = Math.min(idleTimeoutMillis, QUIET_MILLIS) * 1_000_000L;
    long deadline = System.nanoTime() + Math.min(globalTimeoutMillis, SETTLE_MILLIS) * 1_000_000L;
    while (System.nanoTime() < deadline) {
      long lastDraw = MainThread.get(Draws::watch);
      if (System.nanoTime() - lastDraw >= quiet) return;
      try {
        Thread.sleep(16);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private static final long QUIET_MILLIS = 150;
  private static final long SETTLE_MILLIS = 1_500;

  /** When a window on the default display last drew, by {@code System.nanoTime()}. */
  private static final class Draws {
    private static volatile long last = System.nanoTime();
    private static final java.util.Set<android.view.ViewTreeObserver> watched =
        java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** Watches the windows showing now (on the main thread), and says when one last drew. */
    static long watch() {
      for (android.view.View window : Displays.windows(Display.DEFAULT_DISPLAY)) {
        android.view.ViewTreeObserver observer = window.getViewTreeObserver();
        if (observer.isAlive() && watched.add(observer)) {
          observer.addOnDrawListener(() -> last = System.nanoTime());
          // A window that just appeared is a change in itself.
          last = System.nanoTime();
        }
      }
      return last;
    }
  }

  /**
   * Robolectric's own [name], called as {@code super} would: it has these window queries from
   * 4.18, newer than the shadows this class compiles against, so they are found at run time. Null
   * when this Robolectric lacks it, as the framework's answer is without an accessibility service.
   */
  private Object superCall(String name, Object... typesThenArgs) throws Exception {
    int half = typesThenArgs.length / 2;
    Class<?>[] types = new Class<?>[half];
    Object[] args = new Object[half];
    for (int i = 0; i < half; i++) {
      types[i] = (Class<?>) typesThenArgs[i];
      args[i] = typesThenArgs[half + i];
    }
    MethodHandle method;
    try {
      method =
          MethodHandles.lookup()
              .findSpecial(
                  ShadowUiAutomation.class,
                  name,
                  MethodType.methodType(AccessibilityNodeInfo.class, types),
                  SimulatorShadowUiAutomation.class);
    } catch (NoSuchMethodException e) {
      return null;
    }
    Object[] all = new Object[half + 1];
    all[0] = this;
    System.arraycopy(args, 0, all, 1, half);
    try {
      return method.invokeWithArguments(all);
    } catch (Exception | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }
}
