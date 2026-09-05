package com.example.designcatalogm3.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
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
 * ### Reading a value, not guessing one
 *
 * Every argument is matched on the **structure the generator emits** — a chain link's
 * `callableFqn`, a reference's `rootFqn` plus `members` — rather than on the label a palette
 * happened to give it. Labels are for people; two surfaces agreeing on a label while disagreeing on
 * a value is exactly the silent divergence this pane exists to prevent. `padding` is the cautionary
 * case: it once read a bare `Whole`, and when the palette started passing a `Dp` (a chain, because
 * `dp` is an extension property) the match quietly failed and the pane drew `padding(0.dp)` while
 * the source said `padding(8.dp)`. [dpOf] unwraps it.
 */
@Composable
fun ScreenDocumentRender(document: ScreenDocument, modifier: Modifier = Modifier) {
  RenderNode(document.root, modifier)
}

/**
 * The `Int` inside a `Dp` value — `8.dp` is `Chain(Whole(8), [.dp])`, not a number.
 *
 * Null when the value is not shaped like one, so a caller falls back rather than drawing a zero
 * that looks like a deliberate absence of spacing.
 */
private fun dpOf(value: ScreenValue?): Int? {
  val chain = value as? ScreenValue.Chain ?: return null
  if (chain.links.singleOrNull()?.callableFqn != "androidx.compose.ui.unit.dp") return null
  return (chain.receiver as? ScreenValue.Whole)?.value?.toInt()
}

/** A node's `modifier` argument as a real [Modifier], or [Modifier] when it names none. */
private fun modifierOf(node: ScreenNode): Modifier {
  val value = node.arguments["modifier"] as? ScreenValue.Chain ?: return Modifier
  var applied: Modifier = Modifier
  value.links.forEach { link ->
    applied =
      when (link.callableFqn) {
        "androidx.compose.foundation.layout.fillMaxWidth" -> applied.fillMaxWidth()
        "androidx.compose.foundation.layout.fillMaxSize" -> applied.fillMaxSize()
        "androidx.compose.foundation.layout.padding" ->
          dpOf(link.positional.firstOrNull())?.let { applied.padding(it.dp) } ?: applied
        // A link this renderer does not know still generates; it just cannot be previewed. Leaving
        // the modifier unchanged is the honest fallback — inventing a different one would make the
        // pane disagree with the code beside it.
        else -> applied
      }
  }
  return applied
}

/** The last member of a `MaterialTheme.<group>.<name>` read, or null for anything else. */
private fun themeMember(value: ScreenValue?, group: String): String? {
  val reference = value as? ScreenValue.Reference ?: return null
  if (reference.rootFqn != "androidx.compose.material3.MaterialTheme") return null
  return reference.members.takeIf { it.size == 2 && it[0] == group }?.get(1)
}

@Composable
private fun styleOf(node: ScreenNode): TextStyle {
  val typography = MaterialTheme.typography
  return when (themeMember(node.arguments["style"], "typography")) {
    "titleLarge" -> typography.titleLarge
    "titleMedium" -> typography.titleMedium
    "bodyLarge" -> typography.bodyLarge
    "bodyMedium" -> typography.bodyMedium
    "bodySmall" -> typography.bodySmall
    "labelLarge" -> typography.labelLarge
    else -> LocalTextStyleDefault
  }
}

private val LocalTextStyleDefault = TextStyle.Default

@Composable
private fun colorOf(node: ScreenNode, name: String): Color? {
  val scheme = MaterialTheme.colorScheme
  return when (themeMember(node.arguments[name], "colorScheme")) {
    "background" -> scheme.background
    "surface" -> scheme.surface
    "surfaceVariant" -> scheme.surfaceVariant
    "primary" -> scheme.primary
    "secondaryContainer" -> scheme.secondaryContainer
    else -> null
  }
}

/** A node's `verticalArrangement`, matched on the construct or reference the generator emits. */
private fun arrangementOf(node: ScreenNode): Arrangement.Vertical {
  return when (val value = node.arguments["verticalArrangement"]) {
    is ScreenValue.Construct ->
      if (value.callableFqn == "androidx.compose.foundation.layout.Arrangement.spacedBy") {
        Arrangement.spacedBy((dpOf(value.positional.firstOrNull()) ?: 0).dp)
      } else Arrangement.Top
    is ScreenValue.Reference ->
      if (value.members.singleOrNull() == "Center") Arrangement.Center else Arrangement.Top
    else -> Arrangement.Top
  }
}

/** A node's `contentPadding`, for the components that take one. */
private fun contentPaddingOf(node: ScreenNode): PaddingValues? {
  val value = node.arguments["contentPadding"] as? ScreenValue.Construct ?: return null
  if (value.callableFqn != "androidx.compose.foundation.layout.PaddingValues") return null
  value.positional.firstOrNull()?.let { all ->
    return PaddingValues((dpOf(all) ?: 0).dp)
  }
  return PaddingValues(
    horizontal = (dpOf(value.named["horizontal"]) ?: 0).dp,
    vertical = (dpOf(value.named["vertical"]) ?: 0).dp,
  )
}

/** A node's `text` argument, for the components that show one. */
private fun textOf(node: ScreenNode, name: String = "text"): String =
  (node.arguments[name] as? ScreenValue.Text)?.value ?: ""

private fun children(node: ScreenNode, slot: String = "content"): List<ScreenNode> =
  node.slots[slot] ?: emptyList()

@Composable
private fun Slot(node: ScreenNode, slot: String) {
  children(node, slot).forEach { RenderNode(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenderNode(node: ScreenNode, modifier: Modifier = Modifier) {
  val own = modifier.then(modifierOf(node))
  when (node.componentId) {
    "scaffold" ->
      Scaffold(
        modifier = own.fillMaxSize(),
        topBar = { Slot(node, "topBar") },
        floatingActionButton = { Slot(node, "floatingActionButton") },
      ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) { Slot(node, "content") }
      }
    "surface" ->
      Surface(
        modifier = own,
        color = colorOf(node, "color") ?: MaterialTheme.colorScheme.surface,
      ) {
        Column { Slot(node, "content") }
      }
    "column" ->
      Column(own.fillMaxWidth(), verticalArrangement = arrangementOf(node)) {
        Slot(node, "content")
      }
    "card" ->
      ElevatedCard(own.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Slot(node, "content") } }
    "top-app-bar" -> TopAppBar(modifier = own, title = { Slot(node, "title") })
    "list-item" ->
      ListItem(
        modifier = own,
        headlineContent = { Slot(node, "headlineContent") },
        supportingContent =
          children(node, "supportingContent")
            .takeIf { it.isNotEmpty() }
            ?.let { { Slot(node, "supportingContent") } },
      )
    "divider" -> HorizontalDivider(own)
    "button" ->
      Button(
        onClick = {},
        modifier = own,
        contentPadding = contentPaddingOf(node) ?: ButtonDefaults.ContentPadding,
      ) {
        Slot(node, "content")
      }
    "fab" -> FloatingActionButton(onClick = {}, modifier = own) { Slot(node, "content") }
    "text" -> Text(textOf(node), modifier = own, style = styleOf(node))
    else ->
      // Visible, not blank: an unplaceable node in an empty region reads as a layout bug in the
      // component just added.
      Text(
        "unknown component '${node.componentId}'",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
      )
  }
}
