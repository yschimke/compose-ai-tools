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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View.MeasureSpec
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.remote.player.compose.embedded.ExperimentalRemoteDocumentPlayer
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import ee.schimke.composeai.daemon.ComposeFigmaSvgDataProducer
import ee.schimke.composeai.daemon.ComposeSemanticsDataProducer
import ee.schimke.composeai.daemon.LayoutInspectorDataProducer
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorNode
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorPayload
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Executes the claim the vector-export second pass rests on, end to end: play one real staged
 * Remote Compose document through each player, run the **production** `compose/figma-svg` export
 * over the resulting tree, and read what actually landed in `compose-figma.svg`.
 *
 * The two lanes differ only in the player:
 * * **View player** — `RemoteDocumentPlayer` bridges `remote-player-view`'s Android `View` in
 *   through `AndroidView`, which the layout-inspector walk sees as one opaque node. `AndroidView`
 *   is in `FigmaSvgModel.DEFAULT_RASTER_COMPONENTS`, so in hybrid mode (a frame PNG is supplied,
 *   exactly as `RenderEngine` supplies one) it should crop out as a single flat `<image>`.
 * * **Embedded player** — `ExperimentalRemoteDocumentPlayer` (`RcPlayer`) interprets the document
 *   into real Compose layout and draw nodes, so the export should see interior structure and emit
 *   real vector content.
 *
 * Asserted qualitatively, not against exact counts: the counts are a property of whichever catalog
 * document is staged and would be brittle. Both lanes print a full report (element counts plus a
 * head of the SVG) so a human can read the real numbers off a run, and write it beside
 * `rc.semantics.report` when that property is set.
 *
 * **One document per test case, and one lane per test case**, because `setContent` may be called
 * only once per `ComposeTestRule` — the same constraint `RcSemanticsExtractionTest` and
 * `RcEmbeddedRenderHarness` are shaped around. The two lanes therefore assert their own half of the
 * comparison rather than diffing in one method.
 *
 * Slot tables **are** available here: `PreviewSlotTableCapture` lives in a module this one can't
 * reach, but it is only a thin wrapper over `LocalInspectionTables`, which is plain
 * `compose-runtime` — so [InspectableContent] below captures them directly, the same way
 * `FigmaSvgVectorIconRenderTest` does in `:renderer-android`. Without them
 * `LayoutInspectorDataProducer.buildPayload` still returns a tree, but its nodes lose their
 * composable names.
 *
 * Density is pinned to `xhdpi` (2.0) because the catalogs capture at dpi 320 and the documents bake
 * dp->px at that factor; rendering at another density re-lays-out the document.
 *
 * **What a run against `design-artifacts/remote-m3`'s `TitleCardRemote` showed** (kept here because
 * the numbers qualify the claim rather than merely confirm it): the embedded lane exported 2
 * `<text>` and 0 `<image>` over 10 layout nodes; the view lane exported 0 `<text>` and exactly 1
 * full-bleed `<image>` over 3 nodes. So the text half of the claim holds outright. What the
 * embedded lane did **not** produce is any non-text drawing — 0 `<path>`, 0 `<rect>` — so the card's
 * background and shapes are absent and the exported canvas shrinks to the text's own extent
 * (251x110) rather than the document's (640x480). The report therefore prints the canvas size next
 * to the document size, so a regression in coverage is visible without re-deriving it.
 *
 * Runs against a **committed 1 KB fixture** by default, so the coverage actually executes on a plain
 * `check` — unlike the sibling render harnesses, which skip without `rc.embedded.input` because they
 * rasterize a whole catalog. Setting `rc.embedded.input` still wins, so the same assertions can be
 * swept over a staged catalog locally.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xhdpi")
class RcFigmaSvgExportTest {

  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun embeddedPlayerExportsVectorContent() {
    val doc = document()
    val lane = export("embedded", doc) { bytes ->
      ExperimentalRemoteDocumentPlayer(
        document = remember { RemoteDocument(bytes) },
        autoUpdate = false,
        modifier = Modifier.fillMaxSize(),
      )
    }
    report(doc, lane)

    assert(lane.svg.isNotEmpty()) { "the embedded lane wrote no compose-figma.svg at all" }
    assert(lane.texts > 0) {
      "the embedded lane's SVG carries no <text> — the export got nothing vector out of the " +
        "embedded player:\n${lane.head()}"
    }
    assert(lane.elements > lane.images + 1) {
      "the embedded lane's SVG is essentially just raster crops (${lane.images} <image> of " +
        "${lane.elements} elements):\n${lane.head()}"
    }
  }

  @Test
  fun viewPlayerExportsOneFlatRaster() {
    val doc = document()
    val lane = export("view", doc) { bytes ->
      val document = remember { RemoteDocument(bytes) }
      RemoteDocumentPlayer(
        document = document.document,
        documentWidth = doc.width,
        documentHeight = doc.height,
      )
    }
    report(doc, lane)

    // Pinned tightly, because this lane is the *control*: the embedded lane's result is only
    // meaningful relative to "one flat raster covering the whole document". A weaker assertion
    // (merely `images > 0`) would keep passing if the control drifted into several crops, grew
    // vector layers of its own, or started cropping its canvas — and the comparison would quietly
    // stop meaning what it says.
    assert(lane.svg.isNotEmpty()) { "the view lane wrote no compose-figma.svg at all" }
    assert(lane.texts == 0) {
      "the view lane surfaced ${lane.texts} <text> element(s) — the AndroidView bridge was " +
        "expected to be opaque:\n${lane.head()}"
    }
    assert(lane.images == 1) {
      "expected exactly one <image> from the opaque AndroidView, got ${lane.images} — the control " +
        "is no longer a single flat raster:\n${lane.head()}"
    }
    assert(lane.paths == 0 && lane.rects == 0) {
      "the view lane emitted drawn vector content (path=${lane.paths} rect=${lane.rects}); the " +
        "raster crop was expected to be all of it:\n${lane.head()}"
    }
    val (w, h) = lane.canvas()
    assert(w >= doc.width && h >= doc.height) {
      "the view lane's canvas is ${w}x$h, smaller than the ${doc.width}x${doc.height} document — " +
        "the control is expected to cover the whole frame, not shrink-wrap to its content"
    }
  }

  /** One staged catalog document. */
  private data class Doc(val id: String, val width: Int, val height: Int, val bytes: ByteArray)

  /** What one lane's `compose-figma.svg` came out as. */
  private data class Lane(
    val name: String,
    val svg: String,
    val elements: Int,
    val texts: Int,
    val images: Int,
    val paths: Int,
    val rects: Int,
    val groups: Int,
    val layoutNodes: Int,
    val semanticsTexts: Int,
    val note: String,
  ) {
    fun head(): String = svg.take(1600)

    /** The exported canvas, in px — how much of the document the export actually covers. */
    fun canvas(): Pair<Int, Int> {
      val w = Regex("""<svg[^>]*\bwidth="(\d+)"""").find(svg)?.groupValues?.get(1)?.toInt() ?: 0
      val h = Regex("""<svg[^>]*\bheight="(\d+)"""").find(svg)?.groupValues?.get(1)?.toInt() ?: 0
      return w to h
    }
  }

  /**
   * Composes [content] with the staged document's bytes, forces a real measure/layout/draw (the
   * layout-inspector walk reflects over `LayoutNode.getZSortedChildren`, empty until a draw z-sorts
   * them), then runs the production capture + hybrid figma-svg export and reads the SVG back.
   *
   * The frame PNG is drawn straight off the content view rather than through Roborazzi: `RcPlayer`
   * animates off the frame clock and never reaches idle, so every wait-for-idle capture API times
   * out — the same manual-clock + direct-draw workaround `RcEmbeddedRenderHarness` documents.
   */
  private fun export(name: String, doc: Doc, content: @Composable (ByteArray) -> Unit): Lane {
    val rootDir = Files.createTempDirectory("rc-figma-svg-$name").toFile()
    composeRule.mainClock.autoAdvance = false

    val slotTables = mutableSetOf<CompositionData>()
    var density = 1f
    composeRule.setContent {
      density = LocalDensity.current.density
      InspectableContent(slotTables) {
        Box(
          Modifier.size(
            with(LocalDensity.current) { doc.width.toDp() },
            with(LocalDensity.current) { doc.height.toDp() },
          )
        ) {
          content(doc.bytes)
        }
      }
    }
    repeat(FRAMES) { composeRule.mainClock.advanceTimeByFrame() }

    val view = composeRule.activity.findViewById<ViewGroup>(android.R.id.content)
    view.measure(
      MeasureSpec.makeMeasureSpec(doc.width, MeasureSpec.EXACTLY),
      MeasureSpec.makeMeasureSpec(doc.height, MeasureSpec.EXACTLY),
    )
    view.layout(0, 0, doc.width, doc.height)
    val bitmap = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))
    val framePng = File(rootDir, "$name-frame.png")
    framePng.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    val semanticsRoot = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
    val semantics = ComposeSemanticsDataProducer.buildPayload(semanticsRoot, density = density)
    val layout =
      LayoutInspectorDataProducer.buildPayload(
        root = semanticsRoot,
        slotTables = slotTables.toList(),
        density = density,
      )

    if (layout == null) {
      return Lane(name, "", 0, 0, 0, 0, 0, 0, 0, countSemanticsTexts(semantics), "layout was null")
    }

    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = rootDir,
      previewId = name,
      layout = layout,
      semantics = semantics,
      density = density,
      frameImage = framePng,
    )
    val svg = File(rootDir, "$name/compose-figma.svg").takeIf { it.isFile }?.readText().orEmpty()

    return Lane(
      name = name,
      svg = svg,
      elements = Regex("<[a-zA-Z]").findAll(svg).count(),
      texts = Regex("<text[ >]").findAll(svg).count(),
      images = Regex("<image[ >]").findAll(svg).count(),
      paths = Regex("<path[ >]").findAll(svg).count(),
      rects = Regex("<rect[ >]").findAll(svg).count(),
      groups = Regex("<g[ >]").findAll(svg).count(),
      layoutNodes = countLayoutNodes(layout),
      semanticsTexts = countSemanticsTexts(semantics),
      note = "density=$density slotTables=${slotTables.size}",
    )
  }

  private fun report(doc: Doc, lane: Lane) {
    val text =
      buildString {
        appendLine("document: ${doc.id}  (${doc.width}x${doc.height})")
        appendLine("lane: ${lane.name}  [${lane.note}]")
        appendLine("layout-inspector nodes: ${lane.layoutNodes}")
        appendLine("semantics text nodes: ${lane.semanticsTexts}")
        appendLine(
          "svg elements=${lane.elements} text=${lane.texts} image=${lane.images} " +
            "path=${lane.paths} rect=${lane.rects} g=${lane.groups} bytes=${lane.svg.length}"
        )
        val (w, h) = lane.canvas()
        appendLine("svg canvas: ${w}x$h  (document is ${doc.width}x${doc.height})")
        appendLine("--- svg head ---")
        appendLine(lane.head())
      }
    System.getProperty(REPORT_PROPERTY)?.let { File("$it.${lane.name}.svg-report").writeText(text) }
    println(text)
  }

  private fun countLayoutNodes(payload: LayoutInspectorPayload?): Int {
    fun walk(node: LayoutInspectorNode): Int = 1 + node.children.sumOf { walk(it) }
    return payload?.let { walk(it.root) } ?: 0
  }

  private fun countSemanticsTexts(payload: ComposeSemanticsPayload?): Int {
    fun walk(node: ComposeSemanticsNode): Int =
      (if (node.text != null || node.layoutText != null) 1 else 0) +
        node.children.sumOf { walk(it) }
    return payload?.let { walk(it.root) } ?: 0
  }

  /**
   * The document to export, preferring a staged catalog and otherwise falling back to the committed
   * fixture — so this **always runs**, including on a plain `check` in CI.
   *
   * The sibling render harnesses legitimately skip without `rc.embedded.input`: they rasterize the
   * whole catalog for the `rc-compare` page, which is inherently a bulk operation over artefacts too
   * large to commit. This test isn't that. It pins one qualitative property of the export, one
   * document is enough to pin it, and a document is 1 KB — so skipping without a staged catalog
   * would mean the regression coverage silently never runs, which is the same as not having it.
   *
   * `rc.embedded.input` still wins when set, so the same assertions can be swept across a whole
   * catalog locally without touching the fixture.
   */
  private fun document(): Doc = stagedDocument() ?: fixtureDocument()

  private fun stagedDocument(): Doc? {
    val dir =
      System.getProperty(INPUT_PROPERTY)?.let(::File)?.takeIf { it.isDirectory } ?: return null
    val manifest = File(dir, "manifest.json").takeIf { it.isFile } ?: return null
    val entries = Json.decodeFromString<List<RcEmbeddedRenderHarness.Entry>>(manifest.readText())
    // Prefer a text-heavy card: text is the discriminator the export claim turns on.
    val pick =
      entries.firstOrNull { it.id.contains("TitleCardRemote") }
        ?: entries.firstOrNull { it.id.contains("Text") }
        ?: entries.firstOrNull()
        ?: return null
    val rc = File(dir, "${pick.id}.rc").takeIf { it.isFile } ?: return null
    return Doc(pick.id, pick.width, pick.height, rc.readBytes())
  }

  /**
   * `TitleCardRemote` as the `design-catalog-remote-m3` sample bakes it — the same document a staged
   * run picks, captured from `design-artifacts/remote-m3` and committed at 1 KB. Its size is in the
   * filename because a `.rc` carries its own layout but not the frame it was captured for, and the
   * export needs the frame to compare the canvas against.
   */
  private fun fixtureDocument(): Doc {
    val bytes =
      checkNotNull(javaClass.getResourceAsStream("/$FIXTURE")) {
          "missing committed fixture $FIXTURE — this test must never silently skip"
        }
        .use { it.readBytes() }
    return Doc(FIXTURE.substringAfterLast('/'), 640, 480, bytes)
  }

  private companion object {
    const val INPUT_PROPERTY = "rc.embedded.input"
    const val REPORT_PROPERTY = "rc.semantics.report"
    const val FRAMES = 4
    const val FIXTURE = "rc-fixtures/TitleCardRemote-640x480.rc"
  }
}

/** Captures the composition's slot tables so the layout tree keeps its composable names. */
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
