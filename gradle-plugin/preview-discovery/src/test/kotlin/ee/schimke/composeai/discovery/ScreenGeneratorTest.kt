package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenGeneratorTest {

  private fun component(
    name: String,
    callable: String,
    parameters: List<TargetParameter>,
    call: String = "$name()",
    requiredOptIns: List<String> = emptyList(),
  ) =
    ComponentRecord(
      canonicalId = "app/androidx.compose.material3.${name}Kt.$name",
      symbol =
        ComponentSymbol(
          jvmOwner = "androidx.compose.material3.${name}Kt",
          callable = callable,
          name = name,
          origin = ComponentOrigin.LIBRARY,
        ),
      parameters = parameters,
      signatureKnown = true,
      code =
        ComponentCode(call = call, imports = listOf(callable), requiredOptIns = requiredOptIns),
    )

  private val text =
    component(
      "Text",
      "androidx.compose.material3.Text",
      listOf(TargetParameter("text", "String", typeFqn = "kotlin.String")),
    )

  private val card =
    component(
      "Card",
      "androidx.compose.material3.Card",
      listOf(
        TargetParameter(
          "modifier",
          "Modifier",
          typeFqn = "androidx.compose.ui.Modifier",
          hasDefault = true,
        ),
        TargetParameter("content", "ColumnScope.() -> Unit", composableSlot = true),
      ),
    )

  private fun catalog(vararg records: ComponentRecord) =
    ComponentRecordFile(module = "app", variant = "debug", components = records.toList())

  private fun emitted(document: ScreenDocument, catalog: ComponentRecordFile) =
    ScreenGenerator.generate(document, catalog) as ScreenGenerator.Result.Emitted

  private fun refusal(document: ScreenDocument, catalog: ComponentRecordFile) =
    (ScreenGenerator.generate(document, catalog) as ScreenGenerator.Result.Refused).reasons

  @Test
  fun `a screen binds the document's values and nests children into slots`() {
    val screen =
      ScreenDocument(
        name = "HomeScreen",
        root =
          ScreenNode(
            componentId = card.canonicalId,
            slots =
              mapOf(
                "content" to
                  listOf(
                    ScreenNode(
                      text.canonicalId,
                      arguments = mapOf("text" to ScreenValue.Text("Hello")),
                    )
                  )
              ),
          ),
      )

    val source = emitted(screen, catalog(card, text)).source

    // The user's value, not the placeholder `Text(text = "")` the call-site generator prints.
    assertThat(source).contains("""Text(text = "Hello")""")
    assertThat(source).contains("Card(content = {")
    assertThat(source).contains("fun HomeScreen()")
    assertThat(source).contains("import androidx.compose.material3.Card")
    assertThat(source).contains("import androidx.compose.material3.Text")
    // `modifier` is defaulted and untouched, so it is omitted rather than guessed at.
    assertThat(source).doesNotContain("modifier =")
  }

  @Test
  fun `a component the catalog no longer has is refused, not invented`() {
    val screen = ScreenDocument("Screen", ScreenNode("app/com.example.GoneKt.Gone"))

    assertThat(refusal(screen, catalog(text)).single())
      .contains("no component `app/com.example.GoneKt.Gone`")
  }

  @Test
  fun `a component whose call site was refused stays refused here`() {
    // The whole point of gating on `code`: every protection the call-site generator learned — the
    // private, the generic, the collided — arrives here without being re-derived.
    val private =
      component("Secret", "com.example.Secret", emptyList(), call = "Secret()")
        .copy(
          code =
            ComponentCode(
              refusedReason = "not public or internal, so a generated file cannot call it"
            )
        )
    val screen = ScreenDocument("Screen", ScreenNode(private.canonicalId))

    assertThat(refusal(screen, catalog(private)).single()).contains("not public or internal")
  }

  @Test
  fun `a property the component does not declare is refused rather than dropped`() {
    // Dropping it silently would generate a screen that compiles and is not the one designed.
    val screen =
      ScreenDocument(
        "Screen",
        ScreenNode(text.canonicalId, arguments = mapOf("caption" to ScreenValue.Text("x"))),
      )

    assertThat(refusal(screen, catalog(text)).first()).contains("has no parameter `caption`")
  }

  @Test
  fun `a value of the wrong type is refused`() {
    val screen =
      ScreenDocument(
        "Screen",
        ScreenNode(text.canonicalId, arguments = mapOf("text" to ScreenValue.Bool(true))),
      )

    assertThat(refusal(screen, catalog(text)).first()).contains("kotlin.String, which Bool is not")
  }

  @Test
  fun `a domain type that renders like a Kotlin scalar does not accept a literal`() {
    // The trap that bit the call-site generator twice: `com.example.String` renders as `String`.
    val odd =
      component(
        "Odd",
        "com.example.Odd",
        listOf(TargetParameter("value", "String", typeFqn = "com.example.String")),
      )
    val screen =
      ScreenDocument(
        "Screen",
        ScreenNode(odd.canonicalId, arguments = mapOf("value" to ScreenValue.Text("x"))),
      )

    assertThat(refusal(screen, catalog(odd)).first()).contains("com.example.String")
  }

  @Test
  fun `text is escaped so ordinary characters cannot break the generated file`() {
    val screen =
      ScreenDocument(
        "Screen",
        ScreenNode(
          text.canonicalId,
          arguments = mapOf("text" to ScreenValue.Text("""a "quote", a ${'$'}var and a \ slash""")),
        ),
      )

    val source = emitted(screen, catalog(text)).source

    // `$` matters as much as `"`: unescaped it becomes a template for a variable that is not there.
    assertThat(source).contains("""\"quote\"""")
    assertThat(source).contains("""\${'$'}var""")
    assertThat(source).contains("""\\ slash""")
  }

  @Test
  fun `required opt-ins are applied to the generated file, not left to the caller`() {
    val experimental =
      component(
        "Fancy",
        "androidx.compose.material3.Fancy",
        emptyList(),
        requiredOptIns = listOf("androidx.compose.material3.ExperimentalMaterial3Api"),
      )
    val screen = ScreenDocument("Screen", ScreenNode(experimental.canonicalId))

    val emitted = emitted(screen, catalog(experimental))

    assertThat(emitted.source).contains("@OptIn(ExperimentalMaterial3Api::class)")
    assertThat(emitted.source)
      .contains("import androidx.compose.material3.ExperimentalMaterial3Api")
    assertThat(emitted.requiredOptIns)
      .containsExactly("androidx.compose.material3.ExperimentalMaterial3Api")
  }

  @Test
  fun `every problem is reported, not just the first`() {
    // A builder wants the whole list to act on; fixing one error at a time is a bad loop.
    val screen =
      ScreenDocument(
        "Screen",
        ScreenNode(
          card.canonicalId,
          arguments = mapOf("nope" to ScreenValue.Text("x")),
          slots = mapOf("content" to listOf(ScreenNode("app/com.example.GoneKt.Gone"))),
        ),
      )

    assertThat(refusal(screen, catalog(card))).hasSize(2)
  }

  @Test
  fun `a screen name that is not an identifier is refused`() {
    assertThat(refusal(ScreenDocument("my screen", ScreenNode(text.canonicalId)), catalog(text)))
      .containsExactly("screen name `my screen` is not a Kotlin identifier")
  }
}
