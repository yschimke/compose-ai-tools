package ee.schimke.composeai.daemon

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Regression coverage for multiple valid Compose owner/root nodes (issue #2871). */
class MultipleSemanticsRootsRenderTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun rendersAndExportsDataFromTheActivitySurfaceWhenAPopupAddsAnotherRoot() {
    val outputDir = tempFolder.newFolder("renders-multiple-roots")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val previewId = "multiple-roots"
    val host =
      PreviewManifestRouter(
        manifest =
          PreviewManifest(
            previews =
              listOf(
                PreviewManifestEntry(
                  id = previewId,
                  className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
                  functionName = "MultipleSemanticsRoots",
                  params = PreviewParamsEntry(widthDp = 96, heightDp = 96, density = 1.0f),
                )
              )
          )
      )

    host.start()
    try {
      val result =
        host.submit(
          RenderRequest.Render(payload = "previewId=$previewId"),
          timeoutMs = 120_000,
        )
      assertNotNull("PNG path must be populated", result.pngPath)
      assertTrue("rendered PNG must exist", File(result.pngPath!!).isFile)

      val previewDataDir = outputDir.parentFile!!.resolve("data/$previewId")
      val rootDependentProducts =
        listOf(
          "compose-semantics.json",
          "compose-semantics-wireframe.svg",
          "layout-inspector.json",
          "compose-figma.svg",
          "i18n-translations.json",
          "uia-hierarchy.json",
        )
      for (name in rootDependentProducts) {
        assertTrue(
          "$name must survive the additional root; wrote ${previewDataDir.list()?.toList()}",
          previewDataDir.resolve(name).isFile,
        )
      }
      val semantics = previewDataDir.resolve("compose-semantics.json")
      val payload = semantics.readText()
      assertTrue(
        "the selected tree must be the activity surface: $payload",
        "activity-surface" in payload,
      )
    } finally {
      host.shutdown()
    }
  }
}
