package ee.schimke.composeai.simulator;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Whether this device has a connected Data Layer peer right now: the other simulator of {@code
 * launch --pair}, or a peer the agent {@code connect}ed in {@code --data-layer agent}. The system
 * services that report the companion's reachability without Play services (the Wear SDK's {@code
 * RemoteInteractionsManager}) read it here and hear each change. Public and free of Play services
 * types, as those services live in other packages and load whether or not the app has Play
 * services.
 */
public final class PeerPresence {

  private static final Set<String> peers = new LinkedHashSet<>();
  private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

  /** Whether any peer is connected. */
  public static synchronized boolean connected() {
    return !peers.isEmpty();
  }

  /** Runs [listener] (on the thread the change happens on) after each change of {@link #connected}. */
  public static void addListener(Runnable listener) {
    listeners.add(listener);
  }

  public static void removeListener(Runnable listener) {
    listeners.remove(listener);
  }

  static void peerConnected(String nodeId) {
    boolean changed;
    synchronized (PeerPresence.class) {
      boolean before = !peers.isEmpty();
      peers.add(nodeId);
      changed = !before;
    }
    if (changed) notifyListeners();
  }

  static void peerDisconnected(String nodeId) {
    boolean changed;
    synchronized (PeerPresence.class) {
      changed = peers.remove(nodeId) && peers.isEmpty();
    }
    if (changed) notifyListeners();
  }

  private static void notifyListeners() {
    for (Runnable listener : listeners) {
      try {
        listener.run();
      } catch (RuntimeException e) {
        System.err.println("datalayer: a presence listener failed: " + e);
      }
    }
  }

  /** For tests. */
  static synchronized void reset() {
    peers.clear();
    listeners.clear();
  }

  private PeerPresence() {}
}
