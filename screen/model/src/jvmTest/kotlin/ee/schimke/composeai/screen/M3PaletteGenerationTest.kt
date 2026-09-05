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
  fun `a scaffold holding a lazy column, a card and a button generates that screen`() {
    // The screen the builder was tested with, now with the scaffold the goal asks for.
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
                      componentId = "lazy-column",
                      arguments = mapOf("modifier" to fillMaxWidth()),
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
    assertTrue(source, source.contains("LazyColumn("))
    assertTrue(source, source.contains("ElevatedCard("))
    assertTrue(source, source.contains("Button("))
    assertTrue(source, source.contains("\"Open\""))
  }

  /** `Modifier.fillMaxWidth()` as the generator's own vocabulary — a chain, not spliced text. */
  private fun fillMaxWidth(): ScreenValue =
    ScreenValue.Chain(
      receiver = M3Palette.modifierReceiver,
      links = listOf(M3Palette.modifierLinks.first { it.first == "fillMaxWidth" }.second),
      typeFqn = "androidx.compose.ui.Modifier",
    )
}
