package com.example.sampleandroid

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.googlefonts.Font as GoogleFontFont
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.annotations.ManualClockOptions
import com.github.takahirom.roborazzi.annotations.RoboComposePreviewOptions

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
  Text(text = "Hello $name!", modifier = modifier)
}

// Kept `private` on purpose: exercises the private-@Preview render path
// (ClassGraph `ignoreMethodVisibility()` + reflective `setAccessible(true)`)
// end-to-end in the sample. The other boxes stay public to cover both shapes.
@Preview(name = "Red Box", showBackground = true, backgroundColor = 0xFFFF0000)
@Composable
private fun RedBoxPreview() {
  Box(modifier = Modifier.size(100.dp).background(Color.Red), contentAlignment = Alignment.Center) {
    Text("Red", color = Color.White)
  }
}

@Preview(name = "Blue Box", showBackground = true, backgroundColor = 0xFF0000FF)
@Composable
fun BlueBoxPreview() {
  Box(
    modifier = Modifier.size(100.dp).background(Color.Blue),
    contentAlignment = Alignment.Center,
  ) {
    Text("Blue", color = Color.White)
  }
}

@Preview(name = "Green Box", showBackground = true, backgroundColor = 0xFF00FF00)
@Composable
fun GreenBoxPreview() {
  Box(
    modifier = Modifier.size(100.dp).background(Color.Green),
    contentAlignment = Alignment.Center,
  ) {
    Text("Green", color = Color.Black)
  }
}

@Preview(name = "Default", showBackground = true)
@Composable
fun GreetingPreview() {
  MaterialTheme { Greeting("Preview") }
}

@Preview(name = "Loading Spinner", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
fun LoadingPreview() {
  MaterialTheme {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      CircularProgressIndicator()
      Text("Loading...")
    }
  }
}

/**
 * `@RoboComposePreviewOptions`: the same infinite animation captured at three points in time. Each
 * `manualClockOptions` entry becomes its own manifest entry / PNG, suffixed `_TIME_<ms>ms`.
 */
@Preview(name = "Spinner Timeline", showBackground = true, backgroundColor = 0xFFFFFFFF)
@RoboComposePreviewOptions(
  manualClockOptions =
    [
      ManualClockOptions(advanceTimeMillis = 0L),
      ManualClockOptions(advanceTimeMillis = 500L),
      ManualClockOptions(advanceTimeMillis = 1500L),
    ]
)
@Composable
fun SpinnerTimelinePreview() {
  MaterialTheme {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      CircularProgressIndicator()
      Text("Loading...")
    }
  }
}

@Composable
private fun ConfigProbe() {
  val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
  val locale = LocalConfiguration.current.locales[0].toLanguageTag()
  MaterialTheme(colorScheme = scheme) {
    Surface(modifier = Modifier.size(220.dp, 120.dp)) {
      Column(modifier = Modifier.padding(12.dp)) {
        Text("dark=${isSystemInDarkTheme()}")
        Text("locale=$locale")
      }
    }
  }
}

@Preview(name = "Default")
@Preview(name = "Night", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "German", locale = "de")
@Composable
fun ConfigProbePreview() {
  ConfigProbe()
}

@Preview(name = "Phone", device = "spec:width=411dp,height=891dp", showSystemUi = true)
@Composable
fun PhoneGreetingPreview() {
  MaterialTheme { Surface { Column(modifier = Modifier.padding(16.dp)) { Greeting("Phone") } } }
}

/**
 * A known phone with `showSystemUi = true` should look like a phone screenshot. Robolectric has no
 * SystemUI, so the renderer paints synthetic bars
 * ([ee.schimke.composeai.renderer.SystemBarsOverlay]); light and dark variants exercise the
 * `uiMode`-aware tints.
 */
@Preview(name = "Pixel 8", device = "id:pixel_8", showSystemUi = true)
@Preview(
  name = "Pixel 8 - Night",
  device = "id:pixel_8",
  showSystemUi = true,
  uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun Pixel8SystemUiPreview() {
  val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
  MaterialTheme(colorScheme = scheme) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Column(modifier = Modifier.padding(24.dp)) {
        Text(text = "Pixel 8", color = MaterialTheme.colorScheme.onBackground, fontSize = 28.sp)
        Spacer(modifier = Modifier.size(8.dp))
        Text(text = "showSystemUi = true", color = MaterialTheme.colorScheme.onBackground)
      }
    }
  }
}

/**
 * Deliberately broken: a tiny Button with no content description, so ATF flags TouchTargetSize /
 * SpeakableText.
 */
@Preview(name = "Bad Button", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
fun BadButtonPreview() {
  androidx.compose.material3.Button(
    onClick = { /* no-op */ },
    modifier = Modifier.size(width = 20.dp, height = 20.dp),
  ) {}
}

/**
 * Downloadable fonts under Robolectric, using the production `Font(GoogleFont(name), provider)`
 * shape. The renderer's shadow intercepts `FontsContractCompat.requestFont` and serves a TTF
 * downloaded from `fonts.googleapis.com/css2`, cached under `~/.cache/composeai/fonts/`.
 *
 * - **Roboto** — static weights 100/400/700/900.
 * - **Roboto Flex** — variable only; fetched via a `wght@100..1000` range and weighted through
 *   `setFontVariationSettings`. Currently all rows render at ~400: the axis doesn't propagate
 *   through Robolectric's rasterizer (upstream fix: android-review.googlesource.com/c/platform/
 *   frameworks/support/+/3945083).
 * - **Google Sans Flex** — CSS2 returns static per-weight TTFs, so weights render correctly.
 * - **Lobster Two** — a static display script, a very different silhouette.
 */
private val googleFontProvider =
  androidx.compose.ui.text.googlefonts.GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    // Required on-device; under Robolectric the shadow skips signature verification.
    certificates = R.array.com_google_android_gms_fonts_certs,
  )

private fun googleFontFamily(
  name: String,
  weights: List<Int>,
): androidx.compose.ui.text.font.FontFamily =
  androidx.compose.ui.text.font.FontFamily(
    weights.map { w ->
      GoogleFontFont(
        androidx.compose.ui.text.googlefonts.GoogleFont(name),
        googleFontProvider,
        weight = androidx.compose.ui.text.font.FontWeight(w),
      )
    }
  )

// Four families at their characteristic weights. Roboto Flex's 100 and 900
// exercise the variable wght axis; Roboto's own 100 (Thin) is a distinct
// static sub-font. Lobster Two only ships 400 + 700 on Google Fonts.
private val robotoFamily = googleFontFamily("Roboto", listOf(100, 400, 700, 900))
private val robotoFlexFamily = googleFontFamily("Roboto Flex", listOf(100, 400, 700, 900))
private val googleSansFlexFamily = googleFontFamily("Google Sans Flex", listOf(400, 700, 900))
private val lobsterTwoFamily = googleFontFamily("Lobster Two", listOf(400, 700))

@Preview(
  name = "Google Fonts Showcase",
  showBackground = true,
  backgroundColor = 0xFFFFFFFF,
  widthDp = 520,
)
@Composable
fun GoogleFontsShowcasePreview() {
  MaterialTheme {
    Column(modifier = Modifier.padding(16.dp)) {
      FontRow("Roboto", robotoFamily, listOf(100, 400, 700, 900))
      Spacer(modifier = Modifier.size(12.dp))
      FontRow("Roboto Flex", robotoFlexFamily, listOf(100, 400, 700, 900))
      Spacer(modifier = Modifier.size(12.dp))
      FontRow("Google Sans Flex", googleSansFlexFamily, listOf(400, 700, 900))
      Spacer(modifier = Modifier.size(12.dp))
      FontRow("Lobster Two", lobsterTwoFamily, listOf(400, 700))
    }
  }
}

/**
 * `Font(DeviceFontFamilyName("roboto-flex"), …)`, as code targeting Pixel's bundled fonts writes
 * it. Robolectric's `/system/fonts` lacks those families, so `PixelSystemFontAliases` in the
 * renderer seeds `Typeface.sSystemFontMap` with Google Fonts equivalents. Same variable-weight
 * caveat as [GoogleFontsShowcasePreview].
 */
private fun deviceFontFamily(
  familyName: String,
  weights: List<Int>,
  italic: Boolean = false,
): androidx.compose.ui.text.font.FontFamily =
  androidx.compose.ui.text.font.FontFamily(
    weights.map { w ->
      androidx.compose.ui.text.font.Font(
        familyName = androidx.compose.ui.text.font.DeviceFontFamilyName(familyName),
        weight = androidx.compose.ui.text.font.FontWeight(w),
        style =
          if (italic) {
            androidx.compose.ui.text.font.FontStyle.Italic
          } else {
            androidx.compose.ui.text.font.FontStyle.Normal
          },
      )
    }
  )

private val deviceRobotoFlexFamily = deviceFontFamily("roboto-flex", listOf(100, 400, 700, 900))
private val deviceGoogleSansFlexFamily = deviceFontFamily("google-sans-flex", listOf(400, 700))
private val deviceNotoSerifFamily = deviceFontFamily("noto-serif", listOf(400, 700))
private val deviceNotoSerifItalicFamily = deviceFontFamily("noto-serif", listOf(400), italic = true)
private val deviceDancingScriptFamily = deviceFontFamily("dancing-script", listOf(400, 700))

@Preview(
  name = "Device Font Family Showcase",
  showBackground = true,
  backgroundColor = 0xFFFFFFFF,
  widthDp = 520,
)
@Composable
fun DeviceFontFamilyShowcasePreview() {
  MaterialTheme {
    Column(modifier = Modifier.padding(16.dp)) {
      FontRow("roboto-flex", deviceRobotoFlexFamily, listOf(100, 400, 700, 900))
      Spacer(modifier = Modifier.size(12.dp))
      FontRow("google-sans-flex", deviceGoogleSansFlexFamily, listOf(400, 700))
      Spacer(modifier = Modifier.size(12.dp))
      FontRow("noto-serif", deviceNotoSerifFamily, listOf(400, 700))
      Spacer(modifier = Modifier.size(12.dp))
      FontRow("noto-serif (italic)", deviceNotoSerifItalicFamily, listOf(400))
      Spacer(modifier = Modifier.size(12.dp))
      FontRow("dancing-script", deviceDancingScriptFamily, listOf(400, 700))
    }
  }
}

@Composable
private fun FontRow(
  label: String,
  family: androidx.compose.ui.text.font.FontFamily,
  weights: List<Int>,
) {
  Column {
    // Label in the platform font so the family name itself can never
    // deceive — if the sample below renders as Roboto too, something
    // regressed.
    Text(text = label, fontSize = 12.sp, color = Color(0xFF666666))
    weights.forEach { w ->
      Text(
        text = "The quick brown fox ($w)",
        fontFamily = family,
        fontWeight = androidx.compose.ui.text.font.FontWeight(w),
        fontSize = 18.sp,
      )
    }
  }
}
