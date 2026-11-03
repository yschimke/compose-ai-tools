package ee.schimke.composeai.simulator;

/**
 * What the simulator says when an APK calls {@code System.loadLibrary}. The {@code .so} files in an
 * APK are Android ELF binaries linked against bionic, so the host JVM cannot load them (#6342).
 */
final class NativeCode {

  private NativeCode() {}

  /** The {@link UnsatisfiedLinkError} in [error]'s cause chain, or null. */
  static UnsatisfiedLinkError find(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof UnsatisfiedLinkError) return (UnsatisfiedLinkError) current;
      current = current.getCause();
    }
    return null;
  }

  /** One line naming the library and why the simulator cannot load it. */
  static String explain(UnsatisfiedLinkError error) {
    return "compose-preview launch: cannot load Android native code ("
        + error.getMessage()
        + "). The simulator runs on the host JVM and cannot load an APK's bionic libraries (.so).";
  }

  /**
   * Prints [explain] and a control-channel {@code error} event when [error] is (or wraps) an
   * {@link UnsatisfiedLinkError}, then always prints the stack.
   */
  static void report(Throwable error) {
    UnsatisfiedLinkError link = find(error);
    if (link != null) {
      String message = explain(link);
      System.err.println(message);
      Protocol.startupError("native", message);
    }
    error.printStackTrace(System.err);
  }
}
