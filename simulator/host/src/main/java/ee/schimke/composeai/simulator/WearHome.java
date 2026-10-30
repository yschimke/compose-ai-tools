package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.os.Bundle;

/**
 * The Wear system UI's activity: the watch face, launcher, notification stream, tiles carousel and
 * quick settings, all drawn by {@link WearSystemUi} in plain views (no Compose: the app's Compose
 * is the only one on the classpath). It sits at the bottom of the default display's back stack, so
 * a back press from the app's root lands on the watch face, as on a watch. Not in the app's
 * manifest; {@link Navigator#startSystemActivity} starts it directly.
 */
public final class WearHome extends Activity {

  public WearHome() {}

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    setTheme(android.R.style.Theme_DeviceDefault_NoActionBar);
    super.onCreate(savedInstanceState);
    WearSystemUi ui = WearSystemUi.current;
    if (ui != null) setContentView(ui.attach(this));
  }

  @Override
  protected void onRestart() {
    super.onRestart();
    WearSystemUi ui = WearSystemUi.current;
    if (ui != null) ui.homeRestarted();
  }

  @Override
  @SuppressWarnings("deprecation")
  public void onBackPressed() {
    // Never finishes: back steps from a system surface to the watch face.
    WearSystemUi ui = WearSystemUi.current;
    if (ui != null) ui.back();
  }
}
