package com.example.metroviewmodel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.metroViewModel

/**
 * Stateless presenter. Takes the data it needs and the callbacks it can fire — no ViewModel
 * reference, no `LocalMetroViewModelFactory` — so `@Preview` can render it with a literal state and
 * stub lambdas without touching DI. This is the path to prefer for design previews.
 */
@Composable
fun CounterScreenContent(
  count: Int,
  onIncrement: () -> Unit,
  onDecrement: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
    Column(
      modifier = Modifier.padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text("Counter", style = MaterialTheme.typography.titleMedium)
      Text(count.toString(), style = MaterialTheme.typography.displayMedium)
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onDecrement) { Text("−") }
        Button(onClick = onIncrement) { Text("+") }
      }
      Spacer(modifier = Modifier.size(4.dp))
      Text(
        "Initial value injected by CounterRepository",
        style = MaterialTheme.typography.labelSmall,
      )
    }
  }
}

/**
 * Stateful entry point. `metroViewModel()` looks `LocalMetroViewModelFactory` up from the
 * composition and asks the standard Compose `viewModel()` to resolve a [CounterViewModel] through
 * it. The default `viewModelStoreOwner` is `LocalViewModelStoreOwner.current` — Robolectric's
 * `ComponentActivity` provides one, so this works under the renderer the same as it does on a
 * device.
 */
@Composable
fun CounterScreen(modifier: Modifier = Modifier) {
  val viewModel: CounterViewModel = metroViewModel()
  val count by viewModel.count.collectAsState()
  CounterScreenContent(
    count = count,
    onIncrement = viewModel::increment,
    onDecrement = viewModel::decrement,
    modifier = modifier,
  )
}
