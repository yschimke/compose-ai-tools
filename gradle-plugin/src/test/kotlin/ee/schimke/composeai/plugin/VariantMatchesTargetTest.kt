package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the contract of [AndroidPreviewSupport.variantMatchesTarget] — the rule that gates
 * `composePreview*` task registration on AGP variants and that picks the right
 * `${variant}RuntimeClasspath` configuration in the model builder. Both consumers must agree, so
 * the rule is unit-tested here rather than re-derived in each caller.
 *
 * Covers issue #1546: flavored application modules (`demoDebug`, `prodDebug`, …) have no literal
 * `debug` variant, so the previous strict equality check left them invisible to the CLI's module
 * discovery. The build-type suffix fallback fixes the default case without breaking exact
 * `--variant prodDebug` pinning.
 */
class VariantMatchesTargetTest {

  @Test
  fun `exact name matches`() {
    assertThat(AndroidPreviewSupport.variantMatchesTarget("debug", "debug")).isTrue()
    assertThat(AndroidPreviewSupport.variantMatchesTarget("demoDebug", "demoDebug")).isTrue()
    assertThat(AndroidPreviewSupport.variantMatchesTarget("release", "release")).isTrue()
  }

  @Test
  fun `build-type-only target matches flavored variants via suffix`() {
    assertThat(AndroidPreviewSupport.variantMatchesTarget("demoDebug", "debug")).isTrue()
    assertThat(AndroidPreviewSupport.variantMatchesTarget("prodDebug", "debug")).isTrue()
    assertThat(AndroidPreviewSupport.variantMatchesTarget("uatDebug", "debug")).isTrue()
    assertThat(AndroidPreviewSupport.variantMatchesTarget("demoRelease", "release")).isTrue()
  }

  @Test
  fun `build-type-only target does not match the wrong build type`() {
    assertThat(AndroidPreviewSupport.variantMatchesTarget("release", "debug")).isFalse()
    assertThat(AndroidPreviewSupport.variantMatchesTarget("demoRelease", "debug")).isFalse()
    assertThat(AndroidPreviewSupport.variantMatchesTarget("debug", "release")).isFalse()
  }

  @Test
  fun `flavored target does not match flavorless variant`() {
    // User explicitly asked for a flavor the module doesn't have — skip it rather than
    // silently pick the flavorless `debug` (which would render the wrong app config).
    assertThat(AndroidPreviewSupport.variantMatchesTarget("debug", "demoDebug")).isFalse()
    assertThat(AndroidPreviewSupport.variantMatchesTarget("release", "prodRelease")).isFalse()
  }

  @Test
  fun `flavored target only matches itself, not a sibling flavor`() {
    assertThat(AndroidPreviewSupport.variantMatchesTarget("prodDebug", "demoDebug")).isFalse()
  }

  @Test
  fun `empty target matches only an empty variant name`() {
    assertThat(AndroidPreviewSupport.variantMatchesTarget("debug", "")).isFalse()
    assertThat(AndroidPreviewSupport.variantMatchesTarget("", "")).isTrue()
  }
}
