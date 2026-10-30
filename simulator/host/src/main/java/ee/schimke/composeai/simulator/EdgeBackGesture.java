package ee.schimke.composeai.simulator;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedCallback;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Gesture navigation's Back swipe on a phone's displays: every touch passes through here before
 * {@link Displays#inject} hands it to a window, whichever way it came — the simulator's window,
 * the {@code touch} command (Android Studio's Running Devices, over the emulator gRPC endpoint),
 * {@code swipe}, {@code adb shell input swipe}.
 *
 * <p>{@link EdgeSwipe} decides when a touch from an edge becomes the system's. Then the app's
 * pointer is cancelled, and the swipe goes where Back goes ({@link SystemBack}): to an opted-in
 * window's top {@code OnBackInvokedCallback} — an {@code OnBackAnimationCallback} (API 34+) hearing
 * {@code onBackStarted}, an {@code onBackProgressed} for each move and {@code onBackInvoked} or
 * {@code onBackCancelled} on release, which is what drives androidx's predictive back ({@code
 * PredictiveBackHandler}, Navigation's and Material's back animations); anywhere else, the
 * simulator's own Back on release past the trigger distance.
 */
final class EdgeBackGesture {

  /**
   * How far in from either edge a swipe can start, in dp: about a device's default gesture
   * sensitivity.
   */
  static final float EDGE_DP = 24;

  /** How far in a swipe must travel to be Back when lifted, in dp. */
  static final float TRIGGER_DP = 48;

  private final Context context;
  private final Navigator navigator;
  private final IntConsumer back;
  private final Map<Integer, EdgeSwipe> swipes = new HashMap<>();

  private OnBackInvokedCallback callback;
  private boolean animated;

  /** [back] is the simulator's Back on a display, for a swipe no callback takes. */
  EdgeBackGesture(Context context, Navigator navigator, IntConsumer back) {
    this.context = context;
    this.navigator = navigator;
    this.back = back;
  }

  /** Whether [motion] on display [displayId] is the gesture's, and so not the app's. */
  boolean intercept(MotionEvent motion, int displayId) {
    int action = motion.getActionMasked();
    EdgeSwipe swipe = swipes.get(displayId);
    if (action == MotionEvent.ACTION_DOWN) {
      swipe = newSwipe(displayId);
      swipes.put(displayId, swipe);
    }
    if (swipe == null) return false;
    if (action > MotionEvent.ACTION_CANCEL) return swipe.active();
    switch (swipe.on(action, motion.getX(), motion.getY())) {
      case START:
        cancelAppPointer(motion, swipe, displayId);
        callback = SystemBack.swipeCallback(navigator, displayId);
        animated = Build.VERSION.SDK_INT >= 34 && callback instanceof OnBackAnimationCallback;
        if (animated) {
          ((OnBackAnimationCallback) callback).onBackStarted(event(swipe, motion));
          ((OnBackAnimationCallback) callback).onBackProgressed(event(swipe, motion));
        }
        return true;
      case PROGRESS:
        if (animated) ((OnBackAnimationCallback) callback).onBackProgressed(event(swipe, motion));
        return true;
      case COMMIT:
        if (callback != null) callback.onBackInvoked();
        else back.accept(displayId);
        callback = null;
        return true;
      case CANCEL:
        if (animated) ((OnBackAnimationCallback) callback).onBackCancelled();
        callback = null;
        return true;
      default:
        return false;
    }
  }

  private EdgeSwipe newSwipe(int displayId) {
    Display display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
    if (display == null) return null;
    Point size = new Point();
    display.getRealSize(size);
    DisplayMetrics metrics = new DisplayMetrics();
    display.getRealMetrics(metrics);
    return new EdgeSwipe(
        size.x,
        EDGE_DP * metrics.density,
        ViewConfiguration.get(context).getScaledTouchSlop(),
        TRIGGER_DP * metrics.density);
  }

  /**
   * As {@code InputManager.pilferPointers}: the window that had the touch sees it cancelled. Each
   * event is hit-tested where it is, so the cancel goes in where the touch went down, to the window
   * that got the {@code DOWN}, not one the finger has since moved over.
   */
  private static void cancelAppPointer(MotionEvent motion, EdgeSwipe swipe, int displayId) {
    MotionEvent cancel = MotionEvent.obtain(motion);
    cancel.setAction(MotionEvent.ACTION_CANCEL);
    cancel.setLocation(swipe.startX(), swipe.startY());
    Displays.dispatchTouch(cancel, displayId);
    cancel.recycle();
  }


  private static BackEvent event(EdgeSwipe swipe, MotionEvent motion) {
    return new BackEvent(motion.getX(), motion.getY(), swipe.progress(), swipe.edge());
  }
}
