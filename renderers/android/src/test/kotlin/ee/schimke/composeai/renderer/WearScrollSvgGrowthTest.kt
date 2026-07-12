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
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorNode
import ee.schimke.composeai.data.layoutinspector.WearCapsuleStacker
import ee.schimke.composeai.data.render.PreviewContext
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

  // --- Split-scaffold extraction: capture each part in isolation, then stack ----------------------

  /** One captured screen part: its content layers/semantics (frame stripped), height, and frame PNG. */
  private data class Part(
    val layout: List<LayoutInspectorNode>,
    val semantics: List<ComposeSemanticsNode>,
    val height: Int,
    val frame: File = File(""),
  )

  /**
   * Renders a single composable [content] in a FRESH rule at the watch width (a tall throwaway
   * frame so nothing clips), forces a draw so children z-sort, and captures its layout + semantics
   * trees. Returns the content layers (the throwaway frame root is stripped — only its children
   * survive) plus the measured content height, so the caller can stack parts by offsetting each by a
   * cumulative y. Every part is a **real** Wear composable rendered honestly in isolation; only the
   * vertical stacking is synthesised. Isolation dodges the round-face problems the grow-tall path
   * hits: no `TransformingLazyColumn` virtualisation, no fisheye `SurfaceTransformation` scaling, and
   * no screen-height-relative `ScreenScaffold` padding — each row is measured at its natural size.
   */
  private fun capturePart(
    previewId: String,
    frameHeightDp: Int = 420,
    content: @Composable () -> Unit,
  ): Part {
    RuntimeEnvironment.setQualifiers("w${deviceDp}dp-h${frameHeightDp}dp-round-mdpi")
    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()
    var out = Part(emptyList(), emptyList(), 0)
    val statement =
      object : Statement() {
        override fun evaluate() {
          val slotTables = mutableSetOf<CompositionData>()
          rule.mainClock.autoAdvance = false
          rule.setContent { InspectableContent(slotTables) { content() } }
          rule.mainClock.advanceTimeBy(500)
          rule.waitForIdle()
          // Capture over transparency: the crop the EdgeButton raster reads from must be the crescent
          // pixels alone (alpha 0 everywhere else), or an opaque window backdrop would paint a block
          // around the control that the capsule clip then reveals as a stray crescent.
          rule.runOnUiThread {
            rule.activity.window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
          }
          val frameFile = File(rootDir, "part-$previewId.png")
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
          // The rule's frame root fills the throwaway 420dp window; its children are the composed
          // content. Strip the frame and measure the content's true bottom (its natural height).
          val contentLayers = layout.root.children
          val height = WearCapsuleStacker.contentHeight(contentLayers)
          out = Part(contentLayers, semantics.root.children, height, frameFile)
        }
      }
    rule
      .apply(statement, Description.createTestDescription(javaClass, "part-$previewId"))
      .evaluate()
    return out
  }

  /** A flat, unscaled activity row — the resting look of a `TitleCard`, no fisheye transform. */
  @Composable
  private fun CardPart(title: String, subtitle: String) {
    MaterialTheme {
      Box(Modifier.width(deviceDp.dp).padding(horizontal = 12.dp)) {
        TitleCard(
          onClick = {},
          title = { Text(title) },
          subtitle = { Text(subtitle) },
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }

  /**
   * Drives the production [WearCapsuleStacker] with captured [parts] (plus optional [timeText] and
   * [edge]) and writes the resulting capsule SVG. All the tree surgery — offsetting each captured
   * subtree, the correct top/bottom padding so the round clip frames content without shearing it, the
   * synthetic frame root that auto-selects the capsule clip, and the `EdgeButton` raster placement —
   * lives in the stacker; this only composites the edge control's pixels into the frame the hybrid
   * export crops from and mirrors the SVG (+ any rasters) into `build/wear-scroll-svg/` for rendering.
   */
  private fun stackToCapsule(
    previewId: String,
    parts: List<Part>,
    timeText: Part? = null,
    edge: Part? = null,
  ): String {
    fun Part.toStackerPart() = WearCapsuleStacker.Part(layout, semantics, height)
    val stacked =
      WearCapsuleStacker.stack(
        rootId = previewId,
        width = deviceDp,
        parts = parts.map { it.toStackerPart() },
        timeText = timeText?.toStackerPart(),
        edge = edge?.toStackerPart(),
      )

    // When there's a rastered control, composite the frame the hybrid export crops it from: a
    // transparent canvas the size of the stacked frame with just the EdgeButton's isolated pixels
    // (cropped at the source box) pasted at its stacked destination. Vector parts need no pixels here.
    val framePng =
      stacked.edge?.let { er ->
        val edgeFrame = ImageIO.read(edge!!.frame)
        val cropW = (er.source.right - er.source.left).coerceAtMost(edgeFrame.width - er.source.left)
        val cropH = (er.source.bottom - er.source.top).coerceAtMost(edgeFrame.height - er.source.top)
        val edgeCrop = edgeFrame.getSubimage(er.source.left, er.source.top, cropW, cropH)
        val composited = BufferedImage(stacked.width, stacked.height, BufferedImage.TYPE_INT_ARGB)
        composited.createGraphics().apply {
          drawImage(edgeCrop, er.dest.left, er.dest.top, null)
          dispose()
        }
        File(rootDir, "$previewId-frame.png").also { ImageIO.write(composited, "png", it) }
      }

    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = rootDir,
      previewId = previewId,
      layout = stacked.layout,
      semantics = stacked.semantics,
      density = 1f,
      frameImage = framePng,
      roundClip = true,
    )
    val svg = File(rootDir, "$previewId/compose-figma.svg").readText()
    File("build/wear-scroll-svg/figma-raster").mkdirs()
    File("build/wear-scroll-svg/$previewId.svg").writeText(svg)
    File(rootDir, "$previewId/figma-raster").listFiles()?.forEach { src ->
      src.copyTo(File("build/wear-scroll-svg/figma-raster/${src.name}"), overwrite = true)
    }
    return svg
  }

  @Test
  fun `stacks each list item captured in isolation into one tall capsule SVG`() {
    // Capture every screen part on its own: the pinned TimeText, the "Activity" header, each activity
    // row, and the EdgeButton — none of them fighting the round-face scaffold layout.
    val timeText =
      capturePart("time", frameHeightDp = deviceDp) {
        MaterialTheme { TimeText(timeSource = FixedTime) }
      }
    val header = capturePart("header") { MaterialTheme { ListHeader { Text("Activity") } } }
    val cards =
      activities.mapIndexed { i, (title, subtitle) ->
        capturePart("card-$i") { CardPart(title, subtitle) }
      }
    val edge =
      capturePart("edge") {
        MaterialTheme {
          Box(Modifier.width(deviceDp.dp)) {
            EdgeButton(onClick = {}, buttonSize = EdgeButtonSize.Large) { Text("Start workout") }
          }
        }
      }

    val svg = stackToCapsule("wear-parts", listOf(header) + cards, timeText = timeText, edge = edge)

    // Tall → capsule clip, every row present as editable vector, the pinned TimeText + header, and the
    // EdgeButton as a faithful raster whose PNG the export actually wrote.
    assertTrue(
      "the stacked frame must use the capsule clip:\n$svg",
      svg.contains("""<clipPath id="deviceRound"><rect"""),
    )
    assertEquals(
      "every isolated row must land in the stacked frame:\n$svg",
      itemCount,
      itemLayerCount(svg),
    )
    assertTrue("the stacked frame pins TimeText on the rim", svg.contains("10:10"))
    assertTrue("the stacked frame keeps its header", svg.contains(">Activity</text>"))
    assertTrue("the EdgeButton is composited as a raster <image>", svg.contains("<image "))
    assertTrue(
      "the EdgeButton raster PNG must be written next to the SVG",
      File(rootDir, "wear-parts/figma-raster/edge_raster.png").exists(),
    )
  }

  @Test
  fun `stacks a TransformingLazyColumn screen with no EdgeButton`() {
    // A plain TLC list screen — header + rows, no bottom control. The same isolation-capture stack,
    // just with no rastered part: everything stays editable vector.
    val timeText =
      capturePart("nb-time", frameHeightDp = deviceDp) {
        MaterialTheme { TimeText(timeSource = FixedTime) }
      }
    val header = capturePart("nb-header") { MaterialTheme { ListHeader { Text("Activity") } } }
    val cards =
      activities.take(6).mapIndexed { i, (title, subtitle) ->
        capturePart("nb-card-$i") { CardPart(title, subtitle) }
      }

    val svg = stackToCapsule("wear-parts-noedge", listOf(header) + cards, timeText = timeText)

    assertTrue(
      "the no-edge frame must use the capsule clip:\n$svg",
      svg.contains("""<clipPath id="deviceRound"><rect"""),
    )
    assertEquals("all 6 rows land as vector layers:\n$svg", 6, itemLayerCount(svg))
    assertTrue("TimeText is pinned on the rim", svg.contains("10:10"))
    assertTrue("the header is kept", svg.contains(">Activity</text>"))
    assertFalse("with no EdgeButton there is no raster <image>", svg.contains("<image "))
  }

  /** A flat, unscaled full-width `Button` row — the resting look of a Column-scroller item. */
  @Composable
  private fun ButtonRow(label: String) {
    MaterialTheme {
      Box(Modifier.width(deviceDp.dp).padding(horizontal = 12.dp)) {
        Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text(label) }
      }
    }
  }

  @Test
  fun `stacks a verticalScroll Column of buttons into a capsule SVG`() {
    // A different scrolling container: a `Column(Modifier.verticalScroll())` settings screen whose
    // items are full-width Buttons rather than TLC cards. The extraction is container-agnostic — each
    // item is captured flat and stacked the same way.
    val labels =
      listOf(
        "Notifications",
        "Display",
        "Vibration",
        "Connectivity",
        "Tiles",
        "Battery",
        "System",
        "About",
      )
    val timeText =
      capturePart("col-time", frameHeightDp = deviceDp) {
        MaterialTheme { TimeText(timeSource = FixedTime) }
      }
    val header = capturePart("col-header") { MaterialTheme { ListHeader { Text("Settings") } } }
    val buttons = labels.mapIndexed { i, label -> capturePart("col-btn-$i") { ButtonRow(label) } }

    val svg = stackToCapsule("wear-parts-column", listOf(header) + buttons, timeText = timeText)

    assertTrue(
      "the Column frame must use the capsule clip:\n$svg",
      svg.contains("""<clipPath id="deviceRound"><rect"""),
    )
    val landed = labels.count { svg.contains(">$it</text>") }
    assertEquals("every button label lands as a vector layer:\n$svg", labels.size, landed)
    assertTrue("TimeText is pinned on the rim", svg.contains("10:10"))
    assertTrue("the Settings header is kept", svg.contains(">Settings</text>"))
    assertFalse("a Button list needs no raster <image>", svg.contains("<image "))
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
