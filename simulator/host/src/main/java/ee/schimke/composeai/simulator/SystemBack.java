package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedCallback;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.util.ReflectionHelpers;

/**
 * Back as the system delivers it: the {@code back} command, {@code key BACK} and {@code adb shell
 * input keyevent KEYCODE_BACK} all come here.
 *
 * <p>A device's {@code ViewRootImpl} takes Back before the view tree when the focused window opted
 * in to the callback API (API 33+ with {@code android:enableOnBackInvokedCallback}, or by default
 * from targetSdk 36): it hands it to the window's top {@code OnBackInvokedCallback}, and no key
 * reaches the views. That callback is androidx's {@code OnBackPressedDispatcher} while it has an
 * enabled callback (Compose's {@code BackHandler}, Wear's {@code SwipeDismissableNavHost},
 * Navigation, Horologist), and the activity's own default (finish, or the task to the back) while
 * it has none. The simulator injects keys straight into a window's view tree, below those input
 * stages, so this does what they would. A window that did not opt in gets {@code KEYCODE_BACK},
 * which {@code Activity.onKeyUp} turns into {@code onBackPressed}, and from there {@code
 * ComponentActivity} goes to the same dispatcher.
 *
 * <p>The simulator's own home activities ({@link WearHome}, {@link PhoneHome}) are the system's,
 * not the app's, and take Back themselves whatever the app opted in to.
 */
final class SystemBack {

  private SystemBack() {}

  /**
   * Delivers Back to display [displayId] where it needs more than a key, and says how; null when
   * the focused window takes it as {@code KEYCODE_BACK}, which the caller then injects.
   */
  static JSONObject dispatch(Navigator navigator, int displayId) throws JSONException {
    Target target = target(navigator, displayId);
    if (target == null) return null;
    switch (target.route) {
      case SYSTEM:
        onBackPressed(target.owner);
        return new Protocol.Json().put("via", "system").object();
      case ACTIVITY:
        onBackPressed(target.owner);
        return new Protocol.Json().put("via", "Activity.onBackPressed").object();
      case CALLBACK:
        BackRoute.deliver(steps(target.callback), Build.VERSION.SDK_INT);
        return new Protocol.Json()
            .put("via", "OnBackInvokedCallback")
            .put("callback", target.callback.getClass().getName())
            .object();
      default:
        return null;
    }
  }

  /**
   * The callback a Back swipe on display [displayId] drives, its progress and all, as a device's
   * gesture handler hands the swipe to the focused window's top {@code OnBackInvokedCallback}; null
   * when Back goes elsewhere (the key, the activity, the home screen), which the swipe then is on
   * release.
   */
  static OnBackInvokedCallback swipeCallback(Navigator navigator, int displayId) {
    Target target = target(navigator, displayId);
    return target != null && target.route == BackRoute.CALLBACK ? target.callback : null;
  }

  /** Where Back on display [displayId] goes; null with no focused window. */
  private static Target target(Navigator navigator, int displayId) {
    View window = Displays.focusedWindow(displayId);
    if (window == null) return null;
    Navigator.Entry top = navigator.top(displayId);
    // The activity whose own window is focused (not a dialog or popup over it).
    Activity owner =
        top != null
                && top.activity().getWindow() != null
                && top.activity().getWindow().getDecorView() == window
            ? top.activity()
            : null;
    boolean systemWindow = owner != null && top.system;
    Object callback = systemWindow ? null : Displays.backCallback(displayId);
    boolean activityDefault =
        owner != null && callback != null && callback == defaultCallback(owner);
    boolean hasCallback = callback instanceof OnBackInvokedCallback;
    return new Target(
        BackRoute.of(systemWindow, hasCallback, activityDefault),
        owner,
        hasCallback ? (OnBackInvokedCallback) callback : null);
  }

  private static final class Target {
    final BackRoute route;
    final Activity owner;
    final OnBackInvokedCallback callback;

    Target(BackRoute route, Activity owner, OnBackInvokedCallback callback) {
      this.route = route;
      this.owner = owner;
      this.callback = callback;
    }
  }

  /**
   * The callback {@code Activity} registers for itself when it opts in, which asks the system
   * server ({@code ActivityClient.onBackPressed}) to finish it or move its task back. Robolectric
   * has no server to answer, so that one is replaced by {@code onBackPressed}, which Robolectric's
   * {@code ShadowActivity} turns into the finish {@link Navigator} acts on.
   */
  private static Object defaultCallback(Activity activity) {
    try {
      return ReflectionHelpers.getField(activity, "mDefaultBackCallback");
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** [callback] as {@link BackRoute.Steps}: an animated one starts at the left edge, progress 0. */
  private static BackRoute.Steps steps(OnBackInvokedCallback callback) {
    return new BackRoute.Steps() {
      @Override
      public boolean animated() {
        return Build.VERSION.SDK_INT >= 34 && callback instanceof OnBackAnimationCallback;
      }

      @Override
      public void started() {
        ((OnBackAnimationCallback) callback)
            .onBackStarted(new BackEvent(0, 0, 0, BackEvent.EDGE_LEFT));
      }

      @Override
      public void invoked() {
        callback.onBackInvoked();
      }
    };
  }

  @SuppressWarnings("deprecation")
  private static void onBackPressed(Activity activity) {
    activity.onBackPressed();
  }
}
