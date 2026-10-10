package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OverloadSelectionTest {

  private fun param(name: String, type: String, default: Boolean = false, slot: Boolean = false) =
    TargetParameter.Builder(name = name, type = type)
      .also { b ->
        b.hasDefault = default
        b.composableSlot = slot
      }
      .build()

  private fun record(name: String, vararg parameters: TargetParameter, descriptor: String) =
    ComponentRecord.Builder(
        canonicalId = "catalog/androidx.compose.material3.${name}Kt.$name",
        symbol =
          ComponentSymbol.Builder(
              jvmOwner = "androidx.compose.material3.${name}Kt",
              callable = "androidx.compose.material3.$name",
              name = name,
              origin = ComponentOrigin.LIBRARY,
            )
            .also { b -> b.descriptor = descriptor }
            .build(),
      )
      .also { b ->
        b.parameters = parameters.toList()
        b.signatureKnown = true
      }
      .build()

  private fun alt(
    record: ComponentRecord,
    index: Int,
    previews: Int = 0,
    deprecated: Boolean = false,
  ) = OverloadAlternative(record, previews, index, deprecated)

  private val content = param("content", "@Composable () -> Unit", slot = true)
  private val modifier = param("modifier", "Modifier", default = true)

  // TextField: the String overload and the TextFieldState overload, state listed first.
  private val textFieldState =
    record(
      "TextField",
      param("state", "TextFieldState"),
      modifier,
      param("enabled", "Boolean", default = true),
      descriptor = "(state)",
    )
  private val textFieldValue =
    record(
      "TextField",
      param("value", "String"),
      param("onValueChange", "(String) -> Unit"),
      modifier,
      param("enabled", "Boolean", default = true),
      param("singleLine", "Boolean", default = true),
      descriptor = "(value)",
    )

  @Test
  fun `the overload covering the policy's parameter names wins, whatever is listed first`() {
    val choice =
      OverloadSelection.choose(
        listOf(alt(textFieldState, 0, previews = 3), alt(textFieldValue, 1, previews = 1)),
        supplied = setOf("value", "enabled", "singleLine", "isError", "label"),
      )
    assertThat(choice.record.symbol.descriptor).isEqualTo("(value)")
    assertThat(choice.diagnostic).isNull()
  }

  @Test
  fun `builder-only names cover nothing, and an unwritable overload loses to a writable one`() {
    // Button(onClick, content) beside Button(onClick, shapes: ButtonShapes, content): previews call
    // the shapes overload more, and the generator has no placeholder for ButtonShapes.
    val plain =
      record(
        "Button",
        param("onClick", "() -> Unit"),
        modifier,
        param("enabled", "Boolean", default = true),
        content,
        descriptor = "(plain)",
      )
    val shapes =
      record(
        "Button",
        param("onClick", "() -> Unit"),
        param("shapes", "ButtonShapes"),
        modifier,
        param("enabled", "Boolean", default = true),
        content,
        descriptor = "(shapes)",
      )
    val choice =
      OverloadSelection.choose(
        listOf(alt(plain, 0, previews = 20), alt(shapes, 1, previews = 102)),
        // `containerColor` and `onClickAction` are builder words, on no overload.
        supplied = setOf("enabled", "containerColor", "onClickAction", "content"),
      )
    assertThat(choice.record.symbol.descriptor).isEqualTo("(plain)")
  }

  @Test
  fun `with coverage tied, fewer unmatched required parameters beats more callers`() {
    // Card(content) beside Card(onClick, content): the policy names neither `onClick` nor anything
    // only one of them has, and previews call the clickable one.
    val static =
      record(
        "Card",
        modifier,
        param("shape", "Shape", default = true),
        content,
        descriptor = "(static)",
      )
    val clickable =
      record(
        "Card",
        param("onClick", "() -> Unit"),
        modifier,
        param("shape", "Shape", default = true),
        content,
        descriptor = "(clickable)",
      )
    val choice =
      OverloadSelection.choose(
        listOf(alt(static, 0, previews = 1), alt(clickable, 1, previews = 9)),
        supplied = setOf("shape", "containerColor", "content"),
      )
    assertThat(choice.record.symbol.descriptor).isEqualTo("(static)")
  }

  @Test
  fun `then the most called overload, then declaration order`() {
    val a = record("Chip", param("label", "String"), descriptor = "(a)")
    val b = record("Chip", param("label", "String"), descriptor = "(b)")
    assertThat(
        OverloadSelection.choose(
            listOf(alt(a, 0, previews = 1), alt(b, 1, previews = 2)),
            emptySet(),
          )
          .record
          .symbol
          .descriptor
      )
      .isEqualTo("(b)")
    assertThat(
        OverloadSelection.choose(listOf(alt(a, 0), alt(b, 1)), emptySet()).record.symbol.descriptor
      )
      .isEqualTo("(a)")
  }

  @Test
  fun `a deprecated overload that would win coverage is skipped`() {
    // The deprecated overload covers more, is called more and is listed first; the current one
    // still covers the policy, so it is chosen and nothing is reported.
    val deprecated =
      record(
        "Slider",
        param("value", "Float"),
        param("onValueChange", "(Float) -> Unit"),
        param("steps", "Int", default = true),
        descriptor = "(old)",
      )
    val current =
      record(
        "Slider",
        param("value", "Float"),
        param("onValueChange", "(Float) -> Unit"),
        param("steps", "Int", default = true),
        param("enabled", "Boolean", default = true),
        descriptor = "(new)",
      )
    val choice =
      OverloadSelection.choose(
        listOf(alt(deprecated, 0, previews = 9, deprecated = true), alt(current, 1)),
        supplied = setOf("value", "steps", "enabled"),
      )
    assertThat(choice.record.symbol.descriptor).isEqualTo("(new)")
    assertThat(choice.diagnostic).isNull()
  }

  @Test
  fun `only a deprecated overload covering the policy is reported, never emitted`() {
    val deprecated =
      record(
        "TextField",
        param("value", "String"),
        param("onValueChange", "(String) -> Unit"),
        descriptor = "(old)",
      )
    val choice =
      OverloadSelection.choose(
        listOf(alt(textFieldState, 0), alt(deprecated, 1, previews = 5, deprecated = true)),
        supplied = setOf("value", "enabled"),
      )
    assertThat(choice.record.symbol.descriptor).isEqualTo("(state)")
    val diagnostic = checkNotNull(choice.diagnostic)
    assertThat(diagnostic.code).isEqualTo(OverloadSelection.DEPRECATED_ONLY)
    assertThat(diagnostic.subject).isEqualTo(textFieldState.canonicalId)
    // Names the deprecated overload, the property only it covers, and the alternative kept.
    assertThat(diagnostic.message)
      .contains("`TextField(value: String, onValueChange: (String) -> Unit)`")
    assertThat(diagnostic.message).contains("`value`")
    assertThat(diagnostic.message).contains("`TextField(state: TextFieldState,")
  }

  @Test
  fun `when every overload is deprecated the record's code is refused`() {
    val only = record("Divider", modifier, descriptor = "(only)")
    val choice =
      OverloadSelection.choose(listOf(alt(only, 0, previews = 2, deprecated = true)), emptySet())
    assertThat(choice.record.code?.call).isNull()
    assertThat(choice.record.code?.refusedReason).contains("deprecated")
    assertThat(choice.diagnostic?.code).isEqualTo(OverloadSelection.ALL_DEPRECATED)
    // And the screen generator's gate cannot re-derive that refusal from the record's fields, so
    // it is never overruled into a call.
    assertThat(ComponentSnippets.refusalWith(choice.record, emptySet()))
      .isNotEqualTo(choice.record.code?.refusedReason)
  }
}
