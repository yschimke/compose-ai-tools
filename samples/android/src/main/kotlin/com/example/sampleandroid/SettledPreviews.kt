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
 * Demo fixtures for `@SettledPreview`: content driven in by time. [RevealCard] (like Wear's
 * `ConfirmationDialogContent`) fades its children in after a delay, so a default capture shows an
 * empty container; the previews below form a before/after pair.
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
 * The "before": no settle, so the capture shows the bare container. Also pins that the settle never
 * becomes unconditional.
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
 * A value that arrives after the first composition, so an unsettled capture shows the placeholder
 * (like M3's `DateInputTextField` label over its value).
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
 * `@SettledPreview` and `@AnimatedPreview` on one function. A paused clock can't rewind, so the
 * renderers give the settled still its own composition: this publishes the settled `.png` and a
 * `.gif` from the start of the reveal. If they ever share a timeline, one visibly breaks.
 */
@SettledPreview
@AnimatedPreview
@Preview(name = "Settled + animated", showBackground = true, widthDp = 200, heightDp = 200)
@Composable
fun SettledPlusAnimatedPreview() {
  RevealCard()
}
