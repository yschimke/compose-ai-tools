package ee.schimke.composeai.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins how the Android bundle render path reconciles produced PNGs back to previews: by the
 * manifest capture's `renderOutput` leaf — the exact name `RobolectricRenderTest.outputFileFor`
 * writes — not a name derived from the preview id. Getting this wrong silently marks successful
 * renders as failures (Codex review on #1651).
 */
class BundleRendererAndroidOutputTest {

  private fun preview(id: String, renderOutput: String): PreviewInfo =
    PreviewInfo(
      id = id,
      functionName = "CardPreview",
      className = "com.example.FooKt",
      captures = listOf(Capture(renderOutput = renderOutput)),
    )

  @Test
  fun `leaf is the basename of the capture renderOutput`() {
    // Discovery normalizes `com.example.FooKt.CardPreview` to a `renders/CardPreview.png` leaf.
    val p = preview("com.example.FooKt.CardPreview", "renders/CardPreview.png")
    assertEquals("CardPreview.png", BundleRenderer.androidOutputLeaf(p))
  }

  @Test
  fun `empty renderOutput falls back to the raw id dot png`() {
    // Matches RobolectricRenderTest.outputFileFor's `${preview.id}.png` fallback (raw id).
    val p = preview("MyPreview", "")
    assertEquals("MyPreview.png", BundleRenderer.androidOutputLeaf(p))
  }

  @Test
  fun `no captures falls back to the raw id dot png`() {
    val p =
      PreviewInfo(
        id = "Lonely",
        functionName = "Lonely",
        className = "pkg.Kt",
        captures = emptyList(),
      )
    assertEquals("Lonely.png", BundleRenderer.androidOutputLeaf(p))
  }

  @Test
  fun `filterPreviewsJson drops IR-backed previews and preserves other fields`() {
    val raw =
      """
      {"module":":app","previews":[
        {"id":"a.Foo","functionName":"Foo","className":"a.Kt"},
        {"id":"a.TilePreview","functionName":"TilePreview","className":"a.TileKt"}
      ]}
      """
        .trimIndent()

    val filtered = BundleRenderer.filterPreviewsJson(raw, drop = setOf("a.TilePreview"))

    // The IR-backed preview is gone; the classpath preview and top-level `module` survive.
    assertTrue(filtered.contains("\"a.Foo\""))
    assertFalse(filtered.contains("a.TilePreview"))
    assertTrue(filtered.contains("\":app\""))
  }

  @Test
  fun `filterPreviewsJson with an empty drop set keeps every preview`() {
    val raw = """{"module":":app","previews":[{"id":"a.Foo"},{"id":"a.Bar"}]}"""
    val filtered = BundleRenderer.filterPreviewsJson(raw, drop = emptySet())
    assertTrue(filtered.contains("a.Foo"))
    assertTrue(filtered.contains("a.Bar"))
  }

  private fun writePng(dir: File, leaf: String) {
    dir.resolve(leaf).writeBytes(byteArrayOf(1, 2, 3))
  }

  @Test
  fun `exit 0 with all PNGs present marks every preview succeeded`() {
    val dir = createTempDirectory("android-reconcile").toFile()
    val previews =
      listOf(preview("a.FooKt.One", "renders/One.png"), preview("a.FooKt.Two", "renders/Two.png"))
    writePng(dir, "One.png")
    writePng(dir, "Two.png")

    val (succeeded, failed) =
      BundleRenderer.reconcileAndroidRenders(previews, dir, exitCode = 0, tail = "")

    assertEquals(setOf("a.FooKt.One", "a.FooKt.Two"), succeeded.map { it.id }.toSet())
    assertTrue(failed.isEmpty())
  }

  @Test
  fun `non-zero exit keeps partial success for previews that produced a PNG`() {
    val dir = createTempDirectory("android-reconcile").toFile()
    val previews =
      listOf(preview("a.FooKt.One", "renders/One.png"), preview("a.FooKt.Two", "renders/Two.png"))
    // Only One rendered; Two never produced a PNG (e.g. it threw). Partial success is preserved.
    writePng(dir, "One.png")

    val (succeeded, failed) =
      BundleRenderer.reconcileAndroidRenders(previews, dir, exitCode = 1, tail = "boom")

    assertEquals(listOf("a.FooKt.One"), succeeded.map { it.id })
    assertEquals(listOf("a.FooKt.Two"), failed.map { it.id })
  }

  @Test
  fun `timeout fails every preview even when PNGs are present on disk`() {
    val dir = createTempDirectory("android-reconcile").toFile()
    val previews =
      listOf(preview("a.FooKt.One", "renders/One.png"), preview("a.FooKt.Two", "renders/Two.png"))
    // Both PNGs exist (written before the force-kill, or stale from a prior run), but exit 124
    // means the run never completed — so neither may be trusted.
    writePng(dir, "One.png")
    writePng(dir, "Two.png")

    val (succeeded, failed) =
      BundleRenderer.reconcileAndroidRenders(previews, dir, exitCode = 124, tail = "timed out")

    assertTrue(succeeded.isEmpty())
    assertEquals(setOf("a.FooKt.One", "a.FooKt.Two"), failed.map { it.id }.toSet())
    assertTrue(failed.all { it.exitCode == 124 })
  }
}
