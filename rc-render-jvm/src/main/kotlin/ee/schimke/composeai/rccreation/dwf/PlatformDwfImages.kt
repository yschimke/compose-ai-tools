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
