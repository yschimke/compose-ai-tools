package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;

/** Two nodes' channels joined by a wire that hands each frame straight to the other. */
public class ChannelPipesTest {

  /** Every event a node reported, as text. */
  static final class Recorder implements ChannelPipes.Events {
    final List<String> events = new ArrayList<>();
    final AtomicReference<ChannelPipes.Pipe> lastOpened = new AtomicReference<>();
    /** The node's main looper: what {@code inputEnded} posts, run by {@link #idle}. */
    final List<Runnable> looper = new ArrayList<>();
    /** Whether {@code inputEnded} settles at once (a looper that is always idle). */
    boolean immediate = true;
    ChannelPipes pipes;

    @Override
    public void inputEnded(ChannelPipes.Pipe pipe) {
      Runnable settle = () -> pipes.settleInputEnd(pipe);
      if (immediate) settle.run();
      else looper.add(settle);
    }

    void idle() {
      List<Runnable> queued = new ArrayList<>(looper);
      looper.clear();
      for (Runnable r : queued) r.run();
    }

    @Override
    public synchronized void opened(ChannelPipes.Pipe pipe) {
      lastOpened.set(pipe);
      events.add("opened " + pipe.path + " from " + pipe.nodeId);
    }

    @Override
    public synchronized void inputClosed(ChannelPipes.Pipe pipe, int reason, int errorCode) {
      events.add("inputClosed " + reason + "/" + errorCode);
    }

    @Override
    public synchronized void outputClosed(ChannelPipes.Pipe pipe, int reason, int errorCode) {
      events.add("outputClosed " + reason + "/" + errorCode);
    }

    @Override
    public synchronized void closed(ChannelPipes.Pipe pipe, int reason, int errorCode) {
      events.add("closed " + reason + "/" + errorCode);
    }

    @Override
    public synchronized void data(ChannelPipes.Pipe pipe, String direction, byte[] bytes) {
      events.add("data " + direction + " " + bytes.length);
    }

    synchronized List<String> take() {
      List<String> copy = new ArrayList<>(events);
      events.clear();
      return copy;
    }
  }

  private final Recorder phoneEvents = new Recorder();
  private final Recorder watchEvents = new Recorder();
  private final List<JSONObject> frames = new ArrayList<>();
  private ChannelPipes phone;
  private ChannelPipes watch;

  {
    phone =
        new ChannelPipes(
            "phone-sim",
            frame -> {
              frames.add(frame);
              watch.receive(frame);
            },
            phoneEvents);
    watch =
        new ChannelPipes(
            "watch-sim",
            frame -> {
              frames.add(frame);
              phone.receive(frame);
            },
            watchEvents);
    phoneEvents.pipes = phone;
    watchEvents.pipes = watch;
  }

  private static String readAll(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[7];
    for (int n; (n = in.read(buffer)) >= 0; ) out.write(buffer, 0, n);
    return out.toString(StandardCharsets.UTF_8);
  }

  @Test
  public void openingAnnouncesTheChannelToThePeerWithTheSameToken() {
    ChannelPipes.Pipe opened = phone.open("watch-sim", "/sync/log");
    ChannelPipes.Pipe remote = watchEvents.lastOpened.get();
    assertNotNull(remote);
    assertEquals(opened.token, remote.token);
    assertEquals("phone-sim", remote.nodeId);
    assertEquals("/sync/log", remote.path);
    assertEquals(List.of("opened /sync/log from phone-sim"), watchEvents.take());
    assertEquals("channelOpen", frames.get(0).optString("type"));
    assertEquals("watch-sim", frames.get(0).optString("to"));
  }

  @Test
  public void bytesWrittenOnOneSideAreReadInOrderOnTheOtherUntilTheOutputCloses()
      throws IOException {
    ChannelPipes.Pipe opened = phone.open("watch-sim", "/p");
    ChannelPipes.Pipe remote = watch.get(opened.token);
    opened.output.write("hello ".getBytes(StandardCharsets.UTF_8));
    opened.output.write("channel".getBytes(StandardCharsets.UTF_8));
    opened.output.close();
    assertEquals("hello channel", readAll(remote.input));
    assertEquals(List.of("data out 6", "data out 7", "outputClosed 0/0"), phoneEvents.take());
    assertEquals(
        List.of("opened /p from phone-sim", "data in 6", "data in 7", "inputClosed 0/0"),
        watchEvents.take());
  }

  @Test
  public void bothDirectionsCarryBytes() throws IOException {
    ChannelPipes.Pipe opened = phone.open("watch-sim", "/p");
    ChannelPipes.Pipe remote = watch.get(opened.token);
    remote.output.write(new byte[] {1, 2, 3});
    remote.output.close();
    byte[] read = new byte[3];
    assertEquals(3, opened.input.read(read));
    assertArrayEquals(new byte[] {1, 2, 3}, read);
    assertEquals(-1, opened.input.read());
  }

  @Test
  public void aLargeWriteIsSplitIntoFrames() throws IOException {
    ChannelPipes.Pipe opened = phone.open("watch-sim", "/big");
    ChannelPipes.Pipe remote = watch.get(opened.token);
    byte[] big = new byte[ChannelPipes.MAX_FRAME_BYTES * 2 + 5];
    for (int i = 0; i < big.length; i++) big[i] = (byte) i;
    opened.output.write(big);
    opened.output.close();
    assertEquals(big.length, remote.input.available());
    ByteArrayOutputStream got = new ByteArrayOutputStream();
    remote.input.transferTo(got);
    assertArrayEquals(big, got.toByteArray());
    long dataFrames = frames.stream().filter(f -> f.optString("type").equals("channelData")).count();
    assertEquals(3, dataFrames);
  }

  @Test
  public void readBlocksUntilThePeerWrites() throws Exception {
    ChannelPipes.Pipe opened = phone.open("watch-sim", "/p");
    ChannelPipes.Pipe remote = watch.get(opened.token);
    AtomicReference<String> read = new AtomicReference<>();
    Thread reader =
        new Thread(
            () -> {
              try {
                read.set(readAll(remote.input));
              } catch (IOException e) {
                read.set("failed " + e);
              }
            });
    reader.start();
    Thread.sleep(50);
    assertNull(read.get());
    opened.output.write("late".getBytes(StandardCharsets.UTF_8));
    opened.output.close();
    reader.join(5_000);
    assertEquals("late", read.get());
  }

  @Test
  public void closingReportsLocalCloseHereAndRemoteCloseOnThePeer() {
    ChannelPipes.Pipe opened = phone.open("watch-sim", "/p");
    watchEvents.take();
    phone.close(opened, 7);
    assertEquals(List.of("inputClosed 3/7", "outputClosed 3/7", "closed 3/7"), phoneEvents.take());
    assertEquals(List.of("inputClosed 2/7", "outputClosed 2/7", "closed 2/7"), watchEvents.take());
    assertNull(phone.get(opened.token));
    assertNull(watch.get(opened.token));
    assertThrows(IOException.class, () -> opened.output.write(1));
  }

  @Test
  public void closingAfterTheStreamsEndedReportsOnlyTheChannel() throws IOException {
    ChannelPipes.Pipe opened = phone.open("watch-sim", "/p");
    ChannelPipes.Pipe remote = watch.get(opened.token);
    opened.output.close();
    remote.output.close();
    phoneEvents.take();
    watchEvents.take();
    watch.close(remote, 0);
    assertEquals(List.of("closed 3/0"), watchEvents.take());
    assertEquals(List.of("closed 2/0"), phoneEvents.take());
  }

  @Test
  public void aDisconnectClosesTheChannelsToThatNodeAndFailsTheirReads() {
    ChannelPipes.Pipe opened = phone.open("watch-sim", "/p");
    phoneEvents.take();
    phone.disconnected("watch-sim");
    assertEquals(List.of("inputClosed 1/0", "outputClosed 1/0", "closed 1/0"), phoneEvents.take());
    assertThrows(IOException.class, () -> opened.input.read());
    assertTrue(phone.open().isEmpty());
  }

  @Test
  public void receiveFileReportsTheInputClosedWhenItIsDoneNotAtTheEnd() throws IOException {
    ChannelPipes.Pipe opened = phone.open("watch-sim", "/file");
    ChannelPipes.Pipe remote = watch.get(opened.token);
    watch.receivingFile(remote);
    watchEvents.take();
    opened.output.write(new byte[] {9});
    opened.output.close();
    assertEquals(List.of("data in 1"), watchEvents.take());
    readAll(remote.input);
    watch.inputDone(remote);
    assertEquals(List.of("inputClosed 0/0"), watchEvents.take());
  }

  /**
   * The peer's bytes and their end arrive before the app's {@code onChannelOpened} has run (and
   * called {@code receiveFile}): the end is only settled on the looper, after it, so {@code
   * onInputClosed} still waits for the file.
   */
  @Test
  public void anEndThatArrivesBeforeReceiveFileIsCalledStillWaitsForTheFile() throws IOException {
    watchEvents.immediate = false;
    ChannelPipes.Pipe opened = phone.open("watch-sim", "/file");
    opened.output.write(new byte[] {1, 2, 3});
    opened.output.close();
    ChannelPipes.Pipe remote = watch.get(opened.token);
    // onChannelOpened runs on the looper first, and the app calls receiveFile there.
    watch.receivingFile(remote);
    watchEvents.idle();
    assertEquals(List.of("opened /file from phone-sim", "data in 3"), watchEvents.take());
    readAll(remote.input);
    watch.inputDone(remote);
    assertEquals(List.of("inputClosed 0/0"), watchEvents.take());
  }

  @Test
  public void aStreamsEndIsSettledOnTheLooper() throws IOException {
    watchEvents.immediate = false;
    ChannelPipes.Pipe opened = phone.open("watch-sim", "/p");
    opened.output.close();
    watchEvents.take();
    watchEvents.idle();
    assertEquals(List.of("inputClosed 0/0"), watchEvents.take());
  }

  /** A file that could not be written closes the channel with the error, never a normal end. */
  @Test
  public void aFailedReceiveFileClosesWithAnErrorInsteadOfANormalEnd() throws IOException {
    ChannelPipes.Pipe opened = phone.open("watch-sim", "/file");
    ChannelPipes.Pipe remote = watch.get(opened.token);
    watch.receivingFile(remote);
    opened.output.write(new byte[] {1});
    opened.output.close();
    watchEvents.take();
    phoneEvents.take();
    watch.receiveFailed(remote, 8);
    assertEquals(List.of("inputClosed 3/8", "outputClosed 3/8", "closed 3/8"), watchEvents.take());
    assertEquals(List.of("inputClosed 2/8", "closed 2/8"), phoneEvents.take());
    // Nothing reports a normal end afterwards.
    watch.inputDone(remote);
    assertTrue(watchEvents.take().isEmpty());
  }

  @Test
  public void framesForOtherKindsAreNotChannelFrames() throws Exception {
    assertEquals(false, phone.receive(new JSONObject().put("type", "message").put("path", "/x")));
  }
}
