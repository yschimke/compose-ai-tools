package ee.schimke.composeai.renderer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Not an assertion test — a deterministic generator for the PR's visual evidence (issue #4253),
 * same shape as [TalkBackFocusOverlayDemoRender].
 *
 * When `A11Y_LABEL_DEMO_DIR` is set it writes the accessibility overlay for the reported hierarchy
 * twice: `before.png` from the nodes exactly as the ATF walk emitted them (the merged stop blank,
 * its copy stranded on the child), and `after.png` from the same nodes put through
 * [AccessibilityLabels.rollUpMergedLabels]. The legend is the difference: `(unlabelled)` becomes
 * the word on the button.
 *
 * `A11Y_LABEL_DEMO_SOURCE` optionally names the screenshot to overlay — the reported render, when
 * the author has it to hand. Without it the generator paints a stand-in of the same shape, so the
 * evidence is reproducible with nothing but the repo. Skipped silently in normal CI (no env var) so
 * it costs nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilityLabelDemoRender {

  @Test
  fun renderBeforeAndAfter() {
    val dir = System.getenv("A11Y_LABEL_DEMO_DIR")
    assumeNotNull(dir)
    val outDir = File(dir).apply { mkdirs() }

    val source =
      System.getenv("A11Y_LABEL_DEMO_SOURCE")?.let { File(it) }?.takeIf { it.isFile }
        ?: File(outDir, "source.png").also { file ->
          paintMockButton().let { bm ->
            file.outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
          }
        }
    val size = BitmapFactory.decodeFile(source.absolutePath)
    val nodes = reportedNodes(size.width, size.height)
    size.recycle()
    // The announcement the walk now produces for that button, from the same tree ATF hands it —
    // not a string typed in here, so the two frames differ by exactly the change under review.
    val announced = AccessibilityLabels.announcement(reportedTree())

    AccessibilityOverlay.generate(
      sourcePng = source,
      findings = emptyList(),
      nodes = nodes,
      destPng = File(outDir, "before.png"),
    )
    AccessibilityOverlay.generate(
      sourcePng = source,
      findings = emptyList(),
      nodes = nodes.mapIndexed { i, node -> if (i == 0) node.copy(label = announced) else node },
      destPng = File(outDir, "after.png"),
    )
  }

  /**
   * The hierarchy issue #4253 reported, verbatim from the live `a11y` payload for
   * `button-filled__ideal__default` — a Wear `Button(icon = …, label = { Text("Filled") })`. Bounds
   * are scaled to whatever screenshot the generator was given so the boxes land on the button.
   */
  private fun reportedNodes(width: Int, height: Int): List<AccessibilityNode> {
    fun scaled(l: Int, t: Int, r: Int, b: Int): String {
      val x = width / 225f
      val y = height / 136f
      return "${(l * x).toInt()},${(t * y).toInt()},${(r * x).toInt()},${(b * y).toInt()}"
    }
    return listOf(
      AccessibilityNode(
        label = "",
        states = listOf("clickable"),
        merged = true,
        boundsInScreen = scaled(16, 16, 209, 120),
      ),
      AccessibilityNode(
        label = "Filled",
        role = "TextView",
        merged = false,
        boundsInScreen = scaled(104, 50, 181, 86),
      ),
    )
  }

  /**
   * The same button as [reportedNodes], as the tree the ATF walk reads: a merging, clickable
   * surface holding a decorative icon and the label. `:renderer-android`'s
   * `WearButtonA11yHierarchyProbeTest` is where this shape is measured against a real render rather
   * than asserted.
   */
  private fun reportedTree(): AccessibilityLabels.Element =
    element(
      merges = true,
      children = listOf(element(), element(own = "Filled")),
    )

  private fun element(
    own: String = "",
    merges: Boolean = false,
    children: List<AccessibilityLabels.Element> = emptyList(),
  ): AccessibilityLabels.Element =
    object : AccessibilityLabels.Element {
      override val ownLabel = own
      override val mergesDescendants = merges
      override val children = children
    }

  /** A stand-in for the reported render: a filled Wear button with a leading icon and a label. */
  private fun paintMockButton(): Bitmap {
    val bm = Bitmap.createBitmap(225, 136, Bitmap.Config.ARGB_8888)
    val c = Canvas(bm).apply { drawColor(Color.BLACK) }
    val container = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xA8, 0xC7, 0xFA) }
    c.drawRoundRect(RectF(16f, 16f, 209f, 120f), 52f, 52f, container)
    val onContainer = Color.rgb(0x06, 0x2E, 0x6F)
    c.drawCircle(72f, 68f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onContainer })
    c.drawText(
      "Filled",
      104f,
      78f,
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = onContainer
        textSize = 28f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      },
    )
    return bm
  }
}
