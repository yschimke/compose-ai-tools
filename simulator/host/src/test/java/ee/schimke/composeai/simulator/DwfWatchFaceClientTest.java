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

  public static final class StubComplicationText {
    private final String value;

    StubComplicationText(String value) {
      this.value = value;
    }

    public CharSequence getTextAt(Object resources, long nowMillis) {
      return value;
    }
  }

  public static final class StubWireComplicationData {
    private final int type;
    private final StubComplicationText shortText;
    private final StubComplicationText shortTitle;
    private final boolean hasRangedValue;
    private final float rangedValue;
    private final boolean hasRangedMin;
    private final float rangedMin;
    private final boolean hasRangedMax;
    private final float rangedMax;
    private final boolean hasTarget;
    private final float target;
    private final boolean hasIcon;

    StubWireComplicationData(
        int type,
        String shortText,
        String shortTitle,
        boolean hasRangedValue,
        float rangedValue,
        boolean hasRangedMin,
        float rangedMin,
        boolean hasRangedMax,
        float rangedMax,
        boolean hasTarget,
        float target,
        boolean hasIcon) {
      this.type = type;
      this.shortText = shortText == null ? null : new StubComplicationText(shortText);
      this.shortTitle = shortTitle == null ? null : new StubComplicationText(shortTitle);
      this.hasRangedValue = hasRangedValue;
      this.rangedValue = rangedValue;
      this.hasRangedMin = hasRangedMin;
      this.rangedMin = rangedMin;
      this.hasRangedMax = hasRangedMax;
      this.rangedMax = rangedMax;
      this.hasTarget = hasTarget;
      this.target = target;
      this.hasIcon = hasIcon;
    }

    public int getType() {
      return type;
    }

    public StubComplicationText getShortText() {
      return shortText;
    }

    public StubComplicationText getShortTitle() {
      return shortTitle;
    }

    public boolean hasRangedValue() {
      return hasRangedValue;
    }

    public float getRangedValue() {
      return rangedValue;
    }

    public boolean hasRangedMinValue() {
      return hasRangedMin;
    }

    public float getRangedMinValue() {
      return rangedMin;
    }

    public boolean hasRangedMaxValue() {
      return hasRangedMax;
    }

    public float getRangedMaxValue() {
      return rangedMax;
    }

    public boolean hasTargetValue() {
      return hasTarget;
    }

    public float getTargetValue() {
      return target;
    }

    public boolean hasIcon() {
      return hasIcon;
    }
  }

  @Test
  public void preservesNumericAndTextFieldsFromBoundWireComplicationData() {
    DwfWatchFaceClient.Entry entry =
        new DwfWatchFaceClient.Entry(
            "com.example/.WatchFace",
            "Complications",
            new File("."),
            "watchface.rc",
            450,
            450,
            "CIRCLE",
            Collections.singletonList(new WatchFaceClient.Slot(1, new int[] {5, 13}, true)),
            Collections.emptyList());
    DwfWatchFaceClient client = new DwfWatchFaceClient(null, entry, null);
    FakePlayerTarget target = new FakePlayerTarget();

    StubWireComplicationData rangedWire =
        new StubWireComplicationData(
            5, "75%", "Battery", true, 75f, true, 10f, true, 200f, false, 0f, true);
    DwfWatchFaceClient.FakeComplication ranged =
        DwfWatchFaceClient.FakeComplication.fromWireData(1, 5, rangedWire, null, 1_000L);
    client.putFakeComplication(1, ranged);
    client.applyVariablesTo(target, false);
    assertEquals(3, target.ints.get("dwf.complication.1.type").intValue());
    assertEquals("75%", target.strings.get("dwf.complication.1.text"));
    assertEquals("Battery", target.strings.get("dwf.complication.1.title"));
    assertEquals(75f, target.floats.get("dwf.complication.1.value").floatValue(), 0.001f);
    assertEquals(10f, target.floats.get("dwf.complication.1.min").floatValue(), 0.001f);
    assertEquals(200f, target.floats.get("dwf.complication.1.max").floatValue(), 0.001f);
    assertEquals(1, target.ints.get("dwf.complication.1.has_monochromatic_image").intValue());

    StubWireComplicationData goalWire =
        new StubWireComplicationData(
            13, "6,400", "Steps", true, 6400f, false, 0f, false, 0f, true, 10000f, false);
    DwfWatchFaceClient.FakeComplication goal =
        DwfWatchFaceClient.FakeComplication.fromWireData(1, 13, goalWire, null, 1_000L);
    client.putFakeComplication(1, goal);
    client.applyVariablesTo(target, false);
    assertEquals(7, target.ints.get("dwf.complication.1.type").intValue());
    assertEquals("6,400", target.strings.get("dwf.complication.1.text"));
    assertEquals("Steps", target.strings.get("dwf.complication.1.title"));
    assertEquals(6400f, target.floats.get("dwf.complication.1.value").floatValue(), 0.001f);
    assertEquals(0f, target.floats.get("dwf.complication.1.min").floatValue(), 0.001f);
    assertEquals(10000f, target.floats.get("dwf.complication.1.max").floatValue(), 0.001f);
    assertEquals(0, target.ints.get("dwf.complication.1.has_monochromatic_image").intValue());
  }

  @Test
  public void injectsWatchStatePlatformWeatherAccelAmbientAndPaletteVariables() throws Exception {
    WatchState state = new WatchState(null, null);
    try {
      state.clockCommand(new JSONObject().put("is24Hour", true));
      state.weatherCommand(
          new JSONObject()
              .put("available", true)
              .put("condition", "RAIN")
              .put("temperature", 19.5)
              .put("temperatureHigh", 23.0)
              .put("temperatureLow", 14.0)
              .put("chanceOfPrecipitation", 70)
              .put("uvIndex", 4)
              .put(
                  "hours",
                  new org.json.JSONArray()
                      .put(new JSONObject().put("condition", "CLOUDY").put("temperature", 20.0)))
              .put(
                  "days",
                  new org.json.JSONArray()
                      .put(
                          new JSONObject()
                              .put("condition", "RAIN")
                              .put("chanceOfPrecipitation", 70))));
      state.accelCommand(new JSONObject().put("x", 15.0).put("y", -8.0));
      state.syncBattery(64, true, false, false);
      state.syncNotifications(3);

      DwfWatchFaceClient.ConfigOption emeraldOpt =
          new DwfWatchFaceClient.ConfigOption(
              "emerald",
              "Emerald",
              List.of(
                  Integer.valueOf(0xFF00C853),
                  Integer.valueOf(0xFF4C8C64),
                  Integer.valueOf(0xFF00B0FF)));
      DwfWatchFaceClient.ConfigSpec themeSetting =
          new DwfWatchFaceClient.ConfigSpec(
              "colorTheme", "COLOR", "Theme", "emerald", List.of(emeraldOpt));

      WatchFaceClient.Slot boundedSlot =
          new WatchFaceClient.Slot(10, new int[] {3, 5}, true, 100, 150, 200, 250);
      DwfWatchFaceClient.Entry entry =
          new DwfWatchFaceClient.Entry(
              "com.example/.WatchFace",
              "WeatherFace",
              new File("."),
              "watchface.rc",
              450,
              450,
              "CIRCLE",
              List.of(boundedSlot),
              List.of(themeSetting));

      DwfWatchFaceClient client = new DwfWatchFaceClient(null, entry, null);
      FakePlayerTarget target = new FakePlayerTarget();
      client.applyVariablesTo(target, true);

      assertEquals(1, target.ints.get("dwf.is_ambient").intValue());
      assertEquals(1, target.ints.get("dwf.is_24_hour").intValue());
      assertEquals(64f, target.floats.get("dwf.battery_percent").floatValue(), 0.01f);
      assertEquals(1, target.ints.get("dwf.battery_charging").intValue());
      assertEquals(3, target.ints.get("dwf.unread_notifications").intValue());
      assertEquals(1, target.ints.get("dwf.weather.is_available").intValue());
      assertEquals(6, target.ints.get("dwf.weather.condition").intValue()); // RAIN -> 6
      assertEquals(19.5f, target.floats.get("dwf.weather.temperature").floatValue(), 0.01f);
      assertEquals(70, target.ints.get("dwf.weather.days.1.chance_of_precipitation").intValue());
      assertEquals(1, target.ints.get("dwf.weather.hours.1.is_available").intValue());
      assertEquals(2, target.ints.get("dwf.weather.hours.1.condition").intValue()); // CLOUDY -> 2
      assertEquals(15f, target.floats.get("dwf.accel_angle_x").floatValue(), 0.01f);
      assertEquals(-8f, target.floats.get("dwf.accel_angle_y").floatValue(), 0.01f);

      WatchFaceDynamicColors.Palette palette = client.extractPalette();
      assertEquals("configuration:colorTheme:emerald", palette.source);
      assertEquals(0xFF00C853, palette.primaryArgb);
      assertEquals(0xFF4C8C64, palette.secondaryArgb);
      assertEquals(0xFF00B0FF, palette.tertiaryArgb);

      client.start(900, 900, false);
      List<WatchFaceClient.Slot> scaled = client.slots();
      assertEquals(1, scaled.size());
      assertTrue(scaled.get(0).hasBounds);
      assertEquals(200, scaled.get(0).left);
      assertEquals(300, scaled.get(0).top);
      assertEquals(400, scaled.get(0).right);
      assertEquals(500, scaled.get(0).bottom);
    } finally {
      WatchState.resetCurrent();
    }
  }

  @Test
  public void defaultSystemProviderSlotsFollowWatchStateUntilTheAgentTakesThemOver()
      throws Exception {
    WatchState state = new WatchState(null, null);
    try {
      state.syncBattery(37, false, false, false);
      state.syncNotifications(5);
      state.setStepGoal(8000f);

      DwfWatchFaceClient.Entry entry =
          new DwfWatchFaceClient.Entry(
              "com.example/.WatchFace",
              "ProviderFace",
              new File("."),
              "watchface.rc",
              450,
              450,
              "CIRCLE",
              List.of(
                  new WatchFaceClient.Slot(1, new int[] {3, 5}, true),
                  new WatchFaceClient.Slot(2, new int[] {3, 5}, true),
                  new WatchFaceClient.Slot(3, new int[] {3}, true)),
              List.of(),
              Map.of(
                  Integer.valueOf(1), "STEP_COUNT",
                  Integer.valueOf(2), "WATCH_BATTERY",
                  Integer.valueOf(3), "UNREAD_NOTIFICATION_COUNT"));

      DwfWatchFaceClient client = new DwfWatchFaceClient(null, entry, null);
      FakePlayerTarget target = new FakePlayerTarget();
      client.applyVariablesTo(target, false);

      assertEquals(Integer.toString(state.steps()), target.strings.get("dwf.complication.1.text"));
      assertEquals(8000f, target.floats.get("dwf.complication.1.max").floatValue(), 0.01f);
      assertEquals("37%", target.strings.get("dwf.complication.2.text"));
      assertEquals(37f, target.floats.get("dwf.complication.2.value").floatValue(), 0.01f);
      assertEquals("5", target.strings.get("dwf.complication.3.text"));

      DwfWatchFaceClient.FakeComplication fake =
          DwfWatchFaceClient.FakeComplication.fromJson(
              new JSONObject().put("type", "SHORT_TEXT").put("text", "Agent"), 1);
      client.putFakeComplication(1, fake);
      target = new FakePlayerTarget();
      client.applyVariablesTo(target, false);
      assertEquals("Agent", target.strings.get("dwf.complication.1.text"));
      assertEquals("37%", target.strings.get("dwf.complication.2.text"));
    } finally {
      WatchState.resetCurrent();
    }
  }
}
