package ee.schimke.composeai.simulator;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The byte streams of {@code ChannelClient} channels between this node and its peers, as frames
 * over the data layer's link (the hub, or the agent in {@code --data-layer agent}):
 *
 * <ul>
 *   <li>{@code channelOpen} {@code token path}: the sender opened a channel to the receiver;
 *   <li>{@code channelData} {@code token data}: bytes the sender wrote to its output stream, which
 *       the receiver's input stream reads, in order;
 *   <li>{@code channelEof} {@code token}: the sender closed its output stream; the receiver's input
 *       stream ends;
 *   <li>{@code channelClose} {@code token errorCode}: the sender closed the channel.
 * </ul>
 *
 * A channel's {@code token} is the opener's node id and a counter, the same on both sides. Nothing
 * here names a Play services or Android type, so it is tested on its own; {@link DataLayerChannels}
 * turns its {@link Events} into the app's {@code ChannelCallback} and {@code
 * WearableListenerService} calls.
 */
final class ChannelPipes {

  /** {@code ChannelClient.ChannelCallback}'s close reasons. */
  static final int CLOSE_REASON_NORMAL = 0;

  static final int CLOSE_REASON_DISCONNECTED = 1;
  static final int CLOSE_REASON_REMOTE_CLOSE = 2;
  static final int CLOSE_REASON_LOCAL_CLOSE = 3;

  /** The most bytes one {@code channelData} frame carries; a larger write is split. */
  static final int MAX_FRAME_BYTES = 64 * 1024;

  /** Where this node's frames go. */
  interface Wire {
    void send(JSONObject frame);
  }

  /** What happened to a channel, for the app's callbacks and the control channel. */
  interface Events {
    /** A peer opened [pipe] to this node. */
    void opened(Pipe pipe);

    /**
     * The peer closed its output: [pipe]'s input ends once its bytes are read. The listener calls
     * {@link #inputDone} when the input side counts as closed: at once for a stream, or once {@code
     * receiveFile} has written the file.
     */
    void inputEnded(Pipe pipe);

    /** [pipe]'s input side closed: the peer's output ended, or the channel closed. */
    void inputClosed(Pipe pipe, int reason, int errorCode);

    /** [pipe]'s output side closed: the app closed its stream, or the channel closed. */
    void outputClosed(Pipe pipe, int reason, int errorCode);

    /** [pipe] closed, by this node ({@code LOCAL_CLOSE}), the peer, or a disconnect. */
    void closed(Pipe pipe, int reason, int errorCode);

    /** Bytes crossed: [direction] {@code out} (written here) or {@code in}. */
    void data(Pipe pipe, String direction, byte[] bytes);
  }

  /** One channel, as this node sees it. */
  static final class Pipe {
    final String token;
    final String nodeId;
    final String path;
    /** Whether this node opened it. */
    final boolean local;

    final Input input = new Input(this);
    final Output output;
    private boolean closed;
    private boolean inputSideClosed;
    private boolean outputSideClosed;
    /** {@code receiveFile} reads the input: its end is reported once the file is written. */
    private boolean receivingFile;

    private Pipe(ChannelPipes owner, String token, String nodeId, String path, boolean local) {
      this.token = token;
      this.nodeId = nodeId;
      this.path = path;
      this.local = local;
      this.output = new Output(owner, this);
    }

    synchronized boolean isClosed() {
      return closed;
    }

    @Override
    public String toString() {
      return "Channel{" + token + ", node=" + nodeId + ", path=" + path + "}";
    }
  }

  private final String localId;
  private final Wire wire;
  private final Events events;
  private final AtomicInteger counter = new AtomicInteger();
  private final Map<String, Pipe> pipes = new LinkedHashMap<>();

  ChannelPipes(String localId, Wire wire, Events events) {
    this.localId = localId;
    this.wire = wire;
    this.events = events;
  }

  /** The app's {@code openChannel}: a new channel to [nodeId], announced to it. */
  Pipe open(String nodeId, String path) {
    Pipe pipe = new Pipe(this, localId + "/" + counter.incrementAndGet(), nodeId, path, true);
    synchronized (this) {
      pipes.put(pipe.token, pipe);
    }
    wire.send(put(frame("channelOpen", pipe), "path", path));
    return pipe;
  }

  /** A channel by its token, or null. */
  synchronized Pipe get(String token) {
    return pipes.get(token);
  }

  synchronized List<Pipe> open() {
    return new ArrayList<>(pipes.values());
  }

  /** The app's {@code close}: tells the peer, and closes both sides here. */
  void close(Pipe pipe, int errorCode) {
    if (!markClosed(pipe)) return;
    wire.send(put(frame("channelClose", pipe), "errorCode", errorCode));
    finish(pipe, CLOSE_REASON_LOCAL_CLOSE, errorCode);
  }

  /** Closes every channel to [nodeId], which went away. */
  void disconnected(String nodeId) {
    for (Pipe pipe : open()) {
      if (pipe.nodeId.equals(nodeId) && markClosed(pipe)) {
        finish(pipe, CLOSE_REASON_DISCONNECTED, 0);
      }
    }
  }

  /** {@code receiveFile} takes [pipe]'s input: {@link #settleInputEnd} leaves its end to it. */
  void receivingFile(Pipe pipe) {
    synchronized (pipe) {
      pipe.receivingFile = true;
    }
  }

  /**
   * Decides, after the peer's output ended ({@link Events#inputEnded}), whether the input side is
   * closed now: yes for a stream; for {@code receiveFile}, only when it calls {@link #inputDone}
   * (the file is written) or {@link #receiveFailed}.
   */
  void settleInputEnd(Pipe pipe) {
    synchronized (pipe) {
      if (pipe.receivingFile) return;
    }
    inputDone(pipe);
  }

  /**
   * {@code receiveFile} could not write the file: the channel closes with [errorCode] ({@code
   * LOCAL_CLOSE} here, {@code REMOTE_CLOSE} on the peer) instead of the input closing normally,
   * which apps take as "the file is ready".
   */
  void receiveFailed(Pipe pipe, int errorCode) {
    close(pipe, errorCode);
  }

  /** [pipe]'s input side is closed normally: its stream was read to the end, or its file written. */
  void inputDone(Pipe pipe) {
    if (markInputClosed(pipe)) events.inputClosed(pipe, CLOSE_REASON_NORMAL, 0);
  }

  /** A peer's channel frame; false when [frame] is not one. */
  boolean receive(JSONObject frame) {
    String type = frame.optString("type");
    String from = frame.optString("from");
    String token = frame.optString("token", null);
    if (token == null) return false;
    Pipe pipe;
    switch (type) {
      case "channelOpen":
        pipe = new Pipe(this, token, from, frame.optString("path", "/"), false);
        synchronized (this) {
          if (pipes.containsKey(token)) return true;
          pipes.put(token, pipe);
        }
        events.opened(pipe);
        return true;
      case "channelData":
        pipe = get(token);
        if (pipe == null) return true;
        byte[] bytes = decode(frame.optString("data", ""));
        pipe.input.offer(bytes);
        events.data(pipe, "in", bytes);
        return true;
      case "channelEof":
        pipe = get(token);
        if (pipe == null) return true;
        pipe.input.end(null);
        events.inputEnded(pipe);
        return true;
      case "channelClose":
        pipe = get(token);
        if (pipe == null || !markClosed(pipe)) return true;
        finish(pipe, CLOSE_REASON_REMOTE_CLOSE, frame.optInt("errorCode", 0));
        return true;
      default:
        return false;
    }
  }

  // ---------------------------------------------------------------------------------------------

  private boolean markClosed(Pipe pipe) {
    synchronized (pipe) {
      if (pipe.closed) return false;
      pipe.closed = true;
    }
    synchronized (this) {
      pipes.remove(pipe.token);
    }
    return true;
  }

  private static boolean markInputClosed(Pipe pipe) {
    synchronized (pipe) {
      if (pipe.inputSideClosed) return false;
      pipe.inputSideClosed = true;
      return true;
    }
  }

  private static boolean markOutputClosed(Pipe pipe) {
    synchronized (pipe) {
      if (pipe.outputSideClosed) return false;
      pipe.outputSideClosed = true;
      return true;
    }
  }

  /** Both sides of a channel closed with [reason]: the open ones reported, then the channel. */
  private void finish(Pipe pipe, int reason, int errorCode) {
    pipe.input.end(
        reason == CLOSE_REASON_NORMAL
            ? null
            : new IOException("channel closed (reason " + reason + ", error " + errorCode + ")"));
    if (markInputClosed(pipe)) events.inputClosed(pipe, reason, errorCode);
    if (markOutputClosed(pipe)) events.outputClosed(pipe, reason, errorCode);
    events.closed(pipe, reason, errorCode);
  }

  private void write(Pipe pipe, byte[] bytes, int offset, int length) throws IOException {
    if (pipe.isClosed()) throw new IOException("channel " + pipe.token + " is closed");
    synchronized (pipe) {
      if (pipe.outputSideClosed) throw new IOException("output of " + pipe.token + " is closed");
    }
    for (int start = offset; start < offset + length; start += MAX_FRAME_BYTES) {
      int n = Math.min(MAX_FRAME_BYTES, offset + length - start);
      byte[] chunk = new byte[n];
      System.arraycopy(bytes, start, chunk, 0, n);
      wire.send(put(frame("channelData", pipe), "data", Base64.getEncoder().encodeToString(chunk)));
      events.data(pipe, "out", chunk);
    }
  }

  private void endOutput(Pipe pipe) {
    if (pipe.isClosed() || !markOutputClosed(pipe)) return;
    wire.send(frame("channelEof", pipe));
    events.outputClosed(pipe, CLOSE_REASON_NORMAL, 0);
  }

  private JSONObject frame(String type, Pipe pipe) {
    try {
      return new JSONObject()
          .put("type", type)
          .put("from", localId)
          .put("to", pipe.nodeId)
          .put("token", pipe.token);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  private static JSONObject put(JSONObject object, String key, Object value) {
    try {
      return object.put(key, value);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] decode(String text) {
    return text.isEmpty() ? new byte[0] : Base64.getDecoder().decode(text);
  }

  // ---------------------------------------------------------------------------------------------

  /** A channel's input: the peer's bytes, in order, blocking until more arrive or it ends. */
  static final class Input extends InputStream {
    private final Pipe pipe;
    private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
    private byte[] current;
    private int position;
    private boolean ended;
    private IOException failure;
    private boolean closedByApp;

    Input(Pipe pipe) {
      this.pipe = pipe;
    }

    synchronized void offer(byte[] bytes) {
      if (ended || closedByApp || bytes.length == 0) return;
      chunks.add(bytes);
      notifyAll();
    }

    /** No more bytes; [error] makes a read that finds none throw it instead of answering -1. */
    synchronized void end(IOException error) {
      if (ended) return;
      ended = true;
      failure = error;
      notifyAll();
    }

    /** The bytes waiting to be read, for tests and {@code available()}. */
    @Override
    public synchronized int available() {
      int n = current == null ? 0 : current.length - position;
      for (byte[] chunk : chunks) n += chunk.length;
      return n;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      int n = read(one, 0, 1);
      return n < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public synchronized int read(byte[] buffer, int offset, int length) throws IOException {
      if (length == 0) return 0;
      while (true) {
        if (closedByApp) throw new IOException("input of " + pipe.token + " is closed");
        if (current != null && position < current.length) {
          int n = Math.min(length, current.length - position);
          System.arraycopy(current, position, buffer, offset, n);
          position += n;
          return n;
        }
        current = chunks.poll();
        position = 0;
        if (current != null) continue;
        if (ended) {
          if (failure != null) throw failure;
          return -1;
        }
        try {
          wait();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new InterruptedIOException("interrupted reading " + pipe.token);
        }
      }
    }

    @Override
    public synchronized void close() {
      closedByApp = true;
      chunks.clear();
      current = null;
      notifyAll();
    }
  }

  /** A channel's output: each write is a {@code channelData} frame; close ends the peer's input. */
  static final class Output extends OutputStream {
    private final ChannelPipes owner;
    private final Pipe pipe;

    Output(ChannelPipes owner, Pipe pipe) {
      this.owner = owner;
      this.pipe = pipe;
    }

    @Override
    public void write(int b) throws IOException {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      if (length == 0) return;
      owner.write(pipe, bytes, offset, length);
    }

    @Override
    public void close() {
      owner.endOutput(pipe);
    }
  }
}
