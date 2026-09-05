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
    // `ColumnScope` declaring a member `Text` would outrank `import androidx.compose.material3.Text`
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
}
