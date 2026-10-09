package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
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
}
