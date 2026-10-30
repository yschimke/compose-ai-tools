package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.UUID;
import org.junit.Test;

public class GattSubscriptionsTest {

  private static final UUID TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
  private static final UUID OTHER = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");

  @Test
  public void aClientThatNeverSubscribedIsNotNotified() {
    GattSubscriptions s = new GattSubscriptions();
    assertFalse(s.isSubscribed(TX));
  }

  @Test
  public void setCharacteristicNotificationSubscribesAndUnsubscribes() {
    GattSubscriptions s = new GattSubscriptions();
    s.setNotification(TX, true);
    assertTrue(s.isSubscribed(TX));
    assertFalse(s.isSubscribed(OTHER));
    s.setNotification(TX, false);
    assertFalse(s.isSubscribed(TX));
  }

  @Test
  public void aCccdWriteSubscribesForNotificationsOrIndications() {
    GattSubscriptions s = new GattSubscriptions();
    s.descriptorWritten(TX, GattSubscriptions.CCCD, new byte[] {0x01, 0x00});
    assertTrue(s.isSubscribed(TX));
    s.descriptorWritten(TX, GattSubscriptions.CCCD, new byte[] {0x00, 0x00});
    assertFalse(s.isSubscribed(TX));
    s.descriptorWritten(TX, GattSubscriptions.CCCD, new byte[] {0x02, 0x00});
    assertTrue(s.isSubscribed(TX));
  }

  @Test
  public void otherDescriptorWritesDoNotSubscribe() {
    GattSubscriptions s = new GattSubscriptions();
    s.descriptorWritten(
        TX, UUID.fromString("00002901-0000-1000-8000-00805f9b34fb"), new byte[] {1});
    s.descriptorWritten(null, GattSubscriptions.CCCD, new byte[] {1});
    assertFalse(s.isSubscribed(TX));
  }
}
