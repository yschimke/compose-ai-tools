package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import io.github.classgraph.ClassGraph
import io.github.classgraph.MethodInfo
import org.junit.Test

/**
 * Reads the real Kotlin signature of the [sampleComponent] fixture out of its `@kotlin.Metadata`
 * and checks parameter names, rendered types, and default flags — the data a Code Connect call site
 * is built from. Scans this test module's own classes so the fixture's compiled metadata is
 * exercised end to end (no mocking of the metadata blob).
 */
class ComposableSignatureTest {

  /**
   * Resolve the fixture facade's [simpleName] method and read its parameters — all inside the open
   * ClassGraph scan, because `ClassInfo.resource` (which `ComposableSignature` reads the class
   * bytes from) is only valid while the scan is open.
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
    // `typealias AliasedLabel = String` on a parameter. Kotlin's metadata expands aliases, so the
    // classifier is `kotlin.String` and a value claiming `kotlin.String` matches — there is no
    // fabricated `kotlin.AliasedLabel` for a claimed value to be refused against.
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
    // `optInComponent` carries `@ExperimentalFixtureApi` (a real requirement) and
    // `@FixtureInferredTarget` (the shape the Compose compiler stamps on, itself guarded by
    // `@InternalFixtureApi`). Reading ClassGraph's annotation closure rather than the direct
    // annotations reported `InternalFixtureApi` — and `kotlin.RequiresOptIn` itself — telling a
    // caller to opt into internals in order to place a component.
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
    // ClassGraph hands over `MarkerHolder$NestedApi`. The source name is rebuilt from the nesting
    // chain here, at the one place that can see it — an emitter replacing every `$` with `.` would
    // also corrupt a top-level marker whose backticked name legitimately contains one.
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
        return ComposableSignature.knobsOf(classInfo, m)
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
    // `((Boolean) -> Unit)?` rendered as `(Boolean) -> Unit?` says the callback returns `Unit?` and
    // is nullable nowhere — the opposite of the truth. material3 declares `Checkbox`,
    // `RadioButton` and `Switch` exactly this way, so every consumer of `TargetParameter.type` was
    // being handed the wrong reading for three of its most common components.
    val params = parametersOf("nullableCallbackComponent").associate { it.name to it.type }

    assertThat(params["onCheckedChange"]).isEqualTo("((Boolean) -> Unit)?")
    assertThat(params["onClick"]).isEqualTo("(() -> Unit)?")
  }

  @Test
  fun `a nullable parameter is not a knob`() {
    // Passing null is how the renderer says "use the author default", so a knob that can
    // legitimately
    // be null has no way to be seeded null and would silently resolve to its default instead.
    assertThat(knobsOf("nullableKnobComponent").map { it.name }).containsExactly("enabled")
  }

  @Test
  fun `a function with a non-defaulted parameter declares no knobs`() {
    // `sampleComponent` has `state` with no default — an unrenderable shape for the parameter
    // format, since the $default mask cannot supply it. Reporting knobs for it would advertise
    // editable controls on a preview discovery refuses to admit.
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
  }

  // --- constructibility of a required parameter's type (issue #5067) ----------------------------

  /**
   * `isNoArgConstructible` over a real scan of the fixtures. Asked of the TYPE rather than through
   * a component function, because that is the question the clauses are about — and because a
   * fixture function taking an `internal` type could not itself be public, which would change what
   * is being tested.
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
    // The JVM sees `(String, int, int, DefaultConstructorMarker)` and no zero-arg constructor at
    // all; `DefaultedState()` compiles anyway. Reading `declaresDefaultValue` off metadata is what
    // makes that visible — counting JVM parameters would answer no.
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
    // `rememberMangledFactoryState` takes an inline value class, so the emitted method is
    // `rememberMangledFactoryState-…`. The source name is what a call site prints and what
    // metadata records; the mangled one is what the scan can find. Confusing the two is what made
    // the real `rememberTextFieldState` invisible.
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
    // Both travel: the record says what is TRUE of the type, and choosing between them is the
    // generator's job (`ComponentSnippets` prefers the factory). A record that carried only the
    // winner would have to be re-resolved the moment that preference changed.
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
    // Annotation info is enabled because `signatureOf` reads the method's opt-in markers whatever
    // else it is asked for; what this test withholds is the SCAN ARGUMENT, which is the only input
    // constructibility is resolved from.
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
