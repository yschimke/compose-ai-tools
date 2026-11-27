package ee.schimke.composeai.simulator.shadows;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.Window;
import ee.schimke.composeai.simulator.MainThread;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.robolectric.annotation.ClassName;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowPixelCopy;

/**
 * Robolectric's {@code PixelCopy}, from any thread. Its copy reads the window's surface, which it
 * asserts happens on the main thread, as it does in a unit test where the test thread is the main
 * one. An instrumentation test asks from its own thread: Compose's {@code captureToImage()} and
 * screenshot libraries call {@code PixelCopy.request} there and wait for the callback. Here the
 * copy runs on the main thread, the asker's thread waiting, as a device's compositor does the copy
 * while the app keeps running; the callback still arrives on the handler (or executor) asked for.
 * Added to the shadow map by name, by {@code SimulatorSandbox}.
 */
@Implements(value = PixelCopy.class, isInAndroidSdk = false)
public class SimulatorShadowPixelCopy extends ShadowPixelCopy {

  @Implementation
  protected static void request(
      SurfaceView source,
      Bitmap dest,
      PixelCopy.OnPixelCopyFinishedListener listener,
      Handler listenerThread) {
    onMain(() -> ShadowPixelCopy.request(source, dest, listener, listenerThread));
  }

  @Implementation
  protected static void request(
      SurfaceView source,
      Rect srcRect,
      Bitmap dest,
      PixelCopy.OnPixelCopyFinishedListener listener,
      Handler listenerThread) {
    onMain(() -> ShadowPixelCopy.request(source, srcRect, dest, listener, listenerThread));
  }

  @Implementation
  protected static void request(
      Window source,
      Bitmap dest,
      PixelCopy.OnPixelCopyFinishedListener listener,
      Handler listenerThread) {
    onMain(() -> ShadowPixelCopy.request(source, dest, listener, listenerThread));
  }

  @Implementation
  protected static void request(
      Window source,
      Rect srcRect,
      Bitmap dest,
      PixelCopy.OnPixelCopyFinishedListener listener,
      Handler listenerThread) {
    onMain(() -> ShadowPixelCopy.request(source, srcRect, dest, listener, listenerThread));
  }

  @Implementation
  protected static void request(
      Surface source,
      Rect srcRect,
      Bitmap dest,
      PixelCopy.OnPixelCopyFinishedListener listener,
      Handler listenerThread) {
    onMain(() -> ShadowPixelCopy.request(source, srcRect, dest, listener, listenerThread));
  }

  @Implementation(minSdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  protected static void request(
      @ClassName("android.view.PixelCopy$Request") Object requestObject,
      Executor callbackExecutor,
      Consumer<?> listener) {
    onMain(() -> ShadowPixelCopy.request(requestObject, callbackExecutor, listener));
  }

  private interface Copy {
    void run();
  }

  private static void onMain(Copy copy) {
    MainThread.get(
        () -> {
          copy.run();
          return null;
        });
  }
}
