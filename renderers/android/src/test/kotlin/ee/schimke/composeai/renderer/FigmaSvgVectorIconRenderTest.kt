package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
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
 * Tier-1 end-to-end validation + measurement: renders a real `Icon(ImageVector)` through the
 * Robolectric render (measure + draw + z-sort), then runs the production figma-svg export in
 * **hybrid raster mode** (a frame PNG is passed, so `DEFAULT_RASTER_COMPONENTS` is active and an
 * `Icon` is opaque-by-name — the pre-change behaviour cropped it as an `<image>`). It asserts the
 * export now emits the icon as an editable `<path>` and schedules **no** raster crop for it. This is
 * the on-device counterpart of the synthetic `FigmaSvgVectorIconTest` in `:data-layoutinspector-core`
 * — it exercises `VectorGraphicExtractor`'s reflection against the *live* `VectorPainter` tree that a
 * material `Icon` builds, which the synthetic test cannot cover.
 *
 * A draw is forced (`captureRoboImage`) before reading the tree because the layout inspector reflects
 * over `LayoutNode.getZSortedChildren`, empty until measure/draw z-sort the children — the same seam
 * `WearScrollSvgGrowthTest` relies on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigmaSvgVectorIconRenderTest {

  private lateinit var rootDir: File

  // The wear-m3 catalog's `catalogIcon` verbatim: a hand-built five-point star with a single solid
  // fill over a 24-unit viewport — precisely the Tier-1 vectorization case.
  private val catalogStar: ImageVector =
    ImageVector.Builder(
        name = "Star",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
      )
      .apply {
        path(fill = SolidColor(Color.White)) {
          moveTo(12f, 2f)
          lineTo(15.1f, 8.3f)
          lineTo(22f, 9.3f)
          lineTo(17f, 14.1f)
          lineTo(18.2f, 21f)
          lineTo(12f, 17.8f)
          lineTo(5.8f, 21f)
          lineTo(7f, 14.1f)
          lineTo(2f, 9.3f)
          lineTo(8.9f, 8.3f)
          close()
        }
      }
      .build()

  // A single gradient-filled square: its `Brush` fill can't be lowered to a flat colour, so the
  // whole graphic must raster rather than vectorise into an empty/partial icon (#2504 P2).
  private val gradientVector: ImageVector =
    ImageVector.Builder(
        name = "Grad",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
      )
      .apply {
        path(fill = Brush.linearGradient(listOf(Color.Red, Color.Blue))) {
          moveTo(0f, 0f)
          lineTo(24f, 0f)
          lineTo(24f, 24f)
          lineTo(0f, 24f)
          close()
        }
      }
      .build()

  // An ImageVector carrying its own `tintColor`, drawn via `Image` (no external `Icon` tint): its
  // intrinsic colour filter lives on the VectorComponent, so the export must recolour to that tint
  // rather than the source white (#2506 review).
  private val intrinsicallyTintedSquare: ImageVector =
    ImageVector.Builder(
        name = "TintedSquare",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        tintColor = Color(0xFF445566),
      )
      .apply {
        path(fill = SolidColor(Color.White)) {
          moveTo(2f, 2f)
          lineTo(22f, 2f)
          lineTo(22f, 22f)
          lineTo(2f, 22f)
          close()
        }
      }
      .build()

  // A single path with a solid fill AND a gradient stroke: `pathOf` could vectorise the solid fill
  // and silently drop the gradient stroke, so the whole icon must raster instead (#2505 review).
  private val mixedPaintSquare: ImageVector =
    ImageVector.Builder(
        name = "Mixed",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
      )
      .apply {
        path(
          fill = SolidColor(Color.Red),
          stroke = Brush.linearGradient(listOf(Color.Green, Color.Blue)),
          strokeLineWidth = 2f,
        ) {
          moveTo(2f, 2f)
          lineTo(22f, 2f)
          lineTo(22f, 22f)
          lineTo(2f, 22f)
          close()
        }
      }
      .build()

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("figma-svg-vector-icon").toFile()
    System.setProperty("roborazzi.test.record", "true")
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
    System.clearProperty("roborazzi.test.record")
  }

  @Test
  fun `a rendered ImageVector Icon exports as an editable path, not an opaque image crop`() {
    val svg = renderIconSvg("icon-star") { Icon(catalogStar, "Star", Modifier.size(48.dp)) }

    // The whole point: with a frame PNG present (hybrid mode) an `Icon` is opaque-by-name and used to
    // crop out as an `<image>`. Tier-1 captures the VectorPainter, so it is now a real `<path>`.
    // Persist the exported SVG as before/after evidence for the PR (the pre-Tier-1 form of this same
    // preview was `<image href="figma-raster/…png">`).
    File("build/figma-svg-vector-icon").mkdirs()
    File("build/figma-svg-vector-icon/icon-star.svg").writeText(svg)

    assertTrue("the icon must export as a <path>:\n$svg", svg.contains("<path "))
    assertFalse("no <image> raster crop for a vectorised icon:\n$svg", svg.contains("<image "))
    // The vector layer must count toward the canvas extent, so the 48px icon isn't clipped by a
    // degenerate padding-only viewBox (the `paints` regression).
    val width = Regex("""<svg[^>]*\bwidth="(\d+)"""").find(svg)?.groupValues?.get(1)?.toInt() ?: 0
    assertTrue("the canvas must contain the 48px icon, got width=$width:\n$svg", width >= 48)
    // No `figma-raster/` sidecar PNGs were written for it.
    val rasterDir = File(rootDir, "icon-star/figma-raster")
    assertFalse(
      "no raster sidecars for a fully-vectorised icon: ${rasterDir.listFiles()?.joinToString()}",
      rasterDir.isDirectory && (rasterDir.listFiles()?.isNotEmpty() ?: false),
    )
  }

  @Test
  fun `a tinted Icon exports its paths in the tint colour, not the source fill`() {
    val svg =
      renderIconSvg("icon-tinted") {
        Icon(catalogStar, "Star", Modifier.size(48.dp), tint = Color(0xFF112233))
      }
    assertTrue("a tinted icon still vectorises:\n$svg", svg.contains("<path "))
    assertFalse("no <image> raster crop for a vectorised icon:\n$svg", svg.contains("<image "))
    // `Icon` applies its tint as a SrcIn colorFilter at draw time; the export must recolour the path
    // to that tint, not emit the ImageVector's intrinsic white fill.
    assertTrue("the path carries the tint:\n$svg", svg.contains("fill=\"#112233\""))
    assertFalse("the source white fill must not leak through:\n$svg", svg.contains("fill=\"#FFFFFF\""))
  }

  @Test
  fun `an icon with a gradient path rasters instead of dropping the path`() {
    val svg = renderIconSvg("icon-gradient") { Image(gradientVector, null, Modifier.size(48.dp)) }
    // The gradient fill can't be represented as a flat colour, so the whole graphic falls back to a
    // raster crop rather than silently vectorising into a partial/empty icon.
    assertTrue("a gradient-filled icon rasters:\n$svg", svg.contains("<image "))
    assertFalse("no vector path for an unrepresentable gradient icon:\n$svg", svg.contains("<path "))
  }

  @Test
  fun `an intrinsically tinted vector exports in its tint colour, not the source fill`() {
    val svg =
      renderIconSvg("icon-intrinsic") {
        Image(intrinsicallyTintedSquare, null, Modifier.size(48.dp))
      }
    assertTrue("an intrinsically tinted vector still vectorises:\n$svg", svg.contains("<path "))
    assertFalse("no <image> raster crop for a vectorised icon:\n$svg", svg.contains("<image "))
    // The vector's own `tintColor` is applied through the intrinsic colour filter on its component.
    assertTrue("the intrinsic tint is applied:\n$svg", svg.contains("fill=\"#445566\""))
    assertFalse("the source white fill must not leak through:\n$svg", svg.contains("fill=\"#FFFFFF\""))
  }

  @Test
  fun `an icon with a mixed solid-fill gradient-stroke path rasters`() {
    val svg = renderIconSvg("icon-mixed") { Image(mixedPaintSquare, null, Modifier.size(48.dp)) }
    // One side (the stroke) is an unrepresentable gradient, so the whole icon rasters rather than
    // emitting the solid fill alone and silently dropping the gradient stroke.
    assertTrue("a mixed-paint icon rasters:\n$svg", svg.contains("<image "))
    assertFalse("no partial vector for a mixed-paint icon:\n$svg", svg.contains("<path "))
  }

  /**
   * Renders [content] in a fresh rule at a small component size, forces a draw so children z-sort,
   * then runs the production capture + **hybrid** figma-svg export (a frame PNG is passed) and
   * returns the SVG string.
   */
  private fun renderIconSvg(previewId: String, content: @Composable () -> Unit): String {
    RuntimeEnvironment.setQualifiers("w96dp-h96dp-mdpi")
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
private fun InspectableContent(
  capture: MutableSet<CompositionData>,
  content: @Composable () -> Unit,
) {
  currentComposer.collectParameterInformation()
  capture.add(currentComposer.compositionData)
  CompositionLocalProvider(LocalInspectionTables provides capture, content = content)
}
