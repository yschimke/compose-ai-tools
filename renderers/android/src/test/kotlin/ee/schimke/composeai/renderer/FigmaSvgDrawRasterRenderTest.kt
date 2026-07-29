package ee.schimke.composeai.renderer

import android.graphics.Paint
import android.graphics.RectF
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import ee.schimke.composeai.daemon.ComposeFigmaSvgDataProducer
import ee.schimke.composeai.daemon.ComposeSemanticsDataProducer
import ee.schimke.composeai.daemon.LayoutInspectorDataProducer
import ee.schimke.composeai.data.render.PreviewContext
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tier-3 end-to-end validation: a **container** that paints its chrome through the native canvas —
 * the shape every Remote Compose component the embedded player interprets takes (issue #2937), and
 * the one shape the export had no answer for.
 *
 * Neither existing tier reaches it. `VectorGraphicExtractor` needs an `ImageVector`;
 * `DrawCaptureExtractor`'s recorder aborts the moment the lambda touches `drawContext.canvas`, which
 * `drawIntoCanvas` does by definition. And the hybrid frame crop is restricted to childless leaves,
 * because cropping a container's box out of the composited frame bakes its descendants into the
 * `<image>` and then draws them a second time as vector. So the chrome used to vanish outright.
 *
 * What must hold now: the node's own draw is re-invoked against an offscreen bitmap and exported as
 * an `<image>`, *and* the container's text stays an editable `<text>` on top of it — the property
 * that makes an isolated capture different from a frame crop. Both lanes are checked, because the
 * isolated capture is the only raster the export can produce with **no frame at all**.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigmaSvgDrawRasterRenderTest {

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("figma-svg-draw-raster").toFile()
    System.setProperty("roborazzi.test.record", "true")
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
    System.clearProperty("roborazzi.test.record")
  }

  /** A rounded card painted straight onto the native canvas, exactly as an RC component does. */
  private fun Modifier.nativeCard(): Modifier = drawWithContent {
    drawIntoCanvas { canvas ->
      canvas.nativeCanvas.drawRoundRect(
        RectF(0f, 0f, size.width, size.height),
        12f,
        12f,
        Paint().apply { color = android.graphics.Color.rgb(0x67, 0x50, 0xA4) },
      )
    }
    drawContent()
  }

  @Test
  fun `a container drawn through the native canvas exports as an image with its text still editable`() {
    val svg =
      exportSvg("native-card", withFrame = true) {
        Box(Modifier.size(80.dp).nativeCard()) { Text("Hi") }
      }

    assertTrue("the container's own draw must export as an <image>:\n$svg", svg.contains("<image "))
    assertTrue("the container's text must stay editable:\n$svg", svg.contains("<text "))
    assertTrue("…and carry its content:\n$svg", svg.contains(">Hi<"))
    // The `<image>` is the node's isolated chrome, so it is drawn *beneath* the text rather than
    // standing in for the whole subtree the way an opaque frame crop does.
    assertTrue(
      "the raster must precede the text it sits under:\n$svg",
      svg.indexOf("<image ") < svg.indexOf("<text "),
    )
    // The PNG the `<image>` points at was written — a captured raster needs no frame crop, so this
    // is the connector's own bitmap landing on disk.
    val raster = File(rootDir, "native-card/figma-raster").listFiles().orEmpty()
    assertEquals("exactly one captured raster: ${raster.joinToString { it.name }}", 1, raster.size)
    assertTrue("the captured PNG is non-empty", raster.single().length() > 0)
  }

  @Test
  fun `the isolated capture needs no frame, so it survives the vector-only export`() {
    val svg =
      exportSvg("native-card-vector-only", withFrame = false) {
        Box(Modifier.size(80.dp).nativeCard()) { Text("Hi") }
      }

    // Vector-only mode crops nothing out of a frame (there is none), yet the chrome is still there:
    // its pixels rode along in the payload.
    assertTrue("the captured chrome survives without a frame:\n$svg", svg.contains("<image "))
    assertTrue("the text is still editable:\n$svg", svg.contains(">Hi<"))
    assertTrue(
      "the captured PNG is written in vector-only mode too",
      File(rootDir, "native-card-vector-only/figma-raster").listFiles().orEmpty().isNotEmpty(),
    )
  }

  @Test
  fun `a scaled node captures at its own resolution and is placed at the scaled bounds`() {
    // Under a `graphicsLayer` scale the node's placed bounds are already shrunk, but its draw
    // lambda still runs in the node's own coordinates. Replaying it at the shrunk size would scale
    // size-relative geometry while leaving absolute lengths (the 12px corner radius here) alone.
    // So the capture is taken at local resolution and the `<image>` carries the scaled bounds —
    // the renderer applies the one uniform scale to all of it.
    val svg =
      exportSvg("scaled-card", withFrame = true) {
        Box(Modifier.size(80.dp).graphicsLayer(scaleX = 0.5f, scaleY = 0.5f).nativeCard()) {
          Text("Hi")
        }
      }

    val raster = File(rootDir, "scaled-card/figma-raster").listFiles().orEmpty().single()
    val png = ImageIO.read(raster)
    val placed =
      Regex("""<image[^>]*\bwidth="(\d+)"[^>]*\bheight="(\d+)"""").find(svg)?.groupValues
    assertTrue("the capture is emitted as an <image>:\n$svg", placed != null)
    val placedWidth = placed!![1].toInt()
    // The bitmap holds the node's own 80px box; the `<image>` places it in the scaled ~40px slot.
    assertEquals("captured at local resolution", 80, png.width)
    assertTrue(
      "placed at the scaled bounds, got width=$placedWidth:\n$svg",
      placedWidth in 38..42,
    )
  }

  @Test
  fun `a pass-through drawWithContent captures nothing`() {
    val svg =
      exportSvg("pass-through", withFrame = true) {
        Box(Modifier.size(80.dp).drawWithContent { drawContent() }) { Text("Hi") }
      }

    // The capture is cut off at `drawContent()` and a fully transparent result is dropped, so the
    // overlay shape every tint/placeholder modifier lowers to adds no `<image>` — the node stays
    // pure vector, exactly as before.
    assertFalse("a pass-through draw must not raster:\n$svg", svg.contains("<image "))
    assertTrue("the text is untouched:\n$svg", svg.contains(">Hi<"))
  }

  /**
   * Renders [content], forces a draw so children z-sort, then runs the production capture + export.
   * With [withFrame] the export runs in hybrid mode (a frame PNG is supplied, as `RenderEngine`
   * supplies one); without it, vector-only.
   */
  private fun exportSvg(
    previewId: String,
    withFrame: Boolean,
    content: @Composable () -> Unit,
  ): String {
    RuntimeEnvironment.setQualifiers("w160dp-h160dp-mdpi")
    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()
    var svg = ""
    val statement =
      object : Statement() {
        override fun evaluate() {
          val slotTables = mutableSetOf<CompositionData>()
          rule.setContent { InspectableContent(slotTables, content) }
          rule.waitForIdle()
          val frameFile = File(rootDir, "$previewId-frame.png")
          frameFile.parentFile?.mkdirs()
          rule.onRoot().captureRoboImage(file = frameFile)
          val semanticsRoot = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
          val previewContext =
            PreviewContext.Builder(
                previewId = previewId,
                backend = null,
                renderMode = null,
                outputBaseName = previewId,
              )
              .rootForTest(semanticsRoot.root as RootForTest)
              .addSlotTables(slotTables.toList())
              .parameterInformationCollected()
              .build()
          val layout = LayoutInspectorDataProducer.buildPayload(previewContext, density = 1f)!!
          val semantics = ComposeSemanticsDataProducer.buildPayload(semanticsRoot, density = 1f)
          ComposeFigmaSvgDataProducer.writeSvg(
            rootDir = rootDir,
            previewId = previewId,
            layout = layout,
            semantics = semantics,
            density = 1f,
            frameImage = if (withFrame) frameFile else null,
          )
          svg = File(rootDir, "$previewId/compose-figma.svg").readText()
        }
      }
    rule.apply(statement, Description.createTestDescription(javaClass, previewId)).evaluate()
    return svg
  }
}

@OptIn(InternalComposeApi::class)
@Composable
private fun InspectableContent(
  capture: MutableSet<CompositionData>,
  content: @Composable () -> Unit,
) {
  currentComposer.collectParameterInformation()
  capture.add(currentComposer.compositionData)
  CompositionLocalProvider(LocalInspectionTables provides capture, content = content)
}
