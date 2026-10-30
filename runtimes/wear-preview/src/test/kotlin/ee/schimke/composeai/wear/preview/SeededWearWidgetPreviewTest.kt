@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.wear.preview

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.tooling.preview.SquircleLargeWidgetPreviewParams
import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.RemoteComposeController
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import java.net.URLClassLoader
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A daemon render's `overrides.remoteCompose.namedValues` reach a [CapturingWearWidgetPreview]
 * (#401): the embedded lane seeds them into its player and tells the connector so, and every lane
 * that cannot seed them leaves them unreported, so the daemon's "named values were not applied"
 * warning still fires there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeededWearWidgetPreviewTest {

  @get:Rule val rule = createComposeRule()

  private val params: WearWidgetParams = SquircleLargeWidgetPreviewParams().values.first()

  @Before
  @After
  fun reset() {
    RemoteComposeController.resetForNewSession()
  }

  private fun seed(vararg values: Pair<String, RemoteNamedValue>) {
    RemoteComposeController.set(
      RemoteComposeOverride.Builder().apply { namedValues = mapOf(*values) }.build()
    )
  }

  private fun render(player: WearWidgetPreviewPlayer) {
    rule.setContent {
      CapturingWearWidgetPreviewOnLane(
        params = params,
        background = WearWidgetBrush,
        useSafeFallbackRendererVersion = true,
        player = player,
      ) {
        RemoteBox(modifier = RemoteModifier.fillMaxSize())
      }
    }
    rule.waitForIdle()
  }

  @Test
  fun `the connector and the seeded player on this classpath are available`() {
    assertThat(remoteComposeConnectorAvailable).isTrue()
    assertThat(seededEmbeddedPlayerAvailable).isTrue()
  }

  @Test
  fun `the pinned seeded entry point describes the real facade`() {
    val facade = Class.forName(SEEDED_PLAYER_FACADE)
    assertThat(
        declaresEntryPoint(
          facade,
          SEEDED_PLAYER_ENTRY_POINT_PARAMETERS,
          SEEDED_PLAYER_ENTRY_POINT,
        )
      )
      .isTrue()
  }

  @Test
  fun `an absent connector is unavailable rather than an error`() {
    assertThat(connectorControllerPresent(URLClassLoader(emptyArray(), null))).isFalse()
    assertThat(connectorControllerPresent(null)).isFalse()
    assertThat(seededPlayerEntryPointPresent(URLClassLoader(emptyArray(), null))).isFalse()
  }

  @Test
  fun `the embedded lane seeds named values and marks them consumed`() {
    seed(
      "rc.progress" to RemoteNamedValue.FloatValue(0.5f),
      "rc.primary" to RemoteNamedValue.ColorValue("#FF6F61"),
    )

    render(WearWidgetPreviewPlayer.ANDROIDX_EMBEDDED)

    assertThat(RemoteComposeController.namedValuesWereConsumed()).isTrue()
  }

  @Test
  fun `the embedded lane with nothing seeded reports nothing`() {
    render(WearWidgetPreviewPlayer.ANDROIDX_EMBEDDED)

    assertThat(RemoteComposeController.namedValuesWereConsumed()).isFalse()
  }

  @Test
  fun `the View lane cannot seed, so it leaves the values unreported`() {
    seed("rc.progress" to RemoteNamedValue.FloatValue(0.5f))

    render(WearWidgetPreviewPlayer.ANDROIDX_VIEW)

    assertThat(RemoteComposeController.namedValuesWereConsumed()).isFalse()
  }
}
