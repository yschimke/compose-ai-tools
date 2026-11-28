package ee.schimke.composeai.simulator.shadows;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * {@link SimulatorShadowTextureView} and {@link SimulatorShadowSurface} in a real sandbox: the view
 * gets a texture as it is first drawn, a {@code Surface} made from it locks a canvas and posts
 * frames, and the view draws (and {@code getBitmap} reads) the last one. This is how Rive's Canvas
 * renderer draws a {@code RiveAnimationView}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = {SimulatorShadowTextureView.class, SimulatorShadowSurface.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SimulatorShadowTextureViewTest {

  private Activity activity;
  private FrameLayout content;
  private final List<String> events = new ArrayList<>();

  @Before
  public void setUp() {
    activity = Robolectric.setupActivity(Activity.class);
    content = new FrameLayout(activity);
    content.setBackgroundColor(Color.WHITE);
    activity.setContentView(content);
    idle();
  }

  @Test
  public void theFirstDrawMakesTheTexture() {
    TextureView view = add(200, 100);
    assertTrue(view.isAvailable());
    assertEquals("available 200x100", events.get(0));
    assertTrue(new Surface(view.getSurfaceTexture()).isValid());
  }

  @Test
  public void aCollapsedViewGetsNoTexture() {
    TextureView view = add(200, 0);
    assertFalse(view.isAvailable());
    assertEquals(List.of(), events);
  }

  @Test
  public void lockCanvasPostsAFrameTheViewDraws() {
    TextureView view = add(200, 100);
    Surface surface = new Surface(view.getSurfaceTexture());
    post(surface, Color.RED);

    assertTrue(events.contains("updated"));
    assertEquals(Color.RED, view.getBitmap().getPixel(10, 10));
    assertEquals(Color.RED, drawWindow().getPixel(100, 50));
  }

  @Test
  public void aHardwareCanvasPostsToo() {
    TextureView view = add(200, 100);
    Surface surface = new Surface(view.getSurfaceTexture());
    Canvas canvas = surface.lockHardwareCanvas();
    canvas.drawColor(Color.BLUE);
    surface.unlockCanvasAndPost(canvas);
    idle();

    assertEquals(Color.BLUE, view.getBitmap().getPixel(100, 50));
  }

  @Test
  public void theTransformPlacesTheFrame() {
    TextureView view = add(200, 100);
    // Translucent, so the window shows where the scaled frame does not reach (an opaque one's
    // uncovered pixels are undefined, as on a device).
    view.setOpaque(false);
    Surface surface = new Surface(view.getSurfaceTexture());
    post(surface, Color.GREEN);
    Matrix half = new Matrix();
    half.setScale(0.5f, 1f);
    view.setTransform(half);
    idle();

    Bitmap window = drawWindow();
    assertEquals(Color.GREEN, window.getPixel(50, 50));
    assertEquals(Color.WHITE, window.getPixel(150, 50));
  }

  @Test
  public void getBitmapIgnoresTheTransform() {
    TextureView view = add(200, 100);
    Surface surface = new Surface(view.getSurfaceTexture());
    post(surface, Color.GREEN);
    Matrix half = new Matrix();
    half.setScale(0.5f, 1f);
    view.setTransform(half);

    // The whole frame, as the texture holds it; only drawing is scaled.
    assertEquals(Color.GREEN, view.getBitmap().getPixel(150, 50));
  }

  @Test
  public void aResizeTellsTheListener() {
    TextureView view = add(200, 100);
    events.clear();
    view.setLayoutParams(new FrameLayout.LayoutParams(120, 80));
    idle();
    assertEquals(List.of("changed 120x80"), events);

    Surface surface = new Surface(view.getSurfaceTexture());
    Canvas canvas = surface.lockCanvas(null);
    assertEquals(120, canvas.getWidth());
    assertEquals(80, canvas.getHeight());
    surface.unlockCanvasAndPost(canvas);
  }

  @Test
  public void detachingDestroysTheTexture() {
    TextureView view = add(200, 100);
    SurfaceTexture texture = view.getSurfaceTexture();
    Surface surface = new Surface(texture);
    events.clear();
    content.removeView(view);
    idle();

    assertEquals(List.of("destroyed"), events);
    assertNull(view.getSurfaceTexture());
    assertFalse(surface.isValid());
  }

  @Test
  public void aReleasedSurfaceIsNoLongerValid() {
    TextureView view = add(200, 100);
    Surface surface = new Surface(view.getSurfaceTexture());
    assertTrue(surface.isValid());
    surface.release();
    assertFalse(surface.isValid());
  }

  @Test
  public void getBitmapReplacesAReusedBitmap() {
    TextureView view = add(200, 100);
    Bitmap reused = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888);
    reused.eraseColor(Color.RED);
    Surface surface = new Surface(view.getSurfaceTexture());
    post(surface, Color.TRANSPARENT);

    assertEquals(Color.TRANSPARENT, view.getBitmap(reused).getPixel(10, 10));
  }

  @Test
  public void anOrdinarySurfaceIsUnchanged() {
    // A surface from a texture no view shows is as Robolectric makes it: never valid.
    assertFalse(new Surface(new SurfaceTexture(false)).isValid());
  }

  private TextureView add(int width, int height) {
    TextureView view = new TextureView(activity);
    view.setSurfaceTextureListener(new Recorder());
    content.addView(view, new FrameLayout.LayoutParams(width, height));
    idle();
    // A draw, as the window's next frame makes, which is when a texture is made.
    drawWindow();
    idle();
    assertNotNull(view);
    return view;
  }

  private void post(Surface surface, int color) {
    Canvas canvas = surface.lockCanvas(null);
    canvas.drawColor(color);
    surface.unlockCanvasAndPost(canvas);
    idle();
  }

  /** The window as its views draw it, from the content view's origin (below any action bar). */
  private Bitmap drawWindow() {
    View root = activity.getWindow().getDecorView();
    Bitmap window = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
    int[] at = new int[2];
    content.getLocationInWindow(at);
    Canvas canvas = new Canvas(window);
    canvas.translate(-at[0], -at[1]);
    root.draw(canvas);
    return window;
  }

  private static void idle() {
    shadowOf(Looper.getMainLooper()).idle();
  }

  private final class Recorder implements TextureView.SurfaceTextureListener {
    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
      events.add("available " + width + "x" + height);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
      events.add("changed " + width + "x" + height);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
      events.add("destroyed");
      return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
      events.add("updated");
    }
  }
}
