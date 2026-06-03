package com.example.samplexrspatial

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialColumn
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.subspace.semantics.testTag
import ee.schimke.composeai.preview.XrSubspacePreview

/**
 * An `@XrSubspacePreview` — the real `Subspace` layout, rendered offline to a `scene.json` (panel
 * poses/sizes) by `composePreviewRenderXr`, for the VS Code 3D viewer. Unlike a plain `@Preview` of
 * a subspace (which captures an empty Home-Space frame), this is composed under a fake XR runtime so
 * the spatial layout is actually recovered. Each `SpatialPanel` carries a `testTag` — the tag
 * becomes the panel's id (and `<id>.png` texture path) in the scene.
 */
@XrSubspacePreview
@Composable
fun NowPlayingSpatialPreview() {
  Subspace {
    SpatialColumn {
      SpatialPanel(SubspaceModifier.testTag("now-playing").width(560.dp).height(320.dp)) {
        NowPlayingPanel()
      }
      SpatialPanel(SubspaceModifier.testTag("transport").width(560.dp).height(96.dp)) {
        TransportControls()
      }
    }
  }
}
