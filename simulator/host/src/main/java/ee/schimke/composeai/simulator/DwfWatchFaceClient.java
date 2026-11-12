package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Path;
import android.view.View;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

  interface Listener {
    void rendered(DwfWatchFaceClient face, Bitmap frame, String error);
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
      Boolean hasMono =
          c.has("hasMonochromaticImage")
              ? Boolean.valueOf(c.getBoolean("hasMonochromaticImage"))
              : null;
      Boolean hasSmall =
          c.has("hasSmallImage") ? Boolean.valueOf(c.getBoolean("hasSmallImage")) : null;
      Boolean hasPhoto =
          c.has("hasPhotoImage") ? Boolean.valueOf(c.getBoolean("hasPhotoImage")) : null;
      return new FakeComplication(
          slot, wireType, dwfTypeCode, text, title, value, min, max, hasMono, hasSmall, hasPhoto);
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
      this.id = id;
      this.label = label;
      this.dir = dir;
      this.rcFile = rcFile;
      this.width = width;
      this.height = height;
      this.clipShape = clipShape;
      this.slots = slots;
      this.configurations = configurations;
    }
  }

  private final Application app;
  private final Entry entry;
  private final Listener listener;
  private final Map<String, String> configValues = new LinkedHashMap<>();
  private final Map<Integer, FakeComplication> complications = new LinkedHashMap<>();

  private byte[] document;
  private View player;
  private int width;
  private int height;
  private int frames;
  private String error;

  DwfWatchFaceClient(Application app, Entry entry, Listener listener) {
    this.app = app;
    this.entry = entry;
    this.listener = listener;
    for (ConfigSpec cfg : entry.configurations) {
      configValues.put(cfg.id, cfg.defaultValue);
    }
  }

  /** Loads compiled declarative watch faces from {@code -Dcomposeai.simulator.dwfDir}. */
  static List<Entry> availableFromProperty() {
    String dirProp = System.getProperty(DWF_DIR_PROPERTY);
    if (dirProp == null || dirProp.isEmpty()) return Collections.emptyList();
    File dir = new File(dirProp);
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
        JSONArray slotsArr = item.optJSONArray("slots");
        if (slotsArr != null) {
          for (int s = 0; s < slotsArr.length(); s++) {
            JSONObject slotObj = slotsArr.getJSONObject(s);
            int slotId = slotObj.getInt("id");
            boolean enabled = slotObj.optBoolean("enabled", true);
            JSONArray typesArr = slotObj.optJSONArray("supportedTypes");
            int[] types = new int[typesArr == null ? 0 : typesArr.length()];
            if (typesArr != null) {
              for (int t = 0; t < typesArr.length(); t++) types[t] = typesArr.getInt(t);
            }
            slots.add(new WatchFaceClient.Slot(slotId, types, enabled));
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
        result.add(new Entry(id, label, dir, rcFile, w, h, clipShape, slots, configs));
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

  List<WatchFaceClient.Slot> slots() {
    return entry.slots;
  }

  Map<Integer, FakeComplication> fakeComplications() {
    return Collections.unmodifiableMap(complications);
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

  void setConfig(String configId, String value) {
    ConfigSpec spec = null;
    for (ConfigSpec c : entry.configurations) {
      if (c.id.equals(configId)) {
        spec = c;
        break;
      }
    }
    if (spec == null) {
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
    if (data == null) {
      complications.remove(slot);
    } else {
      String text = ComplicationSourceClient.text(data, app.getResources());
      complications.put(
          slot,
          new FakeComplication(
              slot,
              wireType,
              wireTypeToDwfTypeCode(wireType),
              text,
              null,
              null,
              null,
              null,
              null,
              null,
              null));
    }
    render(ambient);
  }

  void setFakeComplication(int slot, FakeComplication fake, boolean ambient) {
    if (fake == null) {
      complications.remove(slot);
    } else {
      complications.put(slot, fake);
    }
    render(ambient);
  }

  void clearFakeComplications(boolean ambient) {
    complications.clear();
    render(ambient);
  }

  void render(boolean ambient) {
    try {
      if (player != null) {
        applyVariables(ambient);
        int wSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int hSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
        player.measure(wSpec, hSpec);
        player.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        if ("CIRCLE".equalsIgnoreCase(entry.clipShape) || FormFactor.isRound()) {
          Path circle = new Path();
          circle.addOval(0f, 0f, width, height, Path.Direction.CW);
          canvas.clipPath(circle);
        }
        player.draw(canvas);
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
          frames++;
          error = null;
          listener.rendered(this, decoded, null);
          return;
        }
      }
      throw new IllegalStateException("RemoteComposePlayer unavailable for " + entry.id);
    } catch (RuntimeException e) {
      fail(e);
    }
  }

  void release() {
    player = null;
    document = null;
  }

  private void initPlayer() {
    try {
      Class<?> playerType = load("androidx.compose.remote.player.view.RemoteComposePlayer");
      View view = (View) playerType.getConstructor(Context.class).newInstance(app);
      playerType.getMethod("setDocument", byte[].class).invoke(view, (Object) document);
      player = view;
    } catch (ReflectiveOperationException | RuntimeException e) {
      player = null;
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
    Class<?> bufferType = load("androidx.compose.remote.core.RemoteComposeBuffer");
    Object buffer =
        bufferType
            .getMethod("fromInputStream", InputStream.class)
            .invoke(null, new ByteArrayInputStream(document));
    ArrayList<Object> ops = new ArrayList<>();
    bufferType.getMethod("inflateFromBuffer", ArrayList.class).invoke(buffer, ops);
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
    } catch (ReflectiveOperationException | RuntimeException ignored) {
    }
    setBothInt("dwf.is_ambient", ambient ? 1 : 0);

    for (ConfigSpec cfg : entry.configurations) {
      String rawVal = configValues.getOrDefault(cfg.id, cfg.defaultValue);
      if ("BOOLEAN".equalsIgnoreCase(cfg.kind)) {
        int b = ("true".equalsIgnoreCase(rawVal) || "1".equals(rawVal)) ? 1 : 0;
        setBothInt("dwf.config." + cfg.id, b);
      } else {
        int optionIndex = -1;
        for (int i = 0; i < cfg.options.size(); i++) {
          if (cfg.options.get(i).id.equals(rawVal)) {
            optionIndex = i;
            break;
          }
        }
        if (optionIndex < 0) {
          try {
            optionIndex = Integer.parseInt(rawVal);
          } catch (NumberFormatException ignored) {
            optionIndex = 0;
          }
        }
        setBothInt("dwf.config." + cfg.id, optionIndex);
        if ("COLOR".equalsIgnoreCase(cfg.kind)
            && optionIndex >= 0
            && optionIndex < cfg.options.size()) {
          List<Integer> colors = cfg.options.get(optionIndex).colorArgbs;
          for (int cIdx = 0; cIdx < colors.size(); cIdx++) {
            setBothColor("dwf.color." + cfg.id + "." + cIdx, colors.get(cIdx).intValue());
          }
        }
      }
    }

    for (FakeComplication comp : complications.values()) {
      int slot = comp.slot;
      setBothInt("dwf.complication." + slot + ".type", comp.dwfTypeCode);
      if (comp.text != null) {
        setBothString("dwf.complication." + slot + ".text", comp.text);
      }
      if (comp.title != null) {
        setBothString("dwf.complication." + slot + ".title", comp.title);
      }
      if (comp.value != null) {
        setBothFloat("dwf.complication." + slot + ".value", comp.value.floatValue());
      }
      if (comp.min != null) {
        setBothFloat("dwf.complication." + slot + ".min", comp.min.floatValue());
      }
      if (comp.max != null) {
        setBothFloat("dwf.complication." + slot + ".max", comp.max.floatValue());
      }
      if (comp.hasMonochromaticImage != null) {
        setBothInt(
            "dwf.complication." + slot + ".has_monochromatic_image",
            comp.hasMonochromaticImage.booleanValue() ? 1 : 0);
      }
      if (comp.hasSmallImage != null) {
        setBothInt(
            "dwf.complication." + slot + ".has_small_image",
            comp.hasSmallImage.booleanValue() ? 1 : 0);
      }
      if (comp.hasPhotoImage != null) {
        setBothInt(
            "dwf.complication." + slot + ".has_photo_image",
            comp.hasPhotoImage.booleanValue() ? 1 : 0);
      }
    }
  }

  private void setBothInt(String key, int value) {
    setUserInt(key, value);
    setUserInt("USER:" + key, value);
  }

  private void setBothFloat(String key, float value) {
    setUserFloat(key, value);
    setUserFloat("USER:" + key, value);
  }

  private void setBothString(String key, String value) {
    setUserString(key, value);
    setUserString("USER:" + key, value);
  }

  private void setBothColor(String key, int argb) {
    setUserColor(key, argb);
    setUserColor("USER:" + key, argb);
  }

  private void setUserInt(String name, int value) {
    try {
      player
          .getClass()
          .getMethod("setUserLocalInt", String.class, int.class)
          .invoke(player, name, value);
    } catch (ReflectiveOperationException | RuntimeException ignored) {
    }
  }

  private void setUserFloat(String name, float value) {
    try {
      player
          .getClass()
          .getMethod("setUserLocalFloat", String.class, float.class)
          .invoke(player, name, value);
    } catch (ReflectiveOperationException | RuntimeException ignored) {
    }
  }

  private void setUserColor(String name, int argb) {
    try {
      player
          .getClass()
          .getMethod("setUserLocalColor", String.class, int.class)
          .invoke(player, name, argb);
      return;
    } catch (ReflectiveOperationException | RuntimeException ignored) {
    }
    setUserInt(name, argb);
  }

  private void setUserString(String name, String value) {
    try {
      player
          .getClass()
          .getMethod("setUserLocalString", String.class, String.class)
          .invoke(player, name, value);
    } catch (ReflectiveOperationException | RuntimeException ignored) {
    }
  }

  private Class<?> load(String name) throws ClassNotFoundException {
    return Class.forName(name, true, app.getClassLoader());
  }

  private void fail(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
    error = cause.getClass().getSimpleName() + ": " + cause.getMessage();
    listener.rendered(this, null, error);
  }
}
