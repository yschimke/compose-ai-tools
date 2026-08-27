package ee.schimke.composeai.renderer

import org.robolectric.annotation.ClassName
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.reflector.Direct
import org.robolectric.util.reflector.ForType
import org.robolectric.util.reflector.Reflector.reflector
import org.robolectric.util.reflector.WithType

/**
 * Keeps native render-thread animations on the **simulated** clock, by drawing each frame with its
 * `FrameInfo` timestamps left exactly as the framework populated them.
 *
 * ## What it undoes, and why that is safe
 *
 * Robolectric 4.17-beta-3 added a `syncAndDrawFrame(FrameInfo)` shadow to
 * [org.robolectric.shadows.ShadowNativeHardwareRenderer] that rewrites the Java-populated frame
 * timestamps into the host's monotonic domain before handing them to hwui, and rewrites them back
 * afterwards:
 * ```java
 * long offset = System.nanoTime() - ShadowPausedSystemClock.uptimeNanos();
 * adjustFrameInfoTimes(frameInfo.frameInfo, offset);   // INTENDED_VSYNC, VSYNC, ANIMATION_START, …
 * ```
 *
 * It exists to stop the native `JankTracker` logging spam on SDK 37+. The first version of it
 * deliberately excluded `VSYNC` "to avoid visual regressions in screenshot tests"; a later commit
 * made the whole set unconditional and deleted the `robolectric.enableFrameInfoVsyncOffsetFix`
 * opt-out, so there is no build-level switch left (issue #4549 records the bisect).
 *
 * Under a paused clock that `offset` is not a small skew — it is "however long this JVM has been
 * up", and it **grows between frames**, because real time passes while simulated time only moves
 * when the session or the render loop advances it. Native animations that Material's
 * `RippleDrawable` runs on the render thread (`RenderNodeAnimator`) take their start and frame
 * times from those fields, so they end up paced by wall-clock host time. A held session that
 * advances the clock one frame budget at a time then buys essentially no animation progress: 400 ms
 * of simulated time renders a fraction of a ripple's enter animation, and the two pixel oracles
 * over the live lane's press feedback (`AndroidRippleFrameTest`, `LivePressRippleTest`) go red —
 * the same user-visible failure #4159 existed to fix.
 *
 * Skipping the translation restores 4.17-beta-2 behaviour **for this sandbox only**. What is given
 * up is the jank-log-spam suppression, which is log noise in a render daemon that never asserts on
 * frame durations; what is bought back is animation timing that tracks the clock the session
 * actually controls. `pressedRippleKeepsAnimatingAcrossHeldFrames` and
 * `aLiveClickPaintsPressFeedback` are the acceptance check — both fail on beta-3/beta-4 without
 * this shadow and pass with it.
 *
 * ## How the override reaches a hidden type
 *
 * `android.graphics.FrameInfo` is `@hide`, so it is not on the compile classpath and the parent's
 * `syncAndDrawFrame(FrameInfo)` cannot be overridden in the ordinary Kotlin sense. Robolectric's
 * [ClassName] (for the shadow method) and [WithType] (for the reflector) name the parameter type as
 * a string instead, which is the supported way to shadow a signature whose types only exist inside
 * the sandbox. The class still **extends** [org.robolectric.shadows.ShadowNativeHardwareRenderer]
 * so every other member of that shadow — the whole native-method surface — keeps working; only the
 * frame-timestamp translation is replaced.
 *
 * The `@Implements` here declares no `shadowPicker`, and it does not need one: a shadow registered
 * through `@Config(shadows = …)` (which is what `SandboxHoldingRunner.getExtraShadows` feeds, and
 * equally what the generated `robolectric.properties` `shadows=` line feeds the render lane) lands
 * in `ShadowMap`'s overridden set, which is consulted before the default shadow's picker — so this
 * replaces `ShadowNativeHardwareRenderer` for `android.graphics.HardwareRenderer` outright rather
 * than racing it. `callNativeMethodsByDefault` is repeated from the parent because those attributes
 * are read off the shadow class that wins, not inherited.
 *
 * ## Scope
 *
 * **Both Robolectric lanes**, because both drive many frames against one paused clock and both were
 * measurably non-deterministic without it.
 *
 * - The **daemon's** held/live lane registers it through
 *   [`SandboxHoldingRunner`][ee.schimke.composeai.daemon.SandboxHoldingRunner] (issue #4159): a
 *   pressed ripple barely moved across a filmed press and a live click painted no press feedback at
 *   all.
 * - The **static render** lane registers it through the generated `robolectric.properties`
 *   (`GenerateRobolectricPropertiesTask`), for `@InteractionPreview` and every other capture that
 *   samples a component mid-animation. Measured on `:samples:design-catalog-wear-m3`'s
 *   `SwitchButtonOn`: three renders of one commit produced three different `.apng`s, 28–31 of the
 *   114 frames differing by up to 42 per channel, all of them inside the two scripted press
 *   windows. With this shadow registered the same three renders are byte-identical (issue #4578).
 *
 * A one-shot still capture draws a single frame and cannot notice, so registering it lane-wide
 * costs those renders nothing. Leaving the consumer's own `robolectric.properties` alone keeps a
 * consumer's ordinary Robolectric tests on stock upstream behaviour.
 *
 * If a later Robolectric restores an opt-out or fixes the paused-clock case, this class and its
 * registration are what to delete — the daemon's two ripple pixel oracles
 * (`AndroidRippleFrameTest`, `LivePressRippleTest`) and a repeated `SwitchButtonOn` render are the
 * checks that it is safe to.
 */
@Implements(
  className = "android.graphics.HardwareRenderer",
  minSdk = 29,
  isInAndroidSdk = false,
  callNativeMethodsByDefault = true,
)
class ShadowPausedClockHardwareRenderer : org.robolectric.shadows.ShadowNativeHardwareRenderer() {

  @RealObject private var realRenderer: Any? = null

  /**
   * Draws the frame with the framework's own timestamps — no host-clock translation either way.
   *
   * The parent's version brackets this same reflector call with `adjustFrameInfoTimes(+offset)` /
   * `adjustFrameInfoTimes(-offset)`; dropping both leaves `frameInfo` untouched, which is what
   * 4.17-beta-2 handed to hwui.
   */
  @Implementation
  protected fun syncAndDrawFrame(@ClassName("android.graphics.FrameInfo") frameInfo: Any?): Int =
    reflector(HardwareRendererReflector::class.java, realRenderer).syncAndDrawFrame(frameInfo)

  /** Direct (un-shadowed) access to the real `HardwareRenderer.syncAndDrawFrame`. */
  @ForType(className = "android.graphics.HardwareRenderer")
  internal interface HardwareRendererReflector {
    @Direct fun syncAndDrawFrame(@WithType("android.graphics.FrameInfo") frameInfo: Any?): Int
  }
}
