package ee.schimke.composeai.simulator;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import com.google.android.gms.common.api.GoogleApi;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemAsset;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeClient;
import com.google.android.gms.wearable.PutDataRequest;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The four Wearable clients {@link ShadowWearable} hands the app, each a subclass of Play services'
 * own abstract client (the app's copy, from its APK) answering from {@link DataLayer}, plus the
 * small value types they return. Every call completes at once except {@code sendRequest}, which
 * completes when the peer answers.
 *
 * <p>Compiled against {@code gmsStubs}; nothing here may be loaded outside the sandbox.
 */
final class DataLayerClients {

  static final class Data extends DataClient {
    private final DataLayer node;

    Data(Context context, DataLayer node) {
      super(context, GoogleApi.Settings.DEFAULT_SETTINGS);
      this.node = node;
    }

    @Override
    public Task<DataItem> putDataItem(PutDataRequest request) {
      return node.later(new SimItem(node.put(request)));
    }

    @Override
    public Task<DataItem> getDataItem(Uri uri) {
      List<DataLayer.Item> found = node.items(uri, FILTER_LITERAL);
      return node.later(found.isEmpty() ? null : new SimItem(found.get(0)));
    }

    @Override
    public Task<DataItemBuffer> getDataItems() {
      return node.later(DataLayer.itemBuffer(node.items(null, FILTER_LITERAL)));
    }

    @Override
    public Task<DataItemBuffer> getDataItems(Uri uri) {
      return getDataItems(uri, FILTER_LITERAL);
    }

    @Override
    public Task<DataItemBuffer> getDataItems(Uri uri, int filterType) {
      return node.later(DataLayer.itemBuffer(node.items(uri, filterType)));
    }

    @Override
    public Task<Integer> deleteDataItems(Uri uri) {
      return deleteDataItems(uri, FILTER_LITERAL);
    }

    @Override
    public Task<Integer> deleteDataItems(Uri uri, int filterType) {
      return node.later(node.delete(uri, filterType));
    }

    @Override
    public Task<GetFdForAssetResponse> getFdForAsset(Asset asset) {
      return fdFor(asset.getDigest());
    }

    @Override
    public Task<GetFdForAssetResponse> getFdForAsset(DataItemAsset asset) {
      return fdFor(asset.getId());
    }

    private Task<GetFdForAssetResponse> fdFor(String digest) {
      byte[] bytes = node.asset(digest);
      if (bytes == null) return node.laterFailure(DataLayer.assetUnavailable(digest));
      return node.later(new AssetResponse(bytes));
    }

    @Override
    public Task<Void> addListener(OnDataChangedListener listener) {
      node.dataListeners.add(new DataLayer.Registration<>(listener, null, FILTER_LITERAL));
      return node.later(null);
    }

    @Override
    public Task<Void> addListener(OnDataChangedListener listener, Uri uri, int filterType) {
      node.dataListeners.add(new DataLayer.Registration<>(listener, uri, filterType));
      return node.later(null);
    }

    @Override
    public Task<Boolean> removeListener(OnDataChangedListener listener) {
      return node.later(node.dataListeners.removeIf(r -> r.listener == listener));
    }
  }

  static final class Messages extends MessageClient {
    private final DataLayer node;

    Messages(Context context, DataLayer node) {
      super(context, GoogleApi.Settings.DEFAULT_SETTINGS);
      this.node = node;
    }

    @Override
    public Task<Integer> sendMessage(String nodeId, String path, byte[] data) {
      return node.sendMessage(nodeId, path, data);
    }

    @Override
    public Task<byte[]> sendRequest(String nodeId, String path, byte[] data) {
      return node.sendRequest(nodeId, path, data);
    }

    @Override
    public Task<Void> addListener(OnMessageReceivedListener listener) {
      node.messageListeners.add(new DataLayer.Registration<>(listener, null, FILTER_LITERAL));
      return node.later(null);
    }

    @Override
    public Task<Void> addListener(OnMessageReceivedListener listener, Uri uri, int filterType) {
      node.messageListeners.add(new DataLayer.Registration<>(listener, uri, filterType));
      return node.later(null);
    }

    @Override
    public Task<Boolean> removeListener(OnMessageReceivedListener listener) {
      return node.later(node.messageListeners.removeIf(r -> r.listener == listener));
    }

    @Override
    public Task<Void> addRpcService(RpcService service, String path) {
      node.rpcServices.put(service, path);
      return node.later(null);
    }

    @Override
    public Task<Void> addRpcService(RpcService service, String path, String nodeId) {
      return addRpcService(service, path);
    }

    @Override
    public Task<Boolean> removeRpcService(RpcService service) {
      return node.later(node.rpcServices.remove(service) != null);
    }
  }

  static final class Nodes extends NodeClient {
    private final DataLayer node;

    Nodes(Context context, DataLayer node) {
      super(context, GoogleApi.Settings.DEFAULT_SETTINGS);
      this.node = node;
    }

    @Override
    public Task<Node> getLocalNode() {
      return node.later(node.localNode());
    }

    @Override
    public Task<List<Node>> getConnectedNodes() {
      return node.later(node.connectedNodes());
    }

    @Override
    public Task<String> getCompanionPackageForNode(String nodeId) {
      return node.later(node.companionPackage(nodeId));
    }

    @Override
    public Task<String> getNodeId(String address) {
      return node.later(null);
    }
  }

  static final class Capabilities extends CapabilityClient {
    private final DataLayer node;

    Capabilities(Context context, DataLayer node) {
      super(context, GoogleApi.Settings.DEFAULT_SETTINGS);
      this.node = node;
    }

    @Override
    public Task<CapabilityInfo> getCapability(String name, int nodeFilter) {
      return node.later(node.capability(name));
    }

    @Override
    public Task<Map<String, CapabilityInfo>> getAllCapabilities(int nodeFilter) {
      return node.later(node.allCapabilities());
    }

    @Override
    public Task<Void> addLocalCapability(String name) {
      node.setLocalCapability(name, true);
      return node.later(null);
    }

    @Override
    public Task<Void> removeLocalCapability(String name) {
      node.setLocalCapability(name, false);
      return node.later(null);
    }

    @Override
    public Task<Void> addListener(OnCapabilityChangedListener listener, String name) {
      node.capabilityListeners.add(new DataLayer.Registration<>(listener, null, FILTER_LITERAL));
      node.capabilityNames.put(listener, name);
      return node.later(null);
    }

    @Override
    public Task<Void> addListener(OnCapabilityChangedListener listener, Uri uri, int filterType) {
      node.capabilityListeners.add(new DataLayer.Registration<>(listener, uri, filterType));
      return node.later(null);
    }

    @Override
    public Task<Boolean> removeListener(OnCapabilityChangedListener listener, String name) {
      return removeListener(listener);
    }

    @Override
    public Task<Boolean> removeListener(OnCapabilityChangedListener listener) {
      node.capabilityNames.remove(listener);
      return node.later(node.capabilityListeners.removeIf(r -> r.listener == listener));
    }
  }

  // ---------------------------------------------------------------------------------------------

  static final class SimNode implements Node {
    private final String id;
    private final String name;

    SimNode(String id, String name) {
      this.id = id;
      this.name = name;
    }

    @Override
    public String getId() {
      return id;
    }

    @Override
    public String getDisplayName() {
      return name;
    }

    @Override
    public boolean isNearby() {
      return true;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof SimNode && ((SimNode) other).id.equals(id);
    }

    @Override
    public int hashCode() {
      return id.hashCode();
    }

    @Override
    public String toString() {
      return "Node{" + name + ", id=" + id + "}";
    }
  }

  static final class SimCapability implements CapabilityInfo {
    private final String name;
    private final Set<Node> nodes;

    SimCapability(String name, Set<Node> nodes) {
      this.name = name;
      this.nodes = nodes;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public Set<Node> getNodes() {
      return nodes;
    }
  }

  static final class SimMessage implements MessageEvent {
    private final int requestId;
    private final String path;
    private final byte[] data;
    private final String source;

    SimMessage(int requestId, String path, byte[] data, String source) {
      this.requestId = requestId;
      this.path = path;
      this.data = data;
      this.source = source;
    }

    @Override
    public int getRequestId() {
      return requestId;
    }

    @Override
    public String getPath() {
      return path;
    }

    @Override
    public byte[] getData() {
      return data;
    }

    @Override
    public String getSourceNodeId() {
      return source;
    }
  }

  /** A data item as {@code putDataItem} / {@code getDataItem} return it, already frozen. */
  static final class SimItem implements DataItem {
    private final Uri uri;
    private byte[] data;
    private final Map<String, DataItemAsset> assets = new LinkedHashMap<>();

    SimItem(DataLayer.Item item) {
      this.uri = Uri.parse(item.uri);
      this.data = item.data;
      for (Map.Entry<String, String> e : item.assets.entrySet()) {
        assets.put(e.getKey(), new SimAsset(e.getValue(), e.getKey()));
      }
    }

    @Override
    public Uri getUri() {
      return uri;
    }

    @Override
    public byte[] getData() {
      return data;
    }

    @Override
    public DataItem setData(byte[] data) {
      this.data = data;
      return this;
    }

    @Override
    public Map<String, DataItemAsset> getAssets() {
      return assets;
    }

    @Override
    public DataItem freeze() {
      return this;
    }

    @Override
    public boolean isDataValid() {
      return true;
    }
  }

  static final class SimAsset implements DataItemAsset {
    private final String id;
    private final String key;

    SimAsset(String id, String key) {
      this.id = id;
      this.key = key;
    }

    @Override
    public String getId() {
      return id;
    }

    @Override
    public String getDataItemKey() {
      return key;
    }

    @Override
    public DataItemAsset freeze() {
      return this;
    }

    @Override
    public boolean isDataValid() {
      return true;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof SimAsset && Objects.equals(((SimAsset) other).id, id);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(id);
    }
  }

  /** An asset's bytes, as a stream; there is no file descriptor behind it. */
  static final class AssetResponse extends DataClient.GetFdForAssetResponse {
    private final byte[] bytes;

    AssetResponse(byte[] bytes) {
      this.bytes = bytes;
    }

    @Override
    public ParcelFileDescriptor getFdForAsset() {
      return null;
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(bytes);
    }

    @Override
    public void release() {}
  }

  /** Whether every API is one of the simulator's clients, which are always available. */
  static boolean allSimulated(Object api, Object[] more) {
    if (!simulated(api)) return false;
    if (more != null) for (Object other : more) if (!simulated(other)) return false;
    return true;
  }

  private static boolean simulated(Object api) {
    return api instanceof Data
        || api instanceof Messages
        || api instanceof Nodes
        || api instanceof Capabilities;
  }

  /** {@code checkApiAvailability}'s answer when every API is available. */
  static Object available() {
    return Tasks.forResult(null);
  }

  private DataLayerClients() {}
}
