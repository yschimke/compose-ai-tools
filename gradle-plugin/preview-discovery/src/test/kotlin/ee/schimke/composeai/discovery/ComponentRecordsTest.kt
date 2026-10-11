package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ComponentRecordsTest {

  private fun target(
    className: String,
    functionName: String,
    parameters: List<TargetParameter> = emptyList(),
    sourceFile: String? = null,
    jvmName: String? = null,
    descriptor: String? = null,
    signatureKnown: Boolean = false,
  ) =
    PreviewTarget(
      className = className,
      functionName = functionName,
      jvmName = jvmName,
      descriptor = descriptor,
      sourceFile = sourceFile,
      confidence = TargetConfidence.HIGH,
      parameters = parameters,
      signatureKnown = signatureKnown,
    )

  private fun preview(
    id: String,
    componentTargets: List<PreviewTarget> = emptyList(),
    targets: List<PreviewTarget> = emptyList(),
    builder: BuilderPolicy? = null,
  ) =
    PreviewInfo(
      id = id,
      functionName = id.substringAfterLast('.'),
      className = "com.example.PreviewsKt",
      targets = targets,
      componentTargets = componentTargets,
      builder = builder,
    )

  private fun manifest(vararg previews: PreviewInfo) =
    PreviewManifest(module = "app", variant = "debug", previews = previews.toList())

  @Test
  fun `a library target and a project target are both recorded, and told apart by origin`() {
    val file =
      ComponentRecords.from(
        manifest(
          preview(
            "p1",
            componentTargets = listOf(target("androidx.compose.material3.CardKt", "Card")),
          ),
          preview(
            "p2",
            targets =
              listOf(target("com.example.HomeKt", "HomeScreen", sourceFile = "src/Home.kt")),
          ),
        )
      )

    assertThat(file.components.map { it.symbol.origin })
      .containsExactly(ComponentOrigin.LIBRARY, ComponentOrigin.PROJECT)
      .inOrder()
    assertThat(file.schemaVersion).isEqualTo(COMPONENT_RECORD_SCHEMA_VERSION)
  }

  @Test
  fun `the relation is inverted - one component lists every preview that renders it`() {
    // previews.json says "this render came from that component"; this says the reverse, which is
    // what makes a component addressable at all.
    val card = target("androidx.compose.material3.CardKt", "Card")
    val file =
      ComponentRecords.from(
        manifest(
          preview("b", componentTargets = listOf(card)),
          preview("a", componentTargets = listOf(card)),
        )
      )

    val record = file.components.single()
    assertThat(record.bindings.map { it.previewId }).containsExactly("a", "b").inOrder()
  }

  @Test
  fun `components and bindings are ordered, so the file is byte-reproducible`() {
    // A data product that reorders itself between builds is a diff nobody can read.
    val file =
      ComponentRecords.from(
        manifest(
          preview(
            "z",
            componentTargets = listOf(target("androidx.compose.material3.TextKt", "Text")),
          ),
          preview(
            "a",
            componentTargets = listOf(target("androidx.compose.material3.CardKt", "Card")),
          ),
        )
      )

    assertThat(file.components.map { it.canonicalId }).isInOrder()
  }

  @Test
  fun `the richest signature wins when one component is seen twice`() {
    // A target resolved through a path that could not read metadata reports no parameters. Letting
    // that overwrite a populated signature would lose the API for every consumer.
    val withParams =
      target(
        "androidx.compose.material3.CardKt",
        "Card",
        parameters =
          listOf(
            TargetParameter.Builder(name = "modifier", type = "Modifier")
              .also { b -> b.hasDefault = true }
              .build()
          ),
      )
    val withNone = target("androidx.compose.material3.CardKt", "Card")

    val file =
      ComponentRecords.from(
        manifest(
          preview("a", componentTargets = listOf(withNone)),
          preview("b", componentTargets = listOf(withParams)),
        )
      )

    assertThat(file.components.single().parameters.map { it.name }).containsExactly("modifier")
  }

  @Test
  fun `a top-level function's callable drops the synthetic file facade`() {
    // Deriving an import from the JVM owner would print `androidx.compose.material3.ButtonKt`,
    // which does not resolve.
    assertThat(
        ComponentRecords.callableFqn(target("androidx.compose.material3.ButtonKt", "Button"))
      )
      .isEqualTo("androidx.compose.material3.Button")
  }

  @Test
  fun `a member of a real class keeps its owner`() {
    assertThat(ComponentRecords.callableFqn(target("com.example.Screens", "Home")))
      .isEqualTo("com.example.Screens.Home")
  }

  @Test
  fun `canonicalId is module-qualified and always present`() {
    assertThat(ComponentRecords.canonicalId("app", target("com.example.HomeKt", "HomeScreen")))
      .isEqualTo("app/com.example.HomeKt.HomeScreen")
  }

  @Test
  fun `every catalog id a preview published the symbol under is kept`() {
    // A shared component's id must not depend on which preview the manifest listed first.
    val card = target("androidx.compose.material3.CardKt", "Card")
    fun catalogued(id: String, componentId: String) =
      preview(id, componentTargets = listOf(card))
        .copy(catalog = CatalogEntry(role = CatalogRole.COMPONENT, componentId = componentId))

    val file =
      ComponentRecords.from(
        manifest(
          preview("plain", componentTargets = listOf(card)),
          catalogued("b", "Containment/Card"),
          catalogued("a", "Media/Card"),
        )
      )

    val record = file.components.single()
    assertThat(record.componentIds).containsExactly("Containment/Card", "Media/Card").inOrder()
    assertThat(record.bindings.first { it.previewId == "plain" }.componentId).isNull()
  }

  @Test
  fun `a nested owner is emitted as a source-level callable`() {
    // `com.example.Controls${'$'}Companion.Button` is a JVM binary name; no Kotlin import accepts
    // it.
    assertThat(
        ComponentRecords.callableFqn(target("com.example.Controls\u0024Companion", "Button"))
      )
      .isEqualTo("com.example.Controls.Companion.Button")
  }

  @Test
  fun `a composable lambda parameter becomes a slot, carrying its receiver scope`() {
    val slots =
      ComponentRecords.slotsOf(
        listOf(
          TargetParameter.Builder(name = "onClick", type = "() -> Unit").build(),
          TargetParameter.Builder(name = "modifier", type = "Modifier")
            .also { b -> b.hasDefault = true }
            .build(),
          TargetParameter.Builder(name = "content", type = "RowScope.() -> Unit")
            .also { b ->
              b.composableSlot = true
              b.composableSlotReceiver = "androidx.compose.foundation.layout.RowScope"
            }
            .build(),
          TargetParameter.Builder(name = "footer", type = "() -> Unit")
            .also { b ->
              b.hasDefault = true
              b.composableSlot = true
            }
            .build(),
        )
      )

    // `onClick` is function-typed but not `@Composable`: a callback, not a slot.
    assertThat(slots.map { it.name }).containsExactly("content", "footer").inOrder()
    // The QUALIFIED name: `RowScope` alone cannot be imported, and two libraries can define it.
    assertThat(slots[0].receiverScope).isEqualTo("androidx.compose.foundation.layout.RowScope")
    // Requiredness is about the LAMBDA argument, never about how many children it may emit.
    assertThat(slots[0].required).isTrue()
    // An unscoped slot records no receiver rather than an empty string.
    assertThat(slots[1].receiverScope).isNull()
    assertThat(slots[1].required).isFalse()
  }

  @Test
  fun `a module with no inferred targets still produces a file`() {
    // An empty component list is a fact worth publishing — it says inference found nothing.
    val file = ComponentRecords.from(manifest(preview("p1")))

    assertThat(file.components).isEmpty()
    assertThat(file.module).isEqualTo("app")
  }

  @Test
  fun `the symbol carries the JVM name and descriptor alongside the source name`() {
    val file =
      ComponentRecords.from(
        manifest(
          preview(
            "p1",
            componentTargets =
              listOf(
                target(
                  "androidx.compose.material3.TextKt",
                  "Text",
                  jvmName = "Text-Nvy7gAk",
                  descriptor = "(Ljava/lang/String;JLandroidx/compose/runtime/Composer;I)V",
                )
              ),
          )
        )
      )

    val symbol = file.components.single().symbol
    // Three answers to three different questions, none of which the others can serve: what to
    // import, what to reflect on, and which method this actually is.
    assertThat(symbol.callable).isEqualTo("androidx.compose.material3.Text")
    assertThat(symbol.jvmName).isEqualTo("Text-Nvy7gAk")
    assertThat(symbol.descriptor)
      .isEqualTo("(Ljava/lang/String;JLandroidx/compose/runtime/Composer;I)V")
  }

  @Test
  fun `overloads merging into one record drop the descriptor rather than name one of them`() {
    // Two overloads merge under one canonical id; keeping the first descriptor would be arbitrary.
    val file =
      ComponentRecords.from(
        manifest(
          preview(
            "p1",
            targets =
              listOf(target("com.example.ChipKt", "Chip", descriptor = "(Ljava/lang/String;)V")),
          ),
          preview(
            "p2",
            targets = listOf(target("com.example.ChipKt", "Chip", descriptor = "(I)V")),
          ),
        )
      )

    val symbol = file.components.single().symbol
    assertThat(symbol.descriptor).isNull()
    assertThat(symbol.name).isEqualTo("Chip")
  }

  @Test
  fun `the overload most previews call speaks for the record, with its own whole signature`() {
    // m3-catalog's shape: most previews call the `value` overload, one the `TextFieldState` one.
    // The majority wins rather than refusing all or mixing signatures.
    fun param(name: String, type: String, default: Boolean = false) =
      TargetParameter.Builder(name = name, type = type).also { b -> b.hasDefault = default }.build()
    val byValue =
      target(
        "androidx.compose.material3.OutlinedTextFieldKt",
        "OutlinedTextField",
        parameters =
          listOf(
            param("value", "String"),
            param("onValueChange", "(String) -> Unit"),
            param("singleLine", "Boolean", default = true),
          ),
        descriptor = "(Ljava/lang/String;Lkotlin/jvm/functions/Function1;Z)V",
        signatureKnown = true,
      )
    val byState =
      target(
        "androidx.compose.material3.OutlinedTextFieldKt",
        "OutlinedTextField",
        parameters =
          listOf(
            param("state", "TextFieldState"),
            param("modifier", "Modifier", default = true),
            param("enabled", "Boolean", default = true),
            param("lineLimits", "TextFieldLineLimits", default = true),
          ),
        descriptor = "(Landroidx/compose/foundation/text/input/TextFieldState;)V",
        signatureKnown = true,
      )

    val record =
      ComponentRecords.from(
          manifest(
            preview("state", componentTargets = listOf(byState)),
            preview("sticker", componentTargets = listOf(byValue)),
            preview("screen", componentTargets = listOf(byValue)),
          )
        )
        .components
        .single()

    assertThat(record.overloadsCollided).isFalse()
    assertThat(record.symbol.descriptor).isEqualTo(byValue.descriptor)
    assertThat(record.parameters.map { it.name })
      .containsExactly("value", "onValueChange", "singleLine")
      .inOrder()
    assertThat(record.code?.call).startsWith("OutlinedTextField(value = ")
  }

  @Test
  fun `a tie between overloads stays collided, because nothing says which one is meant`() {
    val a = target("com.example.ChipKt", "Chip", descriptor = "(I)V", signatureKnown = true)
    val b = target("com.example.ChipKt", "Chip", descriptor = "(J)V", signatureKnown = true)
    val record =
      ComponentRecords.from(
          manifest(
            preview("p1", targets = listOf(a)),
            preview("p2", targets = listOf(b)),
            preview("p3", targets = listOf(a)),
            preview("p4", targets = listOf(b)),
          )
        )
        .components
        .single()
    assertThat(record.overloadsCollided).isTrue()
    assertThat(record.symbol.descriptor).isNull()
  }

  @Test
  fun `one component seen by several previews keeps its descriptor`() {
    // The common case, and the reason the rule above is "disagree" rather than "seen twice":
    // `Card` is rendered by many previews and every one of them reports the same method.
    val file =
      ComponentRecords.from(
        manifest(
          preview(
            "p1",
            targets =
              listOf(target("com.example.CardKt", "Card", jvmName = "Card", descriptor = "(I)V")),
          ),
          preview(
            "p2",
            targets =
              listOf(target("com.example.CardKt", "Card", jvmName = "Card", descriptor = "(I)V")),
          ),
        )
      )

    val symbol = file.components.single().symbol
    assertThat(symbol.descriptor).isEqualTo("(I)V")
    assertThat(symbol.jvmName).isEqualTo("Card")
  }

  @Test
  fun `a record carries the printed call site, so a consumer needs no generator`() {
    // `components.json` must answer "how do I call this?" alone; its consumer doesn't depend on
    // this module.
    val file =
      ComponentRecords.from(
        manifest(
          preview(
            "p1",
            componentTargets =
              listOf(
                target(
                  "androidx.compose.material3.ButtonKt",
                  "Button",
                  parameters =
                    listOf(
                      TargetParameter.Builder(name = "onClick", type = "() -> Unit").build(),
                      TargetParameter.Builder(name = "modifier", type = "Modifier")
                        .also { b -> b.hasDefault = true }
                        .build(),
                      TargetParameter.Builder(name = "content", type = "RowScope.() -> Unit")
                        .also { b -> b.composableSlot = true }
                        .build(),
                    ),
                  signatureKnown = true,
                )
              ),
          )
        )
      )

    val code = file.components.single().code
    assertThat(code?.call).isEqualTo("Button(onClick = {}, content = {})")
    assertThat(code?.imports).containsExactly("androidx.compose.material3.Button")
    assertThat(code?.refusedReason).isNull()
  }

  @Test
  fun `a component with no writable call site records why, not silence`() {
    // The refusal is the half that makes the field trustworthy, and it doubles as the mechanical
    // tier signal: no printable call site means the component cannot reach a Compose exporter.
    val file =
      ComponentRecords.from(
        manifest(
          preview(
            "p1",
            componentTargets =
              listOf(
                target(
                  "androidx.compose.material3.IconKt",
                  "Icon",
                  parameters =
                    listOf(
                      TargetParameter.Builder(name = "imageVector", type = "ImageVector").build()
                    ),
                  signatureKnown = true,
                )
              ),
          )
        )
      )

    val code = file.components.single().code
    assertThat(code?.call).isNull()
    assertThat(code?.refusedReason).contains("imageVector: ImageVector")
  }

  @Test
  fun `an unread signature refuses rather than printing a parameterless call`() {
    // `signatureKnown = false` is "we could not look", which reads identically to "takes nothing".
    // Printing `Card()` from the first is a compile error in someone else's build.
    val file =
      ComponentRecords.from(
        manifest(
          preview(
            "p1",
            componentTargets = listOf(target("androidx.compose.material3.CardKt", "Card")),
          )
        )
      )

    assertThat(file.components.single().code?.refusedReason).contains("not recovered")
  }

  @Test
  fun `builder policy reaches the record and names the preview that declared it`() {
    val card = target("androidx.compose.material3.CardKt", "Card")
    val file =
      ComponentRecords.from(
        manifest(
          preview("p1", componentTargets = listOf(card)),
          preview(
            "p2",
            componentTargets = listOf(card),
            builder = BuilderPolicy.Builder().also { b -> b.canvas = "material3/Card" }.build(),
          ),
        )
      )

    val record = file.components.single()
    assertThat(record.builder?.canvas).isEqualTo("material3/Card")
    // Which sticker to edit, which is not answerable from the policy alone once several previews
    // render one component.
    assertThat(record.builder?.declaredBy).containsExactly("p2")
    assertThat(record.builder?.conflicting).isEmpty()
  }

  @Test
  fun `two previews declaring the same policy agree rather than conflict`() {
    val card = target("androidx.compose.material3.CardKt", "Card")
    val policy = BuilderPolicy.Builder().also { b -> b.canvas = "material3/Card" }.build()
    val file =
      ComponentRecords.from(
        manifest(
          preview("p2", componentTargets = listOf(card), builder = policy),
          preview("p1", componentTargets = listOf(card), builder = policy),
        )
      )

    val merged = file.components.single().builder!!
    assertThat(merged.canvas).isEqualTo("material3/Card")
    assertThat(merged.declaredBy).containsExactly("p1", "p2").inOrder()
    assertThat(merged.conflicting).isEmpty()
  }

  @Test
  fun `disagreeing policies resolve to the lowest preview id and name the rest`() {
    val card = target("androidx.compose.material3.CardKt", "Card")
    val file =
      ComponentRecords.from(
        manifest(
          // Declared in first-seen-wins order to pin that resolution is by preview id, not manifest
          // order.
          preview(
            "p9",
            componentTargets = listOf(card),
            builder = BuilderPolicy.Builder().also { builder -> builder.canvas = "b" }.build(),
          ),
          preview(
            "p1",
            componentTargets = listOf(card),
            builder = BuilderPolicy.Builder().also { b -> b.canvas = "a" }.build(),
          ),
        )
      )

    val merged = file.components.single().builder!!
    assertThat(merged.canvas).isEqualTo("a")
    assertThat(merged.declaredBy).containsExactly("p1")
    // Recorded rather than resolved in silence: the resolution is arbitrary and the disagreement
    // is what somebody has to fix.
    assertThat(merged.conflicting).containsExactly("p9")
  }

  @Test
  fun `a preview that resolves one component through both paths declares its policy once`() {
    val card = target("com.example.CardKt", "Card")
    val file =
      ComponentRecords.from(
        manifest(
          preview(
            "p1",
            componentTargets = listOf(card),
            targets = listOf(card),
            builder = BuilderPolicy.Builder().also { b -> b.canvas = "material3/Card" }.build(),
          )
        )
      )

    assertThat(file.components.single().builder?.declaredBy).containsExactly("p1")
  }

  @Test
  fun `a sticker that renders several components binds its policy to one of them`() {
    // A `Button { Text(label) }` sticker records both calls; the policy must not also land on
    // `Text`.
    val button = target("androidx.wear.compose.material3.ButtonKt", "Button")
    val text = target("androidx.wear.compose.material3.TextKt", "Text")
    val file =
      ComponentRecords.from(
        manifest(
          preview(
            "p1",
            componentTargets = listOf(button, text),
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.id = "wear-m3/button"
                  b.canvas = "placeholder"
                }
                .build(),
          )
        )
      )

    val withPolicy = file.components.filter { it.builder != null }
    assertThat(withPolicy.map { it.canonicalId })
      .containsExactly("app/androidx.wear.compose.material3.ButtonKt.Button")
    // A guess, and said so: the generator reports it and names the fix.
    assertThat(withPolicy.single().builder?.ambiguousWith)
      .containsExactly("app/androidx.wear.compose.material3.TextKt.Text")
  }

  @Test
  fun `naming the subject settles it, and naming nothing the preview renders binds nothing`() {
    val button = target("androidx.wear.compose.material3.ButtonKt", "Button")
    val text = target("androidx.wear.compose.material3.TextKt", "Text")

    val named =
      ComponentRecords.from(
        manifest(
          preview(
            "p1",
            componentTargets = listOf(button, text),
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.component = "Text"
                  b.canvas = "material3/Text"
                }
                .build(),
          )
        )
      )
    val subject = named.components.single { it.builder != null }
    assertThat(subject.canonicalId).endsWith("TextKt.Text")
    assertThat(subject.builder?.ambiguousWith).isEmpty()

    // A policy attached to a component that is not there is a rename that got away. Quietly
    // attaching it to whatever else was in the list would hide exactly that.
    val missing =
      ComponentRecords.from(
        manifest(
          preview(
            "p2",
            componentTargets = listOf(button, text),
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.component = "CheckboxButton"
                  b.canvas = "p"
                }
                .build(),
          )
        )
      )
    assertThat(missing.components.filter { it.builder != null }).isEmpty()
  }

  @Test
  fun `an ambiguous simple name binds nothing and is reported, while the FQN settles it`() {
    // Same-named `Text`s from different packages: a simple name can't pick one silently.
    val wearText = target("androidx.wear.compose.material3.TextKt", "Text")
    val foundationText = target("androidx.compose.foundation.text.TextKt", "Text")

    val ambiguous =
      ComponentRecords.from(
        manifest(
          preview(
            "p1",
            componentTargets = listOf(wearText, foundationText),
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.component = "Text"
                  b.canvas = "p"
                }
                .build(),
          )
        )
      )
    assertThat(ambiguous.components.filter { it.builder != null }).isEmpty()
    val orphan = ambiguous.builderOrphans.single()
    assertThat(orphan.component).isEqualTo("Text")
    assertThat(orphan.candidates).hasSize(2)

    // The documented way out, and it still works: an FQN is unique by construction.
    val byFqn =
      ComponentRecords.from(
        manifest(
          preview(
            "p2",
            componentTargets = listOf(wearText, foundationText),
            builder =
              BuilderPolicy.Builder()
                .also { b ->
                  b.component = "androidx.wear.compose.material3.Text"
                  b.canvas = "p"
                }
                .build(),
          )
        )
      )
    val subject = byFqn.components.single { it.builder != null }
    assertThat(subject.canonicalId).isEqualTo("app/androidx.wear.compose.material3.TextKt.Text")
    assertThat(byFqn.builderOrphans).isEmpty()
  }

  @Test
  fun `orphans belong to the previews they were read from`() {
    // Orphans belong to previews, so a filtered record carries only the selected previews' orphans
    // (as `bundle pack --id A` relies on).
    val wearText = target("androidx.wear.compose.material3.TextKt", "Text")
    val foundationText = target("androidx.compose.foundation.text.TextKt", "Text")
    val orphaning =
      preview(
        "p-orphan",
        componentTargets = listOf(wearText, foundationText),
        builder =
          BuilderPolicy.Builder()
            .also { b ->
              b.component = "Text"
              b.canvas = "p"
            }
            .build(),
      )
    val plain = preview("p-plain", componentTargets = listOf(wearText))

    assertThat(
        ComponentRecords.from(manifest(orphaning, plain)).builderOrphans.map { it.previewId }
      )
      .containsExactly("p-orphan")
    assertThat(ComponentRecords.from(manifest(plain)).builderOrphans).isEmpty()
  }

  @Test
  fun `a component no preview declared a policy for carries none`() {
    val file =
      ComponentRecords.from(
        manifest(
          preview(
            "p1",
            componentTargets = listOf(target("androidx.compose.material3.CardKt", "Card")),
          )
        )
      )

    assertThat(file.components.single().builder).isNull()
  }

  private fun overloadParam(name: String, type: String, default: Boolean = false) =
    TargetParameter.Builder(name = name, type = type).also { b -> b.hasDefault = default }.build()

  private val stateOverload =
    TargetOverload(
      jvmName = "OutlinedTextField",
      descriptor = "(Landroidx/compose/foundation/text/input/TextFieldState;)V",
      parameters =
        listOf(
          TargetParameter.Builder(name = "state", type = "TextFieldState")
            .also { b ->
              b.typeFqn = "androidx.compose.foundation.text.input.TextFieldState"
              b.noArgFactory = "androidx.compose.foundation.text.input.rememberTextFieldState"
            }
            .build(),
          overloadParam("modifier", "Modifier", default = true),
          overloadParam("enabled", "Boolean", default = true),
        ),
    )
  private val valueOverload =
    TargetOverload(
      jvmName = "OutlinedTextField",
      descriptor = "(Ljava/lang/String;Lkotlin/jvm/functions/Function1;Z)V",
      parameters =
        listOf(
          overloadParam("value", "String"),
          overloadParam("onValueChange", "(String) -> Unit"),
          overloadParam("enabled", "Boolean", default = true),
          overloadParam("singleLine", "Boolean", default = true),
        ),
    )

  private fun calling(
    overload: TargetOverload,
    all: List<TargetOverload>,
    deprecated: Boolean = false,
  ) =
    PreviewTarget(
      className = "androidx.compose.material3.OutlinedTextFieldKt",
      functionName = "OutlinedTextField",
      jvmName = overload.jvmName,
      descriptor = overload.descriptor,
      confidence = TargetConfidence.HIGH,
      parameters = overload.parameters,
      signatureKnown = true,
      deprecated = deprecated,
      overloads = all,
    )

  @Test
  fun `the policy's names choose the overload, even one no preview calls`() {
    val all = listOf(stateOverload, valueOverload)
    val selection =
      ComponentRecords.select(
        manifest(
          preview("a", componentTargets = listOf(calling(stateOverload, all))),
          preview("b", componentTargets = listOf(calling(stateOverload, all))),
        )
      ) {
        setOf("value", "enabled", "singleLine", "label")
      }
    val record = selection.record.components.single()
    assertThat(record.symbol.descriptor).isEqualTo(valueOverload.descriptor)
    assertThat(record.parameters.map { it.name }).contains("singleLine")
    assertThat(record.code?.call).startsWith("OutlinedTextField(value = ")
    assertThat(selection.diagnostics).isEmpty()
    // Bindings still name the previews that rendered the component.
    assertThat(record.bindings.map { it.previewId }).containsExactly("a", "b")
  }

  @Test
  fun `with no policy, a deprecated overload the previews call is never the record`() {
    val deprecatedValue = valueOverload.copy(deprecated = true)
    val all = listOf(deprecatedValue, stateOverload)
    val record =
      ComponentRecords.from(
          manifest(
            preview(
              "a",
              componentTargets = listOf(calling(deprecatedValue, all, deprecated = true)),
            )
          )
        )
        .components
        .single()
    assertThat(record.symbol.descriptor).isEqualTo(stateOverload.descriptor)
    assertThat(record.code?.call).startsWith("OutlinedTextField(state = ")
  }

  @Test
  fun `a component whose only form is deprecated gets no code`() {
    val only =
      PreviewTarget(
        className = "androidx.compose.material3.DividerKt",
        functionName = "Divider",
        jvmName = "Divider",
        descriptor = "(Landroidx/compose/ui/Modifier;)V",
        confidence = TargetConfidence.HIGH,
        parameters = listOf(overloadParam("modifier", "Modifier", default = true)),
        signatureKnown = true,
        deprecated = true,
      )
    val selection =
      ComponentRecords.select(manifest(preview("a", componentTargets = listOf(only)))) {
        emptySet()
      }
    val record = selection.record.components.single()
    assertThat(record.code?.call).isNull()
    assertThat(record.code?.refusedReason).contains("deprecated")
    assertThat(selection.diagnostics.single().code).isEqualTo(OverloadSelection.ALL_DEPRECATED)
  }
}
