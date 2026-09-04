package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * `ScreenDocument` is wire data, and nothing exercised it as such.
 *
 * A sealed subtype needs its own `@Serializable`, and a missing one is not a compile error — it
 * fails when a document carrying that shape is encoded, which is to say in a producer nobody runs
 * in this module's tests. `ScreenValue.Chain` lost its annotation to a subtype inserted between it
 * and the annotation, and every check in this repository stayed green. A round trip over one
 * document holding every shape is the check that would not have.
 */
class ScreenDocumentSerializationTest {
  private val json = Json { prettyPrint = false }

  private val everyShape =
    ScreenDocument(
      name = "EveryShape",
      root =
        ScreenNode(
          componentId = "m3/text",
          arguments =
            mapOf(
              "text" to ScreenValue.Text("hello"),
              "enabled" to ScreenValue.Bool(true),
              "count" to ScreenValue.Whole(7),
              "ratio" to ScreenValue.Fractional(0.5),
              "colour" to
                ScreenValue.Reference(
                  rootFqn = "androidx.compose.material3.MaterialTheme",
                  members = listOf("colorScheme", "primary"),
                  typeFqn = "androidx.compose.ui.graphics.Color",
                ),
              "padding" to
                ScreenValue.Construct(
                  callableFqn = "androidx.compose.foundation.layout.PaddingValues",
                  positional = listOf(ScreenValue.Whole(16)),
                  typeFqn = "androidx.compose.foundation.layout.PaddingValues",
                ),
              "label" to ScreenValue.StateRead("caption", "kotlin.String"),
              "modifier" to
                ScreenValue.Chain(
                  receiver =
                    ScreenValue.Reference(
                      rootFqn = "androidx.compose.ui.Modifier",
                      typeFqn = "androidx.compose.ui.Modifier",
                    ),
                  links =
                    listOf(
                      ChainLink(callableFqn = "androidx.compose.foundation.layout.fillMaxWidth"),
                      ChainLink(callableFqn = "androidx.compose.ui.unit.dp", property = true),
                    ),
                  typeFqn = "androidx.compose.ui.Modifier",
                ),
            ),
          handlers =
            mapOf(
              "onClick" to
                listOf(
                  ScreenAction.Toggle("expanded"),
                  ScreenAction.Set("caption", ScreenValue.Text("tapped")),
                )
            ),
        ),
      state =
        listOf(
          ScreenState("expanded", "kotlin.Boolean", ScreenValue.Bool(false)),
          ScreenState("caption", "kotlin.String", ScreenValue.Text("a")),
        ),
    )

  @Test
  fun `a document holding every value shape survives a round trip`() {
    val encoded = json.encodeToString(ScreenDocument.serializer(), everyShape)

    assertThat(json.decodeFromString(ScreenDocument.serializer(), encoded)).isEqualTo(everyShape)
  }

  @Test
  fun `a chain encodes rather than failing for want of a serializer`() {
    // The specific shape that lost its annotation. Named on its own so a failure says which one.
    val chain =
      everyShape.copy(
        root = ScreenNode(componentId = "m3/text", arguments = everyShape.root.arguments)
      )

    val encoded = json.encodeToString(ScreenDocument.serializer(), chain)

    assertThat(encoded).contains("fillMaxWidth")
    assertThat(json.decodeFromString(ScreenDocument.serializer(), encoded)).isEqualTo(chain)
  }
}
