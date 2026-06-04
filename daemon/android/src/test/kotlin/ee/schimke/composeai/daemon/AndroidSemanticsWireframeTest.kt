package ee.schimke.composeai.daemon

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the real `android.graphics` baker with a hand-built payload — no Compose render needed,
 * so it stays fast and graphics-mode-agnostic (asserts the PNG is written and non-empty rather than
 * decoding pixels, which legacy Robolectric graphics wouldn't paint).
 *
 * Pinned to `sdk = 35` like the other `:daemon:android` self-tests: Robolectric SDK 36 needs a
 * JDK 21 test JVM, and the repo toolchain is JDK 17.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidSemanticsWireframeTest {

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
  fun `bakes a non-empty png and creates parent dirs`() {
    val dir = Files.createTempDirectory("android-wireframe-test").toFile()
    try {
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
      val dest = File(dir, "nested/out/wireframe.png")
      val written = AndroidSemanticsWireframe.generate(payload, dest, padding = 16)

      assertNotNull("baker should return the written file", written)
      assertTrue("png must exist", dest.exists())
      assertTrue("png must be non-empty", dest.length() > 0)
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `empty tree still bakes a minimal png`() {
    val dir = Files.createTempDirectory("android-wireframe-empty").toFile()
    try {
      val payload = ComposeSemanticsPayload(root = node("root", "garbage"))
      val dest = File(dir, "empty.png")
      val written = AndroidSemanticsWireframe.generate(payload, dest, padding = 16)
      assertNotNull(written)
      assertTrue(dest.exists())
    } finally {
      dir.deleteRecursively()
    }
  }
}
