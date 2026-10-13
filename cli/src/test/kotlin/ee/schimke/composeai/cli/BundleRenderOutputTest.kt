package ee.schimke.composeai.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class BundleRenderOutputTest {
  @Test
  fun `explicit generated output filename preserves the successful image`() {
    val root = Files.createTempDirectory("bundle-output").toFile()
    try {
      val capture = root.resolve("a.Foo.png").apply { writeText("successful capture") }
      copyBundleRenderOutput(capture, root.resolve("./a.Foo.png"))
      assertEquals("successful capture", capture.readText())
      val named = root.resolve("named.png").apply { writeText("old capture") }
      copyBundleRenderOutput(capture, named)
      assertEquals("successful capture", named.readText())
      assertEquals("successful capture", capture.readText())
    } finally {
      root.deleteRecursively()
    }
  }
}
