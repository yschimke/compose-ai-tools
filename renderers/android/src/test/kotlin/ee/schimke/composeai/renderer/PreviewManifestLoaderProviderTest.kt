package ee.schimke.composeai.renderer

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the `@PreviewParameter` provider-loading behaviour in
 * [PreviewManifestLoader.expandParameterProvider] / `loadProviderValues` (issue #2493):
 * - a `private` (package-private in bytecode) provider is opened up with `isAccessible` and
 *   enumerated instead of crashing with `IllegalAccessException`;
 * - a provider that can't be loaded is isolated to its own preview — it returns no rows and writes a
 *   `.error.json` card rather than throwing out of the shard's `@Parameters` method.
 */
class PreviewManifestLoaderProviderTest {

  @get:Rule val tmp = TemporaryFolder()

  @After
  fun clearOutputDir() {
    System.clearProperty("composeai.render.outputDir")
  }

  private fun entry(id: String, providerFqn: String?): RenderPreviewEntry =
    RenderPreviewEntry(
      id = id,
      functionName = id,
      className = "com.example.PreviewsKt",
      params = RenderPreviewParams(previewParameterProviderClassName = providerFqn),
      captures = listOf(RenderPreviewCapture(renderOutput = "renders/$id.png")),
    )

  @Test
  fun `private provider is enumerated instead of throwing IllegalAccessException`() {
    val rows =
      PreviewManifestLoader.expandParameterProvider(
        entry("FooPreview", "com.example.testproviders.PrivateStringProvider")
      )

    // One row per provided value ("alpha", "beta", "gamma"), each carrying its own value.
    assertEquals(3, rows.size)
    assertEquals(
      listOf("alpha", "beta", "gamma"),
      rows.map { it.previewArgs.single() as String },
    )
    // Every fan-out row is suffixed off the base id / renderOutput.
    assertTrue(rows.all { it.entry.id.startsWith("FooPreview") && it.entry.id != "FooPreview" })
  }

  @Test
  fun `missing provider is isolated to its preview and writes an error card`() {
    System.setProperty("composeai.render.outputDir", tmp.root.absolutePath)

    val rows =
      PreviewManifestLoader.expandParameterProvider(
        entry("FooPreview", "com.example.testproviders.DoesNotExist")
      )

    // No rows for the broken preview — but crucially no throw, so sibling previews still load.
    assertTrue(rows.isEmpty())
    // The failure surfaces as a per-preview error card at the base output path.
    assertTrue(File(tmp.root, "FooPreview.png.error.json").exists())
  }

  @Test
  fun `provider whose getValues throws is isolated and carded`() {
    System.setProperty("composeai.render.outputDir", tmp.root.absolutePath)

    val rows =
      PreviewManifestLoader.expandParameterProvider(
        entry("FooPreview", "com.example.testproviders.ThrowingProvider")
      )

    assertTrue(rows.isEmpty())
    assertTrue(File(tmp.root, "FooPreview.png.error.json").exists())
  }

  @Test
  fun `a successful provider clears a stale error card from a prior failed run`() {
    System.setProperty("composeai.render.outputDir", tmp.root.absolutePath)
    // Simulate a leftover card from a run when the provider was still broken.
    val staleCard = File(tmp.root, "FooPreview.png.error.json")
    staleCard.writeText("{}")

    val rows =
      PreviewManifestLoader.expandParameterProvider(
        entry("FooPreview", "com.example.testproviders.PrivateStringProvider")
      )

    assertEquals(3, rows.size)
    assertFalse("stale error card should be cleared once the provider loads", staleCard.exists())
  }
}
