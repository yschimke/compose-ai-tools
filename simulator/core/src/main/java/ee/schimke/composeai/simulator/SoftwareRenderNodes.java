package ee.schimke.composeai.simulator;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.RenderNode;
import android.media.Image;
import android.media.ImageReader;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code Canvas.drawRenderNode} for a software canvas.
 *
 * <p>On a device a {@code RenderNode} only draws into a hardware canvas: {@code Canvas} throws
 * {@code IllegalArgumentException("Software rendering doesn't support drawRenderNode")} otherwise.
 * Wear OS 6's {@code DeclarativeWatchFaceRuntime} caches layers in render nodes and draws them with
 * {@code drawRenderNode} onto whatever canvas it is given, including the software bitmap canvas of
 * {@code renderWatchFaceToBitmap} (a screenshot). A device never asks for that screenshot through a
 * software canvas while the layer cache is on; the simulator does on every {@code dwf-apk} frame,
 * so the face failed whenever the runtime had a cached layer, and the simulator then kept that error
 * until the face was set again.
 *
 * <p>{@link ApkClassConverter} rewrites the APK's {@code Canvas.drawRenderNode} calls to {@link
 * #draw}. A hardware canvas is passed straight through. For a software canvas the node is drawn
 * through a hardware canvas over an {@link ImageReader} surface (the same readback {@link
 * SimulatorShadowSurfaceView} uses), with the software canvas's current matrix, and the pixels are
 * drawn back at identity. The software canvas's clip is not applied to the node.
 */
public final class SoftwareRenderNodes {
  private static final AtomicLong DRAW_COUNT = new AtomicLong();
  private static final AtomicLong SOFTWARE_FALLBACK_COUNT = new AtomicLong();

  private SoftwareRenderNodes() {}

  /** Total number of {@link #draw(Canvas, RenderNode)} invocations since process start. */
  public static long drawCount() {
    return DRAW_COUNT.get();
  }

  /** Number of {@link #draw(Canvas, RenderNode)} calls that took the software-canvas fallback. */
  public static long softwareFallbackCount() {
    return SOFTWARE_FALLBACK_COUNT.get();
  }

  /** Replacement for {@code canvas.drawRenderNode(node)} in converted APK code. */
  public static void draw(Canvas canvas, RenderNode node) {
    DRAW_COUNT.incrementAndGet();
    if (canvas.isHardwareAccelerated()) {
      canvas.drawRenderNode(node);
      return;
    }
    SOFTWARE_FALLBACK_COUNT.incrementAndGet();
    int width = canvas.getWidth();
    int height = canvas.getHeight();
    if (width <= 0 || height <= 0 || !node.hasDisplayList()) return;
    Bitmap pixels = render(node, canvas.getMatrix(), width, height);
    if (pixels == null) return;
    canvas.save();
    canvas.setMatrix(null);
    canvas.drawBitmap(pixels, 0f, 0f, null);
    canvas.restore();
    pixels.recycle();
  }

  /** {@code node} drawn with {@code matrix} into a {@code width}x{@code height} ARGB bitmap. */
  static Bitmap render(RenderNode node, Matrix matrix, int width, int height) {
    ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1);
    Surface surface = reader.getSurface();
    try {
      Canvas hardware = surface.lockHardwareCanvas();
      try {
        hardware.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        hardware.concat(matrix);
        hardware.drawRenderNode(node);
      } finally {
        surface.unlockCanvasAndPost(hardware);
      }
      // One acquireNextImage per posted frame: Robolectric's native reader aborts on an acquire
      // with nothing queued (see SimulatorShadowSurfaceView#readBack).
      try (Image image = reader.acquireNextImage()) {
        if (image == null) return null;
        Image.Plane plane = image.getPlanes()[0];
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int rowStride = plane.getRowStride();
        ByteBuffer buffer = plane.getBuffer();
        if (rowStride == width * 4) {
          bitmap.copyPixelsFromBuffer(buffer);
        } else {
          ByteBuffer packed = ByteBuffer.allocate(width * height * 4);
          byte[] row = new byte[width * 4];
          for (int y = 0; y < height; y++) {
            buffer.position(y * rowStride);
            buffer.get(row);
            packed.put(row);
          }
          packed.rewind();
          bitmap.copyPixelsFromBuffer(packed);
        }
        return bitmap;
      }
    } finally {
      surface.release();
      reader.close();
    }
  }
}
