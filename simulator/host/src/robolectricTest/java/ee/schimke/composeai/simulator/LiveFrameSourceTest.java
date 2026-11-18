package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Binder;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.Shadows;
import org.robolectric.annotation.GraphicsMode;

/**
 * {@link LiveFrameSource} in a real sandbox: a wallpaper engine, which has no window and so no
 * surface, draws a frame through {@code onSurfaceRedrawNeeded} into the surface it was given.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LiveFrameSourceTest {

  /** An engine that fills its surface with the colour of its draw count. */
  private static final class FillingService extends WallpaperService {
    final AtomicInteger draws = new AtomicInteger();
    volatile int color = Color.RED;
    volatile boolean visible;

    @Override
    public Engine onCreateEngine() {
      return new Engine() {
        @Override
        public void onVisibilityChanged(boolean v) {
          visible = v;
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
          Canvas canvas = holder.lockHardwareCanvas();
          canvas.drawColor(color);
          holder.unlockCanvasAndPost(canvas);
          draws.incrementAndGet();
        }
      };
    }
  }

  /**
   * {@link LiveFrameSource#draw} off the test's thread, as the host calls it, while the test (the
   * main thread under Robolectric) runs the looper the draw is posted to.
   */
  private static Bitmap draw(LiveFrameSource source) throws Exception {
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      Future<Bitmap> frame = worker.submit(source::draw);
      while (!frame.isDone()) {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        Thread.sleep(5);
      }
      return frame.get();
    } finally {
      worker.shutdownNow();
    }
  }

  @Test
  public void aBinderThatIsNotAnEngineHasNoLiveFrames() {
    assertNull(LiveFrameSource.engineOf(new Binder()));
    assertNull(LiveFrameSource.engineOf(null));
  }

  @Test
  public void anEngineDrawsIntoTheSurfaceItIsGiven() throws Exception {
    FillingService service = new FillingService();
    WallpaperService.Engine engine = service.onCreateEngine();
    LiveFrameSource source = LiveFrameSource.attach(engine, 40, 30);
    assertNotNull(source);
    Bitmap frame = draw(source);
    assertNotNull(frame);
    assertEquals(40, frame.getWidth());
    assertEquals(30, frame.getHeight());
    assertEquals(Color.RED, frame.getPixel(10, 10));
    assertEquals(1, service.draws.get());

    // The next request is the next frame, not the last one again.
    service.color = Color.BLUE;
    assertEquals(Color.BLUE, draw(source).getPixel(10, 10));
    assertEquals(2, service.draws.get());
    source.release();
  }

  @Test
  public void theFaceIsToldItIsVisible() {
    // A face that is not visible keeps its complications empty; an engine with a window is told by
    // the system, this one by the source.
    FillingService service = new FillingService();
    LiveFrameSource source = LiveFrameSource.attach(service.onCreateEngine(), 10, 10);
    assertNotNull(source);
    assertEquals(true, service.visible);
    source.release();
  }

  @Test
  public void mockTimeStandsStillAtThePinnedInstant() {
    Intent intent = WatchFaceClient.mockTime(0f, 1772705310000L);
    assertEquals("androidx.wear.watchface.MockTime", intent.getAction());
    assertEquals(
        0f,
        intent.getFloatExtra("androidx.wear.watchface.extra.MOCK_TIME_SPEED_MULTIPLIER", 1f),
        0f);
    assertEquals(
        1772705310000L,
        intent.getLongExtra("androidx.wear.watchface.extra.MOCK_TIME_WRAPPING_MIN_TIME", -1L));
  }
}
