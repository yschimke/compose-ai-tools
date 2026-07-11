package com.example.designcatalogwearm3

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import ee.schimke.composeai.preview.TlcScalingPreview

/**
 * What authoring a TLC-scaling preview for a **Wear Card** looks like: a plain `@Preview` of a
 * `Card` — no `TransformingLazyColumn` — that opts into the scaling scope, so it renders as the card
 * would look riding a list. The whole opt-in is the `ProvidePreviewTlcScaling(fraction) { … }`
 * wrapper plus `Modifier.previewTlcScaling(this)` on the card. `@TlcScalingPreview` on the base frame
 * declares the sweep the four `CardScalingSweep*` frames realise.
 */
@Composable
private fun ExampleCard(centerFraction: Float) =
  MaterialTheme {
    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
      ProvidePreviewTlcScaling(centerFraction) {
        Card(onClick = {}, modifier = Modifier.fillMaxWidth().previewTlcScaling(this)) {
          Column {
            Text("Heart rate")
            Text("72 bpm")
          }
        }
      }
    }
  }

@TlcScalingSweepFrame
@TlcScalingPreview
@Composable
fun CardScalingSweep0() = ExampleCard(tlcDemoSweep[0])

@TlcScalingSweepFrame @Composable fun CardScalingSweep1() = ExampleCard(tlcDemoSweep[1])

@TlcScalingSweepFrame @Composable fun CardScalingSweep2() = ExampleCard(tlcDemoSweep[2])

@TlcScalingSweepFrame @Composable fun CardScalingSweep3() = ExampleCard(tlcDemoSweep[3])
