package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
 * A lambda-form `graphicsLayer { … }` is evaluated against a recording proxy, and that evaluation
 * now outranks the coordinator's applied alpha. So the proxy has to answer `density` with the
 * node's real value: a block converting dp inside itself would otherwise resolve against an assumed
 * mdpi and publish an alpha the frame never used, on every other device (issue #3579, and the
 * review on #3589).
 *
 * The density reaches the proxy through `ModifierInfo.coordinates`, which is a `NodeCoordinator` —
 * a `MeasureScope`, hence a `Density`. This test exists because that is a *cast*, and a cast that
 * silently starts returning null would restore the old wrong answer with nothing else failing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigmaSvgLayerBlockDensityTest {

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("figma-svg-layer-density").toFile()
    System.setProperty("roborazzi.test.record", "true")
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
    System.clearProperty("roborazzi.test.record")
  }

  @Test
  fun `a dp-derived layer alpha resolves against the node's real density`() {
    // xhdpi, so density is 2. The box is 25dp = 50px tall and the block's threshold is 40dp.
    //   real density (2):    50px < 80px  -> the block assigns 0.5
    //   assumed mdpi (1):    50px < 40px  -> the block assigns 1.0 and the fade is lost
    // The two disagree only because the size is in px while the threshold is in dp, which is
    // exactly the shape that made an assumed density invisible in every other test.
    val svg = renderSvg("layer-block-density")
    File("build/figma-svg-layer-density").mkdirs()
    File("build/figma-svg-layer-density/layer-block-density.svg").writeText(svg)

    assertTrue(
      "the dp-derived alpha must resolve against density 2 and emit opacity 0.5; " +
        "an assumed mdpi drops the fade entirely:\n$svg",
      Regex("<g[^>]*\\bopacity=\"0\\.5\"").containsMatchIn(svg),
    )
  }

  private fun renderSvg(previewId: String): String {
    RuntimeEnvironment.setQualifiers("w400dp-h400dp-xhdpi")
    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()
    var svg = ""
    val statement =
      object : Statement() {
        override fun evaluate() {
          val slots = mutableSetOf<CompositionData>()
          rule.setContent {
            InspectableDensityContent(slots) {
              Box(
                Modifier.size(25.dp)
                  .graphicsLayer { alpha = if (size.height < 40.dp.toPx()) 0.5f else 1f }
                  .background(Color.Red)
              )
            }
          }
          rule.waitForIdle()
          val frame = File(rootDir, "$previewId-frame.png")
          frame.parentFile?.mkdirs()
          rule.onRoot().captureRoboImage(file = frame)
          val semRoot = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
          val ctx =
            PreviewContext.Builder(previewId, null, null, previewId)
              .rootForTest(semRoot.root as RootForTest)
              .addSlotTables(slots.toList())
              .parameterInformationCollected()
              .build()
          val layout = LayoutInspectorDataProducer.buildPayload(ctx, density = 2f)!!
          val sem = ComposeSemanticsDataProducer.buildPayload(semRoot, density = 2f)
          ComposeFigmaSvgDataProducer.writeSvg(
            rootDir = rootDir,
            previewId = previewId,
            layout = layout,
            semantics = sem,
            density = 2f,
            frameImage = frame,
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
private fun InspectableDensityContent(
  capture: MutableSet<CompositionData>,
  content: @Composable () -> Unit,
) {
  currentComposer.collectParameterInformation()
  capture.add(currentComposer.compositionData)
  CompositionLocalProvider(LocalInspectionTables provides capture, content = content)
}
