package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Issue #3819 — a `@PreviewParameter` **row id** must select its row on the result-shaped commands
 * (`show` / `list` / `render`), not just on `serve`.
 *
 * `show --id Foo_PARAM_1` used to render and then print "No previews matched.", while printing —
 * for any wider selector — the very rows it refused to select. The rows were there; their **ids**
 * were not: `PreviewResultBuilder` expanded a fan-out into one capture per provider value carrying
 * `parameterLabel` ("parameter 1"), a lossy human coordinate that can't be turned back into a
 * selector. So the id is now derived once, by `PreviewParameterFanout`, carried as
 * [CaptureResult.parameterRowId], and matched by [selectRequestedResults] — the same derivation
 * `serve` addresses its cards with, pinned as such by
 * [serve and the rendered results agree on every row id].
 *
 * Driven against a real fan-out on disk (a temp module dir + `previews.json` + PNG files) rather
 * than hand-built [PreviewResult]s, because the premise under test is precisely that the ids
 * survive the builder and are present by the time filtering runs.
 */
class PreviewRowResultSelectionTest {

  private val tempDir: File = Files.createTempDirectory("preview-row-selection-test").toFile()
  private val json = Json { encodeDefaults = true }

  @AfterTest
  fun cleanup() {
    tempDir.deleteRecursively()
  }

  /** A command with no behaviour of its own — a seam onto the base class's `--id` filtering. */
  private class FilteringCommand(args: List<String>) : Command(args) {
    override fun run() = Unit

    fun filter(all: List<PreviewResult>): List<PreviewResult> = applyFilters(all)
  }

  private fun module(name: String = ":app"): PreviewModule =
    PreviewModule(
      gradlePath = name,
      projectDir =
        tempDir.resolve(name.trim(':')).apply { resolve("build/compose-previews/renders").mkdirs() },
    )

  private fun preview(id: String, parameterized: Boolean = true) =
    PreviewInfo(
      id = id,
      functionName = id.substringBefore('_'),
      className = "com.example.PreviewsKt",
      params =
        PreviewParams(
          previewParameterProviderClassName =
            if (parameterized) "com.example.SwatchProvider" else null
        ),
      captures = listOf(Capture(renderOutput = "renders/$id.png")),
    )

  /** Writes `previews.json` plus one PNG per [renders] leaf name, and builds the results. */
  private fun results(
    module: PreviewModule,
    previews: List<PreviewInfo>,
    renders: List<String>,
  ): List<PreviewResult> {
    val dir = module.projectDir.resolve("build/compose-previews")
    dir
      .resolve("previews.json")
      .writeText(
        json.encodeToString(
          PreviewManifest.serializer(),
          PreviewManifest(module = module.gradlePath, variant = "debug", previews = previews),
        )
      )
    renders.forEach { dir.resolve("renders/$it").writeBytes(it.toByteArray()) }
    return PreviewResultBuilder.build(listOf(module to PreviewResultBuilder.readManifest(module)!!))
  }

  private fun swatchResults(module: PreviewModule = module()): List<PreviewResult> =
    results(
      module,
      listOf(preview("Swatch")),
      listOf("Swatch_PARAM_0.png", "Swatch_PARAM_1.png", "Swatch_Crimson.png"),
    )

  private fun rowIds(results: List<PreviewResult>): List<String?> = results.flatMap { r ->
    r.captures.map { it.parameterRowId }
  }

  /** The premise the whole fix rests on: the per-value captures carry addressable ids. */
  @Test
  fun `expanded fan-out captures carry their row ids`() {
    assertEquals(
      listOf("Swatch_PARAM_0", "Swatch_PARAM_1", "Swatch_Crimson"),
      rowIds(swatchResults()),
    )
  }

  /** The reproduce case: `compose-preview show --id Swatch_PARAM_1`. */
  @Test
  fun `a row id selects exactly its row on show`() {
    val filtered = FilteringCommand(listOf("--id", "Swatch_PARAM_1")).filter(swatchResults())

    assertEquals(listOf("Swatch"), filtered.map { it.id })
    assertEquals(listOf("Swatch_PARAM_1"), rowIds(filtered))
    // The back-compat mirror follows the surviving capture, or `--output` would copy another row.
    assertEquals("Swatch_PARAM_1.png", File(filtered.single().pngPath!!).name)
  }

  /** `list` and `render` filter through the same rule, one step earlier (no `--changed-only`). */
  @Test
  fun `a row id selects exactly its row on list and render`() {
    val filtered =
      selectRequestedResults(swatchResults(), exactId = "Swatch_Crimson", filter = null)

    assertEquals(listOf("Swatch_Crimson"), rowIds(filtered))
  }

  /** Asking for the parameterized preview itself still means asking for all of its states. */
  @Test
  fun `the base id keeps every row`() {
    val filtered = FilteringCommand(listOf("--id", "Swatch")).filter(swatchResults())

    assertEquals(
      listOf("Swatch_PARAM_0", "Swatch_PARAM_1", "Swatch_Crimson"),
      rowIds(filtered),
      "--id Swatch must not be narrowed to one row",
    )
  }

  /**
   * `--filter` is a case-insensitive substring of the final row id — a perfectly ordinary way to
   * ask for one state, and one that matches no base id anywhere (#3795's
   * `previewMatchesRequestIncluding Rows` keeps the module for exactly this).
   */
  @Test
  fun `a substring selector narrows to the rows it names`() {
    assertEquals(
      listOf("Swatch_Crimson"),
      rowIds(FilteringCommand(listOf("--filter", "crimson")).filter(swatchResults())),
    )
    assertEquals(
      listOf("Swatch_PARAM_0", "Swatch_PARAM_1"),
      rowIds(FilteringCommand(listOf("--preview", "PARAM_")).filter(swatchResults())),
    )
  }

  /**
   * The #3798 / #3799 gate, one step later in the pipeline. A real `Swatch_Dark` preview and a
   * parameterized `Swatch` whose fan-out happens to include a `Dark` value: `--id Swatch_Dark`
   * names the preview that exists, and the row lane is off entirely — precise selection stays
   * precise. Deliberately `--id` only; the substring cases above show why the loose selectors must
   * not inherit it.
   */
  @Test
  fun `an exact id that names a real preview wins over a row of the same name`() {
    val module = module()
    val all =
      results(
        module,
        listOf(preview("Swatch"), preview("Swatch_Dark", parameterized = false)),
        listOf("Swatch_Dark.png", "Swatch_Crimson.png"),
      )

    val filtered = FilteringCommand(listOf("--id", "Swatch_Dark")).filter(all)

    assertEquals(listOf("Swatch_Dark"), filtered.map { it.id })
    assertEquals(listOf<String?>(null), rowIds(filtered), "the declared preview, not Swatch's row")
  }

  /** A row id nothing rendered still selects nothing — the row lane is not "keep everything". */
  @Test
  fun `a row id that no fan-out produced matches nothing`() {
    assertTrue(FilteringCommand(listOf("--id", "Swatch_Teal")).filter(swatchResults()).isEmpty())
  }

  /** The selectors intersect, so an unsatisfiable pair still selects nothing. */
  @Test
  fun `an intersecting filter still applies to a row id`() {
    assertEquals(
      listOf("Swatch_PARAM_1"),
      rowIds(
        FilteringCommand(listOf("--id", "Swatch_PARAM_1", "--filter", "Swatch"))
          .filter(swatchResults())
      ),
    )
    assertTrue(
      FilteringCommand(listOf("--id", "Swatch_PARAM_1", "--filter", "Unrelated"))
        .filter(swatchResults())
        .isEmpty()
    )
  }

  /**
   * The anti-drift assertion, and the reason the derivation moved into `PreviewParameterFanout`
   * rather than being written a second time: `serve` routes on these ids, the result-shaped
   * commands now select on them, and a disagreement would not merely misreport — it would hand back
   * a different row than the one asked for.
   *
   * The sibling case is the one that had already drifted: with `Swatch` and `Swatch_Dark` both
   * real, `Swatch_Dark_Alice.png` is a row of the more specific `Swatch_Dark`. The builder excluded
   * it; `serve` claimed it for `Swatch` as row `Dark_Alice`.
   */
  @Test
  fun `serve and the rendered results agree on every row id`() {
    val module = module()
    val previews = listOf(preview("Swatch"), preview("Swatch_Dark"))
    val all =
      results(
        module,
        previews,
        listOf(
          "Swatch_PARAM_0.png",
          "Swatch_Crimson.png",
          "Swatch_Dark.png",
          "Swatch_Dark_Alice.png",
        ),
      )

    val claimed = ee.schimke.composeai.cli.serve.ServeParameterRows.claimedOutputs(previews)
    for (preview in previews) {
      val serveIds =
        ee.schimke.composeai.cli.serve.ServeParameterRows.rowsFor(
            preview = preview,
            moduleDir = module.projectDir,
            siblingOutputs = claimed,
          )
          .map { it.id }
      val resultIds = all.single { it.id == preview.id }.captures.mapNotNull { it.parameterRowId }
      assertEquals(serveIds, resultIds, "row ids for ${preview.id} must not depend on who asked")
    }

    assertEquals(
      listOf("Swatch_PARAM_0", "Swatch_Crimson", "Swatch_Dark_Alice"),
      rowIds(all).filterNotNull(),
      "a longer-stemmed sibling owns its own fan-out on both sides",
    )
  }
}
