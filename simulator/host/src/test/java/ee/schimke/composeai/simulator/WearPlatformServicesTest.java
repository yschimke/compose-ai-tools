package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;

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
}
