package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
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
 * Regression for #2615: the plain viewport figma-svg export used to emit `TransformingLazyColumn`
 * items at their **intrinsic, un-transformed** size while honouring the **compressed** placement
 * offsets Wear's edge scaling produces — so the shrunken items near the round face's edges were
 * drawn at full height at squeezed positions and visually overlapped/merged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigmaSvgWearTlcOverlapTest {

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("figma-svg-wear-tlc").toFile()
    System.setProperty("roborazzi.test.record", "true")
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
    System.clearProperty("roborazzi.test.record")
  }

  @Test
  fun `edge-scaled TransformingLazyColumn items do not overlap in the exported svg`() {
    val svg =
      renderSvg("wear-tlc-overlap") {
        MaterialTheme {
          AppScaffold {
            val state = rememberTransformingLazyColumnState()
            val spec = rememberTransformationSpec()
            ScreenScaffold(scrollState = state) { contentPadding ->
              TransformingLazyColumn(
                state = state,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
              ) {
                items(LABELS.size) { index ->
                  TitleCard(
                    onClick = {},
                    title = { Text(LABELS[index]) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                  )
                }
              }
            }
          }
        }
      }

    File("build/figma-svg-wear-tlc").mkdirs()
    File("build/figma-svg-wear-tlc/wear-tlc-overlap.svg").writeText(svg)

    val rects = cardRects(svg)
    assertTrue("expected at least 3 card rects in the export:\n$svg", rects.size >= 3)
    val overlaps =
      rects.zipWithNext().filter { (upper, lower) -> lower.first < upper.second - TOLERANCE_PX }
    assertTrue(
      "edge-scaled TLC items must not overlap; got ${rects.map { it.first to it.second }}:\n$svg",
      overlaps.isEmpty(),
    )
  }

  /** `(top, bottom)` of every card-sized `<rect>` in the export, sorted top-down. */
  private fun cardRects(svg: String): List<Pair<Double, Double>> =
    RECT.findAll(svg)
      .map { m ->
        val y = m.groupValues[1].toDouble()
        y to y + m.groupValues[2].toDouble()
      }
      // Only the card containers — ignore hairline dividers/indicators and the full-face background.
      .filter { (top, bottom) -> bottom - top in MIN_CARD_PX..MAX_CARD_PX }
      .sortedBy { it.first }
      .toList()

  private fun renderSvg(previewId: String, content: @Composable () -> Unit): String {
    RuntimeEnvironment.setQualifiers("w227dp-h227dp-round-mdpi")
    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()
    var svg = ""
    val statement =
      object : Statement() {
        override fun evaluate() {
          val slots = mutableSetOf<CompositionData>()
          rule.setContent { InspectableTlcContent(slots, content) }
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
            roundClip = true,
          )
          svg = File(rootDir, "$previewId/compose-figma.svg").readText()
          File("build/figma-svg-wear-tlc").mkdirs()
          frame.copyTo(File("build/figma-svg-wear-tlc/$previewId-frame.png"), overwrite = true)
        }
      }
    rule.apply(statement, Description.createTestDescription(javaClass, previewId)).evaluate()
    return svg
  }

  private companion object {
    val LABELS = listOf("KotlinConf", "Fosdem", "droidcon", "Droidcon SF", "Android Makers")
    val RECT = Regex("""<rect\b[^>]*\by="(-?[\d.]+)"[^>]*\bheight="(-?[\d.]+)"""")
    // Rounding in the export means a shared edge can land a pixel off; a real overlap is many px.
    const val TOLERANCE_PX = 2.0
    const val MIN_CARD_PX = 24.0
    const val MAX_CARD_PX = 150.0
  }
}

@OptIn(InternalComposeApi::class)
@Composable
private fun InspectableTlcContent(
  capture: MutableSet<CompositionData>,
  content: @Composable () -> Unit,
) {
  currentComposer.collectParameterInformation()
  capture.add(currentComposer.compositionData)
  CompositionLocalProvider(LocalInspectionTables provides capture, content = content)
}
