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
 * The published policy schema (`scripts/design-artifacts/ui-builder.policy.schema.json`) and
 * `UiBuilderPolicy` must agree, checked both ways on SERIAL names: a field the schema forbids (it
 * is `additionalProperties: false`) makes catalogs invalid, and a schema key no field reads is
 * something nothing consumes. `components` and builtin `traits` / `modifierCapabilities` each
 * drifted this way unnoticed.
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
    // Every role enum, found by walking, must list the same roles, so an enum added later (as the
    // template keys were, missing `container`) is covered automatically.
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
   * Every schema `enum` naming a structural role, by JSON pointer; `screen-root` marks them, as
   * it's in every role enum and no other.
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
