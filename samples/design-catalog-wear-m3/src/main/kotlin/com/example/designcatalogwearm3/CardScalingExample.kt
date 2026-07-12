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

private const val WEAR_LARGE_ROUND = "id:wearos_large_round"

/**
 * The card at the three key list positions — the same [HeartRateCard] code each time, only the
 * ambient [TlcScalePosition] differs:
 * - [CardScalingMiddle] — centred, full scale (the default; nothing provided).
 * - [CardScalingStarting] — scrolled into the top scaling zone, starting to shrink + fade.
 * - [CardScalingEdge] — ridden to the top edge, high scale.
 */
@Preview(name = "Large Round", device = WEAR_LARGE_ROUND, showBackground = true, backgroundColor = 0xFF000000)
@TlcScalingPreview
@Composable
fun CardScalingMiddle() = ProvideTlcScalePosition(TlcScalePosition.Middle) { HeartRateCard() }

@Preview(name = "Large Round", device = WEAR_LARGE_ROUND, showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun CardScalingStarting() = ProvideTlcScalePosition(TlcScalePosition.Starting) { HeartRateCard() }

@Preview(name = "Large Round", device = WEAR_LARGE_ROUND, showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun CardScalingEdge() = ProvideTlcScalePosition(TlcScalePosition.Edge) { HeartRateCard() }

/**
 * The same card, exposed to the GIF render harness (`TlcScalingGifRenderTest`) so it can drive the
 * scroll fraction across a whole animation without any preview scaffolding. Not a `@Preview`.
 */
@Composable
fun HeartRateCardAt(fraction: Float) = ProvideTlcScrollFraction(fraction) { HeartRateCard() }
