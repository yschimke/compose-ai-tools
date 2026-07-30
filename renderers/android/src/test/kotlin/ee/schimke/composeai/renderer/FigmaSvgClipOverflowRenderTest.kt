package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import org.junit.After
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
 * Reproduction probe for issue #2852: a child intentionally placed beyond a `Modifier.clip(shape)`
 * container — the shape of Jetsnack Search/Categories, whose minimum-size image runs past the card
 * under `.clip(CategoryShape)`. The render clips the overflow to the rounded card; the export must
 * mask the child with a `<clipPath>` and keep the canvas at the card box instead of growing it out
 * to the overflowing child.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigmaSvgClipOverflowRenderTest {

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("figma-svg-clip-overflow").toFile()
    System.setProperty("roborazzi.test.record", "true")
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
    System.clearProperty("roborazzi.test.record")
  }

  @Test
  fun `an overflowing child under a rounded clip is masked and does not grow the canvas`() {
    val svg =
      exportSvg("clip-overflow") {
        // A 100dp rounded card that clips its content, holding a 70dp block offset so it runs 40dp
        // off the right edge — the render clips it to the rounded card, the export must too.
        Box(
          Modifier.size(100.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFFEEEEEE))
        ) {
          Box(Modifier.offset(x = 70.dp).size(70.dp).background(Color(0xFF3366CC)))
        }
      }

    // The clip fired end-to-end: modifier → token → model → serializer.
    assertTrue("a clipPath must be emitted for the overflowing clip:\n$svg", svg.contains("<clipPath id=\"clip-"))
    assertTrue("the clipping group must reference it:\n$svg", svg.contains("clip-path=\"url(#clip-"))

    // The canvas is the card (100px) plus the export's transparent padding on each side, NOT the
    // overflow bbox. The child ran to x=140; unclipped the canvas would be ~140 + padding, clipped
    // it stays at ~100 + padding. The threshold cleanly separates the two.
    val width =
      Regex("""<svg[^>]*\bwidth="(\d+)"""").find(svg)!!.groupValues[1].toInt()
    assertTrue(
      "canvas must clamp to the card, not grow to the overflowing child (got width=$width):\n$svg",
      width <= 140,
    )
  }

  private fun exportSvg(previewId: String, content: @Composable () -> Unit): String {
    RuntimeEnvironment.setQualifiers("w160dp-h160dp-mdpi")
    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()
    var svg = ""
    val statement =
      object : Statement() {
        override fun evaluate() {
          val slotTables = mutableSetOf<CompositionData>()
          rule.setContent { InspectableContentClipOverflow(slotTables, content) }
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
            frameImage = frameFile,
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
private fun InspectableContentClipOverflow(
  capture: MutableSet<CompositionData>,
  content: @Composable () -> Unit,
) {
  currentComposer.collectParameterInformation()
  capture.add(currentComposer.compositionData)
  CompositionLocalProvider(LocalInspectionTables provides capture, content = content)
}
