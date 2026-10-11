package com.example.samplewear

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.TimeText

/**
 * Multipreview of round Wear OS devices as they actually report themselves, rather than Studio's
 * synthetic entries. Density is per-device, so the same panel isn't the same layout: 450 px is 225
 * dp on a Pixel Watch (320 dpi) but 211 dp on a Galaxy Watch 5 (340 dpi).
 *
 * | Device                                         | Panel  | densityDpi | density |
 * `screenWidthDp` |
 * |------------------------------------------------|--------|------------|---------|-----------------|
 * | Pixel Watch, Pixel Watch 2                     | 450 px | 320        | 2.0×    | 225
 * | | Pixel Watch 3 (45 mm)                          | 456 px | 320        | 2.0×    | 228
 * | | Galaxy Watch 4, Galaxy Watch 5 (44–45 mm)      | 450 px | 340        | 2.125×  | 211
 * | | Galaxy Watch 6 / 7 (44 mm), Galaxy Watch Ultra | 480 px | 340        | 2.125×  | 225
 * |
 *
 * Pixel 320 dpi and Galaxy Watch 4/5 340 dpi are measured (stock `wm density`); Galaxy Watch
 * 6/7/Ultra 340 dpi is inferred (DPI is locked from Watch 6, but it yields the documented 225 dp
 * breakpoint). 466 px devices are omitted because their density is undocumented.
 *
 * Studio's `id:wearos_xl_round` (240 dp) matches no shipping device; use the catalog ids for
 * extremes ([WearDeviceMatrixPreview]) and this for real wrists. Samsung rows render 2 px narrower
 * than the panel because `screenWidthDp` is an integer; the dp column is the contract.
 *
 * Direct `@Preview`s, since `PreviewDiscovery.resolveMultiPreview` doesn't recurse into nested
 * multipreviews.
 */
@Preview(
  name = "Pixel Watch · 450px @320dpi · 225dp",
  group = "Wear real devices",
  device = "spec:width=225dp,height=225dp,dpi=320,isRound=true",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
@Preview(
  name = "Pixel Watch 3 45mm · 456px @320dpi · 228dp",
  group = "Wear real devices",
  device = "spec:width=228dp,height=228dp,dpi=320,isRound=true",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
@Preview(
  name = "Galaxy Watch 5 44mm · 450px @340dpi · 211dp",
  group = "Wear real devices",
  device = "spec:width=211dp,height=211dp,dpi=340,isRound=true",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
@Preview(
  name = "Galaxy Watch 7 44mm · 480px @340dpi · 225dp",
  group = "Wear real devices",
  device = "spec:width=225dp,height=225dp,dpi=340,isRound=true",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
annotation class WearPreviewRealDevices

/**
 * [WearPreviewRealDevices] over the metrics readout, so each PNG states its viewport. Pixel Watch
 * and Galaxy Watch 7: same 225 dp, different pixels (450 vs 478).
 */
@WearPreviewRealDevices
@Composable
fun WearRealDeviceMatrixPreview() {
  DeviceSpecScreen(label = "Device")
}

/**
 * [WearPreviewRealDevices] over the activity list: how many `TitleCard`s clear the `EdgeButton`
 * across the 211–228 dp the fleet spans.
 */
@WearPreviewRealDevices
@Composable
fun ActivityListRealDeviceMatrixPreview() {
  MaterialTheme {
    AppScaffold(timeText = { TimeText(timeSource = FixedPreviewTimeSource) }) {
      ActivityListScreen()
    }
  }
}
