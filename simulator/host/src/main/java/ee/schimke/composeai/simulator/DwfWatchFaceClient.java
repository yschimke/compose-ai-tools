package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A Declarative Watch Face (Watch Face Format XML) compiled to a Remote Compose {@code .rc}
 * document by {@code DwfRenderMain} and hosted in the Wear simulator through {@code
 * RemoteComposePlayer}.
 */
final class DwfWatchFaceClient {

  static final String DWF_DIR_PROPERTY = "composeai.simulator.dwfDir";
  static final String WATCHFACE_PROPERTY = "composeai.simulator.watchface";
  private static final int PLAYER_MAX_OP_COUNT = 200_000;
  private static final Pattern FONT_REF_PATTERN = Pattern.compile("@font/([A-Za-z0-9_.-]+)");

  interface Listener {
    void rendered(DwfWatchFaceClient face, Bitmap frame, String error);
  }

  /** Clock injected into {@code RemoteComposePlayer}'s {@code RemoteDocument} to follow WatchState. */
  static final class MutableClock extends Clock {
    private final DwfWatchFaceClient owner;
    private final ZoneId fixedZone;

    MutableClock(DwfWatchFaceClient owner, ZoneId fixedZone) {
      this.owner = owner;
      this.fixedZone = fixedZone;
    }

    @Override
    public ZoneId getZone() {
      if (fixedZone != null) return fixedZone;
      WatchState ws = WatchState.current();
      return ws != null ? ws.zoneId() : ZoneId.systemDefault();
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return new MutableClock(owner, zone);
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(owner.effectiveTimeMillis());
    }
  }

  private static final class BoundWireComplication {
    final int wireType;
    final Object rawData;

    BoundWireComplication(int wireType, Object rawData) {
      this.wireType = wireType;
      this.rawData = rawData;
    }
  }

  static final class ConfigOption {
    final String id;
    final String displayName;
    final List<Integer> colorArgbs;

    ConfigOption(String id, String displayName, List<Integer> colorArgbs) {
      this.id = id;
      this.displayName = displayName;
      this.colorArgbs = colorArgbs;
    }
  }

  static final class FlavorComplicationPolicy {
    final String defaultSystemProvider;
    final String defaultSystemProviderType;

    FlavorComplicationPolicy(String defaultSystemProvider, String defaultSystemProviderType) {
      this.defaultSystemProvider = defaultSystemProvider;
      this.defaultSystemProviderType = defaultSystemProviderType;
    }
  }

  static final class FlavorSpec {
    final String id;
    final String displayName;
    final Map<String, String> configurations;
    final Map<Integer, FlavorComplicationPolicy> complications;

    FlavorSpec(
        String id,
        String displayName,
        Map<String, String> configurations,
        Map<Integer, FlavorComplicationPolicy> complications) {
      this.id = id;
      this.displayName = displayName;
      this.configurations = configurations;
      this.complications = complications;
    }
  }

  static final class FakeComplication {
    final int slot;
    final int wireType;
    final int dwfTypeCode;
    final String text;
    final String title;
    final Float value;
    final Float min;
    final Float max;
    final Boolean hasMonochromaticImage;
    final Boolean hasSmallImage;
    final Boolean hasPhotoImage;
    final String monochromaticImage;
    final String smallImage;
    final String photoImage;
    final Object monochromaticBitmap;
    final Object smallBitmap;
    final Object photoBitmap;
    final List<Integer> colors;
    final List<Float> weights;

    FakeComplication(
        int slot,
        int wireType,
        int dwfTypeCode,
        String text,
        String title,
        Float value,
        Float min,
        Float max,
        Boolean hasMonochromaticImage,
        Boolean hasSmallImage,
        Boolean hasPhotoImage) {
      this(
          slot,
          wireType,
          dwfTypeCode,
          text,
          title,
          value,
          min,
          max,
          hasMonochromaticImage,
          hasSmallImage,
          hasPhotoImage,
          Collections.emptyList(),
          Collections.emptyList(),
          null,
          null,
          null,
          null,
          null,
          null);
    }

    FakeComplication(
        int slot,
        int wireType,
        int dwfTypeCode,
        String text,
        String title,
        Float value,
        Float min,
        Float max,
        Boolean hasMonochromaticImage,
        Boolean hasSmallImage,
        Boolean hasPhotoImage,
        List<Integer> colors,
        List<Float> weights) {
      this(
          slot,
          wireType,
          dwfTypeCode,
          text,
          title,
          value,
          min,
          max,
          hasMonochromaticImage,
          hasSmallImage,
          hasPhotoImage,
          colors,
          weights,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    FakeComplication(
        int slot,
        int wireType,
        int dwfTypeCode,
        String text,
        String title,
        Float value,
        Float min,
        Float max,
        Boolean hasMonochromaticImage,
        Boolean hasSmallImage,
        Boolean hasPhotoImage,
        List<Integer> colors,
        List<Float> weights,
        String monochromaticImage,
        String smallImage,
        String photoImage,
        Object monochromaticBitmap,
        Object smallBitmap,
        Object photoBitmap) {
      this.slot = slot;
      this.wireType = wireType;
      this.dwfTypeCode = dwfTypeCode;
      this.text = text;
      this.title = title;
      this.value = value;
      this.min = min;
      this.max = max;
      this.hasMonochromaticImage = hasMonochromaticImage;
      this.hasSmallImage = hasSmallImage;
      this.hasPhotoImage = hasPhotoImage;
      this.colors = colors != null ? colors : Collections.emptyList();
      this.weights = weights != null ? weights : Collections.emptyList();
      this.monochromaticImage = monochromaticImage;
      this.smallImage = smallImage;
      this.photoImage = photoImage;
      this.monochromaticBitmap = monochromaticBitmap;
      this.smallBitmap = smallBitmap;
      this.photoBitmap = photoBitmap;
    }

    static FakeComplication fromJson(JSONObject c, int defaultSlot) throws JSONException {
      int slot = c.optInt("slot", defaultSlot);
      Object rawType = c.opt("type");
      int wireType = parseWireComplicationType(rawType);
      int dwfTypeCode = parseDwfComplicationTypeCode(rawType, wireType);
      String text = c.has("text") && !c.isNull("text") ? c.getString("text") : null;
      String title = c.has("title") && !c.isNull("title") ? c.getString("title") : null;
      Float value = c.has("value") && !c.isNull("value") ? (float) c.getDouble("value") : null;
      Float min = c.has("min") && !c.isNull("min") ? (float) c.getDouble("min") : null;
      Float max = c.has("max") && !c.isNull("max") ? (float) c.getDouble("max") : null;

      String monoUrl = optNonEmptyString(c, "monochromaticImage", "iconUrl");
      String smallUrl = optNonEmptyString(c, "smallImage", "smallImageUrl");
      String photoUrl = optNonEmptyString(c, "photoImage", "photoImageUrl", "largeImage");
      String genericUrl = optNonEmptyString(c, "imageUrl", "url");
      if (genericUrl != null && monoUrl == null && smallUrl == null && photoUrl == null) {
        if (dwfTypeCode == 4) monoUrl = genericUrl;
        else if (dwfTypeCode == 5) smallUrl = genericUrl;
        else photoUrl = genericUrl;
      }

      Boolean hasMono =
          c.has("hasMonochromaticImage")
              ? Boolean.valueOf(c.getBoolean("hasMonochromaticImage"))
              : (monoUrl != null ? Boolean.TRUE : null);
      Boolean hasSmall =
          c.has("hasSmallImage")
              ? Boolean.valueOf(c.getBoolean("hasSmallImage"))
              : (smallUrl != null ? Boolean.TRUE : null);
      Boolean hasPhoto =
          c.has("hasPhotoImage")
              ? Boolean.valueOf(c.getBoolean("hasPhotoImage"))
              : (photoUrl != null ? Boolean.TRUE : null);
      List<Integer> colors = new ArrayList<>();
      JSONArray colArr = c.optJSONArray("colors");
      if (colArr != null) {
        for (int i = 0; i < colArr.length(); i++) {
          Integer parsed = parseArgbColor(colArr.get(i));
          if (parsed != null) colors.add(parsed);
        }
      }
      List<Float> weights = new ArrayList<>();
      JSONArray weightArr = c.optJSONArray("weights");
      if (weightArr != null) {
        for (int i = 0; i < weightArr.length(); i++) {
          weights.add(Float.valueOf((float) weightArr.getDouble(i)));
        }
      }
      return new FakeComplication(
          slot,
          wireType,
          dwfTypeCode,
          text,
          title,
          value,
          min,
          max,
          hasMono,
          hasSmall,
          hasPhoto,
          colors,
          weights,
          monoUrl,
          smallUrl,
          photoUrl,
          null,
          null,
          null);
    }

    private static String optNonEmptyString(JSONObject obj, String... keys) throws JSONException {
      for (String k : keys) {
        if (obj.has(k) && !obj.isNull(k)) {
          String v = obj.getString(k).trim();
          if (!v.isEmpty()) return v;
        }
      }
      return null;
    }

    static FakeComplication fromWireData(
        int slot, int fallbackWireType, Object data, Object resources, long nowMillis) {
      return fromWireData(slot, fallbackWireType, data, resources, null, nowMillis);
    }

    static FakeComplication fromWireData(
        int slot,
        int fallbackWireType,
        Object data,
        Object resources,
        Object context,
        long nowMillis) {
      int actualWireType = optionalInt(data, "getType", fallbackWireType);
      String shortText = wireTextAt(data, "getShortText", resources, nowMillis);
      String longText = wireTextAt(data, "getLongText", resources, nowMillis);
      String text = shortText != null ? shortText : longText;
      String shortTitle = wireTextAt(data, "getShortTitle", resources, nowMillis);
      String longTitle = wireTextAt(data, "getLongTitle", resources, nowMillis);
      String title = shortTitle != null ? shortTitle : longTitle;

      Float value = optionalFloat(data, "hasRangedValue", "getRangedValue");
      Float min = optionalFloat(data, "hasRangedMinValue", "getRangedMinValue");
      Float max = optionalFloat(data, "hasRangedMaxValue", "getRangedMaxValue");
      if (max == null) {
        max = optionalFloat(data, "hasTargetValue", "getTargetValue");
      }
      if (min == null && actualWireType == 13 && (value != null || max != null)) {
        min = Float.valueOf(0f);
      }

      Boolean hasMono = optionalImagePresent(data, "hasIcon", "getIcon");
      Boolean hasSmall = optionalImagePresent(data, "hasSmallImage", "getSmallImage");
      Boolean hasPhoto = optionalImagePresent(data, "hasLargeImage", "getLargeImage");
      Object monoBmp =
          Boolean.TRUE.equals(hasMono) ? extractWireIconBitmap(data, "getIcon", context, 64) : null;
      Object smallBmp =
          Boolean.TRUE.equals(hasSmall)
              ? extractWireIconBitmap(data, "getSmallImage", context, 96)
              : null;
      Object photoBmp =
          Boolean.TRUE.equals(hasPhoto)
              ? extractWireIconBitmap(data, "getLargeImage", context, 450)
              : null;

      return new FakeComplication(
          slot,
          actualWireType,
          wireTypeToDwfTypeCode(actualWireType),
          text,
          title,
          value,
          min,
          max,
          hasMono,
          hasSmall,
          hasPhoto,
          Collections.emptyList(),
          Collections.emptyList(),
          null,
          null,
          null,
          monoBmp,
          smallBmp,
          photoBmp);
    }

    private static Object extractWireIconBitmap(
        Object data, String getter, Object context, int fallbackSize) {
      if (data == null || context == null) return null;
      try {
        return AndroidWireIconDecoder.decode(data, getter, context, fallbackSize);
      } catch (RuntimeException | LinkageError ignored) {
        return null;
      }
    }

    private static String wireTextAt(
        Object data, String getter, Object resources, long nowMillis) {
      try {
        Object complicationText = data.getClass().getMethod(getter).invoke(data);
        if (complicationText == null) return null;
        for (Method m : complicationText.getClass().getMethods()) {
          if (!"getTextAt".equals(m.getName()) || m.getParameterCount() != 2) continue;
          Class<?> secondParam = m.getParameterTypes()[1];
          if (secondParam != long.class && secondParam != Long.class) continue;
          Object value = m.invoke(complicationText, resources, Long.valueOf(nowMillis));
          return value == null || value.toString().isEmpty() ? null : value.toString();
        }
      } catch (ReflectiveOperationException | RuntimeException ignored) {
      }
      return null;
    }

    private static int optionalInt(Object target, String getter, int fallback) {
      try {
        Object out = target.getClass().getMethod(getter).invoke(target);
        if (out instanceof Number) return ((Number) out).intValue();
      } catch (ReflectiveOperationException | RuntimeException ignored) {
      }
      return fallback;
    }

    private static Float optionalFloat(Object target, String hasGetter, String valueGetter) {
      try {
        Method has = findNoArgMethod(target.getClass(), hasGetter);
        if (has != null) {
          Object present = has.invoke(target);
          if (Boolean.FALSE.equals(present)) return null;
        }
        Object out = target.getClass().getMethod(valueGetter).invoke(target);
        if (out instanceof Number) return Float.valueOf(((Number) out).floatValue());
      } catch (ReflectiveOperationException | RuntimeException ignored) {
      }
      return null;
    }

    private static Boolean optionalImagePresent(Object target, String hasGetter, String iconGetter) {
      try {
        Method has = findNoArgMethod(target.getClass(), hasGetter);
        if (has != null) {
          Object present = has.invoke(target);
          if (present instanceof Boolean) return (Boolean) present;
        }
        Object icon = target.getClass().getMethod(iconGetter).invoke(target);
        return Boolean.valueOf(icon != null);
      } catch (ReflectiveOperationException | RuntimeException ignored) {
      }
      return null;
    }

    private static Method findNoArgMethod(Class<?> type, String name) {
      try {
        return type.getMethod(name);
      } catch (NoSuchMethodException e) {
        return null;
      }
    }

    JSONObject toJson() {
      Protocol.Json json =
          new Protocol.Json()
              .put("slot", slot)
              .put("source", "fake")
              .put("face", "app")
              .put("type", wireType)
              .put("dwfType", dwfTypeCode);
      if (text != null) json.put("text", text);
      if (title != null) json.put("title", title);
      if (value != null) json.put("value", value.doubleValue());
      if (min != null) json.put("min", min.doubleValue());
      if (max != null) json.put("max", max.doubleValue());
      if (monochromaticImage != null) json.put("monochromaticImage", monochromaticImage);
      if (smallImage != null) json.put("smallImage", smallImage);
      if (photoImage != null) json.put("photoImage", photoImage);
      return json.object();
    }
  }

  static final class ConfigSpec {
    final String id;
    final String kind;
    final String displayName;
    final String defaultValue;
    final List<ConfigOption> options;

    ConfigSpec(
        String id,
        String kind,
        String displayName,
        String defaultValue,
        List<ConfigOption> options) {
      this.id = id;
      this.kind = kind;
      this.displayName = displayName;
      this.defaultValue = defaultValue;
      this.options = options;
    }
  }

  static final class Entry {
    final String id;
    final String label;
    final File dir;
    final String rcFile;
    final int width;
    final int height;
    final String clipShape;
    final List<WatchFaceClient.Slot> slots;
    final List<ConfigSpec> configurations;

    /**
     * Slot id to the WFF {@code defaultSystemProvider} (e.g. {@code STEP_COUNT}). The compiler bakes
     * that provider's value into the document as a seed, so the simulator overrides it from {@link
     * WatchState} for any slot the agent has not bound or faked.
     */
    final Map<Integer, String> defaultProviders;

    final String defaultFlavorId;
    final List<FlavorSpec> flavors;

    Entry(
        String id,
        String label,
        File dir,
        String rcFile,
        int width,
        int height,
        String clipShape,
        List<WatchFaceClient.Slot> slots,
        List<ConfigSpec> configurations) {
      this(
          id,
          label,
          dir,
          rcFile,
          width,
          height,
          clipShape,
          slots,
          configurations,
          Collections.emptyMap(),
          null,
          Collections.emptyList());
    }

    Entry(
        String id,
        String label,
        File dir,
        String rcFile,
        int width,
        int height,
        String clipShape,
        List<WatchFaceClient.Slot> slots,
        List<ConfigSpec> configurations,
        Map<Integer, String> defaultProviders) {
      this(
          id,
          label,
          dir,
          rcFile,
          width,
          height,
          clipShape,
          slots,
          configurations,
          defaultProviders,
          null,
          Collections.emptyList());
    }

    Entry(
        String id,
        String label,
        File dir,
        String rcFile,
        int width,
        int height,
        String clipShape,
        List<WatchFaceClient.Slot> slots,
        List<ConfigSpec> configurations,
        Map<Integer, String> defaultProviders,
        String defaultFlavorId,
        List<FlavorSpec> flavors) {
      this.id = id;
      this.label = label;
      this.dir = dir;
      this.rcFile = rcFile;
      this.width = width;
      this.height = height;
      this.clipShape = clipShape;
      this.slots = slots;
      this.configurations = configurations;
      this.defaultProviders = defaultProviders != null ? defaultProviders : Collections.emptyMap();
      this.defaultFlavorId = defaultFlavorId;
      this.flavors = flavors != null ? flavors : Collections.emptyList();
    }
  }

  private final Application app;
  private final Entry entry;
  private final SimulatorBitmapLoader bitmapLoader;
  private final Listener listener;
  private final Map<String, String> configValues = new LinkedHashMap<>();
  private final Map<Integer, FakeComplication> complications = new LinkedHashMap<>();
  private final Map<Integer, BoundWireComplication> boundWireComplications = new LinkedHashMap<>();
  private final Set<Integer> touchedSlots = new LinkedHashSet<>();
  private final Map<Integer, FlavorComplicationPolicy> activeFlavorComplications =
      new LinkedHashMap<>();
  private final Map<String, Typeface> customFontCache = new HashMap<>();

  private String activeFlavorId;
  private byte[] document;
  private View player;
  private String initError;
  private Long fixedTimeMillis;
  private Bitmap lastBitmap;
  private int width;
  private int height;
  private int frames;
  private String error;

  DwfWatchFaceClient(Application app, Entry entry, Listener listener) {
    this(app, entry, SimulatorBitmapLoader.shared(), listener);
  }

  DwfWatchFaceClient(
      Application app, Entry entry, SimulatorBitmapLoader bitmapLoader, Listener listener) {
    this.app = app;
    this.entry = entry;
    this.bitmapLoader = bitmapLoader != null ? bitmapLoader : SimulatorBitmapLoader.shared();
    this.listener = listener;
    this.width = entry.width;
    this.height = entry.height;
    for (ConfigSpec cfg : entry.configurations) {
      configValues.put(cfg.id, cfg.defaultValue);
    }
    if (entry.defaultFlavorId != null && !entry.defaultFlavorId.isEmpty() && !entry.flavors.isEmpty()) {
      FlavorSpec def = findFlavor(entry.defaultFlavorId);
      if (def != null) {
        applyFlavorInternal(def);
      }
    }
  }

  /**
   * Loads compiled declarative watch faces from {@code -Dcomposeai.simulator.dwfDir} (single path
   * or {@link File#pathSeparator}-separated paths).
   */
  static List<Entry> availableFromProperty() {
    String dirProp = System.getProperty(DWF_DIR_PROPERTY);
    if (dirProp == null || dirProp.isEmpty()) return Collections.emptyList();
    List<Entry> all = new ArrayList<>();
    for (String part : dirProp.split(File.pathSeparator)) {
      if (part.isEmpty()) continue;
      all.addAll(loadEntriesFromDir(new File(part)));
    }
    return all;
  }

  /** Loads compiled watch face entries from a single directory containing {@code compiled-manifest.json}. */
  static List<Entry> loadEntriesFromDir(File dir) {
    if (dir == null) return Collections.emptyList();
    File manifestFile = new File(dir, "compiled-manifest.json");
    if (!manifestFile.isFile()) return Collections.emptyList();
    try {
      String text = Files.readString(manifestFile.toPath(), StandardCharsets.UTF_8);
      JSONObject root = new JSONObject(text);
      JSONArray arr = root.optJSONArray("watchFaces");
      if (arr == null) return Collections.emptyList();
      List<Entry> result = new ArrayList<>();
      for (int i = 0; i < arr.length(); i++) {
        JSONObject item = arr.getJSONObject(i);
        String id = item.getString("id");
        String label = item.optString("label", id);
        String rcFile = item.getString("rcFile");
        int w = item.optInt("width", 450);
        int h = item.optInt("height", 450);
        String clipShape = item.optString("clipShape", "CIRCLE");

        List<WatchFaceClient.Slot> slots = new ArrayList<>();
        Map<Integer, String> defaultProviders = new LinkedHashMap<>();
        JSONArray slotsArr = item.optJSONArray("slots");
        if (slotsArr != null) {
          for (int s = 0; s < slotsArr.length(); s++) {
            JSONObject slotObj = slotsArr.getJSONObject(s);
            int slotId = slotObj.getInt("id");
            boolean enabled = slotObj.optBoolean("enabled", true);
            String provider = slotObj.optString("defaultSystemProvider", "");
            if (!provider.isEmpty()) defaultProviders.put(Integer.valueOf(slotId), provider);
            JSONArray typesArr = slotObj.optJSONArray("supportedTypes");
            int[] types = new int[typesArr == null ? 0 : typesArr.length()];
            if (typesArr != null) {
              for (int t = 0; t < typesArr.length(); t++) types[t] = typesArr.getInt(t);
            }
            float sx = (float) slotObj.optDouble("x", 0.0);
            float sy = (float) slotObj.optDouble("y", 0.0);
            float sw = (float) slotObj.optDouble("width", 0.0);
            float sh = (float) slotObj.optDouble("height", 0.0);
            if (sw > 0f && sh > 0f) {
              slots.add(
                  new WatchFaceClient.Slot(
                      slotId,
                      types,
                      enabled,
                      Math.round(sx),
                      Math.round(sy),
                      Math.round(sx + sw),
                      Math.round(sy + sh)));
            } else {
              slots.add(new WatchFaceClient.Slot(slotId, types, enabled));
            }
          }
        }

        List<ConfigSpec> configs = new ArrayList<>();
        JSONArray cfgArr = item.optJSONArray("configurations");
        if (cfgArr != null) {
          for (int c = 0; c < cfgArr.length(); c++) {
            JSONObject cfgObj = cfgArr.getJSONObject(c);
            String cfgId = cfgObj.getString("id");
            String kind = cfgObj.optString("kind", "LIST");
            String displayName = cfgObj.optString("displayName", cfgId);
            String defaultVal = cfgObj.optString("defaultValue", "");
            List<ConfigOption> options = new ArrayList<>();
            JSONArray optArr = cfgObj.optJSONArray("options");
            if (optArr != null) {
              for (int o = 0; o < optArr.length(); o++) {
                JSONObject optObj = optArr.getJSONObject(o);
                String optId = optObj.getString("id");
                String optName = optObj.optString("displayName", optId);
                List<Integer> colors = new ArrayList<>();
                JSONArray colArr = optObj.optJSONArray("colors");
                if (colArr != null) {
                  for (int k = 0; k < colArr.length(); k++) {
                    Object rawCol = colArr.get(k);
                    Integer parsed = parseArgbColor(rawCol);
                    if (parsed != null) colors.add(parsed);
                  }
                }
                options.add(new ConfigOption(optId, optName, colors));
              }
            }
            configs.add(new ConfigSpec(cfgId, kind, displayName, defaultVal, options));
          }
        }

        String defaultFlavorId =
            item.has("defaultFlavorId") && !item.isNull("defaultFlavorId")
                ? item.optString("defaultFlavorId", "")
                : "";
        if (defaultFlavorId.isEmpty()) defaultFlavorId = null;
        List<FlavorSpec> flavors = new ArrayList<>();
        JSONArray flavorsArr = item.optJSONArray("flavors");
        if (flavorsArr != null) {
          for (int f = 0; f < flavorsArr.length(); f++) {
            JSONObject flvObj = flavorsArr.getJSONObject(f);
            String flvId = flvObj.getString("id");
            String flvName = flvObj.optString("displayName", flvId);
            Map<String, String> flvConfigs = new LinkedHashMap<>();
            JSONObject flvCfgObj = flvObj.optJSONObject("configurations");
            if (flvCfgObj != null) {
              for (Iterator<String> it = flvCfgObj.keys(); it.hasNext(); ) {
                String k = it.next();
                flvConfigs.put(k, String.valueOf(flvCfgObj.get(k)));
              }
            }
            Map<Integer, FlavorComplicationPolicy> flvComps = new LinkedHashMap<>();
            JSONObject flvCompObj = flvObj.optJSONObject("complications");
            if (flvCompObj != null) {
              for (Iterator<String> it = flvCompObj.keys(); it.hasNext(); ) {
                String k = it.next();
                try {
                  int slotKey = Integer.parseInt(k.trim());
                  JSONObject polObj = flvCompObj.optJSONObject(k);
                  if (polObj != null) {
                    String sysProv =
                        polObj.has("defaultSystemProvider")
                                && !polObj.isNull("defaultSystemProvider")
                            ? polObj.optString("defaultSystemProvider", "")
                            : "";
                    String sysType =
                        polObj.has("defaultSystemProviderType")
                                && !polObj.isNull("defaultSystemProviderType")
                            ? polObj.optString("defaultSystemProviderType", "")
                            : "";
                    flvComps.put(
                        Integer.valueOf(slotKey),
                        new FlavorComplicationPolicy(
                            sysProv.isEmpty() ? null : sysProv,
                            sysType.isEmpty() ? null : sysType));
                  }
                } catch (NumberFormatException ignored) {
                }
              }
            }
            flavors.add(new FlavorSpec(flvId, flvName, flvConfigs, flvComps));
          }
        }
        result.add(
            new Entry(
                id,
                label,
                dir,
                rcFile,
                w,
                h,
                clipShape,
                slots,
                configs,
                defaultProviders,
                defaultFlavorId,
                flavors));
      }
      return result;
    } catch (IOException | JSONException e) {
      return Collections.emptyList();
    }
  }

  private static Integer parseArgbColor(Object raw) {
    if (raw instanceof Number) {
      return Integer.valueOf(((Number) raw).intValue());
    }
    if (raw instanceof String) {
      String s = ((String) raw).trim();
      if (s.startsWith("#")) s = s.substring(1);
      else if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
      try {
        if (s.length() == 6) return Integer.valueOf((int) (0xFF000000L | Long.parseLong(s, 16)));
        if (s.length() == 8) return Integer.valueOf((int) Long.parseUnsignedLong(s, 16));
      } catch (NumberFormatException ignored) {
      }
    }
    return null;
  }

  static int parseWireComplicationType(Object raw) {
    if (raw instanceof Number) return ((Number) raw).intValue();
    if (raw instanceof String) {
      String s = ((String) raw).trim().toUpperCase();
      if (s.startsWith("COMPLICATION_TYPE_")) s = s.substring("COMPLICATION_TYPE_".length());
      switch (s) {
        case "SHORT_TEXT":
          return 3;
        case "LONG_TEXT":
          return 4;
        case "RANGED_VALUE":
          return 5;
        case "MONOCHROMATIC_IMAGE":
        case "ICON":
          return 6;
        case "SMALL_IMAGE":
          return 7;
        case "PHOTO_IMAGE":
        case "LARGE_IMAGE":
          return 8;
        case "NO_PERMISSION":
          return 9;
        case "NO_DATA":
          return 10;
        case "GOAL_PROGRESS":
          return 13;
        case "WEIGHTED_ELEMENTS":
          return 14;
        case "EMPTY":
          return 0;
        default:
          try {
            return Integer.parseInt(s);
          } catch (NumberFormatException ignored) {
            return 3;
          }
      }
    }
    return 3;
  }

  static int parseDwfComplicationTypeCode(Object raw, int wireType) {
    if (raw instanceof String) {
      String s = ((String) raw).trim().toUpperCase();
      if (s.startsWith("COMPLICATION_TYPE_")) s = s.substring("COMPLICATION_TYPE_".length());
      switch (s) {
        case "EMPTY":
        case "NO_DATA":
        case "NO_PERMISSION":
          return 0;
        case "SHORT_TEXT":
          return 1;
        case "LONG_TEXT":
          return 2;
        case "RANGED_VALUE":
          return 3;
        case "MONOCHROMATIC_IMAGE":
        case "ICON":
          return 4;
        case "SMALL_IMAGE":
          return 5;
        case "PHOTO_IMAGE":
        case "LARGE_IMAGE":
          return 6;
        case "GOAL_PROGRESS":
          return 7;
        case "WEIGHTED_ELEMENTS":
          return 8;
        default:
          break;
      }
    }
    return wireTypeToDwfTypeCode(wireType);
  }

  static int wireTypeToDwfTypeCode(int wireType) {
    switch (wireType) {
      case 3:
        return 1; // SHORT_TEXT
      case 4:
        return 2; // LONG_TEXT
      case 5:
        return 3; // RANGED_VALUE
      case 6:
        return 4; // MONOCHROMATIC_IMAGE
      case 7:
        return 5; // SMALL_IMAGE
      case 8:
        return 6; // PHOTO_IMAGE
      case 13:
        return 7; // GOAL_PROGRESS
      case 14:
        return 8; // WEIGHTED_ELEMENTS
      default:
        return 0; // EMPTY
    }
  }

  String id() {
    return entry.id;
  }

  String label() {
    return entry.label;
  }

  int frames() {
    return frames;
  }

  String error() {
    return error;
  }

  void setFixedTimeMillis(Long timeMillis) {
    this.fixedTimeMillis = timeMillis;
  }

  Long fixedTimeMillis() {
    return fixedTimeMillis;
  }

  long effectiveTimeMillis() {
    if (fixedTimeMillis != null) return fixedTimeMillis.longValue();
    WatchState ws = WatchState.current();
    return ws != null ? ws.nowMillis() : System.currentTimeMillis();
  }

  List<WatchFaceClient.Slot> slots() {
    if (width <= 0
        || height <= 0
        || entry.width <= 0
        || entry.height <= 0
        || (width == entry.width && height == entry.height)) {
      return entry.slots;
    }
    float sx = width / (float) entry.width;
    float sy = height / (float) entry.height;
    List<WatchFaceClient.Slot> scaled = new ArrayList<>(entry.slots.size());
    for (WatchFaceClient.Slot s : entry.slots) {
      if (!s.hasBounds) {
        scaled.add(s);
      } else {
        scaled.add(
            new WatchFaceClient.Slot(
                s.id,
                s.supportedTypes,
                s.enabled,
                Math.round(s.left * sx),
                Math.round(s.top * sy),
                Math.round(s.right * sx),
                Math.round(s.bottom * sy)));
      }
    }
    return scaled;
  }

  Map<Integer, FakeComplication> fakeComplications() {
    return Collections.unmodifiableMap(complications);
  }

  WatchFaceDynamicColors.Palette extractPalette() {
    for (ConfigSpec cfg : entry.configurations) {
      if (!"COLOR".equalsIgnoreCase(cfg.kind)) continue;
      String rawVal = configValues.getOrDefault(cfg.id, cfg.defaultValue);
      int idx = resolveOptionIndex(cfg, rawVal);
      if (idx >= 0 && idx < cfg.options.size()) {
        ConfigOption opt = cfg.options.get(idx);
        if (!opt.colorArgbs.isEmpty()) {
          WatchFaceDynamicColors.Palette p =
              WatchFaceDynamicColors.Palette.fromColorList(
                  opt.colorArgbs, "configuration:" + cfg.id + ":" + opt.id);
          if (p != null) return p;
        }
      }
    }
    return WatchFaceDynamicColors.extractFromBitmap(lastBitmap);
  }

  JSONArray describeConfigurations() {
    JSONArray out = new JSONArray();
    for (ConfigSpec cfg : entry.configurations) {
      JSONArray opts = new JSONArray();
      for (ConfigOption opt : cfg.options) {
        Protocol.Json o = new Protocol.Json().put("id", opt.id).put("displayName", opt.displayName);
        if (!opt.colorArgbs.isEmpty()) {
          JSONArray cols = new JSONArray();
          for (Integer col : opt.colorArgbs) cols.put(String.format("#%08X", col));
          o.put("colors", cols);
        }
        opts.put(o.object());
      }
      out.put(
          new Protocol.Json()
              .put("id", cfg.id)
              .put("kind", cfg.kind)
              .put("displayName", cfg.displayName)
              .put("defaultValue", cfg.defaultValue)
              .put("value", configValues.getOrDefault(cfg.id, cfg.defaultValue))
              .put("options", opts)
              .object());
    }
    return out;
  }

  boolean hasFlavors() {
    return !entry.flavors.isEmpty();
  }

  String activeFlavorId() {
    return activeFlavorId;
  }

  JSONArray describeFlavors() {
    return describeFlavorSpecs(entry.flavors, activeFlavorId);
  }

  static JSONArray describeFlavorSpecs(List<FlavorSpec> specs, String activeId) {
    JSONArray out = new JSONArray();
    if (specs == null) return out;
    for (FlavorSpec flv : specs) {
      JSONObject cfgs = new JSONObject();
      for (Map.Entry<String, String> e : flv.configurations.entrySet()) {
        try {
          cfgs.put(e.getKey(), e.getValue());
        } catch (JSONException ignored) {
        }
      }
      JSONObject comps = new JSONObject();
      for (Map.Entry<Integer, FlavorComplicationPolicy> e : flv.complications.entrySet()) {
        Protocol.Json pol = new Protocol.Json();
        if (e.getValue().defaultSystemProvider != null) {
          pol.put("defaultSystemProvider", e.getValue().defaultSystemProvider);
        }
        if (e.getValue().defaultSystemProviderType != null) {
          pol.put("defaultSystemProviderType", e.getValue().defaultSystemProviderType);
        }
        try {
          comps.put(String.valueOf(e.getKey()), pol.object());
        } catch (JSONException ignored) {
        }
      }
      out.put(
          new Protocol.Json()
              .put("id", flv.id)
              .put("displayName", flv.displayName)
              .put("active", flv.id.equals(activeId))
              .put("configurations", cfgs)
              .put("complications", comps)
              .object());
    }
    return out;
  }

  void resetConfigsToDefault() {
    for (ConfigSpec cfg : entry.configurations) {
      configValues.put(cfg.id, cfg.defaultValue);
    }
    activeFlavorId = null;
    activeFlavorComplications.clear();
    if (entry.defaultFlavorId != null
        && !entry.defaultFlavorId.isEmpty()
        && !entry.flavors.isEmpty()) {
      FlavorSpec def = findFlavor(entry.defaultFlavorId);
      if (def != null) {
        applyFlavorInternal(def);
      }
    }
  }

  void setFlavor(String flavorId) {
    FlavorSpec spec = findFlavor(flavorId);
    if (spec == null) {
      throw new IllegalArgumentException(
          "Watch face " + entry.id + " has no flavor '" + flavorId + "'");
    }
    for (ConfigSpec cfg : entry.configurations) {
      configValues.put(cfg.id, cfg.defaultValue);
    }
    applyFlavorInternal(spec);
  }

  private FlavorSpec findFlavor(String raw) {
    return findFlavorSpec(entry.flavors, raw);
  }

  static FlavorSpec findFlavorSpec(List<FlavorSpec> specs, String raw) {
    if (raw == null || specs == null) return null;
    String trimmed = raw.trim();
    for (FlavorSpec flv : specs) {
      if (flv.id.equals(trimmed) || flv.displayName.equalsIgnoreCase(trimmed)) {
        return flv;
      }
    }
    try {
      int idx = Integer.parseInt(trimmed);
      if (idx >= 0 && idx < specs.size()) {
        return specs.get(idx);
      }
    } catch (NumberFormatException ignored) {
    }
    return null;
  }

  private void applyFlavorInternal(FlavorSpec flavor) {
    activeFlavorId = flavor.id;
    configValues.putAll(flavor.configurations);
    activeFlavorComplications.clear();
    activeFlavorComplications.putAll(flavor.complications);
  }

  void setConfig(String configId, String value) {
    ConfigSpec spec = null;
    for (ConfigSpec c : entry.configurations) {
      if (c.id.equals(configId)) {
        spec = c;
        break;
      }
    }
    if (spec == null) {
      if ("flavor".equals(configId) && hasFlavors()) {
        setFlavor(value);
        return;
      }
      throw new IllegalArgumentException(
          "Watch face " + entry.id + " has no configuration '" + configId + "'");
    }
    configValues.put(configId, value);
  }

  void start(int width, int height, boolean ambient) {
    this.width = width > 0 ? width : entry.width;
    this.height = height > 0 ? height : entry.height;
    File rcPath = new File(entry.dir, entry.rcFile);
    try {
      this.document = Files.readAllBytes(rcPath.toPath());
      initPlayer();
      render(ambient);
    } catch (Exception e) {
      fail(e);
    }
  }

  void complication(int slot, int wireType, Object data, boolean ambient) {
    touchedSlots.add(Integer.valueOf(slot));
    if (data == null) {
      boundWireComplications.remove(Integer.valueOf(slot));
      complications.remove(Integer.valueOf(slot));
    } else {
      boundWireComplications.put(Integer.valueOf(slot), new BoundWireComplication(wireType, data));
      refreshBoundWireComplication(slot, wireType, data, effectiveTimeMillis());
    }
    render(ambient);
  }

  private void refreshBoundWireComplication(
      int slot, int wireType, Object rawData, long nowMillis) {
    Object activeData = ComplicationParcels.activeTimelineEntry(rawData, nowMillis);
    Object resources = app == null ? null : app.getResources();
    complications.put(
        Integer.valueOf(slot),
        FakeComplication.fromWireData(slot, wireType, activeData, resources, app, nowMillis));
  }

  void setFakeComplication(int slot, FakeComplication fake, boolean ambient) {
    putFakeComplication(slot, fake);
    render(ambient);
  }

  void putFakeComplication(int slot, FakeComplication fake) {
    touchedSlots.add(Integer.valueOf(slot));
    boundWireComplications.remove(Integer.valueOf(slot));
    if (fake == null) {
      complications.remove(Integer.valueOf(slot));
    } else {
      complications.put(Integer.valueOf(slot), fake);
    }
  }

  void clearFakeComplications(boolean ambient) {
    clearFakeComplicationEntries();
    render(ambient);
  }

  void clearFakeComplicationEntries() {
    touchedSlots.addAll(complications.keySet());
    boundWireComplications.clear();
    complications.clear();
  }

  void render(boolean ambient) {
    try {
      if (!boundWireComplications.isEmpty()) {
        long now = effectiveTimeMillis();
        for (Map.Entry<Integer, BoundWireComplication> e : boundWireComplications.entrySet()) {
          refreshBoundWireComplication(
              e.getKey().intValue(), e.getValue().wireType, e.getValue().rawData, now);
        }
      }
      if (player != null) {
        applyVariables(ambient);
        int wSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int hSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
        player.measure(wSpec, hSpec);
        player.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        // ColorAttribute extends PaintOperation inside CanvasOperations (Phase 2 of
        // CoreDocument.paint), whereas alpha-multiplying FloatExpressions live in top-level
        // mOperations (Phase 1). A warm-up draw populates ColorAttribute outputs and marks
        // dependent top-level FloatExpressions dirty so the real draw pass renders settled RGBA
        // colors on the very same frame (including frame 1 and palette switches).
        player.draw(new Canvas(bitmap));
        bitmap.eraseColor(0);
        Canvas canvas = new Canvas(bitmap);
        if ("CIRCLE".equalsIgnoreCase(entry.clipShape) || FormFactor.isRound()) {
          Path circle = new Path();
          circle.addOval(0f, 0f, width, height, Path.Direction.CW);
          canvas.clipPath(circle);
        }
        player.draw(canvas);
        String innerErr = readInnerPlayerError(player);
        if (innerErr != null) {
          throw new IllegalStateException("RemoteComposeView disabled: " + innerErr);
        }
        lastBitmap = bitmap;
        frames++;
        error = null;
        listener.rendered(this, bitmap, null);
        return;
      }
      // Fallback: pre-rendered PNG in dwfDir if RemoteComposePlayer could not be initialized.
      File pngFallback =
          new File(entry.dir, entry.rcFile.replaceFirst("\\.rc$", "") + ".png");
      if (pngFallback.isFile()) {
        Bitmap decoded = BitmapFactory.decodeFile(pngFallback.getAbsolutePath());
        if (decoded != null) {
          lastBitmap = decoded;
          frames++;
          error = null;
          listener.rendered(this, decoded, null);
          return;
        }
      }
      throw new IllegalStateException(
          "RemoteComposePlayer unavailable for "
              + entry.id
              + (initError != null ? ": " + initError : ""));
    } catch (RuntimeException | LinkageError e) {
      fail(e);
    }
  }

  void release() {
    player = null;
    document = null;
    lastBitmap = null;
  }

  private static final int PLAYER_MAX_BITMAP_MEMORY_BYTES = 256 * 1024 * 1024;
  private static final int PLAYER_MAX_DECOMPRESSED_BYTES = 128 * 1024 * 1024;

  private void initPlayer() {
    initError = null;
    try {
      configureRemoteCoreLimits();
      Class<?> playerType = load("androidx.compose.remote.player.view.RemoteComposePlayer");
      View view = (View) playerType.getConstructor(Context.class).newInstance(app);
      try {
        playerType
            .getMethod("setMaxOpCount", int.class)
            .invoke(view, Integer.valueOf(PLAYER_MAX_OP_COUNT));
      } catch (ReflectiveOperationException | RuntimeException ignored) {
      }
      try {
        playerType
            .getMethod("setMaxBitmapMemory", int.class)
            .invoke(view, Integer.valueOf(PLAYER_MAX_BITMAP_MEMORY_BYTES));
      } catch (ReflectiveOperationException | RuntimeException ignored) {
      }
      SimulatorBitmapLoader.installOnPlayer(playerType, view, bitmapLoader);
      installCustomTypefaceResolver(playerType, view);
      if (!setDocumentWithMutableClock(playerType, view)) {
        playerType.getMethod("setDocument", byte[].class).invoke(view, (Object) document);
      }
      player = view;
    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      Throwable cause = e;
      while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
      initError = cause.getClass().getSimpleName() + ": " + cause.getMessage();
      player = null;
    }
  }

  private void configureRemoteCoreLimits() {
    try {
      configureRemoteCoreLimits(load("androidx.compose.remote.core.Limits"));
    } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
    }
  }

  static void configureRemoteCoreLimits(Class<?> limitsType) {
    if (limitsType == null) return;
    raiseStaticIntField(limitsType, "MAX_OP_COUNT", PLAYER_MAX_OP_COUNT);
    raiseStaticIntField(limitsType, "MAX_BITMAP_MEMORY", PLAYER_MAX_BITMAP_MEMORY_BYTES);
    raiseStaticIntField(limitsType, "MAX_DECOMPRESSED_SIZE", PLAYER_MAX_DECOMPRESSED_BYTES);
    setStaticBooleanField(limitsType, "ENABLE_IMAGE_URLS", true);
    setStaticBooleanField(limitsType, "ENABLE_IMAGE_FILES", true);
  }

  private static void raiseStaticIntField(Class<?> owner, String fieldName, int minValue) {
    try {
      Field f = owner.getField(fieldName);
      int current = f.getInt(null);
      if (current < minValue) {
        f.setInt(null, minValue);
      }
    } catch (ReflectiveOperationException | RuntimeException ignored) {
    }
  }

  private static void setStaticBooleanField(Class<?> owner, String fieldName, boolean value) {
    try {
      Field f = owner.getField(fieldName);
      f.setBoolean(null, value);
    } catch (ReflectiveOperationException | RuntimeException ignored) {
    }
  }

  private void installCustomTypefaceResolver(Class<?> playerType, View view) {
    try {
      Method getResolver = playerType.getMethod("getTypefaceResolver");
      Class<?> resolverType = getResolver.getReturnType();
      Object delegate = getResolver.invoke(view);
      Method setResolver = playerType.getMethod("setTypefaceResolver", resolverType);
      Object proxy =
          Proxy.newProxyInstance(
              resolverType.getClassLoader(),
              new Class<?>[] {resolverType},
              (proxyObj, method, args) -> {
                String methodName = method.getName();
                if ("equals".equals(methodName)) {
                  return Boolean.valueOf(proxyObj == (args != null && args.length > 0 ? args[0] : null));
                }
                if ("hashCode".equals(methodName)) {
                  return Integer.valueOf(System.identityHashCode(proxyObj));
                }
                if ("toString".equals(methodName)) {
                  return "DwfCustomTypefaceResolver(" + entry.id + ")";
                }
                if ("resolve".equals(methodName)
                    && args != null
                    && args.length >= 3
                    && args[0] instanceof String) {
                  String family = (String) args[0];
                  int weight = args[1] instanceof Number ? ((Number) args[1]).intValue() : 400;
                  boolean italic =
                      args[2] instanceof Boolean && ((Boolean) args[2]).booleanValue();
                  Typeface custom = resolveCustomTypeface(family, weight, italic);
                  if (custom != null) {
                    Class<?> fontInstanceType = method.getReturnType();
                    return Proxy.newProxyInstance(
                        fontInstanceType.getClassLoader(),
                        new Class<?>[] {fontInstanceType},
                        (fiProxy, fiMethod, fiArgs) -> {
                          String fiName = fiMethod.getName();
                          if ("getTypeface".equals(fiName)
                              || "applyVariationSettings".equals(fiName)) {
                            return custom;
                          }
                          if ("equals".equals(fiName)) {
                            return Boolean.valueOf(
                                fiProxy == (fiArgs != null && fiArgs.length > 0 ? fiArgs[0] : null));
                          }
                          if ("hashCode".equals(fiName)) {
                            return Integer.valueOf(System.identityHashCode(fiProxy));
                          }
                          if ("toString".equals(fiName)) {
                            return "DwfFontInstance(" + family + ")";
                          }
                          return null;
                        });
                  }
                }
                if (delegate != null) {
                  return method.invoke(delegate, args);
                }
                return null;
              });
      setResolver.invoke(view, proxy);
    } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
    }
  }

  private Typeface resolveCustomTypeface(String family, int weight, boolean italic) {
    File fontFile = findCustomFontFile(entry.dir, family, 0);
    if (fontFile == null) return null;
    try {
      String cacheKey = fontFile.getAbsolutePath();
      Typeface base = customFontCache.get(cacheKey);
      if (base == null) {
        base = Typeface.createFromFile(fontFile);
        if (base == null) return null;
        customFontCache.put(cacheKey, base);
      }
      int clampedWeight = Math.max(100, Math.min(900, weight));
      try {
        return Typeface.create(base, clampedWeight, italic);
      } catch (RuntimeException | LinkageError ignored) {
        int style =
            clampedWeight >= 600
                ? (italic ? Typeface.BOLD_ITALIC : Typeface.BOLD)
                : (italic ? Typeface.ITALIC : Typeface.NORMAL);
        return Typeface.create(base, style);
      }
    } catch (RuntimeException | LinkageError ignored) {
      return null;
    }
  }

  static File findCustomFontFile(File entryDir, String rawFamily, int depth) {
    if (entryDir == null || rawFamily == null || depth > 4) return null;
    String stem = rawFamily.trim();
    if (stem.startsWith("@font/")) stem = stem.substring("@font/".length());
    int slash = stem.lastIndexOf('/');
    if (slash >= 0) stem = stem.substring(slash + 1);
    int dot = stem.lastIndexOf('.');
    if (dot > 0) stem = stem.substring(0, dot);
    String lower = stem.trim().toLowerCase(Locale.ROOT);
    if (lower.isEmpty()
        || "default".equals(lower)
        || "sans-serif".equals(lower)
        || "sans_serif".equals(lower)
        || "serif".equals(lower)
        || "monospace".equals(lower)
        || "roboto".equals(lower)
        || "roboto_flex".equals(lower)
        || "sync_to_device".equals(lower)) {
      return null;
    }
    File resRoot = new File(entryDir, "res");
    File[] resDirs = resRoot.listFiles();
    if (resDirs == null) return null;
    String[] exts = {".ttf", ".otf", ".ttc"};
    File xmlCandidate = null;
    for (File sub : resDirs) {
      if (!sub.isDirectory() || !sub.getName().startsWith("font")) continue;
      for (String ext : exts) {
        File exact = new File(sub, stem + ext);
        if (exact.isFile()) return exact;
      }
      File[] files = sub.listFiles();
      if (files == null) continue;
      for (File f : files) {
        if (!f.isFile()) continue;
        String fname = f.getName();
        int fdot = fname.lastIndexOf('.');
        String fstem = (fdot > 0 ? fname.substring(0, fdot) : fname).toLowerCase(Locale.ROOT);
        String fext = (fdot > 0 ? fname.substring(fdot) : "").toLowerCase(Locale.ROOT);
        if (!fstem.equals(lower)) continue;
        if (".ttf".equals(fext) || ".otf".equals(fext) || ".ttc".equals(fext)) {
          return f;
        }
        if (".xml".equals(fext) && xmlCandidate == null) {
          xmlCandidate = f;
        }
      }
    }
    if (xmlCandidate != null) {
      try {
        String xmlText = Files.readString(xmlCandidate.toPath(), StandardCharsets.UTF_8);
        Matcher m = FONT_REF_PATTERN.matcher(xmlText);
        if (m.find()) {
          return findCustomFontFile(entryDir, m.group(1), depth + 1);
        }
      } catch (IOException ignored) {
      }
    }
    return null;
  }

  private static String readInnerPlayerError(View player) {
    if (player == null) return null;
    try {
      for (Class<?> k = player.getClass(); k != null; k = k.getSuperclass()) {
        for (Field field : k.getDeclaredFields()) {
          if (!field.getType().getName().endsWith(".RemoteComposeView")) continue;
          field.setAccessible(true);
          Object inner = field.get(player);
          if (inner == null) return null;
          boolean disabled = false;
          String msg = null;
          for (Class<?> ik = inner.getClass(); ik != null; ik = ik.getSuperclass()) {
            try {
              Field disField = ik.getDeclaredField("mDisable");
              disField.setAccessible(true);
              Object dv = disField.get(inner);
              if (dv instanceof Boolean) disabled = ((Boolean) dv).booleanValue();
            } catch (NoSuchFieldException ignored) {
            }
            try {
              Field errField = ik.getDeclaredField("mErrorMessage");
              errField.setAccessible(true);
              Object ev = errField.get(inner);
              if (ev instanceof String && !((String) ev).isEmpty()) msg = (String) ev;
            } catch (NoSuchFieldException ignored) {
            }
          }
          if (disabled || (msg != null && !msg.isEmpty())) {
            return msg != null ? msg : "player disabled";
          }
          return null;
        }
      }
    } catch (ReflectiveOperationException | RuntimeException ignored) {
    }
    return null;
  }

  private boolean setDocumentWithMutableClock(Class<?> playerType, View view) {
    try {
      Class<?> remoteClockType = load("androidx.compose.remote.core.RemoteClock");
      Class<?> systemClockType = load("androidx.compose.remote.core.SystemClock");
      Class<?> remoteDocType = load("androidx.compose.remote.player.core.RemoteDocument");
      Object remoteClock =
          systemClockType
              .getConstructor(Clock.class)
              .newInstance(new MutableClock(this, null));
      Constructor<?> docCtor =
          remoteDocType.getConstructor(InputStream.class, remoteClockType);
      Object remoteDoc = docCtor.newInstance(new ByteArrayInputStream(document), remoteClock);
      SimulatorBitmapLoader.normalizeUrlBitmapDimensions(remoteDoc);
      playerType.getMethod("setDocument", remoteDocType).invoke(view, remoteDoc);
      return true;
    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      Throwable cause = e;
      while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
      initError = cause.getClass().getSimpleName() + ": " + cause.getMessage();
      return false;
    }
  }

  private void registerNames() throws ReflectiveOperationException {
    if (player == null || document == null) return;
    Object context = null;
    for (Class<?> k = player.getClass(); k != null && context == null; k = k.getSuperclass()) {
      for (Field field : k.getDeclaredFields()) {
        if (!field.getType().getName().endsWith(".RemoteComposeView")) continue;
        field.setAccessible(true);
        Object view = field.get(player);
        Field arContext = view.getClass().getDeclaredField("mARContext");
        arContext.setAccessible(true);
        context = arContext.get(view);
      }
    }
    if (context == null) return;
    Object remoteDoc = player.getClass().getMethod("getDocument").invoke(player);
    Object coreDoc =
        remoteDoc != null ? remoteDoc.getClass().getMethod("getDocument").invoke(remoteDoc) : null;
    @SuppressWarnings("unchecked")
    List<Object> ops =
        coreDoc != null
            ? (List<Object>) coreDoc.getClass().getMethod("getOperations").invoke(coreDoc)
            : null;
    if (ops == null) return;
    Class<?> named = load("androidx.compose.remote.core.operations.NamedVariable");
    Method register =
        load("androidx.compose.remote.core.RemoteContext")
            .getMethod("loadVariableName", String.class, int.class, int.class);
    for (Object op : ops) {
      if (!named.isInstance(op)) continue;
      register.invoke(
          context,
          named.getField("mVarName").get(op),
          named.getField("mVarId").getInt(op),
          named.getField("mVarType").getInt(op));
    }
  }

  private void applyVariables(boolean ambient) {
    if (player == null) return;
    try {
      registerNames();
    } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
    }
    applyVariablesTo(player, ambient);
  }

  private static int resolveOptionIndex(ConfigSpec cfg, String rawVal) {
    for (int i = 0; i < cfg.options.size(); i++) {
      if (cfg.options.get(i).id.equals(rawVal)) {
        return i;
      }
    }
    try {
      return Integer.parseInt(rawVal);
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  void applyVariablesTo(Object target, boolean ambient) {
    if (target == null) return;
    setBothInt(target, "dwf.is_ambient", ambient ? 1 : 0);

    WatchState ws = WatchState.current();
    if (ws != null) {
      setBothInt(target, "dwf.is_24_hour", ws.is24Hour() ? 1 : 0);
      setBothInt(target, "dwf.is_dst", ws.isDst() ? 1 : 0);
      setBothFloat(target, "dwf.battery_percent", (float) ws.batteryPercent());
      setBothInt(target, "dwf.battery_charging", ws.batteryCharging() ? 1 : 0);
      setBothInt(target, "dwf.battery_is_low", ws.batteryLow() ? 1 : 0);
      setBothInt(target, "dwf.unread_notifications", ws.unreadNotifications());
      setBothFloat(target, "dwf.step_goal", ws.stepGoal());

      setBothFloat(target, "metrics.heart_rate", ws.heartRate());
      setBothInt(target, "metrics.day.steps", ws.steps());
      setBothFloat(target, "metrics.day.calories", ws.calories());
      setBothFloat(target, "metrics.day.distance", ws.distanceMeters());
      setBothFloat(target, "metrics.day.flights_of_stairs", ws.floors());

      setBothFloat(target, "dwf.accel_angle_x", ws.accelAngleX());
      setBothFloat(target, "dwf.accel_angle_y", ws.accelAngleY());

      setBothInt(target, "dwf.weather.is_available", ws.weatherAvailable() ? 1 : 0);
      setBothInt(target, "dwf.weather.is_day", ws.weatherIsDay() ? 1 : 0);
      setBothInt(target, "dwf.weather.condition", ws.weatherCondition());
      setBothString(target, "dwf.weather.condition_name", ws.weatherConditionName());
      setBothFloat(target, "dwf.weather.temperature", ws.weatherTemperature());
      setBothFloat(target, "dwf.weather.temperature_high", ws.weatherTemperatureHigh());
      setBothFloat(target, "dwf.weather.temperature_low", ws.weatherTemperatureLow());
      setBothInt(target, "dwf.weather.temperature_unit", ws.weatherCelsius() ? 1 : 0);
      setBothInt(target, "dwf.weather.chance_of_precipitation", ws.weatherPrecipitation());
      setBothInt(target, "dwf.weather.uv_index", ws.weatherUvIndex());
      setBothString(target, "dwf.weather.last_updated_text", ws.weatherLastUpdated());

      int[] hourlyTempOffsets = {0, 1, 2, 1, 0, -1, -2, -3};
      int[] hourlyCondDefaults = {14, 8, 8, 2, 6, 14, 1, 1};
      for (int hr = 1; hr <= 24; hr++) {
        WatchState.HourlyWeather hw = ws.hourlyWeather().get(Integer.valueOf(hr));
        setBothInt(
            target, "dwf.weather.hours." + hr + ".is_available", ws.weatherAvailable() ? 1 : 0);
        if (hw != null) {
          setBothInt(target, "dwf.weather.hours." + hr + ".is_day", hw.isDay ? 1 : 0);
          setBothInt(target, "dwf.weather.hours." + hr + ".condition", hw.condition);
          setBothFloat(target, "dwf.weather.hours." + hr + ".temperature", hw.temperature);
          setBothInt(target, "dwf.weather.hours." + hr + ".uv_index", hw.uvIndex);
        } else {
          setBothInt(target, "dwf.weather.hours." + hr + ".is_day", hr <= 6 ? 1 : 0);
          setBothInt(
              target,
              "dwf.weather.hours." + hr + ".condition",
              hourlyCondDefaults[(hr - 1) % hourlyCondDefaults.length]);
          setBothFloat(
              target,
              "dwf.weather.hours." + hr + ".temperature",
              ws.weatherTemperature() + hourlyTempOffsets[(hr - 1) % hourlyTempOffsets.length]);
          setBothInt(target, "dwf.weather.hours." + hr + ".uv_index", Math.max(0, 6 - hr));
        }
      }
      int[] dailyCondDefaults = {8, 14, 2, 6, 8};
      for (int dy = 1; dy <= 7; dy++) {
        WatchState.DailyWeather dw = ws.dailyWeather().get(Integer.valueOf(dy));
        setBothInt(
            target, "dwf.weather.days." + dy + ".is_available", ws.weatherAvailable() ? 1 : 0);
        if (dw != null) {
          setBothInt(target, "dwf.weather.days." + dy + ".condition_day", dw.conditionDay);
          setBothInt(target, "dwf.weather.days." + dy + ".condition_night", dw.conditionNight);
          setBothFloat(target, "dwf.weather.days." + dy + ".temperature_high", dw.temperatureHigh);
          setBothFloat(target, "dwf.weather.days." + dy + ".temperature_low", dw.temperatureLow);
          setBothInt(
              target,
              "dwf.weather.days." + dy + ".chance_of_precipitation",
              dw.chanceOfPrecipitation);
          setBothInt(
              target,
              "dwf.weather.days." + dy + ".chance_of_precipitation_night",
              dw.chanceOfPrecipitationNight);
          setBothInt(target, "dwf.weather.days." + dy + ".uv_index", dw.uvIndex);
        } else {
          int fallbackDayCond = dailyCondDefaults[(dy - 1) % dailyCondDefaults.length];
          setBothInt(target, "dwf.weather.days." + dy + ".condition_day", fallbackDayCond);
          setBothInt(target, "dwf.weather.days." + dy + ".condition_night", 1);
          setBothFloat(
              target,
              "dwf.weather.days." + dy + ".temperature_high",
              ws.weatherTemperature() + (3 - (dy % 2)));
          setBothFloat(
              target,
              "dwf.weather.days." + dy + ".temperature_low",
              ws.weatherTemperature() - (5 + (dy % 2)));
          setBothInt(
              target,
              "dwf.weather.days." + dy + ".chance_of_precipitation",
              fallbackDayCond == 6 ? 65 : 15);
          setBothInt(target, "dwf.weather.days." + dy + ".chance_of_precipitation_night", 10);
          setBothInt(target, "dwf.weather.days." + dy + ".uv_index", ws.weatherUvIndex());
        }
      }
    }

    for (ConfigSpec cfg : entry.configurations) {
      String rawVal = configValues.getOrDefault(cfg.id, cfg.defaultValue);
      if ("PHOTOS".equalsIgnoreCase(cfg.kind)) {
        if (SimulatorBitmapLoader.isImageUrl(rawVal)
            || (rawVal != null && !rawVal.isEmpty() && !rawVal.equals(cfg.defaultValue))) {
          Object photoBmp = bitmapLoader.decodeBitmap(rawVal);
          if (photoBmp != null) {
            setBothBitmap(target, "dwf.config." + cfg.id, photoBmp);
          }
        } else {
          clearBothBitmap(target, "dwf.config." + cfg.id);
        }
      } else if ("BOOLEAN".equalsIgnoreCase(cfg.kind)) {
        int b = ("true".equalsIgnoreCase(rawVal) || "1".equals(rawVal)) ? 1 : 0;
        setBothInt(target, "dwf.config." + cfg.id, b);
      } else {
        int optionIndex = resolveOptionIndex(cfg, rawVal);
        setBothInt(target, "dwf.config." + cfg.id, optionIndex);
        if ("COLOR".equalsIgnoreCase(cfg.kind)
            && optionIndex >= 0
            && optionIndex < cfg.options.size()) {
          List<Integer> colors = cfg.options.get(optionIndex).colorArgbs;
          for (int cIdx = 0; cIdx < colors.size(); cIdx++) {
            setBothColor(target, "dwf.color." + cfg.id + "." + cIdx, colors.get(cIdx).intValue());
          }
        }
      }
    }

    for (Integer slotObj : touchedSlots) {
      if (!complications.containsKey(slotObj)) {
        resetComplicationVariables(target, slotObj.intValue());
      }
    }

    if (ws != null) {
      for (Map.Entry<Integer, String> p : entry.defaultProviders.entrySet()) {
        if (touchedSlots.contains(p.getKey()) || activeFlavorComplications.containsKey(p.getKey())) {
          continue;
        }
        applyDefaultProvider(target, p.getKey().intValue(), p.getValue(), false, ws);
      }
    }

    for (Map.Entry<Integer, FlavorComplicationPolicy> fp : activeFlavorComplications.entrySet()) {
      Integer slotObj = fp.getKey();
      if (touchedSlots.contains(slotObj)) continue;
      int slot = slotObj.intValue();
      FlavorComplicationPolicy pol = fp.getValue();
      String prov =
          pol.defaultSystemProvider != null
              ? pol.defaultSystemProvider.trim()
              : entry.defaultProviders.getOrDefault(slotObj, "");
      String pType =
          pol.defaultSystemProviderType != null ? pol.defaultSystemProviderType.trim() : "";
      if ("EMPTY".equalsIgnoreCase(prov)
          || "NONE".equalsIgnoreCase(prov)
          || "EMPTY".equalsIgnoreCase(pType)
          || "NOT_CONFIGURED".equalsIgnoreCase(pType)) {
        resetComplicationVariables(target, slot);
      } else {
        if (!pType.isEmpty()) {
          int wire = parseWireComplicationType(pType);
          int dwfCode = parseDwfComplicationTypeCode(pType, wire);
          setBothInt(target, "dwf.complication." + slot + ".type", dwfCode);
        }
        if (ws != null && !prov.isEmpty()) {
          applyDefaultProvider(target, slot, prov, true, ws);
        }
      }
    }

    for (Map.Entry<Integer, FakeComplication> mapEntry : complications.entrySet()) {
      int slot = mapEntry.getKey().intValue();
      FakeComplication comp = mapEntry.getValue();
      setBothInt(target, "dwf.complication." + slot + ".type", comp.dwfTypeCode);
      setBothString(
          target, "dwf.complication." + slot + ".text", comp.text != null ? comp.text : "");
      setBothString(
          target, "dwf.complication." + slot + ".title", comp.title != null ? comp.title : "");
      setBothFloat(
          target,
          "dwf.complication." + slot + ".value",
          comp.value != null ? comp.value.floatValue() : 0f);
      setBothFloat(
          target,
          "dwf.complication." + slot + ".min",
          comp.min != null ? comp.min.floatValue() : 0f);
      setBothFloat(
          target,
          "dwf.complication." + slot + ".max",
          comp.max != null ? comp.max.floatValue() : 100f);
      setBothInt(
          target,
          "dwf.complication." + slot + ".has_monochromatic_image",
          comp.hasMonochromaticImage != null && comp.hasMonochromaticImage.booleanValue() ? 1 : 0);
      setBothInt(
          target,
          "dwf.complication." + slot + ".has_small_image",
          comp.hasSmallImage != null && comp.hasSmallImage.booleanValue() ? 1 : 0);
      setBothInt(
          target,
          "dwf.complication." + slot + ".has_photo_image",
          comp.hasPhotoImage != null && comp.hasPhotoImage.booleanValue() ? 1 : 0);

      Object monoBmp =
          comp.monochromaticBitmap != null
              ? comp.monochromaticBitmap
              : (comp.monochromaticImage != null
                  ? bitmapLoader.decodeBitmap(comp.monochromaticImage)
                  : null);
      if (monoBmp != null) {
        setBothBitmap(target, "dwf.complication." + slot + ".monochromatic_image", monoBmp);
      } else if (comp.monochromaticImage == null && comp.monochromaticBitmap == null) {
        clearBothBitmap(target, "dwf.complication." + slot + ".monochromatic_image");
      }

      Object smallBmp =
          comp.smallBitmap != null
              ? comp.smallBitmap
              : (comp.smallImage != null ? bitmapLoader.decodeBitmap(comp.smallImage) : null);
      if (smallBmp != null) {
        setBothBitmap(target, "dwf.complication." + slot + ".small_image", smallBmp);
      } else if (comp.smallImage == null && comp.smallBitmap == null) {
        clearBothBitmap(target, "dwf.complication." + slot + ".small_image");
      }

      Object photoBmp =
          comp.photoBitmap != null
              ? comp.photoBitmap
              : (comp.photoImage != null ? bitmapLoader.decodeBitmap(comp.photoImage) : null);
      if (photoBmp != null) {
        setBothBitmap(target, "dwf.complication." + slot + ".photo_image", photoBmp);
      } else if (comp.photoImage == null && comp.photoBitmap == null) {
        clearBothBitmap(target, "dwf.complication." + slot + ".photo_image");
      }

      setBothInt(
          target,
          "dwf.complication." + slot + ".has_weighted_elements",
          comp.dwfTypeCode == 8 || !comp.weights.isEmpty() ? 1 : 0);
      setBothInt(
          target,
          "dwf.complication." + slot + ".color_count",
          Math.min(comp.colors.size(), 8));
      setBothInt(
          target,
          "dwf.complication." + slot + ".has_ranged_colors",
          !comp.colors.isEmpty() && comp.dwfTypeCode == 3 ? 1 : 0);
      setBothInt(
          target,
          "dwf.complication." + slot + ".has_goal_colors",
          !comp.colors.isEmpty() && comp.dwfTypeCode == 7 ? 1 : 0);
      for (int i = 0; i < 8; i++) {
        int col = i < comp.colors.size() ? comp.colors.get(i).intValue() : 0;
        setBothColor(target, "dwf.complication." + slot + ".color." + i, col);
        float w = i < comp.weights.size() ? comp.weights.get(i).floatValue() : 0f;
        setBothFloat(target, "dwf.complication." + slot + ".weight." + i, w);
      }
    }
  }

  static FakeComplication syntheticSystemProviderComplication(
      int slot, String provider, String providerType, WatchState ws) {
    if (provider == null) return null;
    String p = provider.trim().toUpperCase(Locale.ROOT);
    String t = providerType != null ? providerType.trim() : "";
    if (p.isEmpty()
        || "EMPTY".equals(p)
        || "NONE".equals(p)
        || "EMPTY".equalsIgnoreCase(t)
        || "NOT_CONFIGURED".equalsIgnoreCase(t)) {
      return null;
    }
    int wireType = parseWireComplicationType(t.isEmpty() ? "SHORT_TEXT" : t);
    int dwfCode = parseDwfComplicationTypeCode(t.isEmpty() ? "SHORT_TEXT" : t, wireType);
    int steps = ws != null ? ws.steps() : 5200;
    float stepGoal = ws != null ? ws.stepGoal() : 10000f;
    int battery = ws != null ? ws.batteryPercent() : 85;
    float hr = ws != null ? ws.heartRate() : 72f;
    int notifs = ws != null ? ws.unreadNotifications() : 0;
    String text;
    String title;
    float value;
    float min = 0f;
    float max;
    if (p.contains("STEP")) {
      text = Integer.toString(steps);
      title = "STEPS";
      value = (float) steps;
      max = stepGoal;
    } else if (p.contains("BATTERY")) {
      text = battery + "%";
      title = "BAT";
      value = (float) battery;
      max = 100f;
    } else if (p.contains("HEART")) {
      text = Integer.toString(Math.round(hr));
      title = "BPM";
      value = hr;
      max = 200f;
    } else if (p.contains("NOTIFICATION")) {
      text = Integer.toString(notifs);
      title = "ALERTS";
      value = (float) notifs;
      max = 10f;
    } else {
      return null;
    }
    return new FakeComplication(
        slot,
        wireType,
        dwfCode,
        text,
        title,
        Float.valueOf(value),
        Float.valueOf(min),
        Float.valueOf(max),
        Boolean.TRUE,
        null,
        null,
        Collections.emptyList(),
        Collections.emptyList());
  }

  /**
   * Drives a slot's default system provider from live {@link WatchState}. When {@code
   * includeTitleAndIcon} is true (flavor switch), also populates a sensible title and monochromatic
   * icon flag so slots whose compiled default was {@code EMPTY} render full content.
   */
  private void applyDefaultProvider(
      Object target, int slot, String provider, boolean includeTitleAndIcon, WatchState ws) {
    String p = provider.toUpperCase(Locale.ROOT);
    String prefix = "dwf.complication." + slot;
    if (p.contains("STEP")) {
      setBothString(target, prefix + ".text", Integer.toString(ws.steps()));
      if (includeTitleAndIcon) {
        setBothString(target, prefix + ".title", "STEPS");
        setBothInt(target, prefix + ".has_monochromatic_image", 1);
      }
      setBothFloat(target, prefix + ".value", (float) ws.steps());
      setBothFloat(target, prefix + ".min", 0f);
      setBothFloat(target, prefix + ".max", ws.stepGoal());
    } else if (p.contains("BATTERY")) {
      setBothString(target, prefix + ".text", ws.batteryPercent() + "%");
      if (includeTitleAndIcon) {
        setBothString(target, prefix + ".title", "BAT");
        setBothInt(target, prefix + ".has_monochromatic_image", 1);
      }
      setBothFloat(target, prefix + ".value", (float) ws.batteryPercent());
      setBothFloat(target, prefix + ".min", 0f);
      setBothFloat(target, prefix + ".max", 100f);
    } else if (p.contains("HEART")) {
      setBothString(target, prefix + ".text", Integer.toString(Math.round(ws.heartRate())));
      if (includeTitleAndIcon) {
        setBothString(target, prefix + ".title", "BPM");
        setBothInt(target, prefix + ".has_monochromatic_image", 1);
      }
      setBothFloat(target, prefix + ".value", ws.heartRate());
      setBothFloat(target, prefix + ".min", 0f);
      setBothFloat(target, prefix + ".max", 200f);
    } else if (p.contains("NOTIFICATION")) {
      setBothString(target, prefix + ".text", Integer.toString(ws.unreadNotifications()));
      if (includeTitleAndIcon) {
        setBothString(target, prefix + ".title", "ALERTS");
        setBothInt(target, prefix + ".has_monochromatic_image", 1);
      }
      setBothFloat(target, prefix + ".value", (float) ws.unreadNotifications());
      setBothFloat(target, prefix + ".min", 0f);
      setBothFloat(target, prefix + ".max", 10f);
    }
  }

  private void resetComplicationVariables(Object target, int slot) {
    setBothInt(target, "dwf.complication." + slot + ".type", 0);
    setBothString(target, "dwf.complication." + slot + ".text", "");
    setBothString(target, "dwf.complication." + slot + ".title", "");
    setBothFloat(target, "dwf.complication." + slot + ".value", 0f);
    setBothFloat(target, "dwf.complication." + slot + ".min", 0f);
    setBothFloat(target, "dwf.complication." + slot + ".max", 100f);
    setBothInt(target, "dwf.complication." + slot + ".has_monochromatic_image", 0);
    setBothInt(target, "dwf.complication." + slot + ".has_small_image", 0);
    setBothInt(target, "dwf.complication." + slot + ".has_photo_image", 0);
    setBothInt(target, "dwf.complication." + slot + ".has_weighted_elements", 0);
    setBothInt(target, "dwf.complication." + slot + ".has_ranged_colors", 0);
    setBothInt(target, "dwf.complication." + slot + ".has_goal_colors", 0);
    setBothInt(target, "dwf.complication." + slot + ".color_count", 0);
    for (int i = 0; i < 8; i++) {
      setBothColor(target, "dwf.complication." + slot + ".color." + i, 0);
      setBothFloat(target, "dwf.complication." + slot + ".weight." + i, 0f);
    }
    clearBothBitmap(target, "dwf.complication." + slot + ".monochromatic_image");
    clearBothBitmap(target, "dwf.complication." + slot + ".small_image");
    clearBothBitmap(target, "dwf.complication." + slot + ".photo_image");
  }

  private void setBothInt(Object target, String key, int value) {
    setUserInt(target, key, value);
    setUserInt(target, "USER:" + key, value);
  }

  private void setBothFloat(Object target, String key, float value) {
    setUserFloat(target, key, value);
    setUserFloat(target, "USER:" + key, value);
  }

  private void setBothString(Object target, String key, String value) {
    setUserString(target, key, value);
    setUserString(target, "USER:" + key, value);
  }

  private void setBothColor(Object target, String key, int argb) {
    setUserColor(target, key, argb);
    setUserColor(target, "USER:" + key, argb);
  }

  private void setBothBitmap(Object target, String key, Object bitmap) {
    setUserBitmap(target, key, bitmap);
    setUserBitmap(target, "USER:" + key, bitmap);
  }

  private void clearBothBitmap(Object target, String key) {
    clearUserBitmap(target, key);
    clearUserBitmap(target, "USER:" + key);
  }

  private void setUserInt(Object target, String name, int value) {
    try {
      target
          .getClass()
          .getMethod("setUserLocalInt", String.class, int.class)
          .invoke(target, name, value);
    } catch (ReflectiveOperationException | RuntimeException ignored) {
    }
  }

  private void setUserFloat(Object target, String name, float value) {
    try {
      target
          .getClass()
          .getMethod("setUserLocalFloat", String.class, float.class)
          .invoke(target, name, value);
    } catch (ReflectiveOperationException | RuntimeException ignored) {
    }
  }

  private void setUserColor(Object target, String name, int argb) {
    boolean set = false;
    if (!name.startsWith("USER:")) {
      try {
        target
            .getClass()
            .getMethod("setColor", String.class, int.class)
            .invoke(target, name, argb);
        set = true;
      } catch (ReflectiveOperationException | RuntimeException ignored) {
      }
    }
    try {
      target
          .getClass()
          .getMethod("setUserLocalColor", String.class, int.class)
          .invoke(target, name, argb);
      return;
    } catch (ReflectiveOperationException | RuntimeException ignored) {
    }
    if (!set) {
      setUserInt(target, name, argb);
    }
  }

  private void setUserString(Object target, String name, String value) {
    try {
      target
          .getClass()
          .getMethod("setUserLocalString", String.class, String.class)
          .invoke(target, name, value);
    } catch (ReflectiveOperationException | RuntimeException ignored) {
    }
  }

  private void setUserBitmap(Object target, String name, Object bitmap) {
    if (bitmap == null) return;
    try {
      for (Method m : target.getClass().getMethods()) {
        if ("setUserLocalBitmap".equals(m.getName()) && m.getParameterCount() == 2) {
          m.invoke(target, name, bitmap);
          return;
        }
      }
    } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
    }
  }

  private void clearUserBitmap(Object target, String name) {
    try {
      target.getClass().getMethod("clearUserLocalBitmap", String.class).invoke(target, name);
    } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
    }
  }

  private static final class AndroidWireIconDecoder {
    static Object decode(Object data, String getter, Object contextObj, int fallbackSize) {
      if (!(contextObj instanceof Context)) return null;
      Context context = (Context) contextObj;
      try {
        Object iconObj = data.getClass().getMethod(getter).invoke(data);
        if (iconObj == null) return null;
        Method loadDrawable = iconObj.getClass().getMethod("loadDrawable", Context.class);
        Object drawableObj = loadDrawable.invoke(iconObj, context);
        if (!(drawableObj instanceof android.graphics.drawable.Drawable)) return null;
        android.graphics.drawable.Drawable d = (android.graphics.drawable.Drawable) drawableObj;
        int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : fallbackSize;
        int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : fallbackSize;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        if (bmp == null) return null;
        Canvas canvas = new Canvas(bmp);
        d.setBounds(0, 0, w, h);
        d.draw(canvas);
        return bmp;
      } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        return null;
      }
    }
  }

  private Class<?> load(String name) throws ClassNotFoundException {
    return Class.forName(name, true, app.getClassLoader());
  }

  private void fail(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
    error = cause.getClass().getSimpleName() + ": " + cause.getMessage();
    if (listener != null) {
      listener.rendered(this, null, error);
    }
  }
}
