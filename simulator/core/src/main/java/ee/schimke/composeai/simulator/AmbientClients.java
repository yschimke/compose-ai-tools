package ee.schimke.composeai.simulator;

import android.app.Activity;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The activities that opted into ambient mode, through either of the system's controllers the
 * simulator provides: the shared library's {@code WearableActivityController} (androidx.wear's
 * {@code AmbientLifecycleObserver}, {@code AmbientModeSupport}, Horologist's {@code AmbientAware})
 * and the Wear SDK's {@code AmbientManager} (Wear Compose's {@code rememberAmbientModeManager}).
 * {@link ScreenPower} asks it whether the top activity stays on screen when the screen dozes, and
 * calls that activity's callbacks.
 *
 * <p>Public only because those controllers live in the libraries' own packages.
 */
public final class AmbientClients {

  /** One activity's controller, as the system sees it. */
  public interface Client {
    Activity activity();

    /** Opted in, and resumed: the system would keep it on screen in ambient. */
    boolean resumed();

    /** Which controller registered it, for {@code screen}'s answer. */
    String api();

    boolean ambient();

    void enter(boolean burnInProtection, boolean lowBitAmbient);

    void update();

    void exit();
  }

  private static final List<Client> clients = new CopyOnWriteArrayList<>();

  private AmbientClients() {}

  public static void register(Client client) {
    if (!clients.contains(client)) clients.add(client);
  }

  public static void unregister(Client client) {
    clients.remove(client);
  }

  /** [activity]'s clients that would enter ambient now; empty when it has not opted in. */
  static List<Client> ready(Activity activity) {
    List<Client> ready = new ArrayList<>();
    if (activity == null || activity.isDestroyed()) return ready;
    for (Client client : clients) {
      if (client.activity() == activity && client.resumed()) ready.add(client);
    }
    return ready;
  }

  /** The clients now in ambient. */
  static List<Client> inAmbient() {
    List<Client> ambient = new ArrayList<>();
    for (Client client : clients) if (client.ambient()) ambient.add(client);
    return ambient;
  }

  static List<Client> all() {
    return new ArrayList<>(clients);
  }
}
