package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.daemon.DataProductRegistry
import ee.schimke.composeai.daemon.LayoutInspectorDataProducer
import ee.schimke.composeai.daemon.LayoutInspectorDataProductRegistry
import ee.schimke.composeai.data.render.PreviewContext
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Drives `LayoutInspectorDataProducer.writeArtifacts` + `LayoutInspectorDataProductRegistry`
 * against a Compose composition with a known size, then asserts the produced JSON tree carries
 * the structural facts the inspector promises at the root: a `LayoutInspectorNode` with
 * populated `bounds`, `size`, `component`, and `modifiers`. Companion to
 * `TextStringsTruncationTest`, `ComposeSemanticsCoreFieldsTest`, and
 * `I18nTranslationsFixtureTest`.
 *
 * Scope note: under a bare `setContent` + `waitForIdle()`, the producer's child-walk reflects
 * over `LayoutNode.getZSortedChildren$ui_release` which short-circuits to an empty iterable
 * before the LayoutNode has been z-sorted (the real `RenderEngine` walks it after `measure`
 * + `draw` have run, which Z-sorts the layout tree as a side effect). We therefore only assert
 * on what the root node always carries; deeper subtree + per-modifier assertions are covered
 * end-to-end by the gradle-plugin functional tests that exercise the full render pipeline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayoutInspectorFixtureTest {

  @Suppress("DEPRECATION")
  @get:Rule
  val composeRule = createAndroidComposeRule<ComponentActivity>()

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("layout-inspector-fixture").toFile()
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
  }

  @Test
  fun `Box surfaces non-empty bounds + size and a populated modifier chain at the root`() {
    val root = rootFor("box") {
      Box(
        modifier =
          Modifier.testTag("hero").size(160.dp, 80.dp).background(Color.White).padding(8.dp)
      )
    }

    assertEquals("RootMeasurePolicy", root["component"]!!.jsonPrimitive.content)
    val size = root["size"]!!.jsonObject
    assertEquals(160, size["width"]!!.jsonPrimitive.int)
    assertEquals(80, size["height"]!!.jsonPrimitive.int)
    val bounds = root["bounds"]!!.jsonObject
    assertTrue(
      "root bounds should describe a non-degenerate rect",
      bounds["right"]!!.jsonPrimitive.int > bounds["left"]!!.jsonPrimitive.int &&
        bounds["bottom"]!!.jsonPrimitive.int > bounds["top"]!!.jsonPrimitive.int,
    )
    val modifiers = root["modifiers"]!!.jsonArray
    assertTrue("modifier chain on the root should be non-empty", modifiers.isNotEmpty())
    for (m in modifiers) {
      val mod = m.jsonObject
      assertNotNull("each modifier entry should carry a name", mod["name"])
      val modBounds = mod["bounds"]?.jsonObject
      assertNotNull("each modifier entry should carry bounds", modBounds)
    }
  }

  @Test
  fun `registry capability advertises layout-inspector at schema 1 and path transport`() {
    val registry = LayoutInspectorDataProductRegistry(rootDir)
    val cap = registry.capabilities.single()
    assertEquals("layout/inspector", cap.kind)
    assertEquals(1, cap.schemaVersion)
    assertTrue(cap.attachable)
    assertTrue(cap.fetchable)
  }

  /**
   * Builds the producer's `PreviewContext` from the live ComposeRule semantics root, mirroring
   * the real `RenderEngine`'s capture wiring: the content is wrapped in an inspection-aware
   * composable that pumps `currentComposer.compositionData` into a `LocalInspectionTables` set,
   * and the slot tables are passed through `addSlotTables(...)` so the producer can attach
   * source/info to LayoutNode entries via the slot table walk.
   */
  private fun rootFor(previewId: String, content: @Composable () -> Unit): JsonObject {
    val slotTables = mutableSetOf<CompositionData>()
    composeRule.setContent { InspectableContent(slotTables, content) }
    composeRule.waitForIdle()
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
    LayoutInspectorDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = previewId,
      previewContext = previewContext,
    )
    val registry = LayoutInspectorDataProductRegistry(rootDir)
    val outcome =
      registry.fetch(
        previewId = previewId,
        kind = LayoutInspectorDataProducer.KIND,
        params = null,
        inline = true,
      )
    assertTrue("registry should produce a payload", outcome is DataProductRegistry.Outcome.Ok)
    val payload = (outcome as DataProductRegistry.Outcome.Ok).result.payload
    assertNotNull(payload)
    return payload!!.jsonObject["root"]!!.jsonObject
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
