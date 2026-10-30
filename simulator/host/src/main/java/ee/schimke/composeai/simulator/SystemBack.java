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
    Activity home = top != null && top.system ? top.activity() : null;
    boolean systemWindow =
        home != null && home.getWindow() != null && home.getWindow().getDecorView() == window;
    Object callback = systemWindow ? null : Displays.backCallback(displayId);
    switch (BackRoute.of(systemWindow, callback instanceof OnBackInvokedCallback)) {
      case SYSTEM:
        onBackPressed(home);
        return new Protocol.Json().put("via", "system").object();
      case CALLBACK:
        BackRoute.deliver(steps((OnBackInvokedCallback) callback), Build.VERSION.SDK_INT);
        return new Protocol.Json()
            .put("via", "OnBackInvokedCallback")
            .put("callback", callback.getClass().getName())
            .object();
      default:
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
