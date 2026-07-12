package ee.schimke.composeai.renderer

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeSource
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import ee.schimke.composeai.daemon.ComposeFigmaSvgDataProducer
import ee.schimke.composeai.daemon.ComposeSemanticsDataProducer
import ee.schimke.composeai.daemon.LayoutInspectorDataProducer
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorBounds
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorNode
import ee.schimke.composeai.data.layoutinspector.WearScrollSliceStitcher
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.scroll.ScrollAxis
import ee.schimke.composeai.scroll.driveScrollByViewport
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
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

  // A realistic activity list — the same content shape as `:samples:wear`'s `LongActivityListScreen`,
  // the canonical Wear scroll fixture. Each title carries a unique index so it counts cleanly.
  private val activities: List<Pair<String, String>> =
    List(15) { i ->
      when (i % 6) {
        0 -> "Morning run ${i + 1}" to "5.2 km · 28 min"
        1 -> "Heart rate ${i + 1}" to "${70 + i} bpm"
        2 -> "Sleep day ${i + 1}" to "7h ${(i * 3) % 60}m"
        3 -> "Steps day ${i + 1}" to "${6000 + i * 120}"
        4 -> "Calories day ${i + 1}" to "${400 + i * 5} kcal"
        else -> "Timer ${i + 1}" to "${10 + i}:${(i * 7) % 60} remaining"
      }
    }
  private val itemCount
    get() = activities.size

  /** Deterministic `10:10` clock, mirroring `:samples:wear`'s `FixedPreviewTimeSource`. */
  private object FixedTime : TimeSource {
    @Composable override fun currentTime(): String = "10:10"
  }

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
   * The **real** Wear activity screen, verbatim from `:samples:wear`'s `ActivityListLongPreview`: an
   * `AppScaffold` pinning `TimeText` (10:10), a `ScreenScaffold` whose `edgeButton` slot holds the
   * "Start workout" `EdgeButton`, and a `TransformingLazyColumn` with an "Activity" `ListHeader` and
   * `TitleCard` rows scaled by `SurfaceTransformation` / `transformedHeight`. No `reduceMotion`
   * parameter and no hand-tuned padding — this is exactly the code a Wear developer writes. The
   * extraction harness (see [probe]) provides `LocalReduceMotion` externally when it needs the list
   * flattened, the same way the daemon does; the preview itself is unaware of it.
   */
  @Composable
  private fun WearList() {
    MaterialTheme {
      AppScaffold(timeText = { TimeText(timeSource = FixedTime) }) {
        val state = rememberTransformingLazyColumnState()
        val spec = rememberTransformationSpec()
        ScreenScaffold(
          scrollState = state,
          edgeButton = {
            EdgeButton(onClick = {}, buttonSize = EdgeButtonSize.Large) { Text("Start workout") }
          },
        ) { contentPadding ->
          TransformingLazyColumn(
            state = state,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
          ) {
            item {
              ListHeader(
                modifier =
                  Modifier.minimumVerticalContentPadding(
                      top = ListHeaderDefaults.minimumTopListContentPadding,
                      bottom = 0.dp,
                    )
                    .transformedHeight(this, spec),
                transformation = SurfaceTransformation(spec),
              ) {
                Text("Activity")
              }
            }
            items(activities) { (title, subtitle) ->
              TitleCard(
                onClick = {},
                title = { Text(title) },
                subtitle = { Text(subtitle) },
                modifier =
                  Modifier.fillMaxWidth()
                    .minimumVerticalContentPadding(CardDefaults.minimumVerticalListContentPadding)
                    .transformedHeight(this, spec),
                transformation = SurfaceTransformation(spec),
              )
            }
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
          // The extraction harness provides Wear's `LocalReduceMotion` externally to flatten the
          // TransformingLazyColumn scaling — exactly how the daemon does it, via the same reflective
          // seam. The preview stays a plain Wear screen with no knowledge of it. Resolved off this
          // module's own classloader (its test classpath carries wear-compose-foundation).
          val reduceMotionLocal = WearReduceMotionLocal.get()
          assertNotNull(
            "wear-compose-foundation must be on this module's test classpath",
            reduceMotionLocal,
          )
          rule.setContent {
            InspectableContent(slotTables) {
              if (reduceMotion) {
                CompositionLocalProvider(reduceMotionLocal!! provides true) { WearList() }
              } else {
                WearList()
              }
            }
          }
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

  // Count list items by their captured title text — each activity title is unique, so a present
  // `<text>…title…</text>` proves that row composed and reached the export.
  private fun itemLayerCount(svg: String) =
    activities.count { (title, _) -> svg.contains(">$title</text>") }

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
    // The pinned scaffold chrome frames the extracted screen: the ListHeader and the revealed
    // EdgeButton both land in the tall frame (the scaffold pins them for free — no bespoke capture).
    assertTrue("the extracted screen keeps its header", tallSvg.contains(">Activity</text>"))
    assertTrue(
      "the extracted screen reveals the EdgeButton",
      tallSvg.contains(">Start workout</text>"),
    )
  }

  /**
   * Slice-stitches the **real** `ActivityListLongPreview` into a capsule SVG: capture the preview at
   * viewport-steps down its scroll (reduce-motion on, so items are unscaled), feed the layout +
   * semantics trees to the production [WearScrollSliceStitcher], and export. The stitcher chains the
   * slices by shared-item movement, places each list item at its true content position, pins TimeText
   * on the rim, and emits the Canvas-drawn EdgeButton crescent as one raster the test composites from
   * a settled final frame. The result is the tree-level twin of the raster `render-scroll-long` PNG —
   * from the unmodified preview, no reconstructed boxes.
   */
  @Test
  fun `slice-stitches the real preview into a capsule`() {
    RuntimeEnvironment.setQualifiers("w${deviceDp}dp-h${deviceDp}dp-round-mdpi")
    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()

    val slices = mutableListOf<WearScrollSliceStitcher.Slice>()
    var settledFrame: File? = null
    var edgeButtonBounds: LayoutInspectorBounds? = null

    val statement =
      object : Statement() {
        override fun evaluate() {
          val slotTables = mutableSetOf<CompositionData>()
          rule.mainClock.autoAdvance = false
          val rml = WearReduceMotionLocal.get()
          assertNotNull("wear-compose-foundation on classpath", rml)
          rule.setContent {
            InspectableContent(slotTables) {
              CompositionLocalProvider(rml!! provides true) { WearList() }
            }
          }
          rule.mainClock.advanceTimeBy(500)
          rule.waitForIdle()

          fun captureTree(previewId: String): Pair<LayoutInspectorNode, ComposeSemanticsNode> {
            val semRoot = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
            val sem = ComposeSemanticsDataProducer.buildPayload(semRoot, density = 1f)
            val ctx =
              PreviewContext.Builder(
                  previewId = previewId,
                  backend = null,
                  renderMode = null,
                  outputBaseName = previewId,
                )
                .rootForTest(semRoot.root as RootForTest)
                .addSlotTables(slotTables.toList())
                .parameterInformationCollected()
                .build()
            val layout = LayoutInspectorDataProducer.buildPayload(ctx, density = 1f)!!
            return layout.root to sem.root
          }

          driveScrollByViewport(
            rule = rule,
            axis = ScrollAxis.VERTICAL,
            stepPx = deviceDp * 0.8f,
            maxScrollPx = 0,
          ) { _ ->
            // Force a draw so the layout inspector sees z-sorted children, then capture the trees.
            rule.onRoot().captureRoboImage(file = File(rootDir, "ss-${slices.size}.png"))
            val (l, s) = captureTree("ss")
            slices.add(WearScrollSliceStitcher.Slice(l, s))
          }

          // The EdgeButton reveals with an animation that lands *after* the scroll settles (the last
          // scroll slice catches a grey nub). Advance the clock, then capture a settled final frame +
          // the EdgeButton's bounds — the raster path's "final frame".
          rule.mainClock.advanceTimeBy(2000)
          rule.waitForIdle()
          settledFrame = File(rootDir, "ss-settled.png")
          rule.onRoot().captureRoboImage(file = settledFrame!!)
          val (settledLayout, _) = captureTree("ss2")
          fun findEdge(n: LayoutInspectorNode): LayoutInspectorNode? {
            if (n.tokens?.backgroundColor == "#FFE9DDFF" && n.bounds.bottom > n.bounds.top) return n
            n.children.forEach { c -> findEdge(c)?.let { return it } }
            return null
          }
          edgeButtonBounds = findEdge(settledLayout)?.bounds
        }
      }
    rule.apply(statement, Description.createTestDescription(javaClass, "ss")).evaluate()

    // Start the crescent crop a little above the label so its upper curve is included.
    val cropTop = ((edgeButtonBounds?.top ?: 140) - 40).coerceIn(0, deviceDp - 1)
    val stitched =
      WearScrollSliceStitcher.stitch(
        rootId = "wear-slice",
        width = deviceDp,
        slices = slices,
        edgeCropTop = cropTop,
      )

    // Composite the settled crescent into the frame the hybrid export crops from (black-backed, so
    // it lands cleanly on the black capsule face).
    val framePng =
      stitched.edge?.let { er ->
        val settled = ImageIO.read(settledFrame)
        val crop = settled.getSubimage(0, er.sourceTop, deviceDp, deviceDp - er.sourceTop)
        val composited = BufferedImage(deviceDp, stitched.height, BufferedImage.TYPE_INT_ARGB)
        composited.createGraphics().apply {
          drawImage(crop, er.dest.left, er.dest.top, null)
          dispose()
        }
        File(rootDir, "ss-frame.png").also { ImageIO.write(composited, "png", it) }
      }

    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = rootDir,
      previewId = "wear-slice",
      layout = stitched.layout,
      semantics = stitched.semantics,
      density = 1f,
      frameImage = framePng,
      roundClip = true,
      deviceBackground = "#FF000000",
    )
    val svg = File(rootDir, "wear-slice/compose-figma.svg").readText()
    File("build/wear-scroll-svg/figma-raster").mkdirs()
    File("build/wear-scroll-svg/wear-slice.svg").writeText(svg)
    File(rootDir, "wear-slice/figma-raster").listFiles()?.forEach {
      it.copyTo(File("build/wear-scroll-svg/figma-raster/${it.name}"), overwrite = true)
    }

    assertTrue("capsule clip", svg.contains("""<clipPath id="deviceRound"><rect"""))
    assertEquals("all 15 activity titles land", itemCount, itemLayerCount(svg))
    assertTrue("TimeText on the rim", svg.contains("10:10"))
    assertTrue("Activity header", svg.contains(">Activity</text>"))
    assertTrue("EdgeButton crescent as a raster", svg.contains("<image "))
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
