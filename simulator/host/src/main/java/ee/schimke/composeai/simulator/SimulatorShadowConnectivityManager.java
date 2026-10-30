package ee.schimke.composeai.simulator;

import android.app.PendingIntent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import ee.schimke.composeai.simulator.NetworkSim.Kind;
import ee.schimke.composeai.simulator.NetworkSim.SimNetwork;
import java.util.ArrayList;
import java.util.List;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetworkInfo;

/**
 * Robolectric's {@code ConnectivityManager} shadow, answering from {@link NetworkSim}: the
 * networks, their capabilities, link properties and info, metering. Each callback is recorded with
 * its request (Robolectric keeps only the callback), so {@link NetworkSim} can tell it what a
 * device would. Added to the shadow map by {@link SimulatorSandbox}.
 */
@Implements(ConnectivityManager.class)
public class SimulatorShadowConnectivityManager extends ShadowConnectivityManager {

  private static void record(
      ConnectivityManager.NetworkCallback callback, NetworkRequest request, Kind kind) {
    if (callback != null) NetworkSim.get().register(callback, request, kind);
  }

  @Implementation
  @Override
  protected void registerNetworkCallback(
      NetworkRequest request, ConnectivityManager.NetworkCallback networkCallback) {
    super.registerNetworkCallback(request, networkCallback);
    record(networkCallback, request, Kind.LISTEN);
  }

  @Implementation(minSdk = Build.VERSION_CODES.O)
  @Override
  protected void registerNetworkCallback(
      NetworkRequest request,
      ConnectivityManager.NetworkCallback networkCallback,
      Handler handler) {
    super.registerNetworkCallback(request, networkCallback, handler);
    record(networkCallback, request, Kind.LISTEN);
  }

  @Implementation
  @Override
  protected void requestNetwork(
      NetworkRequest request, ConnectivityManager.NetworkCallback networkCallback) {
    super.requestNetwork(request, networkCallback);
    record(networkCallback, request, Kind.REQUEST);
  }

  @Implementation(minSdk = Build.VERSION_CODES.O)
  @Override
  protected void requestNetwork(
      NetworkRequest request, ConnectivityManager.NetworkCallback networkCallback, int timeoutMs) {
    super.requestNetwork(request, networkCallback, timeoutMs);
    record(networkCallback, request, Kind.REQUEST);
  }

  @Implementation(minSdk = Build.VERSION_CODES.O)
  @Override
  protected void requestNetwork(
      NetworkRequest request,
      ConnectivityManager.NetworkCallback networkCallback,
      Handler handler) {
    super.requestNetwork(request, networkCallback, handler);
    record(networkCallback, request, Kind.REQUEST);
  }

  @Implementation(minSdk = Build.VERSION_CODES.O)
  @Override
  protected void requestNetwork(
      NetworkRequest request,
      ConnectivityManager.NetworkCallback networkCallback,
      Handler handler,
      int timeoutMs) {
    super.requestNetwork(request, networkCallback, handler, timeoutMs);
    record(networkCallback, request, Kind.REQUEST);
  }

  @Implementation(minSdk = Build.VERSION_CODES.N)
  @Override
  protected void registerDefaultNetworkCallback(
      ConnectivityManager.NetworkCallback networkCallback) {
    super.registerDefaultNetworkCallback(networkCallback);
    record(networkCallback, null, Kind.DEFAULT);
  }

  @Implementation(minSdk = Build.VERSION_CODES.O)
  @Override
  protected void registerDefaultNetworkCallback(
      ConnectivityManager.NetworkCallback networkCallback, Handler handler) {
    super.registerDefaultNetworkCallback(networkCallback, handler);
    record(networkCallback, null, Kind.DEFAULT);
  }

  @Implementation(minSdk = Build.VERSION_CODES.S)
  @Override
  protected void registerBestMatchingNetworkCallback(
      NetworkRequest request,
      ConnectivityManager.NetworkCallback networkCallback,
      Handler handler) {
    super.registerBestMatchingNetworkCallback(request, networkCallback, handler);
    record(networkCallback, request, Kind.REQUEST);
  }

  @Implementation
  @Override
  protected void unregisterNetworkCallback(ConnectivityManager.NetworkCallback networkCallback) {
    super.unregisterNetworkCallback(networkCallback);
    NetworkSim.get().unregister(networkCallback);
  }

  @Implementation(minSdk = Build.VERSION_CODES.M)
  @Override
  protected void unregisterNetworkCallback(PendingIntent pendingIntent) {
    super.unregisterNetworkCallback(pendingIntent);
  }

  @Implementation
  @Override
  protected NetworkInfo getActiveNetworkInfo() {
    SimNetwork def = NetworkSim.get().defaultNetwork();
    return def == null ? null : def.info;
  }

  @Implementation(minSdk = Build.VERSION_CODES.M)
  @Override
  protected Network getActiveNetwork() {
    SimNetwork def = NetworkSim.get().defaultNetwork();
    return def == null ? null : def.network;
  }

  @Implementation
  @Override
  protected NetworkInfo[] getAllNetworkInfo() {
    List<NetworkInfo> infos = new ArrayList<>();
    for (SimNetwork n : NetworkSim.get().all()) infos.add(n.info);
    return infos.toArray(new NetworkInfo[0]);
  }

  @Implementation
  @Override
  protected NetworkInfo getNetworkInfo(int networkType) {
    SimNetwork n = NetworkSim.get().byLegacyType(networkType);
    if (n != null) return n.info;
    if (networkType != ConnectivityManager.TYPE_WIFI
        && networkType != ConnectivityManager.TYPE_MOBILE) {
      return null;
    }
    // A device reports its Wi-Fi and mobile radios even when they carry nothing.
    return ShadowNetworkInfo.newInstance(
        NetworkInfo.DetailedState.DISCONNECTED,
        networkType,
        0,
        true,
        NetworkInfo.State.DISCONNECTED);
  }

  @Implementation
  @Override
  protected NetworkInfo getNetworkInfo(Network network) {
    SimNetwork n = NetworkSim.get().find(network);
    return n == null ? null : n.info;
  }

  @Implementation
  @Override
  protected Network[] getAllNetworks() {
    List<Network> all = new ArrayList<>();
    for (SimNetwork n : NetworkSim.get().all()) all.add(n.network);
    return all.toArray(new Network[0]);
  }

  @Implementation
  @Override
  protected NetworkCapabilities getNetworkCapabilities(Network network) {
    SimNetwork n = NetworkSim.get().find(network);
    return n == null ? null : new NetworkCapabilities(n.caps);
  }

  @Implementation
  @Override
  protected LinkProperties getLinkProperties(Network network) {
    SimNetwork n = NetworkSim.get().find(network);
    return n == null ? null : NetworkSim.copy(n.link);
  }

  @Implementation
  @Override
  protected boolean isActiveNetworkMetered() {
    return NetworkSim.get().isActiveNetworkMetered();
  }

  @Implementation
  @Override
  protected boolean isDefaultNetworkActive() {
    return NetworkSim.get().defaultNetwork() != null;
  }
}
