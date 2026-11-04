package ee.schimke.composeai.simulator;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewTreeObserver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowSurfaceView;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * A {@code SurfaceView} with a surface. Robolectric's shadow gives it a holder whose {@code
 * getSurface()} and {@code lockCanvas()} return null and whose callbacks never run, so an app that
 * draws into one (video, a camera preview, a game loop, Filament) starts and shows nothing.
 *
 * <p>Here the holder runs {@code surfaceCreated}, {@code surfaceChanged} and {@code
 * surfaceDestroyed} on the main thread as the view is attached, laid out, shown and hidden, and
 * what the app draws reaches the screen ({@link Displays#capture}):
 *
 * <ul>
 *   <li>{@code holder.lockCanvas()} draws into a bitmap kept from frame to frame, as a software
 *       surface keeps its last buffer; {@code unlockCanvasAndPost} publishes it.
 *   <li>{@code holder.getSurface()} is a real surface over an {@link ImageReader}, which a hardware
 *       canvas ({@code lockHardwareCanvas}) or native code can draw into; each frame it posts is
 *       read back once. (A software {@code lockCanvas} straight on that surface draws nothing under
 *       Robolectric's native graphics.)
 * </ul>
 *
 * <p>As on a device, the view punches a transparent hole in its window and the surface shows
 * through it, under whatever the window draws over the view; {@code setZOrderOnTop} puts it over
 * the window instead. A resize replaces the surface (destroyed, then created at the new size)
 * rather than resizing it in place. Added to the shadow map by {@link SimulatorSandbox}.
 */
@Implements(SurfaceView.class)
public class SimulatorShadowSurfaceView extends ShadowSurfaceView {

  @RealObject private SurfaceView view;

  private Holder holder;

  @Implementation
  @Override
  protected SurfaceHolder getHolder() {
    if (holder == null) holder = new Holder(view);
    return holder;
  }

  @Implementation
  @Override
  protected void onAttachedToWindow() {
    Shadow.directlyOn(view, SurfaceView.class, "onAttachedToWindow");
    ((Holder) getHolder()).attach();
  }

  /**
   * The framework's own surface management, which builds a {@code SurfaceControl} and a {@code
   * BLASTBufferQueue} Robolectric has no native object for ("Exception configuring surface"). The
   * {@link Holder} manages the surface instead.
   */
  @Implementation
  protected void updateSurface() {}

  /** The hole: the view's area made transparent, so the surface under the window shows. */
  @Implementation
  public void draw(Canvas canvas) {
    clear(canvas);
    Shadow.directlyOn(view, SurfaceView.class, "draw", ClassParameter.from(Canvas.class, canvas));
  }

  @Implementation
  protected void dispatchDraw(Canvas canvas) {
    clear(canvas);
    Shadow.directlyOn(
        view, SurfaceView.class, "dispatchDraw", ClassParameter.from(Canvas.class, canvas));
  }

  /**
   * Clears the view's own rectangle, not the whole clip: under a parent with {@code
   * clipChildren=false} the clip reaches past the view, and clearing it would erase window content
   * around the surface.
   */
  private void clear(Canvas canvas) {
    if (holder != null && holder.surfaceShown() && !holder.onTop()) {
      int save = canvas.save();
      int x = view.getScrollX();
      int y = view.getScrollY();
      canvas.clipRect(x, y, x + view.getWidth(), y + view.getHeight());
      canvas.drawColor(0, PorterDuff.Mode.CLEAR);
      canvas.restoreToCount(save);
    }
  }

  /** Every holder with a surface now, for {@link Displays}. */
  private static final Set<Holder> LIVE = new CopyOnWriteArraySet<>();

  /** A surface to draw into a window: its view, its last frame and where it goes. */
  record Frame(View view, Bitmap bitmap, boolean onTop) {}

  /** The frames of the surfaces in the window whose root is [root], in drawing order. */
  static List<Frame> frames(View root) {
    List<Frame> frames = new ArrayList<>();
    for (Holder holder : LIVE) {
      if (holder.view.getRootView() != root || !holder.view.isShown()) continue;
      Bitmap bitmap = holder.frame();
      if (bitmap != null) frames.add(new Frame(holder.view, bitmap, holder.onTop()));
    }
    return Collections.unmodifiableList(frames);
  }

  /**
   * Draws the [onTop] (or the other) [frames] of the window whose root is [root] onto [canvas], the
   * screen, where the window's top left is at [left], [top]: the others go under the window's
   * bitmap, showing through the holes their views punch, the on-top ones over it.
   */
  static void drawFrames(
      Canvas canvas, View root, int left, int top, List<Frame> frames, boolean onTop) {
    int[] rootAt = new int[2];
    root.getLocationInWindow(rootAt);
    int[] at = new int[2];
    for (Frame frame : frames) {
      if (frame.onTop() != onTop) continue;
      frame.view().getLocationInWindow(at);
      int x = left + at[0] - rootAt[0];
      int y = top + at[1] - rootAt[1];
      Rect dst = new Rect(x, y, x + frame.view().getWidth(), y + frame.view().getHeight());
      canvas.drawBitmap(frame.bitmap(), null, dst, null);
    }
  }

  /** The surface of one view, and the callbacks the app registered on it. */
  static final class Holder implements SurfaceHolder {
    private final SurfaceView view;
    private final List<Callback> callbacks = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ViewTreeObserver.OnPreDrawListener preDraw =
        () -> {
          update();
          return true;
        };

    private int format = PixelFormat.OPAQUE;
    private int fixedWidth;
    private int fixedHeight;
    private boolean creating;

    // The surface while one exists: its size, the reader behind getSurface(), the bitmap
    // lockCanvas() draws into, and the last frame either one posted.
    private int width;
    private int height;
    private ImageReader reader;
    private Bitmap canvasBitmap;
    private Bitmap frame;
    private Canvas locked;

    Holder(SurfaceView view) {
      this.view = view;
    }

    void attach() {
      view.getViewTreeObserver().addOnPreDrawListener(preDraw);
      view.addOnAttachStateChangeListener(
          new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {}

            @Override
            public void onViewDetachedFromWindow(View v) {
              v.removeOnAttachStateChangeListener(this);
              v.getViewTreeObserver().removeOnPreDrawListener(preDraw);
              destroy();
            }
          });
      main.post(this::update);
    }

    boolean surfaceShown() {
      return reader != null;
    }

    /**
     * {@code setZOrderOnTop}: the surface goes over its window rather than under it. A sublayer of
     * 0 or more is above the window (media overlay, -1, and the default, -2, are below). From API
     * 30 the request waits in {@code mRequestedSubLayer} for {@code updateSurface}, which does
     * nothing here; before that, {@code mSubLayer} takes it directly.
     */
    boolean onTop() {
      Integer subLayer;
      try {
        subLayer = ReflectionHelpers.getField(view, "mRequestedSubLayer");
      } catch (RuntimeException e) {
        subLayer = ReflectionHelpers.getField(view, "mSubLayer");
      }
      return subLayer != null && subLayer >= 0;
    }

    /** Creates, replaces or destroys the surface to match the view: shown, laid out, sized. */
    private void update() {
      // A collapsed view (zero width or height) has no surface: ImageReader takes neither.
      boolean wanted =
          view.isAttachedToWindow() && view.isShown() && bufferWidth() > 0 && bufferHeight() > 0;
      if (!wanted) {
        destroy();
        return;
      }
      if (reader != null && width == bufferWidth() && height == bufferHeight()) return;
      destroy();
      create(bufferWidth(), bufferHeight());
    }

    private int bufferWidth() {
      return fixedWidth > 0 ? fixedWidth : view.getWidth();
    }

    private int bufferHeight() {
      return fixedHeight > 0 ? fixedHeight : view.getHeight();
    }

    private void create(int width, int height) {
      this.width = width;
      this.height = height;
      reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
      // One acquireNextImage per posted frame: Robolectric's native reader aborts the process on
      // an acquire with nothing queued, so acquireLatestImage's drain loop is out.
      reader.setOnImageAvailableListener(this::readBack, main);
      canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      LIVE.add(this);
      creating = true;
      try {
        for (Callback callback : snapshot()) callback.surfaceCreated(this);
        for (Callback callback : snapshot()) callback.surfaceChanged(this, format, width, height);
      } finally {
        creating = false;
      }
      view.invalidate();
    }

    private void destroy() {
      if (reader == null) return;
      for (Callback callback : snapshot()) callback.surfaceDestroyed(this);
      LIVE.remove(this);
      ImageReader old = reader;
      reader = null;
      old.close();
      synchronized (this) {
        frame = null;
      }
      canvasBitmap = null;
      view.invalidate();
    }

    private List<Callback> snapshot() {
      synchronized (callbacks) {
        return new ArrayList<>(callbacks);
      }
    }

    private void readBack(ImageReader from) {
      if (from != reader) return;
      Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      try (Image image = from.acquireNextImage()) {
        if (image == null) return;
        // The same copy Robolectric's PixelCopy makes of a window's surface.
        bitmap.copyPixelsFromBuffer(image.getPlanes()[0].getBuffer());
      }
      publish(bitmap);
    }

    private void publish(Bitmap bitmap) {
      synchronized (this) {
        frame = bitmap;
      }
      view.postInvalidate();
    }

    synchronized Bitmap frame() {
      return frame;
    }

    @Override
    public void addCallback(Callback callback) {
      synchronized (callbacks) {
        if (!callbacks.contains(callback)) callbacks.add(callback);
      }
    }

    @Override
    public void removeCallback(Callback callback) {
      synchronized (callbacks) {
        callbacks.remove(callback);
      }
    }

    @Override
    public boolean isCreating() {
      return creating;
    }

    @Override
    public void setType(int type) {}

    @Override
    public void setFixedSize(int width, int height) {
      fixedWidth = width;
      fixedHeight = height;
      main.post(this::update);
    }

    @Override
    public void setSizeFromLayout() {
      setFixedSize(0, 0);
    }

    @Override
    public void setFormat(int format) {
      this.format = format;
    }

    @Override
    public void setKeepScreenOn(boolean screenOn) {
      view.setKeepScreenOn(screenOn);
    }

    @Override
    public Canvas lockCanvas() {
      return lockCanvas(null);
    }

    @Override
    public Canvas lockCanvas(Rect dirty) {
      Bitmap bitmap = canvasBitmap;
      if (bitmap == null) return null;
      Canvas canvas = new Canvas(bitmap);
      if (dirty != null) canvas.clipRect(dirty);
      locked = canvas;
      return canvas;
    }

    @Override
    public void unlockCanvasAndPost(Canvas canvas) {
      if (canvas != locked || canvasBitmap == null) return;
      locked = null;
      publish(canvasBitmap.copy(Bitmap.Config.ARGB_8888, false));
    }

    @Override
    public Rect getSurfaceFrame() {
      return new Rect(0, 0, width, height);
    }

    @Override
    public Surface getSurface() {
      ImageReader current = reader;
      if (current != null) return current.getSurface();
      // Before a surface exists, as on a device: a Surface that is not valid.
      if (noSurface == null) noSurface = new Surface(new SurfaceTexture(false));
      return noSurface;
    }

    private Surface noSurface;
  }
}
