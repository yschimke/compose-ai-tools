package ee.schimke.composeai.simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The simulated device's radios and what it is connected through, without Android: which networks
 * exist for a given mode, Wi-Fi switch, mobile-data switch and airplane mode, as a phone decides
 * it. {@link NetworkSim} turns the answer into {@code ConnectivityManager} state.
 *
 * <p>Airplane mode turns the Wi-Fi radio (and Bluetooth, which {@link BluetoothSim} owns) off and
 * takes cellular away; either radio can be switched back on while it lasts, and turning it off
 * restores what was on before. Ethernet is wired, and stays.
 */
final class NetworkModel {

  /** What the device is connected through, when its radios let it. */
  enum Mode {
    /** In range of nothing: no network at all. */
    OFFLINE,
    /** A validated, unmetered Wi-Fi network. */
    WIFI,
    /** Mobile data: metered, with a {@code TelephonyManager} network type. */
    CELLULAR,
    ETHERNET,
    /** A VPN over Wi-Fi: the VPN is the default network, Wi-Fi beneath it. */
    VPN,
    /** Wi-Fi behind a captive portal: connected, never validated, no internet. */
    CAPTIVE;

    String id() {
      return name().toLowerCase(Locale.ROOT);
    }

    boolean usesWifi() {
      return this == WIFI || this == VPN || this == CAPTIVE;
    }

    static Mode of(String id) {
      try {
        return valueOf(id.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
      } catch (IllegalArgumentException e) {
        if (id.equalsIgnoreCase("no-internet") || id.equalsIgnoreCase("noInternet")) return CAPTIVE;
        if (id.equalsIgnoreCase("mobile") || id.equalsIgnoreCase("data")) return CELLULAR;
        if (id.equalsIgnoreCase("none")) return OFFLINE;
        throw new IllegalArgumentException(
            "network mode is offline, wifi, cellular, ethernet, vpn or captive, not " + id);
      }
    }
  }

  /** One network's transport, in the order a device ranks them for the default. */
  enum Transport {
    CELLULAR,
    WIFI,
    ETHERNET,
    VPN
  }

  Mode mode = Mode.WIFI;
  boolean airplane;
  boolean wifiRadio = true;

  /** Mobile data, the user's switch. */
  boolean data = true;

  /** Whether the Wi-Fi radio is associated with an access point. */
  boolean wifiConnected = true;

  /** Overrides each network's own metering; null leaves it (cellular metered, the rest not). */
  Boolean metered;

  private boolean wifiBeforeAirplane = true;

  /** Switches to [mode]; Wi-Fi associates when the mode is over Wi-Fi, and drops otherwise. */
  void mode(Mode mode) {
    this.mode = mode;
    wifiConnected = mode.usesWifi();
    if (mode.usesWifi()) wifiRadio = true;
    if (mode == Mode.CELLULAR) data = true;
  }

  /** Airplane mode on saves and switches off the Wi-Fi radio; off restores it. */
  void airplane(boolean on) {
    if (on == airplane) return;
    airplane = on;
    if (on) {
      wifiBeforeAirplane = wifiRadio;
      wifiRadio = false;
    } else {
      wifiRadio = wifiRadio || wifiBeforeAirplane;
    }
  }

  /** The networks up, default last. */
  List<Transport> networks() {
    List<Transport> up = new ArrayList<>();
    switch (mode) {
      case WIFI:
      case CAPTIVE:
        if (wifiRadio && wifiConnected) up.add(Transport.WIFI);
        break;
      case VPN:
        if (wifiRadio && wifiConnected) {
          up.add(Transport.WIFI);
          up.add(Transport.VPN);
        }
        break;
      case CELLULAR:
        if (!airplane && data) up.add(Transport.CELLULAR);
        break;
      case ETHERNET:
        up.add(Transport.ETHERNET);
        break;
      case OFFLINE:
        break;
    }
    return up;
  }

  Transport defaultTransport() {
    List<Transport> up = networks();
    return up.isEmpty() ? null : up.get(up.size() - 1);
  }

  /** Whether the default network reaches the internet (and so passes validation). */
  boolean internet() {
    return defaultTransport() != null && mode != Mode.CAPTIVE;
  }

  /** {@code BluetoothAdapter.STATE_OFF} / {@code STATE_ON}. */
  static final int BLUETOOTH_OFF = 10;

  static final int BLUETOOTH_ON = 12;

  /**
   * The Bluetooth adapter's state at startup: off in airplane mode ({@code launch --network
   * airplane}), else on. Robolectric's adapter starts in no state at all (0), which BLE libraries
   * reject.
   */
  static int bluetoothAtStartup(int robolectricState, boolean airplane) {
    if (airplane) return BLUETOOTH_OFF;
    return robolectricState == 0 ? BLUETOOTH_ON : robolectricState;
  }

  boolean isMetered(Transport transport) {
    if (metered != null) return metered;
    return transport == Transport.CELLULAR;
  }
}
