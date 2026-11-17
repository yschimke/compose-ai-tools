package ee.schimke.composeai.simulator;

import java.nio.charset.StandardCharsets;

/** Minimal reader for the AndroidX health-services request protos the simulator receives. */
final class HealthRequestProto {

  private HealthRequestProto() {}

  /**
   * Reads {@code package_name} (field 1, length-delimited) from a serialized registration request
   * such as {@code PassiveListenerCallbackRegistrationRequest}, or returns null if the bytes do not
   * start with it.
   */
  static String readPackageName(byte[] proto) {
    if (proto == null || proto.length < 2 || proto[0] != 0x0A) return null;
    int len = 0;
    int shift = 0;
    int i = 1;
    while (true) {
      if (i >= proto.length) return null;
      int b = proto[i++] & 0xFF;
      len |= (b & 0x7F) << shift;
      if ((b & 0x80) == 0) break;
      shift += 7;
      if (shift > 28) return null;
    }
    if (len < 0 || len > proto.length - i) return null;
    return new String(proto, i, len, StandardCharsets.UTF_8);
  }
}
