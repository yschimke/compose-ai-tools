package ee.schimke.composeai.simulator.shadows;

import android.graphics.SurfaceTexture;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowSurfaceTexture;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * Robolectric's {@code SurfaceTexture}, plus the buffer size a producer asks for. Its native {@code
 * setDefaultBufferSize} does nothing on the host, so the size is recorded here ({@link
 * TextureFrames}), and the next canvas a {@code TextureView}'s texture locks is that size, as on a
 * device; the view scales the frame to its bounds when it draws it. The framework's own {@code
 * TextureView.onSizeChanged} sets it too. Added to the shadow map by {@code SimulatorSandbox}.
 */
@Implements(SurfaceTexture.class)
public class SimulatorShadowSurfaceTexture extends ShadowSurfaceTexture {

  @RealObject private SurfaceTexture texture;

  @Implementation
  protected void setDefaultBufferSize(int width, int height) {
    TextureFrames.setBufferSize(texture, width, height);
    Shadow.directlyOn(
        texture,
        SurfaceTexture.class,
        "setDefaultBufferSize",
        ClassParameter.from(int.class, width),
        ClassParameter.from(int.class, height));
  }
}
