package ee.schimke.composeai.guidelines

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.guidelines.protocol.GuidelineEvidenceNeedV1
import org.junit.Test

class WidgetFontScaleCapTest {
  private fun need(fontScale: Double?) =
    GuidelineEvidenceNeedV1.Builder(GuidelineEvidenceNeedV1.KIND_RENDER)
      .apply {
        this.fontScale = fontScale
        reason = "text at a large font scale"
      }
      .build()

  private val wearWidget =
    PreviewSubject(
      previewId = "w",
      surface = GuidelineSurfaces.WIDGET,
      profile = GuidelineSurfaces.WEAR_WIDGETS_PROFILE,
    )
  private val launcherWidget = PreviewSubject(previewId = "l", surface = GuidelineSurfaces.WIDGET)
  private val screen = PreviewSubject(previewId = "s", surface = GuidelineSurfaces.SCREEN)

  @Test
  fun `a widget's largest font scale is its host's`() {
    assertThat(
        GuidelineSurfaces.maxFontScale(
          GuidelineSurfaces.WIDGET,
          GuidelineSurfaces.WEAR_WIDGETS_PROFILE,
        )
      )
      .isEqualTo(1.24)
    assertThat(GuidelineSurfaces.maxFontScale(GuidelineSurfaces.WIDGET, null)).isEqualTo(1.5)
    assertThat(GuidelineSurfaces.maxFontScale(GuidelineSurfaces.SCREEN, null)).isNull()
  }

  @Test
  fun `a render need past a widget's maximum is held to it, and nothing else changes`() {
    // The triage's large-font offer asks for 1.5x; a model may ask for 2x.
    assertThat(need(1.5).cappedFor(wearWidget).fontScale).isEqualTo(1.24)
    assertThat(need(2.0).cappedFor(launcherWidget).fontScale).isEqualTo(1.5)
    assertThat(need(2.0).cappedFor(wearWidget).reason).isEqualTo("text at a large font scale")
  }

  @Test
  fun `needs within the maximum, without a font scale, or for a screen pass unchanged`() {
    val within = need(1.1)
    assertThat(within.cappedFor(wearWidget)).isSameInstanceAs(within)
    val none = need(null)
    assertThat(none.cappedFor(wearWidget)).isSameInstanceAs(none)
    val large = need(2.0)
    assertThat(large.cappedFor(screen)).isSameInstanceAs(large)
  }
}
