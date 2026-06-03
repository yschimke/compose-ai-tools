package com.example.samplexrspatial

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.OrbiterAnchorPoint
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.SpatialElevation
import androidx.xr.compose.spatial.SpatialElevationLevel

/**
 * `@Preview`s for the XR spatial sample, rendered by `:samples:xr-spatial:composePreviewRenderAll`.
 *
 * The offline renderer (and Android Studio's `@Preview`, and any phone) has no Jetpack XR
 * `Session`, so `LocalSpatialCapabilities.current` resolves to `SpatialCapabilities.NoCapabilities`
 * and `isSpatialUiEnabled` is `false`. Every `androidx.xr.compose.spatial` affordance below detects
 * that and renders its **2D fallback** — `Orbiter` lays its content out inline against the chosen
 * edge, `SpatialElevation` draws its content with no z-depth. That fallback is precisely what these
 * previews capture, and precisely what your XR app shows in Home Space. The true 3D placement only
 * appears in Full Space on an XR device; see [SubspaceXrLayout] for why the subspace path can't be
 * captured offline.
 */

// Shared device spec for the spatial previews: a wide landscape panel canvas at density 1.0, in the
// shape of a spatial content panel rather than a phone. dpi=160 keeps dp == px so the panel sizes
// below map straight to pixels.
internal const val SPATIAL_PANEL_DEVICE: String = "spec:width=1280,height=720,dpi=160"

/** The plain 2D panel content — the recommended unit to preview (no XR dependency at all). */
@Preview(name = "Panel · Content", device = SPATIAL_PANEL_DEVICE, showBackground = true)
@Composable
fun NowPlayingPanelPreview() {
  MaterialTheme {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
      NowPlayingPanel(Modifier.width(560.dp))
    }
  }
}

/**
 * `Orbiter` with a top-edge control strip. In Full Space the strip floats in front of the panel,
 * anchored to its top edge; in the 2D fallback captured here it renders inline above the panel
 * content. The composable is written exactly as it would be on-device — only the rendering differs.
 */
@Preview(name = "Orbiter · TopControls", device = SPATIAL_PANEL_DEVICE, showBackground = true)
@Composable
fun OrbiterControlsPreview() {
  MaterialTheme {
    Surface {
      Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Orbiter(OrbiterAnchorPoint.Top) { TransportControls() }
        NowPlayingPanel(Modifier.width(560.dp))
      }
    }
  }
}

/**
 * `SpatialElevation` raises its content toward the viewer in Full Space. In the 2D fallback the
 * content is drawn flat (no z-offset), so the capture shows the panel as an ordinary elevated card.
 */
@Preview(name = "SpatialElevation · Panel", device = SPATIAL_PANEL_DEVICE, showBackground = true)
@Composable
fun SpatialElevationPreview() {
  MaterialTheme {
    Surface {
      Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        SpatialElevation(SpatialElevationLevel.Level3) {
          NowPlayingPanel(Modifier.fillMaxWidth(0.6f))
        }
      }
    }
  }
}
