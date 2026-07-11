package com.example.designcatalogwearm3

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.transformedHeight
import ee.schimke.composeai.preview.TlcScalingPreview

/**
 * What authoring a TLC-scaling preview for a **Wear Card** looks like: **one** `@Preview` function.
 * The body is exactly the code you'd write for a real `TransformingLazyColumn` item — real
 * `transformedHeight(this, spec)` + `SurfaceTransformation(spec)`, no preview-specific modifiers.
 * [TlcScalingHost] supplies the genuine `TransformingLazyColumnItemScope` + spec; the
 * [TlcScaleLevels] `@PreviewParameter` sweeps the scroll position, so the plugin renders one frame
 * per level (full → most scaled). `@TlcScalingPreview` declares the sweep + its GIF.
 */
@Preview(
  name = "Large Round",
  device = "id:wearos_large_round",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
@TlcScalingPreview
@Composable
fun CardScalingSweep(@PreviewParameter(TlcScaleLevels::class) level: Float) =
  TlcScalingHost(level) { spec ->
    Card(
      onClick = {},
      modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
      transformation = SurfaceTransformation(spec),
    ) {
      Column {
        Text("Heart rate")
        Text("72 bpm")
      }
    }
  }

/**
 * The same, for a **TitleCard** — again just the normal list-item code. Kept as a second worked
 * example so the sweep is exercised on a titled card too.
 */
@Preview(
  name = "Large Round",
  device = "id:wearos_large_round",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
@TlcScalingPreview
@Composable
fun TitleCardScalingSweep(@PreviewParameter(TlcScaleLevels::class) level: Float) =
  TlcScalingHost(level) { spec ->
    TitleCard(
      onClick = {},
      title = { Text("Activity") },
      subtitle = { Text("72 bpm") },
      modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
      transformation = SurfaceTransformation(spec),
    )
  }
