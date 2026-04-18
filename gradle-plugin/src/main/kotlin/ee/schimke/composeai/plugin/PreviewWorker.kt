package ee.schimke.composeai.plugin

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

interface PreviewRenderParams : WorkParameters {
    val className: Property<String>
    val functionName: Property<String>
    val widthDp: Property<Int>
    val heightDp: Property<Int>
    /**
     * Compose density factor (= densityDpi / 160). Sized via DeviceDimensions
     * from the `@Preview` device, so stub PNGs scale the same way the real
     * Android renderer would (and Android Studio does).
     */
    val density: Property<Float>
    val fontScale: Property<Float>
    val showBackground: Property<Boolean>
    val backgroundColor: Property<Long>
    val outputFile: RegularFileProperty
    val backend: Property<String>
}

abstract class PreviewRenderWorkAction : WorkAction<PreviewRenderParams> {
    override fun execute() {
        val p = parameters
        val density = p.density.getOrElse(DeviceDimensions.DEFAULT_DENSITY)
        val width = (p.widthDp.get() * density).toInt().coerceAtLeast(1)
        val height = (p.heightDp.get() * density).toInt().coerceAtLeast(1)
        val outFile = p.outputFile.get().asFile
        outFile.parentFile?.mkdirs()

        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            val bgLong = p.backgroundColor.get()
            if (p.showBackground.get() || bgLong != 0L) {
                val argb = if (bgLong != 0L) bgLong.toInt() else 0xFFFFFFFF.toInt()
                g.color = Color(argb, true)
                g.fillRect(0, 0, width, height)
            }
        } finally {
            g.dispose()
        }
        ImageIO.write(img, "PNG", outFile)
    }
}
