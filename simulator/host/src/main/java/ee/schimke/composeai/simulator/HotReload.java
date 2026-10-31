package ee.schimke.composeai.simulator;

import android.view.Display;
import ee.schimke.composeai.simulator.hotswap.HotSwap;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The {@code hotswap} command: a reload's changed classes redefined in the running app, then its
 * activity re-created from its saved state, as a configuration change re-creates it. The app keeps
 * its process, its data, its {@code ViewModel}s and whatever it saves across a rotation ({@code
 * rememberSaveable}, a saveable navigation back stack); plain {@code remember} state starts over, as
 * it does on a rotation. It skips the new JVM and the cold start a restart pays.
 *
 * <p>The answer's {@code swapped} says whether the edit is on screen. When it is false the caller
 * restarts the app, as a reload did before; {@code redefined} says whether any class had already
 * changed, which only a re-creation that failed leaves behind.
 */
final class HotReload {

  private HotReload() {}

  /** Runs on the main thread, as the activity's lifecycle does. */
  static JSONObject swap(JSONObject command, Navigator navigator) throws Exception {
    long start = System.nanoTime();
    File dir = new File(command.getString("dir"));
    JSONArray names = command.getJSONArray("classes");
    List<String> classes = new ArrayList<>(names.length());
    for (int i = 0; i < names.length(); i++) classes.add(names.getString(i));

    // Re-creation refreshes the top activity of the default display. Anything else alive would
    // keep showing what the old code drew: refuse before changing anything.
    int everywhere = navigator.appActivities(Display.DEFAULT_DISPLAY, true);
    int here = navigator.appActivities(Display.DEFAULT_DISPLAY, false);
    if (everywhere != 1 || here != 1) {
      return refused(
          "the app has " + everywhere + " activities alive; a swap refreshes only one", false, start);
    }

    int redefined;
    try {
      redefined = HotSwap.redefine(dir, classes);
    } catch (HotSwap.Refused e) {
      return refused(e.getMessage(), false, start);
    }
    long redefinedAt = System.nanoTime();
    try {
      navigator.recreate(Display.DEFAULT_DISPLAY);
    } catch (RuntimeException e) {
      return refused("re-creating the activity failed: " + e, redefined > 0, start);
    }
    return new Protocol.Json()
        .put("swapped", true)
        .put("redefined", redefined)
        .put("classes", classes.size())
        .put("redefineMs", (redefinedAt - start) / 1_000_000)
        .put("recreateMs", (System.nanoTime() - redefinedAt) / 1_000_000)
        .object();
  }

  private static JSONObject refused(String reason, boolean redefined, long start) {
    return new Protocol.Json()
        .put("swapped", false)
        .put("redefined", redefined)
        .put("reason", reason)
        .put("ms", (System.nanoTime() - start) / 1_000_000)
        .object();
  }
}
