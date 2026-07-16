package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import ee.schimke.composeai.daemon.ComposeFigmaSvgDataProducer
import ee.schimke.composeai.daemon.ComposeSemanticsDataProducer
import ee.schimke.composeai.daemon.LayoutInspectorDataProducer
import ee.schimke.composeai.data.render.PreviewContext
import java.io.File
import java.nio.file.Files
import org.junit.After
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
 * A `SegmentedButton`'s label must export as editable `<text>`, not a raster `<image>`. Its content
 * sits under a `MultiContentMeasurePolicyImpl` layout node whose name contains "icon" across the
 * "Mult**iCon**tent" seam; a case-insensitive opaque-by-name match rasterised the whole labelled
 * subtree as if it were an `Icon`. The match is case-sensitive (PascalCase-token) so that seam no
 * longer false-matches — the labels stay text and the selection checkmark vectorises.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigmaSvgSegmentedButtonTest {

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("figma-svg-segmented").toFile()
    System.setProperty("roborazzi.test.record", "true")
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
    System.clearProperty("roborazzi.test.record")
  }

  @Test
  fun `segmented button labels export as editable text, not raster crops`() {
    val svg =
      renderSvg("segmented") {
        SingleChoiceSegmentedButtonRow {
          SegmentedButton(
            selected = true,
            onClick = {},
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
          ) {
            Text("Enabled")
          }
          SegmentedButton(
            selected = false,
            onClick = {},
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
          ) {
            Text("Disabled")
          }
        }
      }

    File("build/figma-svg-segmented").mkdirs()
    File("build/figma-svg-segmented/segmented.svg").writeText(svg)

    assertTrue("the first label must be editable text:\n$svg", svg.contains(">Enabled</text>"))
    assertTrue("the second label must be editable text:\n$svg", svg.contains(">Disabled</text>"))
    assertFalse(
      "the labelled content must not raster as an <image> (the MultiContent 'iCon' seam):\n$svg",
      svg.contains("<image "),
    )
  }

  private fun renderSvg(previewId: String, content: @Composable () -> Unit): String {
    RuntimeEnvironment.setQualifiers("w400dp-h200dp-mdpi")
    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()
    var svg = ""
    val statement =
      object : Statement() {
        override fun evaluate() {
          val slots = mutableSetOf<CompositionData>()
          rule.setContent { InspectableSegContent(slots, content) }
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
          val layout = LayoutInspectorDataProducer.buildPayload(ctx, density = 1f)!!
          val sem = ComposeSemanticsDataProducer.buildPayload(semRoot, density = 1f)
          ComposeFigmaSvgDataProducer.writeSvg(
            rootDir = rootDir,
            previewId = previewId,
            layout = layout,
            semantics = sem,
            density = 1f,
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
private fun InspectableSegContent(
  capture: MutableSet<CompositionData>,
  content: @Composable () -> Unit,
) {
  currentComposer.collectParameterInformation()
  capture.add(currentComposer.compositionData)
  CompositionLocalProvider(LocalInspectionTables provides capture, content = content)
}
