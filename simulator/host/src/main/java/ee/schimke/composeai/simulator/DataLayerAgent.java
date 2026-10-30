package ee.schimke.composeai.simulator;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * {@code launch --data-layer agent}: the control channel plays the app's peers. Each {@code
 * dataLayer} command with an {@code action} becomes the frame a peer simulator would have sent,
 * handed to {@link DataLayer} as if it came over the hub, so it reaches the app's listeners and
 * manifest {@code WearableListenerService}s exactly as a paired simulator's would. What the app
 * sends comes back as {@code datalayer} events of kind {@code send}.
 *
 * <ul>
 *   <li>{@code connect} {@code node name package capabilities nearby}: a peer node joins
 *       (capability listeners, {@code getConnectedNodes}); its {@code hello} answer is the app's
 *       node and capabilities, followed by the app's data items, as a device syncs on connect;
 *   <li>{@code disconnect} {@code node}: it leaves;
 *   <li>{@code capabilities} {@code node capabilities}: its capability set changes;
 *   <li>{@code put} / {@code delete} {@code node path} and a payload: a data item of that node;
 *   <li>{@code message} / {@code request} {@code node path} and a payload: a message, or a request
 *       the app's {@code RpcService} / {@code onRequest} answers (a {@code response} send event);
 *   <li>{@code reply} {@code requestId} and a payload or {@code error}: answers the app's {@code
 *       sendRequest}; for a remote activity launch {@code result} (0 ok, 1 failed); for an OAuth
 *       request {@code responseUrl};
 *   <li>{@code config} {@code requestTimeoutMs}: how long the app's requests wait (0: forever).
 * </ul>
 *
 * A payload is {@code text} (UTF-8), {@code data} (base64) or {@code dataMap} (JSON, see {@link
 * DataMapJson}). {@code node} defaults to the only connected peer.
 */
final class DataLayerAgent {

  private final DataLayer node;

  DataLayerAgent(DataLayer node) {
    this.node = node;
  }

  JSONObject run(JSONObject c) throws JSONException {
    String action = c.getString("action");
    switch (action) {
      case "connect":
        {
          String id = c.optString("node", defaultPeerId());
          JSONObject hello =
              frame("hello", id)
                  .put("name", c.optString("name", "Agent " + id))
                  .put("package", c.optString("package", node.packageName()))
                  .put("capabilities", c.optJSONArray("capabilities") == null
                      ? new JSONArray()
                      : c.getJSONArray("capabilities"))
                  .put("nearby", c.optBoolean("nearby", true))
                  .put("reply", false);
          node.inject(hello);
          break;
        }
      case "disconnect":
        node.inject(frame("bye", peer(c)));
        break;
      case "capabilities":
        node.inject(
            frame("capabilities", peer(c)).put("capabilities", c.getJSONArray("capabilities")));
        break;
      case "put":
        {
          String from = peer(c);
          JSONObject frame =
              frame("data", from)
                  .put("uri", DataLayer.wearUri(from, c.getString("path")))
                  .put("data", DataLayer.encode(payload(c)));
          JSONObject assets = c.optJSONObject("assets");
          if (assets != null) {
            JSONObject keys = new JSONObject();
            JSONObject bytes = new JSONObject();
            for (Iterator<String> it = assets.keys(); it.hasNext(); ) {
              String key = it.next();
              byte[] asset = DataLayer.decode(assets.getString(key));
              String digest = DataLayer.digest(asset);
              keys.put(key, digest);
              bytes.put(digest, DataLayer.encode(asset));
            }
            frame.put("assets", keys).put("assetBytes", bytes);
          }
          node.inject(frame);
          break;
        }
      case "delete":
        {
          String from = peer(c);
          node.inject(
              frame("data", from)
                  .put("uri", DataLayer.wearUri(from, c.getString("path")))
                  .put("deleted", true));
          break;
        }
      case "message":
      case "request":
        {
          JSONObject frame =
              frame("message", peer(c))
                  .put("path", c.getString("path"))
                  .put("data", DataLayer.encode(payload(c)));
          int requestId = 0;
          if (action.equals("request")) {
            requestId = c.optInt("requestId", agentRequests.incrementAndGet());
            frame.put("requestId", requestId);
          }
          node.inject(frame);
          if (requestId != 0) return new Protocol.Json().put("requestId", requestId).object();
          break;
        }
      case "reply":
        return reply(c);
      case "config":
        if (c.has("requestTimeoutMs")) node.requestTimeoutMs = c.getLong("requestTimeoutMs");
        return new Protocol.Json().put("requestTimeoutMs", node.requestTimeoutMs).object();
      default:
        throw new IllegalArgumentException(
            "unknown dataLayer action "
                + action
                + " (connect, disconnect, capabilities, put, delete, message, request, reply,"
                + " config)");
    }
    return new Protocol.Json()
        .put("action", action)
        .put("peers", new JSONArray(node.connectedPeerIds()))
        .object();
  }

  /** Ids of the agent's own requests, clear of the app's. */
  private static final java.util.concurrent.atomic.AtomicInteger agentRequests =
      new java.util.concurrent.atomic.AtomicInteger(1 << 24);

  private JSONObject reply(JSONObject c) throws JSONException {
    int requestId = c.getInt("requestId");
    String kind = node.pendingKind(requestId);
    if (kind == null) throw new IllegalArgumentException("no open request " + requestId);
    String to = c.optString("node", null);
    String from = to != null ? to : firstPeerOr(node.localId + "-agent");
    JSONObject frame;
    switch (kind) {
      case "request":
        frame = frame("response", from).put("requestId", requestId);
        if (c.has("error")) frame.put("error", c.getString("error"));
        else frame.put("data", DataLayer.encode(payload(c)));
        break;
      case "activity":
        frame =
            frame("activityResult", from)
                .put("requestId", requestId)
                .put("result", c.optInt("result", RemoteIntents.RESULT_OK))
                .put("state", "answered by the agent");
        break;
      default:
        frame =
            frame("oauthResult", from)
                .put("requestId", requestId)
                .put("responseUrl", c.optString("responseUrl", null))
                .put("errorCode", c.optInt("errorCode", WearCompanion.NO_ERROR));
    }
    node.inject(frame);
    return new Protocol.Json().put("answered", requestId).put("kind", kind).object();
  }

  private JSONObject frame(String type, String from) throws JSONException {
    return new JSONObject().put("type", type).put("from", from).put("to", node.localId);
  }

  /** The node a command names, or the only connected one. */
  private String peer(JSONObject c) {
    String named = c.optString("node", null);
    if (named != null) return named;
    List<String> peers = node.connectedPeerIds();
    if (peers.size() == 1) return peers.get(0);
    throw new IllegalArgumentException(
        peers.isEmpty()
            ? "no peer: dataLayer connect first"
            : "several peers " + peers + ": name one with \"node\"");
  }

  private String firstPeerOr(String fallback) {
    List<String> peers = node.connectedPeerIds();
    return peers.isEmpty() ? fallback : peers.get(0);
  }

  /** A watch's peer is a phone, a phone's a watch. */
  private String defaultPeerId() {
    return node.isWatchNode() ? "phone-agent" : "watch-agent";
  }

  /** A command's payload: {@code text}, {@code data} (base64) or {@code dataMap} JSON. */
  static byte[] payload(JSONObject c) throws JSONException {
    if (c.has("dataMap")) return DataMapJson.toBytes(c.getJSONObject("dataMap"));
    if (c.has("text")) return c.getString("text").getBytes(StandardCharsets.UTF_8);
    if (c.has("data")) return DataLayer.decode(c.getString("data"));
    return new byte[0];
  }
}
