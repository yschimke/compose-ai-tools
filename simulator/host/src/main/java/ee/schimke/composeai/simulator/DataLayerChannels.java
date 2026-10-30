package ee.schimke.composeai.simulator;

import android.app.Service;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Parcel;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.WearableListenerService;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * {@code ChannelClient} for one node: the app's channels ({@link ChannelPipes}, which carries their
 * bytes as frames to the peer) and what Play services tells the app about them. A peer's {@code
 * openChannel} reaches the app's {@code ChannelCallback}s on the main thread and every manifest
 * {@code WearableListenerService} with a {@code CHANNEL_EVENT} filter matching {@code
 * wear://<node>/<path>}, on the listener thread ({@code onChannelOpened}); the input, output and
 * channel closing ({@code onInputClosed}, {@code onOutputClosed}, {@code onChannelClosed}) reach the
 * same, and the callbacks registered for that channel. Each is a {@code datalayer} event of kind
 * {@code channel} on the control channel, as are the bytes that cross ({@code state} {@code data}).
 */
final class DataLayerChannels implements ChannelPipes.Events {

  static final String ACTION_CHANNEL_EVENT = "com.google.android.gms.wearable.CHANNEL_EVENT";

  private final DataLayer node;
  final ChannelPipes pipes;
  private final List<ChannelClient.ChannelCallback> callbacks = new CopyOnWriteArrayList<>();
  private final Map<String, List<ChannelClient.ChannelCallback>> channelCallbacks =
      new ConcurrentHashMap<>();
  private final Map<String, SimChannel> channels = new ConcurrentHashMap<>();

  DataLayerChannels(DataLayer node) {
    this.node = node;
    this.pipes = new ChannelPipes(node.localId, node::sendFrame, this);
  }

  boolean receive(JSONObject frame) {
    return pipes.receive(frame);
  }

  void disconnected(String nodeId) {
    pipes.disconnected(nodeId);
  }

  // ---------------------------------------------------------------------------------------------
  // The client's calls

  Task<ChannelClient.Channel> open(String nodeId, String path) {
    if (!node.isPeer(nodeId)) return node.laterFailure(DataLayer.notConnected(nodeId));
    ChannelPipes.Pipe pipe = pipes.open(nodeId, path);
    report(pipe, "opened", "out", null, null);
    return node.later(channel(pipe));
  }

  Task<Void> register(ChannelClient.Channel channel, ChannelClient.ChannelCallback callback) {
    if (channel == null) {
      callbacks.add(callback);
    } else {
      channelCallbacks
          .computeIfAbsent(token(channel), t -> new CopyOnWriteArrayList<>())
          .add(callback);
    }
    return node.later(null);
  }

  Task<Boolean> unregister(ChannelClient.Channel channel, ChannelClient.ChannelCallback callback) {
    boolean removed;
    if (channel == null) {
      removed = callbacks.remove(callback);
    } else {
      List<ChannelClient.ChannelCallback> list = channelCallbacks.get(token(channel));
      removed = list != null && list.remove(callback);
    }
    return node.later(removed);
  }

  Task<Void> close(ChannelClient.Channel channel, int errorCode) {
    ChannelPipes.Pipe pipe = pipes.get(token(channel));
    if (pipe != null) pipes.close(pipe, errorCode);
    return node.later(null);
  }

  Task<InputStream> input(ChannelClient.Channel channel) {
    ChannelPipes.Pipe pipe = pipes.get(token(channel));
    if (pipe == null) return node.laterFailure(unknown(channel));
    return node.later(pipe.input);
  }

  Task<OutputStream> output(ChannelClient.Channel channel) {
    ChannelPipes.Pipe pipe = pipes.get(token(channel));
    if (pipe == null) return node.laterFailure(unknown(channel));
    return node.later(pipe.output);
  }

  /**
   * Streams [uri]'s bytes (from [offset], [length] of them or, at -1, to the end) to the channel's
   * output and closes it; the task completes when the last byte is sent, as on a device.
   */
  Task<Void> sendFile(ChannelClient.Channel channel, Uri uri, long offset, long length) {
    ChannelPipes.Pipe pipe = pipes.get(token(channel));
    if (pipe == null) return node.laterFailure(unknown(channel));
    TaskCompletionSource<Void> done = new TaskCompletionSource<>();
    InputStream source;
    try {
      source = resolver().openInputStream(uri);
      if (source == null) throw new IOException("cannot open " + uri);
    } catch (IOException | RuntimeException e) {
      return node.laterFailure(failed("sendFile " + uri, e));
    }
    worker(
        "ChannelClient.sendFile",
        () -> {
          try (InputStream in = source;
              OutputStream out = pipe.output) {
            long skipped = 0;
            while (skipped < offset) {
              long n = in.skip(offset - skipped);
              if (n <= 0) break;
              skipped += n;
            }
            byte[] buffer = new byte[ChannelPipes.MAX_FRAME_BYTES];
            long left = length < 0 ? Long.MAX_VALUE : length;
            while (left > 0) {
              int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
              if (n < 0) break;
              out.write(buffer, 0, n);
              left -= n;
            }
            node.tasks.succeed(done, null);
          } catch (IOException | RuntimeException e) {
            node.tasks.fail(done, failed("sendFile " + uri, e));
          }
        });
    return done.getTask();
  }

  /**
   * Writes what the channel's input carries to [uri] (a {@code file:} path, or a content URI the
   * app's resolver opens). The task completes once the transfer has started, and {@code
   * onInputClosed} says when it is written, as Play services does.
   */
  Task<Void> receiveFile(ChannelClient.Channel channel, Uri uri, boolean append) {
    ChannelPipes.Pipe pipe = pipes.get(token(channel));
    if (pipe == null) return node.laterFailure(unknown(channel));
    OutputStream target;
    try {
      target =
          ContentResolver.SCHEME_FILE.equals(uri.getScheme())
              ? new FileOutputStream(uri.getPath(), append)
              : resolver().openOutputStream(uri, append ? "wa" : "w");
      if (target == null) throw new IOException("cannot open " + uri);
    } catch (IOException | RuntimeException e) {
      return node.laterFailure(failed("receiveFile " + uri, e));
    }
    pipes.receivingFile(pipe);
    worker(
        "ChannelClient.receiveFile",
        () -> {
          IOException failure = null;
          try (OutputStream out = target) {
            pipe.input.transferTo(out);
          } catch (IOException e) {
            failure = e;
          } catch (RuntimeException e) {
            failure = new IOException(e);
          }
          if (failure == null) {
            pipes.inputDone(pipe);
          } else {
            DataLayer.log("channel " + pipe.token + " receiveFile failed: " + failure);
            pipes.receiveFailed(pipe, DataLayer.INTERNAL_ERROR);
          }
        });
    return node.later(null);
  }

  private ContentResolver resolver() {
    return node.app().getContentResolver();
  }

  private static void worker(String name, Runnable body) {
    Thread thread = new Thread(body, name);
    thread.setDaemon(true);
    thread.start();
  }

  private static ApiException unknown(ChannelClient.Channel channel) {
    return new ApiException(
        new Status(DataLayer.INTERNAL_ERROR, "no open channel " + channel));
  }

  private static ApiException failed(String what, Exception e) {
    return new ApiException(new Status(DataLayer.INTERNAL_ERROR, what + ": " + e));
  }

  private static String token(ChannelClient.Channel channel) {
    if (channel instanceof SimChannel) return ((SimChannel) channel).token;
    throw new IllegalArgumentException("not a channel of this simulator: " + channel);
  }

  private SimChannel channel(ChannelPipes.Pipe pipe) {
    return channels.computeIfAbsent(
        pipe.token, t -> new SimChannel(pipe.token, pipe.nodeId, pipe.path));
  }

  // ---------------------------------------------------------------------------------------------
  // ChannelPipes' events, to the app

  /** Which callback method an event calls. */
  private interface Call {
    void callback(ChannelClient.ChannelCallback callback, ChannelClient.Channel channel);

    void service(WearableListenerService service, ChannelClient.Channel channel);
  }

  @Override
  public void opened(ChannelPipes.Pipe pipe) {
    report(pipe, "opened", "in", null, null);
    deliver(
        pipe,
        false,
        new Call() {
          @Override
          public void callback(ChannelClient.ChannelCallback c, ChannelClient.Channel channel) {
            c.onChannelOpened(channel);
          }

          @Override
          public void service(WearableListenerService s, ChannelClient.Channel channel) {
            s.onChannelOpened(channel);
          }
        });
  }

  /**
   * The peer's output ended. Decided on the main looper, after the {@code onChannelOpened} posted
   * before it: an app that calls {@code receiveFile} there hears {@code onInputClosed} only when the
   * file is written, even when the bytes and their end arrived together.
   */
  @Override
  public void inputEnded(ChannelPipes.Pipe pipe) {
    node.onMain(() -> pipes.settleInputEnd(pipe));
  }

  @Override
  public void inputClosed(ChannelPipes.Pipe pipe, int reason, int errorCode) {
    report(pipe, "inputClosed", null, reason, errorCode);
    deliver(
        pipe,
        false,
        new Call() {
          @Override
          public void callback(ChannelClient.ChannelCallback c, ChannelClient.Channel channel) {
            c.onInputClosed(channel, reason, errorCode);
          }

          @Override
          public void service(WearableListenerService s, ChannelClient.Channel channel) {
            s.onInputClosed(channel, reason, errorCode);
          }
        });
  }

  @Override
  public void outputClosed(ChannelPipes.Pipe pipe, int reason, int errorCode) {
    report(pipe, "outputClosed", null, reason, errorCode);
    deliver(
        pipe,
        false,
        new Call() {
          @Override
          public void callback(ChannelClient.ChannelCallback c, ChannelClient.Channel channel) {
            c.onOutputClosed(channel, reason, errorCode);
          }

          @Override
          public void service(WearableListenerService s, ChannelClient.Channel channel) {
            s.onOutputClosed(channel, reason, errorCode);
          }
        });
  }

  @Override
  public void closed(ChannelPipes.Pipe pipe, int reason, int errorCode) {
    report(pipe, "closed", null, reason, errorCode);
    deliver(
        pipe,
        true,
        new Call() {
          @Override
          public void callback(ChannelClient.ChannelCallback c, ChannelClient.Channel channel) {
            c.onChannelClosed(channel, reason, errorCode);
          }

          @Override
          public void service(WearableListenerService s, ChannelClient.Channel channel) {
            s.onChannelClosed(channel, reason, errorCode);
          }
        });
  }

  @Override
  public void data(ChannelPipes.Pipe pipe, String direction, byte[] bytes) {
    Protocol channel = DataLayer.protocol();
    if (channel == null) return;
    channel.event(
        "datalayer",
        base(pipe, "data")
            .put("direction", direction)
            .put("bytes", bytes.length)
            .put("preview", DataLayer.preview(bytes))
            .object());
  }

  /**
   * Calls the app's callbacks for [pipe] on the main thread, then its {@code CHANNEL_EVENT}
   * services on the listener thread; [last] forgets the channel's own callbacks afterwards.
   */
  private void deliver(ChannelPipes.Pipe pipe, boolean last, Call call) {
    SimChannel channel = channel(pipe);
    Uri uri = Uri.parse(DataLayer.wearUri(pipe.nodeId, pipe.path));
    node.onMain(
        () -> {
          for (ChannelClient.ChannelCallback c : callbacks) call.callback(c, channel);
          List<ChannelClient.ChannelCallback> own =
              last
                  ? channelCallbacks.remove(pipe.token)
                  : channelCallbacks.get(pipe.token);
          if (own != null) for (ChannelClient.ChannelCallback c : own) call.callback(c, channel);
          List<Service> services = node.services(ACTION_CHANNEL_EVENT, uri);
          node.onListenerThread(
              () -> {
                for (Service service : services) {
                  if (service instanceof WearableListenerService) {
                    call.service((WearableListenerService) service, channel);
                  }
                }
              });
          if (last) channels.remove(pipe.token);
        });
  }

  private void report(
      ChannelPipes.Pipe pipe, String state, String direction, Integer reason, Integer errorCode) {
    DataLayer.log(
        "channel "
            + pipe.token
            + " "
            + pipe.path
            + " "
            + state
            + (reason == null ? "" : " reason " + reason + " error " + errorCode));
    Protocol channel = DataLayer.protocol();
    if (channel == null) return;
    Protocol.Json json = base(pipe, state);
    if (direction != null) json.put("direction", direction);
    if (reason != null) json.put("closeReason", reason).put("errorCode", errorCode);
    channel.event("datalayer", json.object());
  }

  private static Protocol.Json base(ChannelPipes.Pipe pipe, String state) {
    return new Protocol.Json()
        .put("kind", "channel")
        .put("state", state)
        .put("channel", pipe.token)
        .put("node", pipe.nodeId)
        .put("path", pipe.path);
  }

  /** The open channels, for {@code dataLayer}. */
  JSONArray describe() {
    JSONArray result = new JSONArray();
    for (ChannelPipes.Pipe pipe : pipes.open()) {
      result.put(
          new Protocol.Json()
              .put("channel", pipe.token)
              .put("node", pipe.nodeId)
              .put("path", pipe.path)
              .put("openedHere", pipe.local)
              .put("unread", pipe.input.available())
              .object());
    }
    return result;
  }

  /** The node a channel token belongs to (its peer), or null for an unknown token. */
  String peerOf(String token) {
    ChannelPipes.Pipe pipe = pipes.get(token);
    return pipe == null ? null : pipe.nodeId;
  }

  // ---------------------------------------------------------------------------------------------

  /** A {@code ChannelClient.Channel} as the app holds it: its token, node and path. */
  static final class SimChannel implements ChannelClient.Channel {
    final String token;
    private final String nodeId;
    private final String path;

    SimChannel(String token, String nodeId, String path) {
      this.token = token;
      this.nodeId = nodeId;
      this.path = path;
    }

    @Override
    public String getNodeId() {
      return nodeId;
    }

    @Override
    public String getPath() {
      return path;
    }

    @Override
    public int describeContents() {
      return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
      out.writeString(token);
      out.writeString(nodeId);
      out.writeString(path);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof SimChannel && ((SimChannel) other).token.equals(token);
    }

    @Override
    public int hashCode() {
      return token.hashCode();
    }

    @Override
    public String toString() {
      return "ChannelImpl{token=" + token + ", nodeId=" + nodeId + ", path=" + path + "}";
    }
  }
}
