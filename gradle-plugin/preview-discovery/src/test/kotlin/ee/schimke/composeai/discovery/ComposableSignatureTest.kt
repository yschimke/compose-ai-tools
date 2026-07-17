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
    // The function-typed parameter is flagged as a slot.
    assertThat(params.first { it.name == "onClick" }.composableSlot).isTrue()
    assertThat(params.first { it.name == "state" }.composableSlot).isFalse()
  }

  @Test
  fun `a no-parameter function yields an empty list`() {
    assertThat(parametersOf("noParams")).isEmpty()
  }
}
