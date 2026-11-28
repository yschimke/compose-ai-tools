package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class IntentsRobolectricTest {

  @Test
  public void nullIntentSerializesAsEmptyObject() {
    JSONObject json = Intents.toJson(null);
    assertEquals(0, json.length());
  }

  @Test
  public void recordApplicationClassIsNoOpOutsideProbe() {
    TestRunnerApplication.recordApplicationClass(Object.class);
    TestRunnerApplication.recordApplicationClass(null);
  }
}
