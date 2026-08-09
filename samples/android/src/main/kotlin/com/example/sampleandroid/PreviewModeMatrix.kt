package com.example.sampleandroid

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
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The **preview-mode matrix** (issue #3082) — one fixture covering every way a consumer can shape a
 * `@Preview`, so the pipeline's Android-Studio parity is demonstrated end-to-end rather than
 * assumed:
 * - a bare component preview (both axes wrap to the composable's intrinsic size),
 * - explicit `widthDp` / `heightDp` (per-axis fixed frames, including the one-axis case),
 * - device previews across form factors — phone, wear, foldable, TV — by `device = "id:…"`,
 * - the `device = "spec:…"` grammar including its `dpi=` term,
 * - the typical annotation params: `fontScale`, `locale`, `uiMode`, `showBackground` +
 *   `backgroundColor`, `showSystemUi`,
 * - multipreview annotations, both the AndroidX-supplied ones (`@PreviewLightDark`,
 *   `@PreviewFontScale`, `@PreviewScreenSizes`) and an app-declared meta-annotation.
 *
 * [PreviewModeMatrixTest] is the assertion half: it reads the discovery manifest and the rendered
 * PNGs this file produces and pins each one to the geometry Android Studio resolves for the same
 * annotation — `widthDp × density` for a fixed axis, the composable's measured size for a wrapped
 * one. The two together are the answer to "do we match Studio by default": every knob has a fixture
 * here and an expectation there, so a regression in `DeviceDimensions.resolveForRender`, the
 * qualifier plumbing, or the multipreview walk fails the sample's own `check`.
 *
 * Sizes are deliberately small and colours deliberately flat: these previews exist to be *measured*,
 * so a cheap render and an unambiguous corner pixel matter more than looking good.
 */

/**
 * Fixed-size probe — 160×80dp of flat colour. Wrapped axes crop to exactly this.
 *
 * The default colour follows `isSystemInDarkTheme()` so a light/dark multipreview produces visibly
 * different captures; a flat colour would fan out into two identical PNGs and prove nothing.
 */
@Composable
private fun IntrinsicProbe(
  label: String,
  color: Color = if (isSystemInDarkTheme()) Color(0xFF10131A) else Color(0xFF3366FF),
) {
  Box(modifier = Modifier.size(160.dp, 80.dp).background(color), contentAlignment = Alignment.Center) {
    Text(text = label, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
  }
}

/**
 * Fills whatever canvas it is given and reports the configuration it sees. Used for the device
 * previews, where the point is that the sandbox is the *device's* full frame — a wrapping probe
 * would crop back to its own size and prove nothing.
 */
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
        Text(
          "locale=${config.locales[0].toLanguageTag()} fontScale=${density.fontScale}",
          color = MaterialTheme.colorScheme.onBackground,
          fontSize = 14.sp,
        )
      }
    }
  }
}

// --- Sizing: wrap vs fixed -------------------------------------------------------------------

/** No size params at all: both axes wrap to the probe's 160×80dp, at Studio's default density. */
@Preview(name = "Component wrap")
@Composable
fun MatrixComponentWrapPreview() {
  IntrinsicProbe("wrap")
}

/** Both axes pinned — the classic `@Preview(widthDp = …, heightDp = …)` fixed frame. */
@Preview(name = "Fixed both axes", widthDp = 200, heightDp = 100, showBackground = true)
@Composable
fun MatrixFixedBothAxesPreview() {
  IntrinsicProbe("fixed")
}

/** Only one axis pinned: width is fixed, height still wraps to the probe. Studio does the same. */
@Preview(name = "Fixed width only", widthDp = 240, showBackground = true)
@Composable
fun MatrixFixedWidthOnlyPreview() {
  IntrinsicProbe("fixed-w")
}

// --- Typical annotation params ---------------------------------------------------------------

/** `showBackground` + `backgroundColor`: the harness paints the declared colour behind the probe. */
@Preview(name = "Background colour", widthDp = 120, heightDp = 60, showBackground = true, backgroundColor = 0xFF00FF00)
@Composable
fun MatrixBackgroundColorPreview() {
  // Centred inside the fixed frame so all four corners are pure harness background.
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Box(modifier = Modifier.size(40.dp, 20.dp).background(Color.Black))
  }
}

/** `uiMode = UI_MODE_NIGHT_YES` — the dark sibling of [MatrixLightPreview]; same size, dark pixels. */
@Preview(name = "Night", widthDp = 200, heightDp = 100, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun MatrixNightPreview() {
  CanvasProbe("night")
}

/** The light counterpart, so a test can assert the two differ rather than just that both render. */
@Preview(name = "Day", widthDp = 200, heightDp = 100)
@Composable
fun MatrixLightPreview() {
  CanvasProbe("day")
}

/** `fontScale` — applied as a `Configuration` field, not a qualifier. */
@Preview(name = "Font scale 2x", widthDp = 200, heightDp = 100, fontScale = 2.0f)
@Composable
fun MatrixFontScalePreview() {
  CanvasProbe("font")
}

/** `locale` — a BCP-47 tag threaded into the render `Configuration`. */
@Preview(name = "German", widthDp = 200, heightDp = 100, locale = "de")
@Composable
fun MatrixLocalePreview() {
  CanvasProbe("locale")
}

// --- Device previews -------------------------------------------------------------------------

/** Phone by device id. Fixes both axes to the Pixel 5's 393×851dp @2.75x, per Studio's catalog. */
@Preview(name = "Phone", device = "id:pixel_5")
@Composable
fun MatrixPhoneDevicePreview() {
  CanvasProbe("pixel 5")
}

/** Wear by device id — a small round watch face at 192×192dp @2.0x. */
@Preview(name = "Wear", device = "id:wearos_small_round")
@Composable
fun MatrixWearDevicePreview() {
  CanvasProbe("wear")
}

/** Foldable by device id — the Pixel Fold's *unfolded* natural (landscape-ish) 841×701dp frame. */
@Preview(name = "Foldable", device = "id:pixel_fold")
@Composable
fun MatrixFoldableDevicePreview() {
  CanvasProbe("fold")
}

/** TV by device id — 960×540dp @2.0x, i.e. a 1080p panel. */
@Preview(name = "TV", device = "id:tv_1080p")
@Composable
fun MatrixTvDevicePreview() {
  CanvasProbe("tv")
}

/** The `spec:` grammar, including `dpi=` — resolved without any catalog entry. */
@Preview(name = "Device spec", device = "spec:width=360dp,height=640dp,dpi=320")
@Composable
fun MatrixDeviceSpecPreview() {
  CanvasProbe("spec")
}

/**
 * `orientation=portrait` on a landscape `spec:` — the exact device string AndroidX's own
 * `@PreviewScreenSizes` uses for its "Tablet" entry. 1280×800dp @1.5x rotated is 800×1280dp
 * (1200×1920px). Only `landscape` used to be honoured here, so this rendered landscape — pixel for
 * pixel identical to the un-rotated sibling above (issue #3547).
 */
@Preview(name = "Rotated device spec", device = "spec:width=1280dp,height=800dp,dpi=240,orientation=portrait")
@Composable
fun MatrixRotatedDeviceSpecPreview() {
  CanvasProbe("rotated")
}

/**
 * `spec:parent=…,orientation=…` — what Studio's device picker writes once you pick a catalog device
 * and rotate it. The parent supplies the frame (Small Phone, 360×640dp @2.0x) and `orientation`
 * trades the axes, so this renders 640×360dp landscape (1280×720px) and its Configuration reports
 * `land`. `parent=` used to be unread entirely, collapsing the picked device to the 400×800dp
 * default.
 */
@Preview(name = "Parent device spec", device = "spec:parent=small_phone,orientation=landscape")
@Composable
fun MatrixParentDeviceSpecPreview() {
  CanvasProbe("parent")
}

/**
 * `showSystemUi` with no device: Studio still promotes the preview to a full device frame (its
 * default phone), which is what makes the synthetic status/nav bars meaningful.
 */
@Preview(name = "System UI", showSystemUi = true)
@Composable
fun MatrixSystemUiPreview() {
  CanvasProbe("system ui")
}

// --- Multipreviews ---------------------------------------------------------------------------

/** AndroidX multipreview: light + dark in one annotation. */
@PreviewLightDark
@Composable
fun MatrixLightDarkMultiPreview() {
  IntrinsicProbe("light/dark")
}

/** AndroidX multipreview: the accessibility font-scale ladder. */
@PreviewFontScale
@Composable
fun MatrixFontScaleMultiPreview() {
  IntrinsicProbe("font scales")
}

/** AndroidX multipreview: Studio's reference screen sizes (phone → foldable → tablet → desktop). */
@PreviewScreenSizes
@Composable
fun MatrixScreenSizesMultiPreview() {
  CanvasProbe("screens")
}

/**
 * App-declared multipreview meta-annotation. Discovery walks these transitively (with cycle
 * detection), so a consumer's own `@PhoneAndWatchPreviews` fans out exactly like the AndroidX ones —
 * this is the case a hand-written annotation in a real codebase hits.
 */
@Preview(name = "Meta phone", device = "id:pixel_5")
@Preview(name = "Meta watch", device = "id:wearos_small_round")
annotation class PhoneAndWatchPreviews

@PhoneAndWatchPreviews
@Composable
fun MatrixMetaAnnotationMultiPreview() {
  CanvasProbe("meta")
}
