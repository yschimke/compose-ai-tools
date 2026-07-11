package com.example.designcatalogwearm3

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import ee.schimke.composeai.preview.TlcScalingPreview

/**
 * A **Wear Card** authored exactly as a real `TransformingLazyColumn` item — real
 * `transformedHeight(this, spec)` + `SurfaceTransformation(spec)`, no preview-specific modifiers and
 * no parameters. [TlcScalingHost] supplies the genuine `TransformingLazyColumnItemScope` + spec.
 */
@Composable
private fun HeartRateCard() =
  TlcScalingHost { spec ->
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
 * The card at rest: nothing provides [LocalTlcScalingLevel], so it draws centred at full scale — a
 * plain preview of the component, unchanged.
 */
@Preview(
  name = "Large Round",
  device = "id:wearos_large_round",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
@TlcScalingPreview
@Composable
fun CardScalingRest() = HeartRateCard()

/**
 * The **same** card, wrapped in [ProvideTlcScalingLevel] so the ambient override scrolls it up into
 * the real scaling zone — scaled + faded, with no change to [HeartRateCard]'s code. Demonstrates the
 * composition-local override the sweep/GIF and a live viewer both drive.
 */
@Preview(
  name = "Large Round",
  device = "id:wearos_large_round",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
@Composable
fun CardScalingScaled() = ProvideTlcScalingLevel(0.7f) { HeartRateCard() }
