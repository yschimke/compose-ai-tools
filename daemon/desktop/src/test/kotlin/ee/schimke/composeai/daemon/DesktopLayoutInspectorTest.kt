@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorBounds
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorNode
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorPayload
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the desktop `layout/inspector` producer (issue #1903). Before this the desktop backend
 * registered [LayoutInspectorDataProductRegistry] but never wrote `layout-inspector.json`, so the
 * kind degraded to `NotAvailable` on `data/fetch` — `layout/inspector` was Android-only. This
 * drives the CMP-portable [LayoutInspectorDataProducer.writeArtifacts] overload against a real
 * [ImageComposeScene] and asserts the produced tree carries the structural facts (a non-empty
 * subtree with bounds + size) and the resolved design `tokens` the shared [ModifierTokenResolver]
 * now mirrors across both products. Companion to [DesktopSemanticsTokensTest].
 */
class DesktopLayoutInspectorTest {

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("desktop-layout-inspector").toFile()
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
  }

  private fun writeAndRead(
    previewId: String = "preview",
    density: Float = 1.0f,
    content: @Composable () -> Unit,
  ): LayoutInspectorNode {
    val scene =
      ImageComposeScene(width = 400, height = 400, density = Density(density), content = content)
    try {
      scene.render()
      val root: SemanticsNode = scene.semanticsOwners.first().unmergedRootSemanticsNode
      LayoutInspectorDataProducer.writeArtifacts(
        rootDir = rootDir,
        previewId = previewId,
        root = root,
        density = density,
      )
    } finally {
      scene.close()
    }
    val file = rootDir.resolve(previewId).resolve(LayoutInspectorDataProducer.FILE)
    assertTrue("expected $file to be written", file.exists())
    return Json.decodeFromString(LayoutInspectorPayload.serializer(), file.readText()).root
  }

  private fun LayoutInspectorNode.firstWhere(
    predicate: (LayoutInspectorNode) -> Boolean
  ): LayoutInspectorNode? {
    if (predicate(this)) return this
    return children.firstNotNullOfOrNull { it.firstWhere(predicate) }
  }

  @Test
  fun writes_a_non_empty_layout_tree_on_desktop() {
    val root = writeAndRead {
      Box(Modifier.testTag("card").size(120.dp, 60.dp).background(Color.White)) { Text("hello") }
    }

    // The root is produced and carries a real measured size + a populated subtree — proving the
    // reflection walk reaches `LayoutNode` children after `scene.render()` Z-sorts the tree.
    assertTrue("root must have a measured width", root.size.width > 0)
    assertTrue("root must have a non-empty subtree", root.children.isNotEmpty())
  }

  @Test
  fun captures_effective_graphics_layer_alpha() {
    val root = writeAndRead {
      Box(
        Modifier.testTag("layer")
          .size(80.dp, 40.dp)
          .graphicsLayer {
            alpha = 0.25f
            scaleX = 0.5f
            scaleY = 0.75f
            translationX = 7f
            translationY = 9f
          }
          .background(Color.Red)
      )
    }

    val layer = root.firstWhere { it.tokens?.backgroundColor == "#FFFF0000" }
    assertNotNull("the transformed layer must be captured", layer)
    assertEquals("effective graphicsLayer alpha", 0.25, layer!!.tokens!!.opacity!!, 0.001)
    val graphicsLayer =
      layer.modifiers.firstOrNull {
        it.name == "graphicsLayer" || it.name.contains("GraphicsLayer")
      }
    assertEquals("ordered graphicsLayer alpha", "0.25", graphicsLayer?.properties?.get("alpha"))
    // Translation and scale are already applied by LayoutCoordinates.boundsIn(root), so the
    // exporter must not apply them a second time.
    assertEquals(LayoutInspectorBounds(left = 27, top = 14, right = 67, bottom = 44), layer.bounds)
  }

  @Test
  fun captures_an_imagevector_icon_as_editable_vector_paths() {
    // Tier 1 capture: an `Icon` backed by an `ImageVector` paints through a `VectorPainter`; the
    // inspector must reflect its path tree into `vectorGraphic` (in the vector's 24×24 viewport) so
    // the figma-svg export can emit `<path>`s instead of a raster crop. Exercises the reflection
    // against the real androidx VectorPainter/VectorComponent/PathComponent/PathNode classes.
    val star =
      ImageVector.Builder(
          defaultWidth = 24.dp,
          defaultHeight = 24.dp,
          viewportWidth = 24f,
          viewportHeight = 24f,
        )
        .apply {
          path(fill = SolidColor(Color(0xFF112233))) {
            moveTo(12f, 0f)
            lineTo(15f, 9f)
            lineTo(24f, 9f)
            lineTo(17f, 14f)
            lineTo(20f, 24f)
            lineTo(12f, 18f)
            lineTo(4f, 24f)
            lineTo(7f, 14f)
            lineTo(0f, 9f)
            lineTo(9f, 9f)
            close()
          }
        }
        .build()

    val root = writeAndRead { Icon(star, "star", Modifier.size(48.dp)) }

    val node = root.firstWhere { it.vectorGraphic != null }
    assertNotNull("an ImageVector-backed Icon must carry a captured vectorGraphic", node)
    val graphic = node!!.vectorGraphic!!
    assertEquals(24f, graphic.viewportWidth, 0.01f)
    assertEquals(24f, graphic.viewportHeight, 0.01f)
    assertEquals("one path captured", 1, graphic.paths.size)
    val path = graphic.paths.first()
    assertTrue("path starts at the star tip", path.pathData.startsWith("M12 0"))
    assertTrue("path is closed", path.pathData.trimEnd().endsWith("Z"))
    assertEquals("solid fill resolved", "#FF112233", path.fillArgb)
  }

  @Test
  fun resolves_container_tokens_onto_layout_inspector_nodes() {
    val root = writeAndRead {
      Box(
        Modifier.testTag("card")
          .background(Color(0xFF006A60), RoundedCornerShape(12.dp))
          .padding(16.dp)
      ) {
        Text("Card body")
      }
    }

    val card = root.firstWhere { it.tokens?.backgroundColor == "#FF006A60" }
    assertNotNull("a node must carry the resolved background token", card)
    val tokens = card!!.tokens!!
    assertEquals("12.0dp", tokens.cornerRadius)
    assertEquals("16.0dp", tokens.padding?.start)
    assertEquals("16.0dp", tokens.padding?.bottom)
  }

  @Test
  fun resolves_a_solid_painter_fill_as_the_background_token() {
    // Wear M3's `Button`/`Card`/`FilledTonalButton` fill their container via `Modifier.paint` with
    // a `ColorPainter(containerColor)` (through the wear `surface()` helper), NOT via
    // `Modifier.background` — so the resolver must read a solid `ColorPainter` painter fill as the
    // background, otherwise every wear container fill drops out of the figma-svg export (#1985).
    val root = writeAndRead {
      Box(Modifier.testTag("surface").size(120.dp, 52.dp).paint(ColorPainter(Color(0xFFE9DDFF)))) {
        Text("Filled")
      }
    }

    val filled = root.firstWhere { it.tokens?.backgroundColor == "#FFE9DDFF" }
    assertNotNull("a `Modifier.paint(ColorPainter)` fill must resolve as the background", filled)
  }

  @Test
  fun leaves_a_bitmap_painter_fill_unresolved_for_the_raster_path() {
    // A non-solid painter (an `Image`/`Icon`'s bitmap or vector art) has no single fill colour, so
    // the painter branch must NOT invent a flat background for it — those nodes stay on the raster
    // path. Only a `ColorPainter` yields a token.
    val root = writeAndRead {
      Box(Modifier.testTag("art").size(40.dp, 40.dp).paint(BitmapPainter(ImageBitmap(4, 4))))
    }

    val anyBackground = root.firstWhere { it.tokens?.backgroundColor != null }
    assertTrue("a bitmap painter must not resolve to a background token", anyBackground == null)
  }

  @Test
  fun folds_paint_alpha_into_the_resolved_background_token() {
    // `Modifier.paint(painter, alpha = …)` scales the painter's opacity at draw time, so a
    // half-alpha fill must export as a half-alpha colour rather than an opaque rectangle.
    val root = writeAndRead {
      Box(
        Modifier.testTag("faded")
          .size(40.dp, 40.dp)
          .paint(ColorPainter(Color(0xFFFF0000)), alpha = 0.5f)
      )
    }

    // 0xFF * 0.5 = 127.5 → 0x80 alpha over the opaque red.
    val faded = root.firstWhere { it.tokens?.backgroundColor == "#80FF0000" }
    assertNotNull("Modifier.paint alpha must fold into the resolved background alpha", faded)
  }

  @Test
  fun carries_brush_background_identity_when_inspector_properties_are_absent() {
    val root = writeAndRead {
      Box(
        Modifier.testTag("gradient")
          .size(120.dp, 40.dp)
          .background(Brush.horizontalGradient(listOf(Color.Red, Color.Blue)))
      )
    }

    val gradient = root.firstWhere { node ->
      node.modifiers.any {
        it.name == "BackgroundElement" && it.properties["brush"]?.contains("Gradient") == true
      }
    }
    assertNotNull("a brush background must remain identifiable in layout-inspector data", gradient)
  }

  @Test
  fun skips_a_color_filtered_paint_fill() {
    // A `colorFilter` re-tints the painter at draw time; a flat `#AARRGGBB` token can't reproduce
    // that, so a filtered paint stays unresolved rather than exporting the wrong, unfiltered
    // colour.
    val root = writeAndRead {
      Box(
        Modifier.testTag("tinted")
          .size(40.dp, 40.dp)
          .paint(ColorPainter(Color(0xFFFF0000)), colorFilter = ColorFilter.tint(Color(0xFF00FF00)))
      )
    }

    val anyBackground = root.firstWhere { it.tokens?.backgroundColor != null }
    assertTrue(
      "a colour-filtered paint must not resolve to a flat background",
      anyBackground == null,
    )
  }

  @Test
  fun a_touch_target_inflated_fill_draws_at_its_visual_bounds_not_its_measured_size() {
    // A real M3 `Button` fills via a `BackgroundElement` on a node that also carries
    // `Modifier.minimumInteractiveComponentSize()`: the modifier inflates the measured `size` up to
    // the 48dp touch target (96px @ density 2) while the background still paints at the 40dp visual
    // pill (80px). The figma-svg fill-growth heuristic grows a fill from its
    // (Android-under-reported)
    // `bounds` toward its measured `size`; it MUST NOT do so here, or the coloured pill balloons
    // into
    // its invisible touch margin. This pins the desktop compose-m3 catalog against that regression.
    val root =
      writeAndRead(density = 2f) {
        androidx.compose.material3.Button(onClick = {}) { Text("Filled") }
      }

    val fill = root.firstWhere { n ->
      n.tokens?.backgroundColor != null &&
        n.modifiers.any { it.name.equals("minimumInteractiveComponentSize", ignoreCase = true) }
    }
    assertNotNull("the touch-target-inflated M3 button fill node must be present", fill)
    // The measured size is inflated past the visual bounds — the exact condition the heuristic keys
    // off — so this is a genuine test of the suppression, not a case where size already equals
    // bounds.
    assertTrue(
      "precondition: the fill node's measured size must exceed its visual bounds",
      fill!!.size.height > (fill.bounds.bottom - fill.bounds.top),
    )

    val model =
      ee.schimke.composeai.data.layoutinspector.FigmaSvgModel.from(
        layout = LayoutInspectorPayload(root),
        density = 2f,
      )
    val fillLayer = model.root.firstLayerWhere { it.fill != null }
    assertNotNull("the fill must survive to a figma-svg layer", fillLayer)
    assertEquals(
      "touch-inflated fill height must stay at the visual bounds, not grow to the measured size",
      fill.bounds.bottom - fill.bounds.top,
      fillLayer!!.bottom - fillLayer.top,
    )
    assertEquals(
      "touch-inflated fill top must stay at the visual bounds",
      fill.bounds.top,
      fillLayer.top,
    )
  }

  @Test
  fun a_standard_background_fill_stays_vector_and_never_hits_the_paint_raster_fallback() {
    // The paint-raster fallback (an unvectorizable `Modifier.paint` painter → frame `<image>`) must
    // fire ONLY for wear's `Modifier.paint(painter)` surfaces. A desktop compose-m3 component fills
    // via `Modifier.background` / `BackgroundElement`, which resolves to a flat colour, so even in
    // hybrid mode (`captureCanvasDraws = true`) it must stay a vector fill and produce NO raster
    // target — this pins the compose-m3 catalog against the fallback ever cropping a button/card.
    val root =
      writeAndRead(density = 2f) {
        androidx.compose.material3.Button(onClick = {}) { Text("Filled") }
      }
    val model =
      ee.schimke.composeai.data.layoutinspector.FigmaSvgModel.from(
        layout = LayoutInspectorPayload(root),
        density = 2f,
        captureCanvasDraws = true,
      )
    assertTrue(
      "a standard background-filled button must not raster (rasterTargets must be empty)",
      model.rasterTargets.isEmpty(),
    )
    assertNotNull(
      "the button fill must survive as a vector layer",
      model.root.firstLayerWhere { it.fill != null },
    )
  }

  private fun ee.schimke.composeai.data.layoutinspector.FigmaSvgLayer.firstLayerWhere(
    predicate: (ee.schimke.composeai.data.layoutinspector.FigmaSvgLayer) -> Boolean
  ): ee.schimke.composeai.data.layoutinspector.FigmaSvgLayer? {
    if (predicate(this)) return this
    return children.firstNotNullOfOrNull { it.firstLayerWhere(predicate) }
  }
}
