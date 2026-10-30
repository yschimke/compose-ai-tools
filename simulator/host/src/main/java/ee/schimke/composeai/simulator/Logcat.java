package ee.schimke.composeai.simulator;

import android.os.Process;
import android.util.Log;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.robolectric.shadows.ShadowLog;

/**
 * {@code logcat} over Robolectric's {@link ShadowLog}, which keeps every {@code android.util.Log}
 * call the app makes. Dumped as {@code threadtime} lines, cleared, or followed: while following,
 * each new entry that passes the filter is sent as a {@code log} event.
 */
final class Logcat {

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS");

  /** Dumps are capped; {@code tail} picks fewer. */
  private static final int MAX_DUMP = 5000;

  private final Protocol protocol;
  /** Entries already seen by the follower. */
  private int cursor;

  private Filter following;
  private long lastPollNanos;

  Logcat(Protocol protocol) {
    this.protocol = protocol;
    // Robolectric's simulator resets capture (off) and the time stamp after startup.
    install();
  }

  /** Stamps each entry with the time it was logged; runs before the application's onCreate. */
  static void install() {
    ShadowLog.setCaptureLogsEnabled(true);
    ShadowLog.setTimeSupplier(() -> LocalDateTime.now().format(TIME));
  }

  JSONObject command(JSONObject c) throws JSONException {
    String op = c.optString("op", "dump");
    Filter filter = new Filter(c);
    switch (op) {
      case "dump":
        return dump(filter, c.optInt("tail", MAX_DUMP));
      case "clear":
        // ShadowLog.clear() is its reset: capture off and the time stamp gone, so both again.
        ShadowLog.clear();
        install();
        cursor = 0;
        return new Protocol.Json().put("cleared", true).object();
      case "follow":
        following = filter;
        cursor = ShadowLog.getLogs().size();
        if (c.has("tail")) cursor = Math.max(0, cursor - c.getInt("tail"));
        return new Protocol.Json()
            .put("following", true)
            .put("note", "entries arrive as log events until logcat op stop")
            .object();
      case "stop":
        boolean was = following != null;
        following = null;
        return new Protocol.Json().put("following", false).put("wasFollowing", was).object();
      default:
        throw new IllegalArgumentException("logcat op is dump, clear, follow or stop, not " + op);
    }
  }

  private JSONObject dump(Filter filter, int tail) {
    List<ShadowLog.LogItem> logs = ShadowLog.getLogs();
    JSONArray lines = new JSONArray();
    JSONArray entries = new JSONArray();
    int matched = 0;
    // Newest last, as logcat prints; count first so `tail` takes the last N that match.
    for (ShadowLog.LogItem item : logs) if (filter.matches(item)) matched++;
    int skip = Math.max(0, matched - Math.min(tail, MAX_DUMP));
    int index = 0;
    for (ShadowLog.LogItem item : logs) {
      if (!filter.matches(item)) continue;
      if (index++ >= skip) {
        lines.put(format(item));
        entries.put(entry(item));
      }
    }
    return new Protocol.Json()
        .put("lines", lines)
        .put("entries", entries)
        .put("matched", matched)
        .put("truncated", skip > 0)
        .object();
  }

  /** Called every cycle: sends what was logged since, at most every 100 ms of real time. */
  void poll() {
    if (following == null) return;
    long now = System.nanoTime();
    if (now - lastPollNanos < 100_000_000L) return;
    lastPollNanos = now;
    List<ShadowLog.LogItem> logs;
    try {
      logs = ShadowLog.getLogs();
    } catch (RuntimeException e) {
      following = null;
      protocol.event("error", new Protocol.Json().put("message", "logcat: " + e).object());
      return;
    }
    // Cleared since (by a `logcat -c`, or the app's own tests helpers): start over.
    if (logs.size() < cursor) cursor = 0;
    for (int i = cursor; i < logs.size(); i++) {
      ShadowLog.LogItem item = logs.get(i);
      if (!following.matches(item)) continue;
      protocol.event("log", entry(item));
    }
    cursor = logs.size();
  }

  /** One entry's fields, for a reader that formats it itself (the virtual device's logcat). */
  private static JSONObject entry(ShadowLog.LogItem item) {
    return new Protocol.Json()
        .put("time", item.timeString)
        .put("level", String.valueOf(level(item.type)))
        .put("tag", item.tag)
        .put("message", message(item))
        .put("pid", PID)
        .object();
  }

  /**
   * The pid entries carry: Robolectric's {@code Process.myPid()} is 0, which reads as the kernel;
   * the simulator JVM's own is the app's process here.
   */
  private static final int PID =
      Process.myPid() != 0 ? Process.myPid() : (int) ProcessHandle.current().pid();

  static String format(ShadowLog.LogItem item) {
    int pid = PID;
    return String.format(
        Locale.ROOT,
        "%s %5d %5d %c %s: %s",
        item.timeString == null ? "--" : item.timeString,
        pid,
        pid,
        level(item.type),
        item.tag,
        message(item));
  }

  private static String message(ShadowLog.LogItem item) {
    if (item.throwable == null) return item.msg;
    return item.msg + "\n" + Log.getStackTraceString(item.throwable).trim();
  }

  static char level(int priority) {
    switch (priority) {
      case Log.VERBOSE:
        return 'V';
      case Log.DEBUG:
        return 'D';
      case Log.INFO:
        return 'I';
      case Log.WARN:
        return 'W';
      case Log.ERROR:
        return 'E';
      case Log.ASSERT:
        return 'F';
      default:
        return '?';
    }
  }

  /** logcat's priority order; S (silent) is above everything. */
  static int priority(String level) {
    switch (level.toUpperCase(Locale.ROOT)) {
      case "V":
        return Log.VERBOSE;
      case "D":
        return Log.DEBUG;
      case "I":
        return Log.INFO;
      case "W":
        return Log.WARN;
      case "E":
        return Log.ERROR;
      case "F":
      case "A":
        return Log.ASSERT;
      case "S":
        return Integer.MAX_VALUE;
      default:
        throw new IllegalArgumentException("unknown log level " + level);
    }
  }

  /** Tag filter specs ({@code filters} tag → level, {@code default}) and a message regex. */
  private static final class Filter {
    final Map<String, Integer> byTag = new HashMap<>();
    final int fallback;
    final Pattern regex;

    Filter(JSONObject c) throws JSONException {
      JSONObject filters = c.optJSONObject("filters");
      if (filters != null) {
        for (Iterator<String> keys = filters.keys(); keys.hasNext(); ) {
          String tag = keys.next();
          byTag.put(tag, priority(filters.getString(tag)));
        }
      }
      fallback = priority(c.optString("default", c.optString("level", "V")));
      regex = c.has("regex") ? Pattern.compile(c.getString("regex")) : null;
    }

    boolean matches(ShadowLog.LogItem item) {
      Integer min = byTag.get(item.tag);
      if (item.type < (min != null ? min : fallback)) return false;
      return regex == null || (item.msg != null && regex.matcher(item.msg).find());
    }
  }
}
