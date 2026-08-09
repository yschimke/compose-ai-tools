package com.example.samplecmp

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The **preview-mode matrix for the desktop / Compose-Multiplatform renderer** (issue #3082) — the
 * `ImageComposeScene` counterpart of `:samples:android`'s `PreviewModeMatrix.kt`.
 *
 * Same modes, same declared geometry, deliberately the same preview names: a `@Preview` shaped a
 * given way must resolve to the same dp frame on either backend, because both read the annotation
 * through the shared discovery step. What differs is only how each backend *applies* it
 * (Robolectric resource qualifiers vs. `Density` + `LocalSystemTheme` on an `ImageComposeScene`),
 * and that difference must not be visible in the resulting canvas — which is exactly what
 * [PreviewModeMatrixTest] pins here and its Android sibling pins there.
 *
 * Android-only params are deliberately absent: `showSystemUi` has its own desktop fixture
 * (`Pixel8SystemUiPreview` in `Previews.kt`, issue #1930), and `@PreviewScreenSizes` /
 * `@PreviewDynamicColors` pull in Android device semantics the desktop scene has no analogue for.
 * `@PreviewLightDark` and `@PreviewFontScale` do ship in the multiplatform artifact and are
 * covered.
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
  Box(
    modifier = Modifier.size(160.dp, 80.dp).background(color),
    contentAlignment = Alignment.Center,
  ) {
    Text(text = label, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
  }
}

/** Fills whatever canvas it is given — used where the point is the device's full frame. */
@Composable
private fun CanvasProbe(label: String) {
  val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
  val density = LocalDensity.current
  MaterialTheme(colorScheme = scheme) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Column(modifier = Modifier.padding(12.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp)
        Text(
          "@${density.density}x fontScale=${density.fontScale}",
          color = MaterialTheme.colorScheme.onBackground,
          fontSize = 14.sp,
        )
      }
    }
  }
}

// --- Sizing: wrap vs fixed -------------------------------------------------------------------

/** No size params: both axes wrap to the probe's 160×80dp. */
@Preview(name = "Component wrap")
@Composable
fun MatrixComponentWrapPreview() {
  IntrinsicProbe("wrap")
}

/** Both axes pinned. */
@Preview(name = "Fixed both axes", widthDp = 200, heightDp = 100, showBackground = true)
@Composable
fun MatrixFixedBothAxesPreview() {
  IntrinsicProbe("fixed")
}

/** One axis pinned, the other still wrapping — the desktop wrap-layout's mixed case. */
@Preview(name = "Fixed width only", widthDp = 240, showBackground = true)
@Composable
fun MatrixFixedWidthOnlyPreview() {
  IntrinsicProbe("fixed-w")
}

// --- Typical annotation params ---------------------------------------------------------------

/** `showBackground` + `backgroundColor` — the harness fill, same contract as Android. */
@Preview(
  name = "Background colour",
  widthDp = 120,
  heightDp = 60,
  showBackground = true,
  backgroundColor = 0xFF00FF00,
)
@Composable
fun MatrixBackgroundColorPreview() {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Box(modifier = Modifier.size(40.dp, 20.dp).background(Color.Black))
  }
}

/**
 * `uiMode = 32` (`Configuration.UI_MODE_NIGHT_YES`; the CMP common source set has no `android.*`,
 * so the raw bit is used — discovery and the renderer both treat it as an int). Desktop applies it
 * via `LocalSystemTheme`, so `isSystemInDarkTheme()` flips exactly as it does under Robolectric.
 */
@Preview(name = "Night", widthDp = 200, heightDp = 100, uiMode = 32)
@Composable
fun MatrixNightPreview() {
  CanvasProbe("night")
}

/** The light counterpart, so the pair proves `uiMode` landed rather than that dark pixels exist. */
@Preview(name = "Day", widthDp = 200, heightDp = 100)
@Composable
fun MatrixLightPreview() {
  CanvasProbe("day")
}

/** `fontScale` — desktop has no resource qualifiers, so it rides `Density(density, fontScale)`. */
@Preview(name = "Font scale 2x", widthDp = 200, heightDp = 100, fontScale = 2.0f)
@Composable
fun MatrixFontScalePreview() {
  CanvasProbe("font")
}

/** `locale` — applied as the render JVM's default locale for the capture. */
@Preview(name = "German", widthDp = 200, heightDp = 100, locale = "de")
@Composable
fun MatrixLocalePreview() {
  CanvasProbe("locale")
}

// --- Device previews -------------------------------------------------------------------------

/** Phone by device id: the Pixel 5's 393×851dp @2.75×, resolved from the same shared catalog. */
@Preview(name = "Phone", device = "id:pixel_5")
@Composable
fun MatrixPhoneDevicePreview() {
  CanvasProbe("pixel 5")
}

/** Wear by device id — 192×192dp @2.0×. */
@Preview(name = "Wear", device = "id:wearos_small_round")
@Composable
fun MatrixWearDevicePreview() {
  CanvasProbe("wear")
}

/** Foldable by device id — the Pixel Fold's natural 841×701dp frame. */
@Preview(name = "Foldable", device = "id:pixel_fold")
@Composable
fun MatrixFoldableDevicePreview() {
  CanvasProbe("fold")
}

/** The `spec:` grammar including its `dpi=` term. */
@Preview(name = "Device spec", device = "spec:width=360dp,height=640dp,dpi=320")
@Composable
fun MatrixDeviceSpecPreview() {
  CanvasProbe("spec")
}

/**
 * `orientation=portrait` on a landscape `spec:` — the exact device string AndroidX's own
 * `@PreviewScreenSizes` uses for its "Tablet" entry. 1280×800dp @1.5× rotated is 800×1280dp
 * (1200×1920px). Only `landscape` used to be honoured here, so this rendered landscape — pixel for
 * pixel identical to the un-rotated sibling above (issue #3547).
 */
@Preview(
  name = "Rotated device spec",
  device = "spec:width=1280dp,height=800dp,dpi=240,orientation=portrait",
)
@Composable
fun MatrixRotatedDeviceSpecPreview() {
  CanvasProbe("rotated")
}

/**
 * `spec:parent=…,orientation=…` — what Studio's device picker writes once you pick a catalog device
 * and rotate it. The parent supplies the frame (Small Phone, 360×640dp @2.0×) and `orientation`
 * trades the axes, so this renders 640×360dp landscape (1280×720px). `parent=` used to be unread
 * entirely, collapsing the picked device to the 400×800dp default.
 */
@Preview(name = "Parent device spec", device = "spec:parent=small_phone,orientation=landscape")
@Composable
fun MatrixParentDeviceSpecPreview() {
  CanvasProbe("parent")
}

// --- Multipreviews ---------------------------------------------------------------------------

/** Multiplatform-shipped multipreview: light + dark. */
@PreviewLightDark
@Composable
fun MatrixLightDarkMultiPreview() {
  IntrinsicProbe("light/dark")
}

/** Multiplatform-shipped multipreview: the accessibility font-scale ladder. */
@PreviewFontScale
@Composable
fun MatrixFontScaleMultiPreview() {
  IntrinsicProbe("font scales")
}

/**
 * App-declared multipreview meta-annotation — discovery walks it transitively on this backend too,
 * so a consumer's own annotation fans out identically to the shipped ones.
 */
@Preview(name = "Meta phone", device = "id:pixel_5")
@Preview(name = "Meta watch", device = "id:wearos_small_round")
annotation class PhoneAndWatchPreviews

@PhoneAndWatchPreviews
@Composable
fun MatrixMetaAnnotationMultiPreview() {
  CanvasProbe("meta")
}
