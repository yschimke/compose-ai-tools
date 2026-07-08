package com.example.samplexrspatial

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialBox
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.rotate
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.subspace.semantics.testTag
import androidx.xr.compose.testing.onSubspaceNodeWithTag
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Empirically pins down how the **pose-affecting `SubspaceModifier`s** behave under the offline
 * fake XR runtime — the same recovery path `SubspaceSceneRecorder` drives for
 * `@XrSubspacePreview`s.
 *
 * - `rotate(...)` (Euler / axis-angle / quaternion) is recovered faithfully as the panel's
 *   `poseInRoot.rotation`, and the three forms agree. So rotation flows end-to-end into
 *   `scene.json` and the `xr-composite` bake (it's what [RotatedYawRowPreview] /
 *   [RotationFormsPreview] exercise).
 * - `rotateToLookAtUser()` (the "face the viewer" / billboard modifier) **also works offline** now
 *   — its recovery has its own test, [RotateToLookAtUserPoseTest], because it needs a seeded head
 *   pose and a PAUSED looper. A centred panel ends up facing the viewer and side panels turn
 *   inward, which is what [RotateToLookAtUserPreview] bakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubspaceModifierPoseTest {

  @Suppress("DEPRECATION") @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private fun enableSpatial() {
    val pm = ApplicationProvider.getApplicationContext<Context>().packageManager
    shadowOf(pm).setSystemFeature("android.software.xr.api.spatial", true)
  }

  /** A unit quaternion's rotation angle in degrees: `2·acos(|w|)`. */
  private fun angleDeg(x: Float, y: Float, z: Float, w: Float): Double {
    val vlen = sqrt((x * x + y * y + z * z).toDouble())
    return Math.toDegrees(2.0 * Math.atan2(vlen, abs(w).toDouble()))
  }

  @Test
  fun rotateModifierRecoveredAsQuaternion() {
    enableSpatial()
    rule.setContent {
      Subspace {
        SpatialBox {
          SpatialPanel(
            SubspaceModifier.testTag("yaw30").width(300.dp).height(200.dp).rotate(0f, 30f, 0f)
          ) {
            ReferencePanel("Yaw 30")
          }
        }
      }
    }
    rule.waitForIdle()

    val r = rule.onSubspaceNodeWithTag("yaw30").fetchSemanticsNode("no 'yaw30'").poseInRoot.rotation
    println(
      "rotate(0,30,0) -> quat=(${r.x}, ${r.y}, ${r.z}, ${r.w}) angle=${angleDeg(r.x, r.y, r.z, r.w)}°"
    )

    // A ~30° turn is recovered (not identity), about the vertical (Y) axis.
    assertThat(angleDeg(r.x, r.y, r.z, r.w)).isWithin(2.0).of(30.0)
    assertThat(abs(r.y)).isGreaterThan(abs(r.x))
    assertThat(abs(r.y)).isGreaterThan(abs(r.z))
  }

  @Test
  fun rotateOverloadsAgree() {
    enableSpatial()
    rule.setContent {
      Subspace {
        SpatialBox {
          SpatialPanel(
            SubspaceModifier.testTag("euler").width(300.dp).height(200.dp).rotate(0f, 30f, 0f)
          ) {
            ReferencePanel("e")
          }
          SpatialPanel(
            SubspaceModifier.testTag("axis").width(300.dp).height(200.dp).rotate(Vector3.Up, 30f)
          ) {
            ReferencePanel("a")
          }
          SpatialPanel(
            SubspaceModifier.testTag("quat")
              .width(300.dp)
              .height(200.dp)
              .rotate(Quaternion.fromEulerAngles(0f, 30f, 0f))
          ) {
            ReferencePanel("q")
          }
        }
      }
    }
    rule.waitForIdle()

    val e = rule.onSubspaceNodeWithTag("euler").fetchSemanticsNode("no 'euler'").poseInRoot.rotation
    val a = rule.onSubspaceNodeWithTag("axis").fetchSemanticsNode("no 'axis'").poseInRoot.rotation
    val q = rule.onSubspaceNodeWithTag("quat").fetchSemanticsNode("no 'quat'").poseInRoot.rotation
    // All three 30°-about-Y forms resolve to the same recovered rotation.
    assertThat(a.y).isWithin(0.01f).of(e.y)
    assertThat(q.y).isWithin(0.01f).of(e.y)
    assertThat(a.w).isWithin(0.01f).of(e.w)
    assertThat(q.w).isWithin(0.01f).of(e.w)
  }
}
