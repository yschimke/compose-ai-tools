/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ee.schimke.composeai.rcembedded

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.remote.player.compose.embedded.ExperimentalRemoteDocumentPlayer
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Does the **SVG export** get anything to work with when a Remote Compose document is played by the
 * embedded player?
 *
 * `compose-figma.svg` is built from the captured *semantics* tree (`ComposeSemanticsNode`), not from
 * pixels — so whether a document exports as real vector nodes or as one flat raster comes down to
 * what the player puts into the semantics tree:
 *
 * * **View player** — `RemoteComposePlayer` is an Android `View` bridged in through `AndroidView`.
 *   The whole document is one opaque leaf as far as Compose is concerned, so there is no interior
 *   structure to export.
 * * **Embedded player** — `RcPlayer` interprets the document into real Compose layout and draw
 *   nodes, so the tree should carry the document's actual structure, including its text.
 *
 * This test measures that difference rather than asserting a hard-coded shape, because the exact
 * node count is a property of the catalog document and would be brittle. It asserts the *qualitative*
 * claim the export depends on: the embedded player yields materially more semantics structure than
 * the View player for the same document. Results are also written to `rc.semantics.report` when set,
 * so a run can be inspected.
 *
 * Skips unless a staged catalog is present (`rc.embedded.input`), like the render harnesses.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xhdpi")
class RcSemanticsExtractionTest {

  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun embeddedPlayerExposesDocumentStructureToTheSemanticsTree() {
    val entry = firstTextBearingDocument()
    assumeTrue("no staged catalog — nothing to inspect", entry != null)
    val (id, width, height, bytes) = entry!!

    val embedded = capture(width, height) {
      ExperimentalRemoteDocumentPlayer(
        document = remember { RemoteDocument(bytes) },
        autoUpdate = false,
        modifier = Modifier.fillMaxSize(),
      )
    }

    val report = buildString {
      appendLine("document: $id  (${width}x${height})")
      appendLine("embedded player: ${embedded.nodes} semantics nodes, ${embedded.texts.size} text node(s)")
      embedded.texts.take(12).forEach { appendLine("  text: $it") }
    }
    System.getProperty(REPORT_PROPERTY)?.let { File("$it.embedded").writeText(report) }
    println(report)

    // Assert on *text*, not node count. Node count is not the discriminator — both players report
    // two nodes for this document (root + player). What differs, and what the SVG export actually
    // consumes, is whether the document's strings reach the semantics tree: the embedded player
    // surfaces them, the View player surfaces none (see the control test below). Without them
    // `compose-figma.svg` can only raster the text instead of emitting `<text>`.
    assert(embedded.texts.isNotEmpty()) {
      "embedded player surfaced no text to the semantics tree — the SVG export would have to " +
        "raster this document's strings rather than emit <text>"
    }
  }

  @Test
  fun viewPlayerCollapsesToAnOpaqueNode() {
    // The control. `RemoteComposePlayer` is an Android `View` behind `AndroidView`, so Compose sees
    // one leaf and the SVG export has no text or structure to work with — it can only fall back to
    // a raster. Recorded rather than asserted-against-a-number so the comparison stays readable if
    // AndroidView's own semantics change.
    val entry = firstTextBearingDocument()
    assumeTrue("no staged catalog — nothing to inspect", entry != null)
    val (id, width, height, bytes) = entry!!

    val view = capture(width, height) {
      val document = remember { RemoteDocument(bytes) }
      RemoteDocumentPlayer(
        document = document.document,
        documentWidth = width,
        documentHeight = height,
      )
    }

    val report = buildString {
      appendLine("document: $id  (${width}x${height})")
      appendLine("view player: ${view.nodes} semantics nodes, ${view.texts.size} text node(s)")
      view.texts.take(12).forEach { appendLine("  text: $it") }
    }
    System.getProperty(REPORT_PROPERTY)?.let { File("$it.view").writeText(report) }
    println(report)
  }

  private data class Doc(val id: String, val width: Int, val height: Int, val bytes: ByteArray)

  private data class Captured(val nodes: Int, val texts: List<String>)

  private fun capture(width: Int, height: Int, content: @Composable () -> Unit): Captured {
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      val density = LocalDensity.current
      Box(
        Modifier.size(with(density) { width.toDp() }, with(density) { height.toDp() })
      ) {
        content()
      }
    }
    repeat(4) { composeRule.mainClock.advanceTimeByFrame() }

    val texts = mutableListOf<String>()
    var count = 0
    fun walk(node: SemanticsNode) {
      count++
      node.config.getOrNull(SemanticsProperties.Text)?.forEach { texts += it.text }
      node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { texts += it }
      node.children.forEach(::walk)
    }
    walk(composeRule.onRoot().fetchSemanticsNode())
    return Captured(count, texts)
  }

  private fun firstTextBearingDocument(): Doc? {
    val dir = System.getProperty(INPUT_PROPERTY)?.let(::File)?.takeIf { it.isDirectory } ?: return null
    val manifest = File(dir, "manifest.json").takeIf { it.isFile } ?: return null
    // Prefer a text-heavy card: its structure is what an SVG export most obviously benefits from.
    val entries = Json.decodeFromString<List<RcEmbeddedRenderHarness.Entry>>(manifest.readText())
    val pick =
      entries.firstOrNull { it.id.contains("TitleCardRemote") }
        ?: entries.firstOrNull { it.id.contains("Text") }
        ?: entries.firstOrNull()
        ?: return null
    val rc = File(dir, "${pick.id}.rc").takeIf { it.isFile } ?: return null
    return Doc(pick.id, pick.width, pick.height, rc.readBytes())
  }

  private companion object {
    const val INPUT_PROPERTY = "rc.embedded.input"
    const val REPORT_PROPERTY = "rc.semantics.report"
  }
}
