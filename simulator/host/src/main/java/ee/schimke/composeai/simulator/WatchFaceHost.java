package ee.schimke.composeai.simulator;

import android.app.Application;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.os.Parcelable;
import android.util.DisplayMetrics;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The watch's watch face and complication host: which face is set, which complication data sources
 * feed which slots, which Watch Face Push faces are installed, and which dynamic theme colours the
 * active watch face publishes to the simulated system.
 *
 * <p>With nothing set the system UI shows its built-in face ({@link WatchFaceView}); {@code set}
 * hosts one of the app's androidx or runtime WFF watch faces through {@link WatchFaceClient} or one
 * of its declarative (Watch Face Format) watch faces through {@link DwfWatchFaceClient}; {@code
 * complication} binds one of the device's data sources through {@link ComplicationSourceClient} (or
 * injects a fake complication parcel/entry) and gives its data to the face's slot, or, when the
 * face is not one of the app's, draws it on the built-in face.
 */
final class WatchFaceHost {

  interface Display {
    /** The app's face drew [frame]; null puts the built-in face back. */
    void frame(Bitmap frame);

    /** A complication to draw on the built-in face, or null for none. */
    void builtInComplication(String text);
  }

  /** {@code WallpaperService.SERVICE_INTERFACE}: every watch face service declares it. */
  static final String WALLPAPER_ACTION = "android.service.wallpaper.WallpaperService";

  /** Launch-time watch face ({@code --watchface} or WFF APK package). */
  static final String PROPERTY = "composeai.simulator.watchface";

  static final String ACTION_REQUEST_UPDATE =
      "android.support.wearable.complications.ACTION_REQUEST_UPDATE";
  static final String ACTION_REQUEST_UPDATE_ALL =
      "android.support.wearable.complications.ACTION_REQUEST_UPDATE_ALL";
  static final String EXTRA_PROVIDER_COMPONENT =
      "android.support.wearable.complications.EXTRA_PROVIDER_COMPONENT";
  static final String EXTRA_COMPLICATION_IDS =
      "android.support.wearable.complications.EXTRA_COMPLICATION_IDS";
  static final String BUS_REQUEST_UPDATE = "complicationRequestUpdate";

  private static volatile WatchFaceHost current;

  static WatchFaceHost current() {
    return current;
  }

  static void resetCurrent() {
    current = null;
  }

  private final Application app;
  private final Navigator navigator;
  private final Protocol protocol;
  private final Display display;
  private final Runnable onThemeColorsChanged;
  private final List<DwfWatchFaceClient.Entry> dwfEntries;
  private final Map<String, List<DwfWatchFaceClient.Entry>> pushedDwfEntries =
      new LinkedHashMap<>();
  private final List<Source> sources = new ArrayList<>();
  private final Map<Integer, DwfWatchFaceClient.FakeComplication> fakeOnWireFace =
      new LinkedHashMap<>();
  private WatchFaceClient face;
  private DwfWatchFaceClient dwfFace;
  private boolean ambient;
  private boolean rendering;
  private boolean pendingRender;
  private boolean themeFromWatchface =
      !"false".equalsIgnoreCase(System.getProperty(WatchFaceDynamicColors.PROPERTY, "true"));
  private WatchFaceDynamicColors.Palette lastPalette;
  private String error;

  /** A data source feeding one slot. */
  private static final class Source {
    final int slot;
    /** Bound to the app's face (and released with it), else to the built-in face. */
    final boolean appFace;
    ComplicationSourceClient client;
    Object data;

    Source(int slot, boolean appFace) {
      this.slot = slot;
      this.appFace = appFace;
    }
  }

  WatchFaceHost(Application app, Navigator navigator, Protocol protocol, Display display) {
    this(app, navigator, protocol, display, null);
  }

  WatchFaceHost(
      Application app,
      Navigator navigator,
      Protocol protocol,
      Display display,
      Runnable onThemeColorsChanged) {
    this.app = app;
    this.navigator = navigator;
    this.protocol = protocol;
    this.display = display;
    this.onThemeColorsChanged = onThemeColorsChanged;
    this.dwfEntries = new ArrayList<>(DwfWatchFaceClient.availableFromProperty());
    current = this;
  }

  /** Whether one of the app's faces is set (rather than the built-in face). */
  boolean hosting() {
    return face != null || dwfFace != null;
  }

  /** Returns the package name of the currently active watch face, or null when built-in. */
  String activeWatchFacePackage() {
    if (face != null) {
      return face.component.getPackageName();
    }
    if (dwfFace != null) {
      String id = dwfFace.id();
      int slash = id.indexOf('/');
      if (slash > 0) return id.substring(0, slash);
      for (Map.Entry<String, List<DwfWatchFaceClient.Entry>> entry : pushedDwfEntries.entrySet()) {
        for (DwfWatchFaceClient.Entry e : entry.getValue()) {
          if (e.id.equals(id)) return entry.getKey();
        }
      }
      if (!WffWatchFaces.packages().isEmpty()) {
        return WffWatchFaces.packages().get(0);
      }
      return app.getPackageName();
    }
    return null;
  }

  /**
   * Sets the {@code --watchface} launch target if given, or defaults to the first declarative watch
   * face when the APK has no launcher activity.
   */
  void setFromLaunch() {
    String requested = System.getProperty(PROPERTY);
    if (requested != null && !requested.trim().isEmpty()) {
      try {
        setByName(requested.trim(), null);
      } catch (RuntimeException e) {
        protocol.event(
            "error",
            new Protocol.Json().put("kind", "watchface").put("message", e.getMessage()).object());
      }
      return;
    }
    if (!dwfEntries.isEmpty()
        && app.getPackageManager().getLaunchIntentForPackage(app.getPackageName()) == null) {
      try {
        setDwf(dwfEntries.get(0));
      } catch (RuntimeException e) {
        protocol.event(
            "error",
            new Protocol.Json().put("kind", "watchface").put("message", e.getMessage()).object());
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // WatchState & Watch Face Push hooks

  void onWatchStateChanged() {
    if (!sources.isEmpty()) {
      long now = WatchState.currentTimeMillis();
      for (Source s : sources) {
        if (s.data != null && !(s.data instanceof JSONObject)) {
          if (s.appFace && face != null) {
            face.complication(s.slot, s.data, ambient);
          }
        } else if (!s.appFace) {
          showBuiltInComplication();
        }
      }
      refreshDuePeriodicSources(now);
    }
    requestRender();
  }

  void onPushedSlotUpdated(WatchFacePushStore.StoredSlot slot, boolean rebindIfActive) {
    onPushedSlotUpdated(null, slot, rebindIfActive);
  }

  void onPushedSlotUpdated(
      String previousPackageName, WatchFacePushStore.StoredSlot slot, boolean rebindIfActive) {
    if (previousPackageName != null && !previousPackageName.equals(slot.packageName)) {
      List<DwfWatchFaceClient.Entry> oldEntries = pushedDwfEntries.remove(previousPackageName);
      if (oldEntries != null) dwfEntries.removeAll(oldEntries);
    }
    List<DwfWatchFaceClient.Entry> previous = pushedDwfEntries.remove(slot.packageName);
    if (previous != null) dwfEntries.removeAll(previous);
    if (slot.compiledEntries != null && !slot.compiledEntries.isEmpty()) {
      pushedDwfEntries.put(slot.packageName, new ArrayList<>(slot.compiledEntries));
      for (DwfWatchFaceClient.Entry e : slot.compiledEntries) {
        dwfEntries.removeIf(existing -> existing.id.equals(e.id));
        dwfEntries.add(e);
      }
    }
    if (rebindIfActive) {
      activatePushedSlot(slot);
    }
  }

  void onPushedSlotRemoved(WatchFacePushStore.StoredSlot slot, boolean wasActive) {
    List<DwfWatchFaceClient.Entry> previous = pushedDwfEntries.remove(slot.packageName);
    if (previous != null) dwfEntries.removeAll(previous);
    if (wasActive) {
      unset();
    }
  }

  void activatePushedSlot(WatchFacePushStore.StoredSlot slot) {
    if (WatchFaceClient.hostable(app) && WffWatchFaces.isInstalled(slot.packageName)) {
      if (face != null && slot.packageName.equals(face.component.getPackageName())) {
        releaseFace();
      }
      set(new ComponentName(slot.packageName, ""), null);
      return;
    }
    if (slot.compiledEntries != null && !slot.compiledEntries.isEmpty()) {
      if (dwfFace != null) {
        releaseFace();
      }
      setDwf(slot.compiledEntries.get(0));
    }
  }

  void emitPushEvent(String op, WatchFacePushStore.StoredSlot slot) {
    protocol.event(
        "watchFacePush",
        new Protocol.Json()
            .put("op", op)
            .put("slotId", slot.slotId)
            .put("packageName", slot.packageName)
            .put("versionCode", slot.versionCode)
            .put("compiled", slot.compiledEntries != null && !slot.compiledEntries.isEmpty())
            .object());
  }

  /**
   * Intercepts {@code ComplicationDataSourceUpdateRequester.requestUpdate} / {@code
   * requestUpdateAll} broadcasts (which target {@code com.google.android.wearable.app}) and
   * refreshes matching active slots locally and across {@link DeviceBus}.
   */
  @SuppressWarnings("deprecation")
  static boolean interceptComplicationUpdateBroadcast(Intent intent) {
    if (intent == null) return false;
    String action = intent.getAction();
    if (!ACTION_REQUEST_UPDATE.equals(action) && !ACTION_REQUEST_UPDATE_ALL.equals(action)) {
      return false;
    }
    Object rawComp =
        intent.getExtras() != null ? intent.getExtras().get(EXTRA_PROVIDER_COMPONENT) : null;
    ComponentName provider = rawComp instanceof ComponentName ? (ComponentName) rawComp : null;
    int[] ids =
        ACTION_REQUEST_UPDATE.equals(action) ? intent.getIntArrayExtra(EXTRA_COMPLICATION_IDS) : null;
    WatchFaceHost host = current;
    if (host != null) {
      host.refreshMatchingSources(provider, ids);
    }
    if (DeviceBus.enabled() && provider != null) {
      JSONArray idArray = null;
      if (ids != null) {
        idArray = new JSONArray();
        for (int id : ids) idArray.put(id);
      }
      DeviceBus.broadcast(
          BUS_REQUEST_UPDATE,
          new Protocol.Json()
              .put("component", provider.flattenToString())
              .put("ids", idArray)
              .object());
    }
    return true;
  }

  void onRemoteUpdateRequested(JSONObject body) {
    String compStr = Protocol.string(body, "component");
    ComponentName provider =
        compStr != null ? ComponentName.unflattenFromString(compStr) : null;
    JSONArray idArray = body.optJSONArray("ids");
    int[] ids = null;
    if (idArray != null) {
      ids = new int[idArray.length()];
      for (int i = 0; i < idArray.length(); i++) ids[i] = idArray.optInt(i, -1);
    }
    refreshMatchingSources(provider, ids);
  }

  private void refreshMatchingSources(ComponentName provider, int[] ids) {
    if (provider == null) return;
    for (Source s : new ArrayList<>(sources)) {
      if (!provider.equals(s.client.component)) continue;
      if (ids != null) {
        boolean match = false;
        for (int id : ids) {
          if (id == s.slot) {
            match = true;
            break;
          }
        }
        if (!match) continue;
      }
      try {
        s.client.update();
      } catch (ReflectiveOperationException | RuntimeException e) {
        protocol.event(
            "watchface",
            new Protocol.Json()
                .put("state", "complicationError")
                .put("slot", s.slot)
                .put("error", rootMessage(e))
                .object());
      }
    }
  }

  private void refreshDuePeriodicSources(long nowMillis) {
    for (Source s : new ArrayList<>(sources)) {
      int periodSec = s.client.updatePeriodSeconds();
      if (periodSec <= 0) continue;
      long elapsed = nowMillis - s.client.lastUpdateMillis();
      if (elapsed >= periodSec * 1000L) {
        try {
          s.client.update();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Commands

  /**
   * {@code {"cmd":"watchface", "op": "set" | "render" | "unset" | "config" | "complication" |
   * "uncomplication" | "tap" | "theme" | "push" | "pushed" | "removePush" | "status"}}.
   */
  JSONObject command(JSONObject c) throws JSONException {
    String op = c.optString("op", "status");
    switch (op) {
      case "set":
        {
          String target =
              c.has("component") ? c.getString("component") : c.getString("watchFaceId");
          return setByName(target, parseTimeMillis(c));
        }
      case "render":
        return render(c);
      case "config":
        return configureFace(c);
      case "unset":
        return unset();
      case "complication":
        if (!c.has("component") || c.isNull("component") || c.optString("component").isEmpty()) {
          return fakeComplication(c);
        }
        return complication(
            resolve(c.getString("component")),
            c.has("watchface") ? c.getString("watchface") : null,
            c.optInt("slot", -1),
            DwfWatchFaceClient.parseWireComplicationType(c.opt("type")));
      case "uncomplication":
        if (!c.has("component") && c.has("slot")) {
          return uncomplicationSlot(c.getInt("slot"));
        }
        return uncomplication(c.has("component") ? resolve(c.getString("component")) : null);
      case "tap":
        return tapCommand(c);
      case "theme":
        return themeCommand(c);
      case "push":
        return pushCommand(c);
      case "pushed":
        return new Protocol.Json().put("pushed", WatchFacePushStore.get().describeSlots()).object();
      case "removePush":
        {
          String id =
              c.has("slotId")
                  ? c.getString("slotId")
                  : c.getString("packageName");
          WatchFacePushStore.get().removeFromControl(app, id);
          return describe();
        }
      case "status":
        return describe();
      default:
        throw new IllegalArgumentException(
            "watchface op "
                + op
                + ": use set, render, unset, config, complication, uncomplication, tap, theme, push, pushed, removePush or status");
    }
  }

  private static Long parseTimeMillis(JSONObject c) throws JSONException {
    if (!c.has("timeMillis") || c.isNull("timeMillis")) return null;
    return c.getLong("timeMillis");
  }

  JSONObject setByName(String target) {
    return setByName(target, null);
  }

  JSONObject setByName(String target, Long timeMillis) {
    if (timeMillis != null && WatchState.current() != null) {
      try {
        WatchState.current()
            .clockCommand(
                new JSONObject().put("timeMillis", timeMillis.longValue()).put("frozen", true));
      } catch (JSONException ignored) {
      }
    }
    DwfWatchFaceClient.Entry dwf = findDwfEntry(target);
    if (dwf != null && !WatchFaceClient.hostable(app)) {
      return setDwf(dwf);
    }
    if (dwf != null && !WffWatchFaces.isInstalled(target)) {
      return setDwf(dwf);
    }
    return set(resolve(target), timeMillis);
  }

  /** Sets one of the app's declarative (Watch Face Format) watch faces. */
  JSONObject setDwf(DwfWatchFaceClient.Entry entry) {
    if (dwfFace != null && dwfFace.id().equals(entry.id)) return describe();
    releaseFace();
    dwfFace = new DwfWatchFaceClient(app, entry, this::renderedDwf);
    WatchFacePushStore.get().setActivePackageName(activeWatchFacePackage());
    DisplayMetrics metrics = app.getResources().getDisplayMetrics();
    rendering = true;
    display.builtInComplication(null);
    dwfFace.start(metrics.widthPixels, metrics.heightPixels, ambient);
    return describe();
  }

  /** Sets one of the app's androidx or WFF watch faces, replacing any other. */
  JSONObject set(ComponentName component) {
    return set(component, null);
  }

  JSONObject set(ComponentName component, Long timeMillis) {
    checkWatchFace(component);
    if (face != null && face.component.equals(component)) {
      if (timeMillis != null || (face.fixedTimeMillis() != null)) {
        face.setFixedTimeMillis(timeMillis);
        rendering = true;
        face.render(ambient);
      }
      return describe();
    }
    releaseFace();
    face = new WatchFaceClient(app, navigator, component, this::rendered);
    face.setFixedTimeMillis(timeMillis);
    WatchFacePushStore.get().setActivePackageName(component.getPackageName());
    DisplayMetrics metrics = app.getResources().getDisplayMetrics();
    rendering = true;
    face.start(metrics.widthPixels, metrics.heightPixels, ambient);
    // The built-in face is hidden behind the app's while it is set.
    display.builtInComplication(null);
    return describe();
  }

  private JSONObject configureFace(JSONObject c) throws JSONException {
    if (dwfFace == null && face == null) {
      throw new IllegalStateException(
          "watchface op config requires an active hosted watch face");
    }
    JSONObject values = c.optJSONObject("values");
    String singleCfgId = c.has("configId") ? c.getString("configId") : c.optString("id", "");
    if (dwfFace != null) {
      if (values != null) {
        for (Iterator<String> it = values.keys(); it.hasNext(); ) {
          String k = it.next();
          dwfFace.setConfig(k, String.valueOf(values.get(k)));
        }
      } else {
        if (singleCfgId.isEmpty()) {
          throw new JSONException("watchface op config requires 'configId', 'id', or 'values'");
        }
        dwfFace.setConfig(singleCfgId, String.valueOf(c.get("value")));
      }
      rendering = true;
      dwfFace.render(ambient);
    } else {
      if (values != null) {
        for (Iterator<String> it = values.keys(); it.hasNext(); ) {
          String k = it.next();
          face.setConfig(k, String.valueOf(values.get(k)));
        }
      } else {
        if (singleCfgId.isEmpty()) {
          throw new JSONException("watchface op config requires 'configId', 'id', or 'values'");
        }
        face.setConfig(singleCfgId, String.valueOf(c.get("value")));
      }
      rendering = true;
      face.render(ambient);
    }
    return describe();
  }

  /** Re-renders the active watch face, optionally pinning or clearing {@code timeMillis}. */
  JSONObject render(JSONObject c) throws JSONException {
    if (face == null && dwfFace == null) {
      throw new IllegalStateException("no hosted watch face is set; call op:set first");
    }
    if (c.has("ambient")) {
      ambient = c.getBoolean("ambient");
      if (WatchState.current() != null) {
        WatchState.current()
            .syncDisplay(
                ambient, WatchState.current().burnIn(), WatchState.current().lowBit());
      }
    }
    if (c.has("timeMillis") && WatchState.current() != null) {
      if (c.isNull("timeMillis")) {
        WatchState.current().clockCommand(new JSONObject().put("reset", true));
      } else {
        WatchState.current()
            .clockCommand(
                new JSONObject().put("timeMillis", c.getLong("timeMillis")).put("frozen", true));
      }
    }
    if (face != null) {
      if (c.has("timeMillis")) {
        face.setFixedTimeMillis(c.isNull("timeMillis") ? null : c.getLong("timeMillis"));
      }
      rendering = true;
      face.render(ambient);
    } else {
      rendering = true;
      dwfFace.render(ambient);
    }
    return describe();
  }

  /** Back to the built-in face; complication sources stay bound and draw on it. */
  JSONObject unset() {
    releaseFace();
    WatchFacePushStore.get().setActivePackageName(null);
    updateDynamicColors(null);
    display.frame(null);
    showBuiltInComplication();
    return describe();
  }

  /**
   * Binds [source] to [slot] of [watchFace]: the app's face, set if it is not already, or, when
   * [watchFace] is null or another package's (Android Studio installs its own test face), the
   * built-in face. [type] is the wire {@code ComplicationType} ({@code 3} = short text).
   */
  JSONObject complication(ComponentName source, String watchFace, int slot, int type)
      throws JSONException {
    checkSource(source);
    boolean appFace = false;
    if (watchFace != null) {
      DwfWatchFaceClient.Entry dwf = findDwfEntry(watchFace);
      if (dwf != null && !WatchFaceClient.hostable(app)) {
        appFace = true;
        setDwf(dwf);
      } else {
        ComponentName wfComp = resolve(watchFace);
        if (wfComp.getPackageName().equals(app.getPackageName())
            || WffWatchFaces.isInstalled(wfComp.getPackageName())) {
          appFace = true;
          set(wfComp);
        } else if (dwf != null) {
          appFace = true;
          setDwf(dwf);
        }
      }
    } else if (hosting()) {
      appFace = true;
    }
    if (appFace && !activeSlots().isEmpty()) checkSlot(slot, type);
    // One source per slot, as a face has.
    for (Iterator<Source> it = sources.iterator(); it.hasNext(); ) {
      Source s = it.next();
      if (s.slot == slot && s.appFace == appFace) {
        s.client.release();
        it.remove();
      }
    }
    fakeOnWireFace.remove(slot);
    Source bound = new Source(slot, appFace);
    ComplicationSourceClient client =
        new ComplicationSourceClient(
            app, navigator, source, slot, type, data -> data(bound, data));
    bound.client = client;
    sources.add(bound);
    try {
      client.start();
    } catch (ReflectiveOperationException | RuntimeException e) {
      sources.remove(bound);
      throw new IllegalStateException(
          "could not bind complication data source "
              + source.flattenToShortString()
              + ": "
              + rootMessage(e),
          e);
    }
    return describe()
        .put("complication", slotJson(bound))
        .put("host", appFace ? "app" : "built-in");
  }

  private void data(Source bound, Object data) {
    if (!sources.contains(bound)) return;
    bound.data = data;
    protocol.event(
        "watchface",
        new Protocol.Json()
            .put("state", "complicationData")
            .put("slot", bound.slot)
            .put("text", ComplicationSourceClient.text(data, app.getResources()))
            .object());
    if (bound.appFace && face != null) {
      if (data instanceof JSONObject) {
        try {
          DwfWatchFaceClient.FakeComplication fake =
              DwfWatchFaceClient.FakeComplication.fromJson((JSONObject) data, bound.slot);
          Parcelable built =
              ComplicationParcels.buildFakeParcelable(
                  app.getClassLoader(), fake, findSlot(bound.slot));
          face.complication(bound.slot, built, ambient);
        } catch (JSONException ignored) {
        }
      } else {
        face.complication(bound.slot, data, ambient);
      }
    } else if (bound.appFace && dwfFace != null) {
      if (data instanceof JSONObject) {
        try {
          DwfWatchFaceClient.FakeComplication fake =
              DwfWatchFaceClient.FakeComplication.fromJson((JSONObject) data, bound.slot);
          dwfFace.setFakeComplication(bound.slot, fake, ambient);
        } catch (JSONException ignored) {
        }
      } else {
        dwfFace.complication(bound.slot, bound.client.type, data, ambient);
      }
    } else {
      showBuiltInComplication();
    }
  }

  /**
   * Injects fake complication values directly into a slot (`{"cmd":"watchface","op":"complication",
   * "slot":0,"type":"SHORT_TEXT","text":"60","title":"Steps","value":60,"min":0,"max":100}`) on
   * DWF-RC, runtime WFF, androidx, or built-in watch faces.
   */
  JSONObject fakeComplication(JSONObject c) throws JSONException {
    if (c.has("watchface") && !c.isNull("watchface")) {
      setByName(c.getString("watchface"));
    }
    int defaultSlot = activeSlots().isEmpty() ? 0 : activeSlots().get(0).id;
    DwfWatchFaceClient.FakeComplication fake =
        DwfWatchFaceClient.FakeComplication.fromJson(c, defaultSlot);
    if (dwfFace != null) {
      if (!activeSlots().isEmpty()) checkSlot(fake.slot, fake.wireType);
      releaseBoundAppSlot(fake.slot);
      rendering = true;
      dwfFace.setFakeComplication(fake.slot, fake, ambient);
      return describe().put("complication", fake.toJson()).put("host", "app");
    }
    if (face != null) {
      WatchFaceClient.Slot slot = findSlot(fake.slot);
      if (!activeSlots().isEmpty()) {
        int resolvedType = ComplicationParcels.resolveWireType(fake, slot);
        checkSlot(fake.slot, resolvedType);
      }
      releaseBoundAppSlot(fake.slot);
      Parcelable parcelable =
          ComplicationParcels.buildFakeParcelable(app.getClassLoader(), fake, slot);
      fakeOnWireFace.put(fake.slot, fake);
      rendering = true;
      face.complication(fake.slot, parcelable, ambient);
      return describe().put("complication", fake.toJson()).put("host", "app");
    }
    display.builtInComplication(fake.text != null ? fake.text : String.valueOf(fake.value));
    return describe().put("complication", fake.toJson()).put("host", "built-in");
  }

  private void releaseBoundAppSlot(int slot) {
    for (Iterator<Source> it = sources.iterator(); it.hasNext(); ) {
      Source s = it.next();
      if (s.slot == slot && s.appFace) {
        s.client.release();
        it.remove();
      }
    }
  }

  JSONObject uncomplicationSlot(int slot) {
    for (Iterator<Source> it = sources.iterator(); it.hasNext(); ) {
      Source s = it.next();
      if (s.slot == slot) {
        s.client.release();
        it.remove();
        if (s.appFace && face != null) face.complication(s.slot, null, ambient);
      }
    }
    if (fakeOnWireFace.remove(slot) != null && face != null) {
      rendering = true;
      face.complication(slot, null, ambient);
    }
    if (dwfFace != null) {
      rendering = true;
      dwfFace.setFakeComplication(slot, null, ambient);
    } else {
      showBuiltInComplication();
    }
    return describe();
  }

  /** Unbinds [source]'s complications, or every one with null. */
  JSONObject uncomplication(ComponentName source) {
    for (Iterator<Source> it = sources.iterator(); it.hasNext(); ) {
      Source s = it.next();
      if (source == null || s.client.component.equals(source)) {
        s.client.release();
        it.remove();
        if (s.appFace && face != null) face.complication(s.slot, null, ambient);
        if (s.appFace && dwfFace != null) {
          dwfFace.complication(s.slot, s.client.type, null, ambient);
        }
      }
    }
    if (source == null) {
      if (!fakeOnWireFace.isEmpty() && face != null) {
        for (Integer slotId : new ArrayList<>(fakeOnWireFace.keySet())) {
          face.complication(slotId, null, ambient);
        }
      }
      fakeOnWireFace.clear();
      if (dwfFace != null) {
        rendering = true;
        dwfFace.clearFakeComplications(ambient);
      }
    }
    showBuiltInComplication();
    return describe();
  }

  private JSONObject tapCommand(JSONObject c) throws JSONException {
    Integer slotId = null;
    if (c.has("slot") && !c.isNull("slot")) {
      slotId = c.getInt("slot");
    } else if (c.has("x") && c.has("y")) {
      WatchFaceClient.Slot hit = slotAt((float) c.getDouble("x"), (float) c.getDouble("y"));
      if (hit != null) slotId = hit.id;
    }
    if (slotId == null) {
      return new Protocol.Json().put("tapped", false).put("reason", "noComplicationSlot").object();
    }
    boolean fired = fireComplicationTap(slotId.intValue());
    return new Protocol.Json().put("slot", slotId.intValue()).put("tapped", fired).object();
  }

  /** Handles a single tap at display coordinates {@code (x, y)} on the watch face surface. */
  boolean tapAt(float x, float y) {
    WatchFaceClient.Slot hit = slotAt(x, y);
    if (hit == null) return false;
    return fireComplicationTap(hit.id);
  }

  private WatchFaceClient.Slot slotAt(float x, float y) {
    int px = Math.round(x);
    int py = Math.round(y);
    for (WatchFaceClient.Slot s : activeSlots()) {
      if (s.enabled && s.contains(px, py)) return s;
    }
    return null;
  }

  private boolean fireComplicationTap(int slotId) {
    Source bound = null;
    for (Source s : sources) {
      if (s.slot == slotId) {
        bound = s;
        break;
      }
    }
    boolean fired = false;
    if (bound != null) {
      if (bound.client.isRemote()) {
        fired = bound.client.sendRemoteTap();
      } else if (bound.data != null) {
        Object effective =
            ComplicationParcels.activeTimelineEntry(bound.data, WatchState.currentTimeMillis());
        PendingIntent pi = ComplicationParcels.tapAction(effective);
        if (pi == null) pi = ComplicationParcels.tapAction(bound.data);
        if (pi != null) {
          PendingIntents.send(pi, null, navigator);
          fired = true;
        }
      }
    }
    protocol.event(
        "watchface",
        new Protocol.Json()
            .put("state", "complicationTap")
            .put("slot", slotId)
            .put("tapped", fired)
            .object());
    return fired;
  }

  private JSONObject themeCommand(JSONObject c) throws JSONException {
    if (c.has("enabled")) {
      themeFromWatchface = c.getBoolean("enabled");
      System.setProperty(
          WatchFaceDynamicColors.PROPERTY, Boolean.toString(themeFromWatchface));
      if (themeFromWatchface && lastPalette != null) {
        WatchFaceDynamicColors.apply(app, lastPalette);
      } else if (!themeFromWatchface) {
        WatchFaceDynamicColors.clear(app);
      }
      if (onThemeColorsChanged != null) onThemeColorsChanged.run();
    }
    return describe();
  }

  private JSONObject pushCommand(JSONObject c) throws JSONException {
    Path apkPath = Path.of(c.getString("apk"));
    String token = c.has("validationToken") ? c.optString("validationToken", null) : "control";
    boolean activate = c.optBoolean("activate", true);
    WatchFacePushStore.StoredSlot slot =
        WatchFacePushStore.get().pushFromControl(app, apkPath, token, activate);
    return describe().put("pushedSlot", slot.toJson(activeWatchFacePackage()));
  }

  // ---------------------------------------------------------------------------------------------
  // Frames

  private void requestRender() {
    if (face == null && dwfFace == null) return;
    if (rendering) {
      pendingRender = true;
      return;
    }
    if (face != null && face.error() == null) {
      rendering = true;
      face.render(ambient);
    } else if (dwfFace != null && dwfFace.error() == null) {
      rendering = true;
      dwfFace.render(ambient);
    }
  }

  /** Once a cycle: a new frame each second (each minute in ambient), one in flight at a time. */
  void tick(boolean minuteChanged) {
    refreshDuePeriodicSources(WatchState.currentTimeMillis());
    if (rendering) return;
    if (ambient && !minuteChanged) return;
    boolean clockFrozen = WatchState.current() != null && WatchState.current().isClockFrozen();
    if (face != null && face.error() == null) {
      if ((face.fixedTimeMillis() != null || clockFrozen) && face.frames() > 0) return;
      rendering = true;
      face.render(ambient);
    } else if (dwfFace != null && dwfFace.error() == null) {
      if (clockFrozen && dwfFace.frames() > 0) return;
      rendering = true;
      dwfFace.render(ambient);
    }
  }

  void ambient(boolean value) {
    if (ambient == value) return;
    ambient = value;
    requestRender();
  }

  private void rendered(WatchFaceClient client, Bitmap frame, String failure) {
    if (client != face) return;
    onFrameRendered(displayName(client.component), client.frames(), frame, failure);
  }

  private void renderedDwf(DwfWatchFaceClient client, Bitmap frame, String failure) {
    if (client != dwfFace) return;
    onFrameRendered(client.id(), client.frames(), frame, failure);
  }

  private void onFrameRendered(String componentId, int frameCount, Bitmap frame, String failure) {
    rendering = false;
    if (failure != null) {
      pendingRender = false;
      boolean first = error == null;
      error = failure;
      display.frame(null);
      if (first) {
        protocol.event(
            "watchface",
            new Protocol.Json()
                .put("state", "error")
                .put("component", componentId)
                .put("error", failure)
                .object());
      }
      return;
    }
    boolean first = frameCount == 1;
    error = null;
    display.frame(frame);
    WatchFaceDynamicColors.Palette palette =
        dwfFace != null
            ? dwfFace.extractPalette()
            : (face != null ? face.extractPalette() : null);
    updateDynamicColors(palette);
    if (first) {
      // A complication bound before the face had started could not be checked against its slots.
      for (Source source : sources) {
        if (!source.appFace) continue;
        try {
          checkSlot(source.slot, source.client.type);
        } catch (IllegalArgumentException e) {
          protocol.event(
              "watchface",
              new Protocol.Json()
                  .put("state", "complicationError")
                  .put("slot", source.slot)
                  .put("error", e.getMessage())
                  .object());
        }
      }
      protocol.event(
          "watchface",
          new Protocol.Json()
              .put("state", "set")
              .put("component", componentId)
              .put("width", frame.getWidth())
              .put("height", frame.getHeight())
              .object());
    }
    if (pendingRender) {
      pendingRender = false;
      requestRender();
    }
  }

  private void updateDynamicColors(WatchFaceDynamicColors.Palette palette) {
    if (themeFromWatchface) {
      if (palette != null) {
        WatchFaceDynamicColors.apply(app, palette);
      } else {
        WatchFaceDynamicColors.clear(app);
      }
    } else {
      WatchFaceDynamicColors.clear(app);
    }
    if (!Objects.equals(palette, lastPalette)) {
      lastPalette = palette;
      protocol.event(
          "watchfaceColors",
          new Protocol.Json()
              .put("colors", palette != null ? palette.toJson() : JSONObject.NULL)
              .put("themeApplied", themeFromWatchface && palette != null)
              .object());
      if (themeFromWatchface && onThemeColorsChanged != null) {
        onThemeColorsChanged.run();
      }
    }
  }

  private void showBuiltInComplication() {
    if (hosting()) return;
    StringBuilder text = new StringBuilder();
    for (Source s : sources) {
      if (s.appFace) continue;
      String t = ComplicationSourceClient.text(s.data, app.getResources());
      if (t == null) continue;
      if (text.length() > 0) text.append("  ");
      text.append(t);
    }
    display.builtInComplication(text.length() == 0 ? null : text.toString());
  }

  /** Lets the app's face go, and the complications on it, as a watch does on a face change. */
  private void releaseFace() {
    for (Iterator<Source> it = sources.iterator(); it.hasNext(); ) {
      Source s = it.next();
      if (s.appFace) {
        s.client.release();
        it.remove();
      }
    }
    fakeOnWireFace.clear();
    if (face != null) {
      face.release();
      face = null;
    }
    if (dwfFace != null) {
      dwfFace.release();
      dwfFace = null;
    }
    rendering = false;
    pendingRender = false;
    error = null;
  }

  // ---------------------------------------------------------------------------------------------
  // State

  JSONObject describe() {
    JSONArray complications = new JSONArray();
    for (Source s : sources) complications.put(slotJson(s));
    if (dwfFace != null) {
      for (DwfWatchFaceClient.FakeComplication fake : dwfFace.fakeComplications().values()) {
        if (!hasAppSlotSource(fake.slot)) {
          complications.put(fake.toJson());
        }
      }
    } else if (face != null) {
      for (DwfWatchFaceClient.FakeComplication fake : fakeOnWireFace.values()) {
        if (!hasAppSlotSource(fake.slot)) {
          complications.put(fake.toJson());
        }
      }
    }
    String activeName =
        dwfFace != null
            ? dwfFace.id()
            : (face != null ? displayName(face.component) : "built-in");
    String kind =
        dwfFace != null
            ? "dwf"
            : (face != null
                ? (face.component.getClassName().isEmpty() ? "dwf-runtime" : "androidx")
                : "built-in");
    Protocol.Json json =
        new Protocol.Json()
            .put("watchFace", activeName)
            .put("kind", kind)
            .put("ambient", ambient)
            .put("themeFromWatchface", themeFromWatchface)
            .put("colors", lastPalette != null ? lastPalette.toJson() : JSONObject.NULL)
            .put("pushed", WatchFacePushStore.get().describeSlots())
            .put(
                "state",
                WatchState.current() != null ? WatchState.current().toJson() : JSONObject.NULL)
            .put("complications", complications);
    if (face != null || dwfFace != null) {
      json.put("frames", dwfFace != null ? dwfFace.frames() : face.frames());
      if (face != null) {
        Long fixed = face.fixedTimeMillis();
        if (fixed != null) json.put("timeMillis", fixed);
      }
      JSONArray slots = new JSONArray();
      for (WatchFaceClient.Slot slot : activeSlots()) {
        JSONArray types = new JSONArray();
        for (int t : slot.supportedTypes) types.put(t);
        Protocol.Json slotJson =
            new Protocol.Json()
                .put("id", slot.id)
                .put("enabled", slot.enabled)
                .put("supportedTypes", types);
        JSONObject bounds = slot.boundsJson();
        if (bounds != null) slotJson.put("bounds", bounds);
        slots.put(slotJson.object());
      }
      json.put("slots", slots);
    }
    if (dwfFace != null) {
      json.put("configurations", dwfFace.describeConfigurations());
    } else if (face != null) {
      json.put("configurations", face.describeConfigurations());
    }
    json.put("error", error == null ? JSONObject.NULL : error);
    json.put("available", available());
    return json.object();
  }

  private boolean hasAppSlotSource(int slot) {
    for (Source s : sources) {
      if (s.appFace && s.slot == slot) return true;
    }
    return false;
  }

  private WatchFaceClient.Slot findSlot(int slotId) {
    for (WatchFaceClient.Slot s : activeSlots()) {
      if (s.id == slotId) return s;
    }
    return null;
  }

  private List<WatchFaceClient.Slot> activeSlots() {
    if (dwfFace != null) return dwfFace.slots();
    if (face != null) return face.slots();
    return java.util.Collections.emptyList();
  }

  private String activeFaceLabel() {
    if (dwfFace != null) return dwfFace.id();
    if (face != null) {
      return face.component.getClassName().isEmpty()
          ? face.component.getPackageName()
          : face.component.getShortClassName();
    }
    return "built-in";
  }

  private JSONObject slotJson(Source s) {
    boolean hasTap =
        s.client.isRemote()
            ? s.client.remoteHasTapAction()
            : (ComplicationParcels.tapAction(
                        ComplicationParcels.activeTimelineEntry(s.data, WatchState.currentTimeMillis()))
                    != null
                || ComplicationParcels.tapAction(s.data) != null);
    int period = s.client.updatePeriodSeconds();
    long lastUpdate = s.client.lastUpdateMillis();
    Protocol.Json json =
        new Protocol.Json()
            .put("slot", s.slot)
            .put("source", s.client.component.flattenToShortString())
            .put("face", s.appFace ? "app" : "built-in")
            .put("type", s.client.type)
            .put("updates", s.client.updates())
            .put("updatePeriodSeconds", period)
            .put("lastUpdateMillis", lastUpdate)
            .put("hasTapAction", hasTap)
            .put("text", ComplicationSourceClient.text(s.data, app.getResources()));
    if (period > 0 && lastUpdate > 0) {
      json.put("nextUpdateMillis", lastUpdate + period * 1000L);
    }
    return json.object();
  }

  /** The device's watch faces, installed WFF packages, and complication data sources. */
  private JSONObject available() {
    JSONArray faces = new JSONArray();
    for (String wffPkg : WffWatchFaces.packages()) faces.put(wffPkg);
    for (ComponentName c : services(WALLPAPER_ACTION)) faces.put(c.flattenToShortString());
    for (DwfWatchFaceClient.Entry e : dwfEntries) faces.put(e.id);
    JSONArray dwfArr = new JSONArray();
    for (DwfWatchFaceClient.Entry e : dwfEntries) {
      dwfArr.put(new Protocol.Json().put("id", e.id).put("label", e.label).object());
    }
    JSONArray sourcesArr = new JSONArray();
    for (ComponentName c : servicesAllPackages(ComplicationSourceClient.UPDATE_ACTION)) {
      sourcesArr.put(c.flattenToShortString());
    }
    return new Protocol.Json()
        .put("watchFaces", faces)
        .put("declarativeWatchFaces", dwfArr)
        .put("complicationSources", sourcesArr)
        .put("hostable", WatchFaceClient.hostable(app) || !dwfEntries.isEmpty())
        .object();
  }

  private DwfWatchFaceClient.Entry findDwfEntry(String target) {
    if (target == null || dwfEntries.isEmpty()) return null;
    String trimmed = target.trim();
    for (DwfWatchFaceClient.Entry e : dwfEntries) {
      if (e.id.equals(trimmed) || e.label.equalsIgnoreCase(trimmed)) return e;
      int slash = e.id.lastIndexOf('/');
      if (slash > 0 && e.id.substring(0, slash).equals(trimmed)) return e;
      if (slash >= 0 && e.id.substring(slash + 1).equalsIgnoreCase(trimmed)) return e;
    }
    List<DwfWatchFaceClient.Entry> pushed = pushedDwfEntries.get(trimmed);
    if (pushed != null && !pushed.isEmpty()) {
      return pushed.get(0);
    }
    if (trimmed.equals(app.getPackageName()) && dwfEntries.size() == 1) {
      return dwfEntries.get(0);
    }
    return null;
  }

  private List<ComponentName> services(String action) {
    return servicesInPackages(action, List.of(app.getPackageName()));
  }

  private List<ComponentName> servicesAllPackages(String action) {
    Set<String> pkgs = new LinkedHashSet<>();
    pkgs.add(app.getPackageName());
    pkgs.addAll(DevicePeers.packages());
    return servicesInPackages(action, new ArrayList<>(pkgs));
  }

  private List<ComponentName> servicesInPackages(String action, List<String> packages) {
    List<ComponentName> result = new ArrayList<>();
    for (String pkg : packages) {
      List<ResolveInfo> found =
          app.getPackageManager().queryIntentServices(new Intent(action).setPackage(pkg), 0);
      if (found == null) continue;
      for (ResolveInfo info : found) {
        if (info.serviceInfo != null) {
          ComponentName comp =
              new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name);
          if (!result.contains(comp)) result.add(comp);
        }
      }
    }
    return result;
  }

  private void checkWatchFace(ComponentName component) {
    if (WffWatchFaces.isInstalled(component.getPackageName())) {
      return;
    }
    if (!component.getPackageName().equals(app.getPackageName())) {
      throw new IllegalArgumentException(
          component.getPackageName()
              + " is not installed; the simulator runs only "
              + app.getPackageName());
    }
    if (!services(WALLPAPER_ACTION).contains(component)) {
      List<String> availableNames = names(services(WALLPAPER_ACTION));
      for (DwfWatchFaceClient.Entry e : dwfEntries) availableNames.add(e.id);
      throw new IllegalArgumentException(
          component.flattenToShortString()
              + " is not a watch face (a service with the "
              + WALLPAPER_ACTION
              + " action or a declarative Watch Face Format resource); the app has "
              + availableNames);
    }
  }

  private void checkSource(ComponentName component) {
    List<ComponentName> all = servicesAllPackages(ComplicationSourceClient.UPDATE_ACTION);
    if (!all.contains(component)) {
      throw new IllegalArgumentException(
          component.flattenToShortString()
              + " is not a complication data source; available sources: "
              + names(all));
    }
  }

  private void checkSlot(int slot, int type) {
    for (WatchFaceClient.Slot s : activeSlots()) {
      if (s.id != slot) continue;
      if (!s.supports(type)) {
        throw new IllegalArgumentException(
            "slot " + slot + " of " + activeFaceLabel() + " does not take type " + type);
      }
      return;
    }
    List<Integer> ids = new ArrayList<>();
    for (WatchFaceClient.Slot s : activeSlots()) ids.add(s.id);
    throw new IllegalArgumentException(
        activeFaceLabel() + " has no complication slot " + slot + " (it has " + ids + ")");
  }

  private static String displayName(ComponentName component) {
    return component.getClassName().isEmpty()
        ? component.getPackageName()
        : component.flattenToShortString();
  }

  private static List<String> names(List<ComponentName> components) {
    List<String> names = new ArrayList<>();
    for (ComponentName c : components) names.add(c.flattenToShortString());
    return names;
  }

  /** {@code pkg/.Class}, {@code pkg/pkg.Class}, {@code .Class}, a bare class, or a WFF package. */
  private ComponentName resolve(String component) {
    if (WffWatchFaces.isInstalled(component)) {
      return new ComponentName(component, "");
    }
    if (component.endsWith("/")) {
      String pkgOnly = component.substring(0, component.length() - 1);
      if (WffWatchFaces.isInstalled(pkgOnly)) {
        return new ComponentName(pkgOnly, "");
      }
    }
    String pkg = app.getPackageName();
    if (component.contains("/")) {
      ComponentName c = ComponentName.unflattenFromString(component);
      if (c == null) throw new IllegalArgumentException("not a component: " + component);
      return c;
    }
    // Also match a bare or short class name against installed peer complication sources if not in
    // the host package.
    ComponentName candidate =
        new ComponentName(pkg, component.startsWith(".") ? pkg + component : component);
    if (!services(ComplicationSourceClient.UPDATE_ACTION).contains(candidate)
        && !services(WALLPAPER_ACTION).contains(candidate)) {
      String suffix = component.startsWith(".") ? component : "." + component;
      for (ComponentName peerComp : servicesAllPackages(ComplicationSourceClient.UPDATE_ACTION)) {
        if (peerComp.getClassName().equals(component)
            || peerComp.getClassName().endsWith(suffix)) {
          return peerComp;
        }
      }
    }
    return candidate;
  }

  private static String rootMessage(Throwable e) {
    Throwable cause = e;
    while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
    return cause.getClass().getSimpleName() + ": " + cause.getMessage();
  }
}
