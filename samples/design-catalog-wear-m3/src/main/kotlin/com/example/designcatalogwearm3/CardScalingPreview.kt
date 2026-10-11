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
import ee.schimke.composeai.wear.preview.TlcScalingHost

private const val WEAR_LARGE_ROUND = "id:wearos_large_round"

/**
 * A single Wear Card with real `TransformingLazyColumn` scaling, authored as normal list-item code
 * (`transformedHeight(this, spec)` + `SurfaceTransformation(spec)`). `TlcScalingHost` hosts it in a
 * one-item TLC, centred at full scale.
 *
 * The scroll harness drives position: [ScrollMode.TOP] captures the unscaled frame, and
 * [ScrollMode.END] (bounded by `maxScrollPx`, tuned to the 454px canvas) rides it into the top
 * scaling zone. `reduceMotion = false` keeps the scaling transforms on.
 */
@Preview(
  name = "Large Round",
  device = WEAR_LARGE_ROUND,
  showBackground = true,
  backgroundColor = 0xFF000000,
)
@ScrollingPreview(modes = [ScrollMode.TOP, ScrollMode.END], maxScrollPx = 180, reduceMotion = false)
@Composable
fun CardScaling() = TlcScalingHost { spec ->
  val (title, onClick) = wearCounted("Heart rate")
  Card(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
    transformation = SurfaceTransformation(spec),
  ) {
    Column {
      Text(title)
      Text("72 bpm")
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
 * Scaling GIF: the scroll harness drives a real `TransformingLazyColumn` so cards scale + fade
 * through the curved edges. Authored as a short list since scaling is a list behaviour; the single
 * item case is [CardScaling].
 */
@Preview(
  name = "Large Round",
  device = WEAR_LARGE_ROUND,
  showBackground = true,
  backgroundColor = 0xFF000000,
)
@ScrollingPreview(modes = [ScrollMode.GIF], reduceMotion = false)
@Composable
fun CardScalingScrollGif() = MaterialTheme {
  val state = rememberTransformingLazyColumnState()
  val spec = rememberTransformationSpec()
  TransformingLazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
    items(scrollGifItems) { (rowTitle, subtitle) ->
      val (title, onClick) = wearCounted(rowTitle)
      TitleCard(
        onClick = onClick,
        title = { Text(title) },
        subtitle = { Text(subtitle) },
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
      )
    }
  }
}
