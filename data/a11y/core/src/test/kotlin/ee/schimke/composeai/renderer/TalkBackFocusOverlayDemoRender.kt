package ee.schimke.composeai.renderer

import android.graphics.Bitmap
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
 * Not an assertion test — a deterministic generator for the PR's visual evidence (issue #1956).
 * When `TALKBACK_DEMO_DIR` is set it paints a mock settings screen and writes the TalkBack focus
 * overlay for each focus stop there, so the author can attach before/after frames. Skipped silently
 * in normal CI (no env var) so it costs nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TalkBackFocusOverlayDemoRender {

  @Test
  fun renderDemoFrames() {
    val dir = System.getenv("TALKBACK_DEMO_DIR")
    assumeNotNull(dir)
    val outDir = File(dir).apply { mkdirs() }

    val nodes = mockScreenNodes()
    val screen = paintMockScreen()
    val sourcePng = File(outDir, "00-source.png")
    sourcePng.outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }

    val stops = TalkBackTraversal.focusStops(nodes)
    stops.indices.forEach { i ->
      TalkBackFocusOverlay.generate(
        sourcePng = sourcePng,
        nodes = nodes,
        focusedStop = i,
        destPng = File(outDir, "stop-${i + 1}-${slug(stops[i].label)}.png"),
      )
    }
  }

  private fun slug(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

  private fun mockScreenNodes(): List<AccessibilityNode> =
    listOf(
      AccessibilityNode(label = "Settings", role = "Heading", merged = true, boundsInScreen = "40,60,440,120"),
      AccessibilityNode(
        label = "Wi-Fi",
        role = "Switch",
        states = listOf("checked", "clickable"),
        merged = true,
        boundsInScreen = "40,160,440,240",
      ),
      AccessibilityNode(
        label = "Notifications",
        role = "Switch",
        states = listOf("unchecked", "clickable"),
        merged = true,
        boundsInScreen = "40,260,440,340",
      ),
      AccessibilityNode(
        label = "Brightness",
        role = "SeekBar",
        states = listOf("70%"),
        merged = true,
        boundsInScreen = "40,360,440,440",
      ),
      AccessibilityNode(
        label = "Sign out",
        role = "Button",
        states = listOf("clickable"),
        merged = true,
        boundsInScreen = "40,500,440,580",
      ),
    )

  private fun paintMockScreen(): Bitmap {
    val w = 480
    val h = 800
    val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bm).apply { drawColor(Color.rgb(0xF5, 0xF5, 0xF7)) }
    val title =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x20, 0x20, 0x24)
        textSize = 40f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      }
    c.drawText("Settings", 40f, 108f, title)
    fun row(top: Float, label: String, trailing: String, trailingColor: Int) {
      val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
      c.drawRoundRect(RectF(40f, top, 440f, top + 80f), 16f, 16f, card)
      val text =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.rgb(0x20, 0x20, 0x24)
          textSize = 30f
        }
      c.drawText(label, 64f, top + 50f, text)
      val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = trailingColor; textSize = 26f }
      c.drawText(trailing, 320f, top + 50f, tp)
    }
    row(160f, "Wi-Fi", "On", Color.rgb(0x2E, 0x7D, 0x32))
    row(260f, "Notifications", "Off", Color.rgb(0x9E, 0x9E, 0x9E))
    row(360f, "Brightness", "70%", Color.rgb(0x55, 0x55, 0x55))
    val btn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x16, 0x6F, 0xE0) }
    c.drawRoundRect(RectF(40f, 500f, 440f, 580f), 16f, 16f, btn)
    val btnText =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      }
    c.drawText("Sign out", 240f, 548f, btnText)
    return bm
  }
}
