package ee.schimke.composeai.simulator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * The paired simulator's wire to the CLI's data layer hub: frames of one JSON object each, as a
 * big-endian length and that many UTF-8 bytes, both ways. The hub relays every frame to the other
 * simulators; {@link DataLayer} says what a frame means. No Play services types here, so the link
 * is plain JVM code that never touches the app's classes.
 */
final class DataLayerLink {

  /** The localhost port of the CLI's hub; set only for a paired launch. */
  static final String HUB_PROPERTY = "composeai.simulator.datalayer.hub";
  /**
   * Set (to {@code true}) for {@code launch --data-layer agent}: no hub and no second simulator;
   * the control channel plays every peer ({@link DataLayer#agentCommand}).
   */
  static final String AGENT_PROPERTY = "composeai.simulator.datalayer.agent";

  /** How long an app's {@code sendRequest} waits for an answer, in ms; 0 waits forever. */
  static final String TIMEOUT_PROPERTY = "composeai.simulator.datalayer.requestTimeoutMs";

  /** This node's id, as {@code Node.getId()} reports it to both apps. */
  static final String NODE_PROPERTY = "composeai.simulator.datalayer.node";
  /** This node's display name. */
  static final String NAME_PROPERTY = "composeai.simulator.datalayer.name";

  /**
   * Whether this launch simulates the Data Layer: paired, or with the agent as the peer. Here
   * rather than on {@link DataLayer}, which names the app's Play services classes and so cannot be
   * loaded outside the sandbox ({@link SimulatorSandbox} asks).
   */
  static boolean enabled() {
    return System.getProperty(HUB_PROPERTY) != null || agent();
  }

  /** Whether the control channel, not another simulator, is the peer. */
  static boolean agent() {
    return Boolean.parseBoolean(System.getProperty(AGENT_PROPERTY));
  }

  /** The largest frame either side accepts: a data item with its assets, base64-encoded. */
  static final int MAX_FRAME = 64 * 1024 * 1024;

  private final Socket socket;
  private final DataOutputStream out;

  private DataLayerLink(Socket socket) throws IOException {
    this.socket = socket;
    this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
  }

  /**
   * Connects to the hub on localhost [port] and starts a daemon thread handing each frame that
   * arrives to [onFrame], then [onClose] once the hub goes away.
   */
  static DataLayerLink connect(int port, Consumer<String> onFrame, Runnable onClose)
      throws IOException {
    Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
    socket.setTcpNoDelay(true);
    DataLayerLink link = new DataLayerLink(socket);
    Thread reader =
        new Thread(
            () -> {
              try (DataInputStream in =
                  new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
                while (true) {
                  int length = in.readInt();
                  if (length < 0 || length > MAX_FRAME) {
                    throw new IOException("bad data layer frame length " + length);
                  }
                  byte[] bytes = new byte[length];
                  in.readFully(bytes);
                  onFrame.accept(new String(bytes, StandardCharsets.UTF_8));
                }
              } catch (EOFException e) {
                // The hub closed: the pairing is over.
              } catch (IOException e) {
                System.err.println("datalayer: link failed: " + e);
              } finally {
                onClose.run();
              }
            },
            "simulator-datalayer");
    reader.setDaemon(true);
    reader.start();
    return link;
  }

  /** Sends one frame; any thread. A frame the hub can no longer take is dropped and logged. */
  synchronized void send(String frame) {
    byte[] bytes = frame.getBytes(StandardCharsets.UTF_8);
    try {
      out.writeInt(bytes.length);
      out.write(bytes);
      out.flush();
    } catch (IOException e) {
      System.err.println("datalayer: send failed: " + e);
    }
  }

  void close() {
    try {
      socket.close();
    } catch (IOException e) {
      // Closing anyway.
    }
  }
}
