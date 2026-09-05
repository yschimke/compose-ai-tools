package ee.schimke.composeai.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The cross-repository pin for the preview-selector rule (issue #5185).
 *
 * `--id`, `--filter` and `--preview` are answered twice, by two implementations that live in two
 * repositories: [previewIdMatchesRequest] here, and `previewIdMatchesStandaloneRequest` in
 * `yschimke/compose-preview-server`, which builds `ServeCommandOptions` itself now that
 * `compose-preview serve` is a launcher (#5177) and no longer hands the CLI's rule in. They agreed
 * by inspection and nothing checked it; the failure mode is silent, because a preview that stops
 * matching produces no error anywhere.
 *
 * [FIXTURES] is the shared table, and this suite is one half of the pin — the server repository
 * vendors the same file and runs it through its own rule. Change the rule, change the table, in the
 * same PR: the other half goes red on the next sync either way.
 *
 * The table is deliberately a *table* rather than a shared implementation. The rule is small and
 * pure, and the layer split (docs/design/REPOSITORY_LAYERS.md) puts the two copies on opposite
 * sides of a boundary that a shared function would have to cross.
 */
class PreviewSelectorFixturesTest {

  @Test
  fun `every fixture case answers the same way here`() {
    for (case in cases()) {
      val preview = case.getValue("preview").jsonObject
      val selectors = case.getValue("selectors").jsonObject
      val expected = case.getValue("expected").jsonPrimitive.content.toBoolean()
      val name = case.getValue("name").jsonPrimitive.content
      assertEquals(
        expected,
        previewIdMatchesRequest(
          id = preview.string("id")!!,
          exactId = selectors.string("exactId"),
          filter = selectors.string("filter"),
          previewRef = selectors.string("previewRef"),
          className = preview.string("className"),
          functionName = preview.string("functionName"),
        ),
        "$FIXTURES case: $name",
      )
    }
  }

  /**
   * Every combination of the three selectors is exercised, both ways.
   *
   * Without this a rule change could add a branch — a fourth selector, a precedence between two of
   * them — and land green against a table that never reaches it. The `false` half matters as much
   * as the `true` half: a rule that accepted everything would satisfy a `true`-only table.
   */
  @Test
  fun `the table covers each selector combination in both outcomes`() {
    val seen = mutableMapOf<Set<String>, MutableSet<Boolean>>()
    for (case in cases()) {
      val selectors = case.getValue("selectors").jsonObject
      val combination = SELECTORS.filter { selectors.string(it) != null }.toSet()
      val expected = case.getValue("expected").jsonPrimitive.content.toBoolean()
      seen.getOrPut(combination) { mutableSetOf() }.add(expected)
    }
    for (combination in selectorCombinations()) {
      val outcomes = seen[combination] ?: emptySet<Boolean>()
      val label = if (combination.isEmpty()) "(no selectors)" else combination.sorted().toString()
      // No selectors can only ever keep the preview, so only `true` is required of it.
      val required = if (combination.isEmpty()) setOf(true) else setOf(true, false)
      assertTrue(
        outcomes.containsAll(required),
        "$FIXTURES covers $label with $outcomes; expected $required",
      )
    }
  }

  private fun cases(): List<JsonObject> =
    Json.parseToJsonElement(fixtureFile().readText()).jsonObject.getValue("cases").jsonArray.map {
      it.jsonObject
    }

  /** `null` for an absent selector, which is not the same request as an empty one. */
  private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

  private fun selectorCombinations(): List<Set<String>> =
    (0 until (1 shl SELECTORS.size)).map { mask ->
      SELECTORS.filterIndexed { index, _ -> (mask shr index) and 1 == 1 }.toSet()
    }

  private fun fixtureFile(): File = File(repoRoot(), FIXTURES).also { assertTrue(it.isFile, "$it") }

  /** Walk up from the test working dir (the `:cli` project dir) to the repo root. */
  private fun repoRoot(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      if (File(dir, "settings.gradle.kts").isFile) return dir
      dir = dir.parentFile
    }
    error("could not locate repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
  }

  private companion object {
    const val FIXTURES = "docs/serve/preview-selector-fixtures.json"
    val SELECTORS = listOf("exactId", "filter", "previewRef")
  }
}
