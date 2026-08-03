package com.example.samplewear

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.TimeText

/**
 * Multipreview covering **round Wear OS devices as they actually report themselves**, rather than
 * the three synthetic entries Android Studio's device picker offers.
 *
 * The thing this annotation exists to encode: **density is per-device, so the same panel is not the
 * same layout.** A 450 px round panel is **225 dp** on a Pixel Watch and **211 dp** on a Galaxy
 * Watch 5 — same pixels, 14 dp less room — because Google ships 320 dpi and Samsung ships 340 dpi.
 * Deriving dp from pixels alone (`px / 2`) silently overstates every Samsung device by ~7%.
 *
 * | Device | Panel | densityDpi | density | `screenWidthDp` |
 * |--------|-------|-----------|---------|-----------------|
 * | Pixel Watch, Pixel Watch 2 | 450 px | 320 | 2.0× | 225 |
 * | Pixel Watch 3 (45 mm) | 456 px | 320 | 2.0× | 228 |
 * | Galaxy Watch 4, Galaxy Watch 5 (44–45 mm) | 450 px | 340 | 2.125× | 211 |
 * | Galaxy Watch 6 / 7 (44 mm), Galaxy Watch Ultra | 480 px | 340 | 2.125× | 225 |
 *
 * Provenance, because these numbers are worth distrusting until sourced:
 * - **320 dpi on the Pixel line is measured** — the Pixel Watch's stock `wm density` is 320, and
 *   the Play Console lists Pixel Watch 5's 426 px panel as 320 dpi / xhdpi.
 * - **340 dpi on Samsung is measured for Galaxy Watch 4 and 5** — their stock `wm density` is 340,
 *   reported consistently across the sideloading guides that exist precisely because people change
 *   it. This is the row that makes `px / 2` wrong.
 * - **340 dpi on Galaxy Watch 6 / 7 / Ultra is inferred**, not measured: Samsung locked the DPI
 *   control in developer options from Watch 6 on, so there are no stock `wm density` dumps. Two
 *   things point at 340 anyway — continuity from Watch 4/5, and the arithmetic: 480 px at 340 dpi
 *   is 225.88 dp, i.e. `screenWidthDp == 225`, landing exactly on the 225 dp large-display
 *   breakpoint Google's own Wear guidance documents. At 320 dpi it would report 240 dp instead.
 * - **Devices at 466 px (OnePlus Watch 2, TicWatch Pro 5) are deliberately absent** — the panel is
 *   well documented, the reported density is not, and guessing it is the mistake this annotation
 *   is fixing.
 *
 * Note what the table implies about the catalog: `id:wearos_xl_round` is **240 dp**, which is
 * 480 px at 320 dpi — a combination no device above ships. Studio's XL round is a synthetic
 * ceiling, not a watch. `@WearPreviewDevices` (192 / 227 dp) is likewise catalog geometry. Use
 * those to check you survive the extremes; use this annotation to check the sizes real wrists
 * report. [WearDeviceMatrixPreview] keeps the catalog ids covered.
 *
 * The Samsung rows render 2 px narrower than the physical panel (211 dp × 2.125 = 448 px, not
 * 450). That is inherent: `screenWidthDp` is an integer, so the device itself truncates 211.76 dp
 * to 211, and those last pixels are not addressable in dp. The **dp column is the contract** —
 * it's the space a layout is handed — and the pixel count follows from it.
 *
 * These are **direct** `@Preview`s: `PreviewDiscovery.resolveMultiPreview` returns an annotation
 * class's direct previews without recursing into nested multipreviews, so composing this out of
 * `@WearPreviewLargeRound` + friends would silently drop the nested entries.
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
 * [WearPreviewRealDevices] over the metrics readout — each PNG states the viewport it was rendered
 * at, so the table's dp and density values are verifiable from the images alone. The pair worth
 * looking at is Pixel Watch and Galaxy Watch 7: identical 225 dp, different pixel output (450 vs
 * 478), because the density differs.
 */
@WearPreviewRealDevices
@Composable
fun WearRealDeviceMatrixPreview() {
  DeviceSpecScreen(label = "Device")
}

/**
 * [WearPreviewRealDevices] over the real activity list — the check that matters in review: how many
 * `TitleCard`s clear the `EdgeButton` across the 211 → 228 dp the shipped fleet actually spans.
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
