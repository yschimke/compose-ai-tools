package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * A catalog's saved `wear-m3/button` written as the REAL Wear Material 3 call, from data alone: the
 * variant picks the composable and its colours factory, colours become theme roles or literals, an
 * unstyled button keeps the library's own colours and `enabled = true` is not written.
 */
class ExportProjectionTest {
  private fun parameter(
    name: String,
    type: String,
    typeFqn: String? = null,
    default: Boolean = false,
    slot: Boolean = false,
  ) =
    TargetParameter.Builder(name = name, type = type)
      .also { b ->
        b.typeFqn = typeFqn
        b.hasDefault = default
        b.composableSlot = slot
      }
      .build()

  private fun wearButton(name: String) =
    ComponentRecord.Builder(
        canonicalId = "catalog/androidx.wear.compose.material3.ButtonKt.$name",
        symbol =
          ComponentSymbol.Builder(
              jvmOwner = "androidx.wear.compose.material3.ButtonKt",
              callable = "androidx.wear.compose.material3.$name",
              name = name,
              origin = ComponentOrigin.LIBRARY,
            )
            .build(),
      )
      .also { b ->
        b.signatureKnown = true
        b.parameters =
          listOf(
            parameter("onClick", "() -> Unit", "kotlin.Function0"),
            parameter("modifier", "Modifier", "androidx.compose.ui.Modifier", default = true),
            parameter("enabled", "Boolean", "kotlin.Boolean", default = true),
            parameter(
              "colors",
              "ButtonColors",
              "androidx.wear.compose.material3.ButtonColors",
              default = true,
            ),
            parameter("label", "RowScope.() -> Unit", slot = true),
          )
        b.code =
          ComponentCode.Builder()
            .also { c ->
              c.call = "$name(onClick = {})"
              c.imports = listOf("androidx.wear.compose.material3.$name")
            }
            .build()
      }
      .build()

  private val button = wearButton("Button")
  private val tonal = wearButton("FilledTonalButton")
  private val text =
    ComponentRecord.Builder(
        canonicalId = "catalog/androidx.wear.compose.material3.TextKt.Text",
        symbol =
          ComponentSymbol.Builder(
              jvmOwner = "androidx.wear.compose.material3.TextKt",
              callable = "androidx.wear.compose.material3.Text",
              name = "Text",
              origin = ComponentOrigin.LIBRARY,
            )
            .build(),
      )
      .also { b ->
        b.signatureKnown = true
        b.parameters = listOf(parameter("text", "String", "kotlin.String"))
        b.code =
          ComponentCode.Builder()
            .also { c ->
              c.call = "Text(text = \"\")"
              c.imports = listOf("androidx.wear.compose.material3.Text")
            }
            .build()
      }
      .build()

  private val wearColor =
    ExportProjection.Codec.Color(
      schemeRootFqn = "androidx.wear.compose.material3.MaterialTheme",
      roles = mapOf("surface" to "surfaceContainer"),
    )

  private fun colors(factory: String) =
    ExportProjection.Value.Call(
      "androidx.wear.compose.material3.ButtonDefaults.$factory",
      "androidx.wear.compose.material3.ButtonColors",
      mapOf(
        "containerColor" to ExportProjection.Value.Property("containerColor", wearColor),
        "contentColor" to ExportProjection.Value.Property("contentColor", wearColor),
      ),
    )

  private fun common(factory: String) =
    mapOf(
      "enabled" to
        ExportProjection.Value.Property(
          "enabled",
          ExportProjection.Codec.Bool,
          omitWhen = JsonPrimitive(true),
        ),
      "colors" to colors(factory),
    )

  private val projection =
    ExportProjection(
      variantProperty = "variant",
      targets =
        listOf(
          ExportProjection.Target(button.canonicalId, common("buttonColors")),
          ExportProjection.Target(
            tonal.canonicalId,
            common("filledTonalButtonColors"),
            variants = setOf("filled-tonal"),
          ),
        ),
    )

  private fun source(properties: Map<String, kotlinx.serialization.json.JsonElement>): String {
    val lowered = projection.lower(properties) as ExportProjection.Lowered.Node
    val document =
      ScreenDocument(
        name = "Projected",
        root =
          ScreenNode(
            lowered.record,
            arguments = lowered.arguments,
            slots =
              mapOf(
                "label" to
                  listOf(ScreenNode(text.canonicalId, mapOf("text" to ScreenValue.Text("Start"))))
              ),
          ),
      )
    val result =
      ScreenGenerator.generate(
        document,
        ComponentRecordFile.Builder("catalog", "debug", listOf(button, tonal, text)).build(),
        expressionPackages = setOf("androidx.wear.compose", "androidx.compose.ui.graphics"),
      )
    assertThat(result).isInstanceOf(ScreenGenerator.Result.Emitted::class.java)
    return (result as ScreenGenerator.Result.Emitted).source
  }

  private fun wrapper(type: String, value: Any) = buildJsonObject {
    put("type", type)
    when (value) {
      is Boolean -> put("value", value)
      else -> put("value", value.toString())
    }
  }

  @Test
  fun `a styled tonal button is written as FilledTonalButton with its own colours factory`() {
    val source =
      source(
        mapOf(
          "variant" to wrapper("enum", "filled-tonal"),
          "enabled" to wrapper("bool", false),
          "containerColor" to wrapper("colorToken", "surface"),
          "contentColor" to wrapper("color", "#336699"),
        )
      )
    assertThat(source).contains("FilledTonalButton(")
    assertThat(source).contains("enabled = false")
    assertThat(source)
      .contains(
        "colors = ButtonDefaults.filledTonalButtonColors(containerColor = " +
          "MaterialTheme.colorScheme.surfaceContainer, contentColor = Color(0xFF336699))"
      )
    assertThat(source).contains("import androidx.wear.compose.material3.ButtonDefaults")
    assertThat(source).contains("Text(text = \"Start\")")
  }

  @Test
  fun `an unstyled default button keeps the library's colours and enabled default`() {
    val source = source(mapOf("enabled" to wrapper("bool", true)))
    assertThat(source).contains("Button(")
    assertThat(source).doesNotContain("FilledTonalButton")
    assertThat(source).doesNotContain("colors =")
    assertThat(source).doesNotContain("enabled =")
  }

  @Test
  fun `a malformed value is refused with where it came from`() {
    val lowered =
      projection.lower(mapOf("enabled" to wrapper("string", "maybe")))
        as ExportProjection.Lowered.Refused
    assertThat(lowered.reasons.single()).contains("enabled")
  }
}
