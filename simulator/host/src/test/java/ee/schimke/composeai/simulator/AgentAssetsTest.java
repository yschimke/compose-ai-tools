package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class AgentAssetsTest {

  @Test
  public void assetsAreGivenByKeyAsBase64_theShapePutTakes() {
    Map<String, String> keys = new LinkedHashMap<>();
    keys.put("icon", "d1");
    keys.put("photo", "d2");
    Map<String, String> bytes = new LinkedHashMap<>();
    bytes.put("d1", "AAEC");
    bytes.put("d2", "AwQF");
    Map<String, Integer> omitted = new LinkedHashMap<>();
    Map<String, String> out = AgentAssets.replayable(keys, bytes, 100, omitted);
    assertEquals("AAEC", out.get("icon"));
    assertEquals("AwQF", out.get("photo"));
    assertTrue(omitted.isEmpty());
  }

  @Test
  public void tooLargeOrUnknownAssetsAreNamedNotSent() {
    Map<String, String> keys = new LinkedHashMap<>();
    keys.put("big", "d1");
    keys.put("missing", "d2");
    Map<String, String> bytes = new LinkedHashMap<>();
    bytes.put("d1", "AAAAAAAA");
    Map<String, Integer> omitted = new LinkedHashMap<>();
    Map<String, String> out = AgentAssets.replayable(keys, bytes, 4, omitted);
    assertTrue(out.isEmpty());
    assertEquals(Integer.valueOf(8), omitted.get("big"));
    assertEquals(Integer.valueOf(-1), omitted.get("missing"));
  }
}
