package ee.schimke.composeai.hostnatives;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import com.google.android.filament.Camera;
import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.Filament;
import com.google.android.filament.Renderer;
import com.google.android.filament.Scene;
import com.google.android.filament.Skybox;
import com.google.android.filament.SwapChain;
import com.google.android.filament.View;
import com.google.android.filament.Viewport;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * The host filament-jni in Robolectric, as an app uses it: {@code Engine.createSwapChain(surface)}
 * on a real {@code Surface} (an {@link ImageReader}'s, as the simulator's SurfaceView gives out),
 * frames rendered with the OpenGL backend on headless EGL, and each frame arriving on the surface
 * the right way up.
 *
 * <p>The frame: the whole target cleared red, then a view over its bottom half (Filament's
 * viewports start bottom-left) filled by a blue skybox. Upright, the image's top half is red and
 * its bottom half blue.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FilamentTest {
  private static final int WIDTH = 64;
  private static final int HEIGHT = 48;

  @Test
  public void rendersOntoASurface() {
    Filament.init();
    Engine engine = new Engine.Builder().backend(Engine.Backend.OPENGL).build();
    assertEquals(Engine.Backend.OPENGL, engine.getBackend());

    ImageReader reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 3);
    AtomicReference<Bitmap> last = new AtomicReference<>();
    // One acquire per posted frame: Robolectric's reader aborts on an acquire with none queued.
    reader.setOnImageAvailableListener(
        r -> {
          try (Image image = r.acquireNextImage()) {
            Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(image.getPlanes()[0].getBuffer());
            last.set(bitmap);
          }
        },
        new Handler(Looper.getMainLooper()));

    SwapChain swapChain = engine.createSwapChain(reader.getSurface());
    Renderer renderer = engine.createRenderer();
    Renderer.ClearOptions clear = new Renderer.ClearOptions();
    clear.clear = true;
    clear.clearColor = new float[] {1, 0, 0, 1};
    renderer.setClearOptions(clear);

    Scene scene = engine.createScene();
    scene.setSkybox(new Skybox.Builder().color(0, 0, 1, 1).build(engine));
    Camera camera = engine.createCamera(EntityManager.get().create());
    View view = engine.createView();
    view.setScene(scene);
    view.setCamera(camera);
    view.setPostProcessingEnabled(false);
    view.setViewport(new Viewport(0, 0, WIDTH, HEIGHT / 2));

    // Frames are read back at the end of each and posted when the engine next pumps its
    // callbacks (the next beginFrame), so draw a few.
    for (int i = 0; i < 3; i++) {
      if (renderer.beginFrame(swapChain, 0)) {
        renderer.render(view);
        renderer.endFrame();
      }
      engine.flushAndWait();
    }
    shadowOf(Looper.getMainLooper()).idle();

    Bitmap frame = last.get();
    assertNotNull("no frame reached the surface", frame);
    int top = frame.getPixel(WIDTH / 2, HEIGHT / 4);
    int bottom = frame.getPixel(WIDTH / 2, HEIGHT * 3 / 4);
    assertTrue("top should be red: " + Integer.toHexString(top), red(top));
    assertTrue("bottom should be blue: " + Integer.toHexString(bottom), blue(bottom));

    engine.destroyView(view);
    engine.destroyScene(scene);
    engine.destroyRenderer(renderer);
    engine.destroySwapChain(swapChain);
    engine.destroy();
  }

  private static boolean red(int color) {
    return Color.red(color) > 200 && Color.green(color) < 60 && Color.blue(color) < 60;
  }

  private static boolean blue(int color) {
    return Color.blue(color) > 200 && Color.red(color) < 60 && Color.green(color) < 60;
  }
}
