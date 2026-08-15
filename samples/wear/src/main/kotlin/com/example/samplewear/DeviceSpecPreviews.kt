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
 * Prints the viewport the renderer actually configured — dp size, density, physical pixels, and
 * screen shape — read back out of [LocalConfiguration] / [LocalDensity] from inside the
 * composition.
 *
 * This makes the PNG its own evidence: if a `@Preview(device = …)` string is dropped or
 * mis-resolved, the numbers baked into the image say so, rather than the reader having to eyeball
 * whether a round watch face "looks about 240 dp". Used by the device-matrix previews below to
 * demonstrate both halves of the `device =` grammar — a named Wear device id and a custom `spec:`
 * string.
 *
 * `smallestScreenWidthDp` (`sw … dp`) is printed alongside the viewport because it's a *separate*
 * configuration field the renderer has to keep in step, not something derived from the other two at
 * read time. It used to disagree — every Wear preview reported the renderer's baseline `sw 320 dp`
 * next to a 227 dp screen (issue #3309) — so anything reading it as geometry inscribed itself in
 * the wrong circle. Printing it keeps a regression visible in the PNG diff.
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
 * Wear device matrix: the three named round Wear ids plus a **custom `spec:` device**.
 *
 * The named ids all come from AOSP's `sdklib/devices/wear.xml` (and match
 * `androidx.wear.tooling.preview.devices.WearDevices`) — 192 dp, 227 dp and 240 dp, every one of
 * them 320 dpi / 2.0×. `wearos_xl_round` is the largest of them and the one a layout is most likely
 * to under-use.
 *
 * The fourth entry is *not* a catalog device. `spec:width=385dp,height=385dp,dpi=360,isRound=true`
 * asks for a 385 dp round screen at **2.25×** (360 dpi ÷ 160), i.e. 866 × 866 px — well past the
 * top of the shipped Wear range, which is exactly the point: `dpi=` is honoured as a density rather
 * than silently falling back to the Android Studio default, so a bench-test device that ships
 * before Studio's catalog knows about it is previewable today. `isRound=true` drives the same
 * circular device crop the named `_round` ids get.
 *
 * These are **direct** `@Preview`s rather than the `@WearPreviewSmallRound` / `@WearPreviewDevices`
 * aliases: `PreviewDiscovery.resolveMultiPreview` returns an annotation class's direct previews
 * without recursing into nested multipreviews, so mixing the two forms would silently drop the
 * nested entries (same rationale as `CatalogWearBreakpoints` in `:samples:design-catalog-wear-m3`).
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
 * The same activity list as [ActivityListPreview], rendered on the two devices this demo is about:
 * the largest shipped Wear id and the oversized custom `spec:` device. Shows what the extra
 * viewport actually buys a real layout — how many `TitleCard`s fit above the `EdgeButton` — where
 * [WearDeviceMatrixPreview] only reports the numbers.
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
