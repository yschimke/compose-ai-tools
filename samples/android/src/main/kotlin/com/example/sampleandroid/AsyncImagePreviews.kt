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
 * Regression fixture for coil-backed images captured blank: an `AsyncImage` fed a `ByteArray`,
 * sized on one axis with `ContentScale.FillWidth`, in a centred `Column` with a caption. If the
 * load doesn't resolve, the painter has no intrinsic size, so the image expands to full height and
 * pushes the caption out of frame too — easy to spot. `AsyncImagePixelTest` asserts both halves.
 *
 * Bytes rather than a drawable because a `ByteArray` model is the case that broke, and coil's
 * inspection-mode branch only paints the placeholder, never the model.
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
 * The unresolvable case: preview renders don't hit the network, and `.invalid` resolves nowhere, so
 * this behaves the same everywhere. The renderer should log the failed request in
 * `<png>.warnings.json` and still paint the (bright, flat) placeholder, which `AsyncImagePixelTest`
 * asserts.
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
 * A small procedurally-drawn PNG, as compressed bytes the way app artwork arrives. Rings over a
 * gradient, so tests can assert "more than one colour" and humans can see it resolved.
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
