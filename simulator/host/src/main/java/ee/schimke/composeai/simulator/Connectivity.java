package ee.schimke.composeai.simulator;

import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
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

  private Connectivity() {}
}
