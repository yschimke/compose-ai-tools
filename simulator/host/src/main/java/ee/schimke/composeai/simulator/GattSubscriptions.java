package ee.schimke.composeai.simulator;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Which characteristics one GATT client has subscribed to, so a peripheral's notification reaches
 * only the clients that asked for it. A client subscribes with {@code
 * setCharacteristicNotification} or by writing the characteristic's Client Characteristic
 * Configuration descriptor ({@code 2902}) with notifications or indications on, and unsubscribes
 * either way; apps commonly do both, some one. No Android types, for tests.
 */
final class GattSubscriptions {

  static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

  private final Set<UUID> subscribed = new HashSet<>();

  /** {@code setCharacteristicNotification(characteristic, enable)}. */
  void setNotification(UUID characteristic, boolean enable) {
    if (enable) subscribed.add(characteristic);
    else subscribed.remove(characteristic);
  }

  /** A descriptor write: a CCCD value with bit 0 (notify) or 1 (indicate) set subscribes. */
  void descriptorWritten(UUID characteristic, UUID descriptor, byte[] value) {
    if (characteristic == null || !CCCD.equals(descriptor)) return;
    boolean on = value != null && value.length > 0 && (value[0] & 0x03) != 0;
    setNotification(characteristic, on);
  }

  boolean isSubscribed(UUID characteristic) {
    return subscribed.contains(characteristic);
  }
}
