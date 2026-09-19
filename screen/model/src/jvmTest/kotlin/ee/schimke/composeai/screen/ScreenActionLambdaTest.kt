package ee.schimke.composeai.screen

import ee.schimke.composeai.discovery.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ScreenActionLambdaTest {
  private fun document(actions: List<ScreenAction>) =
    ScreenDocument(
      "ClickableLayout",
      state = listOf(ScreenState("page", "kotlin.Int", ScreenValue.Whole(10))),
      root =
        ScreenNode(
          "column",
          arguments =
            mapOf(
              "modifier" to
                ScreenValue.Chain(
                  ScreenValue.Reference(
                    "androidx.compose.ui.Modifier",
                    typeFqn = "androidx.compose.ui.Modifier",
                  ),
                  listOf(
                    ChainLink(
                      "androidx.compose.foundation.clickable",
                      named = mapOf("onClick" to ScreenValue.ActionLambda(actions)),
                    )
                  ),
                  typeFqn = "androidx.compose.ui.Modifier",
                )
            ),
          slots =
            mapOf(
              "content" to
                listOf(ScreenNode("text", arguments = mapOf("text" to ScreenValue.Text("Next"))))
            ),
        ),
    )

  private fun generate(doc: ScreenDocument) =
    ScreenGenerator.generate(
      doc,
      M3Palette.records,
      expressionPackages = M3Palette.expressionPackages + "androidx.compose.foundation",
    )

  @Test
  fun `nested callback preserves ordered typed writes and serialization`() {
    val doc =
      document(
        listOf(
          ScreenAction.Set("page", ScreenValue.Whole(20)),
          ScreenAction.Set("page", ScreenValue.Whole(30)),
        )
      )
    assertEquals(
      doc,
      Json.decodeFromString<ScreenDocument>(Json.encodeToString(ScreenDocument.serializer(), doc)),
    )
    val result = generate(doc)
    assertTrue(result.toString(), result is ScreenGenerator.Result.Emitted)
    val source = (result as ScreenGenerator.Result.Emitted).source
    assertTrue(source, ".clickable(onClick = { page.value = 20; page.value = 30 })" in source)
  }

  @Test
  fun `nested callbacks retain handler validation`() {
    for ((actions, reason) in
      listOf(
        emptyList<ScreenAction>() to "no actions",
        listOf(ScreenAction.Set("missing", ScreenValue.Whole(20))) to "does not declare",
        listOf(ScreenAction.Set("page", ScreenValue.Text("twenty"))) to "not",
        listOf(ScreenAction.Toggle("page")) to "rather than a kotlin.Boolean",
        listOf(
          ScreenAction.Set(
            "page",
            ScreenValue.Reference("kotlin.Int.MAX_VALUE", typeFqn = "kotlin.Int"),
          )
        ) to "cannot evaluate",
      )) {
      val result = generate(document(actions))
      assertTrue(result.toString(), result is ScreenGenerator.Result.Refused)
      assertTrue(
        result.toString(),
        (result as ScreenGenerator.Result.Refused).reasons.any { reason in it },
      )
    }
  }

  @Test
  fun `action callback cannot be passed as text or composable content`() {
    val original = document(listOf(ScreenAction.Set("page", ScreenValue.Whole(20))))
    val value = ScreenValue.ActionLambda(listOf(ScreenAction.Set("page", ScreenValue.Whole(20))))
    for (node in
      listOf(
        ScreenNode("text", arguments = mapOf("text" to value)),
        ScreenNode("column", arguments = mapOf("content" to value)),
      )) {
      assertTrue(
        generate(original.copy(root = node)).toString(),
        generate(original.copy(root = node)) is ScreenGenerator.Result.Refused,
      )
    }
  }
}
