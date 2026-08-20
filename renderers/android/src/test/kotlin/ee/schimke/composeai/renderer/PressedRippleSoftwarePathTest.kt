package ee.schimke.composeai.renderer

import android.graphics.drawable.RippleDrawable
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the hidden platform API `settlePressedRipple` leans on to make a pressed capture
 * reproducible.
 *
 * From Android 12 a `RippleDrawable` defaults to `STYLE_PATTERNED`, whose enter animation runs on
 * `RenderNodeAnimator` — the native RenderThread, which Robolectric does not have. So on this host
 * a pressed capture's ripple never advances at all, and which pixels a `pressed` sticker publishes
 * comes down to luck. `RippleDrawable.setForceSoftware(true)` moves it onto ordinary
 * `ValueAnimator`s, which the main looper drives and `ShadowLooper.idleFor` can settle.
 *
 * The method is `@UnsupportedAppUsage`, so it is reached by reflection and could disappear under a
 * `compileSdk` or Robolectric bump. If it does, the render does not break — it silently goes back
 * to publishing whatever the shard layout happened to produce, which is exactly the regression
 * `WearFocusedPressPixelTest` exists to catch and exactly the kind that survives a green build. So
 * the disappearance is failed here, at the layer that owns the workaround, rather than left for a
 * catalog's pixel test to notice.
 *
 * Runs at the SDK this repo's own renders pin (see `RobolectricHost.ANDROID_SDK`), because the
 * question is about the framework the render actually loads, not about the compile classpath.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PressedRippleSoftwarePathTest {

  @Test
  fun `RippleDrawable still exposes setForceSoftware`() {
    val method =
      RippleDrawable::class
        .java
        .getDeclaredMethod("setForceSoftware", Boolean::class.javaPrimitiveType)
    assertNotNull(
      "RippleDrawable.setForceSoftware(boolean) is gone — settlePressedRipple can no longer move " +
        "the ripple off the RenderNodeAnimator path, so pressed captures are unsettled again.",
      method,
    )
    assertTrue(
      "expected an instance method",
      !java.lang.reflect.Modifier.isStatic(method.modifiers),
    )
  }

  @Test
  fun `forcing software is accepted by a real RippleDrawable`() {
    val ripple =
      RippleDrawable(
        android.content.res.ColorStateList.valueOf(android.graphics.Color.RED),
        /* content = */ null,
        /* mask = */ null,
      )
    val method =
      RippleDrawable::class
        .java
        .getDeclaredMethod("setForceSoftware", Boolean::class.javaPrimitiveType)
        .apply { isAccessible = true }
    method.invoke(ripple, true)

    val field =
      RippleDrawable::class.java.getDeclaredField("mForceSoftware").apply { isAccessible = true }
    assertTrue(
      "setForceSoftware(true) did not reach mForceSoftware — the field the drawable reads when " +
        "deciding between enterHardware and enterSoftware has been renamed or re-homed.",
      field.getBoolean(ripple),
    )
  }
}
