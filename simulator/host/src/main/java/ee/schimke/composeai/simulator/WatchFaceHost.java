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
 * WatchFaceClient}; {@code complication} binds one of the app's data sources through {@link
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
  private final List<Source> sources = new ArrayList<>();
  private WatchFaceClient face;
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
  }

  /** Whether one of the app's faces is set (rather than the built-in face). */
  boolean hosting() {
    return face != null;
  }

  // ---------------------------------------------------------------------------------------------
  // Commands

  /**
   * {@code {"cmd":"watchface", "op": "set" | "unset" | "complication" | "uncomplication" |
   * "status"}}.
   */
  JSONObject command(JSONObject c) throws JSONException {
    String op = c.optString("op", "status");
    switch (op) {
      case "set":
        return set(resolve(c.getString("component")));
      case "unset":
        return unset();
      case "complication":
        return complication(
            resolve(c.getString("component")),
            c.has("watchface") ? resolve(c.getString("watchface")) : null,
            c.optInt("slot", -1),
            c.getInt("type"));
      case "uncomplication":
        return uncomplication(c.has("component") ? resolve(c.getString("component")) : null);
      case "status":
        return describe();
      default:
        throw new IllegalArgumentException(
            "watchface op " + op + ": use set, unset, complication, uncomplication or status");
    }
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
  JSONObject complication(ComponentName source, ComponentName watchFace, int slot, int type)
      throws JSONException {
    checkSource(source);
    boolean appFace =
        watchFace != null && watchFace.getPackageName().equals(app.getPackageName());
    if (appFace) set(watchFace);
    if (appFace && face != null && !face.slots().isEmpty()) checkSlot(slot, type);
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
          "could not bind complication data source " + source.flattenToShortString() + ": "
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
      }
    }
    showBuiltInComplication();
    return describe();
  }

  // ---------------------------------------------------------------------------------------------
  // Frames

  /** Once a cycle: a new frame each second (each minute in ambient), one in flight at a time. */
  void tick(boolean minuteChanged) {
    if (face == null || rendering || face.error() != null) return;
    if (ambient && !minuteChanged) return;
    rendering = true;
    face.render(ambient);
  }

  void ambient(boolean value) {
    if (ambient == value) return;
    ambient = value;
    if (face != null) {
      rendering = true;
      face.render(ambient);
    }
  }

  private void rendered(WatchFaceClient client, Bitmap frame, String failure) {
    if (client != face) return;
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
                .put("component", client.component.flattenToShortString())
                .put("error", failure)
                .object());
      }
      return;
    }
    boolean first = client.frames() == 1;
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
              .put("component", client.component.flattenToShortString())
              .put("width", frame.getWidth())
              .put("height", frame.getHeight())
              .object());
    }
  }

  private void showBuiltInComplication() {
    if (face != null) return;
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
    if (face == null) return;
    face.release();
    face = null;
    rendering = false;
    error = null;
  }

  // ---------------------------------------------------------------------------------------------
  // State

  JSONObject describe() {
    JSONArray complications = new JSONArray();
    for (Source s : sources) complications.put(slotJson(s));
    Protocol.Json json =
        new Protocol.Json()
            .put("watchFace", face == null ? "built-in" : face.component.flattenToShortString())
            .put("ambient", ambient)
            .put("complications", complications);
    if (face != null) {
      json.put("frames", face.frames());
      JSONArray slots = new JSONArray();
      for (WatchFaceClient.Slot slot : face.slots()) {
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
    json.put("error", error == null ? JSONObject.NULL : error);
    json.put("available", available());
    return json.object();
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
    JSONArray sources = new JSONArray();
    for (ComponentName c : services(ComplicationSourceClient.UPDATE_ACTION)) {
      sources.put(c.flattenToShortString());
    }
    return new Protocol.Json()
        .put("watchFaces", faces)
        .put("complicationSources", sources)
        .put("hostable", WatchFaceClient.hostable(app))
        .object();
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
      throw new IllegalArgumentException(
          component.flattenToShortString()
              + " is not a watch face (a service with the "
              + WALLPAPER_ACTION
              + " action); the app has "
              + names(services(WALLPAPER_ACTION)));
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
    for (WatchFaceClient.Slot s : face.slots()) {
      if (s.id != slot) continue;
      if (!s.supports(type)) {
        throw new IllegalArgumentException(
            "slot " + slot + " of " + face.component.getShortClassName() + " does not take type "
                + type);
      }
      return;
    }
    List<Integer> ids = new ArrayList<>();
    for (WatchFaceClient.Slot s : face.slots()) ids.add(s.id);
    throw new IllegalArgumentException(
        face.component.getShortClassName() + " has no complication slot " + slot + " (it has "
            + ids + ")");
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
