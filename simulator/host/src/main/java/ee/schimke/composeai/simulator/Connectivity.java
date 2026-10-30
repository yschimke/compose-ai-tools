package ee.schimke.composeai.simulator;

import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import org.json.JSONObject;
import org.robolectric.shadows.ShadowNetworkInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.robolectric.shadows.ShadowNetworkCapabilities;

/**
 * A validated Wi-Fi default network, as on a device that is online.
 *
 * <p>Robolectric has an active network but gives it no capabilities, and never calls a registered
 * {@code NetworkCallback}, so an app that checks {@code NET_CAPABILITY_INTERNET} (Now in Android's
 * offline banner) or waits on WorkManager's {@code CONNECTED} constraint (its sync) sees itself
 * offline for good. The capabilities are set before the application's {@code onCreate}; each
 * callback the app registers is then told the network is available on the next simulator cycle.
 */
final class Connectivity {

  private static final Set<ConnectivityManager.NetworkCallback> notified =
      Collections.newSetFromMap(new IdentityHashMap<>());
  private static NetworkCapabilities capabilities;

  /** Gives the active network internet capabilities. */
  static void connect(Context context) {
    ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
    Network network = manager.getActiveNetwork();
    if (network == null) return;
    NetworkCapabilities caps = ShadowNetworkCapabilities.newInstance();
    ShadowNetworkCapabilities shadow = shadowOf(caps);
    shadow.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
    shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
    shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED);
    shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
    shadowOf(manager).setNetworkCapabilities(network, caps);
    capabilities = caps;
  }

  /** Tells each callback registered since the last call that the network is up. */
  static void notifyCallbacks(Context context) {
    if (capabilities == null) return;
    ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
    Network network = manager.getActiveNetwork();
    if (network == null) return;
    // A copy: a callback may register or unregister another.
    for (ConnectivityManager.NetworkCallback callback :
        new ArrayList<>(shadowOf(manager).getNetworkCallbacks())) {
      if (!notified.add(callback)) continue;
      callback.onAvailable(network);
      callback.onCapabilitiesChanged(network, new NetworkCapabilities(capabilities));
    }
  }

  private static boolean wifi = true;
  private static boolean airplane;
  /** The network info taken away while offline, to restore. */
  private static NetworkInfo offlineInfo;

  /**
   * {@code svc wifi} and airplane mode: either takes the one (Wi-Fi) network away, telling every
   * registered callback it is lost and sending {@code CONNECTIVITY_ACTION}; turning both back on
   * restores it, and callbacks hear {@code onAvailable} on the next cycle. A null leaves that
   * switch as it is. Returns the state.
   */
  static JSONObject set(Context context, Boolean wifiOn, Boolean airplaneOn) {
    boolean wasOnline = wifi && !airplane;
    if (wifiOn != null) wifi = wifiOn;
    if (airplaneOn != null) {
      airplane = airplaneOn;
      android.provider.Settings.Global.putInt(
          context.getContentResolver(),
          android.provider.Settings.Global.AIRPLANE_MODE_ON,
          airplaneOn ? 1 : 0);
    }
    WifiManager wifiManager = context.getSystemService(WifiManager.class);
    if (wifiManager != null) wifiManager.setWifiEnabled(wifi && !airplane);
    boolean online = wifi && !airplane;
    ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
    if (wasOnline && !online) {
      Network network = manager.getActiveNetwork();
      offlineInfo = manager.getActiveNetworkInfo();
      for (ConnectivityManager.NetworkCallback callback :
          new ArrayList<>(shadowOf(manager).getNetworkCallbacks())) {
        if (network != null) callback.onLost(network);
      }
      shadowOf(manager).setActiveNetworkInfo(null);
      capabilities = null;
      notified.clear();
      context.sendBroadcast(new android.content.Intent(ConnectivityManager.CONNECTIVITY_ACTION));
    } else if (!wasOnline && online) {
      NetworkInfo info =
          offlineInfo != null
              ? offlineInfo
              : ShadowNetworkInfo.newInstance(
                  NetworkInfo.DetailedState.CONNECTED,
                  ConnectivityManager.TYPE_WIFI,
                  0,
                  true,
                  NetworkInfo.State.CONNECTED);
      shadowOf(manager).setActiveNetworkInfo(info);
      connect(context);
      notified.clear();
      context.sendBroadcast(new android.content.Intent(ConnectivityManager.CONNECTIVITY_ACTION));
    }
    return state(context);
  }

  static JSONObject state(Context context) {
    ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
    return new Protocol.Json()
        .put("wifi", wifi)
        .put("airplane", airplane)
        .put("connected", manager.getActiveNetwork() != null)
        .object();
  }

  private Connectivity() {}
}
