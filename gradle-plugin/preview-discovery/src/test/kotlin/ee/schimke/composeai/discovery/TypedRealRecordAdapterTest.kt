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
    private val colorsArgument: String = "containerColor",
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
            FACTORIES.named("androidx.wear.compose.material3.ButtonDefaults.$factory"),
            colorsArgument to from(containerColor),
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
        FACTORIES,
      )
    assertThat(pair.record.components.map { it.symbol.name })
      .containsExactly("Button", "FilledTonalButton")
    val shelf = pair.catalog.statusSemantics.components
    assertThat(shelf.getValue("wear-m3/button").excluded).isNull()
    assertThat(shelf.values.single { it.record == tonal.canonicalId }.excluded)
      .contains("export target of wear-m3/button")
    // The builder reads the projection back from the published policy, and gets the same one.
    val published = shelf.getValue("wear-m3/button").exportProjection
    assertThat(ExportProjectionJson.decode(published!!).getOrThrow()).isEqualTo(adapter.projection)
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
  fun `units and theme tokens lower to their Kotlin spelling and survive JSON`() {
    val style =
      ExportProjection.Codec.Token(
        "androidx.wear.compose.material3.MaterialTheme",
        listOf("typography"),
        "androidx.compose.ui.text.TextStyle",
        setOf("titleLarge", "bodySmall"),
      )
    val projection =
      ExportProjection(
        listOf(
          ExportProjection.Target(
            "catalog/androidx.wear.compose.material3.TextKt.Text",
            mapOf(
              "fontSize" to
                ExportProjection.Value.Property(
                  "fontSizeSp",
                  AdapterValueCodecs.unit("sp", positive = true).exportCodec!!,
                ),
              "style" to
                ExportProjection.Value.Property(
                  "style",
                  AdapterValueCodecs.token(style).exportCodec!!,
                ),
            ),
          )
        )
      )
    assertThat(ExportProjectionJson.decode(ExportProjectionJson.encode(projection)).getOrThrow())
      .isEqualTo(projection)
    val lowered =
      projection.lower(
        mapOf("fontSizeSp" to JsonPrimitive(14.5), "style" to JsonPrimitive("titleLarge"))
      ) as ExportProjection.Lowered.Node
    val size = lowered.arguments.getValue("fontSize") as ScreenValue.Chain
    assertThat(size.receiver).isEqualTo(ScreenValue.Fractional(14.5))
    assertThat(size.links.single().callableFqn).isEqualTo("androidx.compose.ui.unit.sp")
    assertThat(lowered.arguments.getValue("style"))
      .isEqualTo(
        ScreenValue.Reference(
          "androidx.wear.compose.material3.MaterialTheme",
          listOf("typography", "titleLarge"),
          typeFqn = "androidx.compose.ui.text.TextStyle",
        )
      )
    // Zero is "unset" for a positive unit; an unknown token is refused, not guessed.
    val unset =
      projection.lower(mapOf("fontSizeSp" to JsonPrimitive(0))) as ExportProjection.Lowered.Node
    assertThat(unset.arguments).isEmpty()
    assertThat(projection.lower(mapOf("style" to JsonPrimitive("display"))))
      .isInstanceOf(ExportProjection.Lowered.Refused::class.java)
    assertThat(projection.qualifiedOwners)
      .containsAtLeast(
        "androidx.wear.compose.material3.MaterialTheme",
        "androidx.compose.ui.unit.sp",
      )
    // A bound number would skip the `.sp`, so a unit property is literal-only.
    assertThat(AdapterValueCodecs.unit("sp").stateType).isNull()
  }

  @Test
  fun `a stated variant no target lists is refused rather than exported as the default`() {
    val projection = WearButtonAdapter().projection!!
    val lowered = projection.lower(mapOf("variant" to JsonPrimitive("filled-tonl")))
    assertThat(lowered).isInstanceOf(ExportProjection.Lowered.Refused::class.java)
    assertThat(projection.lower(mapOf("variant" to buildJsonObject { put("type", "enum") })))
      .isInstanceOf(ExportProjection.Lowered.Refused::class.java)
    // Absent or empty is the default target.
    assertThat((projection.lower(emptyMap()) as ExportProjection.Lowered.Node).record)
      .isEqualTo(button.canonicalId)
    assertThat(
        (projection.lower(mapOf("variant" to JsonPrimitive(""))) as ExportProjection.Lowered.Node)
          .record
      )
      .isEqualTo(button.canonicalId)
  }

  @Test
  fun `a malformed wrapper is refused, not read as absent`() {
    val projection = WearButtonAdapter().projection!!
    val lowered =
      projection.lower(mapOf("enabled" to buildJsonObject { put("type", "bool") }))
        as ExportProjection.Lowered.Refused
    assertThat(lowered.reasons.single()).contains("enabled")
  }

  @Test
  fun `a whole number keeps every digit`() {
    val projection =
      ExportProjection(
        listOf(
          ExportProjection.Target(
            "r",
            mapOf("n" to ExportProjection.Value.Property("n", ExportProjection.Codec.Whole)),
          )
        )
      )
    val lowered =
      projection.lower(mapOf("n" to JsonPrimitive(9007199254740993L)))
        as ExportProjection.Lowered.Node
    assertThat(lowered.arguments.getValue("n")).isEqualTo(ScreenValue.Whole(9007199254740993L))
    assertThat(projection.lower(mapOf("n" to JsonPrimitive(1.5))))
      .isInstanceOf(ExportProjection.Lowered.Refused::class.java)
  }

  inner class UnexportedAdapter : TypedComponentAdapter<ButtonModel>("wear-m3/button", button) {
    val enabled = property(ButtonModel::enabled, AdapterValueCodecs.Boolean, true, bindable = false)
    val content = slot("content", required = true)

    init {
      exportAs(target(button, emptyMap(), slots = SLOTS))
    }
  }

  @Test
  fun `a declared property the projection leaves out is reported`() {
    assertThat(UnexportedAdapter().validateAgainst(button).joinToString("\n"))
      .contains("property enabled is not exported")
  }

  @Test
  fun `a fixed value of the wrong type is reported and its owners are admitted`() {
    val fixed =
      ScreenValue.Reference(
        "androidx.wear.compose.material3.ButtonDefaults",
        listOf("shape"),
        typeFqn = "androidx.compose.ui.graphics.Shape",
      )
    val adapter =
      object : TypedComponentAdapter<ButtonModel>("wear-m3/button", button) {
        val enabled =
          property(ButtonModel::enabled, AdapterValueCodecs.Boolean, true, bindable = false)
        val content = slot("content", required = true)

        init {
          exportAs(
            target(
              button,
              mapOf(
                "enabled" to from(enabled),
                "colors" to ExportProjection.Value.Fixed(fixed),
              ),
              slots = SLOTS,
            )
          )
        }
      }
    assertThat(adapter.validateAgainst(button).joinToString("\n"))
      .contains("writes androidx.compose.ui.graphics.Shape, parameter takes $BUTTON_COLORS")
    assertThat(adapter.projection!!.qualifiedOwners)
      .contains("androidx.wear.compose.material3.ButtonDefaults")
  }

  @Test
  fun `a factory argument no overload declares is reported`() {
    val problems = WearButtonAdapter(colorsArgument = "containerColour").validateAgainst(button)
    assertThat(problems.joinToString("\n"))
      .contains("no overload of androidx.wear.compose.material3.ButtonDefaults.buttonColors")
    assertThat(problems.joinToString("\n")).contains("no parameter containerColour")
  }

  @Test
  fun `a factory argument of the wrong type is reported`() {
    val problems = WearButtonAdapter(colorsArgument = "contentColor").validateAgainst(button)
    // `contentColor` is a Color too, so this one is valid; a Boolean into it is not.
    assertThat(problems).isEmpty()
    val adapter =
      object : TypedComponentAdapter<ButtonModel>("wear-m3/button", button) {
        val enabled =
          property(ButtonModel::enabled, AdapterValueCodecs.Boolean, true, bindable = false)
        val content = slot("content", required = true)

        init {
          exportAs(
            target(
              button,
              mapOf(
                "enabled" to from(enabled),
                "colors" to
                  call(
                    FACTORIES.named("androidx.wear.compose.material3.ButtonDefaults.buttonColors"),
                    "containerColor" to from(enabled),
                  ),
              ),
              slots = SLOTS,
            )
          )
        }
      }
    assertThat(adapter.validateAgainst(button).joinToString("\n"))
      .contains("containerColor takes androidx.compose.ui.graphics.Color, written kotlin.Boolean")
  }

  @Test
  fun `a required factory parameter filled only by an optional property is reported`() {
    val fqn = "app.ColorsDefaults.colors"
    val required =
      FactorySignature(
        fqn,
        BUTTON_COLORS,
        listOf(
          TargetParameter.Builder("container", "Color")
            .also { it.typeFqn = "androidx.compose.ui.graphics.Color" }
            .build()
        ),
      )
    val wearColor = ExportProjection.Codec.Color("androidx.wear.compose.material3.MaterialTheme")
    val adapter =
      object : TypedComponentAdapter<ButtonModel>("wear-m3/button", button) {
        val containerColor =
          wireProperty(
            ButtonModel::containerColor,
            AdapterValueCodecs.color(wearColor),
            WireColor.Unset,
          )
        val content = slot("content", required = true)

        init {
          exportAs(
            target(
              button,
              mapOf("colors" to call(listOf(required), "container" to from(containerColor))),
              slots = SLOTS,
            )
          )
        }
      }
    assertThat(adapter.validateAgainst(button).joinToString("\n"))
      .contains("required container is written only when its property is set")
  }

  @Test
  fun `generation refuses a factory discovery did not return`() {
    val error = runCatching {
      TypedAdapterCatalog.generate(
        ComponentRecordFile.Builder("catalog", "debug", listOf(button, tonal)).build(),
        UiBuilderCatalogs.CoverSheet("wear-m3", "Wear"),
        UiBuilderPolicyFile.Builder(UI_BUILDER_POLICY_SCHEMA, "wear").build(),
        listOf(WearButtonAdapter()),
      )
    }
      .exceptionOrNull()
    assertThat(error?.message).contains("absent from discovery")
  }

  @Test
  fun `a colour decodes to what the renderer resolves`() {
    val codec = AdapterValueCodecs.color(wearColor)
    assertThat(codec.decode(JsonPrimitive("#336699"))).isEqualTo(WireColor.Argb(0xFF336699))
    assertThat(codec.decode(JsonPrimitive("primary"))).isEqualTo(WireColor.Role("primary"))
    assertThat(codec.decode(JsonPrimitive(""))).isEqualTo(WireColor.Unset)
  }

  /**
   * Wear's `Button` as discovery records it: two overloads, the record speaking for the `label` one
   * (`RowScope.() -> Unit`), with the `content` one (`BoxScope.() -> Unit`) listed beside it.
   */
  private fun overloaded(callSite: String = "Button(onClick = {})"): ComponentRecord {
    val label = button.parameters
    val content =
      label.filterNot { it.name == "label" } +
        parameter("content", "BoxScope.() -> Unit", slot = true)
    return button
      .newBuilder()
      .also { b ->
        b.symbol = button.symbol.newBuilder().also { it.descriptor = LABEL_DESCRIPTOR }.build()
        b.overloads =
          listOf(LABEL_DESCRIPTOR to label, CONTENT_DESCRIPTOR to content).map { (d, p) ->
            ComponentOverload.Builder()
              .also {
                it.descriptor = d
                it.parameters = p
              }
              .build()
          }
        b.code = button.code!!.newBuilder().also { it.call = callSite }.build()
      }
      .build()
  }

  private fun contentSlotAdapter(record: ComponentRecord, slotParameter: String) =
    object : TypedComponentAdapter<ButtonModel>("wear-m3/button", record) {
      val enabled =
        property(ButtonModel::enabled, AdapterValueCodecs.Boolean, true, bindable = false)
      val content = slot("content", required = true)

      init {
        exportAs(
          target(
            record,
            mapOf("enabled" to from(enabled)),
            slots = mapOf("content" to slotParameter),
          )
        )
      }
    }

  @Test
  fun `a target resolves against the overload whose slots it fills, not only the record's own`() {
    val record = overloaded()
    // `content` exists only on the overload the record does not speak for.
    assertThat(contentSlotAdapter(record, "content").validateAgainst(record)).isEmpty()
    assertThat(contentSlotAdapter(record, "label").validateAgainst(record)).isEmpty()
  }

  @Test
  fun `a target no overload takes names each overload's mismatch`() {
    val record = overloaded()
    val problems = contentSlotAdapter(record, "body").validateAgainst(record).joinToString("\n")
    assertThat(problems).contains("no overload takes this target")
    assertThat(problems)
      .contains("$LABEL_DESCRIPTOR: slot content -> body is not a composable slot")
    assertThat(problems)
      .contains("$CONTENT_DESCRIPTOR: slot content -> body is not a composable slot")
  }

  @Test
  fun `the call site fills an overload's required parameter only where it has that name`() {
    // The record's own call writes `label`, which the `content` overload has no parameter for, so
    // printing it there would not compile.
    val record = overloaded(callSite = "Button(onClick = {}, label = { Text(\"a, b = c\") })")
    val problems = contentSlotAdapter(record, "content").validateAgainst(record).joinToString("\n")
    assertThat(problems).contains("$CONTENT_DESCRIPTOR: the call site writes label")
    assertThat(problems).doesNotContain("writes b")
  }

  @Test
  fun `a required parameter neither written nor at the call site is reported per overload`() {
    val record = overloaded(callSite = "Button()")
    val problems = contentSlotAdapter(record, "content").validateAgainst(record).joinToString("\n")
    assertThat(problems).contains("required parameter onClick is not written")
  }

  private fun listedAdapter(context: ExportProjection.Context) =
    object : TypedComponentAdapter<ButtonModel>("wear-m3/button", button) {
      val enabled =
        property(ButtonModel::enabled, AdapterValueCodecs.Boolean, true, bindable = false)
      val content = slot("content", required = true)

      init {
        exportAs(
          target(
            button,
            mapOf("enabled" to from(enabled)),
            slots = SLOTS,
            contexts = mapOf("list-item" to context),
          )
        )
      }
    }

  private val listPadding =
    ChainLink(
      "androidx.wear.compose.material3.minimumVerticalContentPadding",
      positional =
        listOf(
          ScreenValue.Reference(
            "androidx.wear.compose.material3.ButtonDefaults",
            listOf("minimumVerticalListContentPadding"),
            typeFqn = "androidx.compose.ui.unit.Dp",
          )
        ),
    )

  @Test
  fun `a role context that only appends modifiers validates against a record taking one`() {
    val adapter = listedAdapter(ExportProjection.Context(modifier = listOf(listPadding)))
    assertThat(adapter.validateAgainst(button)).isEmpty()
  }

  @Test
  fun `a role context's arguments are checked against the record like the target's own`() {
    val missing =
      listedAdapter(
          ExportProjection.Context(
            mapOf(
              "transformation" to
                ExportProjection.Value.Fixed(
                  ScreenValue.Construct(
                    "androidx.wear.compose.material3.lazy.SurfaceTransformation",
                    typeFqn = "androidx.wear.compose.material3.SurfaceTransformation",
                  )
                )
            )
          )
        )
        .validateAgainst(button)
    assertThat(missing)
      .containsExactly("wear-m3/button -> Button in list-item: no parameter transformation")

    val mistyped =
      listedAdapter(
          ExportProjection.Context(
            mapOf(
              "colors" to
                ExportProjection.Value.Fixed(
                  ScreenValue.Reference("app.Theme", listOf("colors"), typeFqn = "app.OtherColors")
                )
            )
          )
        )
        .validateAgainst(button)
    assertThat(mistyped)
      .containsExactly(
        "wear-m3/button -> Button in list-item.colors: writes app.OtherColors, parameter takes $BUTTON_COLORS"
      )
  }

  @Test
  fun `a role context appending modifiers to a record without one is reported`() {
    val noModifier =
      button
        .newBuilder()
        .also { b -> b.parameters = button.parameters.filterNot { it.name == "modifier" } }
        .build()
    val adapter =
      object : TypedComponentAdapter<ButtonModel>("wear-m3/button", noModifier) {
        val content = slot("content", required = true)

        init {
          exportAs(
            target(
              noModifier,
              emptyMap(),
              slots = SLOTS,
              contexts =
                mapOf("list-item" to ExportProjection.Context(modifier = listOf(listPadding))),
            )
          )
        }
      }
    assertThat(adapter.validateAgainst(noModifier))
      .containsExactly("wear-m3/button -> Button in list-item: no parameter modifier")
  }

  private companion object {
    const val BUTTON_COLORS = "androidx.wear.compose.material3.ButtonColors"

    /** As `TypedAdapterDiscovery.discoverFactories` reads Wear's `ButtonDefaults`. */
    val FACTORIES =
      listOf("buttonColors", "filledTonalButtonColors").flatMap { name ->
        val fqn = "androidx.wear.compose.material3.ButtonDefaults.$name"
        listOf(
          FactorySignature(fqn, BUTTON_COLORS, emptyList()),
          FactorySignature(
            fqn,
            BUTTON_COLORS,
            listOf("containerColor", "contentColor").map {
              TargetParameter.Builder(it, "Color")
                .also { b ->
                  b.typeFqn = "androidx.compose.ui.graphics.Color"
                  b.hasDefault = true
                }
                .build()
            },
          ),
        )
      }
    const val LABEL_DESCRIPTOR =
      "(Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function3;)V"
    const val CONTENT_DESCRIPTOR =
      "(Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function3;I)V"
    val SLOTS = mapOf("content" to "label")
    val EVENTS = mapOf("click" to "onClick")
  }
}
