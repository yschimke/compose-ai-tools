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
    )

  private fun parameter(
    name: String,
    type: String,
    hasDefault: Boolean = false,
    composableSlot: Boolean = false,
  ) =
    TargetParameter(
      name = name,
      type = type,
      hasDefault = hasDefault,
      composableSlot = composableSlot,
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
              parameter("contentDescription", "String?"),
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
    val snippet = emitted(record(parameters = listOf(parameter("label", "String?"))))

    assertThat(snippet.code).isEqualTo("Button(label = null)")
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
}
