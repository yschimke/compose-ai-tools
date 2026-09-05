package ee.schimke.composeai.screen

import ee.schimke.composeai.discovery.ScreenDocument
import ee.schimke.composeai.discovery.ScreenGenerator
import ee.schimke.composeai.discovery.ScreenNode
import ee.schimke.composeai.discovery.ScreenValue
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The builder's palette, driven through the **real** generator.
 *
 * This is the test that says the two halves are actually combined: the document is
 * `discovery.ScreenDocument`, the records are `ComponentRecordFile`, and the source comes from
 * `discovery.ScreenGenerator` — the 1159-line one with 888 lines of its own tests — rather than a
 * second code path written for the browser. What is authored here is data, not generation.
 */
class M3PaletteGenerationTest {

  private val records = M3Palette.records

  private fun generate(document: ScreenDocument) =
    ScreenGenerator.generate(
      document,
      records,
      packageName = "generated.screen",
      // The palette's own allow-list, not a copy: the builder passes exactly this, so a component
      // added without widening it fails here rather than in the browser.
      expressionPackages = M3Palette.expressionPackages,
    )

  @Test
  fun `a scaffold holding a column, a card and a button generates that screen`() {
    // The screen the builder is driven through by hand, as a document.
    val document =
      ScreenDocument(
        name = "MyScreen",
        root =
          ScreenNode(
            componentId = "scaffold",
            slots =
              mapOf(
                "content" to
                  listOf(
                    ScreenNode(
                      componentId = "column",
                      arguments = mapOf("modifier" to fillMaxWidthAndPadding()),
                      slots =
                        mapOf(
                          "content" to
                            listOf(
                              ScreenNode(
                                componentId = "card",
                                slots =
                                  mapOf(
                                    "content" to
                                      listOf(
                                        ScreenNode(
                                          componentId = "button",
                                          slots =
                                            mapOf(
                                              "content" to
                                                listOf(
                                                  ScreenNode(
                                                    componentId = "text",
                                                    arguments =
                                                      mapOf("text" to ScreenValue.Text("Open")),
                                                  )
                                                )
                                            ),
                                        )
                                      )
                                  ),
                              )
                            )
                        ),
                    )
                  )
              ),
          ),
      )

    val result = generate(document)
    // Print the refusals rather than a bare assertion failure — they are the generator's own
    // account of what it could not prove, and that is what a builder shows its user.
    if (result is ScreenGenerator.Result.Refused) {
      println("REFUSED:\n  " + result.reasons.joinToString("\n  "))
    }
    assertTrue("expected an emitted screen", result is ScreenGenerator.Result.Emitted)
    val source = (result as ScreenGenerator.Result.Emitted).source
    println(source)
    assertTrue(source, source.contains("Scaffold("))
    assertTrue(source, source.contains("ElevatedCard("))
    assertTrue(source, source.contains("Button("))
    assertTrue(source, source.contains("\"Open\""))

    // Every component by its **simple** name, imported once. All but the root sit inside a slot,
    // and the generator used to qualify those, so a whole screen came out written in full. A
    // `contains("Text(")` would pass either way — `androidx.compose.material3.Text(` ends in the
    // same characters — so the absence of the qualified spelling is what actually pins this.
    listOf("Scaffold", "Column", "ElevatedCard", "Button", "Text").forEach { name ->
      assertTrue("no import for $name in:\n$source", source.contains(".$name\n"))
    }
    assertTrue(source, !source.contains("androidx.compose.material3.Text(text ="))
    assertTrue(source, !source.contains("androidx.compose.material3.ElevatedCard("))

    // `Modifier.padding` takes `Dp`. The palette used to pass a bare `Int`, and since a chain
    // link's arguments are not checked against a real signature, `padding(8)` was emitted happily
    // and only the Kotlin compiler objected.
    assertTrue(source, source.contains("padding(8.dp)"))
  }

  /**
   * The modifier chain a user builds by tapping two chips, as the generator's own vocabulary.
   *
   * Taken from [M3Palette.modifierLinks] rather than rebuilt here, so the links this asserts on are
   * the ones the builder actually offers — `padding` shipped passing a bare `Int` to a `Dp`
   * parameter, and a copy of the link in the test would have agreed with it.
   */
  private fun fillMaxWidthAndPadding(): ScreenValue =
    ScreenValue.Chain(
      receiver = M3Palette.modifierReceiver,
      links =
        listOf("fillMaxWidth", "padding(8.dp)").map { label ->
          M3Palette.modifierLinks.first { it.first == label }.second
        },
      typeFqn = "androidx.compose.ui.Modifier",
    )
}
