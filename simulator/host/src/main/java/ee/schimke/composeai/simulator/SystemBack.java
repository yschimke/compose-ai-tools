package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedCallback;
import org.json.JSONException;
import org.json.JSONObject;

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
    View window = Displays.focusedWindow(displayId);
    if (window == null) return null;
    Navigator.Entry top = navigator.top(displayId);
    if (top != null && top.system) {
      Activity home = top.activity();
      if (home.getWindow() != null && home.getWindow().getDecorView() == window) {
        onBackPressed(home);
        return new Protocol.Json().put("via", "system").object();
      }
    }
    Object callback = Displays.backCallback(displayId);
    if (callback == null) return null;
    invoke((OnBackInvokedCallback) callback);
    return new Protocol.Json()
        .put("via", "OnBackInvokedCallback")
        .put("callback", callback.getClass().getName())
        .object();
  }

  /**
   * As {@code ViewRootImpl} hands a Back key to an opted-in window: an animated callback hears the
   * press start (at the left edge, progress 0), then the callback is invoked on the release.
   */
  private static void invoke(OnBackInvokedCallback callback) {
    if (Build.VERSION.SDK_INT >= 34 && callback instanceof OnBackAnimationCallback) {
      ((OnBackAnimationCallback) callback).onBackStarted(new BackEvent(0, 0, 0, BackEvent.EDGE_LEFT));
    }
    callback.onBackInvoked();
  }

  @SuppressWarnings("deprecation")
  private static void onBackPressed(Activity activity) {
    activity.onBackPressed();
  }
}
