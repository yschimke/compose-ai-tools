package ee.schimke.composeai.simulator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The device's companion associations ({@code CompanionDeviceManager}), as the system keeps them,
 * shared by every {@code CompanionDeviceManager} instance ({@link
 * SimulatorShadowCompanionDeviceManager} syncs Robolectric's per-instance state from here).
 *
 * <ul>
 *   <li><b>Seeded:</b> each data-layer peer ({@code launch --pair}'s other device, or a peer the
 *       agent {@code connect}s in {@code --data-layer agent}) is associated with the app when it
 *       connects, with a stable locally administered MAC derived from its node id and its display
 *       name; on the phone a watch's association has {@code DEVICE_PROFILE_WATCH}. It stays when the
 *       peer disconnects, as a pairing does.
 *   <li><b>{@code associate()}:</b> the system's chooser is answered by {@link #policy}: {@code
 *       accept} (the default) picks the first candidate, {@code reject} fails the request, {@code
 *       ask} reports a {@code companion} event and waits for {@code {"cmd":"companion","answer":
 *       "accept"|"reject"}}. Candidates are the data-layer peers (for a request with no device
 *       filters or the watch profile) and the Bluetooth devices in range in {@link BluetoothSim},
 *       with their addresses.
 * </ul>
 *
 * Nothing here names Play services, so the shadow works whether or not the app has it.
 */
final class CompanionDevices {

  static final String DEVICE_PROFILE_WATCH = "android.app.role.COMPANION_DEVICE_WATCH";

  /** One association, as {@code AssociationInfo} reports it. */
  static final class Association {
    final int id;
    final String address;
    final String displayName;
    final String profile;
    /** The data-layer node it was seeded from, or null. */
    final String nodeId;

    Association(int id, String address, String displayName, String profile, String nodeId) {
      this.id = id;
      this.address = address;
      this.displayName = displayName;
      this.profile = profile;
      this.nodeId = nodeId;
    }

    JSONObject json() {
      return new Protocol.Json()
          .put("id", id)
          .put("address", address)
          .put("displayName", displayName)
          .put("deviceProfile", profile)
          .put("node", nodeId)
          .object();
    }
  }

  /** A device the chooser would list. */
  static final class Candidate {
    final String address;
    final String name;
    final String nodeId;

    Candidate(String address, String name, String nodeId) {
      this.address = address;
      this.name = name;
      this.nodeId = nodeId;
    }

    JSONObject json() {
      return new Protocol.Json()
          .put("address", address)
          .put("name", name)
          .put("node", nodeId)
          .object();
    }
  }

  /** An {@code associate()} the chooser has not answered. */
  static final class Request {
    final int id;
    final String profile;
    final List<Candidate> candidates;
    /** Called once: with the chosen candidate, or null when the user rejected. */
    final Consumer<Candidate> answer;

    Request(int id, String profile, List<Candidate> candidates, Consumer<Candidate> answer) {
      this.id = id;
      this.profile = profile;
      this.candidates = candidates;
      this.answer = answer;
    }
  }

  private static volatile Protocol protocol;
  private static final Map<Integer, Association> associations = new LinkedHashMap<>();
  private static final Map<String, String> peers = new LinkedHashMap<>();
  private static final List<Request> requests = new ArrayList<>();
  private static String policy = "accept";
  private static int nextId = 1;
  private static int nextRequest = 1;
  /** Whether this device is the phone of a pair: its watch associations have the watch profile. */
  private static volatile boolean phone = true;

  static void attach(Protocol channel) {
    protocol = channel;
  }

  /** Called by the data layer: a peer node connected (seeding its association once). */
  static void peerConnected(String nodeId, String name, boolean localIsWatch) {
    phone = !localIsWatch;
    synchronized (CompanionDevices.class) {
      peers.put(nodeId, name);
      for (Association a : associations.values()) if (nodeId.equals(a.nodeId)) return;
      int id = nextId++;
      associations.put(
          id,
          new Association(id, macFor(nodeId), name, localIsWatch ? null : DEVICE_PROFILE_WATCH, nodeId));
    }
    report("associated", null);
  }

  static synchronized void peerDisconnected(String nodeId) {
    peers.remove(nodeId);
  }

  static synchronized List<Association> associations() {
    return new ArrayList<>(associations.values());
  }

  static synchronized boolean remove(int id) {
    return associations.remove(id) != null;
  }

  static synchronized boolean removeAddress(String address) {
    return associations.values().removeIf(a -> a.address.equalsIgnoreCase(address));
  }

  /**
   * The app's {@code associate()}: answered now by policy, or held for the agent. [bluetooth] are
   * the Bluetooth devices in range ({@code {address, name}}).
   */
  static void associate(
      String profile, boolean filtered, List<String[]> bluetooth, Consumer<Candidate> answer) {
    List<Candidate> candidates = new ArrayList<>();
    String chosenPolicy;
    Request request;
    synchronized (CompanionDevices.class) {
      if (!filtered || DEVICE_PROFILE_WATCH.equals(profile)) {
        for (Map.Entry<String, String> peer : peers.entrySet()) {
          candidates.add(new Candidate(macFor(peer.getKey()), peer.getValue(), peer.getKey()));
        }
      }
      if (!DEVICE_PROFILE_WATCH.equals(profile)) {
        for (String[] device : bluetooth) candidates.add(new Candidate(device[0], device[1], null));
      }
      request = new Request(nextRequest++, profile, candidates, answer);
      chosenPolicy = policy;
      if (chosenPolicy.equals("ask")) requests.add(request);
    }
    if (chosenPolicy.equals("ask")) {
      report("chooser", request);
      return;
    }
    resolve(request, chosenPolicy.equals("accept") && !candidates.isEmpty() ? candidates.get(0) : null);
  }

  /** Records [chosen]'s association (or none) and answers the app. */
  private static void resolve(Request request, Candidate chosen) {
    if (chosen != null) {
      synchronized (CompanionDevices.class) {
        // The app associating a device it already has gets that association, not a second one.
        boolean known = false;
        for (Association a : associations.values()) {
          if (a.address.equalsIgnoreCase(chosen.address)) known = true;
        }
        if (!known) {
          int id = nextId++;
          associations.put(
              id, new Association(id, chosen.address, chosen.name, request.profile, chosen.nodeId));
        }
      }
      report("associated", request);
    } else {
      report(request.candidates.isEmpty() ? "noDevice" : "rejected", request);
    }
    request.answer.accept(chosen);
  }

  /** The association [answer] created for [address], for the shadow's callback. */
  static synchronized Association find(String address) {
    for (Association a : associations.values()) {
      if (a.address.equalsIgnoreCase(address)) return a;
    }
    return null;
  }

  /** {@code {"cmd":"companion"}}: the associations; {@code policy}; {@code answer}. */
  static JSONObject command(JSONObject c) throws JSONException {
    if (c.has("policy")) {
      String p = c.getString("policy");
      if (!p.equals("accept") && !p.equals("reject") && !p.equals("ask")) {
        throw new IllegalArgumentException("policy is accept, reject or ask, not " + p);
      }
      synchronized (CompanionDevices.class) {
        policy = p;
      }
    }
    if (c.has("disassociate")) {
      if (!remove(c.getInt("disassociate"))) {
        throw new IllegalArgumentException("no association " + c.getInt("disassociate"));
      }
    }
    if (c.has("answer")) {
      Request target = null;
      synchronized (CompanionDevices.class) {
        for (Request r : requests) {
          if (!c.has("requestId") || c.getInt("requestId") == r.id) {
            target = r;
            break;
          }
        }
        if (target != null) requests.remove(target);
      }
      if (target == null) throw new IllegalStateException("no associate() request is waiting");
      String answer = c.getString("answer");
      Candidate chosen = null;
      if (answer.equals("accept")) {
        String address = c.optString("address", null);
        for (Candidate candidate : target.candidates) {
          if (address == null || candidate.address.equalsIgnoreCase(address)) {
            chosen = candidate;
            break;
          }
        }
        if (chosen == null && address != null) {
          // A device the chooser did not list: as if it were found just now.
          chosen = new Candidate(address.toUpperCase(Locale.ROOT), c.optString("name", address), null);
        }
        if (chosen == null) throw new IllegalArgumentException("the chooser lists no device");
      } else if (!answer.equals("reject")) {
        throw new IllegalArgumentException("answer is accept or reject, not " + answer);
      }
      resolve(target, chosen);
    }
    return describe();
  }

  static synchronized JSONObject describe() {
    JSONArray list = new JSONArray();
    for (Association a : associations.values()) list.put(a.json());
    JSONArray waiting = new JSONArray();
    for (Request r : requests) waiting.put(requestJson(r));
    return new Protocol.Json()
        .put("policy", policy)
        .put("associations", list)
        .put("requests", waiting)
        .object();
  }

  private static JSONObject requestJson(Request r) {
    JSONArray candidates = new JSONArray();
    for (Candidate candidate : r.candidates) candidates.put(candidate.json());
    return new Protocol.Json()
        .put("requestId", r.id)
        .put("deviceProfile", r.profile)
        .put("candidates", candidates)
        .object();
  }

  private static void report(String state, Request request) {
    Protocol channel = protocol;
    System.err.println("companion: " + state + (request == null ? "" : " request " + request.id));
    if (channel == null) return;
    Protocol.Json json = new Protocol.Json().put("state", state);
    if (request != null) {
      JSONObject r = requestJson(request);
      json.put("request", r);
      if (state.equals("chooser")) {
        json.put(
            "hint",
            "answer with {\"cmd\":\"companion\",\"answer\":\"accept\"|\"reject\",\"requestId\":"
                + request.id
                + "}");
      }
    }
    JSONArray list = new JSONArray();
    for (Association a : associations()) list.put(a.json());
    json.put("associations", list);
    channel.event("companion", json.object());
  }

  /**
   * A stable, locally administered unicast MAC for a data-layer node, so a peer keeps its address
   * across runs and never collides with a real vendor's.
   */
  static String macFor(String nodeId) {
    byte[] hash;
    try {
      hash = MessageDigest.getInstance("SHA-256").digest(nodeId.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    StringBuilder mac = new StringBuilder();
    for (int i = 0; i < 6; i++) {
      int b = hash[i] & 0xff;
      if (i == 0) b = (b & 0xfc) | 0x02;
      if (i > 0) mac.append(':');
      mac.append(String.format(Locale.ROOT, "%02X", b));
    }
    return mac.toString();
  }

  /** For tests: forgets everything. */
  static synchronized void reset() {
    associations.clear();
    peers.clear();
    requests.clear();
    policy = "accept";
    nextId = 1;
    nextRequest = 1;
  }

  static boolean isPhone() {
    return phone;
  }

  private CompanionDevices() {}
}
