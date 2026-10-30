package ee.schimke.composeai.simulator;

import android.app.PictureInPictureParams;
import android.os.IBinder;
import java.util.Map;
import java.util.WeakHashMap;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Keeps what an activity tells the system about picture-in-picture: {@code
 * Activity.setPictureInPictureParams} reaches the system server through {@code ActivityClient},
 * which under Robolectric goes nowhere, so the aspect ratio and {@code setAutoEnterEnabled} were
 * lost. {@link PictureInPicture} reads them back by the activity's token. Added to the shadow map
 * by {@link SimulatorSandbox}.
 */
@Implements(className = "android.app.ActivityClient", isInAndroidSdk = false)
public class SimulatorShadowActivityClient {

  private static final Map<IBinder, PictureInPictureParams> PARAMS = new WeakHashMap<>();

  @Implementation
  protected void setPictureInPictureParams(IBinder token, PictureInPictureParams params) {
    synchronized (PARAMS) {
      PARAMS.put(token, params);
    }
  }

  /** The params the activity with [token] last set, or null. */
  static PictureInPictureParams params(IBinder token) {
    synchronized (PARAMS) {
      return token == null ? null : PARAMS.get(token);
    }
  }
}
