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
 */
final class DeviceClipboard {

  private static final String EVENT = "clipboard";

  private static volatile ClipboardManager clipboard;

  /** Set while another app's clip is set here, which is not sent on again. */
  private static boolean receiving;

  static void attach(Application app) {
    if (!DeviceBus.enabled()) return;
    ClipboardManager manager = app.getSystemService(ClipboardManager.class);
    if (manager == null) return;
    clipboard = manager;
    manager.addPrimaryClipChangedListener(
        () -> {
          if (receiving) return;
          ClipData clip = manager.getPrimaryClip();
          DeviceBus.broadcast(
              EVENT,
              new Protocol.Json().put("clip", clip == null ? null : Parcels.encode(clip)).object());
        });
    DeviceBus.onBroadcast(EVENT, DeviceClipboard::heard);
  }

  private static void heard(String from, JSONObject body) {
    ClipboardManager manager = clipboard;
    if (manager == null) return;
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
