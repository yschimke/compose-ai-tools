package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * Regression for #3056 — Jetsnack's `Catalog/Filter screen` shape.
 *
 * A `sharedBounds(… RemeasureToBounds) … .heightIn(max =
 * …).verticalScroll(…).skipToLookaheadSize()` chain measures its scroll content to the FULL content
 * height in the lookahead pass while the approach pass (what the PNG paints) is capped by
 * `heightIn`. The export took the taller lookahead/shared-element box as the owning clip, so
 * below-fold children the render clips away stayed visible in the SVG.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigmaSvgSharedBoundsScrollClipTest {

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("figma-svg-shared-scroll").toFile()
    System.setProperty("roborazzi.test.record", "true")
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
    System.clearProperty("roborazzi.test.record")
  }

  @Test
  fun `a height-limited scroll container under sharedBounds clips its below-fold children`() {
    val svg =
      renderSvg("shared-bounds-scroll") {
        MaterialTheme {
          SharedTransitionLayout {
            AnimatedContent(targetState = true, label = "filters") { _ ->
              Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                  Modifier.padding(16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .sharedBounds(
                      rememberSharedContentState(key = "filters"),
                      this@AnimatedContent,
                      resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                    )
                    .wrapContentSize()
                    .heightIn(max = VIEWPORT_DP.dp)
                    .verticalScroll(rememberScrollState())
                    .background(Color(0xFFFFFBFE))
                    .skipToLookaheadSize()
                ) {
                  ROWS.forEach { label ->
                    Box(Modifier.fillMaxWidth().height(80.dp).background(Color(0xFFE7E0EC))) {
                      Text(label, Modifier.padding(12.dp))
                    }
                  }
                }
              }
            }
          }
        }
      }

    val outDir = File("build/figma-svg-shared-scroll").apply { mkdirs() }
    File(outDir, "shared-bounds-scroll.svg").writeText(svg)

    // Every emitted clip rect for the filter surface must be the RENDERED viewport, never the
    // taller lookahead content box.
    val tallClips =
      CLIP_RECT.findAll(svg)
        .map { it.groupValues[1].toDouble() }
        .filter { it > VIEWPORT_DP + SLACK_PX }
        .toList()
    assertTrue(
      "the scroll container's clip must be the ${VIEWPORT_DP}px rendered viewport, " +
        "not the lookahead content height; got $tallClips:\n$svg",
      tallClips.isEmpty(),
    )
  }

  /**
   * The **real** `Catalog/Filter screen` shape, which the simplified fixture above misses.
   *
   * Jetsnack's chain ends `…verticalScroll(…).clickable(…).background(…).padding(horizontal =
   * 24.dp, vertical = 16.dp).skipToLookaheadSize()`, so the node's own `bounds` are the *content*
   * box: inset 24dp per side by that trailing padding while overflowing the viewport vertically.
   * The rendered viewport is therefore both **shorter** and **wider** than `bounds`, which the
   * first pass at #3056 rejected as "not a subset" — the synthetic fixture passed while the real
   * screen kept leaking. It also enters through `AnimatedVisibility`, not `AnimatedContent`, and
   * places the sheet with `Modifier.align` inside a `Box` rather than a `contentAlignment`.
   */
  @Test
  fun `the jetsnack filter sheet exports at its rendered viewport, padding and all`() {
    val svg =
      renderSvg("jetsnack-filter-sheet") {
        MaterialTheme {
          SharedTransitionLayout {
            AnimatedVisibility(true) {
              Box(Modifier.fillMaxSize()) {
                Spacer(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
                Column(
                  Modifier.padding(16.dp)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(20.dp))
                    .sharedBounds(
                      rememberSharedContentState(key = "filters"),
                      animatedVisibilityScope = this@AnimatedVisibility,
                      resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                      clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(20.dp)),
                    )
                    .wrapContentSize()
                    .heightIn(max = VIEWPORT_DP.dp)
                    .verticalScroll(rememberScrollState())
                    .clickable(
                      indication = null,
                      interactionSource = remember { MutableInteractionSource() },
                    ) {}
                    .background(Color(0xFFF6F6F6))
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .skipToLookaheadSize()
                ) {
                  ROWS.forEach { label ->
                    Box(Modifier.fillMaxWidth().height(90.dp).background(Color(0xFFE7E0EC))) {
                      Text(label, Modifier.padding(12.dp))
                    }
                  }
                }
              }
            }
          }
        }
      }

    val outDir = File("build/figma-svg-shared-scroll").apply { mkdirs() }
    File(outDir, "jetsnack-filter-sheet.svg").writeText(svg)

    val tallClips =
      CLIP_RECT.findAll(svg)
        .map { it.groupValues[1].toDouble() }
        .filter { it > VIEWPORT_DP + SLACK_PX }
        .toList()
    assertTrue(
      "the filter sheet's clip must be the ${VIEWPORT_DP}px rendered viewport, " +
        "not the lookahead content height; got $tallClips:\n$svg",
      tallClips.isEmpty(),
    )

    // The sheet's own fill is the clipping coordinator's box — the viewport *including* the
    // 24×16dp padding the trailing modifier insets `bounds` by, exactly what the PNG paints.
    val sheet = SHEET_RECT.find(svg) ?: throw AssertionError("no $SHEET_HEX sheet fill in:\n$svg")
    val (width, height) = sheet.groupValues.drop(1).map { it.toDouble() }
    assertEquals("sheet fill height", VIEWPORT_DP.toDouble(), height, SLACK_PX.toDouble())
    assertTrue(
      "the sheet fill must span its horizontal padding too (>= ${ROW_WIDTH_DP + 2 * SHEET_PAD_DP}" +
        "px), got $width:\n$svg",
      width >= ROW_WIDTH_DP + 2 * SHEET_PAD_DP - SLACK_PX,
    )
  }

  private fun renderSvg(previewId: String, content: @Composable () -> Unit): String {
    RuntimeEnvironment.setQualifiers("w400dp-h800dp-mdpi")
    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()
    var svg = ""
    val statement =
      object : Statement() {
        override fun evaluate() {
          val slots = mutableSetOf<CompositionData>()
          rule.setContent { InspectableSharedContent(slots, content) }
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
          )
          svg = File(rootDir, "$previewId/compose-figma.svg").readText()
          val outDir = File("build/figma-svg-shared-scroll").apply { mkdirs() }
          frame.copyTo(File(outDir, "$previewId-frame.png"), overwrite = true)
        }
      }
    rule.apply(statement, Description.createTestDescription(javaClass, previewId)).evaluate()
    return svg
  }

  private companion object {
    /** `heightIn(max = …)` on the scroll container — the height the PNG actually paints. */
    const val VIEWPORT_DP = 240
    /** Content is far taller than the viewport, so several rows are below the fold. */
    val ROWS = listOf("Category", "Price", "Rating", "Lifestyle", "Delivery", "Dietary")
    val CLIP_RECT = Regex("""<clipPath\b[^>]*>\s*<rect\b[^>]*\bheight="([\d.]+)"""")
    /** Rounding + the surface's own padding; a real leak is hundreds of px. */
    const val SLACK_PX = 8
    /** Jetsnack's sheet fill (`JetsnackTheme.colors.uiFloated`), as the export emits it. */
    const val SHEET_HEX = "#F6F6F6"
    /** `padding(horizontal = 24.dp)` between the scroll viewport and `skipToLookaheadSize`. */
    const val SHEET_PAD_DP = 24
    /** 400dp window − the sheet's own 16dp margin − [SHEET_PAD_DP] on each side. */
    const val ROW_WIDTH_DP = 400 - 2 * 16 - 2 * SHEET_PAD_DP
    val SHEET_RECT =
      Regex("""<rect\b[^>]*\bwidth="([\d.]+)"\s+height="([\d.]+)"[^>]*\bfill="$SHEET_HEX"""")
  }
}

@OptIn(InternalComposeApi::class)
@Composable
private fun InspectableSharedContent(
  capture: MutableSet<CompositionData>,
  content: @Composable () -> Unit,
) {
  currentComposer.collectParameterInformation()
  capture.add(currentComposer.compositionData)
  CompositionLocalProvider(LocalInspectionTables provides capture, content = content)
}
