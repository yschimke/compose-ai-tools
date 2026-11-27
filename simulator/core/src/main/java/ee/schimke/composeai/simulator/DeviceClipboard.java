package ee.schimke.composeai.simulator;

import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import org.json.JSONObject;

/**
 * The clipboard of the device's apps ({@code --app phone:a.apk --app phone:b.apk}), each in a
 * simulator of its own: one clipboard for the device, as on a device. A primary clip one app sets
 * (or a command to it sets) goes over the {@link DeviceBus} to the others, marshalled ({@link
 * Parcels}), and their {@code OnPrimaryClipChangedListener}s hear of it; clearing it clears theirs.
 *
 * <p>Each clip is kept with the time it was set, on the clock all the device's simulators share,
 * and the latest wins. A clip an app set while starting, before it joined the bus, goes out when it
 * joins, and the apps already there answer with theirs, so an app that joins later catches up.
 */
final class DeviceClipboard {

  private static final String EVENT = "clipboard";
  private static final String SYNC = "clipboardSync";

  private static volatile ClipboardManager clipboard;

  /** Set while another app's clip is set here, which is not sent on again. */
  private static boolean receiving;

  /** When the clip here was last set, here or by another app; 0 for never. */
  private static volatile long clipTime;

  static void attach(Application app) {
    if (!DeviceBus.enabled()) return;
    ClipboardManager manager = app.getSystemService(ClipboardManager.class);
    if (manager == null) return;
    clipboard = manager;
    // Set while the app started, before this listened: as of now.
    if (manager.hasPrimaryClip()) clipTime = System.currentTimeMillis();
    manager.addPrimaryClipChangedListener(
        () -> {
          if (receiving) return;
          clipTime = System.currentTimeMillis();
          DeviceBus.broadcast(EVENT, current(manager).object());
        });
    DeviceBus.onBroadcast(EVENT, (from, body) -> heard(body));
    DeviceBus.onBroadcast(
        SYNC,
        (from, body) -> {
          heard(body);
          // A newcomer asks; the apps already there answer once with theirs.
          if (body.optBoolean("ask") && clipTime > 0) {
            DeviceBus.broadcast(SYNC, current(manager).put("ask", false).object());
          }
        });
  }

  /** Once this app is on the bus: its clip, if it set one, goes out, and the others answer. */
  static void joined() {
    ClipboardManager manager = clipboard;
    if (manager == null) return;
    Protocol.Json json = clipTime > 0 ? current(manager) : new Protocol.Json().put("time", 0L);
    DeviceBus.broadcast(SYNC, json.put("ask", true).object());
  }

  private static Protocol.Json current(ClipboardManager manager) {
    ClipData clip = manager.getPrimaryClip();
    return new Protocol.Json()
        .put("clip", clip == null ? null : Parcels.encode(clip))
        .put("time", clipTime);
  }

  private static void heard(JSONObject body) {
    ClipboardManager manager = clipboard;
    long time = body.optLong("time");
    if (manager == null || time <= clipTime) return;
    clipTime = time;
    ClipData clip = Parcels.decode(Protocol.string(body, "clip"), ClipData.CREATOR);
    receiving = true;
    try {
      if (clip != null) manager.setPrimaryClip(clip);
      else manager.clearPrimaryClip();
    } finally {
      receiving = false;
    }
  }

  private DeviceClipboard() {}
}
