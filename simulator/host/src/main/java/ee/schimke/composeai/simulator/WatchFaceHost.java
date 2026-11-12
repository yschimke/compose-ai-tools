package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.util.DisplayMetrics;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The watch's watch face and complication host: which face is set, and which complication data
 * sources feed which slots. With nothing set the system UI shows its built-in face ({@link
 * WatchFaceView}); {@code set} hosts one of the app's androidx watch faces through {@link
 * WatchFaceClient} or one of its declarative (Watch Face Format) watch faces through {@link
 * DwfWatchFaceClient}; {@code complication} binds one of the app's data sources through {@link
 * ComplicationSourceClient} and gives its data to the face's slot, or, when the face is not one of
 * the app's, draws it on the built-in face.
 *
 * <p>This is the {@code watchface} command, and what Android Studio's watch face and complication
 * run configurations ({@code DEBUG_SURFACE set-watchface} / {@code set-complication}) become.
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

  private final Application app;
  private final Navigator navigator;
  private final Protocol protocol;
  private final Display display;
  private final List<DwfWatchFaceClient.Entry> dwfEntries;
  private final List<Source> sources = new ArrayList<>();
  private WatchFaceClient face;
  private DwfWatchFaceClient dwfFace;
  private boolean ambient;
  private boolean rendering;
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
    this.app = app;
    this.navigator = navigator;
    this.protocol = protocol;
    this.display = display;
    this.dwfEntries = DwfWatchFaceClient.availableFromProperty();
  }

  /** Whether one of the app's faces is set (rather than the built-in face). */
  boolean hosting() {
    return face != null || dwfFace != null;
  }

  /**
   * Sets the {@code --watchface} launch target if given, or defaults to the first declarative watch
   * face when the APK has no launcher activity.
   */
  void setFromLaunch() {
    String requested = System.getProperty(DwfWatchFaceClient.WATCHFACE_PROPERTY);
    if (requested != null && !requested.trim().isEmpty()) {
      try {
        setByName(requested.trim());
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
  // Commands

  /**
   * {@code {"cmd":"watchface", "op": "set" | "unset" | "config" | "complication" |
   * "uncomplication" | "status"}}.
   */
  JSONObject command(JSONObject c) throws JSONException {
    String op = c.optString("op", "status");
    switch (op) {
      case "set":
        {
          String target =
              c.has("component") ? c.getString("component") : c.getString("watchFaceId");
          return setByName(target);
        }
      case "config":
        return configureDwf(c);
      case "unset":
        return unset();
      case "complication":
        return complication(
            resolve(c.getString("component")),
            c.has("watchface") ? c.getString("watchface") : null,
            c.optInt("slot", -1),
            c.getInt("type"));
      case "uncomplication":
        return uncomplication(c.has("component") ? resolve(c.getString("component")) : null);
      case "status":
        return describe();
      default:
        throw new IllegalArgumentException(
            "watchface op "
                + op
                + ": use set, unset, config, complication, uncomplication or status");
    }
  }

  JSONObject setByName(String target) {
    DwfWatchFaceClient.Entry dwf = findDwfEntry(target);
    if (dwf != null) {
      return setDwf(dwf);
    }
    return set(resolve(target));
  }

  /** Sets one of the app's declarative (Watch Face Format) watch faces. */
  JSONObject setDwf(DwfWatchFaceClient.Entry entry) {
    if (dwfFace != null && dwfFace.id().equals(entry.id)) return describe();
    releaseFace();
    dwfFace = new DwfWatchFaceClient(app, entry, this::renderedDwf);
    DisplayMetrics metrics = app.getResources().getDisplayMetrics();
    rendering = true;
    display.builtInComplication(null);
    dwfFace.start(metrics.widthPixels, metrics.heightPixels, ambient);
    return describe();
  }

  /** Sets one of the app's androidx watch faces, replacing any other. */
  JSONObject set(ComponentName component) {
    checkWatchFace(component);
    if (face != null && face.component.equals(component)) return describe();
    releaseFace();
    face = new WatchFaceClient(app, navigator, component, this::rendered);
    DisplayMetrics metrics = app.getResources().getDisplayMetrics();
    rendering = true;
    face.start(metrics.widthPixels, metrics.heightPixels, ambient);
    // The built-in face is hidden behind the app's while it is set.
    display.builtInComplication(null);
    return describe();
  }

  private JSONObject configureDwf(JSONObject c) throws JSONException {
    if (dwfFace == null) {
      throw new IllegalStateException(
          "watchface op config requires an active declarative (Watch Face Format) watch face");
    }
    JSONObject values = c.optJSONObject("values");
    if (values != null) {
      for (Iterator<String> it = values.keys(); it.hasNext(); ) {
        String k = it.next();
        dwfFace.setConfig(k, String.valueOf(values.get(k)));
      }
    } else {
      dwfFace.setConfig(c.getString("id"), String.valueOf(c.get("value")));
    }
    rendering = true;
    dwfFace.render(ambient);
    return describe();
  }

  /** Back to the built-in face; complication sources stay bound and draw on it. */
  JSONObject unset() {
    releaseFace();
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
      if (dwf != null) {
        appFace = true;
        setDwf(dwf);
      } else {
        ComponentName wfComp = resolve(watchFace);
        if (wfComp.getPackageName().equals(app.getPackageName())) {
          appFace = true;
          set(wfComp);
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
      face.complication(bound.slot, data, ambient);
    } else if (bound.appFace && dwfFace != null) {
      dwfFace.complication(bound.slot, bound.client.type, data, ambient);
    } else {
      showBuiltInComplication();
    }
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
    showBuiltInComplication();
    return describe();
  }

  // ---------------------------------------------------------------------------------------------
  // Frames

  /** Once a cycle: a new frame each second (each minute in ambient), one in flight at a time. */
  void tick(boolean minuteChanged) {
    if (rendering) return;
    if (ambient && !minuteChanged) return;
    if (face != null && face.error() == null) {
      rendering = true;
      face.render(ambient);
    } else if (dwfFace != null && dwfFace.error() == null) {
      rendering = true;
      dwfFace.render(ambient);
    }
  }

  void ambient(boolean value) {
    if (ambient == value) return;
    ambient = value;
    if (face != null) {
      rendering = true;
      face.render(ambient);
    } else if (dwfFace != null) {
      rendering = true;
      dwfFace.render(ambient);
    }
  }

  private void rendered(WatchFaceClient client, Bitmap frame, String failure) {
    if (client != face) return;
    onFrameRendered(client.component.flattenToShortString(), client.frames(), frame, failure);
  }

  private void renderedDwf(DwfWatchFaceClient client, Bitmap frame, String failure) {
    if (client != dwfFace) return;
    onFrameRendered(client.id(), client.frames(), frame, failure);
  }

  private void onFrameRendered(String componentId, int frameCount, Bitmap frame, String failure) {
    rendering = false;
    if (failure != null) {
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
    if (face != null) {
      face.release();
      face = null;
    }
    if (dwfFace != null) {
      dwfFace.release();
      dwfFace = null;
    }
    rendering = false;
    error = null;
  }

  // ---------------------------------------------------------------------------------------------
  // State

  JSONObject describe() {
    JSONArray complications = new JSONArray();
    for (Source s : sources) complications.put(slotJson(s));
    String activeName =
        dwfFace != null
            ? dwfFace.id()
            : (face != null ? face.component.flattenToShortString() : "built-in");
    String kind = dwfFace != null ? "dwf" : (face != null ? "androidx" : "built-in");
    Protocol.Json json =
        new Protocol.Json()
            .put("watchFace", activeName)
            .put("kind", kind)
            .put("ambient", ambient)
            .put("complications", complications);
    if (face != null || dwfFace != null) {
      json.put("frames", dwfFace != null ? dwfFace.frames() : face.frames());
      JSONArray slots = new JSONArray();
      for (WatchFaceClient.Slot slot : activeSlots()) {
        JSONArray types = new JSONArray();
        for (int t : slot.supportedTypes) types.put(t);
        slots.put(
            new Protocol.Json()
                .put("id", slot.id)
                .put("enabled", slot.enabled)
                .put("supportedTypes", types)
                .object());
      }
      json.put("slots", slots);
    }
    if (dwfFace != null) {
      json.put("configurations", dwfFace.describeConfigurations());
    }
    json.put("error", error == null ? JSONObject.NULL : error);
    json.put("available", available());
    return json.object();
  }

  private List<WatchFaceClient.Slot> activeSlots() {
    if (dwfFace != null) return dwfFace.slots();
    if (face != null) return face.slots();
    return java.util.Collections.emptyList();
  }

  private String activeFaceLabel() {
    if (dwfFace != null) return dwfFace.id();
    if (face != null) return face.component.getShortClassName();
    return "built-in";
  }

  private JSONObject slotJson(Source s) {
    return new Protocol.Json()
        .put("slot", s.slot)
        .put("source", s.client.component.flattenToShortString())
        .put("face", s.appFace ? "app" : "built-in")
        .put("type", s.client.type)
        .put("updates", s.client.updates())
        .put("text", ComplicationSourceClient.text(s.data, app.getResources()))
        .object();
  }

  /** The app's watch faces and complication data sources. */
  private JSONObject available() {
    JSONArray faces = new JSONArray();
    for (ComponentName c : services(WALLPAPER_ACTION)) faces.put(c.flattenToShortString());
    for (DwfWatchFaceClient.Entry e : dwfEntries) faces.put(e.id);
    JSONArray dwfArr = new JSONArray();
    for (DwfWatchFaceClient.Entry e : dwfEntries) {
      dwfArr.put(new Protocol.Json().put("id", e.id).put("label", e.label).object());
    }
    JSONArray sources = new JSONArray();
    for (ComponentName c : services(ComplicationSourceClient.UPDATE_ACTION)) {
      sources.put(c.flattenToShortString());
    }
    return new Protocol.Json()
        .put("watchFaces", faces)
        .put("declarativeWatchFaces", dwfArr)
        .put("complicationSources", sources)
        .put("hostable", WatchFaceClient.hostable(app) || !dwfEntries.isEmpty())
        .object();
  }

  private DwfWatchFaceClient.Entry findDwfEntry(String target) {
    if (target == null || dwfEntries.isEmpty()) return null;
    String trimmed = target.trim();
    for (DwfWatchFaceClient.Entry e : dwfEntries) {
      if (e.id.equals(trimmed) || e.label.equalsIgnoreCase(trimmed)) return e;
      int slash = e.id.lastIndexOf('/');
      if (slash >= 0 && e.id.substring(slash + 1).equalsIgnoreCase(trimmed)) return e;
    }
    if (trimmed.equals(app.getPackageName()) && dwfEntries.size() == 1) {
      return dwfEntries.get(0);
    }
    return null;
  }

  private List<ComponentName> services(String action) {
    List<ComponentName> result = new ArrayList<>();
    List<ResolveInfo> found =
        app.getPackageManager()
            .queryIntentServices(new Intent(action).setPackage(app.getPackageName()), 0);
    if (found == null) return result;
    for (ResolveInfo info : found) {
      result.add(new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name));
    }
    return result;
  }

  private void checkWatchFace(ComponentName component) {
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
    if (!services(ComplicationSourceClient.UPDATE_ACTION).contains(component)) {
      throw new IllegalArgumentException(
          component.flattenToShortString()
              + " is not a complication data source; the app has "
              + names(services(ComplicationSourceClient.UPDATE_ACTION)));
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

  private static List<String> names(List<ComponentName> components) {
    List<String> names = new ArrayList<>();
    for (ComponentName c : components) names.add(c.getClassName());
    return names;
  }

  /** {@code pkg/.Class}, {@code pkg/pkg.Class}, {@code .Class} or a bare class name. */
  private ComponentName resolve(String component) {
    String pkg = app.getPackageName();
    if (component.contains("/")) {
      ComponentName c = ComponentName.unflattenFromString(component);
      if (c == null) throw new IllegalArgumentException("not a component: " + component);
      return c;
    }
    return new ComponentName(pkg, component.startsWith(".") ? pkg + component : component);
  }

  private static String rootMessage(Throwable e) {
    Throwable cause = e;
    while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
    return cause.getClass().getSimpleName() + ": " + cause.getMessage();
  }
}
