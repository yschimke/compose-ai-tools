package com.example.samplexrspatial

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialColumn
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.subspace.semantics.testTag
import androidx.xr.compose.testing.onSubspaceNodeWithTag
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Recovers the *real* subspace layout — panel poses and sizes computed by `androidx.xr.compose` —
 * offline, with no headset, no OpenXR, and no SceneCore native code.
 *
 * This is the proof-of-concept behind the "harvest poses → project to 2D" path implemented by
 * `SubspaceSceneRecorder` (see its KDoc). It also doubles as the **canary** for the alpha XR
 * testing stack: if a future `androidx.xr.*:…-testing` release changes how the fake runtime is
 * discovered, how spatial UI is gated, or the subspace semantics surface, this test fails loudly
 * instead of the capability silently regressing.
 *
 * How it works — entirely public API plus one Robolectric shadow:
 * 1. `FakeSceneRuntimeFactory` / `FakeRenderingRuntimeFactory` (from `scenecore-testing`) are
 *    registered for `ServiceLoader` via `src/test/resources/META-INF/services/…`, so
 *    `Session.create(activity)` yields a fake, JVM-only XR session.
 * 2. `Subspace` only takes its spatial path when
 *    `packageManager.hasSystemFeature("android.software.xr.api.spatial")` is true. Robolectric
 *    reports `false`, so we shadow it on with [ShadowPackageManager.setSystemFeature]. The session
 *    and `LocalComposeXrOwners` then auto-wire from the activity.
 * 3. The panel transforms are read from the public spatial-semantics tree
 *    (`onSubspaceNodeWithTag(tag).fetchSemanticsNode().poseInRoot` / `.size`).
 *
 * Poses/sizes are in dp. A `SpatialColumn` of a 200dp-tall panel over a 160dp-tall panel resolves
 * to a 360dp-tall column with the top panel above the bottom one — which is what we assert.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubspaceLayoutPoseTest {

  // v2 rule API (StandardTestDispatcher) is not on the compat compile classpath yet;
  // suppress until the floor moves up. See renderer-android RobolectricRenderTest.
  @Suppress("DEPRECATION") @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun recoversSubspacePanelPosesOffline() {
    // Gate that selects `Subspace`'s spatial path over its 2D fallback.
    val pm = ApplicationProvider.getApplicationContext<Context>().packageManager
    shadowOf(pm).setSystemFeature("android.software.xr.api.spatial", true)

    var spatialUiEnabled = false
    rule.setContent {
      val caps = LocalSpatialCapabilities.current
      SideEffect { spatialUiEnabled = caps.isSpatialUiEnabled }
      Subspace {
        SpatialColumn(SubspaceModifier.testTag("column")) {
          SpatialPanel(SubspaceModifier.testTag("top").width(560.dp).height(200.dp)) {
            NowPlayingPanel()
          }
          SpatialPanel(SubspaceModifier.testTag("bottom").width(560.dp).height(160.dp)) {
            TransportControls()
          }
        }
      }
    }
    rule.waitForIdle()

    // The fake session reached the composition and spatialization is on.
    assertThat(spatialUiEnabled).isTrue()

    val column = rule.onSubspaceNodeWithTag("column").fetchSemanticsNode("no 'column' node")
    val top = rule.onSubspaceNodeWithTag("top").fetchSemanticsNode("no 'top' node")
    val bottom = rule.onSubspaceNodeWithTag("bottom").fetchSemanticsNode("no 'bottom' node")

    // Sizes are the layout output (dp). Panels keep their requested size; the column is their sum.
    assertThat(top.size.width).isEqualTo(560)
    assertThat(top.size.height).isEqualTo(200)
    assertThat(bottom.size.height).isEqualTo(160)
    assertThat(column.size.height).isEqualTo(360)

    // Real 3D placement: the column stacks the top panel above the bottom one (+y is up).
    assertThat(top.poseInRoot.translation.y).isGreaterThan(bottom.poseInRoot.translation.y)

    println(
      buildString {
        appendLine("Recovered subspace layout (offline, fake runtime):")
        for (n in listOf("column" to column, "top" to top, "bottom" to bottom)) {
          val p = n.second.poseInRoot.translation
          val s = n.second.size
          appendLine(
            "  ${n.first}: poseInRoot=(${p.x}, ${p.y}, ${p.z}) size=(${s.width} x ${s.height})"
          )
        }
      }
    )
  }
}
