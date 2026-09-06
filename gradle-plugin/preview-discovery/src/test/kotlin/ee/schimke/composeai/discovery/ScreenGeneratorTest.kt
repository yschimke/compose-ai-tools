package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenGeneratorTest {

  private fun component(
    name: String,
    callable: String,
    parameters: List<TargetParameter>,
    call: String = "$name()",
    requiredOptIns: List<String> = emptyList(),
    androidxOptIns: List<String> = emptyList(),
  ) =
    ComponentRecord(
      canonicalId = "app/androidx.compose.material3.${name}Kt.$name",
      symbol =
        ComponentSymbol(
          jvmOwner = "androidx.compose.material3.${name}Kt",
          callable = callable,
          name = name,
          origin = ComponentOrigin.LIBRARY,
        ),
      parameters = parameters,
      signatureKnown = true,
      code =
        ComponentCode(
          call = call,
          imports = listOf(callable),
          requiredOptIns = requiredOptIns,
          androidxOptIns = androidxOptIns,
        ),
    )

  private val text =
    component(
      "Text",
      "androidx.compose.material3.Text",
      listOf(TargetParameter("text", "String", typeFqn = "kotlin.String")),
    )

  private val card =
    component(
      "Card",
      "androidx.compose.material3.Card",
      listOf(
        TargetParameter(
          "modifier",
          "Modifier",
          typeFqn = "androidx.compose.ui.Modifier",
          hasDefault = true,
        ),
        TargetParameter("content", "ColumnScope.() -> Unit", composableSlot = true),
      ),
    )

  private fun catalog(vararg records: ComponentRecord) =
    ComponentRecordFile(module = "app", variant = "debug", components = records.toList())

  private fun emitted(document: ScreenDocument, catalog: ComponentRecordFile) =
    ScreenGenerator.generate(document, catalog) as ScreenGenerator.Result.Emitted

  private fun refusal(document: ScreenDocument, catalog: ComponentRecordFile) =
    (ScreenGenerator.generate(document, catalog) as ScreenGenerator.Result.Refused).reasons

  private fun generated(
    document: ScreenDocument,
    catalog: ComponentRecordFile,
    preview: ScreenGenerator.Preview,
  ) =
    ScreenGenerator.generate(document, catalog, preview = preview) as ScreenGenerator.Result.Emitted

  private fun previewRefusal(
    document: ScreenDocument,
    catalog: ComponentRecordFile,
    preview: ScreenGenerator.Preview,
  ) =
    (ScreenGenerator.generate(document, catalog, preview = preview)
        as ScreenGenerator.Result.Refused)
      .reasons

  /** The same nested card-and-text screen several preview tests generate from. */
  private fun screen() =
    ScreenDocument(
      name = "HomeScreen",
      root =
        ScreenNode(
          componentId = card.canonicalId,
          slots =
            mapOf(
              "content" to
                listOf(
                  ScreenNode(text.canonicalId, arguments = mapOf("text" to ScreenValue.Text("Hi")))
                )
            ),
        ),
    )

  @Test
  fun `a screen binds the document's values and nests children into slots`() {
    val screen =
      ScreenDocument(
        name = "HomeScreen",
        root =
          ScreenNode(
            componentId = card.canonicalId,
            slots =
              mapOf(
                "content" to
                  listOf(
                    ScreenNode(
                      text.canonicalId,
                      arguments = mapOf("text" to ScreenValue.Text("Hello")),
                    )
                  )
              ),
          ),
      )

    val source = emitted(screen, catalog(card, text)).source

    // The user's value, not the placeholder `Text(text = "")` the call-site generator prints.
    assertThat(source).contains("""Text(text = "Hello")""")
    assertThat(source).contains("Card(content = {")
    assertThat(source).contains("fun HomeScreen()")
    assertThat(source).contains("import androidx.compose.material3.Card")
    // `Text` is nested inside `Card`'s `ColumnScope` slot and is still imported by simple name:
    // an implicit receiver adds names to the scope, it does not evict the import. See the
    // dedicated test.
    assertThat(source).contains("import androidx.compose.material3.Text")
    assertThat(source).doesNotContain("androidx.compose.material3.Text(text = ")
    // `modifier` is defaulted and untouched, so it is omitted rather than guessed at.
    assertThat(source).doesNotContain("modifier =")
  }

  @Test
  fun `no preview is emitted unless one is asked for`() {
    // The annotation lives in an Android tooling dependency a consumer of this generator need not
    // have, so the default has to stay "source that compiles wherever the screen does".
    val source = emitted(screen(), catalog(card, text)).source

    assertThat(source).doesNotContain("@Preview")
    assertThat(source).doesNotContain("androidx.compose.ui.tooling.preview")
  }

  @Test
  fun `a preview reproduces the design environment and calls the screen`() {
    val source =
      generated(
          screen(),
          catalog(card, text),
          ScreenGenerator.Preview(
            widthDp = 411,
            heightDp = 914,
            fontScale = 1.3,
            locale = "en-US",
            darkMode = true,
          ),
        )
        .source

    assertThat(source).contains("import androidx.compose.ui.tooling.preview.Preview")
    assertThat(source).contains("widthDp = 411,")
    assertThat(source).contains("heightDp = 914,")
    // A Float literal: `@Preview.fontScale` is a Float, and an unsuffixed decimal is a Double.
    assertThat(source).contains("fontScale = 1.3f,")
    assertThat(source).contains("""locale = "en-US",""")
    assertThat(source).contains("showBackground = true,")
    assertThat(source).contains("uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,")
    // A wrapper, not an annotation on the screen: the screen stays callable without dragging a
    // preview onto every call site.
    assertThat(source).contains("private fun HomeScreenPreview() {")
    assertThat(source).contains("    HomeScreen()")
    assertThat(source.indexOf("fun HomeScreen()")).isLessThan(source.indexOf("@Preview"))
  }

  @Test
  fun `the screen sizes fan-out is a second wrapper carrying no environment`() {
    val source =
      generated(
          screen(),
          catalog(card, text),
          ScreenGenerator.Preview(
            widthDp = 411,
            heightDp = 914,
            fontScale = 1.3,
            darkMode = true,
            screenSizes = true,
          ),
        )
        .source

    assertThat(source).contains("import androidx.compose.ui.tooling.preview.PreviewScreenSizes")
    assertThat(source).contains("@PreviewScreenSizes")
    assertThat(source).contains("private fun HomeScreenPreviewScreenSizesPreview() {")
    // The design's own frame stays on its own wrapper: a widthDp beside the multipreview would
    // override the very axis it varies, and a fontScale or uiMode would apply to all of them.
    val fanOut = source.substringAfter("@PreviewScreenSizes")
    assertThat(fanOut).doesNotContain("widthDp")
    assertThat(fanOut).doesNotContain("fontScale")
    assertThat(fanOut).doesNotContain("uiMode")
    // Both wrappers call the screen, and both come after it.
    assertThat(source.indexOf("fun HomeScreen()")).isLessThan(source.indexOf("@PreviewScreenSizes"))
  }

  /** The fan-out is the caller's second question, not a thing every preview drags along. */
  @Test
  fun `a preview without the fan-out names neither the annotation nor the wrapper`() {
    val source = generated(screen(), catalog(card, text), ScreenGenerator.Preview()).source

    assertThat(source).contains("@Preview")
    assertThat(source).doesNotContain("PreviewScreenSizes")
  }

  @Test
  fun `a component the fan-out wrapper would shadow is qualified instead`() {
    // Same trap as the plain wrapper's: the emitted function is top-level, so it beats an import
    // of the same simple name and the body's call would land on the wrapper that calls the screen.
    val clashing =
      component(
        "HomeScreenPreviewScreenSizesPreview",
        "androidx.compose.material3.HomeScreenPreviewScreenSizesPreview",
        emptyList(),
      )
    val document = ScreenDocument(name = "HomeScreen", root = ScreenNode(clashing.canonicalId))

    val source =
      generated(document, catalog(clashing), ScreenGenerator.Preview(screenSizes = true)).source

    assertThat(source)
      .doesNotContain("import androidx.compose.material3.HomeScreenPreviewScreenSizesPreview")
    assertThat(source).contains("androidx.compose.material3.HomeScreenPreviewScreenSizesPreview()")
  }

  @Test
  fun `a light design gets no uiMode rather than a light one`() {
    // `@Preview` already previews light; naming it would claim the design said something it did
    // not, and `UI_MODE_NIGHT_NO` is not the same as "unspecified" to a device configuration.
    val source =
      generated(screen(), catalog(card, text), ScreenGenerator.Preview(darkMode = false)).source

    assertThat(source).doesNotContain("uiMode")
  }

  @Test
  fun `a locale that is not a language tag is refused rather than escaped into the file`() {
    // The locale is wire data reaching a string literal. A quote would close it and continue in
    // code, so the shape is checked rather than trusted.
    val reasons =
      previewRefusal(screen(), catalog(card, text), ScreenGenerator.Preview(locale = """en", x="""))

    assertThat(reasons.single()).contains("is not a language tag")
  }

  @Test
  fun `a nonsensical preview size or font scale is refused, and every problem is named`() {
    val reasons =
      previewRefusal(
        screen(),
        catalog(card, text),
        ScreenGenerator.Preview(widthDp = 0, heightDp = -1, fontScale = 0.0),
      )

    assertThat(reasons).hasSize(3)
    assertThat(reasons.joinToString("\n")).contains("widthDp must be positive")
    assertThat(reasons.joinToString("\n")).contains("heightDp must be positive")
    assertThat(reasons.joinToString("\n")).contains("fontScale must be finite and positive")
  }

  @Test
  fun `a component named Preview is qualified only when a preview is emitted`() {
    val previewComponent =
      component("Preview", "androidx.compose.material3.Preview", parameters = emptyList())
    val document =
      ScreenDocument(name = "HomeScreen", root = ScreenNode(previewComponent.canonicalId))

    // Without one, nothing has spent the name and the ordinary simple import stands.
    val plain = emitted(document, catalog(previewComponent)).source
    assertThat(plain).contains("import androidx.compose.material3.Preview")
    assertThat(plain).contains("    Preview()")

    // With one, the file's own `Preview` would make the call ambiguous, so the component is
    // called fully qualified instead — the same answer a two-package collision gets.
    val withPreview =
      generated(document, catalog(previewComponent), ScreenGenerator.Preview()).source
    assertThat(withPreview).doesNotContain("import androidx.compose.material3.Preview")
    assertThat(withPreview).contains("androidx.compose.material3.Preview()")
  }

  @Test
  fun `a component the preview wrapper would shadow is qualified instead`() {
    // The wrapper is a top-level declaration, so it beats an import of the same simple name. Left
    // alone, `HomeScreenPreview()` in the body would call the wrapper, which calls the screen —
    // a stack overflow standing in for the component somebody placed.
    val clashing =
      component("HomeScreenPreview", "androidx.compose.material3.HomeScreenPreview", emptyList())
    val document = ScreenDocument(name = "HomeScreen", root = ScreenNode(clashing.canonicalId))

    val source = generated(document, catalog(clashing), ScreenGenerator.Preview()).source

    assertThat(source).doesNotContain("import androidx.compose.material3.HomeScreenPreview")
    assertThat(source).contains("androidx.compose.material3.HomeScreenPreview()")
    assertThat(source).contains("private fun HomeScreenPreview() {")
  }

  @Test
  fun `a component the catalog no longer has is refused, not invented`() {
    val screen = ScreenDocument("Screen", ScreenNode("app/com.example.GoneKt.Gone"))

    assertThat(refusal(screen, catalog(text)).single())
      .contains("no component `app/com.example.GoneKt.Gone`")
  }

  @Test
  fun `a component whose call site was refused stays refused here`() {
    // The whole point of gating on `code`: every protection the call-site generator learned — the
    // private, the generic, the collided — arrives here without being re-derived.
    val private =
      component("Secret", "com.example.Secret", emptyList(), call = "Secret()")
        .copy(
          code =
            ComponentCode(
              refusedReason = "not public or internal, so a generated file cannot call it"
            )
        )
    val screen = ScreenDocument("Screen", ScreenNode(private.canonicalId))

    assertThat(refusal(screen, catalog(private)).single()).contains("not public or internal")
  }

  @Test
  fun `a property the component does not declare is refused rather than dropped`() {
    // Dropping it silently would generate a screen that compiles and is not the one designed.
    val screen =
      ScreenDocument(
        "Screen",
        ScreenNode(text.canonicalId, arguments = mapOf("caption" to ScreenValue.Text("x"))),
      )

    assertThat(refusal(screen, catalog(text)).first()).contains("has no parameter `caption`")
  }

  @Test
  fun `a value of the wrong type is refused`() {
    val screen =
      ScreenDocument(
        "Screen",
        ScreenNode(text.canonicalId, arguments = mapOf("text" to ScreenValue.Bool(true))),
      )

    assertThat(refusal(screen, catalog(text)).first()).contains("kotlin.String, which Bool is not")
  }

  @Test
  fun `a domain type that renders like a Kotlin scalar does not accept a literal`() {
    // The trap that bit the call-site generator twice: `com.example.String` renders as `String`.
    val odd =
      component(
        "Odd",
        "com.example.Odd",
        listOf(TargetParameter("value", "String", typeFqn = "com.example.String")),
      )
    val screen =
      ScreenDocument(
        "Screen",
        ScreenNode(odd.canonicalId, arguments = mapOf("value" to ScreenValue.Text("x"))),
      )

    assertThat(refusal(screen, catalog(odd)).first()).contains("com.example.String")
  }

  @Test
  fun `text is escaped so ordinary characters cannot break the generated file`() {
    val screen =
      ScreenDocument(
        "Screen",
        ScreenNode(
          text.canonicalId,
          arguments = mapOf("text" to ScreenValue.Text("""a "quote", a ${'$'}var and a \ slash""")),
        ),
      )

    val source = emitted(screen, catalog(text)).source

    // `$` matters as much as `"`: unescaped it becomes a template for a variable that is not there.
    assertThat(source).contains("""\"quote\"""")
    assertThat(source).contains("""\${'$'}var""")
    assertThat(source).contains("""\\ slash""")
  }

  @Test
  fun `required opt-ins are applied to the generated file, not left to the caller`() {
    val experimental =
      component(
        "Fancy",
        "androidx.compose.material3.Fancy",
        emptyList(),
        requiredOptIns = listOf("androidx.compose.material3.ExperimentalMaterial3Api"),
      )
    val screen = ScreenDocument("Screen", ScreenNode(experimental.canonicalId))

    val emitted = emitted(screen, catalog(experimental))

    // Qualified, not imported: two markers can share a simple name across packages, so the
    // shortened form is ambiguous rather than merely ugly.
    assertThat(emitted.source)
      .contains("@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)")
    assertThat(emitted.source)
      .doesNotContain("import androidx.compose.material3.ExperimentalMaterial3Api")
    assertThat(emitted.requiredOptIns)
      .containsExactly("androidx.compose.material3.ExperimentalMaterial3Api")
  }

  @Test
  fun `every problem is reported, not just the first`() {
    // A builder wants the whole list to act on; fixing one error at a time is a bad loop.
    val screen =
      ScreenDocument(
        "Screen",
        ScreenNode(
          card.canonicalId,
          arguments = mapOf("nope" to ScreenValue.Text("x")),
          slots = mapOf("content" to listOf(ScreenNode("app/com.example.GoneKt.Gone"))),
        ),
      )

    assertThat(refusal(screen, catalog(card))).hasSize(2)
  }

  @Test
  fun `a screen name that is not an identifier is refused`() {
    assertThat(refusal(ScreenDocument("my screen", ScreenNode(text.canonicalId)), catalog(text)))
      .containsExactly("screen name `my screen` is not a usable Kotlin function name")
  }

  @Test
  fun `two components sharing a simple name are called fully qualified`() {
    // `com.a.Badge` and `com.b.Badge` both reduced to `Badge()` under two conflicting imports.
    fun badge(pkg: String) =
      component("Badge", "$pkg.Badge", emptyList()).let {
        it.copy(
          canonicalId = "app/$pkg.BadgeKt.Badge",
          symbol = it.symbol.copy(jvmOwner = "$pkg.BadgeKt", callable = "$pkg.Badge"),
        )
      }
    val a = badge("com.a")
    val b = badge("com.b")
    val card2 =
      card.copy(
        parameters =
          listOf(TargetParameter("content", "ColumnScope.() -> Unit", composableSlot = true))
      )
    val screen =
      ScreenDocument(
        "Screen",
        ScreenNode(
          card2.canonicalId,
          slots = mapOf("content" to listOf(ScreenNode(a.canonicalId), ScreenNode(b.canonicalId))),
        ),
      )

    val source = emitted(screen, catalog(card2, a, b)).source

    assertThat(source).contains("com.a.Badge()")
    assertThat(source).contains("com.b.Badge()")
    assertThat(source).doesNotContain("import com.a.Badge")
    assertThat(source).doesNotContain("import com.b.Badge")
  }

  @Test
  fun `a component sharing the screen's own name is qualified, so the screen cannot recurse`() {
    // `fun HomeScreen()` calling an imported `HomeScreen()` would shadow the import and call
    // itself — a stack overflow that compiles.
    val same =
      component("HomeScreen", "com.example.HomeScreen", emptyList()).let {
        it.copy(
          canonicalId = "app/com.example.HomeScreenKt.HomeScreen",
          symbol = it.symbol.copy(callable = "com.example.HomeScreen"),
        )
      }
    val screen = ScreenDocument("HomeScreen", ScreenNode(same.canonicalId))

    val source = emitted(screen, catalog(same)).source

    assertThat(source).contains("com.example.HomeScreen()")
    assertThat(source).doesNotContain("import com.example.HomeScreen")
  }

  @Test
  fun `an Int value that does not fit is refused rather than silently wrapped`() {
    // `Long.toInt()` turns 2147483648 into -2147483648: source that compiles carrying a number
    // nobody entered.
    val counted =
      component(
        "Counted",
        "com.example.Counted",
        listOf(TargetParameter("count", "Int", typeFqn = "kotlin.Int")),
      )
    val tooBig =
      ScreenDocument(
        "Screen",
        ScreenNode(
          counted.canonicalId,
          arguments = mapOf("count" to ScreenValue.Whole(2147483648L)),
        ),
      )
    val fits =
      ScreenDocument(
        "Screen",
        ScreenNode(counted.canonicalId, arguments = mapOf("count" to ScreenValue.Whole(7L))),
      )

    assertThat(refusal(tooBig, catalog(counted)).first()).contains("count")
    assertThat(emitted(fits, catalog(counted)).source).contains("Counted(count = 7)")
  }

  @Test
  fun `children are refused for a slot a bare lambda cannot satisfy`() {
    // A defaulted slot can be absent from `code.call` and still be uncallable with `{ children }`:
    // two parameters have nothing to bind, and a non-Unit return has nothing to return.
    val dragging =
      component(
        "Dragging",
        "com.example.Dragging",
        listOf(
          TargetParameter(
            "onDrag",
            "(Float, Float) -> Unit",
            hasDefault = true,
            composableSlot = true,
          )
        ),
      )
    val screen =
      ScreenDocument(
        "Screen",
        ScreenNode(
          dragging.canonicalId,
          slots = mapOf("onDrag" to listOf(ScreenNode(text.canonicalId))),
        ),
      )

    assertThat(refusal(screen, catalog(dragging, text)).first())
      .contains("bare lambda cannot satisfy")
  }

  @Test
  fun `a screen named after a Kotlin keyword is refused`() {
    assertThat(refusal(ScreenDocument("when", ScreenNode(text.canonicalId)), catalog(text)).first())
      .contains("not a usable Kotlin function name")
  }

  @Test
  fun `a Float value that cannot survive narrowing is refused`() {
    val sized =
      component(
        "Sized",
        "com.example.Sized",
        listOf(TargetParameter("scale", "Float", typeFqn = "kotlin.Float")),
      )
    fun screen(v: Double) =
      ScreenDocument(
        "Screen",
        ScreenNode(sized.canonicalId, arguments = mapOf("scale" to ScreenValue.Fractional(v))),
      )

    // Past Float's range it becomes Infinity; below it, zero. Neither is the designed value.
    assertThat(refusal(screen(Double.MAX_VALUE), catalog(sized)).first()).contains("scale")
    assertThat(refusal(screen(1.0e-60), catalog(sized)).first()).contains("scale")
    // Non-finite is not a Kotlin literal at all.
    assertThat(refusal(screen(Double.NaN), catalog(sized)).first()).contains("scale")
    assertThat(emitted(screen(0.5), catalog(sized)).source).contains("Sized(scale = 0.5f)")
    // Zero is legitimate and must not be mistaken for an underflow.
    assertThat(emitted(screen(0.0), catalog(sized)).source).contains("Sized(scale = 0.0f)")
  }

  @Test
  fun `opt-in markers are qualified, so two of the same simple name stay distinct`() {
    val a =
      component("A", "com.a.A", emptyList(), requiredOptIns = listOf("com.a.ExperimentalApi")).let {
        it.copy(canonicalId = "app/com.a.AKt.A", symbol = it.symbol.copy(callable = "com.a.A"))
      }
    val b =
      component("B", "com.b.B", emptyList(), requiredOptIns = listOf("com.b.ExperimentalApi")).let {
        it.copy(canonicalId = "app/com.b.BKt.B", symbol = it.symbol.copy(callable = "com.b.B"))
      }
    val holder =
      card.copy(
        parameters =
          listOf(TargetParameter("content", "ColumnScope.() -> Unit", composableSlot = true))
      )
    val screen =
      ScreenDocument(
        "Screen",
        ScreenNode(
          holder.canonicalId,
          slots = mapOf("content" to listOf(ScreenNode(a.canonicalId), ScreenNode(b.canonicalId))),
        ),
      )

    val source = emitted(screen, catalog(holder, a, b)).source

    // `@OptIn(ExperimentalApi::class, ExperimentalApi::class)` would not compile.
    assertThat(source)
      .contains("@kotlin.OptIn(com.a.ExperimentalApi::class, com.b.ExperimentalApi::class)")
  }

  @Test
  fun `a record from a newer schema is refused rather than read as this one`() {
    val newer =
      ComponentRecordFile(
        schemaVersion = COMPONENT_RECORD_SCHEMA_VERSION + 1,
        module = "app",
        variant = "debug",
        components = listOf(text),
      )

    assertThat(refusal(ScreenDocument("Screen", ScreenNode(text.canonicalId)), newer).first())
      .contains("newer than")
  }

  @Test
  fun `a package segment that is not a usable identifier is refused`() {
    val result =
      ScreenGenerator.generate(
        ScreenDocument("Screen", ScreenNode(text.canonicalId)),
        catalog(text),
        packageName = "com.example.when",
      )

    assertThat((result as ScreenGenerator.Result.Refused).reasons.first()).contains("`when`")
  }

  @Test
  fun `Long MIN_VALUE is emitted by name, because its literal does not compile`() {
    // `-9223372036854775808L` is rejected: Kotlin reads the positive token first and it is out of
    // range, then applies unary minus. Confirmed against the compiler — and `Int.MIN_VALUE`, the
    // same spelling one type down, *is* accepted, so it stays a plain literal.
    val counted =
      component(
        "Counted",
        "com.example.Counted",
        listOf(TargetParameter("total", "Long", typeFqn = "kotlin.Long")),
      )
    fun screen(v: Long) =
      ScreenDocument(
        "Screen",
        ScreenNode(counted.canonicalId, arguments = mapOf("total" to ScreenValue.Whole(v))),
      )

    assertThat(emitted(screen(Long.MIN_VALUE), catalog(counted)).source)
      .contains("Counted(total = kotlin.Long.MIN_VALUE)")
    assertThat(emitted(screen(Long.MAX_VALUE), catalog(counted)).source)
      .contains("Counted(total = 9223372036854775807L)")
    assertThat(emitted(screen(7L), catalog(counted)).source).contains("Counted(total = 7L)")
  }

  @Test
  fun `an all-underscore screen name is refused`() {
    // `_` and `__` match every identifier regex and Kotlin reserves them: "Names _, __, ___, ...
    // are reserved in Kotlin".
    assertThat(refusal(ScreenDocument("_", ScreenNode(text.canonicalId)), catalog(text)).first())
      .contains("not a usable Kotlin function name")
    assertThat(refusal(ScreenDocument("__", ScreenNode(text.canonicalId)), catalog(text)).first())
      .contains("not a usable Kotlin function name")
    // A leading underscore on an otherwise ordinary name is legal and must still be accepted.
    assertThat(
        emitted(ScreenDocument("_Screen", ScreenNode(text.canonicalId)), catalog(text)).source
      )
      .contains("fun _Screen()")
  }

  @Test
  fun `an all-underscore package segment is refused`() {
    val result =
      ScreenGenerator.generate(
        ScreenDocument("Screen", ScreenNode(text.canonicalId)),
        catalog(text),
        packageName = "com._.example",
      )

    assertThat((result as ScreenGenerator.Result.Refused).reasons.first()).contains("`_`")
  }

  @Test
  fun `an opt-in marker under a keyword package is escaped`() {
    // ClassGraph reports the FQN unescaped, so a marker in a package Kotlin source spells
    // ``com.`when``` arrives as `com.when` and would emit an annotation that does not compile.
    val fancy =
      component("Fancy", "com.example.Fancy", emptyList(), requiredOptIns = listOf("com.when.Api"))

    val source =
      emitted(ScreenDocument("Screen", ScreenNode(fancy.canonicalId)), catalog(fancy)).source

    assertThat(source).contains("@kotlin.OptIn(com.`when`.Api::class)")
  }

  @Test
  fun `children of a slot the component never declared are still reported`() {
    // The argument loop walks the component's own parameters, so a renamed slot is never reached
    // and its subtree would go unreported — the unresolved-node gap, one level in.
    val holder =
      card.copy(
        parameters =
          listOf(TargetParameter("content", "ColumnScope.() -> Unit", composableSlot = true))
      )
    val screen =
      ScreenDocument(
        "Screen",
        ScreenNode(
          holder.canonicalId,
          slots = mapOf("body" to listOf(ScreenNode("app/com.example.GoneKt.Gone"))),
        ),
      )

    val reasons = refusal(screen, catalog(holder))

    assertThat(reasons).hasSize(2)
    assertThat(reasons.joinToString()).contains("has no slot `body`")
    assertThat(reasons.joinToString()).contains("Gone")
  }

  @Test
  fun `children of an unsatisfiable slot are still reported`() {
    // The third branch that rejects a node and could drop its subtree, after an unresolved id and
    // a slot the component never declared.
    val dragging =
      component(
        "Dragging",
        "com.example.Dragging",
        listOf(
          TargetParameter(
            "onDrag",
            "(Float, Float) -> Unit",
            hasDefault = true,
            composableSlot = true,
          )
        ),
      )
    val screen =
      ScreenDocument(
        "Screen",
        ScreenNode(
          dragging.canonicalId,
          slots = mapOf("onDrag" to listOf(ScreenNode("app/com.example.GoneKt.Gone"))),
        ),
      )

    val reasons = refusal(screen, catalog(dragging))

    assertThat(reasons).hasSize(2)
    assertThat(reasons.joinToString()).contains("bare lambda cannot satisfy")
    assertThat(reasons.joinToString()).contains("Gone")
  }

  @Test
  fun `a child inside a receiver slot is imported, like any other child`() {
    // This reverses a deliberate decision, so it records both halves. The hazard it avoided is
    // real: Kotlin resolves a simple name against implicit receivers before imports, so a
    // `ColumnScope` declaring a member `Text` would outrank `import
    // androidx.compose.material3.Text`
    // and draw something else. The receiver's members are not in the record, so it was avoided
    // rather than checked.
    //
    // What that cost was not worth it. The flag was sticky, so one scoped slot near the root
    // qualified every descendant, and since almost every container in Material 3 scopes its
    // content — `Column`, `Card`, `Button` — a realistic screen came out fully qualified from top
    // to bottom. That is the source a builder shows its user.
    //
    // It is also a hazard the language hands every hand-written file, and one that named arguments
    // blunt: a shadowing member has to match the simple name *and* the parameter names to bind at
    // all, and anything less is a compile error rather than a wrong screen. Checking it properly
    // means recording a receiver's members in the catalog, which is the version worth building.
    val screen =
      ScreenDocument(
        "Screen",
        ScreenNode(
          card.canonicalId,
          slots =
            mapOf(
              "content" to
                listOf(
                  ScreenNode(
                    text.canonicalId,
                    arguments = mapOf("text" to ScreenValue.Text("Hi")),
                  )
                )
            ),
        ),
      )

    val source = emitted(screen, catalog(card, text)).source

    assertThat(source).contains("Text(text = \"Hi\")")
    assertThat(source).doesNotContain("androidx.compose.material3.Text(text = ")
    assertThat(source).contains("import androidx.compose.material3.Text")
    // The container it nests inside is spelled the same way, as it always was.
    assertThat(source).contains("    Card(")
    assertThat(source).contains("import androidx.compose.material3.Card")
  }

  @Test
  fun `a nullable composable slot accepts children`() {
    // `(@Composable () -> Unit)?` renders as `(() -> Unit)?`, which has no ` -> Unit` suffix for
    // the lambda-shape check to find — so an optional slot was refused even though Kotlin accepts
    // a non-null `{ … }` for it.
    val optional =
      component(
        "Optional",
        "com.example.Optional",
        listOf(
          TargetParameter("content", "(() -> Unit)?", hasDefault = true, composableSlot = true)
        ),
      )
    val screen =
      ScreenDocument(
        "Screen",
        ScreenNode(
          optional.canonicalId,
          slots =
            mapOf(
              "content" to
                listOf(
                  ScreenNode(text.canonicalId, arguments = mapOf("text" to ScreenValue.Text("Hi")))
                )
            ),
        ),
      )

    val source = emitted(screen, catalog(optional, text)).source

    assertThat(source).contains("content = {")
    assertThat(source).contains("Text(text = \"Hi\")")
  }

  @Test
  fun `an opt-in marker is emitted exactly as recorded, dollars and all`() {
    // The producer rebuilds a nested marker's name from its nesting chain, so what arrives here is
    // already source notation. The emitter must not rewrite it — turning every `$` into `.` would
    // reference a class that does not exist — but it must still quote a segment that cannot be
    // written bare, which is how such a name came to hold a `$` in the first place.
    // `ComposableSignatureTest` covers both producer halves.
    val nested =
      component(
        "Nested",
        "com.example.Nested",
        emptyList(),
        requiredOptIns = listOf("com.example.Api.Experimental"),
      )
    val dollar =
      component(
        "Dollar",
        "com.example.Dollar",
        emptyList(),
        requiredOptIns = listOf("com.example.Api${'$'}Experimental"),
      )

    assertThat(emitted(ScreenDocument("A", ScreenNode(nested.canonicalId)), catalog(nested)).source)
      .contains("@kotlin.OptIn(com.example.Api.Experimental::class)")
    assertThat(emitted(ScreenDocument("B", ScreenNode(dollar.canonicalId)), catalog(dollar)).source)
      .contains("@kotlin.OptIn(com.example.`Api${'$'}Experimental`::class)")
  }

  @Test
  fun `a non-ASCII but valid Kotlin name is accepted`() {
    // `Übersicht` and `画面` need no backticks in Kotlin. An ASCII-only rule refused documents that
    // were never wrong.
    assertThat(
        emitted(ScreenDocument("Übersicht", ScreenNode(text.canonicalId)), catalog(text)).source
      )
      .contains("fun Übersicht()")
    assertThat(emitted(ScreenDocument("画面", ScreenNode(text.canonicalId)), catalog(text)).source)
      .contains("fun 画面()")
    assertThat(
        ScreenGenerator.generate(
          ScreenDocument("Screen", ScreenNode(text.canonicalId)),
          catalog(text),
          packageName = "généré.écran",
        )
      )
      .isInstanceOf(ScreenGenerator.Result.Emitted::class.java)
  }

  @Test
  fun `a catalog older than the current schema is refused outright`() {
    // Not "refused when it carries markers": a schema-1 record also cannot say whether a component
    // needs a context receiver, and every field added since would need its own exception here. One
    // rule, and a stale catalog is regenerated rather than squinted at.
    val plain = component("Plain", "com.example.Plain", emptyList())
    val legacy =
      ComponentRecordFile(
        schemaVersion = COMPONENT_RECORD_OPT_IN_MECHANISM_SCHEMA - 1,
        module = "app",
        variant = "debug",
        components = listOf(plain),
      )

    val reasons =
      (ScreenGenerator.generate(ScreenDocument("Screen", ScreenNode(plain.canonicalId)), legacy)
          as ScreenGenerator.Result.Refused)
        .reasons

    assertThat(reasons).hasSize(1)
    assertThat(reasons.single()).contains("Re-run discovery")
  }

  @Test
  fun `a conflicted non-slot parameter reports its children once, not twice`() {
    // Both the slot-validation loop and the argument branch can reach these children. Walking them
    // in both duplicates every reason and doubles the work per conflicted level.
    val labelled =
      component(
        "Labelled",
        "com.example.Labelled",
        listOf(TargetParameter("label", "String", typeFqn = "kotlin.String")),
      )
    val screen =
      ScreenDocument(
        "Screen",
        ScreenNode(
          labelled.canonicalId,
          arguments = mapOf("label" to ScreenValue.Text("hi")),
          slots = mapOf("label" to listOf(ScreenNode("app/com.example.GoneKt.Gone"))),
        ),
      )

    val reasons = refusal(screen, catalog(labelled))

    assertThat(reasons.filter { it.contains("Gone") }).hasSize(1)
  }

  @Test
  fun `a string too large for the constant pool is refused`() {
    val labelled =
      component(
        "Labelled",
        "com.example.Labelled",
        listOf(TargetParameter("label", "String", typeFqn = "kotlin.String")),
      )
    fun screen(value: String) =
      ScreenDocument(
        "Screen",
        ScreenNode(labelled.canonicalId, arguments = mapOf("label" to ScreenValue.Text(value))),
      )

    // A JVM string constant is length-prefixed with an unsigned short, so 65536 bytes cannot be a
    // literal. The backend, not this generator, would have been the one to say so.
    assertThat(refusal(screen("a".repeat(65536)), catalog(labelled)).first()).contains("65535")
    // Measured in modified UTF-8, not characters: a 3-byte character reaches the limit in a third
    // of the count.
    assertThat(refusal(screen("\u4e2d".repeat(21846)), catalog(labelled)).first()).contains("65535")
    assertThat(emitted(screen("a".repeat(65535)), catalog(labelled)).source).contains("label = \"")
  }

  @Test
  fun `a component named Composable is qualified, so it cannot collide with the wrapper's import`() {
    // The generated file always imports `androidx.compose.runtime.Composable` for its own
    // `@Composable`
    // annotation. Importing a catalog component of the same simple name alongside it makes
    // `Composable()` ambiguous between the two.
    val clash =
      component("Composable", "com.example.Composable", emptyList()).let {
        it.copy(
          canonicalId = "app/com.example.ComposableKt.Composable",
          symbol = it.symbol.copy(callable = "com.example.Composable"),
        )
      }

    val source =
      emitted(ScreenDocument("Screen", ScreenNode(clash.canonicalId)), catalog(clash)).source

    assertThat(source).contains("com.example.Composable()")
    assertThat(source).doesNotContain("import com.example.Composable")
    // The wrapper's own import is untouched.
    assertThat(source).contains("import androidx.compose.runtime.Composable")
  }

  @Test
  fun `a receiver slot imports its children by simple name`() {
    // This used to assert the opposite: a slot with a receiver qualified everything beneath it,
    // because an import supposedly could not reach inside one. It can — `import …material3.Text`
    // then `Column { Text("hi") }` is ordinary Compose, and an implicit receiver adds names to the
    // scope rather than removing the imported one. Since the flag was sticky, one scoped slot near
    // the root qualified an entire screen. A nullable slot is kept as the case because it is the
    // one whose receiver is invisible in the rendered type, so it proves nesting is not consulted
    // at all rather than merely mis-detected.
    val optional =
      component(
        "Optional",
        "com.example.Optional",
        listOf(
          TargetParameter(
            "content",
            "(ColumnScope.() -> Unit)?",
            hasDefault = true,
            composableSlot = true,
            composableSlotReceiver = "androidx.compose.foundation.layout.ColumnScope",
          )
        ),
      )
    val screen =
      ScreenDocument(
        "Screen",
        ScreenNode(
          optional.canonicalId,
          slots =
            mapOf(
              "content" to
                listOf(
                  ScreenNode(text.canonicalId, arguments = mapOf("text" to ScreenValue.Text("Hi")))
                )
            ),
        ),
      )

    val source = emitted(screen, catalog(optional, text)).source

    assertThat(source).contains("import androidx.compose.material3.Text")
    assertThat(source).contains("Text(text = \"Hi\")")
    assertThat(source).doesNotContain("androidx.compose.material3.Text(text = \"Hi\")")
  }

  @Test
  fun `an AndroidX marker is emitted under the AndroidX annotation, not kotlin OptIn`() {
    // Not interchangeable: `kotlin.OptIn` rejects a marker declared with
    // `androidx.annotation.RequiresOptIn` outright, and the AndroidX annotation takes an array
    // under a named `markerClass`. Emitting one for the other is source the compiler refuses.
    val guarded =
      component(
        "Guarded",
        "com.example.Guarded",
        emptyList(),
        requiredOptIns = listOf("com.example.KotlinApi", "androidx.camera.core.ExperimentalLens"),
        androidxOptIns = listOf("androidx.camera.core.ExperimentalLens"),
      )

    val emitted =
      emitted(ScreenDocument("Screen", ScreenNode(guarded.canonicalId)), catalog(guarded))

    assertThat(emitted.source).contains("@kotlin.OptIn(com.example.KotlinApi::class)")
    assertThat(emitted.source)
      .contains(
        "@androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalLens::class])"
      )
    // The AndroidX marker is not also written under `kotlin.OptIn`, which would reject it.
    assertThat(emitted.source).doesNotContain("@kotlin.OptIn(androidx.camera")
    assertThat(emitted.source).doesNotContain("ExperimentalLens::class, ")
    // Both are still reported to the caller: the split is about which annotation carries them.
    assertThat(emitted.requiredOptIns)
      .containsExactly("com.example.KotlinApi", "androidx.camera.core.ExperimentalLens")
  }

  @Test
  fun `a parameter set as both a value and a slot is reported, and its children too`() {
    val holder =
      card.copy(
        parameters =
          listOf(TargetParameter("content", "ColumnScope.() -> Unit", composableSlot = true))
      )
    val screen =
      ScreenDocument(
        "Screen",
        ScreenNode(
          holder.canonicalId,
          arguments = mapOf("content" to ScreenValue.Text("oops")),
          slots = mapOf("content" to listOf(ScreenNode("app/com.example.GoneKt.Gone"))),
        ),
      )

    val reasons = refusal(screen, catalog(holder))

    assertThat(reasons.joinToString()).contains("both a value and a slot")
    assertThat(reasons.joinToString()).contains("Gone")
  }

  @Test
  fun `children of an unresolved node are still reported`() {
    // A catalog that dropped a whole subtree should name every node it can no longer place.
    val holder =
      card.copy(
        parameters =
          listOf(TargetParameter("content", "ColumnScope.() -> Unit", composableSlot = true))
      )
    val screen =
      ScreenDocument(
        "Screen",
        ScreenNode(
          holder.canonicalId,
          slots =
            mapOf(
              "content" to
                listOf(
                  ScreenNode(
                    "app/com.example.GoneKt.Gone",
                    slots =
                      mapOf("content" to listOf(ScreenNode("app/com.example.AlsoGoneKt.AlsoGone"))),
                  )
                )
            ),
        ),
      )

    val reasons = refusal(screen, catalog(holder))

    assertThat(reasons).hasSize(2)
    assertThat(reasons.joinToString()).contains("AlsoGone")
  }

  private val lazyListScope = "androidx.compose.foundation.lazy.LazyListScope"

  private val columnScope = "androidx.compose.foundation.layout.ColumnScope"

  /**
   * A container whose slot is a **DSL** rather than a composable region.
   *
   * `LazyColumn(content: LazyListScope.() -> Unit)` is the shape that had no expression at all: a
   * bare `{ Text(…) }` satisfies the lambda's type and does not compile, because the children of a
   * lazy list are declared with `item { … }` and `Text` is not a member of `LazyListScope`. That is
   * why every lazy container was left out of the m3 catalog's component record rather than guessed
   * at (compose-preview-server#394).
   */
  private val lazyColumn =
    component(
      "LazyColumn",
      "androidx.compose.foundation.lazy.LazyColumn",
      listOf(
        // Not `composableSlot`, which is the half that made this a record nobody could write:
        // `LazyColumn`'s `content` is a plain receiver lambda, so the whole container used to
        // refuse as "a parameter, not a @Composable slot".
        TargetParameter("content", "LazyListScope.() -> Unit", scopeDslReceiver = lazyListScope)
      ),
    )

  /** `Card`, but with the receiver its slot really has, which [card] leaves unset. */
  private val scopedCard =
    component(
      "ScopedCard",
      "androidx.compose.material3.ScopedCard",
      listOf(
        TargetParameter(
          "content",
          "ColumnScope.() -> Unit",
          composableSlot = true,
          composableSlotReceiver = columnScope,
        )
      ),
    )

  private fun textNode(value: String) =
    ScreenNode(text.canonicalId, arguments = mapOf("text" to ScreenValue.Text(value)))

  private fun list(item: SlotItem, vararg children: ScreenNode) =
    ScreenDocument(
      name = "HomeScreen",
      root =
        ScreenNode(
          componentId = lazyColumn.canonicalId,
          slots = if (children.isEmpty()) emptyMap() else mapOf("content" to children.toList()),
          slotItems = mapOf("content" to item),
        ),
    )

  @Test
  fun `a DSL slot declares each child through its receiver's member`() {
    val source =
      emitted(
          list(SlotItem("item", lazyListScope), textNode("a"), textNode("b")),
          catalog(lazyColumn, text),
        )
        .source

    assertThat(source).contains("content = {")
    // One wrapper per child, not one around the lot: `item { a; b }` is a single list entry
    // holding two composables, which is a different screen from the two-row list designed.
    assertThat(source.split("item {")).hasSize(3)
    assertThat(source).contains("""Text(text = "a")""")
    assertThat(source).contains("""Text(text = "b")""")
    // `item` is a member supplied by the lambda's receiver. Importing it is the one thing that
    // turns a compiling file into a file that fails on its import line.
    assertThat(source).doesNotContain("import androidx.compose.foundation.lazy.LazyListScope")
    assertThat(source).doesNotContain("import androidx.compose.foundation.lazy.item")
  }

  @Test
  fun `a slot item may carry arguments, and takes no parentheses without them`() {
    val keyed = SlotItem("item", lazyListScope, named = mapOf("key" to ScreenValue.Text("row")))
    assertThat(emitted(list(keyed, textNode("a")), catalog(lazyColumn, text)).source)
      .contains("""item(key = "row") {""")
    assertThat(
        emitted(list(SlotItem("item", lazyListScope), textNode("a")), catalog(lazyColumn, text))
          .source
      )
      .doesNotContain("item()")
  }

  @Test
  fun `a slot item claiming the wrong scope is refused, naming both`() {
    // The check that makes the claim worth making, and the same one a scoped `ChainLink` gets.
    // `item` is in scope inside a `LazyListScope` lambda and nowhere else, so a document that says
    // it about a `ColumnScope` slot is an unresolved reference in a file this generator would
    // otherwise have called compilable.
    val document =
      ScreenDocument(
        name = "HomeScreen",
        root =
          ScreenNode(
            componentId = scopedCard.canonicalId,
            slots = mapOf("content" to listOf(textNode("a"))),
            slotItems = mapOf("content" to SlotItem("item", lazyListScope)),
          ),
      )

    assertThat(refusal(document, catalog(scopedCard, text)))
      .contains(
        "`ScopedCard`.`content` wraps its children in `item`, which is declared on " +
          "`$lazyListScope`, and this slot composes under `$columnScope`"
      )
  }

  @Test
  fun `a slot item on a slot with no receiver is refused too`() {
    val document =
      ScreenDocument(
        name = "HomeScreen",
        root =
          ScreenNode(
            componentId = card.canonicalId,
            slots = mapOf("content" to listOf(textNode("a"))),
            slotItems = mapOf("content" to SlotItem("item", lazyListScope)),
          ),
      )

    assertThat(refusal(document, catalog(card, text)))
      .contains(
        "`Card`.`content` wraps its children in `item`, which is declared on `$lazyListScope`, " +
          "and this slot composes under no receiver"
      )
  }

  @Test
  fun `a refused slot item still names what was inside it`() {
    // The fifth branch that rejects a node and would otherwise drop its subtree. A document whose
    // wrapper is stale is exactly the document most likely to be stale further down, and one
    // export per problem is what the refusal list exists to avoid.
    val document =
      ScreenDocument(
        name = "HomeScreen",
        root =
          ScreenNode(
            componentId = card.canonicalId,
            slots = mapOf("content" to listOf(ScreenNode("app/Gone.Gone"))),
            slotItems = mapOf("content" to SlotItem("item", lazyListScope)),
          ),
      )

    assertThat(refusal(document, catalog(card)))
      .contains("no component `app/Gone.Gone` in this catalog")
  }

  @Test
  fun `a slot item for a slot with no children names nothing and says so`() {
    assertThat(refusal(list(SlotItem("item", lazyListScope)), catalog(lazyColumn)))
      .contains("`LazyColumn`.`content` wraps its children in a slot item and has no children")
  }

  @Test
  fun `a scope DSL slot with no slot item stays refused, rather than composing into it`() {
    // The failure this whole shape exists to keep: `{ Text(…) }` type-checks against
    // `LazyListScope.() -> Unit` and does not compile, because `Text` is not a member of
    // `LazyListScope`. Without a wrapper the generator has nothing to write the children as, so it
    // says so instead of emitting a file it would wrongly have called compilable.
    val document =
      ScreenDocument(
        name = "HomeScreen",
        root =
          ScreenNode(
            componentId = lazyColumn.canonicalId,
            slots = mapOf("content" to listOf(textNode("a"))),
          ),
      )

    assertThat(refusal(document, catalog(lazyColumn, text)))
      .contains("`LazyColumn`.`content` is a parameter, not a @Composable slot")
  }

  @Test
  fun `a slot item whose member is not an identifier is refused rather than written`() {
    // A `SlotItem` arrives over the wire like everything else in a `ScreenDocument`, and its
    // member is emitted as a name. One holding a dot escapes nothing — `` `it.em` `` is not a
    // backticked identifier, it is two — so it goes through the same check every other name does.
    assertThat(
        refusal(list(SlotItem("it.em", lazyListScope), textNode("a")), catalog(lazyColumn, text))
      )
      .contains(
        "`LazyColumn`.`content` names `it.em`, which cannot be written as a Kotlin identifier"
      )
  }
}
