package ee.schimke.composeai.simulator.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

public class NetworkGateTest {

  @BeforeClass
  public static void install() {
    List<String> seams = NetworkGate.install();
    assertTrue(seams.toString(), seams.contains("sockets"));
  }

  @After
  public void online() {
    NetworkGate.configure(true, 0, 0, Collections.emptyList());
  }

  @Test
  public void offlineRefusesNamesAndAddressesButNotTheMachineItself() throws IOException {
    NetworkGate.configure(false, 0, 0, Collections.emptyList());
    IOException byName =
        NetworkGate.refusal(
            "example.com",
            new InetSocketAddress(InetAddress.getByAddress(new byte[] {93, 1, 2, 3}), 443));
    assertTrue(byName instanceof UnknownHostException);
    assertEquals(
        "Unable to resolve host \"example.com\": No address associated with hostname",
        byName.getMessage());
    IOException byAddress =
        NetworkGate.refusal(
            "93.1.2.3",
            new InetSocketAddress(InetAddress.getByAddress(new byte[] {93, 1, 2, 3}), 443));
    assertTrue(byAddress instanceof ConnectException);
    assertTrue(byAddress.getMessage(), byAddress.getMessage().contains("ENETUNREACH"));
    assertNull(
        NetworkGate.refusal(
            "127.0.0.1", new InetSocketAddress(InetAddress.getLoopbackAddress(), 8080)));
    assertTrue(NetworkGate.unreachable("example.com"));
    assertTrue(!NetworkGate.unreachable("localhost"));
  }

  @Test
  public void blockedHostsAndWildcards() {
    NetworkGate.configure(true, 0, 0, List.of("ads.example.com", "*.tracker.net"));
    assertTrue(NetworkGate.isBlocked("ADS.example.com"));
    assertTrue(NetworkGate.isBlocked("a.b.tracker.net"));
    assertTrue(NetworkGate.isBlocked("tracker.net"));
    assertTrue(!NetworkGate.isBlocked("example.com"));
    assertTrue(!NetworkGate.unreachable("example.com"));
  }

  @Test
  public void aBlockedAddressRefusesTheSocketAndCutsOneOpen() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Thread echo = echo(server);
      Socket socket = new Socket();
      socket.connect(
          new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()));
      socket.getOutputStream().write(1);
      assertEquals(1, socket.getInputStream().read());

      NetworkGate.configure(true, 0, 0, List.of("127.0.0.1"));
      try {
        socket.getOutputStream().write(2);
        socket.getInputStream().read();
        fail("an open connection to a blocked host is cut");
      } catch (IOException expected) {
        // Cut.
      }
      try (Socket again = new Socket()) {
        again.connect(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()));
        fail("connected to a blocked address");
      } catch (ConnectException expected) {
        assertTrue(expected.getMessage().contains("ENETUNREACH"));
      }
      socket.close();
      echo.interrupt();
    }
  }

  @Test
  public void aBandwidthCapSlowsTheStreams() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Thread echo = echo(server);
      // 80 kbps: 10 kB a second, so 4 kB each way takes about 0.8 s.
      NetworkGate.configure(true, 100, 80, Collections.emptyList());
      long start = System.nanoTime();
      try (Socket socket = new Socket()) {
        socket.connect(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()));
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(new byte[4000]);
        int total = 0;
        byte[] buffer = new byte[1024];
        while (total < 4000) total += in.read(buffer);
      }
      long ms = (System.nanoTime() - start) / 1_000_000;
      assertTrue("took " + ms + " ms", ms >= 700);
      echo.interrupt();
    }
  }

  private static Thread echo(ServerSocket server) {
    Thread thread =
        new Thread(
            () -> {
              try (Socket client = server.accept()) {
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();
                byte[] buffer = new byte[1024];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
              } catch (IOException e) {
                // Done.
              }
            });
    thread.setDaemon(true);
    thread.start();
    return thread;
  }
}
