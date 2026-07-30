package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import ee.schimke.composeai.daemon.ComposeFigmaSvgDataProducer
import ee.schimke.composeai.daemon.ComposeSemanticsDataProducer
import ee.schimke.composeai.daemon.LayoutInspectorDataProducer
import ee.schimke.composeai.data.render.PreviewBackground
import ee.schimke.composeai.data.render.PreviewContext
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Issue #2974 — end-to-end PNG↔SVG parity for a dark `@Preview(showBackground = true)` whose only
 * drawn child is a hairline divider centred in a taller fixed-size `Box`.
 *
 * The render paints the backing colour across the whole window (exactly as production's
 * `RobolectricRenderTest` does — on the activity's `decorView`, not a wrapper composable) and crops
 * top-left, so the PNG fills the whole 100×26 crop with the dark surface. The layered-SVG export
 * must agree: its `showBackground` rect has to cover that same full crop, not shrink-wrap to the
 * ~1px divider extent. Before the fix the background rect was sized from the drawn-content extent,
 * so the SVG was transparent almost everywhere while the PNG was opaque — the two disagreed.
 *
 * Both artifacts are produced here from one capture, so this asserts the two halves against each
 * other: the PNG corners are the dark surface, and the SVG's background rect spans the full frame
 * with the same colour, even though the only child that paints is a single-pixel-tall line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigmaSvgShowBackgroundBoundsRenderTest {

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("figma-svg-show-background").toFile()
    System.setProperty("roborazzi.test.record", "true")
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
    System.clearProperty("roborazzi.test.record")
  }

  @Test
  fun `a thin divider does not shrink-wrap the dark showBackground fill`() {
    val nightArgb = PreviewBackground.NIGHT_ARGB // 0xFF1C1B1F
    val result =
      export(
        previewId = "divider-dark",
        widthPx = 100,
        heightPx = 26,
        backgroundArgb = nightArgb,
      ) {
        Box(Modifier.size(width = 100.dp, height = 26.dp)) {
          // The only child that paints: a hairline, translucent-white like a Material divider.
          Box(
            Modifier.align(Alignment.Center)
              .fillMaxWidth()
              .height(1.dp)
              .background(Color(0x1FFFFFFF))
          )
        }
      }

    // PNG half: every corner is the opaque dark surface — the full crop is background-filled.
    val png = ImageIO.read(result.frame)
    assertEquals("capture is the full fixed-size crop", 100, png.width)
    assertEquals(26, png.height)
    listOf(0 to 0, png.width - 1 to 0, 0 to png.height - 1, png.width - 1 to png.height - 1).forEach {
      (x, y) ->
      val argb = png.getRGB(x, y)
      assertEquals("corner ($x,$y) alpha", 0xff, (argb ushr 24) and 0xff)
      assertEquals("corner ($x,$y) rgb", nightArgb and 0xffffff, argb and 0xffffff)
    }

    // SVG half: the background rect spans the full 100×26 crop with the same dark fill…
    val svg = result.svg
    assertTrue(
      "the dark background must cover the whole crop, not the divider bounds:\n$svg",
      svg.contains("""<rect x="0" y="0" width="100" height="26" fill="#1C1B1F""""),
    )
    // …even though the only child that paints is a single-pixel-tall line (the regression scenario).
    assertTrue(
      "the divider is a hairline, so the background is not shrink-wrapped to it:\n$svg",
      Regex("""<rect[^>]*\bheight="1"[^>]*fill="#FFFFFF"""").containsMatchIn(svg),
    )
    // The canvas is sized to the full crop (+ the 16px export padding on each side), not the ~1px
    // divider extent.
    val canvasHeight = Regex("""<svg[^>]*\bheight="(\d+)"""").find(svg)?.groupValues?.get(1)?.toInt()
    assertEquals("canvas height covers the full crop", 26 + 16 * 2, canvasHeight)
  }

  private class ExportResult(val svg: String, val frame: File)

  /**
   * Renders [content] in a [widthPx]×[heightPx] window painted with [backgroundArgb] (the production
   * `decorView` background paint), captures the frame, then runs the real capture → figma-svg
   * export in hybrid mode with the frame supplied — the same path `RenderEngine` drives.
   */
  private fun export(
    previewId: String,
    widthPx: Int,
    heightPx: Int,
    backgroundArgb: Int,
    content: @Composable () -> Unit,
  ): ExportResult {
    RuntimeEnvironment.setQualifiers("w${widthPx}dp-h${heightPx}dp-mdpi")
    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()
    var svg = ""
    val frameFile = File(rootDir, "$previewId-frame.png")
    val statement =
      object : Statement() {
        override fun evaluate() {
          val slotTables = mutableSetOf<CompositionData>()
          rule.setContent { InspectableShowBackgroundContent(slotTables, content) }
          // Paint the backing colour on the window, exactly as production's RobolectricRenderTest
          // does for `showBackground` — the wrapper-free path that #2884 established.
          rule.runOnUiThread { rule.activity.window.decorView.setBackgroundColor(backgroundArgb) }
          rule.waitForIdle()
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
            frameImage = frameFile,
            deviceBackground = argbToHex(backgroundArgb),
          )
          svg = File(rootDir, "$previewId/compose-figma.svg").readText()
        }
      }
    rule.apply(statement, Description.createTestDescription(javaClass, previewId)).evaluate()
    return ExportResult(svg, frameFile)
  }

  private fun argbToHex(argb: Int): String = "#%08X".format(argb)
}

@OptIn(InternalComposeApi::class)
@Composable
private fun InspectableShowBackgroundContent(
  capture: MutableSet<CompositionData>,
  content: @Composable () -> Unit,
) {
  currentComposer.collectParameterInformation()
  capture.add(currentComposer.compositionData)
  CompositionLocalProvider(LocalInspectionTables provides capture, content = content)
}
