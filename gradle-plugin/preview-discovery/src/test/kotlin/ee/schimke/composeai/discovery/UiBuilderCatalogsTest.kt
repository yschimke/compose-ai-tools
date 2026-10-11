package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Tests the generator that turns a record, a cover sheet and a policy into a catalog's
 * `ui-builder.json`. Mostly about diagnostics: "why is this component missing" and "why is it all
 * placeholders" must be answerable from the published file alone.
 */
class UiBuilderCatalogsTest {

  private val cover = UiBuilderCatalogs.CoverSheet(system = "wear-m3", title = "Wear M3")

  private fun policy(
    platform: String = "wear",
    platformLabel: String? = null,
    builtins: Map<String, UiBuilderBuiltin> = emptyMap(),
    code: UiBuilderCode? = null,
    componentIdPrefix: String? = null,
    browserPreview: kotlinx.serialization.json.JsonElement? = null,
    components: Map<String, UiBuilderAuthoredComponent> = emptyMap(),
  ) =
    UiBuilderPolicyFile.Builder(schema = UI_BUILDER_POLICY_SCHEMA, platform = platform)
      .also { b ->
        b.platformLabel = platformLabel
        b.builtins = builtins
        b.code = code
        b.componentIdPrefix = componentIdPrefix
        b.browserPreview = browserPreview
        b.components = components
      }
      .build()

  private fun record(vararg components: ComponentRecord) =
    ComponentRecordFile.Builder(
        module = ":catalog",
        variant = "debug",
        components = components.toList(),
      )
      .build()

  private fun component(
    name: String,
    catalogId: String? = null,
    group: String? = null,
    builder: BuilderPolicy? = null,
    parameters: List<TargetParameter> = emptyList(),
    signatureKnown: Boolean = true,
    // A callable published under several catalog identities — `Buttons/Filled` and `Buttons/Tonal`
    // over one `Button`. Sorted, the way the record stores the union across every preview.
    catalogIds: List<String>? = null,
    // One callable, several stickers, each with its own group — `Buttons/Filled` and
    // `Buttons/Tonal` over one `Button`. The default is the single binding above.
    bindings: List<ComponentBinding>? = null,
  ) =
    ComponentRecord.Builder(
        canonicalId = ":catalog/androidx.wear.compose.material3.${name}Kt.$name",
        symbol =
          ComponentSymbol.Builder(
              jvmOwner = "androidx.wear.compose.material3.${name}Kt",
              callable = "androidx.wear.compose.material3.$name",
              name = name,
              origin = ComponentOrigin.LIBRARY,
            )
            .build(),
      )
      .also { b3 ->
        b3.componentIds = catalogIds ?: listOfNotNull(catalogId)
        b3.parameters = parameters
        b3.slots = ComponentRecords.slotsOf(parameters)
        b3.signatureKnown = signatureKnown
        b3.builder = builder
        // One binding carrying the catalog's own resolved group, which is what a real record holds
        // and what the menu is built from for a component that annotates nothing.
        b3.bindings =
          bindings
            ?: listOf(
              ComponentBinding.Builder(previewId = "${name}Preview")
                .also { b ->
                  b.componentId = catalogId
                  b.group = group
                }
                .build()
            )
      }
      .build()

  /**
   * A catalog states component vocabulary in the policy file without annotating anything
   * (m3-catalog's shape).
   */
  @Test
  fun `an authored component publishes its vocabulary, with no annotation`() {
    val file =
      UiBuilderCatalogs.generate(
        record(component("Button", catalogId = "Controls/Button", group = "Actions")),
        cover,
        policy(
          componentIdPrefix = "wear-m3/",
          components =
            mapOf(
              "wear-m3/button" to
                UiBuilderAuthoredComponent.Builder()
                  .also { b ->
                    b.displayName = "Button"
                    b.traits = listOf("Action")
                    b.modifierCapabilities = listOf("padding", "size")
                    b.propertyCapabilities =
                      listOf(Json.parseToJsonElement("""{"name":"style","jsonType":"string"}"""))
                    b.slotCapabilities =
                      listOf(Json.parseToJsonElement("""{"name":"label","ordered":false}"""))
                  }
                  .build()
            ),
        ),
      )
    val policy = file!!.statusSemantics.components.getValue("wear-m3/button")
    assertThat(policy.record).isEqualTo(":catalog/androidx.wear.compose.material3.ButtonKt.Button")
    assertThat(policy.displayName).isEqualTo("Button")
    assertThat(policy.traits).containsExactly("Action")
    assertThat(policy.modifierCapabilities).containsExactly("padding", "size").inOrder()
    assertThat(policy.propertyCapabilities).hasSize(1)
    assertThat(policy.slotCapabilities).hasSize(1)
  }

  /**
   * `insertContent` is the UI builder's shape for what a component arrives holding, and this file
   * only carries it: a component's and a builtin's, exactly as authored, and absent when not.
   */
  @Test
  fun `an authored insert content is published as written`() {
    val seed =
      Json.parseToJsonElement(
        """{"slots":{"label":[{"componentId":"wear-m3/text",""" +
          """"properties":{"text":{"type":"string","value":"Checkbox"}}}]}}"""
      )
    val file =
      UiBuilderCatalogs.generate(
        record(
          component("Button", catalogId = "Controls/Button"),
          component("Card", catalogId = "Containers/Card"),
        ),
        cover,
        policy(
          componentIdPrefix = "wear-m3/",
          components =
            mapOf(
              "wear-m3/button" to
                UiBuilderAuthoredComponent.Builder().also { b -> b.insertContent = seed }.build()
            ),
          builtins =
            mapOf(
              "wear-m3/widget-host" to
                UiBuilderBuiltin.Builder(role = "screen-root")
                  .also { b -> b.insertContent = seed }
                  .build()
            ),
        ),
      )!!
    assertThat(file.statusSemantics.components.getValue("wear-m3/button").insertContent)
      .isEqualTo(seed)
    assertThat(file.statusSemantics.components.getValue("wear-m3/card").insertContent).isNull()
    assertThat(file.statusSemantics.builtins.getValue("wear-m3/widget-host").insertContent)
      .isEqualTo(seed)
  }

  /**
   * A record component's shelf role, stated like a builtin's (slot presence can't say `Scaffold`).
   * Carried as written, absent when not, and unknown words reported.
   */
  @Test
  fun `an authored shelf role is published, and an unknown one is reported`() {
    fun stated(shelfRole: String) =
      UiBuilderAuthoredComponent.Builder().also { b -> b.shelfRole = shelfRole }.build()
    val file =
      UiBuilderCatalogs.generate(
        record(
          component("Button", catalogId = "Controls/Button"),
          component("Card", catalogId = "Containers/Card"),
          component("Chip", catalogId = "Controls/Chip"),
        ),
        cover,
        policy(
          componentIdPrefix = "wear-m3/",
          components =
            mapOf("wear-m3/button" to stated("Scaffold"), "wear-m3/chip" to stated("scaffold")),
        ),
      )!!
    val components = file.statusSemantics.components
    assertThat(components.getValue("wear-m3/button").shelfRole).isEqualTo("Scaffold")
    assertThat(components.getValue("wear-m3/card").shelfRole).isNull()
    assertThat(
        file.diagnostics.filter {
          it.code == UiBuilderCatalogs.Diagnostics.COMPONENT_SHELF_ROLE_UNKNOWN
        }
      )
      .hasSize(1)
    assertThat(
        file.diagnostics
          .single { it.code == UiBuilderCatalogs.Diagnostics.COMPONENT_SHELF_ROLE_UNKNOWN }
          .subject
      )
      .isEqualTo("wear-m3/chip")
  }

  @Test
  fun `a catalog publishes its declared Compose source adapter`() {
    val file =
      UiBuilderCatalogs.generate(
        record(component("Button", catalogId = "Controls/Button")),
        cover,
        UiBuilderPolicyFile.Builder(schema = UI_BUILDER_POLICY_SCHEMA, platform = "mobile")
          .also { b ->
            b.composeSourceExport =
              UiBuilderComposeSourceExport.Builder(adapter = "compose-material3", version = 1)
                .build()
          }
          .build(),
      )

    assertThat(file!!.statusSemantics.composeSourceExport)
      .isEqualTo(
        UiBuilderComposeSourceExport.Builder(adapter = "compose-material3", version = 1).build()
      )
  }

  /**
   * A catalog's stable public noun can differ from the current callable's; the explicit record join
   * makes the policy's vocabulary authoritative.
   */
  @Test
  fun `an authored record join preserves a builder id that differs from the derived id`() {
    val canonicalId =
      ":catalog/androidx.wear.compose.material3.LinearProgressIndicatorKt.LinearProgressIndicator"
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "LinearProgressIndicator",
            catalogId = "Progress/Linear",
            group = "Progress",
            builder =
              BuilderPolicy.Builder().also { b -> b.id = "wear-m3/progress-indicator" }.build(),
          )
        ),
        cover,
        policy(
          componentIdPrefix = "wear-m3/",
          components =
            mapOf(
              "wear-m3/linear-progress-indicator" to
                UiBuilderAuthoredComponent.Builder()
                  .also { b ->
                    b.record = canonicalId
                    b.group = "Progress"
                    b.displayName = "Linear progress indicator"
                  }
                  .build()
            ),
        ),
      )!!

    assertThat(generated.statusSemantics.components.keys)
      .containsExactly("wear-m3/linear-progress-indicator")
    assertThat(
        generated.statusSemantics.components.getValue("wear-m3/linear-progress-indicator").record
      )
      .isEqualTo(canonicalId)
    assertThat(generated.statusSemantics.componentMenu.components.keys)
      .containsExactly("wear-m3/linear-progress-indicator")
    assertThat(generated.diagnostics.map { it.code })
      .doesNotContain(UiBuilderCatalogs.Diagnostics.POLICY_ORPHANED)
  }

  /** `modifierCapabilities: []` (accepts none) differs from omitting it (consumer default). */
  @Test
  fun `an omitted capability block stays absent rather than becoming empty`() {
    val file =
      UiBuilderCatalogs.generate(
        record(component("Button", catalogId = "Controls/Button", group = "Actions")),
        cover,
        policy(
          componentIdPrefix = "wear-m3/",
          components =
            mapOf(
              "wear-m3/button" to
                UiBuilderAuthoredComponent.Builder().also { b -> b.displayName = "Button" }.build()
            ),
        ),
      )
    val policy = file!!.statusSemantics.components.getValue("wear-m3/button")
    assertThat(policy.modifierCapabilities).isNull()
    assertThat(policy.propertyCapabilities).isNull()
    assertThat(policy.slotCapabilities).isNull()
  }

  /** The policy file places a component the sticker never annotated. */
  @Test
  fun `an authored group shelves a component`() {
    val file =
      UiBuilderCatalogs.generate(
        record(component("Button", catalogId = "Controls/Button", group = "Actions")),
        cover,
        policy(
          componentIdPrefix = "wear-m3/",
          components =
            mapOf(
              "wear-m3/button" to
                UiBuilderAuthoredComponent.Builder().also { b -> b.group = "Controls" }.build()
            ),
        ),
      )
    assertThat(file!!.statusSemantics.componentMenu.components.getValue("wear-m3/button").group)
      .isEqualTo("Controls")
  }

  /**
   * A policy naming a missing component (a rename that got away) is reported, not dropped, since
   * dropping leaves an unintended default with no symptom.
   */
  @Test
  fun `an authored policy naming no component is reported`() {
    val file =
      UiBuilderCatalogs.generate(
        record(component("Button", catalogId = "Controls/Button", group = "Actions")),
        cover,
        policy(
          componentIdPrefix = "wear-m3/",
          components =
            mapOf(
              "wear-m3/buton" to
                UiBuilderAuthoredComponent.Builder().also { b -> b.displayName = "Typo" }.build()
            ),
        ),
      )
    val orphan =
      file!!.diagnostics.single { it.code == UiBuilderCatalogs.Diagnostics.POLICY_ORPHANED }
    assertThat(orphan.subject).isEqualTo("wear-m3/buton")
    assertThat(orphan.message).contains("joins no component in this record")
  }

  @Test
  fun `a catalog that authors no policy publishes no builder file`() {
    // What makes this contract cost nothing for the catalogs that have not adopted it: the task
    // runs for every module and writes this file for almost none of them.
    assertThat(UiBuilderCatalogs.generate(record(), cover, policy = null)).isNull()
  }

  @Test
  fun `identity comes from the cover sheet and the policy, with the label defaulted`() {
    val generated = UiBuilderCatalogs.generate(record(), cover, policy())!!

    assertThat(generated.schema).isEqualTo(UI_BUILDER_CATALOG_SCHEMA)
    assertThat(generated.catalog.id).isEqualTo("wear-m3")
    assertThat(generated.catalog.title).isEqualTo("Wear M3")
    assertThat(generated.catalog.platform).isEqualTo("wear")
    assertThat(generated.catalog.platformLabel).isEqualTo("Wear")
    assertThat(generated.statusSemantics.platformLabel).isEqualTo("Wear")
  }

  @Test
  fun `a builder id is derived from the catalog identity and overridden by the annotation`() {
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "CheckboxButton",
            catalogId = "Toggles/CheckboxButton",
            builder = BuilderPolicy.Builder().also { b -> b.canvas = "placeholder" }.build(),
          ),
          component(
            "EdgeButton",
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.id = "wear-m3/edge"
                  b.canvas = "p"
                }
                .build(),
          ),
        ),
        cover,
        policy(),
      )!!

    assertThat(generated.statusSemantics.components.keys)
      .containsExactly("wear-m3/checkbox-button", "wear-m3/edge")
    // The join back to the record is the load-bearing field: this file is policy, and the record
    // beside it is the inventory.
    assertThat(generated.statusSemantics.components["wear-m3/checkbox-button"]?.record)
      .isEqualTo(":catalog/androidx.wear.compose.material3.CheckboxButtonKt.CheckboxButton")
  }

  @Test
  fun `a declared component id prefix produces the ids designs already store`() {
    // The component prefix is declared rather than derived from the catalog id (`m3/button`, not
    // `m3-catalog/button`).
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "Button",
            catalogId = "Buttons/Filled",
            builder = BuilderPolicy.Builder().also { b -> b.canvas = "p" }.build(),
          )
        ),
        UiBuilderCatalogs.CoverSheet(system = "m3-catalog", title = "Material 3"),
        policy(platform = "mobile", componentIdPrefix = "m3/"),
      )!!

    assertThat(generated.catalog.id).isEqualTo("m3-catalog")
    assertThat(generated.statusSemantics.components.keys).containsExactly("m3/button")
  }

  @Test
  fun `the slug keeps a run of capitals as one word`() {
    assertThat(UiBuilderCatalogs.slug("CheckboxButton")).isEqualTo("checkbox-button")
    assertThat(UiBuilderCatalogs.slug("TopAppBar")).isEqualTo("top-app-bar")
    assertThat(UiBuilderCatalogs.slug("RTLText")).isEqualTo("rtl-text")
    assertThat(UiBuilderCatalogs.slug("Button2")).isEqualTo("button2")
    assertThat(UiBuilderCatalogs.slug("Screen Scaffold")).isEqualTo("screen-scaffold")
  }

  @Test
  fun `two components claiming one builder id keep the first and report the collision`() {
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "Button",
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.id = "wear-m3/button"
                  b.canvas = "p"
                }
                .build(),
          ),
          component(
            "FilledButton",
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.id = "wear-m3/button"
                  b.canvas = "p"
                }
                .build(),
          ),
        ),
        cover,
        policy(),
      )!!

    assertThat(generated.statusSemantics.components).hasSize(1)
    val collision =
      generated.diagnostics.single { it.code == UiBuilderCatalogs.Diagnostics.ID_COLLISION }
    assertThat(collision.subject).isEqualTo("wear-m3/button")
    assertThat(collision.message).contains("FilledButton")
  }

  @Test
  fun `an unclaimed canvas adapter is reported without being an error`() {
    val generated =
      UiBuilderCatalogs.generate(
        record(component("Card", builder = BuilderPolicy.Builder().build())),
        cover,
        policy(),
      )!!

    // Placeholder is the honest default and needs no fixing. It is reported so that a shelf drawn
    // entirely in placeholders is visible rather than mysterious.
    assertThat(generated.diagnostics.map { it.code })
      .contains(UiBuilderCatalogs.Diagnostics.CANVAS_UNCLAIMED)
    assertThat(generated.statusSemantics.components).hasSize(1)
  }

  @Test
  fun `an authored canvas adapter is claimed in the resolved diagnostics`() {
    val generated =
      UiBuilderCatalogs.generate(
        record(component("Card", builder = BuilderPolicy.Builder().build())),
        cover,
        policy(
          components =
            mapOf(
              "wear-m3/card" to
                UiBuilderAuthoredComponent.Builder().also { b -> b.canvas = "wear-m3/card" }.build()
            )
        ),
      )!!

    assertThat(generated.statusSemantics.components.getValue("wear-m3/card").canvas)
      .isEqualTo("wear-m3/card")
    assertThat(generated.diagnostics.map { it.code })
      .doesNotContain(UiBuilderCatalogs.Diagnostics.CANVAS_UNCLAIMED)
  }

  @Test
  fun `a state callback naming a parameter the component does not take is reported`() {
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "CheckboxButton",
            parameters = listOf(parameter("checked")),
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.canvas = "placeholder"
                  b.stateCallbacks =
                    listOf(
                      BuilderPair.Builder(key = "onCheckedChange", value = "checked:boolean")
                        .build(),
                      BuilderPair.Builder(key = "onSelectedChange", value = "selected:boolean")
                        .build(),
                    )
                }
                .build(),
          )
        ),
        cover,
        policy(),
      )!!

    val reported =
      generated.diagnostics.single {
        it.code == UiBuilderCatalogs.Diagnostics.STATE_CALLBACK_UNKNOWN
      }
    assertThat(reported.subject).endsWith("onSelectedChange")
    // The claim the record CAN check. Unchecked, a misspelt state is invisible until an export
    // silently stops hoisting a `remember` and somebody ships a picture of a checkbox.
    assertThat(reported.message).contains("'selected'")
  }

  @Test
  fun `nothing is checked against a signature that was never recovered`() {
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "CheckboxButton",
            signatureKnown = false,
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.canvas = "placeholder"
                  b.stateCallbacks =
                    listOf(
                      BuilderPair.Builder(key = "onCheckedChange", value = "checked:boolean")
                        .build()
                    )
                  b.slots = listOf(BuilderPair.Builder(key = "content", value = "Content").build())
                }
                .build(),
          )
        ),
        cover,
        policy(),
      )!!

    // "No parameters" and "we could not look" are different facts, and checking against the second
    // would report every entry of a correct policy as wrong.
    assertThat(generated.diagnostics.map { it.code })
      .containsNoneOf(
        UiBuilderCatalogs.Diagnostics.STATE_CALLBACK_UNKNOWN,
        UiBuilderCatalogs.Diagnostics.SLOT_UNKNOWN,
      )
  }

  @Test
  fun `a builtin must name a structural role and must not shadow a record component`() {
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "Card",
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.id = "wear-m3/card"
                  b.canvas = "p"
                }
                .build(),
          )
        ),
        cover,
        policy(
          builtins =
            mapOf(
              "wear-m3/screen-scaffold" to UiBuilderBuiltin.Builder(role = "screen-root").build(),
              "wear-m3/mystery" to UiBuilderBuiltin.Builder(role = "carousel").build(),
              "wear-m3/card" to UiBuilderBuiltin.Builder(role = "list").build(),
            )
        ),
      )!!

    val codes = generated.diagnostics.map { it.code to it.subject }
    assertThat(codes)
      .contains(UiBuilderCatalogs.Diagnostics.BUILTIN_ROLE_UNKNOWN to "wear-m3/mystery")
    // A builtin is for components without a call site; one with a call site belongs in the record.
    assertThat(codes)
      .contains(UiBuilderCatalogs.Diagnostics.BUILTIN_SHADOWS_RECORD to "wear-m3/card")
    assertThat(codes.map { it.first })
      .doesNotContain(
        UiBuilderCatalogs.Diagnostics.BUILTIN_ROLE_UNKNOWN to "wear-m3/screen-scaffold"
      )
  }

  /**
   * A builtin's traits and modifiers reach the published file; without them every builtin read as
   * accepted nowhere.
   */
  /**
   * An excluded component gets no menu entry (the consumer refuses to serve it), but its reason
   * still ships in `statusSemantics.components`.
   */
  @Test
  fun `an excluded component is not on the menu`() {
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component("Card", catalogId = "Containment/Card", group = "Containment"),
          component("Sticker", catalogId = "Containment/Sticker", group = "Containment"),
        ),
        cover,
        policy(
          componentIdPrefix = "wear-m3/",
          components =
            mapOf(
              "wear-m3/sticker" to
                UiBuilderAuthoredComponent.Builder()
                  .also { b -> b.excluded = "the catalog's own preview frame" }
                  .build()
            ),
        ),
      )!!

    val menu = generated.statusSemantics.componentMenu.components
    assertThat(menu.keys).contains("wear-m3/card")
    assertThat(menu.keys).doesNotContain("wear-m3/sticker")
    // The reason is still published, so the shelf's absence is explained rather than silent.
    assertThat(generated.statusSemantics.components.getValue("wear-m3/sticker").excluded)
      .isEqualTo("the catalog's own preview frame")
    val excluded =
      generated.diagnostics.single { it.code == UiBuilderCatalogs.Diagnostics.COMPONENT_EXCLUDED }
    assertThat(excluded.subject).isEqualTo("wear-m3/sticker")
    assertThat(excluded.message).contains("the catalog's own preview frame")
    assertThat(
        generated.diagnostics.any {
          it.code == UiBuilderCatalogs.Diagnostics.CANVAS_UNCLAIMED &&
            it.subject == "wear-m3/sticker"
        }
      )
      .isFalse()
  }

  @Test
  fun `a builtin publishes the traits and modifiers a catalog states`() {
    val generated =
      UiBuilderCatalogs.generate(
        record(component("Card", catalogId = "Containment/Card", group = "Containment")),
        cover,
        policy(
          builtins =
            mapOf(
              "wear-m3/widget-host" to
                UiBuilderBuiltin.Builder(role = "screen-root")
                  .also { b ->
                    b.traits = listOf("WearWidgetHost", "ScreenContent")
                    b.modifierCapabilities = listOf("padding")
                  }
                  .build()
            )
        ),
      )!!

    val builtin = generated.statusSemantics.builtins.getValue("wear-m3/widget-host")
    assertThat(builtin.traits).containsExactly("WearWidgetHost", "ScreenContent").inOrder()
    assertThat(builtin.modifierCapabilities).containsExactly("padding")
  }

  @Test
  fun `a builtin publishes the shelf role, the lanes and the call a catalog states`() {
    // Five fields a consumer otherwise derives (shelf role, canvas lane, export, structured-SVG
    // export, and a slot's `ordered`) must survive republishing, since silence publishes the
    // derived answer as if agreed.
    val generated =
      UiBuilderCatalogs.generate(
        record(component("Card", catalogId = "Containment/Card", group = "Containment")),
        cover,
        policy(
          builtins =
            mapOf(
              "compose-foundation/box" to
                UiBuilderBuiltin.Builder(role = "container")
                  .also { builder ->
                    builder.shelfRole = "Container"
                    builder.wasm =
                      UiBuilderBuiltinWasm.Builder()
                        .also { b ->
                          b.platformSupported = JsonPrimitive(true)
                          b.adapterStatus = "planned"
                        }
                        .build()
                    builder.code =
                      UiBuilderBuiltinCode.Builder(symbol = "Box")
                        .also { b -> b.imports = listOf("androidx.compose.foundation.layout.Box") }
                        .build()
                    builder.svg =
                      UiBuilderBuiltinSvg.Builder(status = "verified", fallback = "none").build()
                    builder.slots =
                      mapOf("children" to Json.parseToJsonElement("{\"ordered\": false}"))
                  }
                  .build()
            )
        ),
      )!!

    // None of the builtin's five fields is reported; the record component's unclaimed canvas isn't
    // this test's subject.
    assertThat(generated.diagnostics.filter { it.subject.startsWith("compose-foundation/") })
      .isEmpty()
    val builtin = generated.statusSemantics.builtins.getValue("compose-foundation/box")
    assertThat(builtin.shelfRole).isEqualTo("Container")
    assertThat(builtin.wasm?.adapterStatus).isEqualTo("planned")
    assertThat(builtin.code?.symbol).isEqualTo("Box")
    assertThat(builtin.svg?.status).isEqualTo("verified")
    // Slots are carried verbatim, so `ordered` reaching the file is the claim.
    assertThat(builtin.slots.getValue("children").jsonObject["ordered"]?.jsonPrimitive?.content)
      .isEqualTo("false")
  }

  /**
   * The editing canvas's mock for record components and builtins, asserted on the encoded serial
   * name. Needed because a container drawn as itself can't show children beyond its frame.
   */
  @Test
  fun `an unrolled mock publishes for a component and a builtin, and stays absent when unstated`() {
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component("LazyColumn", catalogId = "Layout/LazyColumn", group = "Layout"),
          component("Card", catalogId = "Containment/Card", group = "Containment"),
        ),
        cover,
        policy(
          componentIdPrefix = "m3/",
          builtins =
            mapOf(
              "compose-foundation/lazy-grid" to
                UiBuilderBuiltin.Builder(role = "container")
                  .also { builder ->
                    builder.unrolled =
                      UiBuilderUnrolledMock.Builder(layout = "wrap")
                        .also { b ->
                          b.cellWidthDp = JsonPrimitive(280)
                          b.spacingDp = JsonPrimitive(4)
                        }
                        .build()
                  }
                  .build()
            ),
          components =
            mapOf(
              "m3/lazy-column" to
                UiBuilderAuthoredComponent.Builder()
                  .also { b ->
                    b.displayName = "Lazy column"
                    b.canvas = "material3/LazyColumn"
                    b.unrolled = UiBuilderUnrolledMock.Builder(layout = "stack").build()
                  }
                  .build(),
              "m3/card" to
                UiBuilderAuthoredComponent.Builder()
                  .also { b ->
                    b.displayName = "Card"
                    b.canvas = "material3/Card"
                  }
                  .build(),
            ),
        ),
      )!!

    val component = generated.statusSemantics.components.getValue("m3/lazy-column")
    assertThat(component.unrolled?.layout).isEqualTo("stack")
    // Absent is the default and stays absent: a component that states no mock keeps its own layout
    // while editing, which is what every component did before the field existed.
    assertThat(generated.statusSemantics.components.getValue("m3/card").unrolled).isNull()

    val builtin = generated.statusSemantics.builtins.getValue("compose-foundation/lazy-grid")
    assertThat(builtin.unrolled?.layout).isEqualTo("wrap")
    assertThat(builtin.unrolled?.cellWidthDp).isEqualTo(JsonPrimitive(280))
    assertThat(builtin.unrolled?.spacingDp).isEqualTo(JsonPrimitive(4))

    val encoded = Json {
      ignoreUnknownKeys = true
    }
      .parseToJsonElement(Json.encodeToString(generated))
      .jsonObject
      .getValue("statusSemantics")
      .jsonObject
    assertThat(
        encoded
          .getValue("components")
          .jsonObject
          .getValue("m3/lazy-column")
          .jsonObject
          .getValue("unrolled")
          .jsonObject
          .getValue("layout")
          .jsonPrimitive
          .content
      )
      .isEqualTo("stack")
    assertThat(
        encoded
          .getValue("builtins")
          .jsonObject
          .getValue("compose-foundation/lazy-grid")
          .jsonObject
          .getValue("unrolled")
          .jsonObject
          .getValue("cellWidthDp")
          .jsonPrimitive
          .content
      )
      .isEqualTo("280")
  }

  @Test
  fun `browser preview and canvas vocabulary projections are carried without interpretation`() {
    val browserPreview =
      Json.parseToJsonElement("""{"renderer":"remote-compose-document","format":"rc"}""")
    val canvasMapping =
      Json.parseToJsonElement(
        """{"properties":{"fontSizeSp":"fontSize"},"defaults":{"variant":{"type":"enum","value":"filled"}}}"""
      )
    val generated =
      UiBuilderCatalogs.generate(
        record(component("Button", catalogId = "Buttons/Button", group = "Actions")),
        cover,
        policy(
          componentIdPrefix = "remote-m3/",
          browserPreview = browserPreview,
          components =
            mapOf(
              "remote-m3/button" to
                UiBuilderAuthoredComponent.Builder()
                  .also { b ->
                    b.canvas = "wear-m3/button"
                    b.canvasMapping = canvasMapping
                  }
                  .build()
            ),
        ),
      )!!

    assertThat(generated.statusSemantics.browserPreview).isEqualTo(browserPreview)
    assertThat(generated.statusSemantics.components.getValue("remote-m3/button").canvasMapping)
      .isEqualTo(canvasMapping)
  }

  @Test
  fun `the two role vocabularies in one declaration reject each other's words`() {
    // `role` says which template writes the component; `shelfRole` says its shape. Both serialise
    // as `role`, so each must refuse the other's vocabulary.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "Card",
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.id = "m3/card"
                  b.canvas = "p"
                }
                .build(),
          )
        ),
        cover,
        policy(
          builtins =
            mapOf(
              "compose-foundation/box" to
                UiBuilderBuiltin.Builder(role = "container")
                  .also { b -> b.shelfRole = "container" }
                  .build(),
              "compose-foundation/column" to
                UiBuilderBuiltin.Builder(role = "container")
                  .also { builder ->
                    builder.wasm =
                      UiBuilderBuiltinWasm.Builder().also { b -> b.adapterStatus = "soon" }.build()
                    builder.code = UiBuilderBuiltinCode.Builder(symbol = " ").build()
                  }
                  .build(),
            )
        ),
      )!!

    val codes = generated.diagnostics.map { it.code to it.subject }
    assertThat(codes)
      .containsAtLeast(
        UiBuilderCatalogs.Diagnostics.BUILTIN_SHELF_ROLE_UNKNOWN to "compose-foundation/box",
        UiBuilderCatalogs.Diagnostics.BUILTIN_WASM_STATUS_UNKNOWN to "compose-foundation/column",
        UiBuilderCatalogs.Diagnostics.BUILTIN_CODE_EMPTY to "compose-foundation/column",
      )
    // `container` is a structural role now, so it mustn't also be reported as an unknown template
    // role.
    assertThat(codes.map { it.first })
      .doesNotContain(UiBuilderCatalogs.Diagnostics.BUILTIN_ROLE_UNKNOWN)
  }

  @Test
  fun `a builtin slot names a structural role too`() {
    // A slot's role selects a template like a builtin's; it must be checked here, not only in the
    // workflow pre-flight that local and direct-pack consumers never run.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "Card",
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.id = "wear-m3/card"
                  b.canvas = "p"
                }
                .build(),
          )
        ),
        cover,
        policy(
          builtins =
            mapOf(
              "wear-m3/screen-scaffold" to
                UiBuilderBuiltin.Builder(role = "screen-root")
                  .also { b ->
                    b.slots =
                      mapOf(
                        "content" to Json.parseToJsonElement("{\"role\": \"lisst\"}"),
                        "footer" to Json.parseToJsonElement("{\"role\": \"list\"}"),
                        // An unreadable slot shape belongs to the loader, so no diagnostic here.
                        "header" to Json.parseToJsonElement("\"just a string\""),
                      )
                  }
                  .build()
            )
        ),
      )!!

    val codes = generated.diagnostics.map { it.code to it.subject }
    assertThat(codes)
      .contains(
        UiBuilderCatalogs.Diagnostics.BUILTIN_SLOT_ROLE_UNKNOWN to "wear-m3/screen-scaffold/content"
      )
    assertThat(codes)
      .containsNoneOf(
        UiBuilderCatalogs.Diagnostics.BUILTIN_SLOT_ROLE_UNKNOWN to "wear-m3/screen-scaffold/footer",
        UiBuilderCatalogs.Diagnostics.BUILTIN_SLOT_ROLE_UNKNOWN to "wear-m3/screen-scaffold/header",
      )
  }

  @Test
  fun `code strategy is an enum on the authoritative path too`() {
    // A misspelled strategy satisfies neither neighbouring check; it must be reported here, not
    // only in the workflow pre-flight.
    val misspelled =
      UiBuilderCatalogs.generate(
        record(
          component(
            "Card",
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.id = "wear-m3/card"
                  b.canvas = "p"
                }
                .build(),
          )
        ),
        cover,
        policy(code = UiBuilderCode.Builder().also { b -> b.strategy = "templtes" }.build()),
      )!!

    val codes = misspelled.diagnostics.map { it.code to it.subject }
    assertThat(codes).contains(UiBuilderCatalogs.Diagnostics.STRATEGY_UNKNOWN to "code.strategy")
    // Not reported as the agreement failures, which are about a strategy the engine DOES know.
    assertThat(codes.map { it.first })
      .containsNoneOf(
        UiBuilderCatalogs.Diagnostics.STRATEGY_WITHOUT_TEMPLATES,
        UiBuilderCatalogs.Diagnostics.TEMPLATES_WITHOUT_STRATEGY,
      )

    for (known in listOf("record", "templates")) {
      val fine =
        UiBuilderCatalogs.generate(
          record(
            component(
              "Card",
              builder =
                BuilderPolicy.Builder()
                  .also { b ->
                    b.id = "wear-m3/card"
                    b.canvas = "p"
                  }
                  .build(),
            )
          ),
          cover,
          policy(
            code =
              UiBuilderCode.Builder()
                .also { b ->
                  b.strategy = known
                  b.templates =
                    if (known == "templates") mapOf("screen-root" to "Box {}") else mapOf()
                }
                .build()
          ),
        )!!
      assertThat(fine.diagnostics.map { it.code })
        .doesNotContain(UiBuilderCatalogs.Diagnostics.STRATEGY_UNKNOWN)
    }
  }

  @Test
  fun `templates and the strategy have to agree`() {
    val declaredButUnused =
      UiBuilderCatalogs.generate(
        record(),
        cover,
        policy(
          code =
            UiBuilderCode.Builder()
              .also { b ->
                b.strategy = "record"
                b.templates = mapOf("list" to "…")
              }
              .build()
        ),
      )!!
    assertThat(declaredButUnused.diagnostics.map { it.code })
      .contains(UiBuilderCatalogs.Diagnostics.TEMPLATES_WITHOUT_STRATEGY)

    val claimedButAbsent =
      UiBuilderCatalogs.generate(
        record(),
        cover,
        policy(code = UiBuilderCode.Builder().also { b -> b.strategy = "templates" }.build()),
      )!!
    assertThat(claimedButAbsent.diagnostics.map { it.code })
      .contains(UiBuilderCatalogs.Diagnostics.STRATEGY_WITHOUT_TEMPLATES)

    val unknownRole =
      UiBuilderCatalogs.generate(
        record(),
        cover,
        policy(
          code =
            UiBuilderCode.Builder()
              .also { b ->
                b.strategy = "templates"
                b.templates = mapOf("screen-root" to "…", "previews" to "…", "carousel" to "…")
              }
              .build()
        ),
      )!!
    assertThat(
        unknownRole.diagnostics.filter {
          it.code == UiBuilderCatalogs.Diagnostics.TEMPLATE_ROLE_UNKNOWN
        }
      )
      .hasSize(1)
  }

  @Test
  fun `a template that cannot be read is reported against the role that declares it`() {
    // Read as templates, not merely as strings. Without this, a typo'd hole is a refused export
    // weeks later, for somebody who did not write the policy.
    val generated =
      UiBuilderCatalogs.generate(
        record(),
        cover,
        policy(
          code =
            UiBuilderCode.Builder()
              .also { builder ->
                builder.strategy = "templates"
                builder.templates =
                  mapOf("screen-root" to "AppScaffold {\n  \${content}\n}", "list" to "a \${ b")
              }
              .build()
        ),
      )!!

    val reported =
      generated.diagnostics.single { it.code == UiBuilderCatalogs.Diagnostics.TEMPLATE_MALFORMED }
    assertThat(reported.subject).isEqualTo("list")
    assertThat(reported.message).contains("unterminated")
  }

  @Test
  fun `an ambiguous subject and a malformed entry are both reported by name`() {
    // Both are lenient in discovery and only acceptable because they're reported in the published
    // file.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "Button",
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.id = "wear-m3/button"
                  b.canvas = "placeholder"
                  b.ambiguousWith = listOf(":catalog/…TextKt.Text")
                  b.malformed = listOf("starter: noSeparator")
                }
                .build(),
          )
        ),
        cover,
        policy(),
      )!!

    val ambiguous =
      generated.diagnostics.single {
        it.code == UiBuilderCatalogs.Diagnostics.POLICY_AMBIGUOUS_SUBJECT
      }
    assertThat(ambiguous.message).contains("@BuilderComponent(component = ")
    val malformed =
      generated.diagnostics.single {
        it.code == UiBuilderCatalogs.Diagnostics.POLICY_MALFORMED_ENTRY
      }
    assertThat(malformed.message).contains("starter: noSeparator")
  }

  @Test
  fun `a state callback that is not a parameter is reported, not just its state`() {
    // A typoed callback key would publish and leave the export nothing to hoist against, silently.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "CheckboxButton",
            parameters = listOf(parameter("checked"), parameter("onCheckedChange")),
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.canvas = "placeholder"
                  b.stateCallbacks =
                    listOf(
                      BuilderPair.Builder(key = "onChekedChange", value = "checked:boolean").build()
                    )
                }
                .build(),
          )
        ),
        cover,
        policy(),
      )!!

    val reported =
      generated.diagnostics.single {
        it.code == UiBuilderCatalogs.Diagnostics.STATE_CALLBACK_NOT_A_PARAMETER
      }
    assertThat(reported.subject).endsWith("onChekedChange")
  }

  @Test
  fun `two unannotated components deriving one id are reported`() {
    // Unannotated components get ids from `componentIdPrefix` too, so collisions among them matter.
    // Same-named callables in different packages (`Card` in material3 and foundation) are the
    // realistic collision.
    val foundationCard =
      component("Card", catalogId = "Layout/Card").let {
        it
          .newBuilder()
          .also { builder ->
            builder.canonicalId = ":catalog/androidx.wear.compose.foundation.CardKt.Card"
            builder.symbol =
              it.symbol
                .newBuilder()
                .also { b ->
                  b.jvmOwner = "androidx.wear.compose.foundation.CardKt"
                  b.callable = "androidx.wear.compose.foundation.Card"
                }
                .build()
          }
          .build()
      }
    val generated =
      UiBuilderCatalogs.generate(
        record(component("Card", catalogId = "Containment/Card"), foundationCard),
        cover,
        policy(),
      )!!

    val collision =
      generated.diagnostics.single { it.code == UiBuilderCatalogs.Diagnostics.ID_COLLISION }
    assertThat(collision.subject).isEqualTo("wear-m3/card")
    // The first owns the id, and the collision still names both.
    assertThat(generated.statusSemantics.components.getValue("wear-m3/card").record)
      .isEqualTo(":catalog/androidx.wear.compose.material3.CardKt.Card")
  }

  @Test
  fun `an unannotated component colliding with an explicit id is reported`() {
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component("Card"),
          component(
            "Tile",
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.id = "wear-m3/card"
                  b.canvas = "p"
                }
                .build(),
          ),
        ),
        cover,
        policy(),
      )!!

    assertThat(generated.diagnostics.map { it.code })
      .contains(UiBuilderCatalogs.Diagnostics.ID_COLLISION)
  }

  @Test
  fun `a variant property naming nothing the component takes is reported`() {
    // The variant switches in the panel and the generated call never changes, which reads as a
    // builder bug rather than as a typo in the catalog.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "Button",
            parameters = listOf(parameter("style")),
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.canvas = "placeholder"
                  b.variantProperty = "styel"
                  b.variants =
                    listOf(
                      BuilderPair.Builder(key = "Filled", value = "ButtonStyle.Filled").build()
                    )
                }
                .build(),
          )
        ),
        cover,
        policy(),
      )!!

    val reported =
      generated.diagnostics.single {
        it.code == UiBuilderCatalogs.Diagnostics.VARIANT_PROPERTY_UNKNOWN
      }
    assertThat(reported.subject).endsWith("styel")
  }

  @Test
  fun `a state callback without a usable type is reported`() {
    // `onCheckedChange=checked` has no type and `checked:bool` an unknown one; the export prints
    // the initial value from the type, so both must be reported.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "CheckboxButton",
            parameters = listOf(parameter("checked"), parameter("onCheckedChange")),
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.canvas = "placeholder"
                  b.stateCallbacks =
                    listOf(
                      BuilderPair.Builder(key = "onCheckedChange", value = "checked").build(),
                      BuilderPair.Builder(key = "onCheckedChange2", value = "checked:bool").build(),
                      // A colon and type but no state name must not slip past both checks.
                      BuilderPair.Builder(key = "onCheckedChange3", value = ":boolean").build(),
                    )
                }
                .build(),
          )
        ),
        cover,
        policy(),
      )!!

    val reported =
      generated.diagnostics.filter {
        it.code == UiBuilderCatalogs.Diagnostics.STATE_CALLBACK_MALFORMED
      }
    assertThat(reported).hasSize(3)
    assertThat(reported.map { it.subject })
      .containsExactly(
        "wear-m3/checkbox-button.onCheckedChange",
        "wear-m3/checkbox-button.onCheckedChange2",
        "wear-m3/checkbox-button.onCheckedChange3",
      )
    // The message names the vocabulary, because "malformed" without it is not actionable.
    reported.forEach { assertThat(it.message).contains("boolean") }
  }

  @Test
  fun `variants with no property to write to are reported`() {
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "Button",
            parameters = listOf(parameter("style")),
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.canvas = "placeholder"
                  b.variants =
                    listOf(
                      BuilderPair.Builder(key = "Filled", value = "ButtonStyle.Filled").build()
                    )
                }
                .build(),
          )
        ),
        cover,
        policy(),
      )!!

    assertThat(generated.diagnostics.map { it.code })
      .contains(UiBuilderCatalogs.Diagnostics.VARIANTS_WITHOUT_PROPERTY)
  }

  @Test
  fun `a state callback naming a non-function parameter is reported`() {
    // `label=checked:boolean` names a real parameter, so every membership check passes — and the
    // export would emit a lambda where the component wants a String.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "CheckboxButton",
            parameters =
              listOf(
                parameter("label"),
                parameter("checked"),
                parameter("onCheckedChange", type = "(Boolean) -> Unit"),
              ),
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.canvas = "placeholder"
                  b.stateCallbacks =
                    listOf(
                      BuilderPair.Builder(key = "label", value = "checked:boolean").build(),
                      // The correct shape, which must NOT be reported.
                      BuilderPair.Builder(key = "onCheckedChange", value = "checked:boolean")
                        .build(),
                    )
                }
                .build(),
          )
        ),
        cover,
        policy(),
      )!!

    val reported =
      generated.diagnostics.single {
        it.code == UiBuilderCatalogs.Diagnostics.STATE_CALLBACK_NOT_A_FUNCTION
      }
    assertThat(reported.subject).endsWith("label")
  }

  @Test
  fun `a malformed callback is reported even when the signature was never read`() {
    // `bool` is never a supported type, whatever the component turns out to take — so the entry's
    // own syntax is checked before the signature guard, which previously swallowed it.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "Unknown",
            signatureKnown = false,
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.canvas = "placeholder"
                  b.stateCallbacks =
                    listOf(
                      BuilderPair.Builder(key = "onCheckedChange", value = "checked:bool").build()
                    )
                }
                .build(),
          )
        ),
        cover,
        policy(),
      )!!

    assertThat(generated.diagnostics.map { it.code })
      .contains(UiBuilderCatalogs.Diagnostics.STATE_CALLBACK_MALFORMED)
    // …and the signature-dependent checks stay silent, because there is no signature to check.
    assertThat(generated.diagnostics.map { it.code })
      .doesNotContain(UiBuilderCatalogs.Diagnostics.STATE_CALLBACK_NOT_A_PARAMETER)
  }

  @Test
  fun `a declared state type that the component does not take is reported`() {
    // A supported type that mismatches the parameter: the export would thread a String into a
    // Boolean.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "CheckboxButton",
            parameters =
              listOf(
                parameter("checked", type = "Boolean"),
                parameter("onCheckedChange", type = "(Boolean) -> Unit"),
              ),
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.canvas = "placeholder"
                  b.stateCallbacks =
                    listOf(
                      BuilderPair.Builder(key = "onCheckedChange", value = "checked:string").build()
                    )
                }
                .build(),
          )
        ),
        cover,
        policy(),
      )!!

    val reported =
      generated.diagnostics.single {
        it.code == UiBuilderCatalogs.Diagnostics.STATE_CALLBACK_TYPE_MISMATCH
      }
    assertThat(reported.message).contains("Boolean")
  }

  @Test
  fun `the matching declared type is not reported`() {
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "CheckboxButton",
            parameters =
              listOf(
                // Nullable and package-qualified forms are the same classifier; written as records
                // hold them (`kotlin.Boolean`).
                parameter("checked", type = "kotlin.Boolean?"),
                parameter("onCheckedChange", type = "(Boolean) -> Unit"),
              ),
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.canvas = "placeholder"
                  b.stateCallbacks =
                    listOf(
                      BuilderPair.Builder(key = "onCheckedChange", value = "checked:boolean")
                        .build()
                    )
                }
                .build(),
          )
        ),
        cover,
        policy(),
      )!!

    assertThat(generated.diagnostics.map { it.code })
      .doesNotContain(UiBuilderCatalogs.Diagnostics.STATE_CALLBACK_TYPE_MISMATCH)
  }

  @Test
  fun `an unannotated component keeps its catalog group on the shelf`() {
    // Unannotated catalogs must still group every component by its @CatalogGroup.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component("Card", catalogId = "Containment/Card", group = "Containment"),
          component(
            "Button",
            catalogId = "Actions/Button",
            group = "Actions",
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.canvas = "p"
                  b.group = "Overridden"
                }
                .build(),
          ),
        ),
        cover,
        policy(),
      )!!

    val menu = generated.statusSemantics.componentMenu.components
    // The unannotated component keeps the catalog's own grouping…
    assertThat(menu["wear-m3/card"]?.group).isEqualTo("Containment")
    // …and the annotation is an override, which is what it was always documented as.
    assertThat(menu["wear-m3/button"]?.group).isEqualTo("Overridden")
  }

  /**
   * A callable drawn but not declared by a sticker still lands on that sticker's shelf; bindings
   * only carry a group for the declared component, which left many components with no menu entry.
   */
  @Test
  fun `a component the sticker did not declare is shelved by its catalog id`() {
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component("TopAppBar", catalogId = "TopAppBar/Small", group = "Top app bar"),
          component("LargeTopAppBar", catalogId = "TopAppBar/Small", group = null),
        ),
        cover,
        policy(),
      )!!

    val menu = generated.statusSemantics.componentMenu.components
    assertThat(menu["wear-m3/top-app-bar"]?.group).isEqualTo("Top app bar")
    assertThat(menu["wear-m3/large-top-app-bar"]?.group).isEqualTo("Top app bar")
    // The invariant the loop's own comment states, asserted rather than described.
    assertThat(menu.keys).containsExactlyElementsIn(generated.statusSemantics.components.keys)
  }

  /**
   * A component no sticker declares has no catalog id and so no shelf; asserted absent (a stated
   * gap) and placeable via the policy.
   */
  @Test
  fun `a component no sticker declares is left for the policy file to place`() {
    val unclaimed = record(component("AnimatedPane", catalogId = null, group = null))

    val bare = UiBuilderCatalogs.generate(unclaimed, cover, policy())!!
    assertThat(bare.statusSemantics.components.keys).contains("wear-m3/animated-pane")
    assertThat(bare.statusSemantics.componentMenu.components["wear-m3/animated-pane"]).isNull()

    val placed =
      UiBuilderCatalogs.generate(
        unclaimed,
        cover,
        policy(
          components =
            mapOf(
              "wear-m3/animated-pane" to
                UiBuilderAuthoredComponent.Builder().also { b -> b.group = "Layout" }.build()
            )
        ),
      )!!
    assertThat(placed.statusSemantics.componentMenu.components["wear-m3/animated-pane"]?.group)
      .isEqualTo("Layout")
  }

  @Test
  fun `a starter naming something that is not a parameter is reported`() {
    // A starter value is printed as a NAMED ARGUMENT, so a `lable=` typo is either dropped by a
    // lenient consumer or compiled into a call to a parameter that does not exist.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "CheckboxButton",
            parameters = listOf(parameter("label")),
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.canvas = "placeholder"
                  b.starter = listOf(BuilderPair.Builder(key = "lable", value = "Hi").build())
                }
                .build(),
          )
        ),
        cover,
        policy(),
      )!!

    val reported =
      generated.diagnostics.single {
        it.code == UiBuilderCatalogs.Diagnostics.STARTER_UNKNOWN_PARAMETER
      }
    assertThat(reported.subject).endsWith("lable")
  }

  @Test
  fun `the resolved component id prefix is published`() {
    // The prefix is the only way a consumer can name a component this file omits; guessing wrong
    // changes every saved design's identity.
    val prefixed =
      UiBuilderCatalogs.generate(
        record(component("Card")),
        cover,
        policy().newBuilder().also { b -> b.componentIdPrefix = "m3/" }.build(),
      )!!
    assertThat(prefixed.statusSemantics.componentIdPrefix).isEqualTo("m3/")

    // Defaulted from the catalog id when the policy declares none, so the field is always usable.
    val defaulted = UiBuilderCatalogs.generate(record(component("Card")), cover, policy())!!
    assertThat(defaulted.statusSemantics.componentIdPrefix).isEqualTo("wear-m3/")
  }

  @Test
  fun `a published entry names the catalog alias of the sticker that declared it`() {
    // The entry links to the declaring sticker (`Buttons/Tonal`), while the id is the component's.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "Button",
            catalogIds = listOf("Buttons/Filled", "Buttons/Tonal"),
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.canvas = "placeholder"
                  b.declaredForCatalogId = "Buttons/Tonal"
                }
                .build(),
          )
        ),
        cover,
        policy(),
      )!!

    val entry = generated.statusSemantics.components.values.single()
    assertThat(entry.catalogId).isEqualTo("Buttons/Tonal")
    assertThat(generated.statusSemantics.components.keys.single()).isEqualTo("wear-m3/button")
  }

  @Test
  fun `a policy that bound to nothing is reported by the preview that declared it`() {
    // The generator reads the record, not the manifest, so an orphan has to travel in the file or
    // it cannot be reported anywhere a person will look.
    val generated =
      UiBuilderCatalogs.generate(
        record(
            component(
              "Card",
              builder =
                BuilderPolicy.Builder()
                  .also { b ->
                    b.id = "wear-m3/card"
                    b.canvas = "p"
                  }
                  .build(),
            )
          )
          .newBuilder()
          .also { builder ->
            builder.builderOrphans =
              listOf(
                BuilderOrphan.Builder(previewId = "p1", component = "CheckboxButton")
                  .also { b -> b.candidates = listOf(":catalog/…CardKt.Card") }
                  .build()
              )
          }
          .build(),
        cover,
        policy(),
      )!!

    val reported =
      generated.diagnostics.single { it.code == UiBuilderCatalogs.Diagnostics.POLICY_ORPHANED }
    assertThat(reported.subject).isEqualTo("p1")
    assertThat(reported.message).contains("CheckboxButton")
    assertThat(reported.message).contains("CardKt.Card")
  }

  @Test
  fun `a derived id comes from the sticker that declared the policy`() {
    // One callable published under several catalog ids gets one id: the component's symbol.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
              "Button",
              catalogId = "Buttons/Filled",
              builder = BuilderPolicy.Builder().also { b -> b.canvas = "p" }.build(),
            )
            .let {
              it
                .newBuilder()
                .also { b -> b.componentIds = listOf("Buttons/Filled", "Buttons/Tonal") }
                .build()
            }
            .let {
              it
                .newBuilder()
                .also { b3 ->
                  b3.builder =
                    it.builder!!
                      .newBuilder()
                      .also { b -> b.declaredForCatalogId = "Buttons/Tonal" }
                      .build()
                }
                .build()
            }
        ),
        cover,
        policy(),
      )!!

    assertThat(generated.statusSemantics.components.keys).containsExactly("wear-m3/button")
  }

  @Test
  fun `a builtin colliding with an unannotated record component is reported`() {
    // An unannotated component is shelved under its derived id, so a builtin sharing it is a
    // collision.
    val generated =
      UiBuilderCatalogs.generate(
        record(component("Card", catalogId = "Containment/Card")),
        cover,
        policy(
          builtins = mapOf("wear-m3/card" to UiBuilderBuiltin.Builder(role = "decoration").build())
        ),
      )!!

    assertThat(generated.diagnostics.map { it.code to it.subject })
      .contains(UiBuilderCatalogs.Diagnostics.BUILTIN_SHADOWS_RECORD to "wear-m3/card")
  }

  @Test
  fun `a template hole no role supplies is reported against the role`() {
    // `${'$'}{contnet}` is a valid name; only knowing which names the role provides catches it,
    // rather than export failing much later.
    val generated =
      UiBuilderCatalogs.generate(
        record(),
        cover,
        policy(
          code =
            UiBuilderCode.Builder()
              .also { b ->
                b.strategy = "templates"
                b.templates = mapOf("screen-root" to "AppScaffold {\n  \${contnet}\n}")
              }
              .build()
        ),
      )!!

    val reported =
      generated.diagnostics.single {
        it.code == UiBuilderCatalogs.Diagnostics.TEMPLATE_HOLE_UNKNOWN
      }
    assertThat(reported.subject).isEqualTo("screen-root.contnet")
    assertThat(reported.message).contains("content")
  }

  @Test
  fun `the generated file round-trips through JSON`() {
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "CheckboxButton",
            catalogId = "Toggles/CheckboxButton",
            parameters = listOf(parameter("checked")),
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.canvas = "placeholder"
                  b.starter = listOf(BuilderPair.Builder(key = "label", value = "Checkbox").build())
                  b.stateCallbacks =
                    listOf(
                      BuilderPair.Builder(key = "onCheckedChange", value = "checked:boolean")
                        .build()
                    )
                  b.traits = listOf("Action")
                }
                .build(),
          )
        ),
        cover,
        policy(),
      )!!

    val json = Json { ignoreUnknownKeys = true }
    val text = json.encodeToString(generated)
    assertThat(json.decodeFromString<UiBuilderCatalogFile>(text)).isEqualTo(generated)
    // The record it was generated against, so a consumer can tell the two files are a pair.
    assertThat(generated.record.file).isEqualTo(UI_BUILDER_RECORD_FILE)
    assertThat(generated.record.components).isEqualTo(1)
  }

  @Test
  fun `a policy file as a catalog repository actually writes it decodes`() {
    // Modelled on wear-m3-catalog's policy file. Pins what a model-built test can't:
    //   - `$comment` keys: `ignoreUnknownKeys` covers nested objects, but not `builtins` (typed
    //     values), hence the `$comment_<field>` convention beside fields.
    //   - `frame` kept as a raw JsonElement whose geometry this generator doesn't parse.
    val text =
      """
      {
        "${'$'}schema": "https://example/ui-builder.policy.schema.json",
        "${'$'}comment": "Catalog-level policy; per-component policy is @BuilderComponent.",
        "schema": "compose-ui-builder-policy/v1",
        "${'$'}comment_catalogId": "The delivery system is wear-m3-catalog; designs store wear-m3.",
        "catalogId": "wear-m3",
        "platform": "wear",
        "platformLabel": "Wear",
        "previewSurfaces": {
          "wasm": { "fidelity": "approximate", "reason": "cannot link an Android AAR" },
          "native": { "fidelity": "authoritative", "backend": "android" }
        },
        "frame": {
          "adapter": "frame/round-screen",
          "${'$'}comment_seedDevice": "the first documented breakpoint",
          "seedDevice": "id:wearos_small_round",
          "geometry": {
            "${'$'}comment": "written by ScreenScaffoldContentPaddingTest, never by hand",
            "contentPadding": [ { "screenDp": 192, "horizontalDp": 10, "verticalDp": 20 } ]
          }
        },
        "${'$'}comment_builtins": "the only components this file may declare",
        "builtins": {
          "wear-m3/screen-scaffold": {
            "role": "screen-root",
            "displayName": "Screen",
            "group": "Layout",
            "canvas": "frame/round-screen",
            "implementation": ":catalog/androidx.wear.compose.material3.CardKt.Card",
            "slots": { "content": { "required": true, "role": "list" } }
          }
        },
        "supersedes": {
          "m3/card": { "componentId": "wear-m3/card", "properties": { "title": "headline" } }
        },
        "menu": {
          "${'$'}comment": "the catalog's own @CatalogGroup sections, in reaching order",
          "groupOrder": ["Layout", "Navigation", "Actions"]
        },
        "${'$'}comment_code": "no templates until the engine that runs them exists"
      }
      """
        .trimIndent()

    val policy = Json { ignoreUnknownKeys = true }.decodeFromString<UiBuilderPolicyFile>(text)

    assertThat(policy.catalogId).isEqualTo("wear-m3")
    assertThat(policy.platform).isEqualTo("wear")
    assertThat(policy.builtins.keys).containsExactly("wear-m3/screen-scaffold")
    assertThat(policy.builtins.getValue("wear-m3/screen-scaffold").role).isEqualTo("screen-root")
    assertThat(policy.builtins.getValue("wear-m3/screen-scaffold").implementation)
      .isEqualTo(":catalog/androidx.wear.compose.material3.CardKt.Card")
    assertThat(policy.menu?.groupOrder).containsExactly("Layout", "Navigation", "Actions").inOrder()
    assertThat(policy.code).isNull()

    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "Card",
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.id = "wear-m3/card"
                  b.canvas = "p"
                }
                .build(),
          )
        ),
        cover,
        policy,
      )!!

    // The declared id wins over the cover sheet's `system`, and the frame rides through verbatim.
    assertThat(generated.catalog.id).isEqualTo("wear-m3")
    assertThat(generated.statusSemantics.frame).isEqualTo(policy.frame)
    assertThat(generated.statusSemantics.supersedes).isEqualTo(policy.supersedes)
    assertThat(generated.statusSemantics.builtins).isEqualTo(policy.builtins)
    assertThat(generated.diagnostics.map { it.code })
      .containsNoneOf(
        UiBuilderCatalogs.Diagnostics.POLICY_SCHEMA_UNKNOWN,
        UiBuilderCatalogs.Diagnostics.BUILTIN_ROLE_UNKNOWN,
        UiBuilderCatalogs.Diagnostics.BUILTIN_SHADOWS_RECORD,
      )
  }

  @Test
  fun `an unannotated first claimant keeps the id it won`() {
    // The first claimant wins, and its policy (not the later annotated one's) must be published
    // under the contested id.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component("Button", catalogId = "Buttons/Button", group = "Actions"),
          component(
            "Other",
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.id = "wear-m3/button"
                  b.canvas = "frame/round-screen"
                }
                .build(),
          ),
        ),
        cover,
        policy(),
      )!!

    assertThat(
        generated.diagnostics
          .single { it.code == UiBuilderCatalogs.Diagnostics.ID_COLLISION }
          .subject
      )
      .isEqualTo("wear-m3/button")
    // Shelf and menu entries under the contested id belong to the winner, agreeing with the
    // diagnostic.
    assertThat(generated.statusSemantics.components.getValue("wear-m3/button").record)
      .isEqualTo(":catalog/androidx.wear.compose.material3.ButtonKt.Button")
    assertThat(generated.statusSemantics.componentMenu.components["wear-m3/button"]?.group)
      .isEqualTo("Actions")
  }

  @Test
  fun `a shelf of unannotated components reports every unclaimed canvas`() {
    // The placeholder diagnostic must fire for an all-unannotated catalog, the very case it's for.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component("Card", catalogId = "Containment/Card", group = "Containment"),
          component("Chip", catalogId = "Actions/Chip", group = "Actions"),
        ),
        cover,
        policy(),
      )!!

    assertThat(
        generated.diagnostics
          .filter { it.code == UiBuilderCatalogs.Diagnostics.CANVAS_UNCLAIMED }
          .map { it.subject }
      )
      .containsExactly("wear-m3/card", "wear-m3/chip")
    // Unannotated components must be in the map, or consumers re-derive ids.
    assertThat(generated.statusSemantics.components.keys)
      .containsExactly("wear-m3/card", "wear-m3/chip")
    assertThat(generated.statusSemantics.components.getValue("wear-m3/card").record)
      .isEqualTo(":catalog/androidx.wear.compose.material3.CardKt.Card")
    assertThat(generated.statusSemantics.components.getValue("wear-m3/card").propertyCapabilities)
      .isNull()
  }

  @Test
  fun `the menu group comes from the sticker that declared the policy`() {
    // The group comes from the declaring sticker, like the id and `catalogId`.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "Button",
            catalogIds = listOf("Buttons/Filled", "Buttons/Tonal"),
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.declaredForCatalogId = "Buttons/Tonal"
                  b.canvas = "p"
                }
                .build(),
            bindings =
              listOf(
                ComponentBinding.Builder(previewId = "FilledPreview")
                  .also { b ->
                    b.componentId = "Buttons/Filled"
                    b.group = "Actions"
                  }
                  .build(),
                ComponentBinding.Builder(previewId = "TonalPreview")
                  .also { b ->
                    b.componentId = "Buttons/Tonal"
                    b.group = "Selection"
                  }
                  .build(),
              ),
          )
        ),
        cover,
        policy(),
      )!!

    assertThat(generated.statusSemantics.componentMenu.components["wear-m3/button"]?.group)
      .isEqualTo("Selection")
  }

  @Test
  fun `a callback taking a different type than the state it hoists is reported`() {
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "CheckboxButton",
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.canvas = "p"
                  b.stateCallbacks =
                    listOf(
                      BuilderPair.Builder(key = "onCheckedChange", value = "checked:boolean")
                        .build()
                    )
                }
                .build(),
            parameters =
              listOf(
                parameter("checked", type = "kotlin.Boolean"),
                parameter("onCheckedChange", type = "(kotlin.String) -> kotlin.Unit"),
              ),
          )
        ),
        cover,
        policy(),
      )!!

    val mismatch =
      generated.diagnostics.single {
        it.code == UiBuilderCatalogs.Diagnostics.STATE_CALLBACK_TYPE_MISMATCH
      }
    assertThat(mismatch.message).contains("kotlin.String")
    assertThat(mismatch.message).contains("kotlin.Boolean")
  }

  @Test
  fun `a callback agreeing with its state is not reported, and one this cannot read is not guessed`() {
    // Renderings this reader can't settle (two arguments, a receiver, nested function types) stay
    // silent.
    val agreeing =
      UiBuilderCatalogs.generate(
        record(
          component(
            "Switch",
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.canvas = "p"
                  b.stateCallbacks =
                    listOf(
                      BuilderPair.Builder(key = "onCheckedChange", value = "checked:boolean")
                        .build()
                    )
                }
                .build(),
            parameters =
              listOf(
                parameter("checked", type = "kotlin.Boolean"),
                parameter("onCheckedChange", type = "(kotlin.Boolean) -> kotlin.Unit"),
              ),
          )
        ),
        cover,
        policy(),
      )!!
    assertThat(agreeing.diagnostics.map { it.code })
      .doesNotContain(UiBuilderCatalogs.Diagnostics.STATE_CALLBACK_TYPE_MISMATCH)

    val unreadable =
      UiBuilderCatalogs.generate(
        record(
          component(
            "Slider",
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.canvas = "p"
                  b.stateCallbacks =
                    listOf(
                      BuilderPair.Builder(key = "onValueChange", value = "value:number").build()
                    )
                }
                .build(),
            parameters =
              listOf(
                parameter("value", type = "kotlin.Float"),
                parameter("onValueChange", type = "(kotlin.Float, kotlin.Int) -> kotlin.Unit"),
              ),
          )
        ),
        cover,
        policy(),
      )!!
    assertThat(unreadable.diagnostics.map { it.code })
      .doesNotContain(UiBuilderCatalogs.Diagnostics.STATE_CALLBACK_TYPE_MISMATCH)
  }

  @Test
  fun `a callback taking a nullable of the state's type is reported`() {
    // Only nullability differs: a nullable `it` can't be assigned to a non-null `var`.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "CheckboxButton",
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.canvas = "p"
                  b.stateCallbacks =
                    listOf(
                      BuilderPair.Builder(key = "onCheckedChange", value = "checked:boolean")
                        .build()
                    )
                }
                .build(),
            parameters =
              listOf(
                parameter("checked", type = "kotlin.Boolean"),
                parameter("onCheckedChange", type = "(kotlin.Boolean?) -> kotlin.Unit"),
              ),
          )
        ),
        cover,
        policy(),
      )!!

    assertThat(generated.diagnostics.map { it.code })
      .contains(UiBuilderCatalogs.Diagnostics.STATE_CALLBACK_TYPE_MISMATCH)
  }

  @Test
  fun `a non-null callback over nullable state is not reported`() {
    // Non-null into nullable compiles; reporting it would be a false positive.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "CheckboxButton",
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.canvas = "p"
                  b.stateCallbacks =
                    listOf(
                      BuilderPair.Builder(key = "onCheckedChange", value = "checked:boolean")
                        .build()
                    )
                }
                .build(),
            parameters =
              listOf(
                parameter("checked", type = "kotlin.Boolean?"),
                parameter("onCheckedChange", type = "(kotlin.Boolean) -> kotlin.Unit"),
              ),
          )
        ),
        cover,
        policy(),
      )!!

    assertThat(generated.diagnostics.map { it.code })
      .doesNotContain(UiBuilderCatalogs.Diagnostics.STATE_CALLBACK_TYPE_MISMATCH)
  }

  @Test
  fun `a key named twice in a list that becomes a map is reported`() {
    // `policyFor`'s `associate` silently keeps the last duplicate, so contradictions must be
    // reported.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "CheckboxButton",
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.canvas = "p"
                  b.stateCallbacks =
                    listOf(
                      BuilderPair.Builder(key = "onChange", value = "checked:boolean").build(),
                      BuilderPair.Builder(key = "onChange", value = "value:number").build(),
                    )
                  b.starter =
                    listOf(
                      BuilderPair.Builder(key = "label", value = "A").build(),
                      BuilderPair.Builder(key = "label", value = "B").build(),
                    )
                }
                .build(),
            signatureKnown = false,
          )
        ),
        cover,
        policy(),
      )!!

    val repeated =
      generated.diagnostics.filter {
        it.code == UiBuilderCatalogs.Diagnostics.POLICY_MALFORMED_ENTRY
      }
    // Both lists, not just the callbacks: they are collapsed by the same call.
    assertThat(repeated.map { it.subject })
      .containsAtLeast("wear-m3/checkbox-button.onChange", "wear-m3/checkbox-button.label")
  }

  @Test
  fun `a componentIdPrefix missing its slash is reported`() {
    // The trailing slash is required by the schema and pre-flight, but local `ui` and direct
    // `bundle pack` only run this generator; `m3` would derive `m3button`.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "Button",
            builder = BuilderPolicy.Builder().also { b -> b.canvas = "p" }.build(),
          )
        ),
        cover,
        policy().newBuilder().also { b -> b.componentIdPrefix = "m3" }.build(),
      )!!

    val reported =
      generated.diagnostics.single { it.code == UiBuilderCatalogs.Diagnostics.ID_PREFIX_MALFORMED }
    assertThat(reported.message).contains("m3button")
    // Reported, not corrected: the prefix is the identity, so the ids stay as the author wrote
    // them.
    assertThat(generated.statusSemantics.components.keys).containsExactly("m3button")
  }

  @Test
  fun `a well-formed prefix and a derived one are not reported`() {
    // The derived `<catalogId>/` fallback comes from the cover sheet, so don't point the diagnostic
    // at an unset field.
    for (policyFile in
      listOf(
        policy().newBuilder().also { b -> b.componentIdPrefix = "m3/" }.build(),
        policy(),
      )) {
      val generated =
        UiBuilderCatalogs.generate(
          record(
            component(
              "Button",
              builder = BuilderPolicy.Builder().also { b -> b.canvas = "p" }.build(),
            )
          ),
          cover,
          policyFile,
        )!!
      assertThat(generated.diagnostics.map { it.code })
        .doesNotContain(UiBuilderCatalogs.Diagnostics.ID_PREFIX_MALFORMED)
    }
  }

  @Test
  fun `an unannotated component is shelved under the alias its id came from`() {
    // The group must follow the same sorted-first catalog id as `builderIdFor`, not preview-id
    // order.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "Button",
            catalogIds = listOf("Buttons/Filled", "Buttons/Tonal"),
            bindings =
              listOf(
                ComponentBinding.Builder(previewId = "ATonalPreview")
                  .also { b ->
                    b.componentId = "Buttons/Tonal"
                    b.group = "Selection"
                  }
                  .build(),
                ComponentBinding.Builder(previewId = "ZFilledPreview")
                  .also { b ->
                    b.componentId = "Buttons/Filled"
                    b.group = "Actions"
                  }
                  .build(),
              ),
          )
        ),
        cover,
        policy(),
      )!!

    // The shelf comes from the alias the id was attributed to (sorted-first), not the first
    // binding.
    assertThat(generated.statusSemantics.componentMenu.components["wear-m3/button"]?.group)
      .isEqualTo("Actions")
  }

  @Test
  fun `a platform that is a label rather than a word is reported`() {
    // Equality is compatibility, so `Wear` matches no consumer expecting `wear`; checked here since
    // local runs skip the pre-flight.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "Button",
            builder = BuilderPolicy.Builder().also { b -> b.canvas = "p" }.build(),
          )
        ),
        cover,
        policy().newBuilder().also { b -> b.platform = "Wear" }.build(),
      )!!

    assertThat(generated.diagnostics.map { it.code })
      .contains(UiBuilderCatalogs.Diagnostics.PLATFORM_MALFORMED)
    // A lower-case word stays silent, including a hyphenated one.
    for (word in listOf("wear", "remote-compose")) {
      val fine =
        UiBuilderCatalogs.generate(
          record(
            component(
              "Button",
              builder = BuilderPolicy.Builder().also { b -> b.canvas = "p" }.build(),
            )
          ),
          cover,
          policy().newBuilder().also { b -> b.platform = word }.build(),
        )!!
      assertThat(fine.diagnostics.map { it.code })
        .doesNotContain(UiBuilderCatalogs.Diagnostics.PLATFORM_MALFORMED)
    }
  }

  @Test
  fun `a callback that cannot carry the new value is reported`() {
    // The export writes `{ checked = it }`, needing exactly one argument; zero or two get their own
    // message.
    for (type in listOf("() -> kotlin.Unit", "(kotlin.Boolean, kotlin.Int) -> kotlin.Unit")) {
      val generated =
        UiBuilderCatalogs.generate(
          record(
            component(
              "CheckboxButton",
              builder =
                BuilderPolicy.Builder()
                  .also { b ->
                    b.canvas = "p"
                    b.stateCallbacks =
                      listOf(
                        BuilderPair.Builder(key = "onClick", value = "checked:boolean").build()
                      )
                  }
                  .build(),
              parameters =
                listOf(
                  parameter("checked", type = "kotlin.Boolean"),
                  parameter("onClick", type = type),
                ),
            )
          ),
          cover,
          policy(),
        )!!

      assertThat(generated.diagnostics.map { it.code })
        .contains(UiBuilderCatalogs.Diagnostics.STATE_CALLBACK_ARITY)
    }

    // And the one-argument shape stays silent, which is the half that keeps this honest.
    val correct =
      UiBuilderCatalogs.generate(
        record(
          component(
            "CheckboxButton",
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.canvas = "p"
                  b.stateCallbacks =
                    listOf(
                      BuilderPair.Builder(key = "onCheckedChange", value = "checked:boolean")
                        .build()
                    )
                }
                .build(),
            parameters =
              listOf(
                parameter("checked", type = "kotlin.Boolean"),
                parameter("onCheckedChange", type = "(kotlin.Boolean) -> kotlin.Unit"),
              ),
          )
        ),
        cover,
        policy(),
      )!!
    assertThat(correct.diagnostics.map { it.code })
      .doesNotContain(UiBuilderCatalogs.Diagnostics.STATE_CALLBACK_ARITY)
  }

  private fun parameter(name: String, type: String = "kotlin.Boolean") =
    TargetParameter.Builder(name = name, type = type).also { b -> b.hasDefault = true }.build()

  @Test
  fun `a templates entry is a path or an object, and a bare path stays a bare path`() {
    val json = Json { ignoreUnknownKeys = true }
    val text =
      """
      {"schema": "$UI_BUILDER_POLICY_SCHEMA", "platform": "wear",
       "newDesign": {"label": "Wear app", "order": 2},
       "templates": [
         "ui-builder/designs/wear-screen.json",
         {"path": "ui-builder/designs/wear-list.json", "label": "Activity list",
          "supportingText": "Six title cards.", "default": true, "order": 2}
       ]}
      """
    val policy = json.decodeFromString<UiBuilderPolicyFile>(text)
    assertThat(policy.templates.map { it.path })
      .containsExactly("ui-builder/designs/wear-screen.json", "ui-builder/designs/wear-list.json")
      .inOrder()
    assertThat(policy.templates[0].describesItself).isFalse()
    assertThat(policy.templates[1].resolvedId).isEqualTo("wear-list")
    assertThat(policy.newDesign)
      .isEqualTo(
        UiBuilderNewDesign.Builder()
          .also { b ->
            b.label = "Wear app"
            b.order = 2
          }
          .build()
      )

    val written =
      Json.parseToJsonElement(json.encodeToString(UiBuilderPolicyFile.serializer(), policy))
        .jsonObject
        .getValue("templates")
        .let { it as kotlinx.serialization.json.JsonArray }
    assertThat(written[0]).isEqualTo(JsonPrimitive("ui-builder/designs/wear-screen.json"))
    assertThat(written[1].jsonObject.getValue("label").jsonPrimitive.content)
      .isEqualTo("Activity list")
  }

  @Test
  fun `a policy with bare paths and no chip publishes no chooser block, and paths as before`() {
    val policy =
      policy()
        .newBuilder()
        .also { b ->
          b.templates =
            listOf(
              UiBuilderTemplateEntry.Builder(path = "ui-builder/designs/wear-list.json").build()
            )
        }
        .build()
    val semantics =
      checkNotNull(UiBuilderCatalogs.generate(record(), cover, policy)).statusSemantics

    assertThat(semantics.templates).containsExactly("ui-builder/designs/wear-list.json")
    assertThat(semantics.newDesign).isNull()
  }

  @Test
  fun `the chooser copy a policy authors is published beside the paths, by template id`() {
    val policy =
      policy()
        .newBuilder()
        .also { b3 ->
          b3.newDesign =
            UiBuilderNewDesign.Builder()
              .also { b ->
                b.label = "Wear app"
                b.order = 2
              }
              .build()
          b3.templates =
            listOf(
              UiBuilderTemplateEntry.Builder(path = "ui-builder/designs/wear-screen.json").build(),
              UiBuilderTemplateEntry.Builder(path = "ui-builder/designs/wear-list.json")
                .also { b ->
                  b.label = "Activity list"
                  b.default = true
                }
                .build(),
            )
        }
        .build()
    val semantics =
      checkNotNull(UiBuilderCatalogs.generate(record(), cover, policy)).statusSemantics

    assertThat(semantics.templates)
      .containsExactly("ui-builder/designs/wear-screen.json", "ui-builder/designs/wear-list.json")
      .inOrder()
    val chooser = checkNotNull(semantics.newDesign)
    assertThat(chooser.label).isEqualTo("Wear app")
    assertThat(chooser.templates.map { it.id })
      .containsExactly("wear-screen", "wear-list")
      .inOrder()
    assertThat(chooser.templates[1].label).isEqualTo("Activity list")
    assertThat(chooser.templates[1].default).isTrue()
  }
}
