package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.robolectric.internal.bytecode.InstrumentationConfiguration;

public class AppLibraryConfigurationTest {
  private final InstrumentationConfiguration config =
      new SimulatorSandbox.AppLibraryConfiguration(new InstrumentationConfiguration.Builder());

  @Test
  public void acquiresJnaThoughRobolectricNeverAcquiresComSun() {
    InstrumentationConfiguration robolectric = new InstrumentationConfiguration.Builder().build();
    assertFalse(robolectric.shouldAcquire("com.sun.jna.Native"));
    assertTrue(config.shouldAcquire("com.sun.jna.Native"));
    assertTrue(config.shouldAcquire("com.sun.jna.Library$Handler"));
  }

  @Test
  public void leavesTheJdksOwnPackagesToTheParent() {
    assertFalse(config.shouldAcquire("com.sun.net.httpserver.HttpServer"));
    assertFalse(config.shouldAcquire("java.lang.String"));
    assertTrue(config.shouldAcquire("com.example.app.MainActivity"));
  }
}
