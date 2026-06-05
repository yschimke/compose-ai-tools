package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.LottieOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.util.Base64
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM coverage (no Skiko render) for the Lottie timeline override's wire path: the
 * `overrides.lottie.progress` set by a `renderNow` survives the base64 `overrides=` payload token
 * that [RenderSpec.parseFromPayload] decodes, so the desktop `RenderEngine` can provide it as
 * `LocalLottieProgress`. Also pins the "null is a no-op" contract — an absent override leaves
 * `spec.overrides` (and hence the authored progress) untouched.
 */
class LottieOverrideDecodeTest {

  private val json = Json { encodeDefaults = false }

  private fun payloadWith(overrides: PreviewOverrides): String {
    val encoded =
      Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
          json.encodeToString(PreviewOverrides.serializer(), overrides).toByteArray(Charsets.UTF_8)
        )
    return "className=;functionName=spin.json;kind=LOTTIE;assetPath=lottie/spin.json;overrides=$encoded"
  }

  @Test
  fun progressOverrideSurvivesPayloadRoundTrip() {
    val spec =
      RenderSpec.parseFromPayload(payloadWith(PreviewOverrides(lottie = LottieOverride(0.42f))))
    assertEquals(0.42f, spec.overrides?.lottie?.progress)
  }

  @Test
  fun absentLottieOverrideDecodesToNull() {
    // An overrides bag carrying only an unrelated field must leave `lottie` null — the no-op case
    // that keeps the composable's authored progress.
    val spec = RenderSpec.parseFromPayload(payloadWith(PreviewOverrides(fontScale = 1.3f)))
    assertNull(spec.overrides?.lottie)
  }

  @Test
  fun noOverridesTokenLeavesOverridesNull() {
    val spec =
      RenderSpec.parseFromPayload(
        "className=;functionName=spin.json;kind=LOTTIE;assetPath=lottie/spin.json"
      )
    assertNull(spec.overrides?.lottie)
  }
}
