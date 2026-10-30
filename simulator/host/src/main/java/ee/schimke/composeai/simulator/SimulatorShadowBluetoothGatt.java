package ee.schimke.composeai.simulator;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.os.Build;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowBluetoothGatt;

/**
 * Robolectric's {@code BluetoothGatt} shadow, reporting what the app does to a peripheral —
 * discovering services, reading, writing, subscribing — as {@code gatt} events, so the agent
 * playing the peripheral can answer ({@code bluetooth notify}). Robolectric's own behavior is kept:
 * discovery finds the services {@link BluetoothSim} declared, a read answers with the
 * characteristic's value, a write is acknowledged. Added to the shadow map by {@link
 * SimulatorSandbox}.
 */
@Implements(BluetoothGatt.class)
public class SimulatorShadowBluetoothGatt extends ShadowBluetoothGatt {

  @RealObject private BluetoothGatt gatt;

  @Implementation(minSdk = Build.VERSION_CODES.O)
  @Override
  protected boolean discoverServices() {
    BluetoothSim.gattEvent(gatt, "discoverServices", null, null);
    return super.discoverServices();
  }

  @Implementation(minSdk = Build.VERSION_CODES.O)
  @Override
  protected boolean requestMtu(int mtu) {
    BluetoothSim.gattEvent(gatt, "requestMtu " + mtu, null, null);
    return super.requestMtu(mtu);
  }

  @Implementation(minSdk = Build.VERSION_CODES.O)
  @Override
  protected boolean readCharacteristic(BluetoothGattCharacteristic characteristic) {
    BluetoothSim.gattEvent(gatt, "read", characteristic.getUuid(), null);
    return super.readCharacteristic(characteristic);
  }

  @Implementation(minSdk = Build.VERSION_CODES.O)
  @Override
  protected boolean writeCharacteristic(BluetoothGattCharacteristic characteristic) {
    BluetoothSim.gattEvent(gatt, "write", characteristic.getUuid(), characteristic.getValue());
    return super.writeCharacteristic(characteristic);
  }

  @Implementation(minSdk = Build.VERSION_CODES.TIRAMISU)
  @Override
  protected int writeCharacteristic(
      BluetoothGattCharacteristic characteristic, byte[] value, int writeType) {
    BluetoothSim.gattEvent(gatt, "write", characteristic.getUuid(), value);
    return super.writeCharacteristic(characteristic, value, writeType);
  }

  @Implementation(minSdk = Build.VERSION_CODES.O)
  @Override
  protected boolean writeDescriptor(BluetoothGattDescriptor descriptor) {
    BluetoothSim.gattEvent(
        gatt, "writeDescriptor", descriptorTarget(descriptor), descriptor.getValue());
    return super.writeDescriptor(descriptor);
  }

  @Implementation(minSdk = Build.VERSION_CODES.TIRAMISU)
  @Override
  protected int writeDescriptor(BluetoothGattDescriptor descriptor, byte[] value) {
    BluetoothSim.gattEvent(gatt, "writeDescriptor", descriptorTarget(descriptor), value);
    return super.writeDescriptor(descriptor, value);
  }

  @Implementation(minSdk = Build.VERSION_CODES.O)
  @Override
  protected boolean setCharacteristicNotification(
      BluetoothGattCharacteristic characteristic, boolean enable) {
    BluetoothSim.gattEvent(
        gatt, enable ? "subscribe" : "unsubscribe", characteristic.getUuid(), null);
    return super.setCharacteristicNotification(characteristic, enable);
  }

  /** A CCCD write names the characteristic it subscribes to. */
  private static Object descriptorTarget(BluetoothGattDescriptor descriptor) {
    BluetoothGattCharacteristic ch = descriptor.getCharacteristic();
    return ch == null ? descriptor.getUuid() : ch.getUuid() + "/" + descriptor.getUuid();
  }
}
