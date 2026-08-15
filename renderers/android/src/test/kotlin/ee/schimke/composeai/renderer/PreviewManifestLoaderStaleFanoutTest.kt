package ee.schimke.composeai.renderer

import ee.schimke.composeai.renderer.PreviewManifestLoader.PreviewRow
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the stale fan-out cleanup in [PreviewManifestLoader.deleteStaleFanoutFiles], including
 * rendered data products (issue #3823) and the two issue #2193 guards:
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
        dataProducts =
          base.dataProducts.map {
            it.copy(output = PreviewManifestLoader.insertBeforeExtension(it.output, suffix))
          },
      ),
      listOf(Any()),
    )

  private fun productEntry(id: String, output: String): RenderPreviewEntry =
    RenderPreviewEntry(
      id = id,
      functionName = id,
      className = "com.example.PreviewsKt",
      params = RenderPreviewParams(previewParameterProviderClassName = "com.example.UserProvider"),
      captures = emptyList(),
      dataProducts = listOf(RenderPreviewArtifact(kind = "render/scroll/long", output = output)),
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

  /**
   * A `.json` template makes every companion in the directory end with the template's own
   * extension, so a classifier that tries the plain extension before the companion suffixes reads
   * `Foo_Alice.png.error.json` as a JSON output of its own and deletes the PNG's *current*
   * diagnostics. Predates the companion sweep: the pre-#3822 filter was a bare `endsWith(".json")`,
   * which matched it just the same.
   */
  @Test
  fun `a json template does not claim another extension's companions`() {
    val foo = entry("Foo", parameterized = true, "renders/Foo.json")
    val expandedByEntry = listOf(foo to listOf(fanoutRow(foo, "_Alice")))

    tmp.newFile("Foo_Alice.png.error.json")
    tmp.newFile("Foo_Alice.gif.warnings.json")
    tmp.newFile("Foo_stale.json.error.json")

    PreviewManifestLoader.deleteStaleFanoutFiles(
      outDir = tmp.root,
      allEntries = listOf(foo),
      expandedByEntry = expandedByEntry,
      ownedIds = setOf("Foo_Alice"),
    )

    assertTrue(File(tmp.root, "Foo_Alice.png.error.json").exists())
    assertTrue(File(tmp.root, "Foo_Alice.gif.warnings.json").exists())
    assertFalse(File(tmp.root, "Foo_stale.json.error.json").exists())
  }

  /**
   * A `--preview-id` filter selects `Foo` and drops parameterized sibling `Foo_Dark`. The filter
   * applies to the selection `expandedByEntry` is built from, while `allEntries` stays the full
   * manifest — so `Foo_Dark` contributes its declared `Foo_Dark.png` and nothing else, and its
   * fan-out rows are in no expected-name set while still matching `Foo`'s `Foo_*` prefix. The
   * loader's documented promise is that a filtered-out preview keeps its existing artifacts, so the
   * declared sibling stem has to shield the whole fan-out — companions included.
   */
  @Test
  fun `a filtered-out parameterized sibling keeps its fan-out and companions`() {
    val foo = entry("Foo", parameterized = true, "renders/Foo.png")
    val fooDark = entry("Foo_Dark", parameterized = true, "renders/Foo_Dark.png")
    // `Foo_Dark` was filtered out, so it is never expanded: only `Foo` reaches expansion.
    val expandedByEntry = listOf(foo to listOf(fanoutRow(foo, "_Alice")))

    tmp.newFile("Foo_Dark.png") // declared sibling base render
    tmp.newFile("Foo_Dark_Alice.png") // its fan-out row, never expanded this run
    tmp.newFile("Foo_Dark_Alice.png.error.json") // that row failed last run
    tmp.newFile("Foo_stale.png") // genuinely stale fan-out of Foo
    tmp.newFile("Foo_stale.png.error.json")

    PreviewManifestLoader.deleteStaleFanoutFiles(
      outDir = tmp.root,
      allEntries = listOf(foo, fooDark),
      expandedByEntry = expandedByEntry,
      ownedIds = setOf("Foo_Alice"),
    )

    assertTrue(File(tmp.root, "Foo_Dark.png").exists())
    assertTrue(File(tmp.root, "Foo_Dark_Alice.png").exists())
    assertTrue(File(tmp.root, "Foo_Dark_Alice.png.error.json").exists())
    assertFalse(File(tmp.root, "Foo_stale.png").exists())
    assertFalse(File(tmp.root, "Foo_stale.png.error.json").exists())
  }

  /**
   * The sibling-stem guard is same-extension only, mirroring the plugin's `fanoutSiblingStems`:
   * shielding a different-extension sibling would strand a genuinely stale `Foo_Dark.png` left from
   * before that sibling's capture became a GIF.
   */
  @Test
  fun `a different-extension sibling stem does not shield same-extension staleness`() {
    val foo = entry("Foo", parameterized = true, "renders/Foo.png")
    val fooDarkGif = entry("Foo_Dark", parameterized = false, "renders/Foo_Dark.gif")
    val expandedByEntry = listOf(foo to listOf(fanoutRow(foo, "_Alice")))

    tmp.newFile("Foo_Dark.png") // stale: this sibling renders a GIF now
    tmp.newFile("Foo_Dark.gif") // the sibling's current output

    PreviewManifestLoader.deleteStaleFanoutFiles(
      outDir = tmp.root,
      allEntries = listOf(foo, fooDarkGif),
      expandedByEntry = expandedByEntry,
      ownedIds = setOf("Foo_Alice"),
    )

    assertFalse(File(tmp.root, "Foo_Dark.png").exists())
    assertTrue(File(tmp.root, "Foo_Dark.gif").exists())
  }

  @Test
  fun `deletes stale data-product fan-out without cross-directory name collisions`() {
    val previewRoot = tmp.newFolder("previews")
    val rendersDir = File(previewRoot, "renders").also { it.mkdirs() }
    val foo = productEntry("Foo", "data/scroll-long/Foo.png")
    val other = productEntry("Other", "data/other/Foo_stale.png")
    val expandedByEntry =
      listOf(
        foo to listOf(fanoutRow(foo, "_Alice")),
        other to listOf(fanoutRow(other, "_stale")),
      )
    val fooDir = File(previewRoot, "data/scroll-long").also { it.mkdirs() }
    val otherDir = File(previewRoot, "data/other").also { it.mkdirs() }
    File(fooDir, "Foo_Alice.png").createNewFile()
    File(fooDir, "Foo_Alice.png.error.json").createNewFile()
    File(fooDir, "Foo_stale.png").createNewFile()
    File(fooDir, "Foo_stale.png.error.json").createNewFile()
    File(otherDir, "Foo_stale.png").createNewFile()

    PreviewManifestLoader.deleteStaleFanoutFiles(
      outDir = rendersDir,
      allEntries = listOf(foo, other),
      expandedByEntry = expandedByEntry,
      ownedIds = setOf("Foo_Alice"),
    )

    assertTrue(File(fooDir, "Foo_Alice.png").exists())
    assertTrue(File(fooDir, "Foo_Alice.png.error.json").exists())
    assertFalse(File(fooDir, "Foo_stale.png").exists())
    assertFalse(File(fooDir, "Foo_stale.png.error.json").exists())
    assertTrue(File(otherDir, "Foo_stale.png").exists())
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
