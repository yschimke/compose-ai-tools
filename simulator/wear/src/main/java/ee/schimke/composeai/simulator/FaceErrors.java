package ee.schimke.composeai.simulator;

import java.lang.reflect.InvocationTargetException;

/** One-line descriptions of why a hosted watch face failed, naming the stage that failed. */
final class FaceErrors {
  private static final int MAX_CAUSES = 4;

  private FaceErrors() {}

  /**
   * {@code "<stage>: Root: message"}, then each wrapping exception that adds a message and, last,
   * the innermost frame outside the JDK and the framework, which in a runtime failure names the
   * element or class that threw. [stage] says what the host was doing (starting, rendering); null
   * leaves it out.
   */
  static String describe(String stage, Throwable t) {
    Throwable root = unwrap(t);
    StringBuilder out = new StringBuilder();
    if (stage != null) out.append(stage).append(": ");
    out.append(root.getClass().getSimpleName()).append(": ").append(root.getMessage());
    int added = 0;
    for (Throwable w = t; w != null && w != root && added < MAX_CAUSES; w = w.getCause()) {
      if (w instanceof InvocationTargetException) continue;
      if (w.getMessage() == null) continue;
      out.append(" (wrapped by ")
          .append(w.getClass().getSimpleName())
          .append(": ")
          .append(w.getMessage())
          .append(')');
      added++;
    }
    StackTraceElement frame = firstAppFrame(root);
    if (frame != null) out.append(" [at ").append(frame).append(']');
    return out.toString();
  }

  /** The cause that is not a reflection wrapper, as the clients reported it before. */
  static Throwable unwrap(Throwable t) {
    Throwable cause = t;
    while (cause instanceof InvocationTargetException && cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }

  private static StackTraceElement firstAppFrame(Throwable t) {
    for (StackTraceElement e : t.getStackTrace()) {
      String c = e.getClassName();
      if (c.startsWith("java.")
          || c.startsWith("javax.")
          || c.startsWith("jdk.")
          || c.startsWith("sun.")
          || c.startsWith("android.")
          || c.startsWith("com.android.")
          || c.startsWith("org.robolectric.")
          || c.startsWith("ee.schimke.composeai.simulator.")) {
        continue;
      }
      return e;
    }
    return null;
  }
}
