package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.protocol.UiMode
import org.junit.Test

class MatrixAxesTest {
  @Test
  fun `single axis expands to one cell per value`() {
    val cells = MatrixAxes.expand(null, null, uiModes = listOf("light", "dark"), null)
    assertThat(cells).hasSize(2)
    assertThat(cells.map { it.uiMode }).containsExactly("light", "dark").inOrder()
  }

  @Test
  fun `cross product preserves device then locale then uiMode then fontScale order`() {
    val cells =
      MatrixAxes.expand(
        devices = listOf("id:a", "id:b"),
        locales = listOf("en", "ar"),
        uiModes = null,
        fontScales = null,
      )
    // device is the outer loop: a/en, a/ar, b/en, b/ar.
    assertThat(cells.map { it.device to it.locale })
      .containsExactly("id:a" to "en", "id:a" to "ar", "id:b" to "en", "id:b" to "ar")
      .inOrder()
  }

  @Test
  fun `cellCount multiplies set axes and treats unset as one`() {
    assertThat(MatrixAxes.cellCount(listOf("a", "b"), null, listOf("light", "dark"), null))
      .isEqualTo(4)
    assertThat(MatrixAxes.cellCount(null, null, null, null)).isEqualTo(1)
  }

  @Test
  fun `toOverrides maps axis values to typed overrides`() {
    val overrides =
      MatrixCell(device = "id:pixel_5", locale = "ar", uiMode = "dark", fontScale = 2.0f)
        .toOverrides()
    assertThat(overrides.device).isEqualTo("id:pixel_5")
    assertThat(overrides.localeTag).isEqualTo("ar")
    assertThat(overrides.uiMode).isEqualTo(UiMode.DARK)
    assertThat(overrides.fontScale).isEqualTo(2.0f)
  }

  @Test
  fun `toOverrides rejects an unknown uiMode`() {
    val error = runCatching { MatrixCell(uiMode = "sepia").toOverrides() }.exceptionOrNull()
    assertThat(error).isNotNull()
    assertThat(error!!).hasMessageThat().contains("uiMode")
  }

  @Test
  fun `label is compact and falls back to default`() {
    assertThat(MatrixCell(device = "id:pixel_5", uiMode = "dark", fontScale = 2.0f).label)
      .isEqualTo("id:pixel_5 · dark · 2.0x")
    assertThat(MatrixCell().label).isEqualTo("default")
  }

  @Test
  fun `overridesJson echoes the wire keys render_preview accepts`() {
    val json =
      MatrixCell(device = "id:a", locale = "ar", uiMode = "dark", fontScale = 1.5f).overridesJson()
    assertThat(json.keys).containsExactly("device", "localeTag", "uiMode", "fontScale")
  }
}
