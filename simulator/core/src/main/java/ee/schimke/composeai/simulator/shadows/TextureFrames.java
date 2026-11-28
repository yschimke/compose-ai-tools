package ee.schimke.composeai.simulator.shadows;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.robolectric.util.ReflectionHelpers;

/**
 * The frames of the {@code SurfaceTexture}s the simulator gave its {@code TextureView}s ({@link
 * SimulatorShadowTextureView}), which a {@code Surface} made from one draws into ({@link
 * SimulatorShadowSurface}). On a device that texture is a GL texture a hardware layer composites;
 * here it is a bitmap the view draws itself.
 */
final class TextureFrames {
  private TextureFrames() {}

  private static final Map<SurfaceTexture, Target> TARGETS = new WeakHashMap<>();

  /** Each texture's requested buffer size ({@code setDefaultBufferSize}), as {width, height}. */
  private static final Map<SurfaceTexture, int[]> SIZES = new WeakHashMap<>();

  /** Records [texture]'s buffer size, which its next locked canvas takes. */
  static void setBufferSize(SurfaceTexture texture, int width, int height) {
    synchronized (SIZES) {
      SIZES.put(texture, new int[] {width, height});
    }
  }

  /** [texture]'s requested buffer size, or null when it has none. */
  private static int[] bufferSize(SurfaceTexture texture) {
    if (texture == null) return null;
    synchronized (SIZES) {
      return SIZES.get(texture);
    }
  }

  /** Gives [texture] frames drawn by [view], replacing whatever view had it before. */
  static Target register(SurfaceTexture texture, TextureView view) {
    synchronized (TARGETS) {
      Target target = TARGETS.get(texture);
      if (target == null || target.view.get() != view) {
        target = new Target(texture, view);
        TARGETS.put(texture, target);
      }
      return target;
    }
  }

  /**
   * The frames of [texture] while a view shows it, or null: a texture never given to a view, or one
   * its view has let go of (detached, or replaced with setSurfaceTexture), draws nothing anywhere,
   * as on a device.
   */
  static Target of(SurfaceTexture texture) {
    if (texture == null) return null;
    Target target;
    synchronized (TARGETS) {
      target = TARGETS.get(texture);
    }
    if (target == null) return null;
    TextureView view = target.view.get();
    if (view == null || view.getSurfaceTexture() != texture) return null;
    return target;
  }

  /** One texture's buffers: the one a locked canvas draws into, and the last frame posted. */
  static final class Target {
    // Weak, as TARGETS's key is this texture: a strong reference here would keep every entry, and
    // its bitmaps, for the life of the sandbox.
    private final WeakReference<SurfaceTexture> texture;
    private final WeakReference<TextureView> view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean updatePending = new AtomicBoolean();

    private Bitmap back;
    private Canvas locked;
    // The last frame posted, overwritten in place by the next (under this target's lock), and the
    // copy of it the view last drew, which only the main thread touches.
    private Bitmap front;
    private Bitmap shown;

    private Target(SurfaceTexture texture, TextureView view) {
      this.texture = new WeakReference<>(texture);
      this.view = new WeakReference<>(view);
    }

    /**
     * A canvas over the back buffer, at the texture's buffer size ({@code setDefaultBufferSize}, or
     * the view's size when it has none): the buffer is kept from frame to frame, as a device's is,
     * and replaced only when that size changes.
     */
    synchronized Canvas lock(Rect dirty) {
      TextureView current = view.get();
      if (current == null || locked != null) return null;
      int[] size = bufferSize(texture.get());
      int width = size != null ? size[0] : current.getWidth();
      int height = size != null ? size[1] : current.getHeight();
      if (width <= 0 || height <= 0) return null;
      if (back == null || back.getWidth() != width || back.getHeight() != height) {
        back = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      }
      Canvas canvas = new Canvas(back);
      if (dirty != null) canvas.clipRect(dirty);
      locked = canvas;
      return canvas;
    }

    /** Whether [canvas] is the one {@link #lock} handed out and not yet posted. */
    synchronized boolean owns(Object canvas) {
      return canvas != null && canvas == locked;
    }

    /** Publishes the back buffer as the texture's frame, and tells the view and its listener. */
    void post(Canvas canvas) {
      synchronized (this) {
        if (canvas != locked) return;
        locked = null;
        if (front == null
            || front.getWidth() != back.getWidth()
            || front.getHeight() != back.getHeight()) {
          front = Bitmap.createBitmap(back.getWidth(), back.getHeight(), Bitmap.Config.ARGB_8888);
        }
        Canvas copy = new Canvas(front);
        copy.drawColor(0, PorterDuff.Mode.CLEAR);
        copy.drawBitmap(back, 0, 0, null);
      }
      if (!updatePending.compareAndSet(false, true)) return;
      main.post(
          () -> {
            updatePending.set(false);
            TextureView current = view.get();
            SurfaceTexture shown = texture.get();
            if (current == null || shown == null || current.getSurfaceTexture() != shown) return;
            current.invalidate();
            TextureView.SurfaceTextureListener listener =
                ReflectionHelpers.getField(current, "mListener");
            if (listener != null) listener.onSurfaceTextureUpdated(shown);
          });
    }

    /** The last frame posted, copied for drawing on the main thread; null before the first. */
    Bitmap frame() {
      synchronized (this) {
        if (front == null) return null;
        if (shown == null
            || shown.getWidth() != front.getWidth()
            || shown.getHeight() != front.getHeight()) {
          shown = Bitmap.createBitmap(front.getWidth(), front.getHeight(), Bitmap.Config.ARGB_8888);
        }
        Canvas copy = new Canvas(shown);
        copy.drawColor(0, PorterDuff.Mode.CLEAR);
        copy.drawBitmap(front, 0, 0, null);
      }
      return shown;
    }
  }
}
