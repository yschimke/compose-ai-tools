package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.os.Bundle;

/**
 * What the simulator starts when it boots as a device rather than into an app ({@code compose-preview
 * launch} with no APK, or {@code --adb}): Robolectric's {@code Simulator} needs an activity to
 * start, so this one finishes as soon as it is created and the system UI under it ({@link
 * PhoneHome} or {@link WearHome}) is what shows. Declared to the package manager by {@link
 * ApkAppLoader}, not by any manifest.
 */
public final class NoAppActivity extends Activity {

  public NoAppActivity() {}

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    setTheme(android.R.style.Theme_DeviceDefault_NoActionBar);
    super.onCreate(savedInstanceState);
    finish();
  }
}
