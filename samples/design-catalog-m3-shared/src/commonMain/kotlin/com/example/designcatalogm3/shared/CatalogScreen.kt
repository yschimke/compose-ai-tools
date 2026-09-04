package com.example.designcatalogm3.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.screen.Screen
import ee.schimke.composeai.screen.ScreenNode
import ee.schimke.composeai.screen.flatten
import ee.schimke.composeai.screen.knobSeeds

/**
 * The instance index whose knob values the surrounding catalog body should read, or null for the
 * single-sticker case that has no instance at all.
 *
 * This is the seam that makes a *composition* editable rather than a *component*. A catalog body
 * asks for `label` without knowing it is the third button on a screen; [CatalogScreen] provides the
 * instance around each node, and the `catalogOverride*` wrappers resolve `label[3]` before falling
 * back to the bare key. Null by default, so a lone sticker resolves exactly as it always has and no
 * baked render moves.
 */
val LocalCatalogInstance: ProvidableCompositionLocal<Int?> = compositionLocalOf { null }

/**
 * Provides [seeds] to the catalog's knob lookups for the duration of [content].
 *
 * `expect` because the two tiers seed from different places: the browser has no daemon, so the wasm
 * actual provides the map the knob wrappers read directly, while the desktop `@Preview` tier is
 * seeded by the daemon through `previewOverride*` and has nothing to do here. Keeping the seam
 * `expect` is what lets [CatalogScreen] itself live in `commonMain` and render the same tree on
 * both.
 */
@Composable expect fun CatalogKnobSeeds(seeds: Map<String, String>, content: @Composable () -> Unit)

/**
 * Renders a [Screen] — a composition assembled from catalog components — against this catalog.
 *
 * ### How per-instance values work without changing a single component body
 *
 * The document's knobs flatten to `key[index]` (`Screen.knobSeeds`), which is the seed-key scheme
 * the catalog's knob lookups already use for a repeated row. Each node is composed under its own
 * [LocalCatalogInstance], so `catalogOverrideString("label", …)` inside `CatalogComponent` resolves
 * that instance's value with no argument threading and no per-component plumbing. Two buttons on
 * one screen therefore carry different labels, which is the thing a screen builder is for and the
 * thing a bare knob key cannot express.
 *
 * ### What it does with a node it cannot place
 *
 * An id that is neither a container nor a known component renders a visible diagnostic rather than
 * nothing, and its children are still rendered. A silently empty region is the worst outcome for
 * someone assembling a screen: it looks like a layout bug in the component they just added.
 */
@Composable
fun CatalogScreen(screen: Screen, modifier: Modifier = Modifier) {
  CatalogKnobSeeds(screen.knobSeeds()) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      val indices = screen.flatten().associate { it.node to it.index }
      screen.roots.forEach { ScreenNodeContent(it, indices) }
    }
  }
}

/**
 * The ids [CatalogScreen] renders as containers — the ones that take children.
 *
 * Small on purpose. A builder needs somewhere to put things before it needs every Material
 * container, and each of these has a distinct arrangement a user can actually feel: a column
 * stacks, a lazy column scrolls, a card groups.
 */
val catalogContainerIds: List<String> = listOf("column", "lazy-column", "card")

@Composable
private fun ScreenNodeContent(node: ScreenNode, indices: Map<ScreenNode, Int>) {
  val instance = indices[node]
  CompositionLocalProvider(LocalCatalogInstance provides instance) {
    when (node.componentId) {
      "column" ->
        Column(
          Modifier.fillMaxWidth().padding(4.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          node.children.forEach { ScreenNodeContent(it, indices) }
        }
      "lazy-column" ->
        LazyColumn(Modifier.fillMaxWidth().padding(4.dp)) {
          node.children.forEach { child -> item { ScreenNodeContent(child, indices) } }
        }
      "card" ->
        ElevatedCard(Modifier.fillMaxWidth()) {
          Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            node.children.forEach { ScreenNodeContent(it, indices) }
          }
        }
      in catalogComponentIds -> {
        CatalogComponent(node.componentId)
        // A leaf with children is a document the builder should not have produced, but rendering
        // the children anyway loses nothing and shows the user where they went.
        node.children.forEach { ScreenNodeContent(it, indices) }
      }
      else -> {
        Text(
          "unknown component '${node.componentId}'",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.error,
        )
        node.children.forEach { ScreenNodeContent(it, indices) }
      }
    }
  }
}
