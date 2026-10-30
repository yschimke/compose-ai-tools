package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CompanionDevicesTest {

  private static final List<CompanionDevices.Device> NO_BLUETOOTH = new ArrayList<>();
  private static final List<CompanionDevices.Filter> ANY = new ArrayList<>();

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
        CompanionDevices.DEVICE_PROFILE_WATCH, ANY, NO_BLUETOOTH, chosen::set);
    assertEquals("watch-sim", chosen.get().nodeId);
    assertEquals(1, CompanionDevices.associations().size());
  }

  @Test
  public void associatingAKnownDeviceAgainKeepsOneAssociation() {
    CompanionDevices.peerConnected("watch-sim", "Simulated Watch", false);
    AtomicReference<CompanionDevices.Candidate> chosen = new AtomicReference<>();
    CompanionDevices.associate(
        CompanionDevices.DEVICE_PROFILE_WATCH, ANY, NO_BLUETOOTH, chosen::set);
    assertEquals("watch-sim", chosen.get().nodeId);
    assertEquals(1, CompanionDevices.associations().size());
    assertEquals(1, CompanionDevices.find(chosen.get().address).id);
  }

  private static final String HEART_RATE = "0000180d-0000-1000-8000-00805f9b34fb";

  private static CompanionDevices.Device ble(String address, String name, String... uuids) {
    return new CompanionDevices.Device(address, name, true, List.of(uuids));
  }

  private static CompanionDevices.Device classic(String address, String name, String... uuids) {
    return new CompanionDevices.Device(address, name, false, List.of(uuids));
  }

  private static CompanionDevices.Filter bleName(String pattern) {
    return CompanionDevices.Filter.ble(null, Pattern.compile(pattern), null, null);
  }

  private static String chosen(List<CompanionDevices.Filter> filters, List<CompanionDevices.Device> inRange) {
    AtomicReference<String> answered = new AtomicReference<>();
    CompanionDevices.associate(
        null, filters, inRange, c -> answered.set(c == null ? "failed" : c.address));
    return answered.get();
  }

  @Test
  public void aFilteredRequestListsBluetoothDevicesInRange() {
    CompanionDevices.peerConnected("watch-sim", "Simulated Watch", false);
    assertEquals(
        "C0:FF:EE:00:00:01",
        chosen(List.of(bleName("MeshCore")), List.of(ble("C0:FF:EE:00:00:01", "MeshCore radio"))));
    assertEquals("MeshCore radio", CompanionDevices.find("c0:ff:ee:00:00:01").displayName);
  }

  /** The chooser lists only devices a filter matches: an unrelated one is never associated. */
  @Test
  public void bluetoothDevicesThatMatchNoFilterAreNotCandidates() {
    List<CompanionDevices.Device> inRange =
        List.of(
            ble("AA:AA:AA:AA:AA:01", "Kitchen speaker"),
            ble("AA:AA:AA:AA:AA:02", "Chest strap", HEART_RATE),
            ble("AA:AA:AA:AA:AA:03", "MeshCore-1234"));
    // By service UUID.
    assertEquals(
        "AA:AA:AA:AA:AA:02",
        chosen(
            List.of(CompanionDevices.Filter.ble(null, null, UUID.fromString(HEART_RATE), null)),
            inRange));
    // By address.
    assertEquals(
        "AA:AA:AA:AA:AA:03",
        chosen(List.of(CompanionDevices.Filter.ble("aa:aa:aa:aa:aa:03", null, null, null)), inRange));
    // Any of several filters; the first device in range that one matches.
    assertEquals(
        "AA:AA:AA:AA:AA:02", chosen(List.of(bleName("^MeshCore-"), bleName("strap")), inRange));
    // Nothing matches: the request fails rather than taking the speaker.
    assertEquals("failed", chosen(List.of(bleName("Watch")), inRange));
  }

  /** {@code bluetooth advertise} takes {@code 180D}; a filter names the full UUID. */
  @Test
  public void shortAdvertisedUuidsMatchTheirFullForm() {
    assertEquals(
        "AA:AA:AA:AA:AA:02",
        chosen(
            List.of(CompanionDevices.Filter.ble(null, null, UUID.fromString(HEART_RATE), null)),
            List.of(ble("AA:AA:AA:AA:AA:01", "Other", "180F"), ble("AA:AA:AA:AA:AA:02", "Strap", "180D"))));
  }

  /** {@code ScanFilter.setServiceUuid(uuid, mask)}: only the mask's bits are compared. */
  @Test
  public void serviceUuidMasksAreHonoured() {
    UUID wanted = UUID.fromString("12345678-0000-1000-8000-00805f9b34fb");
    UUID topBits = UUID.fromString("ffff0000-0000-0000-0000-000000000000");
    List<CompanionDevices.Device> inRange =
        List.of(
            ble("AA:AA:AA:AA:AA:01", "Other", "abcd0000-0000-1000-8000-00805f9b34fb"),
            ble("AA:AA:AA:AA:AA:02", "Ours", "1234ffff-0000-1000-8000-00805f9b34fb"));
    assertEquals(
        "AA:AA:AA:AA:AA:02",
        chosen(List.of(CompanionDevices.Filter.ble(null, null, wanted, topBits)), inRange));
    // Without the mask the UUID must be equal, and neither is.
    assertEquals(
        "failed", chosen(List.of(CompanionDevices.Filter.ble(null, null, wanted, null)), inRange));
  }

  /** A BLE filter lists only BLE peripherals, a classic one only classic devices. */
  @Test
  public void filtersApplyOnlyToTheirMedium() {
    List<CompanionDevices.Device> inRange =
        List.of(classic("AA:AA:AA:AA:AA:01", "Car kit"), ble("AA:AA:AA:AA:AA:02", "Car tag"));
    assertEquals("AA:AA:AA:AA:AA:02", chosen(List.of(bleName("Car")), inRange));
    assertEquals(
        "AA:AA:AA:AA:AA:01",
        chosen(
            List.of(CompanionDevices.Filter.classic(null, Pattern.compile("Car"), List.of())),
            inRange));
  }

  /**
   * A filter the simulator cannot evaluate (a Wi-Fi one; a BLE one with raw-data, service-data or
   * manufacturer-data predicates, which the shadow reads as {@code NONE}) matches nothing.
   */
  @Test
  public void aFilterTheSimulatorCannotEvaluateMatchesNoBluetoothDevice() {
    assertEquals(
        "failed",
        chosen(
            List.of(CompanionDevices.Filter.NONE),
            List.of(ble("AA:AA:AA:AA:AA:01", "Kitchen speaker"), classic("AA:AA:AA:AA:AA:02", "Car"))));
  }

  @Test
  public void anUnfilteredRequestListsEveryDeviceInRange() {
    assertEquals(
        "AA:AA:AA:AA:AA:01",
        chosen(new ArrayList<>(), List.of(classic("AA:AA:AA:AA:AA:01", "Kitchen speaker"))));
  }

  @Test
  public void noCandidateFails() {
    AtomicReference<String> answered = new AtomicReference<>();
    CompanionDevices.associate(
        null, List.of(CompanionDevices.Filter.NONE), NO_BLUETOOTH, c -> answered.set(c == null ? "failed" : c.address));
    assertEquals("failed", answered.get());
  }

  @Test
  public void askHoldsTheRequestForTheAgent() throws Exception {
    CompanionDevices.command(new JSONObject().put("policy", "ask"));
    CompanionDevices.peerConnected("watch-sim", "Simulated Watch", false);
    AtomicReference<String> answered = new AtomicReference<>();
    CompanionDevices.associate(
        CompanionDevices.DEVICE_PROFILE_WATCH,
        ANY,
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
        null, List.of(CompanionDevices.Filter.NONE), NO_BLUETOOTH, c -> answered.set(c == null ? "rejected" : c.address));
    CompanionDevices.command(new JSONObject().put("answer", "reject"));
    assertEquals("rejected", answered.get());
    CompanionDevices.associate(
        null, List.of(CompanionDevices.Filter.NONE), NO_BLUETOOTH, c -> answered.set(c == null ? "rejected" : c.address));
    CompanionDevices.command(
        new JSONObject().put("answer", "accept").put("address", "aa:bb:cc:dd:ee:ff"));
    assertEquals("AA:BB:CC:DD:EE:FF", answered.get());
  }

  @Test
  public void rejectPolicyFailsAtOnce() throws Exception {
    CompanionDevices.command(new JSONObject().put("policy", "reject"));
    CompanionDevices.peerConnected("watch-sim", "Simulated Watch", false);
    AtomicReference<String> answered = new AtomicReference<>("pending");
    CompanionDevices.associate(null, ANY, NO_BLUETOOTH, c -> answered.set(String.valueOf(c)));
    assertEquals("null", answered.get());
  }
}
