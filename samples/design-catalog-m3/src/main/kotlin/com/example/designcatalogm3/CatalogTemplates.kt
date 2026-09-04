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
      // Each fillable region is a `PreviewSlot`, which is why this template is a *skeleton* and not
      // just a screenshot: a no-op in an ordinary render (it draws the content below, tagged
      // `dp-slot:<name>`), it swaps to a labelled placeholder under `LocalSlotMode` and surfaces
      // through `/render/<id>.slots` as a drop target with its measured box. The knobs stay — a
      // slot's default child is a live example, not an either/or with being editable.
      //
      // The scope is declared explicitly because a `Scaffold` slot lambda has no layout-scope
      // receiver to infer it from: a child that replaces the app bar is laid out as a single box
      // across the full width, not stacked.
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
          // Each row's sender + preview are indexed override knobs (`sender[i]` / `preview[i]`), so
          // a daemon-backed render can reseed any individual row from the `compose/overrides`
          // surface. The preview copy's author default is a string resource so a `localeTag`
          // override translates it; the sender name stays a literal proper noun.
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
