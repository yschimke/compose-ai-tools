package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class DwfWatchFaceClientTest {

  @Test
  public void parsesFakeComplicationJsonAndMapsWireAndDwfTypes() throws Exception {
    JSONObject json =
        new JSONObject()
            .put("slot", 4)
            .put("type", "LONG_TEXT")
            .put("title", "Long Title")
            .put("text", "Text w/ Icon & Title")
            .put("value", 60.0)
            .put("min", 0.0)
            .put("max", 100.0)
            .put("hasMonochromaticImage", true);

    DwfWatchFaceClient.FakeComplication fake =
        DwfWatchFaceClient.FakeComplication.fromJson(json, 0);
    assertEquals(4, fake.slot);
    assertEquals(4, fake.wireType);
    assertEquals(2, fake.dwfTypeCode);
    assertEquals("Long Title", fake.title);
    assertEquals("Text w/ Icon & Title", fake.text);
    assertEquals(60f, fake.value.floatValue(), 0.001f);
    assertTrue(fake.hasMonochromaticImage.booleanValue());

    assertEquals(1, DwfWatchFaceClient.wireTypeToDwfTypeCode(3));
    assertEquals(3, DwfWatchFaceClient.wireTypeToDwfTypeCode(5));
    assertEquals(7, DwfWatchFaceClient.wireTypeToDwfTypeCode(13));
    assertEquals(8, DwfWatchFaceClient.wireTypeToDwfTypeCode(14));
  }

  @Test
  public void loadsCompiledManifestWithIntegerColorPalettes() throws Exception {
    File tmpDir = Files.createTempDirectory("dwf-manifest-test-").toFile();
    String prev = System.getProperty(DwfWatchFaceClient.DWF_DIR_PROPERTY);
    try {
      String manifest =
          "{\n"
              + "  \"watchFaces\": [\n"
              + "    {\n"
              + "      \"id\": \"com.example/.WatchFace\",\n"
              + "      \"label\": \"Flavors\",\n"
              + "      \"rcFile\": \"watchface.rc\",\n"
              + "      \"width\": 450,\n"
              + "      \"height\": 450,\n"
              + "      \"clipShape\": \"CIRCLE\",\n"
              + "      \"slots\": [{\"id\": 0, \"supportedTypes\": [3, 5], \"enabled\": true}],\n"
              + "      \"configurations\": [\n"
              + "        {\n"
              + "          \"id\": \"themeColor\",\n"
              + "          \"kind\": \"COLOR\",\n"
              + "          \"displayName\": \"Theme\",\n"
              + "          \"defaultValue\": \"2\",\n"
              + "          \"options\": [\n"
              + "            {\"id\": \"2\", \"displayName\": \"Rose\", \"colors\": [-2729088, \"#FFEE9480\"]}\n"
              + "          ]\n"
              + "        }\n"
              + "      ]\n"
              + "    }\n"
              + "  ]\n"
              + "}\n";
      Files.writeString(
          new File(tmpDir, "compiled-manifest.json").toPath(), manifest, StandardCharsets.UTF_8);
      System.setProperty(DwfWatchFaceClient.DWF_DIR_PROPERTY, tmpDir.getAbsolutePath());

      List<DwfWatchFaceClient.Entry> entries = DwfWatchFaceClient.availableFromProperty();
      assertEquals(1, entries.size());
      DwfWatchFaceClient.Entry entry = entries.get(0);
      assertEquals(1, entry.configurations.size());
      DwfWatchFaceClient.ConfigOption opt = entry.configurations.get(0).options.get(0);
      assertEquals(2, opt.colorArgbs.size());
      assertEquals(0xFFD65B80, opt.colorArgbs.get(0).intValue());
      assertEquals(0xFFEE9480, opt.colorArgbs.get(1).intValue());
    } finally {
      if (prev == null) {
        System.clearProperty(DwfWatchFaceClient.DWF_DIR_PROPERTY);
      } else {
        System.setProperty(DwfWatchFaceClient.DWF_DIR_PROPERTY, prev);
      }
      for (File f : tmpDir.listFiles()) f.delete();
      tmpDir.delete();
    }
  }
}
