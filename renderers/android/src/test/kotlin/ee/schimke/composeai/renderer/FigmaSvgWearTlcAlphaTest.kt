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
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.FilledIconButton
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
 * Guard for #2615's *alpha* half: the export must never fade transformed Wear content to
 * invisibility.
 *
 * Wear's `TransformingLazyColumn` + `SurfaceTransformation` fades items toward the round face's
 * edges through a `graphicsLayer { alpha = … }` block whose value is derived from the item's scroll
 * progress, and the exporter evaluates that block reflectively against a proxy scope. On Jetcaster
 * Wear that evaluation produces `opacity="0.0"` / `opacity="0.04"` groups around content the PNG
 * paints fully opaque.
 *
 * **This fixture does not currently reproduce that failure** — the production shapes the issue names
 * (`ButtonGroup` + `FilledIconButton`s, a transformed `Button`, transformed `TitleCard` rows, at
 * Wear's 2x density) all evaluate to sane edge fades here (~0.5–1.0). It is kept as the standing
 * guard the issue asks for, so the day the real path is reproduced the assertion is already in
 * place; the Jetcaster-specific trigger is still unidentified.
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
              TransformingLazyColumn(
                state = state,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
              ) {
                item {
                  ButtonGroup(Modifier.fillMaxWidth().transformedHeight(this, spec)) {
                    FilledIconButton(onClick = {}, modifier = Modifier.weight(1f)) { Text("<") }
                    FilledIconButton(onClick = {}, modifier = Modifier.weight(1f)) { Text(">") }
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
    val OPACITY = Regex("""\bopacity="([\d.]+)"""")
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
