package ee.schimke.composeai.daemon

import android.content.Context
import android.view.View
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric shadow for wear-compose-material3's internal `SdkGestureInputManagerImpl` — the
 * on-device bridge the one-handed-gesture framework talks to.
 *
 * On a real Pixel Watch this class forwards to `com.google.wear`'s `GestureInputManager`; off-device
 * `isAvailable(...)` is `false`, so `GestureRegistry.invalidate()` bails immediately and the whole
 * gesture pipeline is inert — which is why an app's raw `Modifier.oneHandedGesture` /
 * `OneHandedGestureIndicator` shows nothing in a preview. This shadow replaces the SDK bridge so the
 * **real** framework pipeline runs under the render, for **any** app, with no source changes:
 *
 * - [isAvailable] returns [GestureStateController.detectionArmed] (armed by [GestureOverrideExtension]
 *   while a gesture override is applied). When armed, `GestureRegistry.invalidate()` proceeds: it
 *   subscribes to gesture actions and, after the framework's indicator delay, invokes the
 *   registered `onGestureAvailable` callback. The app uses that callback to activate its
 *   `OneHandedGestureIndicatorState`, so its own indicator shows without any reporting seam. (A
 *   captured frame must advance past that delay; the daemon does so via `advanceTimeMillis`.)
 * - [subscribeToSdkGestureAction] records the detected action + captures the framework's `onGesture`
 *   callback into [GestureStateController], so the gesture is **surfaced** in `compose/gestures` and
 *   an `overrides.gestures.invoke` can **fire** the real handler.
 *
 * Registered through [SandboxHoldingRunner.getExtraShadows], gated on the wear-compose gesture API
 * being on the classpath — same shape as `ShadowAmbientLifecycleObserver`. The `@Implements(className
 * = …)` string form (not the class-literal form) avoids the deferred-annotation resolution that
 * throws on classpaths lacking the gesture AAR (issue #1244).
 */
@Implements(
  className = "androidx.wear.compose.material3.onehandedgesture.SdkGestureInputManagerImpl"
)
class ShadowSdkGestureInputManager {

  @Implementation
  fun isAvailable(@Suppress("UNUSED_PARAMETER") context: Context): Boolean =
    GestureStateController.detectionArmed()

  @Implementation
  fun subscribeToSdkGestureAction(
    @Suppress("UNUSED_PARAMETER") view: View,
    sdkGestureAction: Int,
    @Suppress("UNUSED_PARAMETER") enabledInAmbient: Boolean,
    onGesture: (Int) -> Unit,
  ) {
    GestureStateController.recordDetected(sdkGestureAction) { onGesture(sdkGestureAction) }
  }

  @Implementation
  fun unsubscribeFromSdkGestureAction(
    @Suppress("UNUSED_PARAMETER") view: View,
    sdkGestureAction: Int,
  ) {
    GestureStateController.clearDetected(sdkGestureAction)
  }

  @Implementation
  fun shouldShowIndicator(
    @Suppress("UNUSED_PARAMETER") key: String,
    @Suppress("UNUSED_PARAMETER") sdkGestureAction: Int,
    @Suppress("UNUSED_PARAMETER") isOverlay: Boolean,
  ): Boolean = GestureStateController.detectionArmed()

  @Implementation
  fun notifyGestureConsumed(
    @Suppress("UNUSED_PARAMETER") key: String,
    @Suppress("UNUSED_PARAMETER") sdkGestureAction: Int,
  ) {}

  @Implementation
  fun notifyIndicatorShown(
    @Suppress("UNUSED_PARAMETER") key: String,
    @Suppress("UNUSED_PARAMETER") sdkGestureAction: Int,
  ) {}
}
