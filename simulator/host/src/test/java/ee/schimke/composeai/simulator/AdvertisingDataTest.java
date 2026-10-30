package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;

public class AdvertisingDataTest {

  @Test
  public void buildsAdStructures() throws Exception {
    JSONObject spec =
        new JSONObject(
            "{\"name\":\"Mesh\",\"serviceUuids\":[\"180D\"],"
                + "\"manufacturerData\":{\"0x004C\":\"0102\"}}");
    byte[] bytes = AdvertisingData.build(spec);
    assertEquals(
        "020106" // flags
            + "03030d18" // 16-bit UUID 0x180D, little-endian
            + "05ff4c000102" // manufacturer 0x004C
            + "05094d657368", // name
        AdvertisingData.hex(bytes));
  }

  @Test
  public void longUuidsAreLittleEndian() throws Exception {
    JSONObject spec =
        new JSONObject("{\"serviceUuids\":[\"6e400001-b5a3-f393-e0a9-e50e24dcca9e\"]}");
    String hex = AdvertisingData.hex(AdvertisingData.build(spec));
    assertEquals("020106" + "1107" + "9ecadc240ee5a9e093f3a3b5010040" + "6e", hex);
  }

  @Test
  public void shortUuidsAndBytes() throws Exception {
    assertEquals(
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), AdvertisingData.uuid("2902"));
    assertArrayEquals(new byte[] {1, (byte) 0xab}, AdvertisingData.bytes("01:AB"));
    assertArrayEquals(
        "hi".getBytes("UTF-8"), AdvertisingData.bytes(new JSONObject("{\"text\":\"hi\"}")));
  }
}
