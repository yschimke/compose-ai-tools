package ee.schimke.composeai.preview.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression test for #1363 — `NotificationContent`'s [NotificationSurface] parameter used to be
 * read inside `AndroidView`'s `factory` block, which Compose only runs once per view instance. Any
 * caller binding `surface` to state (a runtime toggle between collapsed / expanded / heads-up)
 * would see the rendered notification freeze on whichever surface was active at first
 * composition.
 *
 * The fix wraps the `AndroidView` in `key(surface) { ... }`, which forces a fresh view instance
 * (and therefore a fresh RemoteViews inflation) whenever `surface` changes. We exercise that by
 * setting a Compose `mutableStateOf(NotificationSurface)`, asserting the initial render produced
 * the collapsed layout (no expanded big-text body visible), then flipping the state and asserting
 * the next composition produced the expanded layout. We compare against the structural signature
 * of the inflated RemoteViews tree — different surfaces inflate different layout XML, so the
 * resolved set of `TextView` strings differs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationContentSurfaceRecompositionTest {

  @Suppress("DEPRECATION")
  @get:Rule
  val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun `changing surface state re-inflates the notification tree`() {
    val initial = NotificationSurface.COLLAPSED
    val next = NotificationSurface.EXPANDED
    var current by mutableStateOf(initial)

    composeRule.setContent {
      NotificationContent(surface = current) { ctx ->
        buildBigTextNotification(ctx)
      }
    }
    composeRule.waitForIdle()

    val collapsedTexts = collectVisibleText(composeRule.activity)
    assertTrue(
      "expected the collapsed layout to surface the short content text; got $collapsedTexts",
      collapsedTexts.any { it.contains(SHORT_TEXT) },
    )
    assertTrue(
      "collapsed layout should NOT contain the BigTextStyle body; got $collapsedTexts",
      collapsedTexts.none { it.contains(LONG_BIG_TEXT) },
    )

    // Flip the state — pre-fix this would not re-run `factory`, leaving the collapsed tree
    // intact. With `key(surface) { AndroidView(...) }` Compose throws the old view instance away
    // and re-inflates from scratch.
    composeRule.runOnUiThread { current = next }
    composeRule.waitForIdle()

    val expandedTexts = collectVisibleText(composeRule.activity)
    assertTrue(
      "expected the expanded layout to surface the BigTextStyle body after toggling surface; " +
        "got $expandedTexts",
      expandedTexts.any { it.contains(LONG_BIG_TEXT) },
    )
    assertNotEquals(
      "expanded view tree should differ from collapsed tree after the toggle",
      collapsedTexts,
      expandedTexts,
    )
  }

  @Test
  fun `cycling surface state through every enum value re-inflates each time`() {
    // Guards all three branches of `inflateNotificationView`'s `when (surface)`: a regression
    // that breaks COLLAPSED, EXPANDED, or HEADS_UP recomposition fails here. We pick a notification
    // shape that produces distinct visible text across surfaces (BigTextStyle expands; collapsed
    // and heads-up share the short-text layout on AOSP).
    var current by mutableStateOf(NotificationSurface.COLLAPSED)
    composeRule.setContent {
      NotificationContent(surface = current) { ctx ->
        buildBigTextNotification(ctx)
      }
    }
    composeRule.waitForIdle()

    for (surface in NotificationSurface.entries) {
      composeRule.runOnUiThread { current = surface }
      composeRule.waitForIdle()
      val texts = collectVisibleText(composeRule.activity)
      assertTrue(
        "surface=$surface produced an empty TextView set; expected the inflated tree to surface " +
          "at least the title or content text",
        texts.any { it.contains(TITLE) || it.contains(SHORT_TEXT) || it.contains(LONG_BIG_TEXT) },
      )
    }
  }

  private fun collectVisibleText(activity: ComponentActivity): List<String> {
    val root = activity.window.decorView.rootView as ViewGroup
    val out = mutableListOf<String>()
    walk(root) { v ->
      if (v is TextView) {
        val text = v.text?.toString().orEmpty()
        if (text.isNotEmpty()) out += text
      }
    }
    return out
  }

  private fun walk(view: View, visit: (View) -> Unit) {
    visit(view)
    if (view is ViewGroup) {
      for (i in 0 until view.childCount) walk(view.getChildAt(i), visit)
    }
  }

  /**
   * Builds a `BigTextStyle`-styled notification on the platform `Notification.Builder` API so the
   * test source set doesn't have to declare an `androidx.core` dependency for the
   * `NotificationCompat` wrapper. Pinned to API 26+ semantics: SDK is set to 33 above, so
   * `setChannelId` is always available and the legacy pre-O setters never run.
   */
  private fun buildBigTextNotification(context: Context): Notification {
    ensureChannel(context)
    val builder =
      Notification.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(TITLE)
        .setContentText(SHORT_TEXT)
        .setStyle(Notification.BigTextStyle().bigText(LONG_BIG_TEXT))
    return builder.build()
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

  private companion object {
    const val CHANNEL_ID = "notification-content-surface-test"
    const val TITLE = "Title"
    const val SHORT_TEXT = "Tap to read"
    const val LONG_BIG_TEXT =
      "BigTextStyle expanded body — the EXPANDED surface inflates createBigContentView() which " +
        "lays this full string out in a multi-line TextView. The COLLAPSED surface inflates " +
        "createContentView() which clips to the short content text and does not contain this " +
        "string."
  }
}
