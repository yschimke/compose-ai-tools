package ee.schimke.composeai.simulator;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAlarmManager;

/**
 * The app's {@code AlarmManager} alarms go off. Robolectric records them and never fires one, so
 * a reminder app's alarm, or a sync scheduled with {@code setExactAndAllowWhileIdle}, never runs.
 * Each cycle the alarms due on their own clock ({@code RTC} on the wall clock, {@code ELAPSED} on
 * elapsed realtime, {@link WorkTiming}) fire as the system fires them: a {@code PendingIntent}
 * through {@link PendingIntents} (a broadcast reaches manifest receivers, an activity the back
 * stack), an {@code OnAlarmListener} on its handler or executor; a repeating alarm is set again
 * for its next interval. Each firing is an {@code alarm} event.
 *
 * <p>Exact alarms are allowed when the manifest asks for {@code SCHEDULE_EXACT_ALARM} or {@code
 * USE_EXACT_ALARM}, as on a device that granted them; the {@code alarms} command lists what is
 * set, fires one now ({@code fire}) and grants or revokes exact alarms ({@code exact}), with the
 * broadcast that goes with it.
 */
final class Alarms {

  private final Application app;
  private final Protocol protocol;
  private final Navigator navigator;
  private final AlarmManager alarms;

  Alarms(Application app, Protocol protocol, Navigator navigator) {
    this.app = app;
    this.protocol = protocol;
    this.navigator = navigator;
    this.alarms = app.getSystemService(AlarmManager.class);
  }

  /** Before {@code Application.onCreate()}: exact alarms as the manifest's permissions grant. */
  static void grantExact(Application app) {
    if (Build.VERSION.SDK_INT < 31) return;
    ShadowAlarmManager.setCanScheduleExactAlarms(requestsExact(app));
  }

  private static boolean requestsExact(Application app) {
    try {
      PackageInfo info =
          app.getPackageManager()
              .getPackageInfo(app.getPackageName(), PackageManager.GET_PERMISSIONS);
      if (info.requestedPermissions == null) return false;
      List<String> requested = Arrays.asList(info.requestedPermissions);
      return requested.contains(Manifest.permission.SCHEDULE_EXACT_ALARM)
          || requested.contains("android.permission.USE_EXACT_ALARM");
    } catch (PackageManager.NameNotFoundException e) {
      return false;
    }
  }

  /** An alarm, with the context's {@code AlarmManager} that holds it. */
  private static final class Held {
    final SimulatorShadowAlarmManager owner;
    final ShadowAlarmManager.ScheduledAlarm alarm;

    Held(SimulatorShadowAlarmManager owner, ShadowAlarmManager.ScheduledAlarm alarm) {
      this.owner = owner;
      this.alarm = alarm;
    }
  }

  /** Every alarm set through any context, soonest first on its own clock. */
  private static List<Held> scheduled() {
    long elapsed = SystemClock.elapsedRealtime();
    long wall = System.currentTimeMillis();
    List<Held> all = new java.util.ArrayList<>();
    for (SimulatorShadowAlarmManager owner : SimulatorShadowAlarmManager.all()) {
      for (ShadowAlarmManager.ScheduledAlarm alarm : owner.getScheduledAlarms()) {
        all.add(new Held(owner, alarm));
      }
    }
    all.sort(
        java.util.Comparator.comparingLong(
            h -> WorkTiming.alarmIn(h.alarm.type, h.alarm.triggerAtTime, elapsed, wall)));
    return all;
  }

  /** One cycle: fires every alarm now due. */
  void cycle() {
    long elapsed = SystemClock.elapsedRealtime();
    long wall = System.currentTimeMillis();
    // Each firing changes the queue; take the soonest due one at a time.
    for (int guard = 0; guard < 100; guard++) {
      Held due = null;
      for (Held held : scheduled()) {
        if (WorkTiming.alarmDue(held.alarm.type, held.alarm.triggerAtTime, elapsed, wall)) {
          due = held;
          break;
        }
      }
      if (due == null) return;
      fire(due, "due");
    }
  }

  /** Takes [held] off the schedule (setting a repeating one again) and delivers it. */
  private JSONObject fire(Held held, String why) {
    ShadowAlarmManager.ScheduledAlarm alarm = held.alarm;
    AlarmManager owner = held.owner.real();
    JSONObject described = describe(alarm, -1);
    if (alarm.operation != null) {
      owner.cancel(alarm.operation);
      if (alarm.interval > 0) {
        long next = alarm.triggerAtTime + alarm.interval;
        long now = now(alarm.type);
        if (next <= now) next = now + alarm.interval;
        owner.setRepeating(alarm.type, next, alarm.interval, alarm.operation);
      }
    } else if (alarm.onAlarmListener != null) {
      owner.cancel(alarm.onAlarmListener);
    }
    Protocol.Json event = new Protocol.Json().put("why", why).put("alarm", described);
    try {
      if (alarm.operation != null) {
        event.put("result", PendingIntents.send(alarm.operation, null, navigator));
      } else if (alarm.onAlarmListener != null) {
        AlarmManager.OnAlarmListener listener = alarm.onAlarmListener;
        if (alarm.executor != null) {
          alarm.executor.execute(listener::onAlarm);
        } else {
          Handler handler =
              alarm.handler != null ? alarm.handler : new Handler(Looper.getMainLooper());
          handler.post(listener::onAlarm);
        }
      }
    } catch (RuntimeException e) {
      event.put("error", String.valueOf(e));
    }
    JSONObject fired = event.object();
    protocol.event("alarm", fired);
    return fired;
  }

  private static long now(int type) {
    return type == WorkTiming.RTC || type == WorkTiming.RTC_WAKEUP
        ? System.currentTimeMillis()
        : SystemClock.elapsedRealtime();
  }

  /** The {@code alarms} command. */
  JSONObject command(JSONObject c) throws JSONException {
    if (c.has("exact")) setExact(c.getBoolean("exact"));
    if (c.has("fire")) {
      List<Held> all = scheduled();
      Object which = c.get("fire");
      int index = "next".equals(which) ? 0 : c.getInt("fire");
      if (index < 0 || index >= all.size()) {
        throw new IllegalArgumentException(
            all.isEmpty() ? "no alarm is set" : "no alarm " + which + " (" + all.size() + " set)");
      }
      return fire(all.get(index), "command");
    }
    JSONArray list = new JSONArray();
    List<Held> all = scheduled();
    for (int i = 0; i < all.size(); i++) list.put(describe(all.get(i).alarm, i));
    return new Protocol.Json()
        .put("alarms", list)
        .put("canScheduleExactAlarms", Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms())
        .object();
  }

  private void setExact(boolean exact) {
    if (Build.VERSION.SDK_INT < 31) return;
    boolean was = alarms.canScheduleExactAlarms();
    ShadowAlarmManager.setCanScheduleExactAlarms(exact);
    if (exact && !was) {
      // Sent to the app alone when the grant arrives, never when it is taken away (the system
      // kills the app instead; here it simply cannot schedule exact alarms from now on).
      navigator.broadcast(
          new Intent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
              .setPackage(app.getPackageName()));
    }
  }

  private JSONObject describe(ShadowAlarmManager.ScheduledAlarm alarm, int index) {
    long in =
        WorkTiming.alarmIn(
            alarm.type, alarm.triggerAtTime, SystemClock.elapsedRealtime(), System.currentTimeMillis());
    Protocol.Json json =
        new Protocol.Json()
            .put("index", index < 0 ? null : index)
            .put("type", typeName(alarm.type))
            .put("triggerAtMs", alarm.triggerAtTime)
            .put("inMs", in)
            .put("intervalMs", alarm.interval > 0 ? alarm.interval : null)
            .put("tag", alarm.getTag());
    PendingIntent operation = alarm.operation;
    if (operation != null) {
      Intent intent = Shadow.<org.robolectric.shadows.ShadowPendingIntent>extract(operation)
          .getSavedIntent();
      json.put("intent", intent == null ? null : Intents.toJson(intent));
    } else {
      json.put("listener", alarm.onAlarmListener == null ? null : alarm.onAlarmListener.getClass().getName());
    }
    return json.object();
  }

  private static String typeName(int type) {
    switch (type) {
      case AlarmManager.RTC_WAKEUP:
        return "RTC_WAKEUP";
      case AlarmManager.RTC:
        return "RTC";
      case AlarmManager.ELAPSED_REALTIME_WAKEUP:
        return "ELAPSED_REALTIME_WAKEUP";
      case AlarmManager.ELAPSED_REALTIME:
        return "ELAPSED_REALTIME";
      default:
        return String.valueOf(type);
    }
  }
}
