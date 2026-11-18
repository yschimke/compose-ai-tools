package ee.schimke.composeai.simulator;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.Surface;
import android.view.SurfaceHolder;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Frames from a watch face's live drawing path: the wallpaper engine the interactive instance
 * draws into, rather than the instance's {@code renderWatchFaceToBitmap} screenshot call.
 *
 * <p>Wear OS 6's {@code DeclarativeWatchFaceRuntime} draws a screenshot with the date's day of the
 * month forced to 28 (month and year from the real clock, whatever time was asked for): its
 * headless / screenshot branch builds the document's time as {@code
 * ZonedDateTime.ofInstant(requested, UTC).withDayOfMonth(28).withMonth(now.month).withYear(now.year)}.
 * The live draw is the path a device uses, and it follows the clock.
 *
 * <p>A {@link WallpaperService.Engine} in the simulator has no window, so no surface: {@code
 * getSurfaceHolder().getSurface()} is not valid and the face's {@code lockHardwareCanvas} would fail.
 * This gives the engine's {@code BaseSurfaceHolder} a surface over an {@link ImageReader} (the same
 * readback {@link SimulatorShadowSurfaceView} uses), tells the engine it was created and sized, and
 * asks for a draw with {@code onSurfaceRedrawNeeded}, as the system does when a surface needs a
 * frame. Every frame the face posts is read back; {@link #draw} returns the one its own request
 * produced.
 */
final class LiveFrameSource {
  /** How long a requested draw may take to reach the reader. */
  static final long FRAME_TIMEOUT_MS = 10_000;

  /** How long the reader must be idle for earlier frames to count as read back. */
  static final long QUIET_MS = 40;

  private final WallpaperService.Engine engine;
  private final SurfaceHolder holder;
  private final Handler main;
  private final int width;
  private final int height;
  private final HandlerThread readerThread = new HandlerThread("live-frame-reader");

  private ImageReader reader;
  private Bitmap latest;
  private long posted;
  private long lastReadNs = System.nanoTime();
  private final Object lock = new Object();

  private LiveFrameSource(
      WallpaperService.Engine engine, SurfaceHolder holder, int width, int height) {
    this.engine = engine;
    this.holder = holder;
    this.width = width;
    this.height = height;
    this.main = new Handler(Looper.getMainLooper());
  }

  /**
   * The engine behind [engineBinder] (the {@code IWallpaperEngine} the service handed the
   * connection), or null if it is not an in-process wallpaper engine.
   */
  static WallpaperService.Engine engineOf(IBinder engineBinder) {
    if (engineBinder == null) return null;
    try {
      Field f = engineBinder.getClass().getDeclaredField("mEngine");
      f.setAccessible(true);
      Object value = f.get(engineBinder);
      return value instanceof WallpaperService.Engine ? (WallpaperService.Engine) value : null;
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  /**
   * Gives [engineBinder]'s engine a surface of [width] x [height] and reports it created. Call on
   * the main thread. Returns null where the engine cannot be given one (nothing to fall back from
   * but the screenshot path).
   */
  static LiveFrameSource attach(IBinder engineBinder, int width, int height) {
    return attach(engineOf(engineBinder), width, height);
  }

  /** As {@link #attach(IBinder, int, int)}, for an engine already in hand. */
  static LiveFrameSource attach(WallpaperService.Engine engine, int width, int height) {
    if (engine == null || width <= 0 || height <= 0) return null;
    try {
      SurfaceHolder holder = engine.getSurfaceHolder();
      if (holder == null) return null;
      LiveFrameSource source = new LiveFrameSource(engine, holder, width, height);
      source.start();
      return source;
    } catch (ReflectiveOperationException | RuntimeException e) {
      e.printStackTrace(System.err);
      return null;
    }
  }

  private void start() throws ReflectiveOperationException {
    readerThread.start();
    reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
    // One acquireNextImage per posted frame: Robolectric's native reader aborts the process on an
    // acquire with nothing queued, so acquireLatestImage's drain loop is out.
    reader.setOnImageAvailableListener(this::readBack, new Handler(readerThread.getLooper()));
    Surface surface = reader.getSurface();
    // BaseSurfaceHolder.mSurface is the engine's surface; the face locks its canvas through it.
    Field mSurface = holder.getClass().getSuperclass().getDeclaredField("mSurface");
    mSurface.setAccessible(true);
    mSurface.set(holder, surface);
    setBoolean(engine, "mDrawingAllowed", true);
    setBoolean(engine, "mSurfaceCreated", true);
    setInt(engine, "mCurWidth", width);
    setInt(engine, "mCurHeight", height);
    setInt(engine, "mWidth", width);
    setInt(engine, "mHeight", height);
    Method frame = holder.getClass().getMethod("setSurfaceFrameSize", int.class, int.class);
    frame.invoke(holder, width, height);
    engine.onSurfaceCreated(holder);
    engine.onSurfaceChanged(holder, PixelFormat.RGBA_8888, width, height);
    engine.onVisibilityChanged(true);
  }

  private static void setBoolean(Object target, String name, boolean value)
      throws ReflectiveOperationException {
    Field f = findField(target.getClass(), name);
    f.setAccessible(true);
    f.setBoolean(target, value);
  }

  private static void setInt(Object target, String name, int value)
      throws ReflectiveOperationException {
    Field f = findField(target.getClass(), name);
    f.setAccessible(true);
    f.setInt(target, value);
  }

  private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
    for (Class<?> c = type; c != null; c = c.getSuperclass()) {
      try {
        return c.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
        // Keep looking in the superclass.
      }
    }
    throw new NoSuchFieldException(name);
  }

  private void readBack(ImageReader from) {
    if (from != reader) return;
    try (Image image = from.acquireNextImage()) {
      if (image == null) return;
      Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      Image.Plane plane = image.getPlanes()[0];
      int rowStride = plane.getRowStride();
      java.nio.ByteBuffer buffer = plane.getBuffer();
      if (rowStride == width * 4) {
        bitmap.copyPixelsFromBuffer(buffer);
      } else {
        // A padded row: copy each row's pixels, not the padding (as SoftwareRenderNodes does).
        java.nio.ByteBuffer packed = java.nio.ByteBuffer.allocate(width * height * 4);
        byte[] row = new byte[width * 4];
        for (int y = 0; y < height; y++) {
          buffer.position(y * rowStride);
          buffer.get(row);
          packed.put(row);
        }
        packed.rewind();
        bitmap.copyPixelsFromBuffer(packed);
      }
      synchronized (lock) {
        latest = bitmap;
        posted++;
        lastReadNs = System.nanoTime();
        lock.notifyAll();
      }
    }
  }

  /**
   * Asks the face to draw and returns the frame it posted, or null if it posted none in time.
   * Called off the main thread (it waits for the frame); the draw itself runs on the main thread,
   * where the engine's callbacks run.
   */
  Bitmap draw() {
    long before;
    try {
      // Readback runs on its own thread, so a frame the face posted before this request (its own
      // loop, an earlier timed-out request) may land after a counter snapshot and pass for this
      // one. Let those land first: frames come in the order they were posted, so once the reader
      // has been quiet, everything counted below is this request's.
      awaitQuiet();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
    synchronized (lock) {
      before = posted;
    }
    CountDownLatch drawn = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    main.post(
        () -> {
          try {
            engine.onSurfaceRedrawNeeded(holder);
          } catch (Throwable t) {
            failure.set(t);
          } finally {
            drawn.countDown();
          }
        });
    try {
      if (!drawn.await(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null;
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FRAME_TIMEOUT_MS);
      synchronized (lock) {
        while (posted <= before) {
          long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
          if (left <= 0) return null;
          lock.wait(left);
        }
      }
      // This request's frame was queued before the draw returned, behind nothing older; wait for
      // the rest of its burst (a face may post more than once) and take the last.
      awaitQuiet();
      synchronized (lock) {
        return latest;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } finally {
      Throwable t = failure.get();
      if (t != null) t.printStackTrace(System.err);
    }
  }

  /** Returns once no frame has been read back for {@link #QUIET_MS}. */
  private void awaitQuiet() throws InterruptedException {
    long quiet = TimeUnit.MILLISECONDS.toNanos(QUIET_MS);
    synchronized (lock) {
      while (true) {
        long left = quiet - (System.nanoTime() - lastReadNs);
        if (left <= 0) return;
        TimeUnit.NANOSECONDS.timedWait(lock, left);
      }
    }
  }

  void release() {
    ImageReader old = reader;
    reader = null;
    if (old != null) old.close();
    readerThread.quitSafely();
  }
}
