package ee.schimke.composeai.cli

import ee.schimke.composeai.discovery.Capture
import ee.schimke.composeai.discovery.PreviewInfo
import ee.schimke.composeai.discovery.PreviewManifest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BazelCaptureReuseTest {
  @Test
  fun `captures rendered from earlier artifacts are never reused`() {
    val root = Files.createTempDirectory("bazel-capture-reuse").toFile()
    try {
      val app = root.resolve("app.jar").apply { writeText("first build") }
      val artifacts =
        BazelArtifacts(1, "//:app", "desktop", listOf(app.path), listOf(app.path)).resolve(root)
      val manifest =
        PreviewManifest(
          "//:app",
          "desktop",
          listOf(
            PreviewInfo(
              "example.App.Greeting",
              "Greeting",
              "example.App",
              captures = listOf(Capture(renderOutput = "renders/greeting.png")),
            )
          ),
        )
      val output = root.resolve("out").apply { mkdirs() }
      val prepared = PreparedBazelPreviews(manifest, output.resolve("previews.json"))
      val png = output.resolve("renders/greeting.png")
      val first = bazelArtifactsFingerprint(artifacts)
      // A capture with no recorded provenance (an older run, another tool) is not trusted.
      png.parentFile.mkdirs()
      png.writeText("unknown origin")
      assertFalse(bazelCapturesMatch(prepared, first))

      claimBazelCaptures(prepared, first)
      assertFalse(png.exists(), "Captures of unknown provenance are cleared")
      png.writeText("rendered from first build")
      assertTrue(bazelCapturesMatch(prepared, first))
      // Claiming the same artifacts again keeps what was rendered from them.
      claimBazelCaptures(prepared, first)
      assertEquals("rendered from first build", png.readText())

      // Same target/config, preview ids, size and mtime; different bytes (a cache restore or a
      // timestamp-preserving copy). The output directory is shared, so only content tells.
      val mtime = app.lastModified()
      app.writeText("first BUILD")
      app.setLastModified(mtime)
      assertEquals(mtime, app.lastModified())
      assertEquals("first build".length.toLong(), app.length())
      val second = bazelArtifactsFingerprint(artifacts)
      assertNotEquals(first, second)
      assertFalse(bazelCapturesMatch(prepared, second))
      claimBazelCaptures(prepared, second)
      assertFalse(png.exists(), "Stale capture must not survive a partial re-render")
      assertTrue(bazelCapturesMatch(prepared, second))
    } finally {
      root.deleteRecursively()
    }
  }
}
