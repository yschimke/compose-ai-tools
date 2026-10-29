package ee.schimke.composeai.simulator;

import android.app.UiAutomation;
import android.os.Looper;
import java.time.Duration;
import javax.annotation.Priority;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.simulator.pluginapi.RemoteControl;

/**
 * Robolectric's simulator plugin hook, ranked above its no-op default. The simulator calls {@link
 * #connect} once the launcher activity has been requested and {@link #onCycle} on every turn of its
 * main loop, always on the sandbox's main thread; both hand off to {@link Session}.
 *
 * <p>Always active, control channel or not: without {@link Navigator}'s pump an app could not get
 * past its first screen, since Robolectric records {@code startActivity} instead of acting on it.
 */
@Priority(100)
public final class SimulatorControl implements RemoteControl {

  private Session session;

  public SimulatorControl() {}

  @Override
  public void connect(UiAutomation uiAutomation, Looper looper) {
    session = new Session(RuntimeEnvironment.getApplication(), uiAutomation, Protocol.create());
    session.start();
  }

  @Override
  public void onCycle(long uptimeNanos) {
    if (session != null) session.cycle();
  }

  @Override
  public void disconnect(Duration timeout) {
    session = null;
  }
}
