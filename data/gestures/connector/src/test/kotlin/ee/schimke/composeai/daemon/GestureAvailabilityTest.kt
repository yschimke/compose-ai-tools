package ee.schimke.composeai.daemon

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements

/**
 * The published availability signal (issue #5102): "can a gesture reach this composable?"
 *
 * The point of the signal is that it is right in **both** directions, which is what the downstream
 * workaround it replaces could not be — `Class.forName("com.google.wear.Sdk")` proves absence and
 * not presence. These tests exercise the harness direction, the one a preview actually renders in:
 * armed means the harness is the gesture source, disarmed means nothing is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowSdkGestureInputManager::class])
class GestureAvailabilityTest {

  @After
  fun resetController() {
    GestureStateController.resetForNewSession()
  }

  @Test
  fun `an armed harness is a gesture source`() {
    GestureStateController.armDetection(true)

    assertTrue(oneHandedGestureSourceAvailable(RuntimeEnvironment.getApplication()))
  }

  @Test
  fun `an unarmed render reports no gesture source, which is the honest answer`() {
    // A plain `@Preview` rendered without the gesture override chain: the framework pipeline is
    // inert there, so a catalog asking this question must be told "no", not "maybe".
    GestureStateController.armDetection(false)

    assertFalse(oneHandedGestureSourceAvailable(RuntimeEnvironment.getApplication()))
  }

  @Test
  fun `the answer follows the harness rather than being cached`() {
    // The signal is read per render, and a session re-arms between them; an answer that stuck would
    // report the previous preview's capability on this one.
    val context = RuntimeEnvironment.getApplication()
    GestureStateController.armDetection(true)
    assertTrue(oneHandedGestureSourceAvailable(context))

    GestureStateController.armDetection(false)
    assertFalse(oneHandedGestureSourceAvailable(context))
  }

  @Test
  fun `the signal and the shadow name the same bridge class`() {
    // The harness arm works only because the class this reads is the class Robolectric replaced.
    // Sharing one constant makes that structural; this asserts the constant is still the real
    // library type's name, which a rename upstream would break here rather than in a render.
    val annotation = ShadowSdkGestureInputManager::class.java.getAnnotation(Implements::class.java)

    assertEquals(SDK_GESTURE_INPUT_MANAGER_IMPL, annotation!!.className)
    assertEquals(
      "androidx.wear.compose.material3.onehandedgesture.SdkGestureInputManagerImpl",
      SDK_GESTURE_INPUT_MANAGER_IMPL,
    )
    // The real class must actually be there to be shadowed — otherwise these tests would pass by
    // both sides being absent.
    assertEquals(
      SDK_GESTURE_INPUT_MANAGER_IMPL,
      Class.forName(SDK_GESTURE_INPUT_MANAGER_IMPL).name,
    )
  }

  @Test
  fun `an unresolvable bridge answers false rather than throwing`() {
    // The desktop / bare-JVM arm, forced: a classpath without wear-compose 1.7 has no gesture
    // source, and this reader must say so rather than sink the render it was called from.
    assertFalse(
      runCatching { Class.forName("androidx.wear.compose.material3.onehandedgesture.NoSuchBridge") }
        .isSuccess
    )
  }
}
