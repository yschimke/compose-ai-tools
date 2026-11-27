package ee.schimke.composeai.simulator;

import android.content.Intent;
import java.util.TimeZone;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Lightweight extension hooks allowing form-factor modules ({@code :simulator-wear}, {@code
 * :simulator-mobile}, {@code :simulator-wearable}) to plug into {@code :simulator-core} without
 * circular module dependencies.
 */
public final class SimulatorHooks {

  /** System property that enables the Wear or phone launcher shell. */
  public static final String LAUNCHER_PROPERTY = "composeai.simulator.launcher";

  /** Battery/display/timezone observer implemented by Wear's {@code WatchState}. */
  public interface WatchStateObserver {
    void syncBattery(int percent, boolean charging, boolean low, boolean powerSave);

    void syncDisplay(boolean ambient, boolean burnIn, boolean lowBit);

    void notifyTimezoneChanged();
  }

  /** Wear system UI screen power listener implemented by {@code WearSystemUi}. */
  public interface WearScreenListener {
    void screen(ScreenPolicy.State state);
  }

  /** Phone lock screen hook implemented by {@code LockScreen}. */
  public interface LockScreenHost {
    void screenOff();

    boolean hold(Navigator.Entry sleeping);
  }

  private static volatile UnaryOperator<Intent> bindIntentCanonicalizer;
  private static volatile Predicate<Intent> broadcastInterceptor;
  private static volatile LongSupplier clockSource;
  private static volatile WatchStateObserver watchStateObserver;

  private SimulatorHooks() {}

  public static void setBindIntentCanonicalizer(UnaryOperator<Intent> canonicalizer) {
    bindIntentCanonicalizer = canonicalizer;
  }

  public static Intent canonicalBindIntent(Intent intent) {
    UnaryOperator<Intent> fn = bindIntentCanonicalizer;
    return fn != null ? fn.apply(intent) : intent;
  }

  public static void setBroadcastInterceptor(Predicate<Intent> interceptor) {
    broadcastInterceptor = interceptor;
  }

  public static boolean interceptBroadcast(Intent intent) {
    Predicate<Intent> fn = broadcastInterceptor;
    return fn != null && fn.test(intent);
  }

  public static void setClockSource(LongSupplier source) {
    clockSource = source;
  }

  public static long currentTimeMillis() {
    LongSupplier source = clockSource;
    return source != null ? source.getAsLong() : System.currentTimeMillis();
  }

  public static void setWatchStateObserver(WatchStateObserver observer) {
    watchStateObserver = observer;
  }

  public static void syncBattery(int percent, boolean charging, boolean low, boolean powerSave) {
    WatchStateObserver obs = watchStateObserver;
    if (obs != null) obs.syncBattery(percent, charging, low, powerSave);
  }

  public static void syncDisplay(boolean ambient, boolean burnIn, boolean lowBit) {
    WatchStateObserver obs = watchStateObserver;
    if (obs != null) obs.syncDisplay(ambient, burnIn, lowBit);
  }

  public static void notifyTimezoneChanged() {
    WatchStateObserver obs = watchStateObserver;
    if (obs != null) obs.notifyTimezoneChanged();
  }

  /** System-level key handler implemented by {@code Session}. */
  public interface SystemKeyHandler {
    boolean onSystemKey(android.view.KeyEvent event, int displayId);
  }

  private static volatile SystemKeyHandler systemKeyHandler;
  private static volatile SystemKeyHandler wearKeyInterceptor;
  private static volatile Runnable cycleNowHandler;
  private static volatile java.util.function.Function<String, String> shellHandler;
  private static final java.lang.reflect.Method ALLOC_METHOD = findAllocMethod();

  public static void setSystemKeyHandler(SystemKeyHandler handler) {
    systemKeyHandler = handler;
  }

  public static boolean systemKey(android.view.KeyEvent event, int displayId) {
    SystemKeyHandler h = systemKeyHandler;
    return h != null && h.onSystemKey(event, displayId);
  }

  public static void setWearKeyInterceptor(SystemKeyHandler interceptor) {
    wearKeyInterceptor = interceptor;
  }

  public static boolean wearInterceptsKey(android.view.KeyEvent event, int displayId) {
    SystemKeyHandler h = wearKeyInterceptor;
    return h != null && h.onSystemKey(event, displayId);
  }

  public static void setCycleNowHandler(Runnable handler) {
    cycleNowHandler = handler;
  }

  public static void cycleNow() {
    Runnable r = cycleNowHandler;
    if (r != null) r.run();
  }

  public static void setShellHandler(java.util.function.Function<String, String> handler) {
    shellHandler = handler;
  }

  public static String shell(String commandLine) {
    java.util.function.Function<String, String> h = shellHandler;
    return h != null ? h.apply(commandLine) : "";
  }

  private static java.lang.reflect.Method findAllocMethod() {
    try {
      Class<?> cls = Class.forName("com.sun.management.ThreadMXBean");
      return cls.getMethod("getCurrentThreadAllocatedBytes");
    } catch (Throwable ignored) {
      return null;
    }
  }

  public static long currentThreadAllocatedBytes() {
    java.lang.reflect.Method m = ALLOC_METHOD;
    if (m == null) return -1L;
    try {
      Object bean = java.lang.management.ManagementFactory.getThreadMXBean();
      if (!m.getDeclaringClass().isInstance(bean)) return -1L;
      Object result = m.invoke(bean);
      return result instanceof Long ? ((Long) result).longValue() : -1L;
    } catch (Throwable ignored) {
      return -1L;
    }
  }

  public static void simId(android.view.View view, String name) {
    view.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    view.setAccessibilityDelegate(
        new android.view.View.AccessibilityDelegate() {
          @Override
          public void onInitializeAccessibilityNodeInfo(
              android.view.View host, android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setViewIdResourceName("sim:id/" + name);
          }
        });
  }

  public static TimeZone parseTimezone(String id) {
    TimeZone zone = TimeZone.getTimeZone(id);
    if (!zone.getID().equals(id) && !"GMT".equals(id) && !"UTC".equals(id)) {
      throw new IllegalArgumentException("unknown time zone " + id);
    }
    return zone;
  }
}
