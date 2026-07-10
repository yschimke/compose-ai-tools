@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
}
