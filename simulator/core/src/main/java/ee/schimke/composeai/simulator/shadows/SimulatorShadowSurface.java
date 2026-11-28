package ee.schimke.composeai.simulator.shadows;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowNativeSurface;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * Robolectric's native-graphics {@code Surface}, plus a surface made from a {@code TextureView}'s
 * texture ({@link SimulatorShadowTextureView}). Robolectric gives {@code new Surface(texture)} no
 * native surface (a host has no {@code SurfaceTexture}), so it is never valid and {@code
 * lockCanvas} throws; here, while a view shows the texture, the surface is valid and {@code
 * lockCanvas} and {@code lockHardwareCanvas} hand out a canvas over the texture's next frame,
 * which {@code unlockCanvasAndPost} gives the view ({@link TextureFrames}). Rive's Canvas renderer
 * draws this way. Every other surface is {@link ShadowNativeSurface}'s.
 *
 * <p>Added to the shadow map by {@code SimulatorSandbox}, in place of the {@link ShadowNativeSurface}
 * Robolectric's picker chooses in native graphics mode, the only mode the simulator runs.
 */
@Implements(value = Surface.class, isInAndroidSdk = false, callNativeMethodsByDefault = true)
public class SimulatorShadowSurface extends ShadowNativeSurface {

  @RealObject private Surface surface;

  /** The texture this surface was made from, or null for any other surface. */
  private SurfaceTexture texture;

  @Implementation
  protected void __constructor__(SurfaceTexture surfaceTexture) {
    texture = surfaceTexture;
    Shadow.invokeConstructor(
        Surface.class, surface, ClassParameter.from(SurfaceTexture.class, surfaceTexture));
  }

  private TextureFrames.Target target() {
    return texture != null ? TextureFrames.of(texture) : null;
  }

  @Implementation
  protected boolean isValid() {
    if (target() != null) return true;
    return Shadow.directlyOn(surface, Surface.class, "isValid");
  }

  @Implementation
  protected Canvas lockCanvas(Rect inOutDirty) {
    TextureFrames.Target target = target();
    if (target != null) {
      Canvas canvas = target.lock(inOutDirty);
      if (canvas == null) {
        throw new IllegalArgumentException("Surface was already locked, or its view has no size");
      }
      return canvas;
    }
    return Shadow.directlyOn(
        surface, Surface.class, "lockCanvas", ClassParameter.from(Rect.class, inOutDirty));
  }

  @Implementation
  protected Canvas lockHardwareCanvas() {
    if (target() != null) return lockCanvas(null);
    return Shadow.directlyOn(surface, Surface.class, "lockHardwareCanvas");
  }

  @Implementation
  @Override
  protected void unlockCanvasAndPost(Canvas canvas) {
    TextureFrames.Target target = texture != null ? TextureFrames.of(texture) : null;
    if (target != null && target.owns(canvas)) {
      target.post(canvas);
      return;
    }
    Shadow.directlyOn(
        surface, Surface.class, "unlockCanvasAndPost", ClassParameter.from(Canvas.class, canvas));
    // ShadowNativeSurface's own filter, which this method replaces: an ImageReader's listeners.
    super.unlockCanvasAndPost(canvas);
  }
}
