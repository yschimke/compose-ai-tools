package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `.cli-state.json` across a **narrowed** render (issue #3730).
 *
 * Now that `--id` / `--filter` narrows the Gradle drive, the previews outside the request are
 * deliberately not re-rendered — and on a clean tree they have no PNG at all. The change-detection
 * state has to survive that: forgetting their sha would make the *next* full render report every
 * one of them as `changed`, which is precisely the signal the documented agent loop ("re-render,
 * read the entries marked changed") depends on being trustworthy.
 */
class NarrowedRenderStateTest {

  private class ResultHarness : Command(emptyList()) {
    override fun run() = Unit

    fun results(
      module: PreviewModule,
      manifest: PreviewManifest,
      renderedIds: Set<String>? = null,
    ): List<PreviewResult> = buildResults(listOf(module to manifest), renderedIds)
  }

  private fun png(dir: File, name: String, byte: Byte = 1) {
    dir.resolve(name).apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(byte, 2, 3))
  }

  /** `Kept` renders every run; `Skipped` is plain; `Param` fans out over a provider. */
  private fun manifest() =
    PreviewManifest(
      module = "app",
      variant = "debug",
      previews =
        listOf(
          PreviewInfo(
            id = "Kept",
            functionName = "Kept",
            className = "com.example.PreviewsKt",
            captures = listOf(Capture(renderOutput = "renders/Kept.png")),
          ),
          PreviewInfo(
            id = "Skipped",
            functionName = "Skipped",
            className = "com.example.PreviewsKt",
            captures = listOf(Capture(renderOutput = "renders/Skipped.png")),
          ),
          PreviewInfo(
            id = "Param",
            functionName = "Param",
            className = "com.example.PreviewsKt",
            params = PreviewParams(previewParameterProviderClassName = "com.example.Provider"),
            captures = listOf(Capture(renderOutput = "renders/Param.png")),
          ),
        ),
    )

  private fun fullRender(renders: File) {
    png(renders, "Kept.png")
    png(renders, "Skipped.png")
    png(renders, "Param_light.png")
    png(renders, "Param_dark.png")
  }

  @Test
  fun `previews a narrowed render skipped are not reported as changed by the next full render`() {
    val projectDir = Files.createTempDirectory("narrowed-render-state").toFile()
    val renders = projectDir.resolve("build/compose-previews/renders")
    val module = PreviewModule("app", projectDir)
    val manifest = manifest()

    // 1. A full render seeds state for everything.
    fullRender(renders)
    ResultHarness().results(module, manifest)

    // 2. `--id Kept` on a clean renders dir: only `Kept` comes back.
    renders.deleteRecursively()
    png(renders, "Kept.png")
    val narrowed = ResultHarness().results(module, manifest, renderedIds = setOf("Kept"))
    assertEquals(false, narrowed.single { it.id == "Kept" }.changed)
    // The skipped ones have nothing on disk, so they report no PNG and an unknown diff — never a
    // spurious `changed = true`.
    assertNull(narrowed.single { it.id == "Skipped" }.pngPath)
    assertNull(narrowed.single { it.id == "Skipped" }.changed)

    // 3. The next full render sees the same pixels as step 1, so nothing changed.
    fullRender(renders)
    val full = ResultHarness().results(module, manifest)
    assertEquals(false, full.single { it.id == "Skipped" }.changed)
    // The parameterized fan-out is the case the per-capture pass can't reach: with no files on disk
    // it has zero captures, so its `Param#<n>` keys only survive via `carryForwardSkippedState`.
    assertEquals(listOf(false, false), full.single { it.id == "Param" }.captures.map { it.changed })
  }

  @Test
  fun `a re-render that actually changes the pixels is still reported`() {
    val projectDir = Files.createTempDirectory("narrowed-render-state").toFile()
    val renders = projectDir.resolve("build/compose-previews/renders")
    val module = PreviewModule("app", projectDir)
    val manifest = manifest()

    fullRender(renders)
    ResultHarness().results(module, manifest)

    renders.deleteRecursively()
    png(renders, "Kept.png")
    ResultHarness().results(module, manifest, renderedIds = setOf("Kept"))

    // Carrying state forward must not blind the next run to a real diff: `Skipped` renders to
    // different bytes this time.
    png(renders, "Kept.png")
    png(renders, "Skipped.png", byte = 9)
    png(renders, "Param_light.png")
    png(renders, "Param_dark.png", byte = 9)
    val full = ResultHarness().results(module, manifest)

    assertEquals(true, full.single { it.id == "Skipped" }.changed)
    // Fan-out rows are ordered by label, so `_dark` (the one rewritten) is capture 0.
    assertEquals(listOf(true, false), full.single { it.id == "Param" }.captures.map { it.changed })
  }
}
