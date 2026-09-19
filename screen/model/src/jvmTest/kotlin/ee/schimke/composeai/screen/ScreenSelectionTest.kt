package ee.schimke.composeai.screen

import ee.schimke.composeai.discovery.ChainLink
import ee.schimke.composeai.discovery.ScreenDocument
import ee.schimke.composeai.discovery.ScreenGenerator
import ee.schimke.composeai.discovery.ScreenNode
import ee.schimke.composeai.discovery.ScreenSelection
import ee.schimke.composeai.discovery.ScreenState
import ee.schimke.composeai.discovery.ScreenValue
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSelectionTest {
  private fun label(text: String) =
    ScreenNode("text", arguments = mapOf("text" to ScreenValue.Text(text)))

  private fun document() =
    ScreenDocument(
      name = "SelectedScreen",
      state = listOf(ScreenState("page", "kotlin.Int", ScreenValue.Whole(10))),
      root =
        ScreenNode(
          componentId = "",
          selection =
            ScreenSelection(
              ScreenValue.StateRead("page", "kotlin.Int"),
              linkedMapOf("first" to ScreenValue.Whole(10), "second" to ScreenValue.Whole(20)),
              elseSlot = "fallback",
            ),
          slots =
            linkedMapOf(
              "first" to listOf(label("First")),
              "second" to listOf(label("Second")),
              "fallback" to listOf(label("Unknown page")),
            ),
        ),
    )

  private fun generate(document: ScreenDocument) =
    ScreenGenerator.generate(
      document,
      M3Palette.records,
      expressionPackages = M3Palette.expressionPackages,
    )

  private fun emitted(document: ScreenDocument): String {
    val result = generate(document)
    assertTrue(result.toString(), result is ScreenGenerator.Result.Emitted)
    return (result as ScreenGenerator.Result.Emitted).source
  }

  private fun refused(document: ScreenDocument, reason: String) {
    val result = generate(document)
    assertTrue(result.toString(), result is ScreenGenerator.Result.Refused)
    assertTrue(
      result.toString(),
      (result as ScreenGenerator.Result.Refused).reasons.any { reason in it },
    )
  }

  @Test
  fun `cases use their values not their positions`() {
    val source = emitted(document())
    assertTrue(source, "when (page.value)" in source)
    assertTrue(source, "10 -> {" in source)
    assertTrue(source, "20 -> {" in source)
    assertTrue(source, source.indexOf("10 ->") < source.indexOf("20 ->"))
    assertTrue(source, "Unknown page" in source)
  }

  @Test
  fun `branches keep the ordinary editing and serialization paths`() {
    val original = document()
    val encoded = Json.encodeToString(ScreenDocument.serializer(), original)
    assertEquals(original, Json.decodeFromString(ScreenDocument.serializer(), encoded))
    assertEquals(
      listOf("", "text", "text", "text"),
      original.flattenNodes().map { it.node.componentId },
    )
    val edited = original.setArgument(2, "text", ScreenValue.Text("Edited"))
    assertTrue(emitted(edited).contains("Edited"))
    assertEquals(original.root.selection, edited.root.selection)
  }

  @Test
  fun `unmatched boolean selection has an empty fallback`() {
    val original = document()
    val root =
      ScreenNode(
        "",
        slots = mapOf("yes" to listOf(label("Shown"))),
        selection =
          ScreenSelection(
            ScreenValue.StateRead("shown", "kotlin.Boolean"),
            mapOf("yes" to ScreenValue.Bool(true)),
          ),
      )
    val source =
      emitted(
        original.copy(
          root = root,
          state = listOf(ScreenState("shown", "kotlin.Boolean", ScreenValue.Bool(false))),
        )
      )
    assertTrue(source, "true -> {" in source)
    assertTrue(source, "else -> {}" in source)
  }

  @Test
  fun `case types and subject declarations are checked`() {
    val original = document()
    val selection = requireNotNull(original.root.selection)
    refused(original.copy(state = emptyList()), "does not declare")
    refused(
      original.copy(
        root =
          original.root.copy(
            selection =
              selection.copy(cases = selection.cases + ("first" to ScreenValue.Text("10")))
          )
      ),
      "which Text is not",
    )
    refused(
      original.copy(
        root =
          original.root.copy(
            selection = selection.copy(subject = ScreenValue.StateRead("page", "kotlin.String"))
          )
      ),
      "declared as a kotlin.Int",
    )
  }

  @Test
  fun `duplicate cases and dropped or reused branches refuse`() {
    val original = document()
    val selection = requireNotNull(original.root.selection)
    refused(
      original.copy(
        root =
          original.root.copy(
            selection =
              selection.copy(cases = selection.cases + ("second" to ScreenValue.Whole(10)))
          )
      ),
      "duplicates 10",
    )
    refused(
      original.copy(root = original.root.copy(selection = selection.copy(elseSlot = "first"))),
      "is also a case",
    )
    refused(
      original.copy(root = original.root.copy(selection = selection.copy(elseSlot = null))),
      "has no case or fallback",
    )
    refused(
      original.copy(root = original.root.copy(slots = original.root.slots - "first")),
      "has no slot `first`",
    )
  }

  @Test
  fun `unselected branches are validated and component arguments cannot disappear`() {
    val original = document()
    refused(
      original.copy(
        root =
          original.root.copy(
            slots = original.root.slots + ("second" to listOf(ScreenNode("missing")))
          )
      ),
      "no component `missing`",
    )
    refused(
      original.copy(
        root = original.root.copy(arguments = mapOf("ignored" to ScreenValue.Whole(1)))
      ),
      "cannot also call a component",
    )
  }

  @Test
  fun `float narrowing cannot create duplicate cases`() {
    val original = document()
    val root =
      original.root.copy(
        selection =
          ScreenSelection(
            ScreenValue.StateRead("page", "kotlin.Float"),
            mapOf(
              "first" to ScreenValue.Fractional(1.0),
              "second" to ScreenValue.Fractional(1.00000000001),
            ),
            "fallback",
          )
      )
    refused(
      original.copy(
        root = root,
        state = listOf(ScreenState("page", "kotlin.Float", ScreenValue.Fractional(1.0))),
      ),
      "duplicates 1.0f",
    )
  }

  @Test
  fun `selection preserves the containing layout receiver for modifiers`() {
    val original = document()
    val text =
      label("Weighted").let { node ->
        node.copy(
          arguments =
            node.arguments +
              ("modifier" to
                ScreenValue.Chain(
                  receiver =
                    ScreenValue.Reference(
                      "androidx.compose.ui.Modifier",
                      typeFqn = "androidx.compose.ui.Modifier",
                    ),
                  links =
                    listOf(
                      ChainLink(
                        "androidx.compose.foundation.layout.ColumnScope.weight",
                        positional = listOf(ScreenValue.Fractional32(1f)),
                        receiverScopeFqn = "androidx.compose.foundation.layout.ColumnScope",
                      )
                    ),
                  typeFqn = "androidx.compose.ui.Modifier",
                ))
        )
      }
    val selection = original.root.copy(slots = original.root.slots + ("first" to listOf(text)))
    val nested =
      original.copy(root = ScreenNode("column", slots = mapOf("content" to listOf(selection))))
    assertTrue(emitted(nested).contains("Modifier.weight(1.0f)"))
    refused(original.copy(root = selection), "has no receiver")
  }

  @Test
  fun `string cases escape interpolation and quotes`() {
    val original = document()
    val selection =
      requireNotNull(original.root.selection)
        .copy(
          subject = ScreenValue.StateRead("page", "kotlin.String"),
          cases =
            mapOf(
              "first" to ScreenValue.Text("\"\${value}"),
              "second" to ScreenValue.Text("ready"),
            ),
        )
    val source =
      emitted(
        original.copy(
          root = original.root.copy(selection = selection),
          state = listOf(ScreenState("page", "kotlin.String", ScreenValue.Text("ready"))),
        )
      )
    assertTrue(source, "\"\\\"\\\${value}\" ->" in source)
  }
}
