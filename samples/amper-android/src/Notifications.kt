package ee.schimke.composeai.sample.amperandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

// Re-declared (not imported from :preview-annotations) so the Amper module has no Gradle
// dependency. The compose-preview discovery path matches by FQN, so a local class with the
// same simple name + signature would not be picked up by the Gradle plugin — that's
// intentional: this fixture is about proving the build, not wiring rendering into a non-Gradle
// build system. See preview-annotations/src/main/kotlin/ee/schimke/composeai/preview/NotificationPreview.kt
// for the canonical annotation.
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
private annotation class NotificationPreview

private const val CHANNEL_ID = "amper-android-sample"

private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Sample", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
}

@NotificationPreview
fun helloNotification(context: Context): Notification {
    ensureChannel(context)
    return NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Hello, Amper Android")
        .setContentText("Built without Gradle, from a single module.yaml.")
        .build()
}
