package ee.schimke.composeai.simulator;

import android.os.Build;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowTelephonyManager;

/**
 * Robolectric's {@code TelephonyManager} shadow, answering the mobile network's type, data state
 * and service state from {@link NetworkSim} for every instance (Robolectric keeps them per
 * instance). The permission checks stay Robolectric's. Added to the shadow map by {@link
 * SimulatorSandbox}.
 */
@Implements(TelephonyManager.class)
public class SimulatorShadowTelephonyManager extends ShadowTelephonyManager {

  @Implementation(minSdk = Build.VERSION_CODES.N)
  @Override
  protected int getDataNetworkType() {
    super.getDataNetworkType();
    return NetworkSim.get().dataNetworkType();
  }

  @Implementation
  @Override
  protected int getNetworkType() {
    return NetworkSim.get().radioNetworkType();
  }

  @Implementation(minSdk = Build.VERSION_CODES.N)
  @Override
  protected int getVoiceNetworkType() {
    super.getVoiceNetworkType();
    return NetworkSim.get().radioNetworkType();
  }

  @Implementation
  @Override
  protected int getDataState() {
    return NetworkSim.get().dataState();
  }

  @Implementation(minSdk = Build.VERSION_CODES.O)
  @Override
  protected ServiceState getServiceState() {
    ServiceState state = new ServiceState();
    state.setState(NetworkSim.get().serviceState());
    return state;
  }

  @Implementation(minSdk = Build.VERSION_CODES.O)
  @Override
  protected boolean isDataEnabled() {
    super.isDataEnabled();
    return NetworkSim.get().model.data;
  }
}
