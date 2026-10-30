package ee.schimke.composeai.simulator;

import java.util.Locale;

/**
 * The screen's power state and what each change means for the app on top, as a watch's system
 * decides it; {@link ScreenPower} carries the decisions out. Plain Java, so it is unit-tested
 * without a sandbox.
 *
 * <ul>
 *   <li>{@code on}: interactive.
 *   <li>{@code ambient}: the display dozes (Wear OS always-on). An app that opted into ambient and
 *       is resumed on top stays resumed and enters ambient; any other app leaves the screen
 *       (stopped) for the watch face.
 *   <li>{@code off}: the display is off; the app on top is stopped, ambient or not.
 * </ul>
 *
 * Sleeping (the timeout, a palm over the screen, {@code KEYCODE_SLEEP}) goes to ambient on a watch
 * with always-on enabled and off otherwise; waking (a wrist tilt, a tap, a button) goes back on and
 * the app that left the screen comes back.
 */
final class ScreenPolicy {

  /** A minute, which is how often an ambient app is told to update. */
  static final long AMBIENT_UPDATE_MS = 60_000;

  enum State {
    ON,
    AMBIENT,
    OFF;

    String id() {
      return name().toLowerCase(Locale.ROOT);
    }

    static State of(String id) {
      switch (id.toLowerCase(Locale.ROOT)) {
        case "on":
        case "interactive":
          return ON;
        case "ambient":
        case "doze":
          return AMBIENT;
        case "off":
          return OFF;
        default:
          throw new IllegalArgumentException("screen state is on, ambient or off, not " + id);
      }
    }
  }

  /** What one change asks of the system. */
  static final class Transition {
    final State from;
    final State to;
    /** {@code ACTION_SCREEN_OFF}: the screen stopped being interactive. */
    boolean screenOff;
    /** {@code ACTION_SCREEN_ON}: it is interactive again. */
    boolean screenOn;
    /** The app's ambient callbacks: {@code onExitAmbient}, then {@code onEnterAmbient}. */
    boolean exitAmbient;
    boolean enterAmbient;
    /** The app on top leaves the screen (paused and stopped), or comes back to it. */
    boolean stopApp;
    boolean restartApp;

    Transition(State from, State to) {
      this.from = from;
      this.to = to;
    }

    boolean changed() {
      return from != to;
    }
  }

  private final boolean wear;
  private State state = State.ON;
  private boolean alwaysOn;
  private long timeoutMs;
  private long lastInput;
  private long nextUpdate;
  /** The app on top is in ambient. */
  private boolean appAmbient;
  /** The app was sent off the screen by a sleep, and a wake brings it back. */
  private boolean appStopped;

  ScreenPolicy(boolean wear, long now) {
    this.wear = wear;
    this.alwaysOn = wear;
    this.lastInput = now;
  }

  State state() {
    return state;
  }

  boolean wear() {
    return wear;
  }

  boolean alwaysOn() {
    return alwaysOn;
  }

  void alwaysOn(boolean value) {
    alwaysOn = value && wear;
  }

  long timeoutMs() {
    return timeoutMs;
  }

  /** How long without input before the screen sleeps; 0 never. */
  void timeoutMs(long value, long now) {
    timeoutMs = Math.max(0, value);
    lastInput = now;
  }

  boolean appAmbient() {
    return appAmbient;
  }

  boolean appStopped() {
    return appStopped;
  }

  /** Where a sleep goes: ambient on an always-on watch, off otherwise. */
  State sleepTarget() {
    return wear && alwaysOn ? State.AMBIENT : State.OFF;
  }

  /** Input arrived: the timeout starts over. */
  void input(long now) {
    lastInput = now;
  }

  /** The screen has been on, untouched, for the whole timeout. */
  boolean timedOut(long now) {
    return state == State.ON && timeoutMs > 0 && now - lastInput >= timeoutMs;
  }

  /** In ambient, a minute has passed since entering or the last update; the next is scheduled. */
  boolean updateDue(long now) {
    if (state != State.AMBIENT || !appAmbient || now < nextUpdate) return false;
    nextUpdate = now + AMBIENT_UPDATE_MS;
    return true;
  }

  /**
   * Moves to [target]. [appOnTop] says the app (not the system UI) is on top of the screen, and
   * [ambientReady] that it opted into ambient and is resumed.
   */
  Transition to(State target, boolean appOnTop, boolean ambientReady, long now) {
    if (target == State.AMBIENT && !wear) {
      throw new IllegalArgumentException("ambient is a Wear OS state; use off");
    }
    Transition t = new Transition(state, target);
    if (target == state) return t;
    t.screenOff = state == State.ON;
    t.screenOn = target == State.ON;
    boolean stayAmbient = target == State.AMBIENT && appOnTop && ambientReady && !appStopped;
    if (appAmbient && !stayAmbient) {
      t.exitAmbient = true;
      appAmbient = false;
    }
    if (target == State.ON) {
      if (appStopped) {
        t.restartApp = true;
        appStopped = false;
      }
    } else if (stayAmbient) {
      if (!appAmbient) {
        t.enterAmbient = true;
        appAmbient = true;
      }
    } else if (appOnTop && !appStopped) {
      t.stopApp = true;
      appStopped = true;
    }
    state = target;
    if (target == State.AMBIENT) nextUpdate = now + AMBIENT_UPDATE_MS;
    if (target == State.ON) lastInput = now;
    return t;
  }

  /** The app left the screen some other way while asleep (it finished, or was force-stopped). */
  void forgetApp() {
    appAmbient = false;
    appStopped = false;
  }
}
