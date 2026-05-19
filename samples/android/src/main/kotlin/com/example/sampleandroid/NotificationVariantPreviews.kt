package com.example.sampleandroid

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.core.app.NotificationCompat

private const val VARIANTS_CHANNEL_ID = "variants"

private fun ensureVariantsChannel(context: Context) {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (nm.getNotificationChannel(VARIANTS_CHANNEL_ID) == null) {
      nm.createNotificationChannel(
        NotificationChannel(
          VARIANTS_CHANNEL_ID,
          "Variants",
          NotificationManager.IMPORTANCE_DEFAULT,
        )
      )
    }
  }
}

/**
 * `BigTextStyle` notification rendered across the matrix declared by [NotificationVariants].
 *
 * One source function fans out into six PNGs — Light / Dark / Arabic / German / Japanese / Large
 * font — via the existing COMPOSE discovery path. No `@NotificationPreview` annotation involved;
 * the helper composable + stacked `@Preview` is what carries the variant matrix. Notification
 * strings come from `notification_strings.xml` (default + `values-ar/`, `values-de/`,
 * `values-ja/`) so the locale axis shows translated content, not just a layout-direction flip.
 */
@NotificationVariants
@Composable
fun BigTextVariantsPreview() {
  NotificationContent { ctx ->
    ensureVariantsChannel(ctx)
    NotificationCompat.Builder(ctx, VARIANTS_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_dialog_email)
      .setContentTitle(ctx.getString(R.string.notif_variant_title))
      .setContentText(ctx.getString(R.string.notif_variant_text))
      .setStyle(
        NotificationCompat.BigTextStyle()
          .bigText(ctx.getString(R.string.notif_variant_big_text))
      )
      .build()
  }
}
