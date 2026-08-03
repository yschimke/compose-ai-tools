package com.example.samplewear

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.TimeText

/**
 * Multipreview covering the **large end of the shipped Wear OS round range** — the sizes real
 * watches on wrists today actually report, rather than the three entries Android Studio's device
 * picker offers.
 *
 * Studio's catalog stops at `wearos_large_round` (227 dp) and `wearos_xl_round` (240 dp), which
 * between them miss the two panel sizes most current flagships ship. Each entry below is a custom
 * `spec:` device sized from the panel's physical resolution at the **320 dpi / 2.0× density every
 * Wear device in the catalog uses** (`dp = px / 2`), so the numbers are derived, not guessed:
 *
 * | Panel | dp @ 2.0× | Devices with this panel |
 * |-------|-----------|-------------------------|
 * | 450 px | 225 dp | Pixel Watch, Pixel Watch 2 |
 * | 456 px | 228 dp | Pixel Watch 3 (45 mm) |
 * | 466 px | 233 dp | OnePlus Watch 2, TicWatch Pro 5 |
 * | 480 px | 240 dp | Galaxy Watch 4–7 (44 mm), Galaxy Watch Ultra — this one **is** in the catalog as `wearos_xl_round` |
 *
 * The device list is illustrative — panels get reused across models and refreshes, so treat the
 * **px column** as the contract and the device names as "who ships it today". A watch whose OEM
 * pins a non-320 dpi density won't match; that's what the `dpi=` clause is for.
 *
 * The 240 dp row uses the named `id:wearos_xl_round` rather than an equivalent `spec:` string, so
 * this annotation stays consistent with `@WearPreviewDevices` where the catalog already has an
 * entry, and only reaches for `spec:` where it doesn't.
 *
 * These are **direct** `@Preview`s: `PreviewDiscovery.resolveMultiPreview` returns an annotation
 * class's direct previews without recursing into nested multipreviews, so composing this out of
 * `@WearPreviewLargeRound` + friends would silently drop the nested entries.
 */
@Preview(
  name = "450px · 225dp (Pixel Watch, Pixel Watch 2)",
  group = "Wear large devices",
  device = "spec:width=225dp,height=225dp,dpi=320,isRound=true",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
@Preview(
  name = "456px · 228dp (Pixel Watch 3 45mm)",
  group = "Wear large devices",
  device = "spec:width=228dp,height=228dp,dpi=320,isRound=true",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
@Preview(
  name = "466px · 233dp (OnePlus Watch 2, TicWatch Pro 5)",
  group = "Wear large devices",
  device = "spec:width=233dp,height=233dp,dpi=320,isRound=true",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
@Preview(
  name = "480px · 240dp (Galaxy Watch Ultra, Watch 7 44mm)",
  group = "Wear large devices",
  device = "id:wearos_xl_round",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
annotation class WearPreviewLargeDevices

/**
 * [WearPreviewLargeDevices] over the metrics readout — each PNG states the viewport it was
 * rendered at, so the annotation's derived dp values are verifiable from the images alone.
 */
@WearPreviewLargeDevices
@Composable
fun WearLargeDeviceMatrixPreview() {
  DeviceSpecScreen(label = "Device")
}

/**
 * [WearPreviewLargeDevices] over the real activity list — the check that matters in review: how
 * many `TitleCard`s clear the `EdgeButton` as the viewport walks 225 → 240 dp.
 */
@WearPreviewLargeDevices
@Composable
fun ActivityListLargeDeviceMatrixPreview() {
  MaterialTheme {
    AppScaffold(timeText = { TimeText(timeSource = FixedPreviewTimeSource) }) {
      ActivityListScreen()
    }
  }
}
