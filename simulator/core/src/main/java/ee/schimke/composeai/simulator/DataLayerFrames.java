package ee.schimke.composeai.simulator;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * Reading a field of a data layer frame, where an absent value travels as JSON {@code null}.
 *
 * <p>Frames are built through {@link Protocol.Json}, which writes a {@code null} value as {@code
 * JSONObject.NULL}, and {@code optString(key, null)} answers that with the four characters {@code
 * "null"}, not with the fallback. A {@code PutDataRequest} with no payload (Horologist's {@code
 * /tile_tracking_enabled}) then reached the peer as the Base64 decoding of {@code "null"}: three
 * bytes, {@code 9e e9 65}. Only a JSON string is a value here; anything else is absent. Pure Java
 * over {@code JSONObject.opt}'s result, so it is tested without Android's {@code org.json}.
 */
final class DataLayerFrames {
  private DataLayerFrames() {}

  /**
   * Whether a frame of [type] from the app [framePackage] on another node is this app's
   * ([ownPackage]). Play services delivers messages, data items, channels and the remote activity
   * and authentication requests to the app of the same package on the receiving node; which nodes
   * are connected and what they are capable of is the device's, seen by every app. A frame without
   * a package (one the hub writes, a {@code bye}) is everyone's.
   */
  static boolean forApp(String type, String framePackage, String ownPackage) {
    if (framePackage == null) return true;
    switch (type) {
      case "hello":
      case "bye":
      case "capabilities":
        return true;
      default:
        return framePackage.equals(ownPackage);
    }
  }

  /** [value] when it is a string; null for an absent field or JSON {@code null}. */
  static String text(Object value) {
    return value instanceof String ? (String) value : null;
  }

  /** The Base64 payload [value] carries; null when the sender had none. */
  static byte[] bytes(Object value) {
    String text = text(value);
    return text == null ? null : Base64.getDecoder().decode(text);
  }

  /**
   * Whether a put of [data] and [assets] over an item that holds [oldData] and [oldAssets] changes
   * nothing. Play services syncs a data item, and calls {@code onDataChanged}, only when its
   * contents change (apps add a timestamp to force one); a put of the same bytes and assets is not
   * a change. No payload and an empty one are the same item.
   */
  static boolean unchanged(
      byte[] oldData, Map<String, String> oldAssets, byte[] data, Map<String, String> assets) {
    byte[] before = oldData == null ? new byte[0] : oldData;
    byte[] after = data == null ? new byte[0] : data;
    return Arrays.equals(before, after) && Objects.equals(oldAssets, assets);
  }
}
