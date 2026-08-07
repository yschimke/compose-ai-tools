package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asSkiaPath

internal actual fun Path.conicToSkia(x1: Float, y1: Float, x2: Float, y2: Float, weight: Float) {
  // Skiko m144 (CMP 1.11, skiko 0.144.6) deprecated the mutating `Path` API at
  // DeprecationLevel.ERROR in favour of `PathBuilder`. Source-level only — the native still exports
  // `Java_org_jetbrains_skia_PathKt__1nConicTo`, so the geometry is unchanged. This seam exists
  // precisely because `androidx.compose.ui.graphics.Path` exposes no conic; migrating it to
  // `PathBuilder` would mean returning a *new* path rather than mutating the receiver, which is the
  // opposite of what the `expect` contract promises its caller. Mirrors the same suppression in the
  // vendored `FloatsToPath.kt`.
  @Suppress("DEPRECATION_ERROR") asSkiaPath().conicTo(x1, y1, x2, y2, weight)
}
