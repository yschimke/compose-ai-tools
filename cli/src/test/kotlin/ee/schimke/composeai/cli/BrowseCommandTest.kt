package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BrowseCommandTest {
  @Test
  fun `browse supplies the streamlined local-project defaults`() {
    assertEquals(
      listOf(
        "--module",
        ":app",
        "--discover",
        "--component-browser",
        "--no-history",
        "--open-browser",
      ),
      BrowseCommand.serveArgs(listOf("--module", ":app")),
    )
  }

  @Test
  fun `browse does not duplicate defaults already supplied`() {
    val args =
      listOf("--discover", "--component-browser", "--no-history", "--port", "9000", "--no-open")

    assertEquals(args.filterNot { it == "--no-open" }, BrowseCommand.serveArgs(args))
  }

  @Test
  fun `browse with no options discovers the whole project`() {
    assertEquals(
      listOf("--discover", "--component-browser", "--no-history", "--open-browser"),
      BrowseCommand.serveArgs(emptyList()),
    )
  }

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

    val project =
      ServeCommand(emptyList())
        .discoverWasmProjects(root, listOf(PreviewModule("custom:web", web)))
        .single()
    assertEquals("custom:web", project.gradlePath)
    assertNull(project.distribution(), "ordinary Wasm apps must not be auto-selected")
    File(dist, "compose-preview-components.json").writeText("{\"protocol\":1}")
    assertEquals(dist, project.distribution())
    assertEquals(true, project.supports(PreviewModule("shared:ui", ui)))
    assertNull(project.takeIf { it.supports(PreviewModule("other", File(root, "other"))) })
    assertNotNull(project.distribution())
  }
}
