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
import android.graphics.Shader
import android.os.Build
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
 * Inbox-summary (Gmail), `BigPictureStyle` (camera / share notifications), and actions
 * (reply / dismiss button row).
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
