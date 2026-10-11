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
 * A `NavHost` (`home` and `profile/{userId}`, with a [`BackHandler`] on profile) exercising the
 * daemon's navigation surface. Agents can read `data/navigation` (the `intent` and
 * `onBackPressed.hasEnabledCallbacks`, true only on profile), drive `navigation.deepLink`
 * (`app://profile/42`), `navigation.predictiveBack*` and `navigation.back`.
 *
 * Under Robolectric the launch Intent is a bare MAIN/LAUNCHER, so the home snapshot's `intent` is
 * sparse until a deep link sets `action = VIEW` and `dataUri`. See
 * [`NavigationDataProducer`][ee.schimke.composeai.daemon.NavigationDataProducer].
 */
@Preview(name = "NavHost — Home", showBackground = true, widthDp = 320, heightDp = 480)
@Composable
fun NavHostHomePreview() {
  Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
      composable("home") { HomeScreen(onProfile = { navController.navigate("profile/42") }) }
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
