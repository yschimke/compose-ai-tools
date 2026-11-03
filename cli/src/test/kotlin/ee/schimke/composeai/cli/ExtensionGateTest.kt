package ee.schimke.composeai.cli

import ee.schimke.composeai.previewdata.Capture
import ee.schimke.composeai.previewdata.PreviewInfo
import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewModule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A plain `show` must not attach `accessibility.json` left by an earlier `a11y` run. Asking for the
 * extension (`--with-extension a11y`, or the `a11y` command) still reads it (#6341).
 */
class ExtensionGateTest {

  private class ShowHarness(args: List<String>) : Command(args) {
    override fun run() = Unit

    fun extensions(module: PreviewModule, manifest: PreviewManifest) =
      buildResults(listOf(module to manifest)).single().dataExtensions
  }

  private class A11yHarness(args: List<String>) : A11yCommand(args) {
    fun extensions(module: PreviewModule, manifest: PreviewManifest) =
      buildResults(listOf(module to manifest)).single().dataExtensions
  }

  @Test
  fun `show without the extension ignores a leftover accessibility report`() {
    val (module, manifest) = fixture()
    val plain = ShowHarness(emptyList()).extensions(module, manifest)
    assertNull(plain["a11y"])
    val asked = ShowHarness(listOf("--with-extension", "a11y")).extensions(module, manifest)
    assertTrue("a11y" in asked)
    val command = A11yHarness(emptyList()).extensions(module, manifest)
    assertTrue("a11y" in command)
  }

  private fun fixture(): Pair<PreviewModule, PreviewManifest> {
    val projectDir = Files.createTempDirectory("extension-gate").toFile()
    val renders = projectDir.resolve("build/compose-previews/renders")
    renders.mkdirs()
    renders.resolve("Foo.png").writeBytes(byteArrayOf(1, 2, 3))
    projectDir
      .resolve("build/compose-previews/accessibility.json")
      .writeText(
        """
        {"module":"app","entries":[{"previewId":"Foo","findings":[{"level":"ERROR","type":"TouchTargetSize","message":"small"}]}]}
        """
          .trimIndent()
      )
    val module = PreviewModule("app", projectDir)
    val manifest =
      PreviewManifest(
        module = "app",
        variant = "debug",
        previews =
          listOf(
            PreviewInfo(
              id = "Foo",
              functionName = "Foo",
              className = "com.example.PreviewsKt",
              captures = listOf(Capture(renderOutput = "renders/Foo.png")),
            )
          ),
      )
    return module to manifest
  }
}
