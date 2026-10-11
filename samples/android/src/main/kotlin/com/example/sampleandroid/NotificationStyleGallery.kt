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
import ee.schimke.composeai.preview.notification.NotificationSurface

/**
 * Gallery of `NotificationCompat` styles routed through the `NotificationContent` helper, one
 * `@Preview` each (the variants matrix lives in `BigTextVariantsPreview`): Messaging, Inbox,
 * `BigPictureStyle`, actions, `MediaStyle` and `DecoratedCustomViewStyle`. Followed by a
 * surface-axis fan-out (collapsed / expanded / heads-up) and content edge cases.
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
 * Three-message `MessagingStyle` conversation: each `Message` shows its `Person`'s name and
 * `setConversationTitle` becomes the header.
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

/** Inbox-summary style: five short rows under one header, as used for "N unread" digests. */
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
 * Two action buttons (Reply / Archive), rendered as a row beneath the body. Actions require a
 * `PendingIntent`; a no-op `Intent` suffices since nothing is posted.
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
 * `BigPictureStyle`, where the body is an image. The bitmap is generated in code so the sample
 * carries no photo asset.
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
 * Now-playing card with `androidx.media.app.NotificationCompat.MediaStyle`: three transport actions
 * and `setLargeIcon` as album art. No `MediaSessionCompat.Token` is set; the layout draws the same
 * without one.
 */
// MediaStyle is deprecated in favour of media3; this sample deliberately shows the legacy surface.
@Suppress("DEPRECATION")
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
 * A custom progress body in `DecoratedCustomViewStyle`: system header, body from
 * [R.layout.notification_custom_view]. Both `setCustomContentView` and `setCustomBigContentView`
 * are set so `createBigContentView()` resolves to the custom layout.
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

// Surface axis: the same notification through each `NotificationSurface` value. The default on
// `NotificationContent` is `EXPANDED`.

private fun surfaceAxisNotification(ctx: Context) =
  NotificationCompat.Builder(ctx, GALLERY_CHANNEL_ID)
    .setSmallIcon(android.R.drawable.ic_dialog_email)
    .setContentTitle("Compose preview")
    .setContentText("Tap to read the update")
    .setStyle(
      NotificationCompat.BigTextStyle()
        .bigText(
          "Notification previews now expose the three SystemUI surfaces — collapsed shade row, " +
            "expanded body, and heads-up popup — through the `NotificationSurface` enum on the " +
            "`NotificationContent` helper."
        )
    )
    .build()

@Preview(name = "Surface — collapsed")
@Composable
fun CollapsedSurfacePreview() {
  NotificationContent(surface = NotificationSurface.COLLAPSED) { ctx ->
    ensureGalleryChannel(ctx)
    surfaceAxisNotification(ctx)
  }
}

@Preview(name = "Surface — expanded")
@Composable
fun ExpandedSurfacePreview() {
  NotificationContent(surface = NotificationSurface.EXPANDED) { ctx ->
    ensureGalleryChannel(ctx)
    surfaceAxisNotification(ctx)
  }
}

@Preview(name = "Surface — heads-up")
@Composable
fun HeadsUpSurfacePreview() {
  NotificationContent(surface = NotificationSurface.HEADS_UP) { ctx ->
    ensureGalleryChannel(ctx)
    surfaceAxisNotification(ctx)
  }
}

// Content edge cases: how the AOSP layout degrades.

/**
 * Very long title: the expanded layout wraps to two lines, then ellipsises.
 */
@Preview(name = "Edge — long title")
@Composable
fun LongTitlePreview() {
  NotificationContent { ctx ->
    ensureGalleryChannel(ctx)
    NotificationCompat.Builder(ctx, GALLERY_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(
        "A notification title that goes on far past the width of any reasonable phone shade " +
          "to exercise the AOSP layout's title-row ellipsisation"
      )
      .setContentText("Tap to open")
      .build()
  }
}

/** Title only, no text or style: the minimum layout, with the small-icon header still drawn. */
@Preview(name = "Edge — no text")
@Composable
fun NoTextPreview() {
  NotificationContent { ctx ->
    ensureGalleryChannel(ctx)
    NotificationCompat.Builder(ctx, GALLERY_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle("Sync complete")
      .build()
  }
}

/**
 * `MessagingStyle` without `Person` icons: the avatar column drops and names reflow left.
 */
@Preview(name = "Edge — no large icon")
@Composable
fun NoLargeIconPreview() {
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
          .addMessage("No avatars on this one", now - 120_000, alice)
          .addMessage("Just the small icon row", now - 60_000, you)
      )
      .build()
  }
}

/** Six actions: SystemUI caps the visible row at three on most layouts. */
@Preview(name = "Edge — many actions")
@Composable
fun ManyActionsPreview() {
  NotificationContent { ctx ->
    ensureGalleryChannel(ctx)
    val noopIntent =
      PendingIntent.getActivity(
        ctx,
        0,
        Intent("com.example.sampleandroid.NOOP"),
        PendingIntent.FLAG_IMMUTABLE,
      )
    val builder =
      NotificationCompat.Builder(ctx, GALLERY_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle("Action overflow")
        .setContentText("System truncates beyond three")
    listOf("Reply", "Archive", "Snooze", "Mute", "Delete", "Star").forEach { label ->
      builder.addAction(android.R.drawable.ic_menu_more, label, noopIntent)
    }
    builder.build()
  }
}

/**
 * Group summary (`setGroupSummary(true)` + the children's group key), using the plain title/text
 * fields the summary falls back to without an `InboxStyle`.
 */
@Preview(name = "Edge — grouped summary")
@Composable
fun GroupedSummaryPreview() {
  NotificationContent { ctx ->
    ensureGalleryChannel(ctx)
    NotificationCompat.Builder(ctx, GALLERY_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_dialog_email)
      .setContentTitle("3 new messages")
      .setContentText("From Alice, Bob, Carol")
      .setGroup("messages")
      .setGroupSummary(true)
      .setStyle(
        NotificationCompat.InboxStyle()
          .setSummaryText("project-updates@")
          .addLine("Alice  Did the previews land?")
          .addLine("Bob  Reviewed the PR, ship it")
          .addLine("Carol  Question about MessagingStyle")
      )
      .build()
  }
}

/** Synthetic 256×256 album art for [MediaStylePreview], so the sample ships no raster asset. */
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

/** Synthetic 720×384 "photo" (sky gradient and sun) for [BigPictureStylePreview]. */
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
