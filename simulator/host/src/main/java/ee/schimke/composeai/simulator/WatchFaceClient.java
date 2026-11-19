package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.service.wallpaper.WallpaperService;
import android.text.TextUtils;
import android.view.Display;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
  static final String INTERACTIVE_DESCRIPTOR =
      "androidx.wear.watchface.control.IInteractiveWatchFace";
  static final String PENDING_INTERACTIVE_DESCRIPTOR =
      "androidx.wear.watchface.control.IPendingInteractiveWatchFace";

  /**
   * How long to wait for the runtime to hand back its interactive instance before falling back to
   * a headless one, whose only frame path is the screenshot call (and so the 28th of the month).
   * The instance is created on the main thread, which the host's own commands (a push compiles and
   * installs the face there) can keep busy for several seconds, so this is long.
   */
  static final long INTERACTIVE_INSTANCE_TIMEOUT_SECONDS = 60;

  /** {@code IWatchFaceControlService.createHeadlessWatchFaceInstance} (transaction 4). */
  static final int TRANSACTION_CREATE_HEADLESS = IBinder.FIRST_CALL_TRANSACTION + 3;
  /** {@code IWatchFaceControlService.getOrCreateInteractiveWatchFaceClient} (transaction 5). */
  static final int TRANSACTION_GET_OR_CREATE_INTERACTIVE = IBinder.FIRST_CALL_TRANSACTION + 4;

  /** {@code IHeadlessWatchFace} transactions (verified against the published AIDL). */
  static final int TRANSACTION_GET_USER_STYLE_SCHEMA = IBinder.FIRST_CALL_TRANSACTION + 3; // 4
  static final int TRANSACTION_GET_COMPLICATION_STATE = IBinder.FIRST_CALL_TRANSACTION + 4; // 5
  static final int TRANSACTION_RENDER_WATCH_FACE_TO_BITMAP =
      IBinder.FIRST_CALL_TRANSACTION + 5; // 6
  static final int TRANSACTION_RELEASE = IBinder.FIRST_CALL_TRANSACTION + 7; // 8

  /** {@code IInteractiveWatchFace} transactions. */
  static final int TRANSACTION_INTERACTIVE_GET_USER_STYLE_SCHEMA =
      IBinder.FIRST_CALL_TRANSACTION + 8; // 9
  static final int TRANSACTION_INTERACTIVE_GET_COMPLICATION_DETAILS =
      IBinder.FIRST_CALL_TRANSACTION + 9; // 10
  static final int TRANSACTION_INTERACTIVE_RENDER_WATCH_FACE_TO_BITMAP =
      IBinder.FIRST_CALL_TRANSACTION + 10; // 11
  static final int TRANSACTION_INTERACTIVE_RELEASE = IBinder.FIRST_CALL_TRANSACTION + 11; // 12
  /** Read off the runtime's {@code IInteractiveWatchFace} stub, as the published AIDL numbers. */
  static final int TRANSACTION_INTERACTIVE_SET_WATCH_UI_STATE = 16;

  static final int TRANSACTION_INTERACTIVE_UPDATE_INSTANCE_SYNC = 29;
  static final int TRANSACTION_INTERACTIVE_OVERRIDE_COMPLICATION_DATA = 27;
  static final int TRANSACTION_INTERACTIVE_CLEAR_COMPLICATION_DATA = 28;

  private static final String PARCEL_IMPL = "androidx.versionedparcelable.ParcelImpl";
  private static final String HEADLESS_PARAMS_WIRE =
      "androidx.wear.watchface.control.data.HeadlessWatchFaceInstanceParamsParcelizer";
  private static final String INTERACTIVE_PARAMS_WIRE =
      "androidx.wear.watchface.control.data.WallpaperInteractiveWatchFaceInstanceParamsParcelizer";
  private static final String DEVICE_CONFIG_WIRE =
      "androidx.wear.watchface.data.DeviceConfigParcelizer";
  private static final String WATCH_UI_STATE_WIRE =
      "androidx.wear.watchface.data.WatchUiStateParcelizer";
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
  /** Slot IDs populated by a flavor's default provider policy rather than an explicit override. */
  private final Set<Integer> flavorManagedSlots = new LinkedHashSet<>();
  /** Slot IDs explicitly overridden via {@link #complication(int, Object, boolean)}. */
  private final Set<Integer> explicitComplicationSlots = new LinkedHashSet<>();
  /** Selected user style option wire bytes by setting ID. */
  private final Map<String, byte[]> userStyle = new LinkedHashMap<>();
  /** Selected user style option string IDs by setting ID. */
  private final Map<String, String> userStyleIds = new LinkedHashMap<>();

  private IBinder control;
  private ComponentName wallpaperComponent;
  private IBinder wallpaperService;
  private volatile IBinder wallpaperEngine;
  private volatile IBinder headless;
  private volatile boolean interactive;
  /**
   * The id of this client's interactive instance. The runtime keeps one instance per id, so a face
   * set again while the previous client is still being torn down (a push right after the initial
   * face) must not ask for the id the old client holds.
   */
  private final String instanceId = "simulator-" + INSTANCE_IDS.incrementAndGet();

  private static final java.util.concurrent.atomic.AtomicInteger INSTANCE_IDS =
      new java.util.concurrent.atomic.AtomicInteger();
  /** Set by {@link #release()}: whatever the runtime hands back after it is not wanted. */
  private volatile boolean released;

  private static final java.util.concurrent.locks.ReentrantLock CREATION_LOCK =
      new java.util.concurrent.locks.ReentrantLock();

  private volatile LiveFrameSource live;
  /** Whether the last frame published came from the live draw (else the screenshot call). */
  private volatile boolean lastFrameLive;
  private boolean liveTried;
  private volatile List<Slot> slots = Collections.emptyList();
  private volatile List<StyleSetting> schema = Collections.emptyList();
  private volatile String defaultFlavorId;
  private volatile List<DwfWatchFaceClient.FlavorSpec> flavors = Collections.emptyList();
  private final Map<Integer, DwfWatchFaceClient.FlavorComplicationPolicy> defaultSlotPolicies =
      new LinkedHashMap<>();
  private final List<Integer> sceneSlotOrder = new ArrayList<>();
  private volatile String activeFlavorId;
  private volatile DwfWatchFaceClient.FlavorSpec activeFlavorSpec;
  private volatile Set<Integer> lastExternallyBoundSlots = Collections.emptySet();
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
    WffWatchFaces.FlavorsCatalog cat = WffWatchFaces.flavorsForPackage(component.getPackageName());
    if (cat != null) {
      this.defaultSlotPolicies.putAll(cat.defaultSlotPolicies);
      this.sceneSlotOrder.addAll(cat.sceneSlotOrder);
      if (!cat.flavors.isEmpty()) {
        this.defaultFlavorId = cat.defaultFlavorId;
        this.flavors = cat.flavors;
        this.activeFlavorId = cat.defaultFlavorId;
        this.activeFlavorSpec =
            cat.defaultFlavorId != null
                ? DwfWatchFaceClient.findFlavorSpec(cat.flavors, cat.defaultFlavorId)
                : null;
      }
    }
  }

  void setFallbackDefaultProviders(Map<Integer, String> providers) {
    if (providers == null || providers.isEmpty()) return;
    for (Map.Entry<Integer, String> e : providers.entrySet()) {
      String prov = e.getValue() != null ? e.getValue().trim() : "";
      if (prov.isEmpty() || defaultSlotPolicies.containsKey(e.getKey())) continue;
      defaultSlotPolicies.put(
          e.getKey(), new DwfWatchFaceClient.FlavorComplicationPolicy(prov, null));
    }
  }

  void setFallbackFlavors(String fallbackDefaultId, List<DwfWatchFaceClient.FlavorSpec> specs) {
    if (!this.flavors.isEmpty() || specs == null || specs.isEmpty()) return;
    this.defaultFlavorId = fallbackDefaultId;
    this.flavors = specs;
    if (this.activeFlavorId == null) {
      this.activeFlavorId = fallbackDefaultId;
      this.activeFlavorSpec =
          fallbackDefaultId != null
              ? DwfWatchFaceClient.findFlavorSpec(specs, fallbackDefaultId)
              : null;
    }
  }

  boolean hasFlavors() {
    return !flavors.isEmpty();
  }

  String activeFlavorId() {
    return activeFlavorId;
  }

  JSONArray describeFlavors() {
    return DwfWatchFaceClient.describeFlavorSpecs(flavors, activeFlavorId);
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

  void resetConfigsToDefault(Set<Integer> externallyBoundSlots) {
    synchronized (userStyle) {
      userStyle.clear();
      userStyleIds.clear();
    }
    clearFlavorManagedComplications();
    activeFlavorId = null;
    activeFlavorSpec = null;
    if (defaultFlavorId != null && !defaultFlavorId.isEmpty() && !flavors.isEmpty()) {
      DwfWatchFaceClient.FlavorSpec def = DwfWatchFaceClient.findFlavorSpec(flavors, defaultFlavorId);
      if (def != null) {
        applyFlavorPreset(def, externallyBoundSlots);
      }
    }
  }

  void setFlavor(String flavorId, Set<Integer> externallyBoundSlots) {
    DwfWatchFaceClient.FlavorSpec spec = DwfWatchFaceClient.findFlavorSpec(flavors, flavorId);
    if (spec == null) {
      throw new IllegalArgumentException(
          "Watch face " + component + " has no flavor '" + flavorId + "'");
    }
    synchronized (userStyle) {
      userStyle.clear();
      userStyleIds.clear();
    }
    clearFlavorManagedComplications();
    applyFlavorPreset(spec, externallyBoundSlots);
  }

  private void clearFlavorManagedComplications() {
    synchronized (complications) {
      for (Integer slotId : flavorManagedSlots) {
        if (!explicitComplicationSlots.contains(slotId)) {
          complications.remove(slotId);
        }
      }
      flavorManagedSlots.clear();
    }
  }

  private void applyFlavorPreset(
      DwfWatchFaceClient.FlavorSpec spec, Set<Integer> externallyBoundSlots) {
    activeFlavorId = spec.id;
    activeFlavorSpec = spec;
    for (Map.Entry<String, String> e : spec.configurations.entrySet()) {
      setConfig(e.getKey(), e.getValue());
    }
    rebuildFlavorComplications(WatchState.current(), externallyBoundSlots);
  }

  void refreshFlavorComplications(Set<Integer> externallyBoundSlots) {
    rebuildFlavorComplications(WatchState.current(), externallyBoundSlots);
  }

  private void rebuildFlavorComplications(WatchState ws, Set<Integer> externallyBoundSlots) {
    if (externallyBoundSlots != null) {
      lastExternallyBoundSlots = Set.copyOf(externallyBoundSlots);
    }
    Set<Integer> boundSlots =
        externallyBoundSlots != null ? externallyBoundSlots : lastExternallyBoundSlots;
    DwfWatchFaceClient.FlavorSpec spec = activeFlavorSpec;
    synchronized (complications) {
      Map<Integer, DwfWatchFaceClient.FlavorComplicationPolicy> effectivePolicies =
          new LinkedHashMap<>(defaultSlotPolicies);
      if (spec != null) {
        effectivePolicies.putAll(spec.complications);
      }
      Map<Integer, DwfWatchFaceClient.FlavorComplicationPolicy> resolvedPolicies =
          new LinkedHashMap<>();
      Map<Integer, Slot> resolvedSlots = new LinkedHashMap<>();
      for (Map.Entry<Integer, DwfWatchFaceClient.FlavorComplicationPolicy> e :
          effectivePolicies.entrySet()) {
        Integer xmlSlotId = e.getKey();
        Slot targetSlot = null;
        for (Slot s : slots) {
          if (s.id == xmlSlotId.intValue()) {
            targetSlot = s;
            break;
          }
        }
        Integer wireSlotId = xmlSlotId;
        if (targetSlot == null && !slots.isEmpty()) {
          int idx = sceneSlotOrder.indexOf(xmlSlotId);
          if (idx >= 0 && idx < slots.size()) {
            targetSlot = slots.get(idx);
            wireSlotId = Integer.valueOf(targetSlot.id);
          }
        }
        if (!slots.isEmpty() && targetSlot == null) continue;
        resolvedPolicies.put(wireSlotId, e.getValue());
        if (targetSlot != null) resolvedSlots.put(wireSlotId, targetSlot);
      }
      for (Integer slotId : new ArrayList<>(flavorManagedSlots)) {
        boolean keepInSpec = resolvedPolicies.containsKey(slotId);
        boolean overridden =
            explicitComplicationSlots.contains(slotId)
                || (boundSlots != null && boundSlots.contains(slotId));
        if (!keepInSpec || overridden) {
          if (!overridden) {
            complications.remove(slotId);
          }
          flavorManagedSlots.remove(slotId);
        }
      }
      if (resolvedPolicies.isEmpty()) return;
      for (Map.Entry<Integer, DwfWatchFaceClient.FlavorComplicationPolicy> e :
          resolvedPolicies.entrySet()) {
        Integer wireSlotId = e.getKey();
        if (explicitComplicationSlots.contains(wireSlotId)
            || (boundSlots != null && boundSlots.contains(wireSlotId))) {
          continue;
        }
        DwfWatchFaceClient.FlavorComplicationPolicy pol = e.getValue();
        DwfWatchFaceClient.FakeComplication fake =
            DwfWatchFaceClient.syntheticSystemProviderComplication(
                wireSlotId.intValue(), pol.defaultSystemProvider, pol.defaultSystemProviderType, ws);
        Slot targetSlot = resolvedSlots.get(wireSlotId);
        if (fake == null) {
          complications.remove(wireSlotId);
          flavorManagedSlots.remove(wireSlotId);
        } else {
          Parcelable built =
              ComplicationParcels.buildFakeParcelable(app.getClassLoader(), fake, targetSlot);
          if (built != null) {
            complications.put(wireSlotId, built);
            flavorManagedSlots.add(wireSlotId);
          }
        }
      }
    }
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
    if (matched == null) {
      if ("flavor".equals(settingId) && hasFlavors()) {
        setFlavor(value, lastExternallyBoundSlots);
        return;
      }
      if (!currentSchema.isEmpty()) {
        throw new IllegalArgumentException(
            "Watch face " + component + " has no configuration '" + settingId + "'");
      }
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
      try {
        ComponentName wpComponent = component;
        if (wpComponent.getClassName().isEmpty()) {
          List<ResolveInfo> wpFound =
              app.getPackageManager()
                  .queryIntentServices(
                      new Intent(WallpaperService.SERVICE_INTERFACE)
                          .setPackage(service.getPackageName()),
                      0);
          if (wpFound != null && !wpFound.isEmpty()) {
            wpComponent =
                new ComponentName(
                    wpFound.get(0).serviceInfo.packageName, wpFound.get(0).serviceInfo.name);
          }
        }
        wallpaperComponent = wpComponent;
        wallpaperService =
            wpComponent.getClassName().isEmpty()
                ? null
                : navigator.bindForHost(
                    new Intent(WallpaperService.SERVICE_INTERFACE).setComponent(wpComponent));
      } catch (RuntimeException e) {
        System.err.println("WatchFaceClient: no wallpaper service for " + component + ": " + e);
        wallpaperService = null;
      }
    } catch (RuntimeException e) {
      fail(e);
      return;
    }
    IBinder controlBinder = control;
    IBinder wpBinder = wallpaperService;
    WatchState ws = WatchState.current();
    boolean burnIn = ws != null && ws.burnIn();
    boolean lowBit = ws != null && ws.lowBit();
    worker.execute(
        () -> {
          try {
            IBinder instance;
            // One request at a time: the runtime answers a second request made while the first is
            // pending for only one of them (the other waits out its whole timeout), and a face set
            // again right after the initial one (a push) is exactly that. A client released before
            // its turn never asks.
            CREATION_LOCK.lock();
            try {
              if (released) return;
              instance =
                  tryCreateInteractiveInstance(
                      controlBinder, wpBinder, width, height, burnIn, lowBit, ambient);
              if (instance != null && released) {
                // Released while the runtime was creating it: left alive it holds the runtime's one
                // interactive slot and the next face's instance is never created.
                releaseInstance(instance, true);
                IBinder lateEngine = wallpaperEngine;
                wallpaperEngine = null;
                if (lateEngine != null) transactWallpaperEngine(lateEngine, 10, 0, 0);
                return;
              }
            } finally {
              CREATION_LOCK.unlock();
            }
            if (instance != null) {
              interactive = true;
            } else {
              interactive = false;
              instance =
                  createHeadlessInstance(controlBinder, component, width, height, burnIn, lowBit);
            }
            if (instance == null) {
              throw new IllegalStateException(
                  component + " is not a watch face the control service can create");
            }
            if (released) {
              // Released while the runtime was still creating the instance (a face set again right
              // after the initial one): the instance is handed back after release() looked for it.
              // Left alive it holds the runtime's one interactive slot, and the next face's
              // instance is never created, so that face falls back to screenshots.
              releaseInstance(instance, interactive);
              IBinder lateEngine = wallpaperEngine;
              wallpaperEngine = null;
              if (lateEngine != null) transactWallpaperEngine(lateEngine, 10, 0, 0);
              return;
            }
            headless = instance;
            slots = readSlots(instance, interactive);
            schema = readStyleSchema(instance, interactive);
            synchronized (userStyle) {
              for (Map.Entry<String, String> sel : new LinkedHashMap<>(userStyleIds).entrySet()) {
                StyleSetting matched = null;
                for (StyleSetting s : schema) {
                  if (s.id.equals(sel.getKey())) {
                    matched = s;
                    break;
                  }
                }
                if (matched != null) {
                  userStyle.put(sel.getKey(), encodeStyleSelection(matched, sel.getValue()));
                }
              }
            }
            renderNow(ambient);
          } catch (Throwable e) {
            post(null, e);
          }
        });
  }

  /** Sets (or clears, with null) a slot's data and renders again. */
  void complication(int slotId, Object data, boolean ambient) {
    synchronized (complications) {
      flavorManagedSlots.remove(slotId);
      if (data == null) {
        explicitComplicationSlots.remove(slotId);
        complications.remove(slotId);
      } else {
        explicitComplicationSlots.add(slotId);
        complications.put(slotId, data);
      }
    }
    if (data == null) {
      rebuildFlavorComplications(WatchState.current(), null);
    }
    render(ambient);
  }

  /** Renders a frame on the worker; it reaches {@link Listener} on the main looper. */
  void render(boolean ambient) {
    if (headless == null) {
      // Not started (or failed to start): there is nothing to render, so report that again rather
      // than leave the host waiting for a frame that is not coming.
      String previous = error;
      if (previous != null) main.post(() -> listener.rendered(this, null, previous));
      return;
    }
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
    if (headless == null) return;
    Long pinned = fixedTimeMillis;
    WatchState ws = WatchState.current();
    long timeMillis =
        pinned != null
            ? pinned.longValue()
            : (ws != null ? ws.nowMillis() : System.currentTimeMillis());
    RenderedBitmap rendered = renderBitmapInternal(timeMillis, ambient);
    if (rendered == null) return;
    lastBitmap = rendered.bitmap;
    post(rendered.bitmap, null, rendered.live);
  }

  /** Schedules {@code task} on this client's single-threaded render worker. */
  void runOnWorker(Runnable task) {
    worker.execute(task);
  }

  /**
   * Renders one frame at {@code timeMillis} and {@code ambient} mode synchronously. Must be called
   * on the client's worker thread (live frames post to the main looper and await readback).
   */
  Bitmap renderBitmapAt(long timeMillis, boolean ambient) throws RemoteException {
    Long previousFixed = fixedTimeMillis;
    fixedTimeMillis = Long.valueOf(timeMillis);
    try {
      RenderedBitmap rendered = renderBitmapInternal(timeMillis, ambient);
      return rendered != null ? rendered.bitmap : null;
    } finally {
      fixedTimeMillis = previousFixed;
      SimulatorClock.setOverride(previousFixed);
    }
  }

  private static final class RenderedBitmap {
    final Bitmap bitmap;
    final boolean live;

    RenderedBitmap(Bitmap bitmap, boolean live) {
      this.bitmap = bitmap;
      this.live = live;
    }
  }

  private RenderedBitmap renderBitmapInternal(long timeMillis, boolean ambient)
      throws RemoteException {
    IBinder instance = headless;
    if (instance == null) return null;
    WatchState ws = WatchState.current();
    rebuildFlavorComplications(ws, null);
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
    Bitmap liveFrame = renderLive(ambient, snapshot, styleSnapshot);
    if (liveFrame != null) {
      return new RenderedBitmap(liveFrame, true);
    }
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    Bundle bundle;
    try {
      String descriptor = interactive ? INTERACTIVE_DESCRIPTOR : HEADLESS_DESCRIPTOR;
      int tx =
          interactive
              ? TRANSACTION_INTERACTIVE_RENDER_WATCH_FACE_TO_BITMAP
              : TRANSACTION_RENDER_WATCH_FACE_TO_BITMAP;
      data.writeInterfaceToken(descriptor);
      data.writeInt(1);
      writeRenderParams(
          data, ambient ? DRAW_AMBIENT : DRAW_INTERACTIVE, timeMillis, snapshot, styleSnapshot);
      data.setDataPosition(0);
      instance.transact(tx, data, reply, 0);
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
    return new RenderedBitmap(readImage(bundle), false);
  }

  private static final long LIVE_ENGINE_WAIT_MS = 30_000;

  /**
   * The wallpaper engine, waiting up to [timeoutMs] for it: the runtime hands back its interactive
   * instance before (not after) the engine reports itself to the connection, so the first frame
   * can be asked for with no engine yet.
   */
  private IBinder awaitWallpaperEngine(long timeoutMs) {
    return LiveFrameSource.await(() -> wallpaperEngine, timeoutMs);
  }

  /** Whether frames come from the engine's live draw rather than the screenshot call. */
  boolean usesLiveFrames() {
    return lastFrameLive;
  }

  /** A frame from the engine's live draw, or null to use the screenshot call. */
  private Bitmap renderLive(
      boolean ambient, Map<Integer, Object> complicationSnapshot, Map<String, byte[]> style) {
    if (!interactive || !component.getClassName().isEmpty()) return null;
    if (Boolean.getBoolean("composeai.simulator.noLiveFrames")) return null;
    if (!liveTried) {
      IBinder engine = awaitWallpaperEngine(LIVE_ENGINE_WAIT_MS);
      // No engine yet: take this frame from the screenshot call and look again for the next, so a
      // face whose engine attaches after its instance is handed back still gets live frames.
      if (engine == null) return null;
      liveTried = true;
      java.util.concurrent.atomic.AtomicReference<LiveFrameSource> out =
          new java.util.concurrent.atomic.AtomicReference<>();
      java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
      main.post(
          () -> {
            try {
              out.set(LiveFrameSource.attach(engine, width, height));
            } finally {
              done.countDown();
            }
          });
      try {
        done.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      live = out.get();
    }
    LiveFrameSource source = live;
    if (source == null) return null;
    try {
      applyLiveState(ambient, complicationSnapshot, style);
    } catch (RemoteException | RuntimeException e) {
      e.printStackTrace(System.err);
      return null;
    }
    // The face's own clock reads ZonedDateTime.now(), which the converter points at the simulated
    // clock; a pinned time for this face overrides it, and TIME_SET makes the face re-read it.
    SimulatorClock.setOverride(fixedTimeMillis);
    long now = SimulatorClock.nowMillis();
    WatchState liveWs = WatchState.current();
    boolean simulated =
        fixedTimeMillis != null || (liveWs != null && liveWs.isClockPinnedOrScaled());
    if (!simulated) {
      // The host's own clock: the face's timers already follow it.
      if (liveClock != null) {
        liveClock = null;
        liveStateChanged = true;
        app.sendBroadcast(mockTime(1f, -1L));
        app.sendBroadcast(new Intent(Intent.ACTION_TIME_CHANGED));
      }
    } else if (SimulatorClock.needsRefresh(liveClock, now, fixedTimeMillis != null)) {
      liveClock = now;
      liveStateChanged = true;
      app.sendBroadcast(mockTime(0f, now));
      app.sendBroadcast(new Intent(Intent.ACTION_TIME_CHANGED));
    }
    if (liveStateChanged) {
      liveStateChanged = false;
      // The face takes state on its own threads: let it settle, then draw once to flush.
      try {
        Thread.sleep(LIVE_SETTLE_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    Bitmap drawn = source.draw();
    return drawn;
  }

  private Long liveClock;

  /**
   * androidx's {@code MockTime} broadcast: the time its renderer is handed is {@code min +
   * ((now - min) * speed) mod (max - min)}, so a speed of 0 stands still at [min]. (Reset with a
   * speed of 1 and no minimum.)
   */
  static Intent mockTime(float speed, long min) {
    return new Intent("androidx.wear.watchface.MockTime")
        .putExtra("androidx.wear.watchface.extra.MOCK_TIME_SPEED_MULTIPLIER", speed)
        .putExtra("androidx.wear.watchface.extra.MOCK_TIME_WRAPPING_MIN_TIME", min)
        .putExtra("androidx.wear.watchface.extra.MOCK_TIME_WRAPPING_MAX_TIME", Long.MAX_VALUE);
  }
  private static final long LIVE_SETTLE_MS = 400;

  private Boolean liveAmbient;
  private Map<String, byte[]> liveStyle = Collections.emptyMap();
  private String liveComplicationSignature = "";
  private boolean liveStateChanged;

  private static String signature(Map<Integer, Object> complications) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<Integer, Object> e : complications.entrySet()) {
      Parcel p = Parcel.obtain();
      try {
        p.writeParcelable((Parcelable) e.getValue(), 0);
        sb.append(e.getKey()).append(':').append(java.util.Arrays.hashCode(p.marshall())).append(';');
      } finally {
        p.recycle();
      }
    }
    return sb.toString();
  }

  /** Puts the interactive instance in the state a frame is asked for. */
  private void applyLiveState(
      boolean ambient, Map<Integer, Object> complicationSnapshot, Map<String, byte[]> style)
      throws RemoteException {
    IBinder instance = headless;
    if (!sameStyle(style, liveStyle)) {
      Parcel data = Parcel.obtain();
      Parcel reply = Parcel.obtain();
      try {
        data.writeInterfaceToken(INTERACTIVE_DESCRIPTOR);
        data.writeString(instanceId);
        data.writeInt(1);
        writeUserStyleParcel(data, style);
        data.setDataPosition(0);
        instance.transact(TRANSACTION_INTERACTIVE_UPDATE_INSTANCE_SYNC, data, reply, 0);
        reply.setDataPosition(0);
        reply.readException();
      } finally {
        reply.recycle();
        data.recycle();
      }
      liveStyle = new LinkedHashMap<>(style);
      liveStateChanged = true;
    }
    String complicationSignature = signature(complicationSnapshot);
    if (!complicationSignature.equals(liveComplicationSignature)) {
      if (!complicationSignature.equals(liveComplicationSignature)) liveStateChanged = true;
      liveComplicationSignature = complicationSignature;
      Parcel data = Parcel.obtain();
      Parcel reply = Parcel.obtain();
      try {
        data.writeInterfaceToken(INTERACTIVE_DESCRIPTOR);
        int tx;
        if (complicationSnapshot.isEmpty()) {
          tx = TRANSACTION_INTERACTIVE_CLEAR_COMPLICATION_DATA;
        } else {
          tx = TRANSACTION_INTERACTIVE_OVERRIDE_COMPLICATION_DATA;
          data.writeInt(complicationSnapshot.size());
          for (Map.Entry<Integer, Object> entry : complicationSnapshot.entrySet()) {
            data.writeInt(1);
            data.writeString(PARCEL_IMPL);
            data.writeString(ID_AND_COMPLICATION_DATA_WIRE);
            writeIntField(data, 1, entry.getKey());
            int f2 = openField(data, 2);
            data.writeParcelable((Parcelable) entry.getValue(), 0);
            closeField(data, f2);
          }
        }
        data.setDataPosition(0);
        instance.transact(tx, data, reply, 0);
        reply.setDataPosition(0);
        reply.readException();
      } finally {
        reply.recycle();
        data.recycle();
      }
    }
    if (!Boolean.valueOf(ambient).equals(liveAmbient)) {
      sendUiState(instance, ambient);
      liveAmbient = ambient;
      liveStateChanged = true;
    }
  }

  private void sendUiState(IBinder instance, boolean ambient) throws RemoteException {
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(INTERACTIVE_DESCRIPTOR);
      data.writeInt(1);
      writeWatchUiState(data, ambient);
      data.setDataPosition(0);
      instance.transact(TRANSACTION_INTERACTIVE_SET_WATCH_UI_STATE, data, reply, 0);
      reply.setDataPosition(0);
      reply.readException();
    } finally {
      reply.recycle();
      data.recycle();
    }
  }

  private static boolean sameStyle(Map<String, byte[]> a, Map<String, byte[]> b) {
    if (a.size() != b.size()) return false;
    for (Map.Entry<String, byte[]> e : a.entrySet()) {
      if (!java.util.Arrays.equals(e.getValue(), b.get(e.getKey()))) return false;
    }
    return true;
  }

  static void writeWatchUiState(Parcel parcel, boolean ambient) {
    parcel.writeString(PARCEL_IMPL);
    parcel.writeString(WATCH_UI_STATE_WIRE);
    writeBooleanField(parcel, 1, ambient);
    writeIntField(parcel, 2, 1);
  }

  static void writeUserStyleParcel(Parcel parcel, Map<String, byte[]> style) {
    parcel.writeString(PARCEL_IMPL);
    parcel.writeString(USER_STYLE_WIRE);
    int f1 = openField(parcel, 1);
    writeUserStyleMap(parcel, style);
    closeField(parcel, f1);
  }

  private IBinder tryCreateInteractiveInstance(
      IBinder controlBinder,
      IBinder wpBinder,
      int width,
      int height,
      boolean burnIn,
      boolean lowBit,
      boolean ambient) {
    if (wpBinder == null) {
      System.err.println("WatchFaceClient: no wallpaper service bound, so no interactive instance");
      return null;
    }
    CompletableFuture<IBinder> pendingFuture = new CompletableFuture<>();
    Binder pendingCallback =
        new Binder() {
          @Override
          protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
              throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
              reply.writeString(PENDING_INTERACTIVE_DESCRIPTOR);
              return true;
            }
            if (code == IBinder.FIRST_CALL_TRANSACTION + 1) {
              data.enforceInterface(PENDING_INTERACTIVE_DESCRIPTOR);
              reply.writeNoException();
              reply.writeInt(1);
              return true;
            }
            if (code == IBinder.FIRST_CALL_TRANSACTION + 2) {
              data.enforceInterface(PENDING_INTERACTIVE_DESCRIPTOR);
              pendingFuture.complete(data.readStrongBinder());
              return true;
            }
            if (code == IBinder.FIRST_CALL_TRANSACTION + 3) {
              data.enforceInterface(PENDING_INTERACTIVE_DESCRIPTOR);
              pendingFuture.completeExceptionally(
                  new IllegalStateException("Interactive watch face crashed"));
              return true;
            }
            return super.onTransact(code, data, reply, flags);
          }
        };
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    IBinder syncInstance = null;
    try {
      data.writeInterfaceToken(CONTROL_DESCRIPTOR);
      data.writeInt(1);
      writeInteractiveParams(
          data,
          instanceId,
          component.getClassName().isEmpty() ? component.getPackageName() : null,
          burnIn,
          lowBit,
          ambient);
      data.writeStrongBinder(pendingCallback);
      data.setDataPosition(0);
      if (!controlBinder.transact(TRANSACTION_GET_OR_CREATE_INTERACTIVE, data, reply, 0)) {
        return null;
      }
      reply.setDataPosition(0);
      reply.readException();
      syncInstance = reply.readStrongBinder();
    } catch (Throwable t) {
      t.printStackTrace(System.err);
      return null;
    } finally {
      reply.recycle();
      data.recycle();
    }
    if (syncInstance != null) {
      return syncInstance;
    }
    Binder connection =
        new Binder() {
          @Override
          protected boolean onTransact(int code, Parcel in, Parcel out, int flags)
              throws RemoteException {
            String desc = "android.service.wallpaper.IWallpaperConnection";
            if (code == INTERFACE_TRANSACTION) {
              out.writeString(desc);
              return true;
            }
            if (code == 1) { // attachEngine(IWallpaperEngine, int displayId)
              in.enforceInterface(desc);
              IBinder engine = in.readStrongBinder();
              in.readInt();
              wallpaperEngine = engine;
              if (engine != null && released) {
                transactWallpaperEngine(engine, 10, 0, 0); // destroy: nobody will draw it
              } else if (engine != null) {
                transactWallpaperEngine(engine, 1, width, height); // setDesiredSize
                transactWallpaperEngine(engine, 3, 1, 0); // setVisibility(true)
              }
              out.writeNoException();
              return true;
            }
            if (code == 3) { // setWallpaper
              in.enforceInterface(desc);
              out.writeNoException();
              out.writeInt(0);
              return true;
            }
            if (code >= 2 && code <= 5) {
              in.enforceInterface(desc);
              out.writeNoException();
              return true;
            }
            return super.onTransact(code, in, out, flags);
          }
        };
    main.post(
        () -> {
          Parcel wpData = Parcel.obtain();
          try {
            wpData.writeInterfaceToken("android.service.wallpaper.IWallpaperService");
            wpData.writeStrongBinder(connection);
            wpData.writeStrongBinder(new Binder());
            wpData.writeInt(WindowManager.LayoutParams.TYPE_WALLPAPER);
            wpData.writeInt(0); // isPreview = false
            wpData.writeInt(width);
            wpData.writeInt(height);
            wpData.writeInt(1);
            new Rect(0, 0, 0, 0).writeToParcel(wpData, 0);
            wpData.writeInt(Display.DEFAULT_DISPLAY);
            wpData.writeInt(0); // which
            wpData.writeInt(0); // WallpaperInfo = null
            wpData.setDataPosition(0);
            wpBinder.transact(1, wpData, null, IBinder.FLAG_ONEWAY);
          } catch (Throwable t) {
            pendingFuture.completeExceptionally(t);
          } finally {
            wpData.recycle();
          }
        });
    try {
      return pendingFuture.get(INTERACTIVE_INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (Throwable t) {
      t.printStackTrace(System.err);
      return null;
    }
  }

  private static void transactWallpaperEngine(IBinder engine, int tx, int arg1, int arg2) {
    Parcel p = Parcel.obtain();
    try {
      p.writeInterfaceToken("android.service.wallpaper.IWallpaperEngine");
      if (tx == 1) {
        p.writeInt(arg1);
        p.writeInt(arg2);
      } else if (tx == 3) {
        p.writeInt(arg1);
      } else if (tx == 6) {
        p.writeInt(arg1);
        p.writeLong(0L);
      }
      p.setDataPosition(0);
      engine.transact(tx, p, null, IBinder.FLAG_ONEWAY);
    } catch (Throwable ignored) {
    } finally {
      p.recycle();
    }
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

  private static List<Slot> readSlots(IBinder instance, boolean interactive)
      throws RemoteException {
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(interactive ? INTERACTIVE_DESCRIPTOR : HEADLESS_DESCRIPTOR);
      data.setDataPosition(0);
      instance.transact(
          interactive
              ? TRANSACTION_INTERACTIVE_GET_COMPLICATION_DETAILS
              : TRANSACTION_GET_COMPLICATION_STATE,
          data,
          reply,
          0);
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

  private static List<StyleSetting> readStyleSchema(IBinder instance, boolean interactive) {
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(interactive ? INTERACTIVE_DESCRIPTOR : HEADLESS_DESCRIPTOR);
      data.setDataPosition(0);
      int tx =
          interactive
              ? TRANSACTION_INTERACTIVE_GET_USER_STYLE_SCHEMA
              : TRANSACTION_GET_USER_STYLE_SCHEMA;
      if (!instance.transact(tx, data, reply, 0)) {
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
  private static void releaseInstance(IBinder instance, boolean wasInteractive) {
    if (instance == null) return;
    Parcel data = Parcel.obtain();
    try {
      data.writeInterfaceToken(wasInteractive ? INTERACTIVE_DESCRIPTOR : HEADLESS_DESCRIPTOR);
      data.setDataPosition(0);
      instance.transact(
          wasInteractive ? TRANSACTION_INTERACTIVE_RELEASE : TRANSACTION_RELEASE,
          data,
          null,
          IBinder.FLAG_ONEWAY);
    } catch (Throwable ignored) {
      // Already gone.
    } finally {
      data.recycle();
    }
  }

  void release() {
    released = true;
    IBinder instance = headless;
    boolean wasInteractive = interactive;
    headless = null;
    IBinder engine = wallpaperEngine;
    wallpaperEngine = null;
    SimulatorClock.setOverride(null);
    LiveFrameSource liveSource = live;
    live = null;
    if (liveSource != null) liveSource.release();
    if (engine != null) {
      transactWallpaperEngine(engine, 10, 0, 0); // destroy
    }
    worker.execute(() -> releaseInstance(instance, wasInteractive));
    worker.shutdown();
    // The runtime's services are shared by every client of it, and unbinding by intent unbinds them
    // for the next face too (a face set again right after the last one: its instance request then
    // died with "Job was cancelled" when the service's scope was torn down under it). Keep them
    // bound: the next client reuses them.
    boolean sharedRuntime = component.getClassName().isEmpty();
    if (!sharedRuntime && wallpaperService != null && wallpaperComponent != null) {
      navigator.unbindService(
          new Intent(WallpaperService.SERVICE_INTERFACE).setComponent(wallpaperComponent));
      wallpaperService = null;
    }
    if (!sharedRuntime && control != null) {
      ComponentName service = controlService(app);
      if (service != null) {
        navigator.unbindService(new Intent(CONTROL_ACTION).setComponent(service));
      }
      control = null;
    }
  }

  private void post(Bitmap frame, Throwable e) {
    post(frame, e, false);
  }

  private void post(Bitmap frame, Throwable e, boolean fromLiveDraw) {
    main.post(
        () -> {
          if (e != null) {
            fail(e);
          } else {
            error = null;
            // Which path made the bitmap the listener is about to publish, for `watchface status`.
            lastFrameLive = fromLiveDraw;
            frames++;
            listener.rendered(this, frame, null);
          }
        });
  }

  private void fail(Throwable e) {
    error = FaceErrors.describe(frames == 0 ? "starting the watch face" : "rendering the watch face", e);
    listener.rendered(this, null, error);
  }

  // ---------------------------------------------------------------------------------------------
  // VersionedParcelable (ParcelImpl / VersionedParcelParcel) wire encoding
  //
  // VersionedParcelParcel compares field IDs lexicographically as strings
  // (`String.valueOf(mFieldId).compareTo(String.valueOf(fieldId)) > 0`), so fields in every nested
  // sub-parcel MUST be written in ascending order of `String.valueOf(fieldId)` (for example,
  // WatchFaceRenderParams writes 1, 100, 3, 5).

  static void writeInteractiveParams(
      Parcel parcel,
      String instanceId,
      String auxiliaryPackageName,
      boolean burnIn,
      boolean lowBit,
      boolean ambient) {
    parcel.writeString(PARCEL_IMPL);
    parcel.writeString(INTERACTIVE_PARAMS_WIRE);
    // Field 1: String mInstanceId
    int f1 = openField(parcel, 1);
    parcel.writeString(instanceId);
    closeField(parcel, f1);
    // Field 100 ("100" < "2"): List<IdAndComplicationDataWireFormat>
    int f100 = openField(parcel, 100);
    parcel.writeInt(0);
    closeField(parcel, f100);
    // Field 101: auxiliaryComponentName
    int f101 = openField(parcel, 101);
    parcel.writeString(null);
    closeField(parcel, f101);
    // Field 102: resourcePackageName
    int f102 = openField(parcel, 102);
    parcel.writeString(auxiliaryPackageName);
    closeField(parcel, f102);
    // Field 2: DeviceConfig mDeviceConfig (fields 1, 2, 4, 5)
    int f2 = openField(parcel, 2);
    parcel.writeString(DEVICE_CONFIG_WIRE);
    writeBooleanField(parcel, 1, lowBit);
    writeBooleanField(parcel, 2, burnIn);
    writeLongField(parcel, 4, 0L);
    writeLongField(parcel, 5, 0L);
    closeField(parcel, f2);
    // Field 3: WatchUiState (field 1: inAmbientMode, field 2: interruptionFilter)
    int f3 = openField(parcel, 3);
    parcel.writeString(WATCH_UI_STATE_WIRE);
    writeBooleanField(parcel, 1, ambient);
    writeIntField(parcel, 2, 1);
    closeField(parcel, f3);
    // Field 4: UserStyleWireFormat (field 1: Map<String, byte[]>)
    int f4 = openField(parcel, 4);
    parcel.writeString(USER_STYLE_WIRE);
    int f4_1 = openField(parcel, 1);
    writeUserStyleMap(parcel, Collections.emptyMap());
    closeField(parcel, f4_1);
    closeField(parcel, f4);
  }

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
