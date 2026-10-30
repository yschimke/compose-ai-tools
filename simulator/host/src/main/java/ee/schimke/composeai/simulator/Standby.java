package ee.schimke.composeai.simulator;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A simulator started ahead of need: the CLI keeps one waiting so that {@code restart} and {@code
 * reload} skip what every simulator JVM pays before the app's own code runs — the JVM, Robolectric's
 * sandbox, and the framework's setup of the application's resources, display and configuration.
 *
 * <p>With {@value #PROPERTY} set, the launch stops at the first point that needs the app: {@code
 * ApkAppLoader}'s manifest is asked for the app's {@code AppComponentFactory}, immediately before
 * the application is created through it. It sends {@code {"event":"standby"}} and waits for one
 * line on stdin, {@code {"go":{<system properties>}}}: the data to restore, the moment the CLI
 * asked. No class of the app's has been loaded by then, so the CLI can still fill the overlay
 * directory the class path names. End of input instead of a line ends the JVM.
 *
 * <p>The line is read a byte at a time: the control channel's reader takes over stdin afterwards,
 * and must not find a buffer here holding its first command.
 */
final class Standby {

  static final String PROPERTY = "composeai.simulator.standby";
  private static volatile boolean released;

  /** Waits for the CLI's go, once, when this simulator was started as a standby. */
  static void awaitGo() {
    if (released || !Boolean.getBoolean(PROPERTY)) return;
    synchronized (Standby.class) {
      if (released) return;
      Startup.mark("standby");
      PrintStream out =
          new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
      out.println("{\"event\":\"standby\"}");
      String line = readLine(System.in);
      if (line == null) System.exit(0);
      try {
        JSONObject go = new JSONObject(line).getJSONObject("go");
        for (Iterator<String> keys = go.keys(); keys.hasNext(); ) {
          String key = keys.next();
          System.setProperty(key, go.getString(key));
        }
      } catch (JSONException e) {
        System.err.println("compose-preview: not a standby go line: " + line);
        System.exit(2);
      }
      Startup.mark(Startup.GO);
      released = true;
    }
  }

  private static String readLine(InputStream in) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try {
      int b;
      while ((b = in.read()) >= 0) {
        if (b == '\n') return bytes.toString(StandardCharsets.UTF_8);
        bytes.write(b);
      }
    } catch (IOException e) {
      return null;
    }
    return null;
  }

  private Standby() {}
}
