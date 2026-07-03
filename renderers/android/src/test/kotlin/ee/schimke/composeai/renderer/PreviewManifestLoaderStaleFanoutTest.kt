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
 *   the swept one's (`Foo` vs the `@Preview(name = "Dark")` variant `Foo_Dark`) never has its
 *   base render or fan-out classified as stale;
 * - the sweep only runs for previews with a row assigned to this shard, so a fork that owns none
 *   of a preview's fan-out doesn't touch its prefix.
 */
class PreviewManifestLoaderStaleFanoutTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun entry(
        id: String,
        parameterized: Boolean,
        vararg renderOutputs: String,
    ): RenderPreviewEntry =
        RenderPreviewEntry(
            id = id,
            functionName = id,
            className = "com.example.PreviewsKt",
            params = RenderPreviewParams(
                previewParameterProviderClassName =
                    if (parameterized) "com.example.UserProvider" else null,
            ),
            captures = renderOutputs.map { RenderPreviewCapture(renderOutput = it) },
        )

    /** An expanded (suffixed) row of [base], the shape `expandParameterProvider` produces. */
    private fun fanoutRow(base: RenderPreviewEntry, suffix: String): PreviewRow =
        PreviewRow(
            base.copy(
                id = base.id + suffix,
                captures = base.captures.map {
                    it.copy(
                        renderOutput = PreviewManifestLoader.insertBeforeExtension(
                            it.renderOutput,
                            suffix,
                        ),
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
        val expandedByEntry = listOf(
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
        val expandedByEntry = listOf(
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
