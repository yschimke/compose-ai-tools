package ee.schimke.composeai.screen

import ee.schimke.composeai.discovery.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ScreenRepetitionTest {
  private fun text(value: ScreenValue) = ScreenNode("text", arguments = mapOf("text" to value))

  private fun loop(
    fields: Map<String, String> = mapOf("caption" to "kotlin.String"),
    rows: List<Map<String, ScreenValue>> =
      listOf(
        mapOf("caption" to ScreenValue.Text("One")),
        mapOf("caption" to ScreenValue.Text("Two")),
      ),
    body: ScreenNode = text(ScreenValue.RowRead("caption", "kotlin.String")),
  ) =
    ScreenNode(
      "",
      slots = mapOf("body" to listOf(body)),
      repetition = ScreenRepetition(fields, rows),
    )

  private fun generate(node: ScreenNode, state: List<ScreenState> = emptyList()) =
    ScreenGenerator.generate(
      ScreenDocument("RowsScreen", node, state),
      M3Palette.records,
      expressionPackages = M3Palette.expressionPackages,
    )

  private fun emitted(node: ScreenNode, state: List<ScreenState> = emptyList()): String {
    val result = generate(node, state)
    assertTrue(result.toString(), result is ScreenGenerator.Result.Emitted)
    return (result as ScreenGenerator.Result.Emitted).source
  }

  private fun refused(node: ScreenNode, reason: String, state: List<ScreenState> = emptyList()) {
    val result = generate(node, state)
    assertTrue(result.toString(), result is ScreenGenerator.Result.Refused)
    assertTrue(
      result.toString(),
      (result as ScreenGenerator.Result.Refused).reasons.any { reason in it },
    )
  }

  @Test
  fun `typed rows retain actual repetition and template editing`() {
    val node = loop()
    val document = ScreenDocument("RowsScreen", node)
    assertEquals(
      document,
      Json.decodeFromString(
        ScreenDocument.serializer(),
        Json.encodeToString(ScreenDocument.serializer(), document),
      ),
    )
    val source = emitted(node)
    assertTrue(source, "data class ScreenRow(val field0: kotlin.String)" in source)
    assertTrue(source, "ScreenRow(\"One\"), ScreenRow(\"Two\")" in source)
    assertTrue(source, ".forEach { screenRow ->" in source)
    assertTrue(source, "text = screenRow.field0" in source)
    assertEquals(listOf("", "text"), document.flattenNodes().map { it.node.componentId })
  }

  @Test
  fun `empty rows still type check the template`() {
    assertTrue(emitted(loop(rows = emptyList())).contains("listOf<ScreenRow>()"))
    refused(
      loop(rows = emptyList(), body = text(ScreenValue.RowRead("absent", "kotlin.String"))),
      "no such field",
    )
    refused(loop(rows = emptyList(), body = ScreenNode("missing")), "no component")
  }

  @Test
  fun `every row must supply the declared field types`() {
    refused(loop(rows = listOf(emptyMap())), "must supply exactly")
    refused(loop(rows = listOf(mapOf("caption" to ScreenValue.Bool(true)))), "which Bool is not")
    refused(
      loop(
        rows = listOf(mapOf("caption" to ScreenValue.Text("ok"), "ignored" to ScreenValue.Whole(3)))
      ),
      "must supply exactly",
    )
    refused(
      loop(body = text(ScreenValue.RowRead("caption", "kotlin.Boolean"))),
      "scope declares kotlin.String",
    )
    refused(text(ScreenValue.RowRead("caption", "kotlin.String")), "no such field")
  }

  @Test
  fun `nested initializers read the outer row and templates read only the inner row`() {
    val inner =
      loop(rows = listOf(mapOf("caption" to ScreenValue.RowRead("caption", "kotlin.String"))))
    val source = emitted(loop(body = inner))
    assertTrue(source, "ScreenRow_1(screenRow.field0)" in source)
    assertTrue(source, "text = screenRow_1.field0" in source)
    refused(loop(body = loop(fields = emptyMap(), rows = listOf(emptyMap()))), "no such field")
  }

  @Test
  fun `row values feed checked callback assignments`() {
    val node =
      loop(
        fields = mapOf("caption" to "kotlin.String"),
        body =
          ScreenNode(
            "button",
            handlers =
              mapOf(
                "onClick" to
                  listOf(
                    ScreenAction.Set("caption", ScreenValue.RowRead("caption", "kotlin.String"))
                  )
              ),
          ),
      )
    val source =
      emitted(node, listOf(ScreenState("caption", "kotlin.String", ScreenValue.Text("initial"))))
    assertTrue(source, "caption.value = screenRow.field0" in source)
  }

  @Test
  fun `field keys are data and generated local names avoid state capture`() {
    val key = "not a Kotlin name; \n\""
    val node =
      loop(
        fields = mapOf(key to "kotlin.String"),
        rows = listOf(mapOf(key to ScreenValue.Text("safe"))),
        body = text(ScreenValue.RowRead(key, "kotlin.String")),
      )
    val source =
      emitted(
        node,
        listOf(
          ScreenState("screenRow", "kotlin.String", ScreenValue.Text("outside")),
          ScreenState("ScreenRow", "kotlin.Int", ScreenValue.Whole(0)),
        ),
      )
    assertTrue(source, ".forEach { screenRow_1 ->" in source)
    assertTrue(source, "data class ScreenRow_1(" in source)
    assertFalse(source, key in source)
    refused(
      loop(),
      "shadow",
      listOf(ScreenState("kotlin", "kotlin.String", ScreenValue.Text("outside"))),
    )
  }

  @Test
  fun `conflicting node semantics and malformed types refuse`() {
    refused(loop().copy(componentId = "column"), "cannot also call")
    refused(
      loop().copy(selection = ScreenSelection(ScreenValue.Bool(true), emptyMap())),
      "cannot also call",
    )
    refused(loop().copy(slots = emptyMap()), "template slot")
    refused(
      loop(fields = mapOf("caption" to "kotlin.String); error(\"injected\")")),
      "qualified non-function",
    )
    refused(loop(rows = List(10_001) { mapOf("caption" to ScreenValue.Text("x")) }), "10000")
  }
}
