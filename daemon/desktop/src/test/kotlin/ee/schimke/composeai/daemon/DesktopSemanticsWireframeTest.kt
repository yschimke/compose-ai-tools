package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopSemanticsWireframeTest {

  @get:Rule val tmp = TemporaryFolder()

  private fun node(
    id: String,
    bounds: String,
    label: String? = null,
    clickable: Boolean = false,
    mergeMode: String? = null,
    children: List<ComposeSemanticsNode> = emptyList(),
  ) =
    ComposeSemanticsNode(
      nodeId = id,
      boundsInRoot = bounds,
      label = label,
      clickable = clickable,
      mergeMode = mergeMode,
      children = children,
    )

  @Test
  fun bakesPngAtTwiceTheModelExtent() {
    val payload =
      ComposeSemanticsPayload(
        root =
          node(
            "root",
            "0,0,360,640",
            children =
              listOf(
                node("btn", "16,24,344,84", label = "Save", clickable = true),
                node("lyrics", "16,100,344,300", mergeMode = "clearAndSet", label = "Lyrics"),
              ),
          )
      )
    val dest = File(tmp.root, "out/wireframe.png")
    val written = DesktopSemanticsWireframe.generate(payload, dest, padding = 16)

    assertNotNull(written)
    assertTrue(dest.exists())
    val img = ImageIO.read(dest)
    assertNotNull(img)
    // Model extent = 360+32 × 640+32 = 392 × 672, baked at 2×.
    assertEquals(392 * 2, img.width)
    assertEquals(672 * 2, img.height)
  }

  @Test
  fun emptyTreeStillBakesAMinimalPng() {
    val payload = ComposeSemanticsPayload(root = node("root", "garbage"))
    val dest = File(tmp.root, "empty.png")
    val written = DesktopSemanticsWireframe.generate(payload, dest, padding = 16)
    assertNotNull(written)
    val img = ImageIO.read(dest)
    assertEquals(32 * 2, img.width)
    assertEquals(32 * 2, img.height)
  }

  @Test
  fun parentDirsAreCreated() {
    val payload = ComposeSemanticsPayload(root = node("root", "0,0,100,100"))
    val dest = File(tmp.root, "deeply/nested/dir/wireframe.png")
    assertNull(dest.parentFile.takeIf { it.exists() })
    DesktopSemanticsWireframe.generate(payload, dest)
    assertTrue(dest.exists())
  }
}
