package com.example.sampleandroidscreenshot

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.tools.screenshot.PreviewTest

/**
 * **Studio-parity matrix** — the same `@Preview` modes as `:samples:android`'s `PreviewModeMatrix`,
 * declared once and rendered by *two* engines so they can be diffed against each other:
 * - **Layoutlib**, via Google's `com.android.compose.screenshot` plugin. This is the renderer
 *   Android Studio's preview pane itself uses, so its output is the closest thing to "a screenshot
 *   of Studio" that a headless machine can produce — and unlike a hand-captured screenshot it
 *   regenerates on demand and never goes stale.
 * - **Our Robolectric renderer**, which discovers `screenshotTest` previews in this module already
 *   (that is what the module exists for) and writes them to `build/compose-previews/renders/`.
 *
 * `StudioParityTest` pairs the two by preview name and asserts they agree. Where they don't, the
 * divergence is pinned there with an issue link rather than hidden — finding those is the point.
 *
 * Two constraints shape this file:
 * - Every preview needs `@PreviewTest`; from alpha15 on Google's plugin silently discovers nothing
 *   without it (the task fails with "did not discover any tests").
 * - Fixtures are kept cheap and flat. Layoutlib renders serially and this module's job is
 *   *comparison*, not coverage — the exhaustive matrix already lives in `:samples:android`.
 */

/**
 * Fixed-size probe. Deliberately uses `Modifier.size` (not `requiredSize`): `size` is a *preferred*
 * size that yields to tight incoming constraints, which is exactly the axis on which the two
 * engines were found to disagree — Layoutlib measures a fixed-size `@Preview` frame with tight
 * constraints, so the probe stretches to fill it, while our renderer measures loose and letterboxes
 * the probe against the harness background.
 */
@Composable
private fun IntrinsicProbe(label: String) {
  Box(
    modifier = Modifier.size(160.dp, 80.dp).background(Color(0xFF3366FF)),
    contentAlignment = Alignment.Center,
  ) {
    Text(text = label, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
  }
}

/** Fills whatever canvas it is given and reports the configuration it sees. */
@Composable
private fun CanvasProbe(label: String) {
  val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
  val config = LocalConfiguration.current
  val density = LocalDensity.current
  MaterialTheme(colorScheme = scheme) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Column(modifier = Modifier.padding(12.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp)
        Text(
          "${config.screenWidthDp}x${config.screenHeightDp}dp @${density.density}x",
          color = MaterialTheme.colorScheme.onBackground,
          fontSize = 14.sp,
        )
      }
    }
  }
}

// --- Sizing ------------------------------------------------------------------------------------

/** Both axes wrap to the probe's intrinsic 160×80dp. */
@PreviewTest
@Preview(name = "wrap")
@Composable
fun ParityWrapPreview() {
  IntrinsicProbe("wrap")
}

/** Both axes pinned to a frame larger than the probe. */
@PreviewTest
@Preview(name = "fixed", widthDp = 200, heightDp = 100, showBackground = true)
@Composable
fun ParityFixedPreview() {
  IntrinsicProbe("fixed")
}

/** Width pinned, height wrapping — the mixed case that surfaced #3092 on the desktop backend. */
@PreviewTest
@Preview(name = "fixed-width", widthDp = 240, showBackground = true)
@Composable
fun ParityFixedWidthPreview() {
  IntrinsicProbe("fixed-w")
}

// --- Annotation params -------------------------------------------------------------------------

/** `showBackground` + `backgroundColor`, with content that fills so only the frame size varies. */
@PreviewTest
@Preview(
  name = "background",
  widthDp = 120,
  heightDp = 60,
  showBackground = true,
  backgroundColor = 0xFF00FF00,
)
@Composable
fun ParityBackgroundPreview() {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Box(modifier = Modifier.size(40.dp, 20.dp).background(Color.Black))
  }
}

/** `uiMode = UI_MODE_NIGHT_YES`. */
@PreviewTest
@Preview(name = "night", widthDp = 200, heightDp = 100, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ParityNightPreview() {
  CanvasProbe("night")
}

/** The light counterpart of [ParityNightPreview]. */
@PreviewTest
@Preview(name = "day", widthDp = 200, heightDp = 100)
@Composable
fun ParityDayPreview() {
  CanvasProbe("day")
}

/** `fontScale`. */
@PreviewTest
@Preview(name = "fontscale", widthDp = 200, heightDp = 100, fontScale = 2.0f)
@Composable
fun ParityFontScalePreview() {
  CanvasProbe("font")
}

/** `locale`. */
@PreviewTest
@Preview(name = "locale-de", widthDp = 200, heightDp = 100, locale = "de")
@Composable
fun ParityLocalePreview() {
  CanvasProbe("locale")
}

// --- Devices -----------------------------------------------------------------------------------

/** Phone by device id — the geometry half of the AS-parity contract. */
@PreviewTest
@Preview(name = "device-phone", device = "id:pixel_5")
@Composable
fun ParityPhoneDevicePreview() {
  CanvasProbe("pixel 5")
}

/** Wear by device id. */
@PreviewTest
@Preview(name = "device-wear", device = "id:wearos_small_round")
@Composable
fun ParityWearDevicePreview() {
  CanvasProbe("wear")
}

/** The `spec:` grammar including its `dpi=` term. */
@PreviewTest
@Preview(name = "device-spec", device = "spec:width=360dp,height=640dp,dpi=320")
@Composable
fun ParityDeviceSpecPreview() {
  CanvasProbe("spec")
}
