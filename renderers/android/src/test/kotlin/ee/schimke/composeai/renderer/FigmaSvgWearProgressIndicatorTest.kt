package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SegmentedCircularProgressIndicator
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
 * Wear's determinate progress indicators are a `Spacer` whose entire appearance is one
 * `Modifier.drawWithCache { onDrawWithContent { drawArc(...) } }` — arcs and dots in flat solid
 * colours, exactly the primitives the draw recorder exists to translate. They exported as a flat
 * `<image>` anyway, because a `drawWithCache`'s `onBuildDrawCache` is a *builder* (it takes a
 * `CacheDrawScope` and returns a `DrawResult`) and re-invoking it as if it were a draw lambda
 * throws, aborting the capture. Their `Canvas`-drawn siblings — `ArcProgressIndicator`,
 * `LinearProgressIndicator` — vectorised all along, which is what made the raster stand out
 * (yschimke/wear-m3-catalog#62).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigmaSvgWearProgressIndicatorTest {

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("figma-svg-wear-progress").toFile()
    System.setProperty("roborazzi.test.record", "true")
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
    System.clearProperty("roborazzi.test.record")
  }

  @Test
  fun `a segmented circular progress indicator exports as editable arcs`() {
    val svg =
      renderSvg("wear-segmented-progress") {
        MaterialTheme {
          Box(Modifier.size(120.dp)) {
            SegmentedCircularProgressIndicator(
              segmentCount = 3,
              progress = { 0.6f },
              modifier = Modifier.size(120.dp),
            )
          }
        }
      }

    assertFalse("the ring must not be flattened into a raster:\n$svg", svg.contains("<image "))
    // Three track segments plus the drawn part of the progress — every one a stroked arc.
    val arcs = Regex("""<path d="M[^"]*A[^"]*" fill="none" stroke=""").findAll(svg).count()
    assertTrue("expected the segment arcs as stroked paths, got $arcs:\n$svg", arcs >= 4)
    assertTrue(
      "the round stroke cap each segment is drawn with must survive:\n$svg",
      svg.contains("""stroke-linecap="round""""),
    )
  }

  @Test
  fun `a circular progress indicator exports as editable arcs`() {
    val svg =
      renderSvg("wear-circular-progress") {
        MaterialTheme {
          Box(Modifier.size(120.dp)) {
            CircularProgressIndicator(progress = { 0.6f }, modifier = Modifier.size(120.dp))
          }
        }
      }

    assertFalse("the ring must not be flattened into a raster:\n$svg", svg.contains("<image "))
    assertTrue(
      "the track and indicator arcs must export as stroked paths:\n$svg",
      Regex("""<path d="M[^"]*A[^"]*" fill="none" stroke=""").findAll(svg).count() >= 2,
    )
  }

  private fun renderSvg(previewId: String, content: @Composable () -> Unit): String {
    RuntimeEnvironment.setQualifiers("w227dp-h227dp-round-xhdpi")
    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()
    var svg = ""
    val statement =
      object : Statement() {
        override fun evaluate() {
          val slots = mutableSetOf<CompositionData>()
          rule.setContent { InspectableProgressContent(slots, content) }
          rule.waitForIdle()
          val frame = File(rootDir, "$previewId-frame.png")
          rule.onRoot().captureRoboImage(file = frame)
          val semRoot = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
          val ctx =
            PreviewContext.Builder(previewId, null, null, previewId)
              .rootForTest(semRoot.root as RootForTest)
              .addSlotTables(slots.toList())
              .parameterInformationCollected()
              .build()
          val layout = LayoutInspectorDataProducer.buildPayload(ctx, density = 2f)!!
          val semantics = ComposeSemanticsDataProducer.buildPayload(semRoot, density = 2f)
          ComposeFigmaSvgDataProducer.writeSvg(
            rootDir = rootDir,
            previewId = previewId,
            layout = layout,
            semantics = semantics,
            density = 2f,
            frameImage = frame,
          )
          val generated = File(rootDir, previewId)
          svg = File(generated, "compose-figma.svg").readText()
          // Staged for the PR's visual evidence: the render the export is compared against, and
          // the SVG itself.
          val evidence = File("build/figma-svg-wear-progress").apply { mkdirs() }
          frame.copyTo(File(evidence, "$previewId-frame.png"), overwrite = true)
          File(evidence, "$previewId.svg").writeText(svg)
          File(generated, "figma-raster")
            .takeIf { it.exists() }
            ?.copyRecursively(File(evidence, "$previewId-figma-raster"), overwrite = true)
        }
      }
    rule.apply(statement, Description.createTestDescription(javaClass, previewId)).evaluate()
    return svg
  }
}

@OptIn(InternalComposeApi::class)
@Composable
private fun InspectableProgressContent(
  capture: MutableSet<CompositionData>,
  content: @Composable () -> Unit,
) {
  currentComposer.collectParameterInformation()
  capture.add(currentComposer.compositionData)
  CompositionLocalProvider(LocalInspectionTables provides capture, content = content)
}
