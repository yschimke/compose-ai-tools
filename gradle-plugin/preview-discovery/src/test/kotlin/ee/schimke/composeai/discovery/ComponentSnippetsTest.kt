package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ComponentSnippetsTest {

  private fun record(
    jvmOwner: String = "androidx.compose.material3.ButtonKt",
    name: String = "Button",
    callable: String = "androidx.compose.material3.Button",
    receiver: String? = null,
    signatureKnown: Boolean = true,
    parameters: List<TargetParameter> = emptyList(),
    callableFromAnotherFile: Boolean = true,
    hasTypeParameters: Boolean = false,
    overloadsCollided: Boolean = false,
    hasContextReceivers: Boolean = false,
    requiredOptIns: List<String> = emptyList(),
  ) =
    ComponentRecord(
      canonicalId = "app/$jvmOwner.$name",
      symbol =
        ComponentSymbol(
          jvmOwner = jvmOwner,
          callable = callable,
          name = name,
          origin = ComponentOrigin.LIBRARY,
          receiver = receiver,
        ),
      parameters = parameters,
      signatureKnown = signatureKnown,
      callableFromAnotherFile = callableFromAnotherFile,
      hasTypeParameters = hasTypeParameters,
      overloadsCollided = overloadsCollided,
      hasContextReceivers = hasContextReceivers,
      requiredOptIns = requiredOptIns,
    )

  private fun parameter(
    name: String,
    type: String,
    hasDefault: Boolean = false,
    composableSlot: Boolean = false,
    nullable: Boolean = false,
    typeFqn: String? = null,
  ) =
    TargetParameter(
      name = name,
      type = type,
      typeFqn = typeFqn,
      hasDefault = hasDefault,
      composableSlot = composableSlot,
      nullable = nullable,
    )

  private fun emitted(record: ComponentRecord): ComponentSnippet.Emitted =
    ComponentSnippets.callSite(record) as ComponentSnippet.Emitted

  private fun refusal(record: ComponentRecord): String =
    (ComponentSnippets.callSite(record) as ComponentSnippet.Refused).reason

  @Test
  fun `Material3 Button prints its required callback and slot, and omits every default`() {
    // The real `androidx.compose.material3.Button` signature: two required, the rest defaulted.
    val snippet =
      emitted(
        record(
          parameters =
            listOf(
              parameter("onClick", "() -> Unit"),
              parameter("modifier", "Modifier", hasDefault = true),
              parameter("enabled", "Boolean", hasDefault = true),
              parameter("shape", "Shape", hasDefault = true),
              parameter("colors", "ButtonColors", hasDefault = true),
              parameter("content", "RowScope.() -> Unit", composableSlot = true),
            )
        )
      )

    assertThat(snippet.code).isEqualTo("Button(onClick = {}, content = {})")
    assertThat(snippet.imports).containsExactly("androidx.compose.material3.Button")
  }

  @Test
  fun `a required String prints an empty string literal`() {
    val snippet =
      emitted(
        record(
          jvmOwner = "androidx.compose.material3.TextKt",
          name = "Text",
          callable = "androidx.compose.material3.Text",
          parameters =
            listOf(
              parameter("text", "String"),
              parameter("modifier", "Modifier", hasDefault = true),
            ),
        )
      )

    assertThat(snippet.code).isEqualTo("""Text(text = "")""")
  }

  @Test
  fun `a component whose parameters are all defaulted prints a bare call`() {
    val snippet =
      emitted(
        record(
          jvmOwner = "androidx.compose.material3.DividerKt",
          name = "HorizontalDivider",
          callable = "androidx.compose.material3.HorizontalDivider",
          parameters = listOf(parameter("modifier", "Modifier", hasDefault = true)),
        )
      )

    assertThat(snippet.code).isEqualTo("HorizontalDivider()")
  }

  @Test
  fun `a required parameter with no writable literal is refused, and names the parameter`() {
    val reason =
      refusal(
        record(
          jvmOwner = "androidx.compose.material3.IconKt",
          name = "Icon",
          callable = "androidx.compose.material3.Icon",
          parameters =
            listOf(
              parameter("imageVector", "ImageVector"),
              parameter("contentDescription", "String?", nullable = true),
            ),
        )
      )

    assertThat(reason).contains("imageVector: ImageVector")
  }

  @Test
  fun `an unread signature is refused rather than printed as a parameterless call`() {
    // The empty parameter list here is "we could not look", not "takes nothing" — printing
    // `Button()` from it would emit source that does not compile.
    assertThat(refusal(record(signatureKnown = false, parameters = emptyList())))
      .contains("not recovered")
  }

  @Test
  fun `a composable with a context requirement is refused`() {
    // The same reason as an extension receiver, and invisible in the parameter list: a context is
    // not a value parameter, so a printed call would carry no hint that something has to be in
    // scope around it.
    assertThat(refusal(record(hasContextReceivers = true)))
      .contains("context receiver or parameter")
  }

  @Test
  fun `an extension composable is refused because its call site needs the scope`() {
    val reason =
      refusal(
        record(
          jvmOwner = "androidx.compose.animation.AnimatedVisibilityKt",
          name = "AnimatedVisibility",
          callable = "androidx.compose.animation.AnimatedVisibility",
          receiver = "androidx.compose.foundation.layout.ColumnScope",
        )
      )

    assertThat(reason).contains("androidx.compose.foundation.layout.ColumnScope")
  }

  @Test
  fun `a member of a class is refused because its call site needs an instance`() {
    val reason =
      refusal(
        record(
          jvmOwner = "com.example.Controls",
          name = "Row",
          // No `Kt` facade was unwrapped, so the callable still carries the owner.
          callable = "com.example.Controls.Row",
        )
      )

    assertThat(reason).contains("com.example.Controls")
  }

  @Test
  fun `a required nullable parameter takes null`() {
    val snippet =
      emitted(record(parameters = listOf(parameter("label", "String?", nullable = true))))

    assertThat(snippet.code).isEqualTo("Button(label = null)")
  }

  @Test
  fun `a nullable callback takes null rather than being refused`() {
    // material3's `Checkbox(onCheckedChange: ((Boolean) -> Unit)?)`, and `RadioButton` / `Switch`
    // alongside it. No lambda-shaped rule accepts a nullable function type, so before the nullable
    // test came first these were refused outright.
    val snippet =
      emitted(
        record(
          parameters = listOf(parameter("onCheckedChange", "((Boolean) -> Unit)?", nullable = true))
        )
      )

    assertThat(snippet.code).isEqualTo("Button(onCheckedChange = null)")
  }

  @Test
  fun `a record written before the nullable field still answers null for a nullable type`() {
    // A persisted v1 `components.json` has no `nullable`, so it deserialises to `false`. Without
    // a spelling fallback for non-function types, every `String?` such a record carries would go
    // from emitting `null` to being refused purely by upgrading the reader.
    val snippet =
      emitted(record(parameters = listOf(parameter("label", "String?", nullable = false))))

    assertThat(snippet.code).isEqualTo("Button(label = null)")
  }

  @Test
  fun `a non-null callback returning a nullable value is refused, not given null`() {
    // The trap the structural `nullable` flag exists to avoid. `(Int) -> String?` ends in `?` and
    // is *not* nullable — the return type is. Reading nullability off the spelling would emit
    // `lookup = null` for a non-null parameter, which does not compile; `emptyLambda` then refuses
    // it for the separate reason that `{}` cannot return a `String?`.
    assertThat(
        refusal(
          record(parameters = listOf(parameter("lookup", "(Int) -> String?", nullable = false)))
        )
      )
      .contains("lookup")
  }

  @Test
  fun `a single-parameter callback still accepts a bare empty lambda`() {
    val snippet =
      emitted(record(parameters = listOf(parameter("onCheckedChange", "(Boolean) -> Unit"))))

    assertThat(snippet.code).isEqualTo("Button(onCheckedChange = {})")
  }

  @Test
  fun `a comma inside a type argument does not count as a second lambda parameter`() {
    val snippet =
      emitted(record(parameters = listOf(parameter("onResult", "(Map<String, Int>) -> Unit"))))

    assertThat(snippet.code).isEqualTo("Button(onResult = {})")
  }

  @Test
  fun `a two-parameter callback is refused because a bare empty lambda does not type-check`() {
    assertThat(refusal(record(parameters = listOf(parameter("onDrag", "(Float, Float) -> Unit")))))
      .contains("onDrag")
  }

  @Test
  fun `a callback returning a value is refused because an empty lambda returns Unit`() {
    assertThat(refusal(record(parameters = listOf(parameter("predicate", "(Int) -> Boolean")))))
      .contains("predicate")
  }

  @Test
  fun `every scalar placeholder is the literal its type accepts`() {
    val snippet =
      emitted(
        record(
          parameters =
            listOf(
              parameter("count", "Int"),
              parameter("id", "Long"),
              parameter("fraction", "Float"),
              parameter("ratio", "Double"),
              parameter("selected", "Boolean"),
            )
        )
      )

    assertThat(snippet.code)
      .isEqualTo("Button(count = 0, id = 0L, fraction = 0f, ratio = 0.0, selected = false)")
  }

  @Test
  fun `a keyword name is backtick-escaped in both the call and the import`() {
    // ``fun `when`(`is`: String)`` is a legal declaration, and metadata hands back the bare names.
    // They pass the import-identifier filter, so without escaping this prints `when(is = "")` and
    // an import ending `.when` — neither of which parses.
    val snippet =
      emitted(
        record(
          jvmOwner = "com.example.ControlsKt",
          name = "when",
          callable = "com.example.when",
          parameters = listOf(parameter("is", "String", typeFqn = "kotlin.String")),
        )
      )

    assertThat(snippet.code).isEqualTo("`when`(`is` = \"\")")
    assertThat(snippet.imports).containsExactly("com.example.`when`")
  }

  @Test
  fun `a soft keyword is left alone`() {
    // `data`, `value`, `by` and friends are legal identifiers; escaping them would be noise.
    val snippet =
      emitted(
        record(
          name = "Card",
          callable = "androidx.compose.material3.Card",
          parameters = listOf(parameter("value", "String", typeFqn = "kotlin.String")),
        )
      )

    assertThat(snippet.code).isEqualTo("Card(value = \"\")")
  }

  @Test
  fun `a domain type that happens to be named String is refused, not given a string literal`() {
    // `com.example.String` and `kotlin.String` both render as `String`. Choosing the literal off
    // that spelling emits `value = ""` for a type that does not accept it.
    val reason =
      refusal(
        record(parameters = listOf(parameter("value", "String", typeFqn = "com.example.String")))
      )

    assertThat(reason).contains("value: String")
  }

  @Test
  fun `a real Kotlin scalar is still matched by its qualified name`() {
    val snippet =
      emitted(record(parameters = listOf(parameter("count", "Int", typeFqn = "kotlin.Int"))))

    assertThat(snippet.code).isEqualTo("Button(count = 0)")
  }

  @Test
  fun `a private composable is refused because a generated file cannot reach it`() {
    assertThat(refusal(record(callableFromAnotherFile = false))).contains("public or internal")
  }

  @Test
  fun `a generic composable is refused because the call cannot infer its type parameters`() {
    // `fun <T> Picker(items: List<T> = emptyList())` omits every default, leaving nothing to infer
    // `T` from, and the record carries no type argument a consumer could supply instead.
    assertThat(refusal(record(hasTypeParameters = true))).contains("type parameters")
  }

  @Test
  fun `collided overloads are refused because no single call site identifies one`() {
    assertThat(refusal(record(overloadsCollided = true))).contains("overloads collided")
  }

  @Test
  fun `required opt-ins travel with the emitted call rather than refusing it`() {
    // Refusing would drop most of Material 3 over something the caller fixes with one annotation
    // on the wrapper it already has to write.
    val snippet =
      emitted(
        record(requiredOptIns = listOf("androidx.compose.material3.ExperimentalMaterial3Api"))
      )

    assertThat(snippet.code).isEqualTo("Button()")
    assertThat(snippet.requiredOptIns)
      .containsExactly("androidx.compose.material3.ExperimentalMaterial3Api")
  }
}
