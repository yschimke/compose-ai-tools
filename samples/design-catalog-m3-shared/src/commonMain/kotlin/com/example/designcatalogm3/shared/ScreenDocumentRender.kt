package com.example.designcatalogm3.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.discovery.ScreenDocument
import ee.schimke.composeai.discovery.ScreenNode
import ee.schimke.composeai.discovery.ScreenValue

/**
 * Draws a [ScreenDocument] with the real Material 3 components its ids name.
 *
 * ### Why this exists next to a generator that already knows the mapping
 *
 * `ScreenGenerator` turns a document into **source**; it does not render. The builder needs the
 * pixels beside the code, live, with no compile step — so this walks the same document and calls
 * the same components the generated source calls. The two are kept honest by construction: a
 * component that renders here and has no record refuses in the code pane, and the builder shows
 * both, so a divergence is visible rather than silent.
 *
 * It renders **M3 directly**, not the catalog's sticker ids. That is the point of the combination:
 * what you place is what gets generated, so `card` is an `ElevatedCard` in both.
 */
@Composable
fun ScreenDocumentRender(document: ScreenDocument, modifier: Modifier = Modifier) {
  RenderNode(document.root, modifier)
}

/** A node's `modifier` argument as a real [Modifier], or [Modifier] when it names none. */
@Composable
private fun modifierOf(node: ScreenNode): Modifier {
  val value = node.arguments["modifier"] as? ScreenValue.Chain ?: return Modifier
  var applied: Modifier = Modifier
  value.links.forEach { link ->
    // Matched on the link's own fully-qualified callable, which is what the generator emits and
    // imports — so the pane and the source cannot disagree about which modifier was placed.
    applied =
      when (link.callableFqn) {
        "androidx.compose.foundation.layout.fillMaxWidth" -> applied.fillMaxWidth()
        "androidx.compose.foundation.layout.fillMaxSize" -> applied.fillMaxSize()
        "androidx.compose.foundation.layout.padding" -> {
          val dp = (link.positional.firstOrNull() as? ScreenValue.Whole)?.value?.toInt() ?: 0
          applied.padding(dp.dp)
        }
        // A link this renderer does not know still generates; it just cannot be previewed. Leaving
        // the modifier unchanged is the honest fallback — inventing a different one would make the
        // pane disagree with the code beside it.
        else -> applied
      }
  }
  return applied
}

/** A node's `text` argument, for the components that show one. */
private fun textOf(node: ScreenNode, name: String = "text"): String =
  (node.arguments[name] as? ScreenValue.Text)?.value ?: ""

private fun children(node: ScreenNode, slot: String = "content"): List<ScreenNode> =
  node.slots[slot] ?: emptyList()

@Composable
private fun RenderNode(node: ScreenNode, modifier: Modifier = Modifier) {
  val own = modifier.then(modifierOf(node))
  when (node.componentId) {
    "scaffold" ->
      Scaffold(
        modifier = own.fillMaxSize(),
        topBar = { children(node, "topBar").forEach { RenderNode(it) } },
      ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
          children(node).forEach { RenderNode(it) }
        }
      }
    "lazy-column" ->
      LazyColumn(own.fillMaxWidth()) {
        children(node).forEach { child -> item { RenderNode(child) } }
      }
    "column" ->
      Column(own.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        children(node).forEach { RenderNode(it) }
      }
    "card" ->
      ElevatedCard(own.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          children(node).forEach { RenderNode(it) }
        }
      }
    "button" ->
      Button(onClick = {}, modifier = own) {
        val label = children(node).firstOrNull()
        if (label == null) Text("") else RenderNode(label)
      }
    "text" -> Text(textOf(node), modifier = own)
    else ->
      // Visible, not blank: an unplaceable node in an empty region reads as a layout bug in the
      // component just added.
      Text(
        "unknown component '${node.componentId}'",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        modifier = own,
      )
  }
}
