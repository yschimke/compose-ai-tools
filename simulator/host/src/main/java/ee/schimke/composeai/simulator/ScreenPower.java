package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.KeyEvent;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.SensorEventBuilder;
import org.robolectric.shadows.ShadowDisplay;
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.shadows.ShadowSensor;
import org.robolectric.shadows.ShadowSensorManager;

/**
 * The screen's power: on, ambient (a watch's always-on doze) or off, and the gestures that move
 * between them. {@link ScreenPolicy} decides what a change means for the app; this carries it out
 * the way the system does:
 *
 * <ul>
 *   <li>{@code PowerManager.isInteractive()} and the default display's {@code Display.getState()}
 *       ({@code STATE_ON}, {@code STATE_DOZE}, {@code STATE_OFF}) report it, and {@code
 *       ACTION_SCREEN_OFF} / {@code ACTION_SCREEN_ON} are broadcast;
 *   <li>an activity that opted into ambient ({@link AmbientClients}) gets {@code onEnterAmbient}
 *       with the burn-in and low-bit details, {@code onUpdateAmbient} once a minute of simulated
 *       time, and {@code onExitAmbient}; any other app is paused and stopped, for the watch face
 *       with {@code --launcher}, and comes back on wake;
 *   <li>on a watch, a wrist-tilt gesture sensor ({@code Sensor.TYPE_WRIST_TILT_GESTURE}) reports
 *       each {@code tilt} to its listeners, and a tap, swipe, rotary turn or button press on a
 *       sleeping screen wakes it and goes no further, as on a watch.
 * </ul>
 *
 * <p>Commands: {@code screen} (state and settings: {@code state}, {@code timeoutMs}, {@code
 * alwaysOn}, {@code burnIn}, {@code lowBit}), {@code ambient} ({@code value}, {@code op:update}),
 * {@code tilt} / {@code wake}, {@code palm} / {@code sleep}; and {@code key POWER}, {@code SLEEP},
 * {@code WAKEUP}. Runs on the main thread, inside the sandbox.
 */
final class ScreenPower {

  /** {@code Sensor.TYPE_WRIST_TILT_GESTURE}, a system API. */
  static final int TYPE_WRIST_TILT_GESTURE = 26;

  private final Application app;
  private final Navigator navigator;
  private final Protocol protocol;
  /** The Wear system UI ({@code --launcher}), whose watch face an app leaves the screen for. */
  private final WearSystemUi wearUi;
  private final ScreenPolicy policy;
  private boolean burnIn;
  private boolean lowBit;
  private int updates;
  private Sensor wristTilt;
  /** The activity a sleep stopped in place, which a wake restarts. */
  private Navigator.Entry sleeping;
  /** The phone's lock screen ({@code --launcher}), which a sleep locks; or null. */
  private LockScreen lockScreen;

  ScreenPower(Application app, Navigator navigator, Protocol protocol, WearSystemUi wearUi) {
    this.app = app;
    this.navigator = navigator;
    this.protocol = protocol;
    this.wearUi = wearUi;
    this.policy =
        new ScreenPolicy(FormFactor.current() == FormFactor.WEAR, SystemClock.uptimeMillis());
  }

  /** The phone's lock screen: the screen going off locks it, and it holds the app on waking. */
  void lockScreen(LockScreen lockScreen) {
    this.lockScreen = lockScreen;
  }

  /** Sleeps the screen, as {@code sleep}. */
  JSONObject sleep(String reason) {
    return to(policy.sleepTarget(), reason);
  }

  /** Wakes the screen, as {@code wake}. */
  JSONObject wakeUp(String reason) {
    return wake(reason);
  }

  void start() {
    power(ScreenPolicy.State.ON);
    if (!policy.wear()) return;
    wristTilt = ShadowSensor.newInstance(TYPE_WRIST_TILT_GESTURE);
    ShadowSensor sensor = Shadow.extract(wristTilt);
    sensor.setName("Wrist Tilt Gesture (simulator)");
    sensor.setWakeUpFlag(true);
    sensorManager().addSensor(wristTilt);
  }

  /** The control-channel commands this class owns, or null when [name] is not one of them. */
  JSONObject command(String name, JSONObject c) throws JSONException {
    switch (name) {
      case "screen":
        return screen(c);
      case "ambient":
        return ambient(c);
      case "tilt":
        return tilt();
      case "wake":
        return wake("wake");
      case "palm":
      case "sleep":
        return to(policy.sleepTarget(), name);
      default:
        return null;
    }
  }

  static boolean changesUi(String name) {
    switch (name) {
      case "screen":
      case "ambient":
      case "tilt":
      case "wake":
      case "palm":
      case "sleep":
        return true;
      default:
        return false;
    }
  }

  /**
   * Input about to reach the screen ({@code tap}, {@code swipe}, ...): restarts the timeout, and on
   * a sleeping screen wakes it instead, returning what the command answers. Null lets it through.
   */
  JSONObject input(String name) throws JSONException {
    policy.input(SystemClock.uptimeMillis());
    if (policy.state() == ScreenPolicy.State.ON) return null;
    return wake(name).put("consumed", name);
  }

  /** {@code POWER} toggles, {@code SLEEP} and {@code WAKEUP} do what they say; null for others. */
  JSONObject key(int code) throws JSONException {
    switch (code) {
      case KeyEvent.KEYCODE_POWER:
        return policy.state() == ScreenPolicy.State.ON
            ? to(policy.sleepTarget(), "power")
            : wake("power");
      case KeyEvent.KEYCODE_SLEEP:
        return to(policy.sleepTarget(), "sleep");
      case KeyEvent.KEYCODE_WAKEUP:
        return wake("wakeup");
      default:
        return input("key");
    }
  }

  /** One simulator cycle: the timeout, and ambient's minute updates. */
  void cycle() {
    long now = SystemClock.uptimeMillis();
    if (policy.timedOut(now)) to(policy.sleepTarget(), "timeout");
    if (policy.updateDue(now)) updateAmbient();
  }

  /** Whether the default display shows nothing: off, or dozing with no ambient app or face. */
  boolean dark() {
    switch (policy.state()) {
      case OFF:
        return true;
      case AMBIENT:
        return !policy.appAmbient() && wearUi == null;
      default:
        return false;
    }
  }

  String state() {
    return policy.state().id();
  }

  JSONObject describe() {
    JSONArray clients = new JSONArray();
    for (AmbientClients.Client client : AmbientClients.all()) {
      clients.put(
          new Protocol.Json()
              .put("activity", client.activity().getClass().getName())
              .put("api", client.api())
              .put("ready", client.resumed())
              .put("ambient", client.ambient())
              .object());
    }
    PowerManager power = app.getSystemService(PowerManager.class);
    return new Protocol.Json()
        .put("state", policy.state().id())
        .put("interactive", power.isInteractive())
        .put("displayState", displayState(defaultDisplay().getState()))
        .put("alwaysOn", policy.alwaysOn())
        .put("timeoutMs", policy.timeoutMs())
        .put("burnIn", burnIn)
        .put("lowBit", lowBit)
        .put("appAmbient", policy.appAmbient())
        .put("appStopped", policy.appStopped())
        .put("ambientUpdates", updates)
        .put("ambientClients", clients)
        .object();
  }

  // ---------------------------------------------------------------------------------------------

  private JSONObject screen(JSONObject c) throws JSONException {
    long now = SystemClock.uptimeMillis();
    if (c.has("alwaysOn")) policy.alwaysOn(c.getBoolean("alwaysOn"));
    if (c.has("timeoutMs")) policy.timeoutMs(c.getLong("timeoutMs"), now);
    details(c);
    if (c.has("state")) return to(ScreenPolicy.State.of(c.getString("state")), "screen");
    return describe();
  }

  private JSONObject ambient(JSONObject c) throws JSONException {
    if (!policy.wear()) throw new IllegalStateException("ambient mode is a Wear OS state");
    details(c);
    if ("update".equals(c.optString("op"))) {
      if (policy.state() != ScreenPolicy.State.AMBIENT) {
        throw new IllegalStateException("the screen is not in ambient");
      }
      updateAmbient();
      return describe();
    }
    boolean ambient =
        c.has("value")
            ? c.getBoolean("value")
            : policy.state() != ScreenPolicy.State.AMBIENT;
    return ambient ? to(ScreenPolicy.State.AMBIENT, "ambient") : wake("ambient");
  }

  private void details(JSONObject c) throws JSONException {
    if (c.has("burnIn")) burnIn = c.getBoolean("burnIn");
    if (c.has("lowBit")) lowBit = c.getBoolean("lowBit");
  }

  private JSONObject wake(String reason) {
    return to(ScreenPolicy.State.ON, reason);
  }

  /** Delivers one wrist-tilt event; returns how many listeners had registered for it. */
  private int deliverWristTilt() {
    if (wristTilt == null) return 0;
    ShadowSensorManager sensors = sensorManager();
    int listening = 0;
    for (SensorEventListener listener : sensors.getListeners()) {
      if (sensors.hasListener(listener, wristTilt)) listening++;
    }
    SensorEvent event =
        SensorEventBuilder.newBuilder()
            .setSensor(wristTilt)
            .setValues(new float[] {1f})
            .setTimestamp(SystemClock.elapsedRealtimeNanos())
            .build();
    sensors.sendSensorEventToListeners(event, wristTilt);
    return listening;
  }

  private JSONObject to(ScreenPolicy.State target, String reason) {
    List<AmbientClients.Client> ready = ready();
    return apply(
        policy.to(target, appOnTop(), !ready.isEmpty(), SystemClock.uptimeMillis()), ready, reason);
  }

  /** The wrist-tilt gesture: its sensor event, then the wake it causes. */
  private JSONObject tilt() {
    List<AmbientClients.Client> ready = ready();
    return apply(
        policy.tilt(appOnTop(), !ready.isEmpty(), SystemClock.uptimeMillis()), ready, "tilt");
  }

  private boolean appOnTop() {
    Navigator.Entry top = navigator.top();
    return top != null && !top.system;
  }

  /** The top activity's ambient clients that are ready to enter ambient. */
  private List<AmbientClients.Client> ready() {
    return appOnTop()
        ? AmbientClients.ready(navigator.top().activity())
        : java.util.Collections.emptyList();
  }

  private JSONObject apply(
      ScreenPolicy.Transition t, List<AmbientClients.Client> ready, String reason) {
    ScreenPolicy.State target = t.to;
    // Before the screen changes: an ambient app hears the gesture while still in ambient.
    Integer tiltListeners = t.wristTilt ? deliverWristTilt() : null;
    if (!t.changed()) {
      JSONObject state = describe();
      if (tiltListeners != null) put(state, "wristTiltListeners", tiltListeners);
      return state;
    }
    // The display changes first, so the app's callbacks read the state they are about.
    power(target);
    if (t.exitAmbient) {
      for (AmbientClients.Client client : AmbientClients.inAmbient()) client.exit();
    }
    if (t.stopApp) {
      if (wearUi != null) {
        // The watch face comes forward over the app, as when a watch sleeps.
        navigator.goHome();
        sleeping = null;
      } else {
        sleeping = navigator.screenOff();
      }
    }
    if (t.screenOff) navigator.broadcast(new Intent(Intent.ACTION_SCREEN_OFF));
    if (t.screenOff && lockScreen != null) lockScreen.screenOff();
    if (t.enterAmbient) {
      updates = 0;
      for (AmbientClients.Client client : ready) client.enter(burnIn, lowBit);
    }
    if (wearUi != null) wearUi.screen(target);
    if (t.restartApp) {
      if (wearUi != null) navigator.bringAppForward();
      // A locked phone keeps the app stopped behind its lock screen until unlocked.
      else if (lockScreen == null || !lockScreen.hold(sleeping)) navigator.screenOn(sleeping);
      sleeping = null;
    }
    if (t.screenOn) navigator.broadcast(new Intent(Intent.ACTION_SCREEN_ON));
    JSONObject state = describe();
    put(state, "reason", reason);
    put(state, "from", t.from.id());
    protocol.event("screen", state);
    if (tiltListeners != null) put(state, "wristTiltListeners", tiltListeners);
    return state;
  }

  private static void put(JSONObject json, String key, Object value) {
    try {
      json.put(key, value);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  private void updateAmbient() {
    updates++;
    for (AmbientClients.Client client : AmbientClients.inAmbient()) client.update();
  }

  private void power(ScreenPolicy.State state) {
    ShadowPowerManager power = Shadow.extract(app.getSystemService(PowerManager.class));
    power.setIsInteractive(state == ScreenPolicy.State.ON);
    ShadowDisplay display = Shadow.extract(defaultDisplay());
    display.setState(
        state == ScreenPolicy.State.ON
            ? Display.STATE_ON
            : state == ScreenPolicy.State.AMBIENT ? Display.STATE_DOZE : Display.STATE_OFF);
  }

  private static String displayState(int state) {
    switch (state) {
      case Display.STATE_ON:
        return "on";
      case Display.STATE_DOZE:
        return "doze";
      case Display.STATE_DOZE_SUSPEND:
        return "dozeSuspend";
      case Display.STATE_OFF:
        return "off";
      default:
        return String.valueOf(state);
    }
  }

  private Display defaultDisplay() {
    return app.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
  }

  private ShadowSensorManager sensorManager() {
    return Shadow.extract(app.getSystemService(SensorManager.class));
  }
}
