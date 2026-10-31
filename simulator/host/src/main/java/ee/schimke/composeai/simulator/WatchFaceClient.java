package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SharedMemory;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An androidx watch face ({@code androidx.wear.watchface.WatchFaceService}), hosted the way Wear's
 * watch face editor and Android Studio's previews host one: through the library's own {@code
 * WatchFaceControlService} and its headless instance, which renders the face to a bitmap with no
 * wallpaper surface. A watch face built on the library carries that service in its manifest (the
 * library's manifest merges it in), so any such APK can be hosted.
 *
 * <p>The AIDL ({@code IWatchFaceControlService}, {@code IHeadlessWatchFace}) and its wire formats
 * are the app's own classes, reached reflectively as {@link TileClient} reaches the tiles AIDL: this
 * class links against none of them, so it works with whichever library version the app ships.
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

  private static final String WATCHFACE = "androidx.wear.watchface.";
  private static final String CONTROL = WATCHFACE + "control.";
  private static final String DATA = WATCHFACE + "data.";
  private static final String COMPLICATION_DATA =
      "android.support.wearable.complications.ComplicationData";

  /** {@code DrawMode.INTERACTIVE} / {@code AMBIENT}. */
  private static final int DRAW_INTERACTIVE = 0;

  private static final int DRAW_AMBIENT = 3;
  /** {@code WatchFaceLayer.BASE | COMPLICATIONS | COMPLICATIONS_OVERLAY}. */
  private static final int ALL_LAYERS = 0b111;
  /** {@code RenderParametersWireFormat.ELEMENT_TYPE_NONE}. */
  private static final int ELEMENT_NONE = 0;

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
  /** Complication data by slot id, as the app's {@code ComplicationData} (wire) objects. */
  private final Map<Integer, Object> complications = new LinkedHashMap<>();

  private Object control;
  private volatile Object headless;
  private volatile List<Slot> slots = Collections.emptyList();
  private int width;
  private int height;
  private String error;
  private int frames;

  /** One of the face's complication slots, as {@code getComplicationState} describes it. */
  static final class Slot {
    final int id;
    final int[] supportedTypes;
    final boolean enabled;

    Slot(int id, int[] supportedTypes, boolean enabled) {
      this.id = id;
      this.supportedTypes = supportedTypes;
      this.enabled = enabled;
    }

    boolean supports(int type) {
      for (int t : supportedTypes) if (t == type) return true;
      return false;
    }
  }

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

  Map<Integer, Object> complications() {
    return complications;
  }

  /**
   * Binds the control service and creates the headless instance at [width]×[height], then renders
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
                + " has no androidx.wear.watchface WatchFaceControlService: only watch faces built"
                + " on androidx.wear.watchface can be hosted");
      }
      IBinder binder =
          navigator.bindForHost(new Intent(CONTROL_ACTION).setComponent(service));
      if (binder == null) throw new IllegalStateException(service + " returned no binder");
      control = asInterface(CONTROL + "IWatchFaceControlService", binder);
    } catch (ReflectiveOperationException | RuntimeException e) {
      fail(e);
      return;
    }
    worker.execute(
        () -> {
          try {
            Object deviceConfig =
                load(DATA + "DeviceConfig")
                    .getConstructor(boolean.class, boolean.class, long.class, long.class)
                    .newInstance(false, false, 0L, 0L);
            Object params =
                load(CONTROL + "data.HeadlessWatchFaceInstanceParams")
                    .getConstructor(
                        ComponentName.class,
                        load(DATA + "DeviceConfig"),
                        int.class,
                        int.class,
                        String.class)
                    .newInstance(component, deviceConfig, width, height, "simulator");
            Object instance =
                load(CONTROL + "IWatchFaceControlService")
                    .getMethod(
                        "createHeadlessWatchFaceInstance",
                        load(CONTROL + "data.HeadlessWatchFaceInstanceParams"))
                    .invoke(control, params);
            if (instance == null) {
              throw new IllegalStateException(
                  component + " is not a watch face the control service can create");
            }
            headless = instance;
            slots = readSlots(instance);
            renderNow(ambient);
          } catch (Throwable e) {
            post(null, e);
          }
        });
  }

  /** Sets (or clears, with null) a slot's data and renders again. */
  void complication(int slotId, Object data, boolean ambient) {
    if (data == null) complications.remove(slotId);
    else complications.put(slotId, data);
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

  private void renderNow(boolean ambient) throws ReflectiveOperationException {
    Object instance = headless;
    if (instance == null) return;
    Class<?> tapList = List.class;
    Object renderParameters =
        load(DATA + "RenderParametersWireFormat")
            .getConstructor(
                int.class,
                int.class,
                int.class,
                int.class,
                String.class,
                int.class,
                int.class,
                tapList)
            .newInstance(
                ambient ? DRAW_AMBIENT : DRAW_INTERACTIVE,
                ALL_LAYERS,
                ELEMENT_NONE,
                0,
                null,
                0,
                0,
                new ArrayList<>());
    Object style =
        load(WATCHFACE + "style.data.UserStyleWireFormat")
            .getConstructor(Map.class)
            .newInstance(new LinkedHashMap<String, byte[]>());
    List<Object> data = new ArrayList<>();
    Class<?> idAndData = load(DATA + "IdAndComplicationDataWireFormat");
    for (Map.Entry<Integer, Object> e : new LinkedHashMap<>(complications).entrySet()) {
      data.add(
          idAndData
              .getConstructor(int.class, load(COMPLICATION_DATA))
              .newInstance(e.getKey(), e.getValue()));
    }
    Class<?> paramsType = load(CONTROL + "data.WatchFaceRenderParams");
    Object params =
        paramsType
            .getConstructor(
                load(DATA + "RenderParametersWireFormat"),
                long.class,
                load(WATCHFACE + "style.data.UserStyleWireFormat"),
                List.class)
            .newInstance(renderParameters, System.currentTimeMillis(), style, data);
    Bundle bundle =
        (Bundle)
            load(CONTROL + "IHeadlessWatchFace")
                .getMethod("renderWatchFaceToBitmap", paramsType)
                .invoke(instance, params);
    post(readImage(bundle), null);
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

  private List<Slot> readSlots(Object instance) throws ReflectiveOperationException {
    List<?> states =
        (List<?>)
            load(CONTROL + "IHeadlessWatchFace").getMethod("getComplicationState").invoke(instance);
    List<Slot> result = new ArrayList<>();
    if (states == null) return result;
    for (Object idAndState : states) {
      int id = (Integer) call(idAndState, "getId");
      Object state = call(idAndState, "getComplicationState");
      int[] types = (int[]) call(state, "getSupportedTypes");
      boolean enabled = (Boolean) call(state, "isEnabled");
      result.add(new Slot(id, types == null ? new int[0] : types, enabled));
    }
    return result;
  }

  /** Lets the instance and the control service go, as changing the watch face does. */
  void release() {
    Object instance = headless;
    headless = null;
    worker.execute(
        () -> {
          try {
            if (instance != null) {
              load(CONTROL + "IHeadlessWatchFace").getMethod("release").invoke(instance);
            }
          } catch (Throwable ignored) {
            // Already gone.
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

  private Object asInterface(String type, IBinder binder) throws ReflectiveOperationException {
    return load(type + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
  }

  private static Object call(Object target, String name) throws ReflectiveOperationException {
    Method m = target.getClass().getMethod(name);
    m.setAccessible(true);
    return m.invoke(target);
  }

  private Class<?> load(String name) throws ClassNotFoundException {
    return Class.forName(name, true, app.getClassLoader());
  }
}
