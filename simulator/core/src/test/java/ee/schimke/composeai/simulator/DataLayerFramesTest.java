package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;

public class DataLayerFramesTest {

  /** Stands in for {@code JSONObject.NULL}, which {@code opt} returns for a JSON null. */
  private static final Object JSON_NULL =
      new Object() {
        @Override
        public String toString() {
          return "null";
        }
      };

  @Test
  public void anEmptyDataItemStaysEmpty() {
    // Horologist's /tile_tracking_enabled: a PutDataRequest with no payload.
    assertNull(DataLayerFrames.bytes(JSON_NULL));
    assertNull(DataLayerFrames.bytes(null));
  }

  @Test
  public void readingTheNullAsTextWasThreeBytes() {
    // What the peer used to see: optString's "null", decoded as Base64.
    assertEquals(3, Base64.getDecoder().decode("null").length);
  }

  @Test
  public void aPayloadRoundTrips() {
    byte[] data = {1, 2, 3, (byte) 0xff};
    assertArrayEquals(data, DataLayerFrames.bytes(Base64.getEncoder().encodeToString(data)));
    assertArrayEquals(new byte[0], DataLayerFrames.bytes(""));
  }

  @Test
  public void onlyAStringIsText() {
    assertEquals("watch-sim", DataLayerFrames.text("watch-sim"));
    assertNull(DataLayerFrames.text(JSON_NULL));
    assertNull(DataLayerFrames.text(null));
  }

  @Test
  public void putTheSameContentsIsNoChange() {
    Map<String, String> none = Collections.emptyMap();
    assertTrue(DataLayerFrames.unchanged(new byte[] {1, 2}, none, new byte[] {1, 2}, none));
    // Horologist's /tile_tracking_enabled, put again each time its listener service starts.
    assertTrue(DataLayerFrames.unchanged(null, none, null, none));
    assertTrue(DataLayerFrames.unchanged(null, none, new byte[0], none));
  }

  @Test
  public void otherBytesOrAssetsAreAChange() {
    Map<String, String> none = Collections.emptyMap();
    assertFalse(DataLayerFrames.unchanged(new byte[] {1}, none, new byte[] {2}, none));
    assertFalse(
        DataLayerFrames.unchanged(
            new byte[] {1}, none, new byte[] {1}, Collections.singletonMap("photo", "abc")));
  }

  @Test
  public void appFramesReachTheSamePackageAndDeviceFramesEveryApp() {
    assertTrue(DataLayerFrames.forApp("message", "com.a", "com.a"));
    assertFalse(DataLayerFrames.forApp("message", "com.b", "com.a"));
    assertFalse(DataLayerFrames.forApp("data", "com.b", "com.a"));
    assertFalse(DataLayerFrames.forApp("channelOpen", "com.b", "com.a"));
    assertTrue(DataLayerFrames.forApp("hello", "com.b", "com.a"));
    assertTrue(DataLayerFrames.forApp("capabilities", "com.b", "com.a"));
    // The hub's own frames, and a peer from before packages were sent.
    assertTrue(DataLayerFrames.forApp("bye", null, "com.a"));
    assertTrue(DataLayerFrames.forApp("message", null, "com.a"));
  }
}
