package ee.schimke.composeai.renderer.xr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import ee.schimke.composeai.xr.SizeDp
import ee.schimke.composeai.xr.SpatialScene
import java.io.File
import kotlinx.serialization.json.Json

/** A panel's 2D content plus the size to render it at, for capturing the panel's texture. */
public class PanelTexture(
  public val id: String,
  public val sizeDp: SizeDp,
  public val content: @Composable () -> Unit,
)

/**
 * Writes the producer side of the SpatialScene render output: the per-panel texture PNGs and the
 * `scene.json` the VS Code 3D viewer consumes (see docs/design/SPATIAL_SCENE_CONTRACT.md and the
 * consumer in PR #1704). Both land in one directory; the scene's `<id>.png` texture references are
 * relative to it, which is the `textureBaseUri` the viewer resolves against.
 *
 * Must run under Robolectric with the capture properties the gradle plugin sets
 * (`robolectric.graphicsMode=NATIVE`, `pixelCopyRenderMode=hardware`, `roborazzi.test.record=true`)
 * — `captureRoboImage` rasterises the panel content there exactly as the Compose `@Preview` path
 * does. `SubspaceSceneRecorder` recovers the poses; this writes the textures + scene that match.
 */
public object SubspaceSceneWriter {

  private val json = Json {
    prettyPrint = true
    encodeDefaults = true
  }

  /**
   * Renders each [panels] entry's 2D content to `<id>.png` under [outDir] — the same `<id>.png`
   * convention `SubspaceSceneRecorder` stamps into each panel's `texture`, so the scene and its
   * textures line up.
   */
  public fun captureTextures(outDir: File, panels: List<PanelTexture>) {
    outDir.mkdirs()
    for (panel in panels) {
      captureRoboImage(File(outDir, "${panel.id}.png").absolutePath) {
        Box(Modifier.size(panel.sizeDp.width.dp, panel.sizeDp.height.dp)) { panel.content() }
      }
    }
  }

  /** Serialises [scene] to `scene.json` under [outDir] in the wire-contract shape. */
  public fun writeScene(outDir: File, scene: SpatialScene): File {
    outDir.mkdirs()
    val file = File(outDir, "scene.json")
    file.writeText(json.encodeToString(SpatialScene.serializer(), scene))
    return file
  }
}
