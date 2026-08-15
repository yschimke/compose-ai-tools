package ee.schimke.composeai.renderer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp

/**
 * Fixtures for [PrivatePreviewRenderTest] — every preview here is declared **`private`**, so each
 * compiles to a `private static final` method on `PrivatePreviewFixturesKt`.
 *
 * `private fun` previews are idiomatic Kotlin (nothing but tooling ever calls a preview) and both
 * the desktop daemon and the Android renderer draw them. The standalone desktop renderer resolved
 * them fine — `getDeclaredComposableMethod` scans `declaredMethods`, private members included — but
 * invoked them without opening the JVM method, so every one died with `IllegalAccessException:
 * ComposableMethod cannot access … with modifiers "private static final"` (issue #3873). Keeping
 * them private is the whole point of the fixture: making any of them public would silently retire
 * the regression it pins.
 *
 * All top-level, so the renderer reflects them exactly as it reflects a consumer's `@Preview`.
 */

/** Solid fill. The default (single-frame `ImageComposeScene`) path. */
@Suppress("unused") // invoked reflectively by the renderer, never from Kotlin
@Composable
private fun PrivateRedSquare() {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFFEF5350)))
}

/**
 * Swatch payload for [PrivateSwatchProvider]. `label` is what the renderer derives the fan-out
 * filename suffix from, so the rows land on `<stem>_Crimson.png` / `<stem>_Teal.png` rather than
 * opaque `_PARAM_<idx>` — which is exactly the naming Serve reads rows back out of.
 */
internal data class PrivateSwatch(val label: String, val color: Long)

/**
 * Private provider for [PrivateParameterizedSwatch]. Private on purpose too: a `private class`
 * compiles to a package-private JVM class whose nullary constructor and `getValues()` both need
 * opening — that half already worked (`loadProviderValues` opens them), and this pins it alongside
 * the composable-side fix so a parameterized private preview is covered end to end.
 */
@Suppress("unused") // instantiated reflectively by the renderer, never from Kotlin
private class PrivateSwatchProvider : PreviewParameterProvider<PrivateSwatch> {
  override val values: Sequence<PrivateSwatch> =
    sequenceOf(PrivateSwatch("Crimson", 0xFFDC143C), PrivateSwatch("Teal", 0xFF008B8B))
}

/**
 * `@PreviewParameter` fan-out over [PrivateSwatchProvider]. Resolution goes through
 * `findComposableMethodWithArgs` rather than the parameterless lookup, so it is a second, distinct
 * `ComposableMethod` the fix has to open.
 */
@Suppress("unused") // invoked reflectively by the renderer, never from Kotlin
@Composable
private fun PrivateParameterizedSwatch(swatch: PrivateSwatch) {
  Box(modifier = Modifier.fillMaxSize().background(Color(swatch.color)))
}

/** A dot sweeping across a black field — the `@AnimatedPreview` (paused-clock GIF) path. */
@Suppress("unused") // invoked reflectively by the renderer, never from Kotlin
@Composable
private fun PrivateSweepingDot() {
  val transition = rememberInfiniteTransition(label = "sweep")
  val fraction by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(tween(durationMillis = 1000, easing = LinearEasing), RepeatMode.Restart),
      label = "fraction",
    )
  Box(modifier = Modifier.size(64.dp).background(Color.Black)) {
    Box(
      modifier =
        Modifier.offset(x = 48.dp * fraction, y = 24.dp).size(16.dp).background(Color.White)
    )
  }
}

/** Three stock buttons — the `@FocusedPreview` drive needs something that can take focus. */
@Suppress("unused") // invoked reflectively by the renderer, never from Kotlin
@Composable
private fun PrivateFocusableButtonRow() {
  MaterialTheme(colorScheme = lightColorScheme()) {
    Row(modifier = Modifier.padding(16.dp)) {
      listOf("Save", "Edit", "Share").forEach { label ->
        Button(onClick = {}, modifier = Modifier.padding(end = 8.dp)) { Text(label) }
      }
    }
  }
}

/**
 * 20 red rows then one green row, so the `@ScrollingPreview(END)` capture is green-tipped only if
 * the renderer actually composed and drove the list. Mirrors `ColourBandedListFixture`.
 */
@Suppress("unused") // invoked reflectively by the renderer, never from Kotlin
@Composable
private fun PrivateColourBandedList() {
  LazyColumn(modifier = Modifier.fillMaxSize().background(Color.White)) {
    items(20) { Box(modifier = Modifier.fillMaxWidth().height(40.dp).background(Color.Red)) }
    item { Box(modifier = Modifier.fillMaxWidth().height(40.dp).background(Color.Green)) }
  }
}
