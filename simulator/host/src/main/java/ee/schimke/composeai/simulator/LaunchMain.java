package ee.schimke.composeai.simulator;

import org.robolectric.simulator.SimulatorMain;

/**
 * The launched JVM's entry point: Robolectric's {@link SimulatorMain}, with two things set first.
 *
 * <ul>
 *   <li>With the control channel on, {@code System.out} is pointed at stderr, so file descriptor 1
 *       carries only {@link Protocol} lines however much the app and Robolectric log.
 *   <li>The launcher activity is started through {@code Context.startActivity} rather than
 *       Robolectric's {@code setupActivity}, so it reaches {@link Navigator} like every activity
 *       the app starts later, and the back stack has one owner.
 * </ul>
 *
 * Arguments are {@link SimulatorMain}'s: the APK, then extra classpath jars (the converted
 * classes).
 */
public final class LaunchMain {

  public static void main(String[] args) {
    if ("stdio".equals(System.getProperty(Protocol.CONTROL_PROPERTY))) {
      System.setOut(System.err);
    }
    System.setProperty("robolectric.useContextStartActivity", "true");
    SimulatorMain.main(args);
  }

  private LaunchMain() {}
}
