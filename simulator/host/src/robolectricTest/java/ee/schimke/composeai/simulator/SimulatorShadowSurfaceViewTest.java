package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
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
 * {@link SimulatorShadowSurfaceView} in a real sandbox: the holder's callbacks follow the view,
 * what the app draws becomes a frame, the view punches its hole, and {@link
 * SimulatorShadowSurfaceView#drawFrames} puts each frame where its view is.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = SimulatorShadowSurfaceView.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SimulatorShadowSurfaceViewTest {

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
  public void callbacksFollowTheView() {
    SurfaceView view = add(200, 100);
    assertEquals(List.of("created valid=true", "changed 200x100"), events);

    events.clear();
    view.setVisibility(View.GONE);
    idle();
    assertEquals(List.of("destroyed"), events);
    assertFalse(view.getHolder().getSurface().isValid());

    events.clear();
    view.setVisibility(View.VISIBLE);
    idle();
    assertEquals(List.of("created valid=true", "changed 200x100"), events);

    events.clear();
    content.removeView(view);
    idle();
    assertEquals(List.of("destroyed"), events);
  }

  @Test
  public void aResizeReplacesTheSurface() {
    SurfaceView view = add(200, 100);
    events.clear();
    view.setLayoutParams(new FrameLayout.LayoutParams(120, 80));
    idle();
    assertEquals(List.of("destroyed", "created valid=true", "changed 120x80"), events);

    events.clear();
    view.getHolder().setFixedSize(64, 32);
    idle();
    assertEquals(List.of("destroyed", "created valid=true", "changed 64x32"), events);
  }

  @Test
  public void aCollapsedViewGetsNoSurface() {
    SurfaceView view = add(200, 0);
    assertEquals(List.of(), events);
    assertNull(view.getHolder().lockCanvas());
  }

  @Test
  public void lockCanvasPostsAFrame() {
    SurfaceView view = add(200, 100);
    SurfaceHolder holder = view.getHolder();
    Canvas canvas = holder.lockCanvas();
    assertNotNull(canvas);
    canvas.drawColor(Color.RED);
    holder.unlockCanvasAndPost(canvas);
    idle();

    Bitmap frame = onlyFrame(false);
    assertEquals(200, frame.getWidth());
    assertEquals(100, frame.getHeight());
    assertEquals(Color.RED, frame.getPixel(10, 10));
  }

  @Test
  public void aHardwareCanvasOnTheSurfacePostsAFrame() {
    SurfaceView view = add(200, 100);
    Canvas canvas = view.getHolder().getSurface().lockHardwareCanvas();
    canvas.drawColor(Color.BLUE);
    view.getHolder().getSurface().unlockCanvasAndPost(canvas);
    idle();

    assertEquals(Color.BLUE, onlyFrame(false).getPixel(100, 50));
  }

  @Test
  public void setZOrderOnTopPutsTheFrameOverTheWindow() {
    SurfaceView view = new SurfaceView(activity);
    view.setZOrderOnTop(true);
    add(view, 200, 100);
    post(view, Color.GREEN);

    onlyFrame(true);
  }

  @Test
  public void theViewPunchesAHoleUnlessOnTop() {
    SurfaceView behind = add(200, 100);
    post(behind, Color.RED);
    Bitmap window = drawWindow();
    assertEquals(Color.TRANSPARENT, window.getPixel(100, 50));
    assertEquals(Color.WHITE, window.getPixel(100, 150));
    assertEquals(Color.WHITE, window.getPixel(250, 50));

    content.removeView(behind);
    SurfaceView onTop = new SurfaceView(activity);
    onTop.setZOrderOnTop(true);
    add(onTop, 200, 100);
    post(onTop, Color.RED);
    assertEquals(Color.WHITE, drawWindow().getPixel(100, 50));
  }

  @Test
  public void drawFramesPlacesTheFrameOverItsView() {
    SurfaceView view = add(200, 100);
    view.setTranslationX(40);
    view.setTranslationY(30);
    post(view, Color.RED);

    Bitmap screen = composite(false);
    assertEquals(Color.RED, screen.getPixel(40 + 5, 30 + 5));
    assertEquals(Color.RED, screen.getPixel(40 + 195, 30 + 95));
    assertEquals(Color.TRANSPARENT, screen.getPixel(40 + 205, 30 + 50));
    assertEquals(Color.TRANSPARENT, screen.getPixel(20, 20));
  }

  @Test
  public void drawFramesAppliesTheViewAndParentScale() {
    FrameLayout parent = new FrameLayout(activity);
    content.addView(parent, new FrameLayout.LayoutParams(400, 400));
    parent.setPivotX(0);
    parent.setPivotY(0);
    parent.setScaleX(0.5f);
    parent.setScaleY(0.5f);
    SurfaceView view = new SurfaceView(activity);
    view.getHolder().addCallback(new Recorder());
    parent.addView(view, new FrameLayout.LayoutParams(200, 100));
    idle();
    post(view, Color.RED);

    // Half size: 100x50 from the parent's origin, not the 200x100 layout size.
    Bitmap screen = composite(false);
    assertEquals(Color.RED, screen.getPixel(5, 5));
    assertEquals(Color.RED, screen.getPixel(95, 45));
    assertEquals(Color.TRANSPARENT, screen.getPixel(150, 25));
    assertEquals(Color.TRANSPARENT, screen.getPixel(50, 75));
  }

  private SurfaceView add(int width, int height) {
    return add(new SurfaceView(activity), width, height);
  }

  private SurfaceView add(SurfaceView view, int width, int height) {
    view.getHolder().addCallback(new Recorder());
    content.addView(view, new FrameLayout.LayoutParams(width, height));
    idle();
    return view;
  }

  private void post(SurfaceView view, int color) {
    Canvas canvas = view.getHolder().lockCanvas();
    canvas.drawColor(color);
    view.getHolder().unlockCanvasAndPost(canvas);
    idle();
  }

  private View root() {
    return activity.getWindow().getDecorView();
  }

  private Bitmap onlyFrame(boolean onTop) {
    List<SimulatorShadowSurfaceView.Frame> frames = SimulatorShadowSurfaceView.frames(root());
    assertEquals(1, frames.size());
    assertEquals(onTop, frames.get(0).onTop());
    return frames.get(0).bitmap();
  }

  /** The window as its views draw it, from the content view's origin (below any action bar). */
  private Bitmap drawWindow() {
    View root = root();
    Bitmap window = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
    int[] at = new int[2];
    content.getLocationInWindow(at);
    Canvas canvas = new Canvas(window);
    canvas.translate(-at[0], -at[1]);
    root.draw(canvas);
    return window;
  }

  /** Only the frames, drawn the way {@link Displays} draws them onto a screen. */
  private Bitmap composite(boolean onTop) {
    View root = root();
    Bitmap screen = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
    // Measure from the content view's origin, so the window's decor offsets cancel out.
    int[] at = new int[2];
    content.getLocationInWindow(at);
    Canvas canvas = new Canvas(screen);
    canvas.translate(-at[0], -at[1]);
    SimulatorShadowSurfaceView.drawFrames(
        canvas, root, 0, 0, SimulatorShadowSurfaceView.frames(root), onTop);
    return screen;
  }

  private static void idle() {
    shadowOf(Looper.getMainLooper()).idle();
  }

  private final class Recorder implements SurfaceHolder.Callback {
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
      events.add("created valid=" + holder.getSurface().isValid());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
      events.add("changed " + width + "x" + height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
      events.add("destroyed");
    }
  }
}
