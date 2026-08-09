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
 * A Wear scaling list (`TransformingLazyColumn` + `SurfaceTransformation`) fades each item toward
 * the curved edges through **two** graphics layers: a *container* layer outside the card's fill,
 * and a *content* layer inside it. The render therefore draws a faded card's background at the
 * container alpha and its labels at container × content.
 *
 * The export used to publish those fills at full strength (issue #3579): the container layer owns a
 * real graphics layer, so the resolver read `NodeCoordinator.lastLayerAlpha` — which a block that
 * needs draw-time scroll progress leaves at its creation-time `1.0` — and never evaluated the block
 * that actually assigns the alpha. Only the content layer (no layer of its own, so it fell through
 * to the block) faded, leaving every card's background opaque while its own label faded around it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigmaSvgWearScalingAlphaTest {

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("figma-svg-wear-scaling-alpha").toFile()
    System.setProperty("roborazzi.test.record", "true")
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
    System.clearProperty("roborazzi.test.record")
  }

  @Test
  fun `a faded scaling card fades its fill, not just its labels`() {
    val svg = renderSvg("wear-scaling-alpha")
    File("build/figma-svg-wear-scaling").mkdirs()
    File("build/figma-svg-wear-scaling/wear-scaling-alpha.svg").writeText(svg)

    // Every card fill in this list is the same colour, so each <rect> is one card's background.
    val fills = Regex("<rect[^>]*fill=\"#332E3C\"[^>]*/>").findAll(svg).map { it.value }.toList()
    assertTrue("expected several card fills in:\n$svg", fills.size >= 3)

    // The list is anchored at the top, so the last card rides furthest down the curve and is the
    // most faded. Its fill must sit inside a group carrying that container alpha — this is the
    // regression: the fill used to be emitted with no enclosing opacity at all.
    val lastFill = fills.last()
    val beforeFill = svg.substring(0, svg.lastIndexOf(lastFill))
    val container =
      Regex("<g\\b[^>]*\\bopacity=\"([\\d.]+)\"[^>]*>\\s*$")
        .find(beforeFill)
        ?.groupValues
        ?.get(1)
        ?.toDouble()
    assertTrue(
      "the most-faded card's fill must be wrapped in a group carrying the container alpha, " +
        "but no opacity group directly encloses it:\n$svg",
      container != null && container < 0.95,
    )

    // ...and the content layer still fades *on top of* it, so the labels end up at container ×
    // content while the fill stays at container. A single shared group for both would put the
    // labels at the same strength as the background, which is not what the render draws.
    val afterFill = svg.substring(svg.lastIndexOf(lastFill) + lastFill.length)
    val content =
      Regex("^\\s*<g\\b[^>]*\\bopacity=\"([\\d.]+)\"[^>]*>")
        .find(afterFill)
        ?.groupValues
        ?.get(1)
        ?.toDouble()
    assertTrue(
      "the card's content must keep its own opacity group nested inside the container one " +
        "(container=$container, content=$content):\n$svg",
      content != null && content < 0.95,
    )
  }

  private fun renderSvg(previewId: String): String {
    RuntimeEnvironment.setQualifiers("w227dp-h227dp-round-mdpi")
    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()
    var svg = ""
    val statement =
      object : Statement() {
        override fun evaluate() {
          val slots = mutableSetOf<CompositionData>()
          rule.setContent {
            InspectableScalingAlphaContent(slots) {
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
                      items(6) { i ->
                        TitleCard(
                          onClick = {},
                          title = { Text("Card $i") },
                          subtitle = { Text("sub $i") },
                          modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                          transformation = SurfaceTransformation(spec),
                        )
                      }
                    }
                  }
                }
              }
            }
          }
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
          // Keep the render next to the vector so the pair can be re-rasterised for PR evidence
          // without re-deriving which frame this SVG came from.
          File("build/figma-svg-wear-scaling").mkdirs()
          frame.copyTo(File("build/figma-svg-wear-scaling/$previewId-render.png"), overwrite = true)
          // A self-contained copy (raster hrefs base64-inlined) so it rasterises standalone for PR
          // evidence — the temp dir holding the crops is gone by the time the test returns.
          var inlined = svg
          File(rootDir, "$previewId/figma-raster").listFiles().orEmpty().forEach { png ->
            val b64 = java.util.Base64.getEncoder().encodeToString(png.readBytes())
            inlined = inlined.replace("figma-raster/${png.name}", "data:image/png;base64,$b64")
          }
          File("build/figma-svg-wear-scaling/$previewId.inlined.svg").writeText(inlined)
        }
      }
    rule.apply(statement, Description.createTestDescription(javaClass, previewId)).evaluate()
    return svg
  }
}

@OptIn(InternalComposeApi::class)
@Composable
private fun InspectableScalingAlphaContent(
  capture: MutableSet<CompositionData>,
  content: @Composable () -> Unit,
) {
  currentComposer.collectParameterInformation()
  capture.add(currentComposer.compositionData)
  CompositionLocalProvider(LocalInspectionTables provides capture, content = content)
}
