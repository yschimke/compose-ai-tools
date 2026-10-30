package ee.schimke.composeai.simulator;

import android.app.Activity;
import android.app.Application;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBluetoothAdapter;
import org.robolectric.shadows.ShadowBluetoothDevice;
import org.robolectric.shadows.ShadowBluetoothGatt;
import org.robolectric.shadows.ShadowBluetoothLeScanner;

/**
 * Bluetooth as an agent stages it: the adapter on or off ({@code ACTION_STATE_CHANGED}, through
 * {@code TURNING_ON} / {@code TURNING_OFF}), the app's {@code ACTION_REQUEST_ENABLE} answered as
 * the system dialog would be (by policy, or by the agent), bonded devices, classic discovery
 * ({@code ACTION_FOUND}), BLE peripherals advertising to the app's {@code BluetoothLeScanner} scans
 * (filters applied, as the stack applies them), and a GATT peripheral behind each connectable one:
 * {@code connectGatt} connects, {@code discoverServices} finds the services the agent declared,
 * reads return their values, and the app's writes and subscriptions are reported as {@code gatt}
 * events the agent answers with {@code notify}.
 *
 * <p>Robolectric's adapter keeps state but sends nothing; this class compares it every cycle, so an
 * app that calls {@code enable()} or {@code startDiscovery()} itself hears the broadcasts too.
 */
final class BluetoothSim {

  private static BluetoothSim current;

  private final Application app;
  private final Protocol protocol;
  private final Navigator navigator;

  /**
   * How {@code ACTION_REQUEST_ENABLE} is answered: {@code accept}, {@code reject} or {@code ask}.
   */
  private String policy = "accept";

  private int lastState = -1;
  private boolean wasDiscovering;
  private long discoveryEndsAt;
  private boolean onBeforeAirplane = true;

  /** In range: address → its advertisement (BLE) or inquiry answer (classic). */
  private final Map<String, Peripheral> peripherals = new LinkedHashMap<>();

  private final Set<Object> seenScans = Collections.newSetFromMap(new IdentityHashMap<>());
  private final Set<Object> seenLeCallbacks = Collections.newSetFromMap(new IdentityHashMap<>());
  private final List<BluetoothGatt> connecting = new ArrayList<>();
  private final List<EnableRequest> enableRequests = new ArrayList<>();
  private int nextRequestId = 1;

  private static final class Peripheral {
    JSONObject spec;
    BluetoothDevice device;
    byte[] advertisement;
    boolean ble;
  }

  private static final class EnableRequest {
    final int id;
    final Activity requester;
    final int requestCode;
    boolean announced;

    EnableRequest(int id, Activity requester, int requestCode) {
      this.id = id;
      this.requester = requester;
      this.requestCode = requestCode;
    }
  }

  BluetoothSim(Application app, Protocol protocol, Navigator navigator) {
    this.app = app;
    this.protocol = protocol;
    this.navigator = navigator;
    current = this;
    BluetoothAdapter adapter = adapter();
    if (adapter != null) lastState = adapter.getState();
    // Launched in airplane mode: Bluetooth is off, and comes back when airplane mode ends.
    onBeforeAirplane = true;
  }

  static BluetoothSim current() {
    return current;
  }

  /**
   * The devices in range, {@code {address, name}}: what a companion-device chooser would list
   * ({@link SimulatorShadowCompanionDeviceManager}).
   */
  List<String[]> inRange() {
    List<String[]> result = new ArrayList<>();
    for (Map.Entry<String, Peripheral> e : peripherals.entrySet()) {
      String name = e.getValue().spec == null ? null : e.getValue().spec.optString("name", null);
      result.add(new String[] {e.getKey(), name == null ? e.getKey() : name});
    }
    return result;
  }

  private BluetoothAdapter adapter() {
    BluetoothManager manager = app.getSystemService(BluetoothManager.class);
    return manager == null ? null : manager.getAdapter();
  }

  private ShadowBluetoothAdapter shadowAdapter() {
    BluetoothAdapter adapter = adapter();
    return adapter == null ? null : Shadow.extract(adapter);
  }

  boolean isOn() {
    BluetoothAdapter adapter = adapter();
    return adapter != null && adapter.getState() == BluetoothAdapter.STATE_ON;
  }

  // ---------------------------------------------------------------------------------------------
  // The adapter

  /** Turns the radio on or off as the system does, through the transitional state. */
  void setEnabled(boolean on) {
    ShadowBluetoothAdapter shadow = shadowAdapter();
    if (shadow == null || isOn() == on) return;
    int from = adapter().getState();
    int via = on ? BluetoothAdapter.STATE_TURNING_ON : BluetoothAdapter.STATE_TURNING_OFF;
    int to = on ? BluetoothAdapter.STATE_ON : BluetoothAdapter.STATE_OFF;
    shadow.setState(via);
    stateChanged(from, via);
    shadow.setState(to);
    stateChanged(via, to);
    lastState = to;
    Settings.Global.putInt(app.getContentResolver(), Settings.Global.BLUETOOTH_ON, on ? 1 : 0);
    if (!on) {
      // Scans and connections end with the radio.
      wasDiscovering = false;
      for (Peripheral p : peripherals.values()) disconnectAll(p.device, 0);
    }
  }

  private void stateChanged(int from, int to) {
    app.sendBroadcast(
        new Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
            .putExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, from)
            .putExtra(BluetoothAdapter.EXTRA_STATE, to));
  }

  /** Airplane mode takes the radio down, and restores it after. */
  void airplane(boolean on) {
    if (on) {
      onBeforeAirplane = isOn();
      setEnabled(false);
    } else if (onBeforeAirplane) {
      setEnabled(true);
    }
  }

  String setting(String key, String value) {
    if (!key.equals(Settings.Global.BLUETOOTH_ON)) return null;
    setEnabled(!value.equals("0"));
    return "bluetooth " + (isOn() ? "on" : "off");
  }

  /**
   * {@code BluetoothAdapter.ACTION_REQUEST_ENABLE} from the app, which a device shows as a dialog:
   * answered by policy on the next cycle, or held for {@code bluetooth request}.
   */
  boolean requestEnable(Intent intent, int requestCode, Activity requester) {
    if (!BluetoothAdapter.ACTION_REQUEST_ENABLE.equals(intent.getAction())) return false;
    enableRequests.add(new EnableRequest(nextRequestId++, requester, requestCode));
    return true;
  }

  private void answer(EnableRequest request, String answer) {
    enableRequests.remove(request);
    boolean accept = answer.equals("accept");
    if (!accept && !answer.equals("reject")) {
      throw new IllegalArgumentException("answer is accept or reject, not " + answer);
    }
    if (accept) setEnabled(true);
    event(
        "bluetoothRequest",
        new Protocol.Json()
            .put("requestId", request.id)
            .put("request", "enable")
            .put("state", accept ? "accepted" : "rejected")
            .object());
    if (request.requester != null && request.requestCode >= 0) {
      navigator.deliverResult(
          request.requester,
          request.requestCode,
          accept ? Activity.RESULT_OK : Activity.RESULT_CANCELED,
          null);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The `bluetooth` command

  /**
   * {@code op}: {@code status} (default); {@code enable} / {@code disable}; {@code request} ({@code
   * answer} accept / reject the oldest enable request, or {@code requestId}'s; {@code policy}
   * accept / reject / ask); {@code advertise} ({@code address}, {@code name}?, {@code rssi}?,
   * {@code serviceUuids}?, {@code serviceData}?, {@code manufacturerData}?, {@code txPower}?,
   * {@code connectable}?, {@code gatt}? {services: [{uuid, characteristics: [{uuid, properties,
   * value?}]}]}); {@code found} (a classic device: {@code address}, {@code name}, {@code rssi}?,
   * {@code deviceClass}?); {@code remove} ({@code address}); {@code bond} / {@code unbond}; {@code
   * notify} ({@code address}, {@code characteristic}, {@code value}); {@code disconnect}.
   */
  JSONObject command(JSONObject c) throws JSONException {
    String op = c.optString("op", "status");
    switch (op) {
      case "status":
        break;
      case "enable":
        setEnabled(true);
        break;
      case "disable":
        setEnabled(false);
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
          EnableRequest target = null;
          for (EnableRequest r : enableRequests) {
            if (!c.has("requestId") || c.getInt("requestId") == r.id) {
              target = r;
              break;
            }
          }
          if (target == null) throw new IllegalStateException("no Bluetooth request is waiting");
          answer(target, c.getString("answer"));
        }
        break;
      case "advertise":
        {
          Peripheral p = peripheral(c.getString("address"), c);
          p.ble = true;
          p.advertisement = AdvertisingData.build(c);
          deliverToScans(Collections.singletonList(p), null);
          break;
        }
      case "found":
        {
          Peripheral p = peripheral(c.getString("address"), c);
          p.ble = c.optBoolean("ble", false);
          if (adapter() != null && adapter().isDiscovering()) found(p);
          break;
        }
      case "remove":
        {
          Peripheral p = peripherals.remove(c.getString("address").toUpperCase());
          if (p != null) disconnectAll(p.device, 8); // GATT_CONN_TIMEOUT: out of range.
          break;
        }
      case "bond":
      case "unbond":
        bond(c.getString("address"), c, op.equals("bond"));
        break;
      case "notify":
        return notifyCharacteristic(c);
      case "disconnect":
        {
          Peripheral p = peripherals.get(c.getString("address").toUpperCase());
          if (p == null) throw new IllegalArgumentException("no device " + c.getString("address"));
          disconnectAll(p.device, 19); // GATT_CONN_TERMINATE_PEER_USER.
          break;
        }
      default:
        throw new IllegalArgumentException(
            "bluetooth op is status, enable, disable, request, advertise, found, remove, bond,"
                + " unbond, notify or disconnect, not "
                + op);
    }
    return state();
  }

  private Peripheral peripheral(String address, JSONObject c) throws JSONException {
    String key = address.toUpperCase();
    if (!BluetoothAdapter.checkBluetoothAddress(key)) {
      throw new IllegalArgumentException("not a Bluetooth address: " + address);
    }
    BluetoothAdapter adapter = adapter();
    if (adapter == null) throw new IllegalStateException("the simulated device has no Bluetooth");
    Peripheral p = peripherals.get(key);
    if (p == null) {
      p = new Peripheral();
      p.device = adapter.getRemoteDevice(key);
      ShadowBluetoothDevice shadow = Shadow.extract(p.device);
      shadow.setGattConnectionInterceptor(connecting::add);
      peripherals.put(key, p);
    }
    p.spec = c;
    ShadowBluetoothDevice shadow = Shadow.extract(p.device);
    if (c.has("name")) shadow.setName(c.getString("name"));
    shadow.setType(
        c.optBoolean("ble", true) || c.has("serviceUuids") || c.has("gatt")
            ? BluetoothDevice.DEVICE_TYPE_LE
            : BluetoothDevice.DEVICE_TYPE_CLASSIC);
    if (c.has("deviceClass")) {
      try {
        shadow.setBluetoothClass(
            BluetoothClass.class
                .getDeclaredConstructor(int.class)
                .newInstance(c.getInt("deviceClass")));
      } catch (ReflectiveOperationException e) {
        // Left unset.
      }
    }
    return p;
  }

  private void bond(String address, JSONObject c, boolean bonded) throws JSONException {
    Peripheral p = peripheral(address, c);
    ShadowBluetoothDevice shadow = Shadow.extract(p.device);
    int previous = p.device.getBondState();
    shadow.setBondState(bonded ? BluetoothDevice.BOND_BONDED : BluetoothDevice.BOND_NONE);
    Set<BluetoothDevice> set = new HashSet<>();
    for (Peripheral other : peripherals.values()) {
      if (other.device.getBondState() == BluetoothDevice.BOND_BONDED) set.add(other.device);
    }
    shadowAdapter().setBondedDevices(set);
    app.sendBroadcast(
        new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, p.device)
            .putExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, previous)
            .putExtra(BluetoothDevice.EXTRA_BOND_STATE, p.device.getBondState()));
  }

  JSONObject state() {
    JSONArray devices = new JSONArray();
    for (Map.Entry<String, Peripheral> e : peripherals.entrySet()) {
      Peripheral p = e.getValue();
      ShadowBluetoothDevice shadow = Shadow.extract(p.device);
      int gatts = 0;
      int connected = 0;
      for (BluetoothGatt gatt : shadow.getBluetoothGatts()) {
        gatts++;
        if (((ShadowBluetoothGatt) Shadow.extract(gatt)).isConnected()) connected++;
      }
      devices.put(
          new Protocol.Json()
              .put("address", e.getKey())
              .put("name", p.spec.optString("name", null))
              .put("ble", p.ble)
              .put("bonded", p.device.getBondState() == BluetoothDevice.BOND_BONDED)
              .put("gattClients", gatts)
              .put("connected", connected)
              .object());
    }
    BluetoothAdapter adapter = adapter();
    JSONArray scans = new JSONArray();
    BluetoothLeScanner scanner = scanner();
    if (scanner != null) {
      ShadowBluetoothLeScanner shadow = Shadow.extract(scanner);
      for (ShadowBluetoothLeScanner.ScanParams params : shadow.getActiveScans()) {
        scans.put(describe(params));
      }
    }
    JSONArray requests = new JSONArray();
    for (EnableRequest r : enableRequests) {
      requests.put(new Protocol.Json().put("requestId", r.id).put("request", "enable").object());
    }
    return new Protocol.Json()
        .put("present", adapter != null)
        .put("state", adapter == null ? null : stateName(adapter.getState()))
        .put("enabled", isOn())
        .put("discovering", adapter != null && adapter.isDiscovering())
        .put("scans", scans)
        .put("devices", devices)
        .put("policy", policy)
        .put("pendingRequests", requests)
        .object();
  }

  private static String stateName(int state) {
    switch (state) {
      case BluetoothAdapter.STATE_ON:
        return "on";
      case BluetoothAdapter.STATE_OFF:
        return "off";
      case BluetoothAdapter.STATE_TURNING_ON:
        return "turning_on";
      case BluetoothAdapter.STATE_TURNING_OFF:
        return "turning_off";
      default:
        return String.valueOf(state);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Scans and discovery

  private BluetoothLeScanner scanner() {
    BluetoothAdapter adapter = adapter();
    if (adapter == null || !isOn()) return null;
    try {
      return adapter.getBluetoothLeScanner();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private ScanResult scanResult(Peripheral p) {
    ScanRecord record;
    try {
      java.lang.reflect.Method parse =
          ScanRecord.class.getDeclaredMethod("parseFromBytes", byte[].class);
      parse.setAccessible(true);
      record = (ScanRecord) parse.invoke(null, (Object) p.advertisement);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot build a scan record", e);
    }
    int rssi = p.spec.optInt("rssi", -60);
    long now = SystemClock.elapsedRealtimeNanos();
    if (Build.VERSION.SDK_INT >= 26) {
      boolean connectable = p.spec.optBoolean("connectable", true);
      // Legacy advertising: event type bits 0x10 (legacy) | 0x01 (connectable) | 0x02 (scannable).
      int eventType = 0x10 | 0x02 | (connectable ? 0x01 : 0);
      return new ScanResult(
          p.device,
          eventType,
          BluetoothDevice.PHY_LE_1M,
          ScanResult.PHY_UNUSED,
          ScanResult.SID_NOT_PRESENT,
          p.spec.optInt("txPower", ScanResult.TX_POWER_NOT_PRESENT),
          rssi,
          0,
          record,
          now);
    }
    return new ScanResult(p.device, record, rssi, now);
  }

  /** Hands [advertisers] to the app's scans ([only] one, or all), as the stack does. */
  private void deliverToScans(
      List<Peripheral> advertisers, ShadowBluetoothLeScanner.ScanParams only) {
    BluetoothLeScanner scanner = scanner();
    if (scanner == null) return;
    ShadowBluetoothLeScanner shadow = Shadow.extract(scanner);
    for (ShadowBluetoothLeScanner.ScanParams params : new ArrayList<>(shadow.getActiveScans())) {
      if (only != null && params != only) continue;
      List<ScanResult> matched = new ArrayList<>();
      for (Peripheral p : advertisers) {
        if (!p.ble || p.advertisement == null) continue;
        ScanResult result = scanResult(p);
        if (matches(params.scanFilters(), result)) matched.add(result);
      }
      if (matched.isEmpty()) continue;
      ScanSettings settings = params.scanSettings();
      if (params.scanCallback() != null) {
        if (settings != null && settings.getReportDelayMillis() > 0) {
          params.scanCallback().onBatchScanResults(matched);
        } else {
          for (ScanResult r : matched) {
            params.scanCallback().onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r);
          }
        }
      } else if (params.pendingIntent() != null) {
        try {
          params
              .pendingIntent()
              .send(
                  app,
                  0,
                  new Intent()
                      .putParcelableArrayListExtra(
                          BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, new ArrayList<>(matched))
                      .putExtra(
                          BluetoothLeScanner.EXTRA_CALLBACK_TYPE,
                          ScanSettings.CALLBACK_TYPE_ALL_MATCHES));
        } catch (PendingIntent.CanceledException e) {
          // The app cancelled it.
        }
      }
    }
    // The pre-Lollipop API.
    ShadowBluetoothAdapter adapter = shadowAdapter();
    for (BluetoothAdapter.LeScanCallback callback : new ArrayList<>(adapter.getLeScanCallbacks())) {
      for (Peripheral p : advertisers) {
        if (p.ble && p.advertisement != null) {
          callback.onLeScan(p.device, p.spec.optInt("rssi", -60), p.advertisement);
        }
      }
    }
  }

  private static boolean matches(List<ScanFilter> filters, ScanResult result) {
    if (filters == null || filters.isEmpty()) return true;
    for (ScanFilter filter : filters) if (filter.matches(result)) return true;
    return false;
  }

  private void found(Peripheral p) {
    Intent intent =
        new Intent(BluetoothDevice.ACTION_FOUND)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, p.device)
            .putExtra(BluetoothDevice.EXTRA_RSSI, (short) p.spec.optInt("rssi", -60));
    if (p.spec.has("name")) intent.putExtra(BluetoothDevice.EXTRA_NAME, p.spec.optString("name"));
    BluetoothClass type = p.device.getBluetoothClass();
    if (type != null) intent.putExtra(BluetoothDevice.EXTRA_CLASS, type);
    app.sendBroadcast(intent);
  }

  // ---------------------------------------------------------------------------------------------
  // GATT

  private void connectPending() {
    for (BluetoothGatt gatt : new ArrayList<>(connecting)) {
      connecting.remove(gatt);
      ShadowBluetoothGatt shadow = Shadow.extract(gatt);
      if (shadow.isClosed()) continue;
      String address = gatt.getDevice().getAddress();
      Peripheral p = peripherals.get(address);
      boolean reachable = isOn() && p != null && p.spec.optBoolean("connectable", true);
      if (!reachable) {
        event(
            "gatt",
            new Protocol.Json()
                .put("address", address)
                .put("op", "connect")
                .put("state", "failed")
                .object());
        BluetoothGattCallback callback = shadow.getGattCallback();
        // 133, GATT_ERROR: what a device reports for a peripheral that never answers.
        if (callback != null) {
          callback.onConnectionStateChange(gatt, 133, BluetoothProfile.STATE_DISCONNECTED);
        }
        continue;
      }
      for (BluetoothGattService service : services(p.spec)) {
        shadow.addDiscoverableService(service);
        for (BluetoothGattCharacteristic ch : service.getCharacteristics()) {
          int props = ch.getProperties();
          if ((props
                  & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                      | BluetoothGattCharacteristic.PROPERTY_INDICATE))
              != 0) {
            shadow.allowCharacteristicNotification(ch);
          }
        }
      }
      gatt.connect();
      event(
          "gatt",
          new Protocol.Json()
              .put("address", address)
              .put("op", "connect")
              .put("state", "connected")
              .object());
    }
  }

  /** The services a peripheral's {@code gatt} spec declares, fresh for each connection. */
  private static List<BluetoothGattService> services(JSONObject spec) {
    List<BluetoothGattService> out = new ArrayList<>();
    JSONObject gatt = spec.optJSONObject("gatt");
    JSONArray services = gatt == null ? null : gatt.optJSONArray("services");
    if (services == null) return out;
    try {
      for (int i = 0; i < services.length(); i++) {
        JSONObject s = services.getJSONObject(i);
        BluetoothGattService service =
            new BluetoothGattService(
                AdvertisingData.uuid(s.getString("uuid")),
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        JSONArray chars = s.optJSONArray("characteristics");
        for (int j = 0; chars != null && j < chars.length(); j++) {
          JSONObject c = chars.getJSONObject(j);
          int props = properties(c.optJSONArray("properties"));
          BluetoothGattCharacteristic ch =
              new BluetoothGattCharacteristic(
                  AdvertisingData.uuid(c.getString("uuid")),
                  props,
                  BluetoothGattCharacteristic.PERMISSION_READ
                      | BluetoothGattCharacteristic.PERMISSION_WRITE);
          if (c.has("value")) ch.setValue(AdvertisingData.bytes(c.get("value")));
          if ((props
                  & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                      | BluetoothGattCharacteristic.PROPERTY_INDICATE))
              != 0) {
            // The Client Characteristic Configuration descriptor an app writes to subscribe.
            ch.addDescriptor(
                new BluetoothGattDescriptor(
                    AdvertisingData.uuid("2902"),
                    BluetoothGattDescriptor.PERMISSION_READ
                        | BluetoothGattDescriptor.PERMISSION_WRITE));
          }
          service.addCharacteristic(ch);
        }
        out.add(service);
      }
    } catch (JSONException e) {
      throw new IllegalArgumentException("bad gatt spec: " + e.getMessage(), e);
    }
    return out;
  }

  private static int properties(JSONArray names) throws JSONException {
    if (names == null) {
      return BluetoothGattCharacteristic.PROPERTY_READ;
    }
    int props = 0;
    for (int i = 0; i < names.length(); i++) {
      switch (names.getString(i)) {
        case "read":
          props |= BluetoothGattCharacteristic.PROPERTY_READ;
          break;
        case "write":
          props |= BluetoothGattCharacteristic.PROPERTY_WRITE;
          break;
        case "writeNoResponse":
          props |= BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
          break;
        case "notify":
          props |= BluetoothGattCharacteristic.PROPERTY_NOTIFY;
          break;
        case "indicate":
          props |= BluetoothGattCharacteristic.PROPERTY_INDICATE;
          break;
        default:
          throw new IllegalArgumentException(
              "unknown characteristic property " + names.getString(i));
      }
    }
    return props;
  }

  /** The peripheral sends a notification: each connected client hears it. */
  private JSONObject notifyCharacteristic(JSONObject c) throws JSONException {
    Peripheral p = peripherals.get(c.getString("address").toUpperCase());
    if (p == null) throw new IllegalArgumentException("no device " + c.getString("address"));
    UUID uuid = AdvertisingData.uuid(c.getString("characteristic"));
    byte[] value = AdvertisingData.bytes(c.get("value"));
    int delivered = 0;
    int unsubscribed = 0;
    ShadowBluetoothDevice device = Shadow.extract(p.device);
    for (BluetoothGatt gatt : device.getBluetoothGatts()) {
      ShadowBluetoothGatt shadow = Shadow.extract(gatt);
      if (!shadow.isConnected() || shadow.getGattCallback() == null) continue;
      // As a peripheral sends only to clients that enabled it, and the stack hands it only to
      // a client that registered for it.
      if (shadow instanceof SimulatorShadowBluetoothGatt
          && !((SimulatorShadowBluetoothGatt) shadow).subscriptions.isSubscribed(uuid)) {
        unsubscribed++;
        continue;
      }
      for (BluetoothGattService service : gatt.getServices()) {
        BluetoothGattCharacteristic ch = service.getCharacteristic(uuid);
        if (ch == null) continue;
        ch.setValue(value);
        if (Build.VERSION.SDK_INT >= 33) {
          shadow.getGattCallback().onCharacteristicChanged(gatt, ch, value);
        } else {
          shadow.getGattCallback().onCharacteristicChanged(gatt, ch);
        }
        delivered++;
      }
    }
    return new Protocol.Json()
        .put("delivered", delivered)
        .put("notSubscribed", unsubscribed)
        .put("value", AdvertisingData.hex(value))
        .object();
  }

  private void disconnectAll(BluetoothDevice device, int status) {
    ShadowBluetoothDevice shadow = Shadow.extract(device);
    for (BluetoothGatt gatt : shadow.getBluetoothGatts()) {
      ShadowBluetoothGatt g = Shadow.extract(gatt);
      if (!g.isConnected() || g.getGattCallback() == null) continue;
      g.getGattCallback()
          .onConnectionStateChange(gatt, status, BluetoothProfile.STATE_DISCONNECTED);
      try {
        // Marks it disconnected without a second callback.
        java.lang.reflect.Field connected =
            ShadowBluetoothGatt.class.getDeclaredField("isConnected");
        connected.setAccessible(true);
        connected.setBoolean(g, false);
      } catch (ReflectiveOperationException e) {
        // Reported connected until the app closes it.
      }
      event(
          "gatt",
          new Protocol.Json()
              .put("address", device.getAddress())
              .put("op", "disconnect")
              .put("state", "disconnected")
              .put("status", status)
              .object());
    }
  }

  /** From {@link SimulatorShadowBluetoothGatt}: something the app did to a peripheral. */
  static void gattEvent(BluetoothGatt gatt, String op, Object uuid, byte[] value) {
    BluetoothSim sim = current;
    if (sim == null) return;
    sim.event(
        "gatt",
        new Protocol.Json()
            .put("address", gatt.getDevice().getAddress())
            .put("op", op)
            .put("uuid", uuid == null ? null : uuid.toString())
            .put("value", AdvertisingData.hex(value))
            .object());
  }

  // ---------------------------------------------------------------------------------------------

  /** One simulator cycle: state the app changed, scans it started, requests and connections. */
  void cycle() {
    BluetoothAdapter adapter = adapter();
    if (adapter == null) return;
    int state = adapter.getState();
    if (lastState != -1 && state != lastState) {
      stateChanged(lastState, state);
      event(
          "bluetooth",
          new Protocol.Json().put("state", stateName(state)).put("by", "app").object());
      Settings.Global.putInt(
          app.getContentResolver(),
          Settings.Global.BLUETOOTH_ON,
          state == BluetoothAdapter.STATE_ON ? 1 : 0);
    }
    lastState = state;

    boolean discovering = adapter.isDiscovering();
    if (discovering && !wasDiscovering) {
      discoveryEndsAt = SystemClock.uptimeMillis() + 12_000;
      app.sendBroadcast(new Intent(BluetoothAdapter.ACTION_DISCOVERY_STARTED));
      event("bluetoothDiscovery", new Protocol.Json().put("state", "started").object());
      for (Peripheral p : peripherals.values()) found(p);
    } else if (discovering && SystemClock.uptimeMillis() >= discoveryEndsAt) {
      adapter.cancelDiscovery();
      discovering = false;
    }
    if (!discovering && wasDiscovering) {
      app.sendBroadcast(new Intent(BluetoothAdapter.ACTION_DISCOVERY_FINISHED));
      event("bluetoothDiscovery", new Protocol.Json().put("state", "finished").object());
    }
    wasDiscovering = discovering;

    BluetoothLeScanner scanner = scanner();
    if (scanner != null) {
      ShadowBluetoothLeScanner shadow = Shadow.extract(scanner);
      for (ShadowBluetoothLeScanner.ScanParams params : new ArrayList<>(shadow.getActiveScans())) {
        if (!seenScans.add(params)) continue;
        event("bleScan", describe(params));
        deliverToScans(new ArrayList<>(peripherals.values()), params);
      }
      Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<>());
      active.addAll(shadow.getActiveScans());
      seenScans.retainAll(active);
    }
    for (BluetoothAdapter.LeScanCallback callback :
        new ArrayList<>(shadowAdapter().getLeScanCallbacks())) {
      if (!seenLeCallbacks.add(callback)) continue;
      event("bleScan", new Protocol.Json().put("api", "startLeScan").object());
      for (Peripheral p : peripherals.values()) {
        if (p.ble && p.advertisement != null) {
          callback.onLeScan(p.device, p.spec.optInt("rssi", -60), p.advertisement);
        }
      }
    }

    for (EnableRequest r : new ArrayList<>(enableRequests)) {
      if (!r.announced) {
        r.announced = true;
        event(
            "bluetoothRequest",
            new Protocol.Json()
                .put("requestId", r.id)
                .put("request", "enable")
                .put("state", "waiting")
                .put("policy", policy)
                .object());
      }
      if (!policy.equals("ask")) answer(r, policy);
    }
    connectPending();
  }

  private static JSONObject describe(ShadowBluetoothLeScanner.ScanParams params) {
    JSONArray filters = new JSONArray();
    for (ScanFilter f : params.scanFilters()) filters.put(String.valueOf(f));
    ScanSettings settings = params.scanSettings();
    return new Protocol.Json()
        .put("filters", filters)
        .put("scanMode", settings == null ? null : settings.getScanMode())
        .put("reportDelayMs", settings == null ? null : settings.getReportDelayMillis())
        .put("pendingIntent", params.pendingIntent() != null)
        .object();
  }

  private void event(String name, JSONObject body) {
    protocol.event(name, body);
  }
}
