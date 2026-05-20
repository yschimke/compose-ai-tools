package ee.schimke.composeai.renderer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose-free Robolectric test for [NotificationBitmapRenderer]. Deliberately imports no
 * `androidx.compose.*` and no `roborazzi` symbols — the point of the primitive is that callers
 * (notably future Bazel-without-Compose modules) can render `@NotificationPreview` entries with
 * pure platform Android view + canvas + bitmap APIs.
 *
 * Each case builds a real `NotificationCompat` notification, runs it through the primitive at a
 * Pixel-ish width, and asserts the resulting bitmap is non-null, non-empty, and contains at least
 * one non-default pixel (a basic sanity check that *something* drew rather than the View
 * collapsing to an invisible tree). We pin SDK 33 to match the rest of this module's Robolectric
 * suite — high enough that `Notification.Builder.recoverBuilder` and the modern RemoteViews path
 * are stable, low enough to avoid the API-37 Robolectric jar gap tracked in CI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationBitmapRendererTest {

  private val context: Context
    get() = ApplicationProvider.getApplicationContext()

  @Test
  fun `plain notification renders into a non-empty bitmap`() {
    ensureChannel(context)
    val notification =
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Hi")
        .setContentText("Hello from the Compose-free path")
        .build()

    val bitmap = NotificationBitmapRenderer.render(context, notification, WIDTH_PX)

    assertNotNull("render returned null for a plain NotificationCompat", bitmap)
    assertTrue("bitmap width should be > 0, was ${bitmap!!.width}", bitmap.width > 0)
    assertTrue("bitmap height should be > 0, was ${bitmap.height}", bitmap.height > 0)
    assertTrue("bitmap should contain at least one drawn pixel", hasDrawnPixel(bitmap))
  }

  @Test
  fun `BigTextStyle notification renders into a non-empty bitmap`() {
    ensureChannel(context)
    val notification =
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_email)
        .setContentTitle("Long-form update")
        .setContentText("Tap to read")
        .setStyle(
          NotificationCompat.BigTextStyle()
            .bigText(
              "BigTextStyle expanded body — the Compose-free renderer should be able to draw " +
                "this RemoteViews tree without pulling Jetpack Compose onto the test classpath.",
            ),
        )
        .build()

    val bitmap = NotificationBitmapRenderer.render(context, notification, WIDTH_PX)

    assertNotNull("render returned null for a BigTextStyle notification", bitmap)
    assertTrue("bitmap width should be > 0, was ${bitmap!!.width}", bitmap.width > 0)
    assertTrue("bitmap height should be > 0, was ${bitmap.height}", bitmap.height > 0)
    assertTrue("bitmap should contain at least one drawn pixel", hasDrawnPixel(bitmap))
  }

  private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      if (nm.getNotificationChannel(CHANNEL_ID) == null) {
        nm.createNotificationChannel(
          NotificationChannel(CHANNEL_ID, "Test", NotificationManager.IMPORTANCE_DEFAULT),
        )
      }
    }
  }

  /**
   * A bitmap is "drawn" if it has at least one pixel that differs from the fully-transparent
   * default `Bitmap.createBitmap(...)` fill (`0x00000000`). RemoteViews inflation always paints
   * some chrome (title text, icon background, etc.) so any successful render trips this check;
   * a zero-size or all-transparent bitmap would mean inflation produced nothing visible.
   */
  private fun hasDrawnPixel(bitmap: android.graphics.Bitmap): Boolean {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    return pixels.any { it != 0 }
  }

  private companion object {
    const val CHANNEL_ID = "renderer-test"
    const val WIDTH_PX = 1080
  }
}
