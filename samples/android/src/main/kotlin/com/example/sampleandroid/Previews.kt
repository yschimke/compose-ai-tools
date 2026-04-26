package com.example.sampleandroid

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.ui.text.googlefonts.Font as GoogleFontFont
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.annotations.ManualClockOptions
import com.github.takahirom.roborazzi.annotations.RoboComposePreviewOptions

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}

@Preview(name = "Red Box", showBackground = true, backgroundColor = 0xFFFF0000)
@Composable
fun RedBoxPreview() {
    Box(
        modifier = Modifier.size(100.dp).background(Color.Red),
        contentAlignment = Alignment.Center,
    ) {
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
    MaterialTheme {
        Greeting("Preview")
    }
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
 * Demonstrates `@RoboComposePreviewOptions`: the same preview captured at three
 * distinct points along the infinite animation timeline. Each entry in
 * `manualClockOptions` fans out into its own manifest entry / PNG, suffixed
 * `_TIME_<ms>ms`. Useful for reviewing how a spinner looks at frame 0 vs
 * mid-rotation vs a settled-ish point — the kind of thing you'd want a
 * reviewer to see in a diff.
 */
@Preview(name = "Spinner Timeline", showBackground = true, backgroundColor = 0xFFFFFFFF)
@RoboComposePreviewOptions(
    manualClockOptions = [
        ManualClockOptions(advanceTimeMillis = 0L),
        ManualClockOptions(advanceTimeMillis = 500L),
        ManualClockOptions(advanceTimeMillis = 1500L),
    ],
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

@Preview(
    name = "Phone",
    device = "spec:width=411dp,height=891dp",
    showSystemUi = true,
)
@Composable
fun PhoneGreetingPreview() {
    MaterialTheme {
        Surface {
            Column(modifier = Modifier.padding(16.dp)) {
                Greeting("Phone")
            }
        }
    }
}

/**
 * Deliberately-broken preview — a small Button with no content description
 * AND a tiny size. Exercises the accessibility pipeline end-to-end: a real
 * Material Button is important-for-accessibility, so ATF should flag the
 * TouchTargetSize / SpeakableText rules on it.
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
 * Showcase for the downloadable-fonts path under Robolectric. Uses the same
 * `Font(GoogleFont(name), provider)` shape a consumer writes in production —
 * no `src/debug` fork, no preloaded resource fonts. The shadow in
 * `renderer-android` intercepts `FontsContractCompat.requestFont` and swaps
 * in a TTF downloaded on first render from `fonts.googleapis.com/css2`,
 * cached under `.compose-preview-history/fonts/`.
 *
 * Compares four visually distinct families at multiple weights:
 *  - **Roboto** — static family with pre-rendered weights 100/400/700/900.
 *    All four render at their declared weight (Thin, Regular, Bold, Black).
 *  - **Roboto Flex** — purely variable (no static sub-fonts on CSS2 for
 *    non-default weights). The shadow falls back to a `wght@100..1000`
 *    range query to fetch the variable TTF, then applies the requested
 *    weight via `Typeface.Builder.setFontVariationSettings`. All four rows
 *    currently render at ~400 in the Robolectric native-graphics rasterizer
 *    — the variation axis doesn't propagate through Skia's font renderer
 *    under test. Landing upstream with
 *    android-review.googlesource.com/c/platform/frameworks/support/+/3945083
 *    should make this work without any renderer-side changes.
 *  - **Google Sans Flex** — variable family, but CSS2 returns pre-interpolated
 *    static TTFs for single-weight queries; each declared weight caches to a
 *    distinct file and renders at the correct weight.
 *  - **Lobster Two** — static display script (400/700). Radically different
 *    silhouette from the sans-serifs — proves the shadow works regardless
 *    of family shape.
 */
private val googleFontProvider = androidx.compose.ui.text.googlefonts.GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    // Required at runtime on-device, but never consulted under Robolectric —
    // the shadow short-circuits before PackageManager signature verification.
    // A local empty int-array is enough to exercise the preview path without
    // pulling in `play-services-base` for a sample.
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
        },
    )

// Four families at their characteristic weights. Roboto Flex's 100 and 900
// exercise the variable wght axis; Roboto's own 100 (Thin) is a distinct
// static sub-font. Lobster Two only ships 400 + 700 on Google Fonts.
private val robotoFamily = googleFontFamily("Roboto", listOf(100, 400, 700, 900))
private val robotoFlexFamily = googleFontFamily("Roboto Flex", listOf(100, 400, 700, 900))
private val googleSansFlexFamily = googleFontFamily("Google Sans Flex", listOf(400, 700, 900))
private val lobsterTwoFamily = googleFontFamily("Lobster Two", listOf(400, 700))

@Preview(name = "Google Fonts Showcase", showBackground = true, backgroundColor = 0xFFFFFFFF, widthDp = 520)
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
 * Showcase for the `DeviceFontFamilyName` → Google Fonts transparent swap.
 *
 * `Font(DeviceFontFamilyName("roboto-flex"), weight = FontWeight(100))` is the
 * shape consumer code uses when targeting Pixel's bundled variable fonts —
 * on-device it resolves to `/system/fonts/RobotoFlex-Variable.ttf`. Under
 * Robolectric the sandboxed `/system/fonts` doesn't ship those families, so
 * without intervention every row would render as plain Roboto.
 *
 * `PixelSystemFontAliases` in `renderer-android` seeds `Typeface.sSystemFontMap`
 * with the Google Fonts equivalents (`roboto-flex` → `Roboto Flex`,
 * `google-sans-flex` → `Google Sans Flex`, etc.) before the first preview
 * renders, so these calls resolve to cached downloadable TTFs.
 *
 * Same caveat as [GoogleFontsShowcasePreview] for variable families: the wght
 * axis doesn't fully propagate through Robolectric's native-graphics rasterizer
 * yet (tracked upstream), so Roboto Flex's four rows currently render
 * close-to-identical weight. The static families (Noto Serif italic/non-italic,
 * Dancing Script) exercise the seeding path cleanly.
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
                style = if (italic) {
                    androidx.compose.ui.text.font.FontStyle.Italic
                } else {
                    androidx.compose.ui.text.font.FontStyle.Normal
                },
            )
        },
    )

private val deviceRobotoFlexFamily = deviceFontFamily("roboto-flex", listOf(100, 400, 700, 900))
private val deviceGoogleSansFlexFamily = deviceFontFamily("google-sans-flex", listOf(400, 700))
private val deviceNotoSerifFamily = deviceFontFamily("noto-serif", listOf(400, 700))
private val deviceNotoSerifItalicFamily =
    deviceFontFamily("noto-serif", listOf(400), italic = true)
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
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color(0xFF666666),
        )
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
