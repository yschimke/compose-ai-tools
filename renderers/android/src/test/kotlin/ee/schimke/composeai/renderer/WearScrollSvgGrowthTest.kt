package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import ee.schimke.composeai.daemon.ComposeFigmaSvgDataProducer
import ee.schimke.composeai.daemon.ComposeSemanticsDataProducer
import ee.schimke.composeai.daemon.LayoutInspectorDataProducer
import ee.schimke.composeai.data.render.PreviewContext
import java.io.File
import java.nio.file.Files
import kotlin.math.ceil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Demonstrates the **real** `figma-svg-long` extraction on Wear: start from a normal round-watch
 * **device preview** (a square `wearos_large_round`, 227×227dp) and *grow it by measurement* until
 * the virtualised `TransformingLazyColumn` composes every row — deriving the tall height rather than
 * hardcoding it. This is the daemon's `runScrollSvgScenario` growth loop, exercised here in
 * `:renderer-android` so the Wear dependency comes from the module being rendered (its test
 * classpath carries `wear-compose-foundation`), never the daemon.
 *
 * It asserts the two ends of the extraction:
 * - **Device preview (square):** only a handful of items are composed (LazyList virtualisation), and
 *   the export masks the frame to the inscribed **circle** (`height == width`).
 * - **Extracted tall frame:** every row is composed, and the export masks to the **capsule** (the
 *   tall frame the growth loop settled on), the vector analogue of the raster pill clip.
 *
 * A draw is forced (`captureRoboImage`) before each measure/export because the layout inspector
 * reflects over `LayoutNode.getZSortedChildren`, empty until `measure`/`draw` z-sort the tree — the
 * same reason the daemon walks it after its own capture. Each probe uses a **fresh**
 * `createAndroidComposeRule` (the rule forbids a second `setContent`), exactly as
 * `RenderEngine.measureScrollAtHeight` does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearScrollSvgGrowthTest {

  private lateinit var rootDir: File

  // The round-watch device preview we start from: `id:wearos_large_round` is 227×227dp. mdpi keeps
  // px == dp so the grown-height arithmetic reads directly.
  private val deviceDp = 227
  private val itemCount = 12

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("wear-scroll-growth").toFile()
    System.setProperty("roborazzi.test.record", "true")
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
    System.clearProperty("roborazzi.test.record")
  }

  /**
   * A round-watch list with the **real** Wear `TransformingLazyColumn` item scaling
   * (`transformedHeight` against a `TransformationSpec`): rows shrink toward the top and bottom of
   * the round face. Providing `LocalReduceMotion` = [reduceMotion] toggles that scaling — `false`
   * gives the fisheye watch look, `true` flattens every row to its natural height (the state the
   * extraction targets). The local is resolved through the same reflective seam the daemon uses.
   */
  @Composable
  private fun WearList(reduceMotion: Boolean) {
    val reduceMotionLocal = WearReduceMotionLocal.get()
    assertNotNull("wear-compose-foundation must be on this module's test classpath", reduceMotionLocal)
    CompositionLocalProvider(reduceMotionLocal!! provides reduceMotion) {
      MaterialTheme {
        val state = rememberTransformingLazyColumnState()
        val spec = rememberTransformationSpec()
        TransformingLazyColumn(
          state = state,
          modifier = Modifier.fillMaxSize().background(Color.Black),
        ) {
          items(itemCount) { i ->
            // Real Wear item scaling: `transformedHeight` + `SurfaceTransformation` shrink/curve the
            // card toward the round face's edges. `LocalReduceMotion` (provided above) flattens both
            // to natural size — the state the extraction targets.
            TitleCard(
              onClick = {},
              title = { Text("Item $i") },
              modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
              transformation = SurfaceTransformation(spec),
            )
          }
        }
      }
    }
  }

  /** One probe's result: its measured content geometry and, when requested, the exported SVG. */
  private data class Probe(val measure: ScrollContentMeasure.Measure?, val svg: String?)

  /**
   * Renders [WearList] in a FRESH rule at [heightPx] (the round watch's width is fixed; only the
   * height grows), forces a draw so children z-sort, measures the scroll content via the shared
   * [ScrollContentMeasure] the daemon uses, and — when [exportPreviewId] is set — runs the real
   * capture + figma-svg export (inside the rule statement, before teardown) so the SVG reflects this
   * exact frame. [reduceMotion] toggles the Wear item scaling (off = scaled watch look).
   */
  private fun probe(
    heightPx: Int,
    reduceMotion: Boolean,
    exportPreviewId: String? = null,
  ): Probe {
    // A real round-watch device: the `round` qualifier drives `Configuration.isScreenRound` (so the
    // Wear scaling engages) and lets Roborazzi's device crop mask the square frame to the watch
    // circle. Width is fixed at the device's; only the height grows.
    RuntimeEnvironment.setQualifiers("w${deviceDp}dp-h${heightPx}dp-round-mdpi")
    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()
    var out = Probe(measure = null, svg = null)
    val statement =
      object : Statement() {
        override fun evaluate() {
          val slotTables = mutableSetOf<CompositionData>()
          rule.mainClock.autoAdvance = false
          rule.setContent { InspectableContent(slotTables) { WearList(reduceMotion) } }
          rule.mainClock.advanceTimeBy(500)
          rule.waitForIdle()
          // A square frame is a round device → device-crop it to the watch circle; a grown frame is
          // the extracted tall screenshot → no circle crop. (Forcing the draw is the primary reason
          // to capture at all — it z-sorts the layout children.)
          val frameFile =
            if (exportPreviewId != null) File("build/wear-scroll-svg/$exportPreviewId-frame.png")
            else File(rootDir, "frame-$heightPx.png")
          frameFile.parentFile?.mkdirs()
          rule
            .onRoot()
            .captureRoboImage(
              file = frameFile,
              roborazziOptions =
                RoborazziOptions(
                  recordOptions =
                    RoborazziOptions.RecordOptions(applyDeviceCrop = heightPx == deviceDp)
                ),
            )
          val semanticsRoot = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
          val measure = ScrollContentMeasure.measureVerticalScroll(semanticsRoot)
          var svg: String? = null
          if (exportPreviewId != null) {
            val previewContext =
              PreviewContext.Builder(
                  previewId = exportPreviewId,
                  backend = null,
                  renderMode = null,
                  outputBaseName = exportPreviewId,
                )
                .rootForTest(semanticsRoot.root as RootForTest)
                .addSlotTables(slotTables.toList())
                .parameterInformationCollected()
                .build()
            val layout = LayoutInspectorDataProducer.buildPayload(previewContext, density = 1f)!!
            val semantics = ComposeSemanticsDataProducer.buildPayload(semanticsRoot, density = 1f)
            ComposeFigmaSvgDataProducer.writeSvg(
              rootDir = rootDir,
              previewId = exportPreviewId,
              layout = layout,
              semantics = semantics,
              density = 1f,
              roundClip = true,
            )
            svg = File(rootDir, "$exportPreviewId/compose-figma.svg").readText()
          }
          out = Probe(measure = measure, svg = svg)
        }
      }
    rule.apply(statement, Description.createTestDescription(javaClass, "probe-$heightPx")).evaluate()
    return out
  }

  // Count list items by their captured label text — robust whether the card fill lands as a vector
  // rect or (via the hybrid path) a raster `<image>`.
  private fun itemLayerCount(svg: String) = Regex(""">Item \d+<""").findAll(svg).count()

  @Test
  fun `grows a square round device preview into a tall capsule that carries the whole list`() {
    // 1. The device preview: render at the watch's real 227×227 size, WITH the Wear item scaling on
    // (reduceMotion = false) — the fisheye watch look — and export it.
    val device = probe(deviceDp, reduceMotion = false, exportPreviewId = "wear-device")
    val deviceSvg = device.svg!!
    // A square round frame (h == w) masks to the inscribed CIRCLE, and virtualisation means only a
    // few rows are composed — the rest are off the fold, absent from the tree.
    assertTrue(
      "square device preview must use the circle clip",
      deviceSvg.contains("""<clipPath id="deviceRound"><circle"""),
    )
    val itemsOnDevice = itemLayerCount(deviceSvg)
    assertTrue(
      "the device preview must show only a virtualised subset (got $itemsOnDevice of $itemCount)",
      itemsOnDevice in 1 until itemCount,
    )

    // 2. Grow by measurement — the daemon's loop: render at H, read the composed content extent,
    // add a viewport of headroom, repeat until it stops growing. The tall height is DERIVED here,
    // never hardcoded.
    val baseHeight = deviceDp
    var probeHeight = baseHeight
    var sizedHeight = baseHeight
    var prevContentBottom = -1
    var iterations = 0
    while (iterations < 6) {
      iterations++
      val m =
        probe(probeHeight, reduceMotion = true).measure
          ?: error("the Wear list must expose a vertical scroll range to grow")
      val bottomChrome = (probeHeight - m.scrollNodeBottom).coerceAtLeast(0)
      sizedHeight = m.contentBottom + bottomChrome + 8
      if (m.contentBottom <= prevContentBottom) break
      prevContentBottom = m.contentBottom
      probeHeight = m.contentBottom + bottomChrome + baseHeight
    }
    // The growth must have extracted a frame taller than the square device — that's the whole point.
    assertTrue(
      "growth must derive a frame taller than the ${deviceDp}px device (got $sizedHeight)",
      sizedHeight > deviceDp,
    )

    // 3. Render + export at the settled tall height, flattened (reduceMotion = true) — the extracted
    // tall screenshot.
    val tall =
      probe(ceil(sizedHeight.toDouble()).toInt(), reduceMotion = true, exportPreviewId = "wear-tall")
    val tallSvg = tall.svg!!
    File("build/wear-scroll-svg").mkdirs()
    File("build/wear-scroll-svg/wear-device.svg").writeText(deviceSvg)
    File("build/wear-scroll-svg/wear-tall.svg").writeText(tallSvg)

    // The extracted tall frame (h > w) masks to the CAPSULE, not the circle...
    assertTrue(
      "the extracted tall frame must use the capsule clip:\n$tallSvg",
      tallSvg.contains("""<clipPath id="deviceRound"><rect"""),
    )
    assertFalse(
      "the extracted tall frame must not use the circle clip",
      tallSvg.contains("""<clipPath id="deviceRound"><circle"""),
    )
    // ...and now carries EVERY row as a layer — the extraction's payoff.
    assertEquals(
      "the grown frame must compose all $itemCount rows:\n$tallSvg",
      itemCount,
      itemLayerCount(tallSvg),
    )
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
