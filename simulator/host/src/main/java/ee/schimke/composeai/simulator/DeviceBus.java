package ee.schimke.composeai.simulator;

import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The device the simulator's app shares with other apps ({@code launch a.apk --install b.apk}):
 * each app runs in a simulator JVM of its own, as each app runs in a process of its own on a
 * device, and what passes between them (a call into another app's service) goes over this bus as
 * the binder would carry it. The CLI's hub relays every frame to every other simulator
 * ({@link DataLayerLink}'s framing, one JSON object per frame); each simulator takes the frames
 * addressed to its package.
 *
 * <p>A frame is a {@code request} ({@code from}, {@code to}, {@code id}, {@code op}, {@code body}),
 * a {@code reply} to one ({@code id}, {@code body}), a {@code broadcast} to every app ({@code
 * from}, {@code event}, {@code body}), a {@code hello} as a simulator joins, or the hub's {@code
 * bye} when one leaves, which fails the requests waiting on it. A request runs on the
 * receiving app's main thread through the handler registered for its {@code op}, which replies
 * when it has an answer, now or later.
 */
final class DeviceBus {

  /** The localhost port of the CLI's hub; set only for a launch of several apps. */
  static final String BUS_PROPERTY = "composeai.simulator.device.bus";

  /** The other apps' APKs, {@link java.io.File#pathSeparator}-separated. */
  static final String PEERS_PROPERTY = "composeai.simulator.device.peers";

  /** A request's handler: [reply] takes the answer, on any thread, once. */
  interface Op {
    void handle(String from, JSONObject body, Reply reply) throws Exception;
  }

  interface Reply {
    void send(JSONObject body);
  }

  /** A broadcast's handler, on the main thread. */
  interface Listener {
    void heard(String from, JSONObject body) throws Exception;
  }

  private static volatile DataLayerLink link;
  private static volatile String self;
  private static final Map<String, Op> ops = new ConcurrentHashMap<>();
  private static final Map<String, Listener> listeners = new ConcurrentHashMap<>();
  private static final Map<Long, Waiting> waiting = new ConcurrentHashMap<>();
  private static final AtomicLong nextId = new AtomicLong(1);

  private static final class Waiting {
    final String to;
    final CompletableFuture<JSONObject> future = new CompletableFuture<>();

    Waiting(String to) {
      this.to = to;
    }
  }

  static boolean enabled() {
    return System.getProperty(BUS_PROPERTY) != null;
  }

  /** Joins the bus as [packageName]; a no-op without one. */
  static synchronized void start(String packageName) {
    if (!enabled() || link != null) return;
    self = packageName;
    try {
      link =
          DataLayerLink.connect(
              Integer.parseInt(System.getProperty(BUS_PROPERTY)),
              DeviceBus::receive,
              () -> failAll("the device bus closed"));
    } catch (IOException e) {
      System.err.println("device bus: cannot connect: " + e);
      return;
    }
    send(new Protocol.Json().put("type", "hello").put("from", packageName).object());
  }

  /** Handles requests of [op] from now on. */
  static void on(String op, Op handler) {
    ops.put(op, handler);
  }

  /** Hears the other apps' broadcasts of [event] from now on. */
  static void onBroadcast(String event, Listener listener) {
    listeners.put(event, listener);
  }

  /** Tells every other app on the device [event]; a no-op without a bus. */
  static void broadcast(String event, JSONObject body) {
    send(
        new Protocol.Json()
            .put("type", "broadcast")
            .put("from", self)
            .put("event", event)
            .put("body", body)
            .object());
  }

  /**
   * Sends [op] to the app [to]; the future completes with the reply's body, or fails when that
   * app is not on the bus or leaves before answering.
   */
  static CompletableFuture<JSONObject> request(String to, String op, JSONObject body) {
    Waiting pending = new Waiting(to);
    if (link == null) {
      pending.future.completeExceptionally(new IllegalStateException("no device bus"));
      return pending.future;
    }
    long id = nextId.getAndIncrement();
    waiting.put(id, pending);
    send(
        new Protocol.Json()
            .put("type", "request")
            .put("from", self)
            .put("to", to)
            .put("id", id)
            .put("op", op)
            .put("body", body)
            .object());
    return pending.future;
  }

  private static void send(JSONObject frame) {
    DataLayerLink current = link;
    if (current != null) current.send(frame.toString());
  }

  private static void receive(String text) {
    JSONObject frame;
    try {
      frame = new JSONObject(text);
    } catch (JSONException e) {
      return;
    }
    String type = frame.optString("type");
    if (type.equals("bye")) {
      String gone = frame.optString("from");
      for (Map.Entry<Long, Waiting> e : waiting.entrySet()) {
        if (e.getValue().to.equals(gone) && waiting.remove(e.getKey()) != null) {
          e.getValue().future.completeExceptionally(new IllegalStateException(gone + " is gone"));
        }
      }
      return;
    }
    if (type.equals("broadcast")) {
      Listener listener = listeners.get(frame.optString("event"));
      if (listener == null) return;
      String from = frame.optString("from");
      JSONObject body = frame.optJSONObject("body");
      new Handler(Looper.getMainLooper())
          .post(
              () -> {
                try {
                  listener.heard(from, body == null ? new JSONObject() : body);
                } catch (Exception e) {
                  System.err.println("device bus: " + frame.optString("event") + ": " + e);
                }
              });
      return;
    }
    if (!self.equals(frame.optString("to"))) return;
    long id = frame.optLong("id");
    if (type.equals("reply")) {
      Waiting pending = waiting.remove(id);
      if (pending != null) pending.future.complete(frame.optJSONObject("body"));
      return;
    }
    if (!type.equals("request")) return;
    String from = frame.optString("from");
    String op = frame.optString("op");
    JSONObject body = frame.optJSONObject("body");
    Reply reply =
        answer ->
            send(
                new Protocol.Json()
                    .put("type", "reply")
                    .put("from", self)
                    .put("to", from)
                    .put("id", id)
                    .put("body", answer)
                    .object());
    Op handler = ops.get(op);
    if (handler == null) {
      reply.send(error(self + " does not handle " + op));
      return;
    }
    // As a binder call lands on the receiving app's thread; the main one is where its
    // components live.
    new Handler(Looper.getMainLooper())
        .post(
            () -> {
              try {
                handler.handle(from, body == null ? new JSONObject() : body, reply);
              } catch (Exception e) {
                reply.send(error(String.valueOf(e)));
              }
            });
  }

  static JSONObject error(String message) {
    return new Protocol.Json().put("busError", message).object();
  }

  private static void failAll(String why) {
    for (Long id : waiting.keySet()) {
      Waiting pending = waiting.remove(id);
      if (pending != null) pending.future.completeExceptionally(new IllegalStateException(why));
    }
    link = null;
  }

  private DeviceBus() {}
}
