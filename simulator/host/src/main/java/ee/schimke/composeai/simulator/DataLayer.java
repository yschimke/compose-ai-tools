package ee.schimke.composeai.simulator;

import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ResultReceiver;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.common.data.DataHolder;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.WearableListenerService;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.RuntimeEnvironment;

/**
 * This simulator's node of a simulated Wearable Data Layer: what Play services' wearable service
 * does on a device, for one app, with the other simulator of the pair as the only peer.
 *
 * <p>The app's {@code Wearable.getXClient} calls reach here through {@link ShadowWearable}, and the
 * clients ({@link DataLayerClients}) keep their state here: this node's data items and the peers',
 * the listeners the app added, its local capabilities and the ones each peer announced. Frames go
 * to the peer over a {@link DataLayerLink} to the CLI's hub:
 *
 * <ul>
 *   <li>{@code hello} — a node, its display name, package and capabilities; answered once, and
 *       followed by this node's data items, as a device syncs on connect;
 *   <li>{@code message} — a {@code MessageClient.sendMessage}, or with a {@code requestId} a {@code
 *       sendRequest}, answered by a {@code response};
 *   <li>{@code data} — a data item put or deleted, with the bytes of its assets;
 *   <li>{@code capabilities} — the node's capability set after an add or remove;
 *   <li>{@code activity} — a {@code RemoteActivityHelper.startRemoteActivity} intent for the peer
 *       to start, answered by an {@code activityResult};
 *   <li>{@code bye} — the hub's word that a node went away.
 * </ul>
 *
 * Delivery to the app runs on its main looper: the live listeners first, then each manifest
 * {@code WearableListenerService} whose intent filter matches the event's {@code
 * wear://<node>/<path>} URI, created through {@link Navigator} as Play services would bind it.
 * Each delivery is reported on the control channel as a {@code datalayer} event.
 *
 * <p>Enabled by {@link #HUB_PROPERTY}, which the CLI sets for both simulators of {@code launch
 * --pair}; without it the app keeps Robolectric's behavior (no Play services).
 */
final class DataLayer {

  static final String HUB_PROPERTY = DataLayerLink.HUB_PROPERTY;
  static final String NODE_PROPERTY = DataLayerLink.NODE_PROPERTY;
  static final String NAME_PROPERTY = DataLayerLink.NAME_PROPERTY;

  static final String ACTION_MESSAGE_RECEIVED = "com.google.android.gms.wearable.MESSAGE_RECEIVED";
  static final String ACTION_REQUEST_RECEIVED = "com.google.android.gms.wearable.REQUEST_RECEIVED";
  static final String ACTION_DATA_CHANGED = "com.google.android.gms.wearable.DATA_CHANGED";
  static final String ACTION_CAPABILITY_CHANGED =
      "com.google.android.gms.wearable.CAPABILITY_CHANGED";

  static final int TYPE_CHANGED = 1;
  static final int TYPE_DELETED = 2;

  /** {@code WearableStatusCodes.TARGET_NODE_NOT_CONNECTED}. */
  static final int TARGET_NODE_NOT_CONNECTED = 4000;
  /** {@code WearableStatusCodes.ASSET_UNAVAILABLE}. */
  static final int ASSET_UNAVAILABLE = 4005;
  /** {@code CommonStatusCodes.INTERNAL_ERROR}. */
  static final int INTERNAL_ERROR = 8;
  /** {@code CommonStatusCodes.TIMEOUT}. */
  static final int TIMEOUT = 15;

  private static volatile DataLayer instance;
  private static volatile Protocol protocol;

  static boolean enabled() {
    return DataLayerLink.enabled();
  }

  /** The control channel, once {@link Session} exists; null before. */
  static Protocol protocol() {
    return protocol;
  }

  /**
   * Starts the node before {@code Application.onCreate()}, so the peer sees it at once. Callers
   * check {@link DataLayerLink#enabled()} first: loading this class needs the app's Play services.
   */
  static void start(Application app) {
    pairedDeviceType(app);
    get(app);
  }

  /** {@code PhoneTypeHelper}'s {@code paired_device_os_type} for an Android phone. */
  static final int PAIRED_DEVICE_ANDROID = 1;

  /**
   * A watch paired with an Android phone says so in {@code Settings.Global}, which is where {@code
   * PhoneTypeHelper.getPhoneDeviceType} reads it ({@code DEVICE_TYPE_ANDROID}).
   */
  private static void pairedDeviceType(Application app) {
    if (!isWatch(app)) return;
    android.provider.Settings.Global.putInt(
        app.getContentResolver(), "paired_device_os_type", PAIRED_DEVICE_ANDROID);
  }

  static boolean isWatch(Application app) {
    int type =
        app.getResources().getConfiguration().uiMode
            & android.content.res.Configuration.UI_MODE_TYPE_MASK;
    return type == android.content.res.Configuration.UI_MODE_TYPE_WATCH;
  }

  /** The node, connecting it on first use. */
  static DataLayer get() {
    DataLayer node = instance;
    return node != null ? node : get(RuntimeEnvironment.getApplication());
  }

  private static DataLayer get(Application app) {
    synchronized (DataLayer.class) {
      if (instance == null) {
        DataLayer created = new DataLayer(app);
        instance = created;
        created.connect();
      }
      return instance;
    }
  }

  /** The control channel's events, from {@link Session} once it exists. */
  static void attach(Protocol channel) {
    protocol = channel;
    DataLayer node = instance;
    if (node != null) node.flushUnsent();
  }

  private void flushUnsent() {
    List<JSONObject> frames;
    synchronized (unsent) {
      frames = new ArrayList<>(unsent);
      unsent.clear();
    }
    for (JSONObject frame : frames) showFrame(frame);
  }

  /** The agent's {@code dataLayer} command with an {@code action}; agent mode only. */
  static JSONObject agentCurrent(JSONObject command) throws JSONException {
    DataLayer node = get();
    if (!node.agent) {
      throw new IllegalStateException(
          "dataLayer " + command.optString("action") + " needs launch --data-layer agent");
    }
    return new DataLayerAgent(node).run(command);
  }

  /** For {@link Session}'s {@code dataLayer} command: the node's state, or null when unpaired. */
  static JSONObject describeCurrent() {
    DataLayer node = instance;
    return node == null ? null : node.describe();
  }

  // ---------------------------------------------------------------------------------------------

  /** A data item as this node holds it: its own, or a peer's synced copy. */
  static final class Item {
    final String uri;
    final byte[] data;
    /** Asset key → asset digest; the bytes are in {@link #assets}. */
    final Map<String, String> assets;

    Item(String uri, byte[] data, Map<String, String> assets) {
      this.uri = uri;
      this.data = data;
      this.assets = assets;
    }
  }

  static final class Peer {
    final String id;
    final String name;
    final String packageName;
    final boolean nearby;
    final Set<String> capabilities = new LinkedHashSet<>();

    Peer(String id, String name, String packageName, boolean nearby) {
      this.id = id;
      this.name = name;
      this.packageName = packageName;
      this.nearby = nearby;
    }

    Node node() {
      return new DataLayerClients.SimNode(id, name, nearby);
    }
  }

  /** A listener the app added, with the URI filter it gave (null for every event). */
  static final class Registration<L> {
    final L listener;
    final Uri uri;
    final int filter;

    Registration(L listener, Uri uri, int filter) {
      this.listener = listener;
      this.uri = uri;
      this.filter = filter;
    }

    boolean accepts(Uri event) {
      return uri == null || matches(uri, filter, event);
    }
  }

  private final Application app;
  private final Handler main = new Handler(Looper.getMainLooper());
  /** Where manifest services' callbacks run; started on first use. */
  private Handler listenerThread;
  final String localId;
  final String localName;
  private DataLayerLink link;
  /**
   * {@code launch --data-layer agent}: no hub; every frame this node sends is a control-channel
   * event, and {@link DataLayerAgent} turns the agent's commands into the peers' frames.
   */
  final boolean agent = DataLayerLink.agent();
  /** Frames sent before the control channel existed (startup), shown once it does. */
  private final List<JSONObject> unsent = new ArrayList<>();
  /** How long a {@code sendRequest} waits for its answer. */
  volatile long requestTimeoutMs = Long.getLong(DataLayerLink.TIMEOUT_PROPERTY, 30_000L);
  private final java.util.concurrent.ScheduledExecutorService timer =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "datalayer-timeouts");
            t.setDaemon(true);
            return t;
          });

  private final Map<String, Peer> peers = new LinkedHashMap<>();
  private final Set<String> localCapabilities = new LinkedHashSet<>();
  private final Map<String, Item> items = new LinkedHashMap<>();
  private final Map<String, byte[]> assets = new ConcurrentHashMap<>();
  private final Map<Integer, TaskCompletionSource<byte[]>> pending = new ConcurrentHashMap<>();
  /** The app's {@code ResultReceiver} for each remote launch awaiting the peer's answer. */
  private final Map<Integer, ResultReceiver> remoteLaunches = new ConcurrentHashMap<>();
  /** A watch's {@code RemoteAuthClient} callbacks awaiting the phone's answer. */
  private final Map<Integer, Object> authCallbacks = new ConcurrentHashMap<>();
  /** On the phone: the watch's OAuth requests not answered yet, oldest first. */
  private final List<JSONObject> authRequests = new CopyOnWriteArrayList<>();
  private final AtomicInteger requestIds = new AtomicInteger();

  final List<Registration<DataClient.OnDataChangedListener>> dataListeners =
      new CopyOnWriteArrayList<>();
  final List<Registration<MessageClient.OnMessageReceivedListener>> messageListeners =
      new CopyOnWriteArrayList<>();
  final List<Registration<CapabilityClient.OnCapabilityChangedListener>> capabilityListeners =
      new CopyOnWriteArrayList<>();
  /** The capability name a capability listener was added for, by listener identity. */
  final Map<CapabilityClient.OnCapabilityChangedListener, String> capabilityNames =
      new ConcurrentHashMap<>();
  final Map<MessageClient.RpcService, String> rpcServices = new ConcurrentHashMap<>();

  private DataLayer(Application app) {
    this.app = app;
    this.localId = System.getProperty(NODE_PROPERTY, "sim-" + app.getPackageName());
    this.localName = System.getProperty(NAME_PROPERTY, localId);
    localCapabilities.addAll(manifestCapabilities(app));
    channels = new DataLayerChannels(this);
  }

  /** The app's {@code android_wear_capabilities}, which Play services reads from its resources. */
  private static List<String> manifestCapabilities(Application app) {
    List<String> result = new ArrayList<>();
    Resources resources = app.getResources();
    int id =
        resources.getIdentifier("android_wear_capabilities", "array", app.getPackageName());
    if (id == 0) return result;
    try {
      for (String capability : resources.getStringArray(id)) result.add(capability);
    } catch (Resources.NotFoundException e) {
      // Declared for another configuration only.
    }
    return result;
  }

  private void connect() {
    if (!agent) {
      int port = Integer.parseInt(System.getProperty(HUB_PROPERTY));
      try {
        link = DataLayerLink.connect(port, this::receive, this::hubClosed);
      } catch (java.io.IOException e) {
        System.err.println("datalayer: cannot reach the hub on port " + port + ": " + e);
        return;
      }
    }
    send(hello(false));
    listenForRemoteIntents();
    if (isWatch(app)) {
      try {
        WearCompanion.install(app, this);
      } catch (LinkageError e) {
        // The app has no wear-phone-interactions.
      }
    }
    log("node " + localId + " (" + app.getPackageName() + ") capabilities " + localCapabilities);
  }

  // ---------------------------------------------------------------------------------------------
  // Clients

  /** Completes every task the clients hand out on the main looper; see {@link DeferredTasks}. */
  final DeferredTasks tasks = new DeferredTasks(main::post);

  /** A task that completes with [value] on the main looper's next turn ({@link DeferredTasks}). */
  <T> Task<T> later(T value) {
    return tasks.result(value);
  }

  /** As {@link #later(Object)}, failing with [error]. */
  <T> Task<T> laterFailure(Exception error) {
    return tasks.failure(error);
  }

  Object dataClient(Context context) {
    return new DataLayerClients.Data(context, this);
  }

  Object messageClient(Context context) {
    return new DataLayerClients.Messages(context, this);
  }

  Object nodeClient(Context context) {
    return new DataLayerClients.Nodes(context, this);
  }

  Object capabilityClient(Context context) {
    return new DataLayerClients.Capabilities(context, this);
  }

  Object channelClient(Context context) {
    return new DataLayerClients.Channels(context, this);
  }

  /** The app's {@code ChannelClient} channels; see {@link DataLayerChannels}. */
  final DataLayerChannels channels;

  Application app() {
    return app;
  }

  // ---------------------------------------------------------------------------------------------
  // Nodes and capabilities

  Node localNode() {
    return new DataLayerClients.SimNode(localId, localName, true);
  }

  synchronized List<Node> connectedNodes() {
    List<Node> nodes = new ArrayList<>();
    for (Peer peer : peers.values()) nodes.add(peer.node());
    return nodes;
  }

  /**
   * The package of the app that manages [nodeId] on this side: the Wear OS app, as on a device;
   * null for a node that is not connected. It is not the peer's own package: {@code
   * RemoteActivityHelper} addresses its broadcast to it, and Horologist's {@code startCompanion}
   * launches it.
   */
  synchronized String companionPackage(String nodeId) {
    return peers.containsKey(nodeId) ? RemoteIntents.COMPANION_PACKAGE : null;
  }

  private synchronized List<String> peerIds() {
    return new ArrayList<>(peers.keySet());
  }

  /**
   * The nodes that have [name]: the peers that announced it. Play services also lists the local
   * node when it has the capability itself; apps ask for the other side's, so it is left out.
   */
  synchronized CapabilityInfo capability(String name) {
    Set<Node> nodes = new LinkedHashSet<>();
    for (Peer peer : peers.values()) {
      if (peer.capabilities.contains(name)) {
        nodes.add(peer.node());
      }
    }
    return new DataLayerClients.SimCapability(name, nodes);
  }

  synchronized Map<String, CapabilityInfo> allCapabilities() {
    Set<String> names = new LinkedHashSet<>();
    for (Peer peer : peers.values()) names.addAll(peer.capabilities);
    Map<String, CapabilityInfo> result = new LinkedHashMap<>();
    for (String name : names) result.put(name, capability(name));
    return result;
  }

  void setLocalCapability(String name, boolean present) {
    synchronized (this) {
      boolean changed = present ? localCapabilities.add(name) : localCapabilities.remove(name);
      if (!changed) return;
    }
    send(frame("capabilities").put("capabilities", capabilitiesJson()));
  }

  private synchronized JSONArray capabilitiesJson() {
    return new JSONArray(localCapabilities);
  }

  // ---------------------------------------------------------------------------------------------
  // Messages

  Task<Integer> sendMessage(String nodeId, String path, byte[] data) {
    if (!isPeer(nodeId)) return laterFailure(notConnected(nodeId));
    int id = requestIds.incrementAndGet();
    send(frame("message").put("to", nodeId).put("path", path).put("data", encode(data)));
    event("message", "out", nodeId, path, data, null);
    return later(id);
  }

  Task<byte[]> sendRequest(String nodeId, String path, byte[] data) {
    if (!isPeer(nodeId)) return laterFailure(notConnected(nodeId));
    int id = requestIds.incrementAndGet();
    TaskCompletionSource<byte[]> response = new TaskCompletionSource<>();
    pending.put(id, response);
    send(
        frame("message")
            .put("to", nodeId)
            .put("path", path)
            .put("data", encode(data))
            .put("requestId", id));
    event("request", "out", nodeId, path, data, null);
    long timeout = requestTimeoutMs;
    if (timeout > 0) {
      timer.schedule(
          () -> {
            TaskCompletionSource<byte[]> waiting = pending.remove(id);
            if (waiting == null) return;
            tasks.fail(
                waiting,
                new ApiException(new Status(TIMEOUT, "no answer to " + path + " in " + timeout + " ms")));
            event("response", "in", nodeId, path, null, null, "timeout");
          },
          timeout,
          java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    return response.getTask();
  }

  synchronized boolean isPeer(String nodeId) {
    return peers.containsKey(nodeId);
  }

  static ApiException notConnected(String nodeId) {
    return new ApiException(
        new Status(TARGET_NODE_NOT_CONNECTED, "TARGET_NODE_NOT_CONNECTED: " + nodeId));
  }

  // ---------------------------------------------------------------------------------------------
  // Data items

  /** Puts [request] as this node's item, syncs it to the peers, and tells local listeners. */
  Item put(PutDataRequest request) {
    Uri requested = request.getUri();
    String uri = wearUri(localId, requested.getPath());
    Map<String, String> keys = new LinkedHashMap<>();
    JSONObject assetBytes = new JSONObject();
    Map<String, Asset> requestAssets = request.getAssets();
    if (requestAssets != null) {
      for (Map.Entry<String, Asset> e : requestAssets.entrySet()) {
        byte[] bytes = assetBytes(e.getValue());
        String digest = bytes != null ? digest(bytes) : e.getValue().getDigest();
        if (bytes != null) assets.put(digest, bytes);
        if (digest == null) continue;
        keys.put(e.getKey(), digest);
        byte[] known = assets.get(digest);
        if (known != null) putUnchecked(assetBytes, digest, encode(known));
      }
    }
    Item item = new Item(uri, request.getData(), keys);
    synchronized (this) {
      items.put(uri, item);
    }
    send(
        frame("data")
            .put("uri", uri)
            .put("data", encode(item.data))
            .put("assets", new JSONObject(keys))
            .put("assetBytes", assetBytes));
    event("data", "out", null, requested.getPath(), item.data, null);
    List<Item> changed = new ArrayList<>();
    changed.add(item);
    deliverData(changed, TYPE_CHANGED, false);
    return item;
  }

  synchronized List<Item> items(Uri uri, int filter) {
    List<Item> result = new ArrayList<>();
    for (Item item : items.values()) {
      if (uri == null || matches(uri, filter, Uri.parse(item.uri))) result.add(item);
    }
    return result;
  }

  /** Deletes the items [uri] matches, here and on the peers; returns how many. */
  int delete(Uri uri, int filter) {
    List<Item> removed = new ArrayList<>();
    synchronized (this) {
      for (Iterator<Item> it = items.values().iterator(); it.hasNext(); ) {
        Item item = it.next();
        if (matches(uri, filter, Uri.parse(item.uri))) {
          removed.add(item);
          it.remove();
        }
      }
    }
    for (Item item : removed) {
      send(frame("data").put("uri", item.uri).put("deleted", true));
      event("data", "out", null, Uri.parse(item.uri).getPath(), null, null, "deleted");
    }
    if (!removed.isEmpty()) deliverData(removed, TYPE_DELETED, false);
    return removed.size();
  }

  byte[] asset(String digest) {
    return digest == null ? null : assets.get(digest);
  }

  static ApiException assetUnavailable(String digest) {
    return new ApiException(new Status(ASSET_UNAVAILABLE, "ASSET_UNAVAILABLE: " + digest));
  }

  /** The bytes of an {@code Asset.createFromBytes}, which the class keeps in a private field. */
  private static byte[] assetBytes(Asset asset) {
    for (Class<?> type = asset.getClass(); type != null; type = type.getSuperclass()) {
      for (Field field : type.getDeclaredFields()) {
        if (field.getType() != byte[].class) continue;
        try {
          field.setAccessible(true);
          byte[] bytes = (byte[]) field.get(asset);
          if (bytes != null) return bytes;
        } catch (ReflectiveOperationException | RuntimeException e) {
          return null;
        }
      }
    }
    return null;
  }

  /** The rows Play services' {@code DataItemBuffer} / {@code DataEventBuffer} read. */
  static DataHolder holder(List<Item> items, int type) {
    DataHolder.Builder builder =
        DataHolder.builder(new String[] {"path", "data", "event_type", "asset_key", "asset_id"});
    for (Item item : items) {
      if (item.assets.isEmpty()) {
        builder.withRow(row(item, type, null, null));
      } else {
        for (Map.Entry<String, String> asset : item.assets.entrySet()) {
          builder.withRow(row(item, type, asset.getKey(), asset.getValue()));
        }
      }
    }
    return builder.build(0);
  }

  private static ContentValues row(Item item, int type, String assetKey, String assetId) {
    ContentValues values = new ContentValues();
    values.put("path", item.uri);
    values.put("data", item.data);
    values.put("event_type", type);
    values.put("asset_key", assetKey);
    values.put("asset_id", assetId);
    return values;
  }

  static DataItemBuffer itemBuffer(List<Item> items) {
    return new DataItemBuffer(holder(items, TYPE_CHANGED));
  }

  // ---------------------------------------------------------------------------------------------
  // The peer's frames

  private void receive(String text) {
    JSONObject frame;
    try {
      frame = new JSONObject(text);
    } catch (JSONException e) {
      System.err.println("datalayer: unreadable frame " + e);
      return;
    }
    String from = frame.optString("from");
    String to = frame.optString("to", null);
    if (from.equals(localId) || (to != null && !to.equals(localId))) return;
    if (frame.optString("type").startsWith("channel") && channels.receive(frame)) return;
    try {
      switch (frame.optString("type")) {
        case "hello":
          onHello(frame);
          break;
        case "bye":
          onBye(from);
          break;
        case "capabilities":
          onCapabilities(from, frame.optJSONArray("capabilities"));
          break;
        case "message":
          onMessage(frame);
          break;
        case "response":
          onResponse(frame);
          break;
        case "data":
          onData(frame);
          break;
        case "activity":
          onRemoteActivity(frame);
          break;
        case "activityResult":
          onRemoteActivityResult(frame);
          break;
        case "oauth":
          onAuthRequest(frame);
          break;
        case "oauthResult":
          onAuthResult(frame);
          break;
        default:
          System.err.println("datalayer: unknown frame " + frame.optString("type"));
      }
    } catch (RuntimeException | JSONException e) {
      System.err.println("datalayer: frame failed: " + e);
      e.printStackTrace();
    }
  }

  private void onHello(JSONObject frame) throws JSONException {
    String id = frame.getString("from");
    boolean known;
    Peer peer =
        new Peer(
            id,
            frame.optString("name", id),
            frame.optString("package", null),
            frame.optBoolean("nearby", true));
    JSONArray capabilities = frame.optJSONArray("capabilities");
    if (capabilities != null) {
      for (int i = 0; i < capabilities.length(); i++) {
        peer.capabilities.add(capabilities.getString(i));
      }
    }
    List<Item> own = new ArrayList<>();
    synchronized (this) {
      known = peers.containsKey(id);
      peers.put(id, peer);
      String prefix = wearUri(localId, "/");
      for (Item item : items.values()) if (item.uri.startsWith(prefix)) own.add(item);
    }
    if (!frame.optBoolean("reply")) send(hello(true).put("to", id));
    // A device syncs its data items to a node that connects.
    if (!known) {
      for (Item item : own) {
        JSONObject assetBytes = new JSONObject();
        for (String digest : item.assets.values()) {
          byte[] bytes = assets.get(digest);
          if (bytes != null) putUnchecked(assetBytes, digest, encode(bytes));
        }
        send(
            frame("data")
                .put("to", id)
                .put("uri", item.uri)
                .put("data", encode(item.data))
                .put("assets", new JSONObject(item.assets))
                .put("assetBytes", assetBytes));
      }
    }
    log("peer " + id + " (" + peer.packageName + ") connected, capabilities " + peer.capabilities);
    CompanionDevices.peerConnected(id, peer.name, isWatch(app));
    PeerPresence.peerConnected(id);
    reportPeer(peer, "connected");
    if (!known) {
      notifyCapabilities(peer.capabilities);
      notifyPeer(peer, true);
    }
  }

  private void onBye(String id) {
    Peer peer;
    synchronized (this) {
      peer = peers.remove(id);
    }
    if (peer == null) return;
    log("peer " + id + " disconnected");
    CompanionDevices.peerDisconnected(id);
    PeerPresence.peerDisconnected(id);
    channels.disconnected(id);
    reportPeer(peer, "disconnected");
    notifyCapabilities(peer.capabilities);
    notifyPeer(peer, false);
  }

  static final String ACTION_BIND_LISTENER = "com.google.android.gms.wearable.BIND_LISTENER";

  /**
   * {@code WearableListenerService.onPeerConnected} / {@code onPeerDisconnected}, which Play
   * services calls only on a service with the (deprecated) {@code BIND_LISTENER} filter; apps today
   * watch capabilities instead, which {@link #notifyCapabilities} covers.
   */
  private void notifyPeer(Peer peer, boolean connected) {
    Node node = peer.node();
    onMain(
        () -> {
          List<Service> services = services(ACTION_BIND_LISTENER, null);
          onListenerThread(
              () -> {
                for (Service service : services) {
                  if (!(service instanceof WearableListenerService)) continue;
                  WearableListenerService listener = (WearableListenerService) service;
                  if (connected) listener.onPeerConnected(node);
                  else listener.onPeerDisconnected(node);
                }
              });
        });
  }

  private void hubClosed() {
    List<String> gone;
    synchronized (this) {
      gone = new ArrayList<>(peers.keySet());
    }
    for (String id : gone) onBye(id);
  }

  private void onCapabilities(String from, JSONArray capabilities) throws JSONException {
    Set<String> changed = new LinkedHashSet<>();
    synchronized (this) {
      Peer peer = peers.get(from);
      if (peer == null) return;
      Set<String> next = new LinkedHashSet<>();
      for (int i = 0; capabilities != null && i < capabilities.length(); i++) {
        next.add(capabilities.getString(i));
      }
      for (String name : peer.capabilities) if (!next.contains(name)) changed.add(name);
      for (String name : next) if (!peer.capabilities.contains(name)) changed.add(name);
      peer.capabilities.clear();
      peer.capabilities.addAll(next);
    }
    notifyCapabilities(changed);
  }

  private void notifyCapabilities(Set<String> names) {
    for (String name : names) {
      CapabilityInfo info = capability(name);
      Uri uri = Uri.parse("wear://*/" + name);
      onMain(
          () -> {
            for (Registration<CapabilityClient.OnCapabilityChangedListener> r :
                capabilityListeners) {
              String wanted = capabilityNames.get(r.listener);
              if ((wanted != null && wanted.equals(name)) || (wanted == null && r.accepts(uri))) {
                r.listener.onCapabilityChanged(info);
              }
            }
            List<Service> services = services(ACTION_CAPABILITY_CHANGED, uri);
            onListenerThread(
                () -> {
                  for (Service service : services) {
                    if (service instanceof WearableListenerService) {
                      ((WearableListenerService) service).onCapabilityChanged(info);
                    }
                  }
                });
          });
    }
  }

  private void onMessage(JSONObject frame) throws JSONException {
    String from = frame.getString("from");
    String path = frame.getString("path");
    byte[] data = decode(frame.optString("data", null));
    int requestId = frame.optInt("requestId", 0);
    Uri uri = Uri.parse(wearUri(from, path));
    if (requestId != 0) {
      onMain(() -> answer(from, path, data, requestId, uri));
      return;
    }
    DataLayerClients.SimMessage message = new DataLayerClients.SimMessage(0, path, data, from);
    onMain(
        () -> {
          List<String> to = new ArrayList<>();
          for (Registration<MessageClient.OnMessageReceivedListener> r : messageListeners) {
            if (!r.accepts(uri)) continue;
            r.listener.onMessageReceived(message);
            to.add(r.listener.getClass().getName());
          }
          List<Service> services = services(ACTION_MESSAGE_RECEIVED, uri);
          onListenerThread(
              () -> {
                for (Service service : services) {
                  if (!(service instanceof WearableListenerService)) continue;
                  ((WearableListenerService) service).onMessageReceived(message);
                  to.add(service.getClass().getName());
                }
                event("message", "in", from, path, data, to);
              });
        });
  }

  /** Runs the app's handler of a request on the main thread and sends its answer back. */
  private void answer(String from, String path, byte[] data, int requestId, Uri uri) {
    List<String> to = new ArrayList<>();
    Task<byte[]> answer = null;
    for (Map.Entry<MessageClient.RpcService, String> e : rpcServices.entrySet()) {
      if (!path.startsWith(e.getValue())) continue;
      answer = e.getKey().onRequest(from, path, data);
      to.add(e.getKey().getClass().getName());
      if (answer != null) break;
    }
    if (answer != null) {
      event("request", "in", from, path, data, to);
      respond(from, path, requestId, answer);
      return;
    }
    // Play services hands a request to a service declaring REQUEST_RECEIVED, and also to one that
    // declares only MESSAGE_RECEIVED for the path, as Horologist's gRPC services (and its samples)
    // do: MeshCore's phone answers its watch that way.
    List<Service> services = services(ACTION_REQUEST_RECEIVED, uri);
    for (Service service : services(ACTION_MESSAGE_RECEIVED, uri)) {
      if (!services.contains(service)) services.add(service);
    }
    onListenerThread(
        () -> {
          Task<byte[]> handled = null;
          for (Service service : services) {
            if (!(service instanceof WearableListenerService)) continue;
            handled = ((WearableListenerService) service).onRequest(from, path, data);
            to.add(service.getClass().getName());
            if (handled != null) break;
          }
          event("request", "in", from, path, data, to);
          respond(from, path, requestId, handled);
        });
  }

  /** Sends the peer [answer]'s outcome, or that nothing here answers [path]. */
  private void respond(String from, String path, int requestId, Task<byte[]> answer) {
    if (answer == null) {
      send(
          frame("response")
              .put("to", from)
              .put("requestId", requestId)
              .put("error", "no service in " + app.getPackageName() + " answers " + path));
      return;
    }
    answer.addOnCompleteListener(
        task -> {
          JSONObjectBuilder response =
              frame("response").put("to", from).put("requestId", requestId);
          if (task.isSuccessful()) {
            byte[] bytes = task.getResult();
            response.put("data", encode(bytes));
            event("response", "out", from, path, bytes, null);
          } else {
            response.put("error", String.valueOf(task.getException()));
          }
          send(response);
        });
  }

  private void onResponse(JSONObject frame) {
    TaskCompletionSource<byte[]> response = pending.remove(frame.optInt("requestId"));
    if (response == null) return;
    String error = frame.optString("error", null);
    if (error != null) {
      // The hub's reader thread: the app's task completes on its main looper, as Play services'.
      tasks.fail(response, new ApiException(new Status(INTERNAL_ERROR, error)));
      event("response", "in", frame.optString("from"), null, null, null);
      return;
    }
    byte[] data = decode(frame.optString("data", null));
    event("response", "in", frame.optString("from"), null, data, null);
    tasks.succeed(response, data);
  }

  private void onData(JSONObject frame) throws JSONException {
    String uri = frame.getString("uri");
    boolean deleted = frame.optBoolean("deleted");
    Item item;
    synchronized (this) {
      if (deleted) {
        item = items.remove(uri);
        if (item == null) return;
      } else {
        Map<String, String> keys = new LinkedHashMap<>();
        JSONObject assetKeys = frame.optJSONObject("assets");
        if (assetKeys != null) {
          for (Iterator<String> it = assetKeys.keys(); it.hasNext(); ) {
            String key = it.next();
            keys.put(key, assetKeys.getString(key));
          }
        }
        JSONObject assetBytes = frame.optJSONObject("assetBytes");
        if (assetBytes != null) {
          for (Iterator<String> it = assetBytes.keys(); it.hasNext(); ) {
            String digest = it.next();
            assets.put(digest, decode(assetBytes.getString(digest)));
          }
        }
        item = new Item(uri, decode(frame.optString("data", null)), keys);
        items.put(uri, item);
      }
    }
    List<Item> changed = new ArrayList<>();
    changed.add(item);
    deliverData(changed, deleted ? TYPE_DELETED : TYPE_CHANGED, true);
  }

  /**
   * Tells the app's data listeners about [changed], and for a peer's change ([remote]) also its
   * manifest services whose filter matches, on the main thread.
   */
  private void deliverData(List<Item> changed, int type, boolean remote) {
    onMain(
        () -> {
          for (Item item : changed) {
            Uri uri = Uri.parse(item.uri);
            List<Item> one = new ArrayList<>();
            one.add(item);
            List<String> to = new ArrayList<>();
            for (Registration<DataClient.OnDataChangedListener> r : dataListeners) {
              if (!r.accepts(uri)) continue;
              r.listener.onDataChanged(new DataEventBuffer(holder(one, type)));
              to.add(r.listener.getClass().getName());
            }
            if (remote) {
              List<Service> services = services(ACTION_DATA_CHANGED, uri);
              onListenerThread(
                  () -> {
                    for (Service service : services) {
                      if (!(service instanceof WearableListenerService)) continue;
                      ((WearableListenerService) service)
                          .onDataChanged(new DataEventBuffer(holder(one, type)));
                      to.add(service.getClass().getName());
                    }
                    event(
                        "data",
                        "in",
                        uri.getHost(),
                        uri.getPath(),
                        item.data,
                        to,
                        type == TYPE_DELETED ? "deleted" : "changed");
                  });
            }
          }
        });
  }

  /**
   * The app's manifest services with an intent filter for [action] and [uri], each created (once)
   * through {@link Navigator}, as Play services binds a {@code WearableListenerService}.
   */
  List<Service> services(String action, Uri uri) {
    Intent intent = new Intent(action).setPackage(app.getPackageName());
    if (uri != null) intent.setData(uri);
    List<Service> result = new ArrayList<>();
    for (ResolveInfo info : app.getPackageManager().queryIntentServices(intent, 0)) {
      if (info.serviceInfo == null) continue;
      ComponentName component =
          new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name);
      try {
        Service service = Navigator.serviceFor(component);
        if (service != null) result.add(service);
      } catch (RuntimeException e) {
        System.err.println("datalayer: cannot create " + component + ": " + e);
      }
    }
    return result;
  }

  /**
   * Runs a manifest {@code WearableListenerService}'s callback off the main thread, in order, as
   * Play services does: apps block in them (Home Assistant's watch runs {@code runBlocking} work
   * that needs the main thread), which on the main thread would deadlock.
   */
  void onListenerThread(Runnable action) {
    Handler handler;
    synchronized (this) {
      if (listenerThread == null) {
        HandlerThread thread = new HandlerThread("WearableListenerService");
        thread.start();
        listenerThread = new Handler(thread.getLooper());
      }
      handler = listenerThread;
    }
    handler.post(guarded(action));
  }

  void onMain(Runnable action) {
    main.post(guarded(action));
  }

  private Runnable guarded(Runnable action) {
    return () -> {
      try {
        action.run();
      } catch (RuntimeException e) {
        System.err.println("datalayer: delivery failed: " + e);
        e.printStackTrace();
        Protocol channel = protocol;
        if (channel != null) {
          channel.event(
              "error",
              new Protocol.Json()
                  .put("kind", "datalayer")
                  .put("message", String.valueOf(e))
                  .object());
        }
      }
    };
  }

  // ---------------------------------------------------------------------------------------------
  // Remote activities

  /**
   * Takes the broadcast {@code RemoteActivityHelper.startRemoteActivity} sends the Wear OS app
   * ({@code com.google.android.wearable.app}, absent here) and does what that app does: starts the
   * intent on the other node, and answers the app's {@code ResultReceiver} when the node has.
   */
  private void listenForRemoteIntents() {
    BroadcastReceiver receiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context context, Intent intent) {
            onRemoteIntent(intent);
          }
        };
    IntentFilter filter = new IntentFilter(RemoteIntents.ACTION_REMOTE_INTENT);
    main.post(
        guarded(
            () -> {
              if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
              } else {
                app.registerReceiver(receiver, filter);
              }
            }));
  }

  @SuppressWarnings("deprecation") // getParcelableExtra(String): the typed one is API 33+.
  private void onRemoteIntent(Intent broadcast) {
    Intent target = broadcast.getParcelableExtra(RemoteIntents.EXTRA_INTENT);
    ResultReceiver result = broadcast.getParcelableExtra(RemoteIntents.EXTRA_RESULT_RECEIVER);
    String node =
        RemoteIntents.target(broadcast.getStringExtra(RemoteIntents.EXTRA_NODE_ID), peerIds());
    if (target == null || node == null) {
      answerLaunch(result, RemoteIntents.RESULT_FAILED);
      activityEvent(
          "out",
          node,
          target,
          target == null ? "no intent" : "TARGET_NODE_NOT_CONNECTED",
          RemoteIntents.RESULT_FAILED);
      return;
    }
    int id = requestIds.incrementAndGet();
    if (result != null) remoteLaunches.put(id, result);
    send(
        frame("activity")
            .put("to", node)
            .put("requestId", id)
            .put("intent", Intents.toJson(target)));
    activityEvent("out", node, target, "sent", null);
  }

  /** The peer's remote intent: started here when this app has an activity for it. */
  private void onRemoteActivity(JSONObject frame) throws JSONException {
    String from = frame.getString("from");
    int requestId = frame.optInt("requestId");
    Intent intent = Intents.fromJson(frame.getJSONObject("intent"), app);
    onMain(
        () -> {
          Intent launch = new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
          boolean resolves =
              !app.getPackageManager().queryIntentActivities(launch, 0).isEmpty();
          Uri data = launch.getData();
          RemoteIntents.Outcome outcome =
              RemoteIntents.outcome(
                  resolves,
                  data == null ? null : data.getScheme(),
                  launch.getPackage(),
                  app.getPackageName());
          int code =
              outcome == RemoteIntents.Outcome.FAIL
                  ? RemoteIntents.RESULT_FAILED
                  : RemoteIntents.RESULT_OK;
          String state;
          switch (outcome) {
            case START:
              // The navigator starts it on its next cycle, like any startActivity from the app.
              app.startActivity(launch);
              state = "started";
              break;
            case SYSTEM:
              state = "no browser or Play Store in the simulator; a device would open it there";
              break;
            default:
              state = "no activity in " + app.getPackageName() + " handles it";
          }
          send(
              frame("activityResult")
                  .put("to", from)
                  .put("requestId", requestId)
                  .put("result", code)
                  .put("state", state));
          activityEvent("in", from, intent, state, code);
        });
  }

  private void onRemoteActivityResult(JSONObject frame) {
    ResultReceiver result = remoteLaunches.remove(frame.optInt("requestId"));
    int code = frame.optInt("result", RemoteIntents.RESULT_FAILED);
    answerLaunch(result, code);
    Protocol channel = protocol;
    if (channel == null) return;
    channel.event(
        "datalayer",
        new Protocol.Json()
            .put("kind", "activityResult")
            .put("direction", "in")
            .put("node", frame.optString("from"))
            .put("result", code)
            .put("state", frame.optString("state", null))
            .object());
  }

  private static void answerLaunch(ResultReceiver result, int code) {
    if (result == null) return;
    try {
      result.send(code, null);
    } catch (RuntimeException e) {
      System.err.println("datalayer: cannot answer the remote launch: " + e);
    }
  }

  private void activityEvent(
      String direction, String node, Intent intent, String state, Integer result) {
    log(
        "activity "
            + direction
            + " "
            + (node == null ? "" : node + " ")
            + (intent == null ? "" : intent.toUri(0))
            + " "
            + state);
    Protocol channel = protocol;
    if (channel == null) return;
    Protocol.Json json =
        new Protocol.Json()
            .put("kind", "activity")
            .put("direction", direction)
            .put("node", node)
            .put("intent", intent == null ? null : Intents.toJson(intent))
            .put("state", state);
    if (result != null) json.put("result", result);
    channel.event("datalayer", json.object());
  }

  // ---------------------------------------------------------------------------------------------
  // The Wear OS app's OAuth and notification bridging (WearCompanion)

  /**
   * A watch app's {@code RemoteAuthClient.sendAuthorizationRequest}: the Wear OS app opens [url] on
   * the phone, and its redirect comes back to [callback]. With no phone, {@code
   * ERROR_PHONE_UNAVAILABLE}.
   */
  void sendAuthRequest(String url, String packageName, Object callback) {
    List<String> nodes = peerIds();
    if (nodes.isEmpty()) {
      WearCompanion.answer(callback, null, 1 /* RemoteAuthClient.ERROR_PHONE_UNAVAILABLE */);
      return;
    }
    int id = requestIds.incrementAndGet();
    authCallbacks.put(id, callback);
    String node = nodes.get(0);
    send(
        frame("oauth")
            .put("to", node)
            .put("requestId", id)
            .put("url", url)
            .put("package", packageName));
    authEvent("out", node, url, "sent to the phone");
  }

  /**
   * On the phone: a watch's OAuth request. A device opens [url] in the phone's browser, the user
   * signs in, and the provider redirects to {@code https://wear.googleapis.com/3p_auth/<package>},
   * which the Wear OS app sends back. The simulator has no browser: the control channel's {@code
   * dataLayer} command with {@code oauthResponse} stands in for the redirect.
   */
  private void onAuthRequest(JSONObject frame) {
    authRequests.add(frame);
    authEvent(
        "in",
        frame.optString("from"),
        frame.optString("url", null),
        "a device would open this in the phone's browser; answer with"
            + " {\"cmd\":\"dataLayer\",\"oauthResponse\":\"<redirect URL>\"}");
  }

  /** The {@code oauthResponse} command: answers the oldest open OAuth request. */
  JSONObject answerAuth(String responseUrl, int errorCode) {
    if (authRequests.isEmpty()) throw new IllegalStateException("no OAuth request is waiting");
    JSONObject request = authRequests.remove(0);
    String to = request.optString("from");
    send(
        frame("oauthResult")
            .put("to", to)
            .put("requestId", request.optInt("requestId"))
            .put("responseUrl", responseUrl)
            .put("errorCode", errorCode));
    authEvent("out", to, responseUrl, "redirect sent to the watch");
    return new Protocol.Json()
        .put("answered", request.optString("url", null))
        .put("responseUrl", responseUrl)
        .put("errorCode", errorCode)
        .object();
  }

  private void onAuthResult(JSONObject frame) {
    Object callback = authCallbacks.remove(frame.optInt("requestId"));
    String url = frame.optString("responseUrl", null);
    int errorCode = frame.optInt("errorCode", WearCompanion.NO_ERROR);
    authEvent("in", frame.optString("from"), url, "errorCode " + errorCode);
    if (callback != null) WearCompanion.answer(callback, url, errorCode);
  }

  private void authEvent(String direction, String node, String url, String state) {
    log("oauth " + direction + " " + node + " " + url + " " + state);
    Protocol channel = protocol;
    if (channel == null) return;
    channel.event(
        "datalayer",
        new Protocol.Json()
            .put("kind", "oauth")
            .put("direction", direction)
            .put("node", node)
            .put("url", url)
            .put("state", state)
            .object());
  }

  /** {@code BridgingManager.setConfig}: what the app asked the Wear OS app to bridge. */
  void reportBridging(android.os.Bundle config) {
    Protocol.Json json = new Protocol.Json().put("kind", "bridging");
    if (config != null) {
      for (String key : config.keySet()) json.put(key, String.valueOf(config.get(key)));
    }
    log("bridging " + config);
    Protocol channel = protocol;
    if (channel != null) channel.event("datalayer", json.object());
  }

  // ---------------------------------------------------------------------------------------------
  // Reporting

  JSONObject describe() {
    Protocol.Json json = new Protocol.Json();
    json.put("node", nodeJson(localId, localName, app.getPackageName(), localCapabilities));
    JSONArray peerArray = new JSONArray();
    JSONArray itemArray = new JSONArray();
    synchronized (this) {
      for (Peer peer : peers.values()) {
        peerArray.put(nodeJson(peer.id, peer.name, peer.packageName, peer.capabilities));
      }
      for (Item item : items.values()) {
        itemArray.put(
            new Protocol.Json()
                .put("uri", item.uri)
                .put("bytes", item.data == null ? 0 : item.data.length)
                .put("assets", new JSONObject(item.assets))
                .object());
      }
    }
    json.put("peers", peerArray);
    json.put("items", itemArray);
    json.put("dataListeners", dataListeners.size());
    json.put("messageListeners", messageListeners.size());
    json.put("channels", channels.describe());
    return json.object();
  }

  private static JSONObject nodeJson(
      String id, String name, String packageName, Set<String> capabilities) {
    return new Protocol.Json()
        .put("id", id)
        .put("name", name)
        .put("package", packageName)
        .put("capabilities", new JSONArray(capabilities))
        .object();
  }

  private void reportPeer(Peer peer, String state) {
    Protocol channel = protocol;
    if (channel == null) return;
    channel.event(
        "datalayer",
        new Protocol.Json()
            .put("kind", "peer")
            .put("state", state)
            .put("node", nodeJson(peer.id, peer.name, peer.packageName, peer.capabilities))
            .object());
  }

  private void event(
      String kind, String direction, String node, String path, byte[] data, List<String> to) {
    event(kind, direction, node, path, data, to, null);
  }

  /** A {@code datalayer} event on the control channel, and a line on stderr. */
  private void event(
      String kind,
      String direction,
      String node,
      String path,
      byte[] data,
      List<String> to,
      String state) {
    int size = data == null ? 0 : data.length;
    log(
        kind
            + " "
            + direction
            + " "
            + (node == null ? "" : node + " ")
            + (path == null ? "" : path + " ")
            + size
            + " bytes"
            + (state == null ? "" : " " + state)
            + (to == null ? "" : " -> " + to));
    Protocol channel = protocol;
    if (channel == null) return;
    Protocol.Json json =
        new Protocol.Json()
            .put("kind", kind)
            .put("direction", direction)
            .put("node", node)
            .put("path", path)
            .put("bytes", size)
            .put("preview", preview(data));
    if (state != null) json.put("state", state);
    if (to != null) json.put("deliveredTo", new JSONArray(to));
    channel.event("datalayer", json.object());
  }

  /** The payload's start as text when it reads as text, so an agent sees what crossed. */
  static String preview(byte[] data) {
    if (data == null || data.length == 0) return null;
    int n = Math.min(data.length, 120);
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < n; i++) {
      int c = data[i] & 0xff;
      text.append(c >= 0x20 && c < 0x7f ? (char) c : '.');
    }
    return text.toString();
  }

  static void log(String line) {
    System.err.println("datalayer: " + line);
  }

  // ---------------------------------------------------------------------------------------------
  // Frames

  /** {@link Protocol.Json} that also sends: a frame under construction. */
  static final class JSONObjectBuilder {
    final Protocol.Json json = new Protocol.Json();

    JSONObjectBuilder put(String key, Object value) {
      json.put(key, value);
      return this;
    }
  }

  private JSONObjectBuilder frame(String type) {
    return new JSONObjectBuilder().put("type", type).put("from", localId);
  }

  private JSONObjectBuilder hello(boolean reply) {
    return frame("hello")
        .put("name", localName)
        .put("package", app.getPackageName())
        .put("capabilities", capabilitiesJson())
        .put("reply", reply);
  }

  private void send(JSONObjectBuilder frame) {
    sendFrame(frame.json.object());
  }

  /** A frame to the peers: over the hub, or in agent mode as a {@code send} event. */
  void sendFrame(JSONObject frame) {
    if (agent) {
      showFrame(frame);
      return;
    }
    DataLayerLink wire = link;
    if (wire != null) wire.send(frame.toString());
  }

  /**
   * Agent mode: a frame this node sent, as a {@code datalayer} event of kind {@code send}: the
   * frame's fields, with its payload as base64 {@code data}, a {@code text} preview and, when the
   * bytes are a {@code DataMap}, the map as {@code dataMap} JSON; a data item's assets as {@code
   * assets} (key → base64, as {@code dataLayer put} takes them; see {@link AgentAssets}) and their
   * {@code assetDigests}.
   */
  private void showFrame(JSONObject frame) {
    Protocol channel = protocol;
    if (channel == null) {
      synchronized (unsent) {
        unsent.add(frame);
      }
      return;
    }
    Protocol.Json json = new Protocol.Json().put("kind", "send");
    for (java.util.Iterator<String> it = frame.keys(); it.hasNext(); ) {
      String key = it.next();
      if (key.equals("assetBytes")) continue;
      json.put(key, frame.opt(key));
    }
    JSONObject assetKeys = frame.optJSONObject("assets");
    if (assetKeys != null && assetKeys.length() > 0) {
      // Replayable: key → base64, as `dataLayer put` takes `assets`; the digests alongside.
      Map<String, String> digests = new LinkedHashMap<>();
      for (Iterator<String> it = assetKeys.keys(); it.hasNext(); ) {
        String key = it.next();
        digests.put(key, assetKeys.optString(key));
      }
      Map<String, String> bytesByDigest = new LinkedHashMap<>();
      JSONObject assetBytes = frame.optJSONObject("assetBytes");
      if (assetBytes != null) {
        for (Iterator<String> it = assetBytes.keys(); it.hasNext(); ) {
          String digest = it.next();
          bytesByDigest.put(digest, assetBytes.optString(digest));
        }
      }
      Map<String, Integer> omitted = new LinkedHashMap<>();
      json.put(
          "assets",
          new JSONObject(
              AgentAssets.replayable(
                  digests, bytesByDigest, AgentAssets.MAX_BASE64_CHARS, omitted)));
      json.put("assetDigests", assetKeys);
      if (!omitted.isEmpty()) json.put("assetsOmitted", new JSONObject(omitted));
    }
    String data = frame.optString("data", null);
    if (data != null) {
      byte[] bytes = decode(data);
      json.put("bytes", bytes.length);
      json.put("text", preview(bytes));
      JSONObject map = DataMapJson.toJson(bytes);
      if (map != null) json.put("dataMap", map);
    }
    channel.event("datalayer", json.object());
  }

  /** A frame as if a peer sent it: the agent's stand-in for the hub. */
  void inject(JSONObject frame) {
    receive(frame.toString());
  }

  /** What the agent's {@code reply} answers for [requestId]. */
  synchronized String pendingKind(int requestId) {
    if (pending.containsKey(requestId)) return "request";
    if (remoteLaunches.containsKey(requestId)) return "activity";
    if (authCallbacks.containsKey(requestId)) return "oauth";
    return null;
  }

  String packageName() {
    return app.getPackageName();
  }

  boolean isWatchNode() {
    return isWatch(app);
  }

  synchronized List<String> connectedPeerIds() {
    return new ArrayList<>(peers.keySet());
  }

  private static void putUnchecked(JSONObject object, String key, Object value) {
    try {
      object.put(key, value);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // URIs and bytes

  static String wearUri(String node, String path) {
    String p = path == null ? "/" : path.startsWith("/") ? path : "/" + path;
    return "wear://" + node + p;
  }

  /**
   * Whether a listener's or query's [filter] URI takes [event]: the host {@code *} (or none)
   * matches every node, and the path matches literally or, for {@code FILTER_PREFIX}, as a prefix.
   */
  static boolean matches(Uri filter, int filterType, Uri event) {
    String host = filter.getHost();
    if (host != null && !host.isEmpty() && !host.equals("*") && !host.equals(event.getHost())) {
      return false;
    }
    String path = filter.getPath();
    if (path == null || path.isEmpty() || (path.equals("/") && filterType == 1)) return true;
    String eventPath = event.getPath() == null ? "" : event.getPath();
    return filterType == 1 ? eventPath.startsWith(path) : eventPath.equals(path);
  }

  static String encode(byte[] bytes) {
    return bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
  }

  static byte[] decode(String text) {
    return text == null ? null : Base64.getDecoder().decode(text);
  }

  static String digest(byte[] bytes) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-1").digest(bytes);
      StringBuilder hex = new StringBuilder();
      for (byte b : hash) hex.append(String.format("%02x", b));
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
