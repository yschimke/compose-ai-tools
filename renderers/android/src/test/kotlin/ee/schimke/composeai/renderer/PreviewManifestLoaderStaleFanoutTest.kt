package ee.schimke.composeai.renderer

import ee.schimke.composeai.renderer.PreviewManifestLoader.PreviewRow
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the stale fan-out cleanup in [PreviewManifestLoader.deleteStaleFanoutFiles], in particular
 * the two issue #2193 guards:
 * - the expected set spans the whole manifest, so a sibling preview whose stem underscore-extends
 *   the swept one's (`Foo` vs the `@Preview(name = "Dark")` variant `Foo_Dark`) never has its base
 *   render or fan-out classified as stale;
 * - the sweep only runs for previews with a row assigned to this shard, so a fork that owns none of
 *   a preview's fan-out doesn't touch its prefix.
 */
class PreviewManifestLoaderStaleFanoutTest {

  @get:Rule val tmp = TemporaryFolder()

  private fun entry(
    id: String,
    parameterized: Boolean,
    vararg renderOutputs: String,
  ): RenderPreviewEntry =
    RenderPreviewEntry(
      id = id,
      functionName = id,
      className = "com.example.PreviewsKt",
      params =
        RenderPreviewParams(
          previewParameterProviderClassName =
            if (parameterized) "com.example.UserProvider" else null
        ),
      captures = renderOutputs.map { RenderPreviewCapture(renderOutput = it) },
    )

  /** An expanded (suffixed) row of [base], the shape `expandParameterProvider` produces. */
  private fun fanoutRow(base: RenderPreviewEntry, suffix: String): PreviewRow =
    PreviewRow(
      base.copy(
        id = base.id + suffix,
        captures =
          base.captures.map {
            it.copy(
              renderOutput = PreviewManifestLoader.insertBeforeExtension(it.renderOutput, suffix)
            )
          },
      ),
      listOf(Any()),
    )

  @Test
  fun `deletes stale fan-out but keeps expected, sibling base and sibling fan-out files`() {
    val foo = entry("Foo", parameterized = true, "renders/Foo.png")
    val fooDark = entry("Foo_Dark", parameterized = true, "renders/Foo_Dark.png")
    val fooSelected = entry("Foo_Selected", parameterized = false, "renders/Foo_Selected.png")
    val expandedByEntry =
      listOf(
        foo to listOf(fanoutRow(foo, "_Alice"), fanoutRow(foo, "_Bob")),
        fooDark to listOf(fanoutRow(fooDark, "_Alice"), fanoutRow(fooDark, "_Bob")),
        fooSelected to listOf(PreviewRow(fooSelected, emptyList())),
      )

    tmp.newFile("Foo_Alice.png") // expected fan-out of Foo
    tmp.newFile("Foo_Dark_Alice.png") // sibling's fan-out — matches Foo_* but isn't ours
    tmp.newFile("Foo_Selected.png") // non-parameterized sibling's base render
    tmp.newFile("Foo_PARAM_0.png") // stale: pre-label-migration shape
    tmp.newFile("Foo_stale.png") // stale: renamed provider value
    tmp.newFile("Foo_Dark_stale.png") // stale under the sibling's prefix

    PreviewManifestLoader.deleteStaleFanoutFiles(
      outDir = tmp.root,
      allEntries = listOf(foo, fooDark, fooSelected),
      expandedByEntry = expandedByEntry,
      ownedIds = expandedByEntry.flatMap { it.second }.map { it.entry.id }.toSet(),
    )

    assertTrue(File(tmp.root, "Foo_Alice.png").exists())
    assertTrue(File(tmp.root, "Foo_Dark_Alice.png").exists())
    assertTrue(File(tmp.root, "Foo_Selected.png").exists())
    assertFalse(File(tmp.root, "Foo_PARAM_0.png").exists())
    assertFalse(File(tmp.root, "Foo_stale.png").exists())
    assertFalse(File(tmp.root, "Foo_Dark_stale.png").exists())
  }

  @Test
  fun `sweeps only previews with a row assigned to this shard`() {
    val foo = entry("Foo", parameterized = true, "renders/Foo.png")
    val fooDark = entry("Foo_Dark", parameterized = true, "renders/Foo_Dark.png")
    val expandedByEntry =
      listOf(
        foo to listOf(fanoutRow(foo, "_Alice")),
        fooDark to listOf(fanoutRow(fooDark, "_Alice")),
      )

    tmp.newFile("Foo_stale.png")
    tmp.newFile("Foo_Dark_stale.png")

    PreviewManifestLoader.deleteStaleFanoutFiles(
      outDir = tmp.root,
      allEntries = listOf(foo, fooDark),
      expandedByEntry = expandedByEntry,
      // This fork's shard was assigned only the Foo_Dark fan-out.
      ownedIds = setOf("Foo_Dark_Alice"),
    )

    // Foo's prefix belongs to another shard: left alone (including files that would be stale
    // in a full sweep). Foo_Dark's prefix is ours: its stale file goes.
    assertTrue(File(tmp.root, "Foo_stale.png").exists())
    assertFalse(File(tmp.root, "Foo_Dark_stale.png").exists())
  }

  /**
   * The orphan PR #3815 hit: a row that FAILED writes only `<row>.png.error.json`, never a PNG, so
   * the sweep's extension filter matched nothing and the sidecar survived the provider rename that
   * retired the row. The CLI's missing-render report then rediscovers it by directory glob and can
   * present a dead exception as a failure of the current run. A companion is classified by the
   * output it names, so it lives and dies with that output — including under the sibling-stem guard
   * (`Foo_Dark_*` is the sibling's, never `Foo`'s).
   */
  @Test
  fun `sweeps a stale row's companions and keeps an expected row's`() {
    val foo = entry("Foo", parameterized = true, "renders/Foo.png")
    val fooDark = entry("Foo_Dark", parameterized = true, "renders/Foo_Dark.png")
    val expandedByEntry =
      listOf(
        foo to listOf(fanoutRow(foo, "_Alice")),
        fooDark to listOf(fanoutRow(fooDark, "_Alice")),
      )

    tmp.newFile("Foo_Alice.png") // expected fan-out of Foo
    tmp.newFile("Foo_Alice.png.error.json") // its companion: this run's row, keep
    tmp.newFile("Foo_Alice.png.warnings.json")
    tmp.newFile("Foo_renamed.png.error.json") // failed row, provider value since renamed
    tmp.newFile("Foo_renamed.png.warnings.json")
    tmp.newFile("Foo_Dark_Alice.png.error.json") // sibling's companion, not ours

    PreviewManifestLoader.deleteStaleFanoutFiles(
      outDir = tmp.root,
      allEntries = listOf(foo, fooDark),
      expandedByEntry = expandedByEntry,
      ownedIds = expandedByEntry.flatMap { it.second }.map { it.entry.id }.toSet(),
    )

    assertTrue(File(tmp.root, "Foo_Alice.png").exists())
    assertTrue(File(tmp.root, "Foo_Alice.png.error.json").exists())
    assertTrue(File(tmp.root, "Foo_Alice.png.warnings.json").exists())
    assertTrue(File(tmp.root, "Foo_Dark_Alice.png.error.json").exists())
    assertFalse(File(tmp.root, "Foo_renamed.png.error.json").exists())
    assertFalse(File(tmp.root, "Foo_renamed.png.warnings.json").exists())
  }

  /** A companion is scoped by the extension of the output it names, exactly as that output is. */
  @Test
  fun `companions of another template's extension are not candidates`() {
    val foo = entry("Foo", parameterized = true, "renders/Foo.gif")
    val expandedByEntry = listOf(foo to listOf(fanoutRow(foo, "_Alice")))

    tmp.newFile("Foo_stale.gif.error.json")
    tmp.newFile("Foo_stale.png.error.json")

    PreviewManifestLoader.deleteStaleFanoutFiles(
      outDir = tmp.root,
      allEntries = listOf(foo),
      expandedByEntry = expandedByEntry,
      ownedIds = setOf("Foo_Alice"),
    )

    assertFalse(File(tmp.root, "Foo_stale.gif.error.json").exists())
    assertTrue(File(tmp.root, "Foo_stale.png.error.json").exists())
  }

  @Test
  fun `non-parameterized previews never trigger a sweep`() {
    val plain = entry("Foo", parameterized = false, "renders/Foo.png")
    val expandedByEntry = listOf(plain to listOf(PreviewRow(plain, emptyList())))

    tmp.newFile("Foo_anything.png")

    PreviewManifestLoader.deleteStaleFanoutFiles(
      outDir = tmp.root,
      allEntries = listOf(plain),
      expandedByEntry = expandedByEntry,
      ownedIds = setOf("Foo"),
    )

    assertTrue(File(tmp.root, "Foo_anything.png").exists())
  }
}
