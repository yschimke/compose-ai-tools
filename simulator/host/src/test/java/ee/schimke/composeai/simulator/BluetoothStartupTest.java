package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BluetoothStartupTest {

  // BluetoothAdapter.STATE_OFF / STATE_ON / STATE_TURNING_ON.
  private static final int OFF = 10;
  private static final int ON = 12;
  private static final int TURNING_ON = 11;

  @Test
  public void onAtStartupOutsideAirplaneMode() {
    assertEquals(ON, NetworkModel.bluetoothAtStartup(0, false));
    assertEquals(TURNING_ON, NetworkModel.bluetoothAtStartup(TURNING_ON, false));
  }

  @Test
  public void launchingInAirplaneModeLeavesBluetoothOff() {
    NetworkModel m = new NetworkModel();
    m.airplane(true); // what `launch --network cellular,airplane` sets before Bluetooth starts
    assertEquals(OFF, NetworkModel.bluetoothAtStartup(0, m.airplane));
  }

  @Test
  public void offAtStartupInAirplaneMode() {
    assertEquals(OFF, NetworkModel.bluetoothAtStartup(0, true));
    assertEquals(OFF, NetworkModel.bluetoothAtStartup(ON, true));
  }
}
