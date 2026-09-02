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
}
