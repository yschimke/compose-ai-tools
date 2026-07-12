package com.example.designcatalogwearm3

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import ee.schimke.composeai.preview.ScrollMode
import ee.schimke.composeai.preview.ScrollingPreview
import ee.schimke.composeai.wear.preview.ProvideTlcScalePosition
import ee.schimke.composeai.wear.preview.TlcScalePosition
import ee.schimke.composeai.wear.preview.TlcScalingHost

private const val WEAR_LARGE_ROUND = "id:wearos_large_round"

/**
 * A **single** Wear Card shown with real `TransformingLazyColumn` item scaling — authored in the
 * normal list-item code (`transformedHeight(this, spec)` + `SurfaceTransformation(spec)`), with no
 * list. `TlcScalingHost` (`:wear-preview-runtime`) hosts it in a real single-item TLC and hands over
 * the genuine scope + spec; [ProvideTlcScalePosition] rides it up to the top [TlcScalePosition.Edge]
 * so the still shows the scaled + faded state a lone `@Preview` otherwise can't.
 */
@Preview(name = "Large Round", device = WEAR_LARGE_ROUND, showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun CardScalingStill() =
  ProvideTlcScalePosition(TlcScalePosition.Edge) {
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
  }

private val scrollGifItems =
  listOf(
    "Morning run" to "5.2 km · 28 min",
    "Heart rate" to "72 bpm",
    "Sleep" to "7h 14m",
    "Steps" to "6,482",
    "Calories" to "412 kcal",
  )

/**
 * The scaling **animated GIF**: the compose-preview scroll harness drives a real scaling
 * `TransformingLazyColumn`, so the cards scale + fade as they ride through the curved top/bottom edges
 * — one preview, harness-controlled scroll (same mechanism as `:samples:wear`'s
 * `ActivityListGifPreview`). TLC scaling is a list behaviour, so the GIF is authored as a short list
 * rather than a lone item; the isolated-component case is [CardScalingStill].
 */
@Preview(name = "Large Round", device = WEAR_LARGE_ROUND, showBackground = true, backgroundColor = 0xFF000000)
@ScrollingPreview(modes = [ScrollMode.GIF], reduceMotion = false)
@Composable
fun CardScalingScrollGif() =
  MaterialTheme {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    TransformingLazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
      items(scrollGifItems) { (title, subtitle) ->
        TitleCard(
          onClick = {},
          title = { Text(title) },
          subtitle = { Text(subtitle) },
          modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
          transformation = SurfaceTransformation(spec),
        )
      }
    }
  }
