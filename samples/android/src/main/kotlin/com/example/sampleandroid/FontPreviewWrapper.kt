@file:Suppress("RestrictedApiAndroidX")

package com.example.sampleandroid

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font as GoogleFontFont
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.tooling.preview.PreviewWrapperProvider

/**
 * `@PreviewWrapper`-driven wrapper that makes a downloadable Google font the **default** typeface
 * for the preview it wraps — the type-design equivalent of [SystemBarsPreviewWrapper]'s chrome.
 *
 * The wrapper re-themes with a [Typography] whose every role is retargeted to **Lobster Two** (a
 * Google Fonts display script, deliberately unlike Roboto so a glance confirms the wrap fired) and
 * also seeds `LocalTextStyle`, so both idioms pick it up: `Text("…", style =
 * MaterialTheme.typography.headlineMedium)` and a bare `Text("…")`. A preview body therefore needs
 * no font wiring of its own — annotate it and the type changes.
 *
 * The font resolves through the same `Font(GoogleFont(name), provider)` path the rest of the sample
 * uses: on-device it goes through GMS Fonts; under the renderer's Robolectric harness
 * `ShadowFontsContractCompat` intercepts the request and hands back a TTF from the shared
 * `~/.cache/composeai/fonts/` cache (downloaded once from `fonts.googleapis.com/css2`). No bundled
 * TTF, no `src/debug` fork.
 *
 * Applied via [FontPreview] — a multi-preview annotation that hoists
 * `@PreviewWrapperClass(FontPreviewWrapper)` so a single tag both fans the preview out and installs
 * this wrapper. Requires `androidx.compose.ui.tooling.preview` 1.11+ (the version that introduced
 * `PreviewWrapperProvider`) on the compile classpath.
 */
class FontPreviewWrapper : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) {
    MaterialTheme(
      colorScheme = MaterialTheme.colorScheme,
      shapes = MaterialTheme.shapes,
      typography = MaterialTheme.typography.withFontFamily(lobsterTwoFamily),
    ) {
      ProvideTextStyle(LocalTextStyle.current.copy(fontFamily = lobsterTwoFamily)) { content() }
    }
  }
}

private val fontPreviewProvider =
  GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    // Consulted only on-device; the Robolectric shadow short-circuits before signature
    // verification. Shares the sample's empty cert array (see `res/values/font_certs.xml`).
    certificates = R.array.com_google_android_gms_fonts_certs,
  )

private val lobsterTwoFamily =
  FontFamily(
    GoogleFontFont(GoogleFont("Lobster Two"), fontPreviewProvider, weight = FontWeight.Normal),
    GoogleFontFont(GoogleFont("Lobster Two"), fontPreviewProvider, weight = FontWeight.Bold),
  )

/** Retargets every Material 3 type role's `fontFamily` to [family], leaving all other metrics. */
private fun Typography.withFontFamily(family: FontFamily): Typography =
  copy(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
  )
