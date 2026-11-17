package ee.schimke.composeai.simulator;

import java.nio.charset.StandardCharsets;

/** Minimal reader for the AndroidX health-services request protos the simulator receives. */
final class HealthRequestProto {

  private static final int WIRE_VARINT = 0;
  private static final int WIRE_FIXED64 = 1;
  private static final int WIRE_LENGTH_DELIMITED = 2;
  private static final int WIRE_FIXED32 = 5;

  private HealthRequestProto() {}

  /**
   * Reads {@code package_name} (field 1) from a serialized registration request: both {@code
   * PassiveListenerCallbackRegistrationRequest} and {@code
   * PassiveListenerServiceRegistrationRequest} carry it there.
   */
  static String readPackageName(byte[] proto) {
    return readString(proto, 1);
  }

  /**
   * Reads {@code listener_service_class} (field 2) from a {@code
   * PassiveListenerServiceRegistrationRequest}.
   */
  static String readListenerServiceClass(byte[] proto) {
    return readString(proto, 2);
  }

  /**
   * Returns the first length-delimited [field] in [proto] decoded as UTF-8, or null if it is absent
   * or the bytes are malformed.
   */
  static String readString(byte[] proto, int field) {
    if (proto == null) return null;
    int[] pos = {0};
    while (pos[0] < proto.length) {
      long key = readVarint(proto, pos);
      if (key < 0) return null;
      int wireType = (int) (key & 0x7);
      int number = (int) (key >>> 3);
      switch (wireType) {
        case WIRE_VARINT:
          if (readVarint(proto, pos) < 0) return null;
          break;
        case WIRE_FIXED64:
          pos[0] += 8;
          break;
        case WIRE_FIXED32:
          pos[0] += 4;
          break;
        case WIRE_LENGTH_DELIMITED:
          {
            long len = readVarint(proto, pos);
            if (len < 0 || len > proto.length - pos[0]) return null;
            if (number == field) {
              return new String(proto, pos[0], (int) len, StandardCharsets.UTF_8);
            }
            pos[0] += (int) len;
            break;
          }
        default:
          return null;
      }
      if (pos[0] > proto.length) return null;
    }
    return null;
  }

  /** Reads a varint at {@code pos[0]}, advancing it; returns -1 if truncated or too long. */
  private static long readVarint(byte[] proto, int[] pos) {
    long value = 0;
    for (int shift = 0; shift < 64; shift += 7) {
      if (pos[0] >= proto.length) return -1;
      int b = proto[pos[0]++] & 0xFF;
      value |= (long) (b & 0x7F) << shift;
      if ((b & 0x80) == 0) return value;
    }
    return -1;
  }
}
