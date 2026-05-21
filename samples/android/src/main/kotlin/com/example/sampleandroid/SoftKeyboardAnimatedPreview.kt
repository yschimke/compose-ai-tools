@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.sampleandroid

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.daemon.KeyboardController
import ee.schimke.composeai.preview.AnimatedPreview

/**
 * Animated demo of the soft-keyboard data extension (`:data-keyboard-connector`). Demonstrates the
 * two control surfaces the extension exposes:
 *
 * 1. **App-side, passive** — `LocalSoftwareKeyboardController.current?.show()` raises the band
 *    through the connector's shadow controller. This is the same call Compose's `BasicTextField`
 *    makes internally on focus, so any normal app code that focuses a text field gets the band for
 *    free; the explicit `show()` here just keeps the preview's intent obvious in source.
 * 2. **Direct controller writes** — `KeyboardController.notifyKeyDown(label)` /
 *    `notifyKeyUp(label)` mirror what `AndroidInteractiveSession.dispatch` does for live daemon
 *    sessions. The preview drives it from a `LaunchedEffect` so a paused-clock `@AnimatedPreview`
 *    capture can step through a typing sequence without needing an interactive session attached.
 *
 * The on-screen band is rendered by the around-composable in `:data-keyboard-connector`; this file
 * doesn't reach into any rendering API. That's the architectural shape — the extension owns the
 * band, the app owns the state.
 */
@Preview(name = "Soft Keyboard — typing", widthDp = 360, heightDp = 640)
@AnimatedPreview(durationMs = 2400, frameIntervalMs = 80, showCurves = false)
@Composable
fun SoftKeyboardAnimatedPreview() {
  val keyboardController = LocalSoftwareKeyboardController.current
  DisposableEffect(Unit) {
    keyboardController?.show()
    onDispose { keyboardController?.hide() }
  }
  val transition = rememberInfiniteTransition(label = "typing")
  val phase by transition.animateFloat(
    initialValue = 0f,
    targetValue = TYPING_SEQUENCE.size.toFloat(),
    animationSpec =
      infiniteRepeatable(animation = tween(durationMillis = 2400, easing = LinearEasing)),
    label = "phase",
  )
  val index = phase.toInt().coerceIn(0, TYPING_SEQUENCE.size - 1)
  val slot = TYPING_SEQUENCE[index]
  // Mirror the daemon's interactive `KEY_DOWN` / `KEY_UP` shape so the band's `pressedKey` state
  // reaches it through the supported `KeyboardController` API rather than a private back-channel.
  LaunchedEffect(index) {
    KeyboardController.notifyKeyDown(slot.pressed)
  }
  DisposableEffect(Unit) { onDispose { KeyboardController.notifyKeyUp() } }

  val running = buildString { for (i in 0..index) append(TYPING_SEQUENCE[i].typed) }

  Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF1F3F4)) {
    Column(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = androidx.compose.foundation.layout.Arrangement.Top,
    ) {
      Text(text = "Compose", style = MaterialTheme.typography.titleMedium)
      Box(
        modifier =
          Modifier.padding(top = 16.dp)
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 12.dp, vertical = 16.dp),
        contentAlignment = Alignment.CenterStart,
      ) {
        Text(
          text = running.ifEmpty { "" } + "│",
          style =
            TextStyle(color = Color(0xFF1F1F1F), fontSize = 20.sp, fontWeight = FontWeight.Medium),
        )
      }
    }
  }
}

/**
 * Static counterpart that exercises only the app-side path: focusing nothing, calling
 * `keyboardController.show()` directly. The band appears because the around-composable's shadow
 * `LocalSoftwareKeyboardController` catches the call and flips `KeyboardController.notifyImeVisibility(true)`.
 * Useful as a baseline diff target against the animated preview.
 */
@Preview(name = "Soft Keyboard — idle", widthDp = 360, heightDp = 640)
@Composable
fun SoftKeyboardIdlePreview() {
  val keyboardController = LocalSoftwareKeyboardController.current
  DisposableEffect(Unit) {
    keyboardController?.show()
    onDispose { keyboardController?.hide() }
  }
  Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF1F3F4)) {
    Column(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = androidx.compose.foundation.layout.Arrangement.Top,
    ) {
      Text(text = "Compose", style = MaterialTheme.typography.titleMedium)
      Box(
        modifier =
          Modifier.padding(top = 16.dp)
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 12.dp, vertical = 16.dp),
        contentAlignment = Alignment.CenterStart,
      ) {
        Text(
          text = "│",
          style =
            TextStyle(color = Color(0xFF1F1F1F), fontSize = 20.sp, fontWeight = FontWeight.Medium),
        )
      }
    }
  }
}

/**
 * One step in the typing demo: which key cap is depicted as pressed for this slot, and which
 * character (if any) gets appended to the running text. The two can differ — e.g. the "space" key
 * types ' '.
 */
private data class TypingSlot(val pressed: String, val typed: String)

private val TYPING_SEQUENCE: List<TypingSlot> =
  listOf(
    TypingSlot("h", "h"),
    TypingSlot("e", "e"),
    TypingSlot("l", "l"),
    TypingSlot("l", "l"),
    TypingSlot("o", "o"),
    TypingSlot("space", " "),
    TypingSlot("w", "w"),
    TypingSlot("o", "o"),
    TypingSlot("r", "r"),
    TypingSlot("l", "l"),
    TypingSlot("d", "d"),
    TypingSlot("enter", ""),
  )
