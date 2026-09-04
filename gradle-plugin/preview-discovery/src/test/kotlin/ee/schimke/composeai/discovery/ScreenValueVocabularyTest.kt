package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The value kinds beyond the four literals: a qualified read, a qualified call, an extension chain
 * — and the identity aliasing that lets a builder's own component ids resolve.
 *
 * Kept apart from [ScreenGeneratorTest] because the questions are different. That file asks whether
 * a screen is *assembled* correctly (slots, scopes, imports, opt-ins); this one asks whether a
 * *value* is written correctly, which is where the widening put the new ways to be wrong.
 */
class ScreenValueVocabularyTest {

  private fun component(
    name: String,
    callable: String,
    parameters: List<TargetParameter>,
    componentIds: List<String> = emptyList(),
    canonicalId: String = "app/androidx.compose.material3.${name}Kt.$name",
  ) =
    ComponentRecord(
      canonicalId = canonicalId,
      componentIds = componentIds,
      symbol =
        ComponentSymbol(
          jvmOwner = "androidx.compose.material3.${name}Kt",
          callable = callable,
          name = name,
          origin = ComponentOrigin.LIBRARY,
        ),
      parameters = parameters,
      signatureKnown = true,
      code = ComponentCode(call = "$name()", imports = listOf(callable)),
    )

  private val colorParameter =
    TargetParameter(
      "color",
      "Color",
      typeFqn = "androidx.compose.ui.graphics.Color",
      hasDefault = true,
    )

  private val modifierParameter =
    TargetParameter(
      "modifier",
      "Modifier",
      typeFqn = "androidx.compose.ui.Modifier",
      hasDefault = true,
    )

  private val text =
    component(
      "Text",
      "androidx.compose.material3.Text",
      listOf(
        TargetParameter("text", "String", typeFqn = "kotlin.String"),
        modifierParameter,
        colorParameter,
      ),
      componentIds = listOf("m3/text"),
    )

  private val button =
    component(
      "Button",
      "androidx.compose.material3.Button",
      listOf(
        TargetParameter("onClick", "() -> Unit", typeFqn = "kotlin.Function0"),
        TargetParameter("label", "String", typeFqn = "kotlin.String"),
      ),
      componentIds = listOf("m3/button"),
    )

  private fun catalog(vararg records: ComponentRecord) =
    ComponentRecordFile(module = "app", variant = "debug", components = records.toList())

  /**
   * The vocabulary these fixtures name. Passed explicitly in every case, because the generator
   * refuses a claimed value under a package the caller never declared — see the trusted-vocabulary
   * test below for what that protects against.
   */
  private val allowed = setOf("androidx.compose", "com.example")

  private fun generate(root: ScreenNode, catalog: ComponentRecordFile, name: String = "Screen") =
    ScreenGenerator.generate(
      ScreenDocument(name = name, root = root),
      catalog,
      expressionPackages = allowed,
    )

  private fun emitted(root: ScreenNode, catalog: ComponentRecordFile, name: String = "Screen") =
    generate(root, catalog, name) as ScreenGenerator.Result.Emitted

  private fun refusal(root: ScreenNode, catalog: ComponentRecordFile, name: String = "Screen") =
    (generate(root, catalog, name) as ScreenGenerator.Result.Refused).reasons

  private fun textNode(vararg arguments: Pair<String, ScreenValue>) =
    ScreenNode(
      componentId = "m3/text",
      arguments = mapOf("text" to ScreenValue.Text("hi")) + arguments,
    )

  private val color = "androidx.compose.ui.graphics.Color"
  private val modifier = "androidx.compose.ui.Modifier"

  @Test
  fun `a reference is emitted fully qualified and imported nowhere`() {
    val result =
      emitted(
        textNode(
          "color" to
            ScreenValue.Reference(
              rootFqn = "androidx.compose.material3.MaterialTheme",
              members = listOf("colorScheme", "primary"),
              typeFqn = color,
            )
        ),
        catalog(text),
      )
    assertThat(result.source)
      .contains("color = androidx.compose.material3.MaterialTheme.colorScheme.primary")
    assertThat(result.source).doesNotContain("import androidx.compose.material3.MaterialTheme")
  }

  @Test
  fun `a reference with no members is a bare qualified read`() {
    val result =
      emitted(
        textNode(
          "color" to ScreenValue.Reference("androidx.compose.ui.graphics.Color", typeFqn = color)
        ),
        catalog(text),
      )
    assertThat(result.source).contains("color = androidx.compose.ui.graphics.Color")
  }

  @Test
  fun `a reference claiming the wrong type is refused, as a literal of the wrong type is`() {
    assertThat(
        refusal(
          textNode(
            "color" to
              ScreenValue.Reference(
                "androidx.compose.ui.text.style.TextAlign",
                listOf("Center"),
                typeFqn = "androidx.compose.ui.text.style.TextAlign",
              )
          ),
          catalog(text),
        )
      )
      .containsExactly(
        "`Text`.`color` is androidx.compose.ui.graphics.Color, and this value is a " +
          "androidx.compose.ui.text.style.TextAlign"
      )
  }

  @Test
  fun `a construct is a qualified call with positional then named arguments`() {
    val result =
      emitted(
        textNode(
          "color" to
            ScreenValue.Construct(
              callableFqn = "androidx.compose.ui.graphics.Color",
              positional = listOf(ScreenValue.Fractional(0.5)),
              named = mapOf("alpha" to ScreenValue.Fractional(1.0)),
              typeFqn = color,
            )
        ),
        catalog(text),
      )
    assertThat(result.source)
      .contains("color = androidx.compose.ui.graphics.Color(0.5, alpha = 1.0)")
  }

  @Test
  fun `a chain imports each link and calls it by simple name`() {
    val result =
      emitted(
        textNode(
          "modifier" to
            ScreenValue.Chain(
              receiver = ScreenValue.Reference(modifier, typeFqn = modifier),
              links =
                listOf(
                  ChainLink("androidx.compose.foundation.layout.fillMaxWidth"),
                  ChainLink(
                    "androidx.compose.foundation.layout.padding",
                    positional =
                      listOf(
                        ScreenValue.Chain(
                          receiver = ScreenValue.Whole(16),
                          links = listOf(ChainLink("androidx.compose.ui.unit.dp", property = true)),
                          typeFqn = "androidx.compose.ui.unit.Dp",
                        )
                      ),
                  ),
                ),
              typeFqn = modifier,
            )
        ),
        catalog(text),
      )
    assertThat(result.source)
      .contains("modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(16.dp)")
    assertThat(result.source).contains("import androidx.compose.foundation.layout.fillMaxWidth")
    assertThat(result.source).contains("import androidx.compose.foundation.layout.padding")
    assertThat(result.source).contains("import androidx.compose.ui.unit.dp")
  }

  @Test
  fun `two links claiming one simple name are refused rather than resolved`() {
    assertThat(
        refusal(
          textNode(
            "modifier" to
              ScreenValue.Chain(
                receiver = ScreenValue.Reference(modifier, typeFqn = modifier),
                links =
                  listOf(
                    ChainLink("androidx.compose.foundation.layout.padding"),
                    ChainLink("com.example.decor.padding"),
                  ),
                typeFqn = modifier,
              )
          ),
          catalog(text),
        )
      )
      .containsExactly(
        "`padding` would be imported from androidx.compose.foundation.layout.padding and " +
          "com.example.decor.padding, which Kotlin rejects as a conflicting import"
      )
  }

  @Test
  fun `an extension colliding with an imported component is refused too`() {
    val padding =
      component(
        "padding",
        "com.example.decor.padding",
        listOf(TargetParameter("content", "() -> Unit", composableSlot = true)),
        canonicalId = "app/com.example.decor.PaddingKt.padding",
      )
    assertThat(
        refusal(
          ScreenNode(
            componentId = padding.canonicalId,
            slots =
              mapOf(
                "content" to
                  listOf(
                    textNode(
                      "modifier" to
                        ScreenValue.Chain(
                          receiver = ScreenValue.Reference(modifier, typeFqn = modifier),
                          links = listOf(ChainLink("androidx.compose.foundation.layout.padding")),
                          typeFqn = modifier,
                        )
                    )
                  )
              ),
          ),
          catalog(text, padding),
        )
      )
      .containsExactly(
        "`padding` would be imported from androidx.compose.foundation.layout.padding and " +
          "com.example.decor.padding, which Kotlin rejects as a conflicting import"
      )
  }

  @Test
  fun `a link named after the screen is refused, because the screen would shadow it`() {
    assertThat(
        refusal(
          textNode(
            "modifier" to
              ScreenValue.Chain(
                receiver = ScreenValue.Reference(modifier, typeFqn = modifier),
                links = listOf(ChainLink("com.example.decor.Screen")),
                typeFqn = modifier,
              )
          ),
          catalog(text),
          name = "Screen",
        )
      )
      .containsExactly("`Text`.`modifier` imports `Screen`, which is the screen's own name")
  }

  @Test
  fun `a chain with no links is refused as the reference it actually is`() {
    assertThat(
        refusal(
          textNode(
            "modifier" to
              ScreenValue.Chain(
                receiver = ScreenValue.Reference(modifier, typeFqn = modifier),
                links = emptyList(),
                typeFqn = modifier,
              )
          ),
          catalog(text),
        )
      )
      .containsExactly("`Text`.`modifier` is a chain with no links, which is a plain reference")
  }

  @Test
  fun `a property link that also passes arguments is refused`() {
    assertThat(
        refusal(
          textNode(
            "modifier" to
              ScreenValue.Chain(
                receiver = ScreenValue.Reference(modifier, typeFqn = modifier),
                links =
                  listOf(
                    ChainLink(
                      "androidx.compose.ui.unit.dp",
                      positional = listOf(ScreenValue.Whole(1)),
                      property = true,
                    )
                  ),
                typeFqn = modifier,
              )
          ),
          catalog(text),
        )
      )
      .containsExactly("`Text`.`modifier` reads `dp` as a property and also passes it arguments")
  }

  @Test
  fun `a nested whole past Int range is a Long literal, and a nested fraction a Double`() {
    val result =
      emitted(
        textNode(
          "color" to
            ScreenValue.Construct(
              "androidx.compose.ui.graphics.Color",
              positional = listOf(ScreenValue.Whole(0xFF6750A4), ScreenValue.Fractional(2.0)),
              typeFqn = color,
            )
        ),
        catalog(text),
      )
    assertThat(result.source)
      .contains("color = androidx.compose.ui.graphics.Color(4284960932L, 2.0)")
  }

  @Test
  fun `a name that cannot be written as Kotlin is refused rather than emitted`() {
    assertThat(
        refusal(
          textNode(
            "color" to
              ScreenValue.Reference("androidx.compose.ui.graphics.Color", listOf("a b`c"), color)
          ),
          catalog(text),
        )
      )
      .containsExactly(
        "`Text`.`color` names `a b`c`, which cannot be written as a Kotlin identifier"
      )
  }

  @Test
  fun `a hard keyword in a qualified name is backticked, not refused`() {
    val result =
      emitted(
        textNode(
          "color" to ScreenValue.Reference("com.example.in.Palette", listOf("brand"), color)
        ),
        catalog(text),
      )
    assertThat(result.source).contains("color = com.example.`in`.Palette.brand")
  }

  @Test
  fun `values nested past the depth cap are refused instead of overflowing the stack`() {
    var value: ScreenValue = ScreenValue.Whole(1)
    repeat(20) {
      value =
        ScreenValue.Construct(
          "androidx.compose.ui.graphics.Color",
          positional = listOf(value),
          typeFqn = color,
        )
    }
    assertThat(refusal(textNode("color" to value), catalog(text)))
      .containsExactly("`Text`.`color` nests values more than 16 deep")
  }

  @Test
  fun `a single-segment name is refused wherever a qualified one is required`() {
    // A default-package declaration is what a single segment names, and a file in a named package
    // can neither import nor refer to one. Before this, `Construct("Color", …)` emitted a bare
    // `Color(…)` into `package generated.screen` and reported success.
    assertThat(
        refusal(
          textNode("color" to ScreenValue.Construct("Color", typeFqn = color)),
          catalog(text),
        )
      )
      .containsExactly("`Text`.`color` refers to `Color`, which is not a qualified Kotlin name")
    assertThat(
        refusal(textNode("color" to ScreenValue.Reference("Color", typeFqn = color)), catalog(text))
      )
      .containsExactly("`Text`.`color` refers to `Color`, which is not a qualified Kotlin name")
    assertThat(
        refusal(
          textNode(
            "modifier" to
              ScreenValue.Chain(
                receiver = ScreenValue.Reference(modifier, typeFqn = modifier),
                links = listOf(ChainLink("padding")),
                typeFqn = modifier,
              )
          ),
          catalog(text),
        )
      )
      .containsExactly(
        "`Text`.`modifier` refers to `padding`, which is not a qualified Kotlin name"
      )
  }

  @Test
  fun `a negative chain receiver is parenthesised, because the selector binds tighter`() {
    // `-1.dp` parses as `-(1.dp)`. Proven with the compiler on the same shape one type down:
    // `-1.toString()` is rejected outright, since `String` has no `unaryMinus`.
    val whole =
      emitted(
        textNode(
          "modifier" to
            ScreenValue.Chain(
              receiver = ScreenValue.Whole(-1),
              links = listOf(ChainLink("androidx.compose.ui.unit.dp", property = true)),
              typeFqn = modifier,
            )
        ),
        catalog(text),
      )
    assertThat(whole.source).contains("modifier = (-1).dp")
    val fractional =
      emitted(
        textNode(
          "modifier" to
            ScreenValue.Chain(
              receiver = ScreenValue.Fractional(-1.5),
              links = listOf(ChainLink("androidx.compose.ui.unit.dp", property = true)),
              typeFqn = modifier,
            )
        ),
        catalog(text),
      )
    assertThat(fractional.source).contains("modifier = (-1.5).dp")
  }

  @Test
  fun `a positive chain receiver is left bare`() {
    val result =
      emitted(
        textNode(
          "modifier" to
            ScreenValue.Chain(
              receiver = ScreenValue.Whole(16),
              links = listOf(ChainLink("androidx.compose.ui.unit.dp", property = true)),
              typeFqn = modifier,
            )
        ),
        catalog(text),
      )
    assertThat(result.source).contains("modifier = 16.dp")
  }

  @Test
  fun `a callable outside the declared vocabulary is refused, however well spelled`() {
    // A document is wire data and a construct emits a qualified call with the arguments it carries,
    // so spelling is not the question — a host that compiles and renders what it generated would
    // have run this one.
    val exploit =
      ScreenValue.Construct(
        callableFqn = "java.nio.file.Files.readString",
        positional =
          listOf(
            ScreenValue.Construct(
              callableFqn = "java.nio.file.Path.of",
              positional = listOf(ScreenValue.Text("/etc/passwd")),
              typeFqn = "java.nio.file.Path",
            )
          ),
        typeFqn = "kotlin.String",
      )
    assertThat(
        (ScreenGenerator.generate(
            ScreenDocument("Screen", ScreenNode("m3/text", mapOf("text" to exploit))),
            catalog(text),
            expressionPackages = allowed,
          ) as ScreenGenerator.Result.Refused)
          .reasons
      )
      .contains(
        "`Text`.`text` names `java.nio.file.Files.readString`, which is outside the packages this " +
          "screen may call (androidx.compose, com.example)"
      )
  }

  @Test
  fun `an undeclared vocabulary refuses every claimed value, because the default is empty`() {
    // Fail closed: a caller who never thought about the boundary gets refusals, not a wider one.
    val result =
      ScreenGenerator.generate(
        ScreenDocument(
          "Screen",
          ScreenNode(
            "m3/text",
            mapOf(
              "text" to ScreenValue.Text("hi"),
              "color" to
                ScreenValue.Reference(
                  "androidx.compose.material3.MaterialTheme",
                  listOf("colorScheme", "primary"),
                  typeFqn = color,
                ),
            ),
          ),
        ),
        catalog(text),
      )
    assertThat((result as ScreenGenerator.Result.Refused).reasons)
      .containsExactly(
        "`Text`.`color` names `androidx.compose.material3.MaterialTheme`, which is outside the " +
          "packages this screen may call (none are allowed)"
      )
  }

  @Test
  fun `a longer sibling package does not satisfy a prefix`() {
    assertThat(
        (ScreenGenerator.generate(
            ScreenDocument(
              "Screen",
              ScreenNode(
                "m3/text",
                mapOf(
                  "text" to ScreenValue.Text("hi"),
                  "color" to
                    ScreenValue.Reference("androidx.composeevil.Exfiltrate", typeFqn = color),
                ),
              ),
            ),
            catalog(text),
            expressionPackages = setOf("androidx.compose"),
          ) as ScreenGenerator.Result.Refused)
          .reasons
      )
      .contains(
        "`Text`.`color` names `androidx.composeevil.Exfiltrate`, which is outside the packages " +
          "this screen may call (androidx.compose)"
      )
  }

  @Test
  fun `a value's opt-in markers reach the wrapper, at any nesting depth`() {
    val result =
      emitted(
        textNode(
          "color" to
            ScreenValue.Construct(
              "androidx.compose.material3.ExperimentalColor",
              positional =
                listOf(
                  ScreenValue.Reference(
                    "androidx.compose.material3.MaterialTheme",
                    listOf("colorScheme", "primary"),
                    typeFqn = color,
                    androidxOptIns = listOf("androidx.compose.material3.ExperimentalMaterial3Api"),
                  )
                ),
              typeFqn = color,
              requiredOptIns = listOf("com.example.ExperimentalPalette"),
            )
        ),
        catalog(text),
      )
    // The outer construct's marker uses the Kotlin mechanism and the nested reference's uses the
    // AndroidX one, so both annotations appear and neither marker lands under the wrong one.
    assertThat(result.source).contains("@kotlin.OptIn(com.example.ExperimentalPalette::class)")
    assertThat(result.source)
      .contains(
        "@androidx.annotation.OptIn(markerClass = " +
          "[androidx.compose.material3.ExperimentalMaterial3Api::class])"
      )
    assertThat(result.requiredOptIns)
      .containsExactly(
        "com.example.ExperimentalPalette",
        "androidx.compose.material3.ExperimentalMaterial3Api",
      )
  }

  @Test
  fun `a member extension is refused, because a chain link has to be importable`() {
    // `RowScope.weight` is a member of the scope, handed over by an implicit receiver. Neither
    // `import …layout.RowScope.weight` nor a package-level `…layout.weight` resolves, so importing
    // it produces a file that fails on the import line.
    assertThat(
        refusal(
          textNode(
            "modifier" to
              ScreenValue.Chain(
                receiver = ScreenValue.Reference(modifier, typeFqn = modifier),
                links = listOf(ChainLink("androidx.compose.foundation.layout.RowScope.weight")),
                typeFqn = modifier,
              )
          ),
          catalog(text),
        )
      )
      .containsExactly(
        "`Text`.`modifier` links `androidx.compose.foundation.layout.RowScope.weight`, whose " +
          "qualifier names a classifier rather than a package — a member extension comes from an " +
          "implicit receiver and cannot be imported"
      )
  }

  @Test
  fun `a top-level extension in a lower-case package is still accepted`() {
    val result =
      emitted(
        textNode(
          "modifier" to
            ScreenValue.Chain(
              receiver = ScreenValue.Reference(modifier, typeFqn = modifier),
              links = listOf(ChainLink("androidx.compose.foundation.layout.fillMaxWidth")),
              typeFqn = modifier,
            )
        ),
        catalog(text),
      )
    assertThat(result.source).contains("import androidx.compose.foundation.layout.fillMaxWidth")
  }

  @Test
  fun `an opt-in marker that is not a name is refused, because it becomes annotation source`() {
    // A marker is printed straight into `@OptIn(…)`, so a backtick and a newline close the
    // annotation and open a top-level declaration — arbitrary code in the generated file that
    // names nothing `expressionPackages` would have looked at.
    val injected =
      "com.example.Marker`::class)\nval pwned = System.exit(0)\n@kotlin.OptIn(kotlin.Any"
    assertThat(
        refusal(
          textNode(
            "color" to
              ScreenValue.Reference(
                "androidx.compose.ui.graphics.Color",
                listOf("Unspecified"),
                typeFqn = color,
                requiredOptIns = listOf(injected),
              )
          ),
          catalog(text),
        )
      )
      .containsExactly(
        "`Text`.`color` needs opt-in marker `$injected`, which is not a qualified Kotlin name"
      )
  }

  @Test
  fun `a component's markers are checked too, since one printer serves both`() {
    val gated =
      component("Gated", "androidx.compose.material3.Gated", emptyList()).let {
        it.copy(code = it.code!!.copy(requiredOptIns = listOf("not a name")))
      }
    assertThat(refusal(ScreenNode(gated.canonicalId), catalog(gated)))
      .containsExactly(
        "`Gated` needs opt-in marker `not a name`, which is not a qualified Kotlin name"
      )
  }

  @Test
  fun `a node resolves by catalog alias as well as by canonical id`() {
    val byAlias = emitted(textNode(), catalog(text))
    val byCanonical =
      emitted(
        ScreenNode(text.canonicalId, arguments = mapOf("text" to ScreenValue.Text("hi"))),
        catalog(text),
      )
    assertThat(byAlias.source).isEqualTo(byCanonical.source)
  }

  @Test
  fun `an alias two components claim identifies neither`() {
    val label =
      component("Label", "androidx.compose.material3.Label", emptyList(), listOf("m3/text"))
    assertThat(refusal(textNode(), catalog(text, label)))
      .contains(
        "catalog id `m3/text` maps to 2 components " +
          "(`app/androidx.compose.material3.TextKt.Text`, " +
          "`app/androidx.compose.material3.LabelKt.Label`), so it identifies none of them"
      )
  }

  @Test
  fun `a chain link with a malformed qualified name is refused, not imported`() {
    // Only the last segment used to be checked, so `padding` passed and the link was imported as
    // `foo.``.padding` — an empty backticked segment in source this generator had called
    // compilable.
    assertThat(
        refusal(
          textNode(
            "modifier" to
              ScreenValue.Chain(
                receiver = ScreenValue.Reference(modifier, typeFqn = modifier),
                links = listOf(ChainLink("foo..padding")),
                typeFqn = modifier,
              )
          ),
          catalog(text),
        )
      )
      .containsExactly(
        "`Text`.`modifier` refers to `foo..padding`, which is not a qualified Kotlin name"
      )
  }

  @Test
  fun `an all-underscore name is refused wherever a projection supplies one`() {
    assertThat(
        refusal(
          textNode("color" to ScreenValue.Reference(color, listOf("__"), color)),
          catalog(text),
        )
      )
      .containsExactly("`Text`.`color` names `__`, which cannot be written as a Kotlin identifier")
    assertThat(
        refusal(
          textNode(
            "modifier" to
              ScreenValue.Chain(
                receiver = ScreenValue.Reference(modifier, typeFqn = modifier),
                links = listOf(ChainLink("com.example.decor._")),
                typeFqn = modifier,
              )
          ),
          catalog(text),
        )
      )
      .containsExactly(
        "`Text`.`modifier` refers to `com.example.decor._`, which is not a qualified Kotlin name"
      )
  }

  @Test
  fun `two records sharing a canonical id and an alias identify neither through the alias`() {
    // The canonical lookup already refuses this pair. Collapsing the alias list by canonical id
    // made the alias resolve to whichever came first in the file instead — the same question
    // answered two ways depending on which spelling the document happened to use.
    val twin =
      component(
        "Label",
        "androidx.compose.material3.Label",
        emptyList(),
        componentIds = listOf("m3/text"),
        canonicalId = text.canonicalId,
      )
    assertThat(refusal(textNode(), catalog(text, twin)))
      .contains(
        "catalog id `m3/text` maps to 2 components " +
          "(`app/androidx.compose.material3.TextKt.Text`, " +
          "`app/androidx.compose.material3.TextKt.Text`), so it identifies none of them"
      )
  }

  @Test
  fun `one record listing an alias twice still resolves through it`() {
    val twice =
      component(
        "Text",
        "androidx.compose.material3.Text",
        text.parameters,
        componentIds = listOf("m3/text", "m3/text"),
      )
    assertThat(emitted(textNode(), catalog(twice)).source).contains("Text(text = \"hi\")")
  }

  @Test
  fun `a canonical id wins over another record's alias for the same string`() {
    val shadow =
      component(
        "Label",
        "androidx.compose.material3.Label",
        emptyList(),
        componentIds = listOf(text.canonicalId),
      )
    val result =
      emitted(
        ScreenNode(text.canonicalId, arguments = mapOf("text" to ScreenValue.Text("hi"))),
        catalog(text, shadow),
      )
    assertThat(result.source).contains("Text(text = \"hi\")")
  }

  private fun stateful(
    state: List<ScreenState>,
    root: ScreenNode = textNode(),
    name: String = "Stateful",
  ) =
    ScreenGenerator.generate(
      ScreenDocument(name = name, root = root, state = state),
      catalog(text),
      expressionPackages = allowed,
    )

  private fun statefulRefusal(state: List<ScreenState>, root: ScreenNode = textNode()) =
    (stateful(state, root) as ScreenGenerator.Result.Refused).reasons

  private fun readNode(variable: String, typeFqn: String = "kotlin.String") =
    ScreenNode(
      componentId = "m3/text",
      arguments = mapOf("text" to ScreenValue.StateRead(variable, typeFqn)),
    )

  @Test
  fun `a screen declares its state before the tree that reads it`() {
    val result =
      stateful(
        listOf(
          ScreenState("expanded", "kotlin.Boolean", ScreenValue.Bool(false)),
          ScreenState("caption", "kotlin.String", ScreenValue.Text("Hello")),
        ),
        readNode("caption"),
      )
        as ScreenGenerator.Result.Emitted

    // `remember`, or the screen resets on every recomposition that touches it — which reads as the
    // state never changing at all.
    assertThat(result.source)
      .contains(
        "val expanded = androidx.compose.runtime.remember { " +
          "androidx.compose.runtime.mutableStateOf<kotlin.Boolean>(false) }"
      )
    // Declaration order is emitted order: a preamble that reshuffles is a diff nobody can review.
    assertThat(result.source.indexOf("val expanded"))
      .isLessThan(result.source.indexOf("val caption"))
    assertThat(result.source).contains("text = caption.value")
  }

  @Test
  fun `a read of a variable the screen does not declare is refused, and says what it has`() {
    val reasons =
      statefulRefusal(
        listOf(ScreenState("expanded", "kotlin.Boolean", ScreenValue.Bool(false))),
        readNode("missing"),
      )

    assertThat(reasons).hasSize(1)
    assertThat(reasons.single()).contains("does not declare")
    // Naming what it does declare turns a dead end into a typo the reader can fix.
    assertThat(reasons.single()).contains("expanded")
  }

  @Test
  fun `a read whose claimed type disagrees with the declaration is refused`() {
    val reasons =
      statefulRefusal(
        listOf(ScreenState("caption", "kotlin.Boolean", ScreenValue.Bool(false))),
        readNode("caption"),
      )

    assertThat(reasons.single()).contains("declared as a")
  }

  @Test
  fun `state that would shadow a component this screen calls is refused`() {
    // The component is imported by simple name, so a property of the same name captures its call
    // site inside the function body and the screen silently calls the wrong thing.
    val reasons =
      statefulRefusal(listOf(ScreenState("Text", "kotlin.String", ScreenValue.Text("x"))))

    assertThat(reasons.single()).contains("would shadow")
  }

  @Test
  fun `a state name declared twice is refused once, not per use`() {
    val reasons =
      statefulRefusal(
        listOf(
          ScreenState("caption", "kotlin.String", ScreenValue.Text("a")),
          ScreenState("caption", "kotlin.String", ScreenValue.Text("b")),
        )
      )

    assertThat(reasons).containsExactly("state `caption` is declared more than once")
  }

  private fun handled(
    state: List<ScreenState>,
    handlers: Map<String, List<ScreenAction>>,
  ) =
    ScreenGenerator.generate(
      ScreenDocument(
        name = "Stateful",
        root =
          ScreenNode(
            componentId = "m3/button",
            arguments = mapOf("label" to ScreenValue.Text("Go")),
            handlers = handlers,
          ),
        state = state,
      ),
      catalog(button),
      expressionPackages = allowed,
    )

  private fun handledRefusal(
    state: List<ScreenState>,
    handlers: Map<String, List<ScreenAction>>,
  ) = (handled(state, handlers) as ScreenGenerator.Result.Refused).reasons

  @Test
  fun `a handler writes declared state from a lambda`() {
    val result =
      handled(
        listOf(
          ScreenState("expanded", "kotlin.Boolean", ScreenValue.Bool(false)),
          ScreenState("caption", "kotlin.String", ScreenValue.Text("a")),
        ),
        mapOf(
          "onClick" to
            listOf(
              ScreenAction.Toggle("expanded"),
              ScreenAction.Set("caption", ScreenValue.Text("tapped")),
            )
        ),
      )
        as ScreenGenerator.Result.Emitted

    assertThat(result.source)
      .contains("onClick = { expanded.value = !expanded.value; caption.value = \"tapped\" }")
  }

  @Test
  fun `toggling something that is not a boolean is refused`() {
    val reasons =
      handledRefusal(
        listOf(ScreenState("caption", "kotlin.String", ScreenValue.Text("a"))),
        mapOf("onClick" to listOf(ScreenAction.Toggle("caption"))),
      )

    assertThat(reasons.single()).contains("rather than a kotlin.Boolean")
  }

  @Test
  fun `setting a variable to the wrong type is refused`() {
    val reasons =
      handledRefusal(
        listOf(ScreenState("expanded", "kotlin.Boolean", ScreenValue.Bool(false))),
        mapOf("onClick" to listOf(ScreenAction.Set("expanded", ScreenValue.Text("yes")))),
      )

    assertThat(reasons).isNotEmpty()
  }

  @Test
  fun `a handler writing an undeclared variable is refused, and says what it has`() {
    val reasons =
      handledRefusal(
        listOf(ScreenState("expanded", "kotlin.Boolean", ScreenValue.Bool(false))),
        mapOf("onClick" to listOf(ScreenAction.Toggle("missing"))),
      )

    assertThat(reasons.single()).contains("does not declare")
    assertThat(reasons.single()).contains("expanded")
  }

  @Test
  fun `a handler with no actions is refused rather than emitted empty`() {
    // An empty lambda is a button that looks live and is not, and it compiles.
    val reasons =
      handledRefusal(
        listOf(ScreenState("expanded", "kotlin.Boolean", ScreenValue.Bool(false))),
        mapOf("onClick" to emptyList()),
      )

    assertThat(reasons.single()).contains("no actions")
  }

  @Test
  fun `a handler bound to a parameter the component does not declare is refused`() {
    // Dropping it silently would ship a screen whose button does nothing, which compiles fine.
    val reasons =
      handledRefusal(
        listOf(ScreenState("expanded", "kotlin.Boolean", ScreenValue.Bool(false))),
        mapOf("onLongPress" to listOf(ScreenAction.Toggle("expanded"))),
      )

    assertThat(reasons.single()).contains("has no `onLongPress`")
  }
}
