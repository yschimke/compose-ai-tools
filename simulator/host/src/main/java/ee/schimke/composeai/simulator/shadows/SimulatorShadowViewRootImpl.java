package ee.schimke.composeai.simulator.shadows;

import android.os.Handler;
import android.os.Looper;
import java.util.List;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowViewRootImpl;
import org.robolectric.util.ReflectionHelpers;

/**
 * Robolectric's {@code ViewRootImpl}, with {@code ViewTreeObserver.registerFrameCommitCallback}
 * kept. The framework hands the callbacks registered since the last frame to the hardware
 * renderer, which calls back once that frame is committed; Robolectric's native renderer never
 * does (from API 36), so a wait on a commit, as screenshot code and tests do after a draw, never
 * ended. Here they are taken from the tree observer the same way and run on the main thread once
 * the frame being drawn is done. Added to the shadow map by name, by {@code SimulatorSandbox}.
 */
@Implements(className = "android.view.ViewRootImpl", isInAndroidSdk = false)
public class SimulatorShadowViewRootImpl extends ShadowViewRootImpl {

  @Implementation
  protected void addFrameCommitCallbackIfNeeded() {
    // Through reflection: ViewRootImpl is hidden, outside the android.jar this compiles against.
    Object root = ReflectionHelpers.getField(this, "realObject");
    Object attachInfo = ReflectionHelpers.getField(root, "mAttachInfo");
    Object observer = ReflectionHelpers.getField(attachInfo, "mTreeObserver");
    List<Runnable> callbacks =
        ReflectionHelpers.callInstanceMethod(observer, "captureFrameCommitCallbacks");
    if (callbacks == null || callbacks.isEmpty()) return;
    new Handler(Looper.getMainLooper())
        .post(
            () -> {
              for (Runnable callback : callbacks) callback.run();
            });
  }
}
