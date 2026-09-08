package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The generator that turns a discovered record, a cover sheet and an authored policy into the
 * `ui-builder.json` a catalog repository publishes.
 *
 * The interesting half is the diagnostics. A builder catalog is data now, so the two questions
 * somebody asks of a shelf — "why is this component not on it" and "why is all of it placeholders"
 * — have to be answerable from the published artifact by a person who was not watching the build.
 */
class UiBuilderCatalogsTest {

  private val cover = UiBuilderCatalogs.CoverSheet(system = "wear-m3", title = "Wear M3")

  private fun policy(
    platform: String = "wear",
    platformLabel: String? = null,
    builtins: Map<String, UiBuilderBuiltin> = emptyMap(),
    code: UiBuilderCode? = null,
    componentIdPrefix: String? = null,
  ) =
    UiBuilderPolicyFile(
      schema = UI_BUILDER_POLICY_SCHEMA,
      platform = platform,
      platformLabel = platformLabel,
      builtins = builtins,
      code = code,
      componentIdPrefix = componentIdPrefix,
    )

  private fun record(vararg components: ComponentRecord) =
    ComponentRecordFile(module = ":catalog", variant = "debug", components = components.toList())

  private fun component(
    name: String,
    catalogId: String? = null,
    builder: BuilderPolicy? = null,
    parameters: List<TargetParameter> = emptyList(),
    signatureKnown: Boolean = true,
  ) =
    ComponentRecord(
      canonicalId = ":catalog/androidx.wear.compose.material3.${name}Kt.$name",
      componentIds = listOfNotNull(catalogId),
      symbol =
        ComponentSymbol(
          jvmOwner = "androidx.wear.compose.material3.${name}Kt",
          callable = "androidx.wear.compose.material3.$name",
          name = name,
          origin = ComponentOrigin.LIBRARY,
        ),
      parameters = parameters,
      slots = ComponentRecords.slotsOf(parameters),
      signatureKnown = signatureKnown,
      builder = builder,
    )

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
            builder = BuilderPolicy(canvas = "placeholder"),
          ),
          component("EdgeButton", builder = BuilderPolicy(id = "wear-m3/edge", canvas = "p")),
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
    // m3-catalog's components are `m3/button`, not `m3-catalog/button`: the catalog is named for
    // the repository and the components for the library, and no rename reconciles that without
    // invalidating every saved design. So the prefix is declared rather than derived from the id.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component("Button", catalogId = "Buttons/Filled", builder = BuilderPolicy(canvas = "p"))
        ),
        UiBuilderCatalogs.CoverSheet(system = "m3-catalog", title = "Material 3"),
        policy(platform = "mobile", componentIdPrefix = "m3/"),
      )!!

    assertThat(generated.catalog.id).isEqualTo("m3-catalog")
    assertThat(generated.statusSemantics.components.keys).containsExactly("m3/filled")
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
          component("Button", builder = BuilderPolicy(id = "wear-m3/button", canvas = "p")),
          component("FilledButton", builder = BuilderPolicy(id = "wear-m3/button", canvas = "p")),
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
        record(component("Card", builder = BuilderPolicy())),
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
  fun `a state callback naming a parameter the component does not take is reported`() {
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "CheckboxButton",
            parameters = listOf(parameter("checked")),
            builder =
              BuilderPolicy(
                canvas = "placeholder",
                stateCallbacks =
                  listOf(
                    BuilderPair("onCheckedChange", "checked:boolean"),
                    BuilderPair("onSelectedChange", "selected:boolean"),
                  ),
              ),
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
              BuilderPolicy(
                canvas = "placeholder",
                stateCallbacks = listOf(BuilderPair("onCheckedChange", "checked:boolean")),
                slots = listOf(BuilderPair("content", "Content")),
              ),
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
        record(component("Card", builder = BuilderPolicy(id = "wear-m3/card", canvas = "p"))),
        cover,
        policy(
          builtins =
            mapOf(
              "wear-m3/screen-scaffold" to UiBuilderBuiltin(role = "screen-root"),
              "wear-m3/mystery" to UiBuilderBuiltin(role = "carousel"),
              "wear-m3/card" to UiBuilderBuiltin(role = "list"),
            )
        ),
      )!!

    val codes = generated.diagnostics.map { it.code to it.subject }
    assertThat(codes)
      .contains(UiBuilderCatalogs.Diagnostics.BUILTIN_ROLE_UNKNOWN to "wear-m3/mystery")
    // A builtin is for a component with no call site. One that has a call site belongs in the
    // record, with its policy on the sticker — otherwise this file is the second inventory the
    // whole contract exists to avoid.
    assertThat(codes)
      .contains(UiBuilderCatalogs.Diagnostics.BUILTIN_SHADOWS_RECORD to "wear-m3/card")
    assertThat(codes.map { it.first })
      .doesNotContain(
        UiBuilderCatalogs.Diagnostics.BUILTIN_ROLE_UNKNOWN to "wear-m3/screen-scaffold"
      )
  }

  @Test
  fun `templates and the strategy have to agree`() {
    val declaredButUnused =
      UiBuilderCatalogs.generate(
        record(),
        cover,
        policy(code = UiBuilderCode(strategy = "record", templates = mapOf("list" to "…"))),
      )!!
    assertThat(declaredButUnused.diagnostics.map { it.code })
      .contains(UiBuilderCatalogs.Diagnostics.TEMPLATES_WITHOUT_STRATEGY)

    val claimedButAbsent =
      UiBuilderCatalogs.generate(
        record(),
        cover,
        policy(code = UiBuilderCode(strategy = "templates")),
      )!!
    assertThat(claimedButAbsent.diagnostics.map { it.code })
      .contains(UiBuilderCatalogs.Diagnostics.STRATEGY_WITHOUT_TEMPLATES)

    val unknownRole =
      UiBuilderCatalogs.generate(
        record(),
        cover,
        policy(
          code =
            UiBuilderCode(
              strategy = "templates",
              templates = mapOf("screen-root" to "…", "previews" to "…", "carousel" to "…"),
            )
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
            UiBuilderCode(
              strategy = "templates",
              templates =
                mapOf("screen-root" to "AppScaffold {\n  \${content}\n}", "list" to "a \${ b"),
            )
        ),
      )!!

    val reported =
      generated.diagnostics.single { it.code == UiBuilderCatalogs.Diagnostics.TEMPLATE_MALFORMED }
    assertThat(reported.subject).isEqualTo("list")
    assertThat(reported.message).contains("unterminated")
  }

  @Test
  fun `an ambiguous subject and a malformed entry are both reported by name`() {
    // Both are things discovery deliberately does not fail on — it binds a guess, it drops an
    // unreadable entry — and both are only acceptable because they are reported here, in the
    // published file, to somebody who was not watching the build.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "Button",
            builder =
              BuilderPolicy(
                id = "wear-m3/button",
                canvas = "placeholder",
                ambiguousWith = listOf(":catalog/…TextKt.Text"),
                malformed = listOf("starter: noSeparator"),
              ),
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
    // A `onChekedChange` typo passes a state-only check, publishes the misspelled key, and the
    // export then has nothing to hoist against — a component that draws, compiles and does not
    // tick, with no diagnostic anywhere.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "CheckboxButton",
            parameters = listOf(parameter("checked"), parameter("onCheckedChange")),
            builder =
              BuilderPolicy(
                canvas = "placeholder",
                stateCallbacks = listOf(BuilderPair("onChekedChange", "checked:boolean")),
              ),
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
  fun `a policy that bound to nothing is reported by the preview that declared it`() {
    // The generator reads the record, not the manifest, so an orphan has to travel in the file or
    // it cannot be reported anywhere a person will look.
    val generated =
      UiBuilderCatalogs.generate(
        record(component("Card", builder = BuilderPolicy(id = "wear-m3/card", canvas = "p")))
          .copy(
            builderOrphans =
              listOf(
                BuilderOrphan(
                  previewId = "p1",
                  component = "CheckboxButton",
                  candidates = listOf(":catalog/…CardKt.Card"),
                )
              )
          ),
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
    // One callable is routinely published under several catalog ids — `Button/Filled` and
    // `Button/Tonal` over one `Button` — and `componentIds` is the sorted union across previews.
    // Taking its first would give a policy declared on Tonal the identity `…/filled`, which is the
    // string every saved design then stores.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component("Button", catalogId = "Buttons/Filled", builder = BuilderPolicy(canvas = "p"))
            .let { it.copy(componentIds = listOf("Buttons/Filled", "Buttons/Tonal")) }
            .let { it.copy(builder = it.builder!!.copy(declaredForCatalogId = "Buttons/Tonal")) }
        ),
        cover,
        policy(),
      )!!

    assertThat(generated.statusSemantics.components.keys).containsExactly("wear-m3/tonal")
  }

  @Test
  fun `a builtin colliding with an unannotated record component is reported`() {
    // An unannotated component is still shelved under its derived id — the honest default the whole
    // contract rests on — so a builtin sharing that id is two components claiming one saved-design
    // identity, which is exactly what this check is for.
    val generated =
      UiBuilderCatalogs.generate(
        record(component("Card", catalogId = "Containment/Card")),
        cover,
        policy(builtins = mapOf("wear-m3/card" to UiBuilderBuiltin(role = "decoration"))),
      )!!

    assertThat(generated.diagnostics.map { it.code to it.subject })
      .contains(UiBuilderCatalogs.Diagnostics.BUILTIN_SHADOWS_RECORD to "wear-m3/card")
  }

  @Test
  fun `a template hole no role supplies is reported against the role`() {
    // `${'$'}{contnet}` is a perfectly valid NAME, so nothing about the syntax catches it. Only
    // knowing
    // which names the role will have values for does — and without that the refusal arrives at
    // export, weeks from the person who typed it.
    val generated =
      UiBuilderCatalogs.generate(
        record(),
        cover,
        policy(
          code =
            UiBuilderCode(
              strategy = "templates",
              templates = mapOf("screen-root" to "AppScaffold {\n  \${contnet}\n}"),
            )
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
              BuilderPolicy(
                canvas = "placeholder",
                starter = listOf(BuilderPair("label", "Checkbox")),
                stateCallbacks = listOf(BuilderPair("onCheckedChange", "checked:boolean")),
                traits = listOf("Action"),
              ),
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
    // Shaped after wear-m3-catalog's own `ui-builder.policy.json`, and here rather than in that
    // repository because this is where the reader lives. Two things it pins that a
    // model-constructed test cannot:
    //
    //   - `$comment` keys. A policy file is read far more often than written, so it carries prose,
    //     and `ignoreUnknownKeys` has to cover the nested objects too — but NOT inside `builtins`,
    //     whose values are typed, where a comment entry would decode as a builtin with no role.
    //     The convention that keeps both true is a `$comment_<field>` key beside the field.
    //   - `frame`, kept as a raw JsonElement, carrying a geometry block with numbers and prose in
    //     it that this generator deliberately does not parse.
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
            "slots": { "content": { "required": true, "role": "list" } }
          }
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
    assertThat(policy.menu?.groupOrder).containsExactly("Layout", "Navigation", "Actions").inOrder()
    assertThat(policy.code).isNull()

    val generated =
      UiBuilderCatalogs.generate(
        record(component("Card", builder = BuilderPolicy(id = "wear-m3/card", canvas = "p"))),
        cover,
        policy,
      )!!

    // The declared id wins over the cover sheet's `system`, and the frame rides through verbatim.
    assertThat(generated.catalog.id).isEqualTo("wear-m3")
    assertThat(generated.statusSemantics.frame).isEqualTo(policy.frame)
    assertThat(generated.statusSemantics.builtins).isEqualTo(policy.builtins)
    assertThat(generated.diagnostics.map { it.code })
      .containsNoneOf(
        UiBuilderCatalogs.Diagnostics.POLICY_SCHEMA_UNKNOWN,
        UiBuilderCatalogs.Diagnostics.BUILTIN_ROLE_UNKNOWN,
        UiBuilderCatalogs.Diagnostics.BUILTIN_SHADOWS_RECORD,
      )
  }

  private fun parameter(name: String) =
    TargetParameter(name = name, type = "kotlin.Boolean", hasDefault = true)
}
