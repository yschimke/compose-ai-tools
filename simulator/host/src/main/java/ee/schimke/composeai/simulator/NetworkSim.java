package ee.schimke.composeai.simulator;

import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.os.Build;
import android.provider.Settings;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import ee.schimke.composeai.simulator.NetworkModel.Mode;
import ee.schimke.composeai.simulator.NetworkModel.Transport;
import ee.schimke.composeai.simulator.net.NetworkGate;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowNetworkInfo;
import org.robolectric.shadows.ShadowTelephonyManager;

/**
 * The simulated device's networks, as {@code ConnectivityManager} reports them and its callbacks
 * hear them, and as the app's real traffic meets them ({@link NetworkGate}).
 *
 * <p>Robolectric keeps one {@code NetworkInfo} per type, gives networks no capabilities, and never
 * calls a {@code NetworkCallback}. Here {@link SimulatorShadowConnectivityManager} answers every
 * query from this class's networks, and records each callback with its request, so that a change —
 * a mode, a radio, airplane mode, metering — reaches each callback the way a device delivers it:
 * {@code onAvailable} then {@code onCapabilitiesChanged} and {@code onLinkPropertiesChanged} for a
 * network that now matches its request, {@code onCapabilitiesChanged} for one that changed, {@code
 * onLost} for one gone. A default-network callback follows the default network; a {@code
 * registerNetworkCallback} sees every network its request matches (WorkManager's {@code UNMETERED}
 * constraint asks for {@code NOT_METERED}); a {@code requestNetwork} gets the best match. A newly
 * registered callback hears the current state on the next simulator cycle.
 *
 * <p>{@code NetworkInfo}, {@code isActiveNetworkMetered}, the {@code TelephonyManager} network type
 * and service state, the {@code airplane_mode_on} / {@code wifi_on} / {@code mobile_data} settings
 * and {@code CONNECTIVITY_ACTION} are kept consistent with the same state.
 *
 * <p>One instance, created before the application's {@code onCreate()}. Runs on the main thread.
 */
final class NetworkSim {

  private static NetworkSim instance;

  /** A network the device has: its {@link Network} handle stays the same while it stays up. */
  static final class SimNetwork {
    final Transport transport;
    final String key;
    final Network network;
    final int netId;
    NetworkCapabilities caps;
    LinkProperties link;
    NetworkInfo info;

    /** Bumped whenever the capabilities change, so callbacks hear {@code onCapabilitiesChanged}. */
    int generation;

    SimNetwork(Transport transport, String key, int netId) {
      this.transport = transport;
      this.key = key;
      this.netId = netId;
      this.network = ShadowNetwork.newInstance(netId);
    }
  }

  enum Kind {
    DEFAULT,
    LISTEN,
    REQUEST
  }

  /** A registered callback, its request, and what it has heard so far. */
  static final class Registration {
    final ConnectivityManager.NetworkCallback callback;
    final NetworkRequest request;
    final Kind kind;

    /** netId → the generation it last heard. */
    final Map<Integer, Integer> heard = new LinkedHashMap<>();

    /** A local-only Wi-Fi network granted to this request ({@link WifiSim}). */
    SimNetwork peer;

    Registration(ConnectivityManager.NetworkCallback callback, NetworkRequest request, Kind kind) {
      this.callback = callback;
      this.request = request;
      this.kind = kind;
    }
  }

  private final Application app;
  final NetworkModel model = new NetworkModel();

  /** {@code lte}, {@code 5g}, {@code 3g} or {@code 2g}. */
  private String cellular = "lte";

  private int downKbps;
  private int upKbps;
  private long latencyMs;
  private long kbps;
  private List<String> blocked = Collections.emptyList();
  private boolean dataSaver;

  private List<SimNetwork> networks = new ArrayList<>();
  private int nextNetId = 100;
  private final Map<ConnectivityManager.NetworkCallback, Registration> registrations =
      new IdentityHashMap<>();

  /** In registration order, as callbacks are delivered. */
  private final List<Registration> order = new ArrayList<>();

  private Navigator navigator;
  private final WifiSim wifi;
  private BluetoothSim bluetooth;

  private NetworkSim(Application app) {
    this.app = app;
    this.wifi = new WifiSim(app, this);
  }

  /**
   * Creates the networks before the application's {@code onCreate()}, from {@code launch --network}
   * ({@code composeai.simulator.network}): a mode, {@code airplane}, or both ({@code
   * cellular,airplane}). Wi-Fi by default.
   */
  static void connect(Application app) {
    instance = new NetworkSim(app);
    String launch = System.getProperty("composeai.simulator.network", "wifi");
    for (String part : launch.split(",")) {
      String p = part.trim();
      if (p.isEmpty()) continue;
      if (p.equalsIgnoreCase("airplane")) instance.model.airplane(true);
      else instance.model.mode(Mode.of(p));
    }
    instance.writeSettings();
    instance.apply(false);
  }

  /** The instance; created on first use if {@link #connect} has not run (a test, say). */
  static NetworkSim get() {
    if (instance == null) connect(org.robolectric.RuntimeEnvironment.getApplication());
    return instance;
  }

  static NetworkSim peek() {
    return instance;
  }

  void attach(Navigator navigator, BluetoothSim bluetooth) {
    this.navigator = navigator;
    this.bluetooth = bluetooth;
  }

  WifiSim wifi() {
    return wifi;
  }

  Navigator navigator() {
    return navigator;
  }

  // ---------------------------------------------------------------------------------------------
  // The `network` command

  /**
   * {@code mode}, {@code wifi} / {@code data} / {@code airplane} (radios), {@code metered} (true,
   * false, or null for the transport's own), {@code cellular} (generation), {@code downKbps} /
   * {@code upKbps} (the capabilities' estimates), {@code latencyMs} / {@code kbps} (the real
   * traffic), {@code block} (hosts), {@code dataSaver}. Each optional; returns the state.
   */
  JSONObject command(JSONObject c) throws JSONException {
    boolean airplaneBefore = model.airplane;
    if (c.has("mode")) model.mode(Mode.of(c.getString("mode")));
    if (c.has("airplane")) setAirplane(c.getBoolean("airplane"));
    if (c.has("wifi")) model.wifiRadio = c.getBoolean("wifi");
    if (c.has("data")) model.data = c.getBoolean("data");
    if (c.has("metered")) model.metered = c.isNull("metered") ? null : c.getBoolean("metered");
    if (c.has("cellular")) cellular = generation(c.getString("cellular"));
    if (c.has("downKbps")) downKbps = c.getInt("downKbps");
    if (c.has("upKbps")) upKbps = c.getInt("upKbps");
    if (c.has("latencyMs")) latencyMs = c.getLong("latencyMs");
    if (c.has("kbps")) kbps = c.getLong("kbps");
    if (c.has("block")) {
      JSONArray hosts = c.getJSONArray("block");
      List<String> list = new ArrayList<>();
      for (int i = 0; i < hosts.length(); i++) list.add(hosts.getString(i));
      blocked = list;
    }
    if (c.has("dataSaver")) setDataSaver(c.getBoolean("dataSaver"));
    writeSettings();
    apply(true);
    if (model.airplane != airplaneBefore) airplaneBroadcast();
    return state();
  }

  /** What the system does when a setting it acts on is put; null when it is not one of them. */
  String setting(String key, String value) {
    boolean on = !value.equals("0");
    switch (key) {
      case Settings.Global.AIRPLANE_MODE_ON:
        if (on != model.airplane) {
          setAirplane(on);
          writeSettings();
          apply(true);
          airplaneBroadcast();
        }
        return "airplane mode " + (on ? "on" : "off") + ", AIRPLANE_MODE broadcast";
      case Settings.Global.WIFI_ON:
        model.wifiRadio = on;
        writeSettings();
        apply(true);
        return "wifi " + (on ? "on" : "off");
      case "mobile_data":
        model.data = on;
        writeSettings();
        apply(true);
        return "mobile data " + (on ? "on" : "off");
      default:
        return null;
    }
  }

  private void setAirplane(boolean on) {
    model.airplane(on);
    if (bluetooth != null) bluetooth.airplane(on);
  }

  private void airplaneBroadcast() {
    Intent intent =
        new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", model.airplane);
    if (navigator != null) navigator.broadcast(intent);
    else app.sendBroadcast(intent);
  }

  private void setDataSaver(boolean on) {
    if (on == dataSaver) return;
    dataSaver = on;
    shadowOf(manager())
        .setRestrictBackgroundStatus(
            on
                ? ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
                : ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED);
    app.sendBroadcast(new Intent(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED));
  }

  private void writeSettings() {
    android.content.ContentResolver resolver = app.getContentResolver();
    Settings.Global.putInt(resolver, Settings.Global.AIRPLANE_MODE_ON, model.airplane ? 1 : 0);
    Settings.Global.putInt(resolver, Settings.Global.WIFI_ON, model.wifiRadio ? 1 : 0);
    Settings.Global.putInt(resolver, "mobile_data", model.data ? 1 : 0);
  }

  private static String generation(String value) {
    String g = value.toLowerCase(Locale.ROOT);
    switch (g) {
      case "5g":
      case "nr":
        return "5g";
      case "4g":
      case "lte":
        return "lte";
      case "3g":
      case "hspa":
        return "3g";
      case "2g":
      case "edge":
        return "2g";
      default:
        throw new IllegalArgumentException("cellular is 5g, lte, 3g or 2g, not " + value);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Applying a change

  /**
   * Rebuilds the networks from the model, then tells everyone who listens: the managers, the
   * traffic gate, {@code CONNECTIVITY_ACTION} if the default changed, and the callbacks (now when
   * [notify], else on the next cycle).
   */
  void apply(boolean notify) {
    SimNetwork oldDefault = defaultNetwork();
    List<SimNetwork> next = new ArrayList<>();
    for (Transport transport : model.networks()) {
      String key = key(transport);
      SimNetwork existing = null;
      for (SimNetwork n : networks) if (n.key.equals(key)) existing = n;
      SimNetwork network =
          existing != null ? existing : new SimNetwork(transport, key, nextNetId++);
      describe(network);
      next.add(network);
    }
    networks = next;
    SimNetwork newDefault = defaultNetwork();
    if (!model.wifiRadio) {
      // Local-only Wi-Fi networks go with the radio; their requests hear onLost.
      for (Registration r : order) r.peer = null;
    }

    NetworkGate.configure(model.internet(), latencyMs, kbps, blocked);
    telephony();
    wifi.refresh();
    if (oldDefault != newDefault) {
      Intent change = new Intent(ConnectivityManager.CONNECTIVITY_ACTION);
      NetworkInfo info = newDefault != null ? newDefault.info : oldDefault.info;
      change.putExtra(ConnectivityManager.EXTRA_NETWORK_INFO, info);
      change.putExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, info.getType());
      if (newDefault == null) change.putExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, true);
      app.sendBroadcast(change);
    }
    if (notify) sync();
  }

  private String key(Transport transport) {
    if (transport == Transport.WIFI) return "wifi:" + wifi.ssid();
    return transport.name();
  }

  /** Sets [network]'s capabilities, link properties and info from the model. */
  private void describe(SimNetwork network) {
    Transport t = network.transport;
    boolean validated = model.internet();
    boolean metered = model.isMetered(t == Transport.VPN ? Transport.WIFI : t);
    NetworkCapabilities caps = ShadowNetworkCapabilities.newInstance();
    ShadowNetworkCapabilities shadow = shadowOf(caps);
    switch (t) {
      case WIFI:
        shadow.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        break;
      case CELLULAR:
        shadow.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
        break;
      case ETHERNET:
        shadow.addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET);
        break;
      case VPN:
        // A VPN's capabilities carry its underlying network's transport too.
        shadow.addTransportType(NetworkCapabilities.TRANSPORT_VPN);
        shadow.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        break;
    }
    capability(shadow, NetworkCapabilities.NET_CAPABILITY_INTERNET);
    capability(shadow, NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
    capability(shadow, NetworkCapabilities.NET_CAPABILITY_TRUSTED);
    if (t != Transport.VPN) capability(shadow, NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
    capability(shadow, NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
    capability(shadow, NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED);
    capability(shadow, NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
    capability(shadow, NetworkCapabilities.NET_CAPABILITY_FOREGROUND);
    capability(shadow, 28); // NET_CAPABILITY_NOT_VCN_MANAGED, API 31.
    capability(shadow, ShadowNetworkCapabilities.NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED);
    if (!metered) capability(shadow, NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    if (validated) capability(shadow, NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    else if (model.mode == Mode.CAPTIVE) {
      capability(shadow, NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
    }
    int[] bandwidth = bandwidth(t);
    shadow.setLinkDownstreamBandwidthKbps(bandwidth[0]);
    shadow.setLinkUpstreamBandwidthKbps(bandwidth[1]);
    if (t == Transport.WIFI && Build.VERSION.SDK_INT >= 29) {
      try {
        shadow.setTransportInfo(wifi.connectionInfo());
      } catch (RuntimeException e) {
        // An SDK without transport info on capabilities.
      }
    }
    if (network.caps == null || !network.caps.toString().equals(caps.toString())) {
      network.generation++;
    }
    network.caps = caps;
    network.link = link(t);
    network.info = info(legacyType(t), t == Transport.CELLULAR ? networkType() : 0, true);
  }

  /**
   * A legacy {@code NetworkInfo}, named as a device names it ({@code WIFI}, {@code MOBILE} /
   * {@code LTE}); Robolectric's leaves the names null.
   */
  static NetworkInfo info(int type, int subtype, boolean connected) {
    NetworkInfo info =
        ShadowNetworkInfo.newInstance(
            connected ? NetworkInfo.DetailedState.CONNECTED : NetworkInfo.DetailedState.DISCONNECTED,
            type,
            subtype,
            true,
            connected ? NetworkInfo.State.CONNECTED : NetworkInfo.State.DISCONNECTED);
    // Robolectric's shadow answers type and state; the names are the framework's own fields.
    for (String[] field :
        new String[][] {{"mTypeName", typeName(type)}, {"mSubtypeName", subtypeName(subtype)}}) {
      try {
        java.lang.reflect.Field f = NetworkInfo.class.getDeclaredField(field[0]);
        f.setAccessible(true);
        f.set(info, field[1]);
      } catch (ReflectiveOperationException | RuntimeException e) {
        // Left unnamed.
      }
    }
    return info;
  }

  private static String typeName(int type) {
    switch (type) {
      case ConnectivityManager.TYPE_MOBILE:
        return "MOBILE";
      case ConnectivityManager.TYPE_ETHERNET:
        return "ETHERNET";
      case ConnectivityManager.TYPE_VPN:
        return "VPN";
      default:
        return "WIFI";
    }
  }

  private static String subtypeName(int subtype) {
    switch (subtype) {
      case TelephonyManager.NETWORK_TYPE_NR:
        return "NR";
      case TelephonyManager.NETWORK_TYPE_LTE:
        return "LTE";
      case TelephonyManager.NETWORK_TYPE_HSPAP:
        return "HSPA+";
      case TelephonyManager.NETWORK_TYPE_EDGE:
        return "EDGE";
      default:
        return "";
    }
  }

  /** Adds [capability] where the simulated SDK has it; a newer one is out of range and skipped. */
  private static void capability(ShadowNetworkCapabilities shadow, int capability) {
    try {
      shadow.addCapability(capability);
    } catch (RuntimeException e) {
      // Not on this SDK.
    }
  }

  /** {down, up} kbps: an override, the traffic cap, or what such a link typically estimates. */
  private int[] bandwidth(Transport t) {
    int down;
    int up;
    switch (t) {
      case CELLULAR:
        switch (cellular) {
          case "5g":
            down = 150_000;
            up = 30_000;
            break;
          case "3g":
            down = 2_000;
            up = 500;
            break;
          case "2g":
            down = 100;
            up = 50;
            break;
          default:
            down = 30_000;
            up = 12_000;
        }
        break;
      case ETHERNET:
        down = 1_000_000;
        up = 1_000_000;
        break;
      default:
        down = 60_000;
        up = 30_000;
    }
    if (kbps > 0) {
      down = (int) Math.min(down, kbps);
      up = (int) Math.min(up, kbps);
    }
    if (downKbps > 0) down = downKbps;
    if (upKbps > 0) up = upKbps;
    return new int[] {down, up};
  }

  private static int legacyType(Transport t) {
    switch (t) {
      case CELLULAR:
        return ConnectivityManager.TYPE_MOBILE;
      case ETHERNET:
        return ConnectivityManager.TYPE_ETHERNET;
      case VPN:
        return ConnectivityManager.TYPE_VPN;
      default:
        return ConnectivityManager.TYPE_WIFI;
    }
  }

  private int networkType() {
    switch (cellular) {
      case "5g":
        return TelephonyManager.NETWORK_TYPE_NR;
      case "3g":
        return TelephonyManager.NETWORK_TYPE_HSPAP;
      case "2g":
        return TelephonyManager.NETWORK_TYPE_EDGE;
      default:
        return TelephonyManager.NETWORK_TYPE_LTE;
    }
  }

  private static LinkProperties link(Transport t) {
    LinkProperties link = new LinkProperties();
    String address;
    int prefix;
    switch (t) {
      case CELLULAR:
        link.setInterfaceName("rmnet_data0");
        address = "10.120.4.5";
        prefix = 32;
        break;
      case ETHERNET:
        link.setInterfaceName("eth0");
        address = "192.168.0.23";
        prefix = 24;
        break;
      case VPN:
        link.setInterfaceName("tun0");
        address = "10.8.0.2";
        prefix = 24;
        break;
      default:
        link.setInterfaceName("wlan0");
        address = "192.168.1.23";
        prefix = 24;
    }
    try {
      InetAddress ip = InetAddress.getByName(address);
      // LinkAddress(InetAddress, int) is hidden; the framework's own code is here.
      Object linkAddress =
          android.net.LinkAddress.class
              .getConstructor(InetAddress.class, int.class)
              .newInstance(ip, prefix);
      LinkProperties.class
          .getMethod("addLinkAddress", android.net.LinkAddress.class)
          .invoke(link, linkAddress);
      link.setDnsServers(Arrays.asList(InetAddress.getByName("8.8.8.8")));
    } catch (ReflectiveOperationException | java.net.UnknownHostException | RuntimeException e) {
      // The interface name alone.
    }
    return link;
  }

  /** {@code TelephonyManager.getDataNetworkType()}: the generation while on mobile data. */
  int dataNetworkType() {
    SimNetwork def = defaultNetwork();
    boolean onCellular = def != null && def.transport == Transport.CELLULAR;
    return onCellular ? networkType() : TelephonyManager.NETWORK_TYPE_UNKNOWN;
  }

  /** The radio's network type, data or not; unknown in airplane mode. */
  int radioNetworkType() {
    return model.airplane ? TelephonyManager.NETWORK_TYPE_UNKNOWN : networkType();
  }

  int dataState() {
    return dataNetworkType() != TelephonyManager.NETWORK_TYPE_UNKNOWN
        ? TelephonyManager.DATA_CONNECTED
        : TelephonyManager.DATA_DISCONNECTED;
  }

  int serviceState() {
    return model.airplane ? ServiceState.STATE_POWER_OFF : ServiceState.STATE_IN_SERVICE;
  }

  /** Tells the application's service-state listeners when airplane mode turns the radio off. */
  private void telephony() {
    TelephonyManager manager = app.getSystemService(TelephonyManager.class);
    if (manager == null) return;
    ShadowTelephonyManager shadow = shadowOf(manager);
    if (lastServiceState != serviceState()) {
      lastServiceState = serviceState();
      ServiceState service = new ServiceState();
      service.setState(lastServiceState);
      shadow.setServiceState(service);
    }
  }

  private int lastServiceState = ServiceState.STATE_IN_SERVICE;

  // ---------------------------------------------------------------------------------------------
  // Callbacks

  void register(ConnectivityManager.NetworkCallback callback, NetworkRequest request, Kind kind) {
    Registration existing = registrations.remove(callback);
    if (existing != null) order.remove(existing);
    Registration registration = new Registration(callback, request, kind);
    registrations.put(callback, registration);
    order.add(registration);
    if (kind == Kind.REQUEST && request != null) {
      NetworkSpecifier specifier = request.getNetworkSpecifier();
      if (specifier != null && wifi.isPeerRequest(specifier)) wifi.peerRequested(registration);
    }
  }

  void unregister(ConnectivityManager.NetworkCallback callback) {
    Registration registration = registrations.remove(callback);
    if (registration == null) return;
    order.remove(registration);
    wifi.released(registration);
  }

  Registration registration(ConnectivityManager.NetworkCallback callback) {
    return registrations.get(callback);
  }

  /** Tells each callback what changed since it last heard; newly registered ones hear it all. */
  void sync() {
    for (Registration r : new ArrayList<>(order)) {
      if (!registrations.containsKey(r.callback)) continue;
      List<SimNetwork> target = targets(r);
      List<Integer> ids = new ArrayList<>();
      for (SimNetwork n : target) ids.add(n.netId);
      for (Integer id : new ArrayList<>(r.heard.keySet())) {
        if (ids.contains(id)) continue;
        r.heard.remove(id);
        r.callback.onLost(ShadowNetwork.newInstance(id));
      }
      for (SimNetwork n : target) {
        Integer heard = r.heard.get(n.netId);
        if (heard == null) {
          r.heard.put(n.netId, n.generation);
          r.callback.onAvailable(n.network);
          r.callback.onCapabilitiesChanged(n.network, new NetworkCapabilities(n.caps));
          r.callback.onLinkPropertiesChanged(n.network, copy(n.link));
          if (Build.VERSION.SDK_INT >= 29) r.callback.onBlockedStatusChanged(n.network, false);
        } else if (heard != n.generation) {
          r.heard.put(n.netId, n.generation);
          r.callback.onCapabilitiesChanged(n.network, new NetworkCapabilities(n.caps));
        }
      }
    }
  }

  /** Delivers to one registration now: a peer network granted, or {@code onUnavailable}. */
  void unavailable(Registration r) {
    unregister(r.callback);
    r.callback.onUnavailable();
  }

  private List<SimNetwork> targets(Registration r) {
    if (r.peer != null) return Collections.singletonList(r.peer);
    SimNetwork def = defaultNetwork();
    switch (r.kind) {
      case DEFAULT:
        return def == null ? Collections.emptyList() : Collections.singletonList(def);
      case REQUEST:
        {
          if (wifi.isPending(r)) return Collections.emptyList();
          if (def != null && matches(r.request, def)) return Collections.singletonList(def);
          for (SimNetwork n : networks) {
            if (matches(r.request, n)) return Collections.singletonList(n);
          }
          return Collections.emptyList();
        }
      default:
        {
          List<SimNetwork> all = new ArrayList<>();
          for (SimNetwork n : networks) if (matches(r.request, n)) all.add(n);
          return all;
        }
    }
  }

  /** A copy, as each callback gets its own; the copy constructor is hidden. */
  static LinkProperties copy(LinkProperties link) {
    try {
      return LinkProperties.class.getConstructor(LinkProperties.class).newInstance(link);
    } catch (ReflectiveOperationException e) {
      return link;
    }
  }

  private static boolean matches(NetworkRequest request, SimNetwork network) {
    if (request == null) return true;
    if (Build.VERSION.SDK_INT >= 30) return request.canBeSatisfiedBy(network.caps);
    for (int transport = 0; transport <= 8; transport++) {
      if (request.hasTransport(transport) && !network.caps.hasTransport(transport)) {
        // Any one of the requested transports will do.
        boolean any = false;
        for (int other = 0; other <= 8; other++) {
          if (request.hasTransport(other) && network.caps.hasTransport(other)) any = true;
        }
        if (!any) return false;
      }
    }
    for (int capability = 0; capability < 40; capability++) {
      if (request.hasCapability(capability) && !network.caps.hasCapability(capability)) {
        return false;
      }
    }
    return true;
  }

  // ---------------------------------------------------------------------------------------------
  // Queries, for SimulatorShadowConnectivityManager

  SimNetwork defaultNetwork() {
    return networks.isEmpty() ? null : networks.get(networks.size() - 1);
  }

  /** The device's networks, and the local-only ones granted to Wi-Fi requests. */
  List<SimNetwork> all() {
    List<SimNetwork> all = new ArrayList<>(networks);
    for (Registration r : order) if (r.peer != null) all.add(r.peer);
    return all;
  }

  SimNetwork find(Network network) {
    if (network == null) return null;
    for (SimNetwork n : all()) if (n.network.equals(network)) return n;
    return null;
  }

  SimNetwork byLegacyType(int type) {
    for (SimNetwork n : networks) if (n.info.getType() == type) return n;
    return null;
  }

  boolean isActiveNetworkMetered() {
    SimNetwork def = defaultNetwork();
    return def != null && !def.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
  }

  /** A fresh network id, for a local-only network. */
  SimNetwork newPeer(String key) {
    return new SimNetwork(Transport.WIFI, key, nextNetId++);
  }

  private ConnectivityManager manager() {
    return app.getSystemService(ConnectivityManager.class);
  }

  /** Called on each simulator cycle: callbacks registered since the last one hear the state. */
  void cycle() {
    sync();
  }

  // ---------------------------------------------------------------------------------------------

  JSONObject state() {
    SimNetwork def = defaultNetwork();
    JSONArray list = new JSONArray();
    for (SimNetwork n : all()) list.put(describe(n, n == def));
    Map<String, Object> gate = NetworkGate.state();
    Protocol.Json traffic = new Protocol.Json();
    for (Map.Entry<String, Object> e : gate.entrySet()) {
      Object v = e.getValue();
      traffic.put(e.getKey(), v instanceof List ? new JSONArray((List<?>) v) : v);
    }
    return new Protocol.Json()
        .put("mode", model.mode.id())
        .put("airplane", model.airplane)
        .put("wifi", model.wifiRadio)
        .put("data", model.data)
        .put("bluetooth", bluetooth == null ? null : bluetooth.isOn())
        .put("connected", def != null)
        .put("internet", model.internet())
        .put("default", def == null ? null : def.transport.name().toLowerCase(Locale.ROOT))
        .put("metered", isActiveNetworkMetered())
        .put("cellular", cellular)
        .put("dataSaver", dataSaver)
        .put("networks", list)
        .put("callbacks", order.size())
        .put("traffic", traffic.object())
        .object();
  }

  private static JSONObject describe(SimNetwork n, boolean isDefault) {
    JSONArray caps = new JSONArray();
    for (Map.Entry<String, Integer> e : CAPABILITY_NAMES.entrySet()) {
      if (n.caps.hasCapability(e.getValue())) caps.put(e.getKey());
    }
    return new Protocol.Json()
        .put("netId", n.netId)
        .put("transport", n.transport.name().toLowerCase(Locale.ROOT))
        .put("default", isDefault)
        .put("capabilities", caps)
        .put("downKbps", n.caps.getLinkDownstreamBandwidthKbps())
        .put("upKbps", n.caps.getLinkUpstreamBandwidthKbps())
        .put("interface", n.link == null ? null : n.link.getInterfaceName())
        .object();
  }

  private static final Map<String, Integer> CAPABILITY_NAMES = new LinkedHashMap<>();

  static {
    CAPABILITY_NAMES.put("INTERNET", NetworkCapabilities.NET_CAPABILITY_INTERNET);
    CAPABILITY_NAMES.put("VALIDATED", NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    CAPABILITY_NAMES.put("CAPTIVE_PORTAL", NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
    CAPABILITY_NAMES.put("NOT_METERED", NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    CAPABILITY_NAMES.put("NOT_VPN", NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
    CAPABILITY_NAMES.put("NOT_ROAMING", NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
    CAPABILITY_NAMES.put("NOT_RESTRICTED", NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
  }
}
