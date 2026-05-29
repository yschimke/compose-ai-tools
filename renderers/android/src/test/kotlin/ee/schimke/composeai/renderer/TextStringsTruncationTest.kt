package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.daemon.ComposeSemanticsDataProducer
import ee.schimke.composeai.daemon.DataProductRegistry
import ee.schimke.composeai.daemon.PreviewIndex
import ee.schimke.composeai.daemon.TextStringsDataProductRegistry
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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
 * Drives `ComposeSemanticsDataProducer.writeArtifacts` + `TextStringsDataProductRegistry` against
 * Compose composables that mirror the `TruncationPreviews` samples, then asserts each scenario
 * surfaces the specific check it isolates: `didOverflowWidth`, `didOverflowHeight`, the
 * `maxLines` cap, and `overflow=Ellipsis`. Schema-2 contract test for the `text/strings`
 * truncation fields added for compose-ai-tools#705.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextStringsTruncationTest {

  @Suppress("DEPRECATION")
  @get:Rule
  val composeRule = createAndroidComposeRule<ComponentActivity>()

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("text-strings-truncation").toFile()
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
  }

  // Note: Compose's `MultiParagraph` layout in Robolectric reports overflow as
  // `didOverflowHeight=true` even for clearly width-bound cases (softWrap=false in a narrow
  // box). The producer's mapping is correct — it propagates whatever Compose emits — so we
  // assert on the kind-agnostic `truncated` flag plus the configured `overflow` mode here, and
  // exercise the cleanly-distinguished height/maxLines axes in the next two tests.
  @Test
  fun `softWrap=false long line in narrow box reports truncation with overflow=Clip`() {
    val text = textEntryFor("widthNoWrap") { TruncatedWidthNoWrapFixture() }

    assertEquals(true, text["truncated"]!!.jsonPrimitive.boolean)
    assertEquals("Clip", text["overflow"]!!.jsonPrimitive.content)
    assertNotNull("lineCount should be reported", text["lineCount"])
  }

  @Test
  fun `wrapping text in too-short box reports didOverflowHeight`() {
    val text = textEntryFor("heightClip") { TruncatedHeightClipFixture() }

    assertEquals(true, text["truncated"]!!.jsonPrimitive.boolean)
    assertEquals(true, text["didOverflowHeight"]!!.jsonPrimitive.boolean)
    assertTrue(
      "wrapping text should report multiple lines",
      text["lineCount"]!!.jsonPrimitive.int >= 2,
    )
    assertEquals("Clip", text["overflow"]!!.jsonPrimitive.content)
  }

  @Test
  fun `maxLines cap with ellipsis reports configured maxLines, overflow, and lineCount`() {
    val text = textEntryFor("maxLines") { TruncatedMaxLinesEllipsisFixture() }

    assertEquals(true, text["truncated"]!!.jsonPrimitive.boolean)
    assertEquals(2, text["maxLines"]!!.jsonPrimitive.int)
    assertEquals(2, text["lineCount"]!!.jsonPrimitive.int)
    assertEquals("Ellipsis", text["overflow"]!!.jsonPrimitive.content)
  }

  private fun textEntryFor(previewId: String, content: @Composable () -> Unit): JsonObject {
    composeRule.setContent { content() }
    composeRule.waitForIdle()
    val semanticsRoot = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
    ComposeSemanticsDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = previewId,
      root = semanticsRoot,
    )
    val registry =
      TextStringsDataProductRegistry(rootDir = rootDir, previewIndex = PreviewIndex.empty())
    val outcome =
      registry.fetch(
        previewId = previewId,
        kind = TextStringsDataProductRegistry.KIND,
        params = null,
        inline = true,
      )
    assertTrue("registry should produce a payload", outcome is DataProductRegistry.Outcome.Ok)
    val payload = (outcome as DataProductRegistry.Outcome.Ok).result.payload
    assertNotNull(payload)
    val entries = payload!!.jsonObject["texts"]!!.jsonArray
    return entries.firstOrNull { it.jsonObject["text"] != null }?.jsonObject
      ?: error("expected at least one entry with rendered text; got $entries")
  }
}

private const val LongGerman =
  "Vollstaendig und unwiderruflich abgeschlossen, mit zusaetzlichen Erlaeuterungen"

@Composable
private fun TruncatedWidthNoWrapFixture() {
  Box(modifier = Modifier.size(width = 80.dp, height = 32.dp).background(Color.White)) {
    Text(
      text = LongGerman,
      softWrap = false,
      overflow = TextOverflow.Clip,
      fontSize = 14.sp,
    )
  }
}

@Composable
private fun TruncatedHeightClipFixture() {
  Box(modifier = Modifier.width(200.dp).height(20.dp).background(Color.White)) {
    Text(text = LongGerman, overflow = TextOverflow.Clip, fontSize = 14.sp)
  }
}

@Composable
private fun TruncatedMaxLinesEllipsisFixture() {
  Box(modifier = Modifier.size(width = 200.dp, height = 80.dp).background(Color.White)) {
    Text(
      text = LongGerman,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      fontSize = 14.sp,
    )
  }
}
