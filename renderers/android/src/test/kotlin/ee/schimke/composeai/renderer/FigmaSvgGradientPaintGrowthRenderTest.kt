package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.daemon.ComposeFigmaSvgDataProducer
import ee.schimke.composeai.daemon.ComposeSemanticsDataProducer
import ee.schimke.composeai.daemon.LayoutInspectorDataProducer
import ee.schimke.composeai.data.render.PreviewContext
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Where a brush-filled container's shape is drawn, for the two orderings of `background(brush)` and
 * `padding` (issue #3569). A `Modifier.background(brush, …)` resolves no flat
 * `ComposeSemanticsTokens.backgroundColor` — the brush rides on `backgroundGradient` (issue #2852)
 * — so the two must be told apart by the same signals that separate them for a flat fill, not by
 * the fill's kind.
 *
 * Both chains place the node at the same inner box, so the placed `bounds` alone cannot tell them
 * apart; only the modifier order can.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigmaSvgGradientPaintGrowthRenderTest {

  private lateinit var rootDir: File

  private val brush = Brush.horizontalGradient(listOf(Color(0xFFFFD846), Color(0xFFFEB525)))

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("figma-svg-gradient-growth").toFile()
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
  }

  @Test
  fun `a brush painted before the padding fills the whole node`() {
    // Pocket Casts' `GradientRowButton`: the brush covers the node and the padding insets only the
    // label. Before the fix this exported as the inner 88×48 box — a pill floating inside the
    // button the PNG paints edge to edge.
    val svg =
      exportSvg("brush-then-padding") {
        Box(
          Modifier.size(120.dp, 80.dp)
            .background(brush, RoundedCornerShape(12.dp))
            .clickable {}
            .padding(16.dp)
        )
      }
    assertEquals("gradient rect geometry:\n$svg", Rect(0, 0, 120, 80), gradientRect(svg))
  }

  @Test
  fun `a brush painted after the padding stays in the padded box`() {
    // The mirror ordering: the padding leads the brush, so the fill really is the inner 88×48 box
    // and must not be grown back to the node's measured size. A shape-bearing `clip` ahead of the
    // padding paints nothing, so it must not be read as proof that the padding trails the fill.
    val svg =
      exportSvg("padding-then-brush") {
        Box(
          Modifier.size(120.dp, 80.dp)
            .clip(RoundedCornerShape(12.dp))
            .padding(16.dp)
            .background(brush, RoundedCornerShape(12.dp))
        )
      }
    assertEquals("gradient rect geometry:\n$svg", Rect(16, 16, 88, 48), gradientRect(svg))
  }

  @Test
  fun `a shadow ahead of the padding keeps the layer on its outer box`() {
    // A positive elevation paints, unlike a clip: Compose draws this shadow at the outer 120×80
    // box. The exported layer carries one rect for both the fill and its `feDropShadow`, so this
    // chain cannot satisfy both — the outer box wins, which is what the export did before #3569.
    val svg =
      exportSvg("shadow-then-padding-then-brush") {
        Box(
          Modifier.size(120.dp, 80.dp)
            .shadow(8.dp, RoundedCornerShape(12.dp))
            .padding(16.dp)
            .background(brush, RoundedCornerShape(12.dp))
        )
      }
    assertEquals("gradient rect geometry:\n$svg", Rect(0, 0, 120, 80), gradientRect(svg))
  }

  private data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

  /** The `x/y/width/height` of the first `<rect>` filled by a gradient reference. */
  private fun gradientRect(svg: String): Rect {
    val m =
      Regex(
          """<rect x="(-?[\d.]+)" y="(-?[\d.]+)" width="([\d.]+)" height="([\d.]+)"[^>]*fill="url\(#"""
        )
        .find(svg) ?: error("no gradient-filled rect in:\n$svg")
    val (x, y, w, h) = m.destructured
    return Rect(
      x.toDouble().toInt(),
      y.toDouble().toInt(),
      w.toDouble().toInt(),
      h.toDouble().toInt(),
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
          rule.setContent { InspectableContentGradientGrowth(slotTables, content) }
          rule.waitForIdle()
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
private fun InspectableContentGradientGrowth(
  capture: MutableSet<CompositionData>,
  content: @Composable () -> Unit,
) {
  currentComposer.collectParameterInformation()
  capture.add(currentComposer.compositionData)
  CompositionLocalProvider(LocalInspectionTables provides capture, content = content)
}
