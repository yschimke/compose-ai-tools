package com.example.samplewear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText

/**
 * Prints the viewport the renderer configured — dp size, density, pixels, shape, and
 * `smallestScreenWidthDp` (a separate field the renderer must keep in step) — read from
 * [LocalConfiguration] / [LocalDensity], so each PNG is its own evidence that the `device =` string
 * resolved correctly.
 */
@Composable
fun DeviceSpecScreen(label: String) {
  val configuration = LocalConfiguration.current
  val density = LocalDensity.current
  MaterialTheme {
    AppScaffold(timeText = { TimeText(timeSource = FixedPreviewTimeSource) }) {
      ScreenScaffold { contentPadding ->
        Box(
          modifier = Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp),
          contentAlignment = Alignment.Center,
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            Text(
              text = label,
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.primary,
              textAlign = TextAlign.Center,
            )
            Text(
              text = "${configuration.screenWidthDp} × ${configuration.screenHeightDp} dp",
              style = MaterialTheme.typography.bodyMedium,
            )
            Text(
              text = "sw ${configuration.smallestScreenWidthDp} dp",
              style = MaterialTheme.typography.bodySmall,
            )
            Text(
              text = "${density.density}× · ${configuration.densityDpi} dpi",
              style = MaterialTheme.typography.bodySmall,
            )
            Text(
              text =
                "${(configuration.screenWidthDp * density.density).toInt()} × " +
                  "${(configuration.screenHeightDp * density.density).toInt()} px",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = if (configuration.isScreenRound) "round" else "square",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

/**
 * Wear device matrix: the three named round ids (192, 227, 240 dp at 2.0×, from AOSP's
 * `sdklib/devices/wear.xml`) plus a custom `spec:width=385dp,height=385dp,dpi=360,isRound=true`
 * device: 866×866 px at 2.25×, showing `dpi=` is honoured and `isRound` gets the circular crop, so
 * devices newer than Studio's catalog are previewable.
 *
 * Direct `@Preview`s, since `PreviewDiscovery.resolveMultiPreview` doesn't recurse into nested
 * multipreviews.
 */
@Preview(
  name = "Small Round 192dp",
  group = "Wear devices",
  device = "id:wearos_small_round",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
@Preview(
  name = "Large Round 227dp",
  group = "Wear devices",
  device = "id:wearos_large_round",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
@Preview(
  name = "XL Round 240dp",
  group = "Wear devices",
  device = "id:wearos_xl_round",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
@Preview(
  name = "Custom Round 385dp 2.25x",
  group = "Wear devices",
  device = "spec:width=385dp,height=385dp,dpi=360,isRound=true",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
@Composable
fun WearDeviceMatrixPreview() {
  DeviceSpecScreen(label = "Device")
}

/**
 * [ActivityListPreview] on the largest shipped Wear id and the oversized custom device: how many
 * `TitleCard`s fit above the `EdgeButton`.
 */
@Preview(
  name = "Activity list · XL Round 240dp",
  group = "Wear devices",
  device = "id:wearos_xl_round",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
@Preview(
  name = "Activity list · Custom Round 385dp 2.25x",
  group = "Wear devices",
  device = "spec:width=385dp,height=385dp,dpi=360,isRound=true",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
@Composable
fun ActivityListDeviceMatrixPreview() {
  MaterialTheme {
    AppScaffold(timeText = { TimeText(timeSource = FixedPreviewTimeSource) }) {
      ActivityListScreen()
    }
  }
}
