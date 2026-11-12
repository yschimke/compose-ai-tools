package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

public class DwfWatchFaceClientTest {

  public static final class FakePlayerTarget {
    final Map<String, Integer> ints = new HashMap<>();
    final Map<String, Float> floats = new HashMap<>();
    final Map<String, String> strings = new HashMap<>();

    public void setUserLocalInt(String name, int value) {
      ints.put(name, Integer.valueOf(value));
    }

    public void setUserLocalFloat(String name, float value) {
      floats.put(name, Float.valueOf(value));
    }

    public void setUserLocalString(String name, String value) {
      strings.put(name, value);
    }
  }

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
  public void resetsComplicationVariablesWhenRemovedReplacedOrCleared() throws Exception {
    DwfWatchFaceClient.Entry entry =
        new DwfWatchFaceClient.Entry(
            "com.example/.WatchFace",
            "Complications",
            new File("."),
            "watchface.rc",
            450,
            450,
            "CIRCLE",
            Collections.singletonList(new WatchFaceClient.Slot(1, new int[] {3, 5}, true)),
            Collections.emptyList());
    DwfWatchFaceClient client = new DwfWatchFaceClient(null, entry, null);
    FakePlayerTarget target = new FakePlayerTarget();

    // Untouched slot does not clobber .rc defaults on initial render.
    client.applyVariablesTo(target, false);
    assertEquals(false, target.ints.containsKey("dwf.complication.1.type"));

    // Seed slot 1 with RANGED_VALUE complication.
    DwfWatchFaceClient.FakeComplication ranged =
        DwfWatchFaceClient.FakeComplication.fromJson(
            new JSONObject()
                .put("slot", 1)
                .put("type", "RANGED_VALUE")
                .put("title", "Steps")
                .put("text", "8,400")
                .put("value", 84.0)
                .put("min", 0.0)
                .put("max", 100.0)
                .put("hasMonochromaticImage", true),
            1);
    client.putFakeComplication(1, ranged);
    client.applyVariablesTo(target, false);
    assertEquals(3, target.ints.get("dwf.complication.1.type").intValue());
    assertEquals("8,400", target.strings.get("dwf.complication.1.text"));
    assertEquals("Steps", target.strings.get("dwf.complication.1.title"));
    assertEquals(84f, target.floats.get("dwf.complication.1.value").floatValue(), 0.001f);
    assertEquals(1, target.ints.get("dwf.complication.1.has_monochromatic_image").intValue());

    // Replace slot 1 with SHORT_TEXT omitting title/value/icon: omitted fields reset to empty.
    DwfWatchFaceClient.FakeComplication shortText =
        DwfWatchFaceClient.FakeComplication.fromJson(
            new JSONObject().put("slot", 1).put("type", "SHORT_TEXT").put("text", "72"), 1);
    client.putFakeComplication(1, shortText);
    client.applyVariablesTo(target, false);
    assertEquals(1, target.ints.get("dwf.complication.1.type").intValue());
    assertEquals("72", target.strings.get("dwf.complication.1.text"));
    assertEquals("", target.strings.get("dwf.complication.1.title"));
    assertEquals(0f, target.floats.get("dwf.complication.1.value").floatValue(), 0.001f);
    assertEquals(0, target.ints.get("dwf.complication.1.has_monochromatic_image").intValue());

    // Removing slot 1 resets type to EMPTY (0) and clears text in both plain and USER: namespaces.
    client.putFakeComplication(1, null);
    client.applyVariablesTo(target, false);
    assertEquals(0, target.ints.get("dwf.complication.1.type").intValue());
    assertEquals(0, target.ints.get("USER:dwf.complication.1.type").intValue());
    assertEquals("", target.strings.get("dwf.complication.1.text"));
    assertEquals("", target.strings.get("USER:dwf.complication.1.text"));

    // Clearing all fake complications resets touched slot 2 as well.
    client.putFakeComplication(2, shortText);
    client.applyVariablesTo(target, false);
    assertEquals(1, target.ints.get("dwf.complication.2.type").intValue());
    client.clearFakeComplicationEntries();
    client.applyVariablesTo(target, false);
    assertEquals(0, target.ints.get("dwf.complication.2.type").intValue());
    assertEquals("", target.strings.get("dwf.complication.2.text"));
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
