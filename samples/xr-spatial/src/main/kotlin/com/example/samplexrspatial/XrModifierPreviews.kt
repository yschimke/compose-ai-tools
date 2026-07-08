// rotateToLookAtUser was marked @ExperimentalRotateToLookAtUserApi (opt-in, error-level) in
// androidx.xr.compose 1.0.0-alpha15; this sample intentionally demonstrates it, so opt in
// file-wide rather than at each call site.
@file:OptIn(ExperimentalRotateToLookAtUserApi::class)

package com.example.samplexrspatial

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialBox
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SpatialRow
import androidx.xr.compose.subspace.layout.ExperimentalRotateToLookAtUserApi
import androidx.xr.compose.subspace.layout.SpatialArrangement
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.absoluteOffset
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.rotate
import androidx.xr.compose.subspace.layout.rotateToLookAtUser
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.subspace.semantics.testTag
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import ee.schimke.composeai.preview.XrSubspacePreview

/**
 * `@XrSubspacePreview`s that isolate the **pose-affecting `SubspaceModifier`s** — `rotate(...)`
 * (its Euler, axis-angle, and quaternion forms) and `offset` / `absoluteOffset` — so the offline
 * pose recovery + `xr-composite` bake can be checked against a *known* transform.
 *
 * Why these exist: the showcase previews exercise position/rotation only indirectly (a
 * `SpatialCurvedRow` rotates panels along its arc, a `SpatialBox` z-`offset`s them for depth).
 * These drive a single modifier with explicit values, so a wrong axis, a flipped handedness, or a
 * missing perspective foreshortening in the compositor is obvious in the bake (and asserted in
 * `SubspaceModifierPoseTest`). Each `SpatialPanel` carries a unique `testTag`.
 *
 * `rotateToLookAtUser` (the "face the viewer" / billboard modifier) is here too: it sources the
 * user's head pose from an ARCore `ArDevice`, which the offline render path now supplies via a fake
 * perception runtime seeded with a viewer head pose in front of the panels (see `:renderer-xr`'s
 * `FakeXrHeadPose`, and `SubspaceModifierPoseTest` for the empirical recovery). Side panels visibly
 * angle inward to face the viewer.
 */

/**
 * One tagged [SpatialPanel] with the dark [ReferencePanel] body — the unit these previews repeat.
 */
@Composable
private fun RefPanel(tag: String, modifier: SubspaceModifier, title: String) {
  SpatialPanel(SubspaceModifier.testTag(tag).then(modifier)) { ReferencePanel(title) }
}

/**
 * A fanned row of panels rotated about the vertical (Y / yaw) axis from −40° to +40°. The faces
 * turn away from the camera by a known angle, so the bake reveals whether the compositor's
 * **perspective** is right: the angled panels should foreshorten (narrow) with the cosine of their
 * yaw, and the ones turned toward/away should show near/far edges at different scales. A
 * flat/orthographic projection would show no foreshortening; a wrong FOV would over- or
 * under-shorten them.
 */
@XrSubspacePreview
@Composable
fun RotatedYawRowPreview() {
  Subspace {
    SpatialRow(horizontalArrangement = SpatialArrangement.spacedBy(16.dp)) {
      listOf(-40, -20, 0, 20, 40).forEachIndexed { i, yaw ->
        RefPanel(
          tag = "yaw-$i",
          modifier = SubspaceModifier.width(300.dp).height(380.dp).rotate(0f, yaw.toFloat(), 0f),
          title = "Yaw ${yaw}°",
        )
      }
    }
  }
}

/**
 * The three `rotate(...)` overloads side by side, each a 30° turn about the vertical axis: Euler
 * `rotate(pitch, yaw, roll)`, axis-angle `rotate(Vector3.Up, 30f)`, and `rotate(Quaternion)`. All
 * three should resolve to the *same* recovered pose — the bake (and the pose test) confirm the
 * compositor and recorder treat the forms identically.
 */
@XrSubspacePreview
@Composable
fun RotationFormsPreview() {
  Subspace {
    SpatialRow(horizontalArrangement = SpatialArrangement.spacedBy(24.dp)) {
      RefPanel(
        tag = "rot-euler",
        modifier = SubspaceModifier.width(320.dp).height(360.dp).rotate(0f, 30f, 0f),
        title = "Euler 30°",
      )
      RefPanel(
        tag = "rot-axis",
        modifier = SubspaceModifier.width(320.dp).height(360.dp).rotate(Vector3.Up, 30f),
        title = "Axis-angle",
      )
      RefPanel(
        tag = "rot-quat",
        modifier =
          SubspaceModifier.width(320.dp)
            .height(360.dp)
            .rotate(Quaternion.fromEulerAngles(0f, 30f, 0f)),
        title = "Quaternion",
      )
    }
  }
}

/**
 * Position modifiers: a centre panel at the layout origin with three more pushed to known places by
 * `offset` (x and y) and one by `absoluteOffset`. A `SpatialBox` stacks them at a common origin so
 * each panel's recovered translation is exactly its offset — the cleanest check that translation
 * survives recovery and bakes where expected.
 */
@XrSubspacePreview
@Composable
fun OffsetModifiersPreview() {
  Subspace {
    SpatialBox {
      RefPanel(
        tag = "off-center",
        modifier = SubspaceModifier.width(300.dp).height(200.dp),
        title = "Origin",
      )
      RefPanel(
        tag = "off-right",
        modifier = SubspaceModifier.width(300.dp).height(200.dp).offset(x = 360.dp),
        title = "offset x +360",
      )
      RefPanel(
        tag = "off-up",
        modifier = SubspaceModifier.width(300.dp).height(200.dp).offset(y = 280.dp),
        title = "offset y +280",
      )
      RefPanel(
        tag = "off-abs-left",
        modifier =
          SubspaceModifier.width(300.dp)
            .height(200.dp)
            .absoluteOffset(x = (-360).dp, y = (-280).dp),
        title = "absoluteOffset",
      )
    }
  }
}

/**
 * The "face the viewer" / billboard modifier: three panels offset left / centre / right, each
 * `.rotateToLookAtUser()`. The modifier rotates each panel to face the user's head pose. Offline
 * the render path seeds that head pose in front of the panels (see `:renderer-xr`'s
 * `FakeXrHeadPose`), so on bake the centre panel faces head-on while the side panels visibly
 * **angle inward** toward the viewer — the billboard behaviour, recovered offline.
 * `SubspaceModifierPoseTest` asserts the recovered rotations (centre ≈ identity, sides turned about
 * the vertical Y axis, never the old 180° flip).
 */
@XrSubspacePreview
@Composable
fun RotateToLookAtUserPreview() {
  Subspace {
    SpatialBox {
      RefPanel(
        tag = "look-left",
        modifier =
          SubspaceModifier.width(300.dp).height(380.dp).offset(x = (-520).dp).rotateToLookAtUser(),
        title = "Look at user (L)",
      )
      RefPanel(
        tag = "look-center",
        modifier = SubspaceModifier.width(300.dp).height(380.dp).rotateToLookAtUser(),
        title = "Look at user (C)",
      )
      RefPanel(
        tag = "look-right",
        modifier =
          SubspaceModifier.width(300.dp).height(380.dp).offset(x = 520.dp).rotateToLookAtUser(),
        title = "Look at user (R)",
      )
    }
  }
}
