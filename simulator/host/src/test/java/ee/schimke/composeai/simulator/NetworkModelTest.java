package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import ee.schimke.composeai.simulator.NetworkModel.Mode;
import ee.schimke.composeai.simulator.NetworkModel.Transport;
import java.util.Arrays;
import org.junit.Test;

public class NetworkModelTest {

  @Test
  public void wifiByDefault() {
    NetworkModel m = new NetworkModel();
    assertEquals(Transport.WIFI, m.defaultTransport());
    assertTrue(m.internet());
    assertFalse(m.isMetered(Transport.WIFI));
  }

  @Test
  public void airplaneTakesWifiAndCellularAndRestoresWifi() {
    NetworkModel m = new NetworkModel();
    m.airplane(true);
    assertNull(m.defaultTransport());
    assertFalse(m.internet());
    // Wi-Fi back on in airplane mode, as a user can.
    m.wifiRadio = true;
    assertEquals(Transport.WIFI, m.defaultTransport());
    m.airplane(false);
    assertTrue(m.wifiRadio);

    m.mode(Mode.CELLULAR);
    assertEquals(Transport.CELLULAR, m.defaultTransport());
    assertTrue(m.isMetered(Transport.CELLULAR));
    m.airplane(true);
    assertNull(m.defaultTransport());
    m.airplane(false);
    assertEquals(Transport.CELLULAR, m.defaultTransport());
  }

  @Test
  public void wifiOffBeforeAirplaneStaysOffAfter() {
    NetworkModel m = new NetworkModel();
    m.wifiRadio = false;
    m.airplane(true);
    m.airplane(false);
    assertFalse(m.wifiRadio);
    assertNull(m.defaultTransport());
  }

  @Test
  public void vpnSitsOverWifiAndCaptiveHasNoInternet() {
    NetworkModel m = new NetworkModel();
    m.mode(Mode.VPN);
    assertEquals(Arrays.asList(Transport.WIFI, Transport.VPN), m.networks());
    m.mode(Mode.CAPTIVE);
    assertEquals(Transport.WIFI, m.defaultTransport());
    assertFalse(m.internet());
    m.mode(Mode.ETHERNET);
    m.airplane(true);
    assertEquals(Transport.ETHERNET, m.defaultTransport());
  }

  @Test
  public void mobileDataSwitchAndMeteredOverride() {
    NetworkModel m = new NetworkModel();
    m.mode(Mode.CELLULAR);
    m.data = false;
    assertNull(m.defaultTransport());
    m.mode(Mode.WIFI);
    m.metered = true;
    assertTrue(m.isMetered(Transport.WIFI));
    assertEquals(Mode.CAPTIVE, Mode.of("no-internet"));
    assertEquals(Mode.OFFLINE, Mode.of("offline"));
  }
}
