package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class WearPlatformServicesTest {

  @Test
  public void interactiveWatchFaceConstantsMatchAidl() {
    assertEquals(
        "androidx.wear.watchface.control.IInteractiveWatchFace",
        WatchFaceClient.INTERACTIVE_DESCRIPTOR);
    assertEquals(
        "androidx.wear.watchface.control.IPendingInteractiveWatchFace",
        WatchFaceClient.PENDING_INTERACTIVE_DESCRIPTOR);
    assertEquals(5, WatchFaceClient.TRANSACTION_GET_OR_CREATE_INTERACTIVE);
    assertEquals(
        11, WatchFaceClient.TRANSACTION_INTERACTIVE_RENDER_WATCH_FACE_TO_BITMAP);
  }

  @Test
  public void readsPackageNameFromRegistrationRequestProto() {
    byte[] pkg = "com.example.watchface".getBytes(StandardCharsets.UTF_8);
    byte[] proto = new byte[pkg.length + 4];
    proto[0] = 0x0A; // field 1 (package_name), length-delimited
    proto[1] = (byte) pkg.length;
    System.arraycopy(pkg, 0, proto, 2, pkg.length);
    proto[pkg.length + 2] = 0x12; // field 2 (config) follows
    proto[pkg.length + 3] = 0x00;
    assertEquals("com.example.watchface", HealthRequestProto.readPackageName(proto));
    assertNull(HealthRequestProto.readPackageName(new byte[] {0x12, 0x00}));
    assertNull(HealthRequestProto.readPackageName(new byte[] {0x0A, 0x7F, 0x41}));
    assertNull(HealthRequestProto.readPackageName(null));
  }

  @Test
  public void readsListenerServiceClassAfterSkippingOtherFields() {
    // package_name (1) = "p", a varint field 9 = 300, listener_service_class (2) = "Svc".
    byte[] proto = {0x0A, 0x01, 'p', 0x48, (byte) 0xAC, 0x02, 0x12, 0x03, 'S', 'v', 'c'};
    assertEquals("p", HealthRequestProto.readPackageName(proto));
    assertEquals("Svc", HealthRequestProto.readListenerServiceClass(proto));
    assertNull(HealthRequestProto.readListenerServiceClass(new byte[] {0x0A, 0x01, 'p'}));
  }
}
