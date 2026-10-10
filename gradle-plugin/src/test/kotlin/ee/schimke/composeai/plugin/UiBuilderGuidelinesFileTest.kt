package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * The catalog guidelines file: found only beside the chosen policy, and checked before publishing.
 */
class UiBuilderGuidelinesFileTest {
  private fun dir(): File =
    Files.createTempDirectory("ui-builder-guidelines").toFile().also { it.deleteOnExit() }

  private val valid =
    """
    {
      "schema": "compose-ui-builder/catalog-guidelines/v1",
      "catalog": "remote-widgets", "platform": "launcher", "version": 1,
      "frames": [{"kind": "sized", "label": "2x1", "widthDp": 130, "heightDp": 102}],
      "rules": [
        {"id": "launcher.purpose.single-use-case", "kind": "structure", "severity": "warning",
         "guidance": "Focus on one task.", "check": "Does it do one thing?",
         "source": "https://developer.android.com/design/ui/mobile/guides/widgets"}
      ]
    }
    """

  @Test
  fun `it is found beside the policy, and only there`() {
    val module = dir()
    val policy = File(module, "ui-builder.policy.json").apply { writeText("{}") }
    assertThat(UiBuilderGuidelinesFile.besidePolicy(policy)).isNull()

    val guidelines = File(module, UiBuilderGuidelinesFile.FILE_NAME).apply { writeText(valid) }
    assertThat(UiBuilderGuidelinesFile.besidePolicy(policy)).isEqualTo(guidelines)

    // A root-level guidelines file does not attach itself to a module that owns its policy.
    val nested = File(module, "widget-catalog").apply { mkdirs() }
    val nestedPolicy = File(nested, "ui-builder.policy.json").apply { writeText("{}") }
    assertThat(UiBuilderGuidelinesFile.besidePolicy(nestedPolicy)).isNull()
  }

  @Test
  fun `a well-formed file for this catalog has no problems`() {
    assertThat(UiBuilderGuidelinesFile.problems(valid, "remote-widgets")).isEmpty()
  }

  @Test
  fun `a malformed file names everything wrong with it`() {
    val problems =
      UiBuilderGuidelinesFile.problems(
        """
        {"schema": "something-else", "catalog": "wear-m3", "version": "1",
         "rules": [{"id": "r", "kind": "visual", "severity": "info", "guidance": "g",
                    "check": "a statement", "source": "http://example.com"}, {"id": "s"}]}
        """,
        "remote-widgets",
      )
    listOf(
        "`schema`",
        "`catalog` is `wear-m3`",
        "`version` is not an integer",
        "rule r's `check` is not a question",
        "rule r's `source` is not an https URL",
        "rule s has no `guidance`",
      )
      .forEach { expected -> assertThat(problems.any { expected in it }).isTrue() }
    assertThat(UiBuilderGuidelinesFile.problems("[]", "x")).containsExactly("is not a JSON object")
  }

  private val pack =
    """
    {"schema": "compose-ui-builder/catalog-guidelines/v1", "catalog": "general",
     "platform": "any", "version": 1,
     "frames": [{"kind": "sized", "label": "2x1", "widthDp": 130, "heightDp": 102},
                {"kind": "device"}],
     "rules": [
       {"id": "general.all", "kind": "structure", "severity": "info", "guidance": "g",
        "check": "all?", "source": "https://developer.android.com/a"},
       {"id": "general.wear", "platforms": ["wear"], "kind": "structure", "severity": "info",
        "guidance": "g", "check": "wear?", "source": "https://developer.android.com/b"},
       {"id": "general.launcher", "platforms": ["launcher"], "kind": "structure",
        "severity": "info", "guidance": "g", "check": "launcher?",
        "source": "https://developer.android.com/c"},
       {"id": "general.skip", "kind": "structure", "severity": "info", "guidance": "g",
        "check": "skip?", "source": "https://developer.android.com/d"},
       {"id": "launcher.purpose.single-use-case", "kind": "structure", "severity": "info",
        "guidance": "pack", "check": "pack?", "source": "https://developer.android.com/e"}
     ]}
    """
      .toByteArray()

  private val packUrl = "https://raw.githubusercontent.com/o/r/v1/compose-ui.guidelines.json"

  private fun sha256(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
      "%02x".format(it)
    }

  private fun including(sha: String = sha256(pack), extra: String = "") =
    valid.trimEnd().removeSuffix("}") +
      """, "includes": [{"url": "$packUrl", "sha256": "$sha"$extra}]}"""

  @Test
  fun `a file with no includes is published byte for byte`() {
    val flat = UiBuilderGuidelinesFile.flatten(valid) { error("nothing to fetch") }
    assertThat(flat.text).isEqualTo(valid)
    assertThat(flat.problem).isNull()
  }

  @Test
  fun `includes are merged in and removed, so the published file is flat`() {
    val text = including(extra = ""","exclude": ["general.skip"]""")
    assertThat(UiBuilderGuidelinesFile.problems(text, "remote-widgets")).isEmpty()
    val flat =
      UiBuilderGuidelinesFile.flatten(text) { url ->
        check(url == packUrl)
        pack
      }
    assertThat(flat.problem).isNull()
    val root = kotlinx.serialization.json.Json.parseToJsonElement(flat.text).jsonObject
    assertThat(root.keys).doesNotContain("includes")
    val ids =
      root.getValue("rules").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
    // The catalog's own rule replaces the pack's of the same id where it stood; the launcher rule
    // is carried, the wear one and the excluded one are not.
    assertThat(ids)
      .containsExactly("general.all", "general.launcher", "launcher.purpose.single-use-case")
      .inOrder()
    assertThat(
        root
          .getValue("rules")
          .jsonArray
          .last()
          .jsonObject
          .getValue("guidance")
          .jsonPrimitive
          .content
      )
      .isEqualTo("Focus on one task.")
    // The pack's sized frame duplicates the catalog's and is not added twice.
    assertThat(root.getValue("frames").jsonArray).hasSize(2)
    // The flat file is still a valid guidelines file for the catalog.
    assertThat(UiBuilderGuidelinesFile.problems(flat.text, "remote-widgets")).isEmpty()
  }

  @Test
  fun `a pack that does not match its pin is published as written, with the reason`() {
    val text = including(sha = "0".repeat(64))
    val flat = UiBuilderGuidelinesFile.flatten(text) { pack }
    assertThat(flat.text).isEqualTo(text)
    assertThat(flat.problem).contains("does not match its pin")
  }

  @Test
  fun `an include must be an https URL with a sha256`() {
    val problems =
      UiBuilderGuidelinesFile.problems(
        valid.trimEnd().removeSuffix("}") +
          ""","includes": [{"url": "http://x/p.json", "sha256": "abc"}, {"url": "https://x/p.json"}]}""",
        "remote-widgets",
      )
    assertThat(problems)
      .containsExactly(
        "include #0's `url` is not an https URL",
        "include https://x/p.json has no `sha256` (64 lowercase hex digits)",
      )
  }

  @Test
  fun `more includes than a reader takes are not flattened`() {
    val many =
      (1..UiBuilderGuidelinesFile.MAX_INCLUDES + 1).joinToString(",") {
        """{"url": "$packUrl?$it", "sha256": "${sha256(pack)}"}"""
      }
    val text = valid.trimEnd().removeSuffix("}") + """, "includes": [$many]}"""
    val flat = UiBuilderGuidelinesFile.flatten(text) { pack }
    assertThat(flat.text).isEqualTo(text)
    assertThat(flat.problem).contains("more than")
  }

  @Test
  fun `a pack with a malformed rule is not merged in`() {
    val bad =
      String(pack).replace("\"check\": \"all?\"", "\"check\": \"a statement\"").toByteArray()
    val text = including(sha = sha256(bad))
    val flat = UiBuilderGuidelinesFile.flatten(text) { bad }
    assertThat(flat.text).isEqualTo(text)
    assertThat(flat.problem).contains("is not a question")
  }

  @Test
  fun `a verified pack is reused by URL and pin, and a new pin fetches again`() {
    var fetches = 0
    val counting: (String) -> ByteArray = {
      fetches++
      pack
    }
    // A distinct URL keeps this test's cache entries its own.
    val url = "https://raw.githubusercontent.com/o/r/v2/cached.guidelines.json"
    fun withPin(pin: String) =
      valid.trimEnd().removeSuffix("}") + """, "includes": [{"url": "$url", "sha256": "$pin"}]}"""
    assertThat(UiBuilderGuidelinesFile.flatten(withPin(sha256(pack)), counting).problem).isNull()
    assertThat(UiBuilderGuidelinesFile.flatten(withPin(sha256(pack)), counting).problem).isNull()
    assertThat(fetches).isEqualTo(1)
    assertThat(UiBuilderGuidelinesFile.flatten(withPin("1".repeat(64)), counting).problem)
      .contains("does not match its pin")
    assertThat(fetches).isEqualTo(2)
  }
}
