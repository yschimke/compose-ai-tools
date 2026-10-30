package ee.schimke.composeai.simulator;

import android.os.Bundle;
import android.os.ResultReceiver;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowResultReceiver;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * {@code ResultReceiver.send} as on a device. Robolectric's shadow calls the receiver's own {@code
 * onReceiveResult}, which is right for the instance the app made but not for a copy that went
 * through a {@code Parcel}: the copy is a plain {@code ResultReceiver} that forwards over its binder
 * to the original, and under Robolectric's shadow its result was dropped. {@code
 * RemoteActivityHelper} parcels its receiver before handing it over, so a remote activity launch
 * never completed. The framework's own {@code send} handles both. Added to the shadow map by {@link
 * SimulatorSandbox}.
 */
@Implements(ResultReceiver.class)
public class SimulatorShadowResultReceiver extends ShadowResultReceiver {

  @RealObject private ResultReceiver realObject;

  @Implementation
  @Override
  protected void send(int resultCode, Bundle resultData) {
    Shadow.directlyOn(
        realObject,
        ResultReceiver.class,
        "send",
        ClassParameter.from(int.class, resultCode),
        ClassParameter.from(Bundle.class, resultData));
  }
}
