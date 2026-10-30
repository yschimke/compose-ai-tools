package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class PeerPresenceTest {

  @Before
  @After
  public void reset() {
    PeerPresence.reset();
  }

  @Test
  public void listenersHearTheFirstConnectAndTheLastDisconnectOnly() {
    List<Boolean> heard = new ArrayList<>();
    PeerPresence.addListener(() -> heard.add(PeerPresence.connected()));
    assertFalse(PeerPresence.connected());
    PeerPresence.peerConnected("watch-a");
    PeerPresence.peerConnected("watch-b");
    PeerPresence.peerDisconnected("watch-a");
    assertTrue(PeerPresence.connected());
    PeerPresence.peerDisconnected("watch-b");
    PeerPresence.peerDisconnected("watch-b");
    assertEquals(List.of(true, false), heard);
  }

  @Test
  public void aRemovedListenerHearsNothing() {
    List<Boolean> heard = new ArrayList<>();
    Runnable listener = () -> heard.add(PeerPresence.connected());
    PeerPresence.addListener(listener);
    PeerPresence.removeListener(listener);
    PeerPresence.peerConnected("phone");
    assertTrue(heard.isEmpty());
  }
}
