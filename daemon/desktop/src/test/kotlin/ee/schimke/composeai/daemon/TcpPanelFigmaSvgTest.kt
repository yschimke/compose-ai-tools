@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package ee.schimke.composeai.daemon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.data.layoutinspector.FigmaSvgLayer
import ee.schimke.composeai.data.layoutinspector.FigmaSvgModel
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorNode
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorPayload
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end guard for the figma-svg export of a card form (icon + two `OutlinedTextField`s + a
 * `Button`) — the meshcore-mobile `TcpConnectPanel` shape that regressed to a collapsed SVG when a
 * subcomposed field/button was captured with zero-area bounds. Renders the panel through the real
 * capture (`LayoutInspectorDataProducer.writeArtifacts`) and the production model builder
 * (`FigmaSvgModel.from` with `DEFAULT_RASTER_COMPONENTS`), then asserts every drawn layer and
 * raster target has a positive area — no `<image>`/`<rect>` collapses to 0×0. Companion to the
 * synthetic `FigmaSvgZeroBoundsTest` in `data-layoutinspector-core`.
 */
class TcpPanelFigmaSvgTest {
  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("tcp-panel-figma-svg").toFile()
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
  }

  private fun capture(
    width: Int,
    height: Int,
    density: Float,
    content: @Composable () -> Unit,
  ): LayoutInspectorNode {
    val scene =
      ImageComposeScene(
        width = width,
        height = height,
        density = Density(density),
        content = content,
      )
    try {
      scene.render()
      val root: SemanticsNode = scene.semanticsOwners.first().unmergedRootSemanticsNode
      LayoutInspectorDataProducer.writeArtifacts(
        rootDir = rootDir,
        previewId = "preview",
        root = root,
        density = density,
      )
    } finally {
      scene.close()
    }
    val file = rootDir.resolve("preview").resolve(LayoutInspectorDataProducer.FILE)
    return Json.decodeFromString(LayoutInspectorPayload.serializer(), file.readText()).root
  }

  @Test
  fun `the tcp connect panel exports no zero-area layers or raster targets`() {
    val density = 2f
    val root =
      capture(width = 680, height = 640, density = density) { MaterialTheme { TcpPanel() } }
    val model =
      FigmaSvgModel.from(
        layout = LayoutInspectorPayload(root),
        density = density,
        rasterComponents = FigmaSvgModel.DEFAULT_RASTER_COMPONENTS,
        captureCanvasDraws = true,
      )

    fun flatten(layer: FigmaSvgLayer): List<FigmaSvgLayer> = buildList {
      add(layer)
      layer.children.forEach { addAll(flatten(it)) }
    }

    val degenerateDrawn =
      flatten(model.root).filter {
        (it.fill != null || it.raster != null) && (it.right <= it.left || it.bottom <= it.top)
      }
    assertTrue(
      "no drawn layer may collapse to zero area; offenders=" +
        degenerateDrawn.map { "${it.name}(${it.left},${it.top},${it.right},${it.bottom})" },
      degenerateDrawn.isEmpty(),
    )

    val degenerateRasters =
      model.rasterTargets.filter { it.right <= it.left || it.bottom <= it.top }
    assertTrue(
      "no raster target may collapse to zero area; offenders=" +
        degenerateRasters.map { "${it.nodeId}(${it.left},${it.top},${it.right},${it.bottom})" },
      degenerateRasters.isEmpty(),
    )

    // The panel's chrome must actually be present: two text-field rasters + the icon raster, and
    // the button's filled pill as a vector layer.
    assertTrue(
      "expected at least the icon + two text-field raster targets (got ${model.rasterTargets.size})",
      model.rasterTargets.size >= 3,
    )
    assertTrue(
      "the button fill must survive as a positive-area vector layer",
      flatten(model.root).any { it.fill != null && it.right > it.left && it.bottom > it.top },
    )
  }
}

/**
 * A minimal stand-in for the `Icons.Rounded.Lan` glyph so the icon node rasters like the real one.
 */
private val LanIcon: ImageVector =
  ImageVector.Builder(
      name = "Lan",
      defaultWidth = 24.dp,
      defaultHeight = 24.dp,
      viewportWidth = 24f,
      viewportHeight = 24f,
    )
    .apply {
      path(fill = SolidColor(Color.Black)) {
        moveTo(3f, 3f)
        lineTo(21f, 3f)
        lineTo(21f, 10f)
        lineTo(3f, 10f)
        close()
      }
    }
    .build()

@Composable
private fun TcpPanel() {
  var host by remember { mutableStateOf("192.168.1.10") }
  var port by remember { mutableStateOf("5000") }
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = LanIcon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(8.dp))
        Text("Companion over TCP", style = MaterialTheme.typography.titleSmall)
      }
      OutlinedTextField(
        value = host,
        onValueChange = { host = it },
        label = { Text("Host") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = port,
        onValueChange = { port = it },
        label = { Text("Port") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
    }
  }
}
