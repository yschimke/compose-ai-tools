package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PickerGroup
import androidx.wear.compose.material.PickerGroupItem
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.rememberPickerGroupState
import androidx.wear.compose.material.rememberPickerState
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

/** Regression coverage for the legacy Wear PickerGroup used by Horologist's TimePicker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigmaSvgWearPickerTest {

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("figma-svg-wear-picker").toFile()
    System.setProperty("roborazzi.test.record", "true")
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
    System.clearProperty("roborazzi.test.record")
  }

  @Test
  fun `picker options and cleared-semantics separator survive as visible SVG content`() {
    val svg =
      renderSvg("wear-picker") {
        MaterialTheme {
          Box(Modifier.fillMaxSize().background(Color.Black)) {
            val hours = rememberPickerState(24, initiallySelectedOption = 9, repeatItems = true)
            val minutes = rememberPickerState(60, initiallySelectedOption = 41, repeatItems = true)
            PickerGroup(
              PickerGroupItem(
                pickerState = hours,
                modifier = Modifier.width(64.dp),
                option = { value, _ ->
                  Box(Modifier.fillMaxSize()) { Text(value.toString().padStart(2, '0')) }
                },
              ),
              PickerGroupItem(
                pickerState = minutes,
                modifier = Modifier.width(64.dp),
                option = { value, _ ->
                  Box(Modifier.fillMaxSize()) { Text(value.toString().padStart(2, '0')) }
                },
              ),
              modifier = Modifier.fillMaxSize(),
              pickerGroupState = rememberPickerGroupState(0),
              separator = { Text(":", modifier = Modifier.clearAndSetSemantics {}) },
              autoCenter = false,
            )
          }
        }
      }

    val outDir = File("build/figma-svg-wear-picker").apply { mkdirs() }
    File(outDir, "wear-picker.svg").writeText(svg)

    assertTrue("the editable separator is missing:\n$svg", svg.contains(">:</text>"))
    assertEquals(
      "both draw-masked picker columns must use exact frame crops:\n$svg",
      2,
      Regex("<image ").findAll(svg).count(),
    )
    listOf("08", "09", "10", "40", "41", "42").forEach { masked ->
      assertFalse(
        "masked picker option '$masked' leaked as SVG text:\n$svg",
        svg.contains(">$masked</text>"),
      )
    }
    assertTrue(
      "the cleared-semantics separator must retain its 16sp typography at 2x density:\n$svg",
      Regex("""<text [^>]*font-size="32"[^>]*>:</text>""").containsMatchIn(svg),
    )
  }

  @Test
  fun `cleared-semantics text keeps the render's nonlinear resolved font size`() {
    var resolvedFontSizePx = 0f
    val svg =
      renderSvg("wear-picker-font-scale", fontScale = 1.5f) {
        MaterialTheme {
          resolvedFontSizePx = with(LocalDensity.current) { 32.sp.toPx() }
          Text(
            ":",
            modifier = Modifier.clearAndSetSemantics {},
            style = TextStyle(fontSize = 32.sp),
          )
        }
      }

    val emittedFontSize =
      Regex("""<text [^>]*font-size="([0-9.]+)"[^>]*>:</text>""")
        .find(svg)
        ?.groupValues
        ?.get(1)
        ?.toDouble()
        ?: error("resolved separator font size is missing:\n$svg")
    assertEquals(resolvedFontSizePx.toDouble(), emittedFontSize, 0.01)
    assertTrue(
      "API 34 nonlinear scaling should be smaller than the legacy 32sp × 2 × 1.5 result",
      emittedFontSize < 96.0,
    )
  }

  private fun renderSvg(
    previewId: String,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
  ): String {
    RuntimeEnvironment.setQualifiers("w227dp-h227dp-round-xhdpi")
    RuntimeEnvironment.setFontScale(fontScale)
    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()
    var svg = ""
    val statement =
      object : Statement() {
        override fun evaluate() {
          val slots = mutableSetOf<CompositionData>()
          rule.setContent { InspectablePickerContent(slots, content) }
          rule.waitForIdle()
          val frame = File(rootDir, "$previewId-frame.png")
          rule.onRoot().captureRoboImage(file = frame)
          val semRoot = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
          val ctx =
            PreviewContext.Builder(previewId, null, null, previewId)
              .rootForTest(semRoot.root as RootForTest)
              .addSlotTables(slots.toList())
              .parameterInformationCollected()
              .build()
          val layout =
            LayoutInspectorDataProducer.buildPayload(
              ctx,
              density = 2f,
              fontScale = fontScale,
            )!!
          val semantics = ComposeSemanticsDataProducer.buildPayload(semRoot, density = 2f)
          ComposeFigmaSvgDataProducer.writeSvg(
            rootDir = rootDir,
            previewId = previewId,
            layout = layout,
            semantics = semantics,
            density = 2f,
            fontScale = fontScale,
            frameImage = frame,
            roundClip = true,
          )
          val generated = File(rootDir, previewId)
          svg = File(generated, "compose-figma.svg").readText()
          val evidence = File("build/figma-svg-wear-picker").apply { mkdirs() }
          frame.copyTo(File(evidence, "$previewId-frame.png"), overwrite = true)
          File(generated, "figma-raster")
            .takeIf { it.exists() }
            ?.copyRecursively(File(evidence, "figma-raster"), overwrite = true)
        }
      }
    rule.apply(statement, Description.createTestDescription(javaClass, previewId)).evaluate()
    return svg
  }
}

@OptIn(InternalComposeApi::class)
@Composable
private fun InspectablePickerContent(
  capture: MutableSet<CompositionData>,
  content: @Composable () -> Unit,
) {
  currentComposer.collectParameterInformation()
  capture.add(currentComposer.compositionData)
  CompositionLocalProvider(LocalInspectionTables provides capture, content = content)
}
