package com.example.sampleandroid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.ByteArrayOutputStream

/**
 * Regression fixture for issue #2952 — coil-backed images captured blank.
 *
 * This is a deliberately faithful copy of the shape that broke: an `AsyncImage` fed a `ByteArray`,
 * sized only on one axis (`Modifier.width(100.dp)`) with `ContentScale.FillWidth`, sitting in a
 * centred `Column` next to a caption. Two things go wrong at once when the load doesn't resolve,
 * and the second is the nastier one:
 *
 * 1. the artwork is missing, and
 * 2. an unresolved `AsyncImagePainter` reports **no intrinsic size**, so `FillWidth` has nothing to
 *    scale from and the image expands to the parent's full height — shoving the caption out of
 *    frame. The whole screen captures as a solid block with neither element visible.
 *
 * So this preview is a good detector: if the fix regresses, the PNG doesn't just lose the rings —
 * it loses the "Living room speaker" line too, which is impossible to miss in a visual diff.
 * `AsyncImagePixelTest` asserts both halves.
 *
 * The bytes are generated rather than checked in as a drawable on purpose: a `ByteArray` model is
 * the case the issue hit (artwork arriving from a network response or a database blob), and it is
 * the case that has no `LocalInspectionMode` escape hatch — coil's inspection-mode branch only
 * paints the *placeholder*, never the model.
 */
@Composable
fun DeviceStatusCard(image: Any?, description: String?) {
  Surface(color = MaterialTheme.colorScheme.surface) {
    Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      AsyncImage(
        model = image,
        contentDescription = null,
        modifier = Modifier.width(100.dp),
        contentScale = ContentScale.FillWidth,
      )
      Text(text = description ?: "None", style = MaterialTheme.typography.bodyMedium)
    }
  }
}

/** The fixed case: real PNG bytes, resolved inline by the renderer's preview `ImageLoader`. */
@Preview(name = "Async Image Artwork", widthDp = 200, heightDp = 220, showBackground = true)
@Composable
fun AsyncImageArtworkPreview() {
  DeviceStatusCard(image = deviceArtworkPngBytes(), description = "Living room speaker")
}

/**
 * The unresolvable case, kept as a fixture so the placeholder fallback has a regression test too.
 *
 * A preview render deliberately doesn't hit the network — coil runs inline on the render thread so
 * local models resolve, which puts any HTTP fetch under Android's main-thread network guard, and a
 * preview whose pixels depend on live egress wouldn't be reproducible anyway. `.invalid` is a
 * reserved TLD that resolves nowhere, so this fixture behaves identically in a sandbox, in CI and
 * on a laptop regardless. The renderer should keep the real inline load path, diagnose the failed
 * request in `<png>.warnings.json`, and still paint the request placeholder instead of dropping to
 * transparent empty pixels.
 *
 * The placeholder is bright and flat on purpose: `AsyncImagePixelTest` can assert those pixels
 * survived even though the URL did not.
 */
@Preview(name = "Async Image Unreachable", widthDp = 200, heightDp = 220, showBackground = true)
@Composable
fun AsyncImageUnreachablePreview() {
  val context = LocalContext.current
  val request =
    ImageRequest.Builder(context)
      .data(UNREACHABLE_ARTWORK_URL)
      .placeholder(BitmapDrawable(context.resources, unreachablePlaceholderBitmap()))
      .build()

  DeviceStatusCard(
    image = request,
    description = "Offline",
  )
}

/** Reserved-TLD URL: guaranteed not to resolve, on any network, in any environment. */
const val UNREACHABLE_ARTWORK_URL: String = "https://artwork.invalid/living-room-speaker.png"

fun unreachablePlaceholderBitmap(size: Int = 96): Bitmap =
  Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
    eraseColor(Color.rgb(233, 30, 99))
  }

/**
 * A small procedurally-drawn PNG, encoded exactly the way an app's artwork would arrive — as
 * compressed bytes rather than a `Painter` or an `R.drawable`.
 *
 * Concentric rings over a two-axis gradient: busy enough that a pixel test can assert "this area
 * has more than one colour in it" and a human can see at a glance whether the render resolved the
 * image, cheap enough to regenerate on every composition.
 */
fun deviceArtworkPngBytes(size: Int = 96): ByteArray {
  val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bitmap)
  val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  canvas.drawColor(Color.rgb(24, 32, 56))
  val centre = size / 2f
  // Draw outside-in so each ring paints over the previous one's interior.
  var radius = size * 0.52f
  var ring = 0
  while (radius > 2f) {
    val t = radius / (size * 0.52f)
    paint.color =
      Color.rgb(
        (40 + 200 * (1 - t)).toInt().coerceIn(0, 255),
        (30 + 170 * t).toInt().coerceIn(0, 255),
        (90 + 140 * (if (ring % 2 == 0) t else 1 - t)).toInt().coerceIn(0, 255),
      )
    canvas.drawCircle(centre, centre, radius, paint)
    radius -= size * 0.07f
    ring++
  }
  return ByteArrayOutputStream().use { out ->
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    out.toByteArray()
  }
}
