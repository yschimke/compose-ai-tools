package ee.schimke.composeai.simulator.shadows;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowEGL14;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * {@code EGL14} for an app whose OpenGL ES runs in native code that the simulator serves a host
 * build of (Rive's Compose API, {@code host-natives/rive}).
 *
 * <p>Java has no OpenGL here: like Robolectric's {@link ShadowEGL14} this answers every call
 * without a GPU, and its handles are tokens. Unlike it, the tokens are tagged ({@link #TAG}) and
 * remembered, every call an app makes during setup is answered (configs' attributes, destroying,
 * terminating), and a window surface remembers the {@link Surface} it was made for. A host build
 * that renders with the host's own EGL recognises the tokens, gives each a real context or
 * offscreen surface, and after each swap hands the frame to {@link #present}, which draws it into
 * that {@link Surface}: a {@code TextureView}'s ({@link SimulatorShadowTextureView}) or a {@code
 * SurfaceView}'s. Java's own {@code GLES20} calls still do nothing.
 *
 * <p>Added to the shadow map by {@code SimulatorSandbox}.
 */
@Implements(EGL14.class)
public class SimulatorShadowEGL14 extends ShadowEGL14 {

  /**
   * The top 16 bits of every handle this hands out; a host build checks them to tell its tokens
   * from a real EGL's handles. Kinds are in the next 16 bits ({@link #DISPLAY} …), ids below.
   */
  public static final long TAG = 0x51D0L << 48;

  public static final long DISPLAY = 1;
  public static final long CONFIG = 2;
  public static final long CONTEXT = 3;
  public static final long SURFACE = 4;

  private static final AtomicLong NEXT_ID = new AtomicLong(1);
  private static final long THE_DISPLAY = TAG | (DISPLAY << 32) | 1;
  private static final long THE_CONFIG = TAG | (CONFIG << 32) | 1;

  /** Each context token's client version. */
  private static final Map<Long, Integer> CONTEXTS = new ConcurrentHashMap<>();

  /** Each surface token's record. */
  private static final Map<Long, SurfaceRecord> SURFACES = new ConcurrentHashMap<>();

  private static final int EGL_OPENGL_ES3_BIT = 0x40;

  private static final class SurfaceRecord {
    final WeakReference<Surface> window;
    final int width;
    final int height;
    Bitmap frame;

    SurfaceRecord(Surface window, int width, int height) {
      this.window = new WeakReference<>(window);
      this.width = width;
      this.height = height;
    }
  }

  private static long token(long kind) {
    return TAG | (kind << 32) | NEXT_ID.getAndIncrement();
  }

  private static <T> T handle(Class<T> type, long value) {
    return ReflectionHelpers.callConstructor(type, ClassParameter.from(long.class, value));
  }

  private static int attrib(int[] list, int offset, int name, int fallback) {
    if (list == null) return fallback;
    for (int i = offset; i + 1 < list.length && list[i] != EGL14.EGL_NONE; i += 2) {
      if (list[i] == name) return list[i + 1];
    }
    return fallback;
  }

  @Implementation
  protected static EGLDisplay eglGetDisplay(int displayId) {
    return handle(EGLDisplay.class, THE_DISPLAY);
  }

  @Implementation
  protected static boolean eglInitialize(
      EGLDisplay dpy, int[] major, int majorOffset, int[] minor, int minorOffset) {
    if (major != null) major[majorOffset] = 1;
    if (minor != null) minor[minorOffset] = 4;
    return true;
  }

  @Implementation
  protected static boolean eglTerminate(EGLDisplay dpy) {
    return true;
  }

  @Implementation
  protected static boolean eglChooseConfig(
      EGLDisplay dpy,
      int[] attribList,
      int attribListOffset,
      EGLConfig[] configs,
      int configsOffset,
      int configSize,
      int[] numConfig,
      int numConfigOffset) {
    if (configs != null && configSize > 0) {
      configs[configsOffset] = handle(EGLConfig.class, THE_CONFIG);
    }
    numConfig[numConfigOffset] = 1;
    return true;
  }

  /** The one config: RGBA 8888 with depth and stencil, for windows and pbuffers, ES 2 and 3. */
  @Implementation
  protected static boolean eglGetConfigAttrib(
      EGLDisplay dpy, EGLConfig config, int attribute, int[] value, int offset) {
    int answer;
    switch (attribute) {
      case EGL14.EGL_RED_SIZE:
      case EGL14.EGL_GREEN_SIZE:
      case EGL14.EGL_BLUE_SIZE:
      case EGL14.EGL_ALPHA_SIZE:
        answer = 8;
        break;
      case EGL14.EGL_DEPTH_SIZE:
        answer = 24;
        break;
      case EGL14.EGL_STENCIL_SIZE:
        answer = 8;
        break;
      case EGL14.EGL_RENDERABLE_TYPE:
        answer = EGL14.EGL_OPENGL_ES2_BIT | EGL_OPENGL_ES3_BIT;
        break;
      case EGL14.EGL_SURFACE_TYPE:
        answer = EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT;
        break;
      default:
        answer = 0;
    }
    value[offset] = answer;
    return true;
  }

  @Implementation
  protected static EGLContext eglCreateContext(
      EGLDisplay dpy, EGLConfig config, EGLContext shareContext, int[] attribList, int offset) {
    int version = attrib(attribList, offset, EGL14.EGL_CONTEXT_CLIENT_VERSION, 1);
    if (version < 2) return EGL14.EGL_NO_CONTEXT;
    long context = token(CONTEXT);
    CONTEXTS.put(context, version);
    return handle(EGLContext.class, context);
  }

  @Implementation
  protected static boolean eglDestroyContext(EGLDisplay dpy, EGLContext ctx) {
    CONTEXTS.remove(ctx.getNativeHandle());
    return true;
  }

  @Implementation
  protected static boolean eglQueryContext(
      EGLDisplay dpy, EGLContext ctx, int attribute, int[] value, int offset) {
    Integer version = CONTEXTS.get(ctx.getNativeHandle());
    value[offset] =
        attribute == EGL14.EGL_CONTEXT_CLIENT_VERSION && version != null ? version : 0;
    return true;
  }

  /**
   * A window surface over [win]: a {@link Surface}, or a {@code SurfaceTexture}, {@code
   * SurfaceView} or {@code SurfaceHolder}, as {@code EGL14} accepts. Its size is the window's, or
   * 0 until the host build learns it from the app.
   */
  @Implementation
  protected static EGLSurface eglCreateWindowSurface(
      EGLDisplay dpy, EGLConfig config, Object win, int[] attribList, int offset) {
    Surface surface;
    if (win instanceof Surface) {
      surface = (Surface) win;
    } else if (win instanceof SurfaceTexture) {
      surface = new Surface((SurfaceTexture) win);
    } else if (win instanceof SurfaceView) {
      surface = ((SurfaceView) win).getHolder().getSurface();
    } else if (win instanceof SurfaceHolder) {
      surface = ((SurfaceHolder) win).getSurface();
    } else {
      return EGL14.EGL_NO_SURFACE;
    }
    long token = token(SURFACE);
    SURFACES.put(token, new SurfaceRecord(surface, 0, 0));
    return handle(EGLSurface.class, token);
  }

  @Implementation
  protected static EGLSurface eglCreatePbufferSurface(
      EGLDisplay dpy, EGLConfig config, int[] attribList, int offset) {
    long token = token(SURFACE);
    SURFACES.put(
        token,
        new SurfaceRecord(
            null,
            attrib(attribList, offset, EGL14.EGL_WIDTH, 1),
            attrib(attribList, offset, EGL14.EGL_HEIGHT, 1)));
    return handle(EGLSurface.class, token);
  }

  @Implementation
  protected static boolean eglDestroySurface(EGLDisplay dpy, EGLSurface surface) {
    SURFACES.remove(surface.getNativeHandle());
    return true;
  }

  @Implementation
  protected static boolean eglQuerySurface(
      EGLDisplay dpy, EGLSurface surface, int attribute, int[] value, int offset) {
    SurfaceRecord record = SURFACES.get(surface.getNativeHandle());
    int answer = 0;
    if (record != null && attribute == EGL14.EGL_WIDTH) answer = record.width;
    if (record != null && attribute == EGL14.EGL_HEIGHT) answer = record.height;
    value[offset] = answer;
    return true;
  }

  @Implementation
  protected static boolean eglSwapInterval(EGLDisplay dpy, int interval) {
    return true;
  }

  @Implementation
  protected static boolean eglBindAPI(int api) {
    return true;
  }

  @Implementation
  protected static boolean eglReleaseThread() {
    return true;
  }

  /**
   * [count] premultiplied RGBA pixels as colour ints. Explicitly, not with {@code
   * copyPixelsFromBuffer}: a bitmap's bytes are in the host's order (BGRA on x86 and arm64), not
   * Android's RGBA, under Robolectric's native graphics.
   */
  private static int[] colors(ByteBuffer rgba, int count) {
    int[] colors = new int[count];
    rgba.rewind();
    for (int i = 0; i < count; i++) {
      int r = rgba.get() & 0xFF;
      int g = rgba.get() & 0xFF;
      int b = rgba.get() & 0xFF;
      int a = rgba.get() & 0xFF;
      if (a != 0 && a != 255) {
        r = Math.min(255, r * 255 / a);
        g = Math.min(255, g * 255 / a);
        b = Math.min(255, b * 255 / a);
      }
      colors[i] = a == 0 ? 0 : (a << 24) | (r << 16) | (g << 8) | b;
    }
    return colors;
  }

  /**
   * Draws a frame a host build rendered for the window surface [surfaceToken] into that window's
   * {@link Surface}: [rgba] holds [width] × [height] premultiplied RGBA pixels, top row first.
   * Called from the host build's render thread after each swap. False when the token is not a
   * window this made, or its surface is gone or cannot be drawn into.
   */
  public static boolean present(long surfaceToken, ByteBuffer rgba, int width, int height) {
    SurfaceRecord record = SURFACES.get(surfaceToken);
    if (record == null || width <= 0 || height <= 0) return false;
    Surface surface = record.window.get();
    if (surface == null || !surface.isValid()) return false;
    synchronized (record) {
      if (record.frame == null
          || record.frame.getWidth() != width
          || record.frame.getHeight() != height) {
        record.frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      }
      record.frame.setPixels(colors(rgba, width * height), 0, width, 0, 0, width, height);
      Canvas canvas;
      try {
        canvas = surface.lockHardwareCanvas();
      } catch (RuntimeException e) {
        return false;
      }
      if (canvas == null) return false;
      try {
        canvas.drawColor(0, PorterDuff.Mode.CLEAR);
        canvas.drawBitmap(
            record.frame, null, new Rect(0, 0, canvas.getWidth(), canvas.getHeight()), null);
      } finally {
        surface.unlockCanvasAndPost(canvas);
      }
    }
    return true;
  }
}
