package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
 * Regression for #3057 — a lazy-list item straddling the viewport edge kept its surface but lost
 * its text.
 *
 * The two producers disagree about clipping: the layout-inspector records every node's
 * **unclipped** box (`localBoundingBoxOf(clipBounds = false)`), while a semantics node's
 * `boundsInRoot` is **clipped** by its ancestors. For a fully visible row the two agree, so the
 * exporter's bounds-matching attaches the text to its layer. For the first/last row of a
 * `LazyColumn` — half above the viewport's top edge, half below the bottom — they differ by exactly
 * the clipped-away strip, which blows past the 2px matching tolerance, so the text was never
 * attached and the row's `<g>` came out empty while the PNG painted the visible lines.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigmaSvgClippedLazyItemTextTest {

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("figma-svg-clipped-lazy").toFile()
    System.setProperty("roborazzi.test.record", "true")
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
    System.clearProperty("roborazzi.test.record")
  }

  @Test
  fun `text on lazy items straddling both viewport edges stays in the export`() {
    val svg =
      renderSvg("clipped-lazy-text") {
        MaterialTheme {
          // The list is scrolled so the FIRST item is half above the top edge; the content is tall
          // enough that the LAST composed item runs off the bottom — both edges in one capture.
          val state =
            rememberLazyListState(
              initialFirstVisibleItemIndex = 0,
              initialFirstVisibleItemScrollOffset = 30,
            )
          LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            items(ROWS) { label ->
              Box(
                Modifier.fillMaxWidth().height(120.dp).padding(8.dp).background(Color(0xFFE7E0EC))
              ) {
                Text(label, Modifier.padding(12.dp))
              }
            }
          }
        }
      }

    File("build/figma-svg-clipped-lazy").apply { mkdirs() }
    File("build/figma-svg-clipped-lazy/clipped-lazy-text.svg").writeText(svg)

    // The straddling first row is the one the bug blanked — the rows fully inside the viewport are
    // the control, and they always exported fine.
    assertTrue(
      "the top-clipped row must keep its text:\n$svg",
      svg.contains(">${ROWS.first()}</text>"),
    )
    val present = ROWS.filter { svg.contains(">$it</text>") }
    assertTrue(
      "the fully visible rows must keep their text too; got $present:\n$svg",
      present.size >= 4,
    )
  }

  private fun renderSvg(previewId: String, content: @Composable () -> Unit): String {
    RuntimeEnvironment.setQualifiers("w400dp-h600dp-mdpi")
    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()
    var svg = ""
    val statement =
      object : Statement() {
        override fun evaluate() {
          val slots = mutableSetOf<CompositionData>()
          rule.setContent { InspectableClippedContent(slots, content) }
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
          val outDir = File("build/figma-svg-clipped-lazy").apply { mkdirs() }
          frame.copyTo(File(outDir, "$previewId-frame.png"), overwrite = true)
        }
      }
    rule.apply(statement, Description.createTestDescription(javaClass, previewId)).evaluate()
    return svg
  }

  private companion object {
    val ROWS =
      listOf("Alpha row", "Bravo row", "Charlie row", "Delta row", "Echo row", "Foxtrot row")
  }
}

@OptIn(InternalComposeApi::class)
@Composable
private fun InspectableClippedContent(
  capture: MutableSet<CompositionData>,
  content: @Composable () -> Unit,
) {
  currentComposer.collectParameterInformation()
  capture.add(currentComposer.compositionData)
  CompositionLocalProvider(LocalInspectionTables provides capture, content = content)
}
