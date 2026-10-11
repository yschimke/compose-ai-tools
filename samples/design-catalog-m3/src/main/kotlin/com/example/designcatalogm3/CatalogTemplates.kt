@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.designcatalogm3

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import ee.schimke.composeai.preview.slots.PreviewSlot
import ee.schimke.composeai.preview.slots.PreviewSlotConstraints
import ee.schimke.composeai.preview.slots.PreviewSlotScope
import ee.schimke.composeai.preview.slots.PreviewSlotSizing
import org.jetbrains.compose.resources.stringResource

// Scaffold templates: full-screen skeletons an app copies whole, rendered with `showSystemUi =
// true` (see [CatalogTemplate]) so the renderer's synthetic status and nav bars frame them.

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
 * Full-screen app scaffold: an edge-to-edge TopAppBar, a scrolling list and a FAB. There are no
 * real insets behind the synthetic OS bars, so it supplies [SYSTEM_BAR_INSET] itself.
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
      // Each fillable region is a `PreviewSlot` (a no-op normally; a labelled placeholder under
      // `LocalSlotMode`, and a drop target via `/render/<id>.slots`), so this is a skeleton. The
      // scope is explicit because a `Scaffold` slot lambda has no layout-scope receiver.
      PreviewSlot(
        name = "topBar",
        scope = PreviewSlotScope.Box,
        modifier = Modifier.fillMaxWidth(),
        constraints =
          PreviewSlotConstraints(
            horizontal = PreviewSlotSizing.Fill,
            vertical = PreviewSlotSizing.Hug,
          ),
      ) {
        TopAppBar(
          title = {
            Text(previewOverrideString("title", stringResource(Res.string.template_title)))
          },
          windowInsets = WindowInsets(top = SYSTEM_BAR_INSET),
        )
      }
    },
    floatingActionButton = {
      // Hug on both axes: the FAB is sized by its own content, so a child dropped here should be
      // too — filling would stretch it across the screen.
      PreviewSlot(
        name = "fab",
        scope = PreviewSlotScope.Box,
        constraints =
          PreviewSlotConstraints(
            horizontal = PreviewSlotSizing.Hug,
            vertical = PreviewSlotSizing.Hug,
          ),
      ) {
        FloatingActionButton(onClick = {}) { Text(previewOverrideString("fab", "+")) }
      }
    },
  ) { padding ->
    // The body is one slot rather than one per row: a builder replaces the whole content region
    // with its own composition, and the rows below are this template's default fill. `Column`
    // scope, so a filled child stacks vertically from the top — the arrangement the default has.
    PreviewSlot(
      name = "content",
      scope = PreviewSlotScope.Column,
      modifier = Modifier.padding(padding).fillMaxSize(),
      constraints =
        PreviewSlotConstraints(
          horizontal = PreviewSlotSizing.Fill,
          vertical = PreviewSlotSizing.Fill,
        ),
    ) {
      Column(Modifier.fillMaxSize()) {
        templateMessages.forEachIndexed { index, (sender, previewRes) ->
          // Each row's sender and preview are indexed knobs (`sender[i]` / `preview[i]`); the
          // preview copy defaults to a string resource so `localeTag` translates it.
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
}
