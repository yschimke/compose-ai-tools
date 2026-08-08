package com.example.samplewear

import androidx.compose.runtime.Composable
import androidx.wear.compose.ui.tooling.preview.WearPreviewLargeRound

/**
 * The production composition, previewed **without** a [FixedPreviewTimeSource]: [WearApp] hands
 * `AppScaffold` a bare `TimeText()`, so this preview paints whatever Wear's default `TimeSource`
 * reads from the clock.
 *
 * It exists as a committed regression fixture for issue #3239. Before the renderer pinned its wall
 * clock, this PNG changed every minute with no source change — the same drift an activity hero
 * (`renders/activity__MainActivity.png`) shows, which is where it actually hurts, since an activity
 * preview renders the app's own screen and has no argument to inject a fixed time source through.
 * Now `ee.schimke.composeai.renderer.PreviewClock` pins `10:10` for every render, so this PNG is
 * byte-stable and the visual-diff bot flags it the moment either half of that fix regresses.
 *
 * Every other Wear preview here passes [FixedPreviewTimeSource] explicitly, which is still the right
 * thing for an authored preview — it keeps the intent visible in the source. This one deliberately
 * does not, because the whole point is to exercise the renderer's guarantee.
 */
@WearPreviewLargeRound
@Composable
fun WearAppSystemClockPreview() {
  WearApp()
}
