package com.example.sampleandroid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.AnimatedPreview
import ee.schimke.composeai.preview.SettledPreview
import kotlinx.coroutines.delay

/**
 * Demo fixtures for `@SettledPreview` (issue #4202) — a component whose content is driven in by
 * *time* rather than by a gesture.
 *
 * [RevealCard] is Wear's `ConfirmationDialogContent` in miniature: its children start at `alpha =
 * 0` and are animated in from a `LaunchedEffect` after a short delay. Captured at the renderer's
 * default advance it publishes an empty container; the previews below render the same composable
 * with and without a settle so the pair reads as a before/after.
 */
@Composable
fun RevealCard(delayMs: Long = 200, durationMs: Int = 300) {
  val alpha = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    delay(delayMs)
    alpha.animateTo(1f, tween(durationMillis = durationMs, easing = LinearEasing))
  }
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF102027)), Alignment.Center) {
    Column(
      modifier = Modifier.alpha(alpha.value),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Box(modifier = Modifier.size(72.dp).background(Color(0xFF4CAF50), CircleShape))
      Text(text = "Sent", color = Color.White, textAlign = TextAlign.Center)
    }
  }
}

/**
 * The bug, kept renderable: no settle, so the capture lands inside [RevealCard]'s delay and shows
 * the bare container. Deliberately left un-annotated — it is the "before" half of the evidence and
 * the regression pin for the settle ever becoming unconditional.
 */
@Preview(name = "Reveal unsettled", showBackground = true, widthDp = 200, heightDp = 200)
@Composable
fun RevealCardUnsettledPreview() {
  RevealCard()
}

/** The fix: advance until the reveal has quiesced, then capture. */
@SettledPreview
@Preview(name = "Reveal settled", showBackground = true, widthDp = 200, heightDp = 200)
@Composable
fun RevealCardSettledPreview() {
  RevealCard()
}

/**
 * The second shape from the report: nothing fades, but the value arrives after the first
 * composition, so an unsettled capture shows the placeholder rather than the content. Same class of
 * bug as Material 3's `DateInputTextField` publishing its label on top of its own value.
 */
@Composable
fun DeferredValueField() {
  var value by remember { mutableStateOf("") }
  LaunchedEffect(Unit) {
    delay(150)
    value = "08/17/2025"
  }
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFFFFFFFF)), Alignment.Center) {
    Text(
      text = value.ifEmpty { "—" },
      color = Color(0xFF102027),
      modifier = Modifier.padding(16.dp),
    )
  }
}

/** The "before" half of the pair: the placeholder, captured before the value lands. */
@Preview(name = "Deferred unsettled", showBackground = true, widthDp = 200, heightDp = 100)
@Composable
fun DeferredValueUnsettledPreview() {
  DeferredValueField()
}

/** Exact window: the author knows the value lands at 150ms, so there is nothing to search for. */
@SettledPreview(afterMs = 300)
@Preview(name = "Deferred value", showBackground = true, widthDp = 200, heightDp = 100)
@Composable
fun DeferredValueSettledPreview() {
  DeferredValueField()
}

/**
 * `@SettledPreview` **and** `@AnimatedPreview` on one function (issue #4244).
 *
 * The two want opposite things from a paused clock — the GIF records the reveal from its start, the
 * settled still wants the coordinate where it has finished — and virtual time does not rewind. So
 * discovery used to drop the settle and emit a warning naming the collision, which was circular:
 * the still it was meant to fix had already been suppressed by the GIF owning the function.
 *
 * Both ship now. The renderers give the settled still a composition of its own — the desktop lane
 * always had one per output, the Android lane runs a second `setContent` pass — so this preview
 * publishes `renders/SettledPlusAnimatedPreview.png` at the end of the reveal *and*
 * `renders/SettledPlusAnimatedPreview.gif` covering it from the beginning. Committed as the
 * regression pin: if the two ever share a timeline again, one of the pair goes wrong visibly.
 */
@SettledPreview
@AnimatedPreview
@Preview(name = "Settled + animated", showBackground = true, widthDp = 200, heightDp = 200)
@Composable
fun SettledPlusAnimatedPreview() {
  RevealCard()
}
