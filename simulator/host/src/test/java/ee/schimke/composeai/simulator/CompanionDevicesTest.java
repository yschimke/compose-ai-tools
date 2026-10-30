package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CompanionDevicesTest {

  private static final List<String[]> NO_BLUETOOTH = new ArrayList<>();

  @Before
  @After
  public void reset() {
    CompanionDevices.reset();
  }

  @Test
  public void aConnectedWatchIsAssociatedOnceWithTheWatchProfileOnThePhone() {
    CompanionDevices.peerConnected("watch-sim", "Simulated Watch", false);
    CompanionDevices.peerDisconnected("watch-sim");
    CompanionDevices.peerConnected("watch-sim", "Simulated Watch", false);
    List<CompanionDevices.Association> all = CompanionDevices.associations();
    assertEquals(1, all.size());
    assertEquals("Simulated Watch", all.get(0).displayName);
    assertEquals(CompanionDevices.DEVICE_PROFILE_WATCH, all.get(0).profile);
    assertEquals(CompanionDevices.macFor("watch-sim"), all.get(0).address);
  }

  @Test
  public void onTheWatchThePhonesAssociationHasNoProfile() {
    CompanionDevices.peerConnected("phone-sim", "Simulated Phone", true);
    assertNull(CompanionDevices.associations().get(0).profile);
  }

  @Test
  public void macsAreStableLocallyAdministeredAndUnicast() {
    String mac = CompanionDevices.macFor("watch-sim");
    assertEquals(mac, CompanionDevices.macFor("watch-sim"));
    assertTrue(mac.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}"));
    int first = Integer.parseInt(mac.substring(0, 2), 16);
    assertEquals(0x02, first & 0x03);
    assertTrue(!mac.equals(CompanionDevices.macFor("phone-sim")));
  }

  @Test
  public void associateAcceptsTheFirstCandidateByDefault() {
    CompanionDevices.peerConnected("watch-sim", "Simulated Watch", false);
    CompanionDevices.remove(CompanionDevices.associations().get(0).id);
    AtomicReference<CompanionDevices.Candidate> chosen = new AtomicReference<>();
    CompanionDevices.associate(
        CompanionDevices.DEVICE_PROFILE_WATCH, false, NO_BLUETOOTH, chosen::set);
    assertEquals("watch-sim", chosen.get().nodeId);
    assertEquals(1, CompanionDevices.associations().size());
  }

  @Test
  public void aFilteredRequestListsBluetoothDevicesInRange() {
    CompanionDevices.peerConnected("watch-sim", "Simulated Watch", false);
    List<String[]> radios = new ArrayList<>();
    radios.add(new String[] {"C0:FF:EE:00:00:01", "MeshCore radio"});
    AtomicReference<CompanionDevices.Candidate> chosen = new AtomicReference<>();
    CompanionDevices.associate(null, true, radios, chosen::set);
    assertEquals("C0:FF:EE:00:00:01", chosen.get().address);
    assertEquals("MeshCore radio", CompanionDevices.find("c0:ff:ee:00:00:01").displayName);
  }

  @Test
  public void noCandidateFails() {
    AtomicReference<String> answered = new AtomicReference<>();
    CompanionDevices.associate(
        null, true, NO_BLUETOOTH, c -> answered.set(c == null ? "failed" : c.address));
    assertEquals("failed", answered.get());
  }

  @Test
  public void askHoldsTheRequestForTheAgent() throws Exception {
    CompanionDevices.command(new JSONObject().put("policy", "ask"));
    CompanionDevices.peerConnected("watch-sim", "Simulated Watch", false);
    AtomicReference<String> answered = new AtomicReference<>();
    CompanionDevices.associate(
        CompanionDevices.DEVICE_PROFILE_WATCH,
        false,
        NO_BLUETOOTH,
        c -> answered.set(c == null ? "rejected" : c.nodeId));
    assertNull(answered.get());
    JSONObject state = CompanionDevices.describe();
    assertEquals(1, state.getJSONArray("requests").length());
    int id = state.getJSONArray("requests").getJSONObject(0).getInt("requestId");
    CompanionDevices.command(new JSONObject().put("answer", "accept").put("requestId", id));
    assertEquals("watch-sim", answered.get());
    assertEquals(0, CompanionDevices.describe().getJSONArray("requests").length());
    assertThrows(
        IllegalStateException.class,
        () -> CompanionDevices.command(new JSONObject().put("answer", "accept")));
  }

  @Test
  public void theAgentCanRejectOrPickAnUnlistedAddress() throws Exception {
    CompanionDevices.command(new JSONObject().put("policy", "ask"));
    AtomicReference<String> answered = new AtomicReference<>();
    CompanionDevices.associate(
        null, true, NO_BLUETOOTH, c -> answered.set(c == null ? "rejected" : c.address));
    CompanionDevices.command(new JSONObject().put("answer", "reject"));
    assertEquals("rejected", answered.get());
    CompanionDevices.associate(
        null, true, NO_BLUETOOTH, c -> answered.set(c == null ? "rejected" : c.address));
    CompanionDevices.command(
        new JSONObject().put("answer", "accept").put("address", "aa:bb:cc:dd:ee:ff"));
    assertEquals("AA:BB:CC:DD:EE:FF", answered.get());
  }

  @Test
  public void rejectPolicyFailsAtOnce() throws Exception {
    CompanionDevices.command(new JSONObject().put("policy", "reject"));
    CompanionDevices.peerConnected("watch-sim", "Simulated Watch", false);
    AtomicReference<String> answered = new AtomicReference<>("pending");
    CompanionDevices.associate(null, false, NO_BLUETOOTH, c -> answered.set(String.valueOf(c)));
    assertEquals("null", answered.get());
  }
}
