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

  @Test
  public void flavorsApplyPresetConfigurationsComplicationsAndPaletteAndCustomFontsResolve()
      throws Exception {
    File tmpDir = Files.createTempDirectory("dwf-flavors-font-test-").toFile();
    WatchState state = new WatchState(null, null);
    try {
      state.clockCommand(new JSONObject().put("iso", "2026-06-15T10:08:30Z"));
      state.syncBattery(82, false, false, false);

      File fontDir = new File(tmpDir, "res/font");
      assertTrue(fontDir.mkdirs());
      File digital7 = new File(fontDir, "digital7.ttf");
      Files.writeString(digital7.toPath(), "stub-ttf", StandardCharsets.UTF_8);
      File xmlAlias = new File(fontDir, "casio_family.xml");
      Files.writeString(
          xmlAlias.toPath(),
          "<font-family><font android:font=\"@font/digital7\"/></font-family>",
          StandardCharsets.UTF_8);

      assertEquals(digital7, DwfWatchFaceClient.findCustomFontFile(tmpDir, "@font/digital7", 0));
      assertEquals(digital7, DwfWatchFaceClient.findCustomFontFile(tmpDir, "Digital7", 0));
      assertEquals(digital7, DwfWatchFaceClient.findCustomFontFile(tmpDir, "casio_family", 0));
      assertEquals(null, DwfWatchFaceClient.findCustomFontFile(tmpDir, "SYNC_TO_DEVICE", 0));

      String manifest =
          "{\n"
              + "  \"watchFaces\": [\n"
              + "    {\n"
              + "      \"id\": \"com.google.wear.watchface.wffsamples.flavors/watchface\",\n"
              + "      \"label\": \"Flavors\",\n"
              + "      \"rcFile\": \"watchface.rc\",\n"
              + "      \"width\": 450,\n"
              + "      \"height\": 450,\n"
              + "      \"clipShape\": \"CIRCLE\",\n"
              + "      \"slots\": [{\"id\": 1, \"supportedTypes\": [3, 5], \"enabled\": true}],\n"
              + "      \"configurations\": [\n"
              + "        {\n"
              + "          \"id\": \"themeColor\",\n"
              + "          \"kind\": \"COLOR\",\n"
              + "          \"displayName\": \"Theme\",\n"
              + "          \"defaultValue\": \"0\",\n"
              + "          \"options\": [\n"
              + "            {\"id\": \"0\", \"displayName\": \"Blue\", \"colors\": [\"#FF4285F4\"]},\n"
              + "            {\"id\": \"1\", \"displayName\": \"Gold\", \"colors\": [\"#FFFBBC05\"]},\n"
              + "            {\"id\": \"2\", \"displayName\": \"Rose\", \"colors\": [\"#FFD65B80\"]}\n"
              + "          ]\n"
              + "        },\n"
              + "        {\n"
              + "          \"id\": \"clockStyle\",\n"
              + "          \"kind\": \"LIST\",\n"
              + "          \"displayName\": \"Style\",\n"
              + "          \"defaultValue\": \"analog\",\n"
              + "          \"options\": [\n"
              + "            {\"id\": \"analog\", \"displayName\": \"Analog\"},\n"
              + "            {\"id\": \"digital\", \"displayName\": \"Digital\"}\n"
              + "          ]\n"
              + "        }\n"
              + "      ],\n"
              + "      \"defaultFlavorId\": \"flavorZero\",\n"
              + "      \"flavors\": [\n"
              + "        {\n"
              + "          \"id\": \"flavorZero\",\n"
              + "          \"displayName\": \"Zero\",\n"
              + "          \"configurations\": {\"themeColor\": \"0\", \"clockStyle\": \"analog\"},\n"
              + "          \"complications\": {\"1\": {\"defaultSystemProvider\": \"EMPTY\", \"defaultSystemProviderType\": \"EMPTY\"}}\n"
              + "        },\n"
              + "        {\n"
              + "          \"id\": \"flavorTwo\",\n"
              + "          \"displayName\": \"Two\",\n"
              + "          \"configurations\": {\"themeColor\": \"2\", \"clockStyle\": \"digital\"},\n"
              + "          \"complications\": {\"1\": {\"defaultSystemProvider\": \"STEP_COUNT\", \"defaultSystemProviderType\": \"SHORT_TEXT\"}}\n"
              + "        }\n"
              + "      ]\n"
              + "    }\n"
              + "  ]\n"
              + "}\n";
      Files.writeString(
          new File(tmpDir, "compiled-manifest.json").toPath(), manifest, StandardCharsets.UTF_8);

      List<DwfWatchFaceClient.Entry> loaded = DwfWatchFaceClient.loadEntriesFromDir(tmpDir);
      assertEquals(1, loaded.size());
      DwfWatchFaceClient client = new DwfWatchFaceClient(null, loaded.get(0), null);
      assertEquals("flavorZero", client.activeFlavorId());

      FakePlayerTarget target = new FakePlayerTarget();
      client.applyVariablesTo(target, false);
      assertEquals(0, target.ints.get("dwf.config.themeColor").intValue());
      assertEquals(0, target.ints.get("dwf.config.clockStyle").intValue());
      assertEquals(0, target.ints.get("dwf.complication.1.type").intValue());

      client.setFlavor("flavorTwo");
      assertEquals("flavorTwo", client.activeFlavorId());
      target = new FakePlayerTarget();
      client.applyVariablesTo(target, false);
      assertEquals(2, target.ints.get("dwf.config.themeColor").intValue());
      assertEquals(1, target.ints.get("dwf.config.clockStyle").intValue());
      assertEquals(1, target.ints.get("dwf.complication.1.type").intValue());
      assertEquals(Integer.toString(state.steps()), target.strings.get("dwf.complication.1.text"));
      assertEquals("STEPS", target.strings.get("dwf.complication.1.title"));
      assertEquals(0xFFD65B80, client.extractPalette().primaryArgb);
    } finally {
      WatchState.resetCurrent();
      deleteRecursively(tmpDir);
    }
  }

  public static final class FakeLimits {
    public static int MAX_OP_COUNT = 20_000;
    public static int MAX_BITMAP_MEMORY = 20_971_520;
    public static int MAX_DECOMPRESSED_SIZE = 33_554_432;
    public static boolean ENABLE_IMAGE_URLS = false;
    public static boolean ENABLE_IMAGE_FILES = false;
  }

  @Test
  public void configureRemoteCoreLimitsRaisesBitmapMemoryAndEnablesUrlImages() {
    FakeLimits.MAX_OP_COUNT = 20_000;
    FakeLimits.MAX_BITMAP_MEMORY = 20_971_520;
    FakeLimits.MAX_DECOMPRESSED_SIZE = 33_554_432;
    FakeLimits.ENABLE_IMAGE_URLS = false;
    FakeLimits.ENABLE_IMAGE_FILES = false;
    DwfWatchFaceClient.configureRemoteCoreLimits(FakeLimits.class);
    assertEquals(200_000, FakeLimits.MAX_OP_COUNT);
    assertEquals(256 * 1024 * 1024, FakeLimits.MAX_BITMAP_MEMORY);
    assertEquals(128 * 1024 * 1024, FakeLimits.MAX_DECOMPRESSED_SIZE);
    assertTrue(FakeLimits.ENABLE_IMAGE_URLS);
    assertTrue(FakeLimits.ENABLE_IMAGE_FILES);
  }

  @Test
  public void simulatorBitmapLoaderResolvesHttpDataFileAndConfiguredUrls() throws Exception {
    byte[] samplePng =
        java.util.Base64.getDecoder()
            .decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/test-photo.png",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "image/png");
          exchange.sendResponseHeaders(200, samplePng.length);
          exchange.getResponseBody().write(samplePng);
          exchange.close();
        });
    server.start();
    try {
      int port = server.getAddress().getPort();
      String httpUrl = "http://127.0.0.1:" + port + "/test-photo.png";
      SimulatorBitmapLoader loader = new SimulatorBitmapLoader(true);

      byte[] httpLoaded = loader.loadBytes(httpUrl);
      assertEquals(samplePng.length, httpLoaded.length);

      String dataUrl =
          "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
      assertEquals(samplePng.length, loader.loadBytes(dataUrl).length);

      loader.registerUrlBytes("https://configured.example.test/custom.png", samplePng);
      assertEquals(
          samplePng.length,
          loader.loadBytes("https://configured.example.test/custom.png").length);
      assertTrue(
          loader.loadBytes("https://compose-preview.local/dwf/monochromatic_default.png").length
              > 0);
      assertTrue(
          loader.loadBytes("https://compose-preview.local/dwf/small_default.png").length > 0);
      assertTrue(
          loader.loadBytes("https://compose-preview.local/dwf/photo_default.png").length > 0);

      DwfWatchFaceClient.FakeComplication urlComp =
          DwfWatchFaceClient.FakeComplication.fromJson(
              new JSONObject()
                  .put("slot", 0)
                  .put("type", "PHOTO_IMAGE")
                  .put("photoImage", httpUrl),
              0);
      assertEquals(httpUrl, urlComp.photoImage);
      assertTrue(urlComp.hasPhotoImage.booleanValue());
      assertEquals(httpUrl, urlComp.toJson().getString("photoImage"));
    } finally {
      server.stop(0);
    }
  }

  public static final class StubBitmapData {
    private final short encoding;
    private int width;
    private int height;

    StubBitmapData(short encoding, int width, int height) {
      this.encoding = encoding;
      this.width = width;
      this.height = height;
    }

    public short getEncoding() {
      return encoding;
    }

    public int getWidth() {
      return width;
    }

    public int getHeight() {
      return height;
    }

    public void setWidth(int width) {
      this.width = width;
    }

    public void setHeight(int height) {
      this.height = height;
    }
  }

  public static final class StubCoreDocument {
    private final StubBitmapData[] bitmaps;

    StubCoreDocument(StubBitmapData[] bitmaps) {
      this.bitmaps = bitmaps;
    }

    public StubBitmapData[] getBitmapDataSet() {
      return bitmaps;
    }
  }

  public static final class StubRemoteDocument {
    private final StubCoreDocument doc;

    StubRemoteDocument(StubCoreDocument doc) {
      this.doc = doc;
    }

    public StubCoreDocument getDocument() {
      return doc;
    }
  }

  @Test
  public void normalizesPlaceholderUrlBitmapDimensionsWhilePreservingInlineBitmaps() {
    StubBitmapData urlPlaceholder = new StubBitmapData((short) 1, 1, 1);
    StubBitmapData filePlaceholder = new StubBitmapData((short) 2, 1, 1);
    StubBitmapData inline1x1 = new StubBitmapData((short) 0, 1, 1);
    StubBitmapData urlExplicit = new StubBitmapData((short) 1, 64, 64);

    StubRemoteDocument remoteDoc =
        new StubRemoteDocument(
            new StubCoreDocument(
                new StubBitmapData[] {urlPlaceholder, filePlaceholder, inline1x1, urlExplicit}));

    int count = SimulatorBitmapLoader.normalizeUrlBitmapDimensions(remoteDoc);
    assertEquals(2, count);
    assertEquals(0, urlPlaceholder.getWidth());
    assertEquals(0, urlPlaceholder.getHeight());
    assertEquals(0, filePlaceholder.getWidth());
    assertEquals(0, filePlaceholder.getHeight());
    assertEquals(1, inline1x1.getWidth());
    assertEquals(1, inline1x1.getHeight());
    assertEquals(64, urlExplicit.getWidth());
    assertEquals(64, urlExplicit.getHeight());
  }

  private static void deleteRecursively(File f) {
    if (f == null || !f.exists()) return;
    File[] children = f.listFiles();
    if (children != null) {
      for (File c : children) deleteRecursively(c);
    }
    f.delete();
  }
}
