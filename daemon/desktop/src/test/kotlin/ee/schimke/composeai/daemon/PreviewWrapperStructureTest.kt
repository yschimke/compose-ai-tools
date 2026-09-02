package ee.schimke.composeai.daemon

import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewWrapperStructureTest {
  @Test
  fun `upstream Remote Compose wrapper is structural without connector discovery`() {
    assertTrue(
      isStructuralWrapperFqn("androidx.compose.remote.tooling.preview.RemotePreviewWrapper")
    )
  }
}
