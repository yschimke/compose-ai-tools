package ee.schimke.composeai.simulator;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import java.util.List;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowWifiManager;

/**
 * Robolectric's {@code WifiManager} shadow, answering from {@link WifiSim}. Robolectric keeps
 * Wi-Fi state per {@code WifiManager} instance, and an activity's context has its own, so state set
 * on the application's would not reach it; here every instance reads the one device. Added to the
 * shadow map by {@link SimulatorSandbox}.
 */
@Implements(WifiManager.class)
public class SimulatorShadowWifiManager extends ShadowWifiManager {

  private static WifiSim wifi() {
    return NetworkSim.get().wifi();
  }

  @Implementation
  @Override
  protected boolean isWifiEnabled() {
    return wifi().enabled();
  }

  @Implementation
  @Override
  protected int getWifiState() {
    return wifi().enabled() ? WifiManager.WIFI_STATE_ENABLED : WifiManager.WIFI_STATE_DISABLED;
  }

  @Implementation
  @Override
  protected WifiInfo getConnectionInfo() {
    return wifi().currentInfo();
  }

  @Implementation
  @Override
  protected List<ScanResult> getScanResults() {
    return wifi().scanResults();
  }

  @Implementation
  @Override
  protected boolean startScan() {
    return wifi().startScan();
  }

  /** An app targeting Android 10+ may not switch Wi-Fi, as on a device; older ones may. */
  @Implementation
  @Override
  protected boolean setWifiEnabled(boolean wifiEnabled) {
    return wifi().appSetEnabled(wifiEnabled);
  }

  @Implementation(minSdk = Build.VERSION_CODES.Q)
  @Override
  protected int addNetworkSuggestions(List<WifiNetworkSuggestion> networkSuggestions) {
    int status = super.addNetworkSuggestions(networkSuggestions);
    if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
      wifi().suggested(networkSuggestions);
    }
    return status;
  }

  @Implementation(minSdk = Build.VERSION_CODES.R)
  @Override
  protected List<WifiNetworkSuggestion> getNetworkSuggestions() {
    return wifi().suggestionList();
  }
}
