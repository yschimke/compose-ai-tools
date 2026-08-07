@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.designcatalogm3

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.designcatalogm3.shared.CatalogComponent
import com.example.designcatalogm3.shared.generated.resources.Res
import com.example.designcatalogm3.shared.generated.resources.msg_deploy
import com.example.designcatalogm3.shared.generated.resources.msg_diff
import com.example.designcatalogm3.shared.generated.resources.msg_lunch
import com.example.designcatalogm3.shared.generated.resources.msg_merged
import com.example.designcatalogm3.shared.generated.resources.msg_specs
import com.example.designcatalogm3.shared.generated.resources.template_title
import ee.schimke.composeai.overrides.previewOverrideString
import ee.schimke.composeai.preview.CatalogComponent
import org.jetbrains.compose.resources.stringResource

// ---------------------------------------------------------------------------
// Scaffold templates — full-screen, pre-built screen skeletons an app copies
// whole. Rendered on a phone with `showSystemUi = true` (see [CatalogTemplate])
// so the capture reads as a real screenshot: the OS status bar at the top and
// the gesture-pill nav bar at the bottom, drawn by the renderer's
// SystemBarsFrame, framing the template's own Material chrome.
// ---------------------------------------------------------------------------

// Sender names stay literal (proper nouns aren't translated); each preview line is a string
// resource so a `localeTag` override renders the message copy in the target language.
private val templateMessages =
  listOf(
    "Alex Kim" to Res.string.msg_lunch,
    "Design team" to Res.string.msg_specs,
    "Priya Patel" to Res.string.msg_diff,
    "Sam Rivera" to Res.string.msg_merged,
    "On-call" to Res.string.msg_deploy,
  )

/**
 * Full-screen app scaffold: an edge-to-edge TopAppBar, a scrolling list of ListItems, and a
 * FloatingActionButton — the canonical M3 screen an app starts a new surface from. The render
 * environment has no real window insets behind the renderer's synthetic OS bars, so the scaffold
 * supplies them itself ([SYSTEM_BAR_INSET]): the app bar paints under the status bar with its title
 * below the OS clock, and the content/FAB clear the gesture pill.
 */
@CatalogComponent(
  id = "Template/AppScaffold",
  group = "Scaffold templates",
  caption =
    "Full-screen layout with the OS status bar — TopAppBar, a list, and a FAB, captured with " +
      "showSystemUi on a phone.",
)
@CatalogTemplate
@Composable
fun AppScaffoldTemplate() = FullScreenM3 {
  Scaffold(
    contentWindowInsets = WindowInsets(bottom = SYSTEM_BAR_INSET),
    topBar = {
      TopAppBar(
        title = { Text(previewOverrideString("title", stringResource(Res.string.template_title))) },
        windowInsets = WindowInsets(top = SYSTEM_BAR_INSET),
      )
    },
    floatingActionButton = {
      FloatingActionButton(onClick = {}) { Text(previewOverrideString("fab", "+")) }
    },
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize()) {
      templateMessages.forEachIndexed { index, (sender, previewRes) ->
        // Each row's sender + preview are indexed override knobs (`sender[i]` / `preview[i]`), so a
        // daemon-backed render can reseed any individual row from the `compose/overrides` surface.
        // The preview copy's author default is a string resource so a `localeTag` override
        // translates it; the sender name stays a literal proper noun.
        ListItem(
          headlineContent = { Text(previewOverrideString("sender", sender, index = index)) },
          supportingContent = {
            Text(previewOverrideString("preview", stringResource(previewRes), index = index))
          },
        )
        if (index < templateMessages.lastIndex) HorizontalDivider()
      }
    }
  }
}
