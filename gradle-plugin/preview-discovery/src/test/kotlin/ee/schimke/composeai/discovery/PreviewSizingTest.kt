package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [PreviewSizing.resolveForRender] — the plugin's AS-parity fixed-vs-wrap decision per axis. */
class PreviewSizingTest {
  @Test
  fun `resolveForRender wraps both axes when no hints are given`() {
    val spec =
      PreviewSizing.resolveForRender(
        device = null,
        widthDp = null,
        heightDp = null,
        showSystemUi = false,
      )
    assertThat(spec.wrapWidth).isTrue()
    assertThat(spec.wrapHeight).isTrue()
    // Wrap-axes get the phone-shaped 400×800 dp sandbox so fillMaxSize
    // composables render into a reasonable viewport before the PNG crop.
    assertThat(spec.widthDp).isEqualTo(PreviewSizing.SANDBOX_WIDTH_DP)
    assertThat(spec.heightDp).isEqualTo(PreviewSizing.SANDBOX_HEIGHT_DP)
  }

  @Test
  fun `resolveForRender keeps device frame when device is set`() {
    val spec =
      PreviewSizing.resolveForRender(
        device = "id:pixel_6",
        widthDp = null,
        heightDp = null,
        showSystemUi = false,
      )
    assertThat(spec.wrapWidth).isFalse()
    assertThat(spec.wrapHeight).isFalse()
    // Pixel 6 = 411×914 dp after the upstream per-device density refresh.
    assertThat(spec.widthDp).isEqualTo(411)
    assertThat(spec.heightDp).isEqualTo(914)
  }

  @Test
  fun `resolveForRender keeps full frame when showSystemUi is true`() {
    val spec =
      PreviewSizing.resolveForRender(
        device = null,
        widthDp = null,
        heightDp = null,
        showSystemUi = true,
      )
    assertThat(spec.wrapWidth).isFalse()
    assertThat(spec.wrapHeight).isFalse()
    assertThat(spec.widthDp).isEqualTo(400)
    assertThat(spec.heightDp).isEqualTo(800)
  }

  @Test
  fun `resolveForRender wraps only the axis the user left unset`() {
    val spec =
      PreviewSizing.resolveForRender(
        device = null,
        widthDp = 240,
        heightDp = null,
        showSystemUi = false,
      )
    assertThat(spec.wrapWidth).isFalse()
    assertThat(spec.wrapHeight).isTrue()
    assertThat(spec.widthDp).isEqualTo(240)
    assertThat(spec.heightDp).isEqualTo(PreviewSizing.SANDBOX_HEIGHT_DP)
  }

  @Test
  fun `resolveForRender explicit dims override device frame`() {
    val spec =
      PreviewSizing.resolveForRender(
        device = "id:pixel_6",
        widthDp = 200,
        heightDp = 300,
        showSystemUi = false,
      )
    assertThat(spec.wrapWidth).isFalse()
    assertThat(spec.wrapHeight).isFalse()
    assertThat(spec.widthDp).isEqualTo(200)
    assertThat(spec.heightDp).isEqualTo(300)
  }

  @Test
  fun `resolveForRender narrows the wrap sandbox without fixing the axis`() {
    // What a Wear module's device-less previews get from `retargetWearStickers`: the 227dp watch
    // screen as the measuring bound, both axes still wrapped so the capture crops to content.
    val spec =
      PreviewSizing.resolveForRender(
        device = null,
        widthDp = null,
        heightDp = null,
        showSystemUi = false,
        wrapSandboxWidthDp = 227,
        wrapSandboxHeightDp = 227,
      )
    assertThat(spec.wrapWidth).isTrue()
    assertThat(spec.wrapHeight).isTrue()
    assertThat(spec.widthDp).isEqualTo(227)
    assertThat(spec.heightDp).isEqualTo(227)
  }

  @Test
  fun `resolveForRender lets an explicit dp win over the wrap sandbox`() {
    val spec =
      PreviewSizing.resolveForRender(
        device = null,
        widthDp = 120,
        heightDp = null,
        showSystemUi = false,
        wrapSandboxWidthDp = 227,
        wrapSandboxHeightDp = 227,
      )
    assertThat(spec.wrapWidth).isFalse()
    assertThat(spec.widthDp).isEqualTo(120)
    assertThat(spec.wrapHeight).isTrue()
    assertThat(spec.heightDp).isEqualTo(227)
  }

  @Test
  fun `resolveForRender ignores the wrap sandbox on a device frame`() {
    val spec =
      PreviewSizing.resolveForRender(
        device = "id:wearos_large_round",
        widthDp = null,
        heightDp = null,
        showSystemUi = false,
        wrapSandboxWidthDp = 999,
        wrapSandboxHeightDp = 999,
      )
    assertThat(spec.wrapWidth).isFalse()
    assertThat(spec.wrapHeight).isFalse()
    assertThat(spec.widthDp).isEqualTo(227)
    assertThat(spec.heightDp).isEqualTo(227)
  }
}
