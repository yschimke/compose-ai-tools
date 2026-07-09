package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.robolectric.annotation.Implements

/**
 * Pins the `@Implements` contract of [ShadowSdkGestureInputManager] and the SDK gesture-action
 * mapping it relies on.
 *
 * Like [ShadowAmbientLifecycleObserver], the shadow must declare `@Implements(className = …)` (the
 * FQN string form) rather than the class-literal form: the target `SdkGestureInputManagerImpl` is an
 * internal wear-compose-material3 type, and the class-literal form stores a deferred `Class<?>` in
 * the annotation proxy whose resolution throws `TypeNotPresentException` on any classpath lacking the
 * gesture AAR — deep inside Robolectric's sandbox bootstrap, before [SandboxHoldingRunner]'s gate can
 * intercept. Touching `value()` here makes a regression to the class-literal form fail loudly in this
 * test instead of mid-render.
 */
class ShadowSdkGestureInputManagerTest {

  @Test
  fun `shadow targets SdkGestureInputManagerImpl by className`() {
    val annotation =
      ShadowSdkGestureInputManager::class.java.getAnnotation(Implements::class.java)
    assertNotNull("ShadowSdkGestureInputManager must carry @Implements", annotation)
    assertEquals(
      "androidx.wear.compose.material3.onehandedgesture.SdkGestureInputManagerImpl",
      annotation!!.className,
    )
    // Must default to void.class so the annotation proxy never resolves a deferred Class<?> ref.
    assertEquals(Void.TYPE, annotation.value.java)
  }

  @Test
  fun `sdk gesture-action constants match the library mapping`() {
    // Mirrors the library's internal `toSdkGestureAction`: primary → 1, dismiss → 2. If wear-compose
    // ever renumbers these, the shadow's detection wire names go stale — pin them here.
    assertEquals(1, GestureStateController.SDK_ACTION_PRIMARY)
    assertEquals(2, GestureStateController.SDK_ACTION_DISMISS)
  }
}
