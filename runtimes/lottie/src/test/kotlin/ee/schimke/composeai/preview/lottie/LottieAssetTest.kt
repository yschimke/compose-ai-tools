package ee.schimke.composeai.preview.lottie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LottieAssetTest {
  @Test
  fun loadsAssetFromClasspath() {
    val json = loadLottieAsset("lottie/test-anim.json")
    assertTrue("expected Lottie JSON content", json.contains("\"v\""))
  }

  @Test
  fun toleratesLeadingSlash() {
    assertEquals(
      loadLottieAsset("lottie/test-anim.json"),
      loadLottieAsset("/lottie/test-anim.json"),
    )
  }

  @Test
  fun missingAssetThrowsHelpfully() {
    val ex =
      assertThrows(IllegalArgumentException::class.java) {
        loadLottieAsset("lottie/does-not-exist.json")
      }
    assertTrue(
      "message should point at src/main/resources",
      ex.message!!.contains("src/main/resources"),
    )
  }
}
