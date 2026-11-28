package ee.schimke.composeai.simulator.shadows;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.view.TextureView;
import android.view.View;
import org.robolectric.annotation.ClassName;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowTextureView;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * A {@code TextureView} with a texture. On a device, the view's first hardware draw makes it a
 * {@code SurfaceTexture} and calls {@code onSurfaceTextureAvailable}, and each frame a producer
 * posts to that texture is composited as the view's hardware layer. Robolectric has no texture
 * layer ({@link ShadowTextureView}), so the texture is never made and an app waiting for it (Rive,
 * video players, camera previews) draws nothing.
 *
 * <p>Here the first draw of a laid-out, attached view makes the texture and calls the listener, as
 * the framework does, and the view draws the last frame posted to it, under its transform ({@code
 * setTransform}) and layer paint ({@code setLayerPaint}); {@code getBitmap} reads that frame. A
 * {@code Surface} made from the texture draws frames with {@code lockCanvas} (or {@code
 * lockHardwareCanvas}) and {@code unlockCanvasAndPost} ({@link SimulatorShadowSurface}); each post
 * redraws the view and calls {@code onSurfaceTextureUpdated}. Resizing and detaching are the
 * framework's own ({@code onSurfaceTextureSizeChanged}, {@code onSurfaceTextureDestroyed}).
 *
 * <p>Not supported: GL or Vulkan producers (EGL on the texture's surface), and media or camera
 * pipelines that write the texture natively. Added to the shadow map by {@code SimulatorSandbox}.
 */
@Implements(TextureView.class)
public class SimulatorShadowTextureView extends ShadowTextureView {

  @RealObject private TextureView view;

  /** No texture layer, without {@link ShadowTextureView}'s error on every draw. */
  @Implementation(minSdk = android.os.Build.VERSION_CODES.P, maxSdk = android.os.Build.VERSION_CODES.R)
  @Override
  protected @ClassName("android.view.TextureLayer") Object getTextureLayer() {
    return null;
  }

  @Implementation(methodName = "getTextureLayer", minSdk = android.os.Build.VERSION_CODES.S)
  @Override
  protected @ClassName("android.graphics.TextureLayer") Object getTextureLayerRPlus() {
    return null;
  }

  /**
   * No layer. A device's TextureView is always a hardware layer, its texture; here the frame is a
   * bitmap the view draws like any other content. Reporting {@code LAYER_TYPE_HARDWARE} would send a
   * software draw (a screenshot's {@code View.draw}) through a drawing cache that replaces the view's
   * whole area, so a translucent view would erase what is under it.
   */
  @Implementation
  public int getLayerType() {
    return View.LAYER_TYPE_NONE;
  }

  @Implementation
  public void draw(Canvas canvas) {
    // The framework's own draw: its drawn flags, then a texture layer, which there is none of.
    Shadow.directlyOn(view, TextureView.class, "draw", ClassParameter.from(Canvas.class, canvas));
    TextureFrames.Target target = texture();
    if (target == null) return;
    Bitmap frame = target.frame();
    if (frame == null) return;
    int save = canvas.save();
    Matrix transform = view.getTransform(null);
    canvas.concat(transform);
    Paint paint = ReflectionHelpers.getField(view, "mLayerPaint");
    canvas.drawBitmap(frame, null, new Rect(0, 0, view.getWidth(), view.getHeight()), paint);
    canvas.restoreToCount(save);
  }

  /** The last frame, scaled into [bitmap], as the texture layer would copy it. */
  @Implementation
  public Bitmap getBitmap(Bitmap bitmap) {
    if (bitmap == null) return null;
    TextureFrames.Target target = TextureFrames.of(view.getSurfaceTexture());
    Bitmap frame = target != null ? target.frame() : null;
    if (frame != null) {
      Canvas canvas = new Canvas(bitmap);
      canvas.concat(view.getTransform(null));
      canvas.drawBitmap(
          frame, null, new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()), null);
    }
    return bitmap;
  }

  /**
   * The view's texture, made at its first draw as {@code getTextureLayer} makes it: a view the app
   * gave a texture ({@code setSurfaceTexture}) keeps that one, and the listener hears only of one
   * made here.
   */
  private TextureFrames.Target texture() {
    SurfaceTexture texture = view.getSurfaceTexture();
    if (texture != null) {
      // setSurfaceTexture's request for the layer to take it; there is no layer to tell.
      ReflectionHelpers.setField(view, "mUpdateSurface", false);
      return TextureFrames.register(texture, view);
    }
    int width = view.getWidth();
    int height = view.getHeight();
    if (!view.isAttachedToWindow() || width <= 0 || height <= 0) return null;
    texture = new SurfaceTexture(false);
    texture.setDefaultBufferSize(width, height);
    ReflectionHelpers.setField(view, "mSurface", texture);
    TextureFrames.Target target = TextureFrames.register(texture, view);
    TextureView.SurfaceTextureListener listener = ReflectionHelpers.getField(view, "mListener");
    if (listener != null) listener.onSurfaceTextureAvailable(texture, width, height);
    return target;
  }
}
