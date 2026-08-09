package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.Text
import ee.schimke.composeai.daemon.ComposeFigmaSvgDataProducer
import ee.schimke.composeai.daemon.ComposeSemanticsDataProducer
import ee.schimke.composeai.daemon.LayoutInspectorDataProducer
import ee.schimke.composeai.data.render.PreviewContext
import java.io.File
import java.nio.file.Files
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
 * Wear M3's `CompactButton` pads **both sides of its fill**: an 8dp vertical `padding` ahead of the
 * `paint` that draws the pill (the 48dp touch target it must not paint into) and a 12dp horizontal
 * one behind it (which the pill *does* cover). So its drawn pill is the node's **placed height**
 * but its **measured width** — 57×32 inside an 81×48 node.
 *
 * Two things had to hold for that to export correctly (issue #3573). Wear pads through
 * `Modifier.padding(PaddingValues)`, whose `PaddingValuesElement` exposes no per-edge `Dp`, so the
 * insets had to be read off the `PaddingValues` itself — without them nothing held the growth
 * heuristic off and the pill exported at the full 48dp touch target. And the suppression had to be
 * per axis, or the same padding would have squashed the pill to the 57dp content width.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigmaSvgWearCompactButtonRenderTest {

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("figma-svg-wear-compact").toFile()
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
  }

  @Test
  fun `the pill keeps its placed height and its measured width`() {
    val svg = exportSvg("wear-compact") { CompactButton(onClick = {}) { Text("Compact") } }
    // The pill is the rect carrying the container fill; the root frame paints nothing.
    val m =
      Regex(
          """<rect x="(-?[\d.]+)" y="(-?[\d.]+)" width="([\d.]+)" height="([\d.]+)"[^>]*fill="#E9DDFF""""
        )
        .find(svg) ?: error("no filled pill rect in:\n$svg")
    val (x, y, w, h) = m.destructured.toList().map { it.toDouble().toInt() }
    assertTrue("the pill must be a rounded pill:\n$svg", m.value.contains("""rx="18""""))
    // 32dp tall — the painted pill, NOT the 48dp touch target the node measures.
    assertEquals("pill height in:\n$svg", 32, h)
    assertEquals("pill top in:\n$svg", 8, y)
    // 81dp wide — the trailing 12dp padding on each side of the 57dp content box IS painted.
    assertEquals("pill width in:\n$svg", 81, w)
    assertEquals("pill left in:\n$svg", 0, x)
  }

  private fun exportSvg(previewId: String, content: @Composable () -> Unit): String {
    RuntimeEnvironment.setQualifiers("w192dp-h192dp-mdpi")
    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()
    var svg = ""
    val statement =
      object : Statement() {
        override fun evaluate() {
          val slotTables = mutableSetOf<CompositionData>()
          rule.setContent { InspectableWearCompactButton(slotTables, content) }
          rule.waitForIdle()
          val semanticsRoot = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
          val ctx =
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
          val layout = LayoutInspectorDataProducer.buildPayload(ctx, density = 1f)!!
          val semantics = ComposeSemanticsDataProducer.buildPayload(semanticsRoot, density = 1f)
          ComposeFigmaSvgDataProducer.writeSvg(
            rootDir = rootDir,
            previewId = previewId,
            layout = layout,
            semantics = semantics,
            density = 1f,
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
private fun InspectableWearCompactButton(
  capture: MutableSet<CompositionData>,
  content: @Composable () -> Unit,
) {
  currentComposer.collectParameterInformation()
  capture.add(currentComposer.compositionData)
  CompositionLocalProvider(LocalInspectionTables provides capture, content = content)
}
