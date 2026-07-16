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
import androidx.wear.compose.material3.OutlinedCard
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
 * A Wear M3 scaling list (`TransformingLazyColumn` + `SurfaceTransformation`) fills its cards
 * through a `androidx.wear.compose.material3.lazy.BackgroundPainter` — a wrapper that morphs the
 * container shape as the item rides the curved edges. Because that wrapper is not a bare
 * `ColorPainter`, the token resolver used to leave `backgroundColor` unresolved, so the whole card
 * (title + subtitle included) rasterised as one opaque `<image>` and its labels stopped being
 * editable `<text>`. The resolver now unwraps the `BackgroundPainter` to its base `ColorPainter`, so
 * the card exports as a vector fill and the labels stay editable text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigmaSvgWearScalingCardTest {

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("figma-svg-wear-scaling").toFile()
    System.setProperty("roborazzi.test.record", "true")
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
    System.clearProperty("roborazzi.test.record")
  }

  @Test
  fun `a scaling TitleCard keeps its labels as editable text, not a raster crop`() {
    val svg =
      renderSvg("wear-scaling-card") {
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
                item {
                  TitleCard(
                    onClick = {},
                    title = { Text("Morning run") },
                    subtitle = { Text("5.2 km") },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                  )
                }
              }
            }
          }
        }
      }

    File("build/figma-svg-wear-scaling").mkdirs()
    File("build/figma-svg-wear-scaling/wear-scaling-card.svg").writeText(svg)
    // A self-contained copy (raster hrefs base64-inlined) so it renders standalone for PR evidence.
    val rasterDir = File(rootDir, "wear-scaling-card/figma-raster")
    var inlined = svg
    rasterDir.listFiles().orEmpty().forEach { png ->
      val b64 = java.util.Base64.getEncoder().encodeToString(png.readBytes())
      inlined = inlined.replace("figma-raster/${png.name}", "data:image/png;base64,$b64")
    }
    File("build/figma-svg-wear-scaling/wear-scaling-card.inlined.svg").writeText(inlined)

    // Both labels export as editable <text> — the regression is that they were baked into an
    // <image> crop of the whole card.
    assertTrue("the title must be editable text:\n$svg", svg.contains(">Morning run</text>"))
    assertTrue("the subtitle must be editable text:\n$svg", svg.contains(">5.2 km</text>"))
    // The card's BackgroundPainter fill resolved to a flat vector colour rather than rastering.
    assertTrue(
      "the card must export a vector fill (resolved from the wrapped ColorPainter):\n$svg",
      svg.contains("fill=\"#332E3C\""),
    )
    // ...and keeps its rounded corners — the card's shape lives on the BackgroundPainter, so the
    // fill rect must carry a corner radius (`rx`) rather than drawing as a sharp rectangle.
    assertTrue(
      "the card fill must keep its rounded corners (rx) instead of a square rect:\n$svg",
      Regex("<rect[^>]*\\brx=\"[^\"]+\"[^>]*fill=\"#332E3C\"").containsMatchIn(svg),
    )
  }

  @Test
  fun `a bordered scaling card keeps its outline instead of dropping it to a fill-only vector`() {
    val svg =
      renderSvg("wear-outlined-card") {
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
                item {
                  OutlinedCard(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                  ) {
                    Text("Outlined")
                  }
                }
              }
            }
          }
        }
      }

    File("build/figma-svg-wear-scaling").mkdirs()
    File("build/figma-svg-wear-scaling/wear-outlined-card.svg").writeText(svg)

    // A scaling OutlinedCard fills + outlines through one BackgroundPainter that carries a
    // BorderStroke. We can't yet vectorise that morphing outline, so the fix must NOT silently
    // resolve the fill alone (which would drop the border): the card stays on the raster path,
    // preserving the outline as pixels. Guard the guarantee that matters — the border is never lost:
    // the card is either a raster <image> (pixels preserved) or carries a real vector stroke.
    assertTrue(
      "a bordered scaling card must not export as a fill-only vector with no outline:\n$svg",
      svg.contains("<image ") || svg.contains("stroke="),
    )
  }

  private fun renderSvg(previewId: String, content: @Composable () -> Unit): String {
    RuntimeEnvironment.setQualifiers("w227dp-h227dp-round-mdpi")
    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()
    var svg = ""
    val statement =
      object : Statement() {
        override fun evaluate() {
          val slots = mutableSetOf<CompositionData>()
          rule.setContent { InspectableScalingContent(slots, content) }
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
        }
      }
    rule.apply(statement, Description.createTestDescription(javaClass, previewId)).evaluate()
    return svg
  }
}

@OptIn(InternalComposeApi::class)
@Composable
private fun InspectableScalingContent(
  capture: MutableSet<CompositionData>,
  content: @Composable () -> Unit,
) {
  currentComposer.collectParameterInformation()
  capture.add(currentComposer.compositionData)
  CompositionLocalProvider(LocalInspectionTables provides capture, content = content)
}
