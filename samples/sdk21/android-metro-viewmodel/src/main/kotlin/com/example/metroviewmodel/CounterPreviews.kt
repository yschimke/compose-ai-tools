package com.example.metroviewmodel

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import dev.zacsweers.metro.createGraph
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory

/**
 * Idiomatic preview path: render the stateless presenter with a literal state. Fast, no DI
 * involved, the canonical pattern for design previews.
 */
@Preview(name = "Content — literal state", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
fun CounterScreenContentPreview() {
  MaterialTheme { CounterScreenContent(count = 42, onIncrement = {}, onDecrement = {}) }
}

/**
 * Full DI path: build the production [AppGraph], hand its `MetroViewModelFactory` to the
 * composition via [LocalMetroViewModelFactory], then call the stateful [CounterScreen] (which uses
 * `metroViewModel()`). Renders the same wiring the app uses at runtime — the count of `7` here
 * comes from `CounterRepository.initialCount()` resolved through Metro, not a hard-coded literal.
 */
@Preview(name = "Screen — Metro graph", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
fun CounterScreenMetroGraphPreview() {
  val factory = createGraph<AppGraph>().metroViewModelFactory
  CompositionLocalProvider(LocalMetroViewModelFactory provides factory) {
    MaterialTheme { CounterScreen() }
  }
}
