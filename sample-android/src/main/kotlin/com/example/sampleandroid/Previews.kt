package com.example.sampleandroid

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.ui.text.googlefonts.Font as GoogleFontFont
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
 * Smoke test for the downloadable-fonts path under Robolectric. Uses the
 * same `Font(GoogleFont(name), provider)` shape a consumer writes in
 * production — no `src/debug` fork, no preloaded resource fonts. The shadow
 * in `renderer-android` intercepts `FontsContractCompat.requestFont` and
 * swaps in a TTF downloaded on first render from
 * `fonts.googleapis.com/css2`, cached under `.compose-preview-history/fonts/`.
 *
 * Roboto Mono is a distinctively-shaped typeface vs the platform Roboto
 * fallback: every glyph is the same width, zeros have a slash, capital O is
 * a circle. If the shadow regresses to the default resolver the PNG will
 * render in Roboto and the visual diff will be obvious.
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

private val robotoMonoFamily = androidx.compose.ui.text.font.FontFamily(
    GoogleFontFont(
        androidx.compose.ui.text.googlefonts.GoogleFont("Roboto Mono"),
        googleFontProvider,
        weight = androidx.compose.ui.text.font.FontWeight.Normal,
    ),
    GoogleFontFont(
        androidx.compose.ui.text.googlefonts.GoogleFont("Roboto Mono"),
        googleFontProvider,
        weight = androidx.compose.ui.text.font.FontWeight.Bold,
    ),
)

@Preview(name = "Google Font", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
fun GoogleFontPreview() {
    MaterialTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Roboto Mono 400",
                fontFamily = robotoMonoFamily,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
            )
            Text(
                text = "Roboto Mono 700",
                fontFamily = robotoMonoFamily,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
            Text(text = "System fallback (Roboto)")
        }
    }
}
