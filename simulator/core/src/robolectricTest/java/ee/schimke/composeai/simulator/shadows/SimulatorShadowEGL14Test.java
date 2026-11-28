package ee.schimke.composeai.simulator.shadows;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * {@link SimulatorShadowEGL14}: tagged tokens for everything an app sets up, and {@link
 * SimulatorShadowEGL14#present} drawing a host build's frame into a window surface's TextureView.
 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    shadows = {
      SimulatorShadowEGL14.class,
      SimulatorShadowTextureView.class,
      SimulatorShadowSurface.class,
      SimulatorShadowSurfaceTexture.class
    })
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SimulatorShadowEGL14Test {

  private static boolean tagged(long handle) {
    return (handle & (0xFFFFL << 48)) == SimulatorShadowEGL14.TAG;
  }

  private EGLDisplay display() {
    EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
    int[] version = new int[2];
    assertTrue(EGL14.eglInitialize(display, version, 0, version, 1));
    return display;
  }

  private EGLConfig config(EGLDisplay display) {
    EGLConfig[] configs = new EGLConfig[1];
    int[] count = new int[1];
    assertTrue(
        EGL14.eglChooseConfig(
            display, new int[] {EGL14.EGL_NONE}, 0, configs, 0, 1, count, 0));
    return configs[0];
  }

  @Test
  public void setupHandsOutTaggedTokens() {
    EGLDisplay display = display();
    EGLConfig config = config(display);
    EGLContext context =
        EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE},
            0);
    assertTrue(tagged(display.getNativeHandle()));
    assertTrue(tagged(config.getNativeHandle()));
    assertTrue(tagged(context.getNativeHandle()));
    int[] value = new int[1];
    EGL14.eglQueryContext(display, context, EGL14.EGL_CONTEXT_CLIENT_VERSION, value, 0);
    assertEquals(3, value[0]);
    EGL14.eglGetConfigAttrib(display, config, EGL14.EGL_STENCIL_SIZE, value, 0);
    assertEquals(8, value[0]);
    assertTrue(EGL14.eglDestroyContext(display, context));
    assertTrue(EGL14.eglTerminate(display));
  }

  @Test
  public void aPbufferRemembersItsSize() {
    EGLDisplay display = display();
    EGLSurface pbuffer =
        EGL14.eglCreatePbufferSurface(
            display,
            config(display),
            new int[] {EGL14.EGL_WIDTH, 64, EGL14.EGL_HEIGHT, 32, EGL14.EGL_NONE},
            0);
    int[] value = new int[1];
    EGL14.eglQuerySurface(display, pbuffer, EGL14.EGL_WIDTH, value, 0);
    assertEquals(64, value[0]);
    EGL14.eglQuerySurface(display, pbuffer, EGL14.EGL_HEIGHT, value, 0);
    assertEquals(32, value[0]);
    assertNotEquals(EGL14.EGL_NO_SURFACE, pbuffer);
  }

  @Test
  public void presentDrawsIntoTheWindowsTextureView() throws InterruptedException {
    Activity activity = Robolectric.setupActivity(Activity.class);
    FrameLayout content = new FrameLayout(activity);
    activity.setContentView(content);
    TextureView view = new TextureView(activity);
    content.addView(view, new FrameLayout.LayoutParams(40, 20));
    idle();
    // The window's first draw makes the view's texture, as on a device.
    View root = activity.getWindow().getDecorView();
    root.draw(
        new Canvas(
            Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888)));
    idle();

    EGLDisplay display = display();
    Surface window = new Surface(view.getSurfaceTexture());
    EGLSurface surface =
        EGL14.eglCreateWindowSurface(
            display, config(display), window, new int[] {EGL14.EGL_NONE}, 0);
    assertTrue(tagged(surface.getNativeHandle()));

    // A 40x20 opaque teal frame, as a host build reads it back (RGBA, top row first).
    ByteBuffer rgba = ByteBuffer.allocateDirect(40 * 20 * 4);
    for (int i = 0; i < 40 * 20; i++) {
      rgba.put((byte) 23).put((byte) 64).put((byte) 75).put((byte) 255);
    }
    // From a render thread, as a host build presents.
    boolean[] shown = new boolean[1];
    Thread render =
        new Thread(
            () -> shown[0] = SimulatorShadowEGL14.present(surface.getNativeHandle(), rgba, 40, 20));
    render.start();
    render.join();
    assertTrue(shown[0]);
    idle();

    assertEquals(Color.rgb(23, 64, 75), view.getBitmap().getPixel(20, 10));
    // As the window draws the view (what a screenshot sees).
    Bitmap screen = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
    root.draw(new Canvas(screen));
    int[] at = new int[2];
    view.getLocationInWindow(at);
    assertEquals(Color.rgb(23, 64, 75), screen.getPixel(at[0] + 20, at[1] + 10));
  }

  @Test
  public void destroyedTokensAreHandedToTheHostBuild() {
    EGLDisplay display = display();
    EGLConfig config = config(display);
    int[] es3 = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
    // The first call attaches the host build (the shadow's state outlives a test, so it also
    // drains whatever earlier tests destroyed).
    SimulatorShadowEGL14.takeReleased();

    EGLContext context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, es3, 0);
    EGLSurface pbuffer =
        EGL14.eglCreatePbufferSurface(display, config, new int[] {EGL14.EGL_NONE}, 0);
    assertTrue(EGL14.eglDestroyContext(display, context));
    assertTrue(EGL14.eglDestroySurface(display, pbuffer));
    // Destroying twice releases once.
    EGL14.eglDestroySurface(display, pbuffer);
    long[] released = SimulatorShadowEGL14.takeReleased();
    assertEquals(2, released.length);
    assertEquals(context.getNativeHandle(), released[0]);
    assertEquals(pbuffer.getNativeHandle(), released[1]);
    assertEquals(0, SimulatorShadowEGL14.takeReleased().length);
  }

  @Test
  public void aWindowMadeFromATextureLivesUntilItsSurfaceIsDestroyed() throws InterruptedException {
    Activity activity = Robolectric.setupActivity(Activity.class);
    FrameLayout content = new FrameLayout(activity);
    activity.setContentView(content);
    TextureView view = new TextureView(activity);
    content.addView(view, new FrameLayout.LayoutParams(40, 20));
    idle();
    View root = activity.getWindow().getDecorView();
    root.draw(
        new Canvas(
            Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888)));
    idle();

    EGLDisplay display = display();
    // Given the SurfaceTexture, the shadow makes the Surface; nothing else holds it.
    EGLSurface surface =
        EGL14.eglCreateWindowSurface(
            display, config(display), view.getSurfaceTexture(), new int[] {EGL14.EGL_NONE}, 0);
    System.gc();
    Thread.sleep(50);
    System.gc();
    ByteBuffer rgba = ByteBuffer.allocateDirect(40 * 20 * 4);
    for (int i = 0; i < 40 * 20; i++) {
      rgba.put((byte) 23).put((byte) 64).put((byte) 75).put((byte) 255);
    }
    assertTrue(SimulatorShadowEGL14.present(surface.getNativeHandle(), rgba, 40, 20));
    idle();
    assertEquals(Color.rgb(23, 64, 75), view.getBitmap().getPixel(20, 10));

    assertTrue(EGL14.eglDestroySurface(display, surface));
    assertFalse(SimulatorShadowEGL14.present(surface.getNativeHandle(), rgba, 40, 20));
  }

  @Test
  public void presentRefusesWhatIsNotAWindow() {
    EGLDisplay display = display();
    EGLSurface pbuffer =
        EGL14.eglCreatePbufferSurface(display, config(display), new int[] {EGL14.EGL_NONE}, 0);
    ByteBuffer rgba = ByteBuffer.allocateDirect(4);
    assertFalse(SimulatorShadowEGL14.present(pbuffer.getNativeHandle(), rgba, 1, 1));
    assertFalse(SimulatorShadowEGL14.present(12345L, rgba, 1, 1));
  }

  private static void idle() {
    shadowOf(Looper.getMainLooper()).idle();
  }
}
