package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import io.github.classgraph.ClassGraph
import io.github.classgraph.MethodInfo
import org.junit.Test

/**
 * Reads the real Kotlin signature of the [sampleComponent] fixtures from `@kotlin.Metadata` (names,
 * rendered types, defaults), scanning this module's own compiled classes end to end.
 */
class ComposableSignatureTest {

  /**
   * Reads [simpleName]'s parameters inside the open scan: `ClassInfo.resource` is only valid while
   * it's open.
   */
  private fun parametersOf(simpleName: String): List<TargetParameter> {
    ClassGraph()
      .enableClassInfo()
      .enableMethodInfo()
      .acceptPackages("ee.schimke.composeai.discovery")
      .scan()
      .use { scan ->
        val classInfo =
          scan.getClassInfo("ee.schimke.composeai.discovery.SignatureFixturesKt")
            ?: error("fixture facade class not found")
        // A defaulted function also emits a synthetic `<name>$default`; match the real one by name.
        val m: MethodInfo = classInfo.methodInfo.first { it.name == simpleName }
        return ComposableSignature.parametersOf(classInfo, m)
      }
  }

  @Test
  fun `a type alias is recorded as the class it expands to, not as the alias`() {
    // Metadata expands type aliases, so the classifier is `kotlin.String`, not a fabricated
    // `AliasedLabel`.
    val parameter = parametersOf("aliasedComponent").single()
    assertThat(parameter.typeFqn).isEqualTo("kotlin.String")
    assertThat(ComponentSnippets.qualifiedTypeOf(parameter)).isEqualTo("kotlin.String")
  }

  @Test
  fun `reads parameter names, types and defaults from metadata`() {
    val params = parametersOf("sampleComponent")

    assertThat(params.map { it.name })
      .containsExactly("state", "count", "labels", "onClick", "note")
      .inOrder()
    assertThat(params.map { it.type })
      .containsExactly("String", "Int", "List<String>", "() -> Unit", "String?")
      .inOrder()
    assertThat(params.first { it.name == "count" }.hasDefault).isTrue()
    assertThat(params.first { it.name == "note" }.hasDefault).isTrue()
    assertThat(params.first { it.name == "state" }.hasDefault).isFalse()
    assertThat(params.first { it.name == "labels" }.hasDefault).isFalse()
    // An ordinary callback is not child content merely because it is function typed.
    assertThat(params.first { it.name == "onClick" }.composableSlot).isFalse()
    assertThat(params.first { it.name == "state" }.composableSlot).isFalse()
  }

  /** As [parametersOf], for the opt-in markers a caller of the fixture has to apply. */
  private fun optInsOf(simpleName: String): List<String> {
    ClassGraph()
      .enableClassInfo()
      .enableMethodInfo()
      // The producer enables this too; without it no annotation is visible and every method would
      // trivially report no markers, which is a green test that proves nothing.
      .enableAnnotationInfo()
      .acceptPackages("ee.schimke.composeai.discovery")
      .scan()
      .use { scan ->
        val classInfo =
          scan.getClassInfo("ee.schimke.composeai.discovery.SignatureFixturesKt")
            ?: error("fixture facade class not found")
        val m: MethodInfo = classInfo.methodInfo.first { it.name == simpleName }
        return ComposableSignature.signatureOf(classInfo, m)?.requiredOptIns
          ?: error("no signature for $simpleName")
      }
  }

  @Test
  fun `only markers written on the method are reported, not their meta-annotations`() {
    // `@ExperimentalFixtureApi` is a real requirement; `@FixtureInferredTarget` (guarded by
    // `@InternalFixtureApi`, like Compose's inferred-target marker) must not leak
    // `InternalFixtureApi` or `RequiresOptIn` through the annotation closure.
    assertThat(optInsOf("optInComponent"))
      .containsExactly("ee.schimke.composeai.discovery.ExperimentalFixtureApi")
  }

  @Test
  fun `a marker the author wrote on their own component survives`() {
    // The other half, and why a name denylist was the wrong fix: `InternalFixtureApi` is noise as a
    // meta-annotation of a compiler marker and a real requirement when an author applies it.
    assertThat(optInsOf("deliberatelyInternalComponent"))
      .containsExactly("ee.schimke.composeai.discovery.InternalFixtureApi")
  }

  @Test
  fun `a nested marker is recorded in source notation, not by its binary name`() {
    // ClassGraph gives `MarkerHolder$NestedApi`; the source name is rebuilt from the nesting chain,
    // since blanket `$` → `.` would corrupt backticked names.
    assertThat(optInsOf("nestedMarkerComponent"))
      .containsExactly("ee.schimke.composeai.discovery.MarkerHolder.NestedApi")
  }

  @Test
  fun `a top-level marker whose name contains a dollar keeps it`() {
    // The other half of the nesting question, and why replacing every `$` was wrong: this marker
    // has no outer class, so its name is already what source spells.
    assertThat(optInsOf("dollarMarkerComponent"))
      .containsExactly("ee.schimke.composeai.discovery.Api\$Experimental")
  }

  /** As [parametersOf], for the knob view of the same fixtures. */
  private fun knobsOf(simpleName: String): List<PreviewKnob> {
    ClassGraph()
      .enableClassInfo()
      .enableMethodInfo()
      .acceptPackages("ee.schimke.composeai.discovery")
      .scan()
      .use { scan ->
        val classInfo =
          scan.getClassInfo("ee.schimke.composeai.discovery.SignatureFixturesKt")
            ?: error("fixture facade class not found")
        val m: MethodInfo = classInfo.methodInfo.first { it.name == simpleName }
        return ComposableSignature.knobsOf(classInfo, m, scan)
      }
  }

  @Test
  fun `an enum parameter is a knob whose options are its constants, in declaration order`() {
    val knobs = knobsOf("enumKnobComponent").associateBy { it.name }

    val emphasis = knobs.getValue("emphasis")
    assertThat(emphasis.type).isEqualTo(PreviewKnobType.ENUM)
    // The whole point of the kind: a viewer can enumerate these and draw a picker. Declaration
    // order, because that is the order the author wrote and the order a reader expects to browse.
    assertThat(emphasis.options).containsExactly("Filled", "Tonal", "Outlined").inOrder()
    // No default asserted: without the Compose compiler the fixture uses an ordinary `$default`
    // bridge. Enum defaults via `GETSTATIC` are covered in `PreviewKnobDefaultsTest`. The sibling
    // knob is unaffected.
    assertThat(knobs.getValue("label").type).isEqualTo(PreviewKnobType.STRING)
    assertThat(knobs.getValue("label").options).isEmpty()
  }

  @Test
  fun `an aliased constant reports the text it declares, not its own name`() {
    val knob = knobsOf("aliasedKnobComponent").single()

    // Migrating off `previewOverrideChoice` keeps existing seed texts; `extra-large` isn't a legal
    // identifier, so the constant declares it.
    assertThat(knob.options).containsExactly("default", "large", "extra-large").inOrder()
  }

  @Test
  fun `an aliased constant carries both its name and its text, which is what translates a default`() {
    // Defaults are read as the constant name while options are declared texts, so both halves are
    // needed to translate. Asserted on the pairs, since no default is recoverable without the
    // Compose compiler.
    val constants =
      ClassGraph().enableClassInfo().acceptPackages("ee.schimke.composeai.discovery").scan().use {
        scan ->
        PreviewKnobDefaults.enumConstantsOf(
          scan.getClassInfo("ee.schimke.composeai.discovery.KitIconSize")
            ?: error("enum fixture not found")
        )
      }

    assertThat(constants)
      .containsExactly(
        "Default" to "default",
        "Large" to "large",
        "ExtraLarge" to "extra-large",
      )
      .inOrder()
  }

  @Test
  fun `two constants claiming one seed text disable the knob rather than binding either`() {
    // An ambiguous seed isn't seedable, so the enum isn't a knob; the sibling still is.
    assertThat(knobsOf("ambiguousKnobComponent").map { it.name }).containsExactly("tag")
  }

  @Test
  fun `an enum parameter is not a knob at all when the enum cannot be resolved`() {
    // Without a scan the constants can't be read, so it's "not seedable" rather than an empty
    // picker.
    ClassGraph()
      .enableClassInfo()
      .enableMethodInfo()
      .acceptPackages("ee.schimke.composeai.discovery")
      .scan()
      .use { scan ->
        val classInfo =
          scan.getClassInfo("ee.schimke.composeai.discovery.SignatureFixturesKt")
            ?: error("fixture facade class not found")
        val m = classInfo.methodInfo.first { it.name == "enumKnobComponent" }
        val knobs = ComposableSignature.knobsOf(classInfo, m, scanResult = null)
        assertThat(knobs.map { it.name }).containsExactly("label")
      }
  }

  @Test
  fun `every constructible defaulted parameter becomes a knob, in declaration order`() {
    val knobs = knobsOf("knobComponent")

    assertThat(knobs.map { it.name })
      .containsExactly("label", "enabled", "count", "big", "ratio", "precise")
      .inOrder()
    assertThat(knobs.map { it.type })
      .containsExactly(
        PreviewKnobType.STRING,
        PreviewKnobType.BOOLEAN,
        PreviewKnobType.INT,
        PreviewKnobType.LONG,
        PreviewKnobType.FLOAT,
        PreviewKnobType.DOUBLE,
      )
      .inOrder()
    assertThat(knobs.map { it.index }).containsExactly(0, 1, 2, 3, 4, 5).inOrder()
  }

  @Test
  fun `a knob index is its position in the full parameter list, not among the knobs`() {
    // `modifier` is defaulted and renderable but not seedable, so it is not a knob — and `count`
    // keeps index 1, which is where the renderer has to place its argument.
    val knobs = knobsOf("mixedKnobComponent")

    assertThat(knobs).hasSize(1)
    assertThat(knobs.single().name).isEqualTo("count")
    assertThat(knobs.single().index).isEqualTo(1)
  }

  @Test
  fun `a nullable function type is parenthesised so the question mark cannot read as the return`() {
    // `((Boolean) -> Unit)?` must not render as `(Boolean) -> Unit?` (material3's `Checkbox`,
    // `RadioButton`, `Switch`).
    val params = parametersOf("nullableCallbackComponent").associate { it.name to it.type }

    assertThat(params["onCheckedChange"]).isEqualTo("((Boolean) -> Unit)?")
    assertThat(params["onClick"]).isEqualTo("(() -> Unit)?")
  }

  @Test
  fun `a nullable parameter is not a knob`() {
    // `null` means "use the author default", so a nullable knob can't be seeded null.
    assertThat(knobsOf("nullableKnobComponent").map { it.name }).containsExactly("enabled")
  }

  @Test
  fun `a function with a non-defaulted parameter declares no knobs`() {
    // `state` has no default, so the preview is unrenderable in this format and reports no knobs.
    assertThat(knobsOf("sampleComponent")).isEmpty()
  }

  @Test
  fun `a no-parameter function declares no knobs`() {
    assertThat(knobsOf("noParams")).isEmpty()
  }

  @Test
  fun `a no-parameter function yields an empty list`() {
    assertThat(parametersOf("noParams")).isEmpty()
  }

  @Test
  fun `renders a scoped slot as an extension function type`() {
    val content = parametersOf("scopedSlotComponent").single()

    assertThat(content.name).isEqualTo("content")
    assertThat(content.type).isEqualTo("TestRowScope.(Int) -> Unit")
    assertThat(content.composableSlot).isTrue()
    // A composable slot isn't a scope DSL; conflating them would emit `item { … }` in a `RowScope`.
    assertThat(content.scopeDslReceiver).isNull()
  }

  @Test
  fun `a non-composable receiver lambda records its scope, which is what a lazy list needs`() {
    // The `LazyColumn` shape: not a composable slot, so the receiver is what says children are
    // `item { … }`.
    val content = parametersOf("scopeDslComponent").single()

    assertThat(content.composableSlot).isFalse()
    assertThat(content.scopeDslReceiver).isEqualTo("ee.schimke.composeai.discovery.TestListScope")
  }

  @Test
  fun `a function-typed parameter records what its lambda returns`() {
    // `typeFqn` cannot answer this: every zero-argument function type in the library is a
    // `kotlin.Function0`, so a value checked against it alone is checked against almost nothing.
    val progress = parametersOf("valueReturningLambdaComponent").single()

    assertThat(progress.typeFqn).isEqualTo("kotlin.Function0")
    assertThat(progress.lambdaReturnTypeFqn).isEqualTo("kotlin.Float")
  }

  @Test
  fun `the recorded return type is the last type argument, not the first`() {
    // `(Int) -> Float` is a `Function1<Int, Float>`, and reading the wrong end would hold a
    // lambda's body to the type of the argument it was handed.
    assertThat(parametersOf("argumentTakingLambdaComponent").single().lambdaReturnTypeFqn)
      .isEqualTo("kotlin.Float")
  }

  @Test
  fun `a parameter that is not a function type records no lambda return`() {
    assertThat(parametersOf("sampleComponent").first().lambdaReturnTypeFqn).isNull()
  }

  @Test
  fun `an ordinary callback is not a scope DSL`() {
    // The signal is the receiver, not the lambda. `(String) -> Unit` has no receiver, so nothing
    // is in scope inside it and there is no member a child could be wrapped in.
    assertThat(parametersOf("callbackComponent").single().scopeDslReceiver).isNull()
  }

  // --- constructibility of a required parameter's type (issue #5067) ----------------------------

  /**
   * `isNoArgConstructible` asked of the type directly (a public fixture function can't take an
   * `internal` type).
   */
  private fun constructible(simpleName: String): Boolean {
    ClassGraph()
      .enableClassInfo()
      .enableMethodInfo()
      .enableAnnotationInfo()
      .acceptPackages("ee.schimke.composeai.discovery")
      .scan()
      .use { scan ->
        return ComposableSignature.isNoArgConstructible(
          scan,
          "ee.schimke.composeai.discovery.$simpleName",
        )
      }
  }

  @Test
  fun `an all-defaulted constructor is constructible, which only source shows`() {
    // The JVM sees only the `DefaultConstructorMarker` bridge, yet `DefaultedState()` compiles;
    // metadata's `declaresDefaultValue` shows it.
    assertThat(constructible("DefaultedState")).isTrue()
  }

  @Test
  fun `a constructor with no parameters is constructible`() {
    assertThat(constructible("EmptyState")).isTrue()
  }

  @Test
  fun `a required constructor parameter refuses, which is what caps the depth at one`() {
    // No recursion: a type whose own constructor needs a value is simply not constructible, so
    // nothing ever descends into building one.
    assertThat(constructible("RequiredArgState")).isFalse()
  }

  @Test
  fun `an abstract class and an object are not constructible`() {
    assertThat(constructible("AbstractState")).isFalse()
    assertThat(constructible("SingletonState")).isFalse()
  }

  @Test
  fun `a generic, value or inner class is not constructible`() {
    assertThat(constructible("GenericState")).isFalse()
    assertThat(constructible("ValueState")).isFalse()
    assertThat(constructible("OuterHost\$InnerState")).isFalse()
  }

  @Test
  fun `a non-public class is not constructible from a generated file`() {
    assertThat(constructible("InternalState")).isFalse()
  }

  @Test
  fun `an opt-in-gated type is not constructible, because its marker cannot travel`() {
    // The callable's own markers already ride on the record; a constructed type's do not, so
    // emitting `GatedState()` would produce a file the compiler rejects for a missing @OptIn.
    assertThat(constructible("GatedState")).isFalse()
  }

  @Test
  fun `a type that is not on the scanned classpath claims nothing`() {
    assertThat(constructible("NoSuchStateAnywhere")).isFalse()
  }

  // --- the `remember…` factory convention -------------------------------------------------------

  /** `noArgFactoryFor` over a real scan, asked of the TYPE for the same reason as above. */
  private fun factoryFor(simpleName: String): String? {
    ClassGraph()
      .enableClassInfo()
      .enableMethodInfo()
      .enableAnnotationInfo()
      .acceptPackages("ee.schimke.composeai.discovery")
      .scan()
      .use { scan ->
        return ComposableSignature.noArgFactoryFor(
          scan,
          "ee.schimke.composeai.discovery.$simpleName",
        )
      }
  }

  @Test
  fun `a composable, fully defaulted factory beside the type resolves to its callable`() {
    // The whole claim: the callable came off the classpath. Nothing spelled `remember` + the type
    // name and hoped — the scan found this function, in this package, returning this type.
    assertThat(factoryFor("DefaultedState"))
      .isEqualTo("ee.schimke.composeai.discovery.rememberDefaultedState")
  }

  @Test
  fun `a type whose package ships no factory resolves to none`() {
    assertThat(factoryFor("FactorylessState")).isNull()
  }

  @Test
  fun `a factory that is not composable is not the convention`() {
    // Named and shaped exactly right. A resolver matching on the name would take it.
    assertThat(factoryFor("PlainFactoryState")).isNull()
  }

  @Test
  fun `a factory with a required parameter refuses, since the point is a call with none`() {
    assertThat(factoryFor("RequiredFactoryState")).isNull()
  }

  @Test
  fun `a same-named factory returning another type is a collision, not a factory`() {
    assertThat(factoryFor("MismatchedFactoryState")).isNull()
  }

  @Test
  fun `an opt-in-gated factory refuses, because its marker cannot travel either`() {
    assertThat(factoryFor("GatedFactoryState")).isNull()
  }

  @Test
  fun `a factory whose JVM name is mangled still resolves, under the name metadata carries`() {
    // The method name is mangled by an inline value class parameter; lookup must use the JVM name
    // while printing the source name (as with `rememberTextFieldState`).
    assertThat(factoryFor("MangledFactoryState"))
      .isEqualTo("ee.schimke.composeai.discovery.rememberMangledFactoryState")
  }

  @Test
  fun `a type that is not on the scanned classpath has no factory`() {
    assertThat(factoryFor("NoSuchStateAnywhere")).isNull()
  }

  /** Resolve a fixture function's single parameter through the full `signatureOf` path. */
  private fun parameterWithScan(simpleName: String): TargetParameter {
    ClassGraph()
      .enableClassInfo()
      .enableMethodInfo()
      .enableAnnotationInfo()
      .acceptPackages("ee.schimke.composeai.discovery")
      .scan()
      .use { scan ->
        val classInfo =
          scan.getClassInfo("ee.schimke.composeai.discovery.SignatureFixturesKt")
            ?: error("fixture facade class not found")
        val m: MethodInfo = classInfo.methodInfo.first { it.name == simpleName }
        val signature =
          ComposableSignature.signatureOf(classInfo, m, scan) ?: error("signature unreadable")
        return signature.parameters.single()
      }
  }

  @Test
  fun `a required parameter carries the flag, with the qualified name a call site imports`() {
    val parameter = parameterWithScan("defaultedStateComponent")

    assertThat(parameter.noArgConstructible).isTrue()
    assertThat(parameter.typeFqn).isEqualTo("ee.schimke.composeai.discovery.DefaultedState")
  }

  @Test
  fun `a required parameter also carries the factory its package declares`() {
    // Both are recorded; `ComponentSnippets` chooses (preferring the factory), so the record
    // needn't change if that preference does.
    val parameter = parameterWithScan("factoryStateComponent")

    assertThat(parameter.noArgConstructible).isTrue()
    assertThat(parameter.noArgFactory)
      .isEqualTo("ee.schimke.composeai.discovery.rememberDefaultedState")
  }

  @Test
  fun `a defaulted parameter is not asked about the factory either`() {
    assertThat(parameterWithScan("defaultedParameterComponent").noArgFactory).isNull()
  }

  @Test
  fun `a defaulted parameter is not asked about, since the call omits it`() {
    assertThat(parameterWithScan("defaultedParameterComponent").noArgConstructible).isFalse()
  }

  @Test
  fun `without a scan nothing is claimed, so an older caller behaves exactly as before`() {
    // Annotation info is on for opt-in markers; what's withheld is the scan argument
    // constructibility needs.
    ClassGraph()
      .enableClassInfo()
      .enableMethodInfo()
      .enableAnnotationInfo()
      .acceptPackages("ee.schimke.composeai.discovery")
      .scan()
      .use { scan ->
        val classInfo =
          scan.getClassInfo("ee.schimke.composeai.discovery.SignatureFixturesKt")
            ?: error("fixture facade class not found")
        val m = classInfo.methodInfo.first { it.name == "defaultedStateComponent" }
        val signature = ComposableSignature.signatureOf(classInfo, m) ?: error("unreadable")

        assertThat(signature.parameters.single().noArgConstructible).isFalse()
      }
  }
}
