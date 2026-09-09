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
    ComponentRecord(
      canonicalId = ":catalog/androidx.wear.compose.material3.${name}Kt.$name",
      componentIds = catalogIds ?: listOfNotNull(catalogId),
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
      // One binding carrying the catalog's own resolved group, which is what a real record holds
      // and what the menu is built from for a component that annotates nothing.
      bindings =
        bindings
          ?: listOf(
            ComponentBinding(previewId = "${name}Preview", componentId = catalogId, group = group)
          ),
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
  fun `a builtin slot names a structural role too`() {
    // The slot's role selects a template exactly as the builtin's own role does. It was checked in
    // the JavaScript pre-flight and nowhere else — and that pre-flight runs only in the two
    // workflow
    // lanes, so local discovery and a direct `bundle pack`, the consumers this contract exists to
    // make first-class, published a misspelled role with nothing said about it.
    val generated =
      UiBuilderCatalogs.generate(
        record(component("Card", builder = BuilderPolicy(id = "wear-m3/card", canvas = "p"))),
        cover,
        policy(
          builtins =
            mapOf(
              "wear-m3/screen-scaffold" to
                UiBuilderBuiltin(
                  role = "screen-root",
                  slots =
                    mapOf(
                      "content" to Json.parseToJsonElement("{\"role\": \"lisst\"}"),
                      "footer" to Json.parseToJsonElement("{\"role\": \"list\"}"),
                      // Not a role at all, and not this generator's to diagnose: the slot's shape
                      // belongs to the loader, so an unreadable one is left alone rather than
                      // turned into a second opinion about somebody else's contract.
                      "header" to Json.parseToJsonElement("\"just a string\""),
                    ),
                )
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
  fun `two unannotated components deriving one id are reported`() {
    // The consumer shelves an unannotated component by deriving its id from `componentIdPrefix`,
    // exactly as this does — so a collision between two of them is two records claiming one
    // saved-design identity. Excluding them from the check made it blind to most of the shelf.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component("Card", catalogId = "Containment/Card"),
          component("Card2", catalogId = "Layout/Card"),
        ),
        cover,
        policy(),
      )!!

    val collision =
      generated.diagnostics.single { it.code == UiBuilderCatalogs.Diagnostics.ID_COLLISION }
    assertThat(collision.subject).isEqualTo("wear-m3/card")
    // Neither is annotated, so neither has a policy entry — and the collision is still reported.
    assertThat(generated.statusSemantics.components).isEmpty()
  }

  @Test
  fun `an unannotated component colliding with an explicit id is reported`() {
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component("Card"),
          component("Tile", builder = BuilderPolicy(id = "wear-m3/card", canvas = "p")),
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
              BuilderPolicy(
                canvas = "placeholder",
                variantProperty = "styel",
                variants = listOf(BuilderPair("Filled", "ButtonStyle.Filled")),
              ),
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
    // `onCheckedChange=checked` parses to a valid state name and no type at all, and `checked:bool`
    // to a type nothing knows. Both passed while only the part before the colon was looked at, and
    // the export prints the hoisted remember's initial value FROM that type — so the component
    // published a hoist nothing could complete.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "CheckboxButton",
            parameters = listOf(parameter("checked"), parameter("onCheckedChange")),
            builder =
              BuilderPolicy(
                canvas = "placeholder",
                stateCallbacks =
                  listOf(
                    BuilderPair("onCheckedChange", "checked"),
                    BuilderPair("onCheckedChange2", "checked:bool"),
                    // A colon and a supported type, and no state at all: it passed the malformed
                    // check because the colon was there, and the unknown-state check because the
                    // empty name was skipped as "nothing declared".
                    BuilderPair("onCheckedChange3", ":boolean"),
                  ),
              ),
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
              BuilderPolicy(
                canvas = "placeholder",
                variants = listOf(BuilderPair("Filled", "ButtonStyle.Filled")),
              ),
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
              BuilderPolicy(
                canvas = "placeholder",
                stateCallbacks =
                  listOf(
                    BuilderPair("label", "checked:boolean"),
                    // The correct shape, which must NOT be reported.
                    BuilderPair("onCheckedChange", "checked:boolean"),
                  ),
              ),
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
              BuilderPolicy(
                canvas = "placeholder",
                stateCallbacks = listOf(BuilderPair("onCheckedChange", "checked:bool")),
              ),
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
    // `checked:string` names a supported type and a real parameter, and the callback is
    // function-typed — every other check passes. The export would initialise a String and thread it
    // into a Boolean.
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
              BuilderPolicy(
                canvas = "placeholder",
                stateCallbacks = listOf(BuilderPair("onCheckedChange", "checked:string")),
              ),
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
                // Nullable is the same classifier, and so is a package qualifier: a record holds
                // `kotlin.Boolean`, not `Boolean`, and comparing the qualified string against a
                // table keyed on simple names made this check fire on every correct policy. Written
                // the way a record actually holds it, so the table and the record cannot drift
                // apart again behind a test that agrees with neither.
                parameter("checked", type = "kotlin.Boolean?"),
                parameter("onCheckedChange", type = "(Boolean) -> Unit"),
              ),
            builder =
              BuilderPolicy(
                canvas = "placeholder",
                stateCallbacks = listOf(BuilderPair("onCheckedChange", "checked:boolean")),
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
  fun `an unannotated component keeps its catalog group on the shelf`() {
    // "A catalog that annotates nothing still publishes every component, grouped by its
    // @CatalogGroup" was a claim with nothing behind it: only an explicit @BuilderComponent(group)
    // produced a menu entry, so most of the default shelf had no group a consumer could recover.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component("Card", catalogId = "Containment/Card", group = "Containment"),
          component(
            "Button",
            catalogId = "Actions/Button",
            group = "Actions",
            builder = BuilderPolicy(canvas = "p", group = "Overridden"),
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
              BuilderPolicy(canvas = "placeholder", starter = listOf(BuilderPair("lable", "Hi"))),
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
    // The only way a consumer can name a component this file says nothing about. An unannotated
    // record component is deliberately absent from `components` and still belongs on the shelf, so
    // without the prefix a consumer holding m3-catalog's record has to guess between `m3/card` and
    // `m3-catalog/card` — and guessing wrong changes the identity every saved design stores.
    val prefixed =
      UiBuilderCatalogs.generate(
        record(component("Card")),
        cover,
        policy().copy(componentIdPrefix = "m3/"),
      )!!
    assertThat(prefixed.statusSemantics.componentIdPrefix).isEqualTo("m3/")

    // Defaulted from the catalog id when the policy declares none, so the field is always usable.
    val defaulted = UiBuilderCatalogs.generate(record(component("Card")), cover, policy())!!
    assertThat(defaulted.statusSemantics.componentIdPrefix).isEqualTo("wear-m3/")
  }

  @Test
  fun `a published entry names the catalog alias of the sticker that declared it`() {
    // The entry must not contradict its own builder id: keyed `…/tonal` while linking a consumer to
    // `Buttons/Filled` would land them on a different sticker than the one whose author wrote this.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "Button",
            catalogIds = listOf("Buttons/Filled", "Buttons/Tonal"),
            builder = BuilderPolicy(canvas = "placeholder", declaredForCatalogId = "Buttons/Tonal"),
          )
        ),
        cover,
        policy(),
      )!!

    val entry = generated.statusSemantics.components.values.single()
    assertThat(entry.catalogId).isEqualTo("Buttons/Tonal")
    assertThat(generated.statusSemantics.components.keys.single()).endsWith("/tonal")
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

  @Test
  fun `an unannotated first claimant keeps the id it won`() {
    // The sweep says the first claimant wins and the menu follows it. The policy map consulted only
    // itself — a map no unannotated component ever enters — so the later annotated component
    // published its policy under the contested id while the diagnostic said it had lost.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component("Button", catalogId = "Buttons/Button", group = "Actions"),
          component(
            "Other",
            builder = BuilderPolicy(id = "wear-m3/button", canvas = "frame/round-screen"),
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
    // The loser publishes nothing under the id it lost, and the winner keeps the shelf entry.
    assertThat(generated.statusSemantics.components).doesNotContainKey("wear-m3/button")
    assertThat(generated.statusSemantics.componentMenu.components["wear-m3/button"]?.group)
      .isEqualTo("Actions")
  }

  @Test
  fun `a shelf of unannotated components reports every unclaimed canvas`() {
    // The diagnostic's own message says it exists so a shelf drawn entirely in placeholders is
    // visible rather than mysterious — and that shelf is the all-unannotated catalog, which was the
    // one case it could not fire in, because only annotated components were diagnosed at all.
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
    // Still no policy entry: the diagnostics are about the component, the map is about the policy.
    assertThat(generated.statusSemantics.components).isEmpty()
  }

  @Test
  fun `the menu group comes from the sticker that declared the policy`() {
    // The id and `catalogId` already came from the declaring sticker. Taking the group from the
    // first binding shelved a component keyed `…/tonal` under Filled's group, so the entry
    // disagreed with its own identity.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "Button",
            catalogIds = listOf("Buttons/Filled", "Buttons/Tonal"),
            builder = BuilderPolicy(declaredForCatalogId = "Buttons/Tonal", canvas = "p"),
            bindings =
              listOf(
                ComponentBinding(
                  previewId = "FilledPreview",
                  componentId = "Buttons/Filled",
                  group = "Actions",
                ),
                ComponentBinding(
                  previewId = "TonalPreview",
                  componentId = "Buttons/Tonal",
                  group = "Selection",
                ),
              ),
          )
        ),
        cover,
        policy(),
      )!!

    assertThat(generated.statusSemantics.componentMenu.components["wear-m3/tonal"]?.group)
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
              BuilderPolicy(
                canvas = "p",
                stateCallbacks = listOf(BuilderPair("onCheckedChange", "checked:boolean")),
              ),
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
    // The second half matters as much as the first: a rendering this reader cannot settle — two
    // arguments, a receiver, a nested function type — must stay silent rather than report a
    // mismatch against a component that is correct.
    val agreeing =
      UiBuilderCatalogs.generate(
        record(
          component(
            "Switch",
            builder =
              BuilderPolicy(
                canvas = "p",
                stateCallbacks = listOf(BuilderPair("onCheckedChange", "checked:boolean")),
              ),
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
              BuilderPolicy(
                canvas = "p",
                stateCallbacks = listOf(BuilderPair("onValueChange", "value:number")),
              ),
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
    // The bare classifiers agree — both are Boolean — so only nullability separates these. The
    // export writes the callback's argument back into the hoisted state, and a nullable `it` into a
    // non-null `var` does not compile.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "CheckboxButton",
            builder =
              BuilderPolicy(
                canvas = "p",
                stateCallbacks = listOf(BuilderPair("onCheckedChange", "checked:boolean")),
              ),
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
    // The other direction is ordinary and correct: `it` is a Boolean, the state is a `Boolean?`
    // var, and the assignment compiles. Reporting it would be the false positive this check has
    // already produced once.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "CheckboxButton",
            builder =
              BuilderPolicy(
                canvas = "p",
                stateCallbacks = listOf(BuilderPair("onCheckedChange", "checked:boolean")),
              ),
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
    // `policyFor` collapses these with `associate`, which keeps the last silently, so the entry
    // that wins is whichever was written second and the contradiction appears nowhere. Every check
    // above passes because every check above asks about one entry.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "CheckboxButton",
            builder =
              BuilderPolicy(
                canvas = "p",
                stateCallbacks =
                  listOf(
                    BuilderPair("onChange", "checked:boolean"),
                    BuilderPair("onChange", "value:number"),
                  ),
                starter = listOf(BuilderPair("label", "A"), BuilderPair("label", "B")),
              ),
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
    // The schema and the JS pre-flight both require the trailing slash; this generator did not, and
    // it is the one every consumer runs — a local `compose-preview-server ui` and a direct
    // `bundle pack` never see the workflow's pre-flight. `m3` derives ids like `m3button`, which is
    // the string every saved design stores.
    val generated =
      UiBuilderCatalogs.generate(
        record(component("Button", builder = BuilderPolicy(canvas = "p"))),
        cover,
        policy().copy(componentIdPrefix = "m3"),
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
    // The derived fallback is `<catalogId>/`, whose shape follows from the cover sheet rather than
    // from anything anybody wrote — pointing a diagnostic at a field the author never set would
    // send them looking for something that is not in their policy.
    for (policyFile in listOf(policy().copy(componentIdPrefix = "m3/"), policy())) {
      val generated =
        UiBuilderCatalogs.generate(
          record(component("Button", builder = BuilderPolicy(canvas = "p"))),
          cover,
          policyFile,
        )!!
      assertThat(generated.diagnostics.map { it.code })
        .doesNotContain(UiBuilderCatalogs.Diagnostics.ID_PREFIX_MALFORMED)
    }
  }

  @Test
  fun `an unannotated component is shelved under the alias its id came from`() {
    // `builderIdFor` derives an unannotated component's id from the first of the SORTED
    // `componentIds`; the group fallback took the first BINDING's, which is preview-id order. When
    // the two orders differ — as here, `ZFilledPreview` sorting after `ATonalPreview` — the entry
    // was keyed `…/filled` and shelved under Tonal's group. The annotated branch was fixed for this
    // one round earlier; this is the same defect in the branch beside it.
    val generated =
      UiBuilderCatalogs.generate(
        record(
          component(
            "Button",
            catalogIds = listOf("Buttons/Filled", "Buttons/Tonal"),
            bindings =
              listOf(
                ComponentBinding(
                  previewId = "ATonalPreview",
                  componentId = "Buttons/Tonal",
                  group = "Selection",
                ),
                ComponentBinding(
                  previewId = "ZFilledPreview",
                  componentId = "Buttons/Filled",
                  group = "Actions",
                ),
              ),
          )
        ),
        cover,
        policy(),
      )!!

    // The id comes from `Buttons/Filled`, so the shelf has to be Filled's.
    assertThat(generated.statusSemantics.componentMenu.components["wear-m3/filled"]?.group)
      .isEqualTo("Actions")
  }

  @Test
  fun `a platform that is a label rather than a word is reported`() {
    // Equality IS compatibility, so `Wear` joins no consumer expecting `wear`. The schema and the
    // pre-flight both say so, and the pre-flight does not run for a local `ui` or a direct
    // `bundle pack` — the same gap the componentIdPrefix check was added for.
    val generated =
      UiBuilderCatalogs.generate(
        record(component("Button", builder = BuilderPolicy(canvas = "p"))),
        cover,
        policy().copy(platform = "Wear"),
      )!!

    assertThat(generated.diagnostics.map { it.code })
      .contains(UiBuilderCatalogs.Diagnostics.PLATFORM_MALFORMED)
    // A lower-case word stays silent, including a hyphenated one.
    for (word in listOf("wear", "remote-compose")) {
      val fine =
        UiBuilderCatalogs.generate(
          record(component("Button", builder = BuilderPolicy(canvas = "p"))),
          cover,
          policy().copy(platform = word),
        )!!
      assertThat(fine.diagnostics.map { it.code })
        .doesNotContain(UiBuilderCatalogs.Diagnostics.PLATFORM_MALFORMED)
    }
  }

  @Test
  fun `a callback that cannot carry the new value is reported`() {
    // The export writes `{ checked = it }`, which needs exactly one argument. Zero and two were
    // both folded into "this rendering is unreadable, say nothing" — an argument for writing a
    // different message, not for staying quiet.
    for (type in listOf("() -> kotlin.Unit", "(kotlin.Boolean, kotlin.Int) -> kotlin.Unit")) {
      val generated =
        UiBuilderCatalogs.generate(
          record(
            component(
              "CheckboxButton",
              builder =
                BuilderPolicy(
                  canvas = "p",
                  stateCallbacks = listOf(BuilderPair("onClick", "checked:boolean")),
                ),
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
              BuilderPolicy(
                canvas = "p",
                stateCallbacks = listOf(BuilderPair("onCheckedChange", "checked:boolean")),
              ),
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
    TargetParameter(name = name, type = type, hasDefault = true)
}
