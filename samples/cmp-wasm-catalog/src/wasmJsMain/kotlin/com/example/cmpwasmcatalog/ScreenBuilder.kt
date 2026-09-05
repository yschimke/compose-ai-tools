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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.discovery.ChainLink
import ee.schimke.composeai.discovery.ScreenDocument
import ee.schimke.composeai.discovery.ScreenGenerator
import ee.schimke.composeai.discovery.ScreenNode
import ee.schimke.composeai.discovery.ScreenValue
import ee.schimke.composeai.screen.CompileCheck
import ee.schimke.composeai.screen.CompileOutcome
import ee.schimke.composeai.screen.CompileSeverity
import ee.schimke.composeai.screen.M3Palette
import ee.schimke.composeai.screen.SourceHighlighter
import ee.schimke.composeai.screen.SourceTokenKind
import ee.schimke.composeai.screen.addNode
import ee.schimke.composeai.screen.flattenNodes
import ee.schimke.composeai.screen.nodeAt
import ee.schimke.composeai.screen.removeNode
import ee.schimke.composeai.screen.setArgument

/**
 * The UI builder, over the **real** screen document and the **real** generator.
 *
 * A screen starts as a `Scaffold` — the thing an app screen is — and grows by adding containers and
 * components into a selected node's slot. Selecting a node exposes its arguments and its modifier
 * chain; every edit rebuilds the document, which re-renders the middle pane and regenerates the
 * source on the right in the same recomposition.
 *
 * Nothing here generates code. `ScreenGenerator` does — the one in `preview-discovery`, shared as
 * source so it compiles to `wasmJs` too, which is what lets the browser generate with no server.
 * When it refuses, the refusals are shown: they name the node and the reason, and that is more
 * useful than a blank pane.
 */
@Composable
internal fun ScreenBuilderApp(
  previewHost: ScreenPreviewHost = WasmCatalogPreviewHost,
  compileHost: String? = null,
) {
  var document by remember { mutableStateOf(newScreen()) }
  var selected by remember { mutableStateOf(0) }

  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Row(
      Modifier.fillMaxSize().padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(
        Modifier.width(250.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          SectionLabel("Screen")
          TextButton(
            onClick = {
              document = newScreen()
              selected = 0
            }
          ) {
            Text("reset")
          }
        }
        val nodes = document.flattenNodes()
        nodes.forEach { indexed ->
          val depth = depthOf(nodes.map { it.parentIndex }, indexed.index)
          Row(
            Modifier.fillMaxWidth()
              .background(
                if (selected == indexed.index) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface
              )
              .clickable { selected = indexed.index }
              .padding(start = (depth * 12).dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              "[${indexed.index}] ${indexed.node.componentId}",
              style = MaterialTheme.typography.bodySmall,
            )
            if (indexed.index != 0) {
              TextButton(
                onClick = {
                  document = document.removeNode(indexed.index)
                  selected = 0
                }
              ) {
                Text("x")
              }
            }
          }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        val target = document.nodeAt(selected)
        val slot = slotFor(target?.componentId)
        SectionLabel(
          if (slot == null) "Selected node takes no children" else "Add into [$selected].$slot"
        )
        if (slot != null) {
          SectionLabel("Containers")
          M3Palette.containerIds.forEach { id ->
            AddRow(id) { document = document.addNode(selected, slot, ScreenNode(id)) }
          }
          SectionLabel("Components")
          M3Palette.componentIds.forEach { id ->
            AddRow(id) { document = document.addNode(selected, slot, ScreenNode(id)) }
          }
        }
      }

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
          previewHost.Preview(document, Modifier)
        }

        val node = document.nodeAt(selected)
        SectionLabel("Arguments" + if (node == null) "" else " — ${node.componentId}")
        if (node != null) {
          // `text` is the one argument this palette's components take by value; everything else a
          // screen sets is a slot, a handler or the modifier below.
          if (node.componentId == "text") {
            OutlinedTextField(
              value = (node.arguments["text"] as? ScreenValue.Text)?.value.orEmpty(),
              onValueChange = { typed ->
                document =
                  document.setArgument(
                    selected,
                    "text",
                    if (typed.isEmpty()) null else ScreenValue.Text(typed),
                  )
              },
              label = { Text("text") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )
          }
          SectionLabel("Modifiers")
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            M3Palette.modifierLinks.forEach { (label, link) ->
              // A `FilterChip` rather than a button with a tick in its label: the selected state is
              // the chip's own container colour, so it needs no glyph. A `✓` prefix was the first
              // attempt and rendered as tofu — the catalog's self-hosted fonts have no U+2713, and
              // the browser cannot fall back inside a Skia composition the way it would in the DOM.
              FilterChip(
                selected = node.hasLink(link),
                onClick = { document = document.toggleModifier(selected, link) },
                label = { Text(label, style = MaterialTheme.typography.bodySmall) },
              )
            }
          }
        }
      }

      Column(
        Modifier.width(400.dp).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        SectionLabel("Generated Compose")
        // Regenerated in composition, so the pane is a function of the document: every add,
        // remove, argument and modifier lands here in the same frame it lands in the render.
        val result =
          ScreenGenerator.generate(
            document,
            M3Palette.records,
            packageName = "generated.screen",
            expressionPackages = M3Palette.expressionPackages,
          )
        val source = (result as? ScreenGenerator.Result.Emitted)?.source
        Box(
          Modifier.fillMaxWidth()
            .weight(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .verticalScroll(rememberScrollState())
            .padding(8.dp)
        ) {
          if (source != null) {
            Text(
              highlight(source),
              fontFamily = FontFamily.Monospace,
              style = MaterialTheme.typography.bodySmall,
            )
          } else {
            Text(
              "Cannot generate:\n" +
                (result as ScreenGenerator.Result.Refused).reasons.joinToString("\n") { "• $it" },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          }
        }
        if (compileHost != null && source != null) CompilePane(compileHost, source)
      }
    }
  }
}

/** A new screen: the `Scaffold` an app screen starts as. */
private fun newScreen(): ScreenDocument =
  ScreenDocument(name = "MyScreen", root = ScreenNode("scaffold"))

/**
 * The slot a child is added into for [componentId], or null when it takes none.
 *
 * One slot per container keeps the palette honest: `Scaffold` has a `topBar` too, and offering it
 * needs a slot picker rather than a guess about which one an add meant.
 */
private fun slotFor(componentId: String?): String? =
  when (componentId) {
    "scaffold",
    "lazy-column",
    "column",
    "card",
    "button" -> "content"
    else -> null
  }

private fun ScreenNode.hasLink(link: ChainLink): Boolean =
  (arguments["modifier"] as? ScreenValue.Chain)?.links?.any {
    it.callableFqn == link.callableFqn
  } == true

/**
 * Adds or removes [link] from the node's modifier chain.
 *
 * A modifier is a [ScreenValue.Chain] on `Modifier` — the generator's own vocabulary — not text
 * spliced into source. Clearing the last link removes the argument entirely rather than leaving a
 * bare `Modifier`, which would generate `modifier = Modifier` for a node nobody modified.
 */
private fun ScreenDocument.toggleModifier(index: Int, link: ChainLink): ScreenDocument {
  val node = nodeAt(index) ?: return this
  val existing = (node.arguments["modifier"] as? ScreenValue.Chain)?.links ?: emptyList()
  val links =
    if (existing.any { it.callableFqn == link.callableFqn }) {
      existing.filterNot { it.callableFqn == link.callableFqn }
    } else {
      existing + link
    }
  if (links.isEmpty()) return setArgument(index, "modifier", null)
  return setArgument(
    index,
    "modifier",
    ScreenValue.Chain(
      receiver = M3Palette.modifierReceiver,
      links = links,
      typeFqn = "androidx.compose.ui.Modifier",
    ),
  )
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

/**
 * The generated source as an [AnnotatedString], coloured by [SourceHighlighter].
 *
 * The tokens **tile the source exactly**, which is what lets this be a walk over the list with no
 * gap handling: every offset is covered once, so appending each range in order reproduces the text.
 * A gap or an overlap would show as mangled source, and the invariant is asserted in the model's
 * own tests rather than left to be noticed here.
 */
@Composable
private fun highlight(source: String): AnnotatedString {
  val palette = sourcePalette()
  return remember(source, palette) {
    val tokens = SourceHighlighter.tokenize(source)
    if (tokens.isEmpty()) {
      AnnotatedString(source)
    } else {
      buildAnnotatedString {
        tokens.forEach { token ->
          val style = palette[token.kind]
          if (style == null) {
            append(source, token.start, token.end)
          } else {
            withStyle(style) { append(source, token.start, token.end) }
          }
        }
      }
    }
  }
}

/**
 * The one place a token kind becomes a colour, derived entirely from `MaterialTheme.colorScheme`.
 *
 * **Not a hardcoded IDE palette.** The builder honours `?uiMode=dark`, and a fixed light-theme
 * scheme is unreadable on the other one. Deriving from the scheme also means the code pane cannot
 * drift from the surface it sits on when the theme changes.
 *
 * Two details are deliberate. Weight carries part of the distinction, because M3 offers three
 * accent roles and there are more kinds than that — `primary` bold reads differently from `primary`
 * regular without inventing a colour. And `colorScheme.error` is **not** used for any token: it is
 * what the "Cannot generate" list beside the pane is drawn in, and a string literal wearing the
 * error colour would say something false.
 */
@Composable
private fun sourcePalette(): Map<SourceTokenKind, SpanStyle> {
  val scheme = MaterialTheme.colorScheme
  return mapOf(
    SourceTokenKind.KEYWORD to SpanStyle(color = scheme.primary, fontWeight = FontWeight.Bold),
    SourceTokenKind.ANNOTATION to SpanStyle(color = scheme.secondary, fontWeight = FontWeight.Bold),
    SourceTokenKind.CALL to SpanStyle(color = scheme.primary),
    SourceTokenKind.STRING to SpanStyle(color = scheme.tertiary),
    SourceTokenKind.NUMBER to SpanStyle(color = scheme.secondary),
    SourceTokenKind.COMMENT to
      SpanStyle(color = scheme.onSurfaceVariant.copy(alpha = COMMENT_ALPHA)),
    SourceTokenKind.PLAIN to SpanStyle(color = scheme.onSurfaceVariant),
  )
}

/** Comments recede rather than compete; still well clear of the 4.5:1 the body text carries. */
private const val COMMENT_ALPHA = 0.7f

/**
 * The compile check, and the handoff it comes with.
 *
 * One `POST /api/{v}/compiler/run` answers three questions at once: does the generated screen
 * compile, what does the *server* render for it, and where can it be run interactively. So the pane
 * shows the diagnostics **and** the "Run it" link — treating the same response as pass/fail only
 * would throw away the more interesting half.
 *
 * The response's first-frame PNG is deliberately **not** drawn here: the live render is already the
 * pane immediately to the left of this one, showing the same document. Adding a second, staler copy
 * of it would cost a base64 → `ImageBitmap` decode on every result to say something the user can
 * already see.
 */
@Composable
private fun CompilePane(host: String, source: String) {
  HorizontalDivider(Modifier.padding(vertical = 4.dp))
  when (val state = rememberCompileCheck(host, source)) {
    is CompilePaneState.Discovering ->
      Text("Asking $host what it can compile…", style = MaterialTheme.typography.bodySmall)

    is CompilePaneState.Unavailable ->
      Text(
        "Compile check off: ${state.reason}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )

    is CompilePaneState.Ready -> {
      SectionLabel("Compiles against ${state.target.label}")
      val outcome = state.outcome
      when {
        state.checking && outcome == null ->
          Text("Compiling…", style = MaterialTheme.typography.bodySmall)

        outcome is CompileOutcome.Failed ->
          Text(
            "The host could not run the check: ${outcome.message}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )

        outcome is CompileOutcome.Checked -> {
          val prefix = if (state.checking) "Re-checking… last result: " else ""
          if (outcome.compiles) {
            Text(
              prefix + "Compiles.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
            )
            // The token *is* the handoff — the same call that proved it compiles also opened a
            // live session for it.
            outcome.previewUrl?.let { url ->
              TextButton(onClick = { openInNewTab(CompileCheck.absoluteUrl(host, url)) }) {
                Text("Run it →", style = MaterialTheme.typography.bodySmall)
              }
            }
          } else {
            Text(
              prefix + "${outcome.errors.size} error(s).",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          }
          outcome.diagnostics.forEach { diagnostic ->
            Text(
              listOfNotNull(diagnostic.location(), diagnostic.message).joinToString("  "),
              style = MaterialTheme.typography.bodySmall,
              fontFamily = FontFamily.Monospace,
              color =
                if (diagnostic.severity == CompileSeverity.ERROR) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        else -> Text("Edit to check.", style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}
