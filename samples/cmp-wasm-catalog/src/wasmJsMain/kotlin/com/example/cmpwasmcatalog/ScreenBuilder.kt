package com.example.cmpwasmcatalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.designcatalogm3.shared.catalogComponentIds
import com.example.designcatalogm3.shared.catalogComponentSpecs
import com.example.designcatalogm3.shared.catalogContainerIds
import ee.schimke.composeai.screen.Screen
import ee.schimke.composeai.screen.ScreenCodegen
import ee.schimke.composeai.screen.ScreenNode
import ee.schimke.composeai.screen.addNode
import ee.schimke.composeai.screen.flatten
import ee.schimke.composeai.screen.nodeAt
import ee.schimke.composeai.screen.removeNode
import ee.schimke.composeai.screen.setKnob

/**
 * The UI builder, running entirely in the browser against the real M3 catalog.
 *
 * ### Why this can exist at all
 *
 * The M3 catalog compiles to `wasmJs` (the Wear one cannot — `androidx.wear.compose` is
 * Android-only), and the catalog's knob lookups already read a `key[index]` map from a composition
 * local. So a composition can be assembled, *edited per instance*, and rendered here with no
 * daemon, no server round-trip and no change to a single component body: the document flattens to
 * the knob map the catalog already reads, and each node composes under its own instance index.
 *
 * ### The loop
 *
 * Pick a component, it is added to the selected container (or the screen). Select a node, edit its
 * knobs, watch the live render change. The generated Compose updates with every edit — the same
 * source a developer would have written, and the artefact the playground compiles and runs.
 */
@Composable
internal fun ScreenBuilderApp(previewHost: ScreenPreviewHost = WasmCatalogPreviewHost) {
  var screen by remember { mutableStateOf(Screen(name = "My screen")) }
  var selected by remember { mutableStateOf<Int?>(null) }

  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Row(
      Modifier.fillMaxSize().padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // ---- Left: what to add, and what is in the screen ----
      Column(
        Modifier.width(240.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        SectionLabel("Add a container")
        catalogContainerIds.forEach { id ->
          AddRow(id) {
            screen = screen.addNode(containerTargetFor(screen, selected), ScreenNode(id))
          }
        }
        SectionLabel("Add a component")
        catalogComponentIds.forEach { id ->
          AddRow(id) {
            screen = screen.addNode(containerTargetFor(screen, selected), ScreenNode(id))
          }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionLabel("Screen")
        val nodes = screen.flatten()
        if (nodes.isEmpty()) {
          Text("empty — add something", style = MaterialTheme.typography.bodySmall)
        }
        nodes.forEach { (index, node, parent) ->
          val depth = depthOf(nodes.map { it.parentIndex }, index)
          Row(
            Modifier.fillMaxWidth()
              .background(
                if (selected == index) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface
              )
              .clickable { selected = index }
              .padding(start = (depth * 12).dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text("[$index] ${node.componentId}", style = MaterialTheme.typography.bodySmall)
            TextButton(
              onClick = {
                screen = screen.removeNode(index)
                selected = null
              }
            ) {
              Text("x")
            }
          }
          // `parent` is unused in the label but proves the tree is what is being listed rather than
          // a flat set — the indent above is derived from it.
          @Suppress("UNUSED_EXPRESSION") parent
        }
      }

      // ---- Middle: the live render, and the knobs of what is selected ----
      Column(
        Modifier.weight(1f).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        SectionLabel("Live render — ${previewHost.label}")
        Box(
          Modifier.fillMaxWidth()
            .weight(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp)
        ) {
          previewHost.Preview(screen, Modifier)
        }

        SectionLabel("Knobs")
        val index = selected
        val node = index?.let { screen.nodeAt(it) }
        if (index == null || node == null) {
          Text("select a node to edit its values", style = MaterialTheme.typography.bodySmall)
        } else {
          val keys = editableKnobKeys(node.componentId)
          if (keys.isEmpty()) {
            Text(
              "'${node.componentId}' declares no knobs this builder knows about",
              style = MaterialTheme.typography.bodySmall,
            )
          }
          keys.forEach { key ->
            OutlinedTextField(
              value = node.knobs[key].orEmpty(),
              onValueChange = { screen = screen.setKnob(index, key, it) },
              label = { Text(key) },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      }

      // ---- Right: the Kotlin this screen is ----
      Column(
        Modifier.width(360.dp).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        SectionLabel("Generated Compose")
        val generated = ScreenCodegen.generate(screen, catalogComponentSpecs)
        Box(
          Modifier.fillMaxWidth()
            .weight(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .verticalScroll(rememberScrollState())
            .padding(8.dp)
        ) {
          Text(
            generated.source,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        if (generated.problems.isNotEmpty()) {
          Text(
            "Cannot generate:\n" + generated.problems.joinToString("\n") { "• $it" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
    }
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun AddRow(id: String, onAdd: () -> Unit) {
  Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
    Text("+ $id", style = MaterialTheme.typography.bodySmall)
  }
}

/**
 * Where a newly added node goes: **inside** the selection when it is a container, beside it
 * otherwise, and at the top level when nothing is selected.
 *
 * Adding into a selected leaf would build a tree the catalog cannot render (a button has no
 * children), and dropping to the root instead would silently ignore the selection. Adding to the
 * selected leaf's *parent* is the one behaviour that respects both.
 */
internal fun containerTargetFor(screen: Screen, selected: Int?): Int? {
  val index = selected ?: return null
  val node = screen.nodeAt(index) ?: return null
  if (node.componentId in catalogContainerIds) return index
  return screen.flatten().firstOrNull { it.index == index }?.parentIndex
}

/**
 * The knob keys this builder offers for a component — the ones its codegen spec knows how to write.
 *
 * Deliberately the spec table rather than a live declaration: the browser tier has no daemon, so
 * nothing enumerates a component's knobs at runtime here. Offering only what can also be
 * *generated* keeps the two halves of the loop honest — every value you can edit survives into the
 * code.
 */
internal fun editableKnobKeys(componentId: String): List<String> {
  val spec = catalogComponentSpecs[componentId] ?: return emptyList()
  return (spec.knobs.keys + listOfNotNull(spec.contentKnob)).sorted()
}

/** How deep a node sits, walked up the parent chain — for the tree list's indent. */
internal fun depthOf(parents: List<Int?>, index: Int): Int {
  var depth = 0
  var at: Int? = parents.getOrNull(index)
  while (at != null) {
    depth++
    at = parents.getOrNull(at)
  }
  return depth
}
