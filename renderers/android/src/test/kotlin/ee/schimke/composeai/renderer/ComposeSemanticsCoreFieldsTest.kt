package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.daemon.ComposeSemanticsDataProducer
import ee.schimke.composeai.daemon.ComposeSemanticsDataProductRegistry
import ee.schimke.composeai.daemon.DataProductRegistry
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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
 * Drives `ComposeSemanticsDataProducer.writeArtifacts` + `ComposeSemanticsDataProductRegistry`
 * against Compose composables that mirror the `SemanticsCoreFieldsPreviews` samples, then
 * asserts each scenario surfaces the specific core projection it isolates: `testTag`, `label`
 * (from `contentDescription`), `role` + `clickable`, and `mergeMode`. Companion to
 * `TextStringsTruncationTest` for the layout-derived `text/strings` fields.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeSemanticsCoreFieldsTest {

  @Suppress("DEPRECATION")
  @get:Rule
  val composeRule = createAndroidComposeRule<ComponentActivity>()

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("compose-semantics-core-fields").toFile()
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
  }

  @Test
  fun `Modifier_testTag surfaces as testTag on the matching node`() {
    val nodes = nodesFor("testTag") { TestTagFixture() }
    val tagged =
      nodes.firstOrNull { it["testTag"]?.jsonPrimitive?.content == "hero-title" }
        ?: error("expected a node with testTag=hero-title; got $nodes")

    assertEquals("Hero", tagged["text"]!!.jsonPrimitive.content)
    assertEquals("Hero", tagged["label"]!!.jsonPrimitive.content)
  }

  @Test
  fun `Modifier_semantics contentDescription surfaces as label`() {
    val nodes = nodesFor("contentDescription") { ContentDescriptionFixture() }
    val labelled =
      nodes.firstOrNull { it["label"]?.jsonPrimitive?.content == "decorative-heart" }
        ?: error("expected a node with label=decorative-heart; got $nodes")

    // Pure contentDescription has no rendered text — the producer drops `text` for these nodes.
    assertEquals(null, labelled["text"]?.jsonPrimitive?.content)
  }

  @Test
  fun `Material Button surfaces clickable=true and role=Button`() {
    val nodes = nodesFor("clickableButton") { ClickableButtonFixture() }
    val clickable =
      nodes.firstOrNull { it["clickable"]?.jsonPrimitive?.boolean == true }
        ?: error("expected at least one clickable node; got $nodes")

    assertEquals("Button", clickable["role"]!!.jsonPrimitive.content)
  }

  @Test
  fun `Modifier_semantics mergeDescendants surfaces as mergeMode=mergeDescendants`() {
    val nodes = nodesFor("mergeDescendants") { MergeDescendantsFixture() }
    val merging =
      nodes.firstOrNull { it["mergeMode"]?.jsonPrimitive?.content == "mergeDescendants" }
        ?: error("expected a node with mergeMode=mergeDescendants; got $nodes")

    assertNotNull(merging)
  }

  /** Returns every node in the produced compose-semantics JSON tree as a flat list. */
  private fun nodesFor(previewId: String, content: @Composable () -> Unit): List<JsonObject> {
    composeRule.setContent { content() }
    composeRule.waitForIdle()
    val semanticsRoot = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
    ComposeSemanticsDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = previewId,
      root = semanticsRoot,
    )
    val registry = ComposeSemanticsDataProductRegistry(rootDir)
    val outcome =
      registry.fetch(
        previewId = previewId,
        kind = ComposeSemanticsDataProducer.KIND,
        params = null,
        inline = true,
      )
    assertTrue("registry should produce a payload", outcome is DataProductRegistry.Outcome.Ok)
    val payload = (outcome as DataProductRegistry.Outcome.Ok).result.payload
    assertNotNull(payload)
    return collect(payload!!.jsonObject["root"]!!.jsonObject)
  }

  private fun collect(node: JsonObject): List<JsonObject> {
    val children = node["children"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
    return listOf(node) + children.flatMap { collect(it) }
  }
}

@Composable
private fun TestTagFixture() {
  Box(modifier = Modifier.size(200.dp, 32.dp).background(Color.White)) {
    Text(text = "Hero", modifier = Modifier.testTag("hero-title"))
  }
}

@Composable
private fun ContentDescriptionFixture() {
  Box(
    modifier =
      Modifier.size(64.dp).background(Color.Red).semantics {
        contentDescription = "decorative-heart"
      }
  )
}

@Composable
private fun ClickableButtonFixture() {
  Box(modifier = Modifier.size(200.dp, 56.dp).background(Color.White)) {
    Button(onClick = {}) { Text(text = "Buy") }
  }
}

@Composable
private fun MergeDescendantsFixture() {
  Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
    Text(text = "Title")
    Text(text = "Subtitle")
  }
}
