package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderSpecFromInfoTest {

  @Test
  fun `preview id is preserved without params`() {
    val spec =
      renderSpecFromInfo(
        PreviewInfoDto(
          id = "preview-id",
          className = "com.example.FooKt",
          methodName = "Foo",
          params = null,
        )
      )

    assertEquals("preview-id", spec.previewId)
  }

  @Test
  fun `preview id is preserved with params`() {
    val spec =
      renderSpecFromInfo(
        PreviewInfoDto(
          id = "preview-id",
          className = "com.example.FooKt",
          methodName = "Foo",
          params = PreviewParamsDto(widthDp = 200, density = 1.5f),
        )
      )

    assertEquals("preview-id", spec.previewId)
    assertEquals(300, spec.widthPx)
  }
}
