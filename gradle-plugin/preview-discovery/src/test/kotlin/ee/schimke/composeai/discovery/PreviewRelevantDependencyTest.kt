package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PreviewRelevantDependencyTest {

  @Test
  fun `remote material project jars stay on the component inference classpath`() {
    assertThat(
        PreviewDiscovery.isPreviewRelevant(
          "/checkout/vendor/remote-material3/build/intermediates/full_jar/full.jar"
        )
      )
      .isTrue()
  }

  @Test
  fun `glimmer AAR classes stay on the component inference classpath`() {
    assertThat(PreviewDiscovery.isPreviewRelevant("androidx.xr.glimmer:glimmer:1.0.0-alpha19"))
      .isTrue()
  }

  @Test
  fun `unrelated project jars remain outside the scan`() {
    assertThat(PreviewDiscovery.isPreviewRelevant("/checkout/feature/build/libs/feature.jar"))
      .isFalse()
  }
}
