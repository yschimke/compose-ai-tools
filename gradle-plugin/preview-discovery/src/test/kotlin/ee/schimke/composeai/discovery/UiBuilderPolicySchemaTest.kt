package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * The published policy schema and the type a catalog author writes against must agree.
 *
 * They did not, and the way they came apart is the argument for this test.
 * `UiBuilderPolicy.components` — the block the whole "a catalog describes itself" contract runs on,
 * and the one m3-catalog uses for all 26 of its entries — was never added to
 * `scripts/design-artifacts/ui-builder.policy.schema.json`. The schema declares
 * `additionalProperties: false`, so it did not merely fail to describe the block: it **forbade**
 * it. Every catalog using the feature was schema-invalid, and nothing said so, because the
 * JavaScript pre-flight validates by hand and never looks at `components` either.
 *
 * The same drift had already happened once, one level down: a builtin's `traits` and
 * `modifierCapabilities` were read by the consumer, absent from this type, and forbidden by the
 * schema — see `a builtin publishes the traits and modifiers a catalog states`. Fixing an instance
 * twice is what a test is for.
 *
 * Checked in both directions and against the SERIAL names, which are what a JSON file actually
 * carries: a field the schema forbids is a catalog that cannot validate, and a schema key no field
 * reads is a catalog author writing something nothing consumes.
 */
class UiBuilderPolicySchemaTest {

  private val schema: JsonObject by lazy {
    Json.parseToJsonElement(schemaFile().readText()).jsonObject
  }

  @OptIn(ExperimentalSerializationApi::class)
  @Test
  fun `the schema declares every field a component policy serialises`() {
    assertThat(schemaKeys("components"))
      .containsExactlyElementsIn(UiBuilderAuthoredComponent.serializer().descriptor.elementNames)
  }

  @OptIn(ExperimentalSerializationApi::class)
  @Test
  fun `the schema declares every field a builtin serialises`() {
    assertThat(schemaKeys("builtins"))
      .containsExactlyElementsIn(UiBuilderBuiltin.serializer().descriptor.elementNames)
  }

  /** The property names the schema allows under one map-valued top-level block. */
  private fun schemaKeys(block: String): Set<String> =
    schema["properties"]!!
      .jsonObject[block]!!
      .jsonObject["additionalProperties"]!!
      .jsonObject["properties"]!!
      .jsonObject
      .keys

  /**
   * Walked up from the working directory rather than resolved from a property, so the file is found
   * whether the test runs from the module directory or the root.
   */
  private fun schemaFile(): File {
    val relative = "scripts/design-artifacts/ui-builder.policy.schema.json"
    var directory: File? = File(".").absoluteFile
    while (directory != null) {
      val candidate = File(directory, relative)
      if (candidate.isFile) return candidate
      directory = directory.parentFile
    }
    error("could not find $relative from ${File(".").absolutePath}")
  }
}
