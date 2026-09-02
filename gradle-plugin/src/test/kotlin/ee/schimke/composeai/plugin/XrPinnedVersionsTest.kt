package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class XrPinnedVersionsTest {
  @Test
  fun `bakes independent XR component pins into the plugin`() {
    assertThat(XrFakeVersions.renderer).matches("\\d+\\.\\d+\\.\\d+.*")
    assertThat(XrFakeVersions.composite).matches("\\d+\\.\\d+\\.\\d+.*")
  }
}
