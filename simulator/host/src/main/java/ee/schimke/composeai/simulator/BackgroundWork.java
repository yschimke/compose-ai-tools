package ee.schimke.composeai.simulator;

import android.app.Application;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Work the system runs for an app while it is not looking: {@link Alarms}, {@link Jobs}, {@link
 * Downloads}, and the deadline a {@code startForegroundService} sets ({@link
 * Navigator#checkForegroundDeadlines}). {@link Session} hands it commands and cycles; each part
 * runs on its own, so one that fails (an app callback that throws) is reported once as an {@code
 * error} event and holds up neither the others nor the cycle.
 */
final class BackgroundWork {

  private final Protocol protocol;
  private final Navigator navigator;
  private final Alarms alarms;
  private final Jobs jobs;
  private final Downloads downloads;
  private String lastFailure;

  BackgroundWork(Application app, Protocol protocol, Navigator navigator) {
    this.protocol = protocol;
    this.navigator = navigator;
    this.alarms = new Alarms(app, protocol, navigator);
    this.jobs = new Jobs(app, protocol);
    this.downloads = new Downloads(app, protocol, navigator);
  }

  /** The commands this class owns, or null when [name] is not one of them. */
  JSONObject command(String name, JSONObject c) throws JSONException {
    switch (name) {
      case "alarms":
        return alarms.command(c);
      case "jobs":
        return jobs.command(c);
      case "downloads":
        return downloads.command();
      default:
        return null;
    }
  }

  /** {@code alarms} and {@code jobs} can run app code that changes the UI. */
  static boolean changesUi(String name) {
    return name.equals("alarms") || name.equals("jobs");
  }

  void cycle() {
    guard("alarms", alarms::cycle);
    guard("jobs", jobs::cycle);
    guard("downloads", downloads::cycle);
    guard("foregroundService", navigator::checkForegroundDeadlines);
  }

  private void guard(String kind, Runnable step) {
    try {
      step.run();
    } catch (RuntimeException e) {
      String message = kind + ": " + e;
      if (message.equals(lastFailure)) return;
      lastFailure = message;
      StackTraceElement[] frames = e.getStackTrace();
      protocol.event(
          "error",
          new Protocol.Json()
              .put("kind", kind)
              .put("message", String.valueOf(e))
              .put("at", frames.length > 0 ? frames[0].toString() : null)
              .object());
    }
  }
}
