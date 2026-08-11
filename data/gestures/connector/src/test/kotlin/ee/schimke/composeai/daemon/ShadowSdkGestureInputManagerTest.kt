package ee.schimke.composeai.daemon

import android.view.View
import ee.schimke.composeai.daemon.protocol.GestureKindOverride
import kotlin.Function1
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
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
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowSdkGestureInputManager::class])
class ShadowSdkGestureInputManagerTest {

  @After
  fun resetController() {
    GestureStateController.resetForNewSession()
  }

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

  @Test
  fun `shadow makes Wear gestures available and dispatches through the framework callback`() {
    val shadow = ShadowSdkGestureInputManager()
    val context = RuntimeEnvironment.getApplication()
    val view = View(context)
    var dispatchedAction: Int? = null

    assertFalse(shadow.isAvailable(context))
    GestureStateController.armDetection(true)
    assertTrue(shadow.isAvailable(context))
    assertTrue(
      shadow.shouldShowIndicator(
        key = "samplewear:test",
        sdkGestureAction = GestureStateController.SDK_ACTION_PRIMARY,
        isOverlay = false,
      )
    )

    shadow.subscribeToSdkGestureAction(
      view = view,
      sdkGestureAction = GestureStateController.SDK_ACTION_PRIMARY,
      enabledInAmbient = false,
    ) { dispatchedAction = it }

    assertEquals(listOf("primary"), GestureStateController.snapshot().detected)
    assertEquals(1, GestureStateController.invoke(GestureKindOverride.PRIMARY))
    assertEquals(GestureStateController.SDK_ACTION_PRIMARY, dispatchedAction)

    shadow.unsubscribeFromSdkGestureAction(view, GestureStateController.SDK_ACTION_PRIMARY)
    assertTrue(GestureStateController.snapshot().detected.isEmpty())
  }

  @Test
  fun `Robolectric applies the shadow to Wear Compose's real SDK bridge`() {
    val bridgeClass =
      Class.forName("androidx.wear.compose.material3.onehandedgesture.SdkGestureInputManagerImpl")
    val bridge = bridgeClass.getDeclaredConstructor().newInstance()
    val isAvailable = bridgeClass.getDeclaredMethod("isAvailable", android.content.Context::class.java)
    val context = RuntimeEnvironment.getApplication()

    assertEquals(false, isAvailable.invoke(bridge, context))
    GestureStateController.armDetection(true)
    assertEquals(true, isAvailable.invoke(bridge, context))

    val view = View(context)
    val subscribe =
      bridgeClass.getDeclaredMethod(
        "subscribeToSdkGestureAction",
        View::class.java,
        Int::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
        Function1::class.java,
      )
    subscribe.invoke(
      bridge,
      view,
      GestureStateController.SDK_ACTION_PRIMARY,
      false,
      { _: Int -> Unit },
    )
    assertEquals(listOf("primary"), GestureStateController.snapshot().detected)
  }
}
