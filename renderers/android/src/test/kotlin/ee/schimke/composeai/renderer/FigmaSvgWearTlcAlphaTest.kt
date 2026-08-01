package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
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
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.placeholder
import androidx.wear.compose.material3.rememberPlaceholderState
import androidx.compose.ui.unit.dp
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
 * Guard for #2615's *alpha* half: the export must never fade transformed Wear content to
 * invisibility.
 *
 * Wear's `TransformingLazyColumn` + `SurfaceTransformation` leaves the most recently applied edge
 * alpha in Compose's shared graphics-layer scratch scope. The exporter used that scope as a
 * fallback for unrelated alpha-less layers, producing `opacity="0.0"` / `opacity="0.04"` groups
 * around content the PNG paints fully opaque.
 *
 * This deliberately follows Jetcaster's production list shape instead of the original synthetic
 * stack of `TitleCard`s: a transformed `ListHeader`, an inactive-placeholder `ButtonGroup` of
 * `FilledIconButton`s, direct text items that use only `transformedHeight`, and transformed
 * `Button`/`TitleCard` surfaces. The alpha-less graphics layers inside the group, placeholders, and
 * curved `TimeText` are the nodes that used to copy a near-zero alpha from Compose's shared
 * graphics-layer scratch scope.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigmaSvgWearTlcAlphaTest {

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("figma-svg-wear-alpha").toFile()
    System.setProperty("roborazzi.test.record", "true")
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
    System.clearProperty("roborazzi.test.record")
  }

  @Test
  fun `transformed TransformingLazyColumn content is not faded to near-transparency`() {
    val svg =
      renderSvg("wear-tlc-alpha") {
        MaterialTheme {
          AppScaffold {
            val state = rememberTransformingLazyColumnState()
            val spec = rememberTransformationSpec()
            ScreenScaffold(scrollState = state) { contentPadding ->
              val placeholderState = rememberPlaceholderState(isVisible = false)
              TransformingLazyColumn(
                state = state,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
              ) {
                item {
                  ListHeader(
                    modifier =
                      Modifier.fillMaxWidth()
                        .minimumVerticalContentPadding(
                          ListHeaderDefaults.minimumTopListContentPadding,
                          ListHeaderDefaults.minimumBottomListContentPadding,
                        )
                        .transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                  ) {
                    Text("Episode title", modifier = Modifier.placeholder(placeholderState))
                  }
                }
                item {
                  ButtonGroup(
                    Modifier.fillMaxWidth()
                      .minimumVerticalContentPadding(
                        ButtonDefaults.minimumVerticalListContentPadding
                      )
                      .transformedHeight(this, spec)
                      .padding(bottom = 16.dp)
                  ) {
                    FilledIconButton(
                      onClick = {},
                      modifier = Modifier.weight(0.7f).placeholder(placeholderState),
                    ) {
                      Text("▶")
                    }
                    FilledIconButton(
                      onClick = {},
                      modifier = Modifier.weight(0.3f).placeholder(placeholderState),
                    ) {
                      Text("+")
                    }
                  }
                }
                item {
                  Button(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                  ) {
                    Text("Play")
                  }
                }
                item {
                  Text(
                    "Jun 2, 2020",
                    modifier =
                      Modifier.padding(horizontal = 8.dp).transformedHeight(this, spec),
                  )
                }
                item {
                  Text(
                    "A real Jetcaster episode summary",
                    modifier =
                      Modifier.padding(horizontal = 8.dp).transformedHeight(this, spec),
                  )
                }
                items(LABELS.size) { index ->
                  TitleCard(
                    onClick = {},
                    title = { Text(LABELS[index]) },
                    subtitle = { Text("12 min") },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                  )
                }
              }
            }
          }
        }
      }

    val outDir = File("build/figma-svg-wear-alpha").apply { mkdirs() }
    File(outDir, "wear-tlc-alpha.svg").writeText(svg)

    val faded =
      OPACITY.findAll(svg).map { it.groupValues[1].toDouble() }.filter { it < MIN_VISIBLE }.toList()
    REQUIRED_CONTENT.forEach { content ->
      assertTrue("real transformed Wear content '$content' is missing:\n$svg", svg.contains(content))
    }
    assertTrue(
      "FilledIconButton surfaces must survive as opaque vector fills:\n$svg",
      OPAQUE_BUTTON_FILL.findAll(svg).count() >= 2,
    )
    assertTrue(
      "transformed Wear content must not be exported at near-zero opacity; got $faded:\n$svg",
      faded.isEmpty(),
    )
  }

  private fun renderSvg(previewId: String, content: @Composable () -> Unit): String {
    RuntimeEnvironment.setQualifiers("w227dp-h227dp-round-xhdpi")
    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()
    var svg = ""
    val statement =
      object : Statement() {
        override fun evaluate() {
          val slots = mutableSetOf<CompositionData>()
          rule.setContent { InspectableAlphaContent(slots, content) }
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
          val layout = LayoutInspectorDataProducer.buildPayload(ctx, density = DENSITY)!!
          val sem = ComposeSemanticsDataProducer.buildPayload(semRoot, density = DENSITY)
          ComposeFigmaSvgDataProducer.writeSvg(
            rootDir = rootDir,
            previewId = previewId,
            layout = layout,
            semantics = sem,
            density = DENSITY,
            frameImage = frame,
            roundClip = true,
          )
          svg = File(rootDir, "$previewId/compose-figma.svg").readText()
          val outDir = File("build/figma-svg-wear-alpha").apply { mkdirs() }
          frame.copyTo(File(outDir, "$previewId-frame.png"), overwrite = true)
          var inlined = svg
          File(rootDir, "$previewId/figma-raster").listFiles().orEmpty().forEach { png ->
            val b64 = java.util.Base64.getEncoder().encodeToString(png.readBytes())
            inlined = inlined.replace("figma-raster/${png.name}", "data:image/png;base64,$b64")
          }
          File(outDir, "$previewId.inlined.svg").writeText(inlined)
        }
      }
    rule.apply(statement, Description.createTestDescription(javaClass, previewId)).evaluate()
    return svg
  }

  private companion object {
    /** Wear's real screen density — the alpha block converts dp inside itself, so 1x hid the bug. */
    const val DENSITY = 2f
    val LABELS = listOf("Latest episode", "Queue", "Library", "Podcasts", "Downloads")
    // ListHeader paints through its transformed container painter and is intentionally rasterised;
    // these are the editable descendants whose disappearance exposed the alpha contamination.
    val REQUIRED_CONTENT = listOf("▶", "+", "Play", "Jun 2, 2020", "summary")
    val OPACITY = Regex("""\bopacity="([\d.]+)"""")
    val OPAQUE_BUTTON_FILL = Regex("""<rect [^>]*fill="#E9DDFF"""")
    /** Below this the layer is invisible on screen — no authored Wear fade lands here. */
    const val MIN_VISIBLE = 0.1
  }
}

@OptIn(InternalComposeApi::class)
@Composable
private fun InspectableAlphaContent(
  capture: MutableSet<CompositionData>,
  content: @Composable () -> Unit,
) {
  currentComposer.collectParameterInformation()
  capture.add(currentComposer.compositionData)
  CompositionLocalProvider(LocalInspectionTables provides capture, content = content)
}
