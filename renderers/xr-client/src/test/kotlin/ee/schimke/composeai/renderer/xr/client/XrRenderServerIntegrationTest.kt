package ee.schimke.composeai.renderer.xr.client

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue

/**
 * Real end-to-end: drives the actual native `xr-composite --serve` binary through [XrRenderServer]
 * (the Kotlin equivalent of `renderers/xr-composite/test/serve_smoke.py`). Gated on the binary
 * being resolvable (`XR_COMPOSITE_BIN` + materials) and a usable GL context — so it runs in the XR
 * CI job (Xvfb + the dist binary) and locally under `xvfb-run`, and skips cleanly everywhere else.
 */
class XrRenderServerIntegrationTest {

  private val json = Json { ignoreUnknownKeys = true }

  private fun repoRoot(): File {
    var dir = File(".").absoluteFile
    while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
      dir = dir.parentFile
    }
    return dir
  }

  @Test
  fun rendersFixtureAndUpdatesPanelPerFrame() {
    val binary = XrCompositeBinary.resolve()
    assumeTrue("XR_COMPOSITE_BIN not set / not found — skipping native integration", binary != null)
    val materials = XrCompositeBinary.resolveMaterials(binary!!)
    assumeTrue("xr-composite materials not found — skipping", materials != null)

    val fixtureDir = File(repoRoot(), "vscode-extension/preview-harness/fixtures/spatial-scene")
    val scene = json.parseToJsonElement(File(fixtureDir, "scene.json").readText())

    XrRenderServer.start(binary, materials!!, width = 640, height = 400).use { server ->
      assertEquals(true, server.capabilities["render"].toString().toBoolean())

      val first = server.render(scene, sceneDir = fixtureDir.path)
      assertEquals(1L, first.seq)
      assertTrue(first.width > 0 && first.height > 0)
      assertTrue(first.dataBase64.isNotEmpty())

      val moved =
        server.updatePanels(
          buildJsonArray {
            add(
              buildJsonObject {
                put("id", "top")
                put(
                  "poseInRoot",
                  buildJsonObject {
                    put(
                      "translation",
                      buildJsonObject {
                        put("x", 140)
                        put("y", 180)
                        put("z", 0)
                      },
                    )
                    put(
                      "rotation",
                      buildJsonObject {
                        put("x", 0)
                        put("y", 0)
                        put("z", 0)
                        put("w", 1)
                      },
                    )
                  },
                )
              }
            )
          }
        )
      assertEquals(2L, moved.seq)
      // Moving a panel must change the rendered image.
      assertTrue(moved.dataBase64 != first.dataBase64, "frame did not change after moving a panel")
    }
  }
}
