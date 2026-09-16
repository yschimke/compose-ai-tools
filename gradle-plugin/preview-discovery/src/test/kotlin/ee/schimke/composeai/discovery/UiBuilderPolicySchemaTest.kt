package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

  @Test
  fun `every role enum in the schema is the role set, and there are three of them`() {
    // `container` was added to the builtin's `role` and to a slot's, and MISSED on the template
    // keys — so a catalog declaring `code.templates.container`, which both the pre-flight and this
    // generator accept, was rejected by the schema an editor validates against. Enumerating the
    // enums instead of naming them is the point: a fourth one added later is covered the day it
    // lands, which is the property the first three did not have.
    val enums = roleEnums(schema, "")
    assertThat(enums.keys)
      .containsExactly(
        "/properties/builtins/additionalProperties/properties/role",
        "/properties/builtins/additionalProperties/properties/slots/additionalProperties/properties/role",
        "/properties/code/properties/templates/propertyNames",
      )
    for ((path, values) in enums) {
      // `previews` and `file` are whole-file templates rather than node roles, so they are legal
      // template KEYS and illegal roles. Everything else must be the role set exactly.
      assertThat(values - setOf("previews", "file"))
        .containsExactlyElementsIn(UI_BUILDER_STRUCTURAL_ROLES)
        .inOrder()
      assertThat(values.containsAll(listOf("previews", "file")))
        .isEqualTo(path.endsWith("propertyNames"))
    }
  }

  /**
   * Every `enum` in the schema that names a structural role, by JSON pointer.
   *
   * Found by walking rather than listed, so an enum added to the schema later is checked without
   * anybody remembering this test exists. `screen-root` is the marker: it is in every role enum and
   * in no other one.
   */
  private fun roleEnums(node: JsonElement, path: String): Map<String, List<String>> =
    when (node) {
      is JsonObject -> {
        val here =
          (node["enum"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }
            ?.takeIf { "screen-root" in it }
        buildMap {
          if (here != null) put(path, here)
          for ((key, value) in node) putAll(roleEnums(value, "$path/$key"))
        }
      }
      is JsonArray ->
        buildMap {
          node.forEachIndexed { index, value -> putAll(roleEnums(value, "$path[$index]")) }
        }
      else -> emptyMap()
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
