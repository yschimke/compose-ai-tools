package com.example.samplexrspatial

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialColumn
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SpatialRow
import androidx.xr.compose.subspace.layout.SpatialArrangement
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.subspace.semantics.testTag
import ee.schimke.composeai.preview.XrSubspacePreview

/**
 * A close reproduction of the Android XR **"spatial panels"** reference shot
 * (developer.android.com/develop/xr — the 3×2 grid of dark "Top Left … Bottom Right" panels in a
 * warm room). Built as the real `Subspace` layout it depicts: a [SpatialColumn] of three
 * [SpatialRow]s, each holding two [SpatialPanel]s, evenly spaced. The 2D body is [ReferencePanel],
 * whose surface colour (`#0F0C13`), near-white title, and muted eyebrow are sampled straight from
 * the reference; the `xr-composite` compositor supplies the rounded corners, soft shadow, and warm
 * room backdrop, so the bake should land close to the original.
 *
 * It's also the densest layout in the sample set — six panels in a grid — so it doubles as a stress
 * test for the multi-panel pose recovery and the compositor's framing.
 */
@XrSubspacePreview
@Composable
fun SpatialPanelGridPreview() {
  val cell = SubspaceModifier.width(480.dp).height(320.dp)
  Subspace {
    SpatialColumn(verticalArrangement = SpatialArrangement.spacedBy(24.dp)) {
      SpatialRow(horizontalArrangement = SpatialArrangement.spacedBy(24.dp)) {
        SpatialPanel(SubspaceModifier.testTag("grid-top-left").then(cell)) {
          ReferencePanel("Top Left")
        }
        SpatialPanel(SubspaceModifier.testTag("grid-top-right").then(cell)) {
          ReferencePanel("Top Right")
        }
      }
      SpatialRow(horizontalArrangement = SpatialArrangement.spacedBy(24.dp)) {
        SpatialPanel(SubspaceModifier.testTag("grid-middle-left").then(cell)) {
          ReferencePanel("Middle Left")
        }
        SpatialPanel(SubspaceModifier.testTag("grid-middle-right").then(cell)) {
          ReferencePanel("Middle Right")
        }
      }
      SpatialRow(horizontalArrangement = SpatialArrangement.spacedBy(24.dp)) {
        SpatialPanel(SubspaceModifier.testTag("grid-bottom-left").then(cell)) {
          ReferencePanel("Bottom Left")
        }
        SpatialPanel(SubspaceModifier.testTag("grid-bottom-right").then(cell)) {
          ReferencePanel("Bottom Right")
        }
      }
    }
  }
}
