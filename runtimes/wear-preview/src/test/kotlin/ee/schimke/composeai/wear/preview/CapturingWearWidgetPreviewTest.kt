@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.wear.preview

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.core.RendererVersion
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.tooling.preview.SquircleLargeWidgetPreviewParams
import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.data.render.IrSidecarChannel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders [CapturingWearWidgetPreview] through **both** replay lanes against the pinned Glance
 * Wear.
 *
 * The View lane is the load-bearing one for issue #5420: it is the only path that calls upstream
 * `WearWidgetPreview`, and Glance Wear `1.0.0-alpha18` changed that overload's JVM signature
 * (inserting `useSafeFallbackRendererVersion` before `content`). Kotlin recompiles cleanly across
 * such a change, so only *executing* the call catches it — as a `NoSuchMethodError` here rather
 * than in every consumer's widget render.
 *
 * Pinned to Robolectric SDK 35, like the repo's other Robolectric suites: SDK 36 needs JDK 21+ and
 * the render path stays on JDK 17.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CapturingWearWidgetPreviewTest {

  @get:Rule val rule = createComposeRule()

  private val params: WearWidgetParams = SquircleLargeWidgetPreviewParams().values.first()

  @After
  fun clearPreviewId() {
    IrSidecarChannel.setCurrentPreviewId(null)
  }

  private fun render(player: WearWidgetPreviewPlayer, useSafeFallbackRendererVersion: Boolean) {
    rule.setContent {
      CapturingWearWidgetPreviewOnLane(
        params = params,
        background = WearWidgetBrush,
        useSafeFallbackRendererVersion = useSafeFallbackRendererVersion,
        player = player,
      ) {
        RemoteBox(modifier = RemoteModifier.fillMaxSize())
      }
    }
    rule.waitForIdle()
  }

  @Test
  fun `the View lane renders through upstream WearWidgetPreview at the safe fallback version`() {
    render(WearWidgetPreviewPlayer.VIEW, useSafeFallbackRendererVersion = true)
  }

  @Test
  fun `the View lane renders through upstream WearWidgetPreview at the max renderer version`() {
    render(WearWidgetPreviewPlayer.VIEW, useSafeFallbackRendererVersion = false)
  }

  @Test
  fun `the CMP lane renders and offers the captured document as the rc sidecar`() {
    val previewId = "capturing-wear-widget-preview-test"
    IrSidecarChannel.setCurrentPreviewId(previewId)

    render(WearWidgetPreviewPlayer.CMP, useSafeFallbackRendererVersion = true)

    val capture = IrSidecarChannel.consume(previewId)
    assertThat(capture).isNotNull()
    assertThat(capture!!.format).isEqualTo(IrSidecarChannel.FORMAT_REMOTECOMPOSE)
    assertThat(capture.bytes).isNotEmpty()
  }

  @Test
  fun `withRendererVersion swaps only the renderer version`() {
    for (version in
      listOf(RendererVersion.SAFE_FALLBACK_VERSION, RendererVersion.MAX_RENDERER_VERSION)) {
      val updated = params.withRendererVersion(version)
      assertThat(updated.rendererVersion).isEqualTo(version)
      assertThat(updated.instanceId).isEqualTo(params.instanceId)
      assertThat(updated.containerType).isEqualTo(params.containerType)
      assertThat(updated.widthDp).isEqualTo(params.widthDp)
      assertThat(updated.heightDp).isEqualTo(params.heightDp)
      assertThat(updated.horizontalPaddingDp).isEqualTo(params.horizontalPaddingDp)
      assertThat(updated.verticalPaddingDp).isEqualTo(params.verticalPaddingDp)
      assertThat(updated.cornerRadiusDp).isEqualTo(params.cornerRadiusDp)
    }
  }
}
