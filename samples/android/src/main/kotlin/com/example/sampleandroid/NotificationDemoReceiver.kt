package com.example.sampleandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Posts the notifications a phone's shade is exercised with, at run time rather than as previews: a
 * conversation with an inline reply (on a high-importance channel, so it shows heads-up), a long
 * message with an action, a picture and an ongoing download. Its own actions come back here: a
 * reply is added to the conversation, as a messaging app answers it; "Mark as read" and "Archive"
 * cancel theirs.
 *
 * [ACTION_POST_MEDIA] posts a player's `MediaStyle` notification instead, whose controls come back
 * here too.
 *
 * Driven by the simulator's `broadcast` command with [ACTION_POST], as the docs' shade example
 * does; not exported, so only the app and the simulator reach it. API 31+ (mutable reply intents),
 * which is all the simulator runs.
 */
@RequiresApi(Build.VERSION_CODES.S)
class NotificationDemoReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val manager = context.getSystemService(NotificationManager::class.java)
    when (intent.action) {
      ACTION_POST -> post(context, manager)
      ACTION_REPLY -> {
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)
        if (reply != null) manager.notify(ID_CHAT, chat(context, reply))
      }
      ACTION_DONE -> manager.cancel(intent.getIntExtra(EXTRA_ID, 0))
      ACTION_POST_MEDIA -> {
        manager.createNotificationChannel(
          NotificationChannel(CHANNEL_MEDIA, "Playback", NotificationManager.IMPORTANCE_LOW)
        )
        manager.notify(ID_MEDIA, media(context, playing = true, track = 0))
      }
      ACTION_MEDIA ->
        manager.notify(
          ID_MEDIA,
          media(
            context,
            playing = intent.getBooleanExtra(EXTRA_PLAYING, true),
            track = intent.getIntExtra(EXTRA_TRACK, 0),
          ),
        )
    }
  }

  private fun post(context: Context, manager: NotificationManager) {
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH)
    )
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_UPDATES, "Updates", NotificationManager.IMPORTANCE_DEFAULT)
    )
    manager.notify(ID_DOWNLOAD, download(context))
    manager.notify(ID_PHOTO, photo(context))
    manager.notify(ID_DIGEST, digest(context))
    manager.notify(ID_CHAT, chat(context, null))
  }

  private fun chat(context: Context, reply: CharSequence?): Notification {
    val me = android.app.Person.Builder().setName("You").build()
    val ada = android.app.Person.Builder().setName("Ada").build()
    val style =
      Notification.MessagingStyle(me)
        .addMessage("Are we still on for lunch?", 1_000, ada)
        .addMessage("The usual place at 12:30?", 2_000, ada)
    if (reply != null) style.addMessage(reply, 3_000, me)
    val remoteInput =
      RemoteInput.Builder(KEY_REPLY)
        .setLabel("Reply to Ada")
        .setChoices(arrayOf("Sounds good", "Running late"))
        .build()
    val replyAction =
      Notification.Action.Builder(
          Icon.createWithResource(context, android.R.drawable.ic_menu_send),
          "Reply",
          broadcast(context, ACTION_REPLY, ID_CHAT, mutable = true),
        )
        .addRemoteInput(remoteInput)
        .build()
    val readAction =
      Notification.Action.Builder(
          Icon.createWithResource(context, android.R.drawable.ic_menu_view),
          "Mark as read",
          broadcast(context, ACTION_DONE, ID_CHAT),
        )
        .build()
    return Notification.Builder(context, CHANNEL_MESSAGES)
      .setSmallIcon(android.R.drawable.stat_notify_chat)
      .setStyle(style)
      .setContentIntent(open(context))
      .setAutoCancel(true)
      .setOnlyAlertOnce(reply != null)
      .addAction(replyAction)
      .addAction(readAction)
      .build()
  }

  private fun digest(context: Context): Notification =
    Notification.Builder(context, CHANNEL_UPDATES)
      .setSmallIcon(android.R.drawable.ic_dialog_email)
      .setContentTitle("Weekly digest")
      .setContentText("3 new releases and a design review")
      .setStyle(
        Notification.BigTextStyle()
          .bigText(
            "Compose 1.11 is out, the Wear tiles library added a new layout, and the design " +
              "review for the settings screen moved to Thursday. Expand to read it all here."
          )
      )
      .setContentIntent(open(context))
      .addAction(
        Notification.Action.Builder(
            Icon.createWithResource(context, android.R.drawable.ic_menu_delete),
            "Archive",
            broadcast(context, ACTION_DONE, ID_DIGEST),
          )
          .build()
      )
      .build()

  private fun photo(context: Context): Notification {
    val picture = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888)
    Canvas(picture)
      .drawRect(
        0f,
        0f,
        640f,
        320f,
        Paint().apply {
          shader =
            LinearGradient(
              0f,
              0f,
              640f,
              320f,
              0xFF1E88E5.toInt(),
              0xFFFFB300.toInt(),
              Shader.TileMode.CLAMP,
            )
        },
      )
    return Notification.Builder(context, CHANNEL_UPDATES)
      .setSmallIcon(android.R.drawable.ic_menu_camera)
      .setContentTitle("Sunset backed up")
      .setContentText("1 photo added to your library")
      .setStyle(Notification.BigPictureStyle().bigPicture(picture))
      .setContentIntent(open(context))
      .setAutoCancel(true)
      .build()
  }

  /**
   * A player's `MediaStyle` notification: previous, play or pause, next, each a broadcast back here
   * that posts it again with the new state, as a media app's service would.
   */
  private fun media(context: Context, playing: Boolean, track: Int): Notification {
    val (title, artist) = TRACKS[Math.floorMod(track, TRACKS.size)]
    val art = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
    Canvas(art)
      .drawRect(
        0f,
        0f,
        256f,
        256f,
        Paint().apply {
          shader =
            LinearGradient(
              0f,
              0f,
              256f,
              256f,
              0xFF5E35B1.toInt(),
              if (track % 2 == 0) 0xFFD81B60.toInt() else 0xFF00897B.toInt(),
              Shader.TileMode.CLAMP,
            )
        },
      )
    fun control(icon: Int, label: String, nextPlaying: Boolean, nextTrack: Int, code: Int) =
      Notification.Action.Builder(
          Icon.createWithResource(context, icon),
          label,
          PendingIntent.getBroadcast(
            context,
            100 + code,
            Intent(context, NotificationDemoReceiver::class.java)
              .setAction(ACTION_MEDIA)
              .putExtra(EXTRA_PLAYING, nextPlaying)
              .putExtra(EXTRA_TRACK, nextTrack),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
          ),
        )
        .build()
    return Notification.Builder(context, CHANNEL_MEDIA)
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle(title)
      .setContentText(artist)
      .setLargeIcon(art)
      .setContentIntent(open(context))
      .setOngoing(playing)
      .addAction(control(android.R.drawable.ic_media_previous, "Previous", playing, track - 1, 0))
      .addAction(
        if (playing) control(android.R.drawable.ic_media_pause, "Pause", false, track, 1)
        else control(android.R.drawable.ic_media_play, "Play", true, track, 1)
      )
      .addAction(control(android.R.drawable.ic_media_next, "Next", playing, track + 1, 2))
      .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
      .build()
  }

  private fun download(context: Context): Notification =
    Notification.Builder(context, CHANNEL_UPDATES)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle("Downloading maps")
      .setContentText("62 MB of 100 MB")
      .setProgress(100, 62, false)
      .setOngoing(true)
      .build()

  private fun open(context: Context): PendingIntent =
    PendingIntent.getActivity(
      context,
      0,
      Intent(context, NowPlayingActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE,
    )

  private fun broadcast(
    context: Context,
    action: String,
    id: Int,
    mutable: Boolean = false,
  ): PendingIntent =
    PendingIntent.getBroadcast(
      context,
      id * 10 + action.length,
      Intent(context, NotificationDemoReceiver::class.java)
        .setAction(action)
        .putExtra(EXTRA_ID, id),
      PendingIntent.FLAG_UPDATE_CURRENT or
        if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE,
    )

  companion object {
    const val ACTION_POST = "com.example.sampleandroid.POST_NOTIFICATIONS"
    const val ACTION_POST_MEDIA = "com.example.sampleandroid.POST_MEDIA"
    private const val ACTION_MEDIA = "com.example.sampleandroid.MEDIA"
    private const val EXTRA_PLAYING = "playing"
    private const val EXTRA_TRACK = "track"
    private const val CHANNEL_MEDIA = "playback"
    private const val ID_MEDIA = 5
    private val TRACKS = listOf("Midnight City" to "M83", "Wait" to "M83", "Outro" to "M83")
    private const val ACTION_REPLY = "com.example.sampleandroid.REPLY"
    private const val ACTION_DONE = "com.example.sampleandroid.DONE"
    private const val EXTRA_ID = "id"
    private const val KEY_REPLY = "reply"
    private const val CHANNEL_MESSAGES = "messages"
    private const val CHANNEL_UPDATES = "updates"
    private const val ID_CHAT = 1
    private const val ID_DIGEST = 2
    private const val ID_PHOTO = 3
    private const val ID_DOWNLOAD = 4
  }
}
