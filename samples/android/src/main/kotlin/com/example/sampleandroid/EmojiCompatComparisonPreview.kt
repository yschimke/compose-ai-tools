@file:OptIn(ExperimentalTextApi::class)

package com.example.sampleandroid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.EmojiSupportMatch
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformParagraphStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Demonstrates the renderer's `EmojiCompatRenderSupport` hook. Both rows draw the **same** emoji
 * string; the only difference is how each `Text` resolves emoji glyphs:
 *
 * - **Platform** — `EmojiSupportMatch.None` bypasses EmojiCompat, so emoji come from the platform
 *   fallback font (the AOSP `NotoColorEmoji` Robolectric bundles). This is what a preview shows
 *   *without* the hook.
 * - **EmojiCompat (bundled)** — `EmojiSupportMatch.Default` routes emoji through EmojiCompat, which
 *   this module initialises via `emoji2-bundled` (the renderer calls `EmojiCompat.init` before the
 *   composition). This is the app's own version-pinned NotoColorEmoji — what ships on-device.
 *
 * Any per-glyph difference between the two rows is exactly the preview↔device emoji fidelity the
 * hook recovers. The string spans older and newer codepoints so version-skew between the two fonts
 * surfaces (a newer emoji the frozen platform font lacks shows as tofu / a fallback box in the top
 * row and correctly in the bottom row).
 */
private const val EMOJI_SAMPLE = "👋 🌍 🚀 ✨ 🎨 🩷 🩶 🫩 🧑‍🚀 🫱🏽‍🫲🏿"

@Composable
private fun emojiStyle(match: EmojiSupportMatch): TextStyle =
  TextStyle(
    fontSize = 30.sp,
    platformStyle = PlatformTextStyle(null, PlatformParagraphStyle(emojiSupportMatch = match)),
  )

@Preview(name = "EmojiCompat comparison", widthDp = 520, heightDp = 260)
@Composable
fun EmojiCompatComparisonPreview() {
  MaterialTheme {
    Surface {
      Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(text = "Platform fallback font (EmojiSupportMatch.None)", fontSize = 12.sp)
        Text(text = EMOJI_SAMPLE, style = emojiStyle(EmojiSupportMatch.None))
        Text(text = "EmojiCompat bundled font (EmojiSupportMatch.Default)", fontSize = 12.sp)
        Text(text = EMOJI_SAMPLE, style = emojiStyle(EmojiSupportMatch.Default))
      }
    }
  }
}
