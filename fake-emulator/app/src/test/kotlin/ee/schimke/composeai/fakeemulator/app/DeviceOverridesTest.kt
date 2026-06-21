package ee.schimke.composeai.fakeemulator.app

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.protocol.Orientation
import ee.schimke.composeai.daemon.protocol.UiMode as ProtocolUiMode
import ee.schimke.composeai.fakeemulator.DeviceSettings
import ee.schimke.composeai.fakeemulator.RotationQuadrant
import ee.schimke.composeai.fakeemulator.UiMode as DeviceUiMode
import org.junit.Test

class DeviceOverridesTest {
  @Test
  fun `defaults map to an empty override`() {
    assertThat(DeviceSettings().toPreviewOverrides().isEmpty()).isTrue()
  }

  @Test
  fun `dark mode, font scale, density, size and locale map through`() {
    val overrides =
      DeviceSettings(
          uiMode = DeviceUiMode.DARK,
          fontScale = 1.3f,
          densityDpi = 320,
          widthPx = 1080,
          heightPx = 2400,
          localeTag = "fr-FR",
        )
        .toPreviewOverrides()
    assertThat(overrides.uiMode).isEqualTo(ProtocolUiMode.DARK)
    assertThat(overrides.fontScale).isEqualTo(1.3f)
    assertThat(overrides.density).isEqualTo(2.0f) // 320 / 160
    assertThat(overrides.widthPx).isEqualTo(1080)
    assertThat(overrides.heightPx).isEqualTo(2400)
    assertThat(overrides.localeTag).isEqualTo("fr-FR")
    assertThat(overrides.isEmpty()).isFalse()
  }

  @Test
  fun `landscape rotation maps to landscape orientation`() {
    val portrait = DeviceSettings(rotation = RotationQuadrant.PORTRAIT).toPreviewOverrides()
    assertThat(portrait.orientation).isEqualTo(Orientation.PORTRAIT)
    val landscape = DeviceSettings(rotation = RotationQuadrant.LANDSCAPE).toPreviewOverrides()
    assertThat(landscape.orientation).isEqualTo(Orientation.LANDSCAPE)
  }

  @Test
  fun `light mode maps to light`() {
    assertThat(DeviceSettings(uiMode = DeviceUiMode.LIGHT).toPreviewOverrides().uiMode)
      .isEqualTo(ProtocolUiMode.LIGHT)
  }
}
