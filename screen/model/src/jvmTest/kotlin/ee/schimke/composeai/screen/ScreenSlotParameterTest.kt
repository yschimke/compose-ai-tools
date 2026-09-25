package ee.schimke.composeai.screen

import ee.schimke.composeai.discovery.*
import org.junit.Assert.*
import org.junit.Test

/**
 * A slot lambda's parameter, named by `ScreenNode.slotParameters` and read by
 * `ScreenValue.SlotParameterRead` — `Scaffold { padding -> Column(Modifier.padding(padding)) }`,
 * which a bare `{ … }` could not express, so the body drew under the bars.
 */
class ScreenSlotParameterTest {
  private val paddingValues = "androidx.compose.foundation.layout.PaddingValues"

  private fun padded(read: ScreenValue): ScreenValue =
    ScreenValue.Chain(
      receiver = M3Palette.modifierReceiver,
      links = listOf(ChainLink("androidx.compose.foundation.layout.padding", listOf(read))),
      typeFqn = "androidx.compose.ui.Modifier",
    )

  private fun column(modifier: ScreenValue) =
    ScreenNode(
      "column",
      arguments = mapOf("modifier" to modifier),
      slots =
        mapOf("content" to listOf(ScreenNode("text", mapOf("text" to ScreenValue.Text("Hi"))))),
    )

  private fun scaffold(
    body: ScreenNode,
    slotParameters: Map<String, String> = mapOf("content" to "contentPadding"),
  ) =
    ScreenNode(
      "scaffold",
      slots = mapOf("content" to listOf(body)),
      slotParameters = slotParameters,
    )

  private fun generate(node: ScreenNode) =
    ScreenGenerator.generate(
      ScreenDocument("PaddedScreen", node),
      M3Palette.records,
      expressionPackages = M3Palette.expressionPackages,
    )

  private fun emitted(node: ScreenNode): String {
    val result = generate(node)
    assertTrue(result.toString(), result is ScreenGenerator.Result.Emitted)
    return (result as ScreenGenerator.Result.Emitted).source
  }

  private fun refused(node: ScreenNode, reason: String) {
    val result = generate(node)
    assertTrue(result.toString(), result is ScreenGenerator.Result.Refused)
    assertTrue(
      result.toString(),
      (result as ScreenGenerator.Result.Refused).reasons.any { reason in it },
    )
  }

  @Test
  fun `a named slot parameter is the lambda's and the body pads by it`() {
    val source =
      emitted(
        scaffold(column(padded(ScreenValue.SlotParameterRead("contentPadding", paddingValues))))
      )
    assertTrue(source, "Scaffold {" !in source)
    assertTrue(source, "{ contentPadding ->" in source)
    assertTrue(source, "Modifier.padding(contentPadding)" in source)
  }

  @Test
  fun `an unbound slot keeps its bare lambda`() {
    val source = emitted(scaffold(column(M3Palette.modifierReceiver), slotParameters = emptyMap()))
    assertTrue(source, "->" !in source.substringAfter("Scaffold"))
  }

  @Test
  fun `a read no enclosing slot binds is refused`() {
    refused(
      column(padded(ScreenValue.SlotParameterRead("contentPadding", paddingValues))),
      "no enclosing slot binds it",
    )
  }

  @Test
  fun `a read claiming another type is refused`() {
    refused(
      scaffold(column(padded(ScreenValue.SlotParameterRead("contentPadding", "kotlin.Int")))),
      "that slot's lambda takes PaddingValues",
    )
  }

  @Test
  fun `a slot whose lambda takes no parameter cannot be named`() {
    refused(
      ScreenNode(
        "surface",
        slots = mapOf("content" to listOf(column(M3Palette.modifierReceiver))),
        slotParameters = mapOf("content" to "nothing"),
      ),
      "has no single parameter to name",
    )
  }

  @Test
  fun `naming a slot with no children is refused`() {
    refused(
      ScreenNode(
        "scaffold",
        slots = mapOf("content" to listOf(column(M3Palette.modifierReceiver))),
        slotParameters = mapOf("topBar" to "bar"),
      ),
      "names its lambda parameter and has no children",
    )
  }

  @Test
  fun `a generic claim does not match a plain parameter by its last segment`() {
    refused(
      scaffold(
        column(
          padded(
            ScreenValue.SlotParameterRead(
              "contentPadding",
              "kotlin.collections.List<androidx.compose.foundation.layout.PaddingValues>",
            )
          )
        )
      ),
      "that slot's lambda takes PaddingValues",
    )
  }

  @Test
  fun `a structural node cannot name a slot parameter`() {
    refused(
      ScreenNode(
        "",
        slots = mapOf("body" to listOf(column(M3Palette.modifierReceiver))),
        repetition = ScreenRepetition(emptyMap(), listOf(emptyMap())),
        slotParameters = mapOf("body" to "row"),
      ),
      "has no slot lambda to name a parameter of",
    )
  }
}
