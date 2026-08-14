package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Framing coverage for previews that compose into their own window (issue #3048).
 *
 * The standalone renderer's capture spans the whole screen, with the dialog's window composited
 * into it wherever its gravity puts it — so without [DialogWindowCapture] the sticker is the
 * activity frame with the component floating inside, which is what these previews published as.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DialogWindowCaptureTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("dialog-window-capture").toFile()
    System.setProperty("roborazzi.test.record", "true")
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
    System.clearProperty("roborazzi.test.record")
  }

  @Test
  fun `a centred dialog is cropped to the dialog, not the activity frame`() {
    rule.setContent {
      Dialog(onDismissRequest = {}) { Box(modifier = Modifier.size(64.dp).background(FILL)) }
    }
    rule.mainClock.advanceTimeBy(SETTLE_MS)

    val png = capture("dialog.png")
    val uncropped = ImageIO.read(png)
    assertTrue(
      "precondition: the raw capture spans more than the dialog",
      uncropped.width > 64 || uncropped.height > 64,
    )

    val root = resolveRoot()
    val window = DialogWindowCapture.shownDialogWindow(root)
    assertNotNull("the dialog's window must be resolvable from its root", window)
    DialogWindowCapture.cropPngToDialogWindow(png, root, window!!)

    val cropped = ImageIO.read(png)
    assertEquals("cropped width is the dialog's", 64, cropped.width)
    assertEquals("cropped height is the dialog's", 64, cropped.height)
    assertTrue("the crop must land on the dialog's fill, not the backdrop", png.isEntirely(FILL))
  }

  @OptIn(ExperimentalMaterial3Api::class)
  @Test
  fun `a full-screen bottom sheet window keeps the whole frame`() {
    rule.setContent {
      ModalBottomSheet(
        onDismissRequest = {},
        sheetState = rememberStandardBottomSheetState(),
      ) {
        Box(modifier = Modifier.size(64.dp).background(FILL))
      }
    }
    rule.mainClock.advanceTimeBy(SETTLE_MS)

    val png = capture("sheet.png")
    val before = ImageIO.read(png).let { it.width to it.height }

    val root = resolveRoot()
    val window = DialogWindowCapture.shownDialogWindow(root)
    assertNotNull("a ModalBottomSheet also composes into a dialog window", window)
    DialogWindowCapture.cropPngToDialogWindow(png, root, window!!)

    // The sheet's window fills the screen, so the crop is a no-op — the frame is the sticker.
    val after = ImageIO.read(png).let { it.width to it.height }
    assertEquals("a full-screen window must not be cropped", before, after)
  }

  /**
   * The renderer resolves the capture root *per capture*, after advancing the clock to that
   * capture's target time — not once up front. A dialog opened from a `LaunchedEffect` does not
   * exist at virtual time 0, so a selection cached before the advance would miss it entirely.
   */
  @Test
  fun `a dialog opened from a LaunchedEffect is still resolved after the clock advances`() {
    rule.setContent {
      var shown by remember { mutableStateOf(false) }
      LaunchedEffect(Unit) {
        delay(500L)
        shown = true
      }
      if (shown) {
        Dialog(onDismissRequest = {}) { Box(modifier = Modifier.size(64.dp).background(FILL)) }
      }
    }

    // At the usual settle point the dialog has not opened yet: one root, no dialog window.
    rule.mainClock.advanceTimeBy(SETTLE_MS)
    assertNull(
      "precondition: no dialog window before the effect fires",
      DialogWindowCapture.shownDialogWindow(resolveRoot()),
    )

    // Past the delay it is open, and a freshly-resolved root finds it.
    rule.mainClock.advanceTimeBy(1_000L)
    val root = resolveRoot()
    assertNotNull(
      "the dialog must be resolvable once the effect has fired",
      DialogWindowCapture.shownDialogWindow(root),
    )
    assertEquals("the resolved root is the dialog's", 64, root.size.width)
  }

  @Test
  fun `stable dialog crop reuses the first dialog rect across frames`() {
    rule.setContent {
      var large by remember { mutableStateOf(false) }
      LaunchedEffect(Unit) {
        delay(500L)
        large = true
      }
      Dialog(onDismissRequest = {}) {
        Box(modifier = Modifier.size(if (large) 96.dp else 64.dp).background(FILL))
      }
    }

    val stableCrop = DialogWindowCapture.StableDialogCrop()
    rule.mainClock.advanceTimeBy(SETTLE_MS)
    val first = File(rootDir, "stable-first.png")
    stableCrop.captureFrame(rule, first, RoborazziOptions())
    assertEquals("first cropped frame width", 64, ImageIO.read(first).width)

    rule.mainClock.advanceTimeBy(1_000L)
    val rootAfterResize = resolveRoot()
    assertEquals(
      "precondition: the dialog root was freshly resolved after resize",
      96,
      rootAfterResize.size.width,
    )

    val second = File(rootDir, "stable-second.png")
    stableCrop.captureFrame(rule, second, RoborazziOptions())
    assertEquals("second frame keeps the first crop width", 64, ImageIO.read(second).width)
  }

  @Test
  fun `a popup is not a dialog window and is left alone`() {
    rule.setContent {
      Box(modifier = Modifier.fillMaxSize().background(Color(0xFFEF5350)))
      Popup { Box(modifier = Modifier.size(24.dp).background(FILL)) }
    }
    rule.mainClock.advanceTimeBy(SETTLE_MS)

    // A Popup installs its own owner through the window manager but never a `Dialog`, so it must
    // not trigger the dialog crop — otherwise a popup over a real surface would frame the popup.
    assertNull(
      "a popup must not resolve to a dialog window",
      DialogWindowCapture.shownDialogWindow(resolveRoot()),
    )
  }

  /** Mirrors what the renderer does: resolve the subject root rather than trusting `onRoot()`. */
  private fun resolveRoot() = DialogWindowCapture.resolveCaptureRoot(rule).semanticsRoot!!

  private fun capture(name: String): File {
    return File(rootDir, name).also {
      DialogWindowCapture.resolveCaptureRoot(rule)
        .interaction
        .captureRoboImage(file = it, roborazziOptions = RoborazziOptions())
    }
  }

  private fun File.isEntirely(argb: Color): Boolean {
    val image = ImageIO.read(this) ?: return false
    val want = argb.toArgbInt()
    for (y in 0 until image.height) {
      for (x in 0 until image.width) {
        if (image.getRGB(x, y) != want) return false
      }
    }
    return true
  }

  private fun Color.toArgbInt(): Int =
    android.graphics.Color.argb(
      (alpha * 255).toInt(),
      (red * 255).toInt(),
      (green * 255).toInt(),
      (blue * 255).toInt(),
    )

  private companion object {
    val FILL = Color(0xFF42A5F5)
    const val SETTLE_MS = 32L
  }
}
