package ee.schimke.composeai.data.displayfilter

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Applies a [ColorMatrix4x5] to every pixel of a source PNG and writes the result as a new PNG.
 * Pure JVM (BufferedImage / ImageIO) so it works under the Robolectric host JVM and the Desktop
 * renderer without backend-specific glue.
 */
object PngColorMatrix {

  fun apply(source: File, destination: File, matrix: ColorMatrix4x5): File {
    require(source.isFile) { "Source PNG '${source.absolutePath}' does not exist." }
    val image = ImageIO.read(source) ?: error("Could not decode PNG '${source.absolutePath}'.")
    val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
    val width = image.width
    val height = image.height
    val row = IntArray(width)
    for (y in 0 until height) {
      image.getRGB(0, y, width, 1, row, 0, width)
      for (x in 0 until width) {
        row[x] = matrix.applyToArgb(row[x])
      }
      output.setRGB(0, y, width, 1, row, 0, width)
    }
    destination.parentFile?.mkdirs()
    ImageIO.write(output, "png", destination)
    return destination
  }
}
