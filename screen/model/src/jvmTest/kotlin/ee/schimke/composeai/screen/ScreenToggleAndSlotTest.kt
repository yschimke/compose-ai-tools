package ee.schimke.composeai.screen

import ee.schimke.composeai.discovery.*
import org.junit.Assert.*
import org.junit.Test

/**
 * A component that exposes a toggle as an intent passes it to `onCheckedChange` ignoring the
 * reported value, and an optional slot the document left empty is absent rather than `{}`.
 */
class ScreenToggleAndSlotTest {
  private fun record(name: String, vararg parameters: TargetParameter): ComponentRecord =
    ComponentRecord.Builder(
        canonicalId = "test/$name",
        symbol =
          ComponentSymbol.Builder(
              jvmOwner = "androidx.compose.material3.${name}Kt",
              callable = "androidx.compose.material3.$name",
              name = name,
              origin = ComponentOrigin.LIBRARY,
            )
            .build(),
      )
      .also { b ->
        b.componentIds = listOf(name.lowercase())
        b.parameters = parameters.toList()
        b.signatureKnown = true
        b.code =
          ComponentCode.Builder()
            .also { c ->
              c.call = "$name()"
              c.imports = listOf("androidx.compose.material3.$name")
            }
            .build()
      }
      .build()

  private fun parameter(
    name: String,
    type: String,
    typeFqn: String?,
    hasDefault: Boolean = false,
    nullable: Boolean = false,
    slot: Boolean = false,
  ) =
    TargetParameter.Builder(name = name, type = type)
      .also { b ->
        b.typeFqn = typeFqn
        b.hasDefault = hasDefault
        b.nullable = nullable
        b.composableSlot = slot
      }
      .build()

  private val checkbox =
    record(
      "Checkbox",
      parameter("checked", "Boolean", "kotlin.Boolean"),
      parameter("onCheckedChange", "((Boolean) -> Unit)?", "kotlin.Function1", nullable = true),
    )

  private val field =
    record(
      "Field",
      parameter("onValueChange", "(String) -> Unit", "kotlin.Function1"),
    )

  private val item =
    record(
      "Item",
      parameter("headline", "@Composable () -> Unit", null, slot = true),
      parameter(
        "label",
        "(@Composable () -> Unit)?",
        null,
        hasDefault = true,
        nullable = true,
        slot = true,
      ),
      parameter("icon", "(@Composable () -> Unit)?", null, nullable = true, slot = true),
    )

  private fun generate(root: ScreenNode, function: ScreenFunction) =
    ScreenGenerator.generate(
      ScreenDocument(
        "ToggleScreen",
        ScreenNode("", function = function.name),
        functions = listOf(function),
      ),
      M3Palette.records
        .newBuilder()
        .also { it.components = M3Palette.records.components + checkbox + field + item }
        .build(),
      expressionPackages = M3Palette.expressionPackages,
      frame = null,
      output = ScreenGenerator.Output.COMPONENTS,
    )

  @Test
  fun `a toggle intent ignores the value the toggle reports`() {
    val box =
      ScreenNode(
        "checkbox",
        arguments =
          mapOf(
            "checked" to ScreenValue.ParameterRead("checked", "kotlin.Boolean"),
            "onCheckedChange" to
              ScreenValue.ParameterRead("onBoxCheckedChange", "kotlin.Function0"),
          ),
      )
    val function =
      ScreenFunction(
        "Toggle",
        listOf(
          ScreenParameter.Value("checked", "kotlin.Boolean"),
          ScreenParameter.Callback("onBoxCheckedChange"),
        ),
        box,
      )
    val result = generate(box, function)
    assertTrue(result.toString(), result is ScreenGenerator.Result.Emitted)
    val source = (result as ScreenGenerator.Result.Emitted).source
    assertTrue(source, "onCheckedChange = { _ -> onBoxCheckedChange() }" in source)
  }

  @Test
  fun `any other reported value still refuses an intent`() {
    val text =
      ScreenNode(
        "field",
        arguments =
          mapOf("onValueChange" to ScreenValue.ParameterRead("onEdit", "kotlin.Function0")),
      )
    val function = ScreenFunction("Edit", listOf(ScreenParameter.Callback("onEdit")), text)
    val result = generate(text, function)
    assertTrue(result.toString(), result is ScreenGenerator.Result.Refused)
    assertTrue(
      result.toString(),
      (result as ScreenGenerator.Result.Refused).reasons.any { "zero-argument" in it },
    )
  }

  @Test
  fun `an empty optional slot is absent, or null when it has no default`() {
    val node =
      ScreenNode(
        "item",
        slots =
          mapOf(
            "headline" to
              listOf(ScreenNode("text", arguments = mapOf("text" to ScreenValue.Text("Hi")))),
            "label" to emptyList(),
            "icon" to emptyList(),
          ),
      )
    val result = generate(node, ScreenFunction("Row", emptyList(), node))
    assertTrue(result.toString(), result is ScreenGenerator.Result.Emitted)
    val source = (result as ScreenGenerator.Result.Emitted).source
    assertFalse(source, "label =" in source)
    assertTrue(source, "icon = null" in source)
  }
}
