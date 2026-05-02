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
 *
 * **D2.2 — promoted to public** when this file moved from `:renderer-android` into
 * `:data-a11y-core`. `:renderer-android` re-exports it via `api(project(":data-a11y-core"))`
 * so existing imports of `ee.schimke.composeai.renderer.isRoundDevice` resolve unchanged;
 * the public visibility is what makes that re-export load-bearing across the module boundary.
 */
fun isRoundDevice(device: String?): Boolean {
    if (device.isNullOrBlank()) return false
    val lower = device.lowercase()
    // Match `*_round` device IDs (e.g. `id:wearos_small_round`) and
    // spec parameters `isRound=true` / `shape=Round`. The regex avoids
    // false positives on unrelated words like "ground" or "background".
    return lower.contains("_round") ||
            lower.contains("isround=true") ||
            lower.contains("shape=round")
}

/**
 * Returns a new ARGB_8888 bitmap where pixels outside the inscribed circle
 * are fully transparent. Uses the native Canvas/BitmapShader path (requires
 * Robolectric's NATIVE graphics mode) with anti-aliasing on the edge.
 *
 * The input bitmap is not modified.
 *
 * D2.2 — promoted to public alongside [isRoundDevice]; AccessibilityOverlay (also in
 * `:data-a11y-core`) calls it for the round-device circular fill on Wear renders.
 */
fun applyCircularClip(source: Bitmap): Bitmap {
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
