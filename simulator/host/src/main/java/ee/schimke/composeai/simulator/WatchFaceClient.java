package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.text.TextUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Hosts a watch face ({@code androidx.wear.watchface.WatchFaceService} or {@code
 * DeclarativeWatchFaceRuntime}'s Watch Face Format control service) the way Wear's watch face
 * editor and Android Studio's previews do: through {@code IWatchFaceControlService} and its
 * headless instance, rendering frames to a bitmap with no wallpaper surface.
 *
 * <p>Speaks the stable {@code IWatchFaceControlService} / {@code IHeadlessWatchFace} Binder
 * transactions and {@code VersionedParcelable} ({@code ParcelImpl}) wire layout directly on {@link
 * Parcel}, rather than reflecting on AIDL {@code $Stub} classes or wire DTO constructors and
 * getters — R8-minified APKs (including {@code DeclarativeWatchFaceRuntime.apk}) rename {@code
 * $Stub} classes and strip unused DTO constructors and getters while keeping the wire format and
 * {@code *Parcelizer} classes intact.
 *
 * <p>The control service answers on the main looper, which the simulator's cycle drives, so every
 * call into it runs on a worker thread and the frame comes back to {@link Listener} on the main
 * looper.
 */
final class WatchFaceClient {

  interface Listener {
    /** A frame was rendered, or rendering failed with [error] (and [frame] is null). */
    void rendered(WatchFaceClient face, Bitmap frame, String error);
  }

  /** {@code WatchFaceControlService.ACTION_WATCHFACE_CONTROL_SERVICE}. */
  static final String CONTROL_ACTION = "com.google.android.wearable.action.WATCH_FACE_CONTROL";

  static final String CONTROL_DESCRIPTOR =
      "androidx.wear.watchface.control.IWatchFaceControlService";
  static final String HEADLESS_DESCRIPTOR = "androidx.wear.watchface.control.IHeadlessWatchFace";

  /** {@code IWatchFaceControlService.createHeadlessWatchFaceInstance} (transaction 4). */
  static final int TRANSACTION_CREATE_HEADLESS = IBinder.FIRST_CALL_TRANSACTION + 3;

  /** {@code IHeadlessWatchFace} transactions (verified against the published AIDL). */
  static final int TRANSACTION_GET_USER_STYLE_SCHEMA = IBinder.FIRST_CALL_TRANSACTION + 3; // 4
  static final int TRANSACTION_GET_COMPLICATION_STATE = IBinder.FIRST_CALL_TRANSACTION + 4; // 5
  static final int TRANSACTION_RENDER_WATCH_FACE_TO_BITMAP =
      IBinder.FIRST_CALL_TRANSACTION + 5; // 6
  static final int TRANSACTION_RELEASE = IBinder.FIRST_CALL_TRANSACTION + 7; // 8

  private static final String PARCEL_IMPL = "androidx.versionedparcelable.ParcelImpl";
  private static final String HEADLESS_PARAMS_WIRE =
      "androidx.wear.watchface.control.data.HeadlessWatchFaceInstanceParamsParcelizer";
  private static final String DEVICE_CONFIG_WIRE =
      "androidx.wear.watchface.data.DeviceConfigParcelizer";
  private static final String WATCH_FACE_RENDER_PARAMS_WIRE =
      "androidx.wear.watchface.control.data.WatchFaceRenderParamsParcelizer";
  private static final String RENDER_PARAMETERS_WIRE =
      "androidx.wear.watchface.data.RenderParametersWireFormatParcelizer";
  private static final String USER_STYLE_WIRE =
      "androidx.wear.watchface.style.data.UserStyleWireFormatParcelizer";
  private static final String USER_STYLE_SCHEMA_WIRE =
      "androidx.wear.watchface.style.data.UserStyleSchemaWireFormatParcelizer";
  private static final String ID_AND_COMPLICATION_DATA_WIRE =
      "androidx.wear.watchface.data.IdAndComplicationDataWireFormatParcelizer";
  private static final String ID_AND_COMPLICATION_STATE_WIRE =
      "androidx.wear.watchface.data.IdAndComplicationStateWireFormatParcelizer";
  private static final String COMPLICATION_STATE_WIRE =
      "androidx.wear.watchface.data.ComplicationStateWireFormatParcelizer";

  /** {@code VersionedParcel} collection element type tags. */
  private static final int COLLECTION_TYPE_VERSIONED_PARCELABLE = 1;
  private static final int COLLECTION_TYPE_SERIALIZABLE = 3;
  private static final int COLLECTION_TYPE_STRING = 4;
  private static final int COLLECTION_TYPE_INT = 7;

  /** {@code DrawMode.INTERACTIVE} / {@code AMBIENT}. */
  static final int DRAW_INTERACTIVE = 0;

  static final int DRAW_AMBIENT = 3;
  /** {@code WatchFaceLayer.BASE | COMPLICATIONS | COMPLICATIONS_OVERLAY}. */
  private static final int ALL_LAYERS = 0b111;
  /** {@code RenderParametersWireFormat.ELEMENT_TYPE_NONE}. */
  private static final int ELEMENT_NONE = 0;

  /** Parsed option from {@code UserStyleSchemaWireFormat}. */
  static final class StyleOption {
    final String id;
    final byte[] rawId;
    final String displayName;
    final List<Integer> colorArgbs;

    StyleOption(String id, byte[] rawId, String displayName, List<Integer> colorArgbs) {
      this.id = id;
      this.rawId = rawId;
      this.displayName = displayName;
      this.colorArgbs = colorArgbs == null ? Collections.emptyList() : colorArgbs;
    }
  }

  /** Parsed setting from {@code UserStyleSchemaWireFormat}. */
  static final class StyleSetting {
    final String id;
    final String kind;
    final String displayName;
    final int defaultOptionIndex;
    final List<StyleOption> options;

    StyleSetting(
        String id,
        String kind,
        String displayName,
        int defaultOptionIndex,
        List<StyleOption> options) {
      this.id = id;
      this.kind = kind;
      this.displayName = displayName;
      this.defaultOptionIndex = defaultOptionIndex;
      this.options = options == null ? Collections.emptyList() : options;
    }

    String defaultOptionId() {
      if (options.isEmpty()) {
        return "BOOLEAN".equals(kind) ? "true" : "";
      }
      int idx = Math.max(0, Math.min(defaultOptionIndex, options.size() - 1));
      return options.get(idx).id;
    }
  }

  /** One of the face's complication slots, as {@code getComplicationState} describes it. */
  static final class Slot {
    final int id;
    final int[] supportedTypes;
    final boolean enabled;
    final boolean hasBounds;
    final int left;
    final int top;
    final int right;
    final int bottom;

    Slot(int id, int[] supportedTypes, boolean enabled) {
      this(id, supportedTypes, enabled, false, 0, 0, 0, 0);
    }

    Slot(
        int id,
        int[] supportedTypes,
        boolean enabled,
        int left,
        int top,
        int right,
        int bottom) {
      this(id, supportedTypes, enabled, right > left && bottom > top, left, top, right, bottom);
    }

    private Slot(
        int id,
        int[] supportedTypes,
        boolean enabled,
        boolean hasBounds,
        int left,
        int top,
        int right,
        int bottom) {
      this.id = id;
      this.supportedTypes = supportedTypes;
      this.enabled = enabled;
      this.hasBounds = hasBounds;
      this.left = left;
      this.top = top;
      this.right = right;
      this.bottom = bottom;
    }

    boolean supports(int type) {
      for (int t : supportedTypes) if (t == type) return true;
      return false;
    }

    boolean contains(float x, float y) {
      return hasBounds && x >= left && x <= right && y >= top && y <= bottom;
    }

    JSONObject boundsJson() {
      if (!hasBounds) return null;
      return new Protocol.Json()
          .put("left", left)
          .put("top", top)
          .put("right", right)
          .put("bottom", bottom)
          .put("centerX", (left + right) / 2)
          .put("centerY", (top + bottom) / 2)
          .object();
    }
  }

  final ComponentName component;
  private final Application app;
  private final Navigator navigator;
  private final Listener listener;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "simulator-watchface");
            t.setDaemon(true);
            return t;
          });
  /** Complication data by slot id, as the app's {@code ComplicationData} (Parcelable) objects. */
  private final Map<Integer, Object> complications = new LinkedHashMap<>();
  /** Selected user style option wire bytes by setting ID. */
  private final Map<String, byte[]> userStyle = new LinkedHashMap<>();
  /** Selected user style option string IDs by setting ID. */
  private final Map<String, String> userStyleIds = new LinkedHashMap<>();

  private IBinder control;
  private volatile IBinder headless;
  private volatile List<Slot> slots = Collections.emptyList();
  private volatile List<StyleSetting> schema = Collections.emptyList();
  private volatile Long fixedTimeMillis;
  private volatile Bitmap lastBitmap;
  private int width;
  private int height;
  private String error;
  private int frames;

  WatchFaceClient(
      Application app, Navigator navigator, ComponentName component, Listener listener) {
    this.app = app;
    this.navigator = navigator;
    this.component = component;
    this.listener = listener;
  }

  /** Whether [app] can host a watch face: it carries the library's control service. */
  static boolean hostable(Application app) {
    return controlService(app) != null;
  }

  private static ComponentName controlService(Application app) {
    Intent intent = new Intent(CONTROL_ACTION).setPackage(app.getPackageName());
    List<ResolveInfo> found = app.getPackageManager().queryIntentServices(intent, 0);
    if (found == null || found.isEmpty()) return null;
    return new ComponentName(found.get(0).serviceInfo.packageName, found.get(0).serviceInfo.name);
  }

  String error() {
    return error;
  }

  int frames() {
    return frames;
  }

  List<Slot> slots() {
    return slots;
  }

  List<StyleSetting> styleSchema() {
    return schema;
  }

  Map<Integer, Object> complications() {
    return complications;
  }

  void setFixedTimeMillis(Long timeMillis) {
    this.fixedTimeMillis = timeMillis;
  }

  Long fixedTimeMillis() {
    return fixedTimeMillis;
  }

  Bitmap lastBitmap() {
    return lastBitmap;
  }

  /** Sets a user style option on this headless watch face instance. */
  void setConfig(String settingId, String value) {
    List<StyleSetting> currentSchema = schema;
    StyleSetting matched = null;
    for (StyleSetting s : currentSchema) {
      if (s.id.equals(settingId)) {
        matched = s;
        break;
      }
    }
    if (matched == null && !currentSchema.isEmpty()) {
      throw new IllegalArgumentException(
          "Watch face " + component + " has no configuration '" + settingId + "'");
    }
    byte[] wireBytes = encodeStyleSelection(matched, value);
    synchronized (userStyle) {
      userStyle.put(settingId, wireBytes);
      userStyleIds.put(settingId, value);
    }
  }

  private static byte[] encodeStyleSelection(StyleSetting setting, String value) {
    if (setting != null) {
      if ("BOOLEAN".equals(setting.kind)) {
        boolean b = "true".equalsIgnoreCase(value) || "1".equals(value);
        if (!setting.options.isEmpty()) {
          int idx = b ? 0 : Math.min(1, setting.options.size() - 1);
          return setting.options.get(idx).rawId;
        }
        return new byte[] {(byte) (b ? 1 : 0)};
      }
      for (StyleOption opt : setting.options) {
        if (opt.id.equals(value) || opt.displayName.equalsIgnoreCase(value)) {
          return opt.rawId;
        }
      }
      try {
        int idx = Integer.parseInt(value);
        if (idx >= 0 && idx < setting.options.size()) {
          return setting.options.get(idx).rawId;
        }
      } catch (NumberFormatException ignored) {
      }
    }
    return value.getBytes(StandardCharsets.UTF_8);
  }

  JSONArray describeConfigurations() {
    JSONArray out = new JSONArray();
    Map<String, String> selectedIds;
    synchronized (userStyle) {
      selectedIds = new LinkedHashMap<>(userStyleIds);
    }
    for (StyleSetting s : schema) {
      JSONArray opts = new JSONArray();
      for (StyleOption opt : s.options) {
        Protocol.Json o =
            new Protocol.Json().put("id", opt.id).put("displayName", opt.displayName);
        if (!opt.colorArgbs.isEmpty()) {
          JSONArray cols = new JSONArray();
          for (Integer col : opt.colorArgbs) cols.put(String.format("#%08X", col));
          o.put("colors", cols);
        }
        opts.put(o.object());
      }
      String defVal = s.defaultOptionId();
      out.put(
          new Protocol.Json()
              .put("id", s.id)
              .put("kind", s.kind)
              .put("displayName", s.displayName)
              .put("defaultValue", defVal)
              .put("value", selectedIds.getOrDefault(s.id, defVal))
              .put("options", opts)
              .object());
    }
    return out;
  }

  WatchFaceDynamicColors.Palette extractPalette() {
    Map<String, String> selectedIds;
    synchronized (userStyle) {
      selectedIds = new LinkedHashMap<>(userStyleIds);
    }
    for (StyleSetting s : schema) {
      String selected = selectedIds.getOrDefault(s.id, s.defaultOptionId());
      for (StyleOption opt : s.options) {
        if ((opt.id.equals(selected) || opt.displayName.equalsIgnoreCase(selected))
            && !opt.colorArgbs.isEmpty()) {
          WatchFaceDynamicColors.Palette p =
              WatchFaceDynamicColors.Palette.fromColorList(
                  opt.colorArgbs, "configuration:" + s.id + ":" + opt.id);
          if (p != null) return p;
        }
      }
    }
    return WatchFaceDynamicColors.extractFromBitmap(lastBitmap);
  }

  /**
   * Binds the control service and creates the headless instance at [width]x[height], then renders
   * the first frame. Binding is on the calling (main) thread, as the system binds; the instance is
   * created on the worker.
   */
  void start(int width, int height, boolean ambient) {
    this.width = width;
    this.height = height;
    try {
      ComponentName service = controlService(app);
      if (service == null) {
        throw new IllegalStateException(
            app.getPackageName()
                + " has no WatchFaceControlService: only watch faces built on"
                + " androidx.wear.watchface (or hosted via --dwf-runtime) can be hosted");
      }
      IBinder binder = navigator.bindForHost(new Intent(CONTROL_ACTION).setComponent(service));
      if (binder == null) throw new IllegalStateException(service + " returned no binder");
      control = binder;
    } catch (RuntimeException e) {
      fail(e);
      return;
    }
    IBinder controlBinder = control;
    WatchState ws = WatchState.current();
    boolean burnIn = ws != null && ws.burnIn();
    boolean lowBit = ws != null && ws.lowBit();
    worker.execute(
        () -> {
          try {
            IBinder instance =
                createHeadlessInstance(controlBinder, component, width, height, burnIn, lowBit);
            if (instance == null) {
              throw new IllegalStateException(
                  component + " is not a watch face the control service can create");
            }
            headless = instance;
            slots = readSlots(instance);
            schema = readStyleSchema(instance);
            renderNow(ambient);
          } catch (Throwable e) {
            post(null, e);
          }
        });
  }

  /** Sets (or clears, with null) a slot's data and renders again. */
  void complication(int slotId, Object data, boolean ambient) {
    synchronized (complications) {
      if (data == null) complications.remove(slotId);
      else complications.put(slotId, data);
    }
    render(ambient);
  }

  /** Renders a frame on the worker; it reaches {@link Listener} on the main looper. */
  void render(boolean ambient) {
    if (headless == null) return;
    worker.execute(
        () -> {
          try {
            renderNow(ambient);
          } catch (Throwable e) {
            post(null, e);
          }
        });
  }

  private void renderNow(boolean ambient) throws RemoteException {
    IBinder instance = headless;
    if (instance == null) return;
    Long pinned = fixedTimeMillis;
    WatchState ws = WatchState.current();
    long timeMillis =
        pinned != null
            ? pinned.longValue()
            : (ws != null ? ws.nowMillis() : System.currentTimeMillis());
    Map<Integer, Object> snapshot = new LinkedHashMap<>();
    synchronized (complications) {
      for (Map.Entry<Integer, Object> e : complications.entrySet()) {
        Object active = ComplicationParcels.activeTimelineEntry(e.getValue(), timeMillis);
        if (active != null) snapshot.put(e.getKey(), active);
      }
    }
    Map<String, byte[]> styleSnapshot;
    synchronized (userStyle) {
      styleSnapshot = new LinkedHashMap<>(userStyle);
    }
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    Bundle bundle;
    try {
      data.writeInterfaceToken(HEADLESS_DESCRIPTOR);
      data.writeInt(1);
      writeRenderParams(
          data, ambient ? DRAW_AMBIENT : DRAW_INTERACTIVE, timeMillis, snapshot, styleSnapshot);
      data.setDataPosition(0);
      instance.transact(TRANSACTION_RENDER_WATCH_FACE_TO_BITMAP, data, reply, 0);
      reply.setDataPosition(0);
      reply.readException();
      bundle = reply.readInt() != 0 ? Bundle.CREATOR.createFromParcel(reply) : null;
    } finally {
      reply.recycle();
      data.recycle();
    }
    if (bundle == null) {
      throw new IllegalStateException("renderWatchFaceToBitmap returned null bundle");
    }
    Bitmap decoded = readImage(bundle);
    lastBitmap = decoded;
    post(decoded, null);
  }

  private static IBinder createHeadlessInstance(
      IBinder controlBinder,
      ComponentName component,
      int width,
      int height,
      boolean burnIn,
      boolean lowBit)
      throws RemoteException {
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(CONTROL_DESCRIPTOR);
      data.writeInt(1);
      writeHeadlessParams(data, component, width, height, "simulator", burnIn, lowBit);
      data.setDataPosition(0);
      controlBinder.transact(TRANSACTION_CREATE_HEADLESS, data, reply, 0);
      reply.setDataPosition(0);
      reply.readException();
      return reply.readStrongBinder();
    } finally {
      reply.recycle();
      data.recycle();
    }
  }

  /** {@code SharedMemoryImage.ashmemReadImageBundle}: the frame the headless instance wrote. */
  static Bitmap readImage(Bundle bundle) {
    bundle.setClassLoader(SharedMemory.class.getClassLoader());
    Parcelable memory = bundle.getParcelable("KEY_SCREENSHOT");
    if (!(memory instanceof SharedMemory)) {
      throw new IllegalStateException("the watch face returned no frame");
    }
    SharedMemory shared = (SharedMemory) memory;
    int w = bundle.getInt("KEY_BITMAP_WIDTH_PX");
    int h = bundle.getInt("KEY_BITMAP_HEIGHT_PX");
    Bitmap.Config config = Bitmap.Config.values()[bundle.getInt("KEY_BITMAP_CONFIG_ORDINAL")];
    Bitmap bitmap = Bitmap.createBitmap(w, h, config);
    try {
      ByteBuffer buffer = shared.mapReadOnly();
      try {
        bitmap.copyPixelsFromBuffer(buffer);
      } finally {
        SharedMemory.unmap(buffer);
      }
    } catch (android.system.ErrnoException e) {
      throw new IllegalStateException("could not read the watch face's frame: " + e, e);
    } finally {
      shared.close();
    }
    return bitmap;
  }

  private static List<Slot> readSlots(IBinder instance) throws RemoteException {
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(HEADLESS_DESCRIPTOR);
      data.setDataPosition(0);
      instance.transact(TRANSACTION_GET_COMPLICATION_STATE, data, reply, 0);
      reply.setDataPosition(0);
      reply.readException();
      int count = reply.readInt();
      if (count <= 0) return Collections.emptyList();
      List<Slot> result = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        if (reply.readInt() == 0) continue;
        Slot slot = readSlot(reply);
        if (slot != null) result.add(slot);
      }
      return result;
    } finally {
      reply.recycle();
      data.recycle();
    }
  }

  private static List<StyleSetting> readStyleSchema(IBinder instance) {
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(HEADLESS_DESCRIPTOR);
      data.setDataPosition(0);
      if (!instance.transact(TRANSACTION_GET_USER_STYLE_SCHEMA, data, reply, 0)) {
        return Collections.emptyList();
      }
      reply.setDataPosition(0);
      reply.readException();
      if (reply.readInt() == 0) return Collections.emptyList();
      return readUserStyleSchemaParcel(reply);
    } catch (Throwable ignored) {
      return Collections.emptyList();
    } finally {
      reply.recycle();
      data.recycle();
    }
  }

  /** Lets the instance and the control service go, as changing the watch face does. */
  void release() {
    IBinder instance = headless;
    headless = null;
    worker.execute(
        () -> {
          if (instance == null) return;
          Parcel data = Parcel.obtain();
          try {
            data.writeInterfaceToken(HEADLESS_DESCRIPTOR);
            data.setDataPosition(0);
            instance.transact(TRANSACTION_RELEASE, data, null, IBinder.FLAG_ONEWAY);
          } catch (Throwable ignored) {
            // Already gone.
          } finally {
            data.recycle();
          }
        });
    worker.shutdown();
    if (control != null) {
      ComponentName service = controlService(app);
      if (service != null) {
        navigator.unbindService(new Intent(CONTROL_ACTION).setComponent(service));
      }
      control = null;
    }
  }

  private void post(Bitmap frame, Throwable e) {
    main.post(
        () -> {
          if (e != null) {
            fail(e);
          } else {
            error = null;
            frames++;
            listener.rendered(this, frame, null);
          }
        });
  }

  private void fail(Throwable e) {
    Throwable cause = e;
    while (cause instanceof java.lang.reflect.InvocationTargetException
        && cause.getCause() != null) {
      cause = cause.getCause();
    }
    error = cause.getClass().getSimpleName() + ": " + cause.getMessage();
    listener.rendered(this, null, error);
  }

  // ---------------------------------------------------------------------------------------------
  // VersionedParcelable (ParcelImpl / VersionedParcelParcel) wire encoding
  //
  // VersionedParcelParcel compares field IDs lexicographically as strings
  // (`String.valueOf(mFieldId).compareTo(String.valueOf(fieldId)) > 0`), so fields in every nested
  // sub-parcel MUST be written in ascending order of `String.valueOf(fieldId)` (for example,
  // WatchFaceRenderParams writes 1, 100, 3, 5).

  static void writeHeadlessParams(
      Parcel parcel, ComponentName component, int width, int height, String instanceId) {
    writeHeadlessParams(parcel, component, width, height, instanceId, false, false);
  }

  static void writeHeadlessParams(
      Parcel parcel,
      ComponentName component,
      int width,
      int height,
      String instanceId,
      boolean burnIn,
      boolean lowBit) {
    parcel.writeString(PARCEL_IMPL);
    parcel.writeString(HEADLESS_PARAMS_WIRE);
    // Field 1: ComponentName mWatchFaceName
    int f1 = openField(parcel, 1);
    parcel.writeParcelable(component, 0);
    closeField(parcel, f1);
    // Field 2: DeviceConfig mDeviceConfig (fields 1, 2, 4, 5)
    int f2 = openField(parcel, 2);
    parcel.writeString(DEVICE_CONFIG_WIRE);
    writeBooleanField(parcel, 1, lowBit);
    writeBooleanField(parcel, 2, burnIn);
    writeLongField(parcel, 4, 0L);
    writeLongField(parcel, 5, 0L);
    closeField(parcel, f2);
    // Field 3: int mWidth
    writeIntField(parcel, 3, width);
    // Field 4: int mHeight
    writeIntField(parcel, 4, height);
    // Field 5: String mInstanceId
    int f5 = openField(parcel, 5);
    parcel.writeString(instanceId);
    closeField(parcel, f5);
  }

  static void writeRenderParams(
      Parcel parcel, int drawMode, long calendarTimeMillis, Map<Integer, Object> complications) {
    writeRenderParams(parcel, drawMode, calendarTimeMillis, complications, Collections.emptyMap());
  }

  static void writeRenderParams(
      Parcel parcel,
      int drawMode,
      long calendarTimeMillis,
      Map<Integer, Object> complications,
      Map<String, byte[]> userStyle) {
    parcel.writeString(PARCEL_IMPL);
    parcel.writeString(WATCH_FACE_RENDER_PARAMS_WIRE);
    // Field 1: RenderParametersWireFormat (fields 1, 2, 3, 4, 5, 6, 7, 9)
    int f1 = openField(parcel, 1);
    parcel.writeString(RENDER_PARAMETERS_WIRE);
    writeIntField(parcel, 1, drawMode);
    writeIntField(parcel, 2, ALL_LAYERS);
    writeIntField(parcel, 3, ELEMENT_NONE);
    writeIntField(parcel, 4, 0);
    int f1_5 = openField(parcel, 5);
    parcel.writeString(null);
    closeField(parcel, f1_5);
    writeIntField(parcel, 6, 0);
    writeIntField(parcel, 7, 0);
    int f1_9 = openField(parcel, 9);
    parcel.writeInt(0); // empty tapEvents list
    closeField(parcel, f1_9);
    closeField(parcel, f1);

    // Field 100 ("100" < "3"): List<IdAndComplicationDataWireFormat>
    int f100 = openField(parcel, 100);
    if (complications == null || complications.isEmpty()) {
      parcel.writeInt(0);
    } else {
      parcel.writeInt(complications.size());
      parcel.writeInt(COLLECTION_TYPE_VERSIONED_PARCELABLE);
      for (Map.Entry<Integer, Object> entry : complications.entrySet()) {
        parcel.writeString(ID_AND_COMPLICATION_DATA_WIRE);
        writeIntField(parcel, 1, entry.getKey());
        int itemF2 = openField(parcel, 2);
        parcel.writeParcelable((Parcelable) entry.getValue(), 0);
        closeField(parcel, itemF2);
      }
    }
    closeField(parcel, f100);

    // Field 3: long mCalendarTimeMillis
    writeLongField(parcel, 3, calendarTimeMillis);

    // Field 5: UserStyleWireFormat (field 1: Map<String, byte[]>)
    int f5 = openField(parcel, 5);
    parcel.writeString(USER_STYLE_WIRE);
    int f5_1 = openField(parcel, 1);
    writeUserStyleMap(parcel, userStyle);
    closeField(parcel, f5_1);
    closeField(parcel, f5);
  }

  /**
   * Writes {@code VersionedParcel.writeMap(Map<String, byte[]>)}: entry count, key collection
   * ({@code TYPE_STRING = 4}), and value collection ({@code TYPE_SERIALIZABLE = 3} wrapping
   * {@code ObjectOutputStream} bytes of {@code byte[]}).
   */
  static void writeUserStyleMap(Parcel parcel, Map<String, byte[]> map) {
    if (map == null || map.isEmpty()) {
      parcel.writeInt(0);
      return;
    }
    int n = map.size();
    parcel.writeInt(n);
    // Keys collection
    parcel.writeInt(n);
    parcel.writeInt(COLLECTION_TYPE_STRING);
    for (String key : map.keySet()) {
      parcel.writeString(key);
    }
    // Values collection
    parcel.writeInt(n);
    parcel.writeInt(COLLECTION_TYPE_SERIALIZABLE);
    for (byte[] value : map.values()) {
      parcel.writeString("[B");
      byte[] serialized = serializeJavaObject(value == null ? new byte[0] : value);
      parcel.writeInt(serialized.length);
      parcel.writeByteArray(serialized);
    }
  }

  private static byte[] serializeJavaObject(Object obj) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
      oos.writeObject(obj);
    } catch (IOException e) {
      throw new IllegalStateException("could not serialize byte[] for UserStyleWireFormat", e);
    }
    return baos.toByteArray();
  }

  /** Parses {@code ParcelImpl}-wrapped {@code UserStyleSchemaWireFormat} from [parcel]. */
  static List<StyleSetting> readUserStyleSchemaParcel(Parcel parcel) {
    String wrapper = parcel.readString();
    if (!PARCEL_IMPL.equals(wrapper)) return Collections.emptyList();
    String schemaType = parcel.readString();
    if (!USER_STYLE_SCHEMA_WIRE.equals(schemaType)) return Collections.emptyList();

    int end = parcel.dataSize();
    if (parcel.dataPosition() + 8 > end) return Collections.emptyList();
    int f1Start = parcel.dataPosition();
    int f1Size = parcel.readInt();
    int f1Id = parcel.readInt();
    if (f1Id != 1 || f1Size < 8 || f1Start + f1Size > end) return Collections.emptyList();
    int listEnd = f1Start + f1Size;

    int count = parcel.readInt();
    if (count <= 0) {
      parcel.setDataPosition(listEnd);
      return Collections.emptyList();
    }
    int elemType = parcel.readInt();
    if (elemType != COLLECTION_TYPE_VERSIONED_PARCELABLE) {
      parcel.setDataPosition(listEnd);
      return Collections.emptyList();
    }

    List<StyleSetting> settings = new ArrayList<>(count);
    for (int i = 0; i < count && parcel.dataPosition() < listEnd; i++) {
      StyleSetting s = readOneStyleSetting(parcel, listEnd);
      if (s != null) settings.add(s);
    }
    parcel.setDataPosition(listEnd);
    return settings;
  }

  private static StyleSetting readOneStyleSetting(Parcel parcel, int listEnd) {
    String parcelizer = parcel.readString();
    if (parcelizer == null) return null;
    String settingId = "";
    String displayName = "";
    int defaultIndex = 0;
    List<StyleOption> options = new ArrayList<>();
    String prevFieldIdStr = null;

    while (parcel.dataPosition() + 8 <= listEnd) {
      int savedPos = parcel.dataPosition();
      int fSize = parcel.readInt();
      int fId = parcel.readInt();
      String fIdStr = String.valueOf(fId);
      if (fSize < 8
          || savedPos + fSize > listEnd
          || fId < 1
          || fId > 999
          || (prevFieldIdStr != null && fIdStr.compareTo(prevFieldIdStr) <= 0)) {
        parcel.setDataPosition(savedPos);
        break;
      }
      prevFieldIdStr = fIdStr;
      int fieldEnd = savedPos + fSize;
      if (fId == 1) {
        String s = parcel.readString();
        if (s != null) settingId = s;
      } else if (fId == 100) {
        options = readStyleOptionsList(parcel, fieldEnd);
      } else if (fId == 2) {
        CharSequence cs = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
        if (cs != null) displayName = cs.toString();
      } else if (fId == 5) {
        defaultIndex = parcel.readInt();
      }
      parcel.setDataPosition(fieldEnd);
    }

    String kind = classifySettingKind(parcelizer, options);
    if (displayName.isEmpty()) displayName = settingId;
    return new StyleSetting(settingId, kind, displayName, defaultIndex, options);
  }

  private static List<StyleOption> readStyleOptionsList(Parcel parcel, int listEnd) {
    int count = parcel.readInt();
    if (count <= 0) return Collections.emptyList();
    int elemType = parcel.readInt();
    if (elemType != COLLECTION_TYPE_VERSIONED_PARCELABLE) return Collections.emptyList();
    List<StyleOption> opts = new ArrayList<>(count);
    for (int i = 0; i < count && parcel.dataPosition() < listEnd; i++) {
      String optParcelizer = parcel.readString();
      if (optParcelizer == null) break;
      byte[] rawId = new byte[0];
      String optDisplay = "";
      List<Integer> colors = new ArrayList<>();
      String prevFieldIdStr = null;

      while (parcel.dataPosition() + 8 <= listEnd) {
        int savedPos = parcel.dataPosition();
        int fSize = parcel.readInt();
        int fId = parcel.readInt();
        String fIdStr = String.valueOf(fId);
        if (fSize < 8
            || savedPos + fSize > listEnd
            || fId < 1
            || fId > 999
            || (prevFieldIdStr != null && fIdStr.compareTo(prevFieldIdStr) <= 0)) {
          parcel.setDataPosition(savedPos);
          break;
        }
        prevFieldIdStr = fIdStr;
        int fieldEnd = savedPos + fSize;
        if (fId == 1) {
          int len = parcel.readInt();
          if (len >= 0 && parcel.dataPosition() + len <= fieldEnd) {
            rawId = new byte[len];
            parcel.readByteArray(rawId);
          }
        } else if (fId == 2) {
          CharSequence cs = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
          if (cs != null) optDisplay = cs.toString();
        } else if (fId == 4 && optParcelizer.contains("ColorOption")) {
          int colCount = parcel.readInt();
          if (colCount > 0) {
            int colType = parcel.readInt();
            if (colType == COLLECTION_TYPE_INT) {
              for (int k = 0; k < colCount && parcel.dataPosition() + 4 <= fieldEnd; k++) {
                colors.add(Integer.valueOf(parcel.readInt()));
              }
            }
          }
        }
        parcel.setDataPosition(fieldEnd);
      }

      String idStr = decodeOptionId(optParcelizer, rawId, i);
      if (optDisplay.isEmpty()) optDisplay = idStr;
      opts.add(new StyleOption(idStr, rawId, optDisplay, colors));
    }
    return opts;
  }

  private static String decodeOptionId(String optParcelizer, byte[] rawId, int index) {
    if (optParcelizer.contains("BooleanOption")) {
      if (rawId.length == 1) return rawId[0] != 0 ? "true" : "false";
      return index == 0 ? "true" : "false";
    }
    if (rawId.length == 0) return Integer.toString(index);
    return new String(rawId, StandardCharsets.UTF_8);
  }

  private static String classifySettingKind(String parcelizer, List<StyleOption> options) {
    if (parcelizer.contains("BooleanUserStyleSetting")) return "BOOLEAN";
    for (StyleOption o : options) {
      if (!o.colorArgbs.isEmpty()) return "COLOR";
    }
    return "LIST";
  }

  /**
   * Reads one {@code IdAndComplicationStateWireFormat} wrapped in a {@code ParcelImpl} from
   * [parcel], advancing [parcel] to the exact end of its field 2 so subsequent list items align.
   */
  static Slot readSlot(Parcel parcel) {
    String wrapper = parcel.readString();
    if (!PARCEL_IMPL.equals(wrapper)) {
      throw new IllegalStateException("expected ParcelImpl, got " + wrapper);
    }
    String outerType = parcel.readString();
    if (!ID_AND_COMPLICATION_STATE_WIRE.equals(outerType)) {
      throw new IllegalStateException(
          "expected IdAndComplicationStateWireFormat, got " + outerType);
    }
    // Field 1: int mId
    int f1Start = parcel.dataPosition();
    int f1Size = parcel.readInt();
    int f1Id = parcel.readInt();
    if (f1Id != 1 || f1Size < 8) {
      throw new IllegalStateException("unexpected IdAndComplicationState field " + f1Id);
    }
    int slotId = parcel.readInt();
    parcel.setDataPosition(f1Start + f1Size);

    // Field 2: ComplicationStateWireFormat mComplicationState
    int f2Start = parcel.dataPosition();
    int f2Size = parcel.readInt();
    int f2Id = parcel.readInt();
    if (f2Id != 2 || f2Size < 8) {
      throw new IllegalStateException("unexpected IdAndComplicationState field " + f2Id);
    }
    int f2End = f2Start + f2Size;
    String innerType = parcel.readString();
    int[] supportedTypes = new int[0];
    boolean enabled = true;
    int left = 0;
    int top = 0;
    int right = 0;
    int bottom = 0;
    if (COMPLICATION_STATE_WIRE.equals(innerType)) {
      while (parcel.dataPosition() + 8 <= f2End) {
        int subStart = parcel.dataPosition();
        int subSize = parcel.readInt();
        int subId = parcel.readInt();
        if (subSize < 8 || subStart + subSize > f2End) break;
        if (subId == 1) {
          try {
            Rect bounds = parcel.readParcelable(Rect.class.getClassLoader());
            if (bounds != null) {
              left = bounds.left;
              top = bounds.top;
              right = bounds.right;
              bottom = bounds.bottom;
            }
          } catch (RuntimeException ignored) {
          }
        } else if (subId == 3) {
          int n = parcel.readInt();
          if (n > 0) {
            supportedTypes = new int[n];
            for (int i = 0; i < n; i++) supportedTypes[i] = parcel.readInt();
          }
        } else if (subId == 7) {
          enabled = parcel.readInt() != 0;
        }
        parcel.setDataPosition(subStart + subSize);
      }
    }
    parcel.setDataPosition(f2End);
    return new Slot(slotId, supportedTypes, enabled, left, top, right, bottom);
  }

  private static int openField(Parcel parcel, int fieldId) {
    int start = parcel.dataPosition();
    parcel.writeInt(0);
    parcel.writeInt(fieldId);
    return start;
  }

  private static void closeField(Parcel parcel, int start) {
    int end = parcel.dataPosition();
    parcel.setDataPosition(start);
    parcel.writeInt(end - start);
    parcel.setDataPosition(end);
  }

  private static void writeIntField(Parcel parcel, int fieldId, int value) {
    int start = openField(parcel, fieldId);
    parcel.writeInt(value);
    closeField(parcel, start);
  }

  private static void writeBooleanField(Parcel parcel, int fieldId, boolean value) {
    writeIntField(parcel, fieldId, value ? 1 : 0);
  }

  private static void writeLongField(Parcel parcel, int fieldId, long value) {
    int start = openField(parcel, fieldId);
    parcel.writeLong(value);
    closeField(parcel, start);
  }
}
