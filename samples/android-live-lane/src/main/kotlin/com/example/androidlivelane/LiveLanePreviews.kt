package com.example.androidlivelane

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.overrides.previewOverrideString

/**
 * The preview the Android serve-lane e2e renders.
 *
 * The declared `label` string knob is the contract `preview-harness/serve-lanes.spec.mjs` selects on
 * (`overrides[].key == "label"`), so this preview drives the same PNG / SVG / Live-WebSocket
 * assertions the desktop `compose-m3` lane runs — only here the render comes from the Robolectric
 * daemon, whose sandbox has to survive this module's deliberately unresolvable manifest
 * `Application` (see `AndroidManifest.xml`).
 *
 * Text-only on purpose: the assertions compare bytes across a knob flip, so the label has to be the
 * dominant thing on screen, and nothing here should need app resources or a theme the bundle would
 * have to carry.
 */
@Preview(name = "LiveLaneCard", showBackground = true)
@Composable
fun LiveLaneCard() {
  MaterialTheme {
    Surface {
      Column(modifier = Modifier.padding(16.dp)) {
        Text(
          text = previewOverrideString("label", "Live lane"),
          style = MaterialTheme.typography.headlineSmall,
        )
        Button(onClick = {}, modifier = Modifier.padding(top = 12.dp)) {
          Text(previewOverrideString("label", "Live lane"))
        }
      }
    }
  }
}
