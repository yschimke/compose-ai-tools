package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import ee.schimke.composeai.daemon.ComposeFigmaSvgDataProducer
import ee.schimke.composeai.daemon.ComposeSemanticsDataProducer
import ee.schimke.composeai.daemon.LayoutInspectorDataProducer
import ee.schimke.composeai.data.render.PreviewContext
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * End-to-end coverage for the **Wear** `compose/figma-svg` scroll export, hosted here — not in
 * `:daemon:android` — on purpose: the daemon must never link `androidx.wear.compose` (it renders the
 * *user's* app, whose wear-compose arrives on a child classloader, and reaches
 * `LocalReduceMotion` reflectively via [WearReduceMotionLocal]). This module already carries
 * `wear-compose-foundation` on its **test** classpath, so the Wear dependency comes from the module
 * being rendered, exactly as it does in production.
 *
 * The test renders a real Wear [TransformingLazyColumn] at a **tall** device size (mimicking the
 * `figma-svg-long` grown frame — the daemon's growth loop just picks that height automatically),
 * with `LocalReduceMotion(true)` provided through the same reflective seam the daemon uses, then runs
 * the real capture + export the daemon runs post-render ([LayoutInspectorDataProducer] +
 * [ComposeSemanticsDataProducer] + [ComposeFigmaSvgDataProducer.writeSvg]). It asserts the emitted
 * SVG masks the tall frame to the **capsule** (vertical stadium) rather than the inscribed circle,
 * and carries every list item as a layer — the real-geometry counterpart to the pure-model
 * `FigmaLayeredSvgTest` cases.
 *
 * A draw is forced via `captureRoboImage` before the layout walk: the inspector reflects over
 * `LayoutNode.getZSortedChildren`, which is empty until `measure`/`draw` have z-sorted the tree (the
 * daemon walks it after its own `captureRoboImage` for the same reason).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearScrollSvgCaptureTest {

  @Suppress("DEPRECATION") @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("wear-scroll-svg").toFile()
    // Roborazzi defaults to compare-against-baseline; record so `captureRoboImage` just writes the
    // frame (we only need the draw side effect, not a pixel baseline).
    System.setProperty("roborazzi.test.record", "true")
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
    System.clearProperty("roborazzi.test.record")
  }

  @Test
  fun `tall Wear TransformingLazyColumn exports a capsule-clipped SVG carrying every item`() {
    val itemCount = 10
    val cardArgb = 0xFF39394A.toInt()
    val previewId = "wear-scroll"
    val slotTables = mutableSetOf<CompositionData>()
    // Flatten TransformingLazyColumn edge scaling exactly as the daemon does for the grown render —
    // resolved reflectively; non-null here because this module's test classpath carries
    // wear-compose-foundation.
    val reduceMotion = WearReduceMotionLocal.get()
    assertNotNull("wear-compose-foundation must be on the test classpath for this module", reduceMotion)

    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      InspectableContent(slotTables) {
        CompositionLocalProvider(reduceMotion!! provides true) {
          TransformingLazyColumn(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            items(itemCount) { i ->
              Box(
                modifier =
                  Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .height(64.dp)
                    .background(Color(cardArgb))
              ) {
                BasicText(text = "Item $i", style = TextStyle(color = Color.White, fontSize = 16.sp))
              }
            }
          }
        }
      }
    }
    composeRule.mainClock.advanceTimeBy(500)
    composeRule.waitForIdle()

    // Force measure/draw so LayoutNode children z-sort (else the inspector walk sees only the root).
    composeRule
      .onRoot()
      .captureRoboImage(
        file = File(rootDir, "frame.png"),
        roborazziOptions =
          RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(applyDeviceCrop = false)),
      )

    val semanticsRoot = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
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

    val layout = LayoutInspectorDataProducer.buildPayload(previewContext, density = 1f)
    assertNotNull("layout-inspector payload must be captured", layout)
    val semantics = ComposeSemanticsDataProducer.buildPayload(semanticsRoot, density = 1f)

    // The always-on daemon extension passes `roundClip = device.isRound`; a tall round frame
    // auto-selects the capsule. Pass it directly here.
    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = rootDir,
      previewId = previewId,
      layout = layout!!,
      semantics = semantics,
      density = 1f,
      roundClip = true,
    )

    val svg = File(rootDir, "$previewId/compose-figma.svg").readText()
    // Persist for visual evidence (never fails the test).
    runCatching {
      File("build/wear-scroll-svg").mkdirs()
      File("build/wear-scroll-svg/wear-scroll.svg").writeText(svg)
    }

    // The tall frame (900 > 384) is masked to a vertical stadium, not the inscribed circle.
    assertTrue(
      "capsule clip must be emitted for the tall Wear frame:\n$svg",
      svg.contains("""<clipPath id="deviceRound"><rect"""),
    )
    assertFalse(
      "a tall Wear frame must not use the circle clip",
      svg.contains("""<clipPath id="deviceRound"><circle"""),
    )

    // Every list item composed at the tall height must land as an editable layer — the whole point
    // of growing the frame. Count the item card fills (Modifier.background token → <rect fill=…>).
    val cardRects = Regex("""fill="#39394A"""").findAll(svg).count()
    assertEquals(
      "every one of the $itemCount list items must export as a card layer (got $cardRects):\n$svg",
      itemCount,
      cardRects,
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
