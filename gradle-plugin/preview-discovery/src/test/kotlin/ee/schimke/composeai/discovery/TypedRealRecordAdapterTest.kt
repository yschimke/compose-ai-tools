package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * A typed definition on the REAL library records: `wear-m3/button` is `Button`, or
 * `FilledTonalButton` for `variant = filled-tonal`; `containerColor` is a builder property that
 * reaches the call only through `colors = ButtonDefaults.…Colors(containerColor = …)`.
 */
class TypedRealRecordAdapterTest {
  private fun parameter(
    name: String,
    type: String,
    typeFqn: String? = null,
    default: Boolean = false,
    slot: Boolean = false,
    lambda: Boolean = false,
  ) =
    TargetParameter.Builder(name = name, type = type)
      .also { b ->
        b.typeFqn = typeFqn
        b.hasDefault = default
        b.composableSlot = slot
        if (lambda) b.lambdaReturnTypeFqn = "kotlin.Unit"
      }
      .build()

  private fun record(name: String, colorsType: String = BUTTON_COLORS) =
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
            parameter("onClick", "() -> Unit", "kotlin.Function0", lambda = true),
            parameter("modifier", "Modifier", "androidx.compose.ui.Modifier", default = true),
            parameter("enabled", "Boolean", "kotlin.Boolean", default = true),
            parameter("colors", "ButtonColors", colorsType, default = true),
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

  private val button = record("Button")
  private val tonal = record("FilledTonalButton")

  data class ButtonModel(
    val variant: String,
    val enabled: Boolean,
    val containerColor: WireColor,
    val onClick: () -> Unit,
  )

  private val wearColor =
    ExportProjection.Codec.Color("androidx.wear.compose.material3.MaterialTheme")

  inner class WearButtonAdapter(
    own: ComponentRecord = button,
    variantRecord: ComponentRecord = tonal,
    private val colorsParameter: String = "colors",
  ) : TypedComponentAdapter<ButtonModel>("wear-m3/button", own) {
    val variant =
      wireProperty(ButtonModel::variant, AdapterValueCodecs.String, "", bindable = false)
    val enabled = property(ButtonModel::enabled, AdapterValueCodecs.Boolean, true, bindable = false)
    val containerColor =
      wireProperty(
        ButtonModel::containerColor,
        AdapterValueCodecs.color(wearColor),
        WireColor.Unset,
      )
    val onClick = event(ButtonModel::onClick)
    val content = slot("content", required = true)

    private fun parameters(factory: String) =
      mapOf(
        "enabled" to from(enabled, omitWhen = JsonPrimitive(true)),
        colorsParameter to
          call(
            "androidx.wear.compose.material3.ButtonDefaults.$factory",
            BUTTON_COLORS,
            "containerColor" to from(containerColor),
          ),
      )

    init {
      exportAs(
        target(own, parameters("buttonColors"), slots = SLOTS, events = EVENTS),
        target(
          variantRecord,
          parameters("filledTonalButtonColors"),
          variants = setOf("filled-tonal"),
          slots = SLOTS,
          events = EVENTS,
        ),
        variant = variant,
      )
    }
  }

  @Test
  fun `a definition on the real records validates against every target`() {
    val adapter = WearButtonAdapter()
    assertThat(adapter.validateAgainst(button)).isEmpty()
    assertThat(adapter.variantRecords.map { it.symbol.name }).containsExactly("FilledTonalButton")
  }

  @Test
  fun `a projection writing a parameter the record lacks is reported`() {
    val problems = WearButtonAdapter(colorsParameter = "colours").validateAgainst(button)
    assertThat(problems.joinToString("\n")).contains("no parameter colours")
  }

  @Test
  fun `a factory of the wrong type is reported`() {
    val problems =
      WearButtonAdapter(variantRecord = record("FilledTonalButton", "app.OtherColors"))
        .validateAgainst(button)
    assertThat(problems.joinToString("\n"))
      .contains("writes $BUTTON_COLORS, parameter takes app.OtherColors")
  }

  @Test
  fun `the adapter's own projection lowers a saved node onto the variant's record`() {
    val adapter = WearButtonAdapter()
    adapter.freeze()
    val lowered =
      adapter.projection!!.lower(
        mapOf(
          "variant" to
            buildJsonObject {
              put("type", "enum")
              put("value", "filled-tonal")
            },
          "containerColor" to
            buildJsonObject {
              put("type", "colorToken")
              put("value", "primary")
            },
        )
      ) as ExportProjection.Lowered.Node
    assertThat(lowered.record).isEqualTo(tonal.canonicalId)
    assertThat(lowered.slots).containsEntry("content", "label")
    assertThat(lowered.events).containsEntry("click", "onClick")
    val colors = lowered.arguments.getValue("colors") as ScreenValue.Construct
    assertThat(colors.callableFqn).endsWith("filledTonalButtonColors")
  }

  @Test
  fun `generation ships the variant record off the shelf`() {
    val adapter = WearButtonAdapter()
    val pair =
      TypedAdapterCatalog.generate(
        ComponentRecordFile.Builder("catalog", "debug", listOf(button, tonal)).build(),
        UiBuilderCatalogs.CoverSheet("wear-m3", "Wear"),
        UiBuilderPolicyFile.Builder(UI_BUILDER_POLICY_SCHEMA, "wear").build(),
        listOf(adapter),
      )
    assertThat(pair.record.components.map { it.symbol.name })
      .containsExactly("Button", "FilledTonalButton")
    val shelf = pair.catalog.statusSemantics.components
    assertThat(shelf.getValue("wear-m3/button").excluded).isNull()
    assertThat(shelf.values.single { it.record == tonal.canonicalId }.excluded)
      .contains("export target of wear-m3/button")
  }

  @Test
  fun `a projection survives its published JSON spelling`() {
    val projection = WearButtonAdapter().projection!!
    val decoded = ExportProjectionJson.decode(ExportProjectionJson.encode(projection)).getOrThrow()
    assertThat(decoded).isEqualTo(projection)
  }

  @Test
  fun `a projection names what a host must admit and may pass through`() {
    val projection = WearButtonAdapter().projection!!
    assertThat(projection.properties).containsExactly("variant", "enabled", "containerColor")
    assertThat(projection.directParameter(tonal.canonicalId, "enabled")).isEqualTo("enabled")
    // Reaches the call only inside `filledTonalButtonColors(…)`, so a bound value cannot.
    assertThat(projection.directParameter(tonal.canonicalId, "containerColor")).isNull()
    assertThat(projection.qualifiedOwners)
      .containsExactly(
        "androidx.wear.compose.material3.ButtonDefaults",
        "androidx.wear.compose.material3.MaterialTheme",
        "androidx.compose.ui.graphics.Color",
      )
  }

  @Test
  fun `a colour decodes to what the renderer resolves`() {
    val codec = AdapterValueCodecs.color(wearColor)
    assertThat(codec.decode(JsonPrimitive("#336699"))).isEqualTo(WireColor.Argb(0xFF336699))
    assertThat(codec.decode(JsonPrimitive("primary"))).isEqualTo(WireColor.Role("primary"))
    assertThat(codec.decode(JsonPrimitive(""))).isEqualTo(WireColor.Unset)
  }

  private companion object {
    const val BUTTON_COLORS = "androidx.wear.compose.material3.ButtonColors"
    val SLOTS = mapOf("content" to "label")
    val EVENTS = mapOf("click" to "onClick")
  }
}
