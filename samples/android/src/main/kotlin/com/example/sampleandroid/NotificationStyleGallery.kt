package com.example.sampleandroid

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import ee.schimke.composeai.preview.notification.NotificationContent

/**
 * Gallery of additional `NotificationCompat` styles routed through the `NotificationContent`
 * helper. Each function is one `@Preview` (no full `@NotificationVariants` fan-out) — the variants
 * matrix is already demonstrated by `BigTextVariantsPreview`; this file is about *which kinds of
 * notification surface render correctly*, not how many variants of one notification we produce.
 *
 * Covers the surfaces real apps mostly ship: Messaging (Signal / WhatsApp / Discord),
 * Inbox-summary (Gmail), `BigPictureStyle` (camera / share notifications), actions
 * (reply / dismiss button row), `MediaStyle` (now-playing card), and
 * `DecoratedCustomViewStyle` (custom progress body under default chrome).
 */
private const val GALLERY_CHANNEL_ID = "gallery"

private fun ensureGalleryChannel(context: Context) {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (nm.getNotificationChannel(GALLERY_CHANNEL_ID) == null) {
      nm.createNotificationChannel(
        NotificationChannel(GALLERY_CHANNEL_ID, "Gallery", NotificationManager.IMPORTANCE_DEFAULT)
      )
    }
  }
}

/**
 * Three-message conversation rendered with `MessagingStyle`. The expanded layout shows each
 * `Message` with its `Person`'s display name; `setConversationTitle` becomes the header. This is
 * the surface Signal / WhatsApp / Discord use — most notification UX work in real apps lives in
 * this style.
 */
@Preview(name = "Messaging style")
@Composable
fun MessagingStylePreview() {
  NotificationContent { ctx ->
    ensureGalleryChannel(ctx)
    val you = Person.Builder().setName("You").build()
    val alice = Person.Builder().setName("Alice").build()
    val now = System.currentTimeMillis()
    NotificationCompat.Builder(ctx, GALLERY_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.sym_action_chat)
      .setStyle(
        NotificationCompat.MessagingStyle(you)
          .setConversationTitle("Alice")
          .addMessage("Did the previews land?", now - 180_000, alice)
          .addMessage("Yep, six variants per source function.", now - 120_000, you)
          .addMessage("Even RTL? 👀", now - 30_000, alice)
      )
      .build()
  }
}

/**
 * Inbox-summary style — five short rows under a single header. The surface Gmail / Outlook use
 * for "you have N unread" digests. Each `addLine` is a separate `TextView` in the inflated
 * `RemoteViews`; the rendered PNG shows up to ~7 lines depending on shade width.
 */
@Preview(name = "Inbox style")
@Composable
fun InboxStylePreview() {
  NotificationContent { ctx ->
    ensureGalleryChannel(ctx)
    NotificationCompat.Builder(ctx, GALLERY_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_dialog_email)
      .setContentTitle("4 new messages")
      .setContentText("Alice, Bob, Carol, Dave")
      .setStyle(
        NotificationCompat.InboxStyle()
          .setBigContentTitle("4 new messages")
          .setSummaryText("project-updates@")
          .addLine("Alice  Did the previews land?")
          .addLine("Bob  Reviewed the PR, ship it")
          .addLine("Carol  Question about MessagingStyle")
          .addLine("Dave  Heads-up: androidchka still red")
      )
      .build()
  }
}

/**
 * Notification with two action buttons (Reply / Archive). Actions render as a button row beneath
 * the body in the expanded layout, regardless of `setStyle`. `PendingIntent`s are required for
 * the action to exist; we use a benign no-op `Intent` since we never actually post.
 */
@Preview(name = "Actions")
@Composable
fun ActionsPreview() {
  NotificationContent { ctx ->
    ensureGalleryChannel(ctx)
    val noopIntent =
      PendingIntent.getActivity(
        ctx,
        0,
        Intent("com.example.sampleandroid.NOOP"),
        PendingIntent.FLAG_IMMUTABLE,
      )
    NotificationCompat.Builder(ctx, GALLERY_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.sym_action_email)
      .setContentTitle("New message from Alice")
      .setContentText("Did the previews land?")
      .addAction(android.R.drawable.ic_menu_send, "Reply", noopIntent)
      .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Archive", noopIntent)
      .build()
  }
}

/**
 * `BigPictureStyle` — the surface camera / photo-share / weather apps use when the body of the
 * notification is itself an image. The expanded shade layout reserves a wide row for the bitmap
 * and renders the title + text above it.
 *
 * The bitmap is generated programmatically (a gradient sky with a sun) so the sample doesn't have
 * to carry a photo asset in the repo. Real apps would use a `BitmapFactory.decodeResource` /
 * `decodeFile` of an actual image; the rendering path is the same.
 */
@Preview(name = "Big picture")
@Composable
fun BigPictureStylePreview() {
  NotificationContent { ctx ->
    ensureGalleryChannel(ctx)
    NotificationCompat.Builder(ctx, GALLERY_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_menu_camera)
      .setContentTitle("Photo shared")
      .setContentText("Tap to view")
      .setStyle(NotificationCompat.BigPictureStyle().bigPicture(sampleBigPicture()))
      .build()
  }
}

/**
 * Now-playing media card rendered with `androidx.media.app.NotificationCompat.MediaStyle`. Three
 * actions (previous / play / next) collapse into the inline transport row that the media-style
 * layout reserves; `setLargeIcon` becomes the album-art slot on the right edge. This is the
 * surface music apps (Spotify / YouTube Music / Apple Music) use for the now-playing card in the
 * shade.
 *
 * `MediaStyle` ordinarily ties the notification to a `MediaSessionCompat.Token`
 * (`setMediaSession(...)`) so SystemUI can route hardware media keys; for a static render we
 * skip the session — the layout draws identically with or without it because the inflater pulls
 * title / text / icon from the notification's own fields.
 */
@Preview(name = "Media style")
@Composable
fun MediaStylePreview() {
  NotificationContent { ctx ->
    ensureGalleryChannel(ctx)
    val noopIntent =
      PendingIntent.getActivity(
        ctx,
        0,
        Intent("com.example.sampleandroid.NOOP"),
        PendingIntent.FLAG_IMMUTABLE,
      )
    NotificationCompat.Builder(ctx, GALLERY_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle("Saturday Mix")
      .setContentText("Lo-Fi Radio")
      .setLargeIcon(sampleAlbumArt())
      .addAction(android.R.drawable.ic_media_previous, "Previous", noopIntent)
      .addAction(android.R.drawable.ic_media_pause, "Pause", noopIntent)
      .addAction(android.R.drawable.ic_media_next, "Next", noopIntent)
      .setStyle(
        androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1, 2)
      )
      .build()
  }
}

/**
 * Custom progress body wrapped in `DecoratedCustomViewStyle` — the system keeps its standard
 * header (small icon, app name, timestamp) and replaces only the body region with the inflated
 * `RemoteViews` from [R.layout.notification_custom_view]. The surface long-running download /
 * upload / build-progress notifications use when the default progress row isn't expressive enough.
 *
 * `setCustomContentView` *and* `setCustomBigContentView` are both set to the same RemoteViews so
 * the renderer's `createBigContentView()` path resolves to the custom layout rather than falling
 * back to the standard expanded chrome.
 */
@Preview(name = "Decorated custom view")
@Composable
fun DecoratedCustomViewPreview() {
  NotificationContent { ctx ->
    ensureGalleryChannel(ctx)
    val body =
      RemoteViews(ctx.packageName, R.layout.notification_custom_view).apply {
        setTextViewText(R.id.notification_custom_title, "Building project")
        setTextViewText(R.id.notification_custom_progress_label, "37 of 100 modules compiled")
        setProgressBar(R.id.notification_custom_progress, 100, 37, false)
      }
    NotificationCompat.Builder(ctx, GALLERY_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle("Building project")
      .setContentText("37 of 100 modules compiled")
      .setOngoing(true)
      .setCustomContentView(body)
      .setCustomBigContentView(body)
      .setStyle(NotificationCompat.DecoratedCustomViewStyle())
      .build()
  }
}

/**
 * Synthetic 256×256 "album art" for [MediaStylePreview] — a diagonal teal-to-pink gradient with a
 * radial highlight in the upper-left. Generated in-process so the sample doesn't ship a raster
 * asset; real apps would call `BitmapFactory.decodeResource` on a packaged album cover or pull the
 * artwork off `MediaMetadata`.
 */
private fun sampleAlbumArt(): Bitmap {
  val size = 256
  val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bmp)
  val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  paint.shader =
    LinearGradient(
      0f,
      0f,
      size.toFloat(),
      size.toFloat(),
      0xFF26A69A.toInt(),
      0xFFE91E63.toInt(),
      Shader.TileMode.CLAMP,
    )
  canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
  paint.shader =
    RadialGradient(
      size * 0.3f,
      size * 0.3f,
      size * 0.5f,
      0x66FFFFFF,
      0x00FFFFFF,
      Shader.TileMode.CLAMP,
    )
  canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
  return bmp
}

/**
 * Synthetic 720×384 "photo" used by [BigPictureStylePreview]. A linear sky gradient with a sun
 * disc in the upper-right — enough visual structure to read as an actual image at notification
 * size without shipping a raster asset.
 */
private fun sampleBigPicture(): Bitmap {
  val w = 720
  val h = 384
  val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bmp)
  val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  paint.shader =
    LinearGradient(
      0f,
      0f,
      0f,
      h.toFloat(),
      0xFF1976D2.toInt(),
      0xFFFFB74D.toInt(),
      Shader.TileMode.CLAMP,
    )
  canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
  paint.shader = null
  paint.color = 0xFFFFEB3B.toInt()
  canvas.drawCircle(w * 0.75f, h * 0.38f, h * 0.18f, paint)
  return bmp
}
