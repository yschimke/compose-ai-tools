/*
 * Copyright 2025 Yuri Schimke
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.cli.serve.UsageRules.Companion.appliesToModule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * This repo's **own** `compose-usage.json`, run over this repo's **own** catalog source.
 *
 * [PlaygroundSourceCleanerTest] pins the machinery against fixtures; this pins the thing a visitor
 * actually gets, and it is the pairing that matters here. The rules file and the catalog it
 * describes live in one repository and are edited by different hands: renaming `Sticker`, moving
 * `CatalogComponents.kt`, or adding a knob wrapper all break the Source panel silently, because the
 * cleaner's contract is to degrade rather than fail. A served preview whose "source" is
 * `Sticker("button-filled")` and nothing else is exactly what issue #4169 reported.
 *
 * So this reads the real files off disk at the paths the rules name, and asserts the reduction
 * reaches the component.
 */
class CatalogUsageRulesTest {

  private val root = repoRoot()

  private val rules =
    assertNotNull(
      UsageRules.parse(File(root, "compose-usage.json").readText()),
      "compose-usage.json at the repo root must parse as usage rules",
    )

  private val helperSources by lazy {
    rules.scaffoldSources.map { path ->
      val file = File(root, path)
      assertTrue(file.isFile, "compose-usage.json names a scaffold source that is not there: $path")
      file.readText()
    }
  }

  private val strings by lazy {
    val path = assertNotNull(rules.stringsPath).removePrefix("/")
    val file = File(root, path)
    assertTrue(file.isFile, "compose-usage.json names a strings file that is not there: $path")
    Regex("""<string\s+name="([A-Za-z0-9_]+)"\s*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
      .findAll(file.readText())
      .associate { it.groupValues[1] to it.groupValues[2].trim() }
  }

  private val buttonsKt =
    File(
      root,
      "samples/design-catalog-m3/src/main/kotlin/com/example/designcatalogm3/CatalogButtons.kt",
    )

  /** The 1-based line of `fun <name>` in [file] — the anchor discovery records per preview. */
  private fun anchor(file: File, name: String): Int =
    file.readLines().indexOfFirst { Regex("""^fun $name\(""").containsMatchIn(it) } + 1

  private fun cleanPreview(file: File, name: String): PlaygroundSourceCleaner.Result =
    assertNotNull(
      PlaygroundSourceCleaner.clean(
        source = file.readText(),
        bodyLine = anchor(file, name),
        rules = rules,
        strings = strings,
        // The staged PSI sidecar is not present in a plain `./gradlew test`; the text passes are
        // what this asserts, and they are also what a host without the sidecar serves.
        parser = null,
        helperSources = helperSources,
      ),
      "the cleaner declined $name",
    )

  @Test
  fun `the m3 sticker sheet reduces to the component it delegates to`() {
    val result = cleanPreview(buttonsKt, "FilledButton")

    // The whole of issue #4169: the snippet must contain the component, not the wrapper that
    // fetches it. Neither the sticker helper nor the shared component set may survive.
    assertTrue(result.text.contains("Button("), "no Button in:\n${result.text}")
    assertFalse(result.text.contains("Sticker("), "the sticker wrapper survived:\n${result.text}")
    assertFalse(
      result.text.contains("CatalogComponent("),
      "the shared component dispatch survived:\n${result.text}",
    )

    // The label the render actually shows, not the resource lookup that produces it.
    assertTrue(result.text.contains("\"Filled\""), "label not inlined:\n${result.text}")

    // The catalog's own machinery is gone, including the multipreview it declares in the previews'
    // own package — the one an import can never resolve.
    assertFalse(result.text.contains("@CatalogModes"), "annotation survived:\n${result.text}")
    assertFalse(result.text.contains("@CatalogComponent"), "annotation survived:\n${result.text}")
    assertTrue(result.text.contains("@Preview"), "no runnable preview:\n${result.text}")

    // The imports the expansion needs come from the file the expansion came from.
    assertTrue(
      result.text.contains("import androidx.compose.material3.Button"),
      "expanded body's imports missing:\n${result.text}",
    )
    assertEquals(emptyList(), result.residue, "residue:\n${result.text}")
  }

  @Test
  fun `a stateful component brings its shared helper along`() {
    val selectionKt =
      File(
        root,
        "samples/design-catalog-m3/src/main/kotlin/com/example/designcatalogm3/CatalogSelection.kt",
      )
    val result = cleanPreview(selectionKt, "CheckboxChecked")

    // `checkbox-checked` dispatches to `StatefulCheckbox(…)`, which is declared in the shared
    // module — so the closure has to cross the file boundary the same way the expansion did, or the
    // snippet names something the reader cannot see.
    assertTrue(result.text.contains("StatefulCheckbox"), "helper not called:\n${result.text}")
    assertTrue(
      result.text.contains("fun StatefulCheckbox"),
      "helper not closed over:\n${result.text}",
    )
    assertTrue(result.text.contains("Checkbox("), "no Checkbox in:\n${result.text}")
  }

  @Test
  fun `every scaffold source and strings path the rules name exists`() {
    // Cheap, and it is the failure mode that made this whole thing worth a test: a moved file turns
    // the Source panel back into a one-line wrapper with nothing to say it regressed.
    assertTrue(helperSources.all { it.isNotBlank() })
    assertTrue(strings.isNotEmpty())
    assertTrue(
      rules.scaffoldSources.size <= PlaygroundSeedResolver.MAX_SCAFFOLD_SOURCES,
      "more scaffold sources than the resolver will read",
    )
  }

  /**
   * The rules are scoped to the modules they were written for, and the scope actually covers the
   * catalog they describe.
   *
   * Both halves matter and they fail in opposite directions. Too WIDE (the old unscoped file) and
   * the m3 sticker sheet's scaffolds reach the Wear and Remote catalogs, whose helpers these rules
   * never name — their snippets then carry unresolved calls under `declaresCatalogScaffolds`'s
   * stronger "the catalog declared its scaffolding" note. Too NARROW — a module renamed here but
   * not in `catalog.spec.json` — and the m3 catalog silently drops to the generic rules, which is
   * issue #4169 all over again with nothing to say it regressed.
   */
  @Test
  fun `the rules are scoped to the m3 modules they describe`() {
    assertEquals(
      listOf("samples:design-catalog-m3", "samples:design-catalog-m3-shared"),
      rules.modules,
    )

    // The scope has to include the module the m3 catalog actually publishes under, read from the
    // spec rather than repeated here.
    val declared =
      Regex(""""module"\s*:\s*"([^"]+)"""")
        .find(File(root, "samples/design-catalog-m3/catalog.spec.json").readText())
        ?.groupValues
        ?.get(1)
    assertTrue(
      declared != null && rules.appliesToModule(declared),
      "compose-usage.json does not cover the module the m3 catalog publishes under ($declared)",
    )
    // Every scaffold source lives in one of the scoped modules — a source outside them would be
    // read for a catalog these rules no longer apply to.
    assertTrue(
      rules.scaffoldSources.all { path ->
        rules.modules.any { path.startsWith(it.replace(':', '/') + "/") }
      },
      "a scaffold source lives outside the scoped modules: ${rules.scaffoldSources}",
    )

    // The sibling catalogs published from this same repo must NOT inherit them.
    assertFalse(rules.appliesToModule("samples:design-catalog-wear-m3"))
  }

  /**
   * An unscoped rules file — every one written before scoping existed — still applies everywhere.
   */
  @Test
  fun `rules naming no modules apply to every module`() {
    val unscoped =
      assertNotNull(UsageRules.parse("""{"scaffolds":{"Sticker":{"kind":"EXPAND"}}}"""))

    assertTrue(unscoped.appliesToModule("samples:design-catalog-wear-m3"))
    assertTrue(unscoped.appliesToModule(null))
    // A scoped file still covers a location with no module at all: a catalog published before
    // discovery recorded the field must not lose the rules that were written for it.
    assertTrue(rules.appliesToModule(null))
  }
}
