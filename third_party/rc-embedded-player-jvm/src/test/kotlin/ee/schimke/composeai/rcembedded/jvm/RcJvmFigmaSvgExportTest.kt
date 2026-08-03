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

package ee.schimke.composeai.rcembedded.jvm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.daemon.ComposeFigmaSvgDataProducer
import ee.schimke.composeai.daemon.ComposeSemanticsDataProducer
import ee.schimke.composeai.daemon.LayoutInspectorDataProducer
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorNode
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorPayload
import java.io.File
import java.nio.file.Files
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Runs the **production `compose/figma-svg` export over the jvm (cmp-jvm) Remote Compose player** —
 * the desktop/skiko counterpart of `RcFigmaSvgExportTest`'s embedded (Android/Robolectric) lane,
 * over the *same* committed `TitleCardRemote-640x480.rc` fixture, at the same xhdpi density.
 *
 * ## Why this exists
 *
 * The vector export's second pass rests on a claim about the *player*, not the platform: the
 * embedded player interprets a Remote Compose document into real Compose layout/draw nodes, so the
 * layout-inspector walk sees interior structure and the export emits real vector content — where
 * the View player is one opaque `AndroidView` that can only crop out as a flat `<image>`.
 * [RcJvmRenderer] reaches that same shared dispatch (`RcPlayerRootLayoutComponent` /
 * `RcPlayerRawDocument`) off Android, and the whole export pipeline it feeds —
 * [LayoutInspectorDataProducer], [ComposeSemanticsDataProducer], [ComposeFigmaSvgDataProducer] — is
 * a CMP/JVM module the desktop `RenderEngine` already calls. So the claim ought to hold on the JVM
 * too, and this test is what says whether it actually does.
 *
 * The structural difference from the Android lane: this composes into an [ImageComposeScene] and
 * reads the semantics root off `scene.semanticsOwners` — exactly what the desktop `RenderEngine`
 * does — instead of going through a `ComposeTestRule` + `ComponentActivity`, which desktop has no
 * equivalent of. The sized `Box` the Android lane wraps the player in is reproduced here so the two
 * trees stay countable against each other; text, however, is measured by skiko against the *host's*
 * faces rather than by Robolectric against the Android SDK's, and that does move geometry.
 *
 * **What a run against the committed fixture shows** (kept here because the numbers are the point
 * of the lane, not merely a confirmation of it). The jvm export *is* the Android embedded export to
 * within text metrics: same 10 layout-inspector nodes and 2 semantics text nodes, same 10 elements
 * (2 `<text>`, 1 `<image>`, 6 `<g>`, no `<path>`/`<rect>`), same layer order. What differs is
 * downstream of text measurement — canvas 672x204 vs 672x206, drawn-content crop 640x172 at y=154
 * vs 640x174 at y=153, baselines within a pixel — plus one difference that is not about metrics at
 * all: the Android capture writes `FontFamily.Default` (the sentinel's `toString()`) into the text
 * node's family, so its SVG carries `font-family="FontFamily.Default, sans-serif"`, where the jvm
 * text seam resolves a real `GenericFontFamily` and the export emits the plain `sans-serif`. See
 * `PROVENANCE.md` § "the `compose/figma-svg` export runs over the jvm player".
 *
 * ## What is asserted, and what deliberately is not
 *
 * Asserted, qualitatively, exactly as the Android lane asserts its half: the SVG carries `<text>`
 * (the player's interior structure reached the export), it is not merely raster crops, it carries
 * drawn content (issue #2937's fills and shapes), the chrome sits *beneath* the still-editable text
 * rather than replacing it, and the canvas covers the document rather than shrink-wrapping to the
 * text runs.
 *
 * Not asserted: exact element counts, or a match against the Android lane's counts. Those are a
 * property of the staged document *and* of the host's fonts (see [RcJvmRendererTest] and
 * `DesktopTextPlatformTest` on why this module never pins text pixels), and would be brittle. The
 * lane prints a full report — counts, canvas size, and a head of the SVG — so the real numbers can
 * be read off a run and compared with the Android lane's report by eye. Set `rc.jvm.svg.report` to
 * also write that report and the whole export directory to disk.
 */
class RcJvmFigmaSvgExportTest {

  /** Same loud-skip contract as [RcJvmRendererTest]: no skiko natives, no scene, no export. */
  @Before
  fun requireSkikoNatives() {
    if (!SKIKO_LOADED) {
      System.err.println(
        "RcJvmFigmaSvgExportTest skipped entirely: skiko's native library did not load, so the " +
          "jvm figma-svg export was never exercised. Cause: $skikoLoadFailure"
      )
    }
    Assume.assumeTrue("skiko natives unavailable: $skikoLoadFailure", SKIKO_LOADED)
  }

  @Test
  fun jvmPlayerExportsVectorContent() {
    val doc = fixtureDocument()
    val lane = export("cmp-jvm", doc)
    report(doc, lane)

    assert(lane.svg.isNotEmpty()) { "the cmp-jvm lane wrote no compose-figma.svg at all" }
    assert(lane.texts > 0) {
      "the cmp-jvm lane's SVG carries no <text> — the export got nothing vector out of the jvm " +
        "player:\n${lane.head()}"
    }
    assert(lane.elements > lane.images + 1) {
      "the cmp-jvm lane's SVG is essentially just raster crops (${lane.images} <image> of " +
        "${lane.elements} elements):\n${lane.head()}"
    }
    // Issue #2937, on the jvm side: the document's *drawn* content — the card's fill and shape —
    // has to reach the SVG, or the export is a fragment that loses everything that isn't a string.
    assert(lane.paths + lane.rects + lane.images > 0) {
      "the cmp-jvm lane exported no drawn content at all (path=${lane.paths} rect=${lane.rects} " +
        "image=${lane.images}) — the document's fills and shapes are missing:\n${lane.head()}"
    }
    // …and it must not have bought that back by collapsing to a raster: an isolated capture sits
    // *under* the still-editable text, where a frame crop of the same node would have replaced it.
    assert(lane.svg.indexOf("<image") < lane.svg.indexOf("<text")) {
      "the drawn chrome must be exported beneath the editable text, not instead of it:\n" +
        lane.head()
    }
    val (w, h) = lane.canvas()
    assert(w >= doc.width) {
      "the exported canvas is ${w}x$h — narrower than the ${doc.width}px document, so the drawn " +
        "card is clipped out of its own SVG:\n${lane.head()}"
    }
  }

  /** One staged document. */
  private data class Doc(val id: String, val width: Int, val height: Int, val bytes: ByteArray)

  /** What the lane's `compose-figma.svg` came out as. */
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
   * Plays [doc] through the jvm player in an [ImageComposeScene] sized to the document, renders one
   * frame (which both produces the hybrid export's frame PNG and z-sorts the layout children the
   * inspector walk reflects over), then runs the production capture + hybrid figma-svg export and
   * reads the SVG back.
   *
   * The semantics root comes off `scene.semanticsOwners`, the same handle the desktop
   * `RenderEngine` uses after `scene.render()`; there is no `RootForTest` on desktop and none is
   * needed.
   */
  @OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
  )
  private fun export(name: String, doc: Doc): Lane {
    val rootDir = Files.createTempDirectory("rc-jvm-figma-svg-$name").toFile()
    val slotTables = mutableSetOf<CompositionData>()

    val scene =
      ImageComposeScene(width = doc.width, height = doc.height, density = Density(DENSITY)) {
        InspectableContent(slotTables) {
          // The same sized `Box` wrapper the Android lane composes the player into. It changes
          // nothing about the render (the scene is already exactly the document's size), but it
          // keeps the two lanes' layout-inspector trees countable against each other — without it
          // this lane reports one node fewer purely because its harness has one wrapper fewer.
          Box(
            Modifier.size(
              with(LocalDensity.current) { doc.width.toDp() },
              with(LocalDensity.current) { doc.height.toDp() },
            )
          ) {
            val document = remember(doc.bytes) { parseDocument(doc.bytes) }
            RcPlayerJvm(document, Modifier.fillMaxSize())
          }
        }
      }

    try {
      val framePng = File(rootDir, "$name-frame.png")
      val image = scene.render()
      val encoded =
        image.encodeToData(EncodedImageFormat.PNG)
          ?: error("skiko could not encode the rendered frame to PNG")
      framePng.writeBytes(encoded.bytes)

      val semanticsRoot =
        scene.semanticsOwners.firstOrNull()?.unmergedRootSemanticsNode
          ?: return Lane(name, "", 0, 0, 0, 0, 0, 0, 0, 0, "no semantics owner")

      val semantics = ComposeSemanticsDataProducer.buildPayload(semanticsRoot, density = DENSITY)
      val layout =
        LayoutInspectorDataProducer.buildPayload(
          root = semanticsRoot,
          slotTables = slotTables.toList(),
          density = DENSITY,
        )
      if (layout == null) {
        return Lane(
          name,
          "",
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          countSemanticsTexts(semantics),
          "layout was null",
        )
      }

      ComposeFigmaSvgDataProducer.writeSvg(
        rootDir = rootDir,
        previewId = name,
        layout = layout,
        semantics = semantics,
        density = DENSITY,
        frameImage = framePng,
      )
      val svg = File(rootDir, "$name/compose-figma.svg").takeIf { it.isFile }?.readText().orEmpty()
      // Keep the whole export (SVG + its `figma-raster/` sidecars) beside the report when one is
      // asked for: counts say *that* the lane changed, the SVG says what it now looks like.
      System.getProperty(REPORT_PROPERTY)?.let { report ->
        File(rootDir, name).copyRecursively(File("$report.$name.export"), overwrite = true)
      }

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
        note = "density=$DENSITY slotTables=${slotTables.size}",
      )
    } finally {
      scene.close()
    }
  }

  private fun report(doc: Doc, lane: Lane) {
    val text = buildString {
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
   * `TitleCardRemote` as `design-catalog-remote-m3` bakes it — the committed 1 KB fixture the
   * Android lanes render, shared from the Android module's test resources so the two lanes' reports
   * are about the same document.
   */
  private fun fixtureDocument(): Doc {
    val bytes =
      checkNotNull(javaClass.getResourceAsStream("/$FIXTURE")) {
          "missing committed fixture $FIXTURE — is the Android module's test-resources srcDir " +
            "shared?"
        }
        .use { it.readBytes() }
    return Doc(FIXTURE.substringAfterLast('/'), 640, 480, bytes)
  }

  private companion object {
    const val FIXTURE = "rc-fixtures/TitleCardRemote-640x480.rc"
    const val REPORT_PROPERTY = "rc.jvm.svg.report"

    /**
     * xhdpi, matching the Android embedded lane: the catalogs capture at dpi 320 and the documents
     * bake dp->px at that factor, so rendering at another density re-lays-out the document and the
     * two lanes' reports would stop being comparable.
     */
    const val DENSITY = 2f

    var skikoLoadFailure: String? = null

    /** Whether Skia is callable at all — decided once by touching a class that loads the native. */
    val SKIKO_LOADED: Boolean =
      try {
        org.jetbrains.skia.FontMgr.default.familiesCount
        true
      } catch (t: Throwable) {
        skikoLoadFailure = "${t::class.java.simpleName}: ${t.message}"
        false
      }
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
