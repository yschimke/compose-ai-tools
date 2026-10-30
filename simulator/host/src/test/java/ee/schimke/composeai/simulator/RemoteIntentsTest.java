package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import ee.schimke.composeai.simulator.RemoteIntents.Outcome;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class RemoteIntentsTest {

  @Test
  public void aNamedNodeIsTheTargetOnlyWhileConnected() {
    assertEquals("watch-sim", RemoteIntents.target("watch-sim", Arrays.asList("watch-sim")));
    assertNull(RemoteIntents.target("gone", Arrays.asList("watch-sim")));
  }

  @Test
  public void noNodeMeansTheConnectedPeer() {
    // A watch's RemoteActivityHelper names no node: the Wear OS app sends it to the phone.
    assertEquals("phone-sim", RemoteIntents.target(null, Arrays.asList("phone-sim")));
    assertNull(RemoteIntents.target(null, Collections.emptyList()));
  }

  @Test
  public void anActivityOfTheAppStartsThere() {
    assertEquals(Outcome.START, RemoteIntents.outcome(true, "probe", "app.pkg", "app.pkg"));
    assertEquals(Outcome.START, RemoteIntents.outcome(true, "https", null, "app.pkg"));
  }

  @Test
  public void anotherPackagesIntentFails() {
    // The peer app is not installed on that node, even if this app could take the intent.
    assertEquals(Outcome.FAIL, RemoteIntents.outcome(true, "probe", "other.pkg", "app.pkg"));
    assertEquals(Outcome.FAIL, RemoteIntents.outcome(false, "https", "other.pkg", "app.pkg"));
  }

  @Test
  public void webAndStoreLinksWouldOpenInTheSystemsApps() {
    assertEquals(Outcome.SYSTEM, RemoteIntents.outcome(false, "https", null, "app.pkg"));
    assertEquals(Outcome.SYSTEM, RemoteIntents.outcome(false, "market", null, "app.pkg"));
    assertEquals(Outcome.FAIL, RemoteIntents.outcome(false, "probe", null, "app.pkg"));
    assertEquals(Outcome.FAIL, RemoteIntents.outcome(false, null, null, "app.pkg"));
  }
}
