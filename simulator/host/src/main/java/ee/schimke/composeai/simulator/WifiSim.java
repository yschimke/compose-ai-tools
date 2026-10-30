package ee.schimke.composeai.simulator;

import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkSpecifier;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PatternMatcher;
import ee.schimke.composeai.simulator.NetworkSim.Registration;
import ee.schimke.composeai.simulator.NetworkSim.SimNetwork;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowScanResult;
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * Wi-Fi as {@code WifiManager} reports it: the radio's state, the access point it is associated
 * with (SSID, BSSID, RSSI), the scan results an agent puts in range, and the app's own requests — a
 * peer-to-peer {@code requestNetwork} with a {@code WifiNetworkSpecifier} (a system dialog on a
 * device, answered here by policy or by the agent) and {@code addNetworkSuggestions}. Each change
 * sends the broadcast the system sends: {@code WIFI_STATE_CHANGED}, {@code NETWORK_STATE_CHANGED},
 * {@code SCAN_RESULTS_AVAILABLE}, {@code WIFI_NETWORK_SUGGESTION_POST_CONNECTION}.
 *
 * <p>Whether Wi-Fi is up is {@link NetworkModel}'s; {@link NetworkSim} calls {@link #refresh} after
 * each change.
 */
final class WifiSim {

  private final Application app;
  private final NetworkSim network;
  private Protocol protocol;

  private String ssid = "AndroidWifi";
  private String bssid = "02:15:b2:00:01:00";
  private int rssi = -50;
  private int frequency = 5180;
  private final List<JSONObject> scan = new ArrayList<>();

  private Boolean lastEnabled;
  private Boolean lastConnected;
  private String lastSsid;

  /** How a peer-to-peer request is answered: {@code accept}, {@code reject} or {@code ask}. */
  private String policy = "accept";

  private int nextRequestId = 1;
  private final List<Pending> pending = new ArrayList<>();
  private List<android.net.wifi.WifiNetworkSuggestion> lastSuggestions;

  private static final class Pending {
    final int id;
    final Registration registration;
    final JSONObject description;
    boolean announced;

    Pending(int id, Registration registration, JSONObject description) {
      this.id = id;
      this.registration = registration;
      this.description = description;
    }
  }

  WifiSim(Application app, NetworkSim network) {
    this.app = app;
    this.network = network;
  }

  void attach(Protocol protocol) {
    this.protocol = protocol;
  }

  String ssid() {
    return ssid;
  }

  boolean enabled() {
    return network.model.wifiRadio;
  }

  private boolean connected() {
    NetworkModel m = network.model;
    return m.wifiRadio && m.wifiConnected && m.mode.usesWifi();
  }

  // ---------------------------------------------------------------------------------------------

  /** Brings {@code WifiManager} in line with the model, broadcasting what changed. */
  void refresh() {
    boolean enabled = enabled();
    boolean connected = connected();
    if (lastEnabled != null && lastEnabled != enabled) {
      app.sendBroadcast(
          new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION)
              .putExtra(
                  WifiManager.EXTRA_WIFI_STATE,
                  enabled ? WifiManager.WIFI_STATE_ENABLED : WifiManager.WIFI_STATE_DISABLED)
              .putExtra(
                  WifiManager.EXTRA_PREVIOUS_WIFI_STATE,
                  enabled ? WifiManager.WIFI_STATE_DISABLED : WifiManager.WIFI_STATE_ENABLED));
    }
    boolean ssidChanged = connected && lastSsid != null && !lastSsid.equals(ssid);
    if (lastConnected != null && (lastConnected != connected || ssidChanged)) {
      NetworkInfo info = NetworkSim.info(ConnectivityManager.TYPE_WIFI, 0, connected);
      Intent intent =
          new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION)
              .putExtra(WifiManager.EXTRA_NETWORK_INFO, info);
      if (connected) {
        intent.putExtra(WifiManager.EXTRA_BSSID, bssid);
        intent.putExtra(WifiManager.EXTRA_WIFI_INFO, connectionInfo());
      }
      app.sendBroadcast(intent);
      if (connected) suggestionConnected();
    }
    lastEnabled = enabled;
    lastConnected = connected;
    lastSsid = connected ? ssid : null;
  }

  WifiInfo connectionInfo() {
    WifiInfo info = ShadowWifiInfo.newInstance();
    ShadowWifiInfo shadow = shadowOf(info);
    shadow.setSSID(ssid);
    shadow.setBSSID(bssid);
    shadow.setRssi(rssi);
    shadow.setFrequency(frequency);
    shadow.setLinkSpeed(frequency > 3000 ? 866 : 144);
    shadow.setNetworkId(1);
    shadow.setSupplicantState(SupplicantState.COMPLETED);
    shadow.setMacAddress("02:00:00:00:00:00");
    try {
      shadow.setInetAddress(InetAddress.getByName("192.168.1.23"));
    } catch (java.net.UnknownHostException e) {
      // A literal.
    }
    return info;
  }

  private static WifiInfo disconnectedInfo() {
    WifiInfo info = ShadowWifiInfo.newInstance();
    ShadowWifiInfo shadow = shadowOf(info);
    shadow.setSupplicantState(SupplicantState.DISCONNECTED);
    shadow.setNetworkId(-1);
    shadow.setRssi(-127);
    return info;
  }

  /** What {@code getConnectionInfo()} returns now. */
  WifiInfo currentInfo() {
    return connected() ? connectionInfo() : disconnectedInfo();
  }

  /** {@code startScan()}: with the radio on, results are announced on the main looper. */
  boolean startScan() {
    if (!enabled()) return false;
    event("wifiScan", new Protocol.Json().put("by", "app").object());
    new android.os.Handler(android.os.Looper.getMainLooper())
        .post(
            () ->
                app.sendBroadcast(
                    new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                        .putExtra(WifiManager.EXTRA_RESULTS_UPDATED, true)));
    return true;
  }

  /**
   * The app's own {@code setWifiEnabled}: refused for an app targeting Android 10 or later, as a
   * device refuses it; obeyed for an older one.
   */
  boolean appSetEnabled(boolean on) {
    if (app.getApplicationInfo().targetSdkVersion >= 29) return false;
    network.model.wifiRadio = on;
    network.apply(true);
    return true;
  }

  /** The scan results the app sees: none with the radio off. */
  List<ScanResult> scanResults() {
    if (!enabled()) return new ArrayList<>();
    List<ScanResult> results = new ArrayList<>();
    boolean hasCurrent = false;
    for (JSONObject n : scan) {
      results.add(scanResult(n));
      if (ssid.equals(n.optString("ssid"))) hasCurrent = true;
    }
    if (connected() && !hasCurrent) {
      results.add(
          ShadowScanResult.newInstance(
              ssid, bssid, "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]", rssi, frequency));
    }
    return results;
  }

  private static ScanResult scanResult(JSONObject n) {
    String security = n.optString("security", "wpa2").toLowerCase(Locale.ROOT);
    String caps;
    switch (security) {
      case "open":
      case "none":
        caps = "[ESS]";
        break;
      case "wep":
        caps = "[WEP][ESS]";
        break;
      case "wpa3":
      case "sae":
        caps = "[RSN-SAE-CCMP][ESS]";
        break;
      default:
        caps = "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]";
    }
    ScanResult result =
        ShadowScanResult.newInstance(
            n.optString("ssid"),
            n.optString("bssid", bssidFor(n.optString("ssid"))),
            caps,
            n.optInt("rssi", -60),
            n.optInt("frequency", 2437));
    result.timestamp = android.os.SystemClock.elapsedRealtime() * 1000;
    return result;
  }

  /** A stable, locally administered BSSID for an SSID given none. */
  private static String bssidFor(String ssid) {
    int h = ssid.hashCode();
    return String.format(
        Locale.ROOT,
        "02:%02x:%02x:%02x:%02x:01",
        (h >> 24) & 0xff,
        (h >> 16) & 0xff,
        (h >> 8) & 0xff,
        h & 0xff);
  }

  // ---------------------------------------------------------------------------------------------
  // The `wifi` command

  /**
   * {@code op}: {@code status} (default); {@code scan} with {@code networks} [{ssid, bssid?, rssi?,
   * security?, frequency?}] puts them in range and sends {@code SCAN_RESULTS_AVAILABLE}; {@code
   * connect} with {@code ssid} (and bssid?, rssi?) associates with it, Wi-Fi becoming the network;
   * {@code disconnect}; {@code request} answers the app's oldest (or {@code requestId}'s)
   * peer-to-peer request ({@code answer}: accept / reject; {@code ssid} if its pattern names none),
   * or sets {@code policy} (accept / reject / ask) for later ones.
   */
  JSONObject command(JSONObject c) throws JSONException {
    String op = c.optString("op", "status");
    switch (op) {
      case "status":
        break;
      case "scan":
        {
          JSONArray networks = c.optJSONArray("networks");
          if (networks != null) {
            scan.clear();
            for (int i = 0; i < networks.length(); i++) scan.add(networks.getJSONObject(i));
          }
          if (enabled()) {
            refresh();
            app.sendBroadcast(
                new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                    .putExtra(WifiManager.EXTRA_RESULTS_UPDATED, true));
          }
          break;
        }
      case "connect":
        {
          String target = c.getString("ssid");
          JSONObject inRange = null;
          for (JSONObject n : scan) if (target.equals(n.optString("ssid"))) inRange = n;
          ssid = target;
          bssid =
              c.optString(
                  "bssid",
                  inRange != null
                      ? inRange.optString("bssid", bssidFor(target))
                      : bssidFor(target));
          rssi = c.optInt("rssi", inRange != null ? inRange.optInt("rssi", -55) : -55);
          frequency =
              c.optInt("frequency", inRange != null ? inRange.optInt("frequency", 5180) : 5180);
          NetworkModel m = network.model;
          if (!m.mode.usesWifi()) m.mode = NetworkModel.Mode.WIFI;
          m.wifiRadio = true;
          m.wifiConnected = true;
          if (c.has("metered")) m.metered = c.getBoolean("metered");
          network.apply(true);
          break;
        }
      case "disconnect":
        network.model.wifiConnected = false;
        network.apply(true);
        break;
      case "request":
        if (c.has("policy")) {
          String p = c.getString("policy");
          if (!p.equals("accept") && !p.equals("reject") && !p.equals("ask")) {
            throw new IllegalArgumentException("policy is accept, reject or ask, not " + p);
          }
          policy = p;
        }
        if (c.has("answer")) {
          Pending target = null;
          for (Pending p : pending) {
            if (!c.has("requestId") || c.getInt("requestId") == p.id) {
              target = p;
              break;
            }
          }
          if (target == null) throw new IllegalStateException("no Wi-Fi request is waiting");
          answer(target, c.getString("answer"), c.optString("ssid", null));
        }
        break;
      default:
        throw new IllegalArgumentException(
            "wifi op is status, scan, connect, disconnect or request, not " + op);
    }
    return state();
  }

  JSONObject state() {
    JSONArray results = new JSONArray();
    {
      for (ScanResult r : scanResults()) {
        results.put(
            new Protocol.Json()
                .put("ssid", r.SSID)
                .put("bssid", r.BSSID)
                .put("rssi", r.level)
                .put("frequency", r.frequency)
                .put("capabilities", r.capabilities)
                .object());
      }
    }
    JSONArray requests = new JSONArray();
    for (Pending p : pending) requests.put(p.description);
    return new Protocol.Json()
        .put("enabled", enabled())
        .put("connected", connected())
        .put("ssid", connected() ? ssid : null)
        .put("bssid", connected() ? bssid : null)
        .put("rssi", connected() ? rssi : null)
        .put("scanResults", results)
        .put("policy", policy)
        .put("pendingRequests", requests)
        .put("suggestions", suggestions())
        .object();
  }

  // ---------------------------------------------------------------------------------------------
  // Peer-to-peer requests (WifiNetworkSpecifier)

  boolean isPeerRequest(NetworkSpecifier specifier) {
    return Build.VERSION.SDK_INT >= 29
        && specifier.getClass().getName().equals("android.net.wifi.WifiNetworkSpecifier");
  }

  void peerRequested(Registration registration) {
    JSONObject description = describe(registration.request.getNetworkSpecifier());
    Pending p = new Pending(nextRequestId++, registration, description);
    try {
      description.put("requestId", p.id);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    pending.add(p);
  }

  boolean isPending(Registration registration) {
    for (Pending p : pending) if (p.registration == registration) return true;
    return false;
  }

  void released(Registration registration) {
    for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
      Pending p = it.next();
      if (p.registration != registration) continue;
      it.remove();
      event(
          "wifiRequest",
          new Protocol.Json().put("requestId", p.id).put("state", "withdrawn").object());
    }
    if (registration.peer != null) {
      event(
          "wifiRequest",
          new Protocol.Json()
              .put("netId", registration.peer.netId)
              .put("state", "released")
              .object());
    }
  }

  /** Announces new requests, answers them by policy, and reports new suggestions. */
  void cycle() {
    for (Pending p : new ArrayList<>(pending)) {
      if (!p.announced && protocol != null) {
        p.announced = true;
        JSONObject e = new JSONObject();
        try {
          for (Iterator<String> k = p.description.keys(); k.hasNext(); ) {
            String key = k.next();
            e.put(key, p.description.get(key));
          }
          e.put("state", "waiting");
          e.put("policy", policy);
        } catch (JSONException ex) {
          throw new IllegalStateException(ex);
        }
        event("wifiRequest", e);
      }
      if (!policy.equals("ask")) answer(p, policy, null);
    }
  }

  private void answer(Pending p, String answer, String chosenSsid) {
    pending.remove(p);
    Registration r = p.registration;
    if (answer.equals("reject")) {
      event(
          "wifiRequest",
          new Protocol.Json().put("requestId", p.id).put("state", "rejected").object());
      network.unavailable(r);
      return;
    }
    if (!answer.equals("accept")) {
      throw new IllegalArgumentException("answer is accept or reject, not " + answer);
    }
    String name = chosenSsid;
    if (name == null) name = p.description.optString("ssid", null);
    if (name == null) {
      String pattern = p.description.optString("ssidPattern", null);
      for (JSONObject n : scan) {
        if (pattern != null && n.optString("ssid").startsWith(pattern)) name = n.optString("ssid");
      }
      if (name == null) name = pattern != null ? pattern : "DIRECT-peer";
    }
    SimNetwork peer = network.newPeer("peer:" + name + ":" + p.id);
    NetworkCapabilities caps = ShadowNetworkCapabilities.newInstance();
    ShadowNetworkCapabilities shadow = shadowOf(caps);
    shadow.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
    shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
    shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED);
    shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
    shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
    try {
      shadow.setNetworkSpecifier(r.request.getNetworkSpecifier());
    } catch (RuntimeException e) {
      // Matched by ownership regardless.
    }
    peer.caps = caps;
    peer.generation = 1;
    LinkProperties link = new LinkProperties();
    link.setInterfaceName("wlan1");
    peer.link = link;
    peer.info = NetworkSim.info(ConnectivityManager.TYPE_WIFI, 0, true);
    r.peer = peer;
    event(
        "wifiRequest",
        new Protocol.Json()
            .put("requestId", p.id)
            .put("state", "connected")
            .put("ssid", name)
            .put("netId", peer.netId)
            .object());
    network.sync();
  }

  /** The specifier's SSID (or pattern) and BSSID, read from its hidden fields. */
  private static JSONObject describe(NetworkSpecifier specifier) {
    Protocol.Json json = new Protocol.Json().put("specifier", String.valueOf(specifier));
    for (Class<?> type = specifier.getClass(); type != null; type = type.getSuperclass()) {
      for (Field field : type.getDeclaredFields()) {
        if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
        try {
          field.setAccessible(true);
          Object value = field.get(specifier);
          if (value instanceof PatternMatcher) {
            PatternMatcher matcher = (PatternMatcher) value;
            if (matcher.getType() == PatternMatcher.PATTERN_LITERAL) {
              json.put("ssid", matcher.getPath());
            } else {
              json.put("ssidPattern", matcher.getPath());
            }
          } else if (value instanceof android.util.Pair) {
            Object first = ((android.util.Pair<?, ?>) value).first;
            if (first != null && !String.valueOf(first).matches("0[02](:00){5}")) {
              json.put("bssid", String.valueOf(first));
            }
          }
        } catch (ReflectiveOperationException | RuntimeException e) {
          // Described by toString alone.
        }
      }
    }
    return json.object();
  }

  // ---------------------------------------------------------------------------------------------
  // Suggestions

  private JSONArray suggestions() {
    JSONArray list = new JSONArray();
    if (lastSuggestions == null) return list;
    for (Object s : lastSuggestions) list.put(suggestionSsid(s));
    return list;
  }

  private static String suggestionSsid(Object suggestion) {
    if (Build.VERSION.SDK_INT >= 30) {
      String s = ((android.net.wifi.WifiNetworkSuggestion) suggestion).getSsid();
      if (s != null) return s.replace("\"", "");
    }
    return String.valueOf(suggestion);
  }

  /** From {@link SimulatorShadowWifiManager}: the app's {@code addNetworkSuggestions}. */
  void suggested(List<android.net.wifi.WifiNetworkSuggestion> suggestions) {
    List<android.net.wifi.WifiNetworkSuggestion> all = new ArrayList<>();
    if (lastSuggestions != null) all.addAll(lastSuggestions);
    all.addAll(suggestions);
    lastSuggestions = all;
    event("wifiSuggestions", new Protocol.Json().put("ssids", suggestions()).object());
  }

  List<android.net.wifi.WifiNetworkSuggestion> suggestionList() {
    return lastSuggestions == null ? new ArrayList<>() : new ArrayList<>(lastSuggestions);
  }

  /**
   * {@code WIFI_NETWORK_SUGGESTION_POST_CONNECTION}, sent to the app when it connects to one of its
   * own suggestions that asked for it ({@code setIsAppInteractionRequired}).
   */
  private void suggestionConnected() {
    if (lastSuggestions == null || Build.VERSION.SDK_INT < 30) return;
    for (Object s : lastSuggestions) {
      if (!ssid.equals(suggestionSsid(s))) continue;
      boolean wantsIt = true;
      try {
        Field field = s.getClass().getDeclaredField("isAppInteractionRequired");
        field.setAccessible(true);
        wantsIt = field.getBoolean(s);
      } catch (ReflectiveOperationException | RuntimeException e) {
        // Sent.
      }
      if (!wantsIt) continue;
      app.sendBroadcast(
          new Intent(WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION)
              .setPackage(app.getPackageName())
              .putExtra(
                  WifiManager.EXTRA_NETWORK_SUGGESTION,
                  (android.net.wifi.WifiNetworkSuggestion) s));
      event("wifiSuggestions", new Protocol.Json().put("connected", ssid).object());
    }
  }

  private void event(String name, JSONObject body) {
    if (protocol != null) protocol.event(name, body);
  }
}
