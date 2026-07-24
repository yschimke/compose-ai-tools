package ee.schimke.composeai.renderer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isLottieAnimatedOutput] must distinguish the still baseline from the animated APNG companion by
 * the discovery-derived stem, not a blanket `_animated.png` suffix — otherwise an asset literally
 * named `*_animated.json` renders its required still as the animated companion.
 */
class LottieDispatchTest {

  private fun out(name: String) = File("/tmp/renders/$name")

  @Test
  fun `still frame of an ordinary asset is not the animated companion`() {
    assertFalse(isLottieAnimatedOutput("lottie/spin.json", out("lottie__lottie_spin.png")))
  }

  @Test
  fun `animated companion of an ordinary asset is detected`() {
    assertTrue(isLottieAnimatedOutput("lottie/spin.json", out("lottie__lottie_spin_animated.png")))
  }

  @Test
  fun `still frame of an asset named _animated is not the companion`() {
    // Regression: `foo_animated.json` sanitises to a stem ending in `_animated`; its still must
    // still dispatch to the single-frame path, not the APNG companion.
    assertFalse(
      isLottieAnimatedOutput("lottie/foo_animated.json", out("lottie__lottie_foo_animated.png"))
    )
  }

  @Test
  fun `animated companion of an asset named _animated is detected`() {
    assertTrue(
      isLottieAnimatedOutput(
        "lottie/foo_animated.json",
        out("lottie__lottie_foo_animated_animated.png"),
      )
    )
  }

  @Test
  fun `dotlottie archive still and animated resolve correctly`() {
    assertFalse(isLottieAnimatedOutput("hero.lottie", out("lottie__hero.png")))
    assertTrue(isLottieAnimatedOutput("hero.lottie", out("lottie__hero_animated.png")))
  }
}
