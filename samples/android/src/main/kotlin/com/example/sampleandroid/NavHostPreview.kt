package com.example.sampleandroid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/**
 * NavHost-based preview that exercises the daemon's navigation surface end-to-end.
 *
 * **What this preview does (interactively):**
 * - Boots a `NavHost` with two destinations — `home` and `profile/{userId}` — and a
 *   [`BackHandler`] on the profile screen so an `OnBackPressedCallback` is registered with the
 *   activity's `onBackPressedDispatcher`.
 * - The home screen exposes a "Go to profile" button. The profile screen exposes a "Back" button
 *   that calls `navController.popBackStack()`.
 *
 * **Why this fixture is useful for navigation audits:**
 * - Agents can fetch `data/navigation` at any render and observe `intent` (action / data URI /
 *   simple-typed extras) plus `onBackPressed.hasEnabledCallbacks` — the latter flips between
 *   `false` (home) and `true` (profile) so the snapshot tracks where the back-stack pop will go.
 * - Agents can drive `navigation.deepLink` (`Intent(ACTION_VIEW, "app://profile/42")`) to land
 *   directly on the profile screen, then `navigation.predictiveBack*` events to verify the
 *   gesture's animation curve renders correctly, and finally `navigation.back` (or the gesture
 *   commit phase) to pop back to home.
 *
 * **Robolectric reality check.** Under `ActivityScenarioRule<ComponentActivity>`, the launch
 * Intent has `action = MAIN` / `category = LAUNCHER` and an empty extras bag — so the
 * `data/navigation` snapshot's `intent` block is sparse on the home preview. After a
 * `navigation.deepLink` script event the snapshot's `intent.action` flips to `VIEW` and
 * `intent.dataUri` carries the deep-link URI. See
 * [`NavigationDataProducer`][ee.schimke.composeai.daemon.NavigationDataProducer] for the wire
 * shape and the Robolectric-specific extras handling.
 */
@Preview(name = "NavHost — Home", showBackground = true, widthDp = 320, heightDp = 480)
@Composable
fun NavHostHomePreview() {
  Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
      composable("home") {
        HomeScreen(onProfile = { navController.navigate("profile/42") })
      }
      composable("profile/{userId}") { entry ->
        // BackHandler installs an OnBackPressedCallback(enabled = true) — that's what
        // `data/navigation`'s `onBackPressed.hasEnabledCallbacks` flips to true on this screen.
        BackHandler { navController.popBackStack() }
        ProfileScreen(
          userId = entry.arguments?.getString("userId") ?: "?",
          onBack = { navController.popBackStack() },
        )
      }
    }
  }
}

@Composable
private fun HomeScreen(onProfile: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
  ) {
    Text("Home", style = MaterialTheme.typography.headlineSmall)
    Text(
      "Tap to navigate, or send `navigation.deepLink` with deepLinkUri=app://profile/42",
      style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = onProfile) { Text("Go to profile") }
  }
}

@Composable
private fun ProfileScreen(userId: String, onBack: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
  ) {
    Text("Profile #$userId", style = MaterialTheme.typography.headlineSmall)
    Text(
      "BackHandler is registered — `data/navigation.onBackPressed.hasEnabledCallbacks` reports true.",
      style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = onBack) { Text("Back") }
  }
}
