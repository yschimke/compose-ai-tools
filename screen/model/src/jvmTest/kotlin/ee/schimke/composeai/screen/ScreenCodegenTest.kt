package ee.schimke.composeai.screen

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the composition document and the Kotlin it generates — the two halves of the builder that
 * every other surface reads.
 *
 * The scenario throughout is the one the builder exists for: a `LazyColumn`, a header in it with
 * its own text, then a card. Testing that shape rather than a synthetic one keeps the assertions
 * honest about what actually has to work.
 */
class ScreenCodegenTest {

  private val specs =
    mapOf(
      "lazy-column" to
        ComponentSpec(
          call = "LazyColumn",
          imports = listOf("androidx.compose.foundation.lazy.LazyColumn"),
          container = true,
        ),
      "list-header" to
        ComponentSpec(
          call = "ListHeader",
          imports = listOf("com.example.ListHeader"),
          knobs = mapOf("text" to KnobSpec("text")),
        ),
      "card" to
        ComponentSpec(
          call = "Card",
          imports = listOf("androidx.compose.material3.Card"),
          container = true,
          knobs = mapOf("elevation" to KnobSpec("elevationDp", KnobKind.DP)),
        ),
      "button-filled" to
        ComponentSpec(
          call = "Button",
          imports = listOf("androidx.compose.material3.Button"),
          knobs =
            mapOf(
              "label" to KnobSpec("text"),
              "enabled" to KnobSpec("enabled", KnobKind.BOOLEAN),
            ),
        ),
      "scaffold" to
        ComponentSpec(
          call = "Scaffold",
          imports = listOf("androidx.compose.material3.Scaffold"),
          container = true,
          slots = mapOf("topBar" to "topBar"),
        ),
    )

  private val listScreen =
    Screen(
      name = "activity list",
      roots =
        listOf(
          ScreenNode(
            componentId = "lazy-column",
            children =
              listOf(
                ScreenNode("list-header", knobs = mapOf("text" to "Activity")),
                ScreenNode(
                  "card",
                  knobs = mapOf("elevation" to "4"),
                  children = listOf(ScreenNode("button-filled", knobs = mapOf("label" to "Open"))),
                ),
              ),
          )
        ),
    )

  @Test
  fun `the builder's own scenario generates compilable-looking Compose`() {
    val generated = ScreenCodegen.generate(listScreen, specs)

    assertEquals(emptyList<String>(), generated.problems)
    assertEquals(
      """
      import androidx.compose.foundation.lazy.LazyColumn
      import androidx.compose.material3.Button
      import androidx.compose.material3.Card
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.unit.dp
      import com.example.ListHeader

      @Composable
      fun ActivityList() {
        LazyColumn() {
          ListHeader(text = "Activity")
          Card(elevationDp = 4.0.dp) {
            Button(text = "Open")
          }
        }
      }
      """
        .trimIndent() + "\n",
      generated.source,
    )
  }

  @Test
  fun `a named slot becomes a lambda argument, an ordered child becomes trailing content`() {
    val screen =
      Screen(
        name = "app",
        roots =
          listOf(
            ScreenNode(
              "scaffold",
              children =
                listOf(
                  ScreenNode("button-filled", knobs = mapOf("label" to "Up"), slot = "topBar"),
                  ScreenNode("list-header", knobs = mapOf("text" to "Body")),
                ),
            )
          ),
      )
    val generated = ScreenCodegen.generate(screen, specs)

    assertEquals(emptyList<String>(), generated.problems)
    assertTrue(generated.source, generated.source.contains("Scaffold(topBar = {"))
    assertTrue(generated.source, generated.source.contains("""Button(text = "Up")"""))
    // The unslotted child is the trailing lambda, not another argument.
    assertTrue(generated.source, generated.source.contains("""}) {"""))
  }

  @Test
  fun `every literal kind is spelled as Kotlin, and a bad value is quoted rather than zeroed`() {
    val spec =
      ComponentSpec(
        call = "Probe",
        imports = listOf("com.example.Probe"),
        knobs =
          mapOf(
            "s" to KnobSpec("s"),
            "i" to KnobSpec("i", KnobKind.INT),
            "f" to KnobSpec("f", KnobKind.FLOAT),
            "b" to KnobSpec("b", KnobKind.BOOLEAN),
            "c" to KnobSpec("c", KnobKind.COLOR),
            "d" to KnobSpec("d", KnobKind.DP),
          ),
      )
    val screen =
      Screen(
        "probe",
        listOf(
          ScreenNode(
            "probe",
            knobs =
              mapOf(
                "s" to "hi",
                "i" to "3",
                "f" to "1.5",
                "b" to "true",
                "c" to "#FF42A5F5",
                "d" to "12",
              ),
          )
        ),
      )
    val src = ScreenCodegen.generate(screen, mapOf("probe" to spec)).source
    assertTrue(src, src.contains("""b = true"""))
    assertTrue(src, src.contains("""c = Color(0xFF42A5F5)"""))
    assertTrue(src, src.contains("""d = 12.0.dp"""))
    assertTrue(src, src.contains("""f = 1.5f"""))
    assertTrue(src, src.contains("""i = 3"""))
    assertTrue(src, src.contains("""s = "hi""""))

    // A `0` where the user typed `abc` compiles and is wrong — the one outcome worse than not
    // compiling. It comes back quoted, with the problem named at the point of loss.
    val bad =
      ScreenCodegen.generate(
        Screen("probe", listOf(ScreenNode("probe", knobs = mapOf("i" to "abc")))),
        mapOf("probe" to spec),
      )
    assertTrue(bad.source, bad.source.contains("""i = "abc" /* TODO not a valid value */"""))
  }

  @Test
  fun `a string literal is escaped, including the dollar Kotlin would interpolate`() {
    val spec = ComponentSpec("Probe", knobs = mapOf("s" to KnobSpec("s")))
    val src =
      ScreenCodegen.generate(
          Screen("p", listOf(ScreenNode("probe", knobs = mapOf("s" to "a \"b\" \$c\nd")))),
          mapOf("probe" to spec),
        )
        .source
    assertTrue(src, src.contains("""s = "a \"b\" \${'$'}c\nd""""))
  }

  @Test
  fun `what cannot be generated is reported and marked, never guessed`() {
    val screen =
      Screen(
        "mixed",
        listOf(
          ScreenNode("does-not-exist", children = listOf(ScreenNode("list-header"))),
          ScreenNode("list-header", knobs = mapOf("nope" to "x")),
          ScreenNode("list-header", children = listOf(ScreenNode("list-header"))),
        ),
      )
    val generated = ScreenCodegen.generate(screen, specs)

    assertEquals(
      listOf(
        "no spec for component 'does-not-exist'",
        "component 'list-header' has no parameter for knob 'nope'",
        "component 'list-header' takes no children but has 1",
      ),
      generated.problems,
    )
    // Each loss is marked where it happened, so the gap is visible in the file itself.
    assertTrue(generated.source, generated.source.contains("// TODO unknown component"))
    assertTrue(generated.source, generated.source.contains("// TODO knob 'nope'"))
    assertTrue(generated.source, generated.source.contains("takes no children"))
    // An unknown container still emits its children — losing a subtree reports far less than it
    // destroys.
    assertTrue(generated.source, generated.source.contains("ListHeader()"))
  }

  @Test
  fun `knobs flatten to the indexed seed keys a catalog already reads`() {
    // This is the whole per-instance story: the renderer needs no change, because the screen is
    // flattened into the `key[index]` map the wasm catalog's knob lookup already uses.
    assertEquals(
      mapOf("text[1]" to "Activity", "elevation[2]" to "4", "label[3]" to "Open"),
      listScreen.knobSeeds(),
    )
    // Pre-order, so the indices are derivable identically by whoever renders and whoever edits.
    assertEquals(
      listOf("lazy-column", "list-header", "card", "button-filled"),
      listScreen.flatten().map { it.node.componentId },
    )
    assertEquals(listOf(null, 0, 0, 2), listScreen.flatten().map { it.parentIndex })
  }

  @Test
  fun `two instances of one component get different values, which is the point`() {
    val screen =
      Screen(
        "two",
        listOf(
          ScreenNode("button-filled", knobs = mapOf("label" to "First")),
          ScreenNode("button-filled", knobs = mapOf("label" to "Second")),
        ),
      )
    assertEquals(mapOf("label[0]" to "First", "label[1]" to "Second"), screen.knobSeeds())
  }

  @Test
  fun `a required argument and a content knob make the call one a developer would write`() {
    // Without these the generated `Button` would be missing `onClick` (which the compiler demands)
    // and would try to pass its label as an argument that does not exist.
    val button =
      ComponentSpec(
        call = "Button",
        imports = listOf("androidx.compose.material3.Button", "androidx.compose.material3.Text"),
        knobs = mapOf("enabled" to KnobSpec("enabled", KnobKind.BOOLEAN)),
        requiredArgs = listOf("onClick = {}"),
        contentKnob = "label",
      )
    val generated =
      ScreenCodegen.generate(
        Screen(
          "one button",
          listOf(
            ScreenNode(
              "button-filled",
              knobs = mapOf("label" to "Open", "enabled" to "false"),
            )
          ),
        ),
        mapOf("button-filled" to button),
      )

    assertEquals(emptyList<String>(), generated.problems)
    assertTrue(
      generated.source,
      generated.source.contains("""Button(onClick = {}, enabled = false) { Text("Open") }"""),
    )
  }

  @Test
  fun `a screen round-trips through JSON`() {
    val json = Json { prettyPrint = true }
    val text = json.encodeToString(Screen.serializer(), listScreen)
    assertEquals(listScreen, json.decodeFromString(Screen.serializer(), text))
    assertTrue(text, text.contains("\"schema\": \"compose-ai-screen/v1\""))
  }

  @Test
  fun `a screen name becomes a legal function name, or a safe fallback`() {
    assertEquals("ActivityList", ScreenCodegen.functionNameFor("activity list"))
    assertEquals("MyScreen2", ScreenCodegen.functionNameFor("my-screen 2"))
    assertEquals("Screen2Up", ScreenCodegen.functionNameFor("2 up"))
    assertEquals("GeneratedScreen", ScreenCodegen.functionNameFor("***"))
  }

  @Test
  fun `an empty screen generates a compilable empty composable`() {
    val generated = ScreenCodegen.generate(Screen("blank"), specs)
    assertEquals(emptyList<String>(), generated.problems)
    assertEquals(
      "import androidx.compose.runtime.Composable\n\n@Composable\nfun Blank() {\n" +
        "  // (empty screen)\n}\n",
      generated.source,
    )
  }
}

/** The edits a builder makes, which are pure functions over an immutable document. */
class ScreenEditsTest {

  private val base =
    Screen(
      "s",
      listOf(
        ScreenNode("column", children = listOf(ScreenNode("a"), ScreenNode("b"))),
        ScreenNode("c"),
      ),
    )

  // Pre-order: column=0, a=1, b=2, c=3.

  @Test
  fun `adding appends to the named container, or to the roots`() {
    assertEquals(
      listOf("column", "a", "b", "added", "c"),
      base.addNode(0, ScreenNode("added")).flatten().map { it.node.componentId },
    )
    assertEquals(
      listOf("column", "a", "b", "c", "added"),
      base.addNode(null, ScreenNode("added")).flatten().map { it.node.componentId },
    )
  }

  @Test
  fun `a knob is set on the selected instance and nothing else`() {
    val edited = base.setKnob(2, "label", "Second")
    assertEquals(mapOf("label" to "Second"), edited.nodeAt(2)?.knobs)
    assertEquals(emptyMap<String, String>(), edited.nodeAt(1)?.knobs)
    // …and it lands on the seed key that instance renders under.
    assertEquals(mapOf("label[2]" to "Second"), edited.knobSeeds())
  }

  @Test
  fun `clearing a knob removes it rather than storing an empty value`() {
    // An empty string is a real value for a label; "unset" has to be the absence of the key, or a
    // generated call would pass `text = ""` where the author meant "leave the default".
    val edited = base.setKnob(1, "label", "x").setKnob(1, "label", "")
    assertEquals(emptyMap<String, String>(), edited.nodeAt(1)?.knobs)
  }

  @Test
  fun `removing takes the whole subtree, and renumbers what follows`() {
    val edited = base.removeNode(0)
    assertEquals(listOf("c"), edited.flatten().map { it.node.componentId })
    // `c` was index 3 and is now 0 — an index cached across an edit points at the wrong node, which
    // is why the UI re-reads it rather than holding one.
    assertEquals("c", edited.nodeAt(0)?.componentId)

    assertEquals(
      listOf("column", "b", "c"),
      base.removeNode(1).flatten().map { it.node.componentId },
    )
  }

  @Test
  fun `an edit at an index that does not exist leaves the document alone`() {
    assertEquals(base, base.mapNode(99) { it.copy(componentId = "nope") })
    assertEquals(base, base.removeNode(99))
    assertEquals(null, base.nodeAt(99))
  }
}
