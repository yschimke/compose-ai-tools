package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The value kinds beyond the four literals: a qualified read, a qualified call, an extension chain
 * — and the identity aliasing that lets a builder's own component ids resolve.
 *
 * Kept apart from [ScreenGeneratorTest] because the questions are different. That file asks whether
 * a screen is *assembled* correctly (slots, scopes, imports, opt-ins); this one asks whether a
 * *value* is written correctly, which is where the widening put the new ways to be wrong.
 */
class ScreenValueVocabularyTest {

  private fun component(
    name: String,
    callable: String,
    parameters: List<TargetParameter>,
    componentIds: List<String> = emptyList(),
    canonicalId: String = "app/androidx.compose.material3.${name}Kt.$name",
  ) =
    ComponentRecord(
      canonicalId = canonicalId,
      componentIds = componentIds,
      symbol =
        ComponentSymbol(
          jvmOwner = "androidx.compose.material3.${name}Kt",
          callable = callable,
          name = name,
          origin = ComponentOrigin.LIBRARY,
        ),
      parameters = parameters,
      signatureKnown = true,
      code = ComponentCode(call = "$name()", imports = listOf(callable)),
    )

  private val colorParameter =
    TargetParameter(
      "color",
      "Color",
      typeFqn = "androidx.compose.ui.graphics.Color",
      hasDefault = true,
    )

  private val modifierParameter =
    TargetParameter(
      "modifier",
      "Modifier",
      typeFqn = "androidx.compose.ui.Modifier",
      hasDefault = true,
    )

  private val text =
    component(
      "Text",
      "androidx.compose.material3.Text",
      listOf(
        TargetParameter("text", "String", typeFqn = "kotlin.String"),
        modifierParameter,
        colorParameter,
      ),
      componentIds = listOf("m3/text"),
    )

  private fun catalog(vararg records: ComponentRecord) =
    ComponentRecordFile(module = "app", variant = "debug", components = records.toList())

  private fun generate(root: ScreenNode, catalog: ComponentRecordFile, name: String = "Screen") =
    ScreenGenerator.generate(ScreenDocument(name = name, root = root), catalog)

  private fun emitted(root: ScreenNode, catalog: ComponentRecordFile, name: String = "Screen") =
    generate(root, catalog, name) as ScreenGenerator.Result.Emitted

  private fun refusal(root: ScreenNode, catalog: ComponentRecordFile, name: String = "Screen") =
    (generate(root, catalog, name) as ScreenGenerator.Result.Refused).reasons

  private fun textNode(vararg arguments: Pair<String, ScreenValue>) =
    ScreenNode(
      componentId = "m3/text",
      arguments = mapOf("text" to ScreenValue.Text("hi")) + arguments,
    )

  private val color = "androidx.compose.ui.graphics.Color"
  private val modifier = "androidx.compose.ui.Modifier"

  @Test
  fun `a reference is emitted fully qualified and imported nowhere`() {
    val result =
      emitted(
        textNode(
          "color" to
            ScreenValue.Reference(
              rootFqn = "androidx.compose.material3.MaterialTheme",
              members = listOf("colorScheme", "primary"),
              typeFqn = color,
            )
        ),
        catalog(text),
      )
    assertThat(result.source)
      .contains("color = androidx.compose.material3.MaterialTheme.colorScheme.primary")
    assertThat(result.source).doesNotContain("import androidx.compose.material3.MaterialTheme")
  }

  @Test
  fun `a reference with no members is a bare qualified read`() {
    val result =
      emitted(
        textNode(
          "color" to ScreenValue.Reference("androidx.compose.ui.graphics.Color", typeFqn = color)
        ),
        catalog(text),
      )
    assertThat(result.source).contains("color = androidx.compose.ui.graphics.Color")
  }

  @Test
  fun `a reference claiming the wrong type is refused, as a literal of the wrong type is`() {
    assertThat(
        refusal(
          textNode(
            "color" to
              ScreenValue.Reference(
                "androidx.compose.ui.text.style.TextAlign",
                listOf("Center"),
                typeFqn = "androidx.compose.ui.text.style.TextAlign",
              )
          ),
          catalog(text),
        )
      )
      .containsExactly(
        "`Text`.`color` is androidx.compose.ui.graphics.Color, and this value is a " +
          "androidx.compose.ui.text.style.TextAlign"
      )
  }

  @Test
  fun `a construct is a qualified call with positional then named arguments`() {
    val result =
      emitted(
        textNode(
          "color" to
            ScreenValue.Construct(
              callableFqn = "androidx.compose.ui.graphics.Color",
              positional = listOf(ScreenValue.Fractional(0.5)),
              named = mapOf("alpha" to ScreenValue.Fractional(1.0)),
              typeFqn = color,
            )
        ),
        catalog(text),
      )
    assertThat(result.source)
      .contains("color = androidx.compose.ui.graphics.Color(0.5, alpha = 1.0)")
  }

  @Test
  fun `a chain imports each link and calls it by simple name`() {
    val result =
      emitted(
        textNode(
          "modifier" to
            ScreenValue.Chain(
              receiver = ScreenValue.Reference(modifier, typeFqn = modifier),
              links =
                listOf(
                  ChainLink("androidx.compose.foundation.layout.fillMaxWidth"),
                  ChainLink(
                    "androidx.compose.foundation.layout.padding",
                    positional =
                      listOf(
                        ScreenValue.Chain(
                          receiver = ScreenValue.Whole(16),
                          links = listOf(ChainLink("androidx.compose.ui.unit.dp", property = true)),
                          typeFqn = "androidx.compose.ui.unit.Dp",
                        )
                      ),
                  ),
                ),
              typeFqn = modifier,
            )
        ),
        catalog(text),
      )
    assertThat(result.source)
      .contains("modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(16.dp)")
    assertThat(result.source).contains("import androidx.compose.foundation.layout.fillMaxWidth")
    assertThat(result.source).contains("import androidx.compose.foundation.layout.padding")
    assertThat(result.source).contains("import androidx.compose.ui.unit.dp")
  }

  @Test
  fun `two links claiming one simple name are refused rather than resolved`() {
    assertThat(
        refusal(
          textNode(
            "modifier" to
              ScreenValue.Chain(
                receiver = ScreenValue.Reference(modifier, typeFqn = modifier),
                links =
                  listOf(
                    ChainLink("androidx.compose.foundation.layout.padding"),
                    ChainLink("com.example.decor.padding"),
                  ),
                typeFqn = modifier,
              )
          ),
          catalog(text),
        )
      )
      .containsExactly(
        "`padding` would be imported from androidx.compose.foundation.layout.padding and " +
          "com.example.decor.padding, which Kotlin rejects as a conflicting import"
      )
  }

  @Test
  fun `an extension colliding with an imported component is refused too`() {
    val padding =
      component(
        "padding",
        "com.example.decor.padding",
        listOf(TargetParameter("content", "() -> Unit", composableSlot = true)),
        canonicalId = "app/com.example.decor.PaddingKt.padding",
      )
    assertThat(
        refusal(
          ScreenNode(
            componentId = padding.canonicalId,
            slots =
              mapOf(
                "content" to
                  listOf(
                    textNode(
                      "modifier" to
                        ScreenValue.Chain(
                          receiver = ScreenValue.Reference(modifier, typeFqn = modifier),
                          links = listOf(ChainLink("androidx.compose.foundation.layout.padding")),
                          typeFqn = modifier,
                        )
                    )
                  )
              ),
          ),
          catalog(text, padding),
        )
      )
      .containsExactly(
        "`padding` would be imported from androidx.compose.foundation.layout.padding and " +
          "com.example.decor.padding, which Kotlin rejects as a conflicting import"
      )
  }

  @Test
  fun `a link named after the screen is refused, because the screen would shadow it`() {
    assertThat(
        refusal(
          textNode(
            "modifier" to
              ScreenValue.Chain(
                receiver = ScreenValue.Reference(modifier, typeFqn = modifier),
                links = listOf(ChainLink("com.example.decor.Screen")),
                typeFqn = modifier,
              )
          ),
          catalog(text),
          name = "Screen",
        )
      )
      .containsExactly("`Text`.`modifier` imports `Screen`, which is the screen's own name")
  }

  @Test
  fun `a chain with no links is refused as the reference it actually is`() {
    assertThat(
        refusal(
          textNode(
            "modifier" to
              ScreenValue.Chain(
                receiver = ScreenValue.Reference(modifier, typeFqn = modifier),
                links = emptyList(),
                typeFqn = modifier,
              )
          ),
          catalog(text),
        )
      )
      .containsExactly("`Text`.`modifier` is a chain with no links, which is a plain reference")
  }

  @Test
  fun `a property link that also passes arguments is refused`() {
    assertThat(
        refusal(
          textNode(
            "modifier" to
              ScreenValue.Chain(
                receiver = ScreenValue.Reference(modifier, typeFqn = modifier),
                links =
                  listOf(
                    ChainLink(
                      "androidx.compose.ui.unit.dp",
                      positional = listOf(ScreenValue.Whole(1)),
                      property = true,
                    )
                  ),
                typeFqn = modifier,
              )
          ),
          catalog(text),
        )
      )
      .containsExactly("`Text`.`modifier` reads `dp` as a property and also passes it arguments")
  }

  @Test
  fun `a nested whole past Int range is a Long literal, and a nested fraction a Double`() {
    val result =
      emitted(
        textNode(
          "color" to
            ScreenValue.Construct(
              "androidx.compose.ui.graphics.Color",
              positional = listOf(ScreenValue.Whole(0xFF6750A4), ScreenValue.Fractional(2.0)),
              typeFqn = color,
            )
        ),
        catalog(text),
      )
    assertThat(result.source)
      .contains("color = androidx.compose.ui.graphics.Color(4284960932L, 2.0)")
  }

  @Test
  fun `a name that cannot be written as Kotlin is refused rather than emitted`() {
    assertThat(
        refusal(
          textNode(
            "color" to
              ScreenValue.Reference("androidx.compose.ui.graphics.Color", listOf("a b`c"), color)
          ),
          catalog(text),
        )
      )
      .containsExactly(
        "`Text`.`color` names `a b`c`, which cannot be written as a Kotlin identifier"
      )
  }

  @Test
  fun `a hard keyword in a qualified name is backticked, not refused`() {
    val result =
      emitted(
        textNode(
          "color" to ScreenValue.Reference("com.example.in.Palette", listOf("brand"), color)
        ),
        catalog(text),
      )
    assertThat(result.source).contains("color = com.example.`in`.Palette.brand")
  }

  @Test
  fun `values nested past the depth cap are refused instead of overflowing the stack`() {
    var value: ScreenValue = ScreenValue.Whole(1)
    repeat(20) {
      value =
        ScreenValue.Construct(
          "androidx.compose.ui.graphics.Color",
          positional = listOf(value),
          typeFqn = color,
        )
    }
    assertThat(refusal(textNode("color" to value), catalog(text)))
      .containsExactly("`Text`.`color` nests values more than 16 deep")
  }

  @Test
  fun `a node resolves by catalog alias as well as by canonical id`() {
    val byAlias = emitted(textNode(), catalog(text))
    val byCanonical =
      emitted(
        ScreenNode(text.canonicalId, arguments = mapOf("text" to ScreenValue.Text("hi"))),
        catalog(text),
      )
    assertThat(byAlias.source).isEqualTo(byCanonical.source)
  }

  @Test
  fun `an alias two components claim identifies neither`() {
    val label =
      component("Label", "androidx.compose.material3.Label", emptyList(), listOf("m3/text"))
    assertThat(refusal(textNode(), catalog(text, label)))
      .contains(
        "catalog id `m3/text` maps to 2 components " +
          "(`app/androidx.compose.material3.TextKt.Text`, " +
          "`app/androidx.compose.material3.LabelKt.Label`), so it identifies none of them"
      )
  }

  @Test
  fun `a canonical id wins over another record's alias for the same string`() {
    val shadow =
      component(
        "Label",
        "androidx.compose.material3.Label",
        emptyList(),
        componentIds = listOf(text.canonicalId),
      )
    val result =
      emitted(
        ScreenNode(text.canonicalId, arguments = mapOf("text" to ScreenValue.Text("hi"))),
        catalog(text, shadow),
      )
    assertThat(result.source).contains("Text(text = \"hi\")")
  }
}
