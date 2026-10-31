package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import org.json.JSONObject;

/**
 * Broadcasts between the device's apps ({@code --app phone:a.apk --app phone:b.apk}), each in a
 * simulator of its own. What an app sends with {@code sendBroadcast} also goes over the {@link
 * DeviceBus} to the device's other apps, marshalled ({@link Parcels}) so its extras keep their
 * types:
 *
 * <ul>
 *   <li>an implicit broadcast reaches every app's receivers registered at run time, and, as since
 *       API 26, none declared in a manifest;
 *   <li>one naming another app (its package or a component) goes to that app alone, to its
 *       manifest receivers too, and not to the sender's;
 *   <li>a receiver permission is checked against the receiving app's granted permissions;
 *   <li>an implicit {@code android.*} action is the system's own, which each app's simulator sends
 *       itself, so it is not passed on.
 * </ul>
 *
 * <p>Each app that gets one reports a {@code broadcast} event naming the sender. Ordered and sticky
 * broadcasts, and {@code RECEIVER_NOT_EXPORTED}, are not simulated across apps.
 */
final class DeviceBroadcasts {

  private static final String EVENT = "intent";

  private static volatile Application app;
  private static volatile Navigator navigator;
  private static volatile Protocol protocol;

  /** Set while a broadcast from another app is delivered here, which is not sent on again. */
  private static boolean receiving;

  static void attach(Application app, Navigator navigator, Protocol protocol) {
    DeviceBroadcasts.app = app;
    DeviceBroadcasts.navigator = navigator;
    DeviceBroadcasts.protocol = protocol;
    DeviceBus.onBroadcast(EVENT, DeviceBroadcasts::heard);
  }

  /**
   * The app's {@code sendBroadcast} of [intent], before its own delivery: sent on to the device's
   * other apps. True when it names another app, which then has it alone, so it is not delivered
   * here.
   */
  static boolean send(Intent intent, String receiverPermission) {
    Application self = app;
    if (self == null || receiving || !DeviceBus.enabled()) return false;
    ComponentName component = intent.getComponent();
    String target = component != null ? component.getPackageName() : intent.getPackage();
    if (target != null && (target.equals(self.getPackageName()) || !DevicePeers.isPeer(target))) {
      return false;
    }
    // The system's own implicit broadcasts (TIME_TICK, CONNECTIVITY_CHANGE, ...), which each app's
    // simulator sends itself: only the system may send a protected android.* action.
    String action = intent.getAction();
    if (target == null && action != null && action.startsWith("android.")) return false;
    DeviceBus.broadcast(
        EVENT,
        new Protocol.Json()
            .put("to", target)
            .put("intent", Parcels.encode(intent))
            .put("permission", receiverPermission)
            .object());
    return target != null;
  }

  private static void heard(String from, JSONObject body) {
    Application self = app;
    String to = Protocol.string(body, "to");
    if (self == null || (to != null && !to.equals(self.getPackageName()))) return;
    String permission = Protocol.string(body, "permission");
    if (permission != null
        && self.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
      return;
    }
    Intent intent = Parcels.decode(Protocol.string(body, "intent"), Intent.CREATOR);
    if (intent == null) return;
    JSONObject delivered;
    receiving = true;
    try {
      if (to != null) {
        // Named this app: its manifest receivers too.
        delivered = navigator.broadcast(intent);
      } else {
        self.sendBroadcast(intent);
        delivered = null;
      }
    } finally {
      receiving = false;
    }
    protocol.event(
        "broadcast",
        new Protocol.Json()
            .put("from", from)
            .put("intent", Intents.toJson(intent))
            .put("delivered", delivered == null ? null : delivered.opt("delivered"))
            .object());
  }

  private DeviceBroadcasts() {}
}
