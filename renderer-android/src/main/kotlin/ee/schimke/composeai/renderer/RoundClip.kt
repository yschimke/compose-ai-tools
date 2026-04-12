package ee.schimke.composeai.renderer

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader

/**
 * Detects whether a Compose `@Preview(device = ...)` string refers to a round
 * (circular) display — used to match Wear OS round devices.
 *
 * Matches:
 *   - Standard Wear device IDs:  `id:wearos_small_round`, `id:wearos_large_round`
 *   - Custom specs:              `spec:width=200dp,...,isRound=true`, `spec:shape=Round,...`
 *
 * Non-round values (null, blank, `id:wearos_square`, `id:pixel_5`, plain rectangular
 * specs) return false. The check is case-insensitive.
 */
internal fun isRoundDevice(device: String?): Boolean {
    if (device.isNullOrBlank()) return false
    val lower = device.lowercase()
    // `contains("round")` covers both `*_round` device IDs and
    // `isRound=true` / `shape=round` spec parameters after lowercasing.
    return lower.contains("round")
}

/**
 * Returns a new ARGB_8888 bitmap where pixels outside the inscribed circle
 * are fully transparent. Uses the native Canvas/BitmapShader path (requires
 * Robolectric's NATIVE graphics mode) with anti-aliasing on the edge.
 *
 * The input bitmap is not modified.
 */
internal fun applyCircularClip(source: Bitmap): Bitmap {
    val w = source.width
    val h = source.height
    val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    val cx = w / 2f
    val cy = h / 2f
    val radius = minOf(w, h) / 2f
    canvas.drawCircle(cx, cy, radius, paint)
    return output
}
