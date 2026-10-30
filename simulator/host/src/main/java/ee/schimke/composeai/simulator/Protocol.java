package ee.schimke.composeai.simulator;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The control channel's output: one JSON object per line on file descriptor 1.
 *
 * <p>{@link LaunchMain} points {@code System.out} at stderr before Robolectric starts, so app and
 * Robolectric logging never interleave with these lines; this class writes to the descriptor
 * directly. Three line shapes:
 *
 * <ul>
 *   <li>{@code {"id":<id>,"ok":true,"result":{...}}} answers a command;
 *   <li>{@code {"id":<id>,"ok":false,"error":"..."}} refuses or fails one;
 *   <li>{@code {"event":"<name>",...}} reports something the app did on its own (ready, activity
 *       and service lifecycle, uncaught exceptions).
 * </ul>
 */
final class Protocol {

  /** {@code stdio} turns the control channel on; anything else leaves only the window. */
  static final String CONTROL_PROPERTY = "composeai.simulator.control";

  private final PrintStream out;

  private Protocol(PrintStream out) {
    this.out = out;
  }

  static Protocol create() {
    if (!"stdio".equals(System.getProperty(CONTROL_PROPERTY))) return new Protocol(null);
    return new Protocol(
        new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
  }

  boolean enabled() {
    return out != null;
  }

  void ok(Object id, JSONObject result) {
    send(new Json().put("id", id).put("ok", true).put("result", result).object());
  }

  void error(Object id, String message) {
    send(new Json().put("id", id).put("ok", false).put("error", message).object());
  }

  void event(String name, JSONObject body) {
    JSONObject line = body == null ? new JSONObject() : body;
    try {
      line.put("event", name);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    send(line);
  }

  private synchronized void send(JSONObject line) {
    if (out != null) out.println(line);
  }

  /** {@link JSONObject} with unchecked puts, since every key here is a non-null literal. */
  static final class Json {
    private final JSONObject object = new JSONObject();

    Json put(String key, Object value) {
      try {
        object.put(key, value == null ? JSONObject.NULL : value);
      } catch (JSONException e) {
        throw new IllegalStateException(e);
      }
      return this;
    }

    JSONObject object() {
      return object;
    }
  }
}
