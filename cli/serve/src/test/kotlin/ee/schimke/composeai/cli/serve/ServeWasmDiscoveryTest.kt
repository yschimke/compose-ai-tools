package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.previewdata.PreviewModule
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Moved here from `:cli`'s `BrowseCommandTest` with the code it covers.
 *
 * It used to construct a whole `ServeCommand` to reach a function that reads no flags. Now it calls
 * the function.
 */
class ServeWasmDiscoveryTest {
  @Test
  fun `wasm discovery connects an executable app to the preview module it depends on`() {
    val root = Files.createTempDirectory("browse-wasm").toFile().also { it.deleteOnExit() }
    val ui = File(root, "shared/ui").apply { mkdirs() }
    val web = File(root, "webApp").apply { mkdirs() }
    File(web, "build.gradle.kts")
      .writeText(
        """
        kotlin { wasmJs { browser(); binaries.executable() } }
        dependencies { implementation(project(":shared:ui")) }
        """
          .trimIndent()
      )
    val dist = File(web, "build/dist/wasmJs/productionExecutable").apply { mkdirs() }
    File(dist, "index.html").writeText("<html></html>")

    val project = discoverWasmProjects(root, listOf(PreviewModule("custom:web", web))).single()
    assertEquals("custom:web", project.gradlePath)
    assertNull(project.distribution(), "ordinary Wasm apps must not be auto-selected")
    File(dist, "compose-preview-components.json").writeText("{\"protocol\":1}")
    assertEquals(dist, project.distribution())
    assertEquals(true, project.supports(PreviewModule("shared:ui", ui)))
    assertNull(project.takeIf { it.supports(PreviewModule("other", File(root, "other"))) })
    assertNotNull(project.distribution())
  }
}
