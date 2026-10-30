package ee.schimke.composeai.simulator;

import java.lang.management.ManagementFactory;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Where a launch spends its time, reported as the {@code ready} event's {@code startup}: the JVM
 * starting, Robolectric's sandbox being built, the application and its providers being created,
 * and the first activity reaching its first frame.
 *
 * <p>The marks are system properties (epoch milliseconds), not fields: this class is loaded both
 * by {@link LaunchMain}'s class loader and by the sandbox's, and both must see one set. The CLI
 * adds {@value #CLI_PROPERTY}, the moment it started the JVM, so the JVM's own start-up counts.
 */
final class Startup {

  static final String PREFIX = "composeai.simulator.t.";
  /** When the CLI started this JVM, set on its command line. */
  static final String CLI_PROPERTY = PREFIX + "launched";

  static final String MAIN = "main";
  static final String SANDBOX = "sandbox";
  static final String PROVIDERS = "providers";
  static final String APPLICATION = "application";
  static final String READY = "ready";

  /** How many files of a predecessor's data {@link AppFiles#restore} put back. */
  static final String RESTORED_PROPERTY = "composeai.simulator.restoredFiles";

  static void mark(String name) {
    System.setProperty(PREFIX + name, Long.toString(System.currentTimeMillis()));
  }

  /**
   * Milliseconds per phase: {@code jvm} (process start to {@code main}), {@code sandbox}, {@code
   * application} (from the sandbox to {@code Application.onCreate()} returning, providers
   * included), {@code firstActivity} (to its first frame), and {@code totalMs} from the CLI's
   * start of this JVM, or the JVM's own start when the CLI did not say.
   */
  static JSONObject describe() {
    long jvm = ManagementFactory.getRuntimeMXBean().getStartTime();
    long launched = time(CLI_PROPERTY, jvm);
    long main = time(MAIN, jvm);
    long sandbox = time(SANDBOX, main);
    long application = time(APPLICATION, sandbox);
    long ready = time(READY, application);
    JSONObject out = new JSONObject();
    try {
      out.put("jvmMs", Math.max(0, main - launched));
      out.put("sandboxMs", Math.max(0, sandbox - main));
      out.put("applicationMs", Math.max(0, application - sandbox));
      out.put("firstActivityMs", Math.max(0, ready - application));
      out.put("totalMs", Math.max(0, ready - launched));
      String restored = System.getProperty(RESTORED_PROPERTY);
      if (restored != null) out.put("restoredFiles", Integer.parseInt(restored));
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  private static long time(String name, long fallback) {
    String value = System.getProperty(name.startsWith(PREFIX) ? name : PREFIX + name);
    if (value == null) return fallback;
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private Startup() {}
}
