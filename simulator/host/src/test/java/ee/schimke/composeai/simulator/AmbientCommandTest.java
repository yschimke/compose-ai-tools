package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** Which screen state an {@code ambient} control command asks for. */
public class AmbientCommandTest {

  @Test
  public void interactiveStateStaysInteractiveOnEveryRepeat() throws Exception {
    JSONObject c = new JSONObject().put("state", "INTERACTIVE");
    // Repeating the same command from either current state must never flip to ambient.
    assertFalse(ScreenPower.requestedAmbient(c, false));
    assertFalse(ScreenPower.requestedAmbient(c, true));
  }

  @Test
  public void ambientStateStaysAmbientOnEveryRepeat() throws Exception {
    JSONObject c = new JSONObject().put("state", "AMBIENT");
    assertTrue(ScreenPower.requestedAmbient(c, false));
    assertTrue(ScreenPower.requestedAmbient(c, true));
  }

  @Test
  public void valueNamesTheTarget() throws Exception {
    assertTrue(ScreenPower.requestedAmbient(new JSONObject().put("value", true), true));
    assertFalse(ScreenPower.requestedAmbient(new JSONObject().put("value", false), false));
  }

  @Test
  public void withNeitherItToggles() throws Exception {
    assertTrue(ScreenPower.requestedAmbient(new JSONObject(), false));
    assertFalse(ScreenPower.requestedAmbient(new JSONObject(), true));
  }
}
