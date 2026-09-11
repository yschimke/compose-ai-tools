package ee.schimke.composeai.screen

import ee.schimke.composeai.discovery.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ScreenFunctionTest {
  private fun text(value: ScreenValue) = ScreenNode("text", arguments = mapOf("text" to value))

  private fun read(name: String, type: String = "kotlin.String") =
    ScreenValue.ParameterRead(name, type)

  private fun call(label: ScreenValue = ScreenValue.Text("First")) =
    ScreenNode(
      "",
      function = "Choice",
      arguments = mapOf("label" to label),
      handlers =
        mapOf("choose" to listOf(ScreenAction.Set("selected", ScreenValue.Text("chosen")))),
    )

  private fun document() =
    ScreenDocument(
      "FunctionScreen",
      call(),
      state = listOf(ScreenState("selected", "kotlin.String", ScreenValue.Text("initial"))),
      functions =
        listOf(
          ScreenFunction(
            "Choice",
            listOf(
              ScreenParameter.Value("label", "kotlin.String"),
              ScreenParameter.Callback("choose"),
            ),
            ScreenNode(
              "button",
              arguments = mapOf("onClick" to read("choose", "kotlin.Function0")),
              slots = mapOf("content" to listOf(text(read("label")))),
            ),
          )
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

  private fun body(document: ScreenDocument, root: ScreenNode) =
    document.copy(functions = listOf(document.functions.single().copy(root = root)))

  @Test
  fun `functions serialize and emit one definition with explicit callbacks`() {
    val document = document()
    assertEquals(
      document,
      Json.decodeFromString(
        ScreenDocument.serializer(),
        Json.encodeToString(ScreenDocument.serializer(), document),
      ),
    )
    val source = emitted(document)
    assertTrue(
      source,
      "private fun Choice(label: kotlin.String, choose: () -> kotlin.Unit)" in source,
    )
    assertTrue(source, "onClick = choose" in source)
    assertTrue(source, "choose = { selected.value = \"chosen\" }" in source)
    assertEquals(1, Regex("private fun Choice").findAll(source).count())
  }

  @Test
  fun `repeated calls forward each row value into a shared function`() {
    val doc = document()
    val loop =
      ScreenNode(
        "",
        repetition =
          ScreenRepetition(
            mapOf("caption" to "kotlin.String"),
            listOf(
              mapOf("caption" to ScreenValue.Text("One")),
              mapOf("caption" to ScreenValue.Text("Two")),
            ),
          ),
        slots = mapOf("body" to listOf(call(ScreenValue.RowRead("caption", "kotlin.String")))),
      )
    val source = emitted(doc.copy(root = loop))
    assertTrue(source, "Choice(label = screenRow.field0" in source)
    assertEquals(1, Regex("private fun Choice").findAll(source).count())
  }

  @Test
  fun `every call checks arguments and callback kinds`() {
    val doc = document()
    refused(doc.copy(root = doc.root.copy(arguments = emptyMap())), "missing parameter `label`")
    refused(
      doc.copy(root = doc.root.copy(arguments = mapOf("label" to ScreenValue.Bool(true)))),
      "which Bool is not",
    )
    refused(
      doc.copy(
        root = doc.root.copy(arguments = doc.root.arguments + ("extra" to ScreenValue.Text("lost")))
      ),
      "no parameter `extra`",
    )
    refused(
      doc.copy(
        root = doc.root.copy(arguments = doc.root.arguments + ("choose" to ScreenValue.Text("bad")))
      ),
      "supplies `choose` twice",
    )
    refused(
      doc.copy(
        root =
          doc.root.copy(
            handlers = emptyMap(),
            arguments = doc.root.arguments + ("choose" to ScreenValue.Bool(true)),
          )
      ),
      "which Bool is not",
    )
  }

  @Test
  fun `function scopes refuse implicit row and state capture`() {
    val doc = document()
    refused(body(doc, text(ScreenValue.RowRead("caption", "kotlin.String"))), "no such field")
    refused(
      body(doc, text(ScreenValue.StateRead("selected", "kotlin.String"))),
      "cannot capture screen state",
    )
    refused(
      body(doc, ScreenNode("button", handlers = doc.root.handlers.mapKeys { "onClick" })),
      "pass a callback",
    )
    refused(doc.copy(root = text(read("label"))), "does not declare that type")
    refused(body(doc, text(read("label", "kotlin.Boolean"))), "does not declare that type")
  }

  @Test
  fun `functions can forward parameters and callbacks to other definitions`() {
    val doc = document()
    val forwarded =
      doc.functions
        .single()
        .copy(
          name = "Forward",
          root =
            ScreenNode(
              "",
              function = "Choice",
              arguments =
                mapOf("label" to read("label"), "choose" to read("choose", "kotlin.Function0")),
            ),
        )
    val source =
      emitted(
        doc.copy(root = doc.root.copy(function = "Forward"), functions = doc.functions + forwarded)
      )
    assertTrue(source, "Choice(label = label, choose = choose)" in source)
  }

  @Test
  fun `recursive definitions refuse including uncalled definitions`() {
    val doc = document()
    refused(body(doc, ScreenNode("", function = "Choice")), "recursive")
    val one = ScreenFunction("One", emptyList(), ScreenNode("", function = "Two"))
    val two = ScreenFunction("Two", emptyList(), ScreenNode("", function = "One"))
    refused(doc.copy(functions = doc.functions + one + two), "recursive")
    refused(doc.copy(root = doc.root.copy(function = "Missing")), "no generated function")
  }

  @Test
  fun `callback parameters cannot become composable slots or scalar values`() {
    val doc = document()
    refused(
      body(
        doc,
        ScreenNode(
          "button",
          arguments =
            mapOf(
              "onClick" to read("choose", "kotlin.Function0"),
              "content" to read("choose", "kotlin.Function0"),
            ),
        ),
      ),
      "zero-argument Unit callback",
    )
    refused(body(doc, text(read("choose", "kotlin.Function0"))), "zero-argument Unit callback")
  }

  @Test
  fun `names and types cannot corrupt declarations or capture calls`() {
    val doc = document()
    refused(doc.copy(functions = doc.functions + doc.functions), "must be unique")
    refused(
      doc.copy(functions = listOf(doc.functions.single().copy(name = "FunctionScreen"))),
      "available Kotlin",
    )
    refused(
      doc.copy(
        functions =
          listOf(
            doc.functions
              .single()
              .copy(parameters = listOf(ScreenParameter.Value("kotlin", "kotlin.String")))
          )
      ),
      "shadow",
    )
    refused(
      doc.copy(
        functions =
          listOf(
            doc.functions
              .single()
              .copy(parameters = listOf(ScreenParameter.Value("label", "kotlin.String);bad()")))
          )
      ),
      "concrete qualified",
    )
    refused(doc.copy(root = doc.root.copy(componentId = "text")), "cannot also call")
    refused(
      doc.copy(root = doc.root.copy(repetition = ScreenRepetition(emptyMap(), emptyList()))),
      "cannot also",
    )
  }
}
