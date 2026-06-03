package com.example.samplexrspatial

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.ContentEdge
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialBox
import androidx.xr.compose.subspace.SpatialColumn
import androidx.xr.compose.subspace.SpatialCurvedRow
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SpatialRow
import androidx.xr.compose.subspace.layout.SpatialAlignment
import androidx.xr.compose.subspace.layout.SpatialArrangement
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.subspace.semantics.testTag
import ee.schimke.composeai.preview.XrSubspacePreview

/**
 * `@XrSubspacePreview`s that showcase the main spatial-Compose layout patterns from
 * `androidx.xr.compose`. Each composes a real `Subspace { … }` arrangement under the fake XR runtime
 * driven by `composePreviewRenderXr`, which recovers the panel poses/sizes into a `scene.json` (and
 * one `<testTag>.png` texture per panel) for the offline `xr-composite` compositor to bake.
 *
 * Real Android XR apps don't `@Preview` the spatial arrangement itself — only the 2D panel content
 * — because a plain `@Preview` of a `Subspace` captures an empty Home-Space frame (the subspace body
 * is skipped when spatialization is off). These previews exist precisely to exercise the offline
 * subspace render path, so the spatial layout is genuinely measured and laid out rather than
 * fallen-back-to-2D.
 *
 * Invariant: **every `SpatialPanel` carries a unique `SubspaceModifier.testTag("<id>")`.** The tag
 * becomes the panel id in `scene.json` and the `<id>.png` texture filename; a duplicate or missing
 * tag breaks the scene. The 2D bodies live in [SpatialContent.kt][NowPlayingPanel] and friends.
 */

/**
 * `SpatialRow` — three side-by-side panels (library | now-playing | queue) with even spacing. The
 * flat multi-panel layout you'd reach for first when spreading an app across a workspace.
 */
@XrSubspacePreview
@Composable
fun SpatialRowPreview() {
  Subspace {
    SpatialRow(horizontalArrangement = SpatialArrangement.spacedBy(24.dp)) {
      SpatialPanel(SubspaceModifier.testTag("row-library").width(360.dp).height(440.dp)) {
        LibraryGrid()
      }
      SpatialPanel(SubspaceModifier.testTag("row-now-playing").width(480.dp).height(440.dp)) {
        NowPlayingPanel()
      }
      SpatialPanel(SubspaceModifier.testTag("row-queue").width(360.dp).height(440.dp)) { QueueList() }
    }
  }
}

/**
 * `SpatialCurvedRow(curveRadius = 825.dp)` — the signature "cockpit" arc. Five panels wrap around
 * the viewer along a fixed-radius curve, the most visually distinctive spatial arrangement.
 */
@XrSubspacePreview
@Composable
fun SpatialCurvedRowPreview() {
  Subspace {
    SpatialCurvedRow(
      curveRadius = 825.dp,
      horizontalArrangement = SpatialArrangement.spacedBy(16.dp),
    ) {
      SpatialPanel(SubspaceModifier.testTag("curve-library").width(300.dp).height(380.dp)) {
        LibraryGrid()
      }
      SpatialPanel(SubspaceModifier.testTag("curve-search").width(300.dp).height(380.dp)) {
        SearchBar()
      }
      SpatialPanel(SubspaceModifier.testTag("curve-now-playing").width(360.dp).height(380.dp)) {
        NowPlayingPanel()
      }
      SpatialPanel(SubspaceModifier.testTag("curve-queue").width(300.dp).height(380.dp)) {
        QueueList()
      }
      SpatialPanel(SubspaceModifier.testTag("curve-equalizer").width(300.dp).height(380.dp)) {
        EqualizerCard()
      }
    }
  }
}

/**
 * Depth — a foreground panel raised toward the viewer in front of a background panel, via a
 * `SpatialBox` whose children carry different z `offset`s. Shows how spatial panels stack along the
 * depth axis rather than only across a plane.
 */
@XrSubspacePreview
@Composable
fun SpatialDepthPreview() {
  Subspace {
    SpatialBox {
      SpatialPanel(
        SubspaceModifier.testTag("depth-background")
          .width(640.dp)
          .height(420.dp)
          .offset(z = (-120).dp)
      ) {
        LibraryGrid()
      }
      SpatialPanel(
        SubspaceModifier.testTag("depth-foreground")
          .width(420.dp)
          .height(180.dp)
          .offset(z = 120.dp)
      ) {
        AlbumArtCard()
      }
    }
  }
}

/**
 * `Orbiter`s on two edges of a main `SpatialPanel` — a search/title strip floating at
 * [ContentEdge.Top] and the transport controls at [ContentEdge.Bottom]. Orbiters host 2D control
 * strips anchored to a panel's edges in Full Space.
 */
@XrSubspacePreview
@Composable
fun OrbiterPanelPreview() {
  Subspace {
    SpatialPanel(SubspaceModifier.testTag("orbiter-main").width(560.dp).height(360.dp)) {
      NowPlayingPanel()
      Orbiter(position = ContentEdge.Top) { SearchBar() }
      Orbiter(position = ContentEdge.Bottom) { TransportControls() }
    }
  }
}

/**
 * Master-detail — an asymmetric `SpatialRow` using `weight` so a wide master panel takes twice the
 * width of the narrow detail panel. The common list+inspector split, placed spatially.
 */
@XrSubspacePreview
@Composable
fun MasterDetailPreview() {
  Subspace {
    SpatialRow(horizontalArrangement = SpatialArrangement.spacedBy(24.dp)) {
      SpatialPanel(SubspaceModifier.testTag("md-master").weight(2f).height(460.dp)) { QueueList() }
      SpatialPanel(SubspaceModifier.testTag("md-detail").weight(1f).height(460.dp)) {
        AlbumArtCard()
      }
    }
  }
}

/**
 * Nested layout — a `SpatialColumn` of stacked controls nested inside a `SpatialRow` beside a wide
 * now-playing panel. Demonstrates composing the row/column primitives into a richer arrangement.
 */
@XrSubspacePreview
@Composable
fun NestedColumnInRowPreview() {
  Subspace {
    SpatialRow(
      verticalAlignment = SpatialAlignment.CenterVertically,
      horizontalArrangement = SpatialArrangement.spacedBy(24.dp),
    ) {
      SpatialPanel(SubspaceModifier.testTag("nested-now-playing").width(520.dp).height(420.dp)) {
        NowPlayingPanel()
      }
      SpatialColumn(verticalArrangement = SpatialArrangement.spacedBy(16.dp)) {
        SpatialPanel(SubspaceModifier.testTag("nested-search").width(320.dp).height(120.dp)) {
          SearchBar()
        }
        SpatialPanel(SubspaceModifier.testTag("nested-equalizer").width(320.dp).height(280.dp)) {
          EqualizerCard()
        }
      }
    }
  }
}
