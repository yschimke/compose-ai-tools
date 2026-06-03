package com.example.samplexrspatial

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.OrbiterAnchorPoint
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.width

/**
 * The canonical "Full Space" XR spatial layout — a `SpatialPanel` floating in a `Subspace`, with a
 * transport-control `Orbiter` anchored to its top edge.
 *
 * This is the real on-device code, kept here so the sample carries the genuine spatial-layout shape
 * (not just its 2D fallback). It is deliberately **not** annotated `@Preview`:
 *
 * > "A subspace is rendered only when spatialization is enabled. In Home Space or on non-XR
 * > devices, any code within that subspace is ignored."
 *
 * The offline renderer has no Jetpack XR `Session`, so a `@Preview` of this composable would
 * capture an empty frame — the `Subspace` body is skipped entirely. That is correct XR behaviour,
 * not a renderer bug, but a blank PNG is a poor sample artifact. Instead, preview the panel's 2D
 * content directly ([NowPlayingPanelPreview]) and the spatial affordances' 2D fallbacks
 * ([OrbiterControlsPreview], [SpatialElevationPreview]) — those capture exactly what the panel and
 * orbiter show, and are what Android Studio's `@Preview` renders for this UI in Home Space too.
 *
 * See docs/design/XR_SPATIAL_PREVIEW.md for the full rendering model.
 */
@Composable
fun SubspaceXrLayout() {
  Subspace {
    SpatialPanel(SubspaceModifier.width(560.dp).height(360.dp)) {
      NowPlayingPanel()
      Orbiter(OrbiterAnchorPoint.Top) { TransportControls() }
    }
  }
}
