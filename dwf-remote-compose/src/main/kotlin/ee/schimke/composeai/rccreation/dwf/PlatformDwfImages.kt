package ee.schimke.composeai.rccreation.dwf

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

public fun decodeDwfImageBitmap(bytes: ByteArray): ImageBitmap? = runCatching {
  val image = Image.makeFromEncoded(bytes)
  val bitmap = Bitmap().apply { allocN32Pixels(image.width, image.height) }
  val canvas = Canvas(bitmap)
  try {
    canvas.drawImage(image, 0f, 0f)
  } finally {
    canvas.close()
  }
  bitmap.asComposeImageBitmap()
}
  .getOrNull()

public fun encodeDwfPng(image: ImageBitmap): ByteArray? = runCatching {
  Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
}
  .getOrNull()

/**
 * A 64x64 magenta-tinted tile with a cross, drawn in place of a drawable that could not be found so
 * the author sees where it belongs.
 */
public fun createDwfPlaceholderBitmap(): ImageBitmap? = runCatching {
  val size = 64
  val bitmap = Bitmap().apply { allocN32Pixels(size, size) }
  val canvas = Canvas(bitmap)
  try {
    val fill = org.jetbrains.skia.Paint().apply { color = 0x66FF00FF }
    val line =
      org.jetbrains.skia.Paint().apply {
        color = 0xFFFF00FF.toInt()
        strokeWidth = 3f
        mode = org.jetbrains.skia.PaintMode.STROKE
      }
    canvas.drawRect(org.jetbrains.skia.Rect.makeWH(size.toFloat(), size.toFloat()), fill)
    canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(1.5f, 1.5f, size - 3f, size - 3f), line)
    canvas.drawLine(0f, 0f, size.toFloat(), size.toFloat(), line)
    canvas.drawLine(size.toFloat(), 0f, 0f, size.toFloat(), line)
  } finally {
    canvas.close()
  }
  bitmap.asComposeImageBitmap()
}
  .getOrNull()
