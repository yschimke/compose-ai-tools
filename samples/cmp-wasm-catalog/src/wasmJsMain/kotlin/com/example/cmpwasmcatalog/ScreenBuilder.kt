package com.example.cmpwasmcatalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Switch
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
  // Which of the selected node's slots an add drops into. A `Scaffold` has three, and a builder
  // that guesses one cannot express an app screen: the top bar, the FAB and the body are different
  // regions. Reset when the selection moves, so a slot name never leaks onto a node that has no
  // such slot.
  var slot by remember { mutableStateOf<String?>(null) }
  // The amount `padding` carries. The four screens this palette is sized against use 8, 12 and 16
  // between them, so a fixed chip could not build any of them faithfully.
  var paddingDp by remember { mutableStateOf(16) }

  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Row(
      Modifier.fillMaxSize().padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(
        Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        SectionLabel("Screen")
        // The root is a choice, not a constant. A `Scaffold` is what an app screen usually is, but
        // three of the four real screens this palette is sized against are rooted in a `Surface` —
        // a builder that can only start from a scaffold cannot rebuild them at all.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          M3Palette.containerIds.forEach { id ->
            TextButton(
              onClick = {
                document = newScreen(id)
                selected = 0
                slot = null
              }
            ) {
              Text(
                if (document.root.componentId == id) "[$id]" else id,
                style = MaterialTheme.typography.bodySmall,
              )
            }
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
              .clickable {
                selected = indexed.index
                slot = null
              }
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
                  slot = null
                }
              ) {
                Text("x")
              }
            }
          }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        val target = document.nodeAt(selected)
        val slots = M3Palette.slotsOf(target?.componentId)
        val into = slot?.takeIf { it in slots } ?: slots.firstOrNull()
        if (slots.isEmpty()) {
          SectionLabel("[$selected] ${target?.componentId} takes no children")
        } else {
          SectionLabel("Add into [$selected].$into")
          // Shown even for a single-slot container, so the name of the region being filled is
          // always on screen rather than implied.
          // `FlowRow`, because `Scaffold`'s three slot names do not fit the panel on one line and
          // a `Row` clips the last one into an unreadable vertical sliver rather than wrapping.
          FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            slots.forEach { name ->
              FilterChip(
                selected = name == into,
                onClick = { slot = name },
                label = { Text(name, style = MaterialTheme.typography.bodySmall) },
              )
            }
          }
          SectionLabel("Containers")
          M3Palette.containerIds.forEach { id ->
            AddRow(id) { document = document.addNode(selected, into!!, ScreenNode(id)) }
          }
          SectionLabel("Components")
          M3Palette.componentIds.forEach { id ->
            AddRow(id) { document = document.addNode(selected, into!!, ScreenNode(id)) }
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
          // One editor per declared parameter, chosen by the parameter's own `typeFqn` rather than
          // by the component's id. That is what lets the palette grow without the builder growing
          // a branch per component: `Text.style`, `Surface.color` and `Column.verticalArrangement`
          // are all "a reference this palette offers a closed list of", and they get one control.
          M3Palette.editableParametersOf(node.componentId).forEach { parameter ->
            val current = node.arguments[parameter.name]
            when {
              parameter.typeFqn == "kotlin.String" ->
                OutlinedTextField(
                  value = (current as? ScreenValue.Text)?.value.orEmpty(),
                  onValueChange = { typed ->
                    document =
                      document.setArgument(
                        selected,
                        parameter.name,
                        if (typed.isEmpty()) null else ScreenValue.Text(typed),
                      )
                  },
                  label = { Text(parameter.name) },
                  singleLine = true,
                  modifier = Modifier.fillMaxWidth(),
                )

              parameter.typeFqn == "kotlin.Boolean" ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Switch(
                    checked = (current as? ScreenValue.Bool)?.value ?: true,
                    onCheckedChange = { on ->
                      document =
                        document.setArgument(selected, parameter.name, ScreenValue.Bool(on))
                    },
                  )
                  Text(parameter.name, style = MaterialTheme.typography.bodySmall)
                }

              else -> {
                val choices = M3Palette.choicesFor(parameter.typeFqn)
                if (choices.isNotEmpty()) {
                  SectionLabel(parameter.name)
                  FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    choices.forEach { (label, value) ->
                      FilterChip(
                        selected = current == value,
                        onClick = {
                          document =
                            document.setArgument(
                              selected,
                              parameter.name,
                              if (current == value) null else value,
                            )
                        },
                        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                      )
                    }
                  }
                }
              }
            }
          }

          SectionLabel("Modifiers")
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            // A `FilterChip` rather than a button with a tick in its label: the selected state is
            // the chip's own container colour, so it needs no glyph. A `✓` prefix was the first
            // attempt and rendered as tofu — the catalog's self-hosted fonts have no U+2713, and
            // the browser cannot fall back inside a Skia composition the way it would in the DOM.
            M3Palette.modifierLinks(paddingDp).forEach { (label, link) ->
              FilterChip(
                selected = node.hasLink(link),
                onClick = { document = document.toggleModifier(selected, link) },
                label = { Text(label, style = MaterialTheme.typography.bodySmall) },
              )
            }
            // The amount the `padding` chip carries. Stepping it re-toggles a padding already on
            // the node, so the chain follows the control rather than stranding the old amount —
            // otherwise the chip reads `padding(12)` while the source still says `padding(16.dp)`.
            listOf(8, 12, 16).forEach { amount ->
              TextButton(
                onClick = {
                  val previous =
                    M3Palette.modifierLinks(paddingDp)
                      .first { it.first.startsWith("padding") }
                      .second
                  val next =
                    M3Palette.modifierLinks(amount).first { it.first.startsWith("padding") }.second
                  paddingDp = amount
                  if (node.hasLink(previous)) {
                    document =
                      document.toggleModifier(selected, previous).toggleModifier(selected, next)
                  }
                }
              ) {
                Text(
                  if (amount == paddingDp) "[$amount]" else "$amount",
                  style = MaterialTheme.typography.bodySmall,
                )
              }
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

/** A new screen rooted in [rootId] — a `Scaffold` unless someone picks otherwise. */
private fun newScreen(rootId: String = "scaffold"): ScreenDocument =
  ScreenDocument(name = "MyScreen", root = ScreenNode(rootId))

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
