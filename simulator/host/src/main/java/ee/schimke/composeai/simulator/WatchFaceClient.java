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
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.SharedMemory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
  private static final String ID_AND_COMPLICATION_DATA_WIRE =
      "androidx.wear.watchface.data.IdAndComplicationDataWireFormatParcelizer";
  private static final String ID_AND_COMPLICATION_STATE_WIRE =
      "androidx.wear.watchface.data.IdAndComplicationStateWireFormatParcelizer";
  private static final String COMPLICATION_STATE_WIRE =
      "androidx.wear.watchface.data.ComplicationStateWireFormatParcelizer";

  /** {@code VersionedParcel.TYPE_VERSIONED_PARCELABLE}. */
  private static final int COLLECTION_TYPE_VERSIONED_PARCELABLE = 1;

  /** {@code DrawMode.INTERACTIVE} / {@code AMBIENT}. */
  static final int DRAW_INTERACTIVE = 0;

  static final int DRAW_AMBIENT = 3;
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
  /** Complication data by slot id, as the app's {@code ComplicationData} (Parcelable) objects. */
  private final Map<Integer, Object> complications = new LinkedHashMap<>();

  private IBinder control;
  private volatile IBinder headless;
  private volatile List<Slot> slots = Collections.emptyList();
  private volatile Long fixedTimeMillis;
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

  void setFixedTimeMillis(Long timeMillis) {
    this.fixedTimeMillis = timeMillis;
  }

  Long fixedTimeMillis() {
    return fixedTimeMillis;
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
    worker.execute(
        () -> {
          try {
            IBinder instance = createHeadlessInstance(controlBinder, component, width, height);
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

  private void renderNow(boolean ambient) throws RemoteException {
    IBinder instance = headless;
    if (instance == null) return;
    Long pinned = fixedTimeMillis;
    long timeMillis = pinned != null ? pinned : System.currentTimeMillis();
    Map<Integer, Object> snapshot;
    synchronized (complications) {
      snapshot = new LinkedHashMap<>(complications);
    }
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    Bundle bundle;
    try {
      data.writeInterfaceToken(HEADLESS_DESCRIPTOR);
      data.writeInt(1);
      writeRenderParams(
          data, ambient ? DRAW_AMBIENT : DRAW_INTERACTIVE, timeMillis, snapshot);
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
    post(readImage(bundle), null);
  }

  private static IBinder createHeadlessInstance(
      IBinder controlBinder, ComponentName component, int width, int height)
      throws RemoteException {
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(CONTROL_DESCRIPTOR);
      data.writeInt(1);
      writeHeadlessParams(data, component, width, height, "simulator");
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
    parcel.writeString(PARCEL_IMPL);
    parcel.writeString(HEADLESS_PARAMS_WIRE);
    // Field 1: ComponentName mWatchFaceName
    int f1 = openField(parcel, 1);
    parcel.writeParcelable(component, 0);
    closeField(parcel, f1);
    // Field 2: DeviceConfig mDeviceConfig (fields 1, 2, 4, 5)
    int f2 = openField(parcel, 2);
    parcel.writeString(DEVICE_CONFIG_WIRE);
    writeBooleanField(parcel, 1, false);
    writeBooleanField(parcel, 2, false);
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
    parcel.writeInt(0); // empty map
    closeField(parcel, f5_1);
    closeField(parcel, f5);
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
    if (COMPLICATION_STATE_WIRE.equals(innerType)) {
      while (parcel.dataPosition() + 8 <= f2End) {
        int subStart = parcel.dataPosition();
        int subSize = parcel.readInt();
        int subId = parcel.readInt();
        if (subSize < 8 || subStart + subSize > f2End) break;
        if (subId == 3) {
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
    return new Slot(slotId, supportedTypes, enabled);
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
