package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
 * Reproduction probe for issue #2852: a `padding → clip → gradient border → background` chain like
 * Jetsnack's `JetsnackGradientTintedIconButton`. The gradient ring must be drawn at the inner,
 * padded control box, not the outer padded root.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigmaSvgPaddingClipRenderTest {

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("figma-svg-padding-clip").toFile()
    System.setProperty("roborazzi.test.record", "true")
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
    System.clearProperty("roborazzi.test.record")
  }

  @Test
  fun `padding before clip and gradient border rings the inner control, not the padded root`() {
    // The Jetsnack `JetsnackGradientTintedIconButton` chain: an outer padding, then the clip,
    // gradient border, and background that draw the control. At density 1 the node is 85px wide and
    // the 11px padding leaves a 63px control; the gradient ring must land on that inner control.
    val svg =
      exportSvg("padding-ring", withFrame = true) {
        Box(
          Modifier.size(85.dp)
            .padding(11.dp)
            .clip(CircleShape)
            .border(2.dp, Brush.linearGradient(listOf(Color.Cyan, Color.Magenta)), CircleShape)
            .background(Color(0xFF102030))
        )
      }

    // The brush ring must survive as a gradient-stroked shape (issue #2916), not vanish.
    val ring =
      Regex("""<rect[^>]*\bwidth="(\d+)"[^>]*stroke="url\(#[^"]+\)"""").find(svg)
        ?: Regex("""<rect[^>]*stroke="url\(#[^"]+\)"[^>]*\bwidth="(\d+)"""").find(svg)
    assertTrue("a gradient-stroked ring must be emitted:\n$svg", ring != null)
    val width = ring!!.groupValues[1].toInt()
    // The inner 63px control (minus a 1px stroke inset each side ≈ 61), NOT the padded 85px root
    // (which would draw the ring ~83px wide — the pre-fix defect).
    assertTrue(
      "the ring must ring the inner control (~61px), not the padded root (~83px), got $width:\n$svg",
      width in 55..70,
    )
  }

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
          rule.setContent { InspectableContentPaddingClip(slotTables, content) }
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
private fun InspectableContentPaddingClip(
  capture: MutableSet<CompositionData>,
  content: @Composable () -> Unit,
) {
  currentComposer.collectParameterInformation()
  capture.add(currentComposer.compositionData)
  CompositionLocalProvider(LocalInspectionTables provides capture, content = content)
}
