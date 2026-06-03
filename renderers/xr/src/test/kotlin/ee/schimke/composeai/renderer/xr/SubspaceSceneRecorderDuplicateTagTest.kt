package ee.schimke.composeai.renderer.xr

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialColumn
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.subspace.semantics.testTag
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** `recordAll` must reject duplicate panel tags rather than emit colliding ids / texture paths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubspaceSceneRecorderDuplicateTagTest {

  // v2 rule API (StandardTestDispatcher) is not on the compat compile classpath yet;
  // suppress until the floor moves up. See renderer-android RobolectricRenderTest.
  @Suppress("DEPRECATION")
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun recordAllRejectsDuplicateTags() {
    val pm = ApplicationProvider.getApplicationContext<Context>().packageManager
    shadowOf(pm).setSystemFeature(SubspaceSceneRecorder.XR_SPATIAL_FEATURE, true)

    rule.setContent {
      Subspace {
        SpatialColumn {
          SpatialPanel(SubspaceModifier.testTag("dup").width(320.dp).height(200.dp)) {
            Box(Modifier.fillMaxSize())
          }
          SpatialPanel(SubspaceModifier.testTag("dup").width(320.dp).height(160.dp)) {
            Box(Modifier.fillMaxSize())
          }
        }
      }
    }
    rule.waitForIdle()

    val error =
      runCatching { SubspaceSceneRecorder.recordAll(rule, previewId = "dup-test") }.exceptionOrNull()
    assertThat(error).isInstanceOf(IllegalStateException::class.java)
    assertThat(error).hasMessageThat().contains("dup")
  }
}
